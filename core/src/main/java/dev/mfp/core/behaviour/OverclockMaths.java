package dev.mfp.core.behaviour;

/**
 * GregTech's overclocking loops, ported from the fork's {@code OverclockingLogic}.
 *
 * <p>Written as loops rather than as the closed form {@code eut × 4^n, duration × 0.5^n} because
 * the closed form is only right when nothing binds. In practice two things bind constantly:
 *
 * <ul>
 *   <li><b>Duration cannot fall below one tick.</b> An overclock that would take a recipe below a
 *       tick simply does not happen in the plain algorithm — so a short recipe on a high-tier
 *       machine gains far less than {@code 4^n} suggests, while still being charged for the
 *       overclocks it did get.
 *   <li><b>Voltage cannot exceed the machine.</b> The loop stops before the step that would
 *       overshoot, which is not the same as clamping afterwards.
 * </ul>
 *
 * <p>The sub-tick variants spend the overclocks that duration cannot absorb on <em>parallelism</em>
 * instead, which is why a modern GregTech machine keeps getting faster past the one-tick floor.
 * That is a different quantity from duration and has to be tracked separately: it multiplies what a
 * craft yields rather than how long it takes.
 */
public final class OverclockMaths {

    /** EU/t multiplier per overclock. */
    public static final double STD_VOLTAGE_FACTOR = 4.0;
    /** Duration multiplier per ordinary overclock. */
    public static final double STD_DURATION_FACTOR = 0.5;
    /** Duration multiplier per perfect overclock. */
    public static final double PERFECT_DURATION_FACTOR = 0.25;
    /** Kelvin per coil discount step. */
    public static final int COIL_DISCOUNT_TEMPERATURE = 900;

    private OverclockMaths() {}

    /**
     * The outcome of overclocking.
     *
     * @param eutMultiplier      factor applied to the recipe's EU/t
     * @param durationMultiplier factor applied to the recipe's duration
     * @param overclocks         overclocks actually performed
     * @param baseOverclocks     overclocks attributable to tier difference alone, which is what
     *                           GregTech uses for chanced-output boosting in this pack
     * @param parallels          crafts run at once, from sub-tick overclocks
     */
    public record Result(double eutMultiplier, double durationMultiplier,
                         int overclocks, int baseOverclocks, int parallels) {

        public static final Result NONE = new Result(1.0, 1.0, 0, 0, 1);
    }

    /**
     * How many overclocks a recipe gets on a machine.
     *
     * <p>The ULV adjustment is GregTech's own: an ULV recipe does not gain an overclock merely by
     * being run at LV.
     *
     * @param recipeEut  the recipe's EU/t, voltage times amperage
     * @param maxVoltage the machine's overclock voltage
     */
    public static int overclockCount(long recipeEut, long maxVoltage) {
        if (recipeEut <= 0 || maxVoltage <= 0) {
            return 0;
        }
        int recipeTier = GtTiers.tierByVoltage(recipeEut);
        int machineTier = GtTiers.tierByVoltage(maxVoltage);
        int overclocks = machineTier - recipeTier;
        if (recipeTier == GtTiers.ULV) {
            overclocks--;
        }
        return Math.max(0, overclocks);
    }

    /**
     * The parallel ceiling a sub-tick overclock may use, from the fork's {@code getModifier}.
     *
     * <p>Assumes an unbounded input supply, so GregTech's inventory-dependent
     * {@code ParallelLogic.getParallelAmount} is taken to return whatever it was asked for. That is
     * the planner's core assumption about parallelism and it is stated wherever it is relied on: a
     * machine that is not fed this well will not reach these numbers.
     */
    public static int subTickParallelCeiling(int durationTicks, int overclocks) {
        if (durationTicks <= 0) {
            return 1;
        }
        // floor(log4(duration)): how many perfect overclocks fit before duration drops below 4.
        int log4 = (31 - Integer.numberOfLeadingZeros(durationTicks)) / 2;
        if (log4 > overclocks) {
            return 16;
        }
        int shift = 2 * (overclocks - log4);
        if (shift >= 31) {
            return Integer.MAX_VALUE;
        }
        return (1 << shift) + 1;
    }

