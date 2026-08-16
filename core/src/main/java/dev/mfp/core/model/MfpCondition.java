package dev.mfp.core.model;

import java.util.Objects;

/**
 * A gate on whether a recipe is runnable — research, a cleanroom, a dimension, a quest.
 *
 * <p>MFP records conditions but does not evaluate them (plan §15). That is a deliberate scope
 * choice, not an oversight: evaluating "has the player done this research?" needs live player state
 * that a planner does not have. The consequence is that a plan may propose recipes the player
 * cannot run yet, so conditions must be <em>surfaced</em> in the UI rather than dropped silently
 * (plan P4).
 *
 * <p>{@code description} is kept human-readable so an unrecognised condition type still tells the
 * user something useful instead of vanishing.
 *
 * @param type        stable identifier, e.g. {@code gtceu:research} or {@code gtceu:cleanroom}
 * @param description human-readable summary for display
 * @param negated     whether the condition must be false rather than true
 */
public record MfpCondition(String type, String description, boolean negated) {

    public MfpCondition {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(description, "description");
        if (type.isEmpty()) {
            throw new IllegalArgumentException("type must not be empty");
        }
    }

    public static MfpCondition of(String type, String description) {
        return new MfpCondition(type, description, false);
    }
}
