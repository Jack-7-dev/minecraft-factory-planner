package dev.mfp.core.behaviour;

import dev.mfp.core.behaviour.gt.BatchModeBehaviour;
import dev.mfp.core.behaviour.gt.CoilOverclockBehaviour;
import dev.mfp.core.behaviour.gt.ElectricOverclockBehaviour;
import dev.mfp.core.behaviour.gt.GeneratorBehaviour;
import dev.mfp.core.behaviour.gt.LargeTurbineBehaviour;
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

    /** Modifier ids the machine declares that nothing in this registry understands. */
    public List<String> unknownModifiers(BehaviourContext context) {
        List<String> unknown = new ArrayList<>();
        for (String modifierId : context.modifierIds()) {
            MachineBehaviour behaviour = byModifierId.get(modifierId);
            if (behaviour == null || !behaviour.appliesTo(context)) {
                unknown.add(modifierId);
            }
        }
        return unknown;
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