    /**
     * Plain overclocking: faster and hungrier, stopping at one tick.
     *
     * @param durationFactor {@link #STD_DURATION_FACTOR} or {@link #PERFECT_DURATION_FACTOR}
     */
    public static Result standard(long recipeEut, int durationTicks, int overclocks, int baseOverclocks,
                                  long maxVoltage, double durationFactor) {
        double duration = durationTicks;
        double eut = recipeEut;
        int performed = 0;

        for (int i = 0; i < overclocks; i++) {
            double nextEut = eut * STD_VOLTAGE_FACTOR;
            if (nextEut > maxVoltage) {
                break;
            }
            double nextDuration = duration * durationFactor;
            if (nextDuration < 1) {
                break;
            }
            duration = nextDuration;
            eut = nextEut;
            performed++;
        }

        return new Result(Math.pow(STD_VOLTAGE_FACTOR, performed), Math.pow(durationFactor, performed),
                performed, Math.min(performed, baseOverclocks), 1);
    }

    /**
     * Sub-tick overclocking: once duration would drop below a tick, buy parallelism instead.
     *
     * <p>Note what this does to the answer. Past the one-tick floor the machine stops getting
     * faster and starts getting wider, so throughput keeps climbing while duration stays put — and
     * a model that only tracked duration would report a machine count several times too high.
     */
    public static Result subTickParallel(long recipeEut, int durationTicks, int overclocks, int baseOverclocks,
                                         long maxVoltage, double durationFactor, int maxParallels) {
        double duration = durationTicks;
        double eut = recipeEut;
        double parallel = 1;
        boolean parallelising = false;
        int performed = 0;
        double durationMultiplier = 1.0;

        for (int i = 0; i < overclocks; i++) {
            double nextEut = eut * STD_VOLTAGE_FACTOR;
            if (nextEut > maxVoltage) {
                break;
            }

            if (parallelising || duration * durationFactor < 1) {
                double nextParallel = parallel / durationFactor;
                if (nextParallel > maxParallels) {
                    break;
                }
                parallel = nextParallel;
                parallelising = true;
            } else {
                duration *= durationFactor;
                durationMultiplier *= durationFactor;
            }

            eut = nextEut;
            performed++;
        }

        return new Result(Math.pow(STD_VOLTAGE_FACTOR, performed), durationMultiplier,
                performed, Math.min(performed, baseOverclocks), (int) parallel);
    }

    /**
     * Heating-coil overclocking: spend the temperature surplus on perfect overclocks first.
     *
     * <p>Every 900 K above what the recipe asks for buys one discount step, and half of those steps
     * become <em>perfect</em> overclocks — quartering duration instead of halving it. The remaining
     * overclocks are ordinary. Both fall back to parallelism at the one-tick floor.
     */
    public static Result heatingCoil(long recipeEut, int durationTicks, int overclocks, int baseOverclocks,
                                     long maxVoltage, int recipeTemp, int machineTemp, int maxParallels) {
        int perfectRemaining = coilDiscountSteps(recipeTemp, machineTemp) / 2;
        double duration = durationTicks;
        double eut = recipeEut;
        double parallel = 1;
        boolean parallelising = false;
        int performed = 0;
        double durationMultiplier = 1.0;

        for (int i = 0; i < overclocks; i++) {
            boolean perfect = perfectRemaining-- > 0;

            double nextEut = eut * STD_VOLTAGE_FACTOR;
            if (nextEut > maxVoltage) {
                break;
            }

            double factor = perfect ? PERFECT_DURATION_FACTOR : STD_DURATION_FACTOR;
            if (parallelising || duration * factor < 1) {
                double nextParallel = parallel / factor;
                if (nextParallel > maxParallels) {
                    break;
                }
                parallel = nextParallel;
                parallelising = true;
            } else {
                duration *= factor;
                durationMultiplier *= factor;
            }

            eut = nextEut;
            performed++;
        }

        return new Result(Math.pow(STD_VOLTAGE_FACTOR, performed), durationMultiplier,
                performed, Math.min(performed, baseOverclocks), (int) parallel);
    }

    /** Discount steps earned by exceeding the recipe's required temperature. */
    public static int coilDiscountSteps(int recipeTemp, int machineTemp) {
        return Math.max(0, (machineTemp - recipeTemp) / COIL_DISCOUNT_TEMPERATURE);
    }

    /**
     * EU/t discount from running a blast recipe hotter than it needs.
     *
     * <p>The guard matters and is easy to miss: recipes below 900 K get <b>no</b> discount at all,
     * however hot the coils are. A model without it under-states the power draw of every low-tier
     * blast recipe.
     */
    public static double coilEutDiscount(int recipeTemp, int machineTemp) {
        if (recipeTemp < COIL_DISCOUNT_TEMPERATURE) {
            return 1.0;
        }
        int steps = coilDiscountSteps(recipeTemp, machineTemp);
        if (steps < 1) {
            return 1.0;
        }
        return Math.min(1.0, Math.pow(0.95, steps));
    }
}
