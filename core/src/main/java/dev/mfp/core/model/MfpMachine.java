package dev.mfp.core.model;

import java.util.List;
import java.util.Objects;

/**
 * A machine that can run recipes of one or more types.
 *
 * <p>This describes the machine as the game defines it. What the <em>user</em> chose — tier, coil,
 * parallel count, how many they have — is a separate configuration object, because the same machine
 * definition supports many configurations.
 *
 * <p>{@code modifierIds} is the important field and the reason this type stays deliberately dumb.
 * The target pack's most significant multiblocks come from {@code start_core}, not GregTech, and
 * carry their own recipe modifiers ({@code throughput_boosting}, {@code threading_machine} …).
 * MFP records the modifier identifiers verbatim and resolves throughput semantics elsewhere, so an
 * unrecognised modifier degrades to a flagged assumption instead of a wrong number (plan P5).
 * Modifiers apply <em>in order</em> — GregTech composes them with {@code andThen} — so the list is
 * ordered and must stay that way.
 *
 * @param id            registry id, e.g. {@code gtceu:lv_macerator}
 * @param displayName   human-readable name
 * @param tier          voltage tier index, or {@link MfpRecipe#NO_TIER} when untiered
 * @param maxVoltage    EU/t the machine can draw, or 0 when unpowered
 * @param recipeTypeIds recipe types this machine can run
 * @param multiblock    whether the machine is a multiblock structure
 * @param modifierIds   recipe modifiers, in application order
 * @param providerId    which provider contributed this machine
 */
public record MfpMachine(
        String id,
        String displayName,
        int tier,
        long maxVoltage,
        List<String> recipeTypeIds,
        boolean multiblock,
        List<String> modifierIds,
        String providerId) {

    public MfpMachine {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(providerId, "providerId");
        if (id.isEmpty()) {
            throw new IllegalArgumentException("id must not be empty");
        }
        if (maxVoltage < 0) {
            throw new IllegalArgumentException("maxVoltage must be non-negative: " + maxVoltage);
        }
        recipeTypeIds = List.copyOf(Objects.requireNonNull(recipeTypeIds, "recipeTypeIds"));
        modifierIds = List.copyOf(Objects.requireNonNull(modifierIds, "modifierIds"));
    }

    /** An unpowered, untiered machine such as a furnace or a crafting table. */
    public static MfpMachine simple(String id, String displayName, String recipeTypeId, String providerId) {
        return new MfpMachine(id, displayName, MfpRecipe.NO_TIER, 0L,
                List.of(recipeTypeId), false, List.of(), providerId);
    }

    public boolean isPowered() {
        return maxVoltage > 0;
    }

    public boolean runs(String recipeTypeId) {
        return recipeTypeIds.contains(recipeTypeId);
    }
}
