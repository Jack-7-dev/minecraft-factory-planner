package dev.mfp.client;

import dev.mfp.core.behaviour.ThroughputResult;
import dev.mfp.core.plan.Line;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.select.ChooserResult;
import dev.mfp.core.solver.BehaviourThroughputResolver;
import dev.mfp.core.solver.SolveResult;

import java.util.Objects;

/**
 * A solved plan, together with everything needed to explain it.
 *
 * <p>The same four objects {@code PlanSession} keeps on the server, for the same reason: the GUI
 * must render the answer that was computed, never recompute one while drawing. A table that
 * re-solved per frame could show numbers that never existed together, which is the one thing a
 * planner must not do.
 *
 * @param chooseMicros how long choosing the recipes took, in microseconds - a separate number from the
 *                     solve on purpose: they fail differently, and the one that gets slow is
 *                     usually this one. A loop makes the chooser walk the whole graph again for
 *                     every attempt to steer around it, so a plan reading "chose in 400 ms" is
 *                     reporting a loop rather than a big matrix (STATUS 9.10).
 * @param solveMicros how long the solve itself took, in microseconds. Worth showing because the
 *                    first one also builds the index and is noticeably slower than the rest - and
 *                    worth keeping finer than a millisecond, because a four-line matrix solve
 *                    genuinely takes a fraction of one and "0 ms" reads as a broken clock
 */
public record ClientPlan(
        Plan plan,
        ChooserResult chooserResult,
        SolveResult solveResult,
        BehaviourThroughputResolver resolver,
        long chooseMicros,
        long solveMicros) {

    /** Microseconds as milliseconds, to one decimal: {@code 0.4 ms}, {@code 438 ms}. */
    public static String millis(long micros) {
        return micros >= 10_000
                ? String.valueOf(micros / 1000) + " ms"
                : String.format(java.util.Locale.ROOT, "%.1f ms", micros / 1000.0);
    }

    public ClientPlan {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(chooserResult, "chooserResult");
        Objects.requireNonNull(solveResult, "solveResult");
        Objects.requireNonNull(resolver, "resolver");
    }

    /**
     * The behaviour chain's verdict for one line: effective duration, EU/t, notes and whether the
     * machine can run the recipe at all.
     *
     * <p>Resolved once when the table is built rather than per frame — it walks the behaviour
     * registry, and the answer cannot change while the screen is open.
     */
    public ThroughputResult throughputFor(Line line) {
        return resolver.resolveResult(line.recipe(), line.machine());
    }
}
