package dev.mfp.core.select;

import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ways of eating a surplus, ranked (M18).
 *
 * <p><b>The mirror of {@link RecipeScorer}, and not a reuse of it.</b> That class ranks candidates
 * as ways of <em>producing</em> an item, and every term in it is about the cost of making something:
 * how refined the inputs are, how far from raw material the route is, whether the recipe is
 * recycling. None of that applies here. An item's consumers are not candidates for making anything,
 * so a plan asking "what could eat this hydrogen sulfide" gets no useful order out of a cost model —
 * and offering thirty sinks in the index's own order is not an answer to the question.
 *
 * <p>What ranks a sink is what it does for the factory, in this order:
 *
 * <ol>
 *   <li><b>Does it feed the plan?</b> A consumer whose output is something the plan already wants
 *       turns a surplus into a supply. One that merely destroys the item is the baseline, not a
 *       fault — sometimes destroying it is all there is — but it must never outrank the other.
 *   <li><b>Does it cost a new chain?</b> A sink needing three other items the plan has no line for
 *       is not two clicks; it is a second factory with a byproduct problem of its own.
 *   <li><b>Does it eat enough of it?</b> A trickle consumer does not answer a surplus, and rate is
 *       the only term here that can only be judged against the other candidates.
 * </ol>
 *
 * <p>Pure, like the scorer it mirrors: everything that needs the index or the solved plan arrives
 * through {@link Context}, so the ranking is testable with no game and no plan.
 */
public final class SinkScorer {

    /** A consumer whose output something in the plan wants. The headline term, by a distance. */
    public static final double FEEDS_THE_PLAN = 40;

    /**
     * And more when the plan is currently <em>buying</em> that item.
     *
     * <p>Turning a surplus into something already on the shopping list is strictly better than
     * turning it into more of something the plan makes for itself: the first removes an import, the
     * second only moves production around.
     */
    public static final double FEEDS_AN_IMPORT = 20;

    /** Half credit where the useful output is chanced: being handed it sometimes is not a supply. */
    public static final double CHANCED_FRACTION = 0.5;

    /** Nothing else has to be made or bought for this sink to run. */
    public static final double NEEDS_NOTHING_NEW = 12;

    /** Per item the plan would have to start making or buying to run this sink. */
    public static final double PER_NEW_INPUT = 10;

    /** The cap on that, so a long ingredient list cannot swamp every other judgement. */
    public static final double MAX_NEW_INPUT_PENALTY = 30;

    /**
     * A "sink" that hands the item straight back.
     *
     * <p>GregTech is full of pairs that cancel — compress and decompress, polarise and unpolarise —
     * and a recipe that consumes the surplus and produces it again consumes nothing at all. Large
     * enough to sit below every honest disposal route.
     */
    public static final double GIVES_IT_BACK = 45;

    /**
     * A "sink" that only changes the shape of the leftover.
     *
     * <p>The first thing the pack said when this ranking was pointed at a real surplus: the six best
     * ways of eating ash dust were all <em>unpackaging</em> it — dust to small dust, small dust to
     * tiny dust, and the shaped-crafting versions of both. Every one of them consumes the item and
     * every one of them leaves you with exactly the same ash, so the plan's surplus does not shrink,
     * it merely gets a different name. This is {@link RecipeScorer}'s packaging-loop problem seen
     * from the consuming end, and the test is the same data: GregTech's own material classification,
     * read from the game rather than guessed from an id.
     *
     * <p>Smaller than {@link #FEEDS_THE_PLAN} on purpose. Turning surplus ingots into the plates the
     * plan is short of <em>is</em> repackaging, and it is also the right answer; the penalty has to
     * lose to a use and beat a non-use.
     */
    public static final double REPACKAGES = 35;

    /** Points per halving of consumption rate against the hungriest candidate. */
    private static final double RATE_PER_HALVING = 4;

    /** The floor on that, at sixty-four times slower — past which "much slower" is the whole answer. */
    public static final double MAX_RATE_PENALTY = 24;

    /**
     * How many candidates the rate term is applied to.
     *
     * <p>Rate is the one term that costs a throughput resolve per candidate, and the pack has items
     * with thousands of consumers — water has more than the picker could ever show. The cheap terms
     * order the whole set; the expensive one only refines the head of it, which is the part anybody
     * will look at. Beyond this window the ranking is the cheap one, which is honest: it is still an
     * order, and the term that would have changed it is the smallest one here.
     */
    public static final int RATE_WINDOW = 60;

    private SinkScorer() {}

