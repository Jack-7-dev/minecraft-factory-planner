package dev.mfp.core.solver;

import dev.mfp.core.model.ChanceMode;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cancels items that a recipe both consumes and produces.
 *
 * <p>Ported from Factory Planner's {@code Recipe:build_items}. When a recipe takes 1000 mB of
 * lubricant and gives 1000 mB back, no lubricant flows: it is a fixed setup cost, not a consumable.
 * Leaving it uncancelled makes the solver demand a supply chain for something the recipe is merely
 * borrowing, which is how a plan sprouts a phantom lubricant factory.
 *
 * <p>Cancellation is per craft and applies to the smaller side:
 * <pre>
 *   input &gt; output  → input shrinks by the output amount, the output disappears
 *   output &gt; input  → output shrinks by the input amount, the input disappears
 *   equal           → both disappear entirely
 * </pre>
 *
 * <p>Two deliberate exclusions:
 *
 * <ul>
 *   <li><b>Ambiguous inputs are never netted.</b> A tag ingredient that merely <em>could</em> be
 *       satisfied by the output item is not the same as one that is. Cancelling on a maybe would
 *       remove a real material requirement.
 *   <li><b>Chanced outputs are never netted.</b> They are not reliably returned, so treating them
 *       as cancelling a guaranteed input would understate what the chain consumes.
 * </ul>
 */
public final class RecipeNetting {

    /** Amounts below this are treated as zero, to keep floating-point residue out of the plan. */
    private static final double EPSILON = 1e-9;

    private RecipeNetting() {}

    public static NettedRecipe net(MfpRecipe recipe) {
        Map<MfpKey, Double> outputAmounts = new LinkedHashMap<>();
        for (MfpOutput output : recipe.outputs()) {
            if (output.mode() == ChanceMode.ALWAYS) {
                outputAmounts.merge(output.key(), output.amount(), Double::sum);
            }
        }

        Map<MfpKey, Double> catalysts = new LinkedHashMap<>();
        List<MfpIngredient> nettedInputs = new ArrayList<>(recipe.inputs().size());
        Map<MfpKey, Double> cancelledFromOutputs = new LinkedHashMap<>();

        for (MfpIngredient input : recipe.inputs()) {
            // Non-consumed inputs already flow nothing, and an ambiguous input might not be the
            // item coming back out, so neither is a candidate for cancellation.
            if (!input.consumed() || input.isAmbiguous()) {
                nettedInputs.add(input);
                continue;
            }

            MfpKey key = input.primary();
            double available = outputAmounts.getOrDefault(key, 0.0)
                    - cancelledFromOutputs.getOrDefault(key, 0.0);
            if (available <= EPSILON) {
                nettedInputs.add(input);
                continue;
            }

            double cancelled = Math.min(input.amount(), available);
            cancelledFromOutputs.merge(key, cancelled, Double::sum);
            catalysts.merge(key, cancelled, Double::sum);

            double remaining = input.amount() - cancelled;
            if (remaining > EPSILON) {
                nettedInputs.add(new MfpIngredient(input.candidates(), remaining,
                        input.consumed(), input.chance()));
            }
        }

        List<MfpOutput> nettedOutputs = new ArrayList<>(recipe.outputs().size());
        for (MfpOutput output : recipe.outputs()) {
            if (output.mode() != ChanceMode.ALWAYS) {
                nettedOutputs.add(output);
                continue;
            }
            double toCancel = cancelledFromOutputs.getOrDefault(output.key(), 0.0);
            if (toCancel <= EPSILON) {
                nettedOutputs.add(output);
                continue;
            }
            double cancelled = Math.min(output.amount(), toCancel);
            cancelledFromOutputs.merge(output.key(), -cancelled, Double::sum);

            double remaining = output.amount() - cancelled;
            if (remaining > EPSILON) {
                nettedOutputs.add(new MfpOutput(output.key(), remaining, output.chance(),
                        output.mode(), output.groupKey()));
            }
        }

        return new NettedRecipe(recipe, nettedInputs, nettedOutputs, catalysts);
    }
}
