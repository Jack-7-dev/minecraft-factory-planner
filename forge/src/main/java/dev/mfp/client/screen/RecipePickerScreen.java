package dev.mfp.client.screen;

import dev.mfp.client.ClientIndex;
import dev.mfp.client.ClientPlanner;
import dev.mfp.client.KeyStacks;
import dev.mfp.client.MachineStacks;
import dev.mfp.client.widget.Cells;
import dev.mfp.client.widget.MfpWidget;
import dev.mfp.client.widget.ScrollPanel;
import dev.mfp.client.widget.SlotWidget;
import dev.mfp.client.widget.TabBar;
import dev.mfp.client.widget.Table;
import dev.mfp.client.widget.TextButton;
import dev.mfp.client.widget.TextField;
import dev.mfp.client.widget.Theme;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.client.widget.Fmt;
import dev.mfp.core.model.Confidence;
import dev.mfp.core.plan.MachineConfig;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.plan.Preferences;
import dev.mfp.core.select.MachinePicker;
import dev.mfp.core.solver.BehaviourThroughputResolver;
import dev.mfp.core.solver.Throughput;
import dev.mfp.plan.PlanSession;
import dev.mfp.plan.PreferenceStore;
import dev.mfp.core.select.RecipeChooser;
import dev.mfp.core.select.RecipeScorer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every way to make one item, ranked, with the reasons the ranking gives shown beside each.
 *
 * <p>This is not a power-user affordance. Automatic selection is good on material chains and
 * unreliable above them (STATUS §4b.16) — GregTech offers 431 ways to make a steel ingot and the
 * good ones are not distinguishable from the junk by anything visible in a single recipe — so this
 * dialog is the normal way a plan above raw materials gets built.
 *
 * <p>The scorer's own reasons used to have a column here, and no longer do. They explained a ranking
 * the user is not being asked to audit: what is wanted from this screen is the machine, the rate and
 * the ingredients, which is what a recipe is actually chosen on. The ordering they produced is still
 * the order of the list.
 *
 * <p>Two different rejections, deliberately kept apart. <b>Pinning</b> another recipe says "use this
 * one here"; <b>hiding</b> says "never offer this recipe again in this plan", which also steers
 * every other item's expansion away from it. A user who hides the recycling recipe once should not
 * have to pin around it in six places.
 */
public final class RecipePickerScreen extends ModalScreen {

    private static final List<Table.Column> COLUMNS = List.of(
            new Table.Column("Machine", 3.2f,
                    "The machine that runs this recipe. The recipe's own id is in the row's "
                            + "tooltip: it identifies the row, but it rarely tells the user "
                            + "anything they wanted to know."),
            new Table.Column("Makes", 1.7f),
            new Table.Column("Rate", 1.2f,
                    "How much of this item one machine makes per second, on the machine and tier "
                            + "the plan would default to. This is what actually compares two "
                            + "recipes: a bigger batch over a longer cycle is not more throughput."),
            new Table.Column("From", 3.2f));

    /**
     * How many ranked recipes are built into rows before the list stops and says so.
     *
     * <p>Culling stops the clipped rows costing a frame, but it does not stop 430 rows being
     * <em>built</em> — each one resolves an item stack and a display name per ingredient — so the
     * cap is what keeps opening the dialog cheap. It must be visible when it bites: a silently
     * truncated list of ways to make something reads as "these are the ways".
     */
    private static final int ROW_CAP = 60;

    private final MfpKey key;
    private final Plan plan;

    private Table table;
    private int count;
    private int shown;
    private int hiddenByCap;
    private boolean showHidden;
    private BehaviourThroughputResolver resolver;
    /**
     * One chooser for the life of the dialog, not one per rebuild.
     *
     * <p>Every keystroke in the search box rebuilds this screen, and since M14 ranking asks what
     * the blacklist costs several items upstream - a search the chooser only pays for once and then
     * caches. A fresh chooser each rebuild would throw that cache away on every letter typed, which
     * is precisely the way of doing this the milestone warns against. The chooser holds no plan
     * state, so reusing it across a pin, a hide or an unblock is safe: its caches are keyed by the
     * thing that would invalidate them.
     */
    private final RecipeChooser chooser = new RecipeChooser(ClientIndex.get(), preferences());

