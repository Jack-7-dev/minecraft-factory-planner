package dev.mfp.integration.gtceu;

import com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper;
import com.gregtechceu.gtceu.api.data.chemical.material.ItemMaterialData;
import com.gregtechceu.gtceu.api.data.chemical.material.stack.MaterialEntry;
import com.gregtechceu.gtceu.api.data.tag.TagPrefix;
import dev.mfp.core.index.MfpRecipeSink;
import dev.mfp.core.model.MaterialForm;
import dev.mfp.core.model.MfpKey;
import dev.mfp.provider.GameKeys;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Reads GregTech's own classification of every item: what form it is, and what it is made of.
 *
 * <p>This is the data behind the refinement term in {@code RecipeScorer} (plan §13.5), and the
 * reason that term is worth trusting: {@code ChemicalHelper.getMaterialEntry} returns the real
 * {@code (TagPrefix, Material)} pair behind every ore, crushed, dust, ingot and plate item in the
 * game, rather than anything inferred from an item id. The manufactured case comes from
 * {@code ItemMaterialData.getMaterialInfo}, which is the very table GregTech generates its recycling
 * recipes from — so the class of recipe the scorer is trying to demote is identified by the data
 * that creates it.
 *
 * <p>Two details of the fork make this less obvious than it looks.
 *
 * <p><b>{@code TagPrefix.name} is not the field name.</b> The prefix declared as {@code ore} is
 * named "stone", {@code crushed} is named "crushedOre" and {@code rawOre} is named "raw" — the name
 * describes the tag path, not the concept. The ore prefixes are therefore recognised through
 * {@code TagPrefix.ORES}, which is the fork's own set of them, and canonicalised to a single "ore";
 * every other prefix's name is stable and used as it stands. {@code MaterialForms} is keyed on
 * exactly these strings.
 *
 * <p><b>The sweep is over the item registry, not over the recipes.</b> The plan put this in
 * {@code GtContents}, beside ingredient conversion. Doing it here instead costs one pass over the
 * registry and keeps the conversion path — which is the hot, per-recipe one — untouched, and it
 * classifies an item once rather than once per recipe that mentions it.
 */
final class GtMaterialForms {

    private GtMaterialForms() {}

    /** Classify every registered item that GregTech knows anything about. Returns how many. */
    static int collect(MfpRecipeSink sink) {
        int classified = 0;
        for (Item item : ForgeRegistries.ITEMS) {
            if (item == null) {
                continue;
            }
            MaterialForm form = formOf(item);
            if (form == null) {
                form = geodeOf(item);
            }
            if (form == null) {
                continue;
            }
            // The plain form of the item: a variant is the same material in the same shape, and a
            // key that carried an NBT hash would never be looked up by the scorer anyway.
            MfpKey key = GameKeys.of(new ItemStack(item));
            if (key != null) {
                sink.form(key, form);
                classified++;
            }
        }
        return classified;
    }

    private static MaterialForm formOf(Item item) {
        MaterialEntry entry;
        try {
            entry = ChemicalHelper.getMaterialEntry(item);
        } catch (RuntimeException | LinkageError e) {
            // One item that cannot be classified costs one item's classification (plan P8). The
            // lookup resolves lazy suppliers on first use, so it can fail on a broken pack entry.
            return null;
        }
        if (entry != null && !entry.isEmpty()) {
            return MaterialForm.of(formName(entry.tagPrefix()),
                    entry.material().getResourceLocation().toString());
        }
        // No form of its own, but the game knows what it is made of: a machine, a tool, an
        // assembled part. Precisely the class the refinement rule exists to demote.
        return ItemMaterialData.getMaterialInfo(item) != null ? MaterialForm.manufactured() : null;
    }

    private static String formName(TagPrefix prefix) {
        return TagPrefix.ORES.containsKey(prefix) ? "ore" : prefix.name;
    }

    /**
     * The one classification made from an item's name, and the reason it is worth the exception.
     *
     * <p>Star-Technology's geodes are plain KubeJS items — {@code kubejs:ruby_geode} and some forty
     * others, produced by the rock filtrator — with no {@code TagPrefix} and no material info, so
     * everything above says nothing about them. They sit on the ore-source ladder between crushed ore
     * and raw ore, which is a fact about how a player gets their materials and cannot be recovered
     * from GregTech's data because they are not GregTech's items.
     *
     * <p>Deliberately narrow: it fires only on an item nothing else classified, and only on the
     * suffix. If the pack ever gives geodes a real prefix, {@link #formOf} answers first and this
     * never runs. The material is left unknown because the ladder does not read it.
     */
    private static MaterialForm geodeOf(Item item) {
        var id = ForgeRegistries.ITEMS.getKey(item);
        return id != null && id.getPath().endsWith("_geode")
                ? MaterialForm.of("geode", id.toString())
                : null;
    }
}
