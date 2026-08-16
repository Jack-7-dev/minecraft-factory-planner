package dev.mfp.core.solver;

import dev.mfp.core.model.Confidence;
import dev.mfp.core.plan.Line;
import dev.mfp.core.plan.MachineConfig;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.plan.SolverMode;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks the engine for a plan and, when the chosen one cannot answer, falls back rather than showing
 * nothing (plan §9.3).
 *
 * <p>Note what this class does <em>not</em> do: decide whether a plan contains a loop. That is
 * {@code RecipeChooser}'s call and it makes it from an observation, having tracked the recipe path
 * during expansion — a plan on {@link SolverMode#AUTO} arrives here already switched to
 * {@link SolverMode#MATRIX} if a cycle was seen. Re-deriving it here from the sequential engine's
 * warnings is exactly the confusion M3 hit and M4b fixed (STATUS 5.2): a mis-ordered plan and a
 * genuine loop produce the same warning, so a warning can never be the evidence.
 *
 * <h2>Where the simplex engine comes in</h2>
 * It is not a wholesale replacement for the matrix engine, and M10 deliberately did not make it one.
 * It is used in the two places the matrix engine is <em>known</em> to be the wrong tool:
 *
 * <ul>
 *   <li><b>The plan contains an inequality</b> — a machine limit or a line percentage. A system of
 *       equations cannot hold one, so the matrix engine reported them as ignored; the simplex engine
 *       honours them, and states the shortfall if honouring them costs the plan its target.</li>
 *   <li><b>The matrix engine gave up because the plan cannot balance exactly</b>. That is the
 *       over-constrained case, and the simplex engine answers it by construction, because
 *       over-producing is allowed rather than forbidden. It is what the mystical-agriculture chain
 *       that once fell back to 29,867 centrifuges needed (STATUS §6d.22).</li>
 * </ul>
 *
 * <p>The case that is <b>not</b> handed to it is ambiguity — two interchangeable recipes with
 * nothing to choose between them. The simplex engine would answer that happily, by picking one, and
 * picking one is inventing a decision the user has not made (plan P6). So an ambiguous plan still
 * falls back to the sequential pass carrying the matrix engine's diagnosis, which names the lines to
 * do something about.
 */
public final class Solvers {

    private Solvers() {}

    public static SolveResult solve(Plan plan) {
        return solve(plan, ThroughputResolver.BASE);
    }

    public static SolveResult solve(Plan plan, ThroughputResolver resolver) {
        if (plan.solverMode() == SolverMode.SIMPLEX) {
            return simplexOr(plan, resolver, null);
        }
        if (plan.solverMode() != SolverMode.MATRIX) {
            return new SequentialSolver(resolver).solve(plan);
        }
        if (hasInequality(plan)) {
            // The matrix engine would answer this, and would answer it while ignoring the very
            // setting the user went out of their way to set.
            return simplexOr(plan, resolver, null);
        }
        try {
            return new MatrixSolver(resolver).solve(plan);
        } catch (MatrixSolver.UnsolvableSystemException failure) {
            if (failure.isOverConstrained()) {
                return simplexOr(plan, resolver, failure);
            }
            return fallback(plan, resolver, failure.diagnostics(),
                    "the matrix engine could not solve this plan, so these numbers come from "
                            + "the sequential pass and cannot close any loop the plan contains");
        }
    }

    /** Whether the plan says something a system of equations cannot hold. */
    private static boolean hasInequality(Plan plan) {
        for (Line line : plan.allLines()) {
            if (!line.active()) {
                continue;
            }
            if (Math.abs(line.percentage() - 100.0) > 1e-9) {
                return true;
            }
            MachineConfig machine = line.machine();
            if (machine != null && machine.hasLimit()) {
                return true;
            }
        }
        return false;
    }

    private static SolveResult simplexOr(Plan plan, ThroughputResolver resolver,
                                         MatrixSolver.UnsolvableSystemException matrixFailure) {
        try {
            return new SimplexSolver(resolver).solve(plan);
        } catch (RuntimeException failure) {
            List<String> diagnostics = new ArrayList<>();
            if (matrixFailure != null) {
                diagnostics.addAll(matrixFailure.diagnostics());
            }
            diagnostics.add(String.valueOf(failure.getMessage()));
            return fallback(plan, resolver, diagnostics,
                    "the simplex engine could not solve this plan, so these numbers come from the "
                            + "sequential pass and cannot close any loop the plan contains");
        }
    }

    private static SolveResult fallback(Plan plan, ThroughputResolver resolver,
                                        List<String> diagnostics, String headline) {
        SolveResult sequential = new SequentialSolver(resolver).solve(plan);
        List<String> warnings = new ArrayList<>();
        warnings.add(headline);
        diagnostics.forEach(diagnostic -> warnings.add("  " + diagnostic));
        warnings.addAll(sequential.warnings());
        return new SolveResult(sequential.lines(), sequential.byLine(), sequential.products(),
                sequential.rawInputs(), sequential.byproducts(), sequential.unsatisfied(),
                sequential.euDrawPerSecond(), sequential.euGeneratedPerSecond(),
                sequential.steamDrawPerSecond(),
                sequential.confidence().and(Confidence.APPROXIMATE),
                warnings, SolverMode.SEQUENTIAL);
    }
}
