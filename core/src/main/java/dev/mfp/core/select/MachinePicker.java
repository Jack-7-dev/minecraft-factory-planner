package dev.mfp.core.select;

import dev.mfp.core.index.RecipeIndex;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.MachineConfig;
import dev.mfp.core.plan.MachineDefaults;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.plan.Preferences;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Picks the machine a line runs on, and at what tier.
 *
 * <p>Undefined in the original plan and worth stating, because it decides machine counts outright:
 * the same recipe on an LV machine and an HV one differs by a factor of four in speed and sixteen in
 * power draw. The policy, ported from Factory Planner's defaults:
 *
 * <ul>
 *   <li><b>Lowest tier that can run the recipe.</b> Starting cheap and letting the user upgrade is
 *       the right default — the opposite would silently plan a factory the player cannot power.
 *   <li><b>Single blocks before multiblocks.</b> A multiblock needs a structure built and
 *       configured before its numbers mean anything, so it is a choice rather than a default.
 *   <li><b>Remember the user's choice per recipe type</b>, so picking an HV assembler once applies
 *       to every assembler step and survives re-expansion.
 * </ul>
 *
 * <p>Multiblocks are a special case on tier. Their definitions have no meaningful tier — the voltage
 * comes from the energy hatch, which is a build choice — so when one is picked deliberately the tier
 * defaults to the lowest the recipe will accept rather than to the machine's nominal zero.
 */
public final class MachinePicker {

    private MachinePicker() {}

    /** Machines that can run this recipe, best default first. */
    public static List<MfpMachine> candidates(RecipeIndex index, MfpRecipe recipe) {
        return candidates(index, recipe, Preferences.NO_DEFAULT_TIER);
    }

    /**
     * The same, aimed at the tier the player builds at (M8).
     *
     * @param defaultTier the standing tier, or {@link Preferences#NO_DEFAULT_TIER} for "the lowest
     *                    that can run it"
     */
    public static List<MfpMachine> candidates(RecipeIndex index, MfpRecipe recipe, int defaultTier) {
        List<MfpMachine> usable = new ArrayList<>();
        for (MfpMachine machine : index.machinesFor(recipe.recipeTypeId())) {
            if (canRun(machine, recipe)) {
                usable.add(machine);
            }
        }
        usable.sort(order(index, defaultTier));
        return usable;
    }

    /**
     * Best default first: single blocks, then low tier, then cheap to build, then by id.
     *
     * <p>The third term is not a nicety. Star-Technology gives the {@code tree_greenhouse} recipe
     * type to a greenhouse and to a fermenting arboreal rejuvenation monstrosity, and both are
     * multiblocks whose tier comes from a hatch — so the first two terms score them identically and
     * the tie fell through to the id, which put the late-game multiblock first because its name
     * sorts earlier. Alphabetical order is not a policy.
     *
     * <p>A machine nothing in the index makes sorts last among equals rather than first. It might be
     * a quest reward or a creative-only block, but it is certainly not the one to open with.
     */
    public static Comparator<MfpMachine> order(RecipeIndex index) {
        return order(index, Preferences.NO_DEFAULT_TIER);
    }

    /**
     * The same order, but aiming at {@code defaultTier} rather than at the bottom of the family.
     *
     * <p>"The player presses HV and every default machine becomes the HV member of its family" is the
     * general form of the lowest-tier rule, not a replacement for it: with no tier stated this is
     * exactly the old comparator, and with one stated the ordering is still a total order over the
     * same candidates, so nothing downstream — upgrade, downgrade, the picker's tabs — has to know.
     *
     * <p>A machine <em>above</em> the stated tier sorts after every machine at or below it, nearest
     * first. That is the falling back the milestone asks for: an EV-only recipe planned by someone
     * who builds HV gets the EV machine rather than no machine, and gets the cheapest one that will
     * do rather than the largest in the family.
     */
    public static Comparator<MfpMachine> order(RecipeIndex index, int defaultTier) {
        return Comparator
                .comparing(MfpMachine::multiblock)
                .thenComparingInt(machine -> tierRank(machine, defaultTier))
                .thenComparingInt(machine -> {
                    int cost = buildCost(index, machine);
                    return cost < 0 ? Integer.MAX_VALUE : cost;
                })
                .thenComparingInt(machine -> partsCost(index, machine))
                .thenComparing(MfpMachine::id);
    }

    /** How far from what the player wanted this machine's tier is; smaller is better. */
    private static int tierRank(MfpMachine machine, int defaultTier) {
        int tier = machine.tier();
        if (tier < 0) {
            // Untiered — a multiblock whose voltage is its hatch's — so its tier says nothing about
            // whether it is what the player wanted, and it sorts last among equals as it always did.
            return Integer.MAX_VALUE;
        }
        if (defaultTier == Preferences.NO_DEFAULT_TIER) {
            return tier;
        }
        // At or below the stated tier, highest first; above it, lowest first and always afterwards.
        return tier <= defaultTier ? defaultTier - tier : 1_000_000 + (tier - defaultTier);
    }

