package dev.mfp.core.behaviour;

import dev.mfp.core.model.Confidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The accumulated effect of a machine's recipe modifiers.
 *
 * <p>Everything here is a <em>multiplier</em> on the recipe as written, because that is how
 * GregTech composes modifiers: a machine declares an ordered list and each one is applied to the
 * recipe the previous one produced. Modelling the result as absolute values instead would make the
 * order unrepresentable, and order genuinely matters — {@code super_ebf} declares
 * {@code [ebf_oc, throughput_boosting, batch_mode]}, and the pack's boosted turbine composes with
 * {@code andThen}.
 *
 * <p>{@code contentMultiplier} is separate from {@code durationMultiplier} on purpose. Parallelism
 * multiplies what one craft yields; overclocking divides how long it takes. They combine into
 * throughput but are not interchangeable, and GregTech's sub-tick overclocks convert one into the
 * other at the one-tick floor.
 *
 * @param durationMultiplier factor on the recipe's duration
 * @param eutMultiplier      factor on the recipe's EU/t
 * @param contentMultiplier  how many crafts' worth one cycle now produces
 * @param overclocks         overclocks performed, which is what boosts chanced outputs
 * @param steamPerEu         millibuckets of steam this machine burns per EU, or zero when it runs on
 *                           electricity. See {@link #asSteam}
 * @param cancelled          the machine cannot run this recipe at all
 * @param cancelReason       why, when cancelled
 * @param confidence         how much to trust the numbers
 * @param notes              what was assumed, in the order the assumptions were made
 */
public record ThroughputResult(
        double durationMultiplier,
        double eutMultiplier,
        double contentMultiplier,
        int overclocks,
        double steamPerEu,
        boolean cancelled,
        String cancelReason,
        Confidence confidence,
        List<String> notes) {

    /** The recipe unmodified. */
    public static final ThroughputResult IDENTITY =
            new ThroughputResult(1.0, 1.0, 1.0, 0, 0, false, null, Confidence.EXACT, List.of());

    public ThroughputResult {
        Objects.requireNonNull(confidence, "confidence");
        if (durationMultiplier <= 0 || !Double.isFinite(durationMultiplier)) {
            throw new IllegalArgumentException("durationMultiplier must be finite and positive: " + durationMultiplier);
        }
        if (eutMultiplier < 0 || !Double.isFinite(eutMultiplier)) {
            throw new IllegalArgumentException("eutMultiplier must be finite and non-negative: " + eutMultiplier);
        }
        if (contentMultiplier <= 0 || !Double.isFinite(contentMultiplier)) {
            throw new IllegalArgumentException("contentMultiplier must be finite and positive: " + contentMultiplier);
        }
        notes = List.copyOf(Objects.requireNonNull(notes, "notes"));
    }

    /** Compose another modifier's effect onto this one. */
    public ThroughputResult andThen(double duration, double eut, double content, int extraOverclocks) {
        if (cancelled) {
            return this;
        }
        return new ThroughputResult(
                durationMultiplier * duration,
                eutMultiplier * eut,
                contentMultiplier * content,
                overclocks + extraOverclocks,
                steamPerEu,
                false, null, confidence, notes);
    }

    /**
     * Declare that this machine is powered by steam rather than electricity.
     *
     * <p>GregTech keeps the recipe in EU/t and converts at the machine — a steam machine holds a
     * {@code SteamEnergyRecipeHandler} that buys each EU for a fixed number of millibuckets — so the
     * whole modifier chain is unaffected and only the final draw changes units. The rate differs by
     * machine: 1 mB/EU for a bronze single block, 2 for its high-pressure form and for GregTech's
     * steam multiblocks, 3 for {@code start_core}'s.
     *
     * <p>Reporting the draw in EU regardless would be a specific kind of wrong that matters: it tells
     * a player to run a power line to a machine that does not take one.
     */
    public ThroughputResult asSteam(double millibucketsPerEu) {
        if (cancelled || millibucketsPerEu <= 0) {
            return this;
        }
        return new ThroughputResult(durationMultiplier, eutMultiplier, contentMultiplier, overclocks,
                millibucketsPerEu, false, null, confidence, notes);
    }

    /** Whether this machine burns steam instead of drawing EU. */
    public boolean isSteamPowered() {
        return steamPerEu > 0;
    }

    /** This machine cannot run this recipe — an EBF whose coils are too cold, say. */
    public ThroughputResult cancel(String reason) {
        return new ThroughputResult(durationMultiplier, eutMultiplier, contentMultiplier, overclocks,
                steamPerEu, true, reason, confidence, notes);
    }

    /**
     * Record an assumption and weaken the confidence accordingly.
     *
     * <p>Confidence only ever falls (plan P5): a chain is no more trustworthy than its least
     * trustworthy link, and a plan that says "≈ 4 machines, coil tier assumed" is useful in a way
     * that a bare "4 machines" is not.
     */
    public ThroughputResult degrade(Confidence to, String note) {
        List<String> combined = new ArrayList<>(notes);
        if (note != null && !combined.contains(note)) {
            combined.add(note);
        }
        return new ThroughputResult(durationMultiplier, eutMultiplier, contentMultiplier, overclocks,
                steamPerEu, cancelled, cancelReason, confidence.and(to), combined);
    }

    /** Duration in ticks after modifiers, never below the one-tick floor a machine cannot beat. */
    public double durationTicks(double baseDurationTicks) {
        return Math.max(1.0, baseDurationTicks * durationMultiplier);
    }

    /** EU/t after modifiers. */
    public double eut(double baseEut) {
        return baseEut * eutMultiplier;
    }

    /** A single line summarising every assumption, or null when there were none. */
    public String note() {
        if (cancelled) {
            return cancelReason;
        }
        return notes.isEmpty() ? null : String.join("; ", notes);
    }
}
