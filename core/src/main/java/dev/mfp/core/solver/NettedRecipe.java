package dev.mfp.core.solver;

import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A recipe with items that appear on both sides cancelled out.
 *
 * @param recipe    the recipe this was derived from
 * @param inputs    inputs after cancellation; these are what actually flow
 * @param outputs   outputs after cancellation
 * @param catalysts per-craft amounts that cancelled, for display only
 */
public record NettedRecipe(
        MfpRecipe recipe,
        List<MfpIngredient> inputs,
        List<MfpOutput> outputs,
        Map<MfpKey, Double> catalysts) {

    public NettedRecipe {
        Objects.requireNonNull(recipe, "recipe");
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
        catalysts = Map.copyOf(Objects.requireNonNull(catalysts, "catalysts"));
    }

    public boolean hasCatalysts() {
        return !catalysts.isEmpty();
    }
}
