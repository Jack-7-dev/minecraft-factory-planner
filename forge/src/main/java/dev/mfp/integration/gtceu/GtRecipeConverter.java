package dev.mfp.integration.gtceu;

import com.gregtechceu.gtceu.api.capability.recipe.CWURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.gregtechceu.gtceu.api.recipe.chance.logic.ChanceLogic;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.EnergyStack;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import dev.mfp.core.model.ChanceMode;
import dev.mfp.core.model.MfpCondition;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts one {@code GTRecipe} into an {@link MfpRecipe}.
 *
 * <p>Three things about GregTech's model drive the shape of this class.
 *
 * <ul>
 *   <li><b>Contents are grouped by capability, not by kind.</b> A recipe holds four maps — inputs,
 *       outputs, tick inputs, tick outputs — each from a {@code RecipeCapability} to a list of
 *       {@code Content}. Items, fluids, EU and computation all arrive through the same door.
 *   <li><b>Tick contents are per tick, not per craft.</b> Multiplying by the duration is what puts
 *       them on the same footing as everything else, which is what the whole model assumes.
 *   <li><b>{@code chance == 0} on an input means "not consumed"</b>, not "never happens". It is how
 *       GregTech encodes {@code notConsumable}, and programmed circuits use it in hundreds of
 *       recipes in the target pack. Reading it as a probability would make every plan demand
 *       circuits it never actually uses up.
 * </ul>
 *
 * <p>Anything the conversion cannot express is recorded in {@code extra} and flagged rather than
 * dropped (plan P4). That includes the whole of the recipe's {@code data} tag, which is where the
 * pack's own machines keep their structural requirements.
 */
final class GtRecipeConverter {

    /** Extra key listing capabilities MFP saw but does not model. Its presence is a warning. */
    static final String UNMODELLED = "gtceu:unmodelled";

    private GtRecipeConverter() {}

    static MfpRecipe convert(GTRecipe recipe, String recipeId, String providerId, boolean synthetic) {
        String recipeTypeId = String.valueOf(recipe.recipeType.registryName);
        double duration = Math.max(0, recipe.duration);

        MfpRecipe.Builder builder = MfpRecipe.builder(recipeId, recipeTypeId, providerId)
                .duration(duration);

        applyEnergy(recipe, builder);
        Map<String, Object> extra = new LinkedHashMap<>();

        convertContents(recipe.inputs, recipe.inputChanceLogics, false, false, duration, builder, extra);
        convertContents(recipe.tickInputs, recipe.tickInputChanceLogics, false, true, duration, builder, extra);
        convertContents(recipe.outputs, recipe.outputChanceLogics, true, false, duration, builder, extra);
        convertContents(recipe.tickOutputs, recipe.tickOutputChanceLogics, true, true, duration, builder, extra);

        for (MfpCondition condition : convertConditions(recipe)) {
            builder.condition(condition);
        }

        collectMetadata(recipe, synthetic, extra);
        extra.forEach(builder::extra);
        return builder.build();
    }

    // ---------------------------------------------------------------- energy

    /**
     * Energy is carried by the tick maps, so it is read once here rather than as a content.
     *
     * <p>MFP models energy through {@code euIn}/{@code euOut} alone — never as an entry in the
     * inputs or outputs list — because the solver derives the EU flow from those fields. Letting it
     * appear in both places would double-count every powered recipe.
     */
    private static void applyEnergy(GTRecipe recipe, MfpRecipe.Builder builder) {
        EnergyStack in = GtCompat.inputEnergy(recipe);
        EnergyStack out = GtCompat.outputEnergy(recipe);

        builder.euIn(Math.max(0L, in.voltage()))
                .euOut(Math.max(0L, out.voltage()))
                .amperage(GtCompat.amperage(recipe));

        int tier = GtCompat.voltageTier(recipe);
        builder.minTier(tier < 0 ? MfpRecipe.NO_TIER : tier);
    }

