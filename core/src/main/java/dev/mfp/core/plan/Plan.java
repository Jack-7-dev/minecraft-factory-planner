package dev.mfp.core.plan;

import dev.mfp.core.model.MfpKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A factory plan: what the user wants, and how they have chosen to make it.
 *
 * <p><b>Every rate here is per second</b>, without exception (plan P2). Per-minute, per-belt and
 * stacks-per-minute are display conversions applied at render time, never stored. Anything holding
 * a per-minute figure is a bug waiting to be multiplied twice.
 */
public final class Plan {

    private String name;
    private boolean named;
    private final List<TargetOutput> targets = new ArrayList<>();
    private final Floor root = new Floor();
    private final Set<MfpKey> freeItems = new LinkedHashSet<>();
    private final Set<MfpKey> rawMaterialsAdded = new LinkedHashSet<>();
    private final Set<MfpKey> rawMaterialsRemoved = new LinkedHashSet<>();
    private Set<MfpKey> rawMaterialCache;
    private Set<MfpKey> rawMaterialCacheBase;
    private final Set<MfpKey> preferredItems = new LinkedHashSet<>();
    private final Map<MfpKey, String> recipeChoices = new LinkedHashMap<>();
    private final Map<String, String> machineChoices = new LinkedHashMap<>();
    private final Map<String, MachineConfig> machineConfigs = new LinkedHashMap<>();
    private final Set<String> blacklist = new LinkedHashSet<>();
    private final Set<MfpKey> blockedItems = new LinkedHashSet<>();
    private final Set<MfpKey> allowedItems = new LinkedHashSet<>();
    private final List<String> displayOrder = new ArrayList<>();
    private SolverMode solverMode = SolverMode.AUTO;
    private boolean byproductFeeds = true;
    private boolean autoResolve = true;
    private boolean solverModeDerived;
    private int defaultTier = Preferences.NO_DEFAULT_TIER;

    /** A plan that names itself after whatever it is asked to make. */
    public Plan() {
        this.name = "";
        this.named = false;
    }

    public Plan(String name) {
        this.name = Objects.requireNonNull(name, "name");
        this.named = !name.isBlank();
    }

    /**
     * What to call this plan: the user's name for it, or one derived from what it makes.
     *
     * <p><b>A plan the user has not named follows its first target</b> (M9.1). Plans used to be
     * christened at creation from the target they happened to start with and stuck that way, so a
     * plan retargeted from ethanol to steel still called itself ethanol — a list of plans nobody can
     * read at a glance is the failure this milestone exists to fix, and a name that lies is worse
     * than one that is merely ugly.
     */
    public String name() {
        return named ? name : derivedName();
    }

    /**
     * Name this plan by hand, which sticks: no later change of target moves it.
     *
     * <p>A blank name is not an error and not the string {@code ""} — it hands the plan back to
     * {@link #derivedName()}, so clearing the field in the rename box is how a user takes a name back
     * rather than a way to end up with an unnamed row.
     */
    public Plan name(String newName) {
        Objects.requireNonNull(newName, "name");
        this.named = !newName.isBlank();
        this.name = named ? newName.trim() : "";
        return this;
    }

    /** Whether the user named this plan, as opposed to it being named after what it makes. */
    public boolean isNamed() {
        return named;
    }

