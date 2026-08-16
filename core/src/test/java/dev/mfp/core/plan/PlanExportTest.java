package dev.mfp.core.plan;

import dev.mfp.core.index.RecipeIndex;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.select.RecipeChooser;
import dev.mfp.core.solver.SequentialSolver;
import dev.mfp.core.solver.SolveResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A plan as a string (M9.3), and the two things that make it worth having.
 *
 * <p>The first is that it is <em>the same plan</em>: not the same targets, the same answer. So the
 * round trip is asserted by solving both ends and comparing the numbers, which is the only claim a
 * user of an exported plan actually cares about.
 *
 * <p>The second is what happens when it is not: a plan naming recipes this world does not have is
 * still worth importing, and a string that is not a plan at all must say so rather than throw
 * something a screen has to guess a message from.
 */
class PlanExportTest {

    private static final MfpKey ORE = MfpKey.item("mfp", "ore");
    private static final MfpKey INGOT = MfpKey.item("mfp", "ingot");
    private static final MfpKey PLATE = MfpKey.item("mfp", "plate");
    private static final MfpKey COBBLE = MfpKey.item("minecraft", "cobblestone");
    private static final MfpKey WATER = MfpKey.fluid("minecraft", "water");

    @AfterEach
    void resetRawMaterials() {
        // The standing list is global state and these tests move it; leaving it moved would make
        // every later test's plan depend on the order they ran in.
        RawMaterials.resetToShipped();
    }

    private static MfpRecipe recipe(String id, MfpKey in, MfpKey out) {
        return MfpRecipe.builder(id, "mfp:machine", "test")
                .input(MfpIngredient.of(in, 2))
                .output(MfpOutput.of(out, 1))
                .duration(20)
                .euIn(16)
                .minTier(1)
                .build();
    }

    private static RecipeIndex index() {
        RecipeIndex.Builder builder = RecipeIndex.builder();
        builder.beginProvider("test", 0);
        builder.recipe(recipe("mfp:plate", INGOT, PLATE));
        builder.recipe(recipe("mfp:ingot", ORE, INGOT));
        // A second way to make an ingot, so a pin is a decision rather than the only option.
        builder.recipe(recipe("mfp:ingot_alt", COBBLE, INGOT));
        builder.machine(new MfpMachine("mfp:lv_machine", "LV Machine", 1, 32,
                List.of("mfp:machine"), false, List.of(), "test"));
        builder.machine(new MfpMachine("mfp:mv_machine", "MV Machine", 2, 128,
                List.of("mfp:machine"), false, List.of(), "test"));
        return builder.build();
    }

    /** Everything a plan can carry, so the round trip is not testing three easy fields. */
    private static Plan decidedPlan() {
        Plan plan = new Plan("Plate line")
                .target(PLATE, 3)
                .rawMaterial(COBBLE)
                .clearRawMaterial(WATER)
                .freeItem(INGOT)
                .preferItem(ORE)
                .blockItem(MfpKey.item("mfp", "essence"))
                .allowItem(MfpKey.item("mfp", "dust"))
                .blacklistRecipe("mfp:ingot_alt")
                .defaultTier(2)
                .byproductFeeds(false)
                .autoResolve(false)
                .displayOrder(List.of("mfp:ingot", "mfp:plate"))
                .chooseRecipe(INGOT, "mfp:ingot")
                .chooseMachine("mfp:machine", "mfp:mv_machine")
                .solverMode(SolverMode.SEQUENTIAL);
        plan.configureMachine("mfp:ingot",
                new MachineConfig("mfp:lv_machine", 1, 4, 6.0, true,
                        Map.of("coil", "kanthal", "parallel_hatch", 8)));
        return plan;
    }

