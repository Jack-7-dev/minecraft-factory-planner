package dev.mfp.core.select;

import dev.mfp.core.index.RecipeIndex;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One statement of the form "the plan may not use this", followed outwards over the index until it
 * stops costing anything more.
 *
 * <p><b>Why this is a class rather than a method on the chooser.</b> The rule was written twice.
 * {@code RecipeChooser.expandAvoiding} discovers it by walking — a round that finds no route to an
 * item marks the item unavailable in its own right and the next round runs knowing it — and
 * {@code blacklistConsequences} computes the same closure with the walk taken out, for the picker,
 * which ranks one key and never fails at anything (§17). Two copies of one rule already needed a
 * javadoc explaining that they must not disagree. M17 wants a third, for a tier ceiling, and its
 * second slice a fourth, for buildability. Four hand-written copies of one rule is how they drift
 * apart, so the mechanism is written once here and the differences are handed to it as a
 * {@link Rule}.
 *
 * <p><b>The rule, precisely.</b> An item every one of whose recipes needs something the plan cannot
 * have is itself something the plan cannot have, and it is the next round's frontier. Refusing an
 * item can only cost the plan things made <em>from</em> it, so the search starts at the refused
 * items and moves outwards through consumers rather than scanning the index.
 *
 * <p>Two things it deliberately does not claim, both of them the walk's own rules, and both of them
 * asked of the {@link Rule} rather than baked in here:
 *
 * <ul>
 *   <li><b>An item nothing produces is not lost, it is bought.</b> Raw ore has no recipe and that is
 *       not the refusal's doing. Only an item the pack can actually make, and now cannot, propagates
 *       — otherwise every import in the index would arrive as a consequence of refusing anything.
 *   <li><b>A recipe the rule sets aside is not a recipe the refusal took.</b> Recipes
 *       {@link Rule#setsAside} are passed over without ever becoming a reason, so "you hid it" and
 *       "you have none of these" stay separate answers.
 * </ul>
 */
final class Unavailability {

    private Unavailability() {}

    /**
     * What one caller refuses, and where its search stops. Four questions, no mechanism.
     *
     * <p>A predicate rather than a subclass: adding a tier ceiling to MFP should be writing four
     * short answers, not writing another fixpoint.
     */
    interface Rule {

        /** The items refused outright — the seeds the search starts from. */
        Set<MfpKey> refusedItems();

        /**
         * The refused item to blame for {@code key} being unavailable in its own right, or null.
         *
         * <p>Usually {@code key} itself. It is a separate question from {@link #refusedItems}
         * because the seeds are enumerated once, while this is asked of every candidate of every
         * ingredient of every recipe considered.
         */
        MfpKey refusedBecause(MfpKey key);

        /**
         * True when this recipe is not a route at all, whatever its inputs allow — the user hid it,
         * or some other decision took it out of play. Such a recipe never becomes a <em>reason</em>.
         */
        boolean setsAside(MfpRecipe recipe);

        /**
         * True when this recipe is refused in its own right, whatever its inputs allow.
         *
         * <p>The other way round from {@link #refusedItems}, and the reason the mechanism takes
         * both. A blacklist refuses items and the closure works out which recipes that costs; a
         * tier ceiling refuses recipes and the closure works out which items that costs. An item
         * whose every route is refused this way blames <em>itself</em>, because "nothing at or
         * below HV makes nitrogen plasma" is the sentence the user needs, and it is the one that
         * then propagates upwards to whatever wanted it.
         */
        default boolean refusesOutright(MfpRecipe recipe) {
            return false;
        }

        /** True when the plan declares a supply of this item, so nothing above it is at risk. */
        boolean supplied(MfpKey key);
    }

    /** An ingredient the plan cannot have, and the refused item to blame for it. */
    record Unavailable(MfpKey input, MfpKey cause) {}

    /**
     * Every item this rule leaves unmakeable, and the refused item each one blames.
     *
     * @param maxRounds  how deep a chain one refusal may poison; the same bound for every rule,
     *                   because it is the same claim about how far a refusal reaches
     * @param reachLimit a ceiling rather than a budget, so refusing something every third recipe in
     *                   the pack drinks cannot turn opening a picker into a scan of the index
     */
    static Map<MfpKey, MfpKey> closure(RecipeIndex index, Rule rule, int maxRounds, int reachLimit) {
        Set<MfpKey> refused = rule.refusedItems();
        Map<MfpKey, MfpKey> lost = new LinkedHashMap<>();
        Set<MfpKey> frontier = refused;
        for (int round = 0; round < maxRounds && !frontier.isEmpty(); round++) {
            Map<MfpKey, MfpKey> next = new LinkedHashMap<>();
            for (MfpKey gone : frontier) {
                for (MfpRecipe consumer : index.consuming(gone)) {
                    for (MfpOutput output : consumer.outputs()) {
                        MfpKey key = output.key();
                        if (key.isPseudo() || lost.containsKey(key) || next.containsKey(key)
                                || refused.contains(key)) {
                            continue;
                        }
                        MfpKey cause = everyRouteRefused(index, rule, key, lost);
                        if (cause != null) {
                            next.put(key, cause);
                        }
                    }
                }
            }
            if (next.isEmpty() || lost.size() + next.size() > reachLimit) {
                break;
            }
            lost.putAll(next);
            frontier = next.keySet();
        }
        return Map.copyOf(lost);
    }

    /**
     * The refused item to blame when nothing left can make {@code key}, or null if something can.
     *
     * <p>"Or null if something can" is doing the work: one usable recipe is enough, and it is enough
     * however many others are refused, which is why this returns on the first one it finds rather
     * than counting.
     */
    private static MfpKey everyRouteRefused(RecipeIndex index, Rule rule, MfpKey key,
                                            Map<MfpKey, MfpKey> lost) {
        if (rule.supplied(key)) {
            return null;
        }
        List<MfpRecipe> producers = index.producing(key);
        if (producers.isEmpty()) {
            return null;
        }
        MfpKey cause = null;
        for (MfpRecipe producer : producers) {
            if (isDeadEnd(producer, key) || rule.setsAside(producer)) {
                continue;
            }
            MfpKey refused = rule.refusesOutright(producer) ? key : refusedInput(producer, rule, lost);
            if (refused == null) {
                return null;
            }
            cause = lost.getOrDefault(refused, refused);
        }
        return cause;
    }

    /** The refused item that makes this recipe unusable, or null if none does. */
    static MfpKey refusedInput(MfpRecipe recipe, Rule rule, Map<MfpKey, MfpKey> lost) {
        Unavailable found = unavailableInput(recipe, rule, lost);
        return found == null ? null : found.cause();
    }

    /**
     * An ingredient the plan cannot have, and the refused item to blame for it.
     *
     * <p>The two are the same thing until the closure has been followed (M14): with inferium
     * refused, the ingredient is wood essence and the item to blame is still inferium. Both are
     * worth saying — one is what the recipe wanted, the other is the decision that took it away and
     * the only one the user can take back.
     *
     * <p>Every candidate of a consumed input must be refused before the recipe is: an ingredient
     * accepting either inferium or a plain seed is still satisfiable when only the inferium is
     * refused, and the plan simply takes the other one.
     */
    static Unavailable unavailableInput(MfpRecipe recipe, Rule rule, Map<MfpKey, MfpKey> lost) {
        for (MfpIngredient input : recipe.inputs()) {
            if (!input.consumed() || input.effectiveAmount() <= 0) {
                // A catalyst is a thing you own rather than a thing you consume, and the statement
                // being made here is "I have no supply of this", not "I do not have one".
                continue;
            }
            Unavailable refused = null;
            for (MfpKey candidate : input.candidates()) {
                MfpKey cause = causeOf(candidate, rule, lost);
                if (cause == null) {
                    refused = null;
                    break;
                }
                refused = new Unavailable(candidate, cause);
            }
            if (refused != null) {
                return refused;
            }
        }
        return null;
    }

    /** The refused item that makes {@code key} unavailable, directly or upstream. */
    static MfpKey causeOf(MfpKey key, Rule rule, Map<MfpKey, MfpKey> lost) {
        MfpKey direct = rule.refusedBecause(key);
        return direct != null ? direct : lost.get(key);
    }

    /** A recipe whose only route to {@code key} never actually fires is not a way of making it. */
    static boolean isDeadEnd(MfpRecipe recipe, MfpKey key) {
        boolean sawKey = false;
        for (MfpOutput output : recipe.outputs()) {
            if (!output.key().equals(key)) {
                continue;
            }
            sawKey = true;
            if (output.chance() > 0 && output.amount() > 0) {
                return false;
            }
        }
        return sawKey;
    }
}
