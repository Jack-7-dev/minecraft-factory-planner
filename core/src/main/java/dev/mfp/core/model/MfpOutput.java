package dev.mfp.core.model;

import java.util.Objects;

/**
 * One product of a recipe.
 *
 * <p>Outputs are single-keyed, unlike {@link MfpIngredient}: a recipe produces a specific thing,
 * even when its inputs were ambiguous.
 *
 * <p>{@code chance} is the base probability, before any bonus for running the recipe above its own
 * tier. GregTech grants such a bonus per overclock, so the effective chance depends on the machine
 * the line is configured with and cannot be baked in at ingest time — which is why
 * {@code chanceBoostPerTier} is carried alongside rather than folded into {@code chance}.
 *
 * @param key                what is produced
 * @param amount             quantity per successful craft, in items or millibuckets
 * @param chance             base probability in {@code [0,1]} of being produced at all
 * @param chanceBoostPerTier probability added to {@code chance} per overclock, before clamping
 * @param mode               how this output's roll relates to the recipe's other chanced outputs
 * @param groupKey           groups outputs that resolve together under {@link ChanceMode#FIRST_ONLY}
 *                           or {@link ChanceMode#EXCLUSIVE}; null when the output stands alone
 */
public record MfpOutput(
        MfpKey key,
        double amount,
        double chance,
        double chanceBoostPerTier,
        ChanceMode mode,
        String groupKey) {

    public MfpOutput {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mode, "mode");
        if (amount < 0 || !Double.isFinite(amount)) {
            throw new IllegalArgumentException("amount must be finite and non-negative: " + amount);
        }
        if (chance < 0 || chance > 1 || !Double.isFinite(chance)) {
            throw new IllegalArgumentException("chance must be within [0,1]: " + chance);
        }
        if (!Double.isFinite(chanceBoostPerTier)) {
            throw new IllegalArgumentException("chanceBoostPerTier must be finite: " + chanceBoostPerTier);
        }
        if (mode == ChanceMode.ALWAYS && chance != 1.0) {
            throw new IllegalArgumentException("an ALWAYS output cannot have chance " + chance);
        }
    }

    /** An output with no tier bonus, which is the overwhelming majority of them. */
    public MfpOutput(MfpKey key, double amount, double chance, ChanceMode mode, String groupKey) {
        this(key, amount, chance, 0.0, mode, groupKey);
    }

    public static MfpOutput of(MfpKey key, double amount) {
        return new MfpOutput(key, amount, 1.0, ChanceMode.ALWAYS, null);
    }

    /** A chanced output that rolls independently of its siblings — GregTech's default. */
    public static MfpOutput chanced(MfpKey key, double amount, double chance) {
        return new MfpOutput(key, amount, chance, ChanceMode.INDEPENDENT, null);
    }

    public boolean isChanced() {
        return chance < 1.0;
    }

    public boolean hasChanceBoost() {
        return chanceBoostPerTier != 0.0;
    }

    /**
     * Effective probability when the recipe is overclocked {@code overclocks} times.
     *
     * <p>Ports GregTech's {@code ChanceBoostFunction.OVERCLOCK}: a linear bonus per overclock,
     * clamped to a real probability. Zero overclocks means the base chance, so an unconfigured plan
     * is never quietly optimistic.
     */
    public double chanceAt(int overclocks) {
        if (overclocks <= 0 || chanceBoostPerTier == 0.0) {
            return chance;
        }
        return Math.min(1.0, Math.max(0.0, chance + chanceBoostPerTier * overclocks));
    }

    /**
     * Expected yield per craft <em>in isolation</em>, ignoring any competing siblings.
     *
     * <p>Correct only when {@link ChanceMode#isIndependentlyExpectable()} holds. Competing modes
     * need the whole group, which is what {@code ChanceResolver} is for; this method exists for
     * displays and for the netting pass, not as the solver's source of truth.
     */
    public double expectedAmount() {
        return amount * chance;
    }

    /** Whether {@link #expectedAmount()} is exact rather than an over-estimate for this output. */
    public boolean isExpectationExact() {
        return mode.isIndependentlyExpectable();
    }
}
