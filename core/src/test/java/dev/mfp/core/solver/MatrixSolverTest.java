package dev.mfp.core.solver;

import dev.mfp.core.model.Confidence;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.Line;
import dev.mfp.core.plan.MachineConfig;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.plan.SolverMode;
import org.junit.jupiter.api.Test;

import static dev.mfp.core.solver.Fixtures.CASING;
import static dev.mfp.core.solver.Fixtures.FUEL;
import static dev.mfp.core.solver.Fixtures.ORE;
import static dev.mfp.core.solver.Fixtures.STONE_DUST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The matrix engine, checked against numbers worked out by hand.
 *
 * <p>Every expectation here is arithmetic stated in the test, not a golden value captured from a
 * previous run. A golden value would move silently when the model changes; these say why the answer
 * is what it is, which is what makes them useful when it does.
 */
class MatrixSolverTest {

    private static final double TOLERANCE = 1e-9;

    private final MatrixSolver solver = new MatrixSolver();

    // ------------------------------------------------------------- the point

    /**
     * The headline case: an acid loop that feeds back into its own supply.
     *
     * <pre>
     *   smelt:   1 concentrate        -> 1 metal        20 ticks
     *   leach:   1 ore + 0.5 acid     -> 1 concentrate  20 ticks
     *   regen:   1 concentrate        -> 1 acid         20 ticks
     * </pre>
     *
     * <p>Asking for 1 metal/s, the concentrate demand is not 1/s: regenerating the leaching acid
     * eats concentrate too, and that extra concentrate needs more acid, and so on. The series is
     * {@code x = 1 + 0.5x}, so leaching runs at <b>2</b> crafts/s, regeneration at 1, and the ore
     * bill is 2/s rather than the 1/s a single pass arrives at.
     *
     * <p>This is a genuine loop, not a mis-ordered plan: whichever order the three lines are put in,
     * one of them needs something a line above it makes (STATUS 5.2). Acid should net to exactly
     * zero — neither imported nor surplus — which is what "the loop closed" looks like.
     */
    @Test
    void aFeedbackLoopBalancesWhereASinglePassCannot() {
        SolveResult result = solver.solve(acidLoopPlan());

        assertEquals(1.0, result.products().get(Loop.METAL), TOLERANCE);
        assertEquals(2.0, result.rawInputs().get(ORE), TOLERANCE);
        assertNull(result.rawInputs().get(Loop.ACID), "the acid is recycled, not bought");
        assertNull(result.byproducts().get(Loop.ACID), "and none of it is left over either");

        assertEquals(1.0, rateOf(result, "mfp:smelt"), TOLERANCE);
        assertEquals(2.0, rateOf(result, "mfp:leach"), TOLERANCE);
        assertEquals(1.0, rateOf(result, "mfp:regen"), TOLERANCE);
        assertTrue(result.isComplete());
        assertEquals(SolverMode.MATRIX, result.engine());
    }

    /**
     * The same plan through the sequential engine, so the difference is on the record.
     *
     * <p>STATUS 5.9 asked for exactly this: the documented limitation becomes a regression test for
     * the fix. The single pass must still fail honestly — it under-counts the loop, imports an item
     * it also produces, and says so — while the matrix engine above closes it.
     */
    @Test
    void theSequentialEngineStillFailsHonestlyOnTheSamePlan() {
        SolveResult sequential = new SequentialSolver().solve(acidLoopPlan());

        assertTrue(sequential.rawInputs().containsKey(Loop.CONCENTRATE),
                "the feedback demand lands on a line already solved, so it becomes an import");
        assertEquals(1.0, sequential.rawInputs().get(ORE), TOLERANCE);
        assertTrue(sequential.warnings().stream().anyMatch(w -> w.contains("matrix solver")),
                "and it must say so: " + sequential.warnings());
    }

