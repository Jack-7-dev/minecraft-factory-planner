package dev.mfp.client.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** The cell kinds the production table is built from. */
public final class Cells {

    private Cells() {}

    /** A single line of text, vertically centred, ellipsised if it does not fit. */
    public static Table.Cell text(String text, int colour, List<Component> tooltip) {
        return new TextCell(text, colour, tooltip);
    }

    public static Table.Cell text(String text, int colour) {
        return new TextCell(text, colour, List.of());
    }

    /** A wrapping run of item/fluid flows. */
    public static Table.Cell flows(List<SlotWidget> slots) {
        return new FlowCell(slots);
    }

    /**
     * A clickable label inside a row: pin, unpin, hide.
     *
     * <p>Drawn as a small framed button rather than as coloured text, because a row that is itself
     * clickable gives text no way to advertise that this part of it does something else.
     */
    public static Table.Cell button(String label, int colour, List<Component> tooltip, Runnable action) {
        return new ButtonCell(label, colour, tooltip, action);
    }

    /** An icon with a label beside it, for lists of items rather than of flows. */
    public static Table.Cell icon(SlotWidget slot) {
        return new FlowCell(List.of(slot));
    }

    /**
     * A picture and a name, for the things that are named rather than flowing: machines.
     *
     * <p>Unlike {@link #icon(SlotWidget)} the tooltip covers the whole cell, because the name is as
     * much the subject here as the icon is — and in the production table the cell is clickable, so
     * a tooltip that only appeared over sixteen pixels would leave most of the click target
     * unexplained. An empty stack keeps the "?" box rather than shifting the text left: an
     * unrecognised machine should look unrecognised, not look like a different layout.
     */
    public static Table.Cell iconText(ItemStack icon, String text, int colour, List<Component> tooltip) {
        return new IconTextCell(icon, text, colour, tooltip);
    }

    private record IconTextCell(ItemStack icon, String text, int colour, List<Component> tooltip)
            implements Table.Cell {

        private static final int GAP = 3;

        @Override
        public int preferredHeight(int width) {
            return SlotWidget.ICON;
        }

        @Override
        public void render(GuiGraphics graphics, int x, int y, int width, int height,
                           int mouseX, int mouseY) {
            int iconY = y + (height - SlotWidget.ICON) / 2;
            if (icon.isEmpty()) {
                graphics.fill(x + 2, iconY + 2, x + SlotWidget.ICON - 2, iconY + SlotWidget.ICON - 2,
                        0x40FFFFFF);
                graphics.drawString(MfpWidget.font(), "?", x + 6, iconY + 4, Theme.TEXT_DIM, false);
            } else {
                graphics.renderItem(icon, x, iconY);
            }
            int textX = x + SlotWidget.ICON + GAP;
            graphics.drawString(MfpWidget.font(), MfpWidget.fit(text, width - SlotWidget.ICON - GAP),
                    textX, y + (height - 8) / 2, colour, false);
        }

        @Override
        public List<Component> tooltip(int x, int y, int width, int height, int mouseX, int mouseY) {
            return tooltip;
        }
    }

    /**
     * A cell that explains itself wherever it is not already explaining something else.
     *
     * <p>For a column of flows that also stands for the row: hovering an item still gives that
     * item's own tooltip, and hovering the space beside it gives the row's. Merging the two would
     * mean every item in the plan carried a paragraph about the recipe it came out of, and dropping
     * the fallback would mean a single-product line had nowhere to say what it is.
     */
    public static Table.Cell orTooltip(Table.Cell delegate, List<Component> fallback) {
        return new FallbackTooltipCell(delegate, fallback);
    }

    private record FallbackTooltipCell(Table.Cell delegate, List<Component> fallback)
            implements Table.Cell {

        @Override
        public int preferredHeight(int width) {
            return delegate.preferredHeight(width);
        }

        @Override
        public void render(GuiGraphics graphics, int x, int y, int width, int height,
                           int mouseX, int mouseY) {
            delegate.render(graphics, x, y, width, height, mouseX, mouseY);
        }

        @Override
        public List<Component> tooltip(int x, int y, int width, int height, int mouseX, int mouseY) {
            List<Component> own = delegate.tooltip(x, y, width, height, mouseX, mouseY);
            return own.isEmpty() ? fallback : own;
        }

        @Override
        public boolean mouseClicked(int x, int y, int width, int height,
                                    double mouseX, double mouseY, int button) {
            return delegate.mouseClicked(x, y, width, height, mouseX, mouseY, button);
        }
    }

