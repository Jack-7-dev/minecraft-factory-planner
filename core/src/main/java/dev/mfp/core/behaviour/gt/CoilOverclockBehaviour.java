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
 * The Electric Blast Furnace: heating-coil overclocking plus a temperature discount.
 *
 * <p>Three things happen, in this order:
 *
 * <ol>
 *   <li>the recipe is <b>rejected</b> outright if the coils are colder than it needs, or if it
 *       wants a higher voltage tier than the machine has;
 *   <li>EU/t is discounted by 5% per 900 K of surplus — but only for recipes that themselves need
 *       at least 900 K, a guard that is easy to miss and makes every low-tier blast recipe cheaper
 *       than it should be if forgotten;
 *   <li>overclocking runs with the surplus spent on <em>perfect</em> overclocks first.
 * </ol>
 *
 * <p>The machine's effective temperature is the coil's plus 100 K for every tier above MV, so the
 * energy hatch the player chose changes what the furnace can smelt, not just how fast.
 */
public final class CoilOverclockBehaviour implements MachineBehaviour {

    /** Recipe metadata key holding the required temperature, as ingested from GregTech. */
    public static final String EBF_TEMP = "gtceu:ebf_temp";

    private static final String MODIFIER_ID = "ebf_oc";

    @Override
    public String id() {
        return MODIFIER_ID;
    }

    @Override
    public boolean appliesTo(BehaviourContext context) {
        return context.hasModifier(MODIFIER_ID);
    }

    @Override
    public List<OptionSpec> options() {
        // Both keys are offered because the name table is a convenience and the temperature is the
        // contract: a pack coil this table has never heard of is still describable in kelvin.
        return List.of(
                OptionSpec.choice(GtCoils.OPTION_COIL, "Coil",
                        "The heating coil built into the machine. Sets the temperature the recipe "
                                + "is checked against, and every 900 K of surplus is a perfect overclock.",
                        List.copyOf(GtCoils.names())),
                OptionSpec.integer(GtCoils.OPTION_COIL_TEMPERATURE, "Coil temperature (K)",
                        "The coil's temperature in kelvin, for a coil the name table does not know. "
                                + "Overrides the named coil.", 0, 30000));
    }

    @Override
    public ThroughputResult apply(ThroughputResult accumulated, BehaviourContext context) {
        if (!context.hasRecipeExtra(EBF_TEMP)) {
            // GregTech refuses a blast furnace recipe with no temperature at all.
            return accumulated.cancel("recipe declares no blast furnace temperature");
        }
        int recipeTemp = context.recipeExtra(EBF_TEMP, 0);

        int machineTemp = machineTemperature(context);
        if (machineTemp < 0) {
            return accumulated.degrade(Confidence.UNKNOWN,
                    "no coil chosen, so neither the temperature discount nor the perfect overclocks "
                            + "are modelled (set the '" + GtCoils.OPTION_COIL + "' option)");
        }
        if (recipeTemp > machineTemp) {
            return accumulated.cancel("recipe needs " + recipeTemp + " K but the coils reach only "
                    + machineTemp + " K");
        }

        long recipeEut = context.recipeEut();
        if (recipeEut <= 0) {
            return accumulated;
        }

        long recipeVoltage = context.recipe().euIn() > 0 ? context.recipe().euIn() : context.recipe().euOut();
        int voltageTier = GtTiers.tierByVoltage(recipeVoltage);
        if (voltageTier > context.machineTier()) {
            return accumulated.cancel("recipe needs tier " + GtTiers.name(voltageTier)
                    + " but the machine is " + GtTiers.name(context.machineTier()));
        }

        double discount = OverclockMaths.coilEutDiscount(recipeTemp, machineTemp);
        ThroughputResult discounted = accumulated.andThen(1.0, discount, 1.0, 0);

        if (!context.hasMachineVoltage()) {
            return discounted.degrade(Confidence.UNKNOWN,
                    "no machine tier chosen, so no overclock is modelled");
        }

        long maxVoltage = context.machineVoltage();
        double currentEut = discounted.eut(recipeEut);
        // Truncated, not rounded: GregTech hands the overclock loop an int duration that a previous
        // modifier already truncated, and 3.9 ticks going in as 4 rather than 3 can buy an
        // overclock the machine does not actually get.
        int currentDuration = (int) Math.max(1, Math.floor(
                discounted.durationTicks(context.recipe().durationTicks())));

        int overclocks = OverclockMaths.overclockCount((long) currentEut, maxVoltage);
        if (overclocks <= 0) {
            return discounted;
        }

        OverclockMaths.Result result = OverclockMaths.heatingCoil((long) currentEut, currentDuration,
                overclocks, overclocks, maxVoltage, recipeTemp, machineTemp,
                OverclockMaths.subTickParallelCeiling(currentDuration, overclocks));

        ThroughputResult folded = discounted.andThen(result.durationMultiplier(), result.eutMultiplier(),
                result.parallels(), result.baseOverclocks());

        int perfect = Math.min(result.overclocks(),
                OverclockMaths.coilDiscountSteps(recipeTemp, machineTemp) / 2);
        if (perfect > 0) {
            return folded.degrade(Confidence.EXACT,
                    perfect + " of " + result.overclocks() + " overclocks are perfect at " + machineTemp + " K");
        }
        return folded;
    }

    /**
     * The furnace's effective temperature, or -1 when the plan has not said what the coils are.
     *
     * <p>A raw temperature wins over a coil name so a pack that adds its own coils still works
     * without MFP having to know about them.
     */
    private static int machineTemperature(BehaviourContext context) {
        int explicit = context.intOption(GtCoils.OPTION_COIL_TEMPERATURE, -1);
        int coilTemp = explicit >= 0 ? explicit : GtCoils.temperatureOf(coilName(context));
        if (coilTemp < 0) {
            return -1;
        }
        int tier = context.machineTier();
        int bonus = tier > GtTiers.MV ? 100 * (tier - GtTiers.MV) : 0;
        return coilTemp + bonus;
    }

    private static String coilName(BehaviourContext context) {
        Object value = context.options().get(GtCoils.OPTION_COIL);
        return value == null ? null : String.valueOf(value);
    }
}
