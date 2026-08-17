package dev.mfp.core.solver;

import dev.mfp.core.model.Confidence;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.Line;
import dev.mfp.core.plan.MachineConfig;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.plan.SolverMode;
import dev.mfp.core.plan.TargetOutput;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The whole-plan engine, ported from Factory Planner's {@code matrix_engine}.
 *
 * <p>Where {@link SequentialSolver} walks the plan once carrying demand downward, this one asks a
 * single question of every item at once: <b>what set of production rates makes this factory
 * balance?</b> That framing is what makes loops solvable. A sequential pass cannot close
 * "acid leaches ore, refining the concentrate gives the acid back", because by the time the refiner
 * produces acid the leacher has already been solved. Stated as equations, the loop is not special —
 * it is one more row that must sum to zero.
 *
 * <h2>The system</h2>
 * <pre>
 *   rows    = items; energy has a row but is always free, so it never constrains anything
 *   columns = one per line, plus one per "free" item
 *   A[i][j] = net amount of item i per craft of line j   (+ produced, - consumed)
 *   b[i]    = 0 for a constrained item, the target rate for a demanded one
 * </pre>
 *
 * <p>A free item's column holds a single {@code 1} in its own row, so its solved value <em>is</em>
 * that item's net import (positive) or export (negative). Every other item is <b>eliminated</b>:
 * constrained to net exactly zero, which is precisely the "everything an intermediate produces must
 * be consumed" statement a loop needs.
 *
 * <h2>Two deliberate deviations from plan §9.2</h2>
 *
 * <p><b>The unknown is crafts per second, not machines.</b> The plan says a column holds the net
 * rate per <em>machine</em>. That breaks on any recipe with no intrinsic rate — hand crafting, an
 * instant conversion — whose per-machine rate is zero, giving an all-zero column and a system with
 * no unique solution, for a line that is perfectly well defined. Solving for crafts per second keeps
 * the matrix independent of the throughput model entirely; machine count is then
 * {@code crafts / craftsPerSecondPerMachine}, computed after the fact and left fractional as always.
 * The two formulations differ only by a per-column scale factor, so the answer is the same wherever
 * both are defined.
 *
 * <p><b>The system need not be square.</b> Factory Planner requires {@code rows == columns} and asks
 * the user to promote items until it is. Real plans routinely produce a redundant row — a perfectly
 * recycled intermediate says the same thing as the line that recycles it — and rejecting those would
 * reject the loops this engine exists to solve. {@link GaussJordan} reduces whatever shape it is
 * given; redundancy is harmless, and only a genuine contradiction or an unpinned unknown is a
 * failure. Those are reported by {@link UnsolvableSystemException} with the offending items and
 * recipes named, so the caller can fall back rather than show nothing (plan §9.3).
 *
 * <h2>What this engine does not honour</h2>
 * Line percentages and machine limits are sequential-engine features. A limit is an inequality, and
 * a system of equations cannot express "at most four of these" — that is the simplex engine plan
 * §9.2 leaves out of v1. Rather than silently ignoring them, a warning names every line affected.
 */
public final class MatrixSolver {

    private final ColumnBuilder columns;

    public MatrixSolver() {
        this(ThroughputResolver.BASE);
    }

    public MatrixSolver(ThroughputResolver resolver) {
        this.columns = new ColumnBuilder(resolver);
    }

    /** Raised when the system has no single answer, carrying the diagnosis for the user. */
    public static final class UnsolvableSystemException extends RuntimeException {

        private final List<String> diagnostics;
        private final boolean overConstrained;
        private final List<MfpKey> conflicting;

        UnsolvableSystemException(String message, List<String> diagnostics, boolean overConstrained,
                                  List<MfpKey> conflicting) {
            super(message + ": " + String.join("; ", diagnostics));
            this.diagnostics = List.copyOf(diagnostics);
            this.overConstrained = overConstrained;
            this.conflicting = List.copyOf(conflicting);
        }

        /** The items the deadlock is between, which is what relaxing has to choose from. */
        public List<MfpKey> conflicting() {
            return conflicting;
        }

