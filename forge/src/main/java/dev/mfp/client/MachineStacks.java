package dev.mfp.client;

import dev.mfp.core.index.RecipeIndex;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.select.MachinePicker;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a machine id — or a recipe type — into something that can be drawn.
 *
 * <p>The mirror of {@link KeyStacks}, and a much simpler one: GregTech registers every machine's
 * item under the machine's own id, and a multiblock's controller item likewise, so a machine icon
 * is an ordinary item lookup and needs nothing new from ingestion. Nothing is guessed from the id
 * beyond that single registry hit; an id with no item keeps the "?" box {@code SlotWidget} draws,
 * because an invisible machine is worse than an obviously unknown one.
 *
 * <p>A recipe <em>type</em> has no item of its own, so it is represented by a machine that runs it:
 * the lowest-tier single block if there is one, else the multiblock controller. That is the same
 * choice a player makes reading a recipe viewer's tab, and it is only a picture — every number
 * still comes from the machine the line is actually configured with.
 */
public final class MachineStacks {

    private static final Map<String, ItemStack> ICONS = new HashMap<>();
    private static final Map<String, MfpMachine> REPRESENTATIVES = new HashMap<>();

    private MachineStacks() {}

    /** Called when the world changes, since item registries may differ between servers. */
    public static void clearCache() {
        ICONS.clear();
        REPRESENTATIVES.clear();
    }

    /** An icon for a machine id. Never null; may be empty for an id nothing registers. */
    public static ItemStack icon(String machineId) {
        return ICONS.computeIfAbsent(machineId, MachineStacks::buildIcon);
    }

    /** An icon for whatever machine best represents a recipe type, possibly empty. */
    public static ItemStack iconForRecipeType(String recipeTypeId) {
        MfpMachine machine = representative(recipeTypeId);
        return machine == null ? ItemStack.EMPTY : icon(machine.id());
    }

    /**
     * What to call a recipe type: its own id, made readable.
     *
     * <p>{@code gtceu:distillery} becomes "Distillery" and {@code gtceu:electric_blast_furnace}
     * becomes "Electric Blast Furnace". A tab holds every recipe of a type, across every tier of
     * machine that runs it, so labelling it "Basic Distillery" is a lie about the other tiers on the
     * same tab — and the recipe type is the only name that belongs to the family rather than to one
     * member of it.
     *
     * <p>The first attempt derived this from the machines instead, taking the longest run of trailing
     * words their names shared. It reads better on paper and did not work in the pack: the tabs still
     * came out as "Basic Distillery". The recipe type id is what the tab's own tooltip already shows,
     * it needs no agreement between machine names, and it cannot be defeated by a pack that names one
     * tier differently.
     *
     * <p>The plan's own machine column is deliberately unaffected. There the exact machine is the
     * answer to the question, and "Basic Distillery" is what the player has to build.
     */
    public static String shortName(String recipeTypeId) {
        String path = recipeTypeId;
        int colon = path.indexOf(':');
        if (colon >= 0) {
            path = path.substring(colon + 1);
        }
        StringBuilder title = new StringBuilder(path.length());
        boolean startOfWord = true;
        for (char c : path.toCharArray()) {
            if (c == '_' || c == '/' || c == '.') {
                title.append(' ');
                startOfWord = true;
                continue;
            }
            title.append(startOfWord ? Character.toUpperCase(c) : c);
            startOfWord = false;
        }
        return title.toString();
    }

    /**
     * The name a player would recognise for a machine id.
     *
     * <p>From the <em>item</em>, not from the machine definition, and that is the whole point.
     * {@code MachineDefinition.getLangValue()} is unset for a machine registered from KubeJS, so the
     * catalog falls back to the definition's internal name and the planner was printing
     * "steam_kiln" beside a picker tab that said "Steam Kiln". The item stack's hover name resolves
     * the same translation the game shows on the block itself, which is the name the player has
     * actually seen.
     *
     * <p>Falls back through the index's own name to the bare id, so an unregistered machine still
     * prints something.
     */
    public static String name(String machineId) {
        ItemStack stack = icon(machineId);
        if (!stack.isEmpty()) {
            return stack.getHoverName().getString();
        }
        RecipeIndex index = ClientIndex.get();
        MfpMachine machine = index == null ? null : index.machine(machineId);
        return machine == null ? machineId : machine.displayName();
    }

    /**
     * The machine that stands in for a recipe type — the same one the planner would default to.
     *
     * <p>Deliberately {@link MachinePicker#order} rather than a second opinion: the icon and name on
     * a picker tab are a promise about which machine the plan will use, and a tab that showed a
     * late-game multiblock while the plan quietly built a greenhouse would be worse than no icon.
     *
     * <p>Null when the index knows no machine for the type at all, which happens for the synthesised
     * recipe types and for anything a provider recorded without a machine catalogue.
     */
    public static MfpMachine representative(String recipeTypeId) {
        if (REPRESENTATIVES.containsKey(recipeTypeId)) {
            return REPRESENTATIVES.get(recipeTypeId);
        }
        MfpMachine chosen = null;
        RecipeIndex index = ClientIndex.get();
        if (index != null) {
            chosen = index.machinesFor(recipeTypeId).stream()
                    .min(MachinePicker.order(index))
                    .orElse(null);
        }
        REPRESENTATIVES.put(recipeTypeId, chosen);
        return chosen;
    }

    private static ItemStack buildIcon(String machineId) {
        ResourceLocation location = ResourceLocation.tryParse(machineId);
        if (location == null) {
            return ItemStack.EMPTY;
        }
        var item = ForgeRegistries.ITEMS.getValue(location);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }
}
