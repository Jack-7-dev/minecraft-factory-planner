package dev.mfp.core.behaviour.startcore;

import dev.mfp.core.behaviour.BehaviourContext;
import dev.mfp.core.behaviour.MachineBehaviour;
import dev.mfp.core.behaviour.ThroughputResult;
import dev.mfp.core.model.Confidence;

/**
 * Star-Technology's {@code throughput_boosting}, carried by roughly fifteen {@code super_*} multis.
 *
 * <p>Four crafts at once over 1.6 times the duration, for 5% less power: net throughput ×2.5 and
 * energy per item down by a fifth. Decoded from {@code StarTRecipeModifiers.throughputBoosting}.
 *
 * <p>The boost is all-or-nothing — {@code start_core} checks that four parallels are actually
 * available and falls back to the unmodified recipe otherwise. A planner assumes an unbounded input
 * supply, so the boost always applies here; that assumption is the gap between what MFP says and
 * what an under-fed machine does, and it is stated on every result rather than left implicit.
 */
public final class ThroughputBoostingBehaviour implements MachineBehaviour {

    public static final String ID = "throughput_boosting";

    private static final int PARALLELS = 4;
    private static final double DURATION_MULTIPLIER = 1.6;
    private static final double EUT_MULTIPLIER = 0.95;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean appliesTo(BehaviourContext context) {
        return context.hasModifier(ID);
    }

    @Override
    public ThroughputResult apply(ThroughputResult accumulated, BehaviourContext context) {
        return accumulated
                .andThen(DURATION_MULTIPLIER, EUT_MULTIPLIER, PARALLELS, 0)
                .degrade(Confidence.APPROXIMATE,
                        "throughput boosting assumes " + PARALLELS
                                + " parallels are always available, which needs the machine kept fed");
    }
}
