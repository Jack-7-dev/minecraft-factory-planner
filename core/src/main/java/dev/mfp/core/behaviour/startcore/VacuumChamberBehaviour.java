package dev.mfp.core.behaviour.startcore;

import dev.mfp.core.behaviour.BehaviourContext;
import dev.mfp.core.behaviour.MachineBehaviour;
import dev.mfp.core.behaviour.OptionSpec;
import dev.mfp.core.behaviour.ThroughputResult;
import dev.mfp.core.model.Confidence;

import java.util.List;

/**
 * The Vacuum Chemical Reaction Chamber: a requirement to check, not a rate to change.
 *
 * <p>{@code VacuumChemicalReactionChamberMachine} adds time to a recipe only while the chamber is
 * still pumping down to the level the recipe wants — {@code duration += (required − current) /
 * (pumpRate × 0.05)}. At steady state, which is the only state a planner cares about, the chamber
 * is already at level, the difference is zero and the modifier is the identity.
 *
 * <p>So the useful thing to model is the other branch: the recipe is <b>impossible</b> if the pump
 * cannot reach the required vacuum at all. Treating a one-off spin-up as a throughput term would
 * quietly inflate every machine count in a chemical chain; treating the pump cap as a constraint
 * catches a build that will never work.
 */
public final class VacuumChamberBehaviour implements MachineBehaviour {

    public static final String ID = "vacuum_chemical_reaction_chamber";

    /** Recipe metadata key holding the required vacuum level, as ingested from GregTech. */
    public static final String VACUUM_LEVEL = "gtceu:vacuum_level";

    /** Structure option holding the installed pump's maximum vacuum. */
    public static final String OPTION_PUMP_CAP = "vacuum_pump_cap";

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
        return List.of(OptionSpec.integer(OPTION_PUMP_CAP, "Pump vacuum cap",
                "The installed pump's maximum vacuum. Decides whether a recipe can run at all; it "
                        + "does not change the steady-state rate.", 0, 100));
    }

    @Override
    public ThroughputResult apply(ThroughputResult accumulated, BehaviourContext context) {
        int required = context.recipeExtra(VACUUM_LEVEL, 0);
        if (required <= 0) {
            return accumulated;
        }

        int pumpCap = context.intOption(OPTION_PUMP_CAP, -1);
        if (pumpCap < 0) {
            return accumulated.degrade(Confidence.APPROXIMATE,
                    "recipe needs vacuum level " + required + " but no pump was configured, so the"
                            + " requirement is unchecked (set the '" + OPTION_PUMP_CAP + "' option)");
        }
        if (pumpCap < required) {
            return accumulated.cancel("recipe needs vacuum level " + required
                    + " but the configured pump reaches only " + pumpCap);
        }

        // At level, so the pump-down term is zero and the modifier is genuinely the identity.
        return accumulated;
    }
}
