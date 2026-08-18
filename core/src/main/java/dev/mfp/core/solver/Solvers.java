package dev.mfp.core.solver;

import dev.mfp.core.model.Confidence;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.plan.Line;
import dev.mfp.core.plan.LineNode;
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
 * <p><b>Ambiguity used to be the case that could not be handed to it</b>, because the simplex would
 * answer it happily by picking one of two interchangeable recipes, and picking one is inventing a
 * decision the user has not made (plan P6). It now notices instead — a tied column that changes the
 * machine counts is a second, equally good factory — and raises
 * {@link SimplexSolver.AmbiguousPlanException} naming the lines whose rate nothing decides. Either
 * engine reaching that conclusion falls back to the sequential pass carrying the same diagnosis.
 */
public final class Solvers {

    private Solvers() {}

    public static SolveResult solve(Plan plan) {
        return solve(plan, ThroughputResolver.BASE);
    }

    /**
     * Solve, and if the answer says a line is not needed, try the plan without it.
     *
     * <p>A whole-plan engine can decide a line should run at zero — or at a <em>negative</em> rate,
     * meaning the plan could only balance by running that machine backwards, at which point the
     * matrix engine clamps it to zero and admits in a warning that the remaining numbers no longer
     * balance. Both used to be left in the plan as a grey row at zero, which is two bad answers at
     * once: it shows a machine the player must not build, and every other number was computed
     * alongside a line that is not really there.
     *
     * <p>The case that found it: a plan making polyvinyl butyral pins the fermented-biomass
     * distillation tower to make fertiliser for a greenhouse, and that tower's ethanol byproduct
     * covers the plan's whole ethanol demand — so the biomass tower beside it has nothing to do,
     * and the system can only balance by unmaking ethanol.
     *
     * <p><b>The result is only kept if it did not make the plan worse</b>, and that guard is not
     * theoretical: on the very plan above, dropping the dead line leaves the biomass loop with
     * nothing anchoring its scale, and the engine answers that under-determined system by running
     * nine lines at zero and <em>importing</em> a fluid the plan was making. A pruned answer that
     * has to buy something the unpruned one made is not a tidier plan, it is a worse one, so it is
     * thrown away and the original stands — grey row, warning and all. The real fix for that plan
     * is for the chooser to notice the byproduct before it picks a second producer, which is v3
     * (PLAN §13a item 1).
     *
     * <p>One pass, not a loop. Each removal changes what the remaining lines have to do, and
     * chasing that to a fixpoint is how one dead line became nine.
     *
     * <p><b>The sequential pass is exempt.</b> There a line at zero can just as easily mean the plan
     * is in the wrong order — that engine carries demand downward in a single pass, so a line above
     * the one that needs it never sees any — and deleting a mis-ordered line rather than showing it
     * would destroy the evidence of the actual fault.
     *
     * <p>Removing a line does not remove the decision behind it: the user's pinned recipe stays, so
     * the line returns the moment anything demands that item again.
     */
    public static SolveResult solve(Plan plan, ThroughputResolver resolver) {
        SolveResult result = solveOnce(plan, resolver);
        List<Line> dead = deadLines(result);
        if (dead.isEmpty() || !isFlat(plan)) {
            return result;
        }

        // The floor as it stands, so a rejected attempt can be put back exactly. Only a flat floor
        // is attempted at all, which is what makes this restore complete rather than approximate.
        List<LineNode> before = plan.root().nodes();
        int dropped = plan.removeLines(dead);
        SolveResult pruned = solveOnce(plan, resolver);
        List<MfpKey> appeared = newImports(pruned, result);
        List<MfpKey> bought = alsoMadeByTheRemovedLines(dead, pruned, plan);
        if (dropped > 0 && appeared.isEmpty() && bought.isEmpty()) {
            return withNote(pruned, dropped);
        }
        plan.root().clear().addAll(before);
        // Why the row is still there, in the one place a user will look for it. "This line does
        // nothing" invites deleting it by hand; "removing it makes the plan buy its own ingredient"
        // is the fact that stops them, and naming the ingredient gives the next question an answer.
        if (!appeared.isEmpty()) {
            return withKeptNote(result, appeared);
        }
        return bought.isEmpty() ? result : withKeptNote(result, bought);
    }