    // -------------------------------------------------------------- contents

    private static void convertContents(Map<RecipeCapability<?>, List<Content>> contents,
                                        Map<RecipeCapability<?>, ChanceLogic> chanceLogics,
                                        boolean output,
                                        boolean perTick,
                                        double duration,
                                        MfpRecipe.Builder builder,
                                        Map<String, Object> extra) {
        if (contents == null || contents.isEmpty()) {
            return;
        }

        for (Map.Entry<RecipeCapability<?>, List<Content>> entry : contents.entrySet()) {
            RecipeCapability<?> capability = entry.getKey();
            List<Content> list = entry.getValue();
            if (capability == null || list == null || list.isEmpty()) {
                continue;
            }

            // Energy is read from the recipe directly by applyEnergy, not as a content. A non-tick
            // EU content is a flat cost per craft rather than a draw, which the rate model has no
            // field for, so it is recorded instead of being folded into EU/t and quietly distorting it.
            if (capability == EURecipeCapability.CAP) {
                if (!perTick) {
                    recordFlatEnergy(list, output, extra);
                }
                continue;
            }

            ChanceLogic logic = chanceLogics == null ? null : chanceLogics.get(capability);
            for (Content content : list) {
                if (content == null) {
                    continue;
                }
                convertContent(capability, content, logic, output, perTick, duration, builder, extra);
            }
        }
    }

    private static void convertContent(RecipeCapability<?> capability,
                                       Content content,
                                       ChanceLogic logic,
                                       boolean output,
                                       boolean perTick,
                                       double duration,
                                       MfpRecipe.Builder builder,
                                       Map<String, Object> extra) {
        GtContents.Resolved resolved = resolve(capability, content);
        if (resolved == null) {
            noteUnmodelled(extra, capability.name);
            return;
        }
        if (resolved.isEmpty()) {
            // A tag that resolves to no item in this pack. Silently dropping it would understate
            // the recipe's cost, so the caller turns this into a skip with a reason.
            throw new IllegalStateException(
                    "content of capability '" + capability.name + "' matches no registered "
                            + (capability == FluidRecipeCapability.CAP ? "fluid" : "item"));
        }

        // Per tick becomes per craft; every amount in the model is per craft.
        double amount = perTick ? resolved.amount() * duration : resolved.amount();

        if (output) {
            builder.output(toOutput(resolved.candidates().get(0), amount, content, logic, capability, perTick));
        } else {
            builder.input(toIngredient(resolved.candidates(), amount, content));
        }
    }

    /** Resolve a content to keys and a per-craft amount, or null when the capability is unmodelled. */
    private static GtContents.Resolved resolve(RecipeCapability<?> capability, Content content) {
        Object value = content.getContent();
        if (capability == ItemRecipeCapability.CAP && value instanceof Ingredient ingredient) {
            return GtContents.item(ingredient);
        }
        if (capability == FluidRecipeCapability.CAP && value instanceof FluidIngredient ingredient) {
            return GtContents.fluid(ingredient);
        }
        if (capability == CWURecipeCapability.CAP && value instanceof Number cwu) {
            // Computation is a pseudo-item, exactly as energy is (plan P3): a research chain that
            // needs 4 CWU/t shows up as demand the planner can trace instead of a footnote.
            return new GtContents.Resolved(List.of(MfpKey.CWU), cwu.doubleValue());
        }
        return null;
    }

    /**
     * Build an input.
     *
     * <p>{@code chance == 0} is the not-consumed marker. Such an input still has to be present, but
     * it never flows, so it is recorded with a zero amount and {@code consumed = false} — the shape
     * the rest of MFP expects for a fixed setup cost.
     */
    private static MfpIngredient toIngredient(List<MfpKey> candidates, double amount, Content content) {
        if (content.chance == 0) {
            return new MfpIngredient(candidates, 0.0, false, 1.0);
        }
        return new MfpIngredient(candidates, amount, true, probability(content));
    }

