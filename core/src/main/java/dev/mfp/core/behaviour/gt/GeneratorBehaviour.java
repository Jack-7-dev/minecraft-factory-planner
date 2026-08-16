package dev.mfp.core.behaviour.gt;

import dev.mfp.core.behaviour.BehaviourContext;
import dev.mfp.core.behaviour.MachineBehaviour;
import dev.mfp.core.behaviour.ThroughputResult;
import dev.mfp.core.model.Confidence;

/**
 * Single-block generators: burn fuel fast enough to fill the machine's voltage.
 *
 * <p>A GregTech fuel recipe states a modest output — naphtha produces 32 EU/t — and the generator
 * runs as many copies at once as its tier allows, so an HV gas turbine on a 32 EU/t fuel runs
 * sixteen parallels and outputs 512 EU/t, consuming sixteen times the fuel. Both sides scale
 * together, so fuel per EU is unchanged; what changes is how much one machine delivers, which is
 * exactly the number a power plan is built from.
 *
 * <p>Matched on the recipe and machine shape rather than on a modifier id, and that is deliberate.
 * GregTech attaches this rule with a bare method reference, so the modifier has no stable
 * identifier to match on — the id it reports embeds a per-launch address. The shape is the reliable
 * signal: a single-block, tiered machine running a recipe that produces energy.
 */
public final class GeneratorBehaviour implements MachineBehaviour {

    @Override
    public String id() {
        return "simple_generator";
    }

    @Override
    public boolean appliesTo(BehaviourContext context) {
        return context.recipe().isGenerator()
                && !context.isMultiblock()
                && context.machine() != null;
    }

    @Override
    public ThroughputResult apply(ThroughputResult accumulated, BehaviourContext context) {
        long recipeEut = context.recipeEut();
        if (recipeEut <= 0) {
            return accumulated;
        }
        if (!context.hasMachineVoltage()) {
            return accumulated.degrade(Confidence.UNKNOWN,
                    "no generator tier chosen, so a single burn per cycle is assumed");
        }

        long parallels = context.machineVoltage() / recipeEut;
        if (parallels <= 1) {
            // The fuel already fills the machine, or overfills it; GregTech runs one copy either way.
            return accumulated;
        }
        return accumulated.andThen(1.0, parallels, parallels, 0);
    }
}
