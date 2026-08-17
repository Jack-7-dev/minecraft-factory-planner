package dev.mfp.core.solver;

import dev.mfp.core.model.MfpKey;
import dev.mfp.core.plan.Line;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hand the plan's leftover production back to the lines that made it.
 *
 * <p>The sequential solver splits a line's production as it walks: it knows what was still demanded
 * when the line ran, so anything beyond that is visibly surplus. The whole-plan engines have no such
 * moment. They balance every line at once and learn what is left over only afterwards, from the free
 * variables, so their line results used to put every last drop under {@code outputs()} and leave
 * {@code byproducts()} empty.
 *
 * <p>That was defensible and it read badly. A distillation tower making eight fluids of which the
 * plan wants one showed all eight as products of that line while the Byproducts tab, correctly,
 * listed the other seven — the same plan saying two different things about the same fluid depending
 * on where you looked. The tab was right; the line was the half worth fixing.
 *
 * <p>Attribution is proportional to gross production, which is a choice rather than a deduction.
 * When two lines make the same item and only part of it is consumed, no fact in the solved plan says
 * which line's share went into the consumer — the engines net it. Proportional is the one split that
 * is stable (reorder the lines and nothing moves), sums exactly to the plan-level figure, and never
 * claims a line produced surplus it did not produce.
 */
final class SurplusAttribution {

    private SurplusAttribution() {
    }

    /**
     * Rewrite {@code byLine} so each line's outputs are divided into what the plan consumes and what
     * it does not.
     *
     * @param byLine  solved lines, edited in place
     * @param surplus the plan-level byproducts, keyed by item
     */
    static void apply(Map<Line, LineResult> byLine, Map<MfpKey, Double> surplus) {
        if (surplus.isEmpty()) {
            return;
        }

        Map<MfpKey, Double> gross = new LinkedHashMap<>();
        for (LineResult result : byLine.values()) {
            result.outputs().forEach((key, amount) -> {
                if (surplus.containsKey(key)) {
                    gross.merge(key, amount, Double::sum);
                }
            });
        }

        for (Map.Entry<Line, LineResult> entry : byLine.entrySet()) {
            LineResult result = entry.getValue();
            Map<MfpKey, Double> kept = new LinkedHashMap<>();
            Map<MfpKey, Double> spare = new LinkedHashMap<>();
            for (Map.Entry<MfpKey, Double> output : result.outputs().entrySet()) {
                MfpKey key = output.getKey();
                double amount = output.getValue();
                double share = shareOf(key, amount, gross, surplus);
                if (share > ItemFlows.EPSILON) {
                    spare.put(key, share);
                }
                if (amount - share > ItemFlows.EPSILON) {
                    kept.put(key, amount - share);
                }
            }
            if (spare.isEmpty()) {
                continue;
            }
            entry.setValue(new LineResult(result.line(), result.craftsPerSecond(),
                    result.machineCount(), result.euInPerSecond(), result.euOutPerSecond(),
                    result.steamPerSecond(), result.inputs(), kept, spare,
                    result.confidence(), result.note()));
        }
    }

    /**
     * This line's slice of the leftover, never more than it made.
     *
     * <p>The cap is not defensive tidiness. Surplus is production minus consumption and so cannot
     * exceed production in exact arithmetic, but these numbers come out of a solved linear system,
     * and a rounding error that let the share creep past the amount would show a line producing a
     * negative quantity of its own product.
     */
    private static double shareOf(MfpKey key, double amount,
                                  Map<MfpKey, Double> gross, Map<MfpKey, Double> surplus) {
        Double total = gross.get(key);
        Double leftOver = surplus.get(key);
        if (total == null || leftOver == null || total <= ItemFlows.EPSILON) {
            return 0.0;
        }
        return Math.min(amount, amount * (leftOver / total));
    }
}
