package dev.mfp.core.behaviour.gt;

import dev.mfp.core.behaviour.BehaviourContext;
import dev.mfp.core.behaviour.MachineBehaviour;
import dev.mfp.core.behaviour.OptionSpec;
import dev.mfp.core.behaviour.ThroughputResult;
import dev.mfp.core.model.Confidence;

import java.util.List;

/**
 * A parallel hatch: run N copies of the recipe at once, for N times the power.
 *
 * <p>Throughput scales with N and so does EU/t, so energy per item is unchanged — a parallel hatch
 * buys throughput per machine, not efficiency. It is applied before the machine's overclock (that
 * is the order GregTech declares), which means the overclock then sees the parallelised EU/t and
 * may reach a different tier.
 *
 * <p>How many parallels a hatch supplies is a build choice, so it comes from
 * {@code structureOptions} rather than the machine definition. Unset means one — the honest
 * reading, since a hatch that has not been described cannot be assumed to be a good one — and the
 * result says so.
 */
public final class ParallelHatchBehaviour implements MachineBehaviour {

    /** Structure option holding the hatch's parallel count. */
    public static final String OPTION_PARALLELS = "parallel_hatch";

    private final String modifierId;

    public ParallelHatchBehaviour(String modifierId) {
        this.modifierId = modifierId;
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
        return List.of(OptionSpec.integer(OPTION_PARALLELS, "Parallel hatch",
                "How many recipes the installed parallel hatch runs at once. Both throughput and "
                        + "EU/t scale with it.", 1, 1024));
    }

    @Override
    public ThroughputResult apply(ThroughputResult accumulated, BehaviourContext context) {
        int parallels = context.intOption(OPTION_PARALLELS, 1);
        if (parallels <= 1) {
            return accumulated.degrade(Confidence.APPROXIMATE,
                    "machine takes a parallel hatch but none was configured, so one parallel is assumed"
                            + " (set the '" + OPTION_PARALLELS + "' option)");
        }
        return accumulated.andThen(1.0, parallels, parallels, 0);
    }
}
