package dev.mfp.core.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecipeIndexTest {

    private static final MfpKey IRON = MfpKey.item("minecraft", "iron_ingot");
    private static final MfpKey DUST = MfpKey.item("gtceu", "iron_dust");
    private static final MfpKey PLATE = MfpKey.item("gtceu", "iron_plate");
    private static final MfpKey COPPER_PLATE = MfpKey.item("gtceu", "copper_plate");
    private static final MfpKey CIRCUIT = MfpKey.item("gtceu", "programmed_circuit", "cfg4");

    private static MfpRecipe recipe(String id, MfpKey in, MfpKey out) {
        return recipe(id, in, out, "gtceu");
    }

    private static MfpRecipe recipe(String id, MfpKey in, MfpKey out, String providerId) {
        return MfpRecipe.builder(id, "gtceu:macerator", providerId)
                .input(MfpIngredient.of(in, 1))
                .output(MfpOutput.of(out, 1))
                .duration(100)
                .build();
    }

    private static List<String> ids(List<MfpRecipe> recipes) {
        return recipes.stream().map(MfpRecipe::id).toList();
    }

    @Test
    void producingFindsRecipesByOutput() {
        RecipeIndex.Builder builder = RecipeIndex.builder().beginProvider("gtceu", 100);
        builder.recipe(recipe("gtceu:a", IRON, DUST));
        builder.recipe(recipe("gtceu:b", PLATE, DUST));
        builder.recipe(recipe("gtceu:c", IRON, PLATE));
        RecipeIndex index = builder.build();

        assertEquals(List.of("gtceu:a", "gtceu:b"), ids(index.producing(DUST)));
        assertEquals(1, index.producing(PLATE).size());
        assertTrue(index.producing(COPPER_PLATE).isEmpty());
    }

    @Test
    void consumingFindsRecipesByInput() {
        RecipeIndex.Builder builder = RecipeIndex.builder().beginProvider("gtceu", 100);
        builder.recipe(recipe("gtceu:a", IRON, DUST));
        builder.recipe(recipe("gtceu:c", IRON, PLATE));
        RecipeIndex index = builder.build();

        assertEquals(2, index.consuming(IRON).size());
    }

    /** A tag input must be reachable from every candidate, since MFP must not pick one for the user. */
    @Test
    void tagIngredientIsIndexedUnderEveryCandidate() {
        RecipeIndex.Builder builder = RecipeIndex.builder().beginProvider("gtceu", 100);
        builder.recipe(MfpRecipe.builder("gtceu:assembler/x", "gtceu:assembler", "gtceu")
                .input(MfpIngredient.ofAny(List.of(PLATE, COPPER_PLATE), 2))
                .output(MfpOutput.of(DUST, 1))
                .duration(100)
                .build());
        RecipeIndex index = builder.build();

        assertEquals(1, index.consuming(PLATE).size());
        assertEquals(1, index.consuming(COPPER_PLATE).size());
    }

    /** Non-consumed inputs are still "used here", so lookup finds them even though nothing flows. */
    @Test
    void nonConsumedInputsAreStillDiscoverable() {
        RecipeIndex.Builder builder = RecipeIndex.builder().beginProvider("gtceu", 100);
        builder.recipe(MfpRecipe.builder("gtceu:assembler/y", "gtceu:assembler", "gtceu")
                .input(MfpIngredient.notConsumed(CIRCUIT))
                .input(MfpIngredient.of(IRON, 1))
                .output(MfpOutput.of(PLATE, 1))
                .duration(100)
                .build());
        RecipeIndex index = builder.build();

        assertEquals(1, index.consuming(CIRCUIT).size());
        assertFalse(index.consuming(CIRCUIT).get(0).consumes(CIRCUIT));
    }

    @Test
    void higherPriorityProviderWinsDuplicateIds() {
        RecipeIndex.Builder builder = RecipeIndex.builder();
        builder.beginProvider("vanilla", 10);
        builder.recipe(recipe("shared:id", IRON, DUST, "vanilla"));
        builder.beginProvider("gtceu", 100);
        builder.recipe(recipe("shared:id", IRON, PLATE, "gtceu"));
        RecipeIndex index = builder.build();

        assertEquals(1, index.stats().recipeCount());
        assertEquals("gtceu", index.recipe("shared:id").providerId());
        assertEquals(PLATE, index.recipe("shared:id").outputs().get(0).key());
        assertEquals(1, index.stats().overridden());
    }

    /** Ties keep the incumbent, so collection order cannot change the resulting index. */
    @Test
    void equalPriorityKeepsTheFirstContribution() {
        RecipeIndex.Builder builder = RecipeIndex.builder();
        builder.beginProvider("first", 50);
        builder.recipe(recipe("shared:id", IRON, DUST, "first"));
        builder.beginProvider("second", 50);
        builder.recipe(recipe("shared:id", IRON, PLATE, "second"));
        RecipeIndex index = builder.build();

        assertEquals("first", index.recipe("shared:id").providerId());
        assertEquals(DUST, index.recipe("shared:id").outputs().get(0).key());
    }

    @Test
    void skipsAreRecordedWithProviderAndReason() {
        RecipeIndex.Builder builder = RecipeIndex.builder().beginProvider("gtceu", 100);
        builder.skip("gtceu:weird", "unsupported capability");
        RecipeIndex index = builder.build();

        assertFalse(index.stats().isClean());
        IndexStats.Skip skip = index.stats().skips().get(0);
        assertEquals("gtceu", skip.providerId());
        assertEquals("gtceu:weird", skip.recipeId());
        assertEquals("unsupported capability", skip.reason());
    }

    @Test
    void machinesAreLookedUpByRecipeType() {
        RecipeIndex.Builder builder = RecipeIndex.builder().beginProvider("gtceu", 100);
        builder.machine(MfpMachine.simple("gtceu:lv_macerator", "LV Macerator", "gtceu:macerator", "gtceu"));
        builder.machine(MfpMachine.simple("gtceu:mv_macerator", "MV Macerator", "gtceu:macerator", "gtceu"));
        builder.machine(MfpMachine.simple("gtceu:lv_bender", "LV Bender", "gtceu:bender", "gtceu"));
        RecipeIndex index = builder.build();

        assertEquals(2, index.machinesFor("gtceu:macerator").size());
        assertEquals(1, index.machinesFor("gtceu:bender").size());
        assertTrue(index.machinesFor("gtceu:nonexistent").isEmpty());
        assertEquals("LV Macerator", index.machine("gtceu:lv_macerator").displayName());
    }

    /** Same input, same index — otherwise plans stop reproducing and "why this recipe?" has no answer. */
    @Test
    void resultsAreSortedByIdForDeterminism() {
        RecipeIndex.Builder builder = RecipeIndex.builder().beginProvider("gtceu", 100);
        builder.recipe(recipe("gtceu:zzz", IRON, DUST));
        builder.recipe(recipe("gtceu:aaa", IRON, DUST));
        builder.recipe(recipe("gtceu:mmm", IRON, DUST));
        RecipeIndex index = builder.build();

        assertEquals(List.of("gtceu:aaa", "gtceu:mmm", "gtceu:zzz"), ids(index.producing(DUST)));
        assertEquals(List.of("gtceu:aaa", "gtceu:mmm", "gtceu:zzz"), ids(index.all()));
    }

    @Test
    void statsBreakDownByTypeAndProvider() {
        RecipeIndex.Builder builder = RecipeIndex.builder();
        builder.beginProvider("gtceu", 100);
        builder.recipe(recipe("gtceu:a", IRON, DUST));
        builder.recipe(recipe("gtceu:b", IRON, PLATE));
        builder.beginProvider("vanilla", 10);
        builder.recipe(MfpRecipe.builder("minecraft:x", "minecraft:smelting", "vanilla")
                .input(MfpIngredient.of(DUST, 1))
                .output(MfpOutput.of(IRON, 1))
                .duration(200)
                .build());
        RecipeIndex index = builder.build();

        assertEquals(3, index.stats().recipeCount());
        assertEquals(2, index.stats().recipesByType().get("gtceu:macerator"));
        assertEquals(1, index.stats().recipesByType().get("minecraft:smelting"));
        assertEquals(2, index.stats().recipesByProvider().get("gtceu"));
    }

    /**
     * The breakdown is ordered most-frequent-first for reporting. An immutable copy that drops
     * iteration order scrambles every report while every count stays correct, so the failure is
     * invisible in aggregate assertions — hence checking the order itself.
     */
    @Test
    void statsBreakdownIsOrderedByCountDescending() {
        RecipeIndex.Builder builder = RecipeIndex.builder().beginProvider("gtceu", 100);
        builder.recipe(MfpRecipe.builder("gtceu:rare", "type:rare", "gtceu").build());
        for (int i = 0; i < 5; i++) {
            builder.recipe(MfpRecipe.builder("gtceu:mid" + i, "type:mid", "gtceu").build());
        }
        for (int i = 0; i < 20; i++) {
            builder.recipe(MfpRecipe.builder("gtceu:common" + i, "type:common", "gtceu").build());
        }
        RecipeIndex index = builder.build();

        assertEquals(List.of("type:common", "type:mid", "type:rare"),
                List.copyOf(index.stats().recipesByType().keySet()));
    }

    @Test
    void emptyIndexIsUsable() {
        assertTrue(RecipeIndex.EMPTY.isEmpty());
        assertTrue(RecipeIndex.EMPTY.producing(IRON).isEmpty());
        assertNull(RecipeIndex.EMPTY.recipe("anything"));
        assertTrue(RecipeIndex.EMPTY.stats().isClean());
    }
}
