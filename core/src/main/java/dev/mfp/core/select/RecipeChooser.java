package dev.mfp.core.select;

import dev.mfp.core.behaviour.GtTiers;
import dev.mfp.core.index.RecipeIndex;
import dev.mfp.core.model.MaterialForm;
import dev.mfp.core.model.MaterialForms;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.Line;
import dev.mfp.core.plan.MachineConfig;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.plan.Preferences;
import dev.mfp.core.plan.RawMaterials;
import dev.mfp.core.plan.SolverMode;
import dev.mfp.core.plan.TargetOutput;
import dev.mfp.core.solver.ItemFlows;
import dev.mfp.core.solver.LineResult;
import dev.mfp.core.solver.SolveResult;
import dev.mfp.core.solver.Solvers;
import dev.mfp.core.solver.ThroughputResolver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns "make 5 casings a second" into a set of lines the solver can run.
 *
 * <p>Two responsibilities that are easy to conflate and must not be:
 *
 * <ol>
 *   <li><b>Choosing recipes</b>, which {@link RecipeScorer} ranks and the user may override.
 *   <li><b>Ordering lines</b>, which is not presentation. The sequential engine walks a floor once
 *       carrying demand downward, so a line can only be fed by lines below it. Lines emitted in the
 *       wrong order make a perfectly acyclic plan report imports for things it actually produces —
 *       the same symptom a genuine loop produces. Emitting in topological order removes that whole
 *       class of false alarm.
 * </ol>
 *
 * <p>Cycles are <em>observed</em>, not inferred. The walk keeps the current recipe path, so
 * re-entering a recipe already on it is a loop by definition. That is what
 * {@link ChooserResult#requiresMatrixSolver()} is set from, rather than from the solver's warning,
 * so a chooser bug can never be mistaken for a modelling limitation.
 */
public final class RecipeChooser {

    /** How deep expansion may go before giving up. GregTech chains are deep but not unbounded. */
    private static final int MAX_DEPTH = 64;

    /** How many times to retry around a loop before accepting that the loop is real. */
    private static final int MAX_CYCLE_RETRIES = 6;

    /**
     * How many rounds the blacklist's consequences are followed before the plan is accepted as it is.
     *
     * <p>Each round makes one more layer of items unavailable, so this is the depth of chain a
     * blacklisted item can poison: inferium to air essence to wood essence to oak log is three. Six
     * is generous for the shape these have in practice and keeps a pathological graph bounded.
     */
    static final int MAX_BLOCK_ROUNDS = 6;

    /**
     * How many times to re-expand offering the previous plan's leftovers (M11.1).
     *
     * <p>Small on purpose. The spare set only accumulates, so this terminates on its own; the cap is
     * there because each round is a full walk of the graph, and a plan that has not settled after
     * four of them is not going to.
     */
    private static final int MAX_BYPRODUCT_ROUNDS = 4;

    /** Above this many lines a candidate is costed by the single pass rather than the simplex. */
    private static final int PROBE_LINE_LIMIT = 250;

    /**
     * Above this many lines the byproduct pass takes one round with one relaxation, not four with two.
     *
     * <p>Each round is two full walks of the graph and the walks are what cost. On the pack's worst
     * plan — a tungsten chain of fifteen hundred lines that is already reported as UNKNOWN — the full
     * pass took the expansion from five seconds to eleven, and eleven seconds in a GUI reads as a
     * hang. A plan that size is beyond the pass's help anyway: it is not one factory, it is the
     * chooser having failed to find one.
     */
    private static final int LARGE_PLAN_LINES = 400;

    /**
     * How far downstream of an item to look when testing whether a recipe merely undoes it.
     *
     * <p>Two levels, because GregTech's conversion loops are typically four recipes long and each
     * level of this search covers two of them: ingot to nugget, then nugget to magnetic nugget.
     * One level misses them entirely; three starts sweeping in legitimate inputs.
     */
    private static final int DOWNSTREAM_DEPTH = 2;

    /**
     * Consumers examined per item.
     *
     * <p>Generous, because the cap is not a performance nicety but a correctness trade: a common
     * intermediate such as an aluminium ingot has hundreds of consumers, and truncating too early
     * hides the very recipes that reveal a conversion loop. The search is cached per item and runs
     * once per plan, so this costs little.
     */
    private static final int DOWNSTREAM_CONSUMER_LIMIT = 512;

    /** Hard ceiling on the downstream set, so one pathological item cannot stall a plan. */
    private static final int DOWNSTREAM_KEY_LIMIT = 4096;


    private final RecipeIndex index;
    private final Preferences preferences;
    private final Map<MfpKey, Set<MfpKey>> downstreamCache = new LinkedHashMap<>();

    /**
     * How many ways of making an input to look at before giving up on finding a loop.
     *
     * <p>The same bound and the same reason as {@link #DOWNSTREAM_CONSUMER_LIMIT}: water has
     * thousands of producers in this pack, and a loop that only shows up on the two thousandth of
     * them is not one the scorer should be spending a plan's time hunting for.
     */
    private static final int FED_CYCLE_SUPPLIER_LIMIT = 256;

    /**
     * How many items the blacklist's consequences may reach before the search stops (M14).
     *
     * <p>A ceiling rather than a budget: the search only ever looks at consumers of something it
     * has already lost, so on a real blacklist it settles in two or three rounds over a handful of
     * items. The cap is there so that blocking water - which every third recipe in the pack drinks -
     * cannot turn opening a picker into a scan of the index.
     */
    private static final int BLACKLIST_REACH_LIMIT = 4096;

    /** Answers to {@link #wouldCloseAFedCycle}, which is asked once per candidate per pick. */
    private final Map<String, Boolean> fedCycleCache = new LinkedHashMap<>();
    private final Map<String, Integer> buildCostCache = new LinkedHashMap<>();

    /** @see #blacklistConsequences */
    private Map<MfpKey, MfpKey> consequences = Map.of();

    /** The blacklist those consequences were worked out from, so a changed one is noticed. */
    private String consequencesFor;

    /** @see #blacklistRule */
    private Unavailability.Rule blacklistRule;

    /** @see #ceiling */
    private TierCeiling ceiling;

    /** What {@link #ceiling} was built for, so a changed one is noticed. Fields, not a signature
     *  string: this is asked once per candidate recipe, and building a string there is a cost the
     *  walk pays a hundred thousand times over. */
    private Plan ceilingPlan;
    private int ceilingTier;
    private boolean ceilingSwitch;
    private int ceilingHidden;

    /** @see #tierConsequences */
    private Map<MfpKey, MfpKey> tierConsequences = Map.of();

    /** The ceiling those consequences belong to, by identity. */
    private TierCeiling tierConsequencesFor;

    /** The plan {@link #blacklistRule} was built for, by identity. */
    private Plan ruleFor;

    private Set<MfpKey> producedIgnoringVariant;

    /**
     * The raw set of the plan being worked on, for the scorer's terminal-recipe term.
     *
     * <p>Held here rather than passed through {@code RecipeScorer.score} because the oracle is
     * exactly the seam for "questions the scorer cannot answer from a recipe alone", and this is
     * one. Set at every public entry point, so it can never be a leftover from a previous plan.
     */
    private Set<MfpKey> rawMaterials = Set.of();

    /**
     * The plan being worked on, for the scorer's throughput term.
     *
     * <p>Held for the same reason {@link #rawMaterials} is, and needed for a further reason: the
     * rate a candidate achieves depends on the machine the line would be put on, and that is a
     * question about this plan - the user may have chosen a machine for the recipe type, built one
     * for the recipe itself, or set a default tier.
     */
    private Plan scoringPlan;

    /**
     * Per-machine rates already resolved during this walk, keyed by recipe.
     *
     * <p>The walk asks for the same key's candidates repeatedly - once per expansion round, and
     * again for every plan the steering and feeding passes try - and resolving a rate means picking
     * a machine and folding a behaviour chain. Cleared with the plan, because a different plan may
     * put the same recipe on a different machine.
     */
    private final Map<String, Double> rateCache = new LinkedHashMap<>();

    /**
     * Whether this walk is being run with the scorer's throughput term switched off.
     *
     * <p>Not a setting: both halves of one comparison. See {@link #cheaperOfTheRateDecision}.
     */
    private boolean rateBlind;

    /** Whether the throughput term overturned a pick during this expansion. */
    private boolean rateDecidedAPick;

    /**
     * Whether this walk is being run with the scorer's loop terms back in full force (M13 item 5).
     *
     * <p>Not a setting, and the twin of {@link #rateBlind}: both halves of one comparison. See
     * {@link #theBetterOfTheLoopDecision}.
     */
    private boolean loopBlind;

    /** Whether a withheld loop penalty could have decided a pick during this expansion. */
    private boolean loopDecidedAPick;

    /**
     * Items this walk may not answer with a particular recipe, and which one (M13 item 1).
     *
     * <p>Not a blacklist: the recipe stays on the plan and keeps supplying everything else it makes.
     * What is withdrawn is its use as <em>the</em> answer to one demand, which is how the second
     * half of {@link #cheaperOfTheSplitDecision}'s comparison gets built.
     */
    private Map<MfpKey, String> dedicatedFor = Map.of();

    /**
     * Items the previous expansion left over, for the byproduct-feeding pass (M11.1).
     *
     * <p>Empty during the first walk by definition — the plan does not exist yet, so nothing is
     * spare — and that is exactly why this had to be a second pass rather than something the scorer
     * could work out on its own.
     */
    private Set<MfpKey> spareByproducts = Set.of();

    /** Whether this walk is a byproduct-feeding round, and so may reuse a line already chosen. */
    private boolean feedingRound;

    /**
     * Whether this walk also stops counting a spare input as evidence that a recipe is reversible.
     *
     * <p>Separate from {@link #feedingRound} because the two relaxations do not help the same plans,
     * and one knob turning on both meant a plan that wanted one had to pay for the other. Each round
     * walks the graph under both settings and keeps whichever came out better; see
     * {@link #feedByproducts}.
     */
    private boolean spareIsNotAReversal;

    private ThroughputResolver resolver = ThroughputResolver.BASE;

    /**
     * The expansion currently running, or null between walks (M13 item 5).
     *
     * <p>Held here because the scorer's oracle is one object shared by every walk, and the one
     * question item 5 adds - would this pick close a loop, and does anything feed it - can only be
     * answered by the walk that is part-way through building the plan. Nobody outside {@link
     * #expandOnce} sets it, and a caller with no walk in progress (the picker, a test) gets the
     * behaviour that stood before the item.
     */
    private Expansion active;

    /** Answers the scorer's index-dependent questions, sharing this chooser's caches. */
    private final RecipeScorer.Oracle oracle = new RecipeScorer.Oracle() {

        @Override
        public boolean isReversible(MfpRecipe recipe, MfpKey producedKey) {
            return RecipeChooser.this.isReversible(recipe, producedKey);
        }

        @Override
        public boolean isRecycling(MfpRecipe recipe) {
            return RecipeChooser.this.isRecycling(recipe);
        }

        @Override
        public MaterialForm form(MfpKey key) {
            return index.form(key);
        }

        @Override
        public int buildCost(MfpRecipe recipe) {
            return RecipeChooser.this.buildCost(recipe.recipeTypeId());
        }

        @Override
        public boolean isRaw(MfpKey key) {
            return rawMaterials.contains(key);
        }

        @Override
        public boolean isSpareByproduct(MfpKey key) {
            return spareByproducts.contains(key);
        }

        @Override
        public boolean closesAFedCycle(MfpRecipe recipe, MfpKey producedKey) {
            return !loopBlind
                    && ((active != null && active.closesAFedCycle(recipe, producedKey))
                            || wouldCloseAFedCycle(recipe, producedKey));
        }

        @Override
        public double outputPerSecond(MfpRecipe recipe, MfpKey key) {
            return RecipeChooser.this.outputPerSecond(recipe, key);
        }
    };

    /**
     * How much of {@code key} one machine running {@code recipe} makes per second (M13 item 2).
     *
     * <p>Deliberately the same arithmetic as the recipe picker's rate column - the machine the plan
     * would default to, through the resolver the plan is being costed with, times the expected
     * amount of the item per craft. The screen and the scorer disagreeing about which recipe is
     * faster would be worse than neither of them knowing.
     *
     * <p>Expected amount, so a 10% byproduct counts as a tenth. That double-counts chance against
     * the term that already discounts a chanced output, and it should: one is about whether the
     * recipe is a way of making the item on purpose, and this is about how much of it comes out per
     * second, which is the question §13a M13 item 3 turns on.
     */
    private double outputPerSecond(MfpRecipe recipe, MfpKey key) {
        if (rateBlind || !recipe.hasRate()) {
            return RecipeScorer.UNKNOWN_RATE;
        }
        double perCraft = 0;
        for (MfpOutput output : recipe.outputs()) {
            if (output.key().equals(key)) {
                perCraft += output.expectedAmount();
            }
        }
        if (perCraft <= 0) {
            return RecipeScorer.UNKNOWN_RATE;
        }
        Double crafts = rateCache.get(recipe.id());
        if (crafts == null) {
            MachineConfig config = MachinePicker.pick(index, recipe, scoringPlan, preferences, ceiling(scoringPlan));
            crafts = resolver.resolve(recipe, config).craftsPerSecond();
            rateCache.put(recipe.id(), crafts);
        }
        return crafts <= 0 ? RecipeScorer.UNKNOWN_RATE : crafts * perCraft;
    }

    /** Remember which plan the scorer's plan-dependent questions are being asked about. */
    private void scoringPlan(Plan plan) {
        if (scoringPlan != plan) {
            rateCache.clear();
        }
        scoringPlan = plan;
    }

    public RecipeChooser(RecipeIndex index) {
        this(index, Preferences.none());
    }

    /**
     * @param preferences the player's standing defaults, which every plan may overrule (M8)
     */
    public RecipeChooser(RecipeIndex index, Preferences preferences) {
        this.index = index;
        this.preferences = preferences == null ? Preferences.none() : preferences;
    }

    /**
     * The rates to cost a candidate plan with, when deciding whether a byproduct feed was worth it.
     *
     * <p>Worth passing whenever the caller has one. The comparison in {@link #feedByproducts} is
     * between two plans rather than against an absolute, so the base resolver ranks them the same way
     * most of the time — but overclocking is exactly the thing that makes one route cheaper than
     * another in GregTech, and a caller that already built a behaviour-aware resolver should not have
     * the chooser guessing without it.
     */
    public RecipeChooser withResolver(ThroughputResolver newResolver) {
        this.resolver = newResolver == null ? ThroughputResolver.BASE : newResolver;
        return this;
    }

    /**
     * Whether a recipe is recycling a used item rather than producing its output.
     *
     * <p>The tell is an input pinned to a specific NBT variant that no recipe produces, while some
     * other form of the same item is made routinely. That is the shape of GregTech's generated tool
     * recycling: arc-furnacing a worn buzzsaw wants a particular damage value, and nothing makes a
     * buzzsaw in that state, so the plan bottoms out importing the tool — which is not a way to
     * obtain aluminium.
     *
     * <p>Note what this deliberately does not say: "an unproducible input is bad". Raw ore is
     * unproducible too, and smelting ore is the route we most want — but no form of raw ore is
     * produced by anything, so it is not caught here. The rule fires only on the mismatch between an
     * item being manufactured and this particular state of it being unobtainable.
     *
     * <p>It is deliberately narrow. A broader version that dropped the variant requirement caught
     * more junk but pushed selection towards <em>other</em> recycling recipes whose inputs are
     * producible, expanding a two-line plan into eighty. Recipe selection on this graph is not
     * solved by one more rule; see STATUS for what would actually be needed.
     */
    private boolean isRecycling(MfpRecipe recipe) {
        for (MfpIngredient input : recipe.inputs()) {
            if (!input.consumed() || input.effectiveAmount() <= 0) {
                continue;
            }
            MfpKey key = input.primary();
            if (key.variant() == null || !index.producing(key).isEmpty()) {
                continue;
            }
            if (producedIgnoringVariant().contains(key.withoutVariant())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The tier of the cheapest machine that runs {@code recipeTypeId}, judged by how it is made.
     *
     * <p>A machine is an item like any other, so the index already knows what it takes to obtain
     * one: the recipe that produces it, and that recipe's tier. A greenhouse is assembled at LV; the
     * multiblock controller that does the same job later in the game comes off the assembly line at
     * ZPM. Nothing here is named, and nothing is hardcoded — a pack that adds a cheaper machine for
     * an existing recipe type is preferred automatically, which is the behaviour a pack author would
     * expect from adding one.
     *
     * <p>The minimum across the machines, because {@link MachinePicker} will pick the cheapest of
     * them anyway; scoring the recipe by a machine the plan would not use would be scoring a build
     * nobody is going to make.
     *
     * <p>Unknown when no recipe makes any of the machines. That is the honest answer for hand
     * crafting, for a machine given out by a quest, and for one the pack removed the recipe for, and
     * it produces no term at all rather than a guess in either direction.
     */
    private int buildCost(String recipeTypeId) {
        Integer cached = buildCostCache.get(recipeTypeId);
        if (cached != null) {
            return cached;
        }

        int cheapest = RecipeScorer.UNKNOWN_BUILD_COST;
        for (MfpMachine machine : index.machinesFor(recipeTypeId)) {
            int cost = MachinePicker.buildCost(index, machine);
            if (cost != RecipeScorer.UNKNOWN_BUILD_COST
                    && (cheapest == RecipeScorer.UNKNOWN_BUILD_COST || cost < cheapest)) {
                cheapest = cost;
            }
        }
        buildCostCache.put(recipeTypeId, cheapest);
        return cheapest;
    }

    /** Whether this item is an ore in some form — crushed, geode, raw or a plain ore block. */
    private boolean isOreForm(MfpKey key) {
        return MaterialForms.oreSourceRank(index.form(key)) != MaterialForms.NOT_AN_ORE_SOURCE;
    }

    /** Every item some recipe produces, with NBT variants collapsed. Built once, lazily. */
    private Set<MfpKey> producedIgnoringVariant() {
        if (producedIgnoringVariant == null) {
            Set<MfpKey> produced = new LinkedHashSet<>();
            for (MfpRecipe recipe : index.all()) {
                for (MfpOutput output : recipe.outputs()) {
                    produced.add(output.key().withoutVariant());
                }
            }
            producedIgnoringVariant = produced;
        }
        return producedIgnoringVariant;
    }

    /**
     * Whether {@code recipe} merely undoes the thing it is supposed to produce.
     *
     * <p>True when one of its inputs is itself made, directly or nearly so, <em>from</em> that
     * output. Nine steel nuggets make a steel ingot; a steel ingot makes nine steel nuggets. The
     * pair is a unit conversion rather than a way of obtaining steel, and following it leads into a
     * loop — which is why this outweighs every other term in the scorer.
     *
     * <p>Structural rather than name-based, deliberately: a pack can name its items anything, and a
     * rule that keyed off {@code _nugget} would work on GregTech and nowhere else.
     */
    private boolean isReversible(MfpRecipe recipe, MfpKey producedKey) {
        Set<MfpKey> downstream = downstreamOf(producedKey);
        if (downstream.isEmpty()) {
            return false;
        }
        for (MfpIngredient input : recipe.inputs()) {
            if (!input.consumed() || input.effectiveAmount() <= 0) {
                continue;
            }
            if (spareIsNotAReversal && spareByproducts.contains(input.primary())) {
                // An input the plan is already throwing away cannot be the reason this recipe is
                // undoing something (M11.1). Reversibility exists to catch packaging pairs - nugget
                // to ingot and back - where the two recipes cancel; here the plan has a surplus of
                // the item and consuming it is the whole point. Without this exception the rule
                // fires on exactly the shape M11.1 hunts for: the greenhouse turns carbon dioxide
                // into oxygen, so *any* recipe making carbon dioxide out of oxygen looks like its
                // reverse, and the -60 kept the pack's own tree loop 56 points below a five-line
                // beetroot chain that threw the oxygen away.
                continue;
            }
            if (downstream.contains(input.primary())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether choosing this recipe would <em>make</em> a fed loop available (M13 item 5).
     *
     * <p>The other half of the same question, for the place the walk has no path to ask it of: the
     * pick at the top of the plan. Nothing has been chosen yet when the first target is resolved,
     * so there is no cycle to look at and both loop terms fire on shape alone - which is why the
     * pack's tree loop was never found unless the greenhouse was pinned. The greenhouse that eats
     * carbon dioxide scored <b>-34.3</b> against the one that only drinks water at <b>7.7</b>, and
     * the difference is very nearly the reversibility penalty the loop was charged for being a loop.
     *
     * <p>What is asked of the index is deliberately narrow. A recipe invites a loop when something
     * that could supply one of its inputs consumes <em>a different output of its own</em> - the
     * greenhouse gives off oxygen, and burning charcoal in oxygen is how the pack makes the carbon
     * dioxide it drinks. That "different" is the whole of the safety: nine nuggets make an ingot,
     * and the recipe that supplies the nuggets consumes the ingot itself, which is the packaging
     * pair the penalty exists for and is excluded here by construction. And the pair still has to
     * be fed from outside, on the same reasoning as the path case.
     *
     * <p>It is speculative where the path case is factual - the walk may go on to pick some other
     * producer - and that is why it only ever <em>withholds</em> a penalty rather than awarding a
     * bonus. Being wrong costs the plan nothing beyond having judged one loop-shaped recipe on its
     * other merits.
     */
    private boolean wouldCloseAFedCycle(MfpRecipe recipe, MfpKey producedKey) {
        String cacheKey = recipe.id() + "\u0000" + producedKey;
        Boolean cached = fedCycleCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        boolean answer = false;
        outer:
        for (MfpIngredient input : recipe.inputs()) {
            if (!input.consumed() || input.effectiveAmount() <= 0) {
                continue;
            }
            MfpKey wanted = input.primary();
            if (wanted.isPseudo()) {
                continue;
            }
            int scanned = 0;
            for (MfpRecipe supplier : index.producing(wanted)) {
                if (scanned++ >= FED_CYCLE_SUPPLIER_LIMIT) {
                    break;
                }
                for (MfpOutput output : recipe.outputs()) {
                    if (output.key().equals(producedKey) || output.isChanced()) {
                        // The produced key is excluded because a supplier consuming *that* is the
                        // packaging pair itself. A chanced output is excluded because a loop the
                        // factory only closes one time in twenty is not a loop it can run on.
                        continue;
                    }
                    if (supplier.consumes(output.key())
                            && fedFromOutside(List.of(recipe, supplier))) {
                        answer = true;
                        break outer;
                    }
                }
            }
        }
        fedCycleCache.put(cacheKey, answer);
        return answer;
    }

    /** Whether any member of {@code cycle} consumes something no member of it produces. */
    private static boolean fedFromOutside(List<MfpRecipe> cycle) {
        for (MfpRecipe member : cycle) {
            for (MfpIngredient input : member.inputs()) {
                if (!input.consumed() || input.effectiveAmount() <= 0) {
                    continue;
                }
                MfpKey key = input.primary();
                if (key.isPseudo()) {
                    // Energy and computation are how the plan accounts for machines running, not
                    // material entering the loop. Counting them would make every cycle in a
                    // GregTech pack fed, which is to say it would delete the question.
                    continue;
                }
                boolean inside = false;
                for (MfpRecipe other : cycle) {
                    inside |= other.produces(key);
                }
                if (!inside) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Everything reachable by consuming {@code key} and then consuming what that produced.
     *
     * <p>Computed once per item and cached, rather than per candidate: an item with four hundred
     * ways to make it would otherwise repeat the same search four hundred times.
     */
    private Set<MfpKey> downstreamOf(MfpKey key) {
        Set<MfpKey> cached = downstreamCache.get(key);
        if (cached != null) {
            return cached;
        }

        Set<MfpKey> reached = new LinkedHashSet<>();
        Set<MfpKey> frontier = new LinkedHashSet<>(Set.of(key));
        for (int depth = 0; depth < DOWNSTREAM_DEPTH && !frontier.isEmpty(); depth++) {
            Set<MfpKey> next = new LinkedHashSet<>();
            for (MfpKey from : frontier) {
                int scanned = 0;
                for (MfpRecipe consumer : index.consuming(from)) {
                    if (scanned++ >= DOWNSTREAM_CONSUMER_LIMIT || reached.size() >= DOWNSTREAM_KEY_LIMIT) {
                        break;
                    }
                    if (!consumer.consumes(from)) {
                        // consuming() also answers "is used here", which includes catalysts and
                        // programmed circuits. Those do not make the recipe a conversion.
                        continue;
                    }
                    for (MfpOutput output : consumer.outputs()) {
                        if (!output.key().equals(key) && reached.add(output.key())) {
                            next.add(output.key());
                        }
                    }
                }
            }
            frontier = next;
        }

        downstreamCache.put(key, reached);
        return reached;
    }

    /**
     * Expand a plan's targets into lines and install them on its root floor.
     *
     * <p>The plan is left ready to solve: lines ordered, machines chosen, and the solver mode moved
     * to {@link SolverMode#SIMPLEX} if a loop was found and the plan was on {@link SolverMode#AUTO}.
     */
    public ChooserResult expandInto(Plan plan) {
        ChooserResult result = expand(plan);
        result.lines().forEach(plan::add);
        // A sink is fed from above by definition, and one downward pass can only feed a line from
        // below (§5.1) - so a plan carrying one needs the whole-plan engine whether or not its
        // lines happen to share a byproduct in the shape sharesAByproduct looks for.
        if (plan.solverMode() == SolverMode.AUTO
                && (needsWholePlanEngine(result) || !plan.sinks().isEmpty())) {
            // Derived, not chosen: a later expansion that finds no loop must be free to go back to
            // AUTO, or "needs a whole-plan engine" degrades into "once needed one".
            plan.deriveSolverMode(SolverMode.SIMPLEX);
        }
        return result;
    }

    /**
     * Whether this plan is beyond what one top-down pass can answer.
     *
     * <p>Two shapes qualify, and only one of them was being detected. A <b>loop</b> is the obvious
     * one. The other is a <b>byproduct one line makes and another line eats</b>: the sequential
     * engine carries demand downwards in a single pass, so it can only feed a line from below, and a
     * byproduct whose consumer was already solved is left as an import of something the plan
     * demonstrably produces. That is a correct report of a real limitation and a poor answer to the
     * question, and it forced the user to reach for the matrix engine by hand on every chain with a
     * shared byproduct.
     *
     * <p>Which engine that is, is {@code Solvers}' business and not this one's — the mode derived
     * here says <em>a whole-plan engine is needed</em>. It used to say "matrix", and the plan then
     * had to be checked for a machine limit before the mode could be derived at all, because the
     * matrix engine reports a limit as ignored (§5b.6): a plan with both a shared byproduct and a
     * limit had to give one of them up. Since M12 the whole-plan engine is the simplex, which
     * honours a limit and a percentage, so that clause is gone and a plan can have both.
     */
    private static boolean needsWholePlanEngine(ChooserResult result) {
        return result.requiresMatrixSolver() || sharesAByproduct(result.lines());
    }

    /** Whether some line eats a byproduct of another, which a single downward pass may not reach. */
    private static boolean sharesAByproduct(List<Line> lines) {
        for (Line producer : lines) {
            if (producer.recipe().outputs().size() < 2) {
                // With one output there is no byproduct: the line was chosen to make that item, and
                // whoever wanted it is the reason the line exists.
                continue;
            }
            for (MfpOutput output : producer.recipe().outputs()) {
                for (Line consumer : lines) {
                    if (consumer != producer && consumer.recipe().consumes(output.key())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Expand without touching the plan, so callers can review before committing.
     *
     * <p>When the first attempt closes a loop, the chooser tries again while steering around the
     * recipe that closed it. This matters more than it sounds: GregTech is full of reversible
     * "packaging" recipes — nugget to ingot, ingot to block, polarise and unpolarise — and the
     * scorer will happily pick one, producing a loop where a perfectly ordinary production route
     * existed. Steering around it is not the same as pretending loops do not exist: if no acyclic
     * plan can be found, the original result is returned with its loop intact, because that loop is
     * real and is what the matrix engine is for.
     */
    public ChooserResult expand(Plan plan) {
        rawMaterials = plan.rawMaterials();
        scoringPlan(plan);
        spareByproducts = Set.of();
        try {
            if (!plan.autoResolve()) {
                // Hand-built (M11.3): one walk, and nothing in it is the chooser's opinion except
                // the target's own recipe. Both of the passes below exist to second-guess the
                // scorer's picks — steering around a loop it wandered into, and re-picking to eat a
                // leftover — and there are no picks to second-guess. Running them anyway would move
                // the one line the user did not choose and leave the plan's shape depending on
                // which imports happened to be answered, which is precisely the unpredictability
                // this mode exists to escape.
                return placeSinks(plan, expandAvoiding(plan, Set.of()));
            }
            ChooserResult best = expandSteeringAroundPackagingLoops(plan);
            if (loopDecidedAPick) {
                best = theBetterOfTheLoopDecision(plan, best);
            }
            if (rateDecidedAPick) {
                best = cheaperOfTheRateDecision(plan, best);
            }
            if (!plan.byproductFeeds()) {
                return placeSinks(plan, best);
            }
            return placeSinks(plan, cheaperOfTheSplitDecision(plan, feedByproducts(plan, best)));
        } finally {
            spareByproducts = Set.of();
            rateBlind = false;
            rateDecidedAPick = false;
            loopBlind = false;
            loopDecidedAPick = false;
            dedicatedFor = Map.of();
        }
    }

    /**
     * Add the plan's sinks to a plan that has already settled (M18).
     *
     * <p><b>Last, and on purpose.</b> The obvious place for this is inside the walk, straight after
     * the targets, and that is where it was until the pack said otherwise. A sink placed there is
     * part of every candidate plan the passes above build, so its own ingredients become demands the
     * byproduct-feeding round tries to satisfy out of leftovers — and it re-picks the <em>target's</em>
     * chain around them. On the pack's ethylene plan, adding one consumer for hydrogen sulfide threw
     * away the entire naphtha route in favour of an ethanol one, which does not make hydrogen
     * sulfide at all, so the plan ended up <b>importing 200/s of the very thing the sink was added
     * to eat</b>. That is §16.7's fault in a new place: not making something is always cheap.
     *
     * <p>So the rule is: <b>a sink is a decision about the leftovers, never evidence about how to
     * make the target.</b> Everything above has finished choosing before this runs, and this changes
     * nothing that was chosen — it adds lines and expands what those lines themselves need. The
     * surplus therefore cannot stop being made, which is why there is no acceptance test here of the
     * kind §11.3 needed: the failure it would be guarding against is now unreachable by construction.
     *
     * <p>Nothing at all happens to a plan with no sinks, which is every plan before this milestone
     * and most plans after it. That is deliberate: the seeding below reconstructs the dependency
     * edges from what the lines consume rather than from the walk that found them, and a plan that
     * does not need it should not pay for it or be reordered by it.
     */
    private ChooserResult placeSinks(Plan plan, ChooserResult settled) {
        if (plan.sinks().isEmpty()) {
            return settled;
        }
        Expansion expansion = new Expansion(plan, Set.of(), blacklistConsequences(plan));
        Expansion outer = active;
        active = expansion;
        Attempt attempt;
        try {
            expansion.seedFrom(settled);
            plan.sinks().forEach(expansion::consume);
            attempt = expansion.finish();
        } finally {
            active = outer;
        }
        return merge(settled, attempt.result());
    }

    /** The settled plan's own findings, plus whatever placing the sinks turned up. */
    private static ChooserResult merge(ChooserResult settled, ChooserResult withSinks) {
        List<List<String>> cycles = new ArrayList<>(settled.cycles());
        withSinks.cycles().stream().filter(cycle -> !cycles.contains(cycle)).forEach(cycles::add);
        Map<MfpKey, String> reasons = new LinkedHashMap<>(settled.importReasons());
        withSinks.importReasons().forEach(reasons::putIfAbsent);
        return new ChooserResult(withSinks.lines(), cycles,
                union(settled.unresolved(), withSinks.unresolved()),
                union(settled.rawMaterials(), withSinks.rawMaterials()),
                union(settled.truncatedAt(), withSinks.truncatedAt()),
                settled.avoidedForCycles(), Map.copyOf(reasons), settled.byproductFeeds());
    }

    private static List<MfpKey> union(List<MfpKey> first, List<MfpKey> second) {
        Set<MfpKey> all = new LinkedHashSet<>(first);
        all.addAll(second);
        return List.copyOf(all);
    }

    /**
     * Expand a second time with the loop penalties back in force, and keep the better plan.
     *
     * <p>Item 5 lets the scorer withhold its two loop penalties from a recipe whose loop something
     * feeds, and measured on its own that is not safe. It finds the pack's tree loop - the oak plan
     * goes from one greenhouse drinking water at 4500 EU/s to a six-line loop at 1168 - and on the
     * polyvinyl butyral chain it walks into a web of chemistry loops the engine can only balance by
     * relaxing two intermediates into imports, which is §16.7's fault in a new place: <b>not making
     * something is always cheap.</b>
     *
     * <p>So the relaxation does not decide anything. It <em>proposes</em> a plan, the plan the
     * penalties would have built is walked as well, and the two are compared the way every other
     * pass in this milestone compares them - by the same guard that caught the same fault in the
     * feeding round. A loop plan is kept only when it neither costs more energy nor starts buying
     * something it has a line for, and is then strictly better on imports, energy or size. A tie
     * keeps the plan the penalties built, because that is the one in hand.
     *
     * <p>Only run when a withheld penalty could have decided a pick, and "could have" is measured
     * rather than assumed: the winner has to carry one of the markers <em>and</em> have a runner-up
     * within the largest penalty either term withholds. A winner sixty points clear would have won
     * anyway, and walking the graph again to be told so is a second's work for nothing.
     */
    private ChooserResult theBetterOfTheLoopDecision(Plan plan, ChooserResult loopAware) {
        loopBlind = true;
        ChooserResult blind;
        try {
            blind = expandSteeringAroundPackagingLoops(plan);
        } finally {
            loopBlind = false;
        }
        if (blind.lines().isEmpty() || !stillMakesTargets(plan, blind)) {
            return loopAware;
        }
        if (!costsMore(plan, blind, loopAware) && rank(plan, loopAware, blind) < 0) {
            return loopAware;
        }
        // Kept for the rounds that follow, exactly as the rate comparison keeps its own: a feeding
        // round re-walks the whole plan, and re-walking it under the setting that just lost would
        // quietly rebuild the plan this comparison threw out.
        loopBlind = true;
        return blind;
    }

    /**
     * Expand a second time without the throughput term, and keep whichever plan costs less.
     *
     * <p>The term judges a recipe by what one machine makes per second, which is the right question
     * about a recipe and can be the wrong one about a factory. Both halves were measured on the dev
     * chains: the fastest way to distil ethanol is from biomass rather than wood vinegar, and taking
     * it drops the plan from 4.6 MEU/s to 12 kEU/s, while the fastest primitive blast furnace recipe
     * for steel takes wrought iron, which this pack arc-furnaces out of oxygen it then has to fuse
     * nitrogen plasma to obtain - 600 EU/s becomes 160 kEU/s. Both are near-ties in the scoring and
     * nothing local separates them, so nothing local was asked: the plan is costed.
     *
     * <p>Only run when the term actually overturned something, which is what
     * {@link RecipeScorer#RATE_DECIDED} is for - otherwise the second walk is guaranteed to produce
     * the plan already in hand, at the price of expanding everything twice.
     *
     * <p><b>A tie goes to the faster plan.</b> Cost is what the comparison can see and it is not
     * everything: two plans drawing the same power over the same number of lines are separated by
     * how many machines the user has to build, which is precisely what this term knows and
     * {@link #rank} does not - the aluminium chain is one forge hammer against forty macerators.
     */
    private ChooserResult cheaperOfTheRateDecision(Plan plan, ChooserResult withRate) {
        rateBlind = true;
        ChooserResult blind;
        try {
            blind = expandSteeringAroundPackagingLoops(plan);
        } finally {
            rateBlind = false;
        }
        if (blind.lines().isEmpty() || !stillMakesTargets(plan, blind)) {
            return withRate;
        }
        if (rank(plan, withRate, blind) <= 0) {
            return withRate;
        }
        // Kept for the rounds that follow: a feeding round re-walks the whole plan, and re-walking
        // it under the setting that just lost would quietly rebuild the plan this comparison threw
        // out.
        rateBlind = true;
        return blind;
    }

    /**
     * Expand again with a second source for a demand a line is being over-run to cover, and keep
     * whichever plan the solver says is better (M13 item 1).
     *
     * <p>The gap §13a M13 item 1 names: "take the forty the plan already makes and produce the rest"
     * is not a choice of recipe, and one recipe per item is all the walk can choose. So a line that
     * drops forty plates alongside the acid the plan wants, asked for a hundred plates, is simply
     * run two and a half times over and fifteen hundred millibuckets of acid are thrown away. That
     * is a correct answer to a different question (§12.6), and whether it is the right one depends
     * entirely on what is being wasted: on worthless acid it is the cheap answer, on expensive acid
     * it is not.
     *
     * <p><b>Which is the solver's judgement, so the solver is asked.</b> The trigger comes out of
     * the solve rather than out of the recipe: a line producing <em>more of a demanded item than the
     * plan wants</em> is a line being run for something else it makes, and that something else is
     * the demand worth a second source. Rare, precise, and not a guess about the recipe's shape -
     * the same line in a plan that wanted all its acid would not raise it.
     *
     * <p>The alternative plan is built by withdrawing that recipe as the answer to that one demand,
     * which leaves it on the plan supplying everything else it makes. So the second plan carries
     * both sources, and how much comes from the byproduct and how much is made is decided by the
     * engine. <b>Delivery first, then cost</b>: a plan that makes what was asked for beats one that
     * leaves a target short however cheap it is, and a tie in both keeps the plan already in hand.
     */
    private ChooserResult cheaperOfTheSplitDecision(Plan plan, ChooserResult joined) {
        SolverMode engine = probeEngine(joined, joined);
        SolveResult one = probe(plan, joined, engine);
        Map<MfpKey, String> wanted = one == null ? Map.of() : demandsBoughtWithWaste(one);
        if (wanted.isEmpty()) {
            return joined;
        }

        ChooserResult split;
        dedicatedFor = wanted;
        try {
            split = expandSteeringAroundPackagingLoops(plan);
            if (plan.byproductFeeds()) {
                split = feedByproducts(plan, split);
            }
        } finally {
            dedicatedFor = Map.of();
        }
        if (split.lines().isEmpty() || !stillMakesTargets(plan, split)
                || split.unresolved().size() > joined.unresolved().size()) {
            return joined;
        }

        // Both halves are costed by an engine that can actually divide a demand between two
        // sources. This is M11.1's trap in a second dress: the sequential pass takes each line in
        // turn and cannot split a demand at all, so it prices the two-source plan as though the
        // second source were idle - the two plans come out identical to the last decimal and the
        // comparison decides nothing. The whole-plan engine is the one being asked a question about
        // the whole plan.
        SolverMode whole = joined.lines().size() <= PROBE_LINE_LIMIT
                && split.lines().size() <= PROBE_LINE_LIMIT ? SolverMode.SIMPLEX : engine;
        SolveResult withOneSource = probe(plan, joined, whole);
        SolveResult withTwo = probe(plan, split, whole);
        if (withOneSource == null || withTwo == null) {
            return joined;
        }

        // Delivery is the chooser's business, because a plan that does not make what was asked for
        // is not an answer at any price.
        double shortJoined = shortfall(withOneSource);
        double shortSplit = shortfall(withTwo);
        if (Math.abs(shortJoined - shortSplit) > ItemFlows.EPSILON) {
            return shortSplit < shortJoined ? split : joined;
        }

        // The split itself is not. The engine has both sources in front of it and an objective the
        // rest of MFP is already answered by - take what there is, then buy as little as possible -
        // so what it does with the second source is the answer to the question this pass asks.
        // Ranking the two plans on some other measure here would be the chooser deciding after all,
        // which is the thing §13a M13 item 1 is about.
        return usesASecondSource(split, withTwo, wanted) ? split : joined;
    }

    /** Whether the engine actually ran a source this pass added, rather than leaving it idle. */
    private static boolean usesASecondSource(ChooserResult split, SolveResult solved,
                                             Map<MfpKey, String> wanted) {
        for (LineResult line : solved.lines()) {
            String id = line.line().recipe().id();
            for (Map.Entry<MfpKey, String> entry : wanted.entrySet()) {
                if (!id.equals(entry.getValue()) && line.line().recipe().produces(entry.getKey())
                        && line.machineCount() > ItemFlows.EPSILON) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Demands the plan is paying for by over-producing something else, and the line doing it.
     *
     * <p>A line appearing in both halves of its own result - some of an item demanded, more of the
     * same item thrown away - is running faster than that item needs. Nothing else it makes can be
     * the reason it runs at all, so every other demand it covers is a candidate for a source of its
     * own. An ordinary byproduct nobody wants is not this: that item is surplus and never demanded,
     * so it never appears on both sides.
     */
    private static Map<MfpKey, String> demandsBoughtWithWaste(SolveResult solved) {
        Map<MfpKey, String> wanted = new LinkedHashMap<>();
        for (LineResult line : solved.lines()) {
            boolean overRun = false;
            for (MfpKey key : line.outputs().keySet()) {
                overRun |= line.byproducts().containsKey(key);
            }
            if (!overRun) {
                continue;
            }
            for (MfpKey key : line.outputs().keySet()) {
                if (!line.byproducts().containsKey(key)) {
                    wanted.putIfAbsent(key, line.line().recipe().id());
                }
            }
        }
        return wanted;
    }

    /** How much of what the plan was asked for it does not deliver, as a fraction of each target. */
    private static double shortfall(SolveResult solved) {
        double missed = 0;
        for (Map.Entry<MfpKey, Double> entry : solved.unsatisfied().entrySet()) {
            double asked = entry.getValue() + solved.products().getOrDefault(entry.getKey(), 0.0);
            missed += asked > 0 ? entry.getValue() / asked : 0;
        }
        return missed;
    }

    /**
     * Expand again, knowing what the last attempt threw away (M11.1).
     *
     * <p>Star-Technology deliberately builds chains that feed each other — growing a tree gives off
     * the oxygen that burning charcoal needs to make the carbon dioxide the tree wants — and until
     * now MFP found those only by accident, when the walk happened to re-enter a recipe already on
     * its own path (STATUS §6d.28). An ancestor is visible to a depth-first walk; <b>a byproduct from
     * a sibling branch is not</b>, and that is the common case.
     *
     * <p>So the walk runs again with the previous plan's leftovers in hand, and the scorer prefers a
     * candidate that eats one. Iterated to a fixed point, which in practice is two or three rounds:
     * the set of spare items only ever accumulates, so it terminates.
     *
     * <p><b>A round is kept only if it is strictly better and no worse.</b> Fewer lines or fewer
     * imports, and never more imports. That is what makes this safe to have on by default: the pass
     * cannot talk a plan into a worse shape, only out of a chain it did not need. It is also what
     * stops the obvious oscillation — a round that consumes the oxygen makes the oxygen no longer
     * spare, and re-expanding on that would simply rebuild the plan it started from.
     */
    private ChooserResult feedByproducts(Plan plan, ChooserResult first) {
        ChooserResult best = first;
        Set<MfpKey> tried = new LinkedHashSet<>();
        List<MfpKey> fed = new ArrayList<>();
        boolean large = first.lines().size() > LARGE_PLAN_LINES;
        int rounds = large ? 1 : MAX_BYPRODUCT_ROUNDS;
        for (int round = 0; round < rounds; round++) {
            Set<MfpKey> spare = spareOutputs(best, plan, false);
            // Chanced outputs, left out of the spare set entirely until M13 item 3 on the grounds
            // that a 5% trickle is not a supply. That is true of a 5% trickle and false of a 60%
            // one, and the difference is a rate rather than a probability: five per cent of ten
            // thousand millibuckets is a supply and sixty per cent of one item every two minutes is
            // not. So they are offered like any other leftover and the rate decides, in the one
            // place a rate exists - the costed plan that comes back. Covering a demand from a 5%
            // drop means running the line that drops it twenty times over, which the pass already
            // measures as energy and refuses; a 60% one is a plan getting the item from itself.
            // Nothing is judged here about the probability, because the probability is not the
            // question.
            spare.addAll(spareOutputs(best, plan, true));
            if (spare.isEmpty() || !tried.addAll(spare)) {
                break;      // nothing left over, or nothing left over that has not been offered
            }
            spareByproducts = spare;

            // Both relaxations, separately, because they do not help the same plans. Offering the
            // leftovers alone is enough for a polyethylene chain and does nothing for the pack's tree
            // loop; also excusing the reversibility penalty finds the tree loop and, on its own,
            // talks the polyethylene chain into four extra lines of chemistry it does not need. One
            // walk each and keep the better answer is both cheaper and more honest than trying to
            // predict which a plan wants.
            ChooserResult offered = walk(plan, false);
            ChooserResult andNotAReversal = large ? offered : walk(plan, true);
            ChooserResult chosen = better(plan, best, spare, offered, andNotAReversal);
            if (chosen == null) {
                // The round found nothing worth keeping on its own terms. Before giving up, ask
                // whether it was the *first half* of something (M13 item 4).
                chosen = pairedWithASecondPick(plan, best, spare, large, offered, andNotAReversal);
            }
            if (chosen == null) {
                break;
            }
            tried.addAll(spare);
            for (MfpKey key : spare) {
                if (consumedBy(chosen, key) && !consumedBy(best, key) && !fed.contains(key)) {
                    fed.add(key);
                }
            }
            best = chosen;
        }
        return fed.isEmpty() ? best : best.withByproductFeeds(List.copyOf(fed));
    }

    private ChooserResult walk(Plan plan, boolean exemptReversals) {
        feedingRound = true;
        spareIsNotAReversal = exemptReversals;
        try {
            return expandSteeringAroundPackagingLoops(plan);
        } finally {
            feedingRound = false;
            spareIsNotAReversal = false;
        }
    }

    /**
     * The better of two candidates, or null when neither is worth keeping.
     *
     * <p>Ranked on what the user would rank them on, in the order they would: a plan that stops
     * importing something the pack cannot make beats one that merely got cheaper, and a cheaper plan
     * beats a shorter one. Only candidates that eat something spare and pass {@link #isNoWorse} are
     * considered at all, so this is choosing between improvements rather than picking a least-worst.
     */
    /**
     * A pair of picks that is only worth taking together (M13 item 4).
     *
     * <p>The pass offers what the plan throws away, walks once and judges the result, so it can only
     * find a swap that pays for itself <em>on its own</em>. Where the saving is one step further
     * down - the first swap gives off something a second recipe wants, and only the second recipe is
     * cheaper - the round rejects the first half on cost, stops, and never reaches the half that was
     * the whole point. That is a real shape: a chemical step taken for its main product is very
     * often the only thing in a pack that emits some intermediate, and nothing produces that
     * intermediate until the step is on the plan.
     *
     * <p>So a rejected candidate is read as a hypothesis rather than as an answer. What it would
     * have thrown away is offered <em>alongside</em> what the current plan throws away, and the walk
     * runs once more. One walk is enough for both picks because the walk is not incremental: given
     * both sets of leftovers the scorer re-derives the first pick for the first set and the second
     * pick for the second, and the plan that comes back contains the pair.
     *
     * <p>Nothing is relaxed to make room for it. The pair is judged against the incumbent by the
     * same three gates as any other round, and the reason-to-keep is deliberately the narrower one:
     * it must eat something from the <em>second</em> set, which is the thing that only exists if the
     * first pick was made. A pair that only eats what round one already offered is round one again,
     * and round one was refused.
     *
     * <p>Only a candidate that ate something itself is read this way. A walk that came back
     * consuming nothing spare is not half of anything - it is the scorer having wandered - and
     * chasing its leftovers would be a second full walk spent on a guess.
     *
     * @param offered the leftovers this round offered; grown by the second set when a pair is kept,
     *                so the caller can report what was fed
     */
    private ChooserResult pairedWithASecondPick(Plan plan, ChooserResult best, Set<MfpKey> offered,
                                                boolean large, ChooserResult... rejected) {
        if (large) {
            // Not on a plan of this size. The round above already gives a large plan one walk
            // instead of two for the same reason, and this is the same judgement one step further
            // on: chasing a pair means a whole extra walk of the graph on the strength of a
            // rejected guess, and on the pack's tungsten plan a walk is five seconds. Measured at
            // 17 s of choosing against 28 s, for a pair that was refused both times it was tried.
            return null;
        }

        Set<MfpKey> second = new LinkedHashSet<>();
        for (ChooserResult candidate : rejected) {
            if (candidate != null && eats(best, candidate, offered)) {
                second.addAll(spareOutputs(candidate, plan, false));
            }
        }
        second.removeAll(offered);
        if (second.isEmpty()) {
            return null;       // the rejected round leaves nothing new lying about, so there is no pair
        }

        offered.addAll(second);
        spareByproducts = offered;
        ChooserResult pair = walk(plan, false);
        ChooserResult pairKeepingReversals = walk(plan, true);
        ChooserResult chosen = better(plan, best, second, pair, pairKeepingReversals);
        if (chosen == null) {
            offered.removeAll(second);
        }
        return chosen;
    }

    /**
     * The better of two candidates, or null when neither is worth keeping.
     *
     * @param mustEat what a candidate has to consume for there to be any reason to keep it
     */
    private ChooserResult better(Plan plan, ChooserResult best, Set<MfpKey> mustEat,
                                 ChooserResult a, ChooserResult b) {
        // Every gate is applied to each candidate on its own before they are ranked against each
        // other. Ranking first and gating the winner loses a good plan to a better-looking one that
        // turns out to be too expensive - which is exactly what happened to the polyethylene chain,
        // where the cheaper nine-line answer was discarded because a fourteen-line one imported one
        // fewer thing and then failed on cost.
        List<ChooserResult> viable = new ArrayList<>(2);
        for (ChooserResult candidate : List.of(a, b)) {
            if (eats(best, candidate, mustEat) && isNoWorse(plan, best, candidate)
                    && !costsMore(plan, best, candidate)) {
                viable.add(candidate);
            }
        }
        if (viable.isEmpty()) {
            return null;
        }
        if (viable.size() == 1) {
            return viable.get(0);
        }
        return rank(plan, viable.get(0), viable.get(1)) <= 0 ? viable.get(0) : viable.get(1);
    }

    /** Whether a candidate consumes something the previous plan was throwing away. */
    private boolean eats(ChooserResult best, ChooserResult candidate, Set<MfpKey> spare) {
        for (MfpKey key : spare) {
            if (consumedBy(candidate, key) && !consumedBy(best, key)) {
                return true;
            }
        }
        return false;
    }

    /** Negative when {@code a} is the better plan. Imports, then energy, then size. */
    private int rank(Plan plan, ChooserResult a, ChooserResult b) {
        if (a.unresolved().size() != b.unresolved().size()) {
            return Integer.compare(a.unresolved().size(), b.unresolved().size());
        }
        SolveResult solvedA = probe(plan, a, probeEngine(a, b));
        SolveResult solvedB = probe(plan, b, probeEngine(a, b));
        if (solvedA != null && solvedB != null
                && Math.abs(solvedA.euDrawPerSecond() - solvedB.euDrawPerSecond()) > 1e-6) {
            return Double.compare(solvedA.euDrawPerSecond(), solvedB.euDrawPerSecond());
        }
        return Integer.compare(a.lines().size(), b.lines().size());
    }

    /**
     * Whether a re-expansion may be kept: it must have cost the plan nothing.
     *
     * <p>The reason to keep a round is decided separately and it is concrete — some item the plan was
     * throwing away is now being eaten by a line that wanted it. This method is the other half: the
     * feed must not have been bought with a bigger plan or a longer shopping list.
     *
     * <p>Deliberately not "the scorer liked it more". The pass is on by default, so its acceptance
     * test is the only thing standing between a plan the user asked for and a plan the chooser talked
     * itself into, and both halves of the test are things the user can see for themselves. The bound
     * on how far the pick can move is the scorer's own: {@code BYPRODUCT_BONUS} is small enough that
     * a recipe has to have been close to winning already.
     */
    private static boolean isNoWorse(Plan plan, ChooserResult best, ChooserResult retry) {
        if (retry.lines().isEmpty() || !stillMakesTargets(plan, retry)
                || retry.unresolved().size() > best.unresolved().size()) {
            return false;
        }
        // A plan may grow if the growth bought it something it could not buy any other way: an
        // unresolved key is an item *nothing in the pack makes*, so a plan carrying one cannot be
        // built at all. The polyethylene chain is the case - four extra lines to stop sourcing
        // hydrogen from a hydroxide that does not exist is not a plan getting bigger, it is a plan
        // becoming possible. Anything short of that has to fit in the same number of lines.
        return retry.lines().size() <= best.lines().size()
                || retry.unresolved().size() < best.unresolved().size();
    }

    /**
     * Whether the feed was bought with a bigger power bill.
     *
     * <p>The counting test above cannot see this and the pack proved it matters. An acetone plan was
     * offered a calcium acetate recipe that eats the oxygen it was throwing away, took it, and paid
     * ten per cent more energy for four times the machines on that line — the byproduct was consumed
     * and the factory was worse. A tungsten plan did the same thing twenty-six per cent worse. So a
     * candidate is costed rather than counted.
     *
     * <p><b>The engine has to be one that can close the loop the pass just created</b>, and getting
     * this wrong silently disabled the whole milestone for its headline case. Feeding the greenhouse's
     * own oxygen back into a carbon dioxide reactor <em>is</em> a loop; cost it with the sequential
     * pass and that engine — which cannot close a loop by construction — reports the oak log as an
     * import and the plan as more expensive than the five-line beetroot chain it replaces. The pass
     * then dutifully refused its own best answer. So a candidate with a cycle in it is costed by the
     * simplex engine, which always answers and closes loops; everything else by the single pass,
     * which is O(lines) and enough.
     *
     * <p>Above {@link #PROBE_LINE_LIMIT} lines the single pass is used regardless. A dense simplex
     * tableau over a thousand-line plan costs more than the expansion it is judging, and a plan that
     * large is already approximate for other reasons.
     *
     * <p>Note what is <em>not</em> compared: how many things the plan imports. {@link #isNoWorse}
     * already checks the chooser's own unresolved list, which is the honest measure of "the pack
     * cannot make this"; the solver's raw inputs also count declared raw materials such as water,
     * where a count is not meaningful.
     */
    private boolean costsMore(Plan plan, ChooserResult best, ChooserResult retry) {
        // One engine for both, decided from the pair. Choosing per candidate compares a number from
        // one engine with a number from another, and the two do not mean the same thing: the single
        // pass leaves a loop's demand as an import and under-counts the energy, so an acyclic
        // candidate costed that way looks cheaper than a looping one costed exactly. That mistake
        // silently withdrew the polyethylene win this pass had already earned.
        SolverMode engine = probeEngine(best, retry);

        SolveResult before = probe(plan, best, engine);
        SolveResult after = probe(plan, retry, engine);
        if (before == null || after == null) {
            return false;
        }
        if (after.unsatisfied().size() > before.unsatisfied().size()) {
            return true;
        }
        if (startsBuyingWhatItMakes(before, after, retry)) {
            return true;
        }
        return after.euDrawPerSecond() > before.euDrawPerSecond() * (1 + 1e-9);
    }

    /**
     * Whether the retry gave up on making something and bought it instead.
     *
     * <p>Found on the pack's polyvinyl butyral chain from cold. The feeding round came back with a
     * plan drawing 1.33 MEU/s where the plan it replaced drew 30.5 MEU/s, and the energy test waved
     * it through - because the saving was not an efficiency. The round had talked the plan into a
     * set of recipes that cannot balance butyraldehyde, so the engine relaxed it, and forty-one of
     * the plan's forty-two lines sat at zero while the two intermediates it had a whole chemistry
     * tree for were imported. <b>Not making something is always cheap.</b>
     *
     * <p>So the energy test needs the same rule §15.7 gave the pruning pass, for the same reason: an
     * item the plan has a line for and buys anyway is the plan having given up, not the plan having
     * economised. The two guards now say the same thing in the two places a plan can lose a chain.
     *
     * <p>Only items the previous plan was <em>not</em> already buying count, so a plan that shortens
     * a shopping list it already had is unaffected.
     */
    private static boolean startsBuyingWhatItMakes(SolveResult before, SolveResult after,
                                                   ChooserResult retry) {
        for (MfpKey key : after.rawInputs().keySet()) {
            if (before.rawInputs().containsKey(key)) {
                continue;
            }
            for (Line line : retry.lines()) {
                if (line.recipe().produces(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static SolverMode probeEngine(ChooserResult a, ChooserResult b) {
        boolean loops = !a.cycles().isEmpty() || !b.cycles().isEmpty();
        boolean small = a.lines().size() <= PROBE_LINE_LIMIT && b.lines().size() <= PROBE_LINE_LIMIT;
        return loops && small ? SolverMode.SIMPLEX : SolverMode.SEQUENTIAL;
    }

    private SolveResult probe(Plan plan, ChooserResult candidate, SolverMode engine) {
        try {
            Plan costed = plan.copy(plan.name());
            costed.clearLines();
            candidate.lines().forEach(costed::add);
            costed.solverMode(engine);
            return Solvers.solve(costed, resolver);
        } catch (RuntimeException failure) {
            // A plan that will not cost is not evidence against the candidate; fall back to the
            // counting test alone rather than losing a feed to a solver problem.
            return null;
        }
    }

    /**
     * What a plan makes and then has no use for.
     *
     * <p>Targets are excluded, because a target is not spare - it is the point.
     *
     * @param chancedOnly when true, the items the plan produces <em>only</em> by chance
     */
    private static Set<MfpKey> spareOutputs(ChooserResult result, Plan plan, boolean chancedOnly) {
        Set<MfpKey> chanced = new LinkedHashSet<>();
        Set<MfpKey> guaranteed = new LinkedHashSet<>();
        Set<MfpKey> consumed = new LinkedHashSet<>();
        for (Line line : result.lines()) {
            for (MfpOutput output : line.recipe().outputs()) {
                if (output.isChanced() ? output.expectedAmount() > 0 : output.amount() > 0) {
                    (output.isChanced() ? chanced : guaranteed).add(output.key());
                }
            }
            for (MfpIngredient input : line.recipe().inputs()) {
                if (input.consumed() && input.effectiveAmount() > 0) {
                    consumed.addAll(input.candidates());
                }
            }
        }
        if (chancedOnly) {
            chanced.removeAll(guaranteed);
        }
        final Set<MfpKey> produced = chancedOnly ? chanced : guaranteed;
        produced.removeAll(consumed);
        plan.targets().forEach(target -> produced.remove(target.key()));
        produced.removeIf(MfpKey::isPseudo);
        return produced;
    }

    private static boolean consumedBy(ChooserResult result, MfpKey key) {
        return result.lines().stream().anyMatch(line -> line.recipe().consumes(key));
    }

    private ChooserResult expandSteeringAroundPackagingLoops(Plan plan) {
        ChooserResult first = expandAvoiding(plan, Set.of());
        if (packagingCycles(first, plan).isEmpty()) {
            // Either there is no loop at all, or every loop it found is one the pack builds on
            // purpose. Steering around the second kind is the fault M11.1 exists to fix: it is not
            // a wrong turn to undo, it is the answer, and the whole-plan engine closes it.
            return first;
        }

        // A pinned recipe is never avoided. The retry exists to steer around packaging loops the
        // scorer wandered into on its own; steering around a recipe the user chose deliberately is
        // not a repair, it is an override — and it produced exactly that: a pinned greenhouse recipe
        // dropped in favour of a crafting-table one, under a warning telling the user to pin the
        // recipe they had already pinned. If the only way to break the loop is through a pin, the
        // loop is real and the matrix engine is the answer.
        //
        // <b>A standing default is the same kind of statement</b>, and it was not covered — found
        // the first time the pack could be driven headlessly (STATUS §9.16). "This is how I make
        // lubricant" was being dropped by the avoidance pass exactly as a pin used to be, and the
        // plan then rebuilt itself around a recipe the user had never chosen, three steps away from
        // the one they had. The distinction that matters is not where the decision is stored but who
        // made it: the retry exists to undo the *scorer's* wandering, and neither of these is that.
        Set<String> pinned = new LinkedHashSet<>(plan.recipeChoices().values());
        pinned.addAll(preferences.defaultRecipes().values());

        Set<String> avoided = new LinkedHashSet<>();
        // Every recipe seen on a cycle, including the pinned ones that are never avoided. This is
        // what says whether an item lost to the retry was a link of the loop — which is the point of
        // breaking it — or collateral damage; see bannedOutOfExistence.
        Set<String> loopRecipes = new LinkedHashSet<>();
        ChooserResult current = first;
        for (int attempt = 0; attempt < MAX_CYCLE_RETRIES; attempt++) {
            // Avoid the whole loop, not just the recipe that closed it. Dropping one link of a
            // four-recipe loop usually just re-forms the same loop through a different link, so a
            // one-at-a-time retry converges far too slowly to be worth the passes.
            Set<String> nextToAvoid = recipesIn(packagingCycles(current, plan));
            loopRecipes.addAll(nextToAvoid);
            nextToAvoid.removeAll(pinned);
            // And never the only way to make something. A cycle is broken by dropping a link the
            // plan can replace, and the members are not equally replaceable: the loop that cost
            // Star-Technology its dust ran cobblestone -> gravel -> sand -> dust -> sieve, where
            // cobblestone has seven recipes and dust has exactly one. Banning the whole cycle
            // banned the one, and the plan then reported an item the pack plainly makes as an
            // import. Sole sources are what the loop has to be broken *around*, not at.
            nextToAvoid.removeIf(id -> isOnlyWayToMakeSomething(index.recipe(id), plan));
            if (nextToAvoid.isEmpty() || !avoided.addAll(nextToAvoid)) {
                // Every remaining link of the loop is pinned, so there is nothing left to try.
                break;
            }
            ChooserResult retry = expandAvoiding(plan, avoided);
            if (bannedOutOfExistence(plan, first, retry, avoided, loopRecipes)) {
                // And stop. The avoided set only grows, so every later round bans strictly more and
                // can only lose more of the plan; carrying on would spend five more full walks of
                // the graph to arrive at the same answer. The loop is that answer.
                break;
            }
            // Accepted when it is acyclic and can still make what was asked for. Note what is
            // deliberately *not* required: an empty unresolved list. Unresolved keys are the plan's
            // imports, and every real GregTech chain bottoms out in raw ore that nothing produces —
            // demanding none would reject every alternative and make this whole pass dead code.
            if (packagingCycles(retry, plan).isEmpty() && stillMakesTargets(plan, retry)) {
                return retry.withAvoided(List.copyOf(avoided));
            }
            current = retry;
        }
        return first;
    }

    /**
     * Whether steering around the loop has left an item with no way to make it at all.
     *
     * <p>The avoidance pass bans every recipe on a cycle, and after a few rounds that is a large
     * set. If one of those recipes was also the only way to make something else the plan needs, the
     * retry does not merely take a different route — it reports an item the pack demonstrably makes
     * as an import, and (before this check) said "nothing in the index produces it", which is a
     * false statement about the pack rather than a report about the plan. Found against
     * Star-Technology: a lubricant plan lost {@code exnihilosequentia:dust} this way, having
     * accepted a retry that was acyclic and still made lubricant.
     *
     * <p>A retry is allowed to import <em>different</em> things — a different route bottoms out in
     * different ore, and rejecting that would make the pass dead code again (§4b). What it may not
     * do is import something whose every usable recipe this pass is the reason for banning. When
     * that happens the loop is preferred: a loop the matrix engine closes is a correct answer, and
     * an acyclic plan with an invented import is not.
     */
    private boolean bannedOutOfExistence(Plan plan, ChooserResult before, ChooserResult retry,
                                         Set<String> avoided, Set<String> loopRecipes) {
        for (MfpKey key : retry.unresolved()) {
            if (before.unresolved().contains(key)) {
                continue;
            }
            if (usable(key, plan).isEmpty() || !usable(key, plan, avoided, Map.of()).isEmpty()) {
                // Either it was never makeable, or it still is: a retry is allowed to import
                // *different* things, because a different route bottoms out in different ore.
                continue;
            }
            if (isLoopItem(key, loopRecipes)) {
                // A link of the loop itself. Breaking a loop necessarily turns one of its own items
                // into an import — that is what breaking it means — so this one is intended.
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * Whether an item is one the loop passes through, rather than one that hangs off it.
     *
     * <p>Produced by a recipe on the cycle <em>and</em> consumed by one. Bone meal made from cabbage
     * and consumed to grow cabbage is such an item, and importing it is exactly how that loop is
     * broken. The dust a loop recipe happens to give off, consumed by a line that has nothing to do
     * with the loop, is not: banning it is collateral damage and the plan is better off looping.
     */
    private boolean isLoopItem(MfpKey key, Set<String> loopRecipes) {
        boolean produced = false;
        boolean consumed = false;
        for (String id : loopRecipes) {
            MfpRecipe recipe = index.recipe(id);
            if (recipe == null) {
                continue;
            }
            produced |= recipe.produces(key);
            consumed |= recipe.consumes(key);
        }
        return produced && consumed;
    }

    /**
     * Whether this recipe is the only usable way to make one of the things it makes.
     *
     * <p>Only its <em>guaranteed</em> outputs count. Being the sole source of a 5% byproduct is not
     * being the way to make it, and treating it as such would make half the loops in a GregTech
     * graph unbreakable — every macerator recipe is the only source of some trace dust.
     */
    private boolean isOnlyWayToMakeSomething(MfpRecipe recipe, Plan plan) {
        if (recipe == null) {
            return false;
        }
        for (MfpOutput output : recipe.outputs()) {
            if (output.isChanced() || output.amount() <= 0) {
                continue;
            }
            List<MfpRecipe> ways = usable(output.key(), plan);
            if (ways.size() == 1 && ways.get(0).id().equals(recipe.id())) {
                return true;
            }
        }
        return false;
    }

    /** Whether a retry can still produce everything the plan asked for. */
    private static boolean stillMakesTargets(Plan plan, ChooserResult result) {
        if (result.lines().isEmpty()) {
            return false;
        }
        for (TargetOutput target : plan.targets()) {
            if (result.unresolved().contains(target.key())) {
                return false;
            }
        }
        return true;
    }

    /**
     * The cycles worth steering around: the ones that cannot supply anything (M11.1).
     *
     * <p>The test is structural rather than a heuristic, and it is a statement about flow. A cycle
     * is <b>productive</b> when material goes in one side and comes out the other:
     *
     * <ol>
     *   <li>it <b>consumes something from outside itself</b> — a cycle whose only inputs are its own
     *       outputs cannot supply anything, which is every one of GregTech's packaging loops: nugget
     *       to ingot and back, ingot to block and back, macerate and re-smelt; and
     *   <li>it <b>emits something the rest of the plan wants</b> - a target, or an input of a line
     *       outside the loop - and, for the second of those, does not eat it again.
     * </ol>
     *
     * <p>Both halves are load-bearing, and the second one was learned the hard way against the pack.
     * On the first condition alone, a sandstone loop — sand pressed to sandstone, cut to a slab,
     * formed to a pillar, hammered back to sand — counts as productive, because the cutter takes
     * distilled water as a coolant. It consumes something from outside and supplies nothing, and
     * keeping it cost a lubricant plan five lines that solved to zero and a page of warnings about
     * running machines backwards. Asking what the loop <em>emits</em> is what tells a coolant from a
     * feedstock without having to know what either is.
     *
     * <p>Energy is excluded from the reckoning, and has to be: every loop consumes it, so counting it
     * would make every loop pass the first test.
     *
     * <p>Computed on the cycle as a whole rather than per recipe. Any single recipe in a loop consumes
     * something the loop makes — that is what being in a loop means — so the question only has an
     * answer at the level of the cycle.
     */
    private List<List<String>> packagingCycles(ChooserResult result, Plan plan) {
        List<List<String>> packaging = new ArrayList<>();
        for (List<String> cycle : result.cycles()) {
            if (!isProductive(cycle, result, plan)) {
                packaging.add(cycle);
            }
        }
        return packaging;
    }

    private boolean isProductive(List<String> cycle, ChooserResult result, Plan plan) {
        Set<String> members = new LinkedHashSet<>(cycle);
        Set<MfpKey> produced = new LinkedHashSet<>();
        Set<MfpKey> consumed = new LinkedHashSet<>();
        List<MfpIngredient> inputs = new ArrayList<>();
        for (String id : members) {
            MfpRecipe recipe = index.recipe(id);
            if (recipe == null) {
                continue;
            }
            for (MfpOutput output : recipe.outputs()) {
                // Guaranteed outputs only: a loop that hands its own input back one time in twenty
                // is not closed, it is a loop being topped up from outside.
                if (!output.isChanced() && output.amount() > 0) {
                    produced.add(output.key());
                }
            }
            for (MfpIngredient input : recipe.inputs()) {
                if (input.consumed() && input.effectiveAmount() > 0) {
                    inputs.add(input);
                    consumed.addAll(input.candidates());
                }
            }
        }

        boolean drawsFromOutside = false;
        for (MfpIngredient input : inputs) {
            boolean fromInside = false;
            for (MfpKey candidate : input.candidates()) {
                fromInside |= candidate.isPseudo() || produced.contains(candidate);
            }
            if (!fromInside) {
                drawsFromOutside = true;
                break;
            }
        }
        if (!drawsFromOutside) {
            return false;
        }

        for (MfpKey key : produced) {
            if (key.isPseudo()) {
                continue;
            }
            if (isTarget(key, plan)) {
                // A target is wanted whether or not the loop also eats some of it, and that is not
                // a special case - it is the pack's tree loop. The greenhouse grows oak, the oak is
                // charred, the charcoal is burnt in the greenhouse's own oxygen to make the carbon
                // dioxide it drinks, and what leaves the loop is the surplus wood the plan asked
                // for. Skipping every key the cycle consumes read that as a loop supplying nothing
                // and steered around it, which is the one loop the milestone is named after.
                return true;
            }
            if (consumed.contains(key)) {
                continue;
            }
            if (wantedOutsideTheCycle(key, members, result, plan)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTarget(MfpKey key, Plan plan) {
        for (TargetOutput target : plan.targets()) {
            if (target.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a line that is not in this loop wants {@code key}. */
    private static boolean wantedOutsideTheCycle(MfpKey key, Set<String> members,
                                                 ChooserResult result, Plan plan) {
        if (isTarget(key, plan)) {
            return true;
        }
        for (Line line : result.lines()) {
            if (!members.contains(line.recipe().id()) && line.recipe().consumes(key)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> recipesIn(List<List<String>> cycles) {
        Set<String> ids = new LinkedHashSet<>();
        cycles.forEach(ids::addAll);
        return ids;
    }

    /**
     * One expansion, with the blacklist's consequences followed as far as they go.
     *
     * <p><b>Blacklisting an item has to reach further than the recipes that name it.</b> Blocking
     * inferium essence and asking for oak logs first produced a plan that burned wood essence,
     * mixed that from air, water and earth essence, and then reported three imports because the only
     * recipes for those three need inferium — which is a correct account of one route and a useless
     * answer to the question. What the user said is "I have no inferium", and the consequence of
     * that is "so I have no air essence either", all the way up until a route that never wanted any
     * appears — a greenhouse, in that plan.
     *
     * <p>So an item every one of whose recipes is unusable becomes unavailable in its own right, and
     * the walk runs again knowing it. Each round can only add to that set, and it is bounded, so it
     * settles. Two things it deliberately does not do:
     *
     * <ul>
     *   <li><b>It never gives up a plan to keep the rule.</b> A round is accepted only if the plan
     *       still makes what was asked for; where no route avoids the blacklisted item, the first
     *       result is kept, imports and all, because that is then the honest answer rather than an
     *       empty plan.
     *   <li><b>It never touches an ordinary import.</b> Only keys whose failure was <em>caused</em>
     *       by the blacklist propagate. Raw ore is unresolved too, and it is unresolved because the
     *       world makes it, which is not a reason to abandon a route.
     * </ul>
     */
    private ChooserResult expandAvoiding(Plan plan, Set<String> extraBlacklist) {
        // The consequences are worked out from the index before the first walk rather than only
        // discovered by it (M14). The walk discovers them where it goes, and a plan built by hand
        // does not go anywhere: it resolves the target and stops, so the essence route to oak logs
        // was still the target's own line long after the automatic plan had routed around it. The
        // rounds below are kept, because a walk can still meet something this search bounded away.
        Map<MfpKey, MfpKey> unreachable = new LinkedHashMap<>(blacklistConsequences(plan));
        Attempt attempt = expandOnce(plan, extraBlacklist, unreachable);
        if (!unreachable.isEmpty() && !stillMakesTargets(plan, attempt.result())) {
            // Never give up a plan to keep the rule - the same clause the rounds below observe.
            // Where the blacklist leaves no route at all, the honest answer is the plan with its
            // imports and their reasons, not an empty one.
            unreachable = new LinkedHashMap<>();
            attempt = expandOnce(plan, extraBlacklist, unreachable);
        }
        for (int round = 0; round < MAX_BLOCK_ROUNDS && !attempt.blockedKeys().isEmpty(); round++) {
            Map<MfpKey, MfpKey> next = new LinkedHashMap<>(unreachable);
            next.putAll(attempt.blockedKeys());
            if (next.size() == unreachable.size()) {
                break;
            }
            Attempt retry = expandOnce(plan, extraBlacklist, next);
            if (!stillMakesTargets(plan, retry.result())) {
                break;
            }
            unreachable = next;
            attempt = retry;
        }
        return attempt.result();
    }

    private Attempt expandOnce(Plan plan, Set<String> extraBlacklist, Map<MfpKey, MfpKey> unreachable) {
        Expansion expansion = new Expansion(plan, extraBlacklist, unreachable);
        Expansion outer = active;
        active = expansion;
        try {
            for (TargetOutput target : plan.targets()) {
                expansion.resolve(target.key(), 0);
            }
        } finally {
            active = outer;
        }
        return expansion.finish();
    }

    /**
     * One walk's result, and which keys it could not make <em>because of the blacklist</em>.
     *
     * <p>The second half is not in {@link ChooserResult} because it is scaffolding for the next
     * round rather than something a caller should act on: by the time expansion finishes, either the
     * plan routed around those keys and they are gone, or it could not and they are imports with a
     * reason attached.
     */
    private record Attempt(ChooserResult result, Map<MfpKey, MfpKey> blockedKeys) {}

    /**
     * The ranked alternatives for producing {@code key}, for the recipe picker.
     *
     * <p>Each recipe comes back with the plan's and the player's preferred items already applied,
     * which is not cosmetic: the picker lists what a line would actually eat, and until M8 it listed
     * the index's own candidate order and only showed the preference once the row had been clicked
     * and the line rebuilt. Two screens describing the same recipe differently is the one thing a
     * planner cannot afford, and doing it here means every caller is fixed rather than one screen.
     */
    public List<RecipeScorer.Scored> alternatives(MfpKey key, Plan plan) {
        rawMaterials = plan == null ? RawMaterials.defaults() : plan.rawMaterials();
        scoringPlan(plan);
        Set<MfpKey> produced = plan == null ? Set.of() : producedBy(plan);
        Set<MfpKey> preferred = preferences.preferredItemsFor(plan);
        List<RecipeScorer.Scored> ranked = RecipeScorer.rank(
                key, usable(key, plan, Set.of(), blacklistConsequences(plan)), produced, oracle);
        if (preferred.isEmpty()) {
            return ranked;
        }
        List<RecipeScorer.Scored> shown = new ArrayList<>(ranked.size());
        for (RecipeScorer.Scored scored : ranked) {
            // Scored on the recipe as the index holds it and displayed as the plan would run it: a
            // preference reorders candidates and changes no amount, so the ranking is the same either
            // way and scoring the rewritten copy would only risk the two drifting apart.
            shown.add(new RecipeScorer.Scored(scored.recipe().withPreferredInputs(preferred),
                    scored.score(), scored.reasons()));
        }
        return shown;
    }

    /**
     * The recipes for {@code key} the plan may not use, and why.
     *
     * <p>The picker's other half of the blocked-item rule. Filtering silently would leave the user
     * looking at an import with no way to find out what happened to the recipes that would have
     * satisfied it, and no way to change their mind — so the recipes are still obtainable, marked.
     */
    /**
     * Every recipe for {@code key} the chooser refused to rank, and the reason in words.
     *
     * <p>The other half of {@link #alternatives}, and the thing that was missing when a plan said an
     * item could not be made: the ranked list shows what was considered, and this shows what was
     * not. A recipe is excluded for exactly four reasons and they are not interchangeable — the user
     * hid it, an input is blacklisted, it never actually yields the item, or the loop-avoidance pass
     * banned it. Only the first two are decisions the user made, and only the last is MFP's.
     *
     * <p>The avoidance case cannot be answered from a recipe alone, so it is not answered here: the
     * set belongs to a particular expansion and lives on {@link ChooserResult#avoidedForCycles()}.
     * Callers that have one cross-reference it.
     */
    public Map<MfpRecipe, String> excludedAlternatives(MfpKey key, Plan plan) {
        Map<MfpRecipe, String> excluded = new LinkedHashMap<>();
        Set<String> hidden = plan == null ? Set.of() : plan.blacklist();
        for (MfpRecipe recipe : index.producing(key)) {
            if (hidden.contains(recipe.id())) {
                excluded.put(recipe, "you hid it in this plan");
                continue;
            }
            if (isDeadEnd(recipe, key)) {
                excluded.put(recipe, "never actually yields " + key);
                continue;
            }
            Unavailability.Unavailable offender =
                    unavailableInput(recipe, plan, blacklistConsequences(plan));
            if (offender != null) {
                excluded.put(recipe, offender.input().equals(offender.cause())
                        ? "needs " + offender.input() + ", which is blacklisted"
                        : "needs " + offender.input() + ", and every way to make that needs "
                                + offender.cause() + ", which is blacklisted");
            }
        }
        return excluded;
    }

    /**
     * Every recipe for {@code key} the tier ceiling would refuse, and why, in words (M17).
     *
     * <p>Deliberately not folded into {@link #excludedAlternatives}: these recipes are still ranked
     * and still listed. §12's rule is that a recipe the chooser refused stays visible with a reason
     * or the user has no way to change their mind, and a tier ceiling refuses more recipes at once
     * than every other rule put together — hiding them would make the picker look like the pack had
     * shrunk. So the picker shows them marked, and clicking one pins it, which outranks the ceiling.
     */
    /**
     * The machine and tier a line for this recipe would get, judged against the ceiling (M17).
     *
     * <p>{@link MachinePicker#pick} without a ceiling is the honest answer for a caller that has no
     * plan; a screen previewing what a line <em>would</em> be has one, and showing a throughput
     * computed on a machine the plan will not use is the same fault as offering it.
     */
    public MachineConfig pickMachine(MfpRecipe recipe, Plan plan) {
        return MachinePicker.pick(index, recipe, plan, preferences, ceiling(plan));
    }

    /**
     * The part in the way of building this machine at the tier the plan builds at, or null (M17).
     *
     * <p>Public so that a screen or a listing offering machines can agree with the plan that just
     * refused one. A default MFP declares unbuildable and then offers in a dropdown is worse than
     * either answer alone.
     */
    public MfpKey missingPartOf(MfpMachine machine, Plan plan) {
        return ceiling(plan).missingPartOf(machine);
    }

    /**
     * The ranked ways of eating a surplus, for the Byproducts tab's picker (M18).
     *
     * <p>The mirror of {@link #alternatives}, and it goes through {@link SinkScorer} rather than
     * {@link RecipeScorer} for the reason that class opens with: a consumer is not a candidate for
     * making anything, so a cost model has nothing to say about it.
     *
     * <p>Filtered the way an expansion would filter it, and one filter more. Hidden recipes and
     * recipes needing a blocked item are out, exactly as they are when the plan is looking for a
     * producer. <b>And the tier ceiling applies here whether or not it applies to a producer</b>: a
     * machine the player cannot build is not a way to eat a surplus, and unlike a target this is
     * never the question being asked. The refused ones come back through {@link #overTierSinks} so
     * the screen can show them marked rather than pretend the pack has fewer consumers than it does.
     *
     * @param imported what the plan is currently buying, from the last solve. Not derivable here —
     *                 the chooser knows what the plan makes, not what the solver had to import —
     *                 and it is what separates "turns this into something you are paying for" from
     *                 "turns this into more of something you already make".
     */
    public List<SinkScorer.Scored> sinks(MfpKey surplus, Plan plan, Set<MfpKey> imported) {
        rawMaterials = plan == null ? RawMaterials.defaults() : plan.rawMaterials();
        scoringPlan(plan);
        return SinkScorer.rank(surplus, usableSinks(surplus, plan), sinkContext(plan, imported));
    }

    /** The consumers the ceiling refuses, and why — listed apart, never silently missing. */
    public Map<MfpRecipe, String> overTierSinks(MfpKey surplus, Plan plan) {
        Map<MfpRecipe, String> over = new LinkedHashMap<>();
        if (!ceiling(plan).isOn()) {
            return over;
        }
        for (MfpRecipe recipe : index.consuming(surplus)) {
            String reason = beyondCeiling(recipe, plan);
            if (reason != null) {
                over.put(recipe, reason);
            }
        }
        return over;
    }

    private List<MfpRecipe> usableSinks(MfpKey surplus, Plan plan) {
        Set<String> blacklist = plan == null ? Set.of() : plan.blacklist();
        Map<MfpKey, MfpKey> unreachable = blacklistConsequences(plan);
        List<MfpRecipe> usable = new ArrayList<>();
        for (MfpRecipe recipe : index.consuming(surplus)) {
            if (blacklist.contains(recipe.id())) {
                continue;
            }
            if (blockedInput(recipe, plan, unreachable) != null) {
                continue;
            }
            if (beyondCeiling(recipe, plan) != null) {
                continue;
            }
            usable.add(recipe);
        }
        return usable;
    }

    /** What {@link SinkScorer} needs to know about the plan the surplus is in. */
    private SinkScorer.Context sinkContext(Plan plan, Set<MfpKey> imported) {
        Set<MfpKey> wanted = new LinkedHashSet<>();
        Set<MfpKey> produced = new LinkedHashSet<>();
        if (plan != null) {
            plan.targets().forEach(target -> wanted.add(target.key()));
            for (Line line : plan.allLines()) {
                for (MfpIngredient input : line.recipe().inputs()) {
                    if (input.consumed() && input.effectiveAmount() > 0) {
                        wanted.addAll(input.candidates());
                    }
                }
                line.recipe().outputs().forEach(output -> produced.add(output.key()));
            }
        }
        // An import is wanted by definition: the plan is buying it because something in it asked.
        // Adding them here rather than relying on the lines is what makes a sink for a target's own
        // ingredient rank when the plan has no line for that ingredient at all, which is every
        // hand-built plan before it has been answered.
        wanted.addAll(imported);
        Set<MfpKey> raw = plan == null ? RawMaterials.defaults() : plan.rawMaterials();
        return new SinkScorer.Context() {
            @Override
            public boolean wanted(MfpKey key) {
                return wanted.contains(key);
            }

            @Override
            public boolean imported(MfpKey key) {
                return imported.contains(key);
            }

            @Override
            public boolean produced(MfpKey key) {
                return produced.contains(key);
            }

            @Override
            public boolean raw(MfpKey key) {
                return raw.contains(key);
            }

            @Override
            public double consumedPerSecond(MfpRecipe recipe, MfpKey key) {
                return RecipeChooser.this.consumedPerSecond(recipe, key);
            }

            @Override
            public String material(MfpKey key) {
                MaterialForm form = index.form(key);
                return form == null ? null : form.material();
            }
        };
    }

    /**
     * The same figure the sink ranking used, for the column that shows it.
     *
     * <p>Public so the picker cannot compute it a second way. The screen showing one number and the
     * ranking using another would make the order on screen unexplainable from the screen itself,
     * which is exactly the fault §17 fixed on the producing side.
     */
    public double consumedPerSecond(MfpRecipe recipe, MfpKey key, Plan plan) {
        scoringPlan(plan);
        return consumedPerSecond(recipe, key);
    }

    /**
     * How much of {@code key} one machine running {@code recipe} eats per second.
     *
     * <p>The same arithmetic as {@link #outputPerSecond} with the sign reversed, sharing the same
     * per-recipe cache of crafts per second — because the two questions differ only in which side of
     * the recipe they read, and two caches would be two chances to disagree about how fast a machine
     * runs.
     */
    private double consumedPerSecond(MfpRecipe recipe, MfpKey key) {
        if (!recipe.hasRate()) {
            return 0;
        }
        double perCraft = 0;
        for (MfpIngredient input : recipe.inputs()) {
            if (input.consumed() && input.effectiveAmount() > 0 && input.candidates().contains(key)) {
                perCraft += input.effectiveAmount();
            }
        }
        if (perCraft <= 0) {
            return 0;
        }
        Double crafts = rateCache.get(recipe.id());
        if (crafts == null) {
            MachineConfig config = MachinePicker.pick(index, recipe, scoringPlan, preferences,
                    ceiling(scoringPlan));
            crafts = resolver.resolve(recipe, config).craftsPerSecond();
            rateCache.put(recipe.id(), crafts);
        }
        return crafts <= 0 ? 0 : crafts * perCraft;
    }

    public Map<MfpRecipe, String> overTierAlternatives(MfpKey key, Plan plan) {
        Map<MfpRecipe, String> over = new LinkedHashMap<>();
        if (!ceiling(plan).isOn()) {
            return over;
        }
        for (MfpRecipe recipe : index.producing(key)) {
            String reason = beyondCeiling(recipe, plan);
            if (reason != null) {
                over.put(recipe, reason);
            }
        }
        return over;
    }

    public Map<MfpRecipe, MfpKey> blockedAlternatives(MfpKey key, Plan plan) {
        Map<MfpRecipe, MfpKey> blocked = new LinkedHashMap<>();
        Map<MfpKey, MfpKey> lost = blacklistConsequences(plan);
        for (MfpRecipe recipe : index.producing(key)) {
            MfpKey offender = blockedInput(recipe, plan, lost);
            if (offender != null) {
                blocked.put(recipe, offender);
            }
        }
        return blocked;
    }

    /**
     * The blocked item that makes this recipe unusable, or null if none does.
     *
     * <p>Every candidate of a consumed input must be blocked before the recipe is: an ingredient
     * accepting either inferium or a plain seed is still satisfiable when only the inferium is
     * blocked, and the plan simply takes the other one ({@link Expansion#chosenCandidate}).
     */
    private MfpKey blockedInput(MfpRecipe recipe, Plan plan) {
        return blockedInput(recipe, plan, Map.of());
    }

    /**
     * The tier this plan builds at, as a requirement (M17).
     *
     * <p>Cached on the plan and the tier rather than the plan alone, because a tier is the one
     * standing preference a player changes to see what happens: {@code mfp defaults tier} in a
     * session, or the Defaults screen. The seed pass behind it walks the whole index once, so a
     * ceiling that recomputed per key would be paid for on every candidate of every recipe.
     */
    private TierCeiling ceiling(Plan plan) {
        int tier = preferences.defaultTierFor(plan);
        boolean on = plan == null || plan.tierCeiling();
        int hidden = plan == null ? 0 : plan.blacklist().size();
        if (ceiling != null && ceilingPlan == plan && ceilingTier == tier
                && ceilingSwitch == on && ceilingHidden == hidden) {
            return ceiling;
        }
        ceiling = new TierCeiling(index, plan, tier);
        ceilingPlan = plan;
        ceilingTier = tier;
        ceilingSwitch = on;
        ceilingHidden = hidden;
        return ceiling;
    }

    /**
     * Every item this plan's tier ceiling leaves unmakeable, and the item each one blames.
     *
     * <p>Fault 4 of M17: refusing a recipe without following the consequence leaves the walk to meet
     * the same wall three items further up and report it as a mystery. It is the blacklist's
     * propagation exactly — {@link Unavailability} again, with the ceiling's rule instead of the
     * blacklist's — which is the whole argument for having generalised the mechanism first.
     *
     * <p>Empty, and not computed at all, when no tier is stated. A filter this broad has to be
     * provably off when it is off.
     */
    private Map<MfpKey, MfpKey> tierConsequences(Plan plan) {
        TierCeiling ceiling = ceiling(plan);
        if (!ceiling.isOn()) {
            return Map.of();
        }
        if (ceiling == tierConsequencesFor) {
            return tierConsequences;
        }
        tierConsequences = ceiling.consequences();
        tierConsequencesFor = ceiling;
        return tierConsequences;
    }

    /**
     * Why the ceiling refuses this recipe, or null if it does not.
     *
     * <p>Both halves of the refusal in one sentence: the recipe's own voltage and machine, and an
     * ingredient nothing at or below the tier can make (the closure). The second is the one that
     * would otherwise be a mystery three items later.
     *
     * <p>Public because a line that survived the ceiling — pinned, a standing default, or the
     * target — has to say that it did. A plan that quietly contains a machine the player cannot
     * build is the fault this milestone is about, and it is not fixed by refusing it everywhere
     * except where the user asked for it and then saying nothing.
     */
    public String beyondCeiling(MfpRecipe recipe, Plan plan) {
        TierCeiling ceiling = ceiling(plan);
        if (!ceiling.isOn()) {
            return null;
        }
        String direct = ceiling.beyond(recipe);
        if (direct != null) {
            return direct;
        }
        MfpKey missing = Unavailability.refusedInput(recipe, ceiling.rule(), tierConsequences(plan));
        if (missing == null) {
            return null;
        }
        int component = ceiling.componentTier(missing);
        return component >= 0
                // A component's tier is a gate, so say so: "nothing at or below tier 3 makes that"
                // is true and reads as a gap in the pack, where the truth is that nothing ever will
                // below its own tier.
                ? "it needs " + missing + ", which is a tier " + component + " ("
                        + GtTiers.name(component) + ") component and you build at tier "
                        + ceiling.tier()
                : "it needs " + missing + ", and nothing at or below tier " + ceiling.tier()
                        + " makes that";
    }

    /**
     * This plan's blacklist, as one of {@link Unavailability}'s rules.
     *
     * <p>The whole of what "blacklisted" means to the shared fixpoint, and it is four short answers.
     * A tier ceiling is a different four.
     */
    private Unavailability.Rule blacklistRule(Plan plan) {
        // Cached on the plan's identity, and holding no snapshot of it, because this is asked once
        // per recipe per candidate: allocating a rule inside the walk would be paying for the
        // generalisation on the hot path. Every answer below reads the plan live, so blocking an
        // item mid-session is seen without the cache needing to know it happened.
        if (blacklistRule != null && ruleFor == plan) {
            return blacklistRule;
        }
        ruleFor = plan;
        blacklistRule = new Unavailability.Rule() {
            @Override
            public Set<MfpKey> refusedItems() {
                // Asked once per closure, never in the walk, so it is worked out rather than held.
                return blockedItems(plan);
            }

            @Override
            public MfpKey refusedBecause(MfpKey key) {
                return preferences.blocks(plan, key) ? key : null;
            }

            @Override
            public boolean setsAside(MfpRecipe recipe) {
                return plan != null && plan.blacklist().contains(recipe.id());
            }

            @Override
            public boolean supplied(MfpKey key) {
                return plan != null && plan.rawMaterials().contains(key);
            }
        };
        return blacklistRule;
    }

    /**
     * @param unreachable items whose every route runs through a blacklisted one, and the blacklisted
     *                    item each of them ultimately blames
     */
    private MfpKey blockedInput(MfpRecipe recipe, Plan plan, Map<MfpKey, MfpKey> unreachable) {
        return Unavailability.refusedInput(recipe, blacklistRule(plan), unreachable);
    }

    private Unavailability.Unavailable unavailableInput(MfpRecipe recipe, Plan plan,
                                                        Map<MfpKey, MfpKey> unreachable) {
        return Unavailability.unavailableInput(recipe, blacklistRule(plan), unreachable);
    }

    /**
     * Every item this plan's blacklist leaves unmakeable, and the blacklisted item each one blames.
     *
     * <p><b>The picker's half of the fixpoint (M14).</b> An expansion discovers this by walking:
     * a round that finds no route to an item marks it unavailable in its own right, the next round
     * runs knowing it, and blocking inferium essence eventually takes the whole essence route to
     * oak logs off the table ({@link #expandAvoiding}). The picker has no walk. It ranks one key,
     * so it saw {@code start:essence_burning/wood_essence_burning_0} - one input, guaranteed
     * output, tier 1 - score 55 and put it first, while the automatic plan for the same item was
     * building a greenhouse. Clicking it led two clicks further to an item with <em>no</em> ways to
     * make it at all, which is the walk's knowledge arriving too late to be any use.
     *
     * <p>Same question, asked without a plan to walk. The mechanism is {@link Unavailability}, and
     * this is one of its rules rather than a second copy of it (M17): the search starts at the
     * blocked items and moves outwards through consumers, bounded by the same
     * {@link #MAX_BLOCK_ROUNDS} the walk observes, so the two cannot disagree about what a blacklist
     * costs. What is written here is only the part that is about blacklisting -
     * {@link #blacklistRule}, four short answers - and the caveats about raw materials and hidden
     * recipes now live with the mechanism, because they are true of every rule it runs.
     *
     * <p>Computed once per blacklist and cached, because the recipe picker re-ranks on every
     * keystroke in its search box and this must not be something a keystroke pays for. The cache
     * key is the blacklist itself rather than the plan, so it survives every edit that cannot
     * change the answer - which is nearly all of them.
     */
    private Map<MfpKey, MfpKey> blacklistConsequences(Plan plan) {
        String signature = blockedItems(plan).toString()
                + (plan == null ? "" : plan.blacklist().toString()
                        + plan.rawMaterials().toString());
        if (signature.equals(consequencesFor)) {
            return consequences;
        }
        consequences = Unavailability.closure(index, blacklistRule(plan),
                MAX_BLOCK_ROUNDS, BLACKLIST_REACH_LIMIT);
        consequencesFor = signature;
        return consequences;
    }

    /** The items this plan may not use at all, standing preference and per-plan override together. */
    private Set<MfpKey> blockedItems(Plan plan) {
        Set<MfpKey> blocked = new LinkedHashSet<>();
        preferences.blockedItems().forEach(key -> {
            if (preferences.blocks(plan, key)) {
                blocked.add(key);
            }
        });
        if (plan != null) {
            plan.blockedItems().forEach(key -> {
                if (preferences.blocks(plan, key)) {
                    blocked.add(key);
                }
            });
        }
        return blocked;
    }

    private List<MfpRecipe> usable(MfpKey key, Plan plan) {
        return usable(key, plan, Set.of(), Map.of(), false);
    }

    private List<MfpRecipe> usable(MfpKey key, Plan plan, Set<String> extraBlacklist,
                                   Map<MfpKey, MfpKey> unreachable) {
        return usable(key, plan, extraBlacklist, unreachable, false);
    }

    /**
     * @param underCeiling whether the tier the player builds at applies here (M17). Off for the
     *                     picker, which lists over-tier recipes marked rather than hiding them, and
     *                     off for a target, which is the question being asked rather than a step in
     *                     answering it.
     */
    private List<MfpRecipe> usable(MfpKey key, Plan plan, Set<String> extraBlacklist,
                                   Map<MfpKey, MfpKey> unreachable, boolean underCeiling) {
        Set<String> blacklist = plan == null ? Set.of() : plan.blacklist();
        List<MfpRecipe> usable = new ArrayList<>();
        for (MfpRecipe recipe : index.producing(key)) {
            if (blacklist.contains(recipe.id()) || extraBlacklist.contains(recipe.id())) {
                continue;
            }
            if (isDeadEnd(recipe, key)) {
                continue;
            }
            if (blockedInput(recipe, plan, unreachable) != null) {
                // An item the player says they have no supply of takes every chain through it out of
                // consideration, not just off the screen. This is the difference between the item
                // blacklist and the recipe one: hiding a recipe steers this plan, blocking an item
                // steers every recipe that wanted it.
                continue;
            }
            if (underCeiling && beyondCeiling(recipe, plan) != null) {
                // Not a dearer way of making this, and not a slower one: a way the player cannot
                // build. The honest answer where every way is refused is an import with the reason
                // on it (recordWhyNothingWorks), which is a decision they can see and act on.
                continue;
            }
            usable.add(recipe);
        }
        return usable;
    }

    /** A recipe whose only route to {@code key} never actually fires is not a way of making it. */
    private static boolean isDeadEnd(MfpRecipe recipe, MfpKey key) {
        return Unavailability.isDeadEnd(recipe, key);
    }

    private static Set<MfpKey> producedBy(Plan plan) {
        Set<MfpKey> produced = new LinkedHashSet<>();
        for (Line line : plan.allLines()) {
            line.recipe().outputs().forEach(output -> produced.add(output.key()));
        }
        return produced;
    }

    /** One expansion run. Holds the graph being built and the walk's current path. */
    private final class Expansion {

        private final Plan plan;
        private final Set<String> extraBlacklist;
        /** Items an earlier round found no route to, and the blacklisted item each of them blames. */
        private final Map<MfpKey, MfpKey> unreachable;
        private final Map<MfpKey, MfpRecipe> chosen = new LinkedHashMap<>();
        private final Map<String, MfpRecipe> nodes = new LinkedHashMap<>();
        private final Map<String, Set<String>> dependsOn = new LinkedHashMap<>();
        private final Deque<String> path = new ArrayDeque<>();
        private final List<List<String>> cycles = new ArrayList<>();
        private final Set<MfpKey> unresolved = new LinkedHashSet<>();
        private final Set<MfpKey> rawMaterials = new LinkedHashSet<>();
        private final Set<MfpKey> truncated = new LinkedHashSet<>();
        private final Set<String> expanded = new LinkedHashSet<>();
        private final Map<MfpKey, String> importReasons = new LinkedHashMap<>();

        private final Map<MfpKey, MfpKey> blockedKeys = new LinkedHashMap<>();

        private Expansion(Plan plan, Set<String> extraBlacklist, Map<MfpKey, MfpKey> unreachable) {
            this.plan = plan;
            this.extraBlacklist = extraBlacklist;
            this.unreachable = unreachable;
        }

        /** Find or reuse a recipe for {@code key}, and recurse into its inputs. */
        private MfpRecipe resolve(MfpKey key, int depth) {
            // Energy, computation and the user's declared raw materials terminate the walk. Without
            // a cutoff, expanding a GregTech graph does not stop anywhere useful.
            //
            // Except at depth zero, where the key is one of the plan's own targets. "How do I make
            // water" is a question with an answer, and refusing to expand the very thing that was
            // asked for would hand back an empty plan. Being free is a reason not to go looking for
            // it, not a reason not to answer.
            //
            // A pinned recipe outranks both cutoffs below it (M11.2). "This item is free" and "here
            // is how I make it" are both the user's statements, and the second one is about this
            // plan and this item, so it is the more specific. Without this the picker could be
            // opened on a raw import — water, crushed ore, anything the walk stopped at — and the
            // recipe chosen there would do nothing at all, which is the one outcome a picker must
            // never have. Energy is still not expandable: it is not an item anyone makes a recipe
            // for, it is how the plan accounts for machines running.
            boolean answered = plan.recipeChoice(key) != null;
            if (key.isPseudo() || (!answered && depth > 0 && plan.rawMaterials().contains(key))) {
                rawMaterials.add(key);
                return null;
            }
            // An ore, in any of its forms, is where a chain ends. Star-Technology is a skyblock:
            // there is no ore block and no sand to mine, and crushed ore comes out of the pack's own
            // machines rather than out of the ground. Walking below crushed therefore invents a
            // chain nobody can build, which is how a tin plan came to import raw cassiterite sand.
            // Stopping here answers "make me tin" with "feed it crushed tin ore", which is the
            // shape of the answer a player wants; the picker still offers the deeper chain, and the
            // plan settings still take the item back off the list.
            if (!answered && depth > 0 && isOreForm(key)) {
                rawMaterials.add(key);
                return null;
            }
            if (depth > MAX_DEPTH) {
                truncated.add(key);
                return null;
            }
            // Hand-built plans stop here (M11.3): below the target, an input the user has not
            // answered is an import, and answering it is what adds the next line. Recorded with a
            // reason, because the alternative message — "nothing in the index produces this" — is a
            // claim about the pack, and here it would be a claim about a setting.
            //
            // A standing default counts as answered. "This is how I make steel" is the same kind of
            // statement as a pin — the user's, about this item — differing only in being made once
            // rather than per plan, and hand mode exists to keep the scorer's guesses out, not the
            // user's own decisions. So the walk follows a defaulted chain as far as the defaults go
            // and stops where the next answer would have to be invented, which leaves the import
            // list as exactly the set of things they have never decided.
            if (!plan.autoResolve() && !answered && depth > 0 && standingDefault(key) == null) {
                unresolved.add(key);
                if (!index.producing(key).isEmpty()) {
                    importReasons.put(key, "auto-resolve is off - pick a recipe to make it here");
                }
                return null;
            }

            MfpRecipe recipe = chosen.get(key);
            if (recipe == null) {
                MfpRecipe ancestor = ancestorProducing(key);
                if (ancestor != null) {
                    // Close the loop rather than building a second source for something this very
                    // branch already makes. See the method's own note for why it is the answer.
                    cycles.add(cycleFrom(ancestor.id()));
                    return ancestor;
                }
                MfpRecipe sibling = chosenProducing(key);
                if (sibling != null) {
                    chosen.put(key, sibling);
                    return sibling;
                }
                recipe = pick(key);
                if (recipe == null) {
                    unresolved.add(key);
                    return null;
                }
                chosen.put(key, recipe);
            }

            if (path.contains(recipe.id())) {
                // A real loop: this recipe is already being expanded further up the path.
                cycles.add(cycleFrom(recipe.id()));
                return recipe;
            }

            nodes.putIfAbsent(recipe.id(), recipe);
            dependsOn.computeIfAbsent(recipe.id(), id -> new LinkedHashSet<>());

            // Recipes are expanded once; revisiting only adds edges, which the caller does.
            if (!expanded.add(recipe.id())) {
                return recipe;
            }

            path.push(recipe.id());
            for (MfpIngredient input : recipe.inputs()) {
                if (!input.consumed() || input.effectiveAmount() <= 0) {
                    continue;
                }
                MfpRecipe producer = resolve(chosenCandidate(input), depth + 1);
                if (producer != null && !producer.id().equals(recipe.id())) {
                    dependsOn.get(recipe.id()).add(producer.id());
                }
            }
            path.pop();
            return recipe;
        }

        /**
         * Start from a plan that has already been chosen, so this walk only adds to it (M18).
         *
         * <p>Three things are seeded and each of them says something.
         *
         * <ul>
         *   <li><b>Every line is a node, already expanded</b>, so nothing here re-walks a chain
         *       another pass settled. This walk exists to place sinks, not to have opinions.
         *   <li><b>Every guaranteed output answers for its item.</b> A sink whose other ingredient
         *       is something the plan already makes is plumbed into that line rather than given a
         *       source of its own — §11.4's sibling rule, taken here for the same reason and with
         *       the same restriction: being handed a 5% byproduct is not being supplied.
         *   <li><b>The edges are read off what the lines consume</b>, rather than being the edges
         *       the original walk traversed, which this does not have. That is a superset — every
         *       edge the walk drew is a consumption — so a topological order under it is still a
         *       correct order. Where the superset closes a cycle the walk did not have, {@link
         *       #order()} drains early and appends in insertion order, which is the settled plan's
         *       own order, so the worst case is no change.
         * </ul>
         */
        private void seedFrom(ChooserResult settled) {
            for (Line line : settled.lines()) {
                MfpRecipe recipe = line.recipe();
                nodes.put(recipe.id(), recipe);
                expanded.add(recipe.id());
                dependsOn.computeIfAbsent(recipe.id(), id -> new LinkedHashSet<>());
                for (MfpOutput output : recipe.outputs()) {
                    if (!output.isChanced() && output.amount() > 0) {
                        chosen.putIfAbsent(output.key(), recipe);
                    }
                }
            }
            for (Line consumer : settled.lines()) {
                for (Line producer : settled.lines()) {
                    if (consumer == producer) {
                        continue;
                    }
                    for (MfpOutput output : producer.recipe().outputs()) {
                        if (consumer.recipe().consumes(output.key())) {
                            dependsOn.get(consumer.recipe().id()).add(producer.recipe().id());
                            break;
                        }
                    }
                }
            }
        }

        /**
         * Put a line on the plan whose job is to eat {@code surplus} (M18).
         *
         * <p>The mirror of {@link #resolve}, and the differences are the whole of the milestone.
         *
         * <ul>
         *   <li><b>The surplus is not resolved.</b> It is already being made — that is what makes it
         *       a surplus — so asking the index what produces it would build a second source for
         *       something the plan is throwing away, which is the opposite of the request. The
         *       ingredient is skipped and an edge is drawn to the line that is already making it, so
         *       the sink is ordered above its supplier like any other consumer.
         *   <li><b>Every other ingredient is an ordinary demand</b>, expanded from depth one, so the
         *       raw cutoff, the ore cutoff and hand mode all apply to it. A sink that needs oxygen
         *       raises the same question a line that needs oxygen raises, and it should raise it in
         *       the same place.
         *   <li><b>A stale sink is dropped in silence.</b> A recipe the pack has removed, or one
         *       that turns out not to eat this item, adds nothing; the codec has already reported
         *       the first case by name, and the second cannot be reached from the picker.
         * </ul>
         */
        private void consume(MfpKey surplus, String recipeId) {
            MfpRecipe recipe = index.recipe(recipeId);
            if (recipe == null || !recipe.consumes(surplus) || nodes.containsKey(recipeId)) {
                return;
            }
            nodes.put(recipeId, recipe);
            dependsOn.computeIfAbsent(recipeId, id -> new LinkedHashSet<>());
            expanded.add(recipeId);

            MfpRecipe supplier = nodeProducing(surplus);
            if (supplier != null && !supplier.id().equals(recipeId)) {
                dependsOn.get(recipeId).add(supplier.id());
            }

            path.push(recipeId);
            for (MfpIngredient input : recipe.inputs()) {
                if (!input.consumed() || input.effectiveAmount() <= 0
                        || input.candidates().contains(surplus)) {
                    continue;
                }
                MfpRecipe producer = resolve(chosenCandidate(input), 1);
                if (producer != null && !producer.id().equals(recipeId)) {
                    dependsOn.get(recipeId).add(producer.id());
                }
            }
            path.pop();
        }

        /** A line already on the plan that makes {@code key}, chanced outputs included. */
        private MfpRecipe nodeProducing(MfpKey key) {
            for (MfpRecipe candidate : nodes.values()) {
                if (candidate.produces(key)) {
                    return candidate;
                }
            }
            return null;
        }

        /**
         * A recipe already being expanded further up this branch that makes {@code key} anyway.
         *
         * <p>The shape this exists for: growing a tree consumes carbon dioxide and gives off oxygen,
         * and one way of making carbon dioxide is to burn charcoal in oxygen. Expanding that input
         * the ordinary way asks "what makes oxygen?" of the whole index, and the answer it finds is
         * electrolysing acidic bromine exhaust — so a two-recipe loop the player can actually build
         * became a thirty-seven line plan dragging in brine, platinum group sludge and aqua regia,
         * none of which the plan had any reason to make. The matrix engine then had more unknowns
         * than items and could not solve it at all.
         *
         * <p>The rule is deliberately restricted to <em>ancestors</em> rather than to every line
         * chosen so far. An ancestor producing this input means the branch is already a loop: the
         * consumer exists because the ancestor demanded its output, and the ancestor hands back the
         * input in exchange. Sourcing it from anywhere else is inventing a chain to supply something
         * the plan is already handing over. Any line in the plan would be a much broader claim —
         * that a stray byproduct anywhere is a supply for any demand — and a plan needing a thousand
         * ash a second would then be silently plumbed into a line that makes a trickle of it and
         * scaled to absurdity by the matrix engine, so the wider rule is not taken.
         *
         * <p>Nearest ancestor first, which is the tightest loop and the one the player is looking at.
         */
        private MfpRecipe ancestorProducing(MfpKey key) {
            for (String id : path) {            // ArrayDeque push/iterate is innermost-first
                MfpRecipe candidate = nodes.get(id);
                if (candidate != null && candidate.produces(key)) {
                    return candidate;
                }
            }
            return null;
        }

        /**
         * Whether picking {@code candidate} would close a loop that something feeds (M13 item 5).
         *
         * <p>The scorer's two loop terms fire on the shape of a cycle without ever having one; this
         * is where the cycle actually exists. If one of the candidate's inputs is made by an
         * ancestor on the current path, then choosing it does not start a chain - {@link
         * #ancestorProducing} will close the loop the moment the walk reaches that input - and the
         * loop is exactly the run of the path from that ancestor inwards, plus the candidate.
         *
         * <p>What separates a productive loop from a unit conversion is whether anything enters it.
         * Nine nuggets make an ingot and an ingot makes nine nuggets: every input of the pair comes
         * out of the pair, so the loop carries no material and produces none, and it is the shape
         * both penalties were written for. The pack's tree loop takes water at one end and charcoal
         * at the other and hands back logs; it is fed, and following it round is how the factory
         * runs. So the question asked of the cycle is the one that tells them apart.
         *
         * <p>Nearest ancestor only, because that is the loop that would be closed. A more distant
         * one would be a different and larger cycle, and the walk closes the tightest.
         */
        private boolean closesAFedCycle(MfpRecipe candidate, MfpKey producedKey) {
            MfpRecipe ancestor = null;
            for (MfpIngredient input : candidate.inputs()) {
                if (!input.consumed() || input.effectiveAmount() <= 0) {
                    continue;
                }
                ancestor = ancestorProducing(chosenCandidate(input));
                if (ancestor != null) {
                    break;
                }
            }
            if (ancestor == null || ancestor.produces(producedKey)) {
                // No loop to close; or the ancestor already makes the very thing being asked for,
                // which is not a loop either - it is the sibling case, and it has its own rule.
                return false;
            }

            List<MfpRecipe> cycle = new ArrayList<>();
            cycle.add(candidate);
            for (String id : path) {            // innermost first, down to the ancestor inclusive
                MfpRecipe onPath = nodes.get(id);
                if (onPath != null) {
                    cycle.add(onPath);
                }
                if (id.equals(ancestor.id())) {
                    break;
                }
            }
            return RecipeChooser.fedFromOutside(cycle);
        }

        /**
         * A line already in this plan that makes {@code key} too, in a byproduct-feeding round.
         *
         * <p>This is the sibling half of {@link #ancestorProducing}, and the pack is what argued for
         * it. Water electrolysis makes hydrogen and oxygen; so does decomposing hydroxide. A walk
         * that meets oxygen on one branch and hydrogen on another picks a recipe for each — because
         * {@code chosen} is keyed by item — and the plan ends up with both electrolysers, each of
         * them over-producing the other's product. The solver then has two sources for both gases,
         * cannot decide between them, and reports lines running backwards.
         *
         * <p>{@code §6d.28} rejected exactly this rule at the time, and its objection was about
         * scale: a plan wanting a thousand ash a second would be plumbed into a line making a
         * trickle, and the matrix engine would scale that line to absurdity to balance it. M10
         * answers that — the simplex engine takes what there is and imports the rest — and the
         * byproduct pass's own acceptance test is the second guard: a round is kept only if the plan
         * came out no larger and no hungrier. So the rule is taken now, and only inside a feeding
         * round, where both of those are true.
         *
         * <p><b>Guaranteed outputs only.</b> Being handed a 5% byproduct is not being supplied.
         */
        private MfpRecipe chosenProducing(MfpKey key) {
            if (!feedingRound) {
                return null;
            }
            for (MfpRecipe candidate : nodes.values()) {
                if (candidate.id().equals(dedicatedFor.get(key))) {
                    // The line stays; what is withdrawn is its use as the answer to this demand.
                    continue;
                }
                for (MfpOutput output : candidate.outputs()) {
                    if (output.key().equals(key) && !output.isChanced() && output.amount() > 0) {
                        return candidate;
                    }
                }
            }
            return null;
        }

        /**
         * Which of an ambiguous ingredient's candidates to expand.
         *
         * <p>A tag input is genuinely ambiguous, so the user's pin wins if they made one. Otherwise
         * the first candidate is taken and the ambiguity is preserved on the ingredient itself for
         * the picker to surface — the index must not silently narrow it.
         */
        private boolean unavailable(MfpKey key) {
            return Unavailability.causeOf(key, blacklistRule(plan), unreachable) != null;
        }

        private MfpKey chosenCandidate(MfpIngredient input) {
            for (MfpKey candidate : input.candidates()) {
                if (plan.preferredItems().contains(candidate) && !unavailable(candidate)) {
                    return candidate;
                }
            }
            // The standing preference below the plan's own, because that is what "default" means.
            for (MfpKey candidate : input.candidates()) {
                if (preferences.preferredItems().contains(candidate) && !unavailable(candidate)) {
                    return candidate;
                }
            }
            for (MfpKey candidate : input.candidates()) {
                if (unavailable(candidate)) {
                    continue;
                }
                if (plan.recipeChoice(candidate) != null || chosen.containsKey(candidate)) {
                    return candidate;
                }
            }
            // A blocked primary with an unblocked alternative takes the alternative: the recipe is on
            // the plan precisely because it was still satisfiable, and expanding the blocked item
            // would plan a chain the player just said they cannot run.
            for (MfpKey candidate : input.candidates()) {
                if (!unavailable(candidate)) {
                    return candidate;
                }
            }
            return input.primary();
        }

        /** Whether this is one of the plan's own targets, which the tier ceiling does not judge. */
        private boolean isTarget(MfpKey key) {
            for (TargetOutput target : plan.targets()) {
                if (target.key().equals(key)) {
                    return true;
                }
            }
            return false;
        }

        private MfpRecipe pick(MfpKey key) {
            String pinned = plan.recipeChoice(key);
            // Deliberately not checking extraBlacklist: a pin outranks the loop-avoidance pass, and
            // the pass is not supposed to put a pinned recipe in there in the first place.
            if (pinned != null) {
                MfpRecipe recipe = index.recipe(pinned);
                // A pin does not survive the item it needs being blacklisted. Blacklisting is the
                // more recent statement and the stronger one — "I have no supply of this" is a fact
                // about the save, where a pin is a preference about a route — and honouring the pin
                // anyway would make blacklisting an item appear to do nothing on exactly the lines
                // the user was looking at when they did it.
                if (recipe != null && recipe.produces(key)
                        && blockedInput(recipe, plan, unreachable) == null) {
                    return recipe;
                }
            }
            // The player's standing way of making this, below their pin and above the scorer. It is
            // checked against the plan's own exclusions rather than trusted outright: a default that
            // survived being hidden or blocked in this plan would make those buttons do nothing.
            MfpRecipe standing = standingDefault(key);
            if (standing != null) {
                return standing;
            }
            // The ceiling applies everywhere, including to a target - and a target that it leaves
            // with nothing keeps its unfiltered list.
            //
            // Exempting the target outright was the first attempt and it was too broad. "How do I
            // make nitrogen plasma" is a question with an answer and refusing the very thing that
            // was asked hands back an empty plan, so the exemption has to exist; but a target has
            // *inputs*, and exempting those too let the pack's tungsten plan answer with
            // `start:arc_furnace/arc_iv_parallel_hatch` - melt down an IV parallel hatch you cannot
            // build - and report the hatch as an import. Two lines and 774,000 EU/s where the honest
            // answer is a chain. The scorer liked it because recycling one machine part yields six
            // metals at once; nothing but the ceiling was ever going to refuse it.
            //
            // So: filter, and fall back only when the filter leaves nothing. That is the same
            // "never give up a plan to keep the rule" clause expandAvoiding applies to the
            // blacklist, and it separates the two cases exactly - the plasma has no route at HV and
            // keeps its fusion reactor, marked; the tungsten has hundreds and takes one.
            //
            // A pin and a standing default were both consulted above this line, so both outrank the
            // ceiling too: the user's own statement about this plan and this item is more specific.
            List<MfpRecipe> candidates = usable(key, plan, extraBlacklist, unreachable, true);
            if (candidates.isEmpty() && isTarget(key)) {
                candidates = usable(key, plan, extraBlacklist, unreachable, false);
            }
            String withdrawn = dedicatedFor.get(key);
            if (withdrawn != null) {
                candidates = new ArrayList<>(candidates);
                candidates.removeIf(candidate -> candidate.id().equals(withdrawn));
            }
            List<RecipeScorer.Scored> ranked =
                    RecipeScorer.rank(key, candidates, chosenOutputs(), oracle);
            if (ranked.isEmpty()) {
                recordWhyNothingWorks(key);
                return null;
            }
            boolean closeRunnerUp = ranked.size() > 1
                    && ranked.get(0).score() - ranked.get(1).score() < RecipeScorer.MAX_LOOP_PENALTY;
            for (String reason : ranked.get(0).reasons()) {
                if (reason.startsWith(RecipeScorer.RATE_DECIDED)) {
                    rateDecidedAPick = true;
                }
                if (closeRunnerUp && (reason.equals(RecipeScorer.LOOP_IS_FED)
                        || reason.equals(RecipeScorer.CLOSES_A_FED_LOOP))) {
                    loopDecidedAPick = true;
                }
            }
            return ranked.get(0).recipe();
        }

        private MfpRecipe standingDefault(MfpKey key) {
            String defaultId = preferences.defaultRecipe(key);
            // Deliberately not checking extraBlacklist any more, for the reason a pin does not:
            // the avoidance pass is not supposed to put a standing default in there at all, and
            // when it did, the plan quietly rebuilt itself around a recipe the user never chose.
            // The plan's own blacklist still wins — that is this plan saying "not this one, here".
            if (defaultId == null || plan.blacklist().contains(defaultId)) {
                return null;
            }
            MfpRecipe recipe = index.recipe(defaultId);
            if (recipe == null || !recipe.produces(key)
                    || blockedInput(recipe, plan, unreachable) != null) {
                // A pack update that removed the recipe, or a plan that blocked one of its inputs.
                // Falling through to the scorer is the same leniency a stale pin gets (PlanStore).
                return null;
            }
            return recipe;
        }

        /**
         * Why an item has to be imported, whenever the answer is anything other than the pack.
         *
         * <p>{@code ChooserResult} says "nothing in the index produces X" for every unresolved key
         * that has no reason recorded here, and that sentence is a claim about the pack. It was
         * being made about items the pack plainly makes — anything whose recipes MFP itself had
         * removed from consideration, whether the user hid them, blocked an input, or the
         * loop-avoidance pass banned them. A planner that misreports its own decision as a fact
         * about the world sends the user looking for a recipe that is right there (plan P4, P5).
         *
         * <p>So every exclusion this class applies is accounted for. The blocked-item case
         * additionally feeds the next round, because "every way to make this needs something you
         * have none of" makes the item itself unavailable.
         */
        private void recordWhyNothingWorks(MfpKey key) {
            List<MfpRecipe> producers = index.producing(key);
            if (producers.isEmpty()) {
                // The honest case, and the only one the default message describes.
                return;
            }

            MfpKey offender = null;
            int blocked = 0;
            int hidden = 0;
            int avoided = 0;
            int overTier = 0;
            String tierReason = null;
            // A target only reaches here having found nothing at all, filtered or not, so the
            // tier is as much a reason for it as for anything else.
            boolean underCeiling = true;
            for (MfpRecipe candidate : producers) {
                MfpKey found = blockedInput(candidate, plan, unreachable);
                if (found != null) {
                    offender = found;
                    blocked++;
                } else if (plan.blacklist().contains(candidate.id())) {
                    hidden++;
                } else if (extraBlacklist.contains(candidate.id())) {
                    avoided++;
                } else if (underCeiling) {
                    String beyond = beyondCeiling(candidate, plan);
                    if (beyond != null) {
                        // The first is kept rather than the last: producers come out of the index
                        // cheapest-looking first, and the reason a reader wants is the one for the
                        // recipe they would otherwise have expected to see.
                        tierReason = tierReason == null ? beyond : tierReason;
                        overTier++;
                    }
                }
            }
            // Every reason, not the winner of a ranking. Four decisions can each account for part
            // of why an item has no route left, and picking one to print was defensible while
            // there were three of them and they rarely coincided. The ceiling changed that: it
            // refuses more recipes at once than the other three together, so any ranking that put
            // it first would hide "you hid it" behind it, and any ranking that put it last would
            // hide the finding this milestone exists to surface. Saying all of them costs a
            // clause and settles the question.
            List<String> reasons = new ArrayList<>(3);
            if (offender != null) {
                reasons.add(blocked + " recipe(s) for it need " + offender
                        + ", which you have blacklisted");
                // Remembered so the next round can treat this item as unavailable in its own right:
                // if the only ways to make it need a blacklisted item, then so does it, and a route
                // above that wanted it should be looking elsewhere.
                //
                // The tier case below deliberately does NOT do this. That set feeds the next
                // round's blacklist map, and a tier refusal is not a blacklist refusal - it would
                // come back out reported as "which you have blacklisted". The ceiling propagates
                // through its own closure instead, which knows the answer for every item before the
                // walk starts.
                blockedKeys.put(key, offender);
            }
            if (overTier > 0) {
                int unlock = ceiling(plan).unlockTier(key);
                reasons.add(overTier + " recipe(s) for it are above the tier you build at - "
                        + tierReason
                        + (unlock < 0 ? "; no tier makes it" : "; the nearest tier that can is "
                                + unlock + " (" + GtTiers.name(unlock) + ")")
                        + "; pin one, or /mfp ceiling off");
            }
            if (avoided > 0) {
                reasons.add(avoided + " recipe(s) for it were passed over to keep the "
                        + "plan acyclic - pin one to use it anyway");
            }
            if (hidden > 0) {
                reasons.add("you hid " + hidden + " recipe(s) for it");
            }
            if (!reasons.isEmpty()) {
                importReasons.put(key, String.join("; also ", reasons));
            }
        }

        private Set<MfpKey> chosenOutputs() {
            Set<MfpKey> produced = new LinkedHashSet<>();
            nodes.values().forEach(recipe -> recipe.outputs().forEach(o -> produced.add(o.key())));
            return produced;
        }

        private List<String> cycleFrom(String recipeId) {
            List<String> cycle = new ArrayList<>();
            for (String onPath : path) {
                cycle.add(0, onPath);
                if (onPath.equals(recipeId)) {
                    break;
                }
            }
            cycle.add(recipeId);
            return cycle;
        }

        private Attempt finish() {
            List<Line> lines = new ArrayList<>();
            Set<MfpKey> preferred = preferences.preferredItemsFor(plan);
            for (MfpRecipe recipe : order()) {
                MachineConfig machine = MachinePicker.pick(index, recipe, plan, preferences, ceiling(plan));
                // The user's choice of item for an ambiguous input is baked into the line's own copy
                // of the recipe rather than carried alongside it, so the solver reads it without
                // knowing it exists. Anything else risks a plan that expands wood pulp and demands
                // wood chips.
                lines.add(new Line(recipe.withPreferredInputs(withUnblocked(recipe, preferred)),
                        machine));
            }
            return new Attempt(new ChooserResult(lines, List.copyOf(cycles), List.copyOf(unresolved),
                    List.copyOf(rawMaterials), List.copyOf(truncated), List.of(),
                    Map.copyOf(importReasons)), Map.copyOf(blockedKeys));
        }

        /**
         * The preferred items, plus a substitute for any input whose first candidate is blocked.
         *
         * <p>The line's copy of the recipe is what the solver reads, so an ingredient still fronted
         * by a blocked item would have the plan demanding the very thing the player said they have
         * none of — while the walk, which consults {@link #chosenCandidate}, had already expanded the
         * alternative. Applied last so it wins, and only where it changes something.
         */
        private Set<MfpKey> withUnblocked(MfpRecipe recipe, Set<MfpKey> preferred) {
            Set<MfpKey> keys = null;
            for (MfpIngredient input : recipe.inputs()) {
                if (!input.isAmbiguous() || !unavailable(input.primary())) {
                    continue;
                }
                MfpKey substitute = chosenCandidate(input);
                if (!unavailable(substitute)) {
                    if (keys == null) {
                        keys = new LinkedHashSet<>(preferred);
                    }
                    keys.add(substitute);
                }
            }
            return keys == null ? preferred : keys;
        }

        /**
         * Topological order: every recipe before the recipes it depends on.
         *
         * <p>Kahn's algorithm over "who consumes me" counts. When a cycle is present the queue
         * drains early, so the remainder is appended in discovery order — a plan with a loop cannot
         * be ordered correctly by definition, and it is already flagged for the matrix engine.
         */
        private List<MfpRecipe> order() {
            Map<String, Integer> consumers = new LinkedHashMap<>();
            nodes.keySet().forEach(id -> consumers.put(id, 0));
            for (Set<String> producers : dependsOn.values()) {
                for (String producer : producers) {
                    consumers.computeIfPresent(producer, (id, count) -> count + 1);
                }
            }

            Deque<String> ready = new ArrayDeque<>();
            nodes.keySet().stream().filter(id -> consumers.get(id) == 0).forEach(ready::addLast);

            List<MfpRecipe> ordered = new ArrayList<>(nodes.size());
            Set<String> emitted = new LinkedHashSet<>();
            while (!ready.isEmpty()) {
                String id = ready.removeFirst();
                if (!emitted.add(id)) {
                    continue;
                }
                ordered.add(nodes.get(id));
                for (String producer : dependsOn.getOrDefault(id, Set.of())) {
                    Integer left = consumers.computeIfPresent(producer, (key, count) -> count - 1);
                    if (left != null && left == 0) {
                        ready.addLast(producer);
                    }
                }
            }

            for (Map.Entry<String, MfpRecipe> entry : nodes.entrySet()) {
                if (emitted.add(entry.getKey())) {
                    ordered.add(entry.getValue());
                }
            }
            return ordered;
        }
    }
}
