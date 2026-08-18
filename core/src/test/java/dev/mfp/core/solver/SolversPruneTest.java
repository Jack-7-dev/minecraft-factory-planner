package dev.mfp.core.solver;

import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.Line;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.plan.SolverMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.mfp.core.solver.Fixtures.CASING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A line the answer says is unnecessary is taken out of the plan — carefully.
 *
 * <p>A row sitting at zero used to stay on screen, greyed. That is two wrong answers at once: it
 * shows a machine the player must not build, and where the zero came from a <em>negative</em> rate
 * clamped by the matrix engine, every other number on the screen was computed beside a line that is
 * not really there.
 *
 * <p>The reason this is a whole test class rather than a two-line change is the guard. Removing a
 * dead line can take the anchor out of a loop and leave the engine answering an under-determined
 * system by importing something the plan was making — seen on the pack's polyvinyl butyral chain,
 * where chasing it to a fixpoint turned one dead line into nine and bought a fluid the plan had been
 * producing. So the pruned answer is kept only if it did not make the plan worse.
 */
class SolversPruneTest {

    private static Plan fiveStepPlan(SolverMode mode) {
        Plan plan = new Plan("five step").target(CASING, 1.0).solverMode(mode);
        plan.add(new Line(Fixtures.casing()));
        plan.add(new Line(Fixtures.plate()));
        plan.add(new Line(Fixtures.ingot()));
        plan.add(new Line(Fixtures.dust()));
        plan.add(new Line(Fixtures.crushed()));
        return plan;
    }

    @Test
    @DisplayName("a healthy plan loses nothing, which is the failure mode that would matter most")
    void aWorkingPlanIsLeftAlone() {
        Plan plan = fiveStepPlan(SolverMode.MATRIX);

        SolveResult result = Solvers.solve(plan);

        assertEquals(5, result.lines().size());
        assertEquals(5, plan.allLines().size(), "the plan itself must come back intact too");
        assertTrue(result.warnings().stream().noneMatch(warning -> warning.contains("removed")));
    }

    @Test
    @DisplayName("the sequential pass never drops a line, because there a zero can mean mis-ordered")
    void theSequentialPassIsExempt() {
        // That engine carries demand downward in one pass, so a line above the one that needs it
        // never sees any. Deleting it would delete the evidence of the fault rather than the fault.
        Plan plan = fiveStepPlan(SolverMode.SEQUENTIAL);
        plan.add(new Line(Fixtures.generator()));

        SolveResult result = Solvers.solve(plan);

        assertEquals(6, result.lines().size());
        assertTrue(result.lines().get(5).isIdle(), "and it is still visibly doing nothing");
    }

    @Test
    @DisplayName("a line is removed by identity, so a plan running one recipe twice keeps the other")
    void removalIsByIdentityNotByRecipe() {
        Plan plan = fiveStepPlan(SolverMode.MATRIX);
        Line first = new Line(Fixtures.generator());
        Line second = new Line(Fixtures.generator());
        plan.add(first);
        plan.add(second);

        assertEquals(1, plan.removeLines(List.of(first)));

        List<Line> left = plan.allLines();
        assertEquals(6, left.size());
        assertTrue(left.contains(second));
        assertFalse(left.contains(first));
    }

    @Test
    @DisplayName("removing lines leaves the pins alone, so the line returns when demand does")
    void theDecisionOutlivesTheLine() {
        Plan plan = fiveStepPlan(SolverMode.MATRIX);
        plan.chooseRecipe(CASING, "mfp:casing");
        Line casing = plan.allLines().get(0);

        plan.removeLines(List.of(casing));

        assertEquals("mfp:casing", plan.recipeChoice(CASING),
                "a line is a consequence of a decision, not the decision itself");
        assertEquals(4, plan.allLines().size());
    }