    /**
     * Any cell, made to respond to a left click over the whole of it.
     *
     * <p>A wrapper rather than a clickable variant of each cell kind, so a column can be made
     * interactive without changing how it looks. In the production table that matters: the recipe
     * and machine columns became editable in M6b and are drawn exactly as they were in M6a, with
     * the column heading carrying the explanation.
     */
    public static Table.Cell clickable(Table.Cell delegate, Runnable action) {
        return new ClickableCell(delegate, action);
    }

    private record ClickableCell(Table.Cell delegate, Runnable action) implements Table.Cell {

        @Override
        public int preferredHeight(int width) {
            return delegate.preferredHeight(width);
        }

        @Override
        public void render(GuiGraphics graphics, int x, int y, int width, int height,
                           int mouseX, int mouseY) {
            delegate.render(graphics, x, y, width, height, mouseX, mouseY);
        }

        @Override
        public List<Component> tooltip(int x, int y, int width, int height, int mouseX, int mouseY) {
            return delegate.tooltip(x, y, width, height, mouseX, mouseY);
        }

        @Override
        public boolean mouseClicked(int x, int y, int width, int height,
                                    double mouseX, double mouseY, int button) {
            if (button != 0 || delegate.mouseClicked(x, y, width, height, mouseX, mouseY, button)) {
                return button == 0;
            }
            action.run();
            return true;
        }
    }

    /** Where a press inside a {@link #reorder} cell landed. */
    public interface GripPress {

        /**
         * @param arrow  -1 for the up arrow, +1 for the down arrow, 0 for the cell's body
         * @param mouseY where the press was, so the screen can measure a drag from it
         */
        void press(int arrow, double mouseY);
    }

    /**
     * The reorder grip: a pair of stacked arrows, and a handle for dragging the row bodily.
     *
     * <p>Two hit boxes in one cell rather than two cells, so the column stays narrow enough to be
     * worth the width it costs. An arrow at the end of the list is drawn dim and does nothing, which
     * is quieter than a button that appears to work and does not.
     *
     * <p><b>The cell reports the press and does nothing itself.</b> A press here may turn out to be
     * either a click on an arrow or the start of a drag, and which it was is not known until the
     * mouse moves or is released — so the decision belongs to the screen, which is the only thing
     * that sees the whole gesture. This is why the arrows act on release: pressing is now the
     * ambiguous half.
     */
    public static Table.Cell reorder(boolean canMoveUp, boolean canMoveDown,
                                     GripPress onPress, List<Component> tooltip) {
        return new ReorderCell(canMoveUp, canMoveDown, onPress, tooltip);
    }

    private record ReorderCell(boolean canMoveUp, boolean canMoveDown, GripPress onPress,
                               List<Component> tooltip) implements Table.Cell {

        private static final int ARROW_HEIGHT = 9;
        private static final int ARROW_WIDTH = 11;

        /**
         * U+25B2 and U+25BC, checked against the 1.20.1 client's own font before being used.
         *
         * <p>A missing glyph here would be an invisible control rather than an ugly one, so this was
         * verified rather than assumed. Both live in {@code font/nonlatin_european.png} at height 8
         * — the ordinary sheet, so they need no unicode-font setting — and both measure exactly 5x6
         * pixels with the same bounding box, so the pair mirrors. {@code ^} and {@code v} did not:
         * they are two unrelated characters that happen to point in roughly opposite directions, and
         * one of them is a letter.
         *
         * <p>Written as literals, which is safe because the root {@code build.gradle} pins
         * {@code options.encoding = 'UTF-8'} for every subproject.
         *
         * <p><b>Do not reach for another symbol without checking it the same way.</b> 1.20.1's
         * {@code font/include/unifont.json} declares <em>no</em> providers, so there is no unifont
         * fallback: a codepoint outside {@code ascii}, {@code accented} and
         * {@code nonlatin_european} does not render at all.
         */
        private static final String UP = "▲";
        private static final String DOWN = "▼";

        @Override
        public int preferredHeight(int width) {
            return 2 * ARROW_HEIGHT;
        }

        @Override
        public void render(GuiGraphics graphics, int x, int y, int width, int height,
                           int mouseX, int mouseY) {
            int top = y + (height - 2 * ARROW_HEIGHT) / 2;
            arrow(graphics, UP, x, top, canMoveUp, mouseX, mouseY);
            arrow(graphics, DOWN, x, top + ARROW_HEIGHT, canMoveDown, mouseX, mouseY);
        }

        private static void arrow(GuiGraphics graphics, String glyph, int x, int y, boolean enabled,
                                  int mouseX, int mouseY) {
            boolean hovered = enabled && hit(x, y, mouseX, mouseY);
            if (hovered) {
                graphics.fill(x, y, x + ARROW_WIDTH, y + ARROW_HEIGHT, Theme.TAB_SELECTED);
            }
            int colour = enabled ? (hovered ? Theme.TEXT_HEADER : Theme.TEXT_DIM) : Theme.TEXT_IDLE;
            int glyphX = x + (ARROW_WIDTH - MfpWidget.font().width(glyph)) / 2;
            graphics.drawString(MfpWidget.font(), glyph, glyphX, y + 1, colour, false);
        }

        @Override
        public List<Component> tooltip(int x, int y, int width, int height, int mouseX, int mouseY) {
            return tooltip;
        }

        @Override
        public boolean mouseClicked(int x, int y, int width, int height,
                                    double mouseX, double mouseY, int button) {
            if (button != 0) {
                return false;
            }
            int top = y + (height - 2 * ARROW_HEIGHT) / 2;
            int arrow = 0;
            if (canMoveUp && hit(x, top, (int) mouseX, (int) mouseY)) {
                arrow = -1;
            } else if (canMoveDown && hit(x, top + ARROW_HEIGHT, (int) mouseX, (int) mouseY)) {
                arrow = 1;
            }
            onPress.press(arrow, mouseY);
            // Consumed either way. A press anywhere in this column is a grab, and a mis-aimed one
            // must not fall through to the row's own click and open the recipe picker instead.
            return true;
        }

        private static boolean hit(int x, int y, int mouseX, int mouseY) {
            return mouseX >= x && mouseX < x + ARROW_WIDTH && mouseY >= y && mouseY < y + ARROW_HEIGHT;
        }
    }

