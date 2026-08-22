package dev.mfp.core.plan;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * The state stack behind undo (M15): what the plan looked like before each of the last few edits.
 *
 * <p><b>Whole states, not inverses.</b> A plan already deep-copies everything the user decided
 * ({@link Plan#snapshot()}) and the lines are output re-derived on the next solve, so a snapshot is
 * small — proportional to the pins, not to the plan. The alternative, storing each edit's inverse,
 * is far more code and one missed inverse away from silently corrupting a plan someone spent an hour
 * on. That trade would be worth arguing about if a snapshot cost anything; it does not.
 *
 * <p><b>One undoable step is one edit that ended in a re-solve and changed something.</b> That is
 * why nothing here is called from a mutator: {@link #record} is called once after each solve, and it
 * pushes only when the plan's own signature has moved since the last one. A target typed character
 * by character commits once, so it is one step; a drag moving a row four places ends in one
 * re-solve, so it is one step; and a re-solve that changed nothing — the Refresh button, a rebuild
 * after a rejected edit, every display-only toggle in the planner — pushes nothing, because a
 * history full of duplicates is a history where undo appears not to work.
 *
 * <p><b>The signature is {@link PlanCodec}'s own JSON</b>, which is exactly the definition of "what
 * the user decided" that the saved file and the export string use. So a field that travels with a
 * plan is a field undo notices, and a field that does not is one the solver derived. There is no
 * second list to keep in step, which is the whole reason for reusing it.
 *
 * <p><b>Bounded, and it does not outlive the session.</b> Twenty steps of a thousand-line pack plan
 * is a few hundred kilobytes; the oldest is dropped rather than grown into. It is deliberately never
 * written to disk — an undo history restored from a previous session lets someone take back
 * something they did last week without remembering what it was.
 */
public final class PlanHistory {

    /** How many steps back the planner offers. Twenty edits is far past what anyone re-treads. */
    public static final int DEFAULT_DEPTH = 20;

    /**
     * A state and the signature that says whether it is a different state.
     *
     * <p>Held together because the signature is what {@link #record} compares and computing it
     * twice for one snapshot is the one part of this that is not free on a pack plan.
     */
    private record Snapshot(Plan plan, String signature) {

        static Snapshot of(Plan plan) {
            return new Snapshot(plan.snapshot(), PlanCodec.write(plan).toString());
        }
    }

    private final int depth;
    private final Deque<Snapshot> past = new ArrayDeque<>();
    private final Deque<Snapshot> future = new ArrayDeque<>();

    /**
     * The state as of the last {@link #record}, which is the one thing a snapshot-per-edit scheme
     * cannot get from the plan itself: by the time an edit reaches a re-solve, the state it replaced
     * is already gone. Keeping the last one here is what lets every call site stay untouched.
     */
    private Snapshot baseline;

    public PlanHistory() {
        this(DEFAULT_DEPTH);
    }

    public PlanHistory(int depth) {
        if (depth < 1) {
            throw new IllegalArgumentException("a history has to hold at least one step");
        }
        this.depth = depth;
    }

    /**
     * Take note of where the plan is now, after a solve.
     *
     * <p>The first call establishes the baseline and records nothing — there is no earlier state to
     * go back to. Every later call compares and, if the plan has moved, files the state it moved
     * <em>from</em> as the step undo returns to.
     *
     * @return whether this counted as an edit, which is what a screen showing "3 steps" reads
     */
    public boolean record(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        Snapshot now = Snapshot.of(plan);
        if (baseline == null) {
            baseline = now;
            return false;
        }
        if (baseline.signature().equals(now.signature())) {
            return false;
        }
        past.addLast(baseline);
        while (past.size() > depth) {
            past.removeFirst();
        }
        // A new edit is a new branch: anything undone and then edited past is unreachable, and
        // offering redo for it would put back a plan that never existed alongside this edit.
        future.clear();
        baseline = now;
        return true;
    }

    public boolean canUndo() {
        return !past.isEmpty();
    }

    public boolean canRedo() {
        return !future.isEmpty();
    }

    /** How many steps back are available, for the button's tooltip. */
    public int undoDepth() {
        return past.size();
    }

    public int redoDepth() {
        return future.size();
    }

    /** The deepest history this stack will hold. */
    public int depth() {
        return depth;
    }

    /**
     * Put the plan back the way it was before the last edit.
     *
     * <p>Restores <em>into</em> the plan rather than handing back a new one, and that is not an
     * implementation detail: every screen in the planner captured its {@code Plan} when it opened,
     * so an undo that swapped the object would leave half the GUI editing a plan nobody is looking
     * at. The caller re-solves afterwards — the lines are output and this only restores decisions.
     *
     * <p>The baseline moves with it, so the {@link #record} at the end of that re-solve sees a plan
     * that matches and files nothing. Undo is not itself an edit.
     *
     * @return false when there is nothing to undo, and the plan is untouched
     */
    public boolean undo(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        if (past.isEmpty()) {
            return false;
        }
        future.addLast(baseline);
        baseline = past.removeLast();
        plan.restoreFrom(baseline.plan());
        return true;
    }

    /** The other direction, and what makes undo safe to press. */
    public boolean redo(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        if (future.isEmpty()) {
            return false;
        }
        past.addLast(baseline);
        baseline = future.removeLast();
        plan.restoreFrom(baseline.plan());
        return true;
    }

    /**
     * What every state in this history costs, in bytes of JSON.
     *
     * <p>Measured rather than estimated, because "a copy per edit of a thousand-line pack plan is
     * not free" was the objection this had to answer. It counts the signature strings, which are the
     * same shape as the snapshots beside them and the only part with a size worth reporting.
     */
    public long measuredBytes() {
        long total = baseline == null ? 0 : baseline.signature().length();
        for (Snapshot snapshot : past) {
            total += snapshot.signature().length();
        }
        for (Snapshot snapshot : future) {
            total += snapshot.signature().length();
        }
        return total;
    }

    /** Forget everything, keeping the current state as the new starting point. */
    public void clear() {
        past.clear();
        future.clear();
        baseline = null;
    }
}