        /**
         * Whether the plan asks for too much rather than too little.
         *
         * <p>The two failures need opposite fixes: an over-constrained plan is relieved by letting
         * an item be imported or exported, while an ambiguous one needs a line removed. Freeing an
         * item in the ambiguous case only makes it more ambiguous, so the distinction is worth
         * carrying rather than re-deriving.
         */
        public boolean isOverConstrained() {
            return overConstrained;
        }

        /** One entry per problem found, phrased for the user and naming what to change. */
        public List<String> diagnostics() {
            return diagnostics;
        }
    }

    public SolveResult solve(Plan plan) {
        List<Column> columns = new ArrayList<>();
        List<IdleLine> idle = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (Line line : plan.allLines()) {
            if (!line.active()) {
                idle.add(new IdleLine(line, "line is disabled"));
                continue;
            }
            columns.add(this.columns.column(line, warnings));
        }
        reportUnhonouredSettings(columns, warnings);

        Map<MfpKey, Double> targets = new LinkedHashMap<>();
        for (TargetOutput target : plan.targets()) {
            targets.merge(target.key(), target.perSecond(), Double::sum);
        }

        // The plan's raw materials are free items too, and for a stronger reason than convenience:
        // the user has said this item comes from outside the factory without limit. Forcing water to
        // net to zero when a distillation tower drops 500 mB of it as a byproduct is the deadlock
        // that made the whole plan unsolvable and sent it back to the sequential pass, complete with
        // "imported despite being produced in this plan" about the very item the user had declared
        // infinite. Being raw and having to balance are contradictory claims; the raw one is the
        // user's, so it wins.
        Set<MfpKey> free = new LinkedHashSet<>(plan.freeItems());
        free.addAll(plan.rawMaterials());

        try {
            return solveWith(plan, columns, idle, targets, free, warnings);
        } catch (UnsolvableSystemException failure) {
            MfpKey leak = smallestLeak(failure, plan, columns, idle, targets, free);
            if (leak == null) {
                throw failure;
            }
            // Said plainly, because it changes what the numbers mean: this item no longer has to
            // balance, so the plan may over-produce or import it. The alternative was the sequential
            // pass, which cannot close a loop at all and answered a beetroot chain with thirty
            // thousand centrifuges.
            warnings.add("these recipes cannot balance " + leak + " exactly, so the plan is allowed "
                    + "to over-produce or import it - everything else still balances");
            Set<MfpKey> relaxed = new LinkedHashSet<>(free);
            relaxed.add(leak);
            return solveWith(plan, columns, idle, targets, relaxed, warnings);
        }
    }

    /**
     * The one item to stop constraining, chosen as the one the plan leaks least of.
     *
     * <p>A recipe with several outputs fixes the ratio between them — GregTech's centrifuge turns one
     * essence into five others in proportions nobody chose — so a plan consuming them in any other
     * ratio has no exact solution. Requiring every intermediate to net to zero is what makes it
     * unsolvable, and the surplus it refuses to allow is a heap of spare essence, which is a thing a
     * real factory simply has.
     *
     * <p><b>One item, not all of them.</b> The diagnosis names every constrained item because any of
     * them <em>could</em> be the one freed; freeing the lot makes the system trivially solvable and
     * meaningless, since every line may then import whatever it likes. So each is tried alone and the
     * winner is the one whose freed flow is smallest — the least intervention that makes the plan
     * describable, and the one a player would pick for the same reason.
     *
     * <p>Targets are never candidates: the plan exists to balance them against what was asked for,
     * and freeing one would let the engine "satisfy" it by importing it.
     *
     * @return the item to free, or null when nothing single-handedly resolves it
     */
    private MfpKey smallestLeak(UnsolvableSystemException failure, Plan plan, List<Column> columns,
                                List<IdleLine> idle, Map<MfpKey, Double> targets, Set<MfpKey> free) {
        if (!failure.isOverConstrained()) {
            return null;
        }
        MfpKey best = null;
        double smallest = Double.MAX_VALUE;
        int tried = 0;
        for (MfpKey candidate : failure.conflicting()) {
            if (targets.containsKey(candidate) || free.contains(candidate) || tried++ >= RELAX_ATTEMPTS) {
                continue;
            }
            Set<MfpKey> attempt = new LinkedHashSet<>(free);
            attempt.add(candidate);
            try {
                // Copies: solveWith moves disconnected lines from columns to idle, and an attempt
                // that is about to be thrown away must not take the real lists with it.
                SolveResult result = solveWith(plan, new ArrayList<>(columns), new ArrayList<>(idle),
                        targets, attempt, new ArrayList<>());
                // Surplus beats import, whatever the magnitudes. Letting a factory keep spare
                // essence costs a barrel; letting it import an intermediate invents a supply chain
                // the user never asked for, and would quietly excuse a plan that does not work.
                double flow = result.byproducts().containsKey(candidate)
                        ? result.byproducts().get(candidate) - SURPLUS_PREFERENCE
                        : result.rawInputs().getOrDefault(candidate, 0.0);
                if (flow < smallest) {
                    smallest = flow;
                    best = candidate;
                }
            } catch (UnsolvableSystemException ignored) {
                // Freeing this one is not enough on its own; try the next.
            }
        }
        return best;
    }

