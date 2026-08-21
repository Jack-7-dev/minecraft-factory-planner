package dev.mfp.core.select;

import dev.mfp.core.model.MaterialForm;
import dev.mfp.core.model.MaterialForms;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Ranks the recipes that could make an item.
 *
 * <p>Separate from both the solver and the expansion walk, because "which recipe?" is a policy
 * question and the maths must not depend on the answer (plan P6). The scorer's job is to produce a
 * sensible <em>default</em> and a ranked list; the user picks from that list and their choice
 * always wins.
 *
 * <p>The weights encode what a player actually wants when they ask "how do I make this?":
 *
 * <ul>
 *   <li><b>Make it on purpose.</b> A recipe where the item is a guaranteed main product beats one
 *       where it falls out as a 5% byproduct — otherwise "how do I get copper?" answers "macerate
 *       ore for the tin byproduct".
 *   <li><b>Keep the chain shallow.</b> Fewer distinct inputs means less to expand underneath.
 *   <li><b>Stay buildable.</b> Lower tier first, and recipes with unmet research or a cleanroom
 *       requirement are pushed down rather than hidden — the user may well have met them.
 *   <li><b>Avoid loops.</b> A recipe consuming something the plan already produces is a cycle risk,
 *       and the sequential engine cannot resolve cycles.
 * </ul>
 *
 * <p>Scores are relative and only meaningful within one comparison; nothing outside this class
 * should read the number.
 */
public final class RecipeScorer {

    /**
     * Per step of the refinement ladder.
     *
     * <p>Asymmetric on purpose. Refining is what most recipes do, so the bonus is a tie-breaker;
     * going backwards is the thing being ruled out, so the penalty has to outweigh the terms a
     * recycling recipe scores well on — one input, guaranteed, main product, low tier, which comes
     * to roughly +55. A manufactured input against an ingot output is three steps, so 45.
     */
    private static final double REFINEMENT_BONUS_PER_STEP = 6;
    private static final double REFINEMENT_PENALTY_PER_STEP = 15;

    /**
     * Per tier of the cheapest machine that can run the recipe.
     *
     * <p>Smaller than the recipe's own tier penalty on purpose. Running a recipe at a high tier is a
     * recurring cost the plan pays in power for as long as it runs; building a high-tier machine is
     * paid once. It is enough to separate two otherwise identical recipes by six or seven tiers of
     * machine, which is the case it exists for, without overturning a genuinely better recipe that
     * happens to want a bigger machine.
     */
    private static final double BUILD_COST_PER_TIER = 3;

    /**
     * Recipe metadata marking a machine that consumes fuel to run, set by the vanilla provider.
     *
     * <p>Named here rather than imported, because {@code core} cannot see the provider that sets it —
     * and should not: any provider may mark a recipe this way.
     */
    public static final String BURNS_FUEL = "mfp:burns_fuel";

    /** Enough to lose a close call to an electric equivalent, not enough to hide the recipe. */
    private static final double FUEL_BURNER_PENALTY = 12;

    /**
     * Per step up the ore-source ladder — crushed over geode over raw over a plain ore block.
     *
     * <p>Sized to beat the refinement term, which pulls the other way and has to. Refinement scores
     * ore → ingot as three steps and crushed → ingot as two, a difference of {@code 2 ×
     * REFINEMENT_BONUS_PER_STEP} in the ore block's favour; this has to overcome that and then
     * decide, so the crushed route wins by a clear margin rather than by a rounding error.
     */
    private static final double ORE_SOURCE_PER_STEP = 12;

    /**
     * For consuming the ore of the very material being produced.
     *
     * <p>Only ever breaks a tie between recipes that are otherwise the same shape, which is exactly
     * the situation it exists for — so it is small, and it never outranks a term that means
     * something about how the recipe behaves.
     */
    private static final double SAME_MATERIAL_ORE_BONUS = 10;