    /**
     * A leaky loop is over-constrained, and the engine now frees the item itself.
     *
     * <p>Leaching takes 2 acid and refining returns only 1.5, so "acid nets to zero" and "one
     * concentrate per metal" cannot both hold. That is a real contradiction rather than a solver
     * limitation — the plan as written says the acid comes from nowhere — and until M6d it was
     * reported as unsolvable, leaving the sequential pass to answer instead.
     *
     * <p>It answered badly. The same shape in the pack is a multi-output centrifuge whose essences
     * cannot be consumed in the ratio it makes them, and the fallback sized that plan at thirty
     * thousand centrifuges. Since the fix is the one the diagnosis already named — let the item be
     * imported or exported — the engine now does it, once, and says which items it did it to. The
     * numbers are identical to freeing the item by hand, which is the property that makes the
     * automatic version defensible.
     */
    @Test
    void aLeakyLoopFreesTheDeadlockedItemAndSaysSo() {
        SolveResult relaxed = solver.solve(leakyLoopPlan());

        // Concentrate, not acid: freeing it leaks 0.25/s where freeing acid leaks 0.5/s, and the
        // rule is the least intervention that makes the plan describable. Either is a correct
        // answer to an over-constrained system; what matters is that one is chosen, named, and the
        // target still balances exactly.
        assertEquals(1.0, relaxed.products().get(Loop.METAL), TOLERANCE);
        assertEquals(0.25, relaxed.rawInputs().get(Loop.CONCENTRATE), TOLERANCE);
        assertEquals(0.75, relaxed.rawInputs().get(ORE), TOLERANCE);
        assertTrue(relaxed.warnings().stream().anyMatch(w -> w.contains("concentrate")),
                "the plan must say which item stopped balancing: " + relaxed.warnings());

        // Saying which item to free is still the user's call and still wins: freeing acid by hand
        // gives acid's answer, imports the 0.5/s the loop actually loses, and warns about nothing,
        // because nothing was decided on the user's behalf.
        SolveResult byHand = solver.solve(leakyLoopPlan().freeItem(Loop.ACID));

        assertEquals(0.5, byHand.rawInputs().get(Loop.ACID), TOLERANCE);
        assertEquals(1.0, byHand.rawInputs().get(ORE), TOLERANCE);
        assertEquals(1.0, byHand.products().get(Loop.METAL), TOLERANCE);
        assertTrue(byHand.warnings().stream().noneMatch(w -> w.contains("allowed")),
                () -> String.valueOf(byHand.warnings()));
    }

    // ------------------------------------------------- agreement with M3's engine

    /**
     * The five-step chain from {@link Fixtures}, whose every number was hand-computed for M3.
     *
     * <p>An acyclic plan has one answer, so the two engines must agree on it exactly. If they ever
     * disagree here, one of them is wrong about something more fundamental than loops.
     */
    @Test
    void anAcyclicChainAgreesWithTheSequentialEngine() {
        SolveResult matrix = solver.solve(fiveStepChain());
        SolveResult sequential = new SequentialSolver().solve(fiveStepChain());

        assertEquals(10, matrix.totalMachines());
        assertEquals(sequential.totalMachines(), matrix.totalMachines());
        assertEquals(1.0, matrix.rawInputs().get(ORE), TOLERANCE);
        assertEquals(sequential.rawInputs().get(ORE), matrix.rawInputs().get(ORE), TOLERANCE);
        // 320 + 160 + 320 + 320 + 320 EU/s. A draw, not an import: energy left the balance in M6c
        // (plan §13.4), so it is summed from the lines rather than solved for.
        assertEquals(1440.0, matrix.euDrawPerSecond(), TOLERANCE);
        assertEquals(sequential.euDrawPerSecond(), matrix.euDrawPerSecond(), TOLERANCE);
        assertNull(matrix.rawInputs().get(MfpKey.EU), "energy is drawn, not imported");
    }

    /** Machine counts follow from the solved craft rates, and stay fractional. */
    @Test
    void machineCountsComeFromTheSolvedRates() {
        SolveResult result = solver.solve(fiveStepChain());

        // ingot: 40 ticks is 0.5 crafts/s per machine, and 2 crafts/s are needed -> 4 machines.
        LineResult ingot = result.lines().get(2);
        assertEquals("mfp:ingot", ingot.line().recipe().id());
        assertEquals(2.0, ingot.craftsPerSecond(), TOLERANCE);
        assertEquals(4.0, ingot.machineCount(), TOLERANCE);
    }