    @Test
    @DisplayName("a plan survives export and import with identical numbers")
    void roundTripSolvesTheSame() {
        RecipeIndex index = index();
        Plan original = decidedPlan();

        String string = PlanExport.export(original);
        assertTrue(string.startsWith(PlanExport.MAGIC), "the magic prefix says what the string is");

        PlanExport.Imported imported = PlanExport.parse(string,
                recipeId -> index.recipe(recipeId) != null);
        assertTrue(imported.isClean(), "this world has everything the plan names: "
                + imported.problems());
        Plan copy = imported.plan();

        // Every decision, field by field. Solving both would hide a lost pin whose recipe the scorer
        // happened to choose anyway, which is exactly the kind of silent loss this milestone is about.
        assertEquals("Plate line", copy.name());
        assertEquals(original.targets(), copy.targets());
        assertEquals(original.rawMaterials(), copy.rawMaterials());
        assertFalse(copy.rawMaterials().contains(WATER), "the plan's own exception travelled with it");
        assertTrue(copy.rawMaterials().contains(COBBLE));
        assertEquals(original.freeItems(), copy.freeItems());
        assertEquals(original.preferredItems(), copy.preferredItems());
        assertEquals(original.blockedItems(), copy.blockedItems());
        assertEquals(original.allowedItems(), copy.allowedItems());
        assertEquals(original.blacklist(), copy.blacklist());
        assertEquals(original.defaultTier(), copy.defaultTier());
        assertFalse(copy.byproductFeeds(), "switching the byproduct pass off is a decision (M11.1)");
        assertFalse(copy.autoResolve(), "and so is building the plan by hand (M11.3) - a hand-built "
                + "plan that re-expanded on import would bury every choice in it");
        assertEquals(original.displayOrder(), copy.displayOrder());
        assertEquals(original.recipeChoices(), copy.recipeChoices());
        assertEquals(original.machineChoices(), copy.machineChoices());
        assertEquals(original.machineConfigs(), copy.machineConfigs());
        assertEquals(SolverMode.SEQUENTIAL, copy.solverMode());

        // And the claim that matters: the same plan produces the same answer.
        SolveResult before = solve(index, original);
        SolveResult after = solve(index, copy);
        assertEquals(lines(before), lines(after));
        assertEquals(before.totalMachines(), after.totalMachines());
        assertEquals(before.euDrawPerSecond(), after.euDrawPerSecond(), 1e-9);
        assertTrue(after.isComplete());
    }

    @Test
    @DisplayName("an import naming a recipe this world lacks says which, and still opens")
    void unknownRecipesAreReportedRatherThanFatal() {
        Plan plan = new Plan("elsewhere").target(PLATE, 1).chooseRecipe(PLATE, "otherpack:fancy_plate");
        plan.configureMachine("otherpack:fancy_plate", MachineConfig.of("otherpack:machine", 3));

        RecipeIndex index = index();
        PlanExport.Imported imported = PlanExport.parse(PlanExport.export(plan),
                recipeId -> index.recipe(recipeId) != null);

        assertFalse(imported.isClean());
        assertTrue(imported.problems().stream().anyMatch(p -> p.contains("otherpack:fancy_plate")),
                "the message names the recipe: " + imported.problems());
        // The pin is dropped rather than kept as an id nothing can act on, and the rest of the plan
        // is intact - which is the whole reason an unknown recipe is not a refusal.
        assertNull(imported.plan().recipeChoice(PLATE));
        assertTrue(imported.plan().machineConfigs().isEmpty());
        assertEquals(1, imported.plan().targets().size());
        assertTrue(solve(index, imported.plan()).isComplete());
    }

    @Test
    @DisplayName("a string that is not a plan fails with a message, not a stack trace")
    void malformedStringsExplainThemselves() {
        assertTrue(assertThrows(PlanExport.PlanFormatException.class,
                () -> PlanExport.parse("hello")).getMessage().contains("MFP1:"));
        assertTrue(assertThrows(PlanExport.PlanFormatException.class,
                () -> PlanExport.parse("MFP9:abcdef")).getMessage().contains("does not read"));
        assertTrue(assertThrows(PlanExport.PlanFormatException.class,
                () -> PlanExport.parse("MFP1:not base64!!")).getMessage().contains("damaged"));
        assertThrows(PlanExport.PlanFormatException.class, () -> PlanExport.parse("  "));
    }

    @Test
    @DisplayName("a plan string survives being wrapped by a chat window")
    void whitespaceIsIgnored() {
        String string = PlanExport.export(new Plan("wrapped").target(PLATE, 1));
        String wrapped = string.substring(0, 20) + "\n  " + string.substring(20);
        assertEquals("wrapped", PlanExport.parse(wrapped).plan().name());
    }

    @Test
    @DisplayName("an unnamed plan is named after what it makes, and a renamed one stays renamed")
    void namesFollowTheTargetUntilTheUserSaysOtherwise() {
        Plan plan = new Plan().target(PLATE, 2);
        assertFalse(plan.isNamed());
        assertEquals("mfp:plate x 2/s", plan.name());

        // Retargeting moves the name, which is the bug M9.1 exists to fix: a plan christened at
        // creation went on calling itself after the item it no longer made.
        plan.setTarget(0, new TargetOutput(INGOT, 5));
        assertEquals("mfp:ingot x 5/s", plan.name());

        // Naming it sticks, through the string form as well.
        plan.name("Ingot line");
        assertTrue(plan.isNamed());
        plan.setTarget(0, new TargetOutput(PLATE, 1));
        assertEquals("Ingot line", plan.name());
        assertEquals("Ingot line", PlanExport.parse(PlanExport.export(plan)).plan().name());

        // And clearing it hands the plan back to its target rather than naming it the empty string.
        plan.name("  ");
        assertFalse(plan.isNamed());
        assertEquals("mfp:plate x 1/s", plan.name());
        assertFalse(PlanExport.parse(PlanExport.export(plan)).plan().isNamed());
    }