    /**
     * A recipe every consumed input of which the plan already treats as raw is the end of a chain.
     *
     * <p>Close to the strongest thing a planner can know about a candidate: there is nothing left to
     * build underneath it, so choosing it finishes the branch instead of opening more of them.
     * Star-Technology's stone barrel is exactly this shape — water in, cobblestone out, with a lava
     * catalyst it does not consume — and it lost by 23 points to a recipe that made cobblestone from
     * stone and sent expansion off into brine chemistry, which is where this number comes from: big
     * enough to settle that comparison, small enough not to outrank {@code guaranteed output}.
     */
    private static final double TERMINAL_BONUS = 30;

    /**
     * Eating something the plan is already throwing away (M11.1).
     *
     * <p>Deliberately smaller than {@link #TERMINAL_BONUS} and much smaller than the structural
     * penalties. A byproduct feed is an *opportunity*, not a fact about the recipe: it is worth
     * taking where the candidates are otherwise close, and it is not worth reaching for a recycling
     * recipe or a reversible conversion to get. Where it is not enough to move the pick, the plan
     * simply keeps both sources and the whole-plan engine balances them, which since M10 is a
     * perfectly good outcome — the byproduct is used first and the rest is made or imported.
     */
    private static final double BYPRODUCT_BONUS = 20;

    /**
     * Per halving of throughput against the fastest candidate for the same item.
     *
     * <p>Applied per doubling because rate is a ratio, not a difference: 2/s against 1/s is the same
     * comparison as 2000 mB/s against 1000 mB/s, and every other term in this class is a small
     * whole number, so a raw rate would swamp all of them on any fluid.
     *
     * <p>Sized to lose a close call and not to overturn a structural one. Four points a halving puts
     * a route eight times slower thirteen points down — enough to settle two recipes that differ only
     * in how their batch is written, which is the case §13a M13 item 2 names, and nowhere near
     * {@link #TERMINAL_BONUS} or the recycling penalty, which say something about whether the recipe
     * is a way of obtaining the item at all. A slow recipe is still a way of obtaining the item; it
     * just needs more machines.
     */
    private static final double THROUGHPUT_PER_HALVING = 4;

    /**
     * The floor on that term, at sixty-four times slower.
     *
     * <p>Without it the ratio is unbounded — a 5% byproduct of a two-minute recipe against a
     * dedicated one is thousands of times slower — and one term running to hundreds of points would
     * make every other judgement in this class decorative. Past six halvings the answer is already
     * "much slower", and how much past does not change the ranking.
     */
    private static final double MAX_THROUGHPUT_PENALTY = 24;

    /** A candidate and why it scored what it did. */
    public record Scored(MfpRecipe recipe, double score, List<String> reasons)
            implements Comparable<Scored> {

        @Override
        public int compareTo(Scored other) {
            int byScore = Double.compare(other.score, score);
            return byScore != 0 ? byScore : recipe.id().compareTo(other.recipe.id());
        }
    }

    /**
     * The two questions the scorer cannot answer from a recipe alone.
     *
     * <p>Injected rather than computed here because both need the index, and the scorer is otherwise
     * pure and trivially testable. {@link #NONE} answers no to both, which is what a caller without
     * an index gets.
     */
    public interface Oracle {

        /** Answers no to everything; used by tests and by callers with no index. */
        Oracle NONE = new Oracle() {};

        /** Whether the recipe merely undoes the thing it is supposed to produce. */
        default boolean isReversible(MfpRecipe recipe, MfpKey producedKey) {
            return false;
        }

        /** Whether the recipe is recycling something used, rather than making the item. */
        default boolean isRecycling(MfpRecipe recipe) {
            return false;
        }

        /**
         * Whether this plan treats the item as something that comes from outside the factory.
         *
         * <p>The plan's declared raw set, never an inference. That distinction is the whole reason
         * this question is safe to ask where §8.3's distance-to-raw metric was not: that one decided
         * an item was raw because nothing in the index produced it, so a worn tool part counted as
         * depth zero and every honest deep chain was punished for being honest. A declared raw set
         * never contains a tool, because a person put each entry in it.
         */
        default boolean isRaw(MfpKey key) {
            return false;
        }

