package dev.mfp.core.behaviour;

import dev.mfp.core.behaviour.gt.BatchModeBehaviour;
import dev.mfp.core.behaviour.gt.CoilOverclockBehaviour;
import dev.mfp.core.behaviour.gt.CoilTierOverclockBehaviour;
import dev.mfp.core.behaviour.gt.ElectricOverclockBehaviour;
import dev.mfp.core.behaviour.gt.FusionOverclockBehaviour;
import dev.mfp.core.behaviour.gt.GeneratorBehaviour;
import dev.mfp.core.behaviour.gt.LargeTurbineBehaviour;
import dev.mfp.core.behaviour.gt.MultiSmelterBehaviour;
import dev.mfp.core.behaviour.gt.ParallelHatchBehaviour;
import dev.mfp.core.behaviour.gt.SteamMachineBehaviour;
import dev.mfp.core.behaviour.gt.SteamMultiblockBehaviour;
import dev.mfp.core.behaviour.startcore.BoostedPlasmaTurbineBehaviour;
import dev.mfp.core.behaviour.startcore.BulkProcessingBehaviour;
import dev.mfp.core.behaviour.startcore.HellForgeBehaviour;
import dev.mfp.core.behaviour.startcore.SteamParallelBehaviour;
import dev.mfp.core.behaviour.startcore.ThreadingBehaviour;
import dev.mfp.core.behaviour.startcore.ThroughputBoostingBehaviour;
import dev.mfp.core.behaviour.startcore.VacuumChamberBehaviour;
import dev.mfp.core.model.Confidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides which behaviours apply to a machine, and in what order.
 *
 * <p>Order is the whole point. A machine declares its modifiers as an ordered list and GregTech
 * folds them left, so the chain this returns must preserve the machine's own order rather than the
 * registry's registration order — {@code super_ebf} declaring
 * {@code [ebf_oc, throughput_boosting, batch_mode]} means the boost multiplies the already
 * overclocked recipe, not the raw one.
 *
 * <p>Resolution, highest priority first:
 *
 * <ol>
 *   <li><b>Overrides.</b> A pack author correcting MFP about the pack's own machine is the most
 *       reliable source available, and an override that loses to a built-in cannot fix anything.
 *   <li><b>Registered behaviours matching the machine's declared modifiers</b>, in the machine's
 *       order. This covers GregTech's own modifiers and {@code start_core}'s named ones.
 *   <li><b>Shape-matched behaviours</b>, for rules GregTech attaches with a bare method reference
 *       and which therefore have no stable id to match on — generators above all.
 *   <li><b>The unknown default</b>: run the recipe as written, say so, and mark the line
 *       {@link Confidence#UNKNOWN}. Never a plausible guess (plan P5).
 * </ol>
 */
public final class BehaviourRegistry {

    private final Map<String, MachineBehaviour> byModifierId = new LinkedHashMap<>();
    private final List<MachineBehaviour> shapeMatched = new ArrayList<>();
    private final List<BehaviourOverride> overrides = new ArrayList<>();

    private BehaviourRegistry() {}

    /** The registry MFP ships with: GregTech's modifiers plus Star-Technology's. */
    public static BehaviourRegistry standard() {
        BehaviourRegistry registry = new BehaviourRegistry();

        registry.register(ElectricOverclockBehaviour.nonPerfect());
        registry.register(ElectricOverclockBehaviour.perfect());
        registry.register(ElectricOverclockBehaviour.nonPerfectSubTick());
        registry.register(ElectricOverclockBehaviour.perfectSubTick());
        registry.register(new CoilOverclockBehaviour());
        // The three coil multiblocks that are not the blast furnace. Their overclock comes from the
        // energy hatch like anything else's; the coil only scales it.
        for (CoilTierOverclockBehaviour coilTier : List.of(
                CoilTierOverclockBehaviour.chemicalReactor(),
                CoilTierOverclockBehaviour.cracker(),
                CoilTierOverclockBehaviour.pyrolyseOven())) {
            registry.register(coilTier, coilTier.modifierIds());
        }
        registry.register(new MultiSmelterBehaviour());
        registry.register(FusionOverclockBehaviour.gregTech());
        registry.register(FusionOverclockBehaviour.reflector());
        registry.register(new ParallelHatchBehaviour("parallel_hatch"));
        registry.register(new BatchModeBehaviour());

        // start_core. These must work out of the box: the pack's most important multiblocks are
        // these, not GregTech's, so treating them as "some unknown mod's machines" would leave the
        // planner unable to answer the questions it exists for.
        registry.register(new ThroughputBoostingBehaviour());
        registry.register(new BulkProcessingBehaviour());
        registry.register(new HellForgeBehaviour());
        registry.register(new ThreadingBehaviour());
        registry.register(new SteamParallelBehaviour());
        registry.register(new VacuumChamberBehaviour());
        registry.register(new BoostedPlasmaTurbineBehaviour());
        registry.register(new ParallelHatchBehaviour("absolute_parallel"));

        // Matched on shape because GregTech gives these no stable identifier (see GtMachineCatalog).
        registry.registerShapeMatched(new GeneratorBehaviour());
        registry.registerShapeMatched(new SteamMachineBehaviour());
        registry.registerShapeMatched(SteamMultiblockBehaviour.gregTech());
        registry.registerShapeMatched(new LargeTurbineBehaviour("large_turbine"));

        return registry;
    }

    /** A registry with nothing in it, for tests that want to isolate one behaviour. */
    public static BehaviourRegistry empty() {
        return new BehaviourRegistry();
    }

    public BehaviourRegistry register(MachineBehaviour behaviour) {
        byModifierId.put(behaviour.id(), behaviour);
        return this;
    }

    /**
     * Register one behaviour under several ids — the same rule spelled more than one way.
     *
     * <p>Not a convenience. GregTech is a moving target and MFP compiles against one build of a
     * fork while the pack ships another, so a modifier can be renamed underneath us: 1.7.0's
     * {@code pyrolize_oven_oc} became 1.7.0b's {@code pyrolyse_oven_oc}. Keying on a single spelling
     * means the behaviour keeps working in the dev run and stops working in the pack, silently,
     * which is strictly worse than never having written it.
     */
    public BehaviourRegistry register(MachineBehaviour behaviour, List<String> modifierIds) {
        for (String modifierId : modifierIds) {
            byModifierId.put(modifierId, behaviour);
        }
        return this;
    }

    public BehaviourRegistry registerShapeMatched(MachineBehaviour behaviour) {
        shapeMatched.add(behaviour);
        return this;
    }

    public BehaviourRegistry override(BehaviourOverride override) {
        overrides.add(override);
        return this;
    }

    public BehaviourRegistry overrides(List<BehaviourOverride> newOverrides) {
        overrides.addAll(newOverrides);
        return this;
    }

    public List<BehaviourOverride> overrides() {
        return List.copyOf(overrides);
    }

    /**
     * Every registered behaviour, id-matched and shape-matched alike.
     *
     * <p>For asking the registry about itself rather than about a machine — which structure options
     * exist at all, what is installed. Not a substitute for {@link #chainFor}: what applies to a
     * given machine is a question only the context can answer.
     */
    public List<MachineBehaviour> allBehaviours() {
        List<MachineBehaviour> all = new ArrayList<>(byModifierId.values());
        all.addAll(shapeMatched);
        return List.copyOf(all);
    }

    /**
     * Modifiers deliberately treated as no-ops, rather than ones nothing has been written for.
     *
     * <p>The distinction is the whole value of {@link #unknownModifiers}: a warning that fires on
     * modifiers MFP has already decided are irrelevant is a warning the user learns to scroll past,
     * and then the one that matters goes past with it.
     *
     * <ul>
     *   <li>{@code default_environment_requirement} — identity unless the machine is standing in a
     *       pollution zone, which is a property of where it was built and not of the plan.
     *   <li>{@code consume_eu_to_start} — a one-off charge to begin a recipe. It changes what the
     *       machine needs buffered, never its rate.
     *   <li>{@code fake_fusion_overclock} — {@code start_core}'s, and a no-op in the strongest sense
     *       available: its body is {@code return ModifierFunction.IDENTITY;}. The reflector reactors
     *       declare it beside {@code reflector_fusion_reactor}, which is the rule that does the work.
     * </ul>
     *
     * <p>The first two are in GregTech's own {@code GTRecipeModifiers.ignoreModifiers}, which is
     * where the game decides they are not worth showing the player either.
     */
    private static final Set<String> NEUTRAL_MODIFIERS = Set.of(
            "default_environment_requirement", "consume_eu_to_start", "fake_fusion_overclock");

    /**
     * Named modifiers the machine declares that nothing in this registry models.
     *
     * <p>Not the same as "no behaviours applied". A machine whose list is <em>half</em> understood is
     * the dangerous case, because the chain is non-empty and the answer therefore looks confident:
     * the Large Chemical Reactor declares
     * {@code [default_environment_requirement, chemical_reactor_oc, batch_mode]}, and while
     * {@code chemical_reactor_oc} was unimplemented MFP applied batch mode, reported EXACT, and
     * quietly ignored the machine's entire overclock — so changing the energy hatch did nothing to
     * the plan. See STATUS §14.
     *
     * <p><b>Named only</b>, and that restriction is what makes the warning worth reading.
     * {@code GtMachineCatalog} gives a modifier passed as a bare method reference the id
     * {@code lambda:<declaring class>}, which is stable but ambiguous by construction — many rules
     * share one — and says in as many words that no behaviour may match on it. A large share of
     * GregTech's single blocks carry one: the plain chemical reactor is
     * {@code [lambda:GTRecipeModifiers, oc_non_perfect]}. Flagging those would put a warning on a
     * good fraction of every plan, and a warning that common is one the user stops reading — at
     * which point the {@code chemical_reactor_oc}-shaped ones go past unread too. Anonymous
     * modifiers are the shape matcher's job; a name nothing answers to is a gap someone can close.
     */
    public List<String> unmodelledModifiers(BehaviourContext context) {
        List<String> unknown = new ArrayList<>();
        for (String modifierId : context.modifierIds()) {
            if (statusOf(modifierId) != ModifierStatus.UNMODELLED) {
                continue;
            }
            MachineBehaviour behaviour = byModifierId.get(modifierId);
            if (behaviour == null || !behaviour.appliesTo(context)) {
                unknown.add(modifierId);
            }
        }
        return unknown;
    }

    /** What this registry knows about a modifier id, before any machine or recipe is involved. */
    public enum ModifierStatus {
        /** A behaviour is registered under this id. Whether it applies is the context's business. */
        MODELLED,
        /** Deliberately a no-op: see {@link #NEUTRAL_MODIFIERS}. */
        NEUTRAL,
        /** A lambda or script rule with no name to match on; the shape matcher's job. */
        ANONYMOUS,
        /** A named rule nothing here answers to — a gap someone can close. */
        UNMODELLED
    }

    /**
     * Classify one modifier id without a machine or a recipe.
     *
     * <p>Coverage is a property of the registry and the pack, not of any one plan, so answering it
     * needs to be possible by walking the machine catalog alone — which is what {@code /mfp
     * modifiers} does. Asking the question per plan finds only the gaps a player happens to walk
     * into, and the gaps that matter most are on the machines nobody has planned with yet.
     */
    public ModifierStatus statusOf(String modifierId) {
        if (NEUTRAL_MODIFIERS.contains(modifierId)) {
            return ModifierStatus.NEUTRAL;
        }
        if (isAnonymous(modifierId)) {
            return ModifierStatus.ANONYMOUS;
        }
        return byModifierId.containsKey(modifierId) ? ModifierStatus.MODELLED : ModifierStatus.UNMODELLED;
    }

    /** Ids a behaviour is registered under, for reporting what coverage exists. */
    public Set<String> modelledIds() {
        return Set.copyOf(byModifierId.keySet());
    }

    /** Ids {@code GtMachineCatalog} minted because the modifier had no name of its own. */
    private static boolean isAnonymous(String modifierId) {
        return modifierId.startsWith("lambda:") || modifierId.startsWith("js:");
    }

    /**
     * The behaviours to fold, in application order.
     *
     * <p>An empty result means nothing is known about this machine, which the resolver turns into
     * an explicitly unknown answer rather than into "runs exactly as written".
     */
    public List<MachineBehaviour> chainFor(BehaviourContext context) {
        List<MachineBehaviour> chain = new ArrayList<>();

        for (BehaviourOverride override : overrides) {
            if (override.appliesTo(context)) {
                // An override replaces the machine's behaviour outright; composing it with the
                // built-ins it was written to correct would apply the correction twice.
                chain.add(override);
                return chain;
            }
        }

        for (String modifierId : context.modifierIds()) {
            MachineBehaviour behaviour = byModifierId.get(modifierId);
            if (behaviour != null && behaviour.appliesTo(context)) {
                chain.add(behaviour);
            }
        }

        for (MachineBehaviour behaviour : shapeMatched) {
            if (behaviour.appliesTo(context) && !chain.contains(behaviour)) {
                chain.add(behaviour);
            }
        }

        return chain;
    }
}
