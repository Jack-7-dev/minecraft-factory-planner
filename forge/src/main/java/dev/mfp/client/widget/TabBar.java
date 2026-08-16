package dev.mfp.client.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * A row of tabs. Each carries a count, because "Byproducts (0)" is worth knowing before clicking.
 *
 * <p>A tab may also carry a picture. The recipe picker groups hundreds of alternatives by the
 * machine that runs them, and a machine is recognised by its icon long before its name is read —
 * which is why every recipe viewer in the game puts one there. The icon is optional and the bar
 * stays text-only when no tab supplies one, so nothing else has to change.
 *
 * <p>Tabs <b>wrap</b> rather than overflowing. A steel ingot is made by a dozen different machines
 * and a bar that ran off the edge of the dialog would hide the very tabs the list is long enough to
 * need. {@link #preferredHeight(int)} says how tall the wrapped bar will be, so the screen can lay
 * out around it before anything is drawn.
 */
public final class TabBar extends MfpWidget {

    private static final int TEXT_ROW = 14;
    private static final int ICON_ROW = 20;
    private static final int PADDING = 5;
    private static final int SPACING = 1;

    /** @param icon a picture drawn before the title, or an empty stack for a text-only tab */
    public record Tab(String title, int count, String tooltip, ItemStack icon) {

        public Tab(String title, int count, String tooltip) {
            this(title, count, tooltip, ItemStack.EMPTY);
        }
    }

    /** Where one tab ended up once the bar was wrapped. */
    private record Placement(int x, int y, int width) {}

    private final List<Tab> tabs;
    private final IntConsumer onSelect;
    private final int rowHeight;
    private int selected;

    private List<Placement> placements = List.of();
    private int laidOutFor = -1;

    public TabBar(List<Tab> tabs, int selected, IntConsumer onSelect) {
        this.tabs = List.copyOf(tabs);
        this.selected = selected;
        this.onSelect = onSelect;
        this.rowHeight = this.tabs.stream().anyMatch(tab -> !tab.icon().isEmpty()) ? ICON_ROW : TEXT_ROW;
        this.height = rowHeight;
    }

    public int selected() {
        return selected;
    }

    /** How tall the bar needs to be once its tabs have wrapped into {@code forWidth}. */
    public int preferredHeight(int forWidth) {
        layout(forWidth);
        int rows = 1;
        for (Placement placement : placements) {
            rows = Math.max(rows, placement.y() / (rowHeight + SPACING) + 1);
        }
        return rows * (rowHeight + SPACING) - SPACING;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        layout(width);
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            Placement placement = placements.get(i);
            int tabX = x + placement.x();
            int tabY = y + placement.y();
            boolean active = i == selected;

            graphics.fill(tabX, tabY, tabX + placement.width(), tabY + rowHeight,
                    active ? Theme.TAB_SELECTED : Theme.TAB_IDLE);
            if (active) {
                graphics.fill(tabX, tabY + rowHeight - 1, tabX + placement.width(), tabY + rowHeight,
                        Theme.TEXT_HEADER);
            }
            int textX = tabX + PADDING;
            if (!tab.icon().isEmpty()) {
                graphics.renderItem(tab.icon(), textX, tabY + (rowHeight - SlotWidget.ICON) / 2);
                textX += SlotWidget.ICON + 2;
            }
            graphics.drawString(font(), label(tab), textX, tabY + (rowHeight - 8) / 2,
                    active ? Theme.TEXT : Theme.TEXT_DIM, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int hit = tabAt(mouseX, mouseY);
        if (hit < 0) {
            return false;
        }
        selected = hit;
        onSelect.accept(hit);
        return true;
    }

    @Override
    public List<Component> tooltip(int mouseX, int mouseY) {
        int hit = tabAt(mouseX, mouseY);
        if (hit < 0 || tabs.get(hit).tooltip() == null) {
            return List.of();
        }
        return List.of(Component.literal(tabs.get(hit).tooltip()));
    }

    private int tabAt(double mouseX, double mouseY) {
        if (!visible() || placements.size() != tabs.size()) {
            return -1;
        }
        for (int i = 0; i < placements.size(); i++) {
            Placement placement = placements.get(i);
            int tabX = x + placement.x();
            int tabY = y + placement.y();
            if (mouseX >= tabX && mouseX < tabX + placement.width()
                    && mouseY >= tabY && mouseY < tabY + rowHeight) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Wraps the tabs into {@code forWidth}, in coordinates relative to the bar's own origin.
     *
     * <p>Relative, so {@link #preferredHeight(int)} can be asked before the bar has been positioned —
     * the screen needs the height to decide where the bar goes, which it cannot do if the layout
     * depends on where the bar went.
     */
    private void layout(int forWidth) {
        if (laidOutFor == forWidth) {
            return;
        }
        laidOutFor = forWidth;

        List<Placement> laid = new ArrayList<>(tabs.size());
        int cursorX = 0;
        int cursorY = 0;
        for (Tab tab : tabs) {
            int tabWidth = widthOf(tab);
            if (cursorX > 0 && cursorX + tabWidth > forWidth) {
                cursorX = 0;
                cursorY += rowHeight + SPACING;
            }
            laid.add(new Placement(cursorX, cursorY, tabWidth));
            cursorX += tabWidth + SPACING;
        }
        this.placements = List.copyOf(laid);
    }

    private static String label(Tab tab) {
        return tab.title() + " (" + tab.count() + ")";
    }

    private int widthOf(Tab tab) {
        int iconWidth = tab.icon().isEmpty() ? 0 : SlotWidget.ICON + 2;
        return font().width(label(tab)) + iconWidth + 2 * PADDING;
    }
}
