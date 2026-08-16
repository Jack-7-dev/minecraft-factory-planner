package dev.mfp.core.solver;

import dev.mfp.core.model.Confidence;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.MachineConfig;

/**
 * Runs a recipe exactly as written: no overclocking, no coil bonuses, no recipe modifiers.
 *
 * <p>Honest about its limits rather than silently plausible (plan P5). It is {@link Confidence#EXACT}
 * for what it models — a recipe executed unmodified — but the moment the configuration implies
 * something it does not understand, such as a machine tier above what the recipe needs, it drops to
 * {@link Confidence#APPROXIMATE} and says why. In GregTech that tier difference is not a detail: a
 * recipe run two tiers up is four times faster and sixteen times more power-hungry.
 */
final class BaseThroughputResolver implements ThroughputResolver {

    /** Minecraft runs at twenty ticks per second. */
    private static final double TICKS_PER_SECOND = 20.0;

    @Override
    public Throughput resolve(MfpRecipe recipe, MachineConfig machine) {
        if (!recipe.hasRate()) {
            return Throughput.NO_RATE;
        }

        int parallels = machine == null ? 1 : machine.parallels();
        double craftsPerSecond = TICKS_PER_SECOND / recipe.durationTicks() * parallels;

        long amperage = Math.max(1L, recipe.amperage());
        double euIn = recipe.euIn() * amperage * TICKS_PER_SECOND * parallels;
        double euOut = recipe.euOut() * amperage * TICKS_PER_SECOND * parallels;

        Throughput throughput = Throughput.exact(craftsPerSecond, euIn, euOut);

        if (machine != null && recipe.euIn() > 0 && machine.tier() > recipe.minTier()
                && recipe.minTier() >= 0) {
            return throughput.withConfidence(Confidence.APPROXIMATE,
                    "machine runs at tier " + machine.tier() + " but overclocking is not modelled here");
        }
        if (machine != null && !machine.structureOptions().isEmpty()) {
            return throughput.withConfidence(Confidence.APPROXIMATE,
                    "structure options " + machine.structureOptions().keySet() + " are not modelled here");
        }
        return throughput;
    }
}
