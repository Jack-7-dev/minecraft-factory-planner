package dev.mfp.core.behaviour.startcore;

import dev.mfp.core.behaviour.BehaviourContext;
import dev.mfp.core.behaviour.MachineBehaviour;
import dev.mfp.core.behaviour.ThroughputResult;
import dev.mfp.core.model.Confidence;

/**
 * Star-Technology's {@code bulk_processing}, carried by the bulk processing array.
 *
 * <p>Sixteen crafts over thirteen times the duration, with no change to EU/t: net throughput ≈1.23×
 * and energy per item down by a factor of sixteen over thirteen. Decoded from
 * {@code StarTRecipeModifiers.bulkThroughputProcessing}.
 *
 * <p>The interesting number is the power one, not the speed one — this machine is an efficiency
 * play. Like its sibling boost it is all-or-nothing on having sixteen parallels available, which a
 * planner assumes it does.
 */
public final class BulkProcessingBehaviour implements MachineBehaviour {

    public static final String ID = "bulk_processing";

    private static final int PARALLELS = 16;
    private static final double DURATION_MULTIPLIER = 13.0;

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
                .andThen(DURATION_MULTIPLIER, 1.0, PARALLELS, 0)
                .degrade(Confidence.APPROXIMATE,
                        "bulk processing assumes " + PARALLELS
                                + " parallels are always available, which needs the machine kept fed");
    }
}
