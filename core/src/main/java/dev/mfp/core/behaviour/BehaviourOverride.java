package dev.mfp.core.behaviour;

import dev.mfp.core.model.Confidence;

import java.util.List;
import java.util.Objects;

/**
 * A pack- or user-supplied description of how a machine behaves, loaded from JSON.
 *
 * <p>This is the escape hatch that keeps MFP useful when it is wrong. The target pack is a fork
 * with dozens of bespoke multiblocks and more will arrive; a planner that could only be corrected by
 * shipping a new version would be perpetually a little bit out of date. An override lets a pack
 * author state the multipliers directly.
 *
 * <p>Overrides win over built-in behaviours. The plan sketched them as a fallback for machines MFP
 * does not recognise, but a rule that cannot correct a wrong built-in is not much of an override —
 * and when a built-in and a pack author disagree about the pack's own machine, the pack author is
 * the one who can check. What an override cannot do is silently pass itself off as authoritative:
 * it carries its own confidence, defaulting to approximate.
 *
 * @param machineId          machine this applies to; may end in {@code *} to match a prefix
 * @param durationMultiplier factor on duration
 * @param eutMultiplier      factor on EU/t
 * @param contentMultiplier  crafts produced per cycle
 * @param confidence         how much to trust it
 * @param note               explanation surfaced next to the numbers
 */
public record BehaviourOverride(
        String machineId,
        double durationMultiplier,
        double eutMultiplier,
        double contentMultiplier,
        Confidence confidence,
        String note) implements MachineBehaviour {

    public BehaviourOverride {
        Objects.requireNonNull(machineId, "machineId");
        Objects.requireNonNull(confidence, "confidence");
        if (machineId.isBlank()) {
            throw new IllegalArgumentException("machineId must not be blank");
        }
        if (durationMultiplier <= 0 || !Double.isFinite(durationMultiplier)) {
            throw new IllegalArgumentException("durationMultiplier must be finite and positive");
        }
        if (eutMultiplier < 0 || !Double.isFinite(eutMultiplier)) {
            throw new IllegalArgumentException("eutMultiplier must be finite and non-negative");
        }
        if (contentMultiplier <= 0 || !Double.isFinite(contentMultiplier)) {
            throw new IllegalArgumentException("contentMultiplier must be finite and positive");
        }
    }

    @Override
    public String id() {
        return "override:" + machineId;
    }

    @Override
    public boolean appliesTo(BehaviourContext context) {
        String id = context.machineId();
        if (id == null) {
            return false;
        }
        if (machineId.endsWith("*")) {
            return id.startsWith(machineId.substring(0, machineId.length() - 1));
        }
        return machineId.equals(id);
    }

    @Override
    public ThroughputResult apply(ThroughputResult accumulated, BehaviourContext context) {
        ThroughputResult folded = accumulated
                .andThen(durationMultiplier, eutMultiplier, contentMultiplier, 0);
        String reason = note != null && !note.isBlank()
                ? note
                : "configured override for " + machineId;
        return folded.degrade(confidence, reason);
    }

    /** An override that changes nothing, useful for silencing a wrong built-in. */
    public static BehaviourOverride identity(String machineId, String note) {
        return new BehaviourOverride(machineId, 1, 1, 1, Confidence.APPROXIMATE, note);
    }

    /** Whether any of {@code overrides} claims this machine. */
    public static boolean claims(List<BehaviourOverride> overrides, BehaviourContext context) {
        return overrides.stream().anyMatch(override -> override.appliesTo(context));
    }
}