    /** Recipes the last expansion steered around because they closed a loop nothing fed. */
    private final java.util.Set<String> avoided = ClientPlanner.current() == null
            ? java.util.Set.of()
            : java.util.Set.copyOf(ClientPlanner.current().chooserResult().avoidedForCycles());
    private TextField search;
    private String filter = "";
    private boolean showAll;

    /** 0 is the "All" tab; anything higher indexes {@link #tabTypes}. */
    private int selectedTab;
    private List<String> tabTypes = List.of();
    private int tableHeaderY;

    public RecipePickerScreen(Screen parent, MfpKey key) {
        super(parent, "Recipes for", KeyStacks.name(key).getString());
        this.key = key;
        this.plan = ClientPlanner.current() == null ? new Plan("scratch") : ClientPlanner.current().plan();
    }

    @Override
    protected int preferredWidth() {
        return 560;
    }

    @Override
    protected int preferredHeight() {
        return 300;
    }

    @Override
    protected void build() {
        String pinned = plan.recipeChoice(key);

        TextButton unpin = new TextButton(pinned == null ? "Not pinned" : "Clear pin", () -> {
            plan.clearRecipeChoice(key);
            ClientPlanner.refresh();
            rebuild();
        });
        unpin.enabled(pinned != null);
        unpin.tooltip(pinned == null
                ? "Nothing is pinned for this item, so the scorer's first choice is used."
                : "Pinned: " + pinned + " - drop the pin and let the scorer choose again.");
        unpin.bounds(contentX(), panelY + panelHeight - FOOTER_HEIGHT + 3, unpin.preferredWidth(), 14);
        widgets.add(unpin);

        TextButton hidden = new TextButton(
                showHidden ? "Excluded: shown" : "Excluded: " + excludedCount(),
                () -> {
                    showHidden = !showHidden;
                    rebuild();
                });
        hidden.tooltip("Recipes this plan will not use: ones you hid, and ones needing a blacklisted "
                + "item. Hiding excludes a recipe from every item's expansion, not just this one's; "
                + "blacklisting an item excludes every recipe that consumes it.");
        hidden.bounds(unpin.x() + unpin.width() + GAP, unpin.y(), hidden.preferredWidth(), 14);
        widgets.add(hidden);

        if (search == null) {
            search = new TextField()
                    .placeholder("filter by ingredient")
                    .tooltip("Show only recipes made from an item whose name or id matches. "
                            + "Composting a specific crop is otherwise a hunt through a hundred rows "
                            + "that differ only in one slot.")
                    .onChange(value -> {
                        filter = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
                        rebuild();
                    });
        }
        String standing = preferences().defaultRecipe(key);
        TextButton defaults = new TextButton(
                standing == null ? "No default" : "Default: " + pathOf(standing),
                () -> {
                    preferences().clearDefaultRecipe(key);
                    PreferenceStore.save();
                    ClientPlanner.refresh();
                    rebuild();
                });
        defaults.enabled(standing != null);
        defaults.tooltip(standing == null
                ? "Shift-click a recipe to make it the way you always make " + key
                        + " - every plan will use it unless it pins another."
                : "Every plan makes " + key + " with " + standing
                        + ". Press to drop that and let each plan choose again.");
        defaults.bounds(hidden.x() + hidden.width() + GAP, unpin.y(), defaults.preferredWidth(), 14);
        widgets.add(defaults);

        int searchX = defaults.x() + defaults.width() + GAP;
        search.bounds(searchX, unpin.y(), Math.max(60, contentX() + contentWidth() - searchX), 14);
        widgets.add(search);

        List<RecipeScorer.Scored> ranked = chooser.alternatives(key, plan);
        count = ranked.size();
        // Filtered before grouping, so the tabs and their counts describe what is actually on
        // screen. Filtering afterwards would leave tabs that open onto nothing.
        ranked = matching(ranked);

        // Insertion order is best-score order: `ranked` is already sorted, so the first time a
        // recipe type appears is the best any of its recipes scored. That is exactly the tab order
        // §13.2 asks for, with no second sort and no risk of the two disagreeing.
        Map<String, List<RecipeScorer.Scored>> byType = new LinkedHashMap<>();
        for (RecipeScorer.Scored scored : ranked) {
            byType.computeIfAbsent(scored.recipe().recipeTypeId(), id -> new ArrayList<>()).add(scored);
        }
        this.tabTypes = List.copyOf(byType.keySet());
        if (selectedTab > tabTypes.size()) {
            // Hiding the last recipe of a machine takes its tab away underneath the user.
            selectedTab = 0;
        }

        TabBar tabs = buildTabs(byType, ranked.size());
        int tabsHeight = tabs.preferredHeight(contentWidth());
        tabs.bounds(contentX(), contentY(), contentWidth(), tabsHeight);
        widgets.add(tabs);

        List<RecipeScorer.Scored> visible = selectedTab == 0
                ? ranked
                : byType.getOrDefault(tabTypes.get(selectedTab - 1), List.of());

        // Culling stops the clipped rows costing a frame; the cap stops them being built at all.
        int limit = showAll ? visible.size() : Math.min(ROW_CAP, visible.size());
        this.shown = limit;
        this.hiddenByCap = visible.size() - limit;

        Table table = new Table(COLUMNS);
        String best = ranked.isEmpty() ? null : ranked.get(0).recipe().id();
        for (int i = 0; i < limit; i++) {
            RecipeScorer.Scored scored = visible.get(i);
            addRow(table, scored, pinned, scored.recipe().id().equals(best));
        }
        if (hiddenByCap > 0) {
            addCapRow(table, hiddenByCap);
        }
        if (showHidden && selectedTab == 0) {
            for (String hiddenId : plan.blacklist()) {
                MfpRecipe recipe = ClientIndex.get().recipe(hiddenId);
                if (recipe != null && recipe.produces(key)) {
                    addHiddenRow(table, recipe);
                }
            }
            // Recipes excluded by a blocked item rather than by being hidden. They are not in the
            // ranked list at all — that is what blocking means — so without this the user would see
            // an import with no way to find out which decision removed the route, or to take it back.
            chooser.blockedAlternatives(key, plan).forEach((recipe, offender) -> {
                if (!plan.blacklist().contains(recipe.id())) {
                    addBlockedRow(table, recipe, offender);
                }
            });
        }

        this.tableHeaderY = contentY() + tabsHeight + GAP;
        ScrollPanel scroll = new ScrollPanel();
        scroll.bounds(contentX(), tableHeaderY + Table.HEADER_HEIGHT, contentWidth(),
                Math.max(20, contentBottom() - tableHeaderY - Table.HEADER_HEIGHT - GAP));
        table.layout(scroll.viewportWidth());
        scroll.content(table, table.contentHeight());
        widgets.add(scroll);
        this.table = table;
    }

