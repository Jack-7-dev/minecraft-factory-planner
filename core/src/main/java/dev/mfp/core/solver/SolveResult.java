package dev.mfp.core.solver;

import dev.mfp.core.model.Confidence;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.plan.Line;
import dev.mfp.core.plan.SolverMode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The answer: what to build, what it consumes, what it leaves over.
 *
 * @param lines        per-line results in traversal order
 * @param byLine       the same results, addressable by line
 * @param products     targets actually delivered, per second
 * @param rawInputs    what must be imported because nothing in the plan makes it
 * @param byproducts   surplus nothing in the plan consumes
 * @param unsatisfied  demanded targets the plan could not meet, per second
 * @param euDrawPerSecond      what the whole plan draws, per second. <b>Gross, never netted.</b>
 * @param euGeneratedPerSecond what lines in the plan happen to generate, per second, and which is
 *                             deliberately <em>not</em> subtracted from the draw
 * @param steamDrawPerSecond   millibuckets of steam the plan's steam machines burn, per second.
 *                             Reported beside the EU draw rather than converted into it: the two are
 *                             separate utilities a player has to supply separately, and a boiler is
 *                             not an interchangeable substitute for a power line
 * @param confidence   the weakest confidence of any line
 * @param warnings     problems the user should see rather than have hidden
 * @param engine       which engine actually produced this, which is not always the one the plan
 *                     asked for: an unsolvable system falls back to the sequential pass rather than
 *                     showing nothing, and the user needs to know that is what they are reading
 */
public record SolveResult(
        List<LineResult> lines,
        Map<Line, LineResult> byLine,
        Map<MfpKey, Double> products,
        Map<MfpKey, Double> rawInputs,
        Map<MfpKey, Double> byproducts,
        Map<MfpKey, Double> unsatisfied,
        double euDrawPerSecond,
        double euGeneratedPerSecond,
        double steamDrawPerSecond,
        Confidence confidence,
        List<String> warnings,
        SolverMode engine) {

    public SolveResult {
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        byLine = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(byLine, "byLine")));
        products = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(products, "products")));
        rawInputs = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(rawInputs, "rawInputs")));
        byproducts = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(byproducts, "byproducts")));
        unsatisfied = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(unsatisfied, "unsatisfied")));
        Objects.requireNonNull(confidence, "confidence");
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        Objects.requireNonNull(engine, "engine");
    }

    public LineResult resultFor(Line line) {
        return byLine.get(line);
    }

    /** Whether every target was met in full. */
    public boolean isComplete() {
        return unsatisfied.isEmpty();
    }

    /** Total machines to build across the plan, each line rounded up individually. */
    public long totalMachines() {
        long total = 0;
        for (LineResult result : lines) {
            total += result.machinesToBuild();
        }
        return total;
    }

    /**
     * Whether any line in the plan generates power, which is reported but never netted off the draw.
     *
     * <p>Power generation is out of scope from M6c: a player arrives with a power setup already
     * built, so what a plan owes them is how much this factory will draw, not a plan for how to
     * generate it. A recipe that happens to generate — the steam turbine picked for its distilled
     * water — is still selected for its item, and its output is shown on that line and named as
     * excluded here rather than quietly cancelling somebody else's consumption.
     */
    public boolean generatesPower() {
        return euGeneratedPerSecond > 0;
    }

    /** Whether any line in the plan runs on steam, and so needs a boiler rather than a power line. */
    public boolean drawsSteam() {
        return steamDrawPerSecond > 0;
    }
}
