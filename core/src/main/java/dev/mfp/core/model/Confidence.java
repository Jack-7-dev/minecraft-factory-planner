package dev.mfp.core.model;

/**
 * How much trust a derived number deserves (plan P5).
 *
 * <p>MFP targets a modpack built on a GregTech <em>fork</em> with custom machines and custom recipe
 * modifiers, so some throughput numbers are necessarily assumptions. Presenting an assumption as
 * fact is the worst failure mode a planner has: the user builds to it. Every derived quantity
 * carries one of these instead, and the UI renders anything below {@link #EXACT} with a marker.
 */
public enum Confidence {
    /** Computed from rules we have read and modelled. */
    EXACT,
    /** Computed, but under a stated assumption that may not hold. */
    APPROXIMATE,
    /** We do not know how this machine behaves; a conservative default was used. */
    UNKNOWN;

    /** The weaker of two confidences. Combining results can only lose confidence, never gain it. */
    public Confidence and(Confidence other) {
        return compareTo(other) >= 0 ? this : other;
    }
}
