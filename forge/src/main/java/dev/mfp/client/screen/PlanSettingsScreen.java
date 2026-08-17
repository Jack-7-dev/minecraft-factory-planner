package dev.mfp.client.screen;

import dev.mfp.client.ClientPlanner;
import dev.mfp.client.KeyStacks;
import dev.mfp.client.widget.Cells;
import dev.mfp.client.widget.MfpWidget;
import dev.mfp.client.widget.ScrollPanel;
import dev.mfp.client.widget.SlotWidget;
import dev.mfp.client.widget.Table;
import dev.mfp.client.widget.TextButton;
import dev.mfp.client.widget.TextField;
import dev.mfp.client.widget.Theme;
import dev.mfp.core.behaviour.GtTiers;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.plan.Preferences;
import dev.mfp.core.plan.SolverMode;
import dev.mfp.plan.PreferenceStore;
import dev.mfp.core.solver.SolveResult;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The plan's own settings: its name, which engine solves it, and the items it may buy in.
 *
 * <p>Two lists, and they are not the same list. A <b>free item</b> is one the matrix engine may let
 * float rather than forcing to net to zero — a solver relief valve. A <b>raw material</b> is one
 * expansion refuses to plan at all, because the player already has it without limit. Water is raw
 * from the start; cobblestone becomes raw the day its generator is built, which is why that list is
 * the user's to write and not MFP's to guess.
 *
 * <p>Free items are the reason this dialog exists. An over-constrained plan — one the matrix engine
 * cannot balance because some intermediate is required to net to exactly zero — is relieved by
 * letting that item be imported (STATUS §5b.2), and until now that could only be done from code.
 * The failure's own diagnosis names the candidates, so they are offered here as buttons rather than
 * left for the user to guess at: an error message that names a fix nobody can apply is not a fix.
 *
 * <p>Energy is not among them. It is promoted automatically and exactly once, because a GregTech
 * chain picks up a steam turbine incidentally all the time and would otherwise be required to be
 * precisely self-powered (STATUS §5b.5). Every other promotion is a guess about someone's factory,
 * which is why it is a button and not a rule.
 */
public final class PlanSettingsScreen extends ModalScreen {

    private static final List<Table.Column> FREE_COLUMNS = List.of(
            new Table.Column("Item", 2.4f),
            new Table.Column("", 1.0f));

    private final Plan plan;
    private TextField name;
    private Table freeItems;
    private Table rawMaterials;
    private Table planItems;
    private Table allowedItems;
    private int freeListY;
    private int rawListY;
    private int planItemsY;
    private int allowedY;

    public PlanSettingsScreen(Screen parent) {
        super(parent, "Plan settings", null);
        this.plan = ClientPlanner.current() == null ? new Plan("scratch") : ClientPlanner.current().plan();
    }

    @Override
    protected int preferredWidth() {
        return 480;
    }

    @Override
    protected int preferredHeight() {
        return 320;
    }

