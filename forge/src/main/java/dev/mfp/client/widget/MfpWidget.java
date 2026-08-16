package dev.mfp.client.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Base class for the planner's widgets.
 *
 * <p>Vanilla's {@code AbstractWidget} is built around a button: it owns a message, a narration and
 * a click action, and it draws its own background. The planner's widgets are tables and panels that
 * draw hundreds of cells and defer their tooltips, so they subclass this instead — small enough to
 * read in one sitting, and with no vanilla behaviour to work around.
 *
 * <p>Tooltips are <em>returned</em>, not drawn. A widget inside a scroll panel does not know
 * whether it is clipped, and a tooltip drawn during the widget's own render would be painted under
 * everything drawn after it. The screen asks the tree what is under the cursor once, after
 * everything else is on screen.
 */
public abstract class MfpWidget {

    protected int x;
    protected int y;
    protected int width;
    protected int height;

    private boolean visible = true;

    public MfpWidget bounds(int newX, int newY, int newWidth, int newHeight) {
        this.x = newX;
        this.y = newY;
        this.width = Math.max(0, newWidth);
        this.height = Math.max(0, newHeight);
        return this;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean visible() {
        return visible;
    }

    public MfpWidget visible(boolean newVisible) {
        this.visible = newVisible;
        return this;
    }

    public abstract void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick);

    /**
     * The band of screen rows that will actually be seen this frame, in screen coordinates.
     *
     * <p>A widget inside a {@link ScrollPanel} is laid out at its full height and clipped by a
     * scissor, so by default it draws every row it owns and the GPU throws all but a screenful away.
     * That is invisible until a list is long — the recipe picker on a steel ingot is ~430 rows and
     * some 2,400 item renders per frame — at which point it costs the framerate rather than a few
     * wasted calls. The panel therefore tells its content what is visible and content that can skip
     * work does; the default is to ignore it, so a widget only opts in when culling is worth the
     * bookkeeping.
     */
    public void visibleBand(int top, int bottom) {}

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scroll) {
        return false;
    }

    /** What to show under the cursor, or an empty list for nothing. */
    public List<Component> tooltip(int mouseX, int mouseY) {
        return List.of();
    }

    public boolean isMouseOver(double mouseX, double mouseY) {
        return visible
                && mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + height;
    }

    /**
     * A key press, offered to the widget tree before the screen handles it.
     *
     * <p>Returning true swallows the key, which is what stops Escape from closing a screen while a
     * field is being typed into.
     */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    public boolean charTyped(char codePoint, int modifiers) {
        return false;
    }

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    /** {@code text}, shortened with an ellipsis if it does not fit in {@code maxWidth}. */
    public static String fit(String text, int maxWidth) {
        Font font = font();
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int room = Math.max(0, maxWidth - font.width(ellipsis));
        return font.plainSubstrByWidth(text, room) + ellipsis;
    }

    /** Draws a one-pixel outline just inside the given rectangle. */
    public static void outline(GuiGraphics graphics, int x, int y, int width, int height, int colour) {
        graphics.fill(x, y, x + width, y + 1, colour);
        graphics.fill(x, y + height - 1, x + width, y + height, colour);
        graphics.fill(x, y + 1, x + 1, y + height - 1, colour);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, colour);
    }
}