    private SolveResult solveWith(Plan plan, List<Column> columns, List<IdleLine> idle,
                                  Map<MfpKey, Double> targets, Set<MfpKey> free,
                                  List<String> warnings) {
        ItemSystem system = classify(columns, targets, free);
        system = dropDisconnectedLines(columns, idle, targets, free, system);

        double[] solution = solveSystem(system, columns, targets, warnings);
        return assemble(plan.allLines(), system, columns, idle, solution, targets, warnings);
    }

    /**
     * Remove lines the system says nothing about, before deciding the system is ambiguous.
     *
     * <p>A line every one of whose items floats free — nothing demands its output and nothing
     * constrains its inputs — has no rate the equations could determine. Left in, it is an unpinned
     * column and the whole plan is rejected as ambiguous, which is a poor answer for what is really
     * just an unused line. The sequential engine's equivalent is idling it because nothing demanded
     * its output, and that is the answer given here too.
     *
     * <p>Note what this must <em>not</em> catch. A disposal line placed to eat a byproduct produces
     * nothing anyone wants, but by consuming that byproduct it stops the item being free — so the
     * item is constrained and the line's rate follows from it. Testing "does this line touch a
     * constrained item" rather than "does it produce something wanted" is what keeps those working.
     * Two interchangeable recipes both touch their constrained product, so they are kept and
     * correctly reported as ambiguous rather than silently resolved in favour of the first.
     *
     * <p>Dropping a line can free an item that only it consumed, which can strand another line, so
     * this repeats until it settles.
     */
    private static ItemSystem dropDisconnectedLines(List<Column> columns, List<IdleLine> idle,
                                                    Map<MfpKey, Double> targets,
                                                    Set<MfpKey> userFree, ItemSystem system) {
        for (int pass = 0; pass <= columns.size(); pass++) {
            Set<MfpKey> constrained = new LinkedHashSet<>(system.eliminated());
            List<Column> kept = new ArrayList<>();
            for (Column column : columns) {
                if (column.perCraft().keySet().stream().anyMatch(constrained::contains)) {
                    kept.add(column);
                } else {
                    idle.add(new IdleLine(column.line(),
                            "nothing in the plan needs what this line makes"));
                }
            }
            if (kept.size() == columns.size()) {
                return system;
            }
            columns.clear();
            columns.addAll(kept);
            system = classify(columns, targets, userFree);
        }
        return system;
    }

    // ------------------------------------------------------------ the settings

    private static void reportUnhonouredSettings(List<Column> columns, List<String> warnings) {
        List<String> percentages = new ArrayList<>();
        List<String> limits = new ArrayList<>();
        for (Column column : columns) {
            Line line = column.line();
            if (Math.abs(line.percentage() - 100.0) > 1e-9) {
                percentages.add(line.recipe().id());
            }
            MachineConfig machine = line.machine();
            if (machine != null && machine.hasLimit()) {
                limits.add(line.recipe().id());
            }
        }
        if (!percentages.isEmpty()) {
            warnings.add("the matrix engine solves for the rates that balance the plan, so line "
                    + "percentages were ignored for: " + percentages);
        }
        if (!limits.isEmpty()) {
            warnings.add("machine limits are an inequality and cannot be expressed in a system of "
                    + "equations, so they were ignored for: " + limits);
        }
    }