    @Override
    protected void build() {
        int labelWidth = 96;
        int cursorY = contentY();

        if (name == null) {
            name = new TextField()
                    .text(plan.name())
                    .maxLength(60)
                    .placeholder("plan name")
                    .tooltip("What this plan is called in the plan list.")
                    .onCommit(value -> plan.name(value.isBlank() ? "Untitled" : value.trim()));
        }
        name.bounds(contentX() + labelWidth, cursorY, contentWidth() - labelWidth, 14);
        widgets.add(name);
        cursorY += 14 + GAP;

        TextButton engine = new TextButton(engineLabel(), () -> {
            plan.solverMode(nextMode(plan.solverMode()));
            ClientPlanner.refresh();
            rebuild();
        });
        engine.tooltip("AUTO lets the chooser decide: the matrix engine when it observes a loop, the "
                + "sequential one otherwise. Choosing explicitly sticks until you change it.");
        engine.bounds(contentX() + labelWidth, cursorY, engine.preferredWidth(), 14);
        widgets.add(engine);
        cursorY += 14 + GAP;

        // This plan's own build tier, above the standing one (M8). Here rather than in Defaults
        // because a plan is often written for a stage of the game other than the one the player is
        // at - the LV steel line that gets them to MV is the ordinary case.
        TextButton tier = new TextButton(tierLabel(), () -> {
            int next = plan.defaultTier() == Preferences.NO_DEFAULT_TIER ? 0 : plan.defaultTier() + 1;
            plan.defaultTier(next > 14 ? Preferences.NO_DEFAULT_TIER : next);
            ClientPlanner.refresh();
            rebuild();
        });
        tier.tooltip("The tier this plan builds at. Unset follows your standing default, and a "
                + "machine you chose yourself is never moved either way.");
        tier.bounds(contentX() + labelWidth, cursorY, Math.max(110, tier.preferredWidth()), 14);
        widgets.add(tier);
        cursorY += 14 + GAP;

        // M11.1. A real preference rather than a debug switch, which is why it is on this screen and
        // not behind a config file: wiring everything into everything makes a plan that is harder to
        // build on a factory floor than one with a couple of extra imports.
        TextButton feeds = new TextButton(
                "Byproducts: " + (plan.byproductFeeds() ? "feed the plan" : "left over"), () -> {
            plan.byproductFeeds(!plan.byproductFeeds());
            ClientPlanner.refresh();
            rebuild();
        });
        feeds.tooltip("When a line gives off something another recipe could use, prefer the recipe "
                + "that uses it. Off keeps the shape the chooser found first, which is usually a "
                + "simpler floor with a few more imports.");
        feeds.bounds(contentX() + labelWidth, cursorY, Math.max(110, feeds.preferredWidth()), 14);
        widgets.add(feeds);
        cursorY += 14 + GAP;

        // M11.3, and the reason it is a plan setting rather than a global one: which plans are worth
        // building by hand is a judgement about the plan. A material chain the chooser gets right is
        // not worth twenty clicks, and the chain above it usually is.
        TextButton resolve = new TextButton(
                "Expansion: " + (plan.autoResolve() ? "automatic" : "by hand"), () -> {
            plan.autoResolve(!plan.autoResolve());
            ClientPlanner.refresh();
            rebuild();
        });
        resolve.tooltip("Automatic picks a recipe for every input, all the way down. By hand "
                + "follows the recipes you have set as defaults and stops where they run out: "
                + "everything past them arrives on the Imports tab, and you answer them one at a "
                + "time by clicking. Your pinned recipes are what the plan is made of "
                + "either way, so switching back and forth loses nothing.");
        resolve.bounds(contentX() + labelWidth, cursorY, Math.max(110, resolve.preferredWidth()), 14);
        widgets.add(resolve);
        cursorY += 14 + GAP + 4;

        // Four lists in two rows of two. They were stacked, which made the dialog tall and left half
        // its width empty; nothing here needs more than half a dialog to show an item and a button,
        // and a list the user has to scroll the dialog to reach is a list they forget they set.
        int columnWidth = (contentWidth() - GAP) / 2;
        int rightX = contentX() + columnWidth + GAP;
        int rowHeight = Math.max(48, (contentBottom() - cursorY - GAP - 4) / 2);
        int listHeight = Math.max(20, rowHeight - 14 - GAP);

        // Row 1: what the solver may import freely, and what expansion refuses to plan ------------
        TextButton addFree = new TextButton("Add free item", () -> Minecraft.getInstance().setScreen(
                new ItemPickerScreen(this, "Free item", key -> {
                    plan.freeItem(key);
                    ClientPlanner.refresh();
                })));
        addFree.tooltip("Let this item be imported or exported freely instead of being forced to "
                + "balance at zero.");
        addFree.bounds(contentX(), cursorY, addFree.preferredWidth(), 14);
        widgets.add(addFree);

        for (MfpKey candidate : suggestedFreeItems()) {
            TextButton suggestion = new TextButton("+ " + shortName(candidate), () -> {
                plan.freeItem(candidate);
                ClientPlanner.refresh();
                rebuild();
            });
            suggestion.tooltip("The solver named " + candidate + " when it failed: freeing it is the "
                    + "documented relief for an over-constrained plan.");
            int suggestionX = addFree.x() + addFree.width() + GAP;
            if (suggestionX + suggestion.preferredWidth() > contentX() + columnWidth) {
                break;
            }
            suggestion.bounds(suggestionX, cursorY, suggestion.preferredWidth(), 14);
            widgets.add(suggestion);
            break;
        }

        TextButton addRaw = new TextButton("Add raw material", () -> Minecraft.getInstance().setScreen(
                new ItemPickerScreen(this, "Raw material", key -> {
                    plan.rawMaterial(key);
                    ClientPlanner.refresh();
                })));
        addRaw.tooltip("Stop planning how to make this, in this plan only: expansion treats it as "
                + "something you already have without limit. The list starts as your standing one "
                + "from Defaults, and what you add or remove here is this plan's exception to it - "
                + "so the plan about making water is the one that takes water off.");
        addRaw.bounds(rightX, cursorY, addRaw.preferredWidth(), 14);
        widgets.add(addRaw);
        cursorY += 14 + GAP;

        this.freeItems = list(plan.freeItems(), "Remove", key -> {
            plan.clearFreeItem(key);
            ClientPlanner.refresh();
            rebuild();
        });
        this.freeListY = cursorY;
        add(freeItems, contentX(), cursorY, columnWidth, listHeight);

        this.rawMaterials = list(plan.rawMaterials(), "Remove", key -> {
            plan.clearRawMaterial(key);
            ClientPlanner.refresh();
            rebuild();
        });
        this.rawListY = cursorY;
        add(rawMaterials, rightX, cursorY, columnWidth, listHeight);
        cursorY += listHeight + GAP + 4;

        // Row 2: this plan's own half of the blacklist, in both directions ------------------------
        // The picker's "Allow here" writes into the right-hand list, so this is where it can be
        // taken back: a one-way exception with no home is a decision the user cannot find again.
        TextButton addBlocked = new TextButton("Add to blacklist",
                () -> Minecraft.getInstance().setScreen(
                        new ItemPickerScreen(this, "Blacklist in this plan", key -> {
                            plan.blockItem(key);
                            ClientPlanner.refresh();
                        })));
        addBlocked.tooltip("Refuse to plan any chain through this item, in this plan only. "
                + "Blacklisting it for every plan is in Defaults.");
        addBlocked.bounds(contentX(), cursorY, addBlocked.preferredWidth(), 14);
        widgets.add(addBlocked);
        cursorY += 14 + GAP;

        this.planItems = list(plan.blockedItems(), "Remove", key -> {
            plan.unblockItem(key);
            ClientPlanner.refresh();
            rebuild();
        });
        this.planItemsY = cursorY;
        add(planItems, contentX(), cursorY, columnWidth, listHeight);

        this.allowedItems = list(plan.allowedItems(), "Remove", key -> {
            plan.clearAllowedItem(key);
            ClientPlanner.refresh();
            rebuild();
        });
        this.allowedY = cursorY;
        add(allowedItems, rightX, cursorY, columnWidth, listHeight);
    }

