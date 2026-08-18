package dev.mfp.core.solver;

import java.util.Arrays;

/**
 * A two-phase tableau simplex for {@code minimise c·x subject to Ax = b, x >= 0}.
 *
 * <p>Deliberately free of any notion of recipes, for the same reason {@link GaussJordan} is: the
 * construction of a system and the act of solving it are different jobs, and keeping them apart is
 * what lets this be tested on hand-written matrices where every answer can be worked out on paper.
 * {@link SimplexSolver} is the half that knows what an item is.
 *
 * <h2>Why a tableau rather than the revised method</h2>
 * Factory Planner's engine is a revised simplex with Forrest-Tomlin basis updates, which exists to
 * keep a very large basis inverse cheap to maintain. MFP's systems are one row per item and one
 * column per line — a large pack plan is tens of rows, not thousands — and at that size the dense
 * tableau is both faster and enormously easier to be confident in. The pricing and ratio rules below
 * are the parts that decide whether the answer is right; the data structure is not.
 *
 * <h2>Termination</h2>
 * Dantzig pricing (most negative reduced cost) converges quickly but can cycle on a degenerate
 * problem, and production plans are degenerate constantly — every line that solves to exactly zero
 * is a degenerate vertex. So pricing switches to <b>Bland's rule</b> after {@link #BLAND_AFTER}
 * iterations, which is slower but provably terminates. Ratio-test ties always break on the lowest
 * basic variable index, which is Bland's rule for leaving and also what makes the answer the same
 * every run — a plan that solved differently on reload would be worse than one that solved slowly.
 */
final class Simplex {

    /** Below this a coefficient is treated as zero. Rows arrive scaled, so it is a relative one. */
    static final double TOLERANCE = 1e-9;

    /** Iterations of Dantzig pricing before switching to the slower rule that cannot cycle. */
    private static final int BLAND_AFTER = 200;

    /** A backstop, so a pathological problem fails rather than hanging the client thread. */
    private static final int MAX_ITERATIONS = 20_000;

    /** Raised when the problem is unbounded or the iteration cap is hit, so the caller can fall back. */
    static final class NoAnswerException extends RuntimeException {
        NoAnswerException(String message) {
            super(message);
        }
    }

    private final int rows;
    private final int columns;
    /** {@code rows} by {@code columns + 1}; the last entry of each row is its right-hand side. */
    private final double[][] tableau;
    private final int[] basis;
    private final boolean[] blocked;
    private int iterations;

    /**
     * @param a          {@code rows} by {@code columns}
     * @param b          right-hand sides, every one non-negative
     * @param initial    one column index per row, together forming an identity in the tableau
     * @param columnCount stated rather than read off {@code a}, so a programme with no rows at all
     *                   still agrees with its own cost vectors
     */
    Simplex(double[][] a, double[] b, int[] initial, int columnCount) {
        this.rows = a.length;
        this.columns = columnCount;
        this.tableau = new double[rows][columns + 1];
        this.basis = initial.clone();
        this.blocked = new boolean[columns];
        for (int row = 0; row < rows; row++) {
            System.arraycopy(a[row], 0, tableau[row], 0, columns);
            if (b[row] < 0) {
                throw new IllegalArgumentException("right-hand side must be non-negative: " + b[row]);
            }
            tableau[row][columns] = b[row];
        }
    }

    /** Stop a column from entering the basis, used to retire phase one's artificials. */
    void block(int column) {
        blocked[column] = true;
    }

    /**
     * Pivot until no column improves the objective.
     *
     * @return the objective value reached
     */
    double minimise(double[] cost) {
        while (true) {
            double[] reduced = reducedCosts(cost);
            int entering = choose(reduced);
            if (entering < 0) {
                return objective(cost);
            }
            int leaving = ratioTest(entering);
            if (leaving < 0) {
                throw new NoAnswerException("the problem is unbounded in column " + entering);
            }
            pivot(leaving, entering);
            if (++iterations > MAX_ITERATIONS) {
                throw new NoAnswerException("no optimum after " + MAX_ITERATIONS + " pivots");
            }
        }
    }

    /**
     * Replace phase one's artificial basics with real columns, so phase two cannot revive them.
     *
     * <p>An artificial sitting in the basis at zero looks harmless and is not: a later pivot in
     * another row can raise it off zero, which would quietly re-import the very thing phase one just
     * proved unnecessary. Blocking the column stops it <em>entering</em>; only pivoting it out stops
     * it growing while basic. A row with nothing left to pivot on is a redundant equation — two
     * recipes saying the same thing — and is simply left alone, since a row that is all zeros outside
     * its artificial can never be touched again.
     */
    void retire(boolean[] artificial) {
        for (int row = 0; row < rows; row++) {
            if (!artificial[basis[row]]) {
                continue;
            }
            for (int column = 0; column < columns; column++) {
                if (!artificial[column] && !blocked[column]
                        && Math.abs(tableau[row][column]) > TOLERANCE) {
                    pivot(row, column);
                    break;
                }
            }
        }
        for (int column = 0; column < columns; column++) {
            if (artificial[column]) {
                blocked[column] = true;
            }
        }
    }

    /**
     * A copy at the same basis, so an alternative optimum can be probed without losing this one.
     *
     * <p>The tableau is tens of rows by tens of columns, so copying it is cheaper than the pivot it
     * exists to undo, and it means the probe cannot leave the real answer half-pivoted.
     */
    private Simplex(Simplex source) {
        this.rows = source.rows;
        this.columns = source.columns;
        this.tableau = new double[rows][];
        for (int row = 0; row < rows; row++) {
            this.tableau[row] = source.tableau[row].clone();
        }
        this.basis = source.basis.clone();
        this.blocked = source.blocked.clone();
        this.iterations = source.iterations;
    }

