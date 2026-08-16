package dev.mfp.core.behaviour;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins GregTech's overclocking to hand-computed values.
 *
 * <p>Every case here is one the closed form {@code 4^n / 0.5^n} gets wrong, which is why the loops
 * were ported rather than the formula.
 */
class OverclockMathsTest {

    private static final double TOLERANCE = 1e-9;

    @Test
    @DisplayName("tier lookup rounds up: a 120 EU/t recipe is MV, not LV")
    void tierRoundsUp() {
        assertEquals(GtTiers.LV, GtTiers.tierByVoltage(32));
        assertEquals(GtTiers.MV, GtTiers.tierByVoltage(120));
        assertEquals(GtTiers.MV, GtTiers.tierByVoltage(128));
        assertEquals(3, GtTiers.tierByVoltage(129));
        assertEquals(GtTiers.ULV, GtTiers.tierByVoltage(8));
    }

    @Test
    @DisplayName("overclock count is the tier gap, less one for ULV recipes")
    void overclockCount() {
        assertEquals(1, OverclockMaths.overclockCount(120, GtTiers.voltage(3)));
        assertEquals(0, OverclockMaths.overclockCount(120, GtTiers.voltage(2)));
        assertEquals(2, OverclockMaths.overclockCount(30, GtTiers.voltage(3)));
        // ULV: LV does not count as an overclock over it.
        assertEquals(0, OverclockMaths.overclockCount(8, GtTiers.voltage(1)));
        assertEquals(1, OverclockMaths.overclockCount(8, GtTiers.voltage(2)));
    }

    @Test
    @DisplayName("standard overclocking: four times the power, half the duration, each time")
    void standardOverclock() {
        // 30 EU/t over 100 ticks on an HV machine: two overclocks, nothing binding.
        OverclockMaths.Result result = OverclockMaths.standard(
                30, 100, 2, 2, GtTiers.voltage(3), OverclockMaths.STD_DURATION_FACTOR);

        assertEquals(2, result.overclocks());
        assertEquals(16.0, result.eutMultiplier(), TOLERANCE);
        assertEquals(0.25, result.durationMultiplier(), TOLERANCE);
        assertEquals(1, result.parallels());
    }

    @Test
    @DisplayName("the one-tick floor stops overclocks the closed form would grant")
    void standardOverclockStopsAtOneTick() {
        // 30 EU/t over 4 ticks on an IV machine offers four overclocks, but duration runs out
        // after two: 4 -> 2 -> 1, and a third would go below a tick.
        OverclockMaths.Result result = OverclockMaths.standard(
                30, 4, 4, 4, GtTiers.voltage(5), OverclockMaths.STD_DURATION_FACTOR);

        assertEquals(2, result.overclocks(), "a third overclock would take duration below one tick");
        assertEquals(16.0, result.eutMultiplier(), TOLERANCE);
        assertEquals(0.25, result.durationMultiplier(), TOLERANCE);
    }

    @Test
    @DisplayName("sub-tick overclocking spends the leftovers on parallels")
    void subTickOverclockParallelises() {
        // Same recipe and machine as above. The two overclocks duration cannot absorb become
        // parallels instead, so the machine keeps gaining throughput past the one-tick floor.
        OverclockMaths.Result result = OverclockMaths.subTickParallel(
                30, 4, 4, 4, GtTiers.voltage(5), OverclockMaths.STD_DURATION_FACTOR,
                OverclockMaths.subTickParallelCeiling(4, 4));

        assertEquals(4, result.overclocks());
        assertEquals(256.0, result.eutMultiplier(), TOLERANCE);
        assertEquals(0.25, result.durationMultiplier(), TOLERANCE);
        assertEquals(4, result.parallels());
    }

    @Test
    @DisplayName("non-perfect overclocking doubles energy per craft each time")
    void energyPerCraftDoubles() {
        OverclockMaths.Result result = OverclockMaths.standard(
                30, 100, 2, 2, GtTiers.voltage(3), OverclockMaths.STD_DURATION_FACTOR);

        // Energy per craft is EU/t x duration, so the ratio is eut x duration multipliers.
        double energyRatio = result.eutMultiplier() * result.durationMultiplier();
        assertEquals(4.0, energyRatio, TOLERANCE, "two non-perfect overclocks cost 2^2 the energy");
    }

    @Test
    @DisplayName("perfect overclocking keeps energy per craft flat")
    void perfectOverclockIsFree() {
        OverclockMaths.Result result = OverclockMaths.standard(
                30, 100, 2, 2, GtTiers.voltage(3), OverclockMaths.PERFECT_DURATION_FACTOR);

        assertEquals(1.0, result.eutMultiplier() * result.durationMultiplier(), TOLERANCE);
    }

    @Test
    @DisplayName("coil discount needs the recipe itself to be above 900 K")
    void coilDiscountGuard() {
        // The guard that is easy to miss: a cool recipe gets no discount however hot the coils.
        assertEquals(1.0, OverclockMaths.coilEutDiscount(500, 9000), TOLERANCE);
        // 1800 K of surplus is two steps of 5%.
        assertEquals(0.95 * 0.95, OverclockMaths.coilEutDiscount(1500, 3300), TOLERANCE);
        // Surplus below one full step earns nothing.
        assertEquals(1.0, OverclockMaths.coilEutDiscount(1500, 2000), TOLERANCE);
    }

    @Test
    @DisplayName("coil overclocking spends the temperature surplus on perfect overclocks first")
    void heatingCoilPrefersPerfectOverclocks() {
        // 3600 K of surplus is four discount steps, so two perfect overclocks are available.
        OverclockMaths.Result result = OverclockMaths.heatingCoil(
                30, 1000, 3, 3, GtTiers.voltage(4), 1500, 5100, 16);

        assertEquals(3, result.overclocks());
        assertEquals(64.0, result.eutMultiplier(), TOLERANCE);
        // Two perfect (x0.25 each) then one ordinary (x0.5).
        assertEquals(0.25 * 0.25 * 0.5, result.durationMultiplier(), TOLERANCE);
    }
}
