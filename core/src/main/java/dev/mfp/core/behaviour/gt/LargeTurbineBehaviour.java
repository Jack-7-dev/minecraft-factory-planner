package dev.mfp.core.behaviour.gt;

import dev.mfp.core.behaviour.BehaviourContext;
import dev.mfp.core.behaviour.MachineBehaviour;
import dev.mfp.core.behaviour.OptionSpec;
import dev.mfp.core.behaviour.ThroughputResult;
import dev.mfp.core.model.Confidence;

import java.util.List;

/**
 * Large turbines: fill the turbine's voltage, then let the rotor decide the fuel cost.
 *
 * <p>Unlike a single-block generator, a large turbine always outputs its full voltage; what the
 * rotor changes is how long a cycle lasts, and therefore how much fuel that output costs. A better
 * rotor <em>lengthens</em> the cycle — the same EU/t for less fuel per second — which reads
 * backwards until you notice that duration here is a fuel-consumption term, not a speed one.
 *
 * <p>The rotor is a build choice, so without one this reports unknown rather than assuming a
 * middling rotor. A power plan sized on a guessed rotor is wrong in the direction that matters:
 * it under-orders fuel.
 */
public final class LargeTurbineBehaviour implements MachineBehaviour {

    /** Structure option holding the rotor holder's total efficiency, as a percentage. */
    public static final String OPTION_ROTOR_EFFICIENCY = "rotor_efficiency";
    /** Structure option for turbines that boost production above the base, as a multiplier. */
    public static final String OPTION_PRODUCTION_BOOST = "production_boost";

    private final String id;

    public LargeTurbineBehaviour(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean appliesTo(BehaviourContext context) {
        return context.recipe().isGenerator() && context.isMultiblock();
    }

    @Override
    public boolean appliesToMachine(dev.mfp.core.model.MfpMachine machine) {
        // Read off the name, because the real test is "does it run a generating recipe" and there is
        // no recipe here. Deliberately narrow: this decides only whether the standing-build screen
        // offers a rotor field, so a turbine this misses is one the player configures per line as
        // before, while a false positive would ask them about a rotor a machine has no holder for.
        return machine != null && machine.multiblock() && machine.id().contains("turbine");
    }

    @Override
    public List<OptionSpec> options() {
        return List.of(
                OptionSpec.integer(OPTION_ROTOR_EFFICIENCY, "Rotor efficiency (%)",
                        "The rotor holder's total efficiency, as the machine's own screen reports it.",
                        1, 400),
                OptionSpec.integer(OPTION_PRODUCTION_BOOST, "Production boost",
                        "Multiplier for turbines that produce above the base rate. 1 for a plain one.",
                        1, 64));
    }

    @Override
    public ThroughputResult apply(ThroughputResult accumulated, BehaviourContext context) {
        long recipeEut = context.recipeEut();
        if (recipeEut <= 0) {
            return accumulated;
        }
        if (!context.hasMachineVoltage()) {
            return accumulated.degrade(Confidence.UNKNOWN,
                    "no turbine tier chosen, so its output is not modelled");
        }

        long turbineVoltage = context.machineVoltage();
        long recipeVoltage = context.recipe().euOut();
        if (turbineVoltage <= recipeVoltage) {
            return accumulated.cancel("turbine voltage " + turbineVoltage
                    + " does not exceed the fuel's " + recipeVoltage + " EU/t");
        }

        int efficiencyPercent = context.intOption(OPTION_ROTOR_EFFICIENCY, -1);
        if (efficiencyPercent <= 0) {
            return accumulated.degrade(Confidence.UNKNOWN,
                    "no rotor configured, so fuel consumption is not modelled (set the '"
                            + OPTION_ROTOR_EFFICIENCY + "' option, in percent)");
        }

        // Ceiling, as GregTech does, so the turbine actually reaches its rated output.
        long parallels = turbineVoltage / recipeEut;
        if (turbineVoltage % recipeEut != 0) {
            parallels++;
        }

        double boost = context.intOption(OPTION_PRODUCTION_BOOST, 1);
        double eutMultiplier = boost * (double) turbineVoltage / recipeVoltage;
        double durationMultiplier = efficiencyPercent / 100.0;

        return accumulated
                .andThen(durationMultiplier, eutMultiplier, parallels, 0)
                .degrade(Confidence.APPROXIMATE,
                        "assumes the turbine runs continuously at " + efficiencyPercent + "% rotor efficiency");
    }
}
