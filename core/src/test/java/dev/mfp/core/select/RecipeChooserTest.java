package dev.mfp.core.select;

import dev.mfp.core.index.RecipeIndex;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.Line;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.plan.SolverMode;
import dev.mfp.core.solver.SequentialSolver;
import dev.mfp.core.solver.SolveResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chooser's two jobs: picking recipes, and ordering them so the solver can use them.
 *
 * <p>Ordering is the subtle one. A plan whose lines are in the wrong order produces the same
 * symptom as a genuine loop, so these tests check that a correctly ordered acyclic plan is solved
 * with no imports at all, and that a real loop is reported as a loop rather than as an ordering
 * accident.
 */
class RecipeChooserTest {

    private static final MfpKey ORE = MfpKey.item("mfp", "ore");
    private static final MfpKey DUST = MfpKey.item("mfp", "dust");
    private static final MfpKey INGOT = MfpKey.item("mfp", "ingot");
    private static final MfpKey PLATE = MfpKey.item("mfp", "plate");
    private static final MfpKey ACID = MfpKey.fluid("mfp", "acid");
    private static final MfpKey SALT = MfpKey.item("mfp", "salt");

    private static MfpRecipe recipe(String id, MfpKey in, double inAmount, MfpKey out, double outAmount) {
        return MfpRecipe.builder(id, "mfp:machine", "test")
                .input(MfpIngredient.of(in, inAmount))
                .output(MfpOutput.of(out, outAmount))
                .duration(20)
                .euIn(16)
                .minTier(1)
                .build();
    }

    private static RecipeIndex indexOf(MfpRecipe... recipes) {
        RecipeIndex.Builder builder = RecipeIndex.builder();
        builder.beginProvider("test", 0);
        for (MfpRecipe recipe : recipes) {
            builder.recipe(recipe);
        }
        builder.machine(new MfpMachine("mfp:lv_machine", "LV Machine", 1, 32,
                List.of("mfp:machine"), false, List.of(), "test"));
        builder.machine(new MfpMachine("mfp:hv_machine", "HV Machine", 3, 512,
                List.of("mfp:machine"), false, List.of(), "test"));
        return builder.build();
    }

    @Test
    @DisplayName("expansion emits consumers above the lines that feed them")
    void emitsTopologically() {
        RecipeIndex index = indexOf(
                recipe("mfp:dust", ORE, 1, DUST, 1),
                recipe("mfp:ingot", DUST, 1, INGOT, 1),
                recipe("mfp:plate", INGOT, 1, PLATE, 1));

        Plan plan = new Plan("test").target(PLATE, 1).rawMaterial(ORE);
        ChooserResult result = new RecipeChooser(index).expand(plan);

        assertEquals(List.of("mfp:plate", "mfp:ingot", "mfp:dust"),
                result.lines().stream().map(line -> line.recipe().id()).toList());
        assertFalse(result.requiresMatrixSolver());
        assertTrue(result.isComplete());
    }

    @Test
    @DisplayName("a correctly ordered plan imports only its declared raw materials")
    void orderedPlanSolvesWithoutFalseImports() {
        RecipeIndex index = indexOf(
                recipe("mfp:dust", ORE, 1, DUST, 1),
                recipe("mfp:ingot", DUST, 1, INGOT, 1),
                recipe("mfp:plate", INGOT, 1, PLATE, 1));

        Plan plan = new Plan("test").target(PLATE, 1).rawMaterial(ORE);
        new RecipeChooser(index).expandInto(plan);
        SolveResult solved = new SequentialSolver().solve(plan);

        assertEquals(1.0, solved.products().get(PLATE), 1e-9);
        assertTrue(solved.rawInputs().containsKey(ORE));
        assertFalse(solved.rawInputs().containsKey(DUST),
                "dust is made by the plan, so ordering must not turn it into an import");
        assertTrue(solved.warnings().isEmpty(), () -> String.valueOf(solved.warnings()));
    }

    @Test
    @DisplayName("a shared intermediate is placed below every line that consumes it")
    void diamondDependencyOrdersCorrectly() {
        // PLATE needs INGOT and ACID; ACID also needs INGOT. INGOT must sit below both.
        MfpRecipe plate = MfpRecipe.builder("mfp:plate", "mfp:machine", "test")
                .input(MfpIngredient.of(INGOT, 1))
                .input(MfpIngredient.of(ACID, 1))
                .output(MfpOutput.of(PLATE, 1))
                .duration(20).euIn(16).minTier(1).build();
        MfpRecipe acid = recipe("mfp:acid", INGOT, 1, ACID, 1);
        MfpRecipe ingot = recipe("mfp:ingot", ORE, 1, INGOT, 1);

        Plan plan = new Plan("test").target(PLATE, 1).rawMaterial(ORE);
        ChooserResult result = new RecipeChooser(indexOf(plate, acid, ingot)).expand(plan);

        List<String> ids = result.lines().stream().map(line -> line.recipe().id()).toList();
        assertTrue(ids.indexOf("mfp:ingot") > ids.indexOf("mfp:acid"),
                () -> "ingot must be below acid, got " + ids);
        assertTrue(ids.indexOf("mfp:ingot") > ids.indexOf("mfp:plate"),
                () -> "ingot must be below plate, got " + ids);
    }

    @Test
    @DisplayName("a real loop is observed during expansion, not inferred from the solver")
    void detectsCycles() {
        // Acid dissolves salt; salt is recovered from acid. A genuine A -> B -> A loop.
        RecipeIndex index = indexOf(
                recipe("mfp:acid", SALT, 1, ACID, 1),
                recipe("mfp:salt", ACID, 1, SALT, 2));

        Plan plan = new Plan("test").target(ACID, 1);
        ChooserResult result = new RecipeChooser(index).expand(plan);

        assertTrue(result.requiresMatrixSolver());
        assertEquals(1, result.cycles().size());
        assertTrue(result.cycles().get(0).contains("mfp:acid"));
        assertTrue(result.warnings().get(0).contains("production loop"));
    }

    @Test
    @DisplayName("a reversible conversion loses to a genuine production route")
    void prefersAnAcyclicRoute() {
        // The trap GregTech is full of: nine nuggets make an ingot and an ingot makes nine nuggets.
        // On every other criterion the conversion scores beautifully - one input, guaranteed, main
        // product, low tier - so without the reversibility term it wins and the plan loops.
        MfpRecipe fromNugget = recipe("mfp:ingot_from_nugget", SALT, 9, INGOT, 1);
        MfpRecipe toNugget = recipe("mfp:nugget_from_ingot", INGOT, 1, SALT, 9);
        MfpRecipe fromOre = MfpRecipe.builder("mfp:ingot_from_ore", "mfp:machine", "test")
                .input(MfpIngredient.of(ORE, 1))
                .input(MfpIngredient.of(DUST, 2))
                .output(MfpOutput.of(INGOT, 1))
                .duration(20).euIn(16).minTier(2).build();

        Plan plan = new Plan("test").target(INGOT, 1).rawMaterial(ORE).rawMaterial(DUST);
        ChooserResult result = new RecipeChooser(indexOf(fromNugget, toNugget, fromOre)).expand(plan);

        // The ore route has more inputs and a higher tier, and still wins.
        assertEquals("mfp:ingot_from_ore", result.lines().get(0).recipe().id());
        assertFalse(result.requiresMatrixSolver());
        assertTrue(result.avoidedForCycles().isEmpty(),
                "the scorer avoided the loop, so no retry was needed");
    }

