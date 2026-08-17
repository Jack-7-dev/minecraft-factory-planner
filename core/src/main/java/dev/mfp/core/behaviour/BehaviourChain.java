package dev.mfp.core.behaviour;

import dev.mfp.core.model.Confidence;

import java.util.List;

/**
 * Folds a machine's behaviours into a single result.
 *
 * <p>A left fold, matching GregTech's {@code RecipeModifierList}: each modifier sees the recipe as
 * the previous one left it. Once a behaviour cancels, the fold stops — a recipe the machine cannot
 * run has no throughput for later modifiers to adjust, and continuing would produce numbers for an
 * arrangement that will never work.
 */
public final class BehaviourChain {

    private BehaviourChain() {}

    /**
     * Apply every behaviour in order.
     *
     * <p>An empty chain does not mean "no modifiers"; it means nothing recognised this machine. The
     * distinction matters enough to be encoded here rather than left to the caller: an unrecognised
     * machine gets the recipe as written <em>and</em> {@link Confidence#UNKNOWN}, so the number is
     * still usable but never mistaken for a fact.
     */
    public static ThroughputResult fold(List<MachineBehaviour> behaviours, BehaviourContext context) {
        if (behaviours.isEmpty()) {
            return unknownMachine(context);
        }

        ThroughputResult result = ThroughputResult.IDENTITY;
        for (MachineBehaviour behaviour : behaviours) {
            result = behaviour.apply(result, context);
            if (result.cancelled()) {
                return result;
            }
            // Once per behaviour, not once at the end. GregTech's fold writes a whole new GTRecipe
            // between modifiers and its duration is an int, so the truncation happens as many times
            // as there are modifiers — and the next behaviour reads the truncated figure, which is
            // what decides its own overclocks and whether batch mode engages at all.
            result = result.quantiseDuration(context.recipe().durationTicks());
        }
        return result;
    }

    private static ThroughputResult unknownMachine(BehaviourContext context) {
        if (context.machine() == null) {
            return ThroughputResult.IDENTITY.degrade(Confidence.UNKNOWN,
                    "no machine chosen, so the recipe is costed exactly as written");
        }
        if (context.modifierIds().isEmpty()) {
            // A machine that genuinely declares no modifiers runs recipes verbatim, which we know
            // exactly — a plain furnace, or a GregTech machine with no overclock logic.
            return ThroughputResult.IDENTITY;
        }
        return ThroughputResult.IDENTITY.degrade(Confidence.UNKNOWN,
                "no behaviour understands " + context.modifierIds()
                        + ", so the recipe is costed exactly as written");
    }
}
