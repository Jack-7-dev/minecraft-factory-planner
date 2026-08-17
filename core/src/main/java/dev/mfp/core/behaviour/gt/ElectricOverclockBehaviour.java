package dev.mfp.core.behaviour.gt;

import dev.mfp.core.behaviour.BehaviourContext;
import dev.mfp.core.behaviour.GtTiers;
import dev.mfp.core.behaviour.MachineBehaviour;
import dev.mfp.core.behaviour.OverclockMaths;
import dev.mfp.core.behaviour.ThroughputResult;
import dev.mfp.core.model.Confidence;

/**
 * GregTech's electric overclocking, in its four flavours.
 *
 * <p>Which flavour a machine uses is a property of the machine, declared as one of the modifier ids
 * below, so one class covers all four and the registry supplies four instances.
 *
 * <ul>
 *   <li><b>non-perfect</b> — duration halves per overclock. Every ordinary electric machine.
 *   <li><b>perfect</b> — duration quarters per overclock.
 *   <li><b>sub-tick</b> variants — once duration would fall below one tick, further overclocks buy
 *       parallelism instead. Modern multiblocks use these, and ignoring the parallel half would
 *       over-state their machine counts several-fold.
 * </ul>
 *
 * <p>It reads the <em>accumulated</em> duration and EU/t rather than the recipe's own, because a
 * machine may declare a parallel hatch before its overclock and GregTech overclocks the already
 * parallelised recipe. Reading the recipe directly would compute the overclock against the wrong
 * voltage.
 */
public final class ElectricOverclockBehaviour implements MachineBehaviour {

    private final String modifierId;
    private final double durationFactor;
    private final boolean subTick;

    private ElectricOverclockBehaviour(String modifierId, double durationFactor, boolean subTick) {
        this.modifierId = modifierId;
        this.durationFactor = durationFactor;
        this.subTick = subTick;
    }

    public static ElectricOverclockBehaviour nonPerfect() {
        return new ElectricOverclockBehaviour("oc_non_perfect", OverclockMaths.STD_DURATION_FACTOR, false);
    }

    public static ElectricOverclockBehaviour perfect() {
        return new ElectricOverclockBehaviour("oc_perfect", OverclockMaths.PERFECT_DURATION_FACTOR, false);
    }

    public static ElectricOverclockBehaviour nonPerfectSubTick() {
        return new ElectricOverclockBehaviour("oc_non_perfect_subtick", OverclockMaths.STD_DURATION_FACTOR, true);
    }

    public static ElectricOverclockBehaviour perfectSubTick() {
        return new ElectricOverclockBehaviour("oc_perfect_subtick", OverclockMaths.PERFECT_DURATION_FACTOR, true);
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
    public ThroughputResult apply(ThroughputResult accumulated, BehaviourContext context) {
        long recipeEut = context.recipeEut();
        if (recipeEut <= 0) {
            // An unpowered recipe has no tier to overclock from. GregTech returns identity here too.
            return accumulated;
        }

        if (!context.hasMachineVoltage()) {
            return accumulated.degrade(Confidence.UNKNOWN,
                    "no machine tier chosen, so no overclock is modelled");
        }

        // GregTech gates on the recipe's voltage tier, not its total-EU tier.
        long recipeVoltage = context.recipe().euIn() > 0
                ? context.recipe().euIn()
                : context.recipe().euOut();
        int voltageTier = GtTiers.tierByVoltage(recipeVoltage);
        if (voltageTier > context.machineTier()) {
            return accumulated.cancel("recipe needs tier " + GtTiers.name(voltageTier)
                    + " but the machine is " + GtTiers.name(context.machineTier()));
        }

        long maxVoltage = context.machineVoltage();
        double currentEut = accumulated.eut(recipeEut);
        // Truncated, not rounded — see CoilOverclockBehaviour. GregTech's int duration is the input
        // to the loop, and rounding up can manufacture an overclock the machine never gets.
        int currentDuration = (int) Math.max(1, Math.floor(
                accumulated.durationTicks(context.recipe().durationTicks())));

        int overclocks = OverclockMaths.overclockCount((long) currentEut, maxVoltage);
        if (overclocks <= 0) {
            return accumulated;
        }

        OverclockMaths.Result result = subTick
                ? OverclockMaths.subTickParallel((long) currentEut, currentDuration, overclocks, overclocks,
                        maxVoltage, durationFactor,
                        OverclockMaths.subTickParallelCeiling(currentDuration, overclocks))
                : OverclockMaths.standard((long) currentEut, currentDuration, overclocks, overclocks,
                        maxVoltage, durationFactor);

        ThroughputResult folded = accumulated.andThen(result.durationMultiplier(), result.eutMultiplier(),
                result.parallels(), result.baseOverclocks());

        if (subTick && result.parallels() > 1) {
            // Worth surfacing: these parallels come from the overclock, not from a parallel hatch,
            // and they assume the machine is fed fast enough to keep them all busy.
            return folded.degrade(Confidence.APPROXIMATE, result.parallels()
                    + " sub-tick parallels assume the machine is fed without interruption");
        }
        return folded;
    }
}