    @Test
    @DisplayName("when every route is reversible the retry steers around the loop instead")
    void retriesWhenScoringCannotAvoidTheLoop() {
        // No non-reversible way to make the ingot, so the scorer cannot help and the loop is only
        // discovered by walking it. The retry then avoids the whole cycle.
        MfpRecipe fromNugget = recipe("mfp:ingot_from_nugget", SALT, 9, INGOT, 1);
        MfpRecipe toNugget = recipe("mfp:nugget_from_ingot", INGOT, 1, SALT, 9);
        MfpRecipe nuggetFromOre = recipe("mfp:nugget_from_ore", ORE, 1, SALT, 1);

        Plan plan = new Plan("test").target(INGOT, 1).rawMaterial(ORE);
        ChooserResult result = new RecipeChooser(indexOf(fromNugget, toNugget, nuggetFromOre))
                .expand(plan);

        // Salt is made from ore rather than from the ingot, so the plan closes without a loop.
        assertFalse(result.requiresMatrixSolver());
        assertTrue(result.lines().stream()
                .anyMatch(line -> line.recipe().id().equals("mfp:nugget_from_ore")));
    }

    @Test
    @DisplayName("a loop with no acyclic alternative is still reported as a loop")
    void keepsGenuineLoops() {
        RecipeIndex index = indexOf(
                recipe("mfp:acid", SALT, 1, ACID, 1),
                recipe("mfp:salt", ACID, 1, SALT, 2));

        ChooserResult result = new RecipeChooser(index).expand(new Plan("test").target(ACID, 1));

        assertTrue(result.requiresMatrixSolver());
        assertTrue(result.avoidedForCycles().isEmpty(), "nothing was traded away for a worse plan");
    }

    @Test
    @DisplayName("an input an ancestor already gives off closes the loop instead of starting a chain")
    void takesAnInputBackFromTheLineItFeeds() {
        MfpKey log = MfpKey.item("mfp", "log");
        MfpKey co2 = MfpKey.fluid("mfp", "carbon_dioxide");
        MfpKey oxygen = MfpKey.fluid("mfp", "oxygen");
        MfpKey charcoal = MfpKey.item("mfp", "charcoal");
        MfpKey water = MfpKey.fluid("mfp", "water");

        MfpRecipe grow = MfpRecipe.builder("mfp:grow", "mfp:machine", "test")
                .input(MfpIngredient.of(co2, 10))
                .output(MfpOutput.of(log, 1))
                .output(MfpOutput.of(oxygen, 10))
                .duration(20).euIn(16).minTier(1).build();
        MfpRecipe burn = MfpRecipe.builder("mfp:burn", "mfp:machine", "test")
                .input(MfpIngredient.of(charcoal, 1))
                .input(MfpIngredient.of(oxygen, 10))
                .output(MfpOutput.of(co2, 10))
                .duration(20).euIn(16).minTier(1).build();
        // The chain the chooser used to go down instead, and the reason this matters: it is a
        // perfectly good way to make oxygen, so nothing but the loop rules it out.
        MfpRecipe electrolyse = recipe("mfp:electrolyse", water, 100, oxygen, 10);

        Plan plan = new Plan("test").target(log, 1).rawMaterial(charcoal).rawMaterial(water);
        ChooserResult result = new RecipeChooser(indexOf(grow, burn, electrolyse)).expand(plan);

        List<String> ids = result.lines().stream().map(line -> line.recipe().id()).toList();
        assertEquals(List.of("mfp:grow", "mfp:burn"), ids);
        assertTrue(result.requiresMatrixSolver(), "the two lines feed each other, which is a loop");

        // And the point of keeping it small: the matrix engine can close a two-line loop, where the
        // thirty-seven line version had more unknowns than items and could not be solved at all.
        result.lines().forEach(plan::add);
        SolveResult solved = new dev.mfp.core.solver.MatrixSolver().solve(plan);
        assertEquals(1.0, solved.products().getOrDefault(log, 0.0), 1e-9);
        assertTrue(solved.isComplete(), "the target is met: " + solved.warnings());
        assertTrue(solved.rawInputs().containsKey(charcoal), "charcoal is the only thing it needs");
        assertFalse(solved.rawInputs().containsKey(oxygen), "the loop supplies its own oxygen");
    }

    @Test
    @DisplayName("finding a loop switches an AUTO plan to the matrix engine")
    void cycleSwitchesSolverMode() {
        RecipeIndex index = indexOf(
                recipe("mfp:acid", SALT, 1, ACID, 1),
                recipe("mfp:salt", ACID, 1, SALT, 2));

        Plan plan = new Plan("test").target(ACID, 1);
        new RecipeChooser(index).expandInto(plan);

        assertEquals(SolverMode.MATRIX, plan.solverMode());
    }

    @Test
    @DisplayName("recycling a worn tool loses to making the item")
    void skipsToolRecycling() {
        // GregTech generates one of these for every tool. The input is a specific damaged state
        // that nothing produces, so the plan would bottom out importing the tool.
        MfpKey wornSaw = MfpKey.item("mfp", "saw", "abc123");
        MfpKey freshSaw = MfpKey.item("mfp", "saw", "def456");
        MfpRecipe recycle = recipe("mfp:arc_saw", wornSaw, 1, INGOT, 4);
        MfpRecipe craftSaw = recipe("mfp:craft_saw", INGOT, 4, freshSaw, 1);
        MfpRecipe smelt = recipe("mfp:smelt_ore", ORE, 1, INGOT, 1);

        Plan plan = new Plan("test").target(INGOT, 1).rawMaterial(ORE);
        ChooserResult result = new RecipeChooser(indexOf(recycle, craftSaw, smelt)).expand(plan);

        assertEquals("mfp:smelt_ore", result.lines().get(0).recipe().id());
    }

