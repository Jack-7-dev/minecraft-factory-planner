package dev.mfp.core.select;

import dev.mfp.core.index.RecipeIndex;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.Line;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.plan.LineDecision;
import dev.mfp.core.plan.Preferences;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The standing preferences (M8): what the player decides once and means everywhere.
 *
 * <p>Each test here is one of the milestone's four acceptance clauses, and all four are the same
 * claim from different angles — <b>a preference beats the scorer and loses to the plan</b>. That
 * ordering is the whole safety of applying them automatically, so it is asserted in both directions
 * rather than only in the direction that makes the feature look useful.
 */
class PreferencesTest {

    private static final MfpKey ORE = MfpKey.item("mfp", "ore");
    private static final MfpKey DUST = MfpKey.item("mfp", "dust");
    private static final MfpKey INGOT = MfpKey.item("mfp", "ingot");
    private static final MfpKey PLATE = MfpKey.item("mfp", "plate");
    private static final MfpKey OAK = MfpKey.item("mfp", "oak_log");
    private static final MfpKey SPRUCE = MfpKey.item("mfp", "spruce_log");
    private static final MfpKey PLANK = MfpKey.item("mfp", "plank");
    private static final MfpKey ESSENCE = MfpKey.item("mfp", "inferium_essence");

    private static MfpRecipe recipe(String id, MfpKey in, MfpKey out) {
        return MfpRecipe.builder(id, "mfp:machine", "test")
                .input(MfpIngredient.of(in, 1))
                .output(MfpOutput.of(out, 1))
                .duration(20).euIn(16).minTier(1).build();
    }

    private static RecipeIndex.Builder builder() {
        RecipeIndex.Builder builder = RecipeIndex.builder();
        builder.beginProvider("test", 0);
        return builder;
    }

    private static void machines(RecipeIndex.Builder builder) {
        builder.machine(new MfpMachine("mfp:lv_machine", "LV Machine", 1, 32,
                List.of("mfp:machine"), false, List.of(), "test"));
        builder.machine(new MfpMachine("mfp:mv_machine", "MV Machine", 2, 128,
                List.of("mfp:machine"), false, List.of(), "test"));
        builder.machine(new MfpMachine("mfp:hv_machine", "HV Machine", 3, 512,
                List.of("mfp:machine"), false, List.of(), "test"));
    }

    private static String recipeFor(Plan plan, MfpKey key) {
        for (Line line : plan.allLines()) {
            if (line.recipe().produces(key)) {
                return line.recipe().id();
            }
        }
        return null;
    }

    // ----------------------------------------------------------- default recipes

    private static RecipeIndex twoWaysToAnIngot() {
        RecipeIndex.Builder builder = builder();
        // The scorer prefers the shorter of these: one input, guaranteed, main product, same tier.
        builder.recipe(recipe("mfp:smelt_ingot", ORE, INGOT));
        builder.recipe(MfpRecipe.builder("mfp:refine_ingot", "mfp:machine", "test")
                .input(MfpIngredient.of(DUST, 1))
                .input(MfpIngredient.of(ORE, 1))
                .output(MfpOutput.of(INGOT, 2))
                .duration(20).euIn(16).minTier(1).build());
        builder.recipe(recipe("mfp:dust", ORE, DUST));
        machines(builder);
        return builder.build();
    }

    @Test
    @DisplayName("a standing default recipe beats the scorer's own pick")
    void defaultRecipeBeatsTheScorer() {
        RecipeIndex index = twoWaysToAnIngot();
        Preferences preferences = Preferences.none().defaultRecipe(INGOT, "mfp:refine_ingot");

        Plan scored = new Plan("scored").target(INGOT, 1).rawMaterial(ORE);
        new RecipeChooser(index).expandInto(scored);
        assertEquals("mfp:smelt_ingot", recipeFor(scored, INGOT),
                "the scorer's own answer, so the preference has something to overrule");

        Plan plan = new Plan("standing").target(INGOT, 1).rawMaterial(ORE);
        new RecipeChooser(index, preferences).expandInto(plan);
        assertEquals("mfp:refine_ingot", recipeFor(plan, INGOT));
    }