    /**
     * A line is not dead because the answer gave up on making what it makes.
     *
     * <p>Packing nine small into one big and unpacking it again is a cycle with no way in: each item
     * is produced and consumed inside the plan and nothing outside supplies either, so the engine
     * cannot balance them, relaxes one into an import, and both lines go to zero behind it. Deleting
     * them leaves a plan that buys the big item with nothing left to say the plan had a way to make
     * it — and, because the pruned plan re-solves from scratch and nothing in it produces that item
     * at all, without even the warning that explained why.
     *
     * <p>Straight from the pack: the polyvinyl butyral chain expands to 42 lines around two of these
     * (a 3x3 dust assemble and its disassemble), the engine relaxes butyraldehyde and vinyl acetate,
     * 39 lines go idle, and pruning them returned a three-line plan buying two intermediates it had a
     * whole chemistry tree for.
     */
    @Test
    @DisplayName("a line whose product the answer gave up making is kept, not pruned")
    void aLineWhoseProductIsBoughtIsNotDead() {
        Plan plan = solventLoopPlan();

        SolveResult result = Solvers.solve(plan);

        assertEquals(4, result.lines().size(), "the lines stay: " + result.warnings());
        assertEquals(4, plan.allLines().size());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("solvent_b")),
                "and the plan names what it would have to buy: " + result.warnings());
    }

    /**
     * The same guard must not fire on a raw material, or every plan keeps every idle line.
     *
     * <p>The user has said water comes from a hole in the ground, so a tower that drops it as a
     * byproduct is not the plan's way of obtaining it. The reported plan of STATUS §14f is exactly
     * that shape, and without this its row would sit at zero forever for a reason that has nothing to
     * do with it.
     */
    @Test
    @DisplayName("buying something the user declared raw is not a reason to keep a line")
    void aRawMaterialIsNotSomethingTheLineWasMaking() {
        Plan plan = solventLoopPlan();
        plan.rawMaterial(Fixtures.SOLVENT_B);

        SolveResult result = Solvers.solve(plan);

        assertTrue(result.lines().size() < 4, "the idle lines go: " + result.warnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("removed")),
                "and the plan says they went: " + result.warnings());
    }

    /**
     * A working chain that also wants a trickle of something off a loop with no way in.
     *
     * <p>Each solvent is made only from the other, and nothing outside supplies either, so the pair
     * can only run at zero. The casing needs 10 mB of one of them, which leaves the engine no
     * arithmetic but to buy it — and idles the two lines that were the plan's way of making it. The
     * plates and the ore keep the rest of the plan honest, so nothing here is a shortfall.
     */
    private static Plan solventLoopPlan() {
        MfpRecipe casing = MfpRecipe.builder("mfp:casing_with_solvent", "mfp:machine", "test")
                .input(MfpIngredient.of(Fixtures.PLATE, 2))
                .input(MfpIngredient.of(Fixtures.SOLVENT_B, 10))
                .output(MfpOutput.of(CASING, 1))
                .duration(20)
                .euIn(16)
                .build();

        Plan plan = new Plan("solvents").target(CASING, 1.0).solverMode(SolverMode.SIMPLEX);
        plan.rawMaterial(Fixtures.ORE);
        plan.add(new Line(casing));
        plan.add(new Line(Fixtures.simple("mfp:plate_from_ore", Fixtures.ORE, 1, Fixtures.PLATE, 1, 10, 8)));
        plan.add(new Line(Fixtures.simple("mfp:concentrate",
                Fixtures.SOLVENT_A, 9000, Fixtures.SOLVENT_B, 1000, 20, 8)));
        plan.add(new Line(Fixtures.simple("mfp:dilute",
                Fixtures.SOLVENT_B, 1000, Fixtures.SOLVENT_A, 9000, 20, 8)));
        return plan;
    }

    @Test
    @DisplayName("nothing is removed twice, so the reported count is a count of real removals")
    void removingWhatIsNotThereRemovesNothing() {
        Plan plan = fiveStepPlan(SolverMode.MATRIX);
        Line stranger = new Line(Fixtures.generator());

        assertEquals(0, plan.removeLines(List.of(stranger)));
        assertEquals(5, plan.allLines().size());
    }
}