    @Test
    @DisplayName("an unproducible input is fine when it is a primitive, not a worn item")
    void doesNotPunishRawMaterials() {
        // Nothing produces ore either, but no form of it is manufactured, so smelting it must not
        // be mistaken for recycling.
        MfpRecipe smelt = recipe("mfp:smelt_ore", ORE, 1, INGOT, 1);
        Plan plan = new Plan("test").target(INGOT, 1);

        ChooserResult result = new RecipeChooser(indexOf(smelt)).expand(plan);

        assertEquals("mfp:smelt_ore", result.lines().get(0).recipe().id());
        assertEquals(List.of(ORE), result.unresolved());
    }

    @Test
    @DisplayName("a key nothing produces is reported rather than silently dropped")
    void reportsUnresolvedKeys() {
        Plan plan = new Plan("test").target(PLATE, 1);
        ChooserResult result = new RecipeChooser(indexOf(recipe("mfp:plate", INGOT, 1, PLATE, 1)))
                .expand(plan);

        assertEquals(List.of(INGOT), result.unresolved());
        assertFalse(result.isComplete());
        assertFalse(result.requiresMatrixSolver(), "a missing recipe is not a loop");
    }

    @Test
    @DisplayName("the user's pinned recipe beats the scorer")
    void userChoiceWins() {
        MfpRecipe cheap = recipe("mfp:plate_cheap", INGOT, 1, PLATE, 1);
        MfpRecipe awkward = MfpRecipe.builder("mfp:plate_awkward", "mfp:machine", "test")
                .input(MfpIngredient.of(INGOT, 4))
                .input(MfpIngredient.of(ACID, 100))
                .output(MfpOutput.chanced(PLATE, 1, 0.5))
                .duration(20).euIn(16).minTier(3).build();

        Plan plan = new Plan("test").target(PLATE, 1).rawMaterial(INGOT).rawMaterial(ACID);
        RecipeChooser chooser = new RecipeChooser(indexOf(cheap, awkward));

        assertEquals("mfp:plate_cheap", chooser.expand(plan).lines().get(0).recipe().id());

        plan.chooseRecipe(PLATE, "mfp:plate_awkward");
        assertEquals("mfp:plate_awkward", chooser.expand(plan).lines().get(0).recipe().id());
    }

    @Test
    @DisplayName("a guaranteed source outranks a chanced byproduct")
    void prefersGuaranteedOutput() {
        MfpRecipe byproduct = MfpRecipe.builder("mfp:byproduct", "mfp:machine", "test")
                .input(MfpIngredient.of(ORE, 1))
                .output(MfpOutput.of(DUST, 1))
                .output(MfpOutput.chanced(PLATE, 1, 0.05))
                .duration(20).euIn(16).minTier(1).build();
        MfpRecipe direct = recipe("mfp:plate", INGOT, 1, PLATE, 1);

        Plan plan = new Plan("test").target(PLATE, 1).rawMaterial(ORE).rawMaterial(INGOT);
        ChooserResult result = new RecipeChooser(indexOf(byproduct, direct)).expand(plan);

        assertEquals("mfp:plate", result.lines().get(0).recipe().id());
    }

    @Test
    @DisplayName("lines default to the lowest tier machine that can run them")
    void picksLowestUsableTier() {
        Plan plan = new Plan("test").target(PLATE, 1).rawMaterial(INGOT);
        ChooserResult result = new RecipeChooser(indexOf(recipe("mfp:plate", INGOT, 1, PLATE, 1)))
                .expand(plan);

        Line line = result.lines().get(0);
        assertEquals("mfp:lv_machine", line.machine().machineId());
        assertEquals(1, line.machine().tier());
    }

    /**
     * Water is free, so nothing plans how to make it — unless making it is the question.
     *
     * <p>Both halves matter. Expansion that walks into water finds a recipe, because there is always
     * a recipe: melt snow, condense steam, thaw ice. None of them is a thing anyone builds, and each
     * drags in a machine, its power and its own inputs. But a plan whose <em>target</em> is water is
     * someone asking how water is made, and answering that with an empty plan would be obtuse.
     */
    @Test
    @DisplayName("a raw material stops expansion as an input but not as a target")
    void rawMaterialsStopExpansionExceptAtTheTop() {
        RecipeIndex index = indexOf(
                recipe("mfp:dust", ORE, 1, DUST, 1),
                recipe("mfp:ingot", DUST, 1, INGOT, 1));

        Plan usesDust = new Plan("uses dust").target(INGOT, 1).rawMaterial(DUST);
        ChooserResult asInput = new RecipeChooser(index).expand(usesDust);

        assertEquals(1, asInput.lines().size());
        assertTrue(asInput.rawMaterials().contains(DUST));

        Plan makesDust = new Plan("makes dust").target(DUST, 1).rawMaterial(DUST);
        ChooserResult asTarget = new RecipeChooser(index).expand(makesDust);

        assertEquals(1, asTarget.lines().size());
        assertEquals("mfp:dust", asTarget.lines().get(0).recipe().id());
    }

    /** Every plan starts knowing water is free, without anyone having to say so. */
    @Test
    void newPlansAreSeededWithTheShippedRawMaterials() {
        assertTrue(new Plan("fresh").rawMaterials().contains(MfpKey.fluid("minecraft", "water")));
    }

    /**
     * The Star-Technology case: a greenhouse and a "fermenting aroboreal rejuvenation monstrosity"
     * that make the same thing from the same inputs.
     *
     * <p>Every other term in the scorer reads the two recipes as identical, because they are, which
     * left the tie to be broken by recipe id — alphabetically, and so at random. What separates them
     * is not in either recipe: it is that one machine is assembled at LV and the other comes off an
     * assembly line at ZPM. The plan must open with the one a player can actually build, and offer
     * the other in the picker.
     */
    @Test
    @DisplayName("of two identical recipes, the one whose machine is cheaper to build wins")
    void prefersTheCheaperMachine() {
        RecipeIndex.Builder builder = RecipeIndex.builder();
        builder.beginProvider("test", 0);

        builder.recipe(MfpRecipe.builder("mfp:monstrosity/grow", "mfp:monstrosity", "test")
                .input(MfpIngredient.of(ORE, 1)).output(MfpOutput.of(DUST, 1))
                .duration(20).euIn(16).minTier(1).build());
        builder.recipe(MfpRecipe.builder("mfp:greenhouse/grow", "mfp:greenhouse", "test")
                .input(MfpIngredient.of(ORE, 1)).output(MfpOutput.of(DUST, 1))
                .duration(20).euIn(16).minTier(1).build());

        builder.machine(new MfpMachine("mfp:monstrosity", "Monstrosity", -1, 0,
                List.of("mfp:monstrosity"), true, List.of(), "test"));
        builder.machine(new MfpMachine("mfp:greenhouse", "Greenhouse", -1, 0,
                List.of("mfp:greenhouse"), true, List.of(), "test"));

        // How each machine is obtained, which is the whole of the difference between them.
        builder.recipe(MfpRecipe.builder("mfp:assembly_line/monstrosity", "mfp:assembly_line", "test")
                .input(MfpIngredient.of(INGOT, 64))
                .output(MfpOutput.of(MfpKey.item("mfp", "monstrosity"), 1))
                .duration(20).minTier(7).build());
        builder.recipe(MfpRecipe.builder("mfp:assembler/greenhouse", "mfp:assembler", "test")
                .input(MfpIngredient.of(INGOT, 4))
                .output(MfpOutput.of(MfpKey.item("mfp", "greenhouse"), 1))
                .duration(20).minTier(1).build());

        List<RecipeScorer.Scored> ranked = new RecipeChooser(builder.build()).alternatives(DUST, null);

        // Alphabetically the monstrosity comes first, so this is not the order it fell into.
        assertEquals("mfp:greenhouse/grow", ranked.get(0).recipe().id());
        assertTrue(ranked.get(1).reasons().stream().anyMatch(r -> r.startsWith("needs a tier 7")));
    }