    @Test
    @DisplayName("a plan's own pin beats the standing default")
    void pinBeatsTheStandingDefault() {
        RecipeIndex index = twoWaysToAnIngot();
        Preferences preferences = Preferences.none().defaultRecipe(INGOT, "mfp:refine_ingot");

        Plan plan = new Plan("pinned").target(INGOT, 1).rawMaterial(ORE)
                .chooseRecipe(INGOT, "mfp:smelt_ingot");
        new RecipeChooser(index, preferences).expandInto(plan);

        assertEquals("mfp:smelt_ingot", recipeFor(plan, INGOT),
                "a decision made in this plan is not overruled by one made elsewhere");
    }

    @Test
    @DisplayName("a recipe hidden in the plan is not resurrected by being the standing default")
    void hidingBeatsTheStandingDefault() {
        RecipeIndex index = twoWaysToAnIngot();
        Preferences preferences = Preferences.none().defaultRecipe(INGOT, "mfp:refine_ingot");

        Plan plan = new Plan("hidden").target(INGOT, 1).rawMaterial(ORE)
                .blacklistRecipe("mfp:refine_ingot");
        new RecipeChooser(index, preferences).expandInto(plan);

        assertEquals("mfp:smelt_ingot", recipeFor(plan, INGOT));
    }

    @Test
    @DisplayName("adopting a plan records its targets' recipes and its pins, and the marker says so")
    void adoptingAPlanRecordsItsRecipes() {
        RecipeIndex index = twoWaysToAnIngot();
        Plan plan = new Plan("mine").target(INGOT, 1).rawMaterial(ORE)
                .chooseRecipe(INGOT, "mfp:refine_ingot");
        new RecipeChooser(index).expandInto(plan);

        Preferences preferences = Preferences.none();
        preferences.adoptFrom(plan);
        assertEquals("mfp:refine_ingot", preferences.defaultRecipe(INGOT));

        Plan other = new Plan("other").target(INGOT, 1).rawMaterial(ORE);
        new RecipeChooser(index, preferences).expandInto(other);
        assertEquals("mfp:refine_ingot", recipeFor(other, INGOT));

        Line line = other.allLines().stream()
                .filter(candidate -> candidate.recipe().id().equals("mfp:refine_ingot"))
                .findFirst().orElseThrow();
        assertEquals(java.util.Set.of(LineDecision.STANDING_DEFAULT),
                other.decisionsFor(line, preferences),
                "a standing default is a different mark from a pin made here");
    }

    // ---------------------------------------------------------- preferred items

    private static RecipeIndex logsAndPlanks() {
        RecipeIndex.Builder builder = builder();
        builder.recipe(MfpRecipe.builder("mfp:planks", "mfp:machine", "test")
                .input(MfpIngredient.ofAny(List.of(OAK, SPRUCE), 1))
                .output(MfpOutput.of(PLANK, 4))
                .duration(20).euIn(16).minTier(1).build());
        machines(builder);
        return builder.build();
    }

    @Test
    @DisplayName("a preferred item is what the picker lists and what the line expands")
    void preferredItemIsListedAndExpanded() {
        RecipeIndex index = logsAndPlanks();
        Preferences preferences = Preferences.none().preferItem(SPRUCE);
        Plan plan = new Plan("planks").target(PLANK, 1).rawMaterial(OAK).rawMaterial(SPRUCE);

        RecipeChooser chooser = new RecipeChooser(index, preferences);

        // The picker's list: it must show the item the line will eat, not the index's own order.
        List<RecipeScorer.Scored> ranked = chooser.alternatives(PLANK, plan);
        assertEquals(SPRUCE, ranked.get(0).recipe().inputs().get(0).primary());

        chooser.expandInto(plan);
        assertEquals(SPRUCE, plan.allLines().get(0).recipe().inputs().get(0).primary());
    }

    @Test
    @DisplayName("the plan's preferred item beats the standing one")
    void planPreferenceBeatsTheStandingOne() {
        RecipeIndex index = logsAndPlanks();
        Preferences preferences = Preferences.none().preferItem(SPRUCE);
        Plan plan = new Plan("planks").target(PLANK, 1).rawMaterial(OAK).rawMaterial(SPRUCE)
                .preferItem(OAK);

        RecipeChooser chooser = new RecipeChooser(index, preferences);
        assertEquals(OAK, chooser.alternatives(PLANK, plan).get(0).recipe().inputs().get(0).primary());

        chooser.expandInto(plan);
        assertEquals(OAK, plan.allLines().get(0).recipe().inputs().get(0).primary());
    }