    /**
     * The name a plan gets when the user has not chosen one: its first target and its rate.
     *
     * <p>In {@code core} rather than in the GUI because the commands, the plan list and the saved
     * file must agree about what an unnamed plan is called. A prettier form — the item's translated
     * name and an icon — is the renderer's business, and the plan list draws exactly that; this is
     * the one every other caller can produce without a game.
     */
    public String derivedName() {
        if (targets.isEmpty()) {
            return "Untitled";
        }
        TargetOutput first = targets.get(0);
        return first.key() + " x " + trim(first.perSecond()) + "/s";
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** What the plan is for, as rates per second. */
    public List<TargetOutput> targets() {
        return List.copyOf(targets);
    }

    public Plan target(MfpKey key, double perSecond) {
        targets.add(new TargetOutput(key, perSecond));
        return this;
    }

    public Plan target(TargetOutput target) {
        targets.add(Objects.requireNonNull(target, "target"));
        return this;
    }

    /**
     * Replace the target at {@code index}, which is how the GUI edits a rate.
     *
     * <p>By index rather than by key because a plan may legitimately target the same key twice —
     * two customers for the same plate — and replacing "the one with this key" would silently merge
     * them.
     */
    public Plan setTarget(int index, TargetOutput target) {
        targets.set(index, Objects.requireNonNull(target, "target"));
        return this;
    }

    public Plan removeTarget(int index) {
        targets.remove(index);
        return this;
    }

    public Floor root() {
        return root;
    }

    public Plan add(LineNode node) {
        root.add(node);
        return this;
    }

    /**
     * Items the matrix engine may import or export freely instead of forcing to balance at zero.
     *
     * <p>Unused by the sequential engine; kept on the plan because it is a user decision that must
     * survive being saved and reloaded.
     */
    public Set<MfpKey> freeItems() {
        return Set.copyOf(freeItems);
    }

    public Plan freeItem(MfpKey key) {
        freeItems.add(key);
        return this;
    }

    public Plan clearFreeItem(MfpKey key) {
        freeItems.remove(key);
        return this;
    }

    /**
     * Where expansion stops — ores, water, air, energy bought from elsewhere.
     *
     * <p>Without a cutoff, automatic expansion of a GregTech graph does not terminate in any useful
     * place; it keeps going until everything is stone and hydrogen.
     *
     * <p><b>The standing list plus this plan's exceptions, worked out on every read</b> — not a copy
     * taken when the plan was created (M9.4). What counts as infinite is a fact about the save, and
     * the day a cobble generator is built it becomes true of every plan the player has, including the
     * ones already on disk. A plan that had baked the list in at creation would have gone on planning
     * how to make cobblestone forever, and the only way to fix it would have been to rebuild it.
     *
     * <p>So a plan stores only what it says <em>differently</em> from the standing list, in both
     * directions: {@link #rawMaterial} adds, and {@link #clearRawMaterial} takes away — which is what
     * the plan whose whole subject is making water needs, and it must keep working when water is on
     * the shipped list.
     */
    public Set<MfpKey> rawMaterials() {
        // Cached, and this matters: expansion asks whether a key is raw once per resolved input, so
        // on a real GregTech chain this is called thousands of times per solve. Building the union
        // on every call cost more than the rest of the walk put together.
        //
        // The cache is keyed on the *identity* of the standing set, which `RawMaterials.install`
        // replaces wholesale rather than mutating. So a standing declaration made while a plan is
        // open is picked up on the next call, which is the property that made this a live view
        // rather than a copy in the first place.
        Set<MfpKey> standing = RawMaterials.defaults();
        Set<MfpKey> cached = rawMaterialCache;
        if (cached != null && rawMaterialCacheBase == standing) {
            return cached;
        }
        Set<MfpKey> combined = new LinkedHashSet<>(standing);
        combined.addAll(rawMaterialsAdded);
        combined.removeAll(rawMaterialsRemoved);
        Set<MfpKey> result = Set.copyOf(combined);
        rawMaterialCacheBase = standing;
        rawMaterialCache = result;
        return result;
    }

    /** Both deltas change what {@link #rawMaterials()} answers, so both drop the cache. */
    private void invalidateRawMaterials() {
        rawMaterialCache = null;
        rawMaterialCacheBase = null;
    }

    /** What this plan declares raw on top of the standing list. */
    public Set<MfpKey> rawMaterialsAdded() {
        return Set.copyOf(rawMaterialsAdded);
    }

    /** What this plan refuses to treat as raw, whatever the standing list says. */
    public Set<MfpKey> rawMaterialsRemoved() {
        return Set.copyOf(rawMaterialsRemoved);
    }

    public Plan rawMaterial(MfpKey key) {
        invalidateRawMaterials();
        rawMaterialsRemoved.remove(key);
        // Only when it is not already standing, so that "yes, water" does not become a permanent
        // exception that outlives someone taking water off the standing list.
        if (!RawMaterials.defaults().contains(key)) {
            rawMaterialsAdded.add(key);
        }
        return this;
    }

    /** Stop treating this as free, for the plan whose whole subject is making it. */
    public Plan clearRawMaterial(MfpKey key) {
        invalidateRawMaterials();
        rawMaterialsAdded.remove(key);
        if (RawMaterials.defaults().contains(key)) {
            rawMaterialsRemoved.add(key);
        }
        return this;
    }

    /**
     * Items the user chose for ambiguous inputs — "use wood pulp, not wood chips".
     *
     * <p>A set of items rather than a map from ingredient to item, because an ingredient has no
     * identity of its own: the same tag appears in dozens of recipes, and a user who says "wood pulp"
     * once means it everywhere in this plan. Applied by reordering the ingredient's candidates
     * ({@link dev.mfp.core.model.MfpRecipe#withPreferredInputs}), so the chooser and the solver
     * cannot disagree about which item a line actually eats.
     */
    public Set<MfpKey> preferredItems() {
        return Set.copyOf(preferredItems);
    }

    public Plan preferItem(MfpKey key) {
        preferredItems.add(key);
        return this;
    }

    public Plan clearPreferredItem(MfpKey key) {
        preferredItems.remove(key);
        return this;
    }

    /**
     * Recipes the user pinned for particular products, by key.
     *
     * <p>A user choice always beats the scorer. Automatic selection is a convenience; being
     * overruled and then quietly reverting on the next expansion would make the plan untrustworthy.
     */
    public Map<MfpKey, String> recipeChoices() {
        return Map.copyOf(recipeChoices);
    }

    public Plan chooseRecipe(MfpKey key, String recipeId) {
        recipeChoices.put(key, recipeId);
        return this;
    }

    public String recipeChoice(MfpKey key) {
        return recipeChoices.get(key);
    }

    /** Drop a pin, letting the scorer choose for this key again. */
    public Plan clearRecipeChoice(MfpKey key) {
        recipeChoices.remove(key);
        return this;
    }

    /**
     * Machines the user picked per recipe type.
     *
     * <p>Keyed by recipe type rather than by line, so that choosing an HV assembler once applies to
     * every assembler step in the plan and survives re-expansion (plan §8.2).
     */
    public Map<String, String> machineChoices() {
        return Map.copyOf(machineChoices);
    }

    public Plan chooseMachine(String recipeTypeId, String machineId) {
        machineChoices.put(recipeTypeId, machineId);
        return this;
    }

    public String machineChoice(String recipeTypeId) {
        return machineChoices.get(recipeTypeId);
    }

    /**
     * Whole machine configurations the user built, keyed by <em>recipe</em> id.
     *
     * <p>Separate from {@link #machineChoices()} and finer grained, because the two answer different
     * questions. "Assemblers are HV in this factory" is a policy about a recipe type and applies to
     * every line of that type, including ones a later expansion creates. "This blast furnace has
     * kanthal coils and two parallel hatches" is a fact about one build, and applying it to every
     * blast furnace step in the plan would invent structures the user never described.
     *
     * <p>This is also what {@code PLAN.md} §11 requires of a saved plan: a plan reloaded without its
     * coil tier would re-derive different numbers from the same name, which is exactly the silent
     * disagreement {@code Confidence} exists to prevent.
     */
    public Map<String, MachineConfig> machineConfigs() {
        return Map.copyOf(machineConfigs);
    }

    public Plan configureMachine(String recipeId, MachineConfig config) {
        machineConfigs.put(Objects.requireNonNull(recipeId, "recipeId"),
                Objects.requireNonNull(config, "config"));
        return this;
    }

    public MachineConfig machineConfig(String recipeId) {
        return machineConfigs.get(recipeId);
    }

    /** Forget a built configuration, so the default machine policy applies to that recipe again. */
    public Plan clearMachineConfig(String recipeId) {
        machineConfigs.remove(recipeId);
        return this;
    }

    /** Recipe ids the chooser must never select. */
    public Set<String> blacklist() {
        return Set.copyOf(blacklist);
    }

    public Plan blacklistRecipe(String recipeId) {
        blacklist.add(recipeId);
        return this;
    }

    public Plan unblacklistRecipe(String recipeId) {
        blacklist.remove(recipeId);
        return this;
    }

    /**
     * Items this plan may not use, on top of the standing ones.
     *
     * <p>Items rather than recipes, which is the difference that makes this worth having beside
     * {@link #blacklist()}: blocking an item makes <em>every</em> recipe consuming it unusable, so
     * "I have no inferium" is one statement rather than forty hidden recipes.
     */
    public Set<MfpKey> blockedItems() {
        return Set.copyOf(blockedItems);
    }

    public Plan blockItem(MfpKey key) {
        blockedItems.add(key);
        allowedItems.remove(key);
        return this;
    }

    public Plan unblockItem(MfpKey key) {
        blockedItems.remove(key);
        return this;
    }

    /**
     * Items this plan may use even though the standing preferences block them.
     *
     * <p>The other direction of the same override, and it is what makes a standing block safe to
     * set: "I have no inferium" stops being true the day the essence farm is built, and the plan for
     * building it is exactly the plan that must be allowed to assume it.
     */
    public Set<MfpKey> allowedItems() {
        return Set.copyOf(allowedItems);
    }

    public Plan allowItem(MfpKey key) {
        allowedItems.add(key);
        blockedItems.remove(key);
        return this;
    }

    public Plan clearAllowedItem(MfpKey key) {
        allowedItems.remove(key);
        return this;
    }

    /**
     * The tier this plan builds at, or {@link Preferences#NO_DEFAULT_TIER} to follow the standing one.
     *
     * <p>Per plan as well as globally because a plan is often written for a stage of the game rather
     * than for the stage the player is at — planning the LV steel line that gets them to MV is the
     * ordinary case, not the exception.
     */
    public int defaultTier() {
        return defaultTier;
    }

    public Plan defaultTier(int tier) {
        this.defaultTier = tier < 0 ? Preferences.NO_DEFAULT_TIER : tier;
        return this;
    }

    /**
     * Whether the chooser should route a spare byproduct into something that wants it (M11.1).
     *
     * <p>On by default, because Star-Technology deliberately builds chains that feed each other and
     * finding them by accident is not finding them. Off is a real preference rather than a debug
     * switch: wiring everything into everything makes a plan that is harder to build in the world
     * than one with a couple of extra imports, and a player laying out a factory floor is allowed to
     * want the simpler shape.
     */
    public boolean byproductFeeds() {
        return byproductFeeds;
    }

    public Plan byproductFeeds(boolean enabled) {
        this.byproductFeeds = enabled;
        return this;
    }

    /**
     * Whether expansion may choose recipes on the user's behalf below the target (M11.3).
     *
     * <p>On by default, and off is how Factory Planner — the mod this is a port of — has always
     * worked: the player adds a recipe, sees what it needs, and answers each input in turn. MFP's
     * automatic chooser is the more ambitious idea and it is good on material chains and unreliable
     * above them, so it is the default rather than the only way to work. With this off, every input
     * the plan does not have a {@link #recipeChoice} for is left as an import to be answered.
     *
     * <p><b>The target itself is still chosen for.</b> A plan with no lines has nothing to click, so
     * expansion stops <em>below</em> the target rather than at it — one line, and the shopping list
     * that line implies. Everything under it is the user's.
     */
    public boolean autoResolve() {
        return autoResolve;
    }

    public Plan autoResolve(boolean enabled) {
        this.autoResolve = enabled;
        return this;
    }

    /**
     * The order the user dragged their lines into, as recipe ids, or empty for the solver's own.
     *
     * <p><b>Display only.</b> The floor's node order is what the sequential engine walks and is
     * topological by construction (plan §8.1); reordering that would turn a correct plan into one
     * reporting imports for things it produces. So this is a second ordering, applied by
     * {@link DisplayOrder#apply} at render time, and the lines themselves never move.
     */
    public List<String> displayOrder() {
        return List.copyOf(displayOrder);
    }

    public Plan displayOrder(List<String> recipeIds) {
        displayOrder.clear();
        displayOrder.addAll(Objects.requireNonNull(recipeIds, "recipeIds"));
        return this;
    }

    /** Back to the order the solver produced. */
    public Plan clearDisplayOrder() {
        displayOrder.clear();
        return this;
    }

    /**
     * Which of the user's decisions this line is the result of, for the marker on its row.
     *
     * <p>A pin is matched by recipe id rather than by key because a line does not know which of its
     * outputs it was pinned for: a recipe named after ethanol is often on the plan for its bio chaff,
     * and asking "was this recipe chosen for anything" is both cheaper and the question the marker
     * actually answers.
     */
    public Set<LineDecision> decisionsFor(Line line) {
        return decisionsFor(line, null);
    }

    /**
     * The same, distinguishing a decision made here from one carried in from the standing defaults.
     *
     * <p>They are not the same mark and must not read as one. "You pinned this" is a decision the
     * user made in this plan and can change here; "this is how you always make it" was made
     * elsewhere and changing it here would be a surprise — so the marker names which, and the two
     * have somewhere different to go.
     */
    public Set<LineDecision> decisionsFor(Line line, Preferences preferences) {
        Objects.requireNonNull(line, "line");
        Set<LineDecision> decisions = new LinkedHashSet<>();
        if (preferences != null
                && !recipeChoices.containsValue(line.recipe().id())
                && preferences.defaultRecipes().containsValue(line.recipe().id())) {
            decisions.add(LineDecision.STANDING_DEFAULT);
        }
        if (recipeChoices.containsValue(line.recipe().id())) {
            decisions.add(LineDecision.RECIPE);
        }
        if (machineChoices.containsKey(line.recipe().recipeTypeId())) {
            decisions.add(LineDecision.MACHINE);
        }
        if (machineConfigs.containsKey(line.recipe().id())) {
            decisions.add(LineDecision.CONFIG);
        }
        return decisions;
    }

    public SolverMode solverMode() {
        return solverMode;
    }

    /** The user's own choice of engine, which no re-expansion may undo. */
    public Plan solverMode(SolverMode mode) {
        this.solverMode = Objects.requireNonNull(mode, "solverMode");
        this.solverModeDerived = false;
        return this;
    }

    /**
     * The engine the chooser worked out is needed, rather than one the user asked for.
     *
     * <p>Recorded as derived so that {@link #clearLines()} can put it back to {@link SolverMode#AUTO}
     * before the next expansion. Without that distinction a plan that once contained a loop stays on
     * the matrix engine forever, and once the GUI lets the user replace the recipe that closed the loop,
     * "needs matrix" would quietly mean "has ever needed matrix". An explicit choice is never
     * touched — being overruled by a re-solve is the same untrustworthiness a pinned recipe avoids.
     */
    public Plan deriveSolverMode(SolverMode mode) {
        this.solverMode = Objects.requireNonNull(mode, "solverMode");
        this.solverModeDerived = true;
        return this;
    }

    /** Whether the current mode was worked out by the chooser rather than chosen by the user. */
    public boolean solverModeDerived() {
        return solverModeDerived;
    }

    /**
     * A copy with everything the user decided and nothing the solver derived.
     *
     * <p>For "duplicate this plan and try it another way", which is the natural way to compare two
     * builds. The lines are deliberately <em>not</em> copied: they are output, re-derived on the
     * first solve from the targets and choices that are copied here. Copying them would mean two
     * plans sharing {@link Line} objects, and an edit to one would silently move the other.
     */
    public Plan copy(String newName) {
        Plan copy = new Plan(newName);
        targets.forEach(copy::target);
        copy.freeItems.addAll(freeItems);
        copy.rawMaterialsAdded.addAll(rawMaterialsAdded);
        copy.rawMaterialsRemoved.addAll(rawMaterialsRemoved);
        copy.invalidateRawMaterials();
        copy.preferredItems.addAll(preferredItems);
        copy.recipeChoices.putAll(recipeChoices);
        copy.machineChoices.putAll(machineChoices);
        copy.machineConfigs.putAll(machineConfigs);
        copy.blacklist.addAll(blacklist);
        copy.blockedItems.addAll(blockedItems);
        copy.allowedItems.addAll(allowedItems);
        copy.defaultTier = defaultTier;
        copy.byproductFeeds = byproductFeeds;
        copy.autoResolve = autoResolve;
        // A hand order is a decision, and it names recipes rather than the lines this copy has yet
        // to derive, so it travels like the pins do.
        copy.displayOrder.addAll(displayOrder);
        if (!solverModeDerived) {
            // A derived mode belongs to lines this copy does not have yet; an explicit choice is the
            // user's and travels with the plan.
            copy.solverMode(solverMode);
        }
        return copy;
    }

    public List<Line> allLines() {
        return root.allLines();
    }

    /**
     * Drop every line, keeping the targets and the user's choices.
     *
     * <p>What re-expansion needs. {@code RecipeChooser.expandInto} appends, so re-solving a plan
     * that already has lines would double every one of them; clearing first is how a plan is
     * rebuilt after the user changes a recipe or a machine. The pinned recipes, machines,
     * blacklist, raw materials and free items deliberately survive — they are the user's intent,
     * and re-expansion exists to apply them, not to discard them.
     */
    public Plan clearLines() {
        root.clear();
        if (solverModeDerived) {
            // The loop that forced the matrix engine belonged to the lines just discarded; whether
            // the new ones contain one is the next expansion's observation to make.
            solverMode = SolverMode.AUTO;
            solverModeDerived = false;
        }
        return this;
    }
}