    /** One item per row with a button beside it, which is every list on this screen. */
    private Table list(Iterable<MfpKey> keys, String action, java.util.function.Consumer<MfpKey> onPress) {
        Table table = new Table(FREE_COLUMNS);
        for (MfpKey key : keys) {
            // No "blocked here" beside the name: the list it is in says that, and a label repeating
            // the heading costs the width the item's own name needs.
            table.addRow(List.of(
                            Cells.icon(SlotWidget.of(key, KeyStacks.name(key).getString(),
                                    KeyStacks.tooltip(key, null))),
                            Cells.button(action, Theme.TEXT, List.of(), () -> onPress.accept(key))),
                    0, null);
        }
        return table;
    }

    private void add(Table table, int x, int y, int width, int height) {
        ScrollPanel scroll = new ScrollPanel();
        scroll.bounds(x, y, width, height);
        table.layout(scroll.viewportWidth());
        scroll.content(table, table.contentHeight());
        widgets.add(scroll);
    }

    /**
     * The tier as the game names it — {@code HV}, not {@code 3}.
     *
     * <p>Nobody thinks in tier indices: the machines are called LV, MV and HV on their own tooltips,
     * and a number here would have to be translated by the reader every time.
     */
    private String tierLabel() {
        int tier = plan.defaultTier();
        if (tier == Preferences.NO_DEFAULT_TIER) {
            int standing = PreferenceStore.get().defaultTier();
            return standing == Preferences.NO_DEFAULT_TIER
                    ? "your default (unset)" : "your default (" + GtTiers.name(standing) + ")";
        }
        return GtTiers.name(tier);
    }

