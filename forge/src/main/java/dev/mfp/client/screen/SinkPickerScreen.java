package dev.mfp.client.screen;

import dev.mfp.client.ClientIndex;
import dev.mfp.client.ClientPlanner;
import dev.mfp.client.KeyStacks;
import dev.mfp.client.MachineStacks;
import dev.mfp.client.widget.Cells;
import dev.mfp.client.widget.Fmt;
import dev.mfp.client.widget.MfpWidget;
import dev.mfp.client.widget.ScrollPanel;
import dev.mfp.client.widget.SlotWidget;
import dev.mfp.client.widget.Table;
import dev.mfp.client.widget.TextButton;
import dev.mfp.client.widget.TextField;
import dev.mfp.client.widget.Theme;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.select.RecipeChooser;
import dev.mfp.core.select.SinkScorer;
import dev.mfp.plan.PreferenceStore;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every way to eat one surplus, ranked (M18).
 *
 * <p>The mirror of {@link RecipePickerScreen}, opened from the Byproducts tab rather than from a
 * line. Until now that tab was a dead end: it listed what the factory throws away and offered no way
 * to do anything about it, while the Imports tab beside it had been a question you answer in place
 * since M11.2. This is the same gesture pointed the other way — click the thing, get the ranked
 * answers, pick one, and the plan re-solves around it.
 *
 * <p><b>What is genuinely different from the recipe picker, and why it is a second screen.</b> The
 * consumers of an item are not candidates for making anything, so none of the recipe picker's
 * furniture applies to them: there is no pin to clear, no standing default to declare (a sink is a
 * fact about <em>this</em> factory's leftovers, never about how an item is made everywhere), and
 * hiding a consumer is not a thing anyone wants. What replaces them is the column that says how much
 * of the surplus one machine actually eats, which is the number this decision turns on.
 */
public final class SinkPickerScreen extends ModalScreen {

    private static final List<Table.Column> COLUMNS = List.of(
            new Table.Column("Machine", 3.0f,
                    "The machine that would run this. The recipe's own id is in the row's tooltip."),
            new Table.Column("Eats", 1.3f,
                    "How much of the surplus one machine consumes per second, on the machine and "
                            + "tier this plan would default to. A consumer that eats a trickle does "
                            + "not answer a surplus, however useful what it makes."),
            new Table.Column("Makes", 1.8f,
                    "What comes out. A consumer whose output the plan already wants turns the "
                            + "surplus into a supply; one that makes nothing anybody wants is "
                            + "disposal, which is sometimes all there is."),
            new Table.Column("Also needs", 2.6f,
                    "What else has to be fed in. Every one of these the plan does not already make "
                            + "is another chain you will have to answer."));

    /** As {@link RecipePickerScreen}, and for the same reason: rows cost to build, not just to draw. */
    private static final int ROW_CAP = 60;

    private final MfpKey surplus;
    private final Plan plan;
    private final Set<MfpKey> imported;

    /**
     * One chooser for the life of the dialog: its ceiling and blacklist caches are the expensive
     * part, and every keystroke in the search box rebuilds this screen.
     *
     * <p>Behaviour-aware, because the Eats column is a throughput figure and an overclock is
     * exactly what makes one consumer hungrier than another — the same reason the recipe picker's
     * rate column resolves through the plan's own resolver rather than the recipe's bare duration.
     */
    private final RecipeChooser chooser =
            new RecipeChooser(ClientIndex.get(), PreferenceStore.get())
                    .withResolver(dev.mfp.plan.PlanSession.resolverFor(ClientIndex.get()));

    private Table table;
    private int count;
    private int shown;
    private int hiddenByCap;
    private boolean showAll;
    private TextField search;
    private String filter = "";
    private int tableHeaderY;

    /** Consumers the tier ceiling refuses, by id and why — listed rather than silently missing. */
    private final Map<String, String> beyondCeiling = new LinkedHashMap<>();
    private boolean showRefused;

    public SinkPickerScreen(Screen parent, MfpKey surplus) {
        super(parent, "What could eat", KeyStacks.name(surplus).getString());
        this.surplus = surplus;
        this.plan = ClientPlanner.current() == null ? new Plan("scratch") : ClientPlanner.current().plan();
        this.imported = ClientPlanner.current() == null
                ? Set.of()
                : Set.copyOf(ClientPlanner.current().solveResult().rawInputs().keySet());
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
        String chosen = plan.sink(surplus);

        TextButton clear = new TextButton(chosen == null ? "Nothing eats this" : "Stop eating it",
                () -> {
                    plan.clearSink(surplus);
                    ClientPlanner.refresh();
                    rebuild();
                });
        clear.enabled(chosen != null);
        clear.tooltip(chosen == null
                ? "This surplus is currently thrown away. Pick a row to put a consumer on the plan."
                : "Eaten by " + chosen + " - press to drop that line and throw the surplus away again.");
        clear.bounds(contentX(), panelY + panelHeight - FOOTER_HEIGHT + 3, clear.preferredWidth(), 14);
        widgets.add(clear);

        beyondCeiling.clear();
        chooser.overTierSinks(surplus, plan)
                .forEach((recipe, reason) -> beyondCeiling.put(recipe.id(), reason));

        TextButton refused = new TextButton(
                showRefused ? "Above your tier: shown" : "Above your tier: " + beyondCeiling.size(),
                () -> {
                    showRefused = !showRefused;
                    rebuild();
                });
        refused.enabled(!beyondCeiling.isEmpty());
        refused.tooltip("Consumers needing a machine you cannot build yet. A machine above your tier "
                + "is not a way to eat a surplus, so these are not offered - but they are not hidden "
                + "either, because a filter this broad makes the pack look smaller than it is.");
        refused.bounds(clear.x() + clear.width() + GAP, clear.y(), refused.preferredWidth(), 14);
        widgets.add(refused);

        if (search == null) {
            search = new TextField()
                    .placeholder("filter by what it makes")
                    .tooltip("Show only consumers producing something whose name or id matches. "
                            + "The question behind this screen is usually 'can I turn this into "
                            + "something I need', and this is how that is asked directly.")
                    .onChange(value -> {
                        filter = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
                        rebuild();
                    });
        }
        int searchX = refused.x() + refused.width() + GAP;
        search.bounds(searchX, clear.y(), Math.max(60, contentX() + contentWidth() - searchX), 14);
        widgets.add(search);

        List<SinkScorer.Scored> ranked = chooser.sinks(surplus, plan, imported);
        count = ranked.size();
        ranked = matching(ranked);

        int limit = showAll ? ranked.size() : Math.min(ROW_CAP, ranked.size());
        this.shown = limit;
        this.hiddenByCap = ranked.size() - limit;

        Table table = new Table(COLUMNS);
        for (int i = 0; i < limit; i++) {
            addRow(table, ranked.get(i), chosen, i == 0);
        }
        if (hiddenByCap > 0) {
            addCapRow(table, hiddenByCap);
        }
        if (showRefused) {
            beyondCeiling.forEach((id, reason) -> {
                MfpRecipe recipe = ClientIndex.get().recipe(id);
                if (recipe != null) {
                    addRefusedRow(table, recipe, reason);
                }
            });
        }

        this.tableHeaderY = contentY();
        ScrollPanel scroll = new ScrollPanel();
        scroll.bounds(contentX(), tableHeaderY + Table.HEADER_HEIGHT, contentWidth(),
                Math.max(20, contentBottom() - tableHeaderY - Table.HEADER_HEIGHT - GAP));
        table.layout(scroll.viewportWidth());
        scroll.content(table, table.contentHeight());
        widgets.add(scroll);
        this.table = table;
    }

    private void addRow(Table into, SinkScorer.Scored scored, String chosen, boolean isBest) {
        MfpRecipe recipe = scored.recipe();
        boolean isChosen = recipe.id().equals(chosen);
        int background = isChosen ? Theme.ROW_SELECTED : 0;
        int colour = isChosen ? Theme.PINNED : (isBest ? Theme.TEXT : Theme.TEXT_DIM);

        into.addRow(List.of(
                        Cells.iconTwoLine(MachineStacks.iconForRecipeType(recipe.recipeTypeId()),
                                MachineStacks.shortName(recipe.recipeTypeId()), colour,
                                recipe.recipeTypeId() + durationSuffix(recipe),
                                tooltip(recipe, scored, isChosen)),
                        eatsCell(recipe),
                        Cells.flows(outputSlots(recipe)),
                        Cells.flows(otherInputSlots(recipe))),
                background,
                button -> choose(recipe));
    }

    /**
     * How much of the surplus one machine eats per second.
     *
     * <p>The column the decision turns on, and the one the recipe picker has no equivalent of. A
     * consumer eating half a bucket a second against a surplus of five hundred is not an answer to
     * the question, however good what it makes; without this the user finds that out by choosing it
     * and reading a machine count of a thousand.
     */
    private Table.Cell eatsCell(MfpRecipe recipe) {
        double perSecond = chooser.consumedPerSecond(recipe, surplus, plan);
        if (perSecond <= 0) {
            return Cells.text("-", Theme.TEXT_IDLE, List.of(Component.literal(
                            "No rate: hand crafting and instant conversions have no duration, so "
                                    + "there is nothing to eat per second.")
                    .withStyle(ChatFormatting.GRAY)));
        }
        return Cells.text(Fmt.number(perSecond) + "/s", Theme.TEXT_DIM,
                List.of(Component.literal(Fmt.number(perSecond) + " "
                                + KeyStacks.name(surplus).getString() + " per second, per machine"),
                        Component.literal("on the machine this plan would default to")
                                .withStyle(ChatFormatting.GRAY)));
    }

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

    /**
     * A consumer the tier ceiling refuses.
     *
     * <p>Listed and inert, which is where this differs from the recipe picker's over-tier rows: a
     * pin outranks the ceiling because the user may be planning ahead for something they are about
     * to make, and a sink is not that — it is a way of dealing with what a factory that already runs
     * gives off. Offering to build it would be offering a line that cannot run.
     */
    private void addRefusedRow(Table into, MfpRecipe recipe, String reason) {
        into.addRow(List.of(
                        Cells.iconTwoLine(MachineStacks.iconForRecipeType(recipe.recipeTypeId()),
                                MachineStacks.shortName(recipe.recipeTypeId()), Theme.TEXT_IDLE,
                                "above your tier",
                                List.of(Component.literal(recipe.id()).withStyle(ChatFormatting.WHITE),
                                        Component.literal(reason).withStyle(ChatFormatting.YELLOW),
                                        Component.literal("raise the tier you build at in Defaults "
                                                + "to reach it")
                                                .withStyle(ChatFormatting.GRAY))),
                        Cells.text("-", Theme.TEXT_IDLE),
                        Cells.flows(outputSlots(recipe)),
                        Cells.flows(otherInputSlots(recipe))),
                0, null);
    }

    /**
     * Put the line on the plan and go back.
     *
     * <p>The whole pipeline runs again rather than the solved result being patched, exactly as
     * pinning does: a sink changes what the plan demands, and the engine it needs.
     */
    private void choose(MfpRecipe recipe) {
        plan.consumeWith(surplus, recipe.id());
        ClientPlanner.refresh();
        back();
    }

    private List<SinkScorer.Scored> matching(List<SinkScorer.Scored> ranked) {
        if (filter.isEmpty()) {
            return ranked;
        }
        List<SinkScorer.Scored> kept = new ArrayList<>();
        for (SinkScorer.Scored scored : ranked) {
            if (makesSomethingCalled(scored.recipe(), filter)) {
                kept.add(scored);
            }
        }
        return kept;
    }

    private static boolean makesSomethingCalled(MfpRecipe recipe, String needle) {
        for (MfpOutput output : recipe.outputs()) {
            MfpKey key = output.key();
            if (key.toString().toLowerCase(java.util.Locale.ROOT).contains(needle)
                    || KeyStacks.name(key).getString()
                            .toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private List<Component> tooltip(MfpRecipe recipe, SinkScorer.Scored scored, boolean isChosen) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(recipe.id()).withStyle(ChatFormatting.WHITE));
        lines.add(Component.literal(recipe.recipeTypeId() + "  (" + recipe.providerId() + ")")
                .withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.literal(String.format(java.util.Locale.ROOT, "score %.1f", scored.score()))
                .withStyle(ChatFormatting.GRAY));
        for (String reason : scored.reasons()) {
            lines.add(Component.literal("  " + reason).withStyle(ChatFormatting.DARK_GRAY));
        }
        lines.add(Component.literal(isChosen
                        ? "already eating this surplus"
                        : "click to add this line to the plan")
                .withStyle(isChosen ? ChatFormatting.AQUA : ChatFormatting.GRAY));
        return lines;
    }

    private static String durationSuffix(MfpRecipe recipe) {
        if (recipe.durationTicks() <= 0) {
            return "  -  instant";
        }
        String suffix = "  -  " + Fmt.number(recipe.durationTicks()) + " t";
        if (recipe.euIn() > 0) {
            suffix += ", " + recipe.euIn() + " EU/t";
        }
        return suffix;
    }

    private List<SlotWidget> outputSlots(MfpRecipe recipe) {
        List<SlotWidget> slots = new ArrayList<>();
        for (MfpOutput output : recipe.outputs()) {
            String label = Fmt.number(output.amount()) + (output.isChanced()
                    ? " @" + Math.round(output.chance() * 100) + "%" : "");
            List<Component> lines =
                    new ArrayList<>(KeyStacks.tooltip(output.key(), label + " per craft"));
            if (imported.contains(output.key())) {
                lines.add(Component.literal("this plan is currently buying this")
                        .withStyle(ChatFormatting.GREEN));
            }
            slots.add(SlotWidget.of(output.key(), label, lines));
        }
        return slots;
    }

    /** Everything the recipe eats <em>except</em> the surplus, which is the point of the row. */
    private List<SlotWidget> otherInputSlots(MfpRecipe recipe) {
        List<SlotWidget> slots = new ArrayList<>();
        for (MfpIngredient input : recipe.inputs()) {
            if (input.candidates().contains(surplus)) {
                continue;
            }
            MfpKey primary = input.primary();
            String label = Fmt.number(input.amount()) + (input.consumed() ? "" : " (kept)");
            List<Component> lines =
                    new ArrayList<>(KeyStacks.tooltip(primary, label + " per craft"));
            if (!input.consumed()) {
                lines.add(Component.literal("not consumed - a catalyst, not a cost")
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
            slots.add(SlotWidget.of(primary, label, lines));
        }
        return slots;
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (table != null) {
            table.renderHeader(graphics, contentX(), tableHeaderY, contentWidth() - 6);
        }
        if (count == 0) {
            graphics.drawString(font, beyondCeiling.isEmpty()
                            ? "nothing in the pack consumes this"
                            : "nothing you can build consumes this yet",
                    contentX(), tableHeaderY + Table.HEADER_HEIGHT + 6, Theme.TEXT_IDLE, false);
        }
        String note = count + " consumer" + (count == 1 ? "" : "s")
                + (hiddenByCap > 0 ? ", " + shown + " shown" : "");
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
