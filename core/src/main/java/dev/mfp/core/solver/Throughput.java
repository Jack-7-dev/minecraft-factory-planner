package dev.mfp.core.solver;

import dev.mfp.core.model.Confidence;

import java.util.Objects;

/**
 * What one machine achieves, per second.
 *
 * <p>Per <em>machine</em> rather than per line, because the solver needs the per-machine rate to
 * divide demand by, and because keeping quantities linear in machine count is what will later let
 * the matrix engine put them straight into a column.
 *
 * @param craftsPerSecond    completed crafts per second for a single machine
 * @param euInPerSecond      EU drawn per second by a single machine while running
 * @param euOutPerSecond     EU produced per second by a single machine while running
 * @param fixedEuPerSecond   EU drawn regardless of machine count, e.g. idle drain. Kept separate
 *                           because a constant cannot live in a per-machine term
 * @param steamPerSecond     millibuckets of steam drawn per second by a single machine, for the
 *                           machines that run on steam instead of electricity. A machine draws one
 *                           or the other, never both, so this is non-zero exactly when
 *                           {@code euInPerSecond} is zero for a steam machine
 * @param overclocks         overclocks the machine performed, which is what raises chanced yields
 * @param confidence         how much to trust these numbers
 * @param note               why, when confidence is not {@link Confidence#EXACT}
 */
public record Throughput(
        double craftsPerSecond,
        double euInPerSecond,
        double euOutPerSecond,
        double fixedEuPerSecond,
        double steamPerSecond,
        int overclocks,
        Confidence confidence,
        String note) {

    /** A recipe with no intrinsic rate, such as hand crafting. */
    public static final Throughput NO_RATE =
            new Throughput(0, 0, 0, 0, 0, 0, Confidence.EXACT, "recipe has no intrinsic rate");

    public Throughput {
        Objects.requireNonNull(confidence, "confidence");
        if (craftsPerSecond < 0 || !Double.isFinite(craftsPerSecond)) {
            throw new IllegalArgumentException("craftsPerSecond must be finite and non-negative");
        }
    }

    public static Throughput exact(double craftsPerSecond, double euInPerSecond, double euOutPerSecond) {
        return new Throughput(craftsPerSecond, euInPerSecond, euOutPerSecond, 0, 0, 0,
                Confidence.EXACT, null);
    }

    public boolean hasRate() {
        return craftsPerSecond > 0;
    }

    /** Whether this machine burns steam rather than drawing EU. */
    public boolean isSteamPowered() {
        return steamPerSecond > 0;
    }

    public Throughput withConfidence(Confidence newConfidence, String newNote) {
        return new Throughput(craftsPerSecond, euInPerSecond, euOutPerSecond, fixedEuPerSecond,
                steamPerSecond, overclocks, newConfidence, newNote);
    }
}