    /** Two lines of text: a title and a dimmer subtitle under it. */
    public static Table.Cell twoLine(String title, int titleColour, String subtitle,
                                     List<Component> tooltip) {
        return new TwoLineCell(ItemStack.EMPTY, false, title, titleColour, subtitle, tooltip);
    }

    /** The same, with a picture in front of it — a machine, or a recipe's own machine. */
    public static Table.Cell iconTwoLine(ItemStack icon, String title, int titleColour, String subtitle,
                                         List<Component> tooltip) {
        return new TwoLineCell(icon, true, title, titleColour, subtitle, tooltip);
    }

    private record ButtonCell(String label, int colour, List<Component> tooltip, Runnable action)
            implements Table.Cell {

        @Override
        public int preferredHeight(int width) {
            return 12;
        }

        @Override
        public void render(GuiGraphics graphics, int x, int y, int width, int height,
                           int mouseX, int mouseY) {
            int boxWidth = Math.min(width, MfpWidget.font().width(label) + 10);
            int boxY = y + (height - 12) / 2;
            boolean hovered = hit(x, boxY, boxWidth, mouseX, mouseY);
            graphics.fill(x, boxY, x + boxWidth, boxY + 12, hovered ? Theme.TAB_SELECTED : Theme.TAB_IDLE);
            MfpWidget.outline(graphics, x, boxY, boxWidth, 12, hovered ? Theme.BORDER_LIGHT : Theme.BORDER);
            graphics.drawString(MfpWidget.font(), MfpWidget.fit(label, boxWidth - 6), x + 5, boxY + 2,
                    colour, false);
        }

        @Override
        public List<Component> tooltip(int x, int y, int width, int height, int mouseX, int mouseY) {
            return tooltip;
        }

        @Override
        public boolean mouseClicked(int x, int y, int width, int height,
                                    double mouseX, double mouseY, int button) {
            if (button != 0) {
                return false;
            }
            int boxWidth = Math.min(width, MfpWidget.font().width(label) + 10);
            int boxY = y + (height - 12) / 2;
            if (!hit(x, boxY, boxWidth, (int) mouseX, (int) mouseY)) {
                return false;
            }
            action.run();
            return true;
        }

        private static boolean hit(int x, int boxY, int boxWidth, int mouseX, int mouseY) {
            return mouseX >= x && mouseX < x + boxWidth && mouseY >= boxY && mouseY < boxY + 12;
        }
    }