    /**
     * A generator is <b>no longer</b> sized by the plan's own demand, and this test is the record of
     * that being deliberate.
     *
     * <p>Until M6c the energy row was constrained, so adding this line made the chain power itself:
     * 1440 EU/s needed, 640 EU/s per generator, 2.25 generators and 2.25 fuel/s. It fell out of the
     * system beautifully and it answered a question players do not ask — they arrive with a power
     * setup and want to know what this factory adds to it (plan §13.4). With energy free, nothing
     * constrains the generator's rate, so it is dropped as a line the plan says nothing about, and
     * the draw is reported gross.
     */
    @Test
    void aGeneratorIsNoLongerSizedByThePlansOwnDemand() {
        Plan plan = fiveStepChain();
        plan.add(new Line(Fixtures.generator()));

        SolveResult result = solver.solve(plan);

        LineResult generator = result.lines().get(5);
        assertEquals(0.0, generator.machineCount(), TOLERANCE);
        assertTrue(generator.isIdle());
        assertNull(result.rawInputs().get(FUEL), "no fuel is bought to power a plan MFP is not powering");
        assertEquals(1440.0, result.euDrawPerSecond(), TOLERANCE);
        assertFalse(result.generatesPower());
    }

    /**
     * A line that happens to generate still runs for its <em>material</em>, and its power is
     * reported beside the draw rather than subtracted from it.
     *
     * <p>Burning the chain's own stone dust is pinned by how much dust the chain makes — 1/s, so
     * 640 EU/s — because the dust row is constrained even though the energy row is not. That is the
     * distinction M6c rests on: the steam turbine in the distilled-water chain is chosen for the
     * water, keeps running for the water, and its EU is excluded from the total rather than dropped.
     */
    @Test
    void aGeneratingLineRunsForItsMaterialAndItsPowerIsNotNetted() {
        MfpRecipe dustBurner = MfpRecipe.builder("mfp:burn_stone_dust", "mfp:generator", "test")
                .input(MfpIngredient.of(STONE_DUST, 1))
                .duration(20)
                .euOut(32)
                .build();

        Plan plan = new Plan("partly self-powered").target(CASING, 1.0);
        plan.add(new Line(Fixtures.casing()));
        plan.add(new Line(Fixtures.plate()));
        plan.add(new Line(Fixtures.ingot()));
        plan.add(new Line(Fixtures.dust()));
        plan.add(new Line(Fixtures.crushedWithByproduct()));
        plan.add(new Line(dustBurner));

        SolveResult result = solver.solve(plan);

        assertEquals(1.0, rateOf(result, "mfp:burn_stone_dust"), TOLERANCE);
        assertNull(result.rawInputs().get(MfpKey.EU), "energy is drawn, not imported");
        assertEquals(1440.0, result.euDrawPerSecond(), TOLERANCE, "gross, with nothing netted off");
        assertEquals(640.0, result.euGeneratedPerSecond(), TOLERANCE);
        assertTrue(result.generatesPower());
        assertNull(result.byproducts().get(STONE_DUST), "all of it is burnt");
    }

    // ------------------------------------------------------------ the details

    /**
     * Constant overhead belongs in {@code b}, never in a column (plan §9.2, STATUS 5.8).
     *
     * <p>The test is that the constant changes the energy bill and nothing else. If it leaked into a
     * per-craft coefficient the system would become non-linear in machine count, and the machine
     * counts would come out wrong rather than merely imprecise — so the assertion that matters most
     * here is the one about the unchanged machine count.
     */
    @Test
    void idleDrainGoesToTheRightHandSideAndDoesNotDistortMachineCounts() {
        ThroughputResolver withIdleDrain = (recipe, machine) -> {
            Throughput base = ThroughputResolver.BASE.resolve(recipe, machine);
            if (!recipe.id().equals("mfp:ingot")) {
                return base;
            }
            return new Throughput(base.craftsPerSecond(), base.euInPerSecond(), base.euOutPerSecond(),
                    100.0, 0, 0, Confidence.EXACT, null);
        };

        SolveResult plain = solver.solve(fiveStepChain());
        SolveResult drained = new MatrixSolver(withIdleDrain).solve(fiveStepChain());

        assertEquals(plain.lines().get(2).machineCount(), drained.lines().get(2).machineCount(),
                TOLERANCE, "a constant must not change how many machines the rate needs");
        assertEquals(1440.0 + 100.0, drained.euDrawPerSecond(), TOLERANCE);
    }

