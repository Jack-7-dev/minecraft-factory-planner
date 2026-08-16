package dev.mfp.core.solver;

import dev.mfp.core.behaviour.BehaviourChain;
import dev.mfp.core.behaviour.BehaviourContext;
import dev.mfp.core.behaviour.BehaviourRegistry;
import dev.mfp.core.behaviour.MachineBehaviour;
import dev.mfp.core.behaviour.MachineLookup;
import dev.mfp.core.behaviour.ThroughputResult;
import dev.mfp.core.model.Confidence;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.MachineConfig;

import java.util.List;

/**
 * The real throughput resolver: folds a machine's behaviours and turns the result into a rate.
 *
 * <p>Replaces {@code BaseThroughputResolver} for any plan that has chosen machines. The solver is
 * unchanged by this — it still asks for crafts per second and gets an answer with a confidence
 * attached, which is exactly what the seam was for.
 *
 * <p>The arithmetic is short but every term earns its place:
 *
 * <pre>
 * duration = max(1, recipeDuration × durationMultiplier)      one tick is the floor a machine cannot beat
 * cps      = 20 / duration × contentMultiplier × parallels    content and duration are separate quantities
 * EU/s     = recipeEUt × eutMultiplier × 20 × parallels       EU/t already includes behaviour parallelism
 * steam/s  = EU/s × steamPerEu                                for the machines that burn steam, whose EU draw is then zero
 * </pre>
 *
 * <p>{@code MachineConfig.parallels} is the user saying "run this many at once" and multiplies both
 * throughput and power. It is deliberately <em>not</em> the same thing as a parallel hatch, which a
 * behaviour reads from {@code structureOptions}; keeping them separate is what stops a configured
 * hatch from being counted twice.
 */
public final class BehaviourThroughputResolver implements ThroughputResolver {

    private static final double TICKS_PER_SECOND = 20.0;

    private final BehaviourRegistry registry;
    private final MachineLookup machines;

    public BehaviourThroughputResolver(BehaviourRegistry registry, MachineLookup machines) {
        this.registry = registry;
        this.machines = machines == null ? MachineLookup.NONE : machines;
    }

    @Override
    public Throughput resolve(MfpRecipe recipe, MachineConfig config) {
        if (!recipe.hasRate()) {
            return Throughput.NO_RATE;
        }

        ThroughputResult result = resolveResult(recipe, config);
        MachineConfig effective = config == null ? MachineConfig.UNSET : config;

        if (result.cancelled()) {
            // Zero crafts per second, so the line idles and the reason travels with it.
            return new Throughput(0, 0, 0, 0, 0, 0, Confidence.UNKNOWN,
                    "cannot run: " + result.cancelReason());
        }

        int parallels = effective.parallels();
        double duration = result.durationTicks(recipe.durationTicks());
        double craftsPerSecond = TICKS_PER_SECOND / duration * result.contentMultiplier() * parallels;

        long amperage = Math.max(1L, recipe.amperage());
        double eutIn = result.eut((double) recipe.euIn() * amperage);
        double eutOut = result.eut((double) recipe.euOut() * amperage);
        double drawPerSecond = eutIn * TICKS_PER_SECOND * parallels;

        // A steam machine's draw is the same number in different units, so it is converted rather
        // than added: reporting both would double-count the one cost the machine actually has.
        double steamPerSecond = result.isSteamPowered() ? drawPerSecond * result.steamPerEu() : 0;

        return new Throughput(
                craftsPerSecond,
                result.isSteamPowered() ? 0 : drawPerSecond,
                eutOut * TICKS_PER_SECOND * parallels,
                0,
                steamPerSecond,
                result.overclocks(),
                result.confidence(),
                result.note());
    }

    /** The folded behaviour result, exposed so {@code /mfp explain} can show the working. */
    public ThroughputResult resolveResult(MfpRecipe recipe, MachineConfig config) {
        BehaviourContext context = contextFor(recipe, config);
        List<MachineBehaviour> chain = registry.chainFor(context);
        return BehaviourChain.fold(chain, context);
    }

    /** The behaviours that will be applied, in order — the other half of an explanation. */
    public List<MachineBehaviour> chainFor(MfpRecipe recipe, MachineConfig config) {
        return registry.chainFor(contextFor(recipe, config));
    }

    public BehaviourContext contextFor(MfpRecipe recipe, MachineConfig config) {
        MachineConfig effective = config == null ? MachineConfig.UNSET : config;
        MfpMachine machine = effective.machineId() == null ? null : machines.machine(effective.machineId());
        return BehaviourContext.of(recipe, machine, effective);
    }

    public BehaviourRegistry registry() {
        return registry;
    }
}
