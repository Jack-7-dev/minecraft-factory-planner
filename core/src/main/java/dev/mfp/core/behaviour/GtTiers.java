package dev.mfp.core.behaviour;

/**
 * GregTech's voltage tiers.
 *
 * <p>Numbers, not game classes: the table and the tier arithmetic are the only things the throughput
 * maths needs from GregTech, and copying them keeps {@code core} testable with plain JUnit. The
 * ingestion layer reads the real {@code GTValues} and would disagree loudly if these ever drifted —
 * {@code MfpRecipe.minTier} and {@code MfpMachine.tier} both arrive computed by GregTech itself.
 */
public final class GtTiers {

    /** EU/t each tier can push, ULV first. Mirrors {@code GTValues.V}. */
    private static final long[] VOLTAGE = {
            8L, 32L, 128L, 512L, 2048L, 8192L, 32768L, 131072L, 524288L, 2097152L,
            8388608L, 33554432L, 134217728L, 536870912L, 2147483648L
    };

    private static final String[] NAMES = {
            "ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV",
            "UEV", "UIV", "UXV", "OpV", "MAX"
    };

    public static final int ULV = 0;
    public static final int LV = 1;
    public static final int MV = 2;
    public static final int MAX = 14;

    private GtTiers() {}

    /** EU/t available at a tier, or 0 when the tier index is not a real tier. */
    public static long voltage(int tier) {
        return tier >= 0 && tier < VOLTAGE.length ? VOLTAGE[tier] : 0L;
    }

    /** Short name such as {@code LV}, or {@code "?"} for an unknown tier. */
    public static String name(int tier) {
        return tier >= 0 && tier < NAMES.length ? NAMES[tier] : "?";
    }

    /**
     * The lowest tier that can supply {@code voltage} — GregTech's {@code getOCTierByVoltage}.
     *
     * <p>Note the direction: this rounds <em>up</em>. A 120 EU/t recipe is MV, not LV, because LV
     * cannot run it. Rounding down here would let every recipe claim one more overclock than it
     * really gets.
     */
    public static int tierByVoltage(long voltage) {
        if (voltage <= VOLTAGE[ULV]) {
            return ULV;
        }
        for (int tier = LV; tier < VOLTAGE.length; tier++) {
            if (voltage <= VOLTAGE[tier]) {
                return tier;
            }
        }
        return MAX;
    }
}