    // ------------------------------------------------------------ the system

    /**
     * Decide which items float and which are pinned to zero, per plan §9.2.
     *
     * <pre>
     *   rawInputs      = consumed but never produced
     *   byproducts     = produced but never consumed, and not wanted
     *   unproducedOuts = wanted but nothing makes them
     *   free           = those three, plus whatever the user unpinned
     *   eliminated     = everything else, constrained to net zero
     * </pre>
     *
     * <p>The subtraction of the targets from the byproducts is the load-bearing line. Without it a
     * demanded item that nothing consumes would float free, and the equation asking the factory to
     * make one per second would never be written.
     */
    private static ItemSystem classify(List<Column> columns, Map<MfpKey, Double> targets,
                                   Set<MfpKey> userFree) {
        Set<MfpKey> consumed = new LinkedHashSet<>();
        Set<MfpKey> produced = new LinkedHashSet<>();
        Set<MfpKey> all = new LinkedHashSet<>();

        for (Column column : columns) {
            column.perCraft().forEach((key, amount) -> {
                all.add(key);
                if (amount < 0) {
                    consumed.add(key);
                } else {
                    produced.add(key);
                }
            });
            // Idle drain is consumption that does not scale with the column, so the item is real to
            // the system even when no column carries it.
            if (column.throughput().fixedEuPerSecond() > 0) {
                all.add(MfpKey.EU);
                consumed.add(MfpKey.EU);
            }
        }
        all.addAll(targets.keySet());

        Set<MfpKey> free = new LinkedHashSet<>();
        for (MfpKey key : all) {
            boolean isConsumed = consumed.contains(key);
            boolean isProduced = produced.contains(key);
            boolean isTarget = targets.containsKey(key);
            if (isConsumed && !isProduced) {
                free.add(key);                       // raw input
            } else if (isProduced && !isConsumed && !isTarget) {
                free.add(key);                       // byproduct nothing wants
            } else if (isTarget && !isProduced) {
                free.add(key);                       // demanded, but nothing makes it
            }
        }
        for (MfpKey key : userFree) {
            if (all.contains(key)) {
                free.add(key);
            }
        }
        // Energy is unconditionally free, which reverses the "constrain first, relax once" rule this
        // engine used until M6c. Constraining it sized a generator from the plan's own demand — plan
        // P3 working exactly as intended — but GregTech chains pick up a steam turbine incidentally
        // all the time, and the plan was then required to be exactly self-powered, which the user
        // never asked for. The relaxation that existed to relieve that was already an admission that
        // the constraint was rarely wanted, so the constraint is gone and the draw is reported
        // instead (plan §13.4).
        if (all.contains(MfpKey.EU)) {
            free.add(MfpKey.EU);
        }

        List<MfpKey> items = new ArrayList<>(all);
        List<MfpKey> freeItems = new ArrayList<>(free);
        List<MfpKey> eliminated = new ArrayList<>(all);
        eliminated.removeAll(free);
        return new ItemSystem(items, freeItems, eliminated, produced);
    }

