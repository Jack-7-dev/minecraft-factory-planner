package dev.mfp.core.solver;

import dev.mfp.core.model.Confidence;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.plan.Line;

import java.util.Map;

/**
 * One line reduced to what a whole-plan engine needs: its net contents per craft.
 *
 * <p>Shared by {@link MatrixSolver} and {@link SimplexSolver}, and that sharing is the point rather
 * than tidiness. The two engines must agree about what a line <em>is</em> — the same netting, the
 * same chance handling, the same routing of idle drain to the right-hand side — or the acceptance
 * that they produce identical numbers on plans both can solve is testing nothing. Only the shape of
 * the system they build from these columns differs: equalities in one, inequalities in the other.
 *
 * @param line       the plan line this came from
 * @param throughput the resolved rate and energy, per machine
 * @param perCraft   net amount of each item per craft; positive produced, negative consumed
 * @param confidence how much to trust it
 * @param note       why, when confidence is not exact
 */
record Column(Line line, Throughput throughput, Map<MfpKey, Double> perCraft,
              Confidence confidence, String note) {

    /**
     * The item this line exists to make, for the settings that need to name one.
     *
     * <p>The user's priority item when they set one, otherwise the largest produced flow — which for
     * a GregTech recipe is the main product often enough, and is at least a stable choice rather
     * than a map-iteration accident. Ties break on the key's own ordering for determinism.
     */
    MfpKey mainProduct() {
        MfpKey priority = line.priorityItem();
        if (priority != null && perCraft().getOrDefault(priority, 0.0) > ItemFlows.EPSILON) {
            return priority;
        }
        MfpKey best = null;
        double bestAmount = ItemFlows.EPSILON;
        for (Map.Entry<MfpKey, Double> entry : perCraft.entrySet()) {
            if (entry.getKey().equals(MfpKey.EU)) {
                continue;
            }
            double amount = entry.getValue();
            if (amount > bestAmount
                    || (amount == bestAmount && best != null && entry.getKey().toString().compareTo(best.toString()) < 0)) {
                best = entry.getKey();
                bestAmount = amount;
            }
        }
        return best;
    }
}
