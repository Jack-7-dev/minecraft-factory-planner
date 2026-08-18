package dev.mfp.core.solver;

import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.Line;
import dev.mfp.core.plan.MachineConfig;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.plan.SolverMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.mfp.core.solver.Fixtures.CASING;
import static dev.mfp.core.solver.Fixtures.ORE;
import static dev.mfp.core.solver.Fixtures.PLATE;
import static dev.mfp.core.solver.Fixtures.STONE_DUST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The simplex engine, checked against numbers worked out by hand.
 *
 * <p>Two kinds of test live here and both matter. The first kind asserts that on a plan the matrix
 * engine can already solve, this engine gives <b>the same numbers</b> — an engine that answers the
 * new questions but quietly changes the answers to the old ones would not be an improvement. The
 * second kind covers what only this engine can say: at most, at least, and not quite enough.
 */
class SimplexSolverTest {

    private static final double TOLERANCE = 1e-6;

    private final SimplexSolver solver = new SimplexSolver();

    // ------------------------------------------- agreement with the other engines

    /**
     * The five-step chain, whose every number was hand-computed for M3 and pinned since.
     *
     * <p>An acyclic plan has one answer, so all three engines must agree on it. This is the test
     * that would catch the objective quietly preferring to import an intermediate rather than make
     * it: the cheapest factory by the tiers in {@link SimplexSolver} has to be the same factory the
     * other two arrive at by having no choice.
     */
    @Test
    void anAcyclicChainGivesTheSameAnswerAsBothOtherEngines() {
        SolveResult simplex = solver.solve(fiveStepChain());
        SolveResult matrix = new MatrixSolver().solve(fiveStepChain());
        SolveResult sequential = new SequentialSolver().solve(fiveStepChain());

        assertEquals(10, simplex.totalMachines());
        assertEquals(matrix.totalMachines(), simplex.totalMachines());
        assertEquals(sequential.totalMachines(), simplex.totalMachines());
        assertEquals(1.0, simplex.rawInputs().get(ORE), TOLERANCE);
        assertEquals(1440.0, simplex.euDrawPerSecond(), TOLERANCE);
        assertNull(simplex.rawInputs().get(MfpKey.EU), "energy is drawn, not imported");
        assertTrue(simplex.isComplete());
        assertEquals(SolverMode.SIMPLEX, simplex.engine());

        // ingot: 40 ticks is 0.5 crafts/s per machine, and 2 crafts/s are needed -> 4 machines.
        assertEquals(2.0, rateOf(simplex, "mfp:ingot"), TOLERANCE);
        assertEquals(4.0, simplex.lines().get(2).machineCount(), TOLERANCE);
    }

    /**
     * The acid loop closes here too, and closing it is not a special case — it is one more row.
     *
     * <p>{@code x = 1 + 0.5x}, so leaching runs at 2 crafts/s and the ore bill is 2/s rather than the
     * 1/s a single pass arrives at. Acid nets to exactly zero, which is what "the loop closed" looks
     * like, and it has to net to zero <em>by choice</em> here rather than by constraint: surplus acid
     * is allowed and simply costs more than not making it.
     */
    @Test
    void aFeedbackLoopBalancesJustAsTheMatrixEngineDoes() {
        SolveResult result = solver.solve(acidLoopPlan());

        assertEquals(1.0, result.products().get(Loop.METAL), TOLERANCE);
        assertEquals(2.0, result.rawInputs().get(ORE), TOLERANCE);
        assertNull(result.rawInputs().get(Loop.ACID), "the acid is recycled, not bought");
        assertNull(result.byproducts().get(Loop.ACID), "and none of it is left over either");

        assertEquals(1.0, rateOf(result, "mfp:smelt"), TOLERANCE);
        assertEquals(2.0, rateOf(result, "mfp:leach"), TOLERANCE);
        assertEquals(1.0, rateOf(result, "mfp:regen"), TOLERANCE);
        assertTrue(result.isComplete());
    }