    /**
     * The greenhouse case as it actually is, which is not a scorer problem at all.
     *
     * <p>Star-Technology gives {@code tree_greenhouse} to both a greenhouse and a fermenting arboreal
     * rejuvenation monstrosity, so there is only <em>one</em> recipe and nothing for the scorer to
     * rank. The choice is the machine picker's, and both machines are multiblocks whose tier comes
     * from a hatch — so the first two sort terms tie and the third used to be the id, which put the
     * late-game multiblock first because its name sorts earlier.
     */
    @Test
    @DisplayName("two machines for one recipe type: the cheaper to build is the default")
    void picksTheCheaperOfTwoMachinesForTheSameRecipeType() {
        RecipeIndex.Builder builder = RecipeIndex.builder();
        builder.beginProvider("test", 0);
        builder.recipe(recipe("mfp:grow", ORE, 1, DUST, 1));

        builder.machine(new MfpMachine("mfp:aaa_monstrosity", "Monstrosity", -1, 0,
                List.of("mfp:machine"), true, List.of(), "test"));
        builder.machine(new MfpMachine("mfp:greenhouse", "Greenhouse", -1, 0,
                List.of("mfp:machine"), true, List.of(), "test"));

        builder.recipe(MfpRecipe.builder("mfp:assembly_line/monstrosity", "mfp:assembly_line", "test")
                .input(MfpIngredient.of(INGOT, 64))
                .output(MfpOutput.of(MfpKey.item("mfp", "aaa_monstrosity"), 1))
                .duration(20).minTier(7).build());
        builder.recipe(MfpRecipe.builder("mfp:assembler/greenhouse", "mfp:assembler", "test")
                .input(MfpIngredient.of(INGOT, 4))
                .output(MfpOutput.of(MfpKey.item("mfp", "greenhouse"), 1))
                .duration(20).minTier(1).build());

        Plan plan = new Plan("test").target(DUST, 1).rawMaterial(ORE);
        ChooserResult result = new RecipeChooser(builder.build()).expand(plan);

        assertEquals("mfp:greenhouse", result.lines().get(0).machine().machineId());
    }

    /**
     * A pin outranks the loop-avoidance pass, which used to quietly overrule it.
     *
     * <p>Reported from the pack: a greenhouse recipe for cabbage that takes bone meal, and a
     * composting recipe that makes bone meal from cabbage — a real loop. The retry avoided the
     * greenhouse recipe even though the user had pinned it, fell back to a crafting-table recipe
     * from an item nothing produces, and told the user to "pin one with a recipe choice if you
     * wanted it" about the recipe they had already pinned.
     *
     * <p>What should happen is what happens here: the pin stands, the loop stands with it, and the
     * plan is handed to the matrix engine, which is what loops are for.
     *
     * <p><b>The second link stopped being avoidable in §9.17</b>, and this fixture is why the change
     * is safe rather than merely convenient: composting is the only way to make bone meal, so
     * avoiding it does not break the loop, it deletes an item. The plan is the same either way — the
     * pin is used, the crafting-table fallback is not — but the loop now reaches the matrix engine,
     * which closes it exactly, instead of being broken by pretending bone meal cannot be made.
     */
    @Test
    @DisplayName("the loop-avoidance retry never drops a recipe the user pinned")
    void pinnedRecipesSurviveTheAcyclicRetry() {
        MfpRecipe greenhouse = recipe("mfp:greenhouse/cabbage_from_bonemeal", SALT, 1, DUST, 4);
        MfpRecipe composting = recipe("mfp:composting/bonemeal_from_cabbage", DUST, 2, SALT, 1);
        // A way out of the loop, which is what the retry would otherwise take.
        MfpRecipe byHand = recipe("mfp:crafting/cabbage_from_leaves", ORE, 2, DUST, 1);

        Plan plan = new Plan("cabbage").target(DUST, 1)
                .chooseRecipe(DUST, "mfp:greenhouse/cabbage_from_bonemeal");
        ChooserResult result = new RecipeChooser(indexOf(greenhouse, composting, byHand)).expand(plan);

        List<String> chosen = result.lines().stream().map(line -> line.recipe().id()).toList();
        assertTrue(chosen.contains("mfp:greenhouse/cabbage_from_bonemeal"),
                "the pinned recipe must be used, not avoided: " + chosen);
        assertFalse(chosen.contains("mfp:crafting/cabbage_from_leaves"));
        // Neither link may be avoided now: one is pinned, and the other is the only way to make
        // bone meal. So the loop is kept and handed to the engine that can close it.
        assertTrue(result.avoidedForCycles().isEmpty(), () -> String.valueOf(result.avoidedForCycles()));
        assertTrue(result.requiresMatrixSolver());

        // Pin both links and there is nothing left to avoid, so the loop is real and the matrix
        // engine is the answer — which is the honest outcome rather than a quiet override.
        Plan bothPinned = new Plan("cabbage").target(DUST, 1)
                .chooseRecipe(DUST, "mfp:greenhouse/cabbage_from_bonemeal")
                .chooseRecipe(SALT, "mfp:composting/bonemeal_from_cabbage");
        ChooserResult loop = new RecipeChooser(indexOf(greenhouse, composting, byHand)).expand(bothPinned);

        assertTrue(loop.requiresMatrixSolver());
        assertTrue(loop.avoidedForCycles().isEmpty(), "every link is pinned, so nothing may be avoided");
    }

