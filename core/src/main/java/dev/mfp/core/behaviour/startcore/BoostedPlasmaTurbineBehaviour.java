package dev.mfp.core.behaviour.startcore;

import dev.mfp.core.behaviour.BehaviourContext;
import dev.mfp.core.behaviour.MachineBehaviour;
import dev.mfp.core.behaviour.OptionSpec;
import dev.mfp.core.behaviour.ThroughputResult;
import dev.mfp.core.behaviour.gt.LargeTurbineBehaviour;
import dev.mfp.core.model.Confidence;

import java.util.List;

/**
 * Star-Technology's boosted plasma turbine: GregTech's large turbine, then a flat output bonus.
 *
 * <p>{@code BoostedPlasmaTurbine.recipeModifier} literally composes
 * {@code LARGE_TURBINE.getModifier(...).andThen(turbine.getModifierFunction())}, where the second
 * half is nothing but an EU/t multiplier. This class is the same composition, and it is the
 * clearest demonstration of why behaviours had to be chainable rather than a single lookup per
 * machine: the pack builds its multiblocks by extending GregTech's, not by replacing them.
 *
 * <p>The bonus depends on which boosting the turbine currently has running, which is a live machine
 * state rather than a build choice. Unset means unboosted, so the plan reports the turbine's floor
 * rather than a figure it only reaches while boosted.
 */
public final class BoostedPlasmaTurbineBehaviour implements MachineBehaviour {

    public static final String ID = "boosted_plasma_turbine";

    /** Structure option holding the turbine's output bonus, as a multiplier. */
    public static final String OPTION_BONUS = "turbine_bonus";

    private final LargeTurbineBehaviour turbine = new LargeTurbineBehaviour(ID + "/large_turbine");

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
        return List.of(OptionSpec.integer(OPTION_BONUS, "Turbine bonus",
                "The turbine's output multiplier above the base rate.", 1, 64));
    }

    @Override
    public ThroughputResult apply(ThroughputResult accumulated, BehaviourContext context) {
        ThroughputResult base = turbine.apply(accumulated, context);
        if (base.cancelled()) {
            return base;
        }

        int bonus = context.intOption(OPTION_BONUS, 1);
        if (bonus <= 1) {
            return base.degrade(Confidence.APPROXIMATE,
                    "turbine boosting not configured, so the unboosted output is reported (set the '"
                            + OPTION_BONUS + "' option)");
        }
        return base.andThen(1.0, bonus, 1.0, 0);
    }
}