    /** A byproduct two lines both want, balanced across the whole plan rather than handed downward. */
    @Test
    void aSharedByproductFeedsTheLineThatWantsIt() {
        Plan plan = new Plan("shared byproduct").target(Fixtures.GRAVEL, 1.0);
        plan.add(new Line(Fixtures.gravelFromStoneDust()));
        plan.add(new Line(Fixtures.crushedWithByproduct()));

        SolveResult result = solver.solve(plan);

        assertEquals(1.0, result.products().get(Fixtures.GRAVEL), TOLERANCE);
        assertNull(result.rawInputs().get(STONE_DUST), "the stone dust is produced right here");
        assertEquals(1.0, result.rawInputs().get(ORE), TOLERANCE);
        assertEquals(2.0, result.byproducts().get(Fixtures.CRUSHED), TOLERANCE);
    }

    /** And the same plan's crushing line says which half of its production was the point. */
    @Test
    void aLineSeparatesWhatWasWantedFromWhatWasLeftOver() {
        Plan plan = new Plan("shared byproduct").target(Fixtures.GRAVEL, 1.0);
        plan.add(new Line(Fixtures.gravelFromStoneDust()));
        plan.add(new Line(Fixtures.crushedWithByproduct()));

        LineResult crushing = solver.solve(plan).lines().get(1);

        assertEquals(1.0, crushing.outputs().get(STONE_DUST), TOLERANCE);
        assertNull(crushing.outputs().get(Fixtures.CRUSHED), "nothing in the plan wants crushed ore");
        assertEquals(2.0, crushing.byproducts().get(Fixtures.CRUSHED), TOLERANCE);
    }

    /** Catalysts are netted out before the programme is built, so they never appear as flows. */
    @Test
    void aBorrowedCatalystNeverEntersTheProgramme() {
        Plan plan = new Plan("catalyst").target(PLATE, 1.0);
        plan.add(new Line(Fixtures.lubricatedPress()));

        SolveResult result = solver.solve(plan);

        assertNull(result.rawInputs().get(Fixtures.LUBRICANT));
        assertEquals(1.0, result.rawInputs().get(Fixtures.INGOT), TOLERANCE);
    }

    // ------------------------------------------------------- what only this can say

