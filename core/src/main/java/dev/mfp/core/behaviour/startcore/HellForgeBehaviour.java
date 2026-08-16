package dev.mfp.core.behaviour.startcore;

import dev.mfp.core.behaviour.BehaviourContext;
import dev.mfp.core.behaviour.MachineBehaviour;
import dev.mfp.core.behaviour.OptionSpec;
import dev.mfp.core.behaviour.ThroughputResult;
import dev.mfp.core.behaviour.gt.CoilOverclockBehaviour;
import dev.mfp.core.model.Confidence;

import java.util.List;

/**
 * Star-Technology's Hell Forge: temperature surplus buys parallels, not overclocks.
 *
 * <p>Same input as GregTech's blast furnace — the recipe's {@code ebf_temp} — but a different
 * conversion: every 450 K of surplus doubles the number of crafts run at once, with no change to
 * duration or EU/t. Decoded from {@code StarTRecipeModifiers.hellforgeOverclock}.
 *
 * <p>That makes it an efficiency machine rather than a speed one, and the difference is large: at
 * 1350 K of surplus it runs eight crafts for the power of one cycle, where an EBF with the same
 * surplus would be quadrupling its draw instead. A recipe with no temperature at all passes through
 * untouched, unlike the EBF which rejects it.
 */
public final class HellForgeBehaviour implements MachineBehaviour {

    public static final String ID = "hell_forge_oc";

    /** Structure option holding the forge's temperature, in kelvin. */
    public static final String OPTION_TEMPERATURE = "hell_forge_temperature";

    private static final double KELVIN_PER_DOUBLING = 450.0;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean appliesTo(BehaviourContext context) {
        return context.hasModifier(ID);
    }

    @Override
    public List<OptionSpec> options() {
        return List.of(OptionSpec.integer(OPTION_TEMPERATURE, "Forge temperature (K)",
                "The temperature the forge is running at, which decides both what it can smelt and "
                        + "how many perfect overclocks it gets.", 0, 30000));
    }

    @Override
    public ThroughputResult apply(ThroughputResult accumulated, BehaviourContext context) {
        if (!context.hasRecipeExtra(CoilOverclockBehaviour.EBF_TEMP)) {
            // No temperature to work from: the forge runs the recipe as written.
            return accumulated;
        }
        int recipeTemp = context.recipeExtra(CoilOverclockBehaviour.EBF_TEMP, 0);

        int forgeTemp = context.intOption(OPTION_TEMPERATURE, -1);
        if (forgeTemp < 0) {
            return accumulated.degrade(Confidence.UNKNOWN,
                    "no hell forge temperature configured, so its parallels are not modelled (set the '"
                            + OPTION_TEMPERATURE + "' option)");
        }
        if (recipeTemp > forgeTemp) {
            return accumulated.cancel("recipe needs " + recipeTemp + " K but the forge reaches only "
                    + forgeTemp + " K");
        }

        int doublings = (int) Math.floor(Math.max(0.0, (forgeTemp - recipeTemp) / KELVIN_PER_DOUBLING));
        int parallels = (int) Math.pow(2, doublings);
        if (parallels <= 1) {
            return accumulated;
        }

        return accumulated
                .andThen(1.0, 1.0, parallels, 0)
                .degrade(Confidence.APPROXIMATE, parallels + " hell forge parallels from "
                        + (forgeTemp - recipeTemp) + " K of surplus, assuming the machine is kept fed");
    }
}
