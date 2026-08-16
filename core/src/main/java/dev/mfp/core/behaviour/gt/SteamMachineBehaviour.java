package dev.mfp.core.behaviour.gt;

import dev.mfp.core.behaviour.BehaviourContext;
import dev.mfp.core.behaviour.GtTiers;
import dev.mfp.core.behaviour.MachineBehaviour;
import dev.mfp.core.behaviour.ThroughputResult;

/**
 * GregTech's single-block steam machines: half speed unless high pressure, and nothing above LV.
 *
 * <p>{@code SimpleSteamMachine.recipeModifier} is short — reject anything above LV, then double the
 * duration for a low-pressure machine — but it covers the whole of the early game, which is where a
 * planner gets used most. Without it every steam line reads as an unknown, which is honest but not
 * helpful when the rule is this simple.
 *
 * <p>Matched on the machine id, because GregTech attaches this rule with a bare method reference and
 * the id it reports is a per-launch lambda name. GregTech's own naming is {@code lp_steam_*} and
 * {@code hp_steam_*}, so that is what this looks for; a pack that renames its steam machines falls
 * through to the unknown default, which is where it would have been anyway.
 */
public final class SteamMachineBehaviour implements MachineBehaviour {

    private static final String LOW_PRESSURE_PREFIX = "lp_steam_";
    private static final String HIGH_PRESSURE_PREFIX = "hp_steam_";

    @Override
    public String id() {
        return "steam_machine";
    }

    @Override
    public boolean appliesTo(BehaviourContext context) {
        return !context.isMultiblock() && pathOf(context) != null;
    }

    @Override
    public ThroughputResult apply(ThroughputResult accumulated, BehaviourContext context) {
        int recipeTier = context.recipeTier();
        if (recipeTier > GtTiers.LV) {
            return accumulated.cancel("steam machines cannot run a tier "
                    + GtTiers.name(recipeTier) + " recipe");
        }

        String path = pathOf(context);
        boolean highPressure = path != null && path.startsWith(HIGH_PRESSURE_PREFIX);

        // A steam machine buys its EU with steam, at a rate its own SteamEnergyRecipeHandler fixes:
        // 1 mB per EU for bronze, 2 for steel. The recipe stays in EU/t either way; only the units
        // the player has to supply change, which is the difference between "run a cable here" and
        // "run a pipe here".
        ThroughputResult steamPowered = accumulated.asSteam(highPressure ? 2.0 : 1.0);
        if (highPressure) {
            // High pressure runs at the recipe's own speed.
            return steamPowered;
        }
        return steamPowered.andThen(2.0, 1.0, 1.0, 0);
    }

    /** The machine id's path when it names a steam machine, or null. */
    private static String pathOf(BehaviourContext context) {
        String id = context.machineId();
        if (id == null) {
            return null;
        }
        int colon = id.indexOf(':');
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        return path.startsWith(LOW_PRESSURE_PREFIX) || path.startsWith(HIGH_PRESSURE_PREFIX)
                ? path
                : null;
    }
}