    /**
     * "All", then one tab per machine, each showing the machine that runs that recipe type.
     *
     * <p>The "All" tab is kept in front of the machine tabs rather than replaced by them: the
     * cross-machine ranking with its reasons is the thing this dialog exists for (§8.3), and
     * grouping is a way to find a recipe within it, not a replacement for it.
     */
    private TabBar buildTabs(Map<String, List<RecipeScorer.Scored>> byType, int total) {
        List<TabBar.Tab> tabs = new ArrayList<>();
        tabs.add(new TabBar.Tab("All", total, "Every way to make this, best first."));
        for (String typeId : tabTypes) {
            // The type's own name, not the representative machine's: the tab holds every tier's
            // recipes, so naming it after one tier misdescribes the rest. The icon still comes from
            // the machine the plan would default to.
            String title = MachineStacks.shortName(typeId);
            tabs.add(new TabBar.Tab(title, byType.get(typeId).size(),
                    typeId + " - ranking within a machine is the same ranking, filtered",
                    MachineStacks.iconForRecipeType(typeId)));
        }
        return new TabBar(tabs, selectedTab, index -> {
            selectedTab = index;
            // The cap is per tab, so moving to a shorter list should not stay expanded: a tab of 12
            // recipes has nothing to expand, and the button would read as broken.
            showAll = false;
            rebuild();
        });
    }