        /**
         * Whether the plan already gives this item off and nothing in it wants the item (M11.1).
         *
         * <p>Answerable only *between* expansions, which is why byproduct feeding is a second pass
         * rather than a scorer term on its own: the walk is greedy and depth-first, so when it picks
         * a carbon dioxide recipe it cannot yet know that a greenhouse further along will be giving
         * off the oxygen one of the candidates wants. The pass supplies the answer; the scoring is
         * where it gets used.
         */
        default boolean isSpareByproduct(MfpKey key) {
            return false;
        }

        /**
         * Whether choosing this recipe would close a loop that something feeds (M13 item 5).
         *
         * <p>The question the other two loop terms have been answering by assumption. Consuming
         * something the plan already makes is cycle risk, and a recipe whose input can be made from
         * its own output is a reversal - but both of those are statements about a <em>cycle</em>,
         * and neither of them has ever seen one. They fire on the shape a cycle would have, which
         * is why the pack's own tree loop scored like a nugget-to-ingot conversion: the greenhouse
         * turns carbon dioxide into oxygen, so every recipe making carbon dioxide out of oxygen
         * looks like its reverse.
         *
         * <p>The two are different in one respect that can be checked. A unit conversion is
         * <em>closed</em> - nine nuggets make an ingot and an ingot makes nine nuggets, and nothing
         * enters the pair from anywhere else, so following it round consumes nothing and yields
         * nothing. A productive loop is <em>fed</em>: the greenhouse takes water and the carbon
         * dioxide line takes charcoal, and the loop exists to carry the oxygen between them while
         * the logs come out. So the test is not "is there a cycle" but "does the cycle eat anything
         * from outside itself", and a caller that has the walk's current path can answer it.
         *
         * <p>Answered from the path rather than from the index, which is the whole of item 5's
         * ordering problem: the cycle a pick would create is only knowable where the pick is being
         * made. A caller with no walk in progress answers no, and both penalties stand as before.
         */
        default boolean closesAFedCycle(MfpRecipe recipe, MfpKey producedKey) {
            return false;
        }

        /** What form an item is, or null when the game says nothing about it. */
        default MaterialForm form(MfpKey key) {
            return null;
        }

        /**
         * How much of {@code key} one machine running this recipe makes per second.
         *
         * <p>The picker's rate column, asked of the scorer instead of the screen. "Makes 8 dust"
         * says nothing without the cycle it takes, and until this existed the scorer ranked the
         * recipe as written, so a bigger batch over a proportionally longer duration scored the
         * same as a genuine improvement (§13a M13 item 2).
         *
         * <p>It has to be answered by the caller because it depends on the machine the plan would
         * put the line on, which is a choice the scorer does not make and must not: this is the
         * coupling P6 permits — policy may depend on the maths, the maths may not depend on policy.
         *
         * @return the rate, or {@link #UNKNOWN_RATE} for hand crafting and anything else with no
         *         intrinsic rate to compare
         */
        default double outputPerSecond(MfpRecipe recipe, MfpKey key) {
            return UNKNOWN_RATE;
        }

        /**
         * How hard the easiest machine that runs this recipe is to build, as a voltage tier.
         *
         * @return the tier, 0 for something craftable by hand, or {@link #UNKNOWN_BUILD_COST} when
         *         nothing in the index says how the machine is obtained
         */
        default int buildCost(MfpRecipe recipe) {
            return UNKNOWN_BUILD_COST;
        }
    }

    /** No machine cost is known, so the term is skipped rather than guessed at. */
    public static final int UNKNOWN_BUILD_COST = -1;

    /** No rate is known, so the throughput term is skipped rather than guessed at. */
    public static final double UNKNOWN_RATE = -1;