    private double[] solveSystem(ItemSystem system, List<Column> columns,
                                 Map<MfpKey, Double> targets, List<String> warnings) {
        int rows = system.items().size();
        int lineColumns = columns.size();
        int totalColumns = lineColumns + system.freeItems().size();
        if (totalColumns == 0) {
            return new double[0];
        }

        // Free columns first, line columns after. The reduction takes pivots left to right, so with
        // the lines first a plan with more lines than items runs out of rows before it reaches the
        // free columns, and every import and export comes back "undetermined" — which is how a plan
        // whose real fault was thirty-seven lines it never needed came to be reported as the plan
        // failing to determine its own energy draw. A free column is a unit vector on its own item's
        // row, so offering it a pivot first always takes one, and the rank deficiency lands where it
        // belongs: on the lines whose rates genuinely are not decided.
        int freeColumns = system.freeItems().size();
        double[][] augmented = new double[rows][totalColumns + 1];
        for (int row = 0; row < rows; row++) {
            MfpKey item = system.items().get(row);
            for (int col = 0; col < lineColumns; col++) {
                augmented[row][freeColumns + col] = columns.get(col).perCraft().getOrDefault(item, 0.0);
            }
            double rhs = targets.getOrDefault(item, 0.0);
            if (item.equals(MfpKey.EU)) {
                // Idle drain is constant with respect to the unknowns, so it belongs on the
                // right-hand side and never in a column (plan §9.2, STATUS 5.8). A constant folded
                // into a per-craft coefficient makes the system non-linear exactly where the solver
                // assumes it is not, and the machine counts come out wrong rather than imprecise.
                for (Column column : columns) {
                    rhs += column.throughput().fixedEuPerSecond();
                }
            }
            augmented[row][totalColumns] = rhs;
        }

        int[] freeColumnOfRow = new int[rows];
        java.util.Arrays.fill(freeColumnOfRow, -1);
        for (int f = 0; f < freeColumns; f++) {
            int row = system.items().indexOf(system.freeItems().get(f));
            augmented[row][f] = 1.0;
            freeColumnOfRow[row] = f;
        }

        double[] rowScale = scaleRows(augmented, freeColumns, totalColumns);

        GaussJordan.Result reduced = reorder(GaussJordan.reduce(augmented, totalColumns),
                freeColumns, lineColumns);
        if (!reduced.isDetermined()) {
            throw new UnsolvableSystemException("the plan does not describe one definite factory",
                    diagnose(reduced, system, columns, lineColumns),
                    !reduced.inconsistentRows().isEmpty() && reduced.unpinnedColumns().isEmpty(),
                    reduced.inconsistentRows().isEmpty() ? List.of() : system.eliminated());
        }

        double[] solution = reduced.solution();
        // Back to line-first, which is the order every caller and every diagnostic speaks in. The
        // free-first layout is an implementation detail of the reduction and stops here.
        for (int row = 0; row < rows; row++) {
            if (freeColumnOfRow[row] >= 0) {
                freeColumnOfRow[row] += lineColumns;
            }
        }
        // Undo the row scaling for the free unknowns. A free column's single entry was deliberately
        // left at one while its row was divided, so the value that came out is the real one divided
        // by that row's factor.
        for (int row = 0; row < rows; row++) {
            if (freeColumnOfRow[row] >= 0) {
                solution[freeColumnOfRow[row]] *= rowScale[row];
            }
        }

        for (int col = 0; col < lineColumns; col++) {
            if (solution[col] < -ItemFlows.EPSILON) {
                warnings.add(columns.get(col).line().recipe().id() + " solved to a negative rate, "
                        + "which means the plan can only balance by running it backwards; clamped to "
                        + "zero, so the remaining numbers do not balance");
                solution[col] = 0.0;
            } else if (solution[col] < 0) {
                solution[col] = 0.0;
            }
        }
        return solution;
    }

    /**
     * Divide each row by its largest line coefficient, and report the factors.
     *
     * <p>Mandatory rather than a refinement (plan §9.2). Energy coefficients run to 1e5 and beyond
     * while item counts are single digits, so without scaling the partial pivoting picks the energy
     * row for every column it can and the elimination that follows is numerical garbage.
     *
     * <p>The free columns are deliberately <em>not</em> scaled. Keeping their entry at exactly one
     * is what keeps that column well conditioned; the cost is that the solved value comes back
     * divided by the row's factor, which the caller multiplies out.
     */
    private static double[] scaleRows(double[][] augmented, int firstLineColumn, int totalColumns) {
        double[] factors = new double[augmented.length];
        for (int row = 0; row < augmented.length; row++) {
            double largest = 0.0;
            for (int col = firstLineColumn; col < totalColumns; col++) {
                largest = Math.max(largest, Math.abs(augmented[row][col]));
            }
            double factor = largest > 0 ? largest : 1.0;
            factors[row] = factor;
            if (factor == 1.0) {
                continue;
            }
            for (int col = firstLineColumn; col < totalColumns; col++) {
                augmented[row][col] /= factor;
            }
            augmented[row][totalColumns] /= factor;
        }
        return factors;
    }