    /**
     * The row that admits the list was cut short.
     *
     * <p>Never silent: a truncated list of the ways to make something reads as "these are the ways",
     * which is the one thing a picker must not imply.
     */
    private void addCapRow(Table into, int remaining) {
        into.addRow(List.of(
                        Cells.text(remaining + " more not shown", Theme.TEXT_IDLE,
                                List.of(Component.literal("The list stops at " + ROW_CAP
                                                + " so that opening it stays quick.")
                                        .withStyle(ChatFormatting.GRAY),
                                        Component.literal("They are ranked below these, not excluded.")
                                                .withStyle(ChatFormatting.GRAY))),
                        Cells.text("", Theme.TEXT_IDLE),
                        Cells.button("Show all", Theme.TEXT,
                                List.of(Component.literal("These are ranked lower, not excluded.")
                                        .withStyle(ChatFormatting.GRAY)),
                                () -> {
                                    showAll = true;
                                    rebuild();
                                }),
                        Cells.text("", Theme.TEXT_IDLE)),
                0, null);
    }

    private void addRow(Table into, RecipeScorer.Scored scored, String pinned, boolean isDefault) {
        MfpRecipe recipe = scored.recipe();
        boolean isPinned = recipe.id().equals(pinned);
        boolean isStanding = recipe.id().equals(preferences().defaultRecipe(key));

        int background = isPinned || isStanding ? Theme.ROW_SELECTED : 0;
        int colour = isPinned || isStanding ? Theme.PINNED : (isDefault ? Theme.TEXT : Theme.TEXT_DIM);

        into.addRow(List.of(
                        Cells.iconTwoLine(MachineStacks.iconForRecipeType(recipe.recipeTypeId()),
                                MachineStacks.shortName(recipe.recipeTypeId()), colour,
                                (isStanding ? "your default  -  " : "")
                                        + (avoided.contains(recipe.id()) ? "loop  -  " : "")
                                        + recipe.recipeTypeId() + durationSuffix(recipe),
                                recipeTooltip(recipe, scored)),
                        Cells.flows(outputSlots(recipe)),
                        rateCell(recipe),
                        IngredientChoiceScreen.hasChoices(recipe)
                                ? Cells.clickable(Cells.flows(inputSlots(recipe)),
                                        () -> net.minecraft.client.Minecraft.getInstance().setScreen(
                                                new IngredientChoiceScreen(this, recipe)))
                                : Cells.flows(inputSlots(recipe))),
                background,
                button -> {
                    if (button == 0 && Screen.hasShiftDown()) {
                        // Shift because it is the larger statement of the two: pinning decides this
                        // plan, a default decides every plan there will ever be.
                        makeDefault(recipe);
                    } else if (button == 0) {
                        pin(recipe);
                    } else if (button == 1) {
                        hide(recipe);
                    }
                });
    }

    /**
     * A recipe the plan may not use because one of its inputs is blocked.
     *
     * <p>The button allows the item <em>in this plan</em> rather than unblocking it everywhere: the
     * user is looking at one chain and answering one question, and quietly undoing a standing
     * preference from a screen about a single item would be a far larger change than they asked for.
     * The Defaults screen is where a block is lifted for good.
     */
    private void addBlockedRow(Table into, MfpRecipe recipe, MfpKey offender) {
        into.addRow(List.of(
                        Cells.iconTwoLine(MachineStacks.iconForRecipeType(recipe.recipeTypeId()),
                                MachineStacks.shortName(recipe.recipeTypeId()), Theme.TEXT_IDLE,
                                "needs " + KeyStacks.name(offender).getString(),
                                List.of(Component.literal(recipe.id())
                                        .withStyle(ChatFormatting.WHITE))),
                        Cells.flows(outputSlots(recipe)),
                        Cells.button("Allow", Theme.TEXT,
                                List.of(Component.literal("You blacklisted "
                                                + KeyStacks.name(offender).getString())
                                                .withStyle(ChatFormatting.GRAY),
                                        Component.literal("so every recipe consuming it is out of "
                                                + "consideration.").withStyle(ChatFormatting.GRAY),
                                        Component.literal("Use " + KeyStacks.name(offender).getString()
                                                + " in this plan only")
                                        .withStyle(ChatFormatting.GRAY)),
                                () -> {
                                    plan.allowItem(offender);
                                    ClientPlanner.refresh();
                                    rebuild();
                                }),
                        Cells.flows(inputSlots(recipe))),
                0, null);
    }

