package dev.mfp.core.behaviour.gt;

import dev.mfp.core.behaviour.BehaviourContext;
import dev.mfp.core.behaviour.MachineBehaviour;
import dev.mfp.core.behaviour.ThroughputResult;

/**
 * Batch mode, which is throughput-neutral and therefore does nothing here.
 *
 * <p>Worth a class rather than an omission. GregTech's batch mode multiplies inputs, outputs
 * <em>and</em> duration by the same factor, so a machine running sixteen crafts over sixteen times
 * the duration produces at exactly the rate it did before. It exists to cut per-craft overhead in
 * the running game, not to make anything faster.
 *
 * <p>Modelling it explicitly means {@code /mfp explain} can say "batch mode: no effect on rate"
 * instead of leaving a declared modifier unaccounted for, which would look like a gap in the model.
 * It also stops the machine from falling through to the unknown-behaviour default merely because one
 * of its modifiers was unrecognised.
 */
public final class BatchModeBehaviour implements MachineBehaviour {

    private static final String MODIFIER_ID = "batch_mode";

    @Override
    public String id() {
        return MODIFIER_ID;
    }

    @Override
    public boolean appliesTo(BehaviourContext context) {
        return context.hasModifier(MODIFIER_ID);
    }

    @Override
    public ThroughputResult apply(ThroughputResult accumulated, BehaviourContext context) {
        return accumulated;
    }
}
