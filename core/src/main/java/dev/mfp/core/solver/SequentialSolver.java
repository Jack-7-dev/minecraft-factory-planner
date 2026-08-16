package dev.mfp.core.solver;

import dev.mfp.core.model.Confidence;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.Floor;
import dev.mfp.core.plan.Line;
import dev.mfp.core.plan.LineNode;
import dev.mfp.core.plan.MachineConfig;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.plan.ProductionType;
import dev.mfp.core.plan.SolverMode;
import dev.mfp.core.plan.Subfloor;
import dev.mfp.core.plan.TargetOutput;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The top-down engine, ported from Factory Planner's {@code sequential_engine}.
 *
 * <p>The idea that makes it work is an inversion: <b>outstanding demand is the work queue</b>. The
 * solver seeds a demand map with the plan's targets and walks the floor once, top to bottom. Each
 * line looks at what is still demanded, runs only fast enough to cover it, and converts that into
 * demand for its own inputs — which the lines below it then see. When the walk finishes, whatever
 * demand is left is what the factory must import.
 *
 * <p>One pass means one limitation, and it is a real one: a line can only be fed from below. Any
 * genuine cycle (A needs B, B needs A) or byproduct shared between two lines cannot be resolved,
 * because satisfying the second consumer would require revisiting a line already processed. Rather
 * than iterate and hope, the solver leaves such items as imports and emits a warning naming them.
 * Resolving them properly is a job for the matrix engine.
 *
 * <p>Machine counts stay fractional throughout, deliberately. Rounding per line would compound
 * across a chain and would break the linearity the matrix engine needs.
 */
public final class SequentialSolver {

    private final ThroughputResolver resolver;
    private final Map<MfpRecipe, NettedRecipe> nettingCache = new IdentityHashMap<>();

    public SequentialSolver() {
        this(ThroughputResolver.BASE);
    }

    public SequentialSolver(ThroughputResolver resolver) {
        this.resolver = resolver;
    }

    public SolveResult solve(Plan plan) {
        Aggregate aggregate = new Aggregate();
        Map<MfpKey, Double> requested = new LinkedHashMap<>();
        for (TargetOutput target : plan.targets()) {
            aggregate.demand.add(target.key(), target.perSecond());
            requested.merge(target.key(), target.perSecond(), Double::sum);
        }

        List<LineResult> results = new ArrayList<>();
        solveFloor(plan.root(), aggregate, results);

        return assemble(plan, aggregate, requested, results);
    }

    // ---------------------------------------------------------------- traversal

    private void solveFloor(Floor floor, Aggregate aggregate, List<LineResult> results) {
        for (LineNode node : floor.nodes()) {
            if (node instanceof Line line) {
                results.add(solveLine(line, aggregate));
            } else if (node instanceof Subfloor subfloor) {
                solveSubfloor(subfloor, aggregate, results);
            }
        }
    }

    /**
     * Solve a nested floor as if it were a single composite recipe.
     *
     * <p>The subfloor is handed only the slice of demand its defining recipe can satisfy, so it
     * cannot accidentally consume the parent's other requirements. Afterwards its unmet demand and
     * its surplus are folded back into the parent — imports the subfloor could not cover become the
     * parent's problem, and anything it over-produced becomes available to the parent.
     */
    private void solveSubfloor(Subfloor subfloor, Aggregate parent, List<LineResult> results) {
        Line defining = subfloor.definingLine();
        NettedRecipe netted = netted(defining.recipe());

        Aggregate inner = new Aggregate();
        Map<MfpKey, Double> seeded = new LinkedHashMap<>();
        for (MfpOutput output : netted.outputs()) {
            double demanded = parent.demand.get(output.key());
            if (demanded > ItemFlows.EPSILON && !seeded.containsKey(output.key())) {
                seeded.put(output.key(), demanded);
                inner.demand.add(output.key(), demanded);
            }
        }

        solveFloor(subfloor.floor(), inner, results);

        // Whatever the subfloor actually delivered reduces the parent's demand.
        seeded.forEach((key, amount) -> {
            double delivered = amount - inner.demand.get(key);
            if (delivered > ItemFlows.EPSILON) {
                parent.demand.take(key, delivered);
            }
        });

        ItemFlows.balance(inner.demand, parent.surplus, parent.demand);
        inner.surplus.asMap().forEach((key, amount) -> offer(key, amount, parent, null, null));
    }

