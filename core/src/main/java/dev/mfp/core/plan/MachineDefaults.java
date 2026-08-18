package dev.mfp.core.plan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * How the player's copy of one machine is actually built: "my blast furnaces have HSS-G coils".
 *
 * <p>The gap this closes is the one {@link Preferences#defaultTier()} left open. A tier applies to
 * every machine at once, but the numbers that decide a multiblock's throughput do not — a coil tier
 * belongs to the blast furnace, a parallel hatch to the machines that take one, a rotor to the
 * turbines — and until now the only place to say any of it was
 * {@link Plan#configureMachine(String, MachineConfig)}, which is per recipe. So a player who had
 * built one blast furnace with one set of coils had to describe that build again for every recipe
 * they ever smelted in it, in every plan, or read numbers computed for a machine they do not own.
 *
 * <p><b>What it is not.</b> Not a {@link MachineConfig}: there is no machine id (the map key is the
 * machine), no limit — "I own four of these" is a fact about one factory rather than about how the
 * machine is built — and every field may be unstated, because a default that had to answer
 * everything would force the player to invent a coil for a machine that has none.
 *
 * <p>Fields left unstated fall through to what MFP would have done anyway, and a plan that states
 * its own answer wins over all of it: {@code MachinePicker} consults the plan's built configuration
 * first, then the type-level machine choice, and only then this.
 *
 * @param tier             voltage tier this machine's energy hatch supplies, or {@link #UNSET_TIER}
 * @param parallels        copies of the machine's recipe to run at once, or {@link #UNSET_PARALLELS}
 * @param structureOptions build choices, keyed as the behaviours read them; see {@code OptionSpec}
 */
public record MachineDefaults(int tier, int parallels, Map<String, Object> structureOptions) {

    /** No tier stated, so the machine keeps whatever the recipe and the standing default give it. */
    public static final int UNSET_TIER = Preferences.NO_DEFAULT_TIER;

    /**
     * No parallel count stated.
     *
     * <p>Zero rather than one, because one is a real answer that differs from silence: a machine
     * whose default says "one" is one the player has deliberately told MFP not to parallelise, and
     * {@link MachineConfig#parallels()} cannot represent the difference — its floor is one.
     */
    public static final int UNSET_PARALLELS = 0;

    /** Nothing stated about a machine: what a lookup for an unconfigured one returns. */
    public static final MachineDefaults NONE = new MachineDefaults(UNSET_TIER, UNSET_PARALLELS, Map.of());

    public MachineDefaults {
        if (tier < 0) {
            tier = UNSET_TIER;
        }
        if (parallels < 0) {
            parallels = UNSET_PARALLELS;
        }
        structureOptions = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(structureOptions, "structureOptions")));
    }

    /** Whether this says anything at all; an empty default is stored as no default. */
    public boolean isEmpty() {
        return tier == UNSET_TIER && parallels == UNSET_PARALLELS && structureOptions.isEmpty();
    }

    public boolean hasTier() {
        return tier != UNSET_TIER;
    }

    public boolean hasParallels() {
        return parallels != UNSET_PARALLELS;
    }

    public MachineDefaults withTier(int newTier) {
        return new MachineDefaults(newTier, parallels, structureOptions);
    }

    public MachineDefaults withoutTier() {
        return new MachineDefaults(UNSET_TIER, parallels, structureOptions);
    }

    public MachineDefaults withParallels(int newParallels) {
        return new MachineDefaults(tier, newParallels, structureOptions);
    }

    public MachineDefaults withoutParallels() {
        return new MachineDefaults(tier, UNSET_PARALLELS, structureOptions);
    }

    public MachineDefaults withOption(String key, Object value) {
        Map<String, Object> options = new LinkedHashMap<>(structureOptions);
        options.put(Objects.requireNonNull(key, "key"), value);
        return new MachineDefaults(tier, parallels, options);
    }

    public MachineDefaults withoutOption(String key) {
        Map<String, Object> options = new LinkedHashMap<>(structureOptions);
        options.remove(key);
        return new MachineDefaults(tier, parallels, options);
    }

    /**
     * Everything the build says, written into a configuration the picker has just derived.
     *
     * <p>Only where the configuration has not already been told otherwise, which is what makes this
     * safe to apply without checking where each field came from. An option the caller set is a
     * decision about this line and outranks a statement about the machine in general; the tier is
     * the caller's too, because only it knows the recipe's own minimum and whether the machine has
     * a hatch to set at all.
     */
    public MachineConfig applyTo(MachineConfig config) {
        if (isEmpty() || config == null || config.machineId() == null) {
            return config;
        }
        Map<String, Object> options = new LinkedHashMap<>(structureOptions);
        options.putAll(config.structureOptions());
        return new MachineConfig(config.machineId(), config.tier(),
                hasParallels() && config.parallels() <= 1 ? parallels : config.parallels(),
                config.limit(), config.forceLimit(), options);
    }
}
