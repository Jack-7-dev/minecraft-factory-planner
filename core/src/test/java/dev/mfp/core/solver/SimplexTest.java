package dev.mfp.core.solver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The linear programme solver on hand-written matrices, where every answer is arithmetic.
 *
 * <p>These are deliberately not production plans. {@link Simplex} knows nothing about recipes and
 * testing it that way is what keeps the two halves honest: if a plan comes out wrong, this file says
 * whether the fault is in the maths or in the way {@link SimplexSolver} stated the question.
 */
class SimplexTest {

    private static final double TOLERANCE = 1e-9;

    /**
     * The textbook maximisation, stated as a minimisation because that is what the class takes.
     *
     * <pre>
     *   maximise 3a + 5b   subject to   a &lt;= 4,   2b &lt;= 12,   3a + 2b &lt;= 18
     * </pre>
     *
     * <p>The optimum is the corner {@code a = 2, b = 6}, worth 36. Every inequality carries its own
     * slack, so the slacks are a ready-made starting basis and no artificial is needed — which is
     * exactly the situation a production plan is in, since every item can be imported.
     */
    @Test
    void findsTheOptimalCornerOfABoundedProblem() {
        double[][] a = {
                {1, 0, 1, 0, 0},
                {0, 2, 0, 1, 0},
                {3, 2, 0, 0, 1},
        };
        double[] b = {4, 12, 18};
        Simplex simplex = new Simplex(a, b, new int[]{2, 3, 4}, 5);

        double objective = simplex.minimise(new double[]{-3, -5, 0, 0, 0});
        double[] x = simplex.solution();

        assertEquals(-36.0, objective, TOLERANCE);
        assertEquals(2.0, x[0], TOLERANCE);
        assertEquals(6.0, x[1], TOLERANCE);
    }

    /**
     * Two phases: an equality has no slack, so it starts on an artificial that phase one must clear.
     *
     * <pre>
     *   minimise 2x + 3y   subject to   x + y = 10,   x &lt;= 4
     * </pre>
     *
     * <p>The cheapest feasible point uses as much of the cheap {@code x} as the cap allows, so
     * {@code x = 4, y = 6}, worth 26. The point of the test is the second half: after
     * {@link Simplex#retire}, the artificial must be gone from the basis and unable to come back. If
     * it could return, the "equality" would quietly become "at most", and a plan's intermediates
     * would balance only approximately.
     */
    @Test
    void clearsAnArtificialAndDoesNotLetItComeBack() {
        // columns: x, y, capSlack, artificial
        double[][] a = {
                {1, 1, 0, 1},
                {1, 0, 1, 0},
        };
        double[] b = {10, 4};
        Simplex simplex = new Simplex(a, b, new int[]{3, 2}, 4);

        double unmet = simplex.minimise(new double[]{0, 0, 0, 1});
        assertEquals(0.0, unmet, TOLERANCE, "the equality is satisfiable, so the artificial clears");

        simplex.retire(new boolean[]{false, false, false, true});
        double objective = simplex.minimise(new double[]{2, 3, 0, 0});
        double[] x = simplex.solution();

        assertEquals(26.0, objective, TOLERANCE);
        assertEquals(4.0, x[0], TOLERANCE);
        assertEquals(6.0, x[1], TOLERANCE);
        assertEquals(0.0, x[3], TOLERANCE, "the artificial must stay at zero through phase two");
    }

    /**
     * An artificial that cannot reach zero is the honest report of an impossible constraint.
     *
     * <pre>
     *   x + a = 10,   x &lt;= 4
     * </pre>
     *
     * <p>{@code SimplexSolver} reads exactly this as "the plan cannot make ten of these", and the
     * leftover six is the shortfall it states.
     */
    @Test
    void reportsWhatItCannotSatisfyRatherThanFailing() {
        double[][] a = {
                {1, 1, 0},
                {1, 0, 1},
        };
        double[] b = {10, 4};
        Simplex simplex = new Simplex(a, b, new int[]{1, 2}, 3);

        double unmet = simplex.minimise(new double[]{0, 1, 0});

        assertEquals(6.0, unmet, TOLERANCE);
        assertEquals(6.0, simplex.solution()[1], TOLERANCE);
    }

    /** A direction the objective likes and nothing bounds is an error, not an enormous answer. */
    @Test
    void anUnboundedProblemIsReportedRatherThanReturned() {
        double[][] a = {{1, -1, 1}};
        double[] b = {1};
        Simplex simplex = new Simplex(a, b, new int[]{2}, 3);

        assertThrows(Simplex.NoAnswerException.class,
                () -> simplex.minimise(new double[]{0, -1, 0}));
    }

    /**
     * The same problem, solved twice, gives the same vertex.
     *
     * <p>Not a tautology: this problem has a whole edge of optimal points, since both variables cost
     * the same and either can fill the constraint. Which one the simplex returns is decided by the
     * pricing and ratio tie-breaks, and if those were left to floating-point luck a plan could solve
     * differently every time it was opened.
     */
    @Test
    void tiedOptimaAreBrokenTheSameWayEveryTime() {
        double[] first = solveTie();
        double[] second = solveTie();

        assertEquals(first[0], second[0], TOLERANCE);
        assertEquals(first[1], second[1], TOLERANCE);
        assertTrue(first[0] + first[1] >= 10 - TOLERANCE, "and it is still an answer");
    }

    private static double[] solveTie() {
        double[][] a = {{1, 1, 1}};
        double[] b = {10};
        Simplex simplex = new Simplex(a, b, new int[]{2}, 3);
        simplex.minimise(new double[]{0, 0, 1});
        simplex.retire(new boolean[]{false, false, true});
        simplex.minimise(new double[]{1, 1, 0});
        return simplex.solution();
    }
}
