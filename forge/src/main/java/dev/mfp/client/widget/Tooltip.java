package dev.mfp.client.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Deferred tooltip rendering.
 *
 * <p>Collected during the frame and drawn once at the end, above everything. Drawing a tooltip
 * where it is discovered would put it under any panel rendered afterwards, and inside a scroll
 * panel it would also be clipped by the viewport it belongs to.
 */
public final class Tooltip {

    /** Beyond this a tooltip stops being readable, and vanilla will not wrap it for us. */
    private static final int MAX_WIDTH = 260;
    private static final int MAX_LINES = 20;

    private List<Component> lines = List.of();
    private int x;
    private int y;

    /** Records a tooltip for this frame. The first non-empty one wins, i.e. the topmost widget. */
    public void offer(List<Component> newLines, int mouseX, int mouseY) {
        if (lines.isEmpty() && newLines != null && !newLines.isEmpty()) {
            this.lines = wrap(newLines);
            this.x = mouseX;
            this.y = mouseY;
        }
    }

    /**
     * Wrap every line, and stop after twenty.
     *
     * <p>{@code renderComponentTooltip} draws each component on one line whatever its length, so a
     * single long string runs off both edges of the screen — which is what a plan warning naming
     * two hundred recipes did. Wrapping here rather than at each call site means no caller can
     * produce that again, and the cap is the same admission a scrollbar would make: past twenty
     * lines nobody is reading a hover.
     */
    private static List<Component> wrap(List<Component> source) {
        var font = Minecraft.getInstance().font;
        List<Component> wrapped = new java.util.ArrayList<>(source.size());
        for (Component line : source) {
            if (font.width(line) <= MAX_WIDTH) {
                wrapped.add(line);
            } else {
                // Re-wrapped as literals carrying the original line's style, so a yellow warning
                // stays yellow on every row of itself.
                for (var part : font.getSplitter().splitLines(line.getString(), MAX_WIDTH,
                        net.minecraft.network.chat.Style.EMPTY)) {
                    wrapped.add(Component.literal(part.getString()).withStyle(line.getStyle()));
                }
            }
            if (wrapped.size() >= MAX_LINES) {
                wrapped.add(Component.literal("...").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
                break;
            }
        }
        return wrapped;
    }

    /** Draws whatever was collected and clears it, ready for the next frame. */
    public void renderAndClear(GuiGraphics graphics) {
        if (!lines.isEmpty()) {
            graphics.renderComponentTooltip(Minecraft.getInstance().font, lines, x, y);
            lines = List.of();
        }
    }
}