    /**
     * Translate a reduction done free-columns-first back into line-columns-first.
     *
     * <p>Only the indices move; no number changes. Everything outside {@link #solveSystem} — the
     * solution array, {@link #diagnose}, the callers reading machine counts — is written in terms of
     * "lines, then free items", and keeping the translation in one place is what stops the reduction
     * order from leaking into any of it.
     */
    private static GaussJordan.Result reorder(GaussJordan.Result reduced, int freeColumns,
                                              int lineColumns) {
        double[] solution = new double[reduced.solution().length];
        for (int col = 0; col < lineColumns; col++) {
            solution[col] = reduced.solution()[freeColumns + col];
        }
        for (int f = 0; f < freeColumns; f++) {
            solution[lineColumns + f] = reduced.solution()[f];
        }
        List<Integer> unpinned = reduced.unpinnedColumns().stream()
                .map(col -> col < freeColumns ? lineColumns + col : col - freeColumns)
                .sorted()
                .toList();
        return new GaussJordan.Result(solution, unpinned, reduced.inconsistentRows());
    }

    /**
     * Enough to put any surplus ahead of any import, since a rate has no upper bound worth naming.
     *
     * <p>Subtracted rather than compared as a separate key so the ordering stays a single number,
     * and so two surpluses still sort by size against each other.
     */
    private static final double SURPLUS_PREFERENCE = 1e12;

    /** How many single-item relaxations to try before giving up and falling back. */
    private static final int RELAX_ATTEMPTS = 24;

    /** Turn "the matrix is singular" into something a user can act on. */
    private static List<String> diagnose(GaussJordan.Result reduced, ItemSystem system,
                                         List<Column> columns, int lineColumns) {
        List<String> diagnostics = new ArrayList<>();

        List<String> dependentRecipes = new ArrayList<>();
        List<MfpKey> unpinnedItems = new ArrayList<>();
        for (int column : reduced.unpinnedColumns()) {
            if (column < lineColumns) {
                dependentRecipes.add(columns.get(column).line().recipe().id());
            } else {
                unpinnedItems.add(system.freeItems().get(column - lineColumns));
            }
        }
        if (!dependentRecipes.isEmpty()) {
            // Only the columns left without a pivot are named, which is one fewer than the set of
            // interchangeable recipes: the first of them took the pivot. Naming the ones whose rate
            // is undetermined is the accurate statement, and it is also the actionable one.
            diagnostics.add("nothing decides how fast these lines run, because another line in the "
                    + "plan makes the same thing - drop one or pin its rate: " + dependentRecipes);
        }
        if (!unpinnedItems.isEmpty()) {
            diagnostics.add("nothing in the plan determines the import or export of: " + unpinnedItems);
        }
        if (!reduced.inconsistentRows().isEmpty()) {
            // Over-constrained: some intermediate is being asked to balance at zero when the chosen
            // recipes cannot make that happen. Promoting it to a free item is the fix, and it is the
            // user's call which one, so name them all rather than choosing.
            List<MfpKey> conflicting = new ArrayList<>(system.eliminated());
            diagnostics.add("these recipes cannot all balance at once; allow one of the constrained "
                    + "items to be imported or exported to break the deadlock: " + conflicting);
        }
        return diagnostics;
    }

    // ------------------------------------------------------------- assembly

