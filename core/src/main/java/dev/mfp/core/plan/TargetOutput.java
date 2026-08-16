package dev.mfp.core.plan;

import dev.mfp.core.model.MfpKey;

import java.util.Objects;

/**
 * A goal: "produce this much of this, per second".
 *
 * @param key       what to produce
 * @param perSecond desired rate; items or millibuckets per second, EU per second for energy
 */
public record TargetOutput(MfpKey key, double perSecond) {

    public TargetOutput {
        Objects.requireNonNull(key, "key");
        if (perSecond < 0 || !Double.isFinite(perSecond)) {
            throw new IllegalArgumentException("perSecond must be finite and non-negative: " + perSecond);
        }
    }

    /** Convenience for the common way users think about rates. */
    public static TargetOutput perMinute(MfpKey key, double amountPerMinute) {
        return new TargetOutput(key, amountPerMinute / 60.0);
    }

    /** What a newly created target asks for when the user has not said. */
    public static final double DEFAULT_ITEM_PER_SECOND = 1.0;

    /**
     * A bucket a second, because a millibucket a second is a drip.
     *
     * <p>Fluids are stored in mB throughout (plan P2 says every rate is per second; it does not say
     * every rate is in the same unit), so "1" for a fluid means one millibucket — a twentieth of a
     * bucket a minute, which is never what anyone means and produces a plan of fractional machines
     * that reads as broken.
     */
    public static final double DEFAULT_FLUID_PER_SECOND = 1000.0;

    /**
     * The rate a target gets when it is created without one.
     *
     * <p>Here rather than in the GUI so that {@code /mfp plan}, {@code /mfpplan} and every button
     * that adds a target agree — a default that lives at one of the call sites is a default the
     * others quietly disagree with.
     */
    public static double defaultRate(MfpKey key) {
        return key.kind() == MfpKey.Kind.FLUID ? DEFAULT_FLUID_PER_SECOND : DEFAULT_ITEM_PER_SECOND;
    }

    /** A target for {@code key} at {@link #defaultRate}. */
    public static TargetOutput of(MfpKey key) {
        return new TargetOutput(key, defaultRate(key));
    }
}
