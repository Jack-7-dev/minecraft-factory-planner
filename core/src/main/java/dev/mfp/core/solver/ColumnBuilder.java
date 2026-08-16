package dev.mfp.core.solver;

import dev.mfp.core.model.Confidence;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.Line;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns plan lines into {@link Column}s — the {@code A} of {@code Ax = b}, built once and reused.
 *
 * <p>Plan §9.2 asked for this seam from the beginning: keep the construction of the system separate
 * from the step that solves it, so a second engine could be added without inheriting the first
 * one's assumptions. {@link GaussJordan} was the first half of that promise and this is the second.
 * The matrix engine and the simplex engine now differ only in what they do with these columns.
 */
final class ColumnBuilder {

    private final ThroughputResolver resolver;
    private final Map<MfpRecipe, NettedRecipe> nettingCache = new IdentityHashMap<>();

    ColumnBuilder(ThroughputResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * One line's net effect per craft.
     *
     * <p>Netting first, exactly as the sequential engine does, so a catalyst that a recipe borrows
     * and returns never enters the system. Leaving it in would be harmless arithmetically but would
     * add a row constrained to zero for an item that never actually flows, which is one more way for
     * the system to look over-determined for no reason.
     */
    Column column(Line line, List<String> warnings) {
        MfpRecipe recipe = line.recipe();
        NettedRecipe netted = nettingCache.computeIfAbsent(recipe, RecipeNetting::net);
        Throughput throughput = resolver.resolve(recipe, line.machine());

        Map<MfpKey, Double> perCraft = new LinkedHashMap<>();
        for (MfpIngredient input : netted.inputs()) {
            double amount = input.effectiveAmount();
            if (amount > ItemFlows.EPSILON) {
                perCraft.merge(input.primary(), -amount, Double::sum);
            }
        }

        Confidence confidence = throughput.confidence();
        String note = throughput.note();
        for (ChanceResolver.Resolved resolved : ChanceResolver.resolve(netted.outputs(),
                throughput.overclocks())) {
            if (resolved.perCraft() > ItemFlows.EPSILON) {
                perCraft.merge(resolved.key(), resolved.perCraft(), Double::sum);
            }
            if (!resolved.exact()) {
                confidence = confidence.and(Confidence.APPROXIMATE);
                note = note != null ? note
                        : "output " + resolved.key() + " competes with others for its chance roll";
            }
        }

        // Energy per craft, so it scales with the column like every other content (plan P3). The
        // per-second figures are per machine, so dividing by the per-machine craft rate gives the
        // per-craft amount the column needs.
        double cps = throughput.craftsPerSecond();
        if (cps > ItemFlows.EPSILON) {
            if (throughput.euInPerSecond() > 0) {
                perCraft.merge(MfpKey.EU, -throughput.euInPerSecond() / cps, Double::sum);
            }
            if (throughput.euOutPerSecond() > 0) {
                perCraft.merge(MfpKey.EU, throughput.euOutPerSecond() / cps, Double::sum);
            }
        } else if (throughput.euInPerSecond() > 0 || throughput.euOutPerSecond() > 0) {
            warnings.add(recipe.id() + " draws or produces energy but has no craft rate, so its "
                    + "energy cannot be expressed per craft and was left out of the balance");
        }

        perCraft.values().removeIf(amount -> Math.abs(amount) <= ItemFlows.EPSILON);
        return new Column(line, throughput, perCraft, confidence, note);
    }
}
