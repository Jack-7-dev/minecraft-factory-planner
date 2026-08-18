package dev.mfp.client.screen;

import dev.mfp.client.ClientIndex;
import dev.mfp.client.ClientPlanner;
import dev.mfp.client.KeyStacks;
import dev.mfp.client.widget.Cells;
import dev.mfp.client.widget.MfpWidget;
import dev.mfp.client.widget.ScrollPanel;
import dev.mfp.client.widget.SlotWidget;
import dev.mfp.client.widget.Table;
import dev.mfp.client.widget.TextButton;
import dev.mfp.client.widget.Theme;
import dev.mfp.core.behaviour.GtTiers;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.plan.Preferences;
import dev.mfp.core.plan.RawMaterials;
import dev.mfp.behaviour.RawMaterialConfig;
import dev.mfp.plan.PreferenceStore;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Map;

/**
 * The player's standing defaults: how they make things, what they build with, what they have none of.
 *
 * <p>Everything here applies to <em>every</em> plan, which is the whole point — the largest gap
 * between MFP and how a pack is actually played was that the player already has factories and MFP
 * planned as though they did not. It is also why every list on this screen is short and every entry
 * removable: a standing preference the user cannot find again is worse than no preference at all.
 *
 * <p>Nothing here overrules a plan. A pin, a chosen machine, a plan's own tier or its allowed items
 * all win where they disagree, so opening this screen can never invalidate a decision made elsewhere
 * — it changes what happens where no decision was made.
 */
public final class DefaultsScreen extends ModalScreen {

    private static final List<Table.Column> RECIPE_COLUMNS = List.of(
            new Table.Column("Item", 1.8f),
            new Table.Column("Made by", 3.0f),
            new Table.Column("", 1.0f));

    private static final List<Table.Column> ITEM_COLUMNS = List.of(
            new Table.Column("Item", 3.0f),
            new Table.Column("", 1.0f));

    private final Preferences preferences = PreferenceStore.get();

    private Table recipes;
    private Table blocked;
    private Table preferred;
    private Table raw;
    private int recipesY;
    private int blockedY;

    public DefaultsScreen(Screen parent) {
        super(parent, "Defaults", "applied to every plan");
    }

    @Override
    protected int preferredWidth() {
        // Three lists across the bottom rather than two (M9.4). Raw materials are a standing
        // statement about the save - "I have a cobble generator" - so they belong beside the other
        // two rather than only inside one plan, and three columns need the width.
        return 560;
    }

    @Override
    protected int preferredHeight() {
        return 340;
    }