    /**
     * What the pruned answer would have to buy that the original made.
     *
     * <p>The one question that separates "that line was not needed" from "removing it took the
     * anchor out of a loop". Compared by key rather than by amount, because an import appearing at
     * all is the failure and how much of it is beside the point. Returned rather than counted, so
     * the plan can name the item it would have started buying.
     */
    private static List<MfpKey> newImports(SolveResult pruned, SolveResult original) {
        List<MfpKey> appeared = new ArrayList<>();
        for (MfpKey key : pruned.rawInputs().keySet()) {
            if (!original.rawInputs().containsKey(key)) {
                appeared.add(key);
            }
        }
        return appeared;
    }

    /**
     * What the removed lines make that the answer is buying anyway.
     *
     * <p>The second half of the guard, and the half {@link #newImports} cannot see. That one asks
     * whether pruning <em>started</em> an import; this asks whether the lines being taken out are the
     * plan's way of making something already on the shopping list. If they are, the answer to
     * "nothing in the plan demanded anything they make" is that something did, and what the engine
     * really decided was to buy it instead — a decision that belongs on the screen rather than in a
     * deletion.
     *
     * <p>Found on the pack's polyvinyl butyral chain, which expands to 42 lines: the engine could
     * not balance butyraldehyde, relaxed it into an import, and 39 lines went idle behind it.
     * Pruning them left a three-line plan importing two intermediates it had a whole chemistry
     * tree for, and — because the pruned plan re-solves from scratch, and in it those items have no
     * producer at all — the warning explaining why they were bought did not survive either. A plan
     * that quietly says "butyraldehyde is a raw material" is exactly the confidently wrong answer
     * this project's first principle is about.
     *
     * <p><b>Raw materials do not count</b>, and leaving them out is what keeps this from being a
     * rule against dead lines in general. The user has already said water comes from a hole in the
     * ground, so a distillation tower that drops water as a byproduct is not the plan's way of
     * obtaining it and buying it is not a decision anyone needs told about. The reported plan of
     * §14f is exactly that shape — its idle biomass tower makes water among its distillates — and
     * without this clause its row would stay at zero forever for a reason that has nothing to do
     * with it.
     */
    private static List<MfpKey> alsoMadeByTheRemovedLines(List<Line> removed, SolveResult pruned,
                                                          Plan plan) {
        List<MfpKey> bought = new ArrayList<>();
        for (Line line : removed) {
            for (MfpOutput output : line.recipe().outputs()) {
                MfpKey key = output.key();
                if (plan.rawMaterials().contains(key) || plan.freeItems().contains(key)) {
                    continue;
                }
                if (pruned.rawInputs().containsKey(key) && !bought.contains(key)) {
                    bought.add(key);
                }
            }
        }
        return bought;
    }

    private static SolveResult withKeptNote(SolveResult result, List<MfpKey> appeared) {
        List<String> warnings = new ArrayList<>(result.warnings());
        warnings.add("a line at zero was left in the plan: removing it would make the plan import "
                + appeared + " rather than make it");
        return new SolveResult(result.lines(), result.byLine(), result.products(),
                result.rawInputs(), result.byproducts(), result.unsatisfied(),
                result.euDrawPerSecond(), result.euGeneratedPerSecond(), result.steamDrawPerSecond(),
                result.confidence(), warnings, result.engine());
    }

