package dev.mfp.core.behaviour.gt;

import dev.mfp.core.behaviour.BehaviourContext;
import dev.mfp.core.behaviour.GtTiers;
import dev.mfp.core.behaviour.MachineBehaviour;
import dev.mfp.core.behaviour.OptionSpec;
import dev.mfp.core.behaviour.OverclockMaths;
import dev.mfp.core.behaviour.ThroughputResult;
import dev.mfp.core.model.Confidence;

import java.util.List;

/**
 * The fusion reactors, whose overclock is unlike every other machine's.
 *
 * <p>An ordinary overclock buys half the duration for four times the power. Fusion buys half the
 * duration for <em>twice</em> the power, so running a fusion line above its recipe tier is close to
 * free — and modelling it with the usual factor would report a plasma line drawing four times what
 * it does at the top end, which is where every fusion recipe in this pack sits.
 *
 * <p>Two variants, because {@code start_core} rebuilt the machine:
 *
 * <ul>
 *   <li>{@code fusion_overclock} — GregTech's. Overclocks up to the reactor's own voltage.
 *   <li>{@code reflector_fusion_reactor} — the pack's. Same arithmetic, but the reflector built into
 *       the structure raises the ceiling: a reflector better than the recipe demands lifts the
 *       overclock voltage a full tier per surplus step, so the reflector is a throughput choice and
 *       not merely a gate.
 * </ul>
 *
 * <p><b>What this does not model.</b> A fusion recipe also has to fit the reactor's energy buffer —
 * GregTech refuses it when {@code eu_to_start} exceeds the capacity, which depends on how many
 * energy hatches were built in. That is a structure question with no throughput consequence when it
 * passes and a total one when it fails, so it is left to the player rather than guessed at; the tier
 * gate, which is the one that actually varies across a plan, is enforced.
 */
public final class FusionOverclockBehaviour implements MachineBehaviour {

    /** Structure option naming the reflector tier built into a {@code start_core} reactor. */
    public static final String OPTION_REFLECTOR = "reflector_tier";

    /** Recipe metadata: the reflector tier this recipe demands. */
    private static final String RECIPE_REFLECTOR_TIER = "gtceu:reflector_tier";

    private final String modifierId;
    private final boolean reflector;

    private FusionOverclockBehaviour(String modifierId, boolean reflector) {
        this.modifierId = modifierId;
        this.reflector = reflector;
    }

    /** GregTech's own fusion reactors, LuV through UV. */
    public static FusionOverclockBehaviour gregTech() {
        return new FusionOverclockBehaviour("fusion_overclock", false);
    }

    /** {@code start_core}'s reflector reactors, LuV through UIV. */
    public static FusionOverclockBehaviour reflector() {
        return new FusionOverclockBehaviour("reflector_fusion_reactor", true);
    }

    @Override
    public String id() {
        return modifierId;
    }

    @Override
    public boolean appliesTo(BehaviourContext context) {
        return context.hasModifier(modifierId);
    }

    @Override
    public List<OptionSpec> options() {
        if (!reflector) {
            return List.of();
        }
        return List.of(OptionSpec.integer(OPTION_REFLECTOR, "Reflector tier",
                "The reflector built into the reactor. Every tier above what the recipe demands"
                        + " raises the overclock voltage by a full tier, so a better reflector is"
                        + " worth as much as a better reactor.",
                0, 10));
    }

    @Override
    public ThroughputResult apply(ThroughputResult accumulated, BehaviourContext context) {
        long recipeEut = context.recipeEut();
        if (recipeEut <= 0) {
            return accumulated;
        }

        // GregTech's gate is on the recipe's bare voltage against the reactor's tier, not on the
        // amperage-inflated figure the overclock is then computed from.
        long recipeVoltage = context.recipe().euIn() > 0 ? context.recipe().euIn() : context.recipe().euOut();
        int recipeTier = GtTiers.tierByVoltage(recipeVoltage);
        int machineTier = context.machineTier();
        if (machineTier < 0) {
            return accumulated.degrade(Confidence.UNKNOWN,
                    "no reactor tier chosen, so the fusion overclock is unknown");
        }
        if (recipeTier > machineTier) {
            return accumulated.cancel("this recipe needs a " + GtTiers.name(recipeTier)
                    + " fusion reactor and this one is " + GtTiers.name(machineTier));
        }

        ThroughputResult result = accumulated;
        long maxVoltage = GtTiers.voltage(machineTier);

        if (reflector) {
            int recipeReflector = context.recipeExtra(RECIPE_REFLECTOR_TIER, -1);
            int builtReflector = context.intOption(OPTION_REFLECTOR, -1);
            if (recipeReflector < 0) {
                // GregTech returns NULL here — a recipe with no reflector_tier simply does not run
                // in a reflector reactor. Refusing is the honest answer and keeps the plan from
                // routing plasma through a machine that would sit idle.
                return result.cancel("this recipe declares no reflector tier, so a reflector"
                        + " reactor cannot run it");
            }
            if (builtReflector < 0) {
                // The floor: a reflector exactly matching the recipe, which is the weakest one that
                // runs it at all. Errs towards too many machines, and says so.
                builtReflector = recipeReflector;
                result = result.degrade(Confidence.APPROXIMATE,
                        "no reflector chosen, so the weakest one that runs this recipe is assumed"
                                + " (set the '" + OPTION_REFLECTOR + "' option)");
            }
            if (builtReflector < recipeReflector) {
                return result.cancel("this recipe needs reflector tier " + recipeReflector
                        + " and the reactor has " + builtReflector);
            }
            int surplus = builtReflector - recipeReflector;
            maxVoltage = GtTiers.voltage(Math.min(GtTiers.MAX, machineTier + surplus));
        }

        int overclocks = OverclockMaths.overclockCount(recipeEut, maxVoltage);
        if (overclocks <= 0) {
            return result;
        }

        int duration = (int) Math.max(1, Math.floor(
                result.durationTicks(context.recipe().durationTicks())));
        OverclockMaths.Result fusion = OverclockMaths.standard(recipeEut, duration, overclocks,
                overclocks, maxVoltage, OverclockMaths.FUSION_DURATION_FACTOR,
                OverclockMaths.FUSION_VOLTAGE_FACTOR);

        return result.andThen(fusion.durationMultiplier(), fusion.eutMultiplier(), 1.0,
                fusion.overclocks());
    }
}