    @Test
    @DisplayName("a standing raw material reaches a plan that was saved before it was declared")
    void rawMaterialsAreADeltaOverTheStandingList() {
        Plan plan = new Plan("old").target(PLATE, 1);
        String saved = PlanExport.export(plan);

        // The player builds a cobble generator after saving. A plan that had baked the list in at
        // creation would go on planning how to make cobblestone for ever (M9.4).
        RawMaterials.install(List.of(COBBLE));
        assertTrue(PlanExport.parse(saved).plan().rawMaterials().contains(COBBLE));

        // Their own exceptions still win in both directions.
        Plan exception = new Plan("water").target(PLATE, 1).clearRawMaterial(WATER).rawMaterial(ORE);
        Plan back = PlanExport.parse(PlanExport.export(exception)).plan();
        assertFalse(back.rawMaterials().contains(WATER));
        assertTrue(back.rawMaterials().contains(ORE));
        assertTrue(back.rawMaterials().contains(COBBLE), "and the standing list still reaches it");
    }

    @Test
    @DisplayName("a plan file written before M9 reads back as the same set of raw materials")
    void legacyRawMaterialListsMigrate() {
        // The whole list, as PlanStore wrote it until M9: water is standing, ore is the plan's own,
        // and charcoal is standing but absent, which meant the plan had taken it off.
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        com.google.gson.JsonArray targets = new com.google.gson.JsonArray();
        com.google.gson.JsonObject target = new com.google.gson.JsonObject();
        target.addProperty("item", "mfp:plate");
        target.addProperty("perSecond", 1.0);
        targets.add(target);
        json.add("targets", targets);
        com.google.gson.JsonArray raw = new com.google.gson.JsonArray();
        raw.add("fluid:minecraft:water");
        raw.add("mfp:ore");
        json.add("rawMaterials", raw);

        Plan plan = PlanCodec.read(json);

        // Charcoal was standing when that file was written and is missing from it, so the plan took
        // it off on purpose and still has.
        assertFalse(plan.rawMaterials().contains(MfpKey.item("minecraft", "charcoal")));
        assertTrue(plan.rawMaterials().containsAll(java.util.Set.of(WATER, ORE)));

        // And the half that matters for anything added later: cobblestone was not standing when
        // that file was written, so its absence is not a decision and it must reach this plan
        // (M9.13). Reading every absence as a removal would mean no addition to the standing list -
        // shipped or declared in the config - could ever reach a plan that already exists, which is
        // the failure the whole delta model was built to avoid.
        RawMaterials.install(List.of(COBBLE));
        assertTrue(PlanCodec.read(json).rawMaterials().contains(COBBLE),
                "a later addition to the standing list reaches plans already on disk");
        assertFalse(PlanCodec.read(json).rawMaterials()
                        .contains(MfpKey.item("minecraft", "charcoal")),
                "while a removal made when it *was* standing still stands");

        // And the name migration: a plan written before M9 always had one, because plans were
        // christened at creation. A stored name that is exactly the derived one is no name.
        json.addProperty("name", "mfp:plate x 1/s");
        assertFalse(PlanCodec.read(json).isNamed());
        json.addProperty("name", "My plate line");
        assertTrue(PlanCodec.read(json).isNamed());
    }

    @Test
    @DisplayName("a key's variant survives the round trip")
    void variantsAreNotLostInTheSpelling() {
        // Programmed circuits are the motivating case: a preferred item that lost its variant would
        // come back as a key nothing in the index matches, and quietly stop applying.
        MfpKey circuit = MfpKey.item("gtceu", "programmed_circuit", "4");
        Plan plan = new Plan("circuits").target(PLATE, 1).preferItem(circuit);
        assertEquals(java.util.Set.of(circuit),
                PlanExport.parse(PlanExport.export(plan)).plan().preferredItems());
        assertEquals(circuit, KeySpec.parse(KeySpec.of(circuit)));
        assertEquals(MfpKey.fluid("gtceu", "steam"),
                KeySpec.parse(KeySpec.of(MfpKey.fluid("gtceu", "steam"))));
    }

    private static SolveResult solve(RecipeIndex index, Plan plan) {
        plan.clearLines();
        new RecipeChooser(index).expandInto(plan);
        return new SequentialSolver().solve(plan);
    }

    private static List<String> lines(SolveResult result) {
        return result.lines().stream()
                .map(line -> line.line().recipe().id() + " x" + line.machineCount())
                .toList();
    }
}