    @Override
    protected void build() {
        int cursorY = contentY();

        // Tier -----------------------------------------------------------------
        TextButton tier = new TextButton(tierLabel(), () -> {
            // Cycles rather than typing a number: there are fifteen tiers, they have names, and a
            // free-text field would invite "HV" to be typed where an integer was wanted.
            int next = preferences.defaultTier() == Preferences.NO_DEFAULT_TIER
                    ? 0 : preferences.defaultTier() + 1;
            preferences.defaultTier(next > GtTiers.MAX ? Preferences.NO_DEFAULT_TIER : next);
            saveAndResolve();
        });
        tier.secondary(() -> {
            int previous = preferences.defaultTier() == Preferences.NO_DEFAULT_TIER
                    ? GtTiers.MAX : preferences.defaultTier() - 1;
            preferences.defaultTier(previous < 0 ? Preferences.NO_DEFAULT_TIER : previous);
            saveAndResolve();
        });
        tier.tooltip("The tier you build at. Every default machine becomes that member of its family "
                + "- a recipe that cannot run there still gets the lowest machine that can, and a "
                + "machine you chose yourself is never moved. Right-click to go back.");
        tier.bounds(contentX() + 110, cursorY, Math.max(90, tier.preferredWidth()), 14);
        widgets.add(tier);

        // Beside the tier rather than on its own row, because it is the same kind of statement — how
        // every new plan starts — and because the label column here is drawn at fixed offsets.
        TextButton expansion = new TextButton(
                "Expansion: " + (preferences.autoResolve() ? "automatic" : "by hand"), () -> {
            preferences.autoResolve(!preferences.autoResolve());
            saveAndResolve();
        });
        expansion.tooltip("How a new plan starts. By hand follows the default recipes below as far "
                + "as they go and leaves every ingredient past them as a question you answer by "
                + "clicking it; automatic picks the whole chain for you. By hand is the default: "
                + "automatic selection is good on "
                + "material chains and unreliable above them, and a plan it got wrong is one you "
                + "have to unpick. Either way each plan can be switched in its own Settings.");
        expansion.bounds(tier.x() + tier.width() + GAP, cursorY,
                Math.max(120, expansion.preferredWidth()), 14);
        widgets.add(expansion);

        // The fine-grained half of the tier button beside it, because they answer the same question
        // at different sizes: "I build at HV" and "my blast furnace has HSS-G coils in it".
        int builds = preferences.machineDefaults().size();
        TextButton machines = new TextButton(
                builds == 0 ? "Machines" : "Machines (" + builds + ")",
                () -> Minecraft.getInstance().setScreen(new MachineDefaultsScreen(this)));
        machines.tooltip("Describe how your own machines are built - the coils in the blast furnace, "
                + "the parallel hatch, the rotor, the hatch tier. That is what decides a "
                + "multiblock's rate, and until you say, MFP reports the line as a guess.");
        machines.bounds(expansion.x() + expansion.width() + GAP, cursorY,
                Math.max(80, machines.preferredWidth()), 14);
        widgets.add(machines);
        cursorY += 14 + GAP + 4;

        // Default recipes ------------------------------------------------------
        TextButton adopt = new TextButton("Adopt this plan", () -> {
            var current = ClientPlanner.current();
            if (current != null) {
                preferences.adoptFrom(current.plan());
                saveAndResolve();
            }
        });
        adopt.enabled(ClientPlanner.current() != null);
        adopt.tooltip("Take the open plan's recipes for what it makes, and every recipe pinned in it, "
                + "as the way you make those things from now on. Only its targets and its pins - the "
                + "intermediates it happened to choose are left to the scorer.");
        adopt.bounds(contentX() + 110, cursorY, adopt.preferredWidth(), 14);
        widgets.add(adopt);
        cursorY += 14 + GAP;

        Table recipeTable = new Table(RECIPE_COLUMNS);
        for (Map.Entry<MfpKey, String> entry : preferences.defaultRecipes().entrySet()) {
            MfpKey key = entry.getKey();
            String recipeId = entry.getValue();
            boolean known = ClientIndex.get() != null && ClientIndex.get().recipe(recipeId) != null;
            recipeTable.addRow(List.of(
                            Cells.icon(SlotWidget.of(key, KeyStacks.name(key).getString(),
                                    KeyStacks.tooltip(key, null))),
                            // A recipe this pack no longer has is shown rather than dropped: the
                            // chooser already falls back to the scorer for it, and silently deleting
                            // someone's decision because a pack update renamed a recipe is how a tool
                            // stops being trusted.
                            Cells.text(recipeId, known ? Theme.TEXT_DIM : Theme.TEXT_IDLE,
                                    known ? List.of()
                                            : List.of(Component.literal(
                                                    "not in this pack - the scorer chooses instead")
                                                    .withStyle(ChatFormatting.YELLOW))),
                            Cells.button("Forget", Theme.TEXT, List.of(), () -> {
                                preferences.clearDefaultRecipe(key);
                                saveAndResolve();
                            })),
                    0, null);
        }
        this.recipes = recipeTable;

        int remaining = Math.max(60, contentBottom() - cursorY - GAP);
        int listHeight = Math.max(24, remaining / 2 - 14 - GAP);

        ScrollPanel recipeScroll = new ScrollPanel();
        recipeScroll.bounds(contentX(), cursorY, contentWidth(), listHeight);
        recipeTable.layout(recipeScroll.viewportWidth());
        recipeScroll.content(recipeTable, recipeTable.contentHeight());
        widgets.add(recipeScroll);
        this.recipesY = cursorY;
        cursorY += listHeight + GAP + 4;

        // Blacklisted and preferred items, side by side ------------------------
        // Two lists rather than one with a label per row: they are opposite statements — "never use
        // this" and "use this where there is a choice" — and reading which is which off a suffix
        // beside every item was both noisier and narrower than giving each its own column.
        int columnWidth = (contentWidth() - 2 * GAP) / 3;
        int rightX = contentX() + columnWidth + GAP;
        int thirdX = rightX + columnWidth + GAP;

        TextButton block = new TextButton("Add to blacklist", () -> Minecraft.getInstance().setScreen(
                new ItemPickerScreen(this, "Blacklist", key -> {
                    preferences.blockItem(key);
                    PreferenceStore.save();
                    ClientPlanner.refresh();
                })));
        block.tooltip("\"I have no supply of this\". Every recipe that consumes it becomes unusable "
                + "everywhere, so a chain through it is never planned - and where that leaves no "
                + "route at all, the plan says so on the import rather than quietly finding a worse "
                + "one.");
        block.bounds(contentX(), cursorY, block.preferredWidth(), 14);
        widgets.add(block);

        TextButton prefer = new TextButton("Prefer an item", () -> Minecraft.getInstance().setScreen(
                new ItemPickerScreen(this, "Prefer", key -> {
                    preferences.preferItem(key);
                    PreferenceStore.save();
                    ClientPlanner.refresh();
                })));
        prefer.tooltip("Where a recipe accepts several items - any log, any dye - use this one. The "
                + "plan's own choice still wins.");
        prefer.bounds(rightX, cursorY, prefer.preferredWidth(), 14);
        widgets.add(prefer);

        TextButton rawAdd = new TextButton("Add raw material", () -> Minecraft.getInstance().setScreen(
                new ItemPickerScreen(this, "Raw material", key -> {
                    declare(key, true);
                })));
        rawAdd.tooltip("\"I have this without limit\". Expansion stops here instead of planning how "
                + "to make it - a cobble generator makes cobblestone raw, a void miner makes most "
                + "ores raw. Water is on the list from the first minute and cannot be taken off it "
                + "here; a plan that is about making water takes it off in that plan's settings.");
        rawAdd.bounds(thirdX, cursorY, rawAdd.preferredWidth(), 14);
        widgets.add(rawAdd);
        cursorY += 14 + GAP;

        int itemListHeight = Math.max(20, contentBottom() - cursorY - GAP);

        Table blockedTable = new Table(ITEM_COLUMNS);
        for (MfpKey key : preferences.blockedItems()) {
            blockedTable.addRow(List.of(
                            Cells.iconText(KeyStacks.icon(key), KeyStacks.name(key).getString(),
                                    Theme.TEXT_IDLE, KeyStacks.tooltip(key, null)),
                            Cells.button("Remove", Theme.TEXT, List.of(), () -> {
                                preferences.unblockItem(key);
                                saveAndResolve();
                            })),
                    0, null);
        }
        this.blocked = blockedTable;

        ScrollPanel blockedScroll = new ScrollPanel();
        blockedScroll.bounds(contentX(), cursorY, columnWidth, itemListHeight);
        blockedTable.layout(blockedScroll.viewportWidth());
        blockedScroll.content(blockedTable, blockedTable.contentHeight());
        widgets.add(blockedScroll);
        this.blockedY = cursorY;

        Table preferredTable = new Table(ITEM_COLUMNS);
        for (MfpKey key : preferences.preferredItems()) {
            preferredTable.addRow(List.of(
                            Cells.iconText(KeyStacks.icon(key), KeyStacks.name(key).getString(),
                                    Theme.TEXT_DIM, KeyStacks.tooltip(key, null)),
                            Cells.button("Remove", Theme.TEXT, List.of(), () -> {
                                preferences.clearPreferredItem(key);
                                saveAndResolve();
                            })),
                    0, null);
        }
        this.preferred = preferredTable;

        ScrollPanel preferredScroll = new ScrollPanel();
        preferredScroll.bounds(rightX, cursorY, columnWidth, itemListHeight);
        preferredTable.layout(preferredScroll.viewportWidth());
        preferredScroll.content(preferredTable, preferredTable.contentHeight());
        widgets.add(preferredScroll);

        // The standing raw materials. What MFP ships is shown too, greyed and without a Remove
        // button: the list is what expansion actually stops at, and a screen that showed only half
        // of it would leave the user wondering where water went.
        Table rawTable = new Table(ITEM_COLUMNS);
        for (MfpKey key : RawMaterials.defaults()) {
            boolean shipped = RawMaterials.isShipped(key);
            rawTable.addRow(List.of(
                            Cells.iconText(KeyStacks.icon(key), KeyStacks.name(key).getString(),
                                    shipped ? Theme.TEXT_IDLE : Theme.TEXT_DIM,
                                    shipped
                                            ? List.of(Component.literal(
                                                    "free in every save - remove it in one plan's "
                                                            + "settings if that plan is about making it")
                                                    .withStyle(ChatFormatting.DARK_GRAY))
                                            : KeyStacks.tooltip(key, null)),
                            shipped
                                    ? Cells.text("shipped", Theme.TEXT_IDLE, List.of())
                                    : Cells.button("Remove", Theme.TEXT, List.of(),
                                            () -> declare(key, false))),
                    0, null);
        }
        this.raw = rawTable;

        ScrollPanel rawScroll = new ScrollPanel();
        rawScroll.bounds(thirdX, cursorY, columnWidth, itemListHeight);
        rawTable.layout(rawScroll.viewportWidth());
        rawScroll.content(rawTable, rawTable.contentHeight());
        widgets.add(rawScroll);
    }