    private SolveResult assemble(List<Line> order, ItemSystem system, List<Column> columns,
                                 List<IdleLine> idle, double[] solution,
                                 Map<MfpKey, Double> targets, List<String> warnings) {
        Map<Line, LineResult> byLine = new IdentityHashMap<>();
        Confidence confidence = Confidence.EXACT;
        double euIn = 0;
        double euOut = 0;
        double steamIn = 0;

        for (int col = 0; col < columns.size(); col++) {
            LineResult result = lineResult(columns.get(col), solution[col]);
            byLine.put(result.line(), result);
            confidence = confidence.and(result.confidence());
            euIn += result.euInPerSecond();
            euOut += result.euOutPerSecond();
            steamIn += result.steamPerSecond();
        }
        for (IdleLine line : idle) {
            byLine.put(line.line(), new LineResult(line.line(), 0, 0, 0, 0, 0,
                    Map.of(), Map.of(), Map.of(), Confidence.EXACT, line.reason()));
        }

        Map<MfpKey, Double> rawInputs = new LinkedHashMap<>();
        Map<MfpKey, Double> byproducts = new LinkedHashMap<>();
        Map<MfpKey, Double> products = new LinkedHashMap<>();
        Map<MfpKey, Double> unsatisfied = new LinkedHashMap<>();

        for (int f = 0; f < system.freeItems().size(); f++) {
            MfpKey key = system.freeItems().get(f);
            if (key.equals(MfpKey.EU)) {
                // The free variable for energy is a *net*, and netting is the one thing the draw
                // must never do (plan §13.4). It is reported gross, from the lines, so listing it
                // here as an import as well would be the same number twice and smaller both times.
                continue;
            }
            double net = solution[columns.size() + f];
            double demanded = targets.getOrDefault(key, 0.0);
            if (demanded > 0 && !system.produced().contains(key)) {
                // A target nothing makes: the free variable covers it by importing it, which is not
                // a plan. Say so plainly instead of listing the target as a purchase.
                unsatisfied.put(key, demanded);
                net -= demanded;
            }
            if (net > ItemFlows.EPSILON) {
                rawInputs.put(key, net);
            } else if (net < -ItemFlows.EPSILON) {
                byproducts.put(key, -net);
            }
        }
        targets.forEach((key, rate) -> {
            if (!unsatisfied.containsKey(key)) {
                products.put(key, rate);
            }
        });
        if (!unsatisfied.isEmpty()) {
            warnings.add("targets not fully satisfied: " + unsatisfied.keySet());
        }

        // Only now is it known what the plan does not consume, so only now can a line say which part
        // of its production was wanted.
        SurplusAttribution.apply(byLine, byproducts);

        // Report in the plan's own order. The matrix does not care about it, but the user reads the
        // same list they built, and /mfp explain addresses lines by number.
        List<LineResult> results = new ArrayList<>(byLine.size());
        for (Line line : order) {
            LineResult result = byLine.get(line);
            if (result != null) {
                results.add(result);
            }
        }

        return new SolveResult(results, byLine, products, rawInputs, byproducts, unsatisfied,
                euIn, euOut, steamIn, confidence, warnings, SolverMode.MATRIX);
    }

    private static LineResult lineResult(Column column, double craftsPerSecond) {
        Throughput throughput = column.throughput();
        double rate = Math.max(0.0, craftsPerSecond);
        double perMachine = throughput.craftsPerSecond();
        double machineCount = perMachine > ItemFlows.EPSILON ? rate / perMachine : 0.0;

        Map<MfpKey, Double> inputs = new LinkedHashMap<>();
        Map<MfpKey, Double> outputs = new LinkedHashMap<>();
        column.perCraft().forEach((key, amount) -> {
            double flow = amount * rate;
            if (flow < -ItemFlows.EPSILON) {
                inputs.put(key, -flow);
            } else if (flow > ItemFlows.EPSILON) {
                outputs.put(key, flow);
            }
        });
        if (throughput.fixedEuPerSecond() > 0) {
            inputs.merge(MfpKey.EU, throughput.fixedEuPerSecond(), Double::sum);
        }

        // Everything the line makes goes under outputs for now. The matrix balances the whole plan at
        // once, so which part of this was surplus is not known until the free variables are read;
        // SurplusAttribution splits it afterwards.
        return new LineResult(column.line(), rate, machineCount,
                throughput.euInPerSecond() * machineCount + throughput.fixedEuPerSecond(),
                throughput.euOutPerSecond() * machineCount,
                throughput.steamPerSecond() * machineCount,
                inputs, outputs, Map.of(), column.confidence(), column.note());
    }

    /** A line that never entered the system, and the reason it did not. */
    private record IdleLine(Line line, String reason) {}

    /**
     * The item classification the matrix is built from.
     *
     * @param items      every row, in row order
     * @param freeItems  items with a column of their own, in column order
     * @param eliminated items constrained to net zero
     * @param produced   every item some line makes, kept for reporting unmet targets
     */
    private record ItemSystem(List<MfpKey> items, List<MfpKey> freeItems, List<MfpKey> eliminated,
                          Set<MfpKey> produced) {}
}