    private static MfpOutput toOutput(MfpKey key,
                                      double amount,
                                      Content content,
                                      ChanceLogic logic,
                                      RecipeCapability<?> capability,
                                      boolean perTick) {
        double chance = logic == ChanceLogic.NONE ? 0.0 : probability(content);

        if (chance >= 1.0) {
            return new MfpOutput(key, amount, 1.0, ChanceMode.ALWAYS, null);
        }

        ChanceMode mode = chanceModeOf(logic);
        // Competing modes resolve as a group, so the members need to know which group they are in.
        String group = mode == ChanceMode.FIRST_ONLY || mode == ChanceMode.EXCLUSIVE
                ? capability.name + (perTick ? ":tick_output" : ":output")
                : null;
        return new MfpOutput(key, amount, chance, mode, group);
    }

    private static double probability(Content content) {
        if (content.maxChance <= 0) {
            return 1.0;
        }
        return Math.min(1.0, Math.max(0.0, (double) content.chance / content.maxChance));
    }

    /**
     * Map GregTech's chance logic onto MFP's.
     *
     * <p>{@code NONE} produces nothing at all, which is a probability of zero rather than a mode, so
     * it is handled by the caller and never reaches here as a mode. An unrecognised logic — the pack
     * may register its own — falls back to independent, which is GregTech's default.
     */
    private static ChanceMode chanceModeOf(ChanceLogic logic) {
        if (logic == ChanceLogic.AND) {
            return ChanceMode.ALL_OR_NOTHING;
        }
        if (logic == ChanceLogic.FIRST) {
            return ChanceMode.FIRST_ONLY;
        }
        if (logic == ChanceLogic.XOR) {
            return ChanceMode.EXCLUSIVE;
        }
        return ChanceMode.INDEPENDENT;
    }

    private static void recordFlatEnergy(List<Content> contents, boolean output, Map<String, Object> extra) {
        long total = 0;
        for (Content content : contents) {
            if (content.getContent() instanceof EnergyStack stack) {
                total += stack.getTotalEU();
            }
        }
        if (total > 0) {
            extra.put(output ? "gtceu:flat_eu_out" : "gtceu:flat_eu_in", total);
        }
    }

    @SuppressWarnings("unchecked")
    private static void noteUnmodelled(Map<String, Object> extra, String capabilityName) {
        List<String> names = (List<String>) extra.computeIfAbsent(UNMODELLED, k -> new ArrayList<String>());
        if (!names.contains(capabilityName)) {
            names.add(capabilityName);
        }
    }

    // ------------------------------------------------------------ conditions

    /**
     * Record research, cleanroom, dimension and quest gates.
     *
     * <p>MFP does not evaluate these — that needs live player state a planner does not have — but a
     * plan that quietly omits a cleanroom requirement is worse than one that says so (plan P4).
     */
    private static List<MfpCondition> convertConditions(GTRecipe recipe) {
        if (recipe.conditions == null || recipe.conditions.isEmpty()) {
            return List.of();
        }

        List<MfpCondition> converted = new ArrayList<>(recipe.conditions.size());
        for (RecipeCondition<?> condition : recipe.conditions) {
            if (condition == null) {
                continue;
            }
            String type = "gtceu:" + typeNameOf(condition);
            converted.add(new MfpCondition(type, describe(condition, type), condition.isReverse()));
        }
        return converted;
    }

    private static String typeNameOf(RecipeCondition<?> condition) {
        try {
            String key = GTRegistries.RECIPE_CONDITIONS.getKey(condition.getType());
            if (key != null && !key.isEmpty()) {
                return key;
            }
        } catch (RuntimeException ignored) {
            // Fall through to the class name, which is still identifiable.
        }
        return condition.getClass().getSimpleName();
    }

