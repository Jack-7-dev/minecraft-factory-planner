package dev.mfp.core.solver;

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

    @Test
    @DisplayName("nothing is removed twice, so the reported count is a count of real removals")
    void removingWhatIsNotThereRemovesNothing() {
        Plan plan = fiveStepPlan(SolverMode.MATRIX);
        Line stranger = new Line(Fixtures.generator());

        assertEquals(0, plan.removeLines(List.of(stranger)));
        assertEquals(5, plan.allLines().size());
    }
}
