package dev.mfp.core.plan;

import dev.mfp.core.model.MfpKey;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What the player has decided once and means everywhere: their standing preferences.
 *
 * <p>The gap this closes is the largest one between MFP and how a pack is actually played. The
 * player already has factories — a way they make steel, the log they farm, the tier they build at,
 * the material they have never set up — and until now every plan was expanded as though none of that
 * existed, so the same corrections had to be made again in every plan.
 *
 * <p>Five preferences, and they are deliberately the same kinds of decision a {@link Plan} already
 * records per plan:
 *
 * <ul>
 *   <li><b>Default recipes</b> — {@code Plan.recipeChoices} promoted from plan-local to global.
 *   <li><b>Preferred items</b> — which item an ambiguous tag input resolves to.
 *   <li><b>Blocked items</b> — "I have no inferium essence", which must remove every chain through
 *       it from consideration rather than merely from the display.
 *   <li><b>A default tier</b> — the general form of "lowest tier that can run it".
 *   <li><b>Machine builds</b> — the tier is the blunt form of this one. What decides a multiblock's
 *       rate is what the player built inside it, and a coil belongs to the blast furnace rather
 *       than to every machine at once: see {@link MachineDefaults}.
 * </ul>
 *
 * <p><b>A plan always wins.</b> These are defaults, not rules: a pin, a machine choice or a plan's
 * own blocked and allowed lists are consulted first everywhere this class is read. That is what
 * makes them safe to apply automatically — being overruled by a standing preference the user set
 * weeks ago, in a plan where they had just said otherwise, is exactly the untrustworthiness
 * {@link Plan#chooseRecipe} exists to prevent.
 *
 * <p>Mutable, and deliberately <em>not</em> a shared constant: an empty instance is obtained from
 * {@link #none()}, which returns a fresh one, so nothing can quietly write into everyone else's
 * defaults. The game side holds exactly one live instance and loads it from disk.
 */
public final class Preferences {

    /** No tier stated, so machine selection keeps its own "lowest that can run it" rule. */
    public static final int NO_DEFAULT_TIER = -1;

    private final Map<MfpKey, String> defaultRecipes = new LinkedHashMap<>();
    private final Map<String, MachineDefaults> machineDefaults = new LinkedHashMap<>();
    private final Set<MfpKey> preferredItems = new LinkedHashSet<>();
    private final Set<MfpKey> blockedItems = new LinkedHashSet<>();
    private int defaultTier = NO_DEFAULT_TIER;
    private boolean autoResolve;

    /** A fresh empty set of preferences: what a caller with none gets. */
    public static Preferences none() {
        return new Preferences();
    }

    public boolean isEmpty() {
        return defaultRecipes.isEmpty() && preferredItems.isEmpty() && blockedItems.isEmpty()
                && machineDefaults.isEmpty() && defaultTier == NO_DEFAULT_TIER && !autoResolve;
    }

    /**
     * Whether a new plan expands below its target on its own.
     *
     * <p><b>Off.</b> Automatic expansion is good on material chains and unreliable above them
     * (STATUS §4b.16), it finds the loops a pack builds on purpose about half the time (§11.10), and
     * the plan it produces when it is wrong is one the user has to unpick rather than one they can
     * correct. So the default is the thing that always works — the player adds the recipes and the
     * solver balances what is there, which is how Factory Planner has always worked — and automatic
     * expansion is a per-plan opt-in until it can be trusted to find a good chain of ten steps.
     *
     * <p>"By hand" does not mean "one line". {@link #defaultRecipes()} are the user's own decisions,
     * so expansion follows them down as far as they go and stops only where the next answer would
     * have to be the scorer's — which makes the import list exactly the set of items they have never
     * decided about, and makes a well-populated defaults file into a chain that builds itself
     * without ever guessing.
     *
     * <p>A standing preference rather than a constant because it is a judgement that will change:
     * when the chooser is good enough (`PLAN.md` §13a) this flips back, and no saved plan has to
     * move for it to. A plan that states its own answer keeps it either way — {@link Plan#autoResolve}
     * is what expansion actually reads, and this only decides what a <em>new</em> plan starts with.
     */
    public boolean autoResolve() {
        return autoResolve;
    }

    public Preferences autoResolve(boolean enabled) {
        this.autoResolve = enabled;
        return this;
    }

    // ------------------------------------------------------------ default recipes

    /** The way the player makes each item, by recipe id. */
    public Map<MfpKey, String> defaultRecipes() {
        return Map.copyOf(defaultRecipes);
    }

    public Preferences defaultRecipe(MfpKey key, String recipeId) {
        defaultRecipes.put(Objects.requireNonNull(key, "key"),
                Objects.requireNonNull(recipeId, "recipeId"));
        return this;
    }

    public String defaultRecipe(MfpKey key) {
        return defaultRecipes.get(key);
    }

    public Preferences clearDefaultRecipe(MfpKey key) {
        defaultRecipes.remove(key);
        return this;
    }

    /**
     * Adopt a solved plan's recipes as the standing defaults for what it makes.
     *
     * <p>"This is <em>the</em> way I make steel" is a statement about a plan the player has built and
     * is happy with, so it is taken from the plan rather than typed in: every pin it carries, plus
     * the recipe each of its targets is actually produced by.
     *
     * <p>Only the targets, not every line. A plan for steel plate happens to contain a recipe for
     * every intermediate down to crushed ore, and promoting all of them would silently fix hundreds
     * of decisions the user never looked at — including the ones the scorer is known to be unreliable
     * about (STATUS §4b.16). The pins are exactly the decisions they did look at.
     *
     * @return how many defaults this changed, for the button that reports what it did
     */
    public int adoptFrom(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        int changed = 0;
        for (Map.Entry<MfpKey, String> pin : plan.recipeChoices().entrySet()) {
            if (!pin.getValue().equals(defaultRecipes.put(pin.getKey(), pin.getValue()))) {
                changed++;
            }
        }
        for (TargetOutput target : plan.targets()) {
            String recipeId = producerOf(plan, target.key());
            if (recipeId != null && !recipeId.equals(defaultRecipes.put(target.key(), recipeId))) {
                changed++;
            }
        }
        return changed;
    }

    /** The plan's own line that makes {@code key}, or null when nothing on it does. */
    private static String producerOf(Plan plan, MfpKey key) {
        for (Line line : plan.allLines()) {
            if (line.recipe().produces(key)) {
                return line.recipe().id();
            }
        }
        return null;
    }

    // ----------------------------------------------------------- preferred items

    /**
     * Items the player wants used wherever an input accepts several — the log they actually farm.
     *
     * <p>The same shape as {@link Plan#preferredItems()} and applied the same way, by reordering the
     * ingredient's candidates so the chooser and the solver cannot disagree about what a line eats.
     */
    public Set<MfpKey> preferredItems() {
        return Set.copyOf(preferredItems);
    }

    public Preferences preferItem(MfpKey key) {
        preferredItems.add(Objects.requireNonNull(key, "key"));
        return this;
    }

    public Preferences clearPreferredItem(MfpKey key) {
        preferredItems.remove(key);
        return this;
    }

    /**
     * Every preferred item, the plan's last so that it wins.
     *
     * <p>Order is load-bearing: {@link dev.mfp.core.model.MfpRecipe#withPreferredInputs} applies each
     * key in turn and each moves to the front of the ingredient it belongs to, so the last one
     * mentioned is the one a line ends up eating. A plan that says "oak here" must beat a standing
     * "spruce everywhere", which is the whole meaning of these being defaults.
     */
    public Set<MfpKey> preferredItemsFor(Plan plan) {
        Set<MfpKey> ordered = new LinkedHashSet<>(preferredItems);
        if (plan != null) {
            ordered.addAll(plan.preferredItems());
        }
        return ordered;
    }

    // ------------------------------------------------------------- blocked items

    /**
     * Items the player has no supply of, so no chain may run through them.
     *
     * <p>Different in kind from {@link Plan#blacklist()}, which hides <em>recipes</em>. Blocking an
     * item makes every recipe that consumes it unusable, wherever it appears — which is the only
     * form the statement "I have no inferium essence set up" can usefully take, since the alternative
     * is hunting down the forty recipes that quietly want one.
     */
    public Set<MfpKey> blockedItems() {
        return Set.copyOf(blockedItems);
    }

    public Preferences blockItem(MfpKey key) {
        blockedItems.add(Objects.requireNonNull(key, "key"));
        return this;
    }

    public Preferences unblockItem(MfpKey key) {
        blockedItems.remove(key);
        return this;
    }

    /**
     * Whether this plan may not use {@code key} at all.
     *
     * <p>The plan gets both directions of override: it may block something globally allowed, and it
     * may allow something globally blocked. The exception is what makes a standing block safe to
     * set — "I have none" stops being true the day it is built, and one plan wanting to assume it
     * already is should not have to unpick the preference for every other plan.
     */
    public boolean blocks(Plan plan, MfpKey key) {
        if (plan != null) {
            if (plan.allowedItems().contains(key)) {
                return false;
            }
            if (plan.blockedItems().contains(key)) {
                return true;
            }
        }
        return blockedItems.contains(key);
    }

    // ----------------------------------------------------------- machine builds

    /**
     * How the player's own copy of each machine is built, by machine id.
     *
     * <p>The finest of the standing preferences and the one that moves the numbers most. A tier
     * says which hatch every multiblock has; this says what is inside one of them — the coils in
     * the blast furnace, the parallel hatch in the assembly line, the rotor in the turbine — which
     * is what a multiblock's throughput actually depends on (STATUS §4a.9, §4b.13).
     */
    public Map<String, MachineDefaults> machineDefaults() {
        return Map.copyOf(machineDefaults);
    }

    /** The player's build for one machine, or {@link MachineDefaults#NONE} when they have not said. */
    public MachineDefaults machineDefaults(String machineId) {
        MachineDefaults defaults = machineId == null ? null : machineDefaults.get(machineId);
        return defaults == null ? MachineDefaults.NONE : defaults;
    }

    /**
     * State how a machine is built, or forget it.
     *
     * <p>An empty build is stored as no build rather than as an entry saying nothing, so the screens
     * that list what the player has decided list only decisions — and clearing the last field of a
     * machine leaves the same state as never having touched it.
     */
    public Preferences machineDefaults(String machineId, MachineDefaults defaults) {
        Objects.requireNonNull(machineId, "machineId");
        if (defaults == null || defaults.isEmpty()) {
            machineDefaults.remove(machineId);
        } else {
            machineDefaults.put(machineId, defaults);
        }
        return this;
    }

    public Preferences clearMachineDefaults(String machineId) {
        machineDefaults.remove(machineId);
        return this;
    }

    // -------------------------------------------------------------- default tier

    /**
     * The voltage tier the player builds at, or {@link #NO_DEFAULT_TIER}.
     *
     * <p>Not a cap and not a floor: it is where machine selection aims. A recipe that cannot run
     * there falls back to the lowest tier that can, rather than failing — a plan is a statement about
     * what to build, and refusing to plan an EV recipe because the player usually builds HV would be
     * an answer nobody asked for.
     */
    public int defaultTier() {
        return defaultTier;
    }

    public Preferences defaultTier(int tier) {
        this.defaultTier = tier < 0 ? NO_DEFAULT_TIER : tier;
        return this;
    }

    /** The tier this plan builds at: its own if it states one, otherwise the standing default. */
    public int defaultTierFor(Plan plan) {
        if (plan != null && plan.defaultTier() != NO_DEFAULT_TIER) {
            return plan.defaultTier();
        }
        return defaultTier;
    }
}