    // ---------------------------------------------------------------- one line

    private LineResult solveLine(Line line, Aggregate aggregate) {
        MfpRecipe recipe = line.recipe();
        NettedRecipe netted = netted(recipe);
        MachineConfig machine = line.machine();
        Throughput throughput = resolver.resolve(recipe, machine);

        if (!line.active()) {
            return idle(line, throughput, "line is disabled");
        }

        double perMachineCps = throughput.craftsPerSecond();
        List<Flow> products = outputFlows(netted, throughput.overclocks());
        List<Flow> ingredients = inputFlows(netted);

        double ratio = line.productionType() == ProductionType.PRODUCE
                ? producingRatio(products, aggregate, line)
                : consumingRatio(ingredients, aggregate, line);

        ratio *= line.percentage() / 100.0;
        ratio = applyMachineLimit(ratio, perMachineCps, machine);

        if (ratio <= ItemFlows.EPSILON) {
            return idle(line, throughput, "nothing demanded this line's output");
        }

        double machineCount = perMachineCps > 0 ? ratio / perMachineCps : 0.0;

        ItemFlows lineOutputs = new ItemFlows();
        ItemFlows lineByproducts = new ItemFlows();
        for (Flow product : products) {
            offer(product.key(), product.perCraft() * ratio, aggregate, lineOutputs, lineByproducts);
        }

        ItemFlows wanted = new ItemFlows();
        for (Flow ingredient : ingredients) {
            wanted.add(ingredient.key(), ingredient.perCraft() * ratio);
        }
        Map<MfpKey, Double> lineInputs = wanted.asMap();
        ItemFlows.balance(wanted, aggregate.surplus, aggregate.demand);

        Confidence confidence = throughput.confidence();
        String note = throughput.note();
        for (Flow product : products) {
            if (!product.exact()) {
                confidence = confidence.and(Confidence.APPROXIMATE);
                note = note != null ? note
                        : "output " + product.key() + " competes with others for its chance roll";
            }
        }

        return new LineResult(line, ratio, machineCount,
                throughput.euInPerSecond() * machineCount + throughput.fixedEuPerSecond(),
                throughput.euOutPerSecond() * machineCount,
                throughput.steamPerSecond() * machineCount,
                lineInputs, lineOutputs.asMap(), lineByproducts.asMap(), confidence, note);
    }

    /**
     * How fast a producing line must run.
     *
     * <p>With one demanded product the answer is arithmetic. With several it is a genuine choice,
     * and GregTech recipes routinely have three or more outputs. Taking the maximum satisfies every
     * demand at the cost of overproducing the rest — the safe default, since the surplus is tracked
     * and offered to later lines rather than lost. A priority item overrides that when the user
     * would rather pace the line by one specific product and import the others.
     */
    private static double producingRatio(List<Flow> products, Aggregate aggregate, Line line) {
        MfpKey priority = line.priorityItem();
        if (priority != null) {
            for (Flow product : products) {
                if (product.key().equals(priority)) {
                    double demanded = aggregate.demand.get(priority);
                    return product.perCraft() > ItemFlows.EPSILON ? demanded / product.perCraft() : 0.0;
                }
            }
        }

        double ratio = 0.0;
        for (Flow product : products) {
            double demanded = aggregate.demand.get(product.key());
            if (demanded <= ItemFlows.EPSILON || product.perCraft() <= ItemFlows.EPSILON) {
                continue;
            }
            ratio = Math.max(ratio, demanded / product.perCraft());
        }
        return ratio;
    }