    /** A byproduct nothing consumes comes out as an export, not as a demand on the plan. */
    @Test
    void anUnwantedByproductIsExported() {
        Plan plan = new Plan("byproduct").target(Fixtures.CRUSHED, 2.0);
        plan.add(new Line(Fixtures.crushedWithByproduct()));

        SolveResult result = solver.solve(plan);

        // 2 crushed per craft, so 1 craft/s, which also drops 1 stone dust per second.
        assertEquals(1.0, result.lines().get(0).craftsPerSecond(), TOLERANCE);
        assertEquals(1.0, result.byproducts().get(STONE_DUST), TOLERANCE);
        assertEquals(1.0, result.rawInputs().get(ORE), TOLERANCE);
    }

    /**
     * A byproduct two lines both want is the other half of what the matrix engine is for.
     *
     * <p>The sequential pass hands surplus downward only, so whichever consumer it reaches second
     * gets nothing. Balanced as a system there is no "second": the stone dust row simply has to sum
     * to zero across every line that touches it.
     */
    @Test
    void aSharedByproductFeedsTheLineThatWantsIt() {
        Plan plan = new Plan("shared byproduct").target(Fixtures.GRAVEL, 1.0);
        // Gravel is made from stone dust, which is only ever a byproduct of crushing ore.
        plan.add(new Line(Fixtures.gravelFromStoneDust()));
        plan.add(new Line(Fixtures.crushedWithByproduct()));

        SolveResult result = solver.solve(plan);

        assertEquals(1.0, result.products().get(Fixtures.GRAVEL), TOLERANCE);
        assertNull(result.rawInputs().get(STONE_DUST), "the stone dust is produced right here");
        assertEquals(1.0, result.rawInputs().get(ORE), TOLERANCE);
        // Crushing runs at 1 craft/s for the dust, so its 2 crushed/s are surplus.
        assertEquals(2.0, result.byproducts().get(Fixtures.CRUSHED), TOLERANCE);
    }

    /**
     * A line says which part of its own production the plan wanted.
     *
     * <p>The engine cannot know that while it solves — surplus is a property of the balanced plan —
     * but it can say so afterwards, and it has to. Listing every output as a product while the
     * Byproducts tab called most of them surplus was the same plan answering the same question two
     * ways depending on where you looked.
     */
    @Test
    void aLineSeparatesWhatWasWantedFromWhatWasLeftOver() {
        Plan plan = new Plan("shared byproduct").target(Fixtures.GRAVEL, 1.0);
        plan.add(new Line(Fixtures.gravelFromStoneDust()));
        plan.add(new Line(Fixtures.crushedWithByproduct()));

        LineResult crushing = solver.solve(plan).lines().get(1);

        assertEquals(1.0, crushing.outputs().get(STONE_DUST), TOLERANCE,
                "the dust is why this line is here: the gravel line eats all of it");
        assertNull(crushing.outputs().get(Fixtures.CRUSHED), "nothing in the plan wants crushed ore");
        assertEquals(2.0, crushing.byproducts().get(Fixtures.CRUSHED), TOLERANCE);
    }

    /** Catalysts are netted out before the system is built, so they never appear as flows. */
    @Test
    void aBorrowedCatalystNeverEntersTheSystem() {
        Plan plan = new Plan("catalyst").target(Fixtures.PLATE, 1.0);
        plan.add(new Line(Fixtures.lubricatedPress()));

        SolveResult result = solver.solve(plan);

        assertNull(result.rawInputs().get(Fixtures.LUBRICANT),
                "the press gives all of it back, so none of it flows");
        assertEquals(1.0, result.rawInputs().get(Fixtures.INGOT), TOLERANCE);
    }

