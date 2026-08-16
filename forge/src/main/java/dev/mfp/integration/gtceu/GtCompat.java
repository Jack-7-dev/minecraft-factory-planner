package dev.mfp.integration.gtceu;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.ingredient.EnergyStack;

/**
 * The one place that knows which generation of GregTech Modern we are compiled against.
 *
 * <p>MFP builds against the Star-Technology fork (GTCEu Modern 1.7.0b, LDLib-based), which is a
 * different generation from upstream 8.x: the integration packages are laid out differently and
 * older GTCEu represents EU as a plain {@code long} content rather than an {@link EnergyStack}.
 * Everything outside this class is written against the narrow surface that is stable across both
 * generations, so retargeting means editing this file rather than the converter.
 *
 * <p>It is deliberately thin. The fork's own source is the compile target, so this is a seam kept
 * open on purpose, not an abstraction load-bearing today.
 */
final class GtCompat {

    private GtCompat() {}

    /** EU/t the recipe draws, with its amperage. Empty for unpowered recipes. */
    static EnergyStack inputEnergy(GTRecipe recipe) {
        return recipe.getInputEUt();
    }

    /** EU/t the recipe produces — generators and turbines. Empty for consumers. */
    static EnergyStack outputEnergy(GTRecipe recipe) {
        return recipe.getOutputEUt();
    }

    /**
     * Amperage to record for the recipe.
     *
     * <p>A recipe is either a consumer or a generator, so whichever side carries energy is the side
     * that carries the amperage; 1 when neither does.
     */
    static long amperage(GTRecipe recipe) {
        EnergyStack in = inputEnergy(recipe);
        if (!in.isEmpty()) {
            return Math.max(1L, in.amperage());
        }
        EnergyStack out = outputEnergy(recipe);
        return out.isEmpty() ? 1L : Math.max(1L, out.amperage());
    }

    /** Voltage tier the recipe needs, or {@code -1} when it uses no energy at all. */
    static int voltageTier(GTRecipe recipe) {
        if (inputEnergy(recipe).isEmpty() && outputEnergy(recipe).isEmpty()) {
            return -1;
        }
        return RecipeHelper.getRecipeEUtTier(recipe);
    }

    /** EU/t a machine of this tier can push, or 0 when the tier index is out of range. */
    static long voltageOfTier(int tier) {
        return tier >= 0 && tier < GTValues.V.length ? GTValues.V[tier] : 0L;
    }

    /** Short tier name such as {@code LV} or {@code UHV}, or null when out of range. */
    static String tierName(int tier) {
        return tier >= 0 && tier < GTValues.VN.length ? GTValues.VN[tier] : null;
    }
}