    /**
     * How fast a byproduct-consuming line can run: no faster than its scarcest material allows.
     *
     * <p>The minimum, not the maximum. Running faster would consume a byproduct that was never
     * produced, which is how a disposal chain quietly invents material.
     *
     * <p>Energy is excluded from the pacing, and that exclusion matters. Energy is a utility, not a
     * byproduct: you can always import more of it. Letting it into the minimum would mean a disposal
     * line refuses to run whenever the plan has no surplus power — which is almost always — so the
     * byproduct it exists to consume would pile up untouched. It is still demanded as an input; it
     * simply does not decide the pace.
     */
    private static double consumingRatio(List<Flow> ingredients, Aggregate aggregate, Line line) {
        MfpKey priority = line.priorityItem();
        if (priority != null) {
            for (Flow ingredient : ingredients) {
                if (ingredient.key().equals(priority)) {
                    double available = aggregate.surplus.get(priority);
                    return ingredient.perCraft() > ItemFlows.EPSILON
                            ? available / ingredient.perCraft() : 0.0;
                }
            }
        }

        double ratio = Double.MAX_VALUE;
        boolean sawAny = false;
        for (Flow ingredient : ingredients) {
            if (ingredient.perCraft() <= ItemFlows.EPSILON || ingredient.key().isPseudo()) {
                continue;
            }
            sawAny = true;
            double available = aggregate.surplus.get(ingredient.key());
            ratio = Math.min(ratio, available / ingredient.perCraft());
        }
        return sawAny && ratio != Double.MAX_VALUE ? ratio : 0.0;
    }

    private static double applyMachineLimit(double ratio, double perMachineCps, MachineConfig machine) {
        if (machine == null || !machine.hasLimit() || perMachineCps <= ItemFlows.EPSILON) {
            return ratio;
        }
        double capped = perMachineCps * machine.limit();
        // forceLimit means "run exactly this many", even when demand would not fill them.
        return machine.forceLimit() ? capped : Math.min(ratio, capped);
    }

    /** Hand production to whoever wants it; the remainder becomes surplus for later lines. */
    private static void offer(MfpKey key, double amount, Aggregate aggregate,
                              ItemFlows outputs, ItemFlows byproducts) {
        if (amount <= ItemFlows.EPSILON) {
            return;
        }
        double used = aggregate.demand.take(key, amount);
        if (used > ItemFlows.EPSILON && outputs != null) {
            outputs.add(key, used);
        }
        double leftover = amount - used;
        if (leftover > ItemFlows.EPSILON) {
            aggregate.surplus.add(key, leftover);
            if (byproducts != null) {
                byproducts.add(key, leftover);
            }
        }
    }

    // ---------------------------------------------------------------- flows

    /**
     * Everything a craft produces — materials only.
     *
     * <p><b>Energy is deliberately absent from both flow lists</b>, and it used to be in both. It
     * flowed through the solver as an item, so a plan that picked up a turbine was sized to power
     * itself and a plan that did not reported an energy deficit as though it were a missing
     * ingredient. Neither is what a player wants: they arrive with a power setup already built, and
     * what they need from a planner is <em>how much this factory draws</em> (plan §13.4). So the
     * draw is computed per line from the throughput and summed, and nothing in the balance depends
     * on it.
     *
     * <p>Plan P3 is unaffected — energy is still expressed as a key and still computed by the same
     * per-machine arithmetic. It simply no longer has to balance, so no line is ever paced by it and
     * no generator is ever sized from it.
     */
    private static List<Flow> outputFlows(NettedRecipe netted, int overclocks) {
        List<Flow> flows = new ArrayList<>(netted.outputs().size());
        // Group-aware: competing chance modes resolve together, so this is the true expectation
        // rather than the per-output over-estimate the model used before M4b.
        for (ChanceResolver.Resolved resolved : ChanceResolver.resolve(netted.outputs(), overclocks)) {
            flows.add(new Flow(resolved.key(), resolved.perCraft(), resolved.exact()));
        }
        return flows;
    }

    private static List<Flow> inputFlows(NettedRecipe netted) {
        List<Flow> flows = new ArrayList<>(netted.inputs().size());
        for (MfpIngredient input : netted.inputs()) {
            double amount = input.effectiveAmount();
            if (amount > ItemFlows.EPSILON) {
                flows.add(new Flow(input.primary(), amount, true));
            }
        }
        return flows;
    }