    /** A human-readable summary, so an unrecognised condition still says something useful. */
    private static String describe(RecipeCondition<?> condition, String fallback) {
        try {
            var tooltip = condition.getTooltips();
            if (tooltip != null) {
                String text = tooltip.getString();
                if (!text.isBlank()) {
                    return text;
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Some conditions build their tooltip from client-side helpers. The type name is enough.
        }
        return fallback;
    }

    // -------------------------------------------------------------- metadata

    private static void collectMetadata(GTRecipe recipe, boolean synthetic, Map<String, Object> extra) {
        if (recipe.recipeCategory != null) {
            extra.put("gtceu:category", String.valueOf(recipe.recipeCategory.registryKey));
        }
        if (recipe.parallels > 1) {
            extra.put("gtceu:parallels", recipe.parallels);
        }
        if (synthetic) {
            // Generated by the recipe type rather than loaded from a datapack — recycling, brewing,
            // scanner research. Worth flagging: these have no JSON to check a number against.
            extra.put("gtceu:synthetic", true);
        }

        Map<String, Object> data = tagToMap(recipe.data);
        if (!data.isEmpty()) {
            // Kept whole. This is where the pack's own multiblocks record their structural
            // requirements, and M4b's behaviours read them from here rather than from GregTech.
            extra.put("gtceu:data", data);
            hoistInt(data, "ebf_temp", "gtceu:ebf_temp", extra);
            hoistInt(data, "vacuum_level", "gtceu:vacuum_level", extra);
            // start_core's reflector reactors gate on this and overclock above their own tier when
            // the built reflector beats it, so it decides both whether a fusion line runs and how
            // fast — see FusionOverclockBehaviour.
            hoistInt(data, "reflector_tier", "gtceu:reflector_tier", extra);
        }

        Map<String, Integer> boosts = tierChanceBoosts(recipe);
        if (!boosts.isEmpty()) {
            // The fork changed chance boosting to scale with recipe tier rather than overclocks, so
            // M4b has to read its implementation. Carrying the per-content values makes that possible.
            extra.put("gtceu:tier_chance_boost", boosts);
        }
    }

    /** Copy a numeric data entry to a top-level key, so {@code MfpRecipe#intExtra} can find it. */
    private static void hoistInt(Map<String, Object> data, String from, String to, Map<String, Object> extra) {
        if (data.get(from) instanceof Number number) {
            extra.put(to, number.intValue());
        }
    }

    private static Map<String, Integer> tierChanceBoosts(GTRecipe recipe) {
        Map<String, Integer> boosts = new LinkedHashMap<>();
        collectBoosts(recipe.outputs, "output", boosts);
        collectBoosts(recipe.tickOutputs, "tick_output", boosts);
        return boosts;
    }

    private static void collectBoosts(Map<RecipeCapability<?>, List<Content>> contents,
                                      String label,
                                      Map<String, Integer> boosts) {
        if (contents == null) {
            return;
        }
        contents.forEach((capability, list) -> {
            if (capability == null || list == null) {
                return;
            }
            for (int i = 0; i < list.size(); i++) {
                Content content = list.get(i);
                if (content != null && content.tierChanceBoost != 0) {
                    boosts.put(capability.name + ':' + label + '[' + i + ']', content.tierChanceBoost);
                }
            }
        });
    }

    /** Flatten an NBT compound into plain values, so nothing in {@code data} is lost to the model. */
    private static Map<String, Object> tagToMap(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : tag.getAllKeys()) {
            Tag value = tag.get(key);
            if (value instanceof CompoundTag nested) {
                values.put(key, tagToMap(nested));
            } else if (value instanceof NumericTag numeric) {
                values.put(key, numeric.getAsLong() == (long) numeric.getAsDouble()
                        ? (Object) numeric.getAsLong()
                        : (Object) numeric.getAsDouble());
            } else if (value instanceof StringTag string) {
                values.put(key, string.getAsString());
            } else if (value != null) {
                values.put(key, value.toString());
            }
        }
        return values;
    }
}
