package dev.mfp.core.plan;

/** Which engine solves a plan. */
public enum SolverMode {
    /** Single top-down pass. Fast and readable, but cannot resolve loops or shared byproducts. */
    SEQUENTIAL,
    /** Linear system over the whole plan. Handles loops; needs a square, independent system. */
    MATRIX,
    /**
     * Linear programme over the whole plan. Handles everything the matrix engine does, plus the
     * things that are inequalities rather than equations: machine limits, line percentages, and
     * producing more than was asked for rather than exactly as much.
     */
    SIMPLEX,
    /** Sequential unless the plan contains a cycle or a shared byproduct, then matrix. */
    AUTO;

    /**
     * Whether an engine balances the plan as a whole, and so can close a loop.
     *
     * <p>Asked of the engine that actually produced the numbers, never of the mode the plan asked
     * for: after a fallback those are not the same, and a warning telling the user to switch to an
     * engine the status bar says they are already using makes every other warning less believable.
     */
    public boolean closesLoops() {
        return this == MATRIX || this == SIMPLEX;
    }
}
