package dev.mfp.client;

import dev.mfp.core.model.MfpKey;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns an {@link MfpKey} back into something that can be drawn.
 *
 * <p>The inverse of {@code GameKeys}, and deliberately a lossy one. A key carries a <em>hash</em>
 * of the distinguishing NBT, never the NBT itself (plan §5.1), so a GregTech programmed circuit 4
 * comes back as a plain circuit item. That is a display limitation and it is shown as one: the
 * tooltip says the key is a variant rather than pretending the icon is the whole story. Recovering
 * the real stack would mean the index keeping every NBT it ever saw, which is a much larger promise
 * than drawing an icon is worth.
 *
 * <p>Fluids are drawn as their bucket, because a bucket is an item and items are what the GUI can
 * render for free. The amount beside it is still in millibuckets, which is what the solver works in.
 */
public final class KeyStacks {

    private static final Map<MfpKey, ItemStack> ICONS = new HashMap<>();
    private static final Map<MfpKey, Component> NAMES = new HashMap<>();

    private KeyStacks() {}

    /** Called when the world changes, since item registries may differ between servers. */
    public static void clearCache() {
        ICONS.clear();
        NAMES.clear();
    }

    /** An icon for the key. Never null, but may be an empty stack for something unregistered. */
    public static ItemStack icon(MfpKey key) {
        return ICONS.computeIfAbsent(key, KeyStacks::buildIcon);
    }

    /** The name a player would recognise, falling back to the raw id. */
    public static Component name(MfpKey key) {
        return NAMES.computeIfAbsent(key, KeyStacks::buildName);
    }

    /**
     * Tooltip lines for a flow of this key: name, rate, id, and any caveat about the icon.
     *
     * @param rateLine already-formatted rate text, e.g. "12.5/s"
     */
    public static List<Component> tooltip(MfpKey key, String rateLine) {
        List<Component> lines = new ArrayList<>();
        lines.add(name(key).copy().withStyle(ChatFormatting.WHITE));
        if (rateLine != null) {
            lines.add(Component.literal(rateLine).withStyle(ChatFormatting.AQUA));
        }
        lines.add(Component.literal(key.id()).withStyle(ChatFormatting.DARK_GRAY));
        if (key.kind() == MfpKey.Kind.FLUID) {
            lines.add(Component.literal("fluid, amounts in mB").withStyle(ChatFormatting.DARK_GRAY));
        }
        if (key.variant() != null) {
            lines.add(Component.literal("NBT variant #" + key.variant() + " - icon shows the plain form")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        return lines;
    }

    /**
     * How many of this key make one "stack", for the stacks-per-minute view.
     *
     * <p>A bucket for a fluid, the item's own stack size for an item, and zero — meaning "no such
     * unit" — for energy and computation. Zero rather than one, because the display falls back to
     * per minute when it gets it: "0.02 stacks of EU per minute" is not a smaller number, it is a
     * meaningless one.
     */
    public static double unitsPerStack(MfpKey key) {
        return switch (key.kind()) {
            case FLUID -> 1000.0;
            case ITEM -> {
                ItemStack stack = icon(key);
                yield stack.isEmpty() ? 64.0 : stack.getMaxStackSize();
            }
            default -> 0.0;
        };
    }

    private static ItemStack buildIcon(MfpKey key) {
        return switch (key.kind()) {
            case ITEM -> {
                var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(key.namespace(), key.path()));
                yield item == null ? ItemStack.EMPTY : new ItemStack(item);
            }
            case FLUID -> {
                Fluid fluid = ForgeRegistries.FLUIDS.getValue(new ResourceLocation(key.namespace(), key.path()));
                if (fluid == null) {
                    yield new ItemStack(Items.BUCKET);
                }
                ItemStack bucket = new ItemStack(fluid.getBucket());
                yield bucket.isEmpty() ? new ItemStack(Items.BUCKET) : bucket;
            }
            // Pseudo-items have no game object at all, so these icons are conventions rather than
            // lookups: energy is redstone-coloured, computation is a book.
            case ENERGY -> new ItemStack(Items.REDSTONE_TORCH);
            case COMPUTATION -> new ItemStack(Items.KNOWLEDGE_BOOK);
            case PSEUDO -> new ItemStack(Items.BARRIER);
        };
    }

    private static Component buildName(MfpKey key) {
        if (key.equals(MfpKey.EU)) {
            return Component.literal("Energy (EU)");
        }
        if (key.equals(MfpKey.CWU)) {
            return Component.literal("Computation (CWU)");
        }
        if (key.kind() == MfpKey.Kind.FLUID) {
            Fluid fluid = ForgeRegistries.FLUIDS.getValue(new ResourceLocation(key.namespace(), key.path()));
            if (fluid != null) {
                return new FluidStack(fluid, 1).getDisplayName();
            }
            return Component.literal(key.id());
        }
        ItemStack stack = icon(key);
        return stack.isEmpty() ? Component.literal(key.id()) : stack.getHoverName();
    }
}
