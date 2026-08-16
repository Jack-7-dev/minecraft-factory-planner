package dev.mfp.core.behaviour;

import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.MachineConfig;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything a behaviour is allowed to look at: the recipe, the machine, and how the user
 * configured it.
 *
 * <p>The accessors here exist so each behaviour does not re-derive the same handful of quantities —
 * and, more importantly, so they all derive them the <em>same</em> way. Tier and voltage in
 * particular have several plausible readings, and picking different ones in different behaviours
 * would produce a chain whose parts disagree.
 *
 * @param recipe  the recipe being run
 * @param machine the machine definition, or null when the plan has not chosen one
 * @param config  the user's configuration of that machine; never null, {@link MachineConfig#UNSET}
 *                when unconfigured
 */
public record BehaviourContext(MfpRecipe recipe, MfpMachine machine, MachineConfig config) {

    public BehaviourContext {
        Objects.requireNonNull(recipe, "recipe");
        Objects.requireNonNull(config, "config");
    }

    public static BehaviourContext of(MfpRecipe recipe, MfpMachine machine, MachineConfig config) {
        return new BehaviourContext(recipe, machine, config == null ? MachineConfig.UNSET : config);
    }

    /** Modifier identifiers the machine declares, in application order; empty when unknown. */
    public List<String> modifierIds() {
        return machine == null ? List.of() : machine.modifierIds();
    }

    public boolean hasModifier(String modifierId) {
        return modifierIds().contains(modifierId);
    }

    public String machineId() {
        return machine != null ? machine.id() : config.machineId();
    }

    public boolean isMultiblock() {
        return machine != null && machine.multiblock();
    }

    /**
     * The recipe's EU/t, voltage times amperage.
     *
     * <p>GregTech compares the <em>total</em> against the machine when deciding overclocks, so a
     * two-amp recipe is treated as twice the voltage. Using the bare voltage would hand such
     * recipes an overclock they do not get.
     */
    public long recipeEut() {
        long amperage = Math.max(1L, recipe.amperage());
        long voltage = recipe.euIn() > 0 ? recipe.euIn() : recipe.euOut();
        return voltage * amperage;
    }

    public int recipeTier() {
        long eut = recipeEut();
        return eut <= 0 ? -1 : GtTiers.tierByVoltage(eut);
    }

    /**
     * The voltage the machine overclocks to.
     *
     * <p>The user's configured tier wins over the machine definition, because for a multiblock the
     * definition has no meaningful tier at all — the voltage comes from the energy hatch the player
     * installed, which is a configuration choice rather than a property of the machine.
     */
    public long machineVoltage() {
        if (config.tier() >= 0) {
            return GtTiers.voltage(config.tier());
        }
        return machine != null ? machine.maxVoltage() : 0L;
    }

    public int machineTier() {
        if (config.tier() >= 0) {
            return config.tier();
        }
        return machine != null ? machine.tier() : -1;
    }

    /** Whether the plan has said enough to compute an overclock at all. */
    public boolean hasMachineVoltage() {
        return machineVoltage() > 0;
    }

    public Map<String, Object> options() {
        return config.structureOptions();
    }

    /** A structure option as an integer, or {@code fallback} when unset or not a number. */
    public int intOption(String key, int fallback) {
        Object value = config.structureOptions().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public boolean hasOption(String key) {
        return config.structureOptions().containsKey(key);
    }

    /** An integer from the recipe's ingested GregTech metadata, e.g. {@code gtceu:ebf_temp}. */
    public int recipeExtra(String key, int fallback) {
        return recipe.intExtra(key, fallback);
    }

    public boolean hasRecipeExtra(String key) {
        return recipe.extra().containsKey(key);
    }
}
