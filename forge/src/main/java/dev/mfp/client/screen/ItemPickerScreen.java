package dev.mfp.client.screen;

import dev.mfp.client.KeyCatalog;
import dev.mfp.client.widget.Cells;
import dev.mfp.client.widget.MfpWidget;
import dev.mfp.client.widget.ScrollPanel;
import dev.mfp.client.widget.SlotWidget;
import dev.mfp.client.widget.Table;
import dev.mfp.client.widget.TextButton;
import dev.mfp.client.widget.TextField;
import dev.mfp.client.widget.Theme;
import dev.mfp.core.model.MfpKey;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * Choose an item or fluid: what a plan's targets, raw materials and free items are picked from.
 *
 * <p>It offers only what the index can <em>produce</em> (see {@code KeyCatalog}). A picker listing
 * every registered item would let a user set a target the planner can only answer with "nothing
 * makes this", which is a dead end dressed up as a choice.
 *
 * <p>Fluids are listed beside items rather than behind a filter, because the distinction the user
 * cares about — water the fluid versus a water bucket the item — is exactly the one a shared list
 * with a "fluid" label makes visible. The id is shown under every name for the same reason: two mods
 * routinely register the same display name.
 */
public final class ItemPickerScreen extends ModalScreen {

    /** How many results are listed. Beyond this the answer is "search for it", not "scroll". */
    private static final int RESULT_LIMIT = 200;

    private static final List<Table.Column> COLUMNS = List.of(
            new Table.Column("Item", 1.4f),
            new Table.Column("Id", 1.6f, "Two mods routinely register the same display name."));

    private final Consumer<MfpKey> onChosen;

    private TextField search;
    private String query = "";
    private int matchCount;

    private int searchY;
    private java.util.function.Supplier<String> optionLabel;
    private Runnable onOption;
    private String optionTooltip;

    public ItemPickerScreen(Screen parent, String title, Consumer<MfpKey> onChosen) {
        super(parent, title, "search by name or id");
        this.onChosen = onChosen;
    }

    /**
     * A switch above the search box, for a choice that has to be made <em>before</em> the pick.
     *
     * <p>The new-plan flow is the case it exists for (M11.3). Choosing an item creates the plan and
     * expands it in the same breath, so "automatic or by hand" cannot be answered afterwards without
     * throwing the first expansion away — and on a large chain that first expansion is the slow part
     * and the part the user did not want. So it is answered here, on the screen where the plan is
     * being started, seeded from the standing default.
     *
     * @param label   read on every rebuild, so the button says what the switch is now
     * @param toggle  flips it; this screen rebuilds itself afterwards
     */
    public ItemPickerScreen withOption(java.util.function.Supplier<String> label, Runnable toggle,
                                       String tooltip) {
        this.optionLabel = label;
        this.onOption = toggle;
        this.optionTooltip = tooltip;
        return this;
    }

    @Override
    protected int preferredWidth() {
        return 300;
    }

    @Override
    protected int preferredHeight() {
        return 260;
    }

    @Override
    protected void build() {
        int headerY = contentY();
        if (optionLabel != null) {
            TextButton option = new TextButton(optionLabel.get(), () -> {
                onOption.run();
                rebuild();
            });
            option.tooltip(optionTooltip);
            option.bounds(contentX(), headerY, Math.max(140, option.preferredWidth()), 14);
            widgets.add(option);
            headerY += 14 + GAP;
        }
        this.searchY = headerY;

        if (search == null) {
            // Kept across rebuilds rather than recreated, because a rebuild happens on every
            // keystroke and a fresh field would put the caret back at the end of the text each
            // time — making it impossible to correct a typo in the middle of a word.
            search = new TextField()
                    .placeholder("search, e.g. steel ingot or gtceu:steel")
                    .text(query)
                    .onChange(value -> {
                        query = value;
                        // Re-listed per keystroke: the catalog is prebuilt and this is a scan of a
                        // few thousand strings, which is what makes a search box feel like one.
                        rebuild();
                    });
            search.focus();
        }
        search.bounds(contentX(), searchY, contentWidth(), 14);
        widgets.add(search);

        List<KeyCatalog.Entry> results = KeyCatalog.search(query, RESULT_LIMIT);
        matchCount = results.size();

        Table table = new Table(COLUMNS);
        for (KeyCatalog.Entry entry : results) {
            MfpKey key = entry.key();
            table.addRow(List.of(
                            Cells.icon(SlotWidget.of(key, entry.displayName(), tooltipFor(entry))),
                            Cells.text(key.id(), Theme.TEXT_DIM, tooltipFor(entry))),
                    0,
                    button -> {
                        if (button == 0) {
                            onChosen.accept(key);
                            back();
                        }
                    });
        }

        int listY = searchY + 14 + GAP;
        ScrollPanel scroll = new ScrollPanel();
        scroll.bounds(contentX(), listY, contentWidth(), Math.max(20, contentBottom() - listY - GAP));
        table.layout(scroll.viewportWidth());
        scroll.content(table, table.contentHeight());
        widgets.add(scroll);
    }

    private static List<Component> tooltipFor(KeyCatalog.Entry entry) {
        return List.of(
                Component.literal(entry.displayName()).withStyle(ChatFormatting.WHITE),
                Component.literal(entry.key().id()).withStyle(ChatFormatting.DARK_GRAY),
                Component.literal(entry.key().kind() == MfpKey.Kind.FLUID
                        ? "fluid - rates in mB" : "item").withStyle(ChatFormatting.DARK_GRAY),
                Component.literal("click to choose").withStyle(ChatFormatting.GRAY));
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (matchCount == 0) {
            graphics.drawString(font, "nothing in the index produces that",
                    contentX(), searchY + 22, Theme.TEXT_IDLE, false);
            return;
        }
        String note = matchCount >= RESULT_LIMIT
                ? RESULT_LIMIT + "+ matches - keep typing"
                : matchCount + " match" + (matchCount == 1 ? "" : "es");
        graphics.drawString(font, MfpWidget.fit(note, contentWidth()),
                contentX(), panelY + panelHeight - FOOTER_HEIGHT + 6, Theme.TEXT_DIM, false);
    }
}
