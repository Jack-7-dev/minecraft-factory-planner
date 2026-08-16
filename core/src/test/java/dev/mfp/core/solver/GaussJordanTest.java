package dev.mfp.core.solver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reduction on its own, on matrices small enough to check by inspection.
 *
 * <p>Worth testing apart from {@link MatrixSolver}: when a plan comes out wrong, the first question
 * is whether the arithmetic or the model is at fault, and that is only a quick question to answer if
 * the arithmetic has its own tests.
 */
class GaussJordanTest {

    private static final double TOLERANCE = 1e-9;

    /** {@code 2x + y = 5, x - y = 1} has the solution {@code x = 2, y = 1}. */
    @Test
    void solvesASquareSystem() {
        GaussJordan.Result result = GaussJordan.reduce(new double[][] {
                {2, 1, 5},
                {1, -1, 1},
        }, 2);

        assertTrue(result.isDetermined());
        assertEquals(2.0, result.solution()[0], TOLERANCE);
        assertEquals(1.0, result.solution()[1], TOLERANCE);
    }

    /**
     * A row that says nothing new is not an error.
     *
     * <p>This is the shape a perfectly recycled intermediate produces, and Factory Planner's
     * square-system requirement would reject it. Rejecting it would mean rejecting the loops the
     * matrix engine exists for.
     */
    @Test
    void toleratesARedundantRow() {
        GaussJordan.Result result = GaussJordan.reduce(new double[][] {
                {2, 1, 5},
                {1, -1, 1},
                {4, 2, 10},     // twice the first row
        }, 2);

        assertTrue(result.isDetermined());
        assertEquals(2.0, result.solution()[0], TOLERANCE);
        assertEquals(1.0, result.solution()[1], TOLERANCE);
    }

    /** Two rows that contradict each other are reported, not averaged into a plausible answer. */
    @Test
    void reportsAContradiction() {
        // x = 2 and y = 1 pin both unknowns; the third row then demands x + y = 5, which they do
        // not satisfy. Over-constrained rather than ambiguous, which is the distinction the caller
        // needs: the fix is to relax a constraint, not to remove a recipe.
        GaussJordan.Result result = GaussJordan.reduce(new double[][] {
                {1, 0, 2},
                {0, 1, 1},
                {1, 1, 5},
        }, 2);

        assertEquals(List.of(2), result.inconsistentRows());
        assertTrue(result.unpinnedColumns().isEmpty());
    }

    /** An unknown nothing constrains is named rather than defaulted to zero. */
    @Test
    void reportsAnUnpinnedUnknown() {
        GaussJordan.Result result = GaussJordan.reduce(new double[][] {
                {1, 0, 0, 4},
                {0, 1, 0, 3},
        }, 3);

        assertEquals(List.of(2), result.unpinnedColumns());
        assertEquals(4.0, result.solution()[0], TOLERANCE);
        assertTrue(Double.isNaN(result.solution()[2]));
    }

    /**
     * Partial pivoting on a matrix that would otherwise divide by something near zero.
     *
     * <p>The first pivot candidate is 1e-14 while the row below has a 1. Taking the first non-zero
     * would amplify the error by fourteen orders of magnitude; picking the largest keeps it exact.
     */
    @Test
    void pivotsOnTheLargestCoefficient() {
        GaussJordan.Result result = GaussJordan.reduce(new double[][] {
                {1e-14, 1, 1},
                {1, 1, 2},
        }, 2);

        assertTrue(result.isDetermined());
        assertEquals(1.0, result.solution()[0], 1e-9);
        assertEquals(1.0, result.solution()[1], 1e-9);
    }
}