    /** A candidate sink and why it scored what it did. */
    public record Scored(MfpRecipe recipe, double score, List<String> reasons)
            implements Comparable<Scored> {

        @Override
        public int compareTo(Scored other) {
            int byScore = Double.compare(other.score, score);
            return byScore != 0 ? byScore : recipe.id().compareTo(other.recipe.id());
        }
    }

    /**
     * What the ranking needs to know about the plan around the surplus.
     *
     * <p>Every default is the answer a caller with nothing to say would give, so {@link #NONE}
     * produces a ranking driven by the recipes alone — which is what the tests for the individual
     * terms want.
     */
    public interface Context {

        Context NONE = new Context() {};

        /** Whether some line in the plan consumes this, or the plan targets it. */
        default boolean wanted(MfpKey key) {
            return false;
        }

        /** Whether the plan is currently importing this — buying it from outside the factory. */
        default boolean imported(MfpKey key) {
            return false;
        }

        /** Whether the plan already makes this, so a sink asking for it costs nothing new. */
        default boolean produced(MfpKey key) {
            return false;
        }

        /** Whether the plan treats this as something that simply arrives: raw, free, or declared. */
        default boolean raw(MfpKey key) {
            return false;
        }

        /** How much of {@code key} one machine running this recipe eats per second; 0 if unknown. */
        default double consumedPerSecond(MfpRecipe recipe, MfpKey key) {
            return 0;
        }

        /**
         * What material this item is made of, or null when the game does not classify it.
         *
         * <p>The game's own answer, never an inference from the id — the same classification
         * {@link RecipeScorer} reads for its refinement term. Null for everything in a pack with no
         * GregTech, which makes {@link #REPACKAGES} silent rather than wrong.
         */
        default String material(MfpKey key) {
            return null;
        }
    }

    /**
     * Rank {@code candidates} as ways of consuming {@code surplus}.
     *
     * <p>Candidates that do not actually consume the item are dropped rather than scored badly: an
     * index lookup answers "mentions this" and a catalyst is mentioned without being eaten, so
     * offering one as a sink would be offering a line that changes nothing.
     */
    public static List<Scored> rank(MfpKey surplus, List<MfpRecipe> candidates, Context context) {
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (MfpRecipe recipe : candidates) {
            if (eats(recipe, surplus) <= 0) {
                continue;
            }
            scored.add(score(surplus, recipe, context));
        }
        Collections.sort(scored);
        return withRate(surplus, scored, context);
    }

    /**
     * The rate term, applied to the head of the ranking.
     *
     * <p>"Eats a lot" has no absolute meaning — a bucket a second is a torrent of lubricant and a
     * trickle of water — so it can only mean "a lot compared with the other ways of eating this",
     * which is why this cannot be a per-candidate term. A candidate whose rate is unknown is left
     * alone rather than ranked last, exactly as {@link RecipeScorer} leaves a recipe with no
     * duration alone: hand crafting has no rate and the question does not apply to it.
     */
    private static List<Scored> withRate(MfpKey surplus, List<Scored> scored, Context context) {
        int window = Math.min(RATE_WINDOW, scored.size());
        if (window < 2) {
            return scored;
        }

        double[] rates = new double[window];
        double hungriest = 0;
        int known = 0;
        for (int i = 0; i < window; i++) {
            double rate = context.consumedPerSecond(scored.get(i).recipe(), surplus);
            rates[i] = rate > 0 && Double.isFinite(rate) ? rate : 0;
            if (rates[i] > 0) {
                known++;
                hungriest = Math.max(hungriest, rates[i]);
            }
        }
        if (known < 2) {
            return scored;
        }

        List<Scored> adjusted = new ArrayList<>(scored);
        for (int i = 0; i < window; i++) {
            if (rates[i] <= 0 || rates[i] >= hungriest) {
                continue;
            }
            Scored one = scored.get(i);
            double ratio = hungriest / rates[i];
            double penalty = Math.min(MAX_RATE_PENALTY,
                    RATE_PER_HALVING * (Math.log(ratio) / Math.log(2)));
            List<String> reasons = new ArrayList<>(one.reasons());
            reasons.add(times(ratio) + " less of it per machine than the hungriest way to use it");
            adjusted.set(i, new Scored(one.recipe(), one.score() - penalty, List.copyOf(reasons)));
        }
        Collections.sort(adjusted);
        return adjusted;
    }

    private static String times(double ratio) {
        return ratio < 9.5 ? String.format("%.1fx", ratio) : Math.round(ratio) + "x";
    }