    /**
     * The reason a winner carries when the throughput term is what won it, naming the runner-up.
     *
     * <p>A marker as much as a sentence: {@code RecipeChooser} reads it to decide whether this
     * expansion is worth doing twice.
     */
    public static final String RATE_DECIDED = "faster than the otherwise better ";

    /**
     * The reasons a winner carries when a loop term was withheld from it (M13 item 5).
     *
     * <p>Markers in the same sense as {@link #RATE_DECIDED}: {@code RecipeChooser} reads them to
     * decide whether this expansion is worth doing a second time with the penalties back in force.
     */
    public static final String LOOP_IS_FED = "looks reversible, but the loop is fed";

    /** @see #LOOP_IS_FED */
    public static final String CLOSES_A_FED_LOOP = "closes a loop the plan feeds";

    /** The largest penalty either loop term withholds, which bounds what a second walk can move. */
    public static final double MAX_LOOP_PENALTY = 60;

    private RecipeScorer() {}

    /**
     * Rank {@code candidates} as ways of producing {@code key}.
     *
     * @param alreadyProduced keys the plan already makes; consuming one risks a cycle
     */
    public static List<Scored> rank(MfpKey key, List<MfpRecipe> candidates, Set<MfpKey> alreadyProduced) {
        return rank(key, candidates, alreadyProduced, Oracle.NONE);
    }

    public static List<Scored> rank(MfpKey key, List<MfpRecipe> candidates,
                                    Set<MfpKey> alreadyProduced, Oracle oracle) {
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (MfpRecipe recipe : candidates) {
            scored.add(score(key, recipe, alreadyProduced, oracle));
        }
        scored = withThroughput(key, scored, oracle);
        scored.sort(null);
        return scored;
    }

    /**
     * The throughput term, which is the one judgement here that is not about a single recipe.
     *
     * <p>Rate has no absolute meaning - 1/s is fast for an ingot and a trickle for a fluid - so
     * "fast" can only mean "fast for this item", and the comparison is against the fastest candidate
     * offered for the same key. That is why this lives in {@link #rank} rather than in
     * {@link #score}: a per-candidate score cannot express it, and inventing an absolute scale would
     * be the same guess §8.3's distance-to-raw metric was withdrawn for.
     *
     * <p><b>A candidate with no rate is left alone rather than ranked last.</b> Hand crafting has no
     * duration and no machine, so the question does not apply to it; scoring it as infinitely slow
     * would bury every shaped recipe in the game behind whichever machine happens to make the item.
     * This is the same rule the refinement term takes for an unclassified item, for the same reason.
     */
    private static List<Scored> withThroughput(MfpKey key, List<Scored> scored, Oracle oracle) {
        if (scored.size() < 2) {
            // Nothing to be fast relative to, and the common case for an item with one recipe, so
            // it is worth not asking the oracle at all.
            return scored;
        }

        double[] rates = new double[scored.size()];
        double fastest = 0;
        int known = 0;
        for (int i = 0; i < scored.size(); i++) {
            double rate = oracle.outputPerSecond(scored.get(i).recipe(), key);
            rates[i] = rate > 0 && Double.isFinite(rate) ? rate : UNKNOWN_RATE;
            if (rates[i] > 0) {
                known++;
                fastest = Math.max(fastest, rates[i]);
            }
        }
        if (known < 2) {
            return scored;
        }

        Scored wonWithout = Collections.min(scored);
        List<Scored> adjusted = new ArrayList<>(scored.size());
        for (int i = 0; i < scored.size(); i++) {
            Scored one = scored.get(i);
            if (rates[i] <= 0 || rates[i] >= fastest) {
                adjusted.add(one);
                continue;
            }
            double ratio = fastest / rates[i];
            double halvings = Math.log(ratio) / Math.log(2);
            double penalty = Math.min(MAX_THROUGHPUT_PENALTY, THROUGHPUT_PER_HALVING * halvings);
            List<String> reasons = new ArrayList<>(one.reasons());
            reasons.add(times(ratio) + " slower than the fastest way to make this");
            adjusted.add(new Scored(one.recipe(), one.score() - penalty, List.copyOf(reasons)));
        }

        // Say so when the term is the whole reason for the answer, because the caller has to know.
        // Rate is the one judgement here that can be right about the recipe and wrong about the
        // factory - the fastest way to make steel takes wrought iron, and wrought iron in this pack
        // is arc-furnaced out of oxygen the plan then has to fuse nitrogen to obtain. So an
        // expansion that turns on this marker is expanded a second time without the term and the two
        // plans are costed against each other; nothing else in this class needs that treatment,
        // because nothing else in it changes an answer that the rest of the scoring had settled.
        Scored wonWith = Collections.min(adjusted);
        if (!wonWith.recipe().id().equals(wonWithout.recipe().id())) {
            List<String> reasons = new ArrayList<>(wonWith.reasons());
            reasons.add(RATE_DECIDED + wonWithout.recipe().id());
            adjusted.set(adjusted.indexOf(wonWith),
                    new Scored(wonWith.recipe(), wonWith.score(), List.copyOf(reasons)));
        }
        return adjusted;
    }

