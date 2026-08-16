package dev.mfp.core.model;

import java.util.List;
import java.util.Objects;

/**
 * One input of a recipe.
 *
 * <p>{@code candidates} is a list rather than a single key because tag ingredients are genuinely
 * ambiguous: "any copper plate" may be satisfied by several items and MFP must not silently pick
 * one. The index registers the ingredient under every candidate, and the recipe picker lets the
 * user pin a concrete choice.
 *
 * <p>{@code consumed} deserves care. GregTech encodes "not consumable" as {@code Content.chance == 0},
 * which reads like "never happens" but means the opposite: the item must be <em>present</em> and is
 * <em>not</em> used up. Programmed circuits work this way and appear in hundreds of recipes in the
 * target pack, so getting this backwards would corrupt a large fraction of the graph. A
 * non-consumed ingredient contributes nothing to the material flow — it is a fixed setup cost.
 *
 * @param candidates items or fluids that satisfy this input; never empty
 * @param amount     quantity per craft, in items or millibuckets. Zero for non-consumed inputs
 * @param consumed   whether the craft uses the item up
 * @param chance     probability in {@code [0,1]} that the input is consumed; 1 for ordinary inputs
 */
public record MfpIngredient(List<MfpKey> candidates, double amount, boolean consumed, double chance) {

    public MfpIngredient {
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("an ingredient needs at least one candidate");
        }
        if (amount < 0 || !Double.isFinite(amount)) {
            throw new IllegalArgumentException("amount must be finite and non-negative: " + amount);
        }
        if (chance < 0 || chance > 1 || !Double.isFinite(chance)) {
            throw new IllegalArgumentException("chance must be within [0,1]: " + chance);
        }
        candidates = List.copyOf(candidates);
    }

    public static MfpIngredient of(MfpKey key, double amount) {
        return new MfpIngredient(List.of(key), amount, true, 1.0);
    }

    public static MfpIngredient ofAny(List<MfpKey> candidates, double amount) {
        return new MfpIngredient(candidates, amount, true, 1.0);
    }

    /**
     * An input that must be present but is not used up — GregTech's {@code notConsumable}, of which
     * a programmed circuit is the canonical example.
     */
    public static MfpIngredient notConsumed(MfpKey key) {
        return new MfpIngredient(List.of(key), 0.0, false, 1.0);
    }

    /**
     * The same ingredient with {@code key} moved to the front, or this one unchanged.
     *
     * <p>Front matters: {@link #primary()} is what the chooser expands <em>and</em> what the solver
     * takes for the material flow, so a choice expressed any other way would have the plan producing
     * one item and demanding another. Reordering keeps the two in step by construction, and keeps
     * every other candidate on the ingredient where the picker can still offer it.
     */
    public MfpIngredient withPreferred(MfpKey key) {
        if (candidates.size() < 2 || !candidates.contains(key) || candidates.get(0).equals(key)) {
            return this;
        }
        List<MfpKey> reordered = new java.util.ArrayList<>(candidates.size());
        reordered.add(key);
        for (MfpKey candidate : candidates) {
            if (!candidate.equals(key)) {
                reordered.add(candidate);
            }
        }
        return new MfpIngredient(reordered, amount, consumed, chance);
    }

    /** True when more than one item satisfies this input, i.e. the user may need to choose. */
    public boolean isAmbiguous() {
        return candidates.size() > 1;
    }

    /** The candidate MFP uses when the user has expressed no preference. */
    public MfpKey primary() {
        return candidates.get(0);
    }

    /**
     * Quantity per craft that actually flows, i.e. what the solver should demand. Non-consumed
     * inputs flow nothing no matter what amount they nominally carry.
     */
    public double effectiveAmount() {
        return consumed ? amount * chance : 0.0;
    }
}
