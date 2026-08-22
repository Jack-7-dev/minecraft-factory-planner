package dev.mfp.plan;

import dev.mfp.behaviour.BehaviourConfig;
import dev.mfp.behaviour.RawMaterialConfig;
import dev.mfp.core.behaviour.BehaviourRegistry;
import dev.mfp.core.index.RecipeIndex;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.plan.PlanHistory;
import dev.mfp.core.select.ChooserResult;
import dev.mfp.core.solver.BehaviourThroughputResolver;
import dev.mfp.core.solver.SolveResult;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The most recent plan each user asked for, so {@code /mfp explain} has something to explain.
 *
 * <p>Two commands rather than one long one is deliberate. {@code /mfp plan} answers the question;
 * {@code /mfp explain} shows the working. Recomputing inside explain would risk explaining a
 * different answer from the one that was printed, which is the one thing a debugging tool must
 * never do.
 *
 * <p>Keyed by name so a shared server does not have two players overwriting each other's working.
 */
public final class PlanSession {

    private static final Map<String, PlanSession> SESSIONS = new ConcurrentHashMap<>();

    private final Plan plan;
    private final ChooserResult chooserResult;
    private final SolveResult solveResult;
    private final BehaviourThroughputResolver resolver;
    private final PlanHistory history;

    private PlanSession(Plan plan, ChooserResult chooserResult, SolveResult solveResult,
                        BehaviourThroughputResolver resolver, PlanHistory history) {
        this.plan = plan;
        this.chooserResult = chooserResult;
        this.solveResult = solveResult;
        this.resolver = resolver;
        this.history = history;
    }

    /**
     * Remember this solve, and file the edit that led to it (M15).
     *
     * <p>The history follows the <em>plan object</em> rather than the owner: {@code /mfp plan}
     * builds a new one and starts a new history, while {@code /mfp pin}, {@code /mfp resolve} and
     * {@code /mfp option} re-solve the one already here and add a step to it. That is the same rule
     * the client uses, and it has to be, or {@code /mfp undo} would not be checking what the Undo
     * button does.
     */
    public static PlanSession store(String owner, Plan plan, ChooserResult chooserResult,
                                    SolveResult solveResult, BehaviourThroughputResolver resolver) {
        Objects.requireNonNull(plan, "plan");
        PlanSession previous = SESSIONS.get(owner);
        PlanHistory history = previous != null && previous.plan == plan
                ? previous.history
                : new PlanHistory();
        // After the solve, like the client: the chooser may have derived a solver mode on the way
        // through, and that is the solve's observation rather than the user's edit.
        history.record(plan);
        PlanSession session = new PlanSession(
                plan,
                Objects.requireNonNull(chooserResult, "chooserResult"),
                Objects.requireNonNull(solveResult, "solveResult"),
                Objects.requireNonNull(resolver, "resolver"),
                history);
        SESSIONS.put(owner, session);
        return session;
    }

    public static PlanSession of(String owner) {
        return SESSIONS.get(owner);
    }

    public static void clear() {
        SESSIONS.clear();
    }

    /**
     * A resolver wired to the current index and the config directory's overrides.
     *
     * <p>Built per plan rather than cached, so editing a behaviour override and re-running
     * {@code /mfp plan} shows the effect without restarting the server — which is how anyone
     * calibrating a machine against the game will actually work.
     */
    public static BehaviourThroughputResolver resolverFor(RecipeIndex index) {
        // Same reasoning, same moment: the player's infinite-resource list is re-read here so that
        // editing it and re-running a plan is enough to see the effect.
        RawMaterialConfig.reload();
        BehaviourRegistry registry = BehaviourConfig.loadRegistry();
        return new BehaviourThroughputResolver(registry, index::machine);
    }

    public Plan plan() {
        return plan;
    }

    public ChooserResult chooserResult() {
        return chooserResult;
    }

    public SolveResult solveResult() {
        return solveResult;
    }

    public BehaviourThroughputResolver resolver() {
        return resolver;
    }

    /** What this plan looked like before each of the last few edits (M15). */
    public PlanHistory history() {
        return history;
    }
}
