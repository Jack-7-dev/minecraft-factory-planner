package dev.mfp.client.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * A wrapping area of item/fluid flows, used for the products, byproducts and imports tabs.
 *
 * <p>Shares its layout with the production table's flow cells, so a rate reads the same wherever it
 * appears.
 */
public final class FlowPanel extends MfpWidget {

    private final Table.Cell cell;
    private final String emptyMessage;
    private final boolean empty;

    public FlowPanel(List<SlotWidget> slots, String emptyMessage) {
        this.cell = Cells.flows(slots);
        this.emptyMessage = emptyMessage;
        this.empty = slots.isEmpty();
    }

    public int preferredHeight(int forWidth) {
        return empty ? 9 : cell.preferredHeight(forWidth);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (empty) {
            graphics.drawString(font(), emptyMessage, x, y, Theme.TEXT_IDLE, false);
            return;
        }
        cell.render(graphics, x, y, width, height, mouseX, mouseY);
    }

    @Override
    public List<Component> tooltip(int mouseX, int mouseY) {
        return empty ? List.of() : cell.tooltip(x, y, width, height, mouseX, mouseY);
    }

    /** For the imports tab, where a flow is a question the user can answer in place (M11.2). */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return !empty && cell.mouseClicked(x, y, width, height, mouseX, mouseY, button);
    }
}
