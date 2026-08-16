package dev.mfp.provider.vanilla;

import dev.mfp.core.index.MfpRecipeSink;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.provider.CollectionContext;
import dev.mfp.provider.GameKeys;
import dev.mfp.provider.MfpRecipeProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Vanilla crafting, smelting, blasting, smoking, campfire cooking and stonecutting.
 *
 * <p>Low priority: when GregTech proxies a vanilla recipe into one of its own machine types, the
 * GregTech version carries EU/t and duration and should win.
 *
 * <p>Beyond being useful on its own, this provider is how the SPI gets exercised without GregTech
 * installed — the M2 acceptance check runs in a plain vanilla world.
 */
public final class VanillaRecipeProvider implements MfpRecipeProvider {

    public static final String ID = "vanilla";

    /** What the vanilla cooking machines burn, per {@code RawMaterials} and the pack's own habits. */
    private static final MfpKey FUEL = MfpKey.item("minecraft", "charcoal");

    /** Charcoal's burn time in ticks, which is what turns a cooking time into a fuel cost. */
    private static final double CHARCOAL_BURN_TICKS = 1600.0;

    /** Set on a recipe whose machine burns fuel, so the scorer can prefer an electric equivalent. */
    public static final String BURNS_FUEL = "mfp:burns_fuel";

    private static final String CAMPFIRE = "minecraft:campfire_cooking";

    /**
     * Machines that give vanilla recipes a rate. Hand crafting deliberately has none.
     *
     * <p><b>Stonecutting is deliberately absent</b>, and its absence removes its recipes as well as
     * its machine: a type nothing here claims is left to other providers and never converted. The
     * stonecutter is a block-shape converter — stone to stairs, to slabs, to bricks and back around —
     * so every recipe it contributes is a sideways move between forms of the same material, and none
     * of them is a step in making anything. In Star-Technology nothing uses it at all. Leaving it in
     * costs a machine that cannot be automated and a few hundred recipes that only give the chooser
     * new ways to go in circles.
     *
     * <p>The crafting table stays for now despite having the same automation problem. Its recipes are
     * real routes and often the only route, so dropping them would turn genuinely craftable items
     * into unresolved imports. It goes when there is an automatable crafter to replace it with.
     */
    private static final Map<String, String[]> MACHINES_BY_TYPE = Map.of(
            "minecraft:crafting", new String[] {"minecraft:crafting_table", "Crafting Table"},
            "minecraft:smelting", new String[] {"minecraft:furnace", "Furnace"},
            "minecraft:blasting", new String[] {"minecraft:blast_furnace", "Blast Furnace"},
            "minecraft:smoking", new String[] {"minecraft:smoker", "Smoker"},
            "minecraft:campfire_cooking", new String[] {"minecraft:campfire", "Campfire"});

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void collect(MfpRecipeSink sink, CollectionContext context) {
        for (Map.Entry<String, String[]> entry : MACHINES_BY_TYPE.entrySet()) {
            String[] machine = entry.getValue();
            sink.machine(MfpMachine.simple(machine[0], machine[1], entry.getKey(), ID));
        }

        for (Recipe<?> recipe : context.recipeManager().getRecipes()) {
            String recipeId = String.valueOf(recipe.getId());
            try {
                convert(recipe, context, sink, recipeId);
            } catch (RuntimeException e) {
                // One malformed recipe costs one recipe, never the whole index (plan P8).
                sink.skip(recipeId, "conversion failed: " + e);
            }
        }
    }

    private void convert(Recipe<?> recipe, CollectionContext context, MfpRecipeSink sink, String recipeId) {
        ResourceLocation typeId = ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType());
        if (typeId == null) {
            sink.skip(recipeId, "recipe type is not registered");
            return;
        }
        String recipeTypeId = typeId.toString();
        if (!MACHINES_BY_TYPE.containsKey(recipeTypeId)) {
            // Another provider owns this type — GregTech's own types arrive here too.
            return;
        }

        // Special recipes (armour dyeing, firework crafting, map cloning) compute their result from
        // the inputs at craft time, so they have no fixed output to plan against. Excluded rather
        // than skipped: they are not broken, they are simply not plannable.
        if (recipe.isSpecial()) {
            return;
        }

        ItemStack result = recipe.getResultItem(context.registries());
        if (result == null || result.isEmpty()) {
            sink.skip(recipeId, "no fixed result");
            return;
        }
        MfpKey resultKey = GameKeys.of(result);
        if (resultKey == null) {
            sink.skip(recipeId, "result item is not registered");
            return;
        }