    /**
     * @param hasIcon whether an icon was intended at all. Distinct from {@code icon.isEmpty()}:
     *                a cell that wanted a picture and could not find one draws the "?" box, while a
     *                cell that never wanted one starts its text at the left edge.
     */
    private record TwoLineCell(ItemStack icon, boolean hasIcon, String title, int titleColour,
                               String subtitle, List<Component> tooltip)
            implements Table.Cell {

        @Override
        public int preferredHeight(int width) {
            return 19;
        }

        @Override
        public void render(GuiGraphics graphics, int x, int y, int width, int height,
                           int mouseX, int mouseY) {
            int textX = x;
            int textWidth = width;
            if (hasIcon) {
                int iconY = y + (19 - SlotWidget.ICON) / 2;
                if (icon.isEmpty()) {
                    graphics.fill(x + 2, iconY + 2, x + SlotWidget.ICON - 2, iconY + SlotWidget.ICON - 2,
                            0x40FFFFFF);
                    graphics.drawString(MfpWidget.font(), "?", x + 6, iconY + 4, Theme.TEXT_DIM, false);
                } else {
                    graphics.renderItem(icon, x, iconY);
                }
                textX += SlotWidget.ICON + 3;
                textWidth -= SlotWidget.ICON + 3;
            }
            graphics.drawString(MfpWidget.font(), MfpWidget.fit(title, textWidth), textX, y + 1,
                    titleColour, false);
            graphics.drawString(MfpWidget.font(), MfpWidget.fit(subtitle, textWidth), textX, y + 11,
                    Theme.TEXT_DIM, false);
        }

        @Override
        public List<Component> tooltip(int x, int y, int width, int height, int mouseX, int mouseY) {
            return tooltip;
        }
    }

    private record TextCell(String text, int colour, List<Component> tooltip) implements Table.Cell {

        @Override
        public int preferredHeight(int width) {
            return 9;
        }

        @Override
        public void render(GuiGraphics graphics, int x, int y, int width, int height,
                           int mouseX, int mouseY) {
            graphics.drawString(MfpWidget.font(), MfpWidget.fit(text, width),
                    x, y + (height - 8) / 2, colour, false);
        }

        @Override
        public List<Component> tooltip(int x, int y, int width, int height, int mouseX, int mouseY) {
            return tooltip;
        }
    }

    /**
     * Flows laid out left to right, wrapping to a new line when the next one does not fit.
     *
     * <p>The layout is recomputed on every render rather than cached because it depends on the
     * column width, and the column width depends on the window. Positioning a handful of slots is
     * cheap; keeping a cache correct across a resize is not.
     */
    private record FlowCell(List<SlotWidget> slots) implements Table.Cell {

        private static final int GAP_X = 6;
        private static final int GAP_Y = 2;

        @Override
        public int preferredHeight(int width) {
            return layout(0, 0, width);
        }

        @Override
        public void render(GuiGraphics graphics, int x, int y, int width, int height,
                           int mouseX, int mouseY) {
            layout(x, y, width);
            for (SlotWidget slot : slots) {
                slot.render(graphics, mouseX, mouseY, 0f);
            }
        }

        @Override
        public List<Component> tooltip(int x, int y, int width, int height, int mouseX, int mouseY) {
            layout(x, y, width);
            for (SlotWidget slot : slots) {
                List<Component> lines = slot.tooltip(mouseX, mouseY);
                if (!lines.isEmpty()) {
                    return lines;
                }
            }
            return List.of();
        }

        /**
         * A click on one of the flows, where that flow was given something to do (M11.2).
         *
         * <p>Laid out first for the same reason the tooltip is: the slots' bounds belong to the last
         * render at the last width, and a click is not a render.
         */
        @Override
        public boolean mouseClicked(int x, int y, int width, int height,
                                    double mouseX, double mouseY, int button) {
            if (button != 0) {
                return false;
            }
            layout(x, y, width);
            for (SlotWidget slot : slots) {
                if (slot.mouseClicked(mouseX, mouseY, button)) {
                    return true;
                }
            }
            return false;
        }

        /** Positions every slot and returns the total height used. */
        private int layout(int x, int y, int width) {
            int cursorX = x;
            int cursorY = y;
            int rowHeight = SlotWidget.ICON;
            for (SlotWidget slot : slots) {
                // Never wider than the cell. A slot that asks for more is one whose label is a
                // display name rather than a rate, and letting it have what it asked for draws it
                // over whatever is in the next column.
                int slotWidth = Math.min(slot.preferredWidth(), width);
                if (cursorX > x && cursorX + slotWidth > x + width) {
                    cursorX = x;
                    cursorY += rowHeight + GAP_Y;
                }
                slot.bounds(cursorX, cursorY, slotWidth, SlotWidget.ICON);
                cursorX += slotWidth + GAP_X;
            }
            return slots.isEmpty() ? 9 : (cursorY - y) + rowHeight;
        }
    }
}
