package dev.mfp.integration.gtceu;

import com.gregtechceu.gtceu.data.recipe.CustomTags;
import dev.mfp.core.behaviour.GtTiers;
import dev.mfp.core.index.MfpRecipeSink;
import dev.mfp.core.model.MfpKey;
import dev.mfp.provider.GameKeys;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The items whose tier is a <em>gate</em> rather than a cost: GregTech's cover components and its
 * circuits.
 *
 * <p><b>The fault this exists to fix.</b> M17's ceiling asked one question of every item — is there
 * a recipe at or below your tier whose inputs you can likewise make, to any depth — and concluded
 * that a player at HV can have an IV emitter, because {@code gtceu:iv_emitter} has a shaped crafting
 * recipe and a tier 1 assembler one. That is true of the recipe and false of the game. The pack's
 * extreme chemical reactor takes two IV emitters and an IV circuit, and MFP offered it to a player
 * at HV. <b>The tier of a component is in the item, not in the recipe that assembles it</b>, and
 * nothing MFP knew about tier could express that: every other tier it holds is a voltage, and a
 * voltage can be paid for with a bigger hatch.
 *
 * <p><b>GregTech declares this, so MFP reads it rather than listing it.</b> Two different
 * declarations, because the fork makes them differently:
 *
 * <ul>
 *   <li><b>Circuits carry their tier in a tag.</b> {@code CustomTags.CIRCUITS_ARRAY} is indexed by
 *       tier from ULV, and the tags are {@code gtceu:circuits/lv} through {@code circuits/max}.
 *       Nothing is inferred: the tier is the tag.
 *   <li><b>The ten cover components carry their family in a tag and their tier in their id.</b>
 *       {@code gtceu:electric_motors} holds every motor from LV to UHV together, so the family is
 *       authoritative and the tier is not there. It is in the id — {@code iv_electric_motor},
 *       {@code hv_voltage_coil} — because that is how GregTech registers them, from
 *       {@code GTValues.VN}. So the family comes from the game and the tier is read off the prefix.
 * </ul>
 *
 * <p><b>Voltage coils have no family tag at all</b> in this fork, so they are matched by the same
 * {@code <tier>_<family>} shape against a named suffix. That is the one place MFP supplies the list
 * GregTech does not, and it is one line rather than ninety.
 *
 * <p><b>Anything unrecognised is reported, not dropped.</b> An item sitting in a component tag whose
 * id has no tier prefix is a component MFP will not gate — the exact shape of failure that lets a
 * plan quietly offer something unbuildable — so the count and the names come back to the caller and
 * {@code mfp components} prints them. The same argument {@code mfp modifiers} makes about
 * behaviours: a classifier nobody audits is a classifier that silently stops classifying after a
 * pack update.
 *
 * <p>Loadable only when {@code MfpMod.isGregTechLoaded()}, like everything else in this package.
 */
final class GtComponentTiers {

    private GtComponentTiers() {}

    /**
     * The families whose tier lives in the item id, and the tag that says which items are in each.
     *
     * <p>A tag rather than a name pattern wherever the fork has one, because the tag is what a pack
     * adding its own components joins. {@code start_core} has done exactly that before.
     */
    private static final Map<String, TagKey<Item>> FAMILIES = new LinkedHashMap<>();

    static {
        FAMILIES.put("electric_motor", CustomTags.ELECTRIC_MOTORS);
        FAMILIES.put("electric_pump", CustomTags.ELECTRIC_PUMPS);
        FAMILIES.put("fluid_regulator", CustomTags.FLUID_REGULATORS);
        FAMILIES.put("conveyor_module", CustomTags.CONVEYOR_MODULES);
        FAMILIES.put("electric_piston", CustomTags.ELECTRIC_PISTONS);
        FAMILIES.put("robot_arm", CustomTags.ROBOT_ARMS);
        FAMILIES.put("field_generator", CustomTags.FIELD_GENERATORS);
        FAMILIES.put("emitter", CustomTags.EMITTERS);
        FAMILIES.put("sensor", CustomTags.SENSORS);
        // The tenth. No tag in this fork, so the name is all there is; see the class javadoc.
        FAMILIES.put("voltage_coil", null);
    }

    /** What one sweep found, for the ingestion log and for {@code mfp components}. */
    record Result(int classified, int circuits, List<String> unrecognised) {}

    /**
     * Classify every tiered component in the game. Returns what it found and what it could not.
     *
     * <p>Order matters only in that the first classification of a key wins, and the circuits are
     * done first because their tier is declared rather than parsed.
     */
    static Result collect(MfpRecipeSink sink) {
        int classified = 0;
        int circuits = 0;
        List<String> unrecognised = new ArrayList<>();

        for (int tier = 0; tier < CustomTags.CIRCUITS_ARRAY.length && tier <= GtTiers.MAX; tier++) {
            for (Item item : itemsIn(CustomTags.CIRCUITS_ARRAY[tier])) {
                if (record(sink, item, tier)) {
                    classified++;
                    circuits++;
                }
            }
        }

        for (Map.Entry<String, TagKey<Item>> family : FAMILIES.entrySet()) {
            for (Item item : membersOf(family.getKey(), family.getValue())) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                int tier = tierFromId(id);
                if (tier < 0) {
                    // Named as a component and not named with a tier. Reported rather than guessed:
                    // guessing here means either gating something that should not be or, worse,
                    // not gating something that should.
                    unrecognised.add(String.valueOf(id));
                    continue;
                }
                if (record(sink, item, tier)) {
                    classified++;
                }
            }
        }
        return new Result(classified, circuits, List.copyOf(unrecognised));
    }

    private static boolean record(MfpRecipeSink sink, Item item, int tier) {
        MfpKey key = GameKeys.of(new ItemStack(item));
        if (key == null) {
            return false;
        }
        sink.componentTier(key, tier);
        return true;
    }

    /** Members of the family's tag, or — where the fork has no tag — items named for the family. */
    private static List<Item> membersOf(String family, TagKey<Item> tag) {
        if (tag != null) {
            List<Item> tagged = itemsIn(tag);
            if (!tagged.isEmpty()) {
                return tagged;
            }
            // An empty tag is a finding in itself: it means the fork moved and the sweep below is
            // now doing the work the tag was supposed to. Falling through rather than returning
            // nothing, because gating by name is far better than not gating at all.
        }
        List<Item> named = new ArrayList<>();
        for (Item item : ForgeRegistries.ITEMS) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id != null && id.getPath().endsWith("_" + family)) {
                named.add(item);
            }
        }
        return named;
    }

    private static List<Item> itemsIn(TagKey<Item> tag) {
        List<Item> items = new ArrayList<>();
        try {
            ForgeRegistries.ITEMS.tags().getTag(tag).forEach(items::add);
        } catch (RuntimeException | LinkageError missing) {
            // A tag that does not exist in this pack costs that tag, not the sweep (plan P8).
            return List.of();
        }
        return items;
    }

    /**
     * The tier from an id of the form {@code <tier>_<family>}, or {@code -1}.
     *
     * <p>Matched against {@link GtTiers}' own names lowercased, which is where GregTech gets the
     * prefixes from — {@code luv_electric_motor}, not {@code LuV_electric_motor}.
     */
    private static int tierFromId(ResourceLocation id) {
        if (id == null) {
            return -1;
        }
        String path = id.getPath();
        for (int tier = 0; tier <= GtTiers.MAX; tier++) {
            String prefix = GtTiers.name(tier).toLowerCase(Locale.ROOT) + "_";
            if (path.startsWith(prefix)) {
                return tier;
            }
        }
        return -1;
    }
}