        MfpRecipe.Builder builder = MfpRecipe.builder(qualify(recipeTypeId, recipeId), recipeTypeId, ID)
                .duration(durationOf(recipe))
                .output(new MfpOutput(resultKey, result.getCount(), 1.0,
                        dev.mfp.core.model.ChanceMode.ALWAYS, null));

        for (MfpIngredient ingredient : mergeIngredients(recipe.getIngredients())) {
            builder.input(ingredient);
        }

        double fuel = fuelPerCraft(recipe, recipeTypeId);
        if (fuel > 0) {
            builder.input(MfpIngredient.of(FUEL, fuel));
            builder.extra(BURNS_FUEL, 1);
        }

        sink.recipe(builder.build());
    }

    /**
     * The recipe id, prefixed with the machine that runs it.
     *
     * <p>Not cosmetic. GregTech ingests every vanilla smelting recipe into its own electric furnace
     * type <em>under the original id</em>, and the index resolves duplicate ids by provider
     * priority — so 1,623 vanilla recipes were being overridden, and the vanilla furnace was left
     * with nothing to run at all. It was not that the furnace ranked badly; it was that MFP had
     * decided the two recipes were the same recipe.
     *
     * <p>They are not. They make the same item in different machines, at different speeds, from
     * different utilities — one takes a cable, the other takes charcoal — which is exactly the choice
     * the picker exists to offer. Qualifying the id keeps both, and reads the way GregTech's own ids
     * already do ({@code gtceu:macerator/iron}).
     */
    private static String qualify(String recipeTypeId, String recipeId) {
        return recipeTypeId + '/' + recipeId;
    }

    /**
     * Charcoal per craft, for the machines that burn something.
     *
     * <p>A furnace's real cost is fuel, and leaving it out made the vanilla machines look free. One
     * charcoal burns for 1,600 ticks, so a 200-tick smelt costs an eighth of one and a 100-tick blast
     * costs a sixteenth — the fraction is the point, and it stays fractional through the solver like
     * every other rate.
     *
     * <p>Charcoal specifically, because it is renewable, it is what a player actually feeds a furnace
     * bank in this pack, and it is on the shipped raw-material list — so the plan reports the fuel it
     * needs without expanding a chain to produce it. Any other fuel is the player's substitution to
     * make; the arithmetic scales linearly with burn time.
     *
     * <p>Campfires are excluded: they cook without consuming their fuel.
     */
    private static double fuelPerCraft(Recipe<?> recipe, String recipeTypeId) {
        if (!(recipe instanceof AbstractCookingRecipe cooking) || CAMPFIRE.equals(recipeTypeId)) {
            return 0;
        }
        return cooking.getCookingTime() / CHARCOAL_BURN_TICKS;
    }

    /**
     * Collapse per-slot ingredients into per-recipe amounts.
     *
     * <p>Vanilla models a shaped recipe as one {@link Ingredient} per grid slot, each meaning "one
     * item". Nine iron ingots therefore arrive as nine separate entries of count one. Passing those
     * through unmerged would tell the solver a block of iron needs one ingot nine times over rather
     * than nine ingots, and every downstream rate would be wrong by that factor.
     *
     * <p>Ingredients are merged by their candidate set, so two different tags that happen to share
     * a slot count stay distinct.
     */
    private static List<MfpIngredient> mergeIngredients(List<Ingredient> ingredients) {
        Map<List<MfpKey>, Double> amounts = new LinkedHashMap<>();

        for (Ingredient ingredient : ingredients) {
            if (ingredient == null || ingredient.isEmpty()) {
                continue;
            }
            List<MfpKey> candidates = candidatesOf(ingredient);
            if (candidates.isEmpty()) {
                continue;
            }
            amounts.merge(candidates, 1.0, Double::sum);
        }

        List<MfpIngredient> merged = new ArrayList<>(amounts.size());
        amounts.forEach((candidates, amount) -> merged.add(MfpIngredient.ofAny(candidates, amount)));
        return merged;
    }

    /** Every item that satisfies an ingredient, so tags stay ambiguous instead of being narrowed. */
    private static List<MfpKey> candidatesOf(Ingredient ingredient) {
        Set<MfpKey> candidates = new LinkedHashSet<>();
        for (ItemStack stack : ingredient.getItems()) {
            MfpKey key = GameKeys.of(stack);
            if (key != null) {
                candidates.add(key);
            }
        }
        return new ArrayList<>(candidates);
    }

    private static double durationOf(Recipe<?> recipe) {
        if (recipe instanceof AbstractCookingRecipe cooking) {
            return cooking.getCookingTime();
        }
        // Crafting and stonecutting are instantaneous player actions, not timed processes.
        return MfpRecipe.INSTANT;
    }
}