    /** "12x", or "1.5x" below ten, where rounding would say "1x" about a real difference. */
    private static String times(double ratio) {
        return ratio < 9.5 ? String.format("%.1fx", ratio) : Math.round(ratio) + "x";
    }

    public static Scored score(MfpKey key, MfpRecipe recipe, Set<MfpKey> alreadyProduced) {
        return score(key, recipe, alreadyProduced, Oracle.NONE);
    }

    public static Scored score(MfpKey key, MfpRecipe recipe, Set<MfpKey> alreadyProduced,
                               Oracle oracle) {
        List<String> reasons = new ArrayList<>();
        double score = 0;

        MfpOutput target = null;
        double bestAmount = 0;
        int chancedOutputs = 0;
        for (MfpOutput output : recipe.outputs()) {
            if (output.key().equals(key) && output.amount() > bestAmount) {
                target = output;
                bestAmount = output.amount();
            }
            if (output.isChanced()) {
                chancedOutputs++;
            }
        }

        if (target == null) {
            // Should not happen for a candidate drawn from the index, but scoring it lowest is
            // safer than throwing: a malformed recipe costs a rank, not the whole picker.
            return new Scored(recipe, -1000, List.of("does not produce " + key));
        }

        if (!target.isChanced()) {
            score += 50;
            reasons.add("guaranteed output");
        } else {
            // A 10% byproduct needs ten crafts per item; rank it far below a guaranteed source.
            score += 50 * target.chance();
            reasons.add("chanced output at " + Math.round(target.chance() * 100) + "%");
        }

        double targetAmount = target.amount();
        boolean primary = recipe.outputs().stream()
                .noneMatch(other -> other.amount() > targetAmount && !other.isChanced());
        if (primary) {
            score += 15;
            reasons.add("main product");
        }

        int inputs = recipe.inputs().size();
        score -= 3.0 * inputs;
        if (inputs <= 2) {
            reasons.add("few inputs");
        }

        if (recipe.minTier() >= 0) {
            score -= 4.0 * recipe.minTier();
            reasons.add("tier " + recipe.minTier());
        }

        // What it costs to *own* the machine, as opposed to what it costs to run it. Packs routinely
        // give one recipe to several machines — Star-Technology has a greenhouse and a fermenting
        // aroboreal rejuvenation monstrosity that produce the same thing from the same inputs — and
        // every other term in this method scores those identically, leaving the tie to be broken by
        // recipe id, which is to say alphabetically. Whichever machine is cheapest to build is the
        // one to offer first; the rest stay one click away in the picker.
        // A machine that burns fuel is the fallback, not the default. It is a real route and the
        // picker offers it, but in a pack with electric furnaces the vanilla one is what you use
        // before you have any, and its throughput is bounded by a fuel supply the planner is not
        // modelling. Small enough that it only decides otherwise-close calls.
        if (recipe.intExtra(BURNS_FUEL, 0) > 0) {
            score -= FUEL_BURNER_PENALTY;
            reasons.add("burns fuel");
        }

        int buildCost = oracle.buildCost(recipe);
        if (buildCost > 0) {
            score -= BUILD_COST_PER_TIER * buildCost;
            reasons.add("needs a tier " + buildCost + " machine built");
        }

        if (!recipe.conditions().isEmpty()) {
            score -= 12;
            reasons.add(recipe.conditions().size() + " unevaluated condition(s)");
        }

        int byproducts = Math.max(0, recipe.outputs().size() - 1);
        score -= 2.0 * byproducts;
        score -= 1.5 * chancedOutputs;

        // Consuming something the plan already makes is normally cycle risk, and the penalty is
        // not wrong: most GregTech loops are unit conversions that lead nowhere. But a *spare*
        // byproduct is the opposite case — the plan is throwing the item away — and the two were
        // being scored identically, so the scorer was actively steering away from the loops the
        // pack builds on purpose (STATUS §6d.28). They are separated here rather than the sign
        // flipped, because both readings are right about their own case.
        //
        // And a third case, which is neither: an input the plan already makes *and* that the walk
        // would be taking straight back off the line that makes it, in a loop something feeds
        // (M13 item 5). That is not a risk being run, it is a loop being closed, and the plan is
        // the better for it. Asked only where one of the other two readings would have applied, so
        // a recipe with nothing loop-shaped about it never pays for the question.
        int cycleRisk = 0;
        int byproductFeeds = 0;
        int fedLoops = 0;
        Boolean fedCycle = null;
        for (MfpIngredient input : recipe.inputs()) {
            if (!input.consumed()) {
                continue;
            }
            boolean spare = false;
            boolean produced = false;
            for (MfpKey candidate : input.candidates()) {
                spare |= oracle.isSpareByproduct(candidate);
                produced |= alreadyProduced.contains(candidate);
            }
            if (spare) {
                byproductFeeds++;
            } else if (produced) {
                if (fedCycle == null) {
                    fedCycle = oracle.closesAFedCycle(recipe, key);
                }
                if (fedCycle) {
                    fedLoops++;
                } else {
                    cycleRisk++;
                }
            }
        }
        if (cycleRisk > 0) {
            score -= 20.0 * cycleRisk;
            reasons.add("consumes " + cycleRisk + " item(s) the plan already makes");
        }
        if (fedLoops > 0) {
            reasons.add(CLOSES_A_FED_LOOP);
        }
        if (byproductFeeds > 0) {
            score += BYPRODUCT_BONUS * byproductFeeds;
            reasons.add("eats " + byproductFeeds + " byproduct(s) the plan has spare");
        }

        Integer refinement = refinementDelta(key, recipe, oracle);

        // The single most important term on a GregTech graph. "Nine steel nuggets make a steel
        // ingot" scores beautifully on every other criterion — one input, guaranteed, main product,
        // low tier — but it is a unit conversion, not a way of obtaining steel, and following it
        // leads straight into a loop. The test is structural rather than name-based: an input that
        // can itself be made from this recipe's own output means the two recipes undo each other.
        //
        // It is softened, not lifted, for a recipe that refines. Smelting a dust into an ingot is
        // reversible — a macerator turns the ingot straight back into dust — but it is not a unit
        // conversion, it is the ore chain's last step, and the full penalty is what kept every
        // honest aluminium route below the arc furnace's recycling recipes. Reversibility is really
        // about the rank-*neutral* pairs (nugget <-> ingot, block <-> ingot); where the ranks differ
        // the refinement term is the one with something to say, so the two overlap rather than one
        // silencing the other. Measured, not assumed: raising the refinement penalty instead let the
        // nugget conversion win outright (STATUS §6c.5).
        //
        // Unless the loop it would close is fed (M13 item 5). Reversibility is a claim that the two
        // recipes cancel, and two recipes that between them eat charcoal and water and hand back
        // logs do not cancel - they are the pack's tree loop, and the -60 was scoring it as though
        // it were a nugget.
        if (oracle.isReversible(recipe, key)) {
            if (fedCycle == null) {
                fedCycle = oracle.closesAFedCycle(recipe, key);
            }
            if (!fedCycle) {
                boolean refines = refinement != null && refinement > 0;
                score -= refines ? 25 : 60;
                reasons.add(refines ? "reversible, but it refines" : "reversible conversion");
            } else {
                reasons.add(LOOP_IS_FED);
            }
        }

        // GregTech generates a recycling recipe for every tool and machine in the game, and they
        // score well: one input, one guaranteed output, low tier. But "melt down a worn buzzsaw" is
        // not a way to obtain aluminium, and the plan it produces imports the buzzsaw and stops.
        if (oracle.isRecycling(recipe)) {
            score -= 70;
            reasons.add("recycles a used item");
        }

        // Which end of the ore chain to start from, which the refinement term gets backwards on its
        // own: ore -> ingot is the longer climb, so refinement alone prefers to begin at an ore
        // block, and in Star-Technology an ore block is the one form of the material that cannot be
        // obtained. Crushed ore comes out of the ground, geodes out of a rock filtrator, raw ore out
        // of the few vanilla-style veins, and the plain ore block out of nothing at all.
        // Of the eight ores that smelt to iron, the one called iron is the answer. GregTech gives
        // every composite ore — basaltic mineral sand, granitic mineral sand, cassiterite sand —
        // a recipe to the same metal, and they score identically to the material's own ore, so the
        // tie fell to the recipe id and picked whichever sorted first. Sand veins do not exist in a
        // skyblock; the material's own ore is both the obvious answer and the obtainable one.
        if (sharesMaterialWithAnOreInput(key, recipe, oracle)) {
            score += SAME_MATERIAL_ORE_BONUS;
            reasons.add("uses this metal's own ore");
        }

        int oreSource = bestOreSource(recipe, oracle);
        if (oreSource != MaterialForms.NOT_AN_ORE_SOURCE) {
            score += ORE_SOURCE_PER_STEP * oreSource;
            if (oreSource > 0) {
                reasons.add("starts from a form of the ore you can actually get");
            } else {
                reasons.add("starts from a plain ore block");
            }
        }

        if (isTerminal(recipe, oracle)) {
            score += TERMINAL_BONUS;
            reasons.add("everything it consumes is already raw");
        }

        if (refinement != null && refinement > 0) {
            score += REFINEMENT_BONUS_PER_STEP * refinement;
            reasons.add("refines its inputs");
        } else if (refinement != null && refinement < 0) {
            score += REFINEMENT_PENALTY_PER_STEP * refinement;
            reasons.add("consumes something more refined than it makes");
        }

        return new Scored(recipe, score, List.copyOf(reasons));
    }