    /**
     * <b>At most.</b> A machine limit is honoured, and the demand it costs is stated.
     *
     * <p>The casing recipe runs one craft per second per machine, so half a machine is half a casing
     * per second — half of what the plan asked for. Everything upstream is then sized for the half
     * that does get made: 1 plate/s, 1 ingot/s, 1 dust/s, 0.5 ore/s. The matrix engine could only
     * ignore the limit and report a factory the user has said they will not build.
     */
    @Test
    void aMachineLimitIsHonouredAndTheShortfallIsStated() {
        Plan plan = fiveStepChain();
        plan.allLines().get(0).machine(MachineConfig.UNSET.withLimit(0.5, false));

        SolveResult result = solver.solve(plan);

        assertEquals(0.5, rateOf(result, "mfp:casing"), TOLERANCE);
        assertEquals(0.5, result.lines().get(0).machineCount(), TOLERANCE);
        assertEquals(0.5, result.products().get(CASING), TOLERANCE);
        assertEquals(0.5, result.unsatisfied().get(CASING), TOLERANCE);
        assertFalse(result.isComplete());
        assertEquals(0.5, result.rawInputs().get(ORE), TOLERANCE);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("machine limit")),
                "the limit must be named as the cause: " + result.warnings());
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("ignored")),
                "and it must not be reported as ignored: " + result.warnings());
    }

    /**
     * A forced machine count runs even where demand would not fill it, and the surplus is reported.
     *
     * <p>Two casing machines against a demand of one casing per second make two, so one per second is
     * left over. That is the difference between {@code forceLimit} and a cap, and it is the case a
     * system of equations cannot state at all.
     */
    @Test
    void aForcedMachineCountRunsAndTheSurplusIsReported() {
        Plan plan = fiveStepChain();
        plan.allLines().get(0).machine(MachineConfig.UNSET.withLimit(2.0, true));

        SolveResult result = solver.solve(plan);

        assertEquals(2.0, rateOf(result, "mfp:casing"), TOLERANCE);
        assertEquals(2.0, result.rawInputs().get(ORE), TOLERANCE);
        assertEquals(1.0, result.byproducts().get(CASING), TOLERANCE,
                "one casing per second more than the plan asked for");
    }

    /**
     * <b>A share of the work.</b> A line at fifty per cent covers half its product's demand.
     *
     * <p>One casing per second needs two plates per second; at fifty per cent the press makes one and
     * the other is bought. The remaining plate is an <em>import of an item the plan also makes</em>,
     * which is normally the symptom of a broken plan — so it is named in a warning rather than
     * slipped into the shopping list, and the ingot line below is sized for one plate, not two.
     */
    @Test
    void aLinePercentageMakesThatShareAndBuysTheRest() {
        Plan plan = fiveStepChain();
        plan.allLines().get(1).percentage(50);

        SolveResult result = solver.solve(plan);

        assertEquals(1.0, result.products().get(CASING), TOLERANCE);
        assertEquals(1.0, rateOf(result, "mfp:plate"), TOLERANCE);
        assertEquals(1.0, result.rawInputs().get(PLATE), TOLERANCE);
        assertEquals(1.0, rateOf(result, "mfp:ingot"), TOLERANCE);
        assertEquals(0.5, result.rawInputs().get(ORE), TOLERANCE);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("plate")),
                "importing something the plan makes must be said out loud: " + result.warnings());
    }

    /**
     * <b>At least.</b> A leaky loop over-produces or imports rather than being declared unsolvable.
     *
     * <p>Leaching takes 2 acid and refining returns only 1.5, so "acid nets to zero" and "one
     * concentrate per metal" cannot both hold. The matrix engine has to notice the contradiction,
     * then try freeing one item at a time until the plan solves. Here nothing has to be tried: phase
     * one minimises exactly the imports the plan should not be making, so the item it cannot drive to
     * zero <em>is</em> the one to free, and the amount it stops at is what freeing it costs.
     *
     * <p>It lands on concentrate — 0.25/s where freeing acid would leak 0.5/s — which is the same
     * item and the same numbers {@link MatrixSolver} reaches by its own route. That agreement is
     * worth more than either answer alone: two engines with nothing in common but the columns they
     * are built from arrive at the same least intervention.
     */
    @Test
    void aLeakyLoopBuysWhatTheLoopLosesInsteadOfFailing() {
        SolveResult result = solver.solve(leakyLoopPlan());
        SolveResult matrix = new MatrixSolver().solve(leakyLoopPlan());

        assertEquals(1.0, result.products().get(Loop.METAL), TOLERANCE);
        assertEquals(0.25, result.rawInputs().get(Loop.CONCENTRATE), TOLERANCE);
        assertEquals(0.75, result.rawInputs().get(ORE), TOLERANCE);
        assertEquals(matrix.rawInputs().get(ORE), result.rawInputs().get(ORE), TOLERANCE);
        assertEquals(matrix.rawInputs().get(Loop.CONCENTRATE),
                result.rawInputs().get(Loop.CONCENTRATE), TOLERANCE);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("concentrate")),
                "the item that stopped balancing must be named: " + result.warnings());
        assertTrue(result.isComplete());
    }

    /**
     * A ratio nobody chose is absorbed as surplus rather than sizing the plan by its rarest output.
     *
     * <p>This is the shape behind the thirty-thousand-centrifuge plan of STATUS §6d.22: one recipe
     * makes two things in a fixed proportion and the plan wants them in another. Demanding four of
     * the scarce output and one of the plentiful one means running four times, and the three spare
     * plentiful ones are simply spare — a heap of essence in a barrel, which is a thing a real
     * factory has.
     */
    @Test
    void afixedOutputRatioLeavesASurplusRatherThanExplodingTheMachineCount() {
        MfpKey common = MfpKey.item("mfp", "common_essence");
        MfpKey rare = MfpKey.item("mfp", "rare_essence");
        MfpRecipe centrifuge = MfpRecipe.builder("mfp:centrifuge", "mfp:centrifuge", "test")
                .input(MfpIngredient.of(ORE, 1))
                .output(MfpOutput.of(common, 4))
                .output(MfpOutput.of(rare, 1))
                .duration(20)
                .euIn(8)
                .build();

        Plan plan = new Plan("fixed ratio").target(rare, 4.0).target(common, 4.0);
        plan.add(new Line(centrifuge));

        SolveResult result = solver.solve(plan);

        assertEquals(4.0, rateOf(result, "mfp:centrifuge"), TOLERANCE);
        assertEquals(4.0, result.products().get(rare), TOLERANCE);
        assertEquals(4.0, result.products().get(common), TOLERANCE);
        assertEquals(12.0, result.byproducts().get(common), TOLERANCE, "16 made, 4 wanted");
        assertTrue(result.isComplete());
    }

    /** A target nothing makes is unsatisfied, not quietly listed as something to buy. */
    @Test
    void anUnproducedTargetIsReportedAsUnsatisfied() {
        Plan plan = new Plan("nothing makes this").target(CASING, 1.0);
        plan.add(new Line(Fixtures.plate()));   // makes plates, not casings

        SolveResult result = solver.solve(plan);

        assertEquals(1.0, result.unsatisfied().get(CASING), TOLERANCE);
        assertFalse(result.isComplete());
        assertNull(result.rawInputs().get(CASING), "importing the target is not a plan");
    }

    /** A disabled line is absent from the programme entirely, not solved at zero and then charged. */
    @Test
    void aDisabledLineContributesNothing() {
        Plan plan = new Plan("disabled").target(CASING, 1.0);
        plan.add(new Line(Fixtures.casing()));
        plan.add(new Line(Fixtures.plate()).active(false));

        SolveResult result = solver.solve(plan);

        assertTrue(result.lines().get(1).isIdle());
        assertEquals(0.0, result.lines().get(1).euInPerSecond(), TOLERANCE);
        assertEquals(2.0, result.rawInputs().get(PLATE), TOLERANCE);
    }

    // ---------------------------------------------------------------- ambiguity

    /**
     * Two ways to make the same thing, with nothing to choose between them, has no single answer —
     * and this engine now says so rather than picking one.
     *
     * <p>The gate on making simplex the default engine (PLAN §13a, M12 item 1). Every split between
     * the two recipes balances equally well and costs exactly the same, so the answer the simplex
     * hands back is an accident of its pivot order; presenting it would be inventing a decision the
     * user never made (plan P6). This is the one thing the matrix engine did better, and the whole
     * reason it stayed the default (STATUS §10.9).
     *
     * <p>The same line is named as {@code MatrixSolverTest} names — the one left without a pivot,
     * which is one fewer than the set of interchangeable recipes, because the first of them is the
     * one the answer is written in terms of.
     */
    @Test
    void interchangeableRecipesAreReportedRatherThanPicked() {
        SimplexSolver.AmbiguousPlanException failure = assertThrows(
                SimplexSolver.AmbiguousPlanException.class, () -> solver.solve(twoWaysToMakeDust()));

        assertEquals(List.of("mfp:dust_b"), failure.undecidedLines());
        assertTrue(failure.getMessage().contains("drop one or pin its rate"),
                "and must say what to do about it, got: " + failure.getMessage());
    }

    /** The matrix engine's verdict on the same plan, so the two are known to agree. */
    @Test
    void bothWholePlanEnginesNameTheSameUndecidedLine() {
        MatrixSolver.UnsolvableSystemException matrix = assertThrows(
                MatrixSolver.UnsolvableSystemException.class,
                () -> new MatrixSolver().solve(twoWaysToMakeDust()));
        SimplexSolver.AmbiguousPlanException simplex = assertThrows(
                SimplexSolver.AmbiguousPlanException.class,
                () -> solver.solve(twoWaysToMakeDust()));

        assertTrue(matrix.getMessage().contains("mfp:dust_b"), matrix.getMessage());
        assertTrue(simplex.getMessage().contains("mfp:dust_b"), simplex.getMessage());
    }

    /**
     * A plan that <em>does</em> have one answer is not reported as ambiguous, and that is the half of
     * this check that can quietly ruin every plan MFP solves.
     *
     * <p>A false report costs the user their answer, so the cases that look tied and are not get
     * asserted explicitly: an acyclic chain where every rate is forced, and a loop, whose spin rate
     * is decided by nothing but the cost of running and would be a free column without it.
     */
    @Test
    void aPlanWithOneAnswerIsNotCalledAmbiguous() {
        assertTrue(solver.solve(fiveStepChain()).isComplete());
        assertEquals(SolverMode.SIMPLEX, solver.solve(acidLoopPlan()).engine());
    }

    /** An ambiguous plan falls back to the sequential pass carrying the diagnosis, either way in. */
    @Test
    void solversFallsBackWhenSimplexFindsThePlanAmbiguous() {
        SolveResult result = Solvers.solve(twoWaysToMakeDust().solverMode(SolverMode.SIMPLEX));

        assertEquals(SolverMode.SEQUENTIAL, result.engine(),
                "an ambiguous plan must fall back rather than show a guess");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("more than one answer")),
                "and must say why: " + result.warnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("mfp:dust_b")),
                "naming the line to do something about: " + result.warnings());
    }

    // ---------------------------------------------------------------- dispatch

    /**
     * A plan carrying an inequality goes to the engine that can hold one.
     *
     * <p>The matrix engine would answer this plan, and would answer it having discarded the one
     * setting the user went out of their way to set. Routing on the presence of a limit rather than
     * on a failure is what makes that impossible.
     */
    @Test
    void solversSendsAPlanWithALimitToTheSimplexEngine() {
        Plan limited = fiveStepChain().solverMode(SolverMode.MATRIX);
        limited.allLines().get(0).machine(MachineConfig.UNSET.withLimit(0.5, false));

        SolveResult result = Solvers.solve(limited);

        assertEquals(SolverMode.SIMPLEX, result.engine());
        assertEquals(0.5, result.unsatisfied().get(CASING), TOLERANCE);
    }

    /** Asking for it by name works too, and an acyclic plan still goes to the sequential pass. */
    @Test
    void solversRoutesByModeAsBefore() {
        assertEquals(SolverMode.SEQUENTIAL, Solvers.solve(fiveStepChain()).engine());
        assertEquals(SolverMode.SIMPLEX,
                Solvers.solve(fiveStepChain().solverMode(SolverMode.SIMPLEX)).engine());
        assertEquals(SolverMode.MATRIX,
                Solvers.solve(acidLoopPlan().solverMode(SolverMode.MATRIX)).engine());
    }

    // ----------------------------------------------------------------- fixtures

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

    /** Two recipes with identical inputs, outputs and durations: the ambiguous plan. */
    private static Plan twoWaysToMakeDust() {
        Plan plan = new Plan("ambiguous").target(Fixtures.DUST, 1.0);
        plan.add(new Line(Fixtures.simple("mfp:dust_a", Fixtures.CRUSHED, 1, Fixtures.DUST, 1, 20, 8)));
        plan.add(new Line(Fixtures.simple("mfp:dust_b", Fixtures.CRUSHED, 1, Fixtures.DUST, 1, 20, 8)));
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

    private static double rateOf(SolveResult result, String recipeId) {
        return result.lines().stream()
                .filter(line -> line.line().recipe().id().equals(recipeId))
                .mapToDouble(LineResult::craftsPerSecond)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no line for " + recipeId));
    }
}
