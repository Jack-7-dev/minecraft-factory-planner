package dev.mfp.client;

import dev.mfp.behaviour.RawMaterialConfig;
import dev.mfp.core.index.RecipeIndex;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.select.ChooserResult;
import dev.mfp.core.select.RecipeChooser;
import dev.mfp.core.solver.BehaviourThroughputResolver;
import dev.mfp.core.solver.SolveResult;
import dev.mfp.core.solver.Solvers;
import dev.mfp.plan.PlanSession;
import dev.mfp.plan.PlanStore;
import dev.mfp.plan.PreferenceStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds and solves plans on the client, and holds the ones the GUI can switch between.
 *
 * <p>The pipeline is exactly the one {@code /mfp plan} runs — choose, resolve, solve — deliberately
 * so. If the GUI and the command ever disagreed about a number, the command is the thing that has
 * been verified against live data since M4b, and the GUI would be wrong. Sharing the sequence keeps
 * that impossible; only the index and the output differ.
 *
 * <p><b>Every edit goes through {@link #refresh()}</b> (M6b). Changing a target, pinning a recipe or
 * configuring a machine mutates the {@link Plan} and re-runs the whole pipeline, rather than
 * patching the solved result in place. That costs a solve per edit — a few milliseconds once the
 * index is warm — and buys the one guarantee the table depends on: every number on screen came from
 * a single solve, so no two of them can belong to different answers.
 */
public final class ClientPlanner {

    private static final List<ClientPlan> PLANS = new ArrayList<>();
    private static volatile int currentIndex = -1;
    private static boolean loaded;

    private ClientPlanner() {}

    /** The plan the GUI is showing, or null before anything has been planned. */
    public static ClientPlan current() {
        return currentIndex < 0 || currentIndex >= PLANS.size() ? null : PLANS.get(currentIndex);
    }

    /** Every plan this session, in creation order. */
    public static List<ClientPlan> plans() {
        return List.copyOf(PLANS);
    }

    public static int currentIndex() {
        return currentIndex;
    }

    public static void select(int index) {
        if (index >= 0 && index < PLANS.size()) {
            currentIndex = index;
        }
    }

    /** Forget a plan. The selection moves to a neighbour, or to nothing when it was the last. */
    public static void remove(int index) {
        if (index < 0 || index >= PLANS.size()) {
            return;
        }
        PLANS.remove(index);
        currentIndex = PLANS.isEmpty() ? -1 : Math.min(currentIndex, PLANS.size() - 1);
    }

    public static void clear() {
        PLANS.clear();
        currentIndex = -1;
        loaded = false;
    }

    /**
     * Bring the saved plans back, once per session.
     *
     * <p>Called when the index is first wanted rather than on login, because a plan cannot be solved
     * until there is something to solve it against — and a plan that failed to solve on the way in
     * would look broken rather than merely early. Plans that no longer solve are still added: their
     * warnings are the honest report that the pack moved underneath them.
     */
    public static void loadSaved() {
        if (loaded) {
            return;
        }
        loaded = true;
        for (Plan saved : PlanStore.load()) {
            add(saved);
        }
        if (!PLANS.isEmpty()) {
            currentIndex = 0;
        }
    }

    /** Write every plan out, tagged with the world they were built against. */
    public static void saveAll(String worldName) {
        if (!loaded && PLANS.isEmpty()) {
            // Never loaded and nothing made: writing here would empty the file on a session that
            // never opened the planner at all.
            return;
        }
        PlanStore.save(PLANS.stream().map(ClientPlan::plan).toList(), worldName);
        // Beside the plans, and for the same reason: a standing default set this session is a
        // decision, and one that only lived in memory would be quietly forgotten on the way out.
        PreferenceStore.save();
    }

    /**
     * Solves for {@code perSecond} of {@code key} as a new plan, and makes it current.
     *
     * @return the solved plan, or null when nothing in the index produces the key
     */
    public static ClientPlan plan(MfpKey key, double perSecond) {
        // Before the plan exists, because a plan is seeded with the raw materials in force when it
        // was created. Reloading afterwards would leave the first plan of a session on the shipped
        // list and every later one on the player's.
        RawMaterialConfig.reload();
        PreferenceStore.reload();
        RecipeIndex index = ClientIndex.get();
        if (index.producing(key).isEmpty()) {
            return null;
        }
        // Unnamed: a plan follows the target it is for until the user renames it (M9.1). Naming it
        // here is what used to leave a retargeted plan calling itself after the item it no longer
        // makes.
        //
        // Expansion follows the standing default (M11.3). This is the /mfpplan path, which has no
        // screen to ask on; the New button asks, because it has one.
        return add(new Plan().target(key, perSecond)
                .autoResolve(PreferenceStore.get().autoResolve()));
    }

    /** Adds an already-built plan, solves it, and makes it current. */
    public static ClientPlan add(Plan plan) {
        ClientPlan solved = solve(plan, ClientIndex.get());
        PLANS.add(solved);
        currentIndex = PLANS.size() - 1;
        return solved;
    }

    /**
     * Re-solves the current plan against a freshly built index.
     *
     * <p>Both the toolbar's refresh and every edit in the GUI. It is not merely a convenience:
     * behaviour overrides are read from disk on every solve (plan §11), so editing one and pressing
     * refresh shows the effect without leaving the world.
     */
    public static ClientPlan refresh() {
        ClientPlan existing = current();
        if (existing == null) {
            return null;
        }
        ClientPlan resolved = solve(existing.plan(), ClientIndex.get());
        PLANS.set(currentIndex, resolved);
        return resolved;
    }

    private static ClientPlan solve(Plan plan, RecipeIndex index) {
        long started = System.nanoTime();

        // Expansion appends, so the previous solve's lines have to go first. The plan's targets and
        // pinned choices survive, which is what makes a re-solve after a recipe change apply that
        // change rather than reconstruct the plan around it.
        plan.clearLines();
        // The player's standing defaults, read from the same live instance the Defaults screen edits
        // (M8). Passed in rather than looked up inside the chooser so that `core` keeps knowing
        // nothing about where preferences are stored.
        BehaviourThroughputResolver resolver = PlanSession.resolverFor(index);
        ChooserResult chooserResult = new RecipeChooser(index, PreferenceStore.get())
                .withResolver(resolver)
                .expandInto(plan);
        long chosen = System.nanoTime();
        // The chooser has already moved the plan to MATRIX if it observed a loop while expanding.
        SolveResult solved = Solvers.solve(plan, resolver);
        long done = System.nanoTime();

        // Timed apart, because they are different costs with different causes and the status line
        // used to attribute both to the solver.
        // In microseconds, rendered as milliseconds with a decimal: a matrix solve of four lines
        // really does take a fraction of a millisecond, and "0 ms" reads as a broken clock rather
        // than as the answer.
        return new ClientPlan(plan, chooserResult, solved, resolver,
                (chosen - started) / 1_000L, (done - chosen) / 1_000L);
    }
}