    /**
     * Does this recipe refine its inputs, or un-refine them?
     *
     * <p>The third attempt at the problem §8.3 describes, and the first one that asks a question the
     * recipe can actually answer. The arc furnace can turn a manufactured item back into steel, but
     * that item had to be made from steel first, so a recipe consuming it is not a way to obtain
     * steel. Stated as forms — <b>a recipe that consumes something more refined than it produces is
     * going backwards</b> — the rule generalises and names no machine, which is what the two earlier
     * attempts could not manage.
     *
     * <p>Two decisions worth stating, because both could reasonably have gone the other way.
     *
     * <p><b>The minimum rank over the inputs</b>, not the largest input by amount. This is the
     * generous reading: one basic input redeems a recipe. It is the safer error, since the cost of
     * being wrong is failing to demote a recycling recipe, while the other reading risks demoting an
     * honest recipe that happens to use one refined component.
     *
     * <p><b>Either side unclassified means no term at all</b>, not a default rank. Vanilla coal,
     * sand and every mob drop have no material data, and both earlier attempts failed precisely by
     * assuming something about items the game says nothing about.
     *
     * @return how many steps up the ladder the recipe moves — negative for backwards — or null when
     *         either side is unclassified and the rule has nothing to say
     */
    /**
     * The best ore form this recipe consumes, or {@link MaterialForms#NOT_AN_ORE_SOURCE}.
     *
     * <p>The <em>best</em>, not the worst: a recipe taking both crushed ore and an ore block is
     * reachable by anyone who can reach the crushed ore, so it is judged by the form that makes it
     * reachable. Recipes consuming no ore at all — which is nearly all of them — get no term.
     */
    /**
     * Whether this recipe bottoms out: every input it consumes is already declared raw.
     *
     * <p>Inputs the recipe borrows and gives back are ignored, because a catalyst is not something
     * the plan has to obtain — the stone barrel's lava is the case that matters. A tag input counts
     * as raw when <em>any</em> of its candidates is, which is the same generous reading
     * {@link #refinementDelta} takes of its inputs and for the same reason: the plan will use the
     * candidate it can get.
     *
     * <p>A recipe consuming nothing at all does <b>not</b> qualify. It arguably terminates a chain
     * even more completely, but the class of recipes that consume nothing is large and strange —
     * world interactions, generators, mob farms — and folding them in would have made this term
     * unmeasurable against the one comparison it was built to settle.
     */
    private static boolean isTerminal(MfpRecipe recipe, Oracle oracle) {
        boolean consumesAnything = false;
        for (MfpIngredient input : recipe.inputs()) {
            if (!input.consumed()) {
                continue;
            }
            consumesAnything = true;
            boolean raw = false;
            for (MfpKey candidate : input.candidates()) {
                if (oracle.isRaw(candidate)) {
                    raw = true;
                    break;
                }
            }
            if (!raw) {
                return false;
            }
        }
        return consumesAnything;
    }

