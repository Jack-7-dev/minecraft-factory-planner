package dev.mfp.core.solver;

import java.util.ArrayList;
import java.util.List;

/**
 * Gauss-Jordan elimination to reduced row echelon form, with partial pivoting.
 *
 * <p>Deliberately separate from {@link MatrixSolver}: building the system from a plan and reducing
 * it are two unrelated jobs, and keeping the reduction free of any notion of recipes is what lets it
 * be tested on hand-written matrices. Plan §9.2 also asks for this seam, so a simplex engine could
 * later reuse the same {@code A}/{@code b} construction without inheriting the square-system
 * assumption.
 *
 * <p>The matrix is <b>not</b> required to be square. A production plan routinely produces more
 * equations than unknowns — a perfectly recycled intermediate contributes a row that is a multiple
 * of another — and rejecting those would mean rejecting exactly the loops this engine exists for.
 * Redundant rows reduce to {@code 0 = 0} and are ignored; a row that reduces to {@code 0 = c} with
 * {@code c} non-zero is a genuine contradiction and is reported, as is any column left without a
 * pivot, which means the system does not pin that unknown down.
 */
final class GaussJordan {

    /**
     * Below this a coefficient is treated as zero when looking for a pivot.
     *
     * <p>Rows arrive pre-scaled to a maximum coefficient of one (see {@code MatrixSolver}), so this
     * is effectively a relative tolerance and the value from plan §9.2 applies as written.
     */
    static final double PIVOT_TOLERANCE = 1e-12;

    /**
     * How large a leftover right-hand side may be on an all-zero row before it counts as a
     * contradiction rather than as accumulated floating-point noise.
     *
     * <p>Much looser than {@link #PIVOT_TOLERANCE}, and it has to be: elimination over a chain of
     * dozens of recipes accumulates error, so a residue of 1e-12 per operation is not evidence of
     * anything. A genuinely inconsistent plan misses by a rate, not by a rounding error.
     */
    static final double RESIDUAL_TOLERANCE = 1e-7;

    private GaussJordan() {}

    /**
     * @param solution         one value per column; {@link Double#NaN} where the column had no pivot
     * @param unpinnedColumns  columns the system does not determine, in column order
     * @param inconsistentRows rows that reduced to {@code 0 = non-zero}, in row order
     */
    record Result(double[] solution, List<Integer> unpinnedColumns, List<Integer> inconsistentRows) {

        boolean isDetermined() {
            return unpinnedColumns.isEmpty() && inconsistentRows.isEmpty();
        }
    }

    /**
     * Reduce an augmented matrix in place.
     *
     * @param augmented {@code rows} arrays of {@code columns + 1} values; the last entry of each row
     *                  is that row's right-hand side
     * @param columns   how many unknowns the system has
     */
    static Result reduce(double[][] augmented, int columns) {
        int rows = augmented.length;
        int[] pivotRowOfColumn = new int[columns];
        java.util.Arrays.fill(pivotRowOfColumn, -1);

        int pivotRow = 0;
        for (int column = 0; column < columns && pivotRow < rows; column++) {
            // Partial pivoting: the largest remaining coefficient in this column makes the division
            // below as well-conditioned as it can be. Without it the first non-zero would do, and in
            // a system mixing energy with item counts that is routinely the worst possible choice.
            int best = -1;
            double bestMagnitude = PIVOT_TOLERANCE;
            for (int row = pivotRow; row < rows; row++) {
                double magnitude = Math.abs(augmented[row][column]);
                if (magnitude > bestMagnitude) {
                    best = row;
                    bestMagnitude = magnitude;
                }
            }
            if (best < 0) {
                continue;   // nothing pins this unknown down; recorded as unpinned below
            }

            double[] swap = augmented[pivotRow];
            augmented[pivotRow] = augmented[best];
            augmented[best] = swap;

            double pivot = augmented[pivotRow][column];
            for (int c = column; c <= columns; c++) {
                augmented[pivotRow][c] /= pivot;
            }
            augmented[pivotRow][column] = 1.0;   // exact, rather than 1 ± an ulp

            for (int row = 0; row < rows; row++) {
                if (row == pivotRow) {
                    continue;
                }
                double factor = augmented[row][column];
                if (Math.abs(factor) <= PIVOT_TOLERANCE) {
                    continue;
                }
                for (int c = column; c <= columns; c++) {
                    augmented[row][c] -= factor * augmented[pivotRow][c];
                }
                augmented[row][column] = 0.0;
            }

            pivotRowOfColumn[column] = pivotRow;
            pivotRow++;
        }

        double[] solution = new double[columns];
        List<Integer> unpinned = new ArrayList<>();
        for (int column = 0; column < columns; column++) {
            if (pivotRowOfColumn[column] < 0) {
                solution[column] = Double.NaN;
                unpinned.add(column);
            } else {
                solution[column] = augmented[pivotRowOfColumn[column]][columns];
            }
        }

        List<Integer> inconsistent = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            boolean allZero = true;
            for (int column = 0; column < columns; column++) {
                if (Math.abs(augmented[row][column]) > PIVOT_TOLERANCE) {
                    allZero = false;
                    break;
                }
            }
            if (allZero && Math.abs(augmented[row][columns]) > RESIDUAL_TOLERANCE) {
                inconsistent.add(row);
            }
        }

        return new Result(solution, List.copyOf(unpinned), List.copyOf(inconsistent));
    }
}
