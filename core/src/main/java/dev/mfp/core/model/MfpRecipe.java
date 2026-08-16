package dev.mfp.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A recipe, normalised away from whatever mod produced it.
 *
 * <p>Two conventions hold everywhere and are worth stating once:
 *
 * <ul>
 *   <li><b>Amounts are per craft</b>, not per second. Rates belong to the solver, which turns a
 *       recipe plus a machine into throughput. Items are counts; fluids are millibuckets.
 *   <li><b>Energy is signed by direction.</b> {@code euIn} is what a consumer draws, {@code euOut}
 *       what a generator produces. Modelling generators as recipes that output energy is what lets
 *       "how many turbines do I need?" fall out of the same solve as "how much copper?" (plan P3).
 * </ul>
 *
 * <p>{@code durationTicks} of {@link #INSTANT} means the recipe has no intrinsic rate — hand
 * crafting is the case that matters. Such recipes have no meaningful machine count, and the solver
 * must report that rather than dividing by zero.
 *
 * <p>Anything the adapter could not model belongs in {@code extra} rather than being discarded
 * (plan P4). GregTech's {@code ebf_temp} and {@code vacuum_level} live there.
 */
public record MfpRecipe(
        String id,
        String recipeTypeId,
        String providerId,
        List<MfpIngredient> inputs,
        List<MfpOutput> outputs,
        double durationTicks,
        long euIn,
        long euOut,
        long amperage,
        int minTier,
        List<MfpCondition> conditions,
        Map<String, Object> extra) {

    /** Duration meaning "no intrinsic rate", e.g. a hand-crafting recipe. */
    public static final double INSTANT = 0.0;

    /** Tier is unknown or not applicable (a vanilla furnace has no voltage tier). */
    public static final int NO_TIER = -1;

    public MfpRecipe {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(recipeTypeId, "recipeTypeId");
        Objects.requireNonNull(providerId, "providerId");
        if (id.isEmpty()) {
            throw new IllegalArgumentException("id must not be empty");
        }
        if (durationTicks < 0 || !Double.isFinite(durationTicks)) {
            throw new IllegalArgumentException("durationTicks must be finite and non-negative: " + durationTicks);
        }
        if (euIn < 0 || euOut < 0) {
            throw new IllegalArgumentException("energy must be non-negative; direction is euIn vs euOut");
        }
        if (amperage < 0) {
            throw new IllegalArgumentException("amperage must be non-negative: " + amperage);
        }
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
        conditions = List.copyOf(Objects.requireNonNull(conditions, "conditions"));
        extra = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(extra, "extra")));
    }

    /** Whether this recipe runs over time and therefore has a machine count. */
    public boolean hasRate() {
        return durationTicks > 0;
    }

    /**
     * Whether energy crosses this recipe at all, in either direction.
     *
     * <p>The question a voltage tier is only meaningful for. A coke oven and a primitive blast
     * furnace burn nothing electrical, so an energy hatch — and therefore a tier — describes nothing
     * about the structure that runs them.
     */
    public boolean usesEnergy() {
        return euIn > 0 || euOut > 0;
    }

    /** Whether this recipe generates energy rather than consuming it. */
    public boolean isGenerator() {
        return euOut > 0;
    }

    /** Total EU drawn per craft; zero for unpowered recipes. */
    public double totalEnergyIn() {
        return (double) euIn * Math.max(1L, amperage) * durationTicks;
    }

    /** Distinct keys this recipe can produce, ignoring chance. */
    public boolean produces(MfpKey key) {
        for (MfpOutput output : outputs) {
            if (output.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** Whether any input can be satisfied by {@code key}. */
    public boolean consumes(MfpKey key) {
        for (MfpIngredient input : inputs) {
            if (input.consumed() && input.candidates().contains(key)) {
                return true;
            }
        }
        return false;
    }

    public int intExtra(String key, int fallback) {
        Object value = extra.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    /**
     * This recipe with every ambiguous input pointed at the user's chosen item, where they chose one.
     *
     * <p>Returns {@code this} when nothing changes, which is the common case and keeps recipe
     * identity — and therefore the netting cache, pinned machine configurations and the blacklist —
     * intact. The id is unchanged either way: it is still the same recipe, run on a different one of
     * the items it already accepted.
     */
    public MfpRecipe withPreferredInputs(java.util.Set<MfpKey> preferred) {
        if (preferred.isEmpty()) {
            return this;
        }
        List<MfpIngredient> updated = null;
        for (int i = 0; i < inputs.size(); i++) {
            MfpIngredient input = inputs.get(i);
            MfpIngredient changed = input;
            for (MfpKey choice : preferred) {
                changed = changed.withPreferred(choice);
            }
            if (changed != input) {
                if (updated == null) {
                    updated = new java.util.ArrayList<>(inputs);
                }
                updated.set(i, changed);
            }
        }
        if (updated == null) {
            return this;
        }
        return new MfpRecipe(id, recipeTypeId, providerId, updated, outputs, durationTicks,
                euIn, euOut, amperage, minTier, conditions, extra);
    }

    public static Builder builder(String id, String recipeTypeId, String providerId) {
        return new Builder(id, recipeTypeId, providerId);
    }

    /** Mutable assembler for {@link MfpRecipe}; providers build recipes field by field. */
    public static final class Builder {
        private final String id;
        private final String recipeTypeId;
        private final String providerId;
        private final List<MfpIngredient> inputs = new ArrayList<>();
        private final List<MfpOutput> outputs = new ArrayList<>();
        private final List<MfpCondition> conditions = new ArrayList<>();
        private final Map<String, Object> extra = new LinkedHashMap<>();
        private double durationTicks = INSTANT;
        private long euIn;
        private long euOut;
        private long amperage = 1;
        private int minTier = NO_TIER;

        private Builder(String id, String recipeTypeId, String providerId) {
            this.id = id;
            this.recipeTypeId = recipeTypeId;
            this.providerId = providerId;
        }

        public Builder input(MfpIngredient ingredient) {
            inputs.add(ingredient);
            return this;
        }

        public Builder output(MfpOutput output) {
            outputs.add(output);
            return this;
        }

        public Builder condition(MfpCondition condition) {
            conditions.add(condition);
            return this;
        }

        public Builder extra(String key, Object value) {
            extra.put(key, value);
            return this;
        }

        public Builder duration(double ticks) {
            this.durationTicks = ticks;
            return this;
        }

        public Builder euIn(long eu) {
            this.euIn = eu;
            return this;
        }

        public Builder euOut(long eu) {
            this.euOut = eu;
            return this;
        }

        public Builder amperage(long amps) {
            this.amperage = amps;
            return this;
        }

        public Builder minTier(int tier) {
            this.minTier = tier;
            return this;
        }

        public MfpRecipe build() {
            return new MfpRecipe(id, recipeTypeId, providerId, inputs, outputs, durationTicks,
                    euIn, euOut, amperage, minTier, conditions, extra);
        }
    }
}