    /**
     * Whether the plan is one flat floor, which is the only shape this is willing to prune.
     *
     * <p>Not a limitation anyone meets today — expansion produces a flat floor — but restoring a
     * rejected attempt means putting the nodes back exactly, and that is only honest at the top
     * level. A subfloor's internal order would have to be rebuilt from the inside out, and a prune
     * that half-restores a plan is worse than one that declines to run.
     */
    private static boolean isFlat(Plan plan) {
        for (LineNode node : plan.root().nodes()) {
            if (!(node instanceof Line)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The lines this answer says are not needed at all.
     *
     * <p>Zero crafts a second, by the same epsilon the rest of the solver rounds with. A line
     * running at a millionth of a machine is doing something and stays.
     */
    private static List<Line> deadLines(SolveResult result) {
        if (result.engine() == SolverMode.SEQUENTIAL) {
            return List.of();
        }
        List<Line> dead = new ArrayList<>();
        for (LineResult line : result.lines()) {
            if (line.isIdle()) {
                dead.add(line.line());
            }
        }
        return dead;
    }

    /**
     * Say that lines were removed, because a row vanishing without explanation is its own bug report.
     *
     * <p>Usually the line was one the plan added itself and the user never knew about, but it can be
     * one they pinned — and being told "nothing here needed it" is the difference between a planner
     * that answered and one that lost their choice.
     */
    private static SolveResult withNote(SolveResult result, int dropped) {
        List<String> warnings = new ArrayList<>(result.warnings());
        warnings.add(dropped == 1
                ? "one line was removed: nothing in the plan demanded anything it makes"
                : dropped + " lines were removed: nothing in the plan demanded anything they make");
        return new SolveResult(result.lines(), result.byLine(), result.products(),
                result.rawInputs(), result.byproducts(), result.unsatisfied(),
                result.euDrawPerSecond(), result.euGeneratedPerSecond(), result.steamDrawPerSecond(),
                result.confidence(), warnings, result.engine());
    }

    private static SolveResult solveOnce(Plan plan, ThroughputResolver resolver) {
        if (plan.solverMode() == SolverMode.SIMPLEX) {
            return wholePlan(plan, resolver);
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

    /**
     * The whole-plan path, which is the one a plan reaches on its own: simplex, then the matrix
     * engine, then the sequential pass.
     *
     * <p>M12 turned the order around. The matrix engine was the default and patched its own failures
     * with a heuristic that ranked candidates on raw flow — which is not a comparison across
     * different items, and on the pack's polyvinyl butyral chain it chose to discard 99.6% of the
     * plan's own ethylene and inflate the plan twentyfold (STATUS §14f.2). The simplex engine owns
     * that case by construction, because it allows over-production rather than forbidding it, and it
     * answered the same plan correctly with no relaxation at all.
     *
     * <p><b>The matrix engine is still here, and it is a fallback rather than a rival.</b> It is
     * tried when simplex fails outright — an unbounded programme, or the iteration cap — because two
     * engines built from the same columns fail at different things, and an answer from either beats
     * the sequential pass on a plan with a loop in it. It is <em>not</em> tried when the plan carries
     * an inequality, because it would answer while ignoring the very setting the user set.
     *
     * <p>Ambiguity does not go to it. Both engines now reach that verdict (M12 item 1) and it is a
     * statement about the plan rather than about the engine, so asking the other one is asking a
     * question already answered.
     */
    private static SolveResult wholePlan(Plan plan, ThroughputResolver resolver) {
        try {
            return new SimplexSolver(resolver).solve(plan);
        } catch (SimplexSolver.AmbiguousPlanException ambiguous) {
            return fallback(plan, resolver, ambiguous.diagnostics(),
                    "this plan has more than one answer and nothing in it says which, so these "
                            + "numbers come from the sequential pass");
        } catch (RuntimeException failure) {
            if (!hasInequality(plan)) {
                try {
                    return withNote(new MatrixSolver(resolver).solve(plan),
                            "the simplex engine could not solve this plan, so these numbers come "
                                    + "from the matrix engine: " + failure.getMessage());
                } catch (RuntimeException second) {
                    return fallback(plan, resolver,
                            List.of(String.valueOf(failure.getMessage()),
                                    String.valueOf(second.getMessage())),
                            "neither whole-plan engine could solve this plan, so these numbers come "
                                    + "from the sequential pass and cannot close any loop it "
                                    + "contains");
                }
            }
            return fallback(plan, resolver, List.of(String.valueOf(failure.getMessage())),
                    "the simplex engine could not solve this plan, so these numbers come from the "
                            + "sequential pass and cannot close any loop the plan contains");
        }
    }

    /** The same answer carrying one more warning. */
    private static SolveResult withNote(SolveResult result, String warning) {
        List<String> warnings = new ArrayList<>();
        warnings.add(warning);
        warnings.addAll(result.warnings());
        return new SolveResult(result.lines(), result.byLine(), result.products(),
                result.rawInputs(), result.byproducts(), result.unsatisfied(),
                result.euDrawPerSecond(), result.euGeneratedPerSecond(), result.steamDrawPerSecond(),
                result.confidence(), warnings, result.engine());
    }

    private static SolveResult simplexOr(Plan plan, ThroughputResolver resolver,
                                         MatrixSolver.UnsolvableSystemException matrixFailure) {
        try {
            return new SimplexSolver(resolver).solve(plan);
        } catch (SimplexSolver.AmbiguousPlanException ambiguous) {
            // Not a failure of the engine: the engine is refusing to pick, which is the point of it
            // having learned to notice. Worded as the matrix engine words the same finding, since
            // the fix — drop one of the lines or pin its rate — is the same one.
            return fallback(plan, resolver, ambiguous.diagnostics(),
                    "this plan has more than one answer and nothing in it says which, so these "
                            + "numbers come from the sequential pass");
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