    private void addHiddenRow(Table into, MfpRecipe recipe) {
        into.addRow(List.of(
                        Cells.iconTwoLine(MachineStacks.iconForRecipeType(recipe.recipeTypeId()),
                                MachineStacks.shortName(recipe.recipeTypeId()), Theme.TEXT_IDLE,
                                "hidden in this plan",
                                List.of(Component.literal(recipe.id())
                                        .withStyle(ChatFormatting.WHITE))),
                        Cells.flows(outputSlots(recipe)),
                        Cells.button("Unhide", Theme.TEXT,
                                List.of(Component.literal("Hidden in this plan. Press to let it be "
                                                + "offered again.").withStyle(ChatFormatting.GRAY)),
                                () -> {
                                    plan.unblacklistRecipe(recipe.id());
                                    ClientPlanner.refresh();
                                    rebuild();
                                }),
                        Cells.flows(inputSlots(recipe))),
                0, null);
    }

    /**
     * What one machine of this recipe's default machine actually delivers, per second.
     *
     * <p>The column that makes the list comparable. "Makes 8 dust" says nothing without the cycle it
     * takes, and a recipe that produces more per craft over a proportionally longer duration is not
     * an improvement — while a higher-tier machine running the same recipe usually is, which is
     * exactly the relation between tiers a player is choosing between. Resolved through the same
     * behaviour chain the plan uses, on the machine and tier the plan would default to, so the number
     * on this screen is the number that will appear on the line if it is pinned.
     */
    private Table.Cell rateCell(MfpRecipe recipe) {
        var index = ClientIndex.get();
        if (index == null || !recipe.hasRate()) {
            return Cells.text("-", Theme.TEXT_IDLE, List.of(Component.literal(
                    "Hand crafting and other recipes with no duration have no rate at all.")
                    .withStyle(ChatFormatting.GRAY)));
        }

        MachineConfig config = MachinePicker.pick(index, recipe, plan, preferences());
        Throughput throughput = resolver().resolve(recipe, config);
        double perCraft = 0;
        for (MfpOutput output : recipe.outputs()) {
            if (output.key().equals(key)) {
                perCraft += output.expectedAmount();
            }
        }
        double perSecond = throughput.craftsPerSecond() * perCraft;
        if (perSecond <= 0) {
            return Cells.text("-", Theme.TEXT_IDLE, List.of(Component.literal(
                    throughput.note() == null ? "This machine cannot run this recipe."
                            : throughput.note()).withStyle(ChatFormatting.GRAY)));
        }

        String machineName = config.machineId() == null
                ? "the default machine" : MachineStacks.name(config.machineId());
        List<Component> tooltip = new ArrayList<>();
        tooltip.add(Component.literal(Fmt.number(perSecond) + " " + KeyStacks.name(key).getString()
                + " per second, per machine"));
        tooltip.add(Component.literal("on " + machineName).withStyle(ChatFormatting.GRAY));
        if (throughput.confidence() != Confidence.EXACT && throughput.note() != null) {
            tooltip.add(Component.literal(throughput.note()).withStyle(ChatFormatting.YELLOW));
        }
        return Cells.text(Fmt.number(perSecond) + "/s",
                throughput.confidence() == Confidence.EXACT ? Theme.TEXT_DIM
                        : Theme.forConfidence(throughput.confidence()),
                tooltip);
    }

    /** Built once per screen: it re-reads the behaviour overrides from disk on every construction. */
    private BehaviourThroughputResolver resolver() {
        if (resolver == null) {
            resolver = PlanSession.resolverFor(ClientIndex.get());
        }
        return resolver;
    }