    /** A disabled line is absent from the system entirely, not solved at zero and then charged. */
    @Test
    void aDisabledLineContributesNothing() {
        Plan plan = new Plan("disabled").target(CASING, 1.0);
        plan.add(new Line(Fixtures.casing()));
        plan.add(new Line(Fixtures.plate()).active(false));

        SolveResult result = solver.solve(plan);

        LineResult disabled = result.lines().get(1);
        assertTrue(disabled.isIdle());
        assertEquals(0.0, disabled.euInPerSecond(), TOLERANCE);
        assertEquals(2.0, result.rawInputs().get(Fixtures.PLATE), TOLERANCE);
    }

    // ---------------------------------------------------------- honest failure

    /**
     * Two ways to make the same thing, with nothing to choose between them, has no single answer.
     *
     * <p>This is the linear-dependence case plan §9.2 asks to be reported rather than guessed at.
     * Any split between the two recipes balances equally well, and picking one would be inventing a
     * decision the user has not made.
     */
    @Test
    void interchangeableRecipesAreReportedRatherThanGuessedAt() {
        Plan plan = new Plan("ambiguous").target(Fixtures.DUST, 1.0);
        plan.add(new Line(Fixtures.simple("mfp:dust_a", Fixtures.CRUSHED, 1, Fixtures.DUST, 1, 20, 8)));
        plan.add(new Line(Fixtures.simple("mfp:dust_b", Fixtures.CRUSHED, 1, Fixtures.DUST, 1, 20, 8)));

        MatrixSolver.UnsolvableSystemException failure = assertThrows(
                MatrixSolver.UnsolvableSystemException.class, () -> solver.solve(plan));

        // One of the two takes the pivot; the other is the one whose rate nothing decides, and that
        // is the line the user has to do something about.
        assertTrue(failure.getMessage().contains("mfp:dust_b"),
                "the undetermined line must be named, got: " + failure.getMessage());
    }

    /** A target nothing in the plan makes is unsatisfied, not quietly listed as something to buy. */
    @Test
    void anUnproducedTargetIsReportedAsUnsatisfied() {
        Plan plan = new Plan("nothing makes this").target(CASING, 1.0);
        plan.add(new Line(Fixtures.plate()));   // makes plates, not casings

        SolveResult result = solver.solve(plan);

        assertEquals(1.0, result.unsatisfied().get(CASING), TOLERANCE);
        assertFalse(result.isComplete());
        assertNull(result.rawInputs().get(CASING), "importing the target is not a plan");
    }