    /** Whether an ore this recipe consumes is made of the same material it produces. */
    private static boolean sharesMaterialWithAnOreInput(MfpKey key, MfpRecipe recipe, Oracle oracle) {
        MaterialForm produced = oracle.form(key);
        if (produced == null || produced.material() == null) {
            return false;
        }
        for (MfpIngredient input : recipe.inputs()) {
            if (!input.consumed()) {
                continue;
            }
            for (MfpKey candidate : input.candidates()) {
                MaterialForm form = oracle.form(candidate);
                if (form != null
                        && MaterialForms.oreSourceRank(form) != MaterialForms.NOT_AN_ORE_SOURCE
                        && produced.material().equals(form.material())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int bestOreSource(MfpRecipe recipe, Oracle oracle) {
        int best = MaterialForms.NOT_AN_ORE_SOURCE;
        for (MfpIngredient input : recipe.inputs()) {
            if (!input.consumed()) {
                continue;
            }
            for (MfpKey candidate : input.candidates()) {
                best = Math.max(best, MaterialForms.oreSourceRank(oracle.form(candidate)));
            }
        }
        return best;
    }

    private static Integer refinementDelta(MfpKey key, MfpRecipe recipe, Oracle oracle) {
        int output = MaterialForms.rankOf(oracle.form(key));
        if (output == MaterialForms.UNRANKED) {
            return null;
        }

        int lowestInput = Integer.MAX_VALUE;
        for (MfpIngredient input : recipe.inputs()) {
            if (!input.consumed()) {
                // A catalyst is not what the recipe is made from, so its form says nothing about
                // which direction the recipe runs in.
                continue;
            }
            for (MfpKey candidate : input.candidates()) {
                int rank = MaterialForms.rankOf(oracle.form(candidate));
                if (rank != MaterialForms.UNRANKED) {
                    lowestInput = Math.min(lowestInput, rank);
                }
            }
        }
        return lowestInput == Integer.MAX_VALUE ? null : output - lowestInput;
    }
}