    /**
     * A byproduct one line makes and another eats needs the matrix engine, loop or no loop.
     *
     * <p>The sequential pass carries demand downwards once, so it can only feed a line from below;
     * a byproduct whose consumer was already solved comes out as an import of something the plan
     * visibly produces. Detecting the shape at expansion time is what stops the user having to
     * notice it and switch engines by hand.
     */
    @Test
    @DisplayName("a shared byproduct sends an acyclic plan to the matrix engine")
    void sharedByproductsDeriveTheMatrixEngine() {
        // Smelting ore gives an ingot and some slag; the acid line eats the slag.
        MfpRecipe smelt = MfpRecipe.builder("mfp:smelt", "mfp:machine", "test")
                .input(MfpIngredient.of(ORE, 1))
                .output(MfpOutput.of(INGOT, 1))
                .output(MfpOutput.of(SALT, 1))
                .duration(20).euIn(16).minTier(1).build();
        MfpRecipe fromSlag = recipe("mfp:acid", SALT, 1, ACID, 1);
        MfpRecipe plate = MfpRecipe.builder("mfp:plate", "mfp:machine", "test")
                .input(MfpIngredient.of(INGOT, 1))
                .input(MfpIngredient.of(ACID, 1))
                .output(MfpOutput.of(PLATE, 1))
                .duration(20).euIn(16).minTier(1).build();

        Plan plan = new Plan("plate").target(PLATE, 1).rawMaterial(ORE);
        ChooserResult result = new RecipeChooser(indexOf(smelt, fromSlag, plate)).expandInto(plan);

        assertFalse(result.requiresMatrixSolver(), "there is no loop here, only a shared byproduct");
        assertEquals(SolverMode.MATRIX, plan.solverMode());
        assertTrue(plan.solverModeDerived(), "worked out, so a later expansion may take it back");
    }

    /**
     * The loop-avoidance pass may not ban an item out of existence to get an acyclic plan.
     *
     * <p>Reported from Star-Technology: a lubricant plan warned "nothing in the index produces
     * exnihilosequentia:dust" about an item a forge hammer makes. The pass had avoided every recipe
     * on the loop it was steering around — hundreds of them after six rounds — and one of those
     * recipes was also the only way to make the dust. The retry was acyclic and still made
     * lubricant, so it was accepted, and the plan then reported MFP's own decision as a fact about
     * the pack.
     *
     * <p>The loop is the better answer here: the matrix engine closes it exactly, and an acyclic
     * plan that imports something the player can make is wrong in a way that sends them looking for
     * a recipe that is right there.
     */
    @Test
    @DisplayName("steering around a loop never costs the plan an item the pack can make")
    void avoidanceDoesNotBanAnItemOutOfExistence() {
        // The target needs a dust, and the only thing that makes dust is a recipe inside a loop
        // elsewhere in the plan — exactly the pack's shape, where the dust came off a recipe the
        // avoidance pass banned for reasons that had nothing to do with dust.
        MfpRecipe plate = MfpRecipe.builder("mfp:plate", "mfp:machine", "test")
                .input(MfpIngredient.of(INGOT, 1))
                .input(MfpIngredient.of(DUST, 1))
                .output(MfpOutput.of(PLATE, 1))
                .duration(20).euIn(16).minTier(1).build();
        // The loop: ingot from acid, acid from ingot. The ingot line also gives off the dust.
        MfpRecipe ingot = MfpRecipe.builder("mfp:ingot", "mfp:machine", "test")
                .input(MfpIngredient.of(ACID, 1))
                .output(MfpOutput.of(INGOT, 1))
                .output(MfpOutput.of(DUST, 1))
                .duration(20).euIn(16).minTier(1).build();
        MfpRecipe acid = recipe("mfp:acid", INGOT, 1, ACID, 2);

        RecipeIndex index = indexOf(plate, ingot, acid);
        Plan plan = new Plan("plate").target(PLATE, 1).rawMaterial(ORE);
        ChooserResult result = new RecipeChooser(index).expand(plan);

        // Ingot and acid are the loop's own items, and importing one of them is how a loop is
        // broken. Dust is not: it merely hangs off a recipe the pass wanted to ban, so banning it
        // is collateral damage and the loop is the better answer.
        assertFalse(result.unresolved().contains(DUST),
                "dust is made by a recipe in the index and must not be reported as an import: "
                        + result.unresolved());
        assertTrue(result.requiresMatrixSolver(), "so the real loop is kept, for the matrix engine");
        assertFalse(String.join(" ", result.warnings()).contains("nothing in the index produces"),
                "MFP must not report its own avoidance as a fact about the pack: "
                        + result.warnings());
    }

    /**
     * And when an item really is lost to a decision, the plan says whose decision it was.
     *
     * <p>"Nothing in the index produces X" is a claim about the pack. Every exclusion the chooser
     * applies itself — a hidden recipe, a blocked input, an avoided loop — has to be reported as
     * what it is, or the user goes looking for a recipe that exists.
     */
    @Test
    @DisplayName("an item lost to a hidden recipe is reported as hidden, not as unmakeable")
    void hiddenRecipesAreNotReportedAsMissingRecipes() {
        RecipeIndex index = indexOf(
                recipe("mfp:dust", ORE, 1, DUST, 1),
                recipe("mfp:plate", DUST, 1, PLATE, 1));

        Plan plan = new Plan("plate").target(PLATE, 1).rawMaterial(ORE)
                .blacklistRecipe("mfp:dust");
        ChooserResult result = new RecipeChooser(index).expand(plan);

        assertTrue(result.unresolved().contains(DUST));
        String warnings = String.join(" ", result.warnings());
        assertFalse(warnings.contains("nothing in the index produces"), warnings);
        assertTrue(warnings.contains("you hid 1 recipe(s) for it"), warnings);
    }