    /** Settings the engine cannot honour are named, not silently dropped (plan P4). */
    @Test
    void ignoredLimitsAndPercentagesAreCalledOut() {
        Plan plan = new Plan("limited").target(CASING, 1.0);
        plan.add(new Line(Fixtures.casing(), MachineConfig.UNSET.withLimit(4.0, false)));
        plan.add(new Line(Fixtures.plate()).percentage(50));

        SolveResult result = solver.solve(plan);

        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("machine limits")),
                "got: " + result.warnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("percentages")),
                "got: " + result.warnings());
    }

    // ------------------------------------------------------------- dispatch

    /** {@code Solvers} routes by the plan's mode, which the chooser sets from an observed cycle. */
    @Test
    void solversRoutesByModeAndFallsBackWhenTheSystemCannotBeSolved() {
        Plan acyclic = fiveStepChain();
        assertEquals(SolverMode.SEQUENTIAL, Solvers.solve(acyclic).engine());

        Plan loop = acidLoopPlan().solverMode(SolverMode.MATRIX);
        assertEquals(SolverMode.MATRIX, Solvers.solve(loop).engine());

        Plan ambiguous = new Plan("ambiguous").target(Fixtures.DUST, 1.0).solverMode(SolverMode.MATRIX);
        ambiguous.add(new Line(Fixtures.simple("mfp:dust_a", Fixtures.CRUSHED, 1, Fixtures.DUST, 1, 20, 8)));
        ambiguous.add(new Line(Fixtures.simple("mfp:dust_b", Fixtures.CRUSHED, 1, Fixtures.DUST, 1, 20, 8)));

        SolveResult fallback = Solvers.solve(ambiguous);
        assertEquals(SolverMode.SEQUENTIAL, fallback.engine(),
                "an unsolvable system must fall back rather than show nothing");
        assertTrue(fallback.warnings().stream().anyMatch(w -> w.contains("could not solve")),
                "and must say the numbers are not the matrix engine's: " + fallback.warnings());
    }

    // -------------------------------------------------------------- fixtures

    /** Item keys for the acid loop; kept out of {@link Fixtures} because only this test uses them. */
    private static final class Loop {
        private static final MfpKey ACID = MfpKey.fluid("mfp", "acid");
        private static final MfpKey CONCENTRATE = MfpKey.item("mfp", "concentrate");
        private static final MfpKey METAL = MfpKey.item("mfp", "metal");
    }

    private static Plan acidLoopPlan() {
        MfpRecipe smelt = MfpRecipe.builder("mfp:smelt", "mfp:furnace", "test")
                .input(MfpIngredient.of(Loop.CONCENTRATE, 1))
                .output(MfpOutput.of(Loop.METAL, 1))
                .duration(20)
                .build();
        MfpRecipe leach = MfpRecipe.builder("mfp:leach", "mfp:leacher", "test")
                .input(MfpIngredient.of(ORE, 1))
                .input(MfpIngredient.of(Loop.ACID, 0.5))
                .output(MfpOutput.of(Loop.CONCENTRATE, 1))
                .duration(20)
                .build();
        // Regenerating the leaching acid costs concentrate, which is what closes the loop.
        MfpRecipe regen = MfpRecipe.builder("mfp:regen", "mfp:regenerator", "test")
                .input(MfpIngredient.of(Loop.CONCENTRATE, 1))
                .output(MfpOutput.of(Loop.ACID, 1))
                .duration(20)
                .build();

        Plan plan = new Plan("acid loop").target(Loop.METAL, 1.0);
        plan.add(new Line(smelt));
        plan.add(new Line(leach));
        plan.add(new Line(regen));
        return plan;
    }

    private static double rateOf(SolveResult result, String recipeId) {
        return result.lines().stream()
                .filter(line -> line.line().recipe().id().equals(recipeId))
                .mapToDouble(LineResult::craftsPerSecond)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no line for " + recipeId));
    }

    /** The acid loop, but refining returns only 1.5 of the 2 acid the leacher takes. */
    private static Plan leakyLoopPlan() {
        MfpRecipe leach = MfpRecipe.builder("mfp:leach", "mfp:leacher", "test")
                .input(MfpIngredient.of(ORE, 1))
                .input(MfpIngredient.of(Loop.ACID, 2))
                .output(MfpOutput.of(Loop.CONCENTRATE, 1))
                .duration(20)
                .build();
        MfpRecipe refine = MfpRecipe.builder("mfp:refine", "mfp:refiner", "test")
                .input(MfpIngredient.of(Loop.CONCENTRATE, 1))
                .output(MfpOutput.of(Loop.METAL, 1))
                .output(MfpOutput.of(Loop.ACID, 1.5))
                .duration(20)
                .build();

        Plan plan = new Plan("leaky loop").target(Loop.METAL, 1.0);
        plan.add(new Line(leach));
        plan.add(new Line(refine));
        return plan;
    }

    private static Plan fiveStepChain() {
        Plan plan = new Plan("five steps").target(CASING, 1.0);
        plan.add(new Line(Fixtures.casing()));
        plan.add(new Line(Fixtures.plate()));
        plan.add(new Line(Fixtures.ingot()));
        plan.add(new Line(Fixtures.dust()));
        plan.add(new Line(Fixtures.crushed()));
        return plan;
    }
}
