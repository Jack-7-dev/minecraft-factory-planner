package dev.mfp.integration.gtceu;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.category.GTRecipeCategory;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import dev.mfp.core.index.MfpRecipeSink;
import dev.mfp.core.model.MfpRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Enumerates every GregTech recipe in the running instance.
 *
 * <p>No recipe viewer is involved, and none is needed. {@code GTRecipeType} implements vanilla's
 * {@code RecipeType}, so GregTech recipes live in the ordinary {@link RecipeManager} and can be read
 * server-side with full fidelity — EU/t, duration, chances, conditions and all. Going through EMI
 * would only mean reflecting back onto the same objects, with less information and a client
 * requirement attached.
 *
 * <p>Two sources are merged, because neither is complete on its own:
 *
 * <ol>
 *   <li>the {@link RecipeManager}, which holds every datapack recipe after KubeJS has finished
 *       rewriting them;
 *   <li>each recipe type's category map after {@code buildRepresentativeRecipes()}, which is where
 *       recipes with no JSON at all live — macerator and arc-furnace recycling, brewing, forming
 *       press, scanner research. These are synthesised from the final item set, so they exist only
 *       once the type has been asked to build them.
 * </ol>
 *
 * <p>The two overlap, so results are deduplicated by recipe id with the recipe manager winning.
 */
public final class GtceuCollector {

    private static final Logger LOGGER = LoggerFactory.getLogger("MFP");

    private GtceuCollector() {}

    public static void collect(MfpRecipeSink sink, RecipeManager recipeManager, String providerId) {
        int machines = GtMachineCatalog.collect(sink, providerId);

        Set<String> seen = new HashSet<>();
        int fromManager = collectFromRecipeManager(sink, recipeManager, providerId, seen);
        int synthetic = collectSynthetic(sink, providerId, seen);
        // After the recipes, because classification resolves lazy material suppliers and the
        // recycling recipes above are generated from the same table.
        int forms = GtMaterialForms.collect(sink);
        // The items whose tier is a gate rather than a cost (M17). Reported in the same line
        // because the number dropping is the symptom of a fork that has moved its tags, and a
        // classifier nobody watches is one that silently stops classifying.
        GtComponentTiers.Result components = GtComponentTiers.collect(sink);

        LOGGER.info("MFP GregTech ingestion: {} recipes from the recipe manager, {} synthesised, "
                        + "{} machines, {} items classified by form, {} tiered components "
                        + "({} circuits)",
                fromManager, synthetic, machines, forms,
                components.classified(), components.circuits());
        if (!components.unrecognised().isEmpty()) {
            LOGGER.warn("MFP: {} item(s) look like GregTech components but carry no tier in their "
                            + "id, so they are NOT gated by tier - run /mfp components: {}",
                    components.unrecognised().size(),
                    components.unrecognised().size() > 12
                            ? components.unrecognised().subList(0, 12) + " ..."
                            : components.unrecognised());
        }
    }

    private static int collectFromRecipeManager(MfpRecipeSink sink,
                                                RecipeManager recipeManager,
                                                String providerId,
                                                Set<String> seen) {
        int count = 0;
        for (GTRecipeType type : GTRegistries.RECIPE_TYPES) {
            if (type == null) {
                continue;
            }
            List<GTRecipe> recipes;
            try {
                recipes = recipeManager.getAllRecipesFor(type);
            } catch (RuntimeException e) {
                sink.skip(String.valueOf(type.registryName), "could not list recipes: " + e);
                continue;
            }
            for (GTRecipe recipe : recipes) {
                if (emit(sink, recipe, providerId, seen, false)) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Ask every recipe type to build its synthetic recipes, then read them out of its category map.
     *
     * <p>Building is per type and guarded, because these run real generation logic over the whole
     * item registry: one type that throws must cost only its own recipes.
     */
    private static int collectSynthetic(MfpRecipeSink sink, String providerId, Set<String> seen) {
        int count = 0;
        for (GTRecipeType type : GTRegistries.RECIPE_TYPES) {
            if (type == null) {
                continue;
            }
            try {
                type.buildRepresentativeRecipes();
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("MFP could not build representative recipes for {}", type.registryName, e);
                sink.skip(String.valueOf(type.registryName), "buildRepresentativeRecipes failed: " + e);
            }

            for (GTRecipeCategory category : type.getCategories()) {
                if (category == null) {
                    continue;
                }
                for (GTRecipe recipe : type.getRecipesInCategory(category)) {
                    if (emit(sink, recipe, providerId, seen, true)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** Convert and accept one recipe. Returns whether it was new and converted successfully. */
    private static boolean emit(MfpRecipeSink sink,
                                GTRecipe recipe,
                                String providerId,
                                Set<String> seen,
                                boolean synthetic) {
        if (recipe == null) {
            return false;
        }

        ResourceLocation id = recipe.getId();
        if (id == null) {
            // Without an id there is nothing to deduplicate against and nothing for /mfp recipe to
            // look up. Rare enough to be worth reporting rather than inventing a name for.
            sink.skip("<" + recipe.recipeType.registryName + ":unnamed>", "recipe has no id");
            return false;
        }

        String recipeId = id.toString();
        if (!seen.add(recipeId)) {
            return false;
        }

        try {
            MfpRecipe converted = GtRecipeConverter.convert(recipe, recipeId, providerId, synthetic);
            sink.recipe(converted);
            return true;
        } catch (RuntimeException e) {
            // One unconvertible recipe out of tens of thousands costs one recipe (plan P8).
            sink.skip(recipeId, "conversion failed: " + e);
            return false;
        }
    }
}