    /**
     * A cycle is broken at a link the plan can replace, never at its only source of something.
     *
     * <p>The Star-Technology case, reproduced (STATUS §9.17). The loop ran cobblestone -> gravel ->
     * sand -> dust -> sieve -> cobblestone; cobblestone had seven recipes and dust had exactly one,
     * and the pass banned every member of the cycle, so the plan lost the item with no alternative
     * and reported it as an import. The members of a cycle are not equally replaceable and the pass
     * has to know it.
     */
    @Test
    @DisplayName("a cycle is broken at a replaceable link, not at the only source of something")
    void avoidanceBreaksTheCycleWhereItCan() {
        // PLATE needs DUST; DUST comes only from SALT; SALT comes only from INGOT; INGOT comes from
        // DUST, which closes the loop. INGOT is the replaceable link - it has a second recipe from
        // ore - so that is where the cycle must be broken.
        MfpRecipe plate = recipe("mfp:plate", DUST, 1, PLATE, 1);
        MfpRecipe dust = recipe("mfp:dust_from_salt", SALT, 1, DUST, 1);
        MfpRecipe salt = recipe("mfp:salt_from_ingot", INGOT, 1, SALT, 1);
        MfpRecipe ingotLoop = recipe("mfp:ingot_from_dust", DUST, 2, INGOT, 1);
        MfpRecipe ingotOre = recipe("mfp:ingot_from_ore", ORE, 1, INGOT, 1);

        Plan plan = new Plan("plate").target(PLATE, 1).rawMaterial(ORE);
        ChooserResult result = new RecipeChooser(
                indexOf(plate, dust, salt, ingotLoop, ingotOre)).expand(plan);

        List<String> chosen = result.lines().stream().map(line -> line.recipe().id()).toList();
        assertTrue(chosen.contains("mfp:ingot_from_ore"),
                "the loop is broken by taking the other route to the replaceable item: " + chosen);
        assertFalse(result.unresolved().contains(DUST),
                "and never by banning the only way to make something: " + result.unresolved());
        assertFalse(result.unresolved().contains(SALT), () -> String.valueOf(result.unresolved()));
        assertFalse(result.requiresMatrixSolver(), "which leaves an acyclic plan");
        // Whatever the pass had to pass over, it was never one of the two sole sources. Note it may
        // have had to pass over nothing at all here - the scorer dodges this loop on its own - and
        // that is the point of asserting the plan rather than the mechanism: the claim is that no
        // item is lost, by any route to that outcome.
        assertFalse(result.avoidedForCycles().contains("mfp:dust_from_salt"));
        assertFalse(result.avoidedForCycles().contains("mfp:salt_from_ingot"));
        assertTrue(result.isComplete() || result.unresolved().equals(List.of(ORE)),
                () -> "the plan still makes its target: " + result.unresolved());
    }

    @Test
    @DisplayName("a remembered machine choice survives re-expansion")
    void machineChoiceIsRemembered() {
        RecipeIndex index = indexOf(recipe("mfp:plate", INGOT, 1, PLATE, 1));
        Plan plan = new Plan("test").target(PLATE, 1).rawMaterial(INGOT)
                .chooseMachine("mfp:machine", "mfp:hv_machine");

        ChooserResult result = new RecipeChooser(index).expand(plan);

        assertEquals("mfp:hv_machine", result.lines().get(0).machine().machineId());
        assertEquals(3, result.lines().get(0).machine().tier());
    }

    // ------------------------------------------------- byproducts feed the plan (M11.1)

    /**
     * The shape the pack builds on purpose, reduced to four recipes.
     *
     * <p>Making a widget takes a gasket, and one of the two gasket recipes wants oxygen. Nothing on
     * the widget branch knows that the frame branch — expanded afterwards, from a different input —
     * gives oxygen off. A greedy depth-first walk therefore picks the gasket recipe that starts a
     * second chain, and the oxygen it was already making goes in the bin. That is STATUS §6d.28's
     * fault with the ancestor rule removed from it: an ancestor is visible to the walk, a sibling is
     * not, and the sibling is the common case.
     */
    @Test
    @DisplayName("a byproduct from a sibling branch is fed to the recipe that wants it")
    void aSiblingsByproductIsUsedRatherThanThrownAway() {
        RecipeIndex index = byproductIndex();

        Plan without = new Plan("no feeding").target(WIDGET, 1).byproductFeeds(false);
        ChooserResult plain = new RecipeChooser(index).expand(without);

        Plan with = new Plan("feeding").target(WIDGET, 1);
        ChooserResult fed = new RecipeChooser(index).expand(with);

        assertTrue(ids(plain).contains("mfp:gasket_from_brine"),
                () -> "without the pass, the walk cannot see the oxygen: " + ids(plain));
        assertTrue(ids(fed).contains("mfp:gasket_from_oxygen"),
                () -> "with it, the gasket recipe that eats the spare oxygen wins: " + ids(fed));
        assertEquals(List.of(OXYGEN), fed.byproductFeeds());
        assertTrue(ids(fed).size() < ids(plain).size(),
                () -> "and the brine chain it replaces is gone: " + ids(fed));
    }

    /** The setting is a real preference: off means the first walk's answer stands. */
    @Test
    void theByproductPassCanBeSwitchedOff() {
        RecipeIndex index = byproductIndex();

        ChooserResult off = new RecipeChooser(index)
                .expand(new Plan("off").target(WIDGET, 1).byproductFeeds(false));

        assertTrue(off.byproductFeeds().isEmpty());
        assertTrue(ids(off).contains("mfp:gasket_from_brine"));
    }

    /**
     * A packaging loop is still steered around; a productive one is now kept.
     *
     * <p>The test that separates them is conservation, and it needs both halves. Nugget to ingot and
     * back consumes nothing from outside itself, so it can supply nothing — the classic GregTech
     * unit conversion, and the retry pass exists for it. The tree loop takes charcoal from outside
     * and emits a log the plan asked for, so following it is the answer rather than a wrong turn.
     */
    @Test
    @DisplayName("a closed loop is broken and an open one is kept")
    void packagingLoopsAreAvoidedAndProductiveOnesAreNot() {
        RecipeIndex packaging = indexOf(
                recipe("mfp:ingot_from_ore", ORE, 1, INGOT, 1),
                recipe("mfp:ingot_from_nuggets", NUGGET, 9, INGOT, 1),
                recipe("mfp:nuggets_from_ingot", INGOT, 1, NUGGET, 9));
        ChooserResult broken = new RecipeChooser(packaging).expand(new Plan("nuggets").target(INGOT, 1));

        assertFalse(broken.requiresMatrixSolver(),
                () -> "a loop that consumes nothing from outside cannot supply anything: "
                        + broken.cycles());
        assertTrue(ids(broken).contains("mfp:ingot_from_ore"));

        ChooserResult kept = new RecipeChooser(treeIndex()).expand(new Plan("logs").target(LOG, 1));

        assertTrue(kept.requiresMatrixSolver(),
                "a loop drawing charcoal in and putting logs out is the plan, not a wrong turn");
        assertTrue(ids(kept).contains("mfp:grow_tree") && ids(kept).contains("mfp:burn_charcoal"),
                () -> String.valueOf(ids(kept)));
    }

    private static List<String> ids(ChooserResult result) {
        return idsOf(result.lines());
    }

    private static List<String> idsOf(List<Line> lines) {
        return lines.stream().map(line -> line.recipe().id()).toList();
    }

    private static final MfpKey WIDGET = MfpKey.item("mfp", "widget");
    private static final MfpKey GASKET = MfpKey.item("mfp", "gasket");
    private static final MfpKey FRAME = MfpKey.item("mfp", "frame");
    private static final MfpKey OXYGEN = MfpKey.fluid("mfp", "oxygen");
    private static final MfpKey BRINE = MfpKey.fluid("mfp", "brine");
    private static final MfpKey NUGGET = MfpKey.item("mfp", "nugget");
    private static final MfpKey LOG = MfpKey.item("mfp", "log");
    private static final MfpKey CHARCOAL = MfpKey.item("mfp", "charcoal");
    private static final MfpKey CARBON_DIOXIDE = MfpKey.fluid("mfp", "carbon_dioxide");