    private String engineLabel() {
        String mode = plan.solverMode().name().toLowerCase(java.util.Locale.ROOT);
        return plan.solverModeDerived() ? mode + " (worked out)" : mode;
    }

    private static SolverMode nextMode(SolverMode mode) {
        SolverMode[] modes = SolverMode.values();
        return modes[(mode.ordinal() + 1) % modes.length];
    }

    /** Items the last solve's own diagnosis named as candidates for freeing. */
    private List<MfpKey> suggestedFreeItems() {
        var solved = ClientPlanner.current();
        if (solved == null) {
            return List.of();
        }
        SolveResult result = solved.solveResult();
        return result.rawInputs().keySet().stream()
                .filter(key -> key.kind() != MfpKey.Kind.ENERGY)
                .filter(key -> !plan.freeItems().contains(key))
                .limit(1)
                .toList();
    }

    private static String shortName(MfpKey key) {
        String name = KeyStacks.name(key).getString();
        return name.length() > 18 ? name.substring(0, 17) + "..." : name;
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int cursorY = contentY();
        graphics.drawString(font, "Name", contentX(), cursorY + 3, Theme.TEXT_DIM, false);
        cursorY += 14 + GAP;
        graphics.drawString(font, "Solver", contentX(), cursorY + 3, Theme.TEXT_DIM, false);
        cursorY += 14 + GAP;
        graphics.drawString(font, "Build at tier", contentX(), cursorY + 3, Theme.TEXT_DIM, false);

        // Each list is half the dialog wide now, so its heading and its empty message are drawn at
        // its own column rather than across the whole content area.
        int columnWidth = (contentWidth() - GAP) / 2;
        int rightX = contentX() + columnWidth + GAP;

        // Three of the four lists are labelled by the button that adds to them; the fourth is only
        // ever written from the recipe picker's "Allow here", so it gets a plain heading in the same
        // place a button would have been.
        graphics.drawString(font, "Allowed anyway", rightX, allowedY - 14 - GAP + 3,
                Theme.TEXT_DIM, false);

        empty(graphics, freeItems, contentX(), freeListY, columnWidth,
                "no free items - every item must balance exactly");
        empty(graphics, rawMaterials, rightX, rawListY, columnWidth,
                "nothing is free - every input is planned back to its recipe");
        empty(graphics, planItems, contentX(), planItemsY, columnWidth,
                "nothing blacklisted in this plan");
        empty(graphics, allowedItems, rightX, allowedY, columnWidth,
                "nothing here overrides your blacklist");
    }

    /**
     * What a list says when it has nothing in it, drawn in that list's own column.
     *
     * <p>Which list a row belongs to is now visible from the column it is in, which is what let the
     * rows drop the "blocked here" label they used to carry — it repeated the heading and spent the
     * width the item's own name needs.
     */
    private void empty(GuiGraphics graphics, Table table, int x, int listY, int width, String message) {
        if (table != null && table.rowCount() == 0) {
            graphics.drawString(font, MfpWidget.fit(message, width), x, listY + 4, Theme.TEXT_IDLE, false);
        }
    }

    @Override
    protected void offerTooltips(int mouseX, int mouseY) {
        var solved = ClientPlanner.current();
        if (solved == null || solved.solveResult().warnings().isEmpty()) {
            return;
        }
        if (mouseY >= panelY + panelHeight - FOOTER_HEIGHT && mouseX >= contentX()) {
            offer(solved.solveResult().warnings().stream()
                    .map(warning -> (Component) Component.literal("- " + warning)
                            .withStyle(ChatFormatting.YELLOW))
                    .toList(), mouseX, mouseY);
        }
    }
}
