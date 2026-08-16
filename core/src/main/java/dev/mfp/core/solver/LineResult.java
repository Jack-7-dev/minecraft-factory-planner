package dev.mfp.core.solver;

import dev.mfp.core.model.Confidence;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.plan.Line;

import java.util.Map;
import java.util.Objects;

/**
 * What the solver worked out for one line.
 *
 * <p>{@code machineCount} stays fractional. Rounding up here would be wrong twice over: it breaks
 * the linearity the matrix engine depends on, and it compounds — a chain of five lines each rounded
 * up overstates the build considerably. Ceiling belongs in the display layer, once.
 *
 * @param line             the line this describes
 * @param craftsPerSecond  how fast the line runs in total
 * @param machineCount     machines needed, fractional; zero when the recipe has no intrinsic rate
 * @param euInPerSecond    energy drawn
 * @param euOutPerSecond   energy produced
 * @param steamPerSecond   millibuckets of steam drawn, for a line whose machine runs on steam. Such
 *                         a line draws no EU at all, so this is an alternative to
 *                         {@code euInPerSecond} rather than an addition to it
 * @param inputs           per-second consumption
 * @param outputs          per-second production that was demanded
 * @param byproducts       per-second production nothing asked for
 * @param confidence       trustworthiness of the derived numbers
 * @param note             explanation when confidence is not exact
 */
public record LineResult(
        Line line,
        double craftsPerSecond,
        double machineCount,
        double euInPerSecond,
        double euOutPerSecond,
        double steamPerSecond,
        Map<MfpKey, Double> inputs,
        Map<MfpKey, Double> outputs,
        Map<MfpKey, Double> byproducts,
        Confidence confidence,
        String note) {

    public LineResult {
        Objects.requireNonNull(line, "line");
        inputs = Map.copyOf(Objects.requireNonNull(inputs, "inputs"));
        outputs = Map.copyOf(Objects.requireNonNull(outputs, "outputs"));
        byproducts = Map.copyOf(Objects.requireNonNull(byproducts, "byproducts"));
        Objects.requireNonNull(confidence, "confidence");
    }

    /** Machines actually built, i.e. the fractional count rounded up. */
    public long machinesToBuild() {
        return (long) Math.ceil(machineCount - ItemFlows.EPSILON);
    }

    /** Whether this line's machines burn steam rather than drawing EU. */
    public boolean isSteamPowered() {
        return steamPerSecond > 0;
    }

    /** True when the line contributes nothing, usually because nothing demanded its output. */
    public boolean isIdle() {
        return craftsPerSecond <= ItemFlows.EPSILON;
    }
}
