package dev.mfp.client;

import dev.mfp.core.index.RecipeIndex;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpRecipe;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What it takes to craft one machine <em>block</em>.
 *
 * <p>The machine tab lists what the plan costs in machines; this answers the follow-up question of
 * what each of those machines costs in items. It leans on the same fact {@link MachineStacks} does:
 * GregTech registers every machine's item — and every multiblock controller's item — under the
 * machine's own id, so a machine id is already an {@link MfpKey}, and the index can be asked
 * straight out what produces it.
 *
 * <p><b>The controller block only.</b> A multiblock is a controller plus casings, coils, pipes and
 * hatches, and none of that is here. Those vary per machine and per structure and the index knows
 * nothing about them; working out where a recipe viewer gets its structure list from is deferred.
 * A row here is therefore the price of the block you place, not the price of the machine you run.
 *
 * <p><b>Which recipe, when several make the block.</b> The index returns every producer of the key,
 * ordered by provider priority, so the raw first entry is already deterministic — but it is
 * deterministic, not correct. A machine is typically craftable at a bench <em>and</em> assemblable,
 * and later it is also the output of some replication or upgrade route that costs a completed
 * machine of the tier below. So the pick is: prefer a recipe whose type reads as hand crafting,
 * then one that reads as assembly, then whatever came first — and among equals, the fewest inputs,
 * since a replication route drags in extra reagents that a plain build does not. Choosing by cheapest
 * total item count was tried on paper and rejected: it prefers whichever route happens to bulk-craft,
 * and the question here is "what do I put in the grid", not "what is cheapest".
 *
 * <p>That heuristic <b>can be the wrong route</b> for a machine with several, and this class does
 * not pretend otherwise. It is a best effort standing in until v3 finds where a recipe viewer reads
 * these from; {@link #recipeFor} exposes the recipe that was chosen precisely so the UI can show its
 * id and let the player judge it rather than take the list on faith.
 *
 * <p>Cached, and this is not an optimisation nicety. The tab redraws every frame; asking the index
 * for producers of a key on every frame for every row is a framerate bug. {@link #clearCache()} is
 * the counterpart, and must be called from the same places {@code MachineStacks.clearCache()} is
 * (recipes updated, and logging out) — the index and the item registries both belong to the
 * connection, and a cached parts list outliving them would describe the previous server's machines.
 */
public final class MachineParts {

    private static final Map<String, MfpRecipe> RECIPES = new HashMap<>();

    private MachineParts() {}

    /** Called when the world or recipe set changes, since neither the index nor the items survive it. */
    public static void clearCache() {
        RECIPES.clear();
    }

    /**
     * The ingredients of one machine block.
     *
     * <p>Empty, never null and never a throw, when nothing in the index makes the machine. That is
     * an ordinary outcome and a true statement: a modpack is full of machines granted by quest
     * reward, spawned in creative, or crafted by a mod the providers do not read, and an empty list
     * says "we do not know of a recipe" while a crash says nothing useful at all.
     *
     * <p>Non-consumed inputs are kept. A programmed circuit in an assembler recipe is not used up,
     * so it costs the player nothing per craft, but it is still something they must have and set —
     * and {@link MfpIngredient#consumed()} is on the ingredient for the UI to render that
     * distinction rather than for this class to silently drop it.
     */
    public static List<MfpIngredient> forMachine(String machineId) {
        MfpRecipe recipe = recipeFor(machineId);
        return recipe == null ? List.of() : recipe.inputs();
    }

    /**
     * The recipe {@link #forMachine} read the ingredients from, or null if there is none.
     *
     * <p>Exposed so the tab can name the route it is showing. Given the pick is a heuristic, a list
     * of items with no indication of where they came from would be the one presentation that cannot
     * be checked.
     */
    public static MfpRecipe recipeFor(String machineId) {
        if (machineId == null) {
            return null;
        }
        if (RECIPES.containsKey(machineId)) {
            return RECIPES.get(machineId);
        }
        MfpRecipe chosen = choose(machineId);
        RECIPES.put(machineId, chosen);
        return chosen;
    }

    private static MfpRecipe choose(String machineId) {
        RecipeIndex index = ClientIndex.peek();
        // peek(), not get(): this runs from a draw call, and get() may spend most of a second
        // building the index. If it is not built yet there is nothing to show, and the next frame
        // after the planner opened it will be.
        if (index == null || index.isEmpty()) {
            return null;
        }
        MfpKey key = MfpKey.parse(machineId, MfpKey.Kind.ITEM);
        MfpRecipe best = null;
        int bestRank = Integer.MAX_VALUE;
        for (MfpRecipe candidate : index.producing(key)) {
            int rank = rank(candidate);
            if (best == null
                    || rank < bestRank
                    || (rank == bestRank && candidate.inputs().size() < best.inputs().size())) {
                best = candidate;
                bestRank = rank;
            }
        }
        return best;
    }

    /**
     * How much this route looks like "the way a player builds this machine": lower is better.
     *
     * <p>Matched on the recipe type's path rather than on an enumerated list of ids, because the
     * pack's machines come from several mods and KubeJS, and a hard-coded {@code gtceu:assembler}
     * would silently rank a pack-added assembler as an unknown route.
     */
    private static int rank(MfpRecipe recipe) {
        String type = recipe.recipeTypeId();
        if (type == null) {
            return 3;
        }
        String path = type.toLowerCase(java.util.Locale.ROOT);
        if (path.contains("crafting")) {
            return 0;
        }
        if (path.contains("assembl")) {
            return 1;
        }
        return 2;
    }
}