    /**
     * Non-basic columns that could enter the basis without making the objective any worse.
     *
     * <p>A zero reduced cost at the optimum is the textbook signature of an <b>alternative
     * optimum</b>: this answer is one of several the programme rates equally, and which one came
     * back is an accident of the pivot order. That is what {@link SimplexSolver} needs to tell an
     * answer from a guess (plan P6) — the simplex will always hand back <em>an</em> answer, and the
     * question is whether the programme actually decided it.
     *
     * <p>Zero cost is necessary and not sufficient: the column may only be able to enter at a step
     * of zero, in which case the answer does not move at all. Deciding that is
     * {@link #answerWith(int)}'s job, because it is the answer that settles it rather than the
     * arithmetic.
     */
    int[] tiedColumns(double[] cost) {
        double[] reduced = reducedCosts(cost);
        boolean[] basic = new boolean[columns];
        for (int row = 0; row < rows; row++) {
            basic[basis[row]] = true;
        }
        int[] tied = new int[columns];
        int found = 0;
        for (int column = 0; column < columns; column++) {
            if (basic[column] || blocked[column]) {
                continue;
            }
            if (Math.abs(reduced[column]) <= TOLERANCE) {
                tied[found++] = column;
            }
        }
        return Arrays.copyOf(tied, found);
    }

    /**
     * The answer reached by forcing {@code column} into the basis, or null if nothing can leave.
     *
     * <p>Only ever called on a column {@link #tiedColumns} named, so the result is another optimum
     * rather than a worse plan. It may also be the <em>same</em> optimum described by a different
     * basis — a degenerate pivot moves the basis and no number in it — which is exactly why the
     * caller compares the two answers rather than trusting the tie.
     */
    double[] answerWith(int column) {
        Simplex probe = new Simplex(this);
        int leaving = probe.ratioTest(column);
        if (leaving < 0) {
            return null;
        }
        probe.pivot(leaving, column);
        return probe.solution();
    }

    /** The value of every variable, non-basic ones being zero. */
    double[] solution() {
        double[] values = new double[columns];
        for (int row = 0; row < rows; row++) {
            values[basis[row]] = tableau[row][columns];
        }
        for (int column = 0; column < columns; column++) {
            if (values[column] < 0 && values[column] > -TOLERANCE) {
                values[column] = 0.0;   // -0.0 and rounding dust, never a real negative
            }
        }
        return values;
    }

    private double objective(double[] cost) {
        double total = 0;
        for (int row = 0; row < rows; row++) {
            total += cost[basis[row]] * tableau[row][columns];
        }
        return total;
    }

    private double[] reducedCosts(double[] cost) {
        double[] reduced = cost.clone();
        for (int row = 0; row < rows; row++) {
            double basic = cost[basis[row]];
            if (basic == 0) {
                continue;
            }
            for (int column = 0; column < columns; column++) {
                reduced[column] -= basic * tableau[row][column];
            }
        }
        return reduced;
    }

    private int choose(double[] reduced) {
        boolean bland = iterations >= BLAND_AFTER;
        int best = -1;
        double bestValue = -TOLERANCE;
        for (int column = 0; column < columns; column++) {
            if (blocked[column] || reduced[column] >= -TOLERANCE) {
                continue;
            }
            if (bland) {
                return column;
            }
            if (reduced[column] < bestValue) {
                best = column;
                bestValue = reduced[column];
            }
        }
        return best;
    }

    /**
     * How far the entering column can rise before a basic variable would go negative.
     *
     * <p>Ties go to the smallest basic index — Bland's leaving rule. Without a fixed tie-break a
     * degenerate plan can cycle forever between two equally valid bases, and, just as importantly,
     * two runs over the same plan could return different answers.
     */
    private int ratioTest(int entering) {
        int best = -1;
        double bestRatio = Double.MAX_VALUE;
        for (int row = 0; row < rows; row++) {
            double coefficient = tableau[row][entering];
            if (coefficient <= TOLERANCE) {
                continue;
            }
            double ratio = tableau[row][columns] / coefficient;
            if (ratio < bestRatio - TOLERANCE
                    || (ratio < bestRatio + TOLERANCE && best >= 0 && basis[row] < basis[best])) {
                best = row;
                bestRatio = ratio;
            }
        }
        return best;
    }

    private void pivot(int pivotRow, int pivotColumn) {
        double pivot = tableau[pivotRow][pivotColumn];
        for (int column = 0; column <= columns; column++) {
            tableau[pivotRow][column] /= pivot;
        }
        tableau[pivotRow][pivotColumn] = 1.0;   // exact, rather than 1 +/- an ulp

        for (int row = 0; row < rows; row++) {
            if (row == pivotRow) {
                continue;
            }
            double factor = tableau[row][pivotColumn];
            if (Math.abs(factor) <= TOLERANCE) {
                continue;
            }
            for (int column = 0; column <= columns; column++) {
                tableau[row][column] -= factor * tableau[pivotRow][column];
            }
            tableau[row][pivotColumn] = 0.0;
        }
        basis[pivotRow] = pivotColumn;
    }

    @Override
    public String toString() {
        return "Simplex[" + rows + "x" + columns + ", basis=" + Arrays.toString(basis) + "]";
    }
}
