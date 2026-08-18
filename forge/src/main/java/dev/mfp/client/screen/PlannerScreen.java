package dev.mfp.client.screen;

import dev.mfp.client.ClientIndex;
import dev.mfp.client.ClientPlan;
import dev.mfp.client.ClientPlanner;
import dev.mfp.client.KeyStacks;
import dev.mfp.client.MachineStacks;
import dev.mfp.client.MfpClient;
import dev.mfp.client.widget.Cells;
import dev.mfp.client.widget.FlowPanel;
import dev.mfp.client.widget.Fmt;
import dev.mfp.client.widget.MfpWidget;
import dev.mfp.client.widget.ScrollPanel;
import dev.mfp.client.widget.SlotWidget;
import dev.mfp.client.widget.TabBar;
import dev.mfp.client.widget.Table;
import dev.mfp.client.widget.TextButton;
import dev.mfp.client.widget.TextField;
import dev.mfp.client.widget.Theme;
import dev.mfp.client.widget.Timescale;
import dev.mfp.client.widget.Tooltip;
import dev.mfp.core.behaviour.GtTiers;
import dev.mfp.core.behaviour.MachineBehaviour;
import dev.mfp.core.behaviour.ThroughputResult;
import dev.mfp.core.model.Confidence;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.DisplayOrder;
import dev.mfp.core.plan.Line;
import dev.mfp.core.plan.LineDecision;
import dev.mfp.core.plan.MachineConfig;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.plan.PlanExport;
import dev.mfp.core.plan.SolverMode;
import dev.mfp.core.plan.TargetOutput;
import dev.mfp.core.solver.LineResult;
import dev.mfp.core.solver.SolveResult;
import dev.mfp.plan.PreferenceStore;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The planner's main screen: a solved plan, and everything that edits it.
 *
 * <p><b>Nothing here computes a rate.</b> Every edit — a target, a recipe, a machine, a plan — is a
 * mutation of the {@link Plan} followed by a full re-solve through {@code ClientPlanner}, and the
 * screen is then rebuilt from the result. That is more work than patching the table would be, and
 * it buys the property the whole screen depends on: every figure came from one solve, so no two of
 * them can belong to different answers. A planner that showed a stale machine count beside a fresh
 * one would be worse than a slow one.
 *
 * <p>Confidence is rendered rather than summarised (plan P5). A line MFP is not sure about is
 * yellow and prefixed {@code ~}, a line that cannot run at all is red with its reason, and both
 * carry the behaviour chain's own notes in the tooltip — the same strings {@code /mfp explain}
 * prints.
 */
public final class PlannerScreen extends Screen {

    private static final int MARGIN = 6;
    private static final int TITLE_HEIGHT = 14;
    private static final int LEFT_WIDTH = 132;
    private static final int STATUS_HEIGHT = 11;
    private static final int GAP = 4;
    private static final int TARGET_WIDTH = 96;

    /** Room under the plan list for its stats block: six rows of eleven pixels, and a gap. */
    private static final int STATS_HEIGHT = 68;

    /** For the energy column, so a steam machine's cost is recognisable as steam at a glance. */
    private static final MfpKey STEAM = MfpKey.fluid("gtceu", "steam");

    /** Kept across openings: display preferences the user should not have to set twice. */
    private static Timescale timescale = Timescale.PER_SECOND;
    private static boolean perMachine;
    private static int selectedTab;

    private final Tooltip tooltip = new Tooltip();
    private final List<MfpWidget> widgets = new ArrayList<>();

    private ClientPlan plan;
    private Table table;
    private ScrollPanel scroll;
    private int tableX;
    private int tableWidth;
    private int headerY;
    private int targetsY;
    private List<String> warningStrip = List.of();
    private int warningStripY;
    private int planListY;
    private final List<Integer> planRowY = new ArrayList<>();

    /** What the last export or import said, and how long it stays on the title bar. */
    private String notice;
    private boolean noticeIsError;
    private long noticeUntil;

    /**
     * The drag gesture, held on the screen rather than in the table.
     *
     * <p>The table is rebuilt whenever anything about the plan changes, so state living in it would
     * not survive the gesture that changes the plan. These fields outlive every rebuild because the
     * screen instance does — a modal returns to this same object, and even a resize only re-runs
     * {@code init}.
     */
    private List<String> displayedOrder = List.of();
    private int grabbedRow = -1;
    private int grabbedArrow;
    private double grabbedAtY;
    private double dragMouseY;
    private boolean dragging;
    private int dropIndex = -1;

    /** How far the mouse must move before a press on the grip stops being a click on an arrow. */
    private static final int DRAG_THRESHOLD = 4;

    /** Pixels from the viewport edge at which a drag starts scrolling the table under itself. */
    private static final int AUTOSCROLL_MARGIN = 14;
    private static final int AUTOSCROLL_SPEED = 5;

    public PlannerScreen() {
        super(Component.literal("Minecraft Factory Planner"));
    }

    /**
     * Closing the planner is the natural moment to write.
     *
     * <p>Logout writes as well, but a session can end without one — a crash, or the game being
     * killed — and losing an afternoon's planning to that would be the kind of failure a user never
     * forgives. Writing here costs a few kilobytes at the one moment the user is not looking at the
     * screen.
     */
    @Override
    public void onClose() {
        ClientPlanner.saveAll(MfpClient.worldName());
        super.onClose();
    }

    @Override
    protected void init() {
        this.plan = ClientPlanner.current();
        rebuild();
    }

    /** Re-solve and redraw. What every edit ends with. */
    private void resolve() {
        ClientPlanner.refresh();
        this.plan = ClientPlanner.current();
        rebuild();
    }

