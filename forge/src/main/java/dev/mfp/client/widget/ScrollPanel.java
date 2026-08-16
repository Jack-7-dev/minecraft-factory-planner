package dev.mfp.client.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * A clipped viewport over one taller child widget.
 *
 * <p>The child is laid out at its full height and simply positioned above the viewport's top edge
 * by the scroll offset, so it never needs to know it is being scrolled and its children keep real
 * screen coordinates — which is what lets hit testing and tooltips work without a coordinate
 * transform anywhere.
 */
public final class ScrollPanel extends MfpWidget {

    private static final int BAR_WIDTH = 4;
    private static final int WHEEL_STEP = 22;

    private MfpWidget content;
    private int contentHeight;
    private int scroll;
    private boolean draggingBar;

    /**
     * @param contentHeight the child's full height; the child is given exactly this much room
     */
    public ScrollPanel content(MfpWidget newContent, int newContentHeight) {
        this.content = newContent;
        this.contentHeight = Math.max(0, newContentHeight);
        this.scroll = Mth.clamp(scroll, 0, maxScroll());
        return this;
    }

    /** How much horizontal room the content gets, i.e. the panel less the scrollbar gutter. */
    public int viewportWidth() {
        return Math.max(0, width - BAR_WIDTH - 2);
    }

    public int maxScroll() {
        return Math.max(0, contentHeight - height);
    }

    public boolean scrollable() {
        return maxScroll() > 0;
    }

    public void scrollTo(int offset) {
        this.scroll = Mth.clamp(offset, 0, maxScroll());
    }

    public int scrollOffset() {
        return scroll;
    }

    /** Nudge the view, for autoscrolling while something is dragged towards an edge. */
    public void scrollBy(int delta) {
        scrollTo(scroll + delta);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (content == null) {
            return;
        }
        scroll = Mth.clamp(scroll, 0, maxScroll());

        // The gutter is reserved whether or not a bar is drawn. Giving the content the extra four
        // pixels when it happens to fit would change its layout, which can change its height, which
        // can make it not fit — a viewport that oscillates by one row.
        content.bounds(x, y - scroll, viewportWidth(), contentHeight);
        // The scissor below stops the clipped rows being *seen*; this stops them being drawn at all.
        content.visibleBand(y, y + height);

        graphics.enableScissor(x, y, x + width, y + height);
        content.render(graphics, mouseX, mouseY, partialTick);
        graphics.disableScissor();

        if (scrollable()) {
            int barX = x + width - BAR_WIDTH;
            graphics.fill(barX, y, barX + BAR_WIDTH, y + height, 0x40000000);
            int handleHeight = Math.max(12, (int) ((long) height * height / contentHeight));
            int travel = height - handleHeight;
            int handleY = y + (maxScroll() == 0 ? 0 : travel * scroll / maxScroll());
            graphics.fill(barX, handleY, barX + BAR_WIDTH, handleY + handleHeight, Theme.BORDER_LIGHT);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!isMouseOver(mouseX, mouseY) || !scrollable()) {
            return false;
        }
        scrollTo(scroll - (int) (delta * WHEEL_STEP));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }
        if (scrollable() && mouseX >= x + width - BAR_WIDTH) {
            draggingBar = true;
            dragTo(mouseY);
            return true;
        }
        return content != null && content.mouseClicked(mouseX, mouseY, button);
    }

    /** Called by the screen while a mouse button is held, so the scrollbar can be dragged. */
    public void mouseDragged(double mouseY) {
        if (draggingBar) {
            dragTo(mouseY);
        }
    }

    public void mouseReleased() {
        draggingBar = false;
    }

    private void dragTo(double mouseY) {
        double fraction = (mouseY - y) / Math.max(1, height);
        scrollTo((int) Math.round(fraction * maxScroll()));
    }

    @Override
    public List<Component> tooltip(int mouseX, int mouseY) {
        // The containment check is what stops a clipped row's tooltip appearing when the cursor is
        // outside the viewport: the child is laid out at full height, most of it off-screen.
        if (content == null || !isMouseOver(mouseX, mouseY)) {
            return List.of();
        }
        return content.tooltip(mouseX, mouseY);
    }
}