    /**
     * A widget needs a gasket and a frame; making the frame gives off oxygen nothing wants.
     *
     * <p>The two gasket recipes are deliberately close on every term the scorer can see, so what
     * decides between them is whether the plan already has the oxygen — which is the whole question.
     */
    private static RecipeIndex byproductIndex() {
        return indexOf(
                MfpRecipe.builder("mfp:widget", "mfp:machine", "test")
                        .input(MfpIngredient.of(GASKET, 1))
                        .input(MfpIngredient.of(FRAME, 1))
                        .output(MfpOutput.of(WIDGET, 1))
                        .duration(20).euIn(16).minTier(1).build(),
                MfpRecipe.builder("mfp:frame", "mfp:machine", "test")
                        .input(MfpIngredient.of(ORE, 1))
                        .output(MfpOutput.of(FRAME, 1))
                        .output(MfpOutput.of(OXYGEN, 1000))
                        .duration(20).euIn(16).minTier(1).build(),
                MfpRecipe.builder("mfp:gasket_from_oxygen", "mfp:machine", "test")
                        .input(MfpIngredient.of(DUST, 1))
                        .input(MfpIngredient.of(OXYGEN, 1000))
                        .output(MfpOutput.of(GASKET, 1))
                        .duration(20).euIn(16).minTier(1).build(),
                MfpRecipe.builder("mfp:gasket_from_brine", "mfp:machine", "test")
                        .input(MfpIngredient.of(DUST, 1))
                        .input(MfpIngredient.of(BRINE, 1000))
                        .output(MfpOutput.of(GASKET, 1))
                        .duration(20).euIn(16).minTier(1).build(),
                recipe("mfp:brine", SALT, 1, BRINE, 1000));
    }

    /** Growing a tree eats carbon dioxide and gives off nothing else; burning charcoal makes it. */
    private static RecipeIndex treeIndex() {
        return indexOf(
                MfpRecipe.builder("mfp:grow_tree", "mfp:machine", "test")
                        .input(MfpIngredient.of(CARBON_DIOXIDE, 500))
                        .output(MfpOutput.of(LOG, 1))
                        .output(MfpOutput.of(OXYGEN, 1000))
                        .duration(20).euIn(16).minTier(1).build(),
                MfpRecipe.builder("mfp:burn_charcoal", "mfp:machine", "test")
                        .input(MfpIngredient.of(CHARCOAL, 1))
                        .input(MfpIngredient.of(OXYGEN, 1000))
                        .output(MfpOutput.of(CARBON_DIOXIDE, 500))
                        .duration(20).euIn(16).minTier(1).build());
    }

    /**
     * The pack's tree loop, and the two scorer terms that were hiding it.
     *
     * <p>Reported from a real plan: with the carbon dioxide greenhouse pinned, MFP built five lines
     * of beetroot farming to make the carbon dioxide and threw the greenhouse's oxygen away, when
     * burning charcoal in that oxygen closes the loop in two. Both penalties that buried it are about
     * loops, and both are right about the loops they were written for:
     *
     * <ul>
     *   <li>{@code consumes N item(s) the plan already makes} — separated by the spare-byproduct
     *       term, since the plan is discarding the oxygen rather than fighting over it;
     *   <li>{@code reversible conversion} — the greenhouse turns carbon dioxide into oxygen, so
     *       <em>any</em> recipe making carbon dioxide out of oxygen looks like its reverse. That is
     *       true and it is not a reason to refuse: the pair is the loop, not a packaging pair.
     * </ul>
     *
     * <p>The competing recipe here is deliberately the one the scorer prefers on every local term —
     * one input, no reversal, no cycle risk — which is exactly the position the beetroot chain was in.
     */
    @Test
    @DisplayName("a loop that looks reversible is still the answer when the plan has the input spare")
    void aProductiveLoopBeatsALongerChainTheScorerPrefersLocally() {
        RecipeIndex index = treeAndBiomassIndex();

        ChooserResult off = new RecipeChooser(index)
                .expand(new Plan("off").target(LOG, 1).byproductFeeds(false));
        ChooserResult on = new RecipeChooser(index)
                .expand(new Plan("on").target(LOG, 1));

        assertTrue(ids(off).contains("mfp:co2_from_biomass"),
                () -> "the scorer prefers the chain on every local term: " + ids(off));
        assertFalse(ids(off).contains("mfp:burn_charcoal"),
                () -> "and the loop is invisible to it: " + ids(off));

        assertTrue(ids(on).contains("mfp:burn_charcoal"),
                () -> "with the oxygen offered, the two-line loop wins: " + ids(on));
        assertFalse(ids(on).contains("mfp:co2_from_biomass"),
                () -> "and the biomass chain is gone: " + ids(on));
        assertEquals(List.of(OXYGEN), on.byproductFeeds());
        assertTrue(on.requiresMatrixSolver(), "the loop is kept for the whole-plan engine to close");
    }

    private static final MfpKey BIOMASS = MfpKey.fluid("mfp", "biomass");
    private static final MfpKey BEETROOT = MfpKey.item("mfp", "beetroot");

    /** The tree loop, plus a longer carbon dioxide chain that outscores it on every local term. */
    private static RecipeIndex treeAndBiomassIndex() {
        return indexOf(
                MfpRecipe.builder("mfp:grow_tree", "mfp:machine", "test")
                        .input(MfpIngredient.of(CARBON_DIOXIDE, 500))
                        .output(MfpOutput.of(LOG, 1))
                        .output(MfpOutput.of(OXYGEN, 1000))
                        .duration(20).euIn(16).minTier(1).build(),
                MfpRecipe.builder("mfp:burn_charcoal", "mfp:machine", "test")
                        .input(MfpIngredient.of(CHARCOAL, 1))
                        .input(MfpIngredient.of(OXYGEN, 1000))
                        .output(MfpOutput.of(CARBON_DIOXIDE, 500))
                        .duration(20).euIn(16).minTier(1).build(),
                recipe("mfp:co2_from_biomass", BIOMASS, 1, CARBON_DIOXIDE, 500),
                recipe("mfp:biomass", BEETROOT, 1, BIOMASS, 1));
    }

    // ------------------------------------------------------------ M11.2 and M11.3, hand-building

    private static final MfpKey GAS = MfpKey.fluid("mfp", "gas");
    private static final MfpKey WIDGET_ORE = MfpKey.item("mfp", "widget_ore");