    /**
     * Add or remove one of the player's own raw materials, and write the file.
     *
     * <p>Through {@code RawMaterialConfig} rather than {@code RawMaterials.install} directly,
     * because installing alone is what made this the one screen whose decisions did not survive the
     * session (M9.4).
     */
    private void declare(MfpKey key, boolean add) {
        java.util.List<MfpKey> declared = new java.util.ArrayList<>(RawMaterialConfig.declared());
        declared.remove(key);
        if (add) {
            declared.add(key);
        }
        RawMaterialConfig.save(declared);
        ClientPlanner.refresh();
        rebuild();
    }

    /**
     * Write the file and re-solve, in that order.
     *
     * <p>Immediately rather than on closing the world, because these are few and small and a
     * preference lost to a crash is a decision the user has to remember making. Re-solving is what
     * makes the screen honest: a default that did not change the plan behind the dialog would leave
     * the user unsure whether it had taken.
     */
    private void saveAndResolve() {
        PreferenceStore.save();
        ClientPlanner.refresh();
        rebuild();
    }

    private String tierLabel() {
        int tier = preferences.defaultTier();
        if (tier == Preferences.NO_DEFAULT_TIER) {
            return "lowest that runs it";
        }
        // Named, not numbered: the machines call themselves LV, MV and HV, and a tier index is a
        // number the reader would have to translate every time they looked at it.
        return GtTiers.name(tier);
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.drawString(font, "Build at tier", contentX(), contentY() + 3, Theme.TEXT_DIM, false);
        graphics.drawString(font, "Recipes", contentX(), contentY() + 14 + GAP + 4 + 3,
                Theme.TEXT_DIM, false);

        if (recipes != null && recipes.rowCount() == 0) {
            graphics.drawString(font, MfpWidget.fit(
                            "no default recipes - every plan asks the scorer", contentWidth()),
                    contentX(), recipesY + 4, Theme.TEXT_IDLE, false);
        }
        int columnWidth = (contentWidth() - 2 * GAP) / 3;
        if (blocked != null && blocked.rowCount() == 0) {
            graphics.drawString(font, MfpWidget.fit("nothing blacklisted", columnWidth),
                    contentX(), blockedY + 4, Theme.TEXT_IDLE, false);
        }
        if (preferred != null && preferred.rowCount() == 0) {
            graphics.drawString(font, MfpWidget.fit("no preferred items", columnWidth),
                    contentX() + columnWidth + GAP, blockedY + 4, Theme.TEXT_IDLE, false);
        }
        if (raw != null && raw.rowCount() == 0) {
            graphics.drawString(font, MfpWidget.fit("nothing is free", columnWidth),
                    contentX() + 2 * (columnWidth + GAP), blockedY + 4, Theme.TEXT_IDLE, false);
        }
    }
}
