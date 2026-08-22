package dev.mfp.core.select;

import dev.mfp.core.index.RecipeIndex;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.plan.Preferences;

import dev.mfp.core.behaviour.GtTiers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The tier the player builds at, treated as a requirement rather than a preference (M17).
 *
 * <p><b>The fault this exists to fix.</b> The player states a tier — the pack's own test world says
 * HV — and MFP charged for exceeding it: four points per tier of the recipe and three per tier of
 * the machine, deliberately small enough not to overturn a genuinely better recipe. So a LuV route
 * won whenever the alternatives were sixty points worse, and when it was the <em>only</em> route it
 * won at any score at all. The pack's HV motor plan came back with fourteen lines above the stated
 * tier; asking for nitrogen plasma answered with a fusion reactor an age away, offered without
 * comment. A machine you cannot craft is not a slower option, it is not an option, and a plan full
 * of them is a shopping list for a different age of the game.
 *
 * <p><b>Two questions, and they are not the same question.</b>
 *
 * <ul>
 *   <li><b>Runnable</b> — the recipe's own voltage, and a machine at or below the ceiling to run it
 *       on. Cheap: it is a property of the recipe and its type.
 *   <li><b>Craftable</b> — the machine's own item is obtainable at or below the ceiling, and so is
 *       every part of it, <em>to any depth</em>. That is a graph search, and it is the same
 *       {@link Unavailability} fixpoint asked a second time.
 * </ul>
 *
 * <p><b>So the answer is built in two layers, and the layering is the whole design.</b> Runnable is
 * decided first and its consequences followed to a fixpoint; craftable is then decided <em>using
 * that answer</em>, and its consequences followed again. Two passes rather than one because the
 * question is circular otherwise — whether a recipe is allowed depends on whether its machine is
 * obtainable, which depends on which recipes are allowed. Two layers break the circle at a defined
 * place and terminate; iterating to a joint fixpoint would refuse marginally more and is not worth
 * a second unbounded loop. Stated here because it is a real limit: a machine made only by a machine
 * made only by an over-tier machine is caught, and a chain of refusals that only closes on the
 * third layer is not.
 *
 * <p><b>Why not {@code MachinePicker.partsCost}.</b> It answers the same question one level deep and
 * says in its own javadoc why: recursing would turn a comparator into a graph search. That is the
 * right call for a comparator and the wrong one for a constraint. The pack shows the gap — the
 * {@code large_chemical_reactor} recipe type has three multiblocks, two of them shaped crafting
 * recipes: the large chemical reactor from an HV hull and an HV motor, and the extreme chemical
 * reactor from IV emitters, naquadah pipes <b>and a large chemical reactor</b>. One level separates
 * those two. Two levels are what a player actually pays. So the comparator keeps its cheap halves
 * for ordering and the constraint gets its own answer, rather than the shallow test being promoted
 * into a job it was written not to do.
 *
 * <p><b>A third question, and it is not a voltage at all.</b> The two above are about what a recipe
 * needs and what a machine supplies, and both are voltages — payable with a bigger hatch. A
 * GregTech <em>component</em>'s tier is not. An IV emitter has a shaped crafting recipe and a tier 1
 * assembler one, so "some recipe at or below your tier, every input likewise" concludes that a
 * player at HV can hand-craft one; the pack's extreme chemical reactor takes two of them and an IV
 * circuit, and MFP offered it at HV on exactly that reasoning. <b>The tier is in the item, not in
 * the recipe that assembles it.</b> So a component above the ceiling is refused outright, before any
 * recipe for it is considered, and it seeds the fixpoint like a blacklisted item does. The
 * classification comes from GregTech's own tags rather than from a list MFP maintains — see
 * {@code GtComponentTiers}.
 *
 * <p><b>Untiered machines get both questions asked, in different places.</b> A multiblock's tier is
 * its hatch's, so {@code MfpMachine.tier()} is {@code -1} and says nothing about whether the player
 * can have one — {@code tierRank} sorts it last among equals, which is a sensible default for a
 * comparator and no answer at all for a ceiling. The voltage question for these is entirely
 * {@code recipe.minTier()}, the hatch the line would be configured at; the structure question is
 * the build fixpoint over the controller's own item. A multiblock with an affordable hatch that the
 * player cannot build is refused, and so is one they own that the recipe would need a ZPM hatch to
 * run in.
 *
 * <p><b>A recipe with no machine is refused too</b>, which is the limiting case rather than a
 * separate rule: unbuildable at every tier is unbuildable at this one.
 * {@code start:plasma_generator} has no machine in the index — {@code mfp machines} says so plainly
 * — and its nitrogen recipe still ranked twelfth of twenty-nine ways to make nitrogen. Note that
 * this is a claim about a recipe <em>type</em> with no machine, not about hand crafting: the
 * crafting table is a machine in the index like any other, so shaped recipes are unaffected.
 *
 * <p><b>Inert with no tier set.</b> {@link Preferences#NO_DEFAULT_TIER} means the player has not
 * said, and a filter this broad has to be provably off when it is off — so every method here
 * returns "fine" and the chooser never builds a closure.
 */
final class TierCeiling {

    /** How many items the ceiling's consequences may reach before the search stops.
     *
     * <p>Far larger than the blacklist's, and for the reason the blacklist's is small: blocking one
     * item is a perturbation, and declaring a tier refuses a whole age of the game at once. Hitting
     * the cap leaves a partial answer, which errs towards allowing a recipe rather than refusing
     * one, so it is a bound on the work and not on the correctness of what it does say. */
    static final int REACH_LIMIT = 65_536;

    /**
     * How deep a chain one refusal may poison, the same bound the blacklist's closure observes.
     *
     * <p>Duplicated as a constant rather than reached for across the class boundary, and it must
     * stay equal to {@code RecipeChooser.MAX_BLOCK_ROUNDS}: the milestone's claim is that one bound
     * covers every predicate, because it is the same claim about how far a refusal reaches.
     */
    static final int MAX_ROUNDS = 6;

    /** @see #consequences() */
    private Map<MfpKey, MfpKey> consequences;

    private final RecipeIndex index;
    private final Plan plan;
    private final int ceiling;

    /**
     * Per recipe type: the machine tiers available, and whether any of them is untiered.
     *
     * <p>The seed pass asks this of every recipe in the index — 64,078 of them in the pack — and
     * the answer depends only on the type, so it is worked out once per type rather than once per
     * recipe.
     */
    private final Map<String, int[]> tiersByType = new HashMap<>();

    /** @see #runnableSeeds() */
    private Set<MfpKey> runnableSeeds;

    /** @see #runnableRule() */
    private Unavailability.Rule runnableRule;

    /** @see #runnableLost() */
    private Map<MfpKey, MfpKey> runnableLost;

    /** @see #seeds() */
    private Set<MfpKey> seeds;

    /** @see #rule() */
    private Unavailability.Rule rule;

    /** Answers to {@link #beyond}, which the two seed passes and then the walk all ask. */
    private final Map<String, String> beyondCache = new HashMap<>();

    /** Probe ceilings one tier at a time, for {@link #unlockTier}; shared across every refused item. */
    private final Map<Integer, TierCeiling> probes = new HashMap<>();

    /** @see #unlockTier */
    private final Map<MfpKey, Integer> unlockCache = new HashMap<>();

    TierCeiling(RecipeIndex index, Plan plan, int ceiling) {
        this.index = index;
        this.plan = plan;
        this.ceiling = ceiling;
    }

    /** True when the player has stated a tier and this plan has not switched the ceiling off. */
    boolean isOn() {
        return ceiling != Preferences.NO_DEFAULT_TIER && (plan == null || plan.tierCeiling());
    }

    int tier() {
        return ceiling;
    }

    /**
     * Why this recipe is beyond the ceiling, in the words an import reason uses, or null if it is
     * not.
     *
     * <p>A sentence rather than a boolean because every caller needs to say it: the import reason,
     * the picker's mark against a recipe it still lists, and {@code mfp explain}. A refusal the user
     * cannot read is indistinguishable from MFP having lost the recipe.
     */
    String beyond(MfpRecipe recipe) {
        if (!isOn()) {
            return null;
        }
        String cached = beyondCache.get(recipe.id());
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String answer = notRunnable(recipe);
        if (answer == null) {
            answer = notCraftable(recipe);
        }
        beyondCache.put(recipe.id(), answer == null ? "" : answer);
        return answer;
    }

    /** The recipe's own voltage, and a machine at or below the ceiling to run it on. Layer one. */
    private String notRunnable(MfpRecipe recipe) {
        if (recipe.minTier() > ceiling) {
            return "it runs at tier " + recipe.minTier() + " and you build at tier " + ceiling;
        }
        int[] tiers = machineTiers(recipe.recipeTypeId());
        if (tiers.length == 0) {
            return "nothing in the index runs " + recipe.recipeTypeId();
        }
        int cheapest = -1;
        for (int tier : tiers) {
            if (tier < 0) {
                // Untiered: a multiblock, whose voltage is the hatch the line is configured at, and
                // that is recipe.minTier() above. Nothing more to ask at this slice.
                return null;
            }
            if (tier < recipe.minTier()) {
                continue;
            }
            if (tier <= ceiling) {
                return null;
            }
            if (cheapest < 0 || tier < cheapest) {
                cheapest = tier;
            }
        }
        return cheapest < 0
                ? "nothing in the index runs " + recipe.recipeTypeId()
                : "the cheapest machine that runs it is tier " + cheapest
                        + " and you build at tier " + ceiling;
    }

    /**
     * Whether any machine that could run this recipe is one the player can actually build. Layer two.
     *
     * <p>One buildable machine is enough, and it is enough however many others are out of reach —
     * which is why this returns on the first one rather than counting, exactly as the fixpoint's own
     * "or null if something can" does.
     *
     * <p>A machine <em>nothing</em> in the index produces is not refused here. It might be a quest
     * reward or a creative-only block, and "the pack cannot make it" is not the ceiling's doing —
     * the same rule the fixpoint applies to raw ore. {@link MachinePicker#buildCost} already sorts
     * such a machine last among equals, which is the right weight for a fact this uncertain.
     */
    private String notCraftable(MfpRecipe recipe) {
        List<MfpMachine> machines = index.machinesFor(recipe.recipeTypeId());
        MfpKey missing = null;
        String blame = null;
        for (MfpMachine machine : machines) {
            if (!MachinePicker.canRun(machine, recipe) || machine.tier() > ceiling) {
                continue;
            }
            MfpKey part = missingPartOf(machine);
            if (part == null) {
                return null;
            }
            if (missing == null) {
                missing = part;
                blame = machine.id();
            }
        }
        if (missing == null) {
            return null;
        }
        // A machine whose own recipe is out of reach blames itself, and saying "X needs X" is a
        // sentence nobody can act on. The two cases read differently because they are different
        // findings: one is the machine, the other is a part several levels inside it.
        int component = componentTier(missing);
        if (component >= 0) {
            return "the only machine that runs it, " + blame + ", needs " + missing
                    + ", which is a tier " + component + " (" + GtTiers.name(component)
                    + ") component and you build at tier " + ceiling;
        }
        return missing.toString().equals(blame)
                ? "nothing at or below tier " + ceiling + " makes " + blame
                        + ", the only machine that runs it"
                : "the only machine that runs it, " + blame + ", needs " + missing
                        + ", and nothing at or below tier " + ceiling + " makes that";
    }

    /**
     * The part in the way of building this machine, or null if the player could build it.
     *
     * <p>Layer one's fixpoint answers it: the machine is an item like any other, so "can I obtain
     * this item at or below my tier, to any depth" is a question the closure has already answered
     * for every item it could reach. No new mechanism, and no second definition of craftable that
     * could drift from the first.
     */
    MfpKey missingPartOf(MfpMachine machine) {
        if (!isOn() || machine == null) {
            return null;
        }
        return Unavailability.causeOf(MfpKey.parse(machine.id(), MfpKey.Kind.ITEM),
                runnableRule(), runnableLost());
    }

    /**
     * The lowest tier above this one at which {@code key} becomes obtainable, or {@code -1} if no
     * tier does.
     *
     * <p>The thing a player actually wants when a plan comes back short. Not "you cannot make
     * perfluoroelastomer at HV" but "you cannot, and the nearest tier that can is EV".
     *
     * <p><b>The lowest, so it is searched upwards one tier at a time</b> rather than reported from
     * the first thing that failed — the tier of the first obstacle is not the tier that clears the
     * chain, because clearing it can reveal another. Each probe is a whole ceiling with its own two
     * passes, so the probes are shared across every refused item in the plan and the answer is
     * cached per item; the common case stops after one or two.
     */
    int unlockTier(MfpKey key) {
        if (!isOn()) {
            return -1;
        }
        Integer known = unlockCache.get(key);
        if (known != null) {
            return known;
        }
        int found = -1;
        for (int tier = ceiling + 1; tier <= GtTiers.MAX; tier++) {
            TierCeiling probe = probes.computeIfAbsent(tier, t -> new TierCeiling(index, plan, t));
            if (Unavailability.causeOf(key, probe.rule(), probe.consequences()) == null) {
                found = tier;
                break;
            }
        }
        unlockCache.put(key, found);
        return found;
    }

    /** Machine tiers for this recipe type, cached; {@code -1} appears for an untiered machine. */
    private int[] machineTiers(String recipeTypeId) {
        int[] cached = tiersByType.get(recipeTypeId);
        if (cached != null) {
            return cached;
        }
        List<MfpMachine> machines = index.machinesFor(recipeTypeId);
        int[] tiers = new int[machines.size()];
        for (int i = 0; i < machines.size(); i++) {
            tiers[i] = machines.get(i).tier();
        }
        tiersByType.put(recipeTypeId, tiers);
        return tiers;
    }

    /**
     * This ceiling as one of {@link Unavailability}'s rules.
     *
     * <p>Where the blacklist's rule refuses <em>items</em> and lets the closure work out which
     * recipes that costs, this one refuses <em>recipes</em> and lets the closure work out which
     * items that costs. The mechanism does not care which way round it is, which is the argument
     * for having written it once.
     */
    Unavailability.Rule rule() {
        if (rule == null) {
            rule = newRule(this::seeds, this::beyond);
        }
        return rule;
    }

    /** Layer one's rule: runnable only, and the input to layer two's craftability question. */
    private Unavailability.Rule runnableRule() {
        if (runnableRule == null) {
            runnableRule = newRule(this::runnableSeeds, this::notRunnable);
        }
        return runnableRule;
    }

    /** Layer one's closure. @see #missingPartOf */
    private Map<MfpKey, MfpKey> runnableLost() {
        if (runnableLost == null) {
            runnableLost = Unavailability.closure(index, runnableRule(), MAX_ROUNDS, REACH_LIMIT);
        }
        return runnableLost;
    }

    /** Both layers' closure — the one the chooser walks with. */
    Map<MfpKey, MfpKey> consequences() {
        if (consequences == null) {
            consequences = Unavailability.closure(index, rule(), MAX_ROUNDS, REACH_LIMIT);
        }
        return consequences;
    }

    private Unavailability.Rule newRule(Supplier<Set<MfpKey>> seeds,
                                        Function<MfpRecipe, String> refuses) {
        return new Unavailability.Rule() {
            @Override
            public Set<MfpKey> refusedItems() {
                return seeds.get();
            }

            @Override
            public MfpKey refusedBecause(MfpKey key) {
                return seeds.get().contains(key) ? key : null;
            }

            @Override
            public boolean setsAside(MfpRecipe recipe) {
                // "You hid it" stays a separate answer from "you cannot build it", exactly as it is
                // separate from "you have none of these".
                return plan != null && plan.blacklist().contains(recipe.id());
            }

            @Override
            public boolean refusesOutright(MfpRecipe recipe) {
                return refuses.apply(recipe) != null;
            }

            @Override
            public boolean supplied(MfpKey key) {
                return plan != null && plan.rawMaterials().contains(key);
            }

        };
    }

    /**
     * The seeds, worked out once.
     *
     * <p>Held on the ceiling rather than on the rule, and the rule held too, because this is where
     * the first attempt went wrong: {@code rule()} returned a fresh instance whose lazily-computed
     * seeds were therefore also fresh, and {@code beyondCeiling} asks for a rule once per candidate
     * recipe. That is a scan of all 64,078 pack recipes per candidate — a plan that took seconds
     * took minutes. One ceiling, one rule, one pass.
     */
    private Set<MfpKey> seeds() {
        if (seeds == null) {
            seeds = unmakeableHere(this::beyond);
        }
        return seeds;
    }

    /** Layer one's seeds: items no <em>runnable</em> recipe makes, before craftability is asked. */
    private Set<MfpKey> runnableSeeds() {
        if (runnableSeeds == null) {
            runnableSeeds = unmakeableHere(this::notRunnable);
        }
        return runnableSeeds;
    }

    /**
     * Every item the index can make and this ceiling cannot: the closure's starting frontier.
     *
     * <p>The blacklist's seeds are handed to it — the user named them. Nobody names these, so they
     * are found in one pass over the index: an item with producers, none of which this ceiling
     * allows, is an item the player cannot have. The pass is why the answer is cached per ceiling
     * rather than recomputed per key.
     *
     * <p>A recipe the plan hides contributes nothing either way, so an item whose only recipes the
     * user hid is not reported as a casualty of their tier.
     */
    private Set<MfpKey> unmakeableHere(Function<MfpRecipe, String> refuses) {
        Map<MfpKey, Boolean> anyRoute = new HashMap<>();
        // Components above the ceiling are refused before any recipe for them is looked at: their
        // tier is a gate rather than a cost, and every route to one leads through the same gate.
        // Put in as "no route" rather than skipped, so the loop below cannot overturn it by finding
        // a hand-craft recipe - which is precisely how an IV emitter got past the first version.
        for (Map.Entry<MfpKey, Integer> component : index.componentTiers().entrySet()) {
            if (component.getValue() > ceiling) {
                anyRoute.put(component.getKey(), Boolean.FALSE);
            }
        }
        Set<String> hidden = plan == null ? Set.of() : plan.blacklist();
        for (MfpRecipe recipe : index.all()) {
            if (hidden.contains(recipe.id())) {
                continue;
            }
            boolean allowed = refuses.apply(recipe) == null;
            for (MfpOutput output : recipe.outputs()) {
                MfpKey key = output.key();
                if (key.isPseudo() || Unavailability.isDeadEnd(recipe, key)
                        || gated(key)) {
                    continue;
                }
                anyRoute.merge(key, allowed, Boolean::logicalOr);
            }
        }
        Set<MfpKey> unmakeable = new LinkedHashSet<>();
        for (Map.Entry<MfpKey, Boolean> entry : anyRoute.entrySet()) {
            if (!entry.getValue() && !supplied(entry.getKey())) {
                unmakeable.add(entry.getKey());
            }
        }
        return unmakeable;
    }

    private boolean supplied(MfpKey key) {
        return plan != null && plan.rawMaterials().contains(key);
    }

    /**
     * Whether this item is a component the ceiling gates outright.
     *
     * <p>The raw set still wins, above: "I have a supply of IV emitters" is the player telling MFP
     * something about their save that no tag can know, and it is the same statement that makes an
     * ore a raw material. The gate is a default, not a decree.
     */
    private boolean gated(MfpKey key) {
        Integer tier = index.componentTiers().get(key);
        return tier != null && tier > ceiling;
    }

    /**
     * The tier of {@code key} as a component, or {@code -1} if it is not one.
     *
     * <p>Public to the package so a refusal can say "it is an IV component" rather than the
     * fixpoint's more general "nothing at or below tier 3 makes that", which is true and, for a
     * component, misleading: nothing at any tier below its own ever will.
     */
    int componentTier(MfpKey key) {
        Integer tier = index.componentTiers().get(key);
        return tier == null ? -1 : tier;
    }

    /**
     * The machines that may run this recipe under the ceiling, best first, or the unfiltered list
     * when the ceiling leaves none.
     *
     * <p>The fallback is not a hole in the rule. By the time a line exists at all the ceiling has
     * either allowed the recipe or been overruled by a pin, a standing default or the target
     * exemption — and a line with no machine reports no numbers, which is a worse answer than a
     * machine the plan has already said is above the tier. The place the ceiling refuses a recipe is
     * the chooser, not here.
     */
    List<MfpMachine> allowed(List<MfpMachine> candidates) {
        if (!isOn() || candidates.isEmpty()) {
            return candidates;
        }
        List<MfpMachine> kept = new ArrayList<>(candidates.size());
        for (MfpMachine machine : candidates) {
            // Both questions, because a machine can fail either: its voltage is above the ceiling,
            // or the player cannot build one. The second is what keeps the extreme chemical reactor
            // out of every chemical line in the pack rather than merely sorting it later.
            if (machine.tier() <= ceiling && missingPartOf(machine) == null) {
                kept.add(machine);
            }
        }
        return kept.isEmpty() ? candidates : kept;
    }
}
