package dev.mfp.provider;

import dev.mfp.core.model.MfpKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Converts game objects into {@link MfpKey}s. The single place NBT identity is decided. */
public final class GameKeys {

    private GameKeys() {}

    public static MfpKey of(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return null;
        }
        return new MfpKey(id.getNamespace(), id.getPath(), MfpKey.Kind.ITEM, variantOf(stack.getTag()));
    }

    public static MfpKey of(Fluid fluid) {
        ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluid);
        if (id == null) {
            return null;
        }
        return new MfpKey(id.getNamespace(), id.getPath(), MfpKey.Kind.FLUID, null);
    }

    /**
     * A fluid key, <b>never carrying an NBT variant</b>.
     *
     * <p>Items need variants — a recipe wanting programmed circuit 4 is not satisfied by circuit 7 —
     * but a fluid is the same fluid whatever tag rode in on the stack. Star-Technology's Bacterial
     * Hydrocarbon Harvester emits its output with a tag, which split every fluid it touches in two:
     * the item picker offered two ethylenes, and a plan producing {@code ethylene#9a9f0dec} could not
     * feed a recipe consuming plain {@code ethylene}. Two identical-looking entries that refuse to
     * connect is worse than any distinction the tag might have carried.
     *
     * <p>If a fluid ever genuinely needs distinguishing by tag, it needs distinguishing by something
     * a player can see, which a hash is not.
     */
    public static MfpKey of(FluidStack stack) {
        return of(stack.getFluid());
    }

    /**
     * A short, stable hash of an item's NBT, or null when it has none.
     *
     * <p>This is what keeps GregTech programmed circuits apart — a recipe wanting circuit 4 is not
     * satisfied by circuit 7, and without a variant both collapse to one key and every
     * circuit-differentiated recipe becomes ambiguous.
     *
     * <p>The tag is canonicalised (keys sorted, recursively) before hashing. NBT is backed by a hash
     * map, so its natural {@code toString()} order is not guaranteed stable; hashing that directly
     * would make item identity — and therefore saved plans — quietly non-reproducible.
     */
    public static String variantOf(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        return Integer.toHexString(canonical(tag).hashCode());
    }

    private static String canonical(Tag tag) {
        if (!(tag instanceof CompoundTag compound)) {
            return tag.toString();
        }
        List<String> keys = new ArrayList<>(compound.getAllKeys());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder("{");
        for (String key : keys) {
            Tag value = compound.get(key);
            sb.append(key).append(':').append(value == null ? "null" : canonical(value)).append(',');
        }
        return sb.append('}').toString();
    }
}