    // ---------------------------------------------------------------- assembly

    private SolveResult assemble(Plan plan, Aggregate aggregate,
                                 Map<MfpKey, Double> requested, List<LineResult> results) {
        Map<Line, LineResult> byLine = new IdentityHashMap<>();
        Map<MfpKey, Double> producedAnywhere = new LinkedHashMap<>();
        Confidence confidence = Confidence.EXACT;
        double euIn = 0;
        double euOut = 0;
        double steamIn = 0;

        for (LineResult result : results) {
            byLine.put(result.line(), result);
            confidence = confidence.and(result.confidence());
            euIn += result.euInPerSecond();
            euOut += result.euOutPerSecond();
            steamIn += result.steamPerSecond();
            result.outputs().forEach((key, amount) -> producedAnywhere.merge(key, amount, Double::sum));
            result.byproducts().forEach((key, amount) -> producedAnywhere.merge(key, amount, Double::sum));
        }

        Map<MfpKey, Double> products = new LinkedHashMap<>();
        Map<MfpKey, Double> unsatisfied = new LinkedHashMap<>();
        for (Map.Entry<MfpKey, Double> entry : requested.entrySet()) {
            double outstanding = aggregate.demand.get(entry.getKey());
            double delivered = entry.getValue() - outstanding;
            if (delivered > ItemFlows.EPSILON) {
                products.put(entry.getKey(), delivered);
            }
            if (outstanding > ItemFlows.EPSILON) {
                unsatisfied.put(entry.getKey(), outstanding);
            }
        }

        Map<MfpKey, Double> rawInputs = new LinkedHashMap<>();
        aggregate.demand.asMap().forEach((key, amount) -> {
            if (!requested.containsKey(key) && amount > ItemFlows.EPSILON) {
                rawInputs.put(key, amount);
            }
        });

        List<String> warnings = new ArrayList<>();
        collectLoopWarnings(rawInputs, producedAnywhere, warnings);
        if (!unsatisfied.isEmpty()) {
            warnings.add("targets not fully satisfied: " + unsatisfied.keySet());
        }

        return new SolveResult(List.copyOf(results), byLine, products, rawInputs,
                aggregate.surplus.asMap(), unsatisfied, euIn, euOut, steamIn, confidence, warnings,
                SolverMode.SEQUENTIAL);
    }

    /**
     * Flag items the plan both imports and produces.
     *
     * <p>That combination is the visible symptom of the single-pass limitation: the item was needed
     * by a line the producing line could no longer reach. Saying so plainly is better than
     * presenting an import list that looks authoritative but is an artefact of line ordering.
     */
    private static void collectLoopWarnings(Map<MfpKey, Double> rawInputs,
                                            Map<MfpKey, Double> producedAnywhere,
                                            List<String> warnings) {
        Set<MfpKey> both = new LinkedHashSet<>(rawInputs.keySet());
        both.retainAll(producedAnywhere.keySet());
        if (!both.isEmpty()) {
            warnings.add("imported despite being produced in this plan, which a single top-down pass "
                    + "cannot resolve - use the matrix solver for: " + both);
        }
    }

    private LineResult idle(Line line, Throughput throughput, String reason) {
        return new LineResult(line, 0, 0, 0, 0, 0, Map.of(), Map.of(), Map.of(),
                throughput.confidence(), reason);
    }

    private NettedRecipe netted(MfpRecipe recipe) {
        return nettingCache.computeIfAbsent(recipe, RecipeNetting::net);
    }

    /** One item moving at a per-craft rate, plus whether that rate is exactly known. */
    private record Flow(MfpKey key, double perCraft, boolean exact) {}

    /** Outstanding demand and available surplus as the walk proceeds. */
    private static final class Aggregate {
        private final ItemFlows demand = new ItemFlows();
        private final ItemFlows surplus = new ItemFlows();
    }
}