    /**
     * What it takes to obtain this machine: the lowest tier of any recipe that produces its item.
     *
     * <p>A machine is an item like any other, so the index already knows this — no new ingestion, and
     * nothing named. A greenhouse is assembled at LV, the monstrosity comes off an assembly line at
     * ZPM, and a machine crafted at a bench reports no tier at all, which is the cheapest there is.
     *
     * @return the tier, 0 for hand-crafted, or {@link RecipeScorer#UNKNOWN_BUILD_COST} when nothing
     *         in the index produces the machine
     */
    public static int buildCost(RecipeIndex index, MfpMachine machine) {
        if (index == null || machine == null) {
            return RecipeScorer.UNKNOWN_BUILD_COST;
        }
        MfpKey item = MfpKey.parse(machine.id(), MfpKey.Kind.ITEM);
        int cheapest = RecipeScorer.UNKNOWN_BUILD_COST;
        for (MfpRecipe recipe : index.producing(item)) {
            int tier = Math.max(0, recipe.minTier());
            if (cheapest == RecipeScorer.UNKNOWN_BUILD_COST || tier < cheapest) {
                cheapest = tier;
            }
        }
        return cheapest;
    }

    /**
     * What the <em>parts</em> of the cheapest build cost: the highest tier any ingredient needs.
     *
     * <p>{@link #buildCost} asks what tier the machine's own recipe runs at, and for anything crafted
     * at a bench the answer is zero — which is true and says nothing. Star-Technology gives the
     * {@code large_chemical_reactor} recipe type to three multiblocks, and two of them are shaped
     * crafting recipes: the large chemical reactor, built from an HV hull and an HV motor, and the
     * extreme chemical reactor, built from IV emitters, naquadah pipes and <b>a large chemical
     * reactor</b>. Both scored zero, the tie fell through to the id, and "extreme" sorts before
     * "large" — so every chemical line in the pack defaulted to a machine several ages away. That is
     * the greenhouse fault of §6d again, in the one shape the build-tier rule cannot see.
     *
     * <p>One level deep, deliberately. The tier of the thing you must already be able to make is a
     * strong signal and a cheap one; recursing would turn a comparator into a graph search, and the
     * ordering only has to separate machines that are otherwise identical.
     *
     * <p>Consulted <em>after</em> the build tier, so it can only decide cases that would otherwise be
     * settled alphabetically — which is never a defensible answer. A machine nothing produces keeps
     * sorting last.
     */
    public static int partsCost(RecipeIndex index, MfpMachine machine) {
        if (index == null || machine == null) {
            return Integer.MAX_VALUE;
        }
        MfpKey item = MfpKey.parse(machine.id(), MfpKey.Kind.ITEM);
        int cheapest = Integer.MAX_VALUE;
        for (MfpRecipe recipe : index.producing(item)) {
            int dearest = 0;
            for (MfpIngredient input : recipe.inputs()) {
                if (!input.consumed() || input.effectiveAmount() <= 0) {
                    continue;
                }
                dearest = Math.max(dearest, tierOf(index, input.primary()));
            }
            cheapest = Math.min(cheapest, dearest);
        }
        return cheapest;
    }

    /** The lowest tier anything in the index makes {@code key} at, or 0 for "no tier involved". */
    private static int tierOf(RecipeIndex index, MfpKey key) {
        int cheapest = -1;
        for (MfpRecipe recipe : index.producing(key)) {
            int tier = Math.max(0, recipe.minTier());
            if (cheapest < 0 || tier < cheapest) {
                cheapest = tier;
            }
        }
        // Nothing makes it — raw ore, or an item from the world — which is not evidence that the
        // machine is advanced. Zero, so an unobtainable part cannot outvote a tiered one.
        return Math.max(cheapest, 0);
    }

    /**
     * The configuration a freshly expanded line should start with.
     *
     * <p>Returns {@link MachineConfig#UNSET} when nothing in the index can run the recipe, which is
     * the honest answer for a hand-crafting recipe or an unindexed machine type. The resolver then
     * reports the line as unknown rather than inventing a rate for it.
     */
    public static MachineConfig pick(RecipeIndex index, MfpRecipe recipe, Plan plan) {
        return pick(index, recipe, plan, null);
    }