    // ------------------------------------------------------------ blocked items

    private static RecipeIndex essenceAndOre() {
        RecipeIndex.Builder builder = builder();
        builder.recipe(recipe("mfp:grow_ingot", ESSENCE, INGOT));
        builder.recipe(recipe("mfp:smelt_ingot", ORE, INGOT));
        machines(builder);
        return builder.build();
    }

    @Test
    @DisplayName("a blocked item removes every recipe consuming it")
    void blockedItemRemovesItsRecipes() {
        RecipeIndex index = essenceAndOre();
        Plan plan = new Plan("blocked").target(INGOT, 1).rawMaterial(ORE).rawMaterial(ESSENCE);
        Preferences preferences = Preferences.none().blockItem(ESSENCE);

        RecipeChooser chooser = new RecipeChooser(index, preferences);
        List<String> offered = chooser.alternatives(INGOT, plan).stream()
                .map(scored -> scored.recipe().id()).toList();
        assertEquals(List.of("mfp:smelt_ingot"), offered);

        chooser.expandInto(plan);
        assertEquals("mfp:smelt_ingot", recipeFor(plan, INGOT));

        assertEquals(ESSENCE, chooser.blockedAlternatives(INGOT, plan).get(index.recipe("mfp:grow_ingot")),
                "the picker must still be able to show what was excluded, and why");
    }