    /** Rebuilds every widget. Called on open, on resize, and whenever anything changes. */
    private void rebuild() {
        widgets.clear();
        planRowY.clear();
        int panelX = MARGIN;
        int panelY = MARGIN;
        int panelWidth = width - 2 * MARGIN;
        int panelHeight = height - 2 * MARGIN;

        int rightX = panelX + 4 + LEFT_WIDTH + GAP + 2;
        int rightWidth = panelX + panelWidth - 4 - rightX;
        int cursorY = panelY + TITLE_HEIGHT + 3;

        buildPlanList(panelX + 4, cursorY, panelY + panelHeight - STATUS_HEIGHT - 4);

        if (plan == null) {
            return;
        }

        // Toolbar ------------------------------------------------------------
        TextButton refresh = new TextButton("Refresh", this::resolve);
        refresh.tooltip("Re-index and solve again. Behaviour overrides are re-read from disk.");
        refresh.bounds(rightX, cursorY, refresh.preferredWidth(), 14);
        widgets.add(refresh);

        TextButton scaleToggle = new TextButton("Rate: " + timescale.suffix(), () -> {
            timescale = timescale.next();
            rebuild();
        });
        scaleToggle.tooltip("Per second, per minute or stacks per minute. A display multiplication "
                + "only - everything MFP stores is per second.");
        scaleToggle.bounds(refresh.x() + refresh.width() + GAP, cursorY, scaleToggle.preferredWidth(), 14);
        widgets.add(scaleToggle);

        TextButton perMachineToggle = new TextButton(perMachine ? "Per machine" : "Per line", () -> {
            perMachine = !perMachine;
            rebuild();
        });
        perMachineToggle.tooltip("Show each line's flows for one machine rather than for the whole "
                + "line - what a single machine's hatches must be fed.");
        perMachineToggle.bounds(scaleToggle.x() + scaleToggle.width() + GAP, cursorY,
                perMachineToggle.preferredWidth(), 14);
        widgets.add(perMachineToggle);

        // The engine belongs on the main screen, not two clicks away in Settings: it is the one
        // setting that changes every number on the page, and under AUTO the engine that actually ran
        // is a fact about this solve rather than a preference.
        TextButton engine = new TextButton(engineLabel(), () -> {
            // From what the button says, not from what the plan holds: a derived MATRIX reads as
            // "Auto", and advancing from MATRIX would make the first press appear to do nothing.
            plan.plan().solverMode(nextMode(displayedMode()));
            resolve();
        });
        engine.tooltip(engineTooltip());
        engine.bounds(perMachineToggle.x() + perMachineToggle.width() + GAP, cursorY,
                engine.preferredWidth(), 14);
        widgets.add(engine);

        TextButton settings = new TextButton("Settings", () ->
                Minecraft.getInstance().setScreen(new PlanSettingsScreen(this)));
        settings.tooltip("Name, solver engine, and the items this plan may import freely.");
        settings.bounds(engine.x() + engine.width() + GAP, cursorY, settings.preferredWidth(), 14);
        widgets.add(settings);

        // Beside Settings rather than inside it, because the two are different scopes and the
        // distinction is the whole of M8: Settings is about this plan, Defaults is about every plan.
        TextButton defaults = new TextButton("Defaults", () ->
                Minecraft.getInstance().setScreen(new DefaultsScreen(this)));
        defaults.tooltip("How you make things, the tier you build at, and what you have no supply of "
                + "- applied to every plan, and overruled by anything you decide in one.");
        defaults.bounds(settings.x() + settings.width() + GAP, cursorY, defaults.preferredWidth(), 14);
        widgets.add(defaults);

        cursorY += 14 + GAP;

        // Targets --------------------------------------------------------------
        // On the main page with its own heading above it. They used to be labelled in the gutter
        // beside the plan list, where the plan list's own buttons clipped the label — and a target
        // is a property of the plan on screen, not of the list of plans.
        this.targetsY = cursorY;
        cursorY = buildTargets(rightX, cursorY + 10, rightWidth);

        // Warnings -----------------------------------------------------------
        List<String> warnings = allWarnings();
        if (!warnings.isEmpty()) {
            cursorY += 11;
        }
        int warningsY = cursorY - 11;

        // Products / byproducts / imports -------------------------------------
        SolveResult solved = plan.solveResult();
        TabBar tabs = new TabBar(List.of(
                new TabBar.Tab("Products", solved.products().size(),
                        "What the plan delivers, against what was asked for."),
                new TabBar.Tab("Byproducts", countReal(solved.byproducts()),
                        "Surplus nothing in the plan consumes."),
                new TabBar.Tab("Imports", countReal(solved.rawInputs()),
                        "What must come from outside: ores, water, anything the plan does not make.")),
                selectedTab,
                index -> {
                    selectedTab = index;
                    rebuild();
                });
        tabs.bounds(rightX, cursorY, rightWidth, 14);
        widgets.add(tabs);
        cursorY += 14;

        FlowPanel flows = new FlowPanel(tabSlots(solved), emptyMessage());
        int flowHeight = Math.min(56, Math.max(18, flows.preferredHeight(rightWidth - 8) + 6));
        flows.bounds(rightX + 4, cursorY + 4, rightWidth - 8, flowHeight - 6);
        widgets.add(flows);
        cursorY += flowHeight + GAP;

        // Production table ----------------------------------------------------
        this.tableX = rightX;
        this.headerY = cursorY;
        int bodyY = cursorY + Table.HEADER_HEIGHT;
        int bodyHeight = panelY + panelHeight - 4 - STATUS_HEIGHT - bodyY;

        this.scroll = new ScrollPanel();
        scroll.bounds(rightX, bodyY, rightWidth, Math.max(20, bodyHeight));
        this.tableWidth = scroll.viewportWidth();

        this.table = buildTable();
        table.layout(tableWidth);
        scroll.content(table, table.contentHeight());
        widgets.add(scroll);

        this.warningStripY = warningsY;
        this.warningStrip = warnings;
    }

    // ----------------------------------------------------------------- engine

    /**
     * The engine button's label: what was asked for, and under AUTO what actually ran.
     *
     * <p>The plan already distinguishes a mode the user chose from one the chooser worked out
     * ({@code solverModeDerived}); this is where that distinction becomes visible. "Auto (matrix)"
     * says both things at once — nobody pinned an engine, and a loop in this plan needed one.
     */
    /** What the button says the mode is: a derived mode is the chooser's work, not the user's. */
    private SolverMode displayedMode() {
        return plan.plan().solverModeDerived() ? SolverMode.AUTO : plan.plan().solverMode();
    }

    private String engineLabel() {
        SolverMode chosen = displayedMode();
        String label = "Solver: " + name(chosen);
        return chosen == SolverMode.AUTO ? label + " (" + name(plan.solveResult().engine()) + ")" : label;
    }

    private String engineTooltip() {
        SolverMode actual = plan.solveResult().engine();
        String tail = plan.plan().solverModeDerived() || plan.plan().solverMode() == SolverMode.AUTO
                ? " This solve used the " + name(actual) + " engine."
                : "";
        return "Auto picks the matrix engine when the chooser observes a loop and the sequential one "
                + "otherwise, and hands the plan to simplex when it carries a machine limit or a line "
                + "percentage, which only simplex can honour. Choosing explicitly sticks until you "
                + "change it, and a plan with a loop forced onto the sequential engine will import "
                + "its way around it." + tail;
    }

    private static SolverMode nextMode(SolverMode mode) {
        SolverMode[] modes = SolverMode.values();
        return modes[(mode.ordinal() + 1) % modes.length];
    }

