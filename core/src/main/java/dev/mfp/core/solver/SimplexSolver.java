package dev.mfp.core.solver;

import dev.mfp.core.model.Confidence;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.plan.Line;
import dev.mfp.core.plan.MachineConfig;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.plan.SolverMode;
import dev.mfp.core.plan.TargetOutput;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The whole-plan engine that can hold an <b>inequality</b>.
 *
 * <p>Everything MFP could not express before this class is the same shape. A machine limit is
 * <em>at most</em> four of these. Production is <em>at least</em> what was asked for, not exactly.
 * A byproduct covering part of a demand is "take what there is and import the rest". None of those
 * is a system of equations, which is why {@link MatrixSolver} had to report limits and percentages
 * as ignored and had to invent the single-item relaxation to survive a plan that over-produces.
 *
 * <h2>The programme</h2>
 * <pre>
 *   variables   x_j      crafts per second of line j                          >= 0
 *               imp_i    item i brought in from outside                       >= 0
 *               sur_i    item i left over                                     >= 0
 *   subject to  sum_j A[i][j] x_j + imp_i - sur_i = demand_i     one row per item
 *               x_j + slack = limit_j * crafts-per-machine       one row per machine limit
 *               share_j = percentage_j * demand for its product  one row per line percentage
 *   minimising  the imports, then the leftovers, then the running
 * </pre>
 *
 * <p>Every item gets both an {@code imp} and a {@code sur} column, so the system is <b>always
 * feasible</b>: the trivial answer is "import everything, run nothing", and the simplex walks from
 * there to the cheapest real factory. That is the structural reason this engine does not have the
 * matrix engine's failure modes — there is no such thing here as a plan that cannot be described,
 * only a plan that has to import something, and importing something is a fact to report rather than
 * an error to raise.
 *
 * <h2>What the objective actually says</h2>
 * The costs are tiers, not tuning knobs, and they are in <em>row-scaled</em> units — each item row
 * is divided by its largest per-craft coefficient, so one unit of cost is one craft's worth of that
 * item rather than one millibucket. Without that normalisation "minimise imports" would quietly mean
 * "prefer chains that use fluids", since a fluid is counted in thousands.
 *
 * <ol>
 *   <li><b>Do not import something the plan claims to make.</b> Phase one of the simplex minimises
 *       exactly these, and they are the artificial variables in the textbook sense: an intermediate
 *       being imported means the plan does not hold together. If phase one cannot drive them to zero
 *       the plan genuinely cannot balance that item, and it is <em>relaxed</em> — named in a warning
 *       and re-solved as something the plan may import — which is the same treatment
 *       {@link MatrixSolver#smallestLeak} arrived at, reached here by construction rather than by
 *       trying items one at a time.</li>
 *   <li><b>Do not under-deliver a target</b> ({@link #SHORTFALL}). A limit that caps production is
 *       honoured, and the demand it cannot meet is stated rather than silently dropped.</li>
 *   <li><b>Use as little raw material as possible</b> (cost 1). This is the only tier that ever
 *       chooses between two ways of doing something, and it only gets the chance when the plan
 *       contains two ways — which is the chooser's decision, not this engine's (plan P6).</li>
 *   <li><b>Prefer not to leave things over</b> ({@link #SURPLUS}) and <b>prefer not to run</b>
 *       ({@link #ACTIVITY}). Tie-breaks, and small enough not to trade against anything above them.
 *       They are what stop a loop from spinning at an arbitrary rate: circulating costs nothing
 *       material, so without a cost on running there would be nothing to say how fast.</li>
 * </ol>
 *
 * <p>Determinism is not incidental. Pricing ties break on the lowest column index and ratio ties on
 * the lowest basic index ({@link Simplex}), so the same plan gives the same answer every time it is
 * opened — which for a planner matters more than shaving pivots.
 */
public final class SimplexSolver {

    /**
     * The cost of failing to deliver a target, per craft-scaled unit.
     *
     * <p>Large enough that no amount of raw material saved is worth a shortfall, which is the
     * ordering the tiers exist to encode; small enough to stay far away from the point where the
     * arithmetic stops being exact. A plan would need to import on the order of a million craft-loads
     * of raw material before the two tiers could be confused, and a plan that large has other
     * problems.
     */
    private static final double SHORTFALL = 1e6;

    /** Prefer a plan that leaves nothing over, where it has the choice. */
    private static final double SURPLUS = 1e-3;

    /** Prefer a plan that runs fewer crafts, where it has the choice. */
    private static final double ACTIVITY = 1e-6;

    /** How many items may be relaxed before giving up; one round frees at least one. */
    private static final int MAX_RELAXATIONS = 64;

    private final ColumnBuilder builder;

    public SimplexSolver() {
        this(ThroughputResolver.BASE);
    }

    public SimplexSolver(ThroughputResolver resolver) {
        this.builder = new ColumnBuilder(resolver);
    }

    public SolveResult solve(Plan plan) {
        List<String> warnings = new ArrayList<>();
        List<Column> columns = new ArrayList<>();
        List<IdleLine> idle = new ArrayList<>();
        for (Line line : plan.allLines()) {
            if (!line.active()) {
                idle.add(new IdleLine(line, "line is disabled"));
                continue;
            }
            columns.add(builder.column(line, warnings));
        }

        Map<MfpKey, Double> targets = new LinkedHashMap<>();
        for (TargetOutput target : plan.targets()) {
            targets.merge(target.key(), target.perSecond(), Double::sum);
        }

        Classification classification = classify(plan, columns, targets);
        Set<Line> droppedLimits = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Line> droppedPercentages = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

        for (int round = 0; round <= MAX_RELAXATIONS; round++) {
            Programme programme = new Programme(columns, targets, classification,
                    droppedLimits, droppedPercentages, warnings);
            Simplex simplex = programme.start();
            double unbalanced = simplex.minimise(programme.phaseOneCost());
            if (unbalanced > Simplex.TOLERANCE) {
                if (relax(programme, simplex, classification, droppedLimits, droppedPercentages,
                        warnings)) {
                    continue;
                }
            }
            simplex.retire(programme.artificials());
            simplex.minimise(programme.phaseTwoCost());
            return assemble(plan, programme, simplex.solution(), columns, idle, targets,
                    classification, warnings);
        }
        throw new IllegalStateException("the plan could not be relaxed into a solvable one");
    }

    // ------------------------------------------------------------ classification

    /**
     * Which items may be brought in from outside, and which the plan is claiming to make itself.
     *
     * <p>The same three rules the matrix engine uses (consumed but never produced, produced but
     * never wanted, demanded but never made), plus whatever the user declared raw or freed by hand,
     * plus energy — which is free unconditionally, because a factory buys power rather than balancing
     * it (plan §13.4).
     *
     * <p>{@code shortfall} is the fourth category and it is the one the matrix engine has no room
     * for: an item the plan is supposed to make and cannot make enough of. Importing it is not a
     * legitimate answer, so it costs {@link #SHORTFALL} rather than 1, and it is reported as demand
     * the plan failed to meet rather than as a shopping list.
     */
    private static Classification classify(Plan plan, List<Column> columns,
                                           Map<MfpKey, Double> targets) {
        Set<MfpKey> items = new LinkedHashSet<>();
        Set<MfpKey> consumed = new LinkedHashSet<>();
        Set<MfpKey> produced = new LinkedHashSet<>();
        for (Column column : columns) {
            column.perCraft().forEach((key, amount) -> {
                items.add(key);
                if (amount < 0) {
                    consumed.add(key);
                } else {
                    produced.add(key);
                }
            });
            if (column.throughput().fixedEuPerSecond() > 0) {
                items.add(MfpKey.EU);
                consumed.add(MfpKey.EU);
            }
        }
        items.addAll(targets.keySet());

        Set<MfpKey> free = new LinkedHashSet<>();
        Set<MfpKey> shortfall = new LinkedHashSet<>();
        for (MfpKey key : items) {
            boolean isConsumed = consumed.contains(key);
            boolean isProduced = produced.contains(key);
            boolean isTarget = targets.containsKey(key);
            if (isConsumed && !isProduced) {
                free.add(key);
            } else if (isProduced && !isConsumed && !isTarget) {
                free.add(key);
            } else if (isTarget && !isProduced) {
                // Nothing makes it, so no round of relaxation is going to discover otherwise.
                shortfall.add(key);
            }
        }
        for (MfpKey key : plan.freeItems()) {
            if (items.contains(key)) {
                free.add(key);
            }
        }
        for (MfpKey key : plan.rawMaterials()) {
            if (items.contains(key)) {
                free.add(key);
            }
        }
        if (items.contains(MfpKey.EU)) {
            free.add(MfpKey.EU);
        }
        free.removeAll(shortfall);
        return new Classification(new ArrayList<>(items), free, shortfall,
                new LinkedHashSet<>(targets.keySet()));
    }

    /**
     * Free whatever phase one could not balance, once, and say so.
     *
     * <p>An artificial left standing is an exact statement: <em>these recipes cannot make this item
     * balance</em>. A target that cannot be met becomes a shortfall; anything else becomes something
     * the plan may import or leave over, which is the same answer the matrix engine reaches through
     * {@code smallestLeak} — except that this one does not have to guess which item to try, because
     * the simplex has already named it.
     *
     * @return true if something was relaxed and the solve should be retried
     */
    private static boolean relax(Programme programme, Simplex simplex, Classification classification,
                                 Set<Line> droppedLimits, Set<Line> droppedPercentages,
                                 List<String> warnings) {
        double[] values = simplex.solution();
        boolean relaxed = false;
        for (int column = 0; column < values.length; column++) {
            if (!programme.artificials()[column] || values[column] <= Simplex.TOLERANCE) {
                continue;
            }
            MfpKey item = programme.itemOfImport(column);
            if (item != null) {
                if (classification.targetKeys().contains(item)) {
                    classification.shortfall().add(item);
                    classification.free().remove(item);
                } else {
                    classification.free().add(item);
                    warnings.add("these recipes cannot balance " + item + " exactly, so the plan is "
                            + "allowed to over-produce or import it - everything else still balances");
                }
                relaxed = true;
                continue;
            }
            // A forced machine count or a line percentage that contradicts the rest of the plan.
            // Dropping the setting and naming it beats refusing to answer, and it is the only one of
            // the two that tells the user which knob is the problem.
            Line forced = programme.lineOfForcedLimit(column);
            if (forced != null && droppedLimits.add(forced)) {
                warnings.add(forced.recipe().id() + " cannot run at exactly the machine count this "
                        + "plan forces on it, so that setting was dropped rather than the plan");
                relaxed = true;
                continue;
            }
            Line paced = programme.lineOfPercentage(column);
            if (paced != null && droppedPercentages.add(paced)) {
                warnings.add(paced.recipe().id() + " cannot run at the percentage this plan sets, so "
                        + "that setting was dropped rather than the plan");
                relaxed = true;
            }
        }
        return relaxed;
    }

    // ------------------------------------------------------------- the programme

    /**
     * The linear programme for one attempt: the matrix, the costs, and what each column means.
     *
     * <p>Column order is fixed and load-bearing, because it is what makes the answer reproducible:
     * lines first, then an import and a surplus column for each item in turn, then one slack or
     * artificial per honoured setting.
     */
    private static final class Programme {

        private final List<Column> columns;
        private final List<MfpKey> items;
        private final Map<MfpKey, Integer> rowOfItem = new LinkedHashMap<>();
        private final Classification classification;

        private final double[][] a;
        private final double[] b;
        private final int[] basis;
        private final boolean[] artificial;
        private final double[] rowScale;

        /** Craft-rate cap per line, or NaN where the line has no honoured limit. */
        private final double[] capOf;
        private final Map<Integer, Line> forcedLimitOf = new LinkedHashMap<>();
        private final Map<Integer, Line> percentageOf = new LinkedHashMap<>();

        private final int lineCount;
        private final int itemCount;
        private final int totalColumns;

        Programme(List<Column> columns, Map<MfpKey, Double> targets, Classification classification,
                  Set<Line> droppedLimits, Set<Line> droppedPercentages, List<String> warnings) {
            this.columns = columns;
            this.classification = classification;
            this.items = classification.items();
            this.lineCount = columns.size();
            this.itemCount = items.size();
            for (int i = 0; i < itemCount; i++) {
                rowOfItem.put(items.get(i), i);
            }

            List<Integer> limitLines = new ArrayList<>();
            List<Integer> percentageLines = new ArrayList<>();
            this.capOf = new double[lineCount];
            java.util.Arrays.fill(capOf, Double.NaN);
            for (int j = 0; j < lineCount; j++) {
                Column column = columns.get(j);
                Line line = column.line();
                MachineConfig machine = line.machine();
                if (machine != null && machine.hasLimit() && !droppedLimits.contains(line)) {
                    double perMachine = column.throughput().craftsPerSecond();
                    if (perMachine > ItemFlows.EPSILON) {
                        capOf[j] = perMachine * machine.limit();
                        limitLines.add(j);
                    } else {
                        String note = line.recipe().id() + " has a machine limit but no craft rate, "
                                + "so there is nothing for the limit to cap";
                        if (!warnings.contains(note)) {
                            warnings.add(note);
                        }
                    }
                }
                if (Math.abs(line.percentage() - 100.0) > 1e-9 && !droppedPercentages.contains(line)
                        && column.mainProduct() != null) {
                    percentageLines.add(j);
                }
            }

            int extras = limitLines.size() + percentageLines.size();
            int rows = itemCount + extras;
            this.totalColumns = lineCount + 2 * itemCount + extras;
            this.a = new double[rows][totalColumns];
            this.b = new double[rows];
            this.basis = new int[rows];
            this.artificial = new boolean[totalColumns];
            this.rowScale = new double[rows];
            java.util.Arrays.fill(rowScale, 1.0);

            double fixedEnergy = 0;
            for (Column column : columns) {
                fixedEnergy += column.throughput().fixedEuPerSecond();
            }

            for (int i = 0; i < itemCount; i++) {
                MfpKey key = items.get(i);
                double largest = 0;
                for (int j = 0; j < lineCount; j++) {
                    largest = Math.max(largest, Math.abs(columns.get(j).perCraft().getOrDefault(key, 0.0)));
                }
                double scale = largest > ItemFlows.EPSILON ? largest : 1.0;
                rowScale[i] = scale;
                for (int j = 0; j < lineCount; j++) {
                    a[i][j] = columns.get(j).perCraft().getOrDefault(key, 0.0) / scale;
                }
                // Idle drain is constant with respect to the unknowns, so it belongs on the
                // right-hand side and never in a column (plan §9.2). A constant folded into a
                // per-craft coefficient makes the system non-linear exactly where the solver assumes
                // it is not, and the machine counts come out wrong rather than imprecise.
                double demand = targets.getOrDefault(key, 0.0)
                        + (key.equals(MfpKey.EU) ? fixedEnergy : 0.0);
                b[i] = demand / scale;
                a[i][importColumn(i)] = 1.0;
                a[i][surplusColumn(i)] = -1.0;
                basis[i] = importColumn(i);
                artificial[importColumn(i)] = !classification.free().contains(key)
                        && !classification.shortfall().contains(key);
            }

            int extra = 0;
            int row = itemCount;
            for (int j : limitLines) {
                a[row][j] = 1.0;
                int slack = lineCount + 2 * itemCount + extra;
                a[row][slack] = 1.0;
                b[row] = capOf[j];
                basis[row] = slack;
                if (columns.get(j).line().machine().forceLimit()) {
                    // "Run exactly this many" is an equality, so its slack is a genuine artificial:
                    // phase one has to drive it to zero, and if it cannot, the plan and the forced
                    // count contradict each other.
                    artificial[slack] = true;
                    forcedLimitOf.put(slack, columns.get(j).line());
                }
                extra++;
                row++;
            }
            for (int j : percentageLines) {
                Column column = columns.get(j);
                MfpKey product = column.mainProduct();
                double share = column.line().percentage() / 100.0;
                double largest = 0;
                for (int k = 0; k < lineCount; k++) {
                    double net = columns.get(k).perCraft().getOrDefault(product, 0.0);
                    double coefficient = (k == j ? Math.max(0.0, net) : 0.0)
                            - share * Math.max(0.0, -net);
                    a[row][k] = coefficient;
                    largest = Math.max(largest, Math.abs(coefficient));
                }
                double scale = largest > ItemFlows.EPSILON ? largest : 1.0;
                for (int k = 0; k < lineCount; k++) {
                    a[row][k] /= scale;
                }
                rowScale[row] = scale;
                b[row] = share * targets.getOrDefault(product, 0.0) / scale;
                int slack = lineCount + 2 * itemCount + extra;
                a[row][slack] = 1.0;
                basis[row] = slack;
                artificial[slack] = true;
                percentageOf.put(slack, column.line());
                extra++;
                row++;
            }
        }

        Simplex start() {
            return new Simplex(a, b, basis, totalColumns);
        }

        int importColumn(int item) {
            return lineCount + 2 * item;
        }

        int surplusColumn(int item) {
            return lineCount + 2 * item + 1;
        }

        boolean[] artificials() {
            return artificial;
        }

        double rowScale(int item) {
            return rowScale[item];
        }

        /** The item an import column stands for, or null when the column is not an import. */
        MfpKey itemOfImport(int column) {
            if (column < lineCount || column >= lineCount + 2 * itemCount) {
                return null;
            }
            int offset = column - lineCount;
            return offset % 2 == 0 ? items.get(offset / 2) : null;
        }

        /** The line whose forced machine count a column enforces, or null. */
        Line lineOfForcedLimit(int column) {
            return forcedLimitOf.get(column);
        }

        /** The line whose percentage a column enforces, or null. */
        Line lineOfPercentage(int column) {
            return percentageOf.get(column);
        }

        double capOf(int line) {
            return capOf[line];
        }

        double[] phaseOneCost() {
            double[] cost = new double[totalColumns];
            for (int column = 0; column < cost.length; column++) {
                cost[column] = artificial[column] ? 1.0 : 0.0;
            }
            return cost;
        }

        double[] phaseTwoCost() {
            double[] cost = new double[totalColumns];
            for (int j = 0; j < lineCount; j++) {
                cost[j] = ACTIVITY;
            }
            for (int i = 0; i < itemCount; i++) {
                MfpKey key = items.get(i);
                if (key.equals(MfpKey.EU)) {
                    continue;   // energy is bought, and buying it is neither a cost nor a failure
                }
                if (classification.shortfall().contains(key)) {
                    cost[importColumn(i)] = SHORTFALL;
                } else if (classification.free().contains(key)) {
                    cost[importColumn(i)] = 1.0;
                }
                if (!classification.free().contains(key)) {
                    cost[surplusColumn(i)] = SURPLUS;
                }
            }
            return cost;
        }
    }

    // ------------------------------------------------------------------ assembly

    private SolveResult assemble(Plan plan, Programme programme, double[] values,
                                 List<Column> columns, List<IdleLine> idle,
                                 Map<MfpKey, Double> targets, Classification classification,
                                 List<String> warnings) {
        Map<Line, LineResult> byLine = new IdentityHashMap<>();
        Confidence confidence = Confidence.EXACT;
        double euIn = 0;
        double euOut = 0;
        double steamIn = 0;
        List<String> atLimit = new ArrayList<>();

        for (int j = 0; j < columns.size(); j++) {
            Column column = columns.get(j);
            LineResult result = lineResult(column, values[j]);
            byLine.put(result.line(), result);
            confidence = confidence.and(result.confidence());
            euIn += result.euInPerSecond();
            euOut += result.euOutPerSecond();
            steamIn += result.steamPerSecond();
            double cap = programme.capOf(j);
            if (!Double.isNaN(cap) && values[j] >= cap - Simplex.TOLERANCE && cap > ItemFlows.EPSILON) {
                atLimit.add(column.line().recipe().id());
            }
        }
        for (IdleLine line : idle) {
            byLine.put(line.line(), new LineResult(line.line(), 0, 0, 0, 0, 0,
                    Map.of(), Map.of(), Map.of(), Confidence.EXACT, line.reason()));
        }

        Map<MfpKey, Double> rawInputs = new LinkedHashMap<>();
        Map<MfpKey, Double> byproducts = new LinkedHashMap<>();
        Map<MfpKey, Double> products = new LinkedHashMap<>();
        Map<MfpKey, Double> unsatisfied = new LinkedHashMap<>();

        for (int i = 0; i < classification.items().size(); i++) {
            MfpKey key = classification.items().get(i);
            if (key.equals(MfpKey.EU)) {
                // The energy variables are a *net*, and netting is the one thing the draw must never
                // do (plan §13.4). It is reported gross, from the lines.
                continue;
            }
            double scale = programme.rowScale(i);
            double imported = values[programme.importColumn(i)] * scale;
            double leftOver = values[programme.surplusColumn(i)] * scale;
            if (imported > ItemFlows.EPSILON) {
                if (classification.shortfall().contains(key)) {
                    // Only the part that stands in for demand is a shortfall. An item that is both a
                    // target and an ingredient can be short by more than the target, and calling all
                    // of that "demand not met" would overstate it.
                    double missing = Math.min(imported, targets.getOrDefault(key, 0.0));
                    if (missing > ItemFlows.EPSILON) {
                        unsatisfied.put(key, missing);
                    }
                    if (imported - missing > ItemFlows.EPSILON) {
                        rawInputs.put(key, imported - missing);
                    }
                } else {
                    rawInputs.put(key, imported);
                }
            }
            if (leftOver > ItemFlows.EPSILON) {
                byproducts.put(key, leftOver);
            }
        }
        targets.forEach((key, rate) -> {
            double missing = unsatisfied.getOrDefault(key, 0.0);
            if (rate - missing > ItemFlows.EPSILON) {
                products.put(key, rate - missing);
            }
        });
        if (!unsatisfied.isEmpty()) {
            warnings.add("targets not fully satisfied: " + unsatisfied.keySet());
            if (!atLimit.isEmpty()) {
                warnings.add("these lines are already running at their machine limit, which is what "
                        + "caps the plan: " + atLimit);
            }
        }

        // Only now is it known what the plan does not consume, so only now can a line say which part
        // of its production was wanted.
        SurplusAttribution.apply(byLine, byproducts);

        // Report in the plan's own order: the user reads the same list they built, and /mfp explain
        // addresses lines by number.
        List<LineResult> results = new ArrayList<>(byLine.size());
        for (Line line : plan.allLines()) {
            LineResult result = byLine.get(line);
            if (result != null) {
                results.add(result);
            }
        }

        return new SolveResult(results, byLine, products, rawInputs, byproducts, unsatisfied,
                euIn, euOut, steamIn, confidence, warnings, SolverMode.SIMPLEX);
    }

    private static LineResult lineResult(Column column, double craftsPerSecond) {
        Throughput throughput = column.throughput();
        double rate = Math.max(0.0, craftsPerSecond);
        double perMachine = throughput.craftsPerSecond();
        double machineCount = perMachine > ItemFlows.EPSILON ? rate / perMachine : 0.0;

        Map<MfpKey, Double> inputs = new LinkedHashMap<>();
        Map<MfpKey, Double> outputs = new LinkedHashMap<>();
        column.perCraft().forEach((key, amount) -> {
            double flow = amount * rate;
            if (flow < -ItemFlows.EPSILON) {
                inputs.put(key, -flow);
            } else if (flow > ItemFlows.EPSILON) {
                outputs.put(key, flow);
            }
        });
        if (throughput.fixedEuPerSecond() > 0) {
            inputs.merge(MfpKey.EU, throughput.fixedEuPerSecond(), Double::sum);
        }

        String note = column.note();
        if (rate <= ItemFlows.EPSILON && note == null) {
            note = "nothing in the plan needs what this line makes";
        }
        // Everything the line makes goes under outputs for now, as in the matrix engine: what is
        // surplus is not known until the leftover columns are read, and SurplusAttribution splits it
        // afterwards.
        return new LineResult(column.line(), rate, machineCount,
                throughput.euInPerSecond() * machineCount + throughput.fixedEuPerSecond(),
                throughput.euOutPerSecond() * machineCount,
                throughput.steamPerSecond() * machineCount,
                inputs, outputs, Map.of(), column.confidence(), note);
    }

    /** A line that never entered the programme, and the reason it did not. */
    private record IdleLine(Line line, String reason) {}

    /**
     * Which items may come from outside and which the plan owes.
     *
     * <p>{@code free} and {@code shortfall} are deliberately mutable: {@link #relax} moves items
     * between them between rounds, and carrying that forward is the whole mechanism by which a plan
     * that cannot balance gets an answer rather than an error.
     *
     * @param items      every item the programme has a row for, in row order
     * @param free       items that may be imported, at cost
     * @param shortfall  demanded items the plan cannot make enough of
     * @param targetKeys what the plan was asked for, which is never something to import
     */
    private record Classification(List<MfpKey> items, Set<MfpKey> free, Set<MfpKey> shortfall,
                                  Set<MfpKey> targetKeys) {}
}