    @Test
    @DisplayName("a blocked item that leaves no route is named as the reason for the import")
    void blockedItemIsGivenAsTheReasonForAnImport() {
        RecipeIndex.Builder builder = builder();
        builder.recipe(recipe("mfp:grow_ingot", ESSENCE, INGOT));
        machines(builder);
        RecipeIndex index = builder.build();

        Plan plan = new Plan("blocked").target(INGOT, 1).rawMaterial(ORE);
        ChooserResult result = new RecipeChooser(index, Preferences.none().blockItem(ESSENCE))
                .expand(plan);

        assertTrue(result.unresolved().contains(INGOT));
        String reason = result.importReasons().get(INGOT);
        assertNotNull(reason, () -> "no reason recorded, only " + result.importReasons());
        assertTrue(reason.contains(ESSENCE.toString()), reason);
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains(ESSENCE.toString())),
                () -> String.valueOf(result.warnings()));
    }

    @Test
    @DisplayName("blacklisting an item re-routes a plan that had pinned a recipe needing it")
    void blockingBeatsAPinThatNeedsTheItem() {
        RecipeIndex index = essenceAndOre();
        Plan plan = new Plan("pinned").target(INGOT, 1).rawMaterial(ORE).rawMaterial(ESSENCE)
                .chooseRecipe(INGOT, "mfp:grow_ingot");

        new RecipeChooser(index).expandInto(plan);
        assertEquals("mfp:grow_ingot", recipeFor(plan, INGOT), "the pin, before anything is blocked");

        plan.clearLines();
        new RecipeChooser(index, Preferences.none().blockItem(ESSENCE)).expandInto(plan);
        assertEquals("mfp:smelt_ingot", recipeFor(plan, INGOT),
                "blacklisting an item must change the plan, including the lines that were pinned");
    }

    /**
     * The mystical agriculture shape, from the pack.
     *
     * <p>A log is burned from wood essence, wood essence is mixed from air essence, and the only way
     * to air essence is inferium. There is also a greenhouse, which wants nothing but water — worse
     * on every term the scorer can see (more inputs, higher tier), so it is not the default.
     */
    private static RecipeIndex essenceChainAndAGreenhouse() {
        RecipeIndex.Builder builder = builder();
        builder.recipe(recipe("mfp:burn_essence", DUST, OAK));
        builder.recipe(recipe("mfp:mix_essence", PLATE, DUST));
        builder.recipe(recipe("mfp:grow_air_essence", ESSENCE, PLATE));
        builder.recipe(MfpRecipe.builder("mfp:greenhouse", "mfp:machine", "test")
                .input(MfpIngredient.of(ORE, 4))
                .input(MfpIngredient.of(SPRUCE, 1))
                .output(MfpOutput.of(OAK, 1))
                .duration(20).euIn(16).minTier(2).build());
        machines(builder);
        return builder.build();
    }

    @Test
    @DisplayName("a blacklisted item takes the whole chain above it out, not just the recipes naming it")
    void blacklistCascadesUpTheChain() {
        RecipeIndex index = essenceChainAndAGreenhouse();

        // Note what this plan does *not* declare raw. Once the greenhouse's ore and spruce are in
        // the raw set the scorer's terminal-recipe term picks the greenhouse on its own merits
        // (M10), and a "before" that already chose the greenhouse would make the blacklist below
        // prove nothing. With them producible, the essence route is still the default.
        Plan open = new Plan("logs").target(OAK, 1).rawMaterial(ESSENCE);
        new RecipeChooser(index).expandInto(open);
        assertEquals("mfp:burn_essence", recipeFor(open, OAK),
                "the essence route is the default, so blacklisting has something to move");

        Plan plan = new Plan("logs").target(OAK, 1).rawMaterial(ORE).rawMaterial(SPRUCE);
        ChooserResult result =
                new RecipeChooser(index, Preferences.none().blockItem(ESSENCE)).expand(plan);

        List<String> ids = result.lines().stream().map(line -> line.recipe().id()).toList();
        assertEquals(List.of("mfp:greenhouse"), ids,
                "blacklisting inferium must abandon the whole essence route, not import air essence");
        assertTrue(result.importReasons().isEmpty(),
                () -> "a route was found, so nothing should be reported as blocked: "
                        + result.importReasons());
    }

    @Test
    @DisplayName("with no way round it, the blacklisted cause is still reported rather than the plan lost")
    void blacklistWithNoAlternativeKeepsThePlanAndSaysWhy() {
        RecipeIndex.Builder builder = builder();
        builder.recipe(recipe("mfp:burn_essence", DUST, OAK));
        builder.recipe(recipe("mfp:mix_essence", PLATE, DUST));
        builder.recipe(recipe("mfp:grow_air_essence", ESSENCE, PLATE));
        machines(builder);
        RecipeIndex index = builder.build();

        Plan plan = new Plan("logs").target(OAK, 1);
        ChooserResult result =
                new RecipeChooser(index, Preferences.none().blockItem(ESSENCE)).expand(plan);

        assertFalse(result.lines().isEmpty(), "the only route is still the answer when there is no other");
        // The dead end is reported as high up the chain as it reaches: with air essence unavailable,
        // wood essence is too, so the plan stops there rather than laying out machines for a chain
        // whose bottom the player cannot run. The cause named is still the blacklisted item itself.
        assertTrue(result.importReasons().containsKey(DUST),
                () -> "the plan should stop at the highest unreachable step: " + result.importReasons());
        assertTrue(result.importReasons().values().stream()
                        .anyMatch(reason -> reason.contains(ESSENCE.toString())),
                () -> "the import must name the blacklisted cause: " + result.importReasons());
    }

    @Test
    @DisplayName("a plan may allow an item the standing preferences block")
    void planCanAllowABlockedItem() {
        RecipeIndex index = essenceAndOre();
        Preferences preferences = Preferences.none().blockItem(ESSENCE);
        Plan plan = new Plan("exception").target(INGOT, 1).rawMaterial(ORE).rawMaterial(ESSENCE)
                .allowItem(ESSENCE)
                .chooseRecipe(INGOT, "mfp:grow_ingot");
        new RecipeChooser(index, preferences).expandInto(plan);

        assertEquals("mfp:grow_ingot", recipeFor(plan, INGOT),
                "the plan for building the essence farm is exactly the plan that must assume it");
    }

    @Test
    @DisplayName("a blocked candidate of an ambiguous input takes the alternative, everywhere")
    void blockedCandidateFallsBackToTheOtherOne() {
        RecipeIndex index = logsAndPlanks();
        Plan plan = new Plan("planks").target(PLANK, 1).rawMaterial(OAK).rawMaterial(SPRUCE);
        new RecipeChooser(index, Preferences.none().blockItem(OAK)).expandInto(plan);

        MfpIngredient input = plan.allLines().get(0).recipe().inputs().get(0);
        assertEquals(SPRUCE, input.primary(),
                "the line the solver reads must eat the item the walk expanded");
        assertFalse(plan.allLines().isEmpty());
    }

    // ------------------------------------------------------------- default tier

    @Test
    @DisplayName("raising the default tier moves every unpinned machine and leaves the pinned one")
    void defaultTierMovesUnpinnedMachinesOnly() {
        RecipeIndex.Builder builder = builder();
        builder.recipe(recipe("mfp:ingot", ORE, INGOT));
        builder.recipe(recipe("mfp:plate", INGOT, PLATE));
        machines(builder);
        RecipeIndex index = builder.build();

        Plan plan = new Plan("tiers").target(PLATE, 1).rawMaterial(ORE);
        new RecipeChooser(index).expandInto(plan);
        assertTrue(plan.allLines().stream().allMatch(line ->
                        "mfp:lv_machine".equals(line.machine().machineId())),
                "with no stated tier the lowest that can run it is still the default");

        Plan chosen = new Plan("chosen").target(PLATE, 1).rawMaterial(ORE)
                .chooseMachine("mfp:machine", "mfp:mv_machine");
        new RecipeChooser(index, Preferences.none().defaultTier(3)).expandInto(chosen);
        assertTrue(chosen.allLines().stream().allMatch(line ->
                        "mfp:mv_machine".equals(line.machine().machineId())),
                "an explicitly chosen machine stays chosen whatever the default tier says");

        Plan unpinned = new Plan("hv").target(PLATE, 1).rawMaterial(ORE);
        new RecipeChooser(index, Preferences.none().defaultTier(3)).expandInto(unpinned);
        assertTrue(unpinned.allLines().stream().allMatch(line ->
                        "mfp:hv_machine".equals(line.machine().machineId())),
                () -> "every default machine should be the HV member, got "
                        + unpinned.allLines().stream().map(line -> line.machine().machineId()).toList());
    }

    @Test
    @DisplayName("a recipe that cannot run at the default tier falls back to the lowest that can")
    void tooHighARecipeFallsBackRatherThanFailing() {
        RecipeIndex.Builder builder = builder();
        builder.recipe(MfpRecipe.builder("mfp:ingot", "mfp:machine", "test")
                .input(MfpIngredient.of(ORE, 1))
                .output(MfpOutput.of(INGOT, 1))
                .duration(20).euIn(2048).minTier(3).build());
        machines(builder);
        RecipeIndex index = builder.build();

        Plan plan = new Plan("low").target(INGOT, 1).rawMaterial(ORE);
        new RecipeChooser(index, Preferences.none().defaultTier(1)).expandInto(plan);

        assertEquals("mfp:hv_machine", plan.allLines().get(0).machine().machineId(),
                "an HV-only recipe planned by someone who builds LV gets the HV machine, not none");
    }

    @Test
    @DisplayName("an unpowered multiblock is not given a tier it has no hatch for")
    void unpoweredMultiblockKeepsItsTier() {
        RecipeIndex.Builder builder = builder();
        builder.recipe(MfpRecipe.builder("mfp:coke", "mfp:oven", "test")
                .input(MfpIngredient.of(ORE, 1))
                .output(MfpOutput.of(INGOT, 1))
                .duration(20).minTier(0).build());
        builder.machine(new MfpMachine("mfp:coke_oven", "Coke Oven", -1, 0,
                List.of("mfp:oven"), true, List.of(), "test"));
        RecipeIndex index = builder.build();

        Plan plan = new Plan("coke").target(INGOT, 1).rawMaterial(ORE);
        new RecipeChooser(index, Preferences.none().defaultTier(3)).expandInto(plan);

        assertEquals(0, plan.allLines().get(0).machine().tier(),
                "a structure that burns no electricity has no voltage to state");
    }

    @Test
    @DisplayName("the plan's own tier beats the standing one")
    void planTierBeatsTheStandingTier() {
        RecipeIndex.Builder builder = builder();
        builder.recipe(recipe("mfp:ingot", ORE, INGOT));
        machines(builder);
        RecipeIndex index = builder.build();

        Plan plan = new Plan("mv").target(INGOT, 1).rawMaterial(ORE).defaultTier(2);
        new RecipeChooser(index, Preferences.none().defaultTier(3)).expandInto(plan);

        assertEquals("mfp:mv_machine", plan.allLines().get(0).machine().machineId());
    }
}
