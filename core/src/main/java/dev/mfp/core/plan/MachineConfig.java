package dev.mfp.core.plan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The user's choice of how a line's recipe is actually run.
 *
 * <p>Separate from {@code MfpMachine} because one machine definition supports many configurations:
 * the same Electric Blast Furnace runs at different tiers, with different coils, at different
 * parallel counts.
 *
 * <p>{@code structureOptions} is an open map rather than named fields on purpose. In the target
 * pack, throughput depends on what the player physically built inside the multiblock — coil tier,
 * vacuum pump tier, threading stat blocks, hell forge temperature. Those vary per machine and come
 * from a third-party mod, so enumerating them here would mean editing this class every time the
 * pack adds a multiblock.
 *
 * @param machineId        id of the chosen machine, or null when none has been picked yet
 * @param tier             voltage tier the machine runs at, or -1 when untiered
 * @param parallels        how many recipes run at once; at least 1
 * @param limit            cap on machine count, or null for unlimited
 * @param forceLimit       run exactly {@code limit} machines rather than at most that many
 * @param structureOptions machine-specific build choices that affect throughput
 */
public record MachineConfig(
        String machineId,
        int tier,
        int parallels,
        Double limit,
        boolean forceLimit,
        Map<String, Object> structureOptions) {

    /** No machine chosen: the recipe runs exactly as written. */
    public static final MachineConfig UNSET = new MachineConfig(null, -1, 1, null, false, Map.of());

    public MachineConfig {
        if (parallels < 1) {
            throw new IllegalArgumentException("parallels must be at least 1: " + parallels);
        }
        if (limit != null && (limit < 0 || !Double.isFinite(limit))) {
            throw new IllegalArgumentException("limit must be finite and non-negative: " + limit);
        }
        structureOptions = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(structureOptions, "structureOptions")));
    }

    public static MachineConfig of(String machineId, int tier) {
        return new MachineConfig(machineId, tier, 1, null, false, Map.of());
    }

    /** The same build on a different machine, keeping the user's other settings. */
    public MachineConfig withMachine(String newMachineId, int newTier) {
        return new MachineConfig(newMachineId, newTier, parallels, limit, forceLimit, structureOptions);
    }

    public MachineConfig withTier(int newTier) {
        return new MachineConfig(machineId, newTier, parallels, limit, forceLimit, structureOptions);
    }

    /** Remove a cap entirely, which {@link #withLimit} cannot express. */
    public MachineConfig withoutLimit() {
        return new MachineConfig(machineId, tier, parallels, null, false, structureOptions);
    }

    /** Drop a structure option, so the behaviour falls back to its documented default. */
    public MachineConfig withoutOption(String key) {
        Map<String, Object> options = new LinkedHashMap<>(structureOptions);
        options.remove(key);
        return new MachineConfig(machineId, tier, parallels, limit, forceLimit, options);
    }

    public MachineConfig withParallels(int newParallels) {
        return new MachineConfig(machineId, tier, newParallels, limit, forceLimit, structureOptions);
    }

    /** Cap the number of machines, e.g. "I only have four of these". */
    public MachineConfig withLimit(double newLimit, boolean force) {
        return new MachineConfig(machineId, tier, parallels, newLimit, force, structureOptions);
    }

    public MachineConfig withOption(String key, Object value) {
        Map<String, Object> options = new LinkedHashMap<>(structureOptions);
        options.put(key, value);
        return new MachineConfig(machineId, tier, parallels, limit, forceLimit, options);
    }

    public boolean hasLimit() {
        return limit != null;
    }
}