    private static String name(SolverMode mode) {
        String lower = mode.name().toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    // ---------------------------------------------------------------- plan list

    private void buildPlanList(int listX, int listY, int bottomY) {
        this.planListY = listY;

        int half = (LEFT_WIDTH - 6) / 2;
        int rightHalfX = listX + 4 + half;
        int buttonY = listY + 15;
        boolean hasPlan = ClientPlanner.current() != null;

        // Held outside the picker because it is answered before the item is: choosing the item both
        // creates the plan and expands it, so this cannot be a setting the user reaches afterwards.
        boolean[] automatic = { PreferenceStore.get().autoResolve() };
        TextButton create = new TextButton("New", () -> Minecraft.getInstance().setScreen(
                new ItemPickerScreen(this, "New plan", key -> {
                    // A starting point, not a guess at what the user wants: the rate is the first
                    // thing they edit. It is one per second for an item and a bucket per second for
                    // a fluid, both decided by TargetOutput rather than here.
                    // Unnamed, so it goes on calling itself after whatever it currently makes.
                    ClientPlanner.add(new Plan().target(TargetOutput.of(key))
                            .autoResolve(automatic[0]));
                }).withOption(
                        () -> "Expansion: " + (automatic[0] ? "automatic" : "by hand"),
                        () -> automatic[0] = !automatic[0],
                        "By hand adds the recipe for what you asked for, follows your default "
                                + "recipes as far as they go, and leaves every ingredient past them "
                                + "as a question you answer by clicking it. Automatic "
                                + "picks the whole chain. Starts at your standing default, set in "
                                + "Defaults, and this plan can be switched later in Settings.")));
        create.tooltip("Start another plan. Plans are saved with the world.");
        create.bounds(listX + 2, buttonY, half, 14);
        widgets.add(create);

        TextButton delete = new TextButton("Delete", () -> {
            ClientPlanner.remove(ClientPlanner.currentIndex());
            this.plan = ClientPlanner.current();
            rebuild();
        });
        delete.enabled(hasPlan);
        delete.tooltip("Forget the current plan.");
        delete.bounds(rightHalfX, buttonY, half, 14);
        widgets.add(delete);
        buttonY += 16;

        // Renaming is here rather than only in Settings because a name is a property of the list,
        // and the list is where the user is standing when they cannot tell two plans apart (M9.1).
        TextButton rename = new TextButton("Rename", () -> {
            ClientPlan current = ClientPlanner.current();
            if (current != null) {
                Minecraft.getInstance().setScreen(new RenameScreen(this, current.plan()));
            }
        });
        rename.enabled(hasPlan);
        rename.tooltip("Name this plan. Until you do, it is named after what it makes and follows "
                + "the target if you change it.");
        rename.bounds(listX + 2, buttonY, half, 14);
        widgets.add(rename);

        TextButton duplicate = new TextButton("Copy", () -> {
            ClientPlan current = ClientPlanner.current();
            if (current != null) {
                ClientPlanner.add(current.plan().copy(current.plan().name() + " (copy)"));
                this.plan = ClientPlanner.current();
                rebuild();
            }
        });
        duplicate.enabled(hasPlan);
        duplicate.tooltip("Copy this plan's targets and every choice in it into a new plan, so two "
                + "builds can be compared side by side. The lines are re-solved rather than copied, "
                + "so editing one plan never moves the other.");
        duplicate.bounds(rightHalfX, buttonY, half, 14);
        widgets.add(duplicate);
        buttonY += 16;

        // Export and import, through the clipboard (M9.3). The clipboard rather than a text box
        // because the string is a few hundred characters and its whole purpose is to be pasted
        // somewhere else — a field the user has to select the contents of adds a step to both ends.
        TextButton export = new TextButton("Export", this::exportToClipboard);
        export.enabled(hasPlan);
        export.tooltip("Copy this whole plan to the clipboard as a string: its targets, pinned "
                + "recipes, machines, raw materials and hand order. Paste it anywhere and import it "
                + "back here or in someone else's game.");
        export.bounds(listX + 2, buttonY, half, 14);
        widgets.add(export);

        TextButton importButton = new TextButton("Import", this::importFromClipboard);
        importButton.tooltip("Read a plan string from the clipboard and open it as a new plan. "
                + "Recipes this world does not have are reported rather than refused.");
        importButton.bounds(rightHalfX, buttonY, half, 14);
        widgets.add(importButton);
        buttonY += 14 + GAP;

        int rowY = buttonY;
        List<ClientPlan> plans = ClientPlanner.plans();
        for (int i = 0; i < plans.size(); i++) {
            if (rowY + 13 > bottomY - STATS_HEIGHT) {
                break;
            }
            int index = i;
            Plan listed = plans.get(i).plan();
            TextButton entry = new TextButton(planLabel(listed), () -> {
                ClientPlanner.select(index);
                this.plan = ClientPlanner.current();
                rebuild();
            });
            entry.icon(planIcon(listed));
            entry.tooltip(planLabel(listed));
            entry.bounds(listX + 2, rowY, LEFT_WIDTH - 4, 13);
            widgets.add(entry);
            planRowY.add(rowY);
            rowY += 14;
        }
    }

    /**
     * What a plan is called in the list: its name, or the thing it makes and how fast.
     *
     * <p>The derived form is built here rather than taken from {@link Plan#derivedName()} because
     * this is the one caller that can do better — {@code KeyStacks} knows the item's translated
     * name, and "Ethanol x1000/s" is a plan a user recognises where {@code gtceu:ethanol x 1000/s}
     * is one they have to read.
     */
    private static String planLabel(Plan listed) {
        if (listed.isNamed() || listed.targets().isEmpty()) {
            return listed.name();
        }
        TargetOutput first = listed.targets().get(0);
        return KeyStacks.name(first.key()).getString() + " x" + Fmt.number(first.perSecond()) + "/s";
    }

    /** The plan's end product, drawn the same way the Products column draws it. */
    private static ItemStack planIcon(Plan listed) {
        return listed.targets().isEmpty()
                ? ItemStack.EMPTY
                : KeyStacks.icon(listed.targets().get(0).key());
    }

    // ------------------------------------------------------------------ export and import

    private void exportToClipboard() {
        ClientPlan current = ClientPlanner.current();
        if (current == null) {
            return;
        }
        String text = PlanExport.export(current.plan());
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
        notice("Copied \"" + planLabel(current.plan()) + "\" to the clipboard ("
                + text.length() + " characters)", false);
    }

    /**
     * Read a plan from the clipboard.
     *
     * <p>An unreadable string says why and changes nothing. A readable one that names recipes this
     * world does not have still opens — the plan is worth what remains of it, and the dropped pins
     * are reported rather than left to be discovered as "why did it pick that?" three screens later.
     */
    private void importFromClipboard() {
        String text = Minecraft.getInstance().keyboardHandler.getClipboard();
        PlanExport.Imported imported;
        try {
            imported = PlanExport.parse(text, recipeId -> ClientIndex.get().recipe(recipeId) != null);
        } catch (PlanExport.PlanFormatException e) {
            notice(e.getMessage(), true);
            return;
        } catch (RuntimeException e) {
            notice("that plan string could not be read: " + e, true);
            return;
        }
        ClientPlanner.add(imported.plan());
        this.plan = ClientPlanner.current();
        rebuild();
        notice(imported.isClean()
                ? "Imported \"" + planLabel(imported.plan()) + "\""
                : "Imported \"" + planLabel(imported.plan()) + "\", with "
                        + imported.problems().size() + " thing(s) this world does not have: "
                        + imported.problems().get(0),
                !imported.isClean());
    }

    /**
     * Say something in the title bar for a few seconds.
     *
     * <p>In the title bar rather than in chat because chat is not visible behind a screen, and on a
     * timer because it reports an action rather than a state — a message about a copy made a minute
     * ago is furniture. Errors stay twice as long: they are the ones worth reading twice.
     */
    private void notice(String message, boolean error) {
        this.notice = message;
        this.noticeIsError = error;
        this.noticeUntil = Util.getMillis() + (error ? 12_000L : 6_000L);
    }

    // ------------------------------------------------------------------ targets

    /**
     * The plan's targets, editable in place.
     *
     * <p>Each is an icon, a rate and a remove button. The rate commits on Enter or on losing focus
     * rather than on every keystroke: a re-solve per character would be both wasteful and confusing,
     * since "1", "12" and "125" are all valid rates and only the last one was meant.
     */
    private int buildTargets(int x, int y, int availableWidth) {
        List<TargetOutput> targets = plan.plan().targets();
        int cursorX = x;
        int cursorY = y;

        for (int i = 0; i < targets.size(); i++) {
            int index = i;
            TargetOutput target = targets.get(i);

            if (cursorX > x && cursorX + TARGET_WIDTH > x + availableWidth) {
                cursorX = x;
                cursorY += 16 + GAP;
            }

            SlotWidget icon = SlotWidget.of(target.key(), "",
                    KeyStacks.tooltip(target.key(), Fmt.rate(target.perSecond(), Timescale.PER_SECOND)
                            + " wanted"));
            icon.bounds(cursorX, cursorY, SlotWidget.ICON, SlotWidget.ICON);
            widgets.add(icon);

            TextField rate = new TextField()
                    .text(Fmt.number(target.perSecond()))
                    .filter(TextField.NUMERIC)
                    .maxLength(10)
                    .tooltip("Wanted per second. Everything downstream is sized from this.")
                    .onCommit(value -> {
                        double wanted = parseDouble(value, target.perSecond());
                        if (wanted > 0 && wanted != target.perSecond()) {
                            plan.plan().setTarget(index, new TargetOutput(target.key(), wanted));
                            resolve();
                        }
                    });
            rate.bounds(cursorX + SlotWidget.ICON + 2, cursorY + 1, 48, 14);
            widgets.add(rate);

            TextButton remove = new TextButton("X", () -> {
                plan.plan().removeTarget(index);
                resolve();
            });
            remove.centred();
            remove.tooltip("Stop asking for " + KeyStacks.name(target.key()).getString());
            remove.bounds(cursorX + SlotWidget.ICON + 2 + 48 + 2, cursorY + 1, 12, 14);
            widgets.add(remove);

            cursorX += TARGET_WIDTH;
        }

        TextButton add = new TextButton("+ Target", () -> Minecraft.getInstance().setScreen(
                new ItemPickerScreen(this, "Add a target", key -> {
                    // TargetOutput decides the rate, so a fluid starts at a bucket a second rather
                    // than at a millibucket - and the GUI and the commands cannot disagree about it.
                    plan.plan().target(TargetOutput.of(key));
                    ClientPlanner.refresh();
                })));
        add.tooltip("Ask this plan for something else as well. One plan can make several things.");
        if (cursorX > x && cursorX + add.preferredWidth() > x + availableWidth) {
            cursorX = x;
            cursorY += 16 + GAP;
        }
        add.bounds(cursorX, cursorY + 1, add.preferredWidth(), 14);
        widgets.add(add);

        return cursorY + 16 + GAP;
    }

    // ------------------------------------------------------------------ table

    private static final List<Table.Column> COLUMNS = List.of(
            new Table.Column("", 0.45f,
                    "Move a line up or down. Display order only - the solver's own order is "
                            + "topological and is not changed by this."),
            new Table.Column("Machine", 2.4f, "Click to choose the machine, its tier and what is built into it."),
            new Table.Column("Tier", 0.6f, "Click to configure the machine."),
            new Table.Column("Machines", 1.0f, "Fractional need, with the number to build in brackets."),
            new Table.Column("EU/t", 0.9f, "Drawn (-) and produced (+) by the whole line."),
            new Table.Column("Products", 2.6f,
                    "What this line delivers. Hover it for the recipe behind it, and click it to "
                            + "see every other way of making the same thing."),
            new Table.Column("Byproducts", 1.8f,
                    "Surplus this line makes. Empty under the matrix engine, which balances the "
                            + "plan as a whole - see the Byproducts tab."),
            new Table.Column("Ingredients", 2.6f,
                    "What this line eats. Click one to say where it comes from - the picker opens on "
                            + "that item, and choosing a recipe adds the line that makes it. This is "
                            + "how a chain is built out by hand."));

    private Table buildTable() {
        Table built = new Table(COLUMNS);
        SolveResult solved = plan.solveResult();

        // The rows may be in the user's own order; the lines underneath them are not (plan §13 M7.6).
        List<LineResult> ordered = DisplayOrder.apply(plan.plan().displayOrder(), solved.lines(),
                result -> result.line().recipe().id());
        this.displayedOrder = ordered.stream().map(result -> result.line().recipe().id()).toList();

        for (int row = 0; row < ordered.size(); row++) {
            LineResult result = ordered.get(row);
            int position = row;
            Line line = result.line();
            MfpRecipe recipe = line.recipe();
            MachineConfig config = line.machine();
            ThroughputResult throughput = plan.throughputFor(line);
            double divisor = perMachine ? Math.max(1e-9, result.machineCount()) : 1.0;

            int background = throughput.cancelled() ? 0x30FF3030 : 0;
            int textColour = result.isIdle() ? Theme.TEXT_IDLE : Theme.forConfidence(result.confidence());

            Table.Cell machineCell = Cells.clickable(
                    Cells.iconText(machineIcon(config, recipe), machineName(config), textColour,
                            machineTooltip(config, line)),
                    () -> openMachineConfig(line));

            built.addRow(List.of(
                    Cells.reorder(position > 0, position < ordered.size() - 1,
                            (arrow, mouseY) -> grab(position, arrow, mouseY),
                            List.of(Component.literal("Drag to move this line, or use the arrows")
                                            .withStyle(ChatFormatting.WHITE),
                                    Component.literal("Display order only: the solver walks the plan "
                                            + "in its own order and this does not change any number.")
                                            .withStyle(ChatFormatting.GRAY))),
                    machineCell,
                    Cells.clickable(tierCell(result, recipe, config), () -> openMachineConfig(line)),
                    machineCountCell(result, throughput),
                    energyCell(result),
                    // The recipe used to have a column of its own, showing its id. Almost nothing a
                    // user decides depends on that string, and it cost the width the flows wanted, so
                    // it moved here: the products are how a line is recognised anyway, and the recipe
                    // behind them is a hover away and one click from being replaced.
                    Cells.clickable(
                            Cells.orTooltip(Cells.flows(slotsFor(result.outputs(), divisor)),
                                    recipeTooltip(line, throughput, producedFor(recipe, result))),
                            () -> openRecipePicker(recipe, result)),
                    byproductCell(result, divisor),
                    // The one column whose flows lead somewhere: an ingredient is a question about
                    // where it comes from, and clicking it is how the chain is built out (M11.2).
                    Cells.flows(ingredientSlots(result.inputs(), divisor, solved))
            ), background, null, markerFor(line));
        }
        return built;
    }

    // -------------------------------------------------------------- reordering

    /**
     * A press on a row's grip, which is not yet known to be a click or a drag.
     *
     * <p>Nothing happens here on purpose. Whether the user meant "nudge this row one place" or "pick
     * this row up" is answered by what they do next, and acting on the press would make the first
     * pixel of a drag also perform a move.
     */
    private void grab(int row, int arrow, double mouseY) {
        this.grabbedRow = row;
        this.grabbedArrow = arrow;
        this.grabbedAtY = mouseY;
        this.dragMouseY = mouseY;
        this.dragging = false;
        this.dropIndex = -1;
    }

    /** Where the held row would land if it were dropped now. */
    private void updateDrop() {
        if (table != null) {
            this.dropIndex = table.insertionIndexAt(dragMouseY);
        }
    }

    /**
     * Scrolls the table under a drag held near the top or bottom of the viewport.
     *
     * <p>Necessary rather than a nicety: a plan longer than the screen cannot otherwise be reordered
     * across the fold at all, which is exactly the plan long enough to want reordering.
     */
    private void autoScroll() {
        if (scroll == null || !scroll.scrollable()) {
            return;
        }
        if (dragMouseY < scroll.y() + AUTOSCROLL_MARGIN) {
            scroll.scrollBy(-AUTOSCROLL_SPEED);
        } else if (dragMouseY > scroll.y() + scroll.height() - AUTOSCROLL_MARGIN) {
            scroll.scrollBy(AUTOSCROLL_SPEED);
        }
    }

    /**
     * Drops the held row at the indicator.
     *
     * <p>An insertion boundary is one greater than the row index once the boundary is below the row
     * being moved, because lifting the row out closes the gap it left behind.
     */
    private void dropAt(int from, int insertion) {
        int to = insertion > from ? insertion - 1 : insertion;
        if (to != from) {
            reorderTo(DisplayOrder.movedTo(displayedOrder, from, to));
        }
    }

    /**
     * Stores a new display order and redraws, without re-solving.
     *
     * <p>Every other edit on this screen is a mutation plus a full re-solve; this one is not, and
     * that is the point — the order is presentation, and re-solving would be both wasteful and a lie
     * about what the gesture did.
     *
     * <p>The whole displayed order is stored, not just the entries that moved: an order that
     * recorded only the moves would depend on the solver's order staying put underneath it, and the
     * solver's order changes whenever a recipe does.
     */
    private void reorderTo(List<String> newOrder) {
        plan.plan().displayOrder(newOrder);
        rebuild();
    }

    /**
     * The bar down a row's left edge saying the user decided something about this line.
     *
     * <p>Without it there is no way to tell a line the scorer picked from one the user pinned, which
     * matters most immediately after a re-solve: a pin that failed to survive looks exactly like a
     * pin that did.
     */
    private int markerFor(Line line) {
        // The standing defaults are passed in so that a line following one is marked too (M8): a
        // recipe chosen by a preference set weeks ago is not the scorer's pick, and a user who could
        // not tell the difference would go looking for the decision in the wrong place.
        return plan.plan().decisionsFor(line, PreferenceStore.get()).isEmpty() ? 0 : Theme.PINNED;
    }

    /**
     * Opens the recipe picker on the item this line exists to make.
     *
     * <p>The largest guaranteed output, because that is what the plan asked this line for. Picking
     * a chanced byproduct instead would offer the user a list of ways to make something they were
     * not trying to make.
     */
    private void openRecipePicker(MfpRecipe recipe, LineResult result) {
        MfpKey key = producedFor(recipe, result);
        if (key != null) {
            Minecraft.getInstance().setScreen(new RecipePickerScreen(this, key));
        }
    }

    /**
     * The item this line exists to make, which is not always the item the recipe is named after.
     *
     * <p>Bio chaff comes out of a recipe called {@code fermenting/ethanol}, because ethanol is what
     * that recipe mostly makes; a plan that wants bio chaff picks it anyway, correctly. Opening the
     * picker on the recipe's largest output then offered ways to make <em>ethanol</em> — so the one
     * line you wanted to change was the one line you could not, and the picker looked broken rather
     * than mis-aimed.
     *
     * <p>The solved line already knows: {@code outputs()} is production something demanded, and
     * {@code byproducts()} is production nothing asked for. Where that leaves more than one
     * candidate, an output another line consumes, or that the plan targets, is the one that was
     * wanted. Only when nothing distinguishes them does this fall back to the largest, which is the
     * old behaviour and right for a single-output recipe.
     */
    private MfpKey producedFor(MfpRecipe recipe, LineResult result) {
        if (result != null && !result.outputs().isEmpty()) {
            List<MfpKey> demanded = List.copyOf(result.outputs().keySet());
            if (demanded.size() == 1) {
                return demanded.get(0);
            }
            for (MfpKey candidate : demanded) {
                if (isWantedElsewhere(candidate, recipe)) {
                    return candidate;
                }
            }
        }

        MfpKey key = null;
        double best = -1;
        for (MfpOutput output : recipe.outputs()) {
            double amount = output.expectedAmount();
            if (amount > best) {
                best = amount;
                key = output.key();
            }
        }
        return key;
    }

    /** Whether the plan targets this key, or some other line consumes it. */
    private boolean isWantedElsewhere(MfpKey key, MfpRecipe from) {
        for (TargetOutput target : plan.plan().targets()) {
            if (target.key().equals(key)) {
                return true;
            }
        }
        for (Line line : plan.plan().allLines()) {
            if (line.recipe().id().equals(from.id())) {
                continue;
            }
            if (line.recipe().consumes(key)) {
                return true;
            }
        }
        return false;
    }

    private void openMachineConfig(Line line) {
        Minecraft.getInstance().setScreen(
                new MachineConfigScreen(this, line.recipe(), line.machine()));
    }

    private Table.Cell machineCountCell(LineResult result, ThroughputResult throughput) {
        if (throughput.cancelled()) {
            return Cells.text("cannot run", Theme.ERROR,
                    List.of(Component.literal("This machine cannot run this recipe")
                                    .withStyle(ChatFormatting.RED),
                            Component.literal(String.valueOf(throughput.cancelReason()))
                                    .withStyle(ChatFormatting.WHITE),
                            Component.literal("click the machine column to change it")
                                    .withStyle(ChatFormatting.GRAY)));
        }
        String marker = result.confidence() == Confidence.EXACT ? "" : "~";
        String text = marker + Fmt.machines(result.machineCount(), result.machinesToBuild());

        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(Fmt.number(result.machineCount()) + " machines needed, build "
                + result.machinesToBuild()).withStyle(ChatFormatting.WHITE));
        lines.add(Component.literal(Fmt.rate(result.craftsPerSecond(), timescale) + " crafts")
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("confidence: " + result.confidence())
                .withStyle(result.confidence() == Confidence.EXACT
                        ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        if (result.note() != null) {
            lines.add(Component.literal(result.note()).withStyle(ChatFormatting.YELLOW));
        }
        for (String note : throughput.notes()) {
            lines.add(Component.literal("- " + note).withStyle(ChatFormatting.YELLOW));
        }
        if (result.isIdle()) {
            lines.add(Component.literal("idle: nothing in the plan demands this line's output")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        return Cells.text(text, result.isIdle() ? Theme.TEXT_IDLE : Theme.forConfidence(result.confidence()),
                lines);
    }

    private Table.Cell energyCell(LineResult result) {
        if (result.euOutPerSecond() > 0) {
            return Cells.text("+" + Fmt.eut(result.euOutPerSecond()), Theme.ENERGY_OUT,
                    List.of(Component.literal("generates " + Fmt.number(result.euOutPerSecond()) + " EU/s")));
        }
        if (result.euInPerSecond() > 0) {
            return Cells.text("-" + Fmt.eut(result.euInPerSecond()), Theme.ENERGY_IN,
                    List.of(Component.literal("draws " + Fmt.number(result.euInPerSecond()) + " EU/s")));
        }
        if (result.isSteamPowered()) {
            // Steam rather than EU, and labelled as such in the cell itself: a bare number in the
            // energy column would be read as EU/t and be wrong by the machine's conversion rate.
            return Cells.iconText(KeyStacks.icon(STEAM),
                    "-" + Fmt.steam(result.steamPerSecond()) + " mB/t", Theme.ENERGY_IN,
                    List.of(Component.literal("burns " + Fmt.number(result.steamPerSecond())
                                    + " mB/s of steam"),
                            Component.literal("A steam machine draws no EU: it needs a boiler and a "
                                    + "pipe, not a power line.").withStyle(ChatFormatting.DARK_GRAY)));
        }
        return Cells.text("-", Theme.TEXT_IDLE);
    }

    private Table.Cell byproductCell(LineResult result, double divisor) {
        if (result.byproducts().isEmpty()) {
            // Now a plain statement of fact under every engine. It used to carry a disclaimer, because
            // the whole-plan engines put a line's entire production under outputs and left this column
            // empty whether or not the plan wanted it — so a distillation tower making eight fluids
            // for the sake of one called all eight products here while the Byproducts tab called seven
            // of them surplus. The engines attribute it per line now.
            return Cells.text("-", Theme.TEXT_IDLE);
        }
        return Cells.flows(slotsFor(result.byproducts(), divisor));
    }

    private List<Component> recipeTooltip(Line line, ThroughputResult throughput,
                                          MfpKey producedFor) {
        MfpRecipe recipe = line.recipe();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(recipe.id()).withStyle(ChatFormatting.WHITE));
        // A recipe is named after whatever it mostly makes, which need not be why this line is here:
        // bio chaff comes out of a recipe called fermenting/ethanol. Saying so removes the confusion
        // at its source, and matches what clicking the cell will open.
        if (producedFor != null && !recipe.produces(producedFor)) {
            producedFor = null;
        }
        if (producedFor != null && recipe.outputs().size() > 1) {
            lines.add(Component.literal("in this plan, for " + KeyStacks.name(producedFor).getString())
                    .withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.literal(recipe.recipeTypeId() + "  (" + recipe.providerId() + ")")
                .withStyle(ChatFormatting.DARK_GRAY));
        if (recipe.durationTicks() > 0) {
            double effective = throughput.durationTicks(recipe.durationTicks());
            String duration = Fmt.number(recipe.durationTicks()) + " t";
            if (Math.abs(effective - recipe.durationTicks()) > 1e-6) {
                duration += " -> " + Fmt.number(effective) + " t";
            }
            lines.add(Component.literal("duration: " + duration).withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(Component.literal("no intrinsic rate (instant)").withStyle(ChatFormatting.GRAY));
        }
        if (recipe.euIn() > 0) {
            double effective = throughput.eut(recipe.euIn() * Math.max(1, recipe.amperage()));
            lines.add(Component.literal("EU/t: " + recipe.euIn() + " -> " + Fmt.number(effective))
                    .withStyle(ChatFormatting.GRAY));
        }
        if (throughput.contentMultiplier() != 1.0) {
            lines.add(Component.literal("parallel crafts per cycle: x"
                    + Fmt.number(throughput.contentMultiplier())).withStyle(ChatFormatting.GRAY));
        }
        if (throughput.overclocks() > 0) {
            lines.add(Component.literal("overclocks: " + throughput.overclocks())
                    .withStyle(ChatFormatting.GRAY));
        }
        // What the marker bar on this row means, spelled out: a colour says "you decided something
        // here" and nothing more, and which decision it was is the thing worth knowing.
        for (LineDecision decision : plan.plan().decisionsFor(line, PreferenceStore.get())) {
            lines.add(Component.literal(decision == LineDecision.STANDING_DEFAULT
                            ? decision.label() + ", from Defaults"
                            : decision.label() + " by you")
                    .withStyle(ChatFormatting.AQUA));
        }
        lines.add(Component.literal("click for every other way to make this")
                .withStyle(ChatFormatting.GRAY));
        return lines;
    }

    private List<Component> machineTooltip(MachineConfig config, Line line) {
        List<Component> lines = new ArrayList<>();
        if (config.machineId() == null) {
            lines.add(Component.literal("No machine chosen; the recipe runs exactly as written.")
                    .withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("click to choose one").withStyle(ChatFormatting.GRAY));
            return lines;
        }
        lines.add(Component.literal(config.machineId()).withStyle(ChatFormatting.WHITE));
        MfpMachine machine = ClientIndex.peek().machine(config.machineId());
        if (machine != null && machine.multiblock()) {
            lines.add(Component.literal("multiblock").withStyle(ChatFormatting.DARK_GRAY));
        }
        if (config.parallels() > 1) {
            lines.add(Component.literal("parallels: " + config.parallels()).withStyle(ChatFormatting.GRAY));
        }
        if (config.hasLimit()) {
            lines.add(Component.literal((config.forceLimit() ? "exactly " : "at most ")
                    + Fmt.number(config.limit()) + " machines").withStyle(ChatFormatting.GRAY));
        }
        config.structureOptions().forEach((key, value) ->
                lines.add(Component.literal(key + ": " + value).withStyle(ChatFormatting.GRAY)));

        List<MachineBehaviour> chain = plan.resolver().chainFor(line.recipe(), config);
        if (chain.isEmpty()) {
            lines.add(Component.literal("no behaviour recognised - rates are the recipe's own")
                    .withStyle(ChatFormatting.YELLOW));
        } else {
            lines.add(Component.literal("behaviours, in order:").withStyle(ChatFormatting.DARK_GRAY));
            chain.forEach(behaviour -> lines.add(Component.literal("  " + behaviour.id())
                    .withStyle(ChatFormatting.GRAY)));
        }
        lines.add(Component.literal("click to configure").withStyle(ChatFormatting.GRAY));
        return lines;
    }

    // ------------------------------------------------------------------ flows

    private List<SlotWidget> slotsFor(Map<MfpKey, Double> flows, double divisor) {
        // Energy is excluded because it has its own column; showing a redstone torch beside the
        // EU/t figure would double-count it to the eye.
        return flows.entrySet().stream()
                .filter(entry -> entry.getKey().kind() != MfpKey.Kind.ENERGY)
                .sorted(Comparator.comparingDouble((Map.Entry<MfpKey, Double> e) -> -e.getValue()))
                .map(entry -> SlotWidget.flow(entry.getKey(), entry.getValue() / divisor, timescale))
                .toList();
    }

    private List<SlotWidget> tabSlots(SolveResult solved) {
        // The plan-level tabs are never per machine: "the plan imports 4 ore a second" is a fact
        // about the plan, and dividing it by some line's machine count would mean nothing.
        return switch (selectedTab) {
            case 1 -> slotsFor(solved.byproducts(), 1.0);
            case 2 -> importSlots(solved.rawInputs());
            default -> slotsFor(solved.products(), 1.0);
        };
    }

    /**
     * The imports, each one clickable if the pack has any way of making it (M11.2).
     *
     * <p>An import is the plan saying "I gave up here", and until now the only way to answer one was
     * to start a second plan for it and read the two side by side. It is the same picker the recipe
     * column opens, on the imported key rather than on a line's product, and pinning there is what
     * adds the line — so a plan can be grown downwards one answer at a time. That is the whole of
     * hand-building (M11.3): with auto-resolve off, every input below the target arrives here.
     *
     * <p>Built here rather than through {@link #slotsFor} because the action needs the key, and that
     * method has already thrown it away by the time it has a slot.
     */
    private List<SlotWidget> importSlots(Map<MfpKey, Double> imports) {
        return answerableSlots(imports, 1.0, key -> "click to choose a recipe for this");
    }

    /**
     * A line's own ingredients, each one clickable — the gesture the whole hand-built mode is for.
     *
     * <p>This is how Factory Planner builds a factory: you look at a line, see what it eats, and
     * click the ingredient to say where it comes from. The plan-level Imports tab answers the same
     * questions and answers them all in one place, which is the better view once a plan is large;
     * this is the better one while it is being built, because <b>the question is asked where it
     * arises</b> — under the recipe that raised it, next to the rate it needs.
     *
     * <p>Every ingredient the pack can make is offered, not only the imported ones. An ingredient
     * another line already supplies opens the picker on that line's recipe, which is how the chain is
     * changed rather than extended; the hint says which of the two is about to happen, because
     * clicking and getting the wrong one of those is the sort of surprise that stops people clicking.
     */
    private List<SlotWidget> ingredientSlots(Map<MfpKey, Double> flows, double divisor,
                                             SolveResult solved) {
        return answerableSlots(flows, divisor, key -> solved.rawInputs().containsKey(key)
                ? "click to choose a recipe for this"
                : "made by another line - click to change how");
    }

    /**
     * Flows, each clickable when the pack has some way of making it.
     *
     * <p>An item nothing produces is deliberately left inert: the picker would open on an empty list,
     * which reads as a broken screen rather than as an answer. Raw ore and water are the common
     * cases, and both are things the plan is right to be importing.
     */
    private List<SlotWidget> answerableSlots(Map<MfpKey, Double> flows, double divisor,
                                             java.util.function.Function<MfpKey, String> hint) {
        List<SlotWidget> slots = new ArrayList<>();
        flows.entrySet().stream()
                .filter(entry -> entry.getKey().kind() != MfpKey.Kind.ENERGY)
                .sorted(Comparator.comparingDouble((Map.Entry<MfpKey, Double> e) -> -e.getValue()))
                .forEach(entry -> {
                    MfpKey key = entry.getKey();
                    SlotWidget slot = SlotWidget.flow(key, entry.getValue() / divisor, timescale);
                    if (!ClientIndex.get().producing(key).isEmpty()) {
                        slot.onClick(() -> Minecraft.getInstance().setScreen(
                                new RecipePickerScreen(this, key)), hint.apply(key));
                    }
                    slots.add(slot);
                });
        return slots;
    }

    private String emptyMessage() {
        return switch (selectedTab) {
            case 1 -> "nothing surplus";
            case 2 -> "nothing imported";
            default -> "nothing produced";
        };
    }

    private static int countReal(Map<MfpKey, Double> flows) {
        return (int) flows.keySet().stream()
                .filter(key -> key.kind() != MfpKey.Kind.ENERGY)
                .count();
    }

    // ----------------------------------------------------------------- render

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        int panelX = MARGIN;
        int panelY = MARGIN;
        int panelWidth = width - 2 * MARGIN;
        int panelHeight = height - 2 * MARGIN;

        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, Theme.PANEL);
        MfpWidget.outline(graphics, panelX, panelY, panelWidth, panelHeight, Theme.BORDER);

        graphics.fill(panelX + 1, panelY + 1, panelX + panelWidth - 1, panelY + TITLE_HEIGHT, 0x40000000);
        graphics.drawString(font, "Minecraft Factory Planner", panelX + 6, panelY + 4,
                Theme.TEXT_HEADER, false);

        renderNotice(graphics, panelX, panelY, panelWidth);

        renderPlanListChrome(graphics, panelX + 4, planListY,
                panelY + panelHeight - STATUS_HEIGHT - 4);

        if (plan == null) {
            renderEmptyState(graphics, panelX, panelY, panelWidth);
            for (MfpWidget widget : widgets) {
                widget.render(graphics, mouseX, mouseY, partialTick);
            }
            for (MfpWidget widget : widgets) {
                tooltip.offer(widget.tooltip(mouseX, mouseY), mouseX, mouseY);
            }
            super.render(graphics, mouseX, mouseY, partialTick);
            tooltip.renderAndClear(graphics);
            return;
        }

        graphics.drawString(font, MfpWidget.fit(plan.plan().name(), panelWidth - 240),
                panelX + 6 + font.width("Minecraft Factory Planner  "), panelY + 4, Theme.TEXT, false);

        graphics.drawString(font, "Targets", tableX, targetsY, Theme.TEXT_HEADER, false);
        renderPlanStats(graphics, panelX + 4, panelY + panelHeight - STATUS_HEIGHT - 4 - STATS_HEIGHT);
        renderWarnings(graphics);

        // Driven from the frame rather than from mouse events, because a drag held still at the edge
        // of the viewport produces no events at all and must still scroll.
        if (dragging) {
            autoScroll();
            updateDrop();
        }
        if (table != null) {
            table.drag(dragging ? grabbedRow : -1, dragging ? dropIndex : -1);
        }

        for (MfpWidget widget : widgets) {
            widget.render(graphics, mouseX, mouseY, partialTick);
        }
        table.renderHeader(graphics, tableX, headerY, tableWidth);
        renderStatusBar(graphics, panelX, panelY + panelHeight - STATUS_HEIGHT - 2, panelWidth);

        for (MfpWidget widget : widgets) {
            tooltip.offer(widget.tooltip(mouseX, mouseY), mouseX, mouseY);
        }
        tooltip.offer(table.headerTooltip(tableX, headerY, tableWidth, mouseX, mouseY), mouseX, mouseY);
        tooltip.offer(warningTooltip(mouseX, mouseY), mouseX, mouseY);

        super.render(graphics, mouseX, mouseY, partialTick);
        tooltip.renderAndClear(graphics);
    }

    /**
     * The export/import message, right-aligned in the title bar.
     *
     * <p>Drawn before the empty-state branch, because importing into a planner with no plans in it
     * is exactly the case where the message matters most — there is nothing else on the screen to
     * tell the user whether anything happened.
     */
    private void renderNotice(GuiGraphics graphics, int panelX, int panelY, int panelWidth) {
        if (notice == null) {
            return;
        }
        if (Util.getMillis() > noticeUntil) {
            notice = null;
            return;
        }
        // Half the bar at most: the other half is the plan's own name, and a message that overwrote
        // it would take away the thing the user needs to check the message against.
        String drawn = MfpWidget.fit(notice, panelWidth / 2 - 12);
        graphics.drawString(font, drawn, panelX + panelWidth - 6 - font.width(drawn), panelY + 4,
                noticeIsError ? Theme.ERROR : Theme.TEXT_HEADER, false);
    }

    private void renderEmptyState(GuiGraphics graphics, int panelX, int panelY, int panelWidth) {
        int centreY = panelY + 50;
        int centreX = panelX + panelWidth / 2 + LEFT_WIDTH / 2;
        drawCentred(graphics, "No plan yet.", centreX, centreY, Theme.TEXT);
        drawCentred(graphics, "Press New on the left and choose something to make,",
                centreX, centreY + 14, Theme.TEXT_DIM);
        drawCentred(graphics, "or run  /mfpplan <rate> <item>  in chat:", centreX, centreY + 26,
                Theme.TEXT_DIM);
        drawCentred(graphics, "/mfpplan 1 gtceu:steel_ingot", centreX, centreY + 40, Theme.TEXT_HEADER);
    }

    private void drawCentred(GuiGraphics graphics, String text, int centreX, int y, int colour) {
        graphics.drawString(font, text, centreX - font.width(text) / 2, y, colour, false);
    }

    private void renderPlanListChrome(GuiGraphics graphics, int listX, int listY, int bottomY) {
        graphics.fill(listX, listY, listX + LEFT_WIDTH, bottomY, Theme.PANEL_INNER);
        MfpWidget.outline(graphics, listX, listY, LEFT_WIDTH, bottomY - listY, Theme.BORDER);
        graphics.drawString(font, "Plans", listX + 5, listY + 4, Theme.TEXT_HEADER, false);

        // The selected plan is marked behind its button rather than by relabelling it, so the name
        // stays the whole width and is not shortened by a marker.
        int selected = ClientPlanner.currentIndex();
        if (selected >= 0 && selected < planRowY.size()) {
            graphics.fill(listX + 1, planRowY.get(selected) - 1, listX + LEFT_WIDTH - 1,
                    planRowY.get(selected) + 14, Theme.ROW_SELECTED);
        }
    }

    private void renderPlanStats(GuiGraphics graphics, int listX, int y) {
        SolveResult solved = plan.solveResult();
        y = planStat(graphics, listX, y, "lines", String.valueOf(solved.lines().size()));
        y = planStat(graphics, listX, y, "machines", String.valueOf(solved.totalMachines()));
        y = planStat(graphics, listX, y, "engine", solved.engine().name().toLowerCase(java.util.Locale.ROOT));
        y = planStat(graphics, listX, y, "confidence",
                solved.confidence().name().toLowerCase(java.util.Locale.ROOT));
        y = planStat(graphics, listX, y, "chose in", ClientPlan.millis(plan.chooseMicros()));
        planStat(graphics, listX, y, "solved in", ClientPlan.millis(plan.solveMicros()));
    }

    private int planStat(GuiGraphics graphics, int listX, int y, String label, String value) {
        graphics.drawString(font, label, listX + 6, y, Theme.TEXT_DIM, false);
        String fitted = MfpWidget.fit(value, LEFT_WIDTH - 14 - font.width(label));
        graphics.drawString(font, fitted, listX + LEFT_WIDTH - 6 - font.width(fitted), y, Theme.TEXT, false);
        return y + 11;
    }

    private void renderWarnings(GuiGraphics graphics) {
        if (warningStrip.isEmpty()) {
            return;
        }
        int stripX = tableX;
        int stripWidth = width - MARGIN - 4 - stripX;
        graphics.fill(stripX, warningStripY, stripX + stripWidth, warningStripY + 10, 0x30FF3030);
        String text = warningStrip.size() == 1
                ? warningStrip.get(0)
                : warningStrip.size() + " warnings: " + warningStrip.get(0);
        graphics.drawString(font, MfpWidget.fit(text, stripWidth - 6), stripX + 3, warningStripY + 1,
                Theme.WARNING, false);
    }

    private List<Component> warningTooltip(int mouseX, int mouseY) {
        if (warningStrip.isEmpty() || mouseY < warningStripY || mouseY >= warningStripY + 10
                || mouseX < tableX) {
            return List.of();
        }
        return warningStrip.stream()
                .map(warning -> (Component) Component.literal("- " + warning).withStyle(ChatFormatting.YELLOW))
                .toList();
    }

    private void renderStatusBar(GuiGraphics graphics, int panelX, int barY, int panelWidth) {
        SolveResult solved = plan.solveResult();
        // A gross draw, never a balance. A player already has a power setup; what they need from a
        // planner is what this factory will add to it (plan §13.4).
        String left = solved.lines().size() + " lines   "
                + solved.totalMachines() + " machines   "
                + "draws " + Fmt.eut(solved.euDrawPerSecond()) + " EU/t"
                // Steam is a second utility, not part of the draw: converting it into EU would tell
                // a player they need power for machines that take a steam pipe instead.
                + (solved.drawsSteam()
                        ? " + " + Fmt.steam(solved.steamDrawPerSecond()) + " mB/t steam"
                        : "")
                + (solved.generatesPower()
                        ? "   (+" + Fmt.eut(solved.euGeneratedPerSecond()) + " EU/t generated, excluded)"
                        : "");
        graphics.drawString(font, left, panelX + 6, barY + 1, Theme.TEXT, false);

        String right = solved.engine().name().toLowerCase(java.util.Locale.ROOT) + " engine, "
                + solved.confidence().name().toLowerCase(java.util.Locale.ROOT)
                + (solved.isComplete() ? "" : "  -  targets not met");
        graphics.drawString(font, right, panelX + panelWidth - 6 - font.width(right), barY + 1,
                solved.isComplete() ? Theme.TEXT_DIM : Theme.ERROR, false);
    }

    private List<String> allWarnings() {
        // Phrased for the engine that actually produced these numbers: a loop the matrix engine
        // closed is not something to warn about, and telling the user to use the solver they are
        // already using devalues every other warning on the strip.
        List<String> warnings = new ArrayList<>(plan.chooserResult()
                .warnings(plan.solveResult().engine().closesLoops()));
        warnings.addAll(plan.solveResult().warnings());
        return warnings;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // A snapshot: a widget's action re-solves and rebuilds this list underneath the loop.
        for (MfpWidget widget : List.copyOf(widgets)) {
            if (widget.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (grabbedRow >= 0) {
            this.dragMouseY = mouseY;
            if (!dragging && Math.abs(mouseY - grabbedAtY) >= DRAG_THRESHOLD) {
                dragging = true;
            }
            if (dragging) {
                updateDrop();
                return true;
            }
        }
        if (scroll != null) {
            scroll.mouseDragged(mouseY);
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (scroll != null) {
            scroll.mouseReleased();
        }
        if (grabbedRow >= 0) {
            // Read and cleared before acting: the action rebuilds the screen, and leaving a live
            // gesture pointing at rows that no longer exist is how a drag survives its own drop.
            int row = grabbedRow;
            int arrow = grabbedArrow;
            boolean wasDragging = dragging;
            int drop = dropIndex;
            this.grabbedRow = -1;
            this.dragging = false;
            this.dropIndex = -1;

            if (wasDragging) {
                if (drop >= 0) {
                    dropAt(row, drop);
                }
            } else if (arrow != 0) {
                // A press that never moved is a click on an arrow, resolved here rather than on the
                // press so the first pixel of a drag cannot also perform a move.
                reorderTo(DisplayOrder.moved(displayedOrder, row, arrow));
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        for (MfpWidget widget : List.copyOf(widgets)) {
            if (widget.mouseScrolled(mouseX, mouseY, delta)) {
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        for (MfpWidget widget : List.copyOf(widgets)) {
            if (widget.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        for (MfpWidget widget : List.copyOf(widgets)) {
            if (widget.charTyped(codePoint, modifiers)) {
                return true;
            }
        }
        return super.charTyped(codePoint, modifiers);
    }

    /** The world keeps running: a planner is a reference you consult, not a menu you are stuck in. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static double parseDouble(String text, double fallback) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String pathOf(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    private static String tierName(int tier) {
        return tier < 0 ? "-" : GtTiers.name(tier);
    }

    /**
     * The tier column, which is a voltage only for the machines that take a voltage.
     *
     * <p>A multiblock's tier is its energy hatch, and plenty of them have no energy hatch to fit: a
     * steam multi takes a steam hatch instead, and a large stone barrel, a wooden barrel or a farm
     * takes nothing at all. Printing "LV" against those invites the user to click and change
     * something that does not exist, and invites them to believe an overclock is available. Both are
     * read straight off the solved line rather than from a list of machine names — a line that burns
     * steam says so, and a line that draws no energy at all had no voltage to report in the first
     * place.
     */
    private static Table.Cell tierCell(LineResult result, MfpRecipe recipe, MachineConfig config) {
        if (result.isSteamPowered()) {
            return Cells.text("Steam", Theme.TEXT_DIM,
                    List.of(Component.literal("Powered by a steam hatch, not an energy hatch, so it "
                            + "has no voltage tier and never overclocks.")));
        }
        if (recipe.euIn() <= 0 && recipe.euOut() <= 0) {
            return Cells.text("-", Theme.TEXT_IDLE,
                    List.of(Component.literal("This machine takes no energy at all, so there is no "
                            + "hatch to choose and no tier to set.")));
        }
        return Cells.text(tierName(config.tier()), Theme.TEXT_DIM);
    }

    /**
     * The machine's own item, or the recipe type's representative when no machine is configured.
     *
     * <p>A line with no machine still runs somewhere, and showing the type's usual machine greyed
     * beside "(none)" is more use than an empty box — the number is still the recipe's own, which
     * the text says.
     */
    private ItemStack machineIcon(MachineConfig config, MfpRecipe recipe) {
        return config.machineId() == null
                ? MachineStacks.iconForRecipeType(recipe.recipeTypeId())
                : MachineStacks.icon(config.machineId());
    }

    private String machineName(MachineConfig config) {
        if (config.machineId() == null) {
            return "(none)";
        }
        // Through MachineStacks so a KubeJS-registered machine gets the name the game shows on the
        // block, rather than the internal name its definition carries.
        String name = MachineStacks.name(config.machineId());
        return name.equals(config.machineId()) ? pathOf(config.machineId()) : name;
    }
}