    /**
     * The same, with the player's standing default tier applied where they have not chosen (M8).
     *
     * <p>The tier is the blunt end of it. Where the player has described a particular machine —
     * its coils, its hatch, its rotor — {@link MachineDefaults} for that machine is applied too, and
     * beats the general tier, because it is the more specific thing they said.
     *
     * <p>Two things it must not do, and the order of the checks is what stops it. <b>A machine the
     * user picked explicitly stays picked</b> — both the built configuration and the type-level
     * choice are consulted before the tier is looked at, so raising the default tier moves the
     * defaults and leaves every decision alone. And <b>a recipe that cannot run at the chosen tier
     * falls back</b> rather than losing its machine, which is {@link #order(RecipeIndex, int)}'s
     * last bucket.
     */
    public static MachineConfig pick(RecipeIndex index, MfpRecipe recipe, Plan plan,
                                     Preferences preferences) {
        // A configuration the user built for this exact recipe outranks everything, including their
        // own policy for the recipe type: it carries coils, hatches and limits that a type-level
        // choice cannot express, and re-deriving it would discard the build they described.
        MachineConfig built = plan == null ? null : plan.machineConfig(recipe.id());
        if (built != null && built.machineId() != null && canRun(index.machine(built.machineId()), recipe)) {
            return built;
        }

        int defaultTier = preferences == null
                ? Preferences.NO_DEFAULT_TIER : preferences.defaultTierFor(plan);

        String chosenId = plan == null ? null : plan.machineChoice(recipe.recipeTypeId());
        if (chosenId != null) {
            MfpMachine chosen = index.machine(chosenId);
            if (chosen != null && canRun(chosen, recipe)) {
                return configFor(chosen, recipe, defaultTier, preferences);
            }
        }

        List<MfpMachine> usable = candidates(index, recipe, defaultTier);
        return usable.isEmpty()
                ? MachineConfig.UNSET : configFor(usable.get(0), recipe, defaultTier, preferences);
    }

    /** Step to the next tier up, keeping every other setting. Null when already at the top. */
    public static MachineConfig upgrade(RecipeIndex index, MfpRecipe recipe, MachineConfig current) {
        return step(index, recipe, current, 1);
    }

    /** Step to the next tier down. Null when the recipe cannot run any lower. */
    public static MachineConfig downgrade(RecipeIndex index, MfpRecipe recipe, MachineConfig current) {
        return step(index, recipe, current, -1);
    }

    private static MachineConfig step(RecipeIndex index, MfpRecipe recipe, MachineConfig current, int direction) {
        List<MfpMachine> usable = candidates(index, recipe);
        if (usable.isEmpty() || current == null || current.machineId() == null) {
            return null;
        }
        int at = -1;
        for (int i = 0; i < usable.size(); i++) {
            if (usable.get(i).id().equals(current.machineId())) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            return null;
        }
        int next = at + direction;
        if (next < 0 || next >= usable.size()) {
            return null;
        }
        MachineConfig stepped = configFor(usable.get(next), recipe);
        // Structure options and limits are the user's; only the machine and tier move.
        return new MachineConfig(stepped.machineId(), stepped.tier(), current.parallels(),
                current.limit(), current.forceLimit(), current.structureOptions());
    }

    private static MachineConfig configFor(MfpMachine machine, MfpRecipe recipe) {
        return configFor(machine, recipe, Preferences.NO_DEFAULT_TIER, null);
    }

    /**
     * The configuration this machine starts at, before the plan says anything about the line.
     *
     * <p>Two standing preferences meet here and they are not the same statement. The default tier is
     * about the factory — "I build at HV" — and the machine's own build is about one machine — "my
     * blast furnace has HSS-G coils and an EV hatch". The specific one wins, because a player who
     * has described a particular machine has said something the general answer cannot know.
     */
    private static MachineConfig configFor(MfpMachine machine, MfpRecipe recipe, int defaultTier,
                                           Preferences preferences) {
        MachineDefaults build = preferences == null
                ? MachineDefaults.NONE : preferences.machineDefaults(machine.id());
        int tier = machine.tier();
        if (tier < 0) {
            // A multiblock: its voltage is the hatch's, so it is a build choice rather than a
            // property of the machine — which makes it exactly what a standing default tier is for.
            // The recipe's own minimum still wins, because a hatch too small to run the recipe is
            // not a plan, it is a machine that never starts.
            tier = Math.max(0, recipe.minTier());
            int wanted = build.hasTier() ? build.tier() : defaultTier;
            if (wanted > tier && recipe.usesEnergy()) {
                // Only where a hatch means anything. A coke oven and a primitive blast furnace take
                // no power at all, so calling one "tier 3" would be a claim about a structure that
                // has no voltage — it changes no number and misdescribes the build.
                tier = wanted;
            }
        }
        return build.applyTo(MachineConfig.of(machine.id(), tier));
    }

    private static boolean canRun(MfpMachine machine, MfpRecipe recipe) {
        if (machine == null) {
            return false;
        }
        if (!machine.runs(recipe.recipeTypeId())) {
            return false;
        }
        // An untiered machine carries no voltage claim, so it cannot be excluded on tier.
        return machine.tier() < 0 || recipe.minTier() < 0 || machine.tier() >= recipe.minTier();
    }
}
