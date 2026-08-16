package dev.mfp.integration.gtceu;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Smallest useful piece of the GregTech integration: counts what is actually there.
 *
 * <p>This exists to prove the GregTech classpath is wired correctly, and it is the seed of the real
 * provider. Note what it demonstrates about the design: {@link GTRecipeType} implements
 * {@link RecipeType}, so GregTech recipes live in the ordinary {@link RecipeManager} and can be
 * enumerated server-side with full fidelity — no recipe viewer involved.
 *
 * <p>Every class in this package may only be loaded once {@code MfpMod.isGregTechLoaded()} is true.
 * Referencing it otherwise raises {@link NoClassDefFoundError}.
 */
public final class GtceuProbe {

    private GtceuProbe() {}

    /** Number of GregTech recipe types registered in this instance. */
    public static int recipeTypeCount() {
        int count = 0;
        for (RecipeType<?> type : ForgeRegistries.RECIPE_TYPES) {
            if (type instanceof GTRecipeType) {
                count++;
            }
        }
        return count;
    }

    /**
     * Total GregTech recipes reachable from the recipe manager.
     *
     * <p>Incomplete on purpose: it misses the recipes GregTech synthesises on demand via
     * {@code GTRecipeType.buildRepresentativeRecipes()} (macerator and arc-furnace recycling,
     * brewery, forming press, scanner research). The real provider must merge those in.
     */
    public static int recipeCount(RecipeManager recipeManager) {
        int count = 0;
        for (RecipeType<?> type : ForgeRegistries.RECIPE_TYPES) {
            if (type instanceof GTRecipeType gtType) {
                count += recipeManager.getAllRecipesFor(gtType).size();
            }
        }
        return count;
    }

    /** Highest voltage tier GregTech defines, e.g. {@code MAX}. */
    public static String highestTierName() {
        return GTValues.VN[GTValues.MAX];
    }

    /** Voltage in EU/t of a tier index, from {@link GTValues#V}. Used later for overclock maths. */
    public static long voltageOfTier(int tier) {
        return GTValues.V[tier];
    }

    /** Compile-time proof that the recipe record's fields are reachable as the plan assumes. */
    public static int durationOf(GTRecipe recipe) {
        return recipe.duration;
    }
}