    @Test
    @DisplayName("with auto-resolve off, expansion stops below the target")
    void handBuildingStopsBelowTheTarget() {
        RecipeIndex index = indexOf(
                recipe("mfp:dust", ORE, 1, DUST, 1),
                recipe("mfp:ingot", DUST, 1, INGOT, 1),
                recipe("mfp:plate", INGOT, 1, PLATE, 1));

        ChooserResult result = new RecipeChooser(index)
                .expand(new Plan("by hand").target(PLATE, 1).rawMaterial(ORE).autoResolve(false));

        assertEquals(List.of("mfp:plate"), ids(result),
                "the target still gets a line - a plan with nothing in it has nothing to click");
        assertEquals(List.of(INGOT), result.unresolved());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("auto-resolve is off")),
                () -> "and it says why, rather than claiming the pack cannot make it: "
                        + result.warnings());
    }

    @Test
    @DisplayName("answering one import adds one line and asks the next question")
    void answeringAnImportAddsOneLine() {
        RecipeIndex index = indexOf(
                recipe("mfp:dust", ORE, 1, DUST, 1),
                recipe("mfp:ingot", DUST, 1, INGOT, 1),
                recipe("mfp:plate", INGOT, 1, PLATE, 1));

        Plan plan = new Plan("by hand").target(PLATE, 1).rawMaterial(ORE).autoResolve(false);
        plan.chooseRecipe(INGOT, "mfp:ingot");
        ChooserResult result = new RecipeChooser(index).expand(plan);

        assertEquals(List.of("mfp:plate", "mfp:ingot"), ids(result));
        assertEquals(List.of(DUST), result.unresolved(), "one layer at a time, in order");
    }

    /**
     * The milestone's own acceptance: hand-building and automatic expansion are the same planner.
     *
     * <p>Answering every import with the scorer's first choice is what the automatic walk does at
     * each step, so the two must arrive at the same lines and the same numbers. If they ever did
     * not, one of the two paths would be doing something it does not admit to.
     */
    @Test
    @DisplayName("a plan built by hand solves to the same numbers as the automatic one")
    void handBuiltMatchesAutomatic() {
        RecipeIndex index = indexOf(
                recipe("mfp:dust", ORE, 1, DUST, 1),
                recipe("mfp:ingot", DUST, 1, INGOT, 1),
                recipe("mfp:plate", INGOT, 1, PLATE, 1));

        Plan automatic = new Plan("auto").target(PLATE, 3).rawMaterial(ORE);
        new RecipeChooser(index).expandInto(automatic);
        SolveResult expected = new SequentialSolver().solve(automatic);

        Plan byHand = new Plan("by hand").target(PLATE, 3).rawMaterial(ORE).autoResolve(false);
        ChooserResult result = null;
        for (int click = 0; click < 8; click++) {
            byHand.clearLines();
            result = new RecipeChooser(index).expandInto(byHand);
            if (result.unresolved().isEmpty()) {
                break;
            }
            // Exactly what clicking an import and taking the top row does.
            for (MfpKey key : result.unresolved()) {
                byHand.chooseRecipe(key,
                        new RecipeChooser(index).alternatives(key, byHand).get(0).recipe().id());
            }
        }
        SolveResult actual = new SequentialSolver().solve(byHand);

        assertEquals(idsOf(automatic.allLines()), idsOf(byHand.allLines()),
                "the same recipes, in the same order");
        assertTrue(result != null && result.unresolved().isEmpty(), "and nothing left to answer");
        assertEquals(expected.euDrawPerSecond(), actual.euDrawPerSecond(), 1e-9);
        assertEquals(expected.rawInputs(), actual.rawInputs());
        for (int i = 0; i < expected.lines().size(); i++) {
            assertEquals(expected.lines().get(i).machineCount(),
                    actual.lines().get(i).machineCount(), 1e-9,
                    "line " + i + " runs at the same rate");
        }
    }

    /**
     * A byproduct covering <em>part</em> of a demand, which is the shape M11 was written around.
     *
     * <p>The chooser cannot express this — it commits to one recipe per item before anything is
     * solved, so "take the 40 the plan already makes and import the rest" is not a choice of recipe
     * (see {@code PLAN.md} 13a). Built by hand it is expressible, because the user adds the line
     * that makes the 40 and leaves the question of where the rest comes from to the solver, which is
     * exactly what the simplex engine answers.
     */
    @Test
    @DisplayName("a hand-built plan imports only the part its own byproduct does not cover")
    void aByproductCoveringPartOfADemandImportsTheRemainder() {
        RecipeIndex index = indexOf(
                MfpRecipe.builder("mfp:widget", "mfp:machine", "test")
                        .input(MfpIngredient.of(GAS, 100))
                        .input(MfpIngredient.of(PLATE, 1))
                        .output(MfpOutput.of(INGOT, 1))
                        .duration(20).euIn(16).minTier(1).build(),
                MfpRecipe.builder("mfp:plate_and_gas", "mfp:machine", "test")
                        .input(MfpIngredient.of(WIDGET_ORE, 1))
                        .output(MfpOutput.of(PLATE, 1))
                        .output(MfpOutput.of(GAS, 40))
                        .duration(20).euIn(16).minTier(1).build());

        Plan plan = new Plan("by hand").target(INGOT, 1)
                .rawMaterial(WIDGET_ORE)
                .autoResolve(false)
                .solverMode(SolverMode.SIMPLEX);
        plan.chooseRecipe(PLATE, "mfp:plate_and_gas");
        // One machine, and exactly one: the plan is asking "I have this line, where does the rest of
        // the gas come from?", and without the limit the engine answers by running three of them and
        // throwing the spare plates away - which is a correct answer to a different question.
        plan.configureMachine("mfp:plate_and_gas",
                dev.mfp.core.plan.MachineConfig.of("mfp:lv_machine", 1).withLimit(1, true));
        new RecipeChooser(index).expandInto(plan);
        SolveResult solved = new dev.mfp.core.solver.SimplexSolver().solve(plan);

        assertEquals(60.0, solved.rawInputs().getOrDefault(GAS, 0.0), 1e-9,
                () -> "the plan makes 40 of the 100 it needs: " + solved.rawInputs());
    }

    @Test
    @DisplayName("a pinned recipe is expanded even for an item the plan calls raw")
    void aPinOutranksTheRawMaterialCutoff() {
        RecipeIndex index = indexOf(
                recipe("mfp:dust", ORE, 1, DUST, 1),
                recipe("mfp:ingot", DUST, 1, INGOT, 1));

        Plan plan = new Plan("raw dust").target(INGOT, 1).rawMaterial(DUST);
        assertEquals(List.of("mfp:ingot"), ids(new RecipeChooser(index).expand(plan)),
                "declared raw, so the walk stops there");

        // What clicking that import in the picker does. Without this the picker would open on a raw
        // import, offer recipes, and changing nothing.
        plan.chooseRecipe(DUST, "mfp:dust");
        assertEquals(List.of("mfp:ingot", "mfp:dust"), ids(new RecipeChooser(index).expand(plan)));
    }
}