    /**
     * The recipes whose inputs match the search box, or all of them when it is empty.
     *
     * <p>Matches on every candidate of every input, not just the one the plan would take: a user
     * looking for "carrot" wants the composting recipe whether or not carrots are the item MFP
     * happened to put first. Ids are matched as well as names, since a pack's display names are not
     * always what the recipe is known by.
     */
    private List<RecipeScorer.Scored> matching(List<RecipeScorer.Scored> ranked) {
        if (filter.isEmpty()) {
            return ranked;
        }
        List<RecipeScorer.Scored> kept = new ArrayList<>();
        for (RecipeScorer.Scored scored : ranked) {
            if (mentions(scored.recipe(), filter)) {
                kept.add(scored);
            }
        }
        return kept;
    }

    private static boolean mentions(MfpRecipe recipe, String needle) {
        for (MfpIngredient input : recipe.inputs()) {
            for (MfpKey candidate : input.candidates()) {
                if (candidate.toString().toLowerCase(java.util.Locale.ROOT).contains(needle)
                        || KeyStacks.name(candidate).getString()
                                .toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void pin(MfpRecipe recipe) {
        plan.chooseRecipe(key, recipe.id());
        // Pinning a recipe can remove the loop that forced the matrix engine, or introduce one, so
        // the whole pipeline runs again rather than the solved result being patched.
        ClientPlanner.refresh();
        back();
    }

    /**
     * Make this the way the player always makes the item.
     *
     * <p>Written through immediately rather than on closing the world: a standing preference lost to
     * a crash is worse than one written a few milliseconds early, and the file is small.
     *
     * <p>The plan's own pin for this key is dropped at the same time. Leaving it would mean the row
     * carried two marks saying the same thing, and the next time the user cleared the pin — expecting
     * to go back to the scorer — nothing would appear to happen.
     *
     * <p>Then it closes, exactly as pinning does. Declaring a default is also a decision about the
     * plan on screen, and leaving the user in the picker made them scroll back down and click the
     * same row a second time to get the recipe they had just called their usual one.
     */
    private void makeDefault(MfpRecipe recipe) {
        preferences().defaultRecipe(key, recipe.id());
        plan.clearRecipeChoice(key);
        PreferenceStore.save();
        ClientPlanner.refresh();
        back();
    }

    /** The one live instance; the Defaults screen and the commands edit the same object. */
    private static Preferences preferences() {
        return PreferenceStore.get();
    }

    /** How many recipes for this item the plan will not use, hidden and blocked together. */
    private int excludedCount() {
        int hidden = 0;
        for (String id : plan.blacklist()) {
            MfpRecipe recipe = ClientIndex.get().recipe(id);
            if (recipe != null && recipe.produces(key)) {
                hidden++;
            }
        }
        return hidden + chooser.blockedAlternatives(key, plan).size();
    }

    private void hide(MfpRecipe recipe) {
        plan.blacklistRecipe(recipe.id());
        if (recipe.id().equals(plan.recipeChoice(key))) {
            // A pin the user has just hidden would otherwise win over the blacklist and be used
            // anyway, which reads as the button doing nothing.
            plan.clearRecipeChoice(key);
        }
        ClientPlanner.refresh();
        rebuild();
    }

    private static String durationSuffix(MfpRecipe recipe) {
        if (recipe.durationTicks() <= 0) {
            return "  -  instant";
        }
        String suffix = "  -  " + trim(recipe.durationTicks()) + " t";
        if (recipe.euIn() > 0) {
            suffix += ", " + recipe.euIn() + " EU/t";
        }
        if (recipe.euOut() > 0) {
            suffix += ", +" + recipe.euOut() + " EU/t";
        }
        return suffix;
    }

    private List<SlotWidget> outputSlots(MfpRecipe recipe) {
        List<SlotWidget> slots = new ArrayList<>();
        for (MfpOutput output : recipe.outputs()) {
            String label = trim(output.amount()) + (output.isChanced()
                    ? " @" + Math.round(output.chance() * 100) + "%" : "");
            List<Component> lines = new ArrayList<>(KeyStacks.tooltip(output.key(), label + " per craft"));
            if (output.isChanced()) {
                lines.add(Component.literal("chance mode: " + output.mode())
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
            slots.add(SlotWidget.of(output.key(), label, lines));
        }
        return slots;
    }

    private List<SlotWidget> inputSlots(MfpRecipe recipe) {
        List<SlotWidget> slots = new ArrayList<>();
        for (MfpIngredient input : recipe.inputs()) {
            MfpKey primary = input.primary();
            String label = trim(input.amount()) + (input.consumed() ? "" : " (kept)");
            List<Component> lines = new ArrayList<>(KeyStacks.tooltip(primary, label + " per craft"));
            if (input.candidates().size() > 1) {
                // An ambiguous input is a choice the plan makes silently; saying so here is the
                // only place the user finds out a tag was narrowed to one item.
                lines.add(Component.literal(input.candidates().size()
                                + " alternatives accepted - click to choose")
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
            if (!input.consumed()) {
                lines.add(Component.literal("not consumed - a catalyst, not a cost")
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
            slots.add(SlotWidget.of(primary, label, lines));
        }
        return slots;
    }

    private List<Component> recipeTooltip(MfpRecipe recipe, RecipeScorer.Scored scored) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(recipe.id()).withStyle(ChatFormatting.WHITE));
        lines.add(Component.literal(recipe.recipeTypeId() + "  (" + recipe.providerId() + ")")
                .withStyle(ChatFormatting.DARK_GRAY));
        // What the scorer thought, in its own words (M14). `mfp alternatives` has printed the score
        // and its reasons since M9.13 and this screen - the one an actual player reads - printed
        // neither, so the ranking arrived as an order with no argument behind it. The gap between
        // two rows is the whole question: a point apart is a tie the user should break, fifty apart
        // is the scorer having an opinion they can disagree with.
        lines.add(Component.literal(String.format(java.util.Locale.ROOT, "score %.1f", scored.score()))
                .withStyle(ChatFormatting.GRAY));
        for (String reason : scored.reasons()) {
            lines.add(Component.literal("  " + reason).withStyle(ChatFormatting.DARK_GRAY));
        }
        if (avoided.contains(recipe.id())) {
            // Not a property of the recipe and not something this screen could work out: the
            // expansion tried it, found it closed a loop that fed nobody, and built the plan
            // without it. Saying so is the difference between a recipe the user has not chosen and
            // one MFP has already chosen against.
            lines.add(Component.literal("passed over to keep the plan acyclic")
                    .withStyle(ChatFormatting.YELLOW));
            lines.add(Component.literal("pin it to use it anyway").withStyle(ChatFormatting.GRAY));
        }
        if (recipe.minTier() >= 0) {
            lines.add(Component.literal("needs tier " + recipe.minTier()).withStyle(ChatFormatting.GRAY));
        }
        if (!recipe.conditions().isEmpty()) {
            lines.add(Component.literal(recipe.conditions().size()
                    + " condition(s) MFP cannot evaluate").withStyle(ChatFormatting.YELLOW));
        }
        lines.add(Component.literal("left click to pin, right click to hide")
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("shift + left click to make it your default everywhere")
                .withStyle(ChatFormatting.GRAY));
        return lines;
    }

    private static String trim(double value) {
        return dev.mfp.client.widget.Fmt.number(value);
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (table != null) {
            table.renderHeader(graphics, contentX(), tableHeaderY, contentWidth() - 6);
        }
        if (count == 0) {
            graphics.drawString(font, "nothing in the index produces this",
                    contentX(), tableHeaderY + Table.HEADER_HEIGHT + 6, Theme.TEXT_IDLE, false);
        }
        String note = count + " recipe" + (count == 1 ? "" : "s")
                + (hiddenByCap > 0 ? ", " + shown + " shown" : "")
                + " - the first is the default";
        graphics.drawString(font, MfpWidget.fit(note, contentWidth() - 120),
                contentX() + contentWidth() - font.width(note) - 70,
                panelY + panelHeight - FOOTER_HEIGHT + 6, Theme.TEXT_DIM, false);
    }

    @Override
    protected void offerTooltips(int mouseX, int mouseY) {
        if (table != null) {
            offer(table.headerTooltip(contentX(), tableHeaderY, contentWidth() - 6, mouseX, mouseY),
                    mouseX, mouseY);
        }
    }
}