    public static Scored score(MfpKey surplus, MfpRecipe recipe, Context context) {
        List<String> reasons = new ArrayList<>();
        double score = 0;

        // 1. What it does with the surplus.
        double handedBack = producedAmount(recipe, surplus);
        if (handedBack > 0) {
            score -= GIVES_IT_BACK;
            reasons.add("hands the " + surplus.id() + " straight back, so it consumes none of it");
        }

        // 2. Whether anything in the plan wants what comes out.
        double feeds = 0;
        String fed = null;
        boolean removesAnImport = false;
        for (MfpOutput output : recipe.outputs()) {
            MfpKey key = output.key();
            if (key.equals(surplus) || key.isPseudo() || output.amount() <= 0
                    || !context.wanted(key)) {
                continue;
            }
            double credit = FEEDS_THE_PLAN + (context.imported(key) ? FEEDS_AN_IMPORT : 0);
            if (output.isChanced()) {
                credit *= CHANCED_FRACTION;
            }
            if (credit > feeds) {
                feeds = credit;
                fed = key.id();
                removesAnImport = context.imported(key);
            }
        }
        if (feeds > 0) {
            score += feeds;
            reasons.add(removesAnImport
                    ? "makes " + fed + ", which this plan is currently importing"
                    : "makes " + fed + ", which this plan wants");
        } else {
            reasons.add("disposal: nothing in the plan wants what this makes");
        }

        if (repackages(surplus, recipe, context)) {
            score -= REPACKAGES;
            reasons.add("only repackages it: everything it makes is the same material");
        }

        // 3. What it costs to run. Counted as *kinds* of thing rather than amounts, because the
        // question is how many new chains the sink drags in, and a chain is a chain whatever its
        // rate.
        int newInputs = 0;
        List<String> named = new ArrayList<>();
        for (MfpIngredient input : recipe.inputs()) {
            if (!input.consumed() || input.effectiveAmount() <= 0) {
                continue;
            }
            if (satisfied(input, surplus, context)) {
                continue;
            }
            newInputs++;
            if (named.size() < 3) {
                named.add(input.primary().id());
            }
        }
        if (newInputs == 0) {
            score += NEEDS_NOTHING_NEW;
            reasons.add("needs nothing the plan has not already got");
        } else {
            score -= Math.min(MAX_NEW_INPUT_PENALTY, PER_NEW_INPUT * newInputs);
            reasons.add("needs " + newInputs + " more thing" + (newInputs == 1 ? "" : "s")
                    + " the plan does not make: " + String.join(", ", named)
                    + (newInputs > named.size() ? ", ..." : ""));
        }

        return new Scored(recipe, score, List.copyOf(reasons));
    }

    /**
     * Whether every guaranteed output is the same material as the surplus.
     *
     * <p>Asked of the whole recipe rather than per output, and it has to be: a centrifuge that
     * separates ash into several elements makes one output of the same material and several that are
     * not, and it is a real sink. Only a recipe with <em>nothing but</em> the same material coming
     * out has left the plan's surplus exactly where it was.
     *
     * <p>Chanced outputs are ignored, because nobody builds a machine for what it gives back one
     * time in twenty; a recipe with no guaranteed output at all is destruction rather than
     * repackaging, and is left alone.
     */
    private static boolean repackages(MfpKey surplus, MfpRecipe recipe, Context context) {
        String material = context.material(surplus);
        if (material == null) {
            return false;
        }
        boolean any = false;
        for (MfpOutput output : recipe.outputs()) {
            if (output.isChanced() || output.amount() <= 0 || output.key().isPseudo()) {
                continue;
            }
            if (!material.equals(context.material(output.key()))) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /**
     * Whether an input is already answered — by the surplus itself, by the plan, or by being free.
     *
     * <p>Any candidate of an ambiguous input will do. A tag input the plan can satisfy with one of
     * its own items is satisfied, and insisting on the primary candidate would count a chain the
     * plan does not need.
     */
    private static boolean satisfied(MfpIngredient input, MfpKey surplus, Context context) {
        for (MfpKey candidate : input.candidates()) {
            if (candidate.equals(surplus) || candidate.isPseudo()
                    || context.produced(candidate) || context.raw(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** How much of {@code key} one craft consumes, over every ingredient that could be it. */
    private static double eats(MfpRecipe recipe, MfpKey key) {
        double total = 0;
        for (MfpIngredient input : recipe.inputs()) {
            if (!input.consumed() || input.effectiveAmount() <= 0) {
                continue;
            }
            if (input.candidates().contains(key)) {
                total += input.effectiveAmount();
            }
        }
        return total;
    }

    /** How much of {@code key} one craft produces, chanced outputs included at their expectation. */
    private static double producedAmount(MfpRecipe recipe, MfpKey key) {
        double total = 0;
        for (MfpOutput output : recipe.outputs()) {
            if (output.key().equals(key)) {
                total += output.expectedAmount();
            }
        }
        return total;
    }
}
