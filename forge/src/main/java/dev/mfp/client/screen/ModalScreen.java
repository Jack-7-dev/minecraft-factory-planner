package dev.mfp.client.screen;

import dev.mfp.client.widget.MfpWidget;
import dev.mfp.client.widget.ScrollPanel;
import dev.mfp.client.widget.TextButton;
import dev.mfp.client.widget.Theme;
import dev.mfp.client.widget.Tooltip;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The chrome and the plumbing every editing dialog shares: a titled panel, a widget list, deferred
 * tooltips, and the way back to the screen that opened it.
 *
 * <p>A modal is a real {@link Screen} rather than an overlay inside {@link PlannerScreen}. The
 * planner's own layout is computed in one pass against the window size, so drawing a dialog on top
 * of it would mean either two widget trees competing for the same clicks or a second event path
 * through the table underneath. A screen with a {@code parent} costs one extra {@code init} on the
 * way back and keeps both trees honest — and that {@code init} is what makes an edit visible, since
 * returning to the planner rebuilds it from the freshly re-solved plan.
 *
 * <p>Every subclass edits the plan through {@code ClientPlanner}, never by patching a solved result:
 * an edit is a mutation plus a re-solve, so what the planner draws afterwards is one answer rather
 * than a repaired one.
 */
public abstract class ModalScreen extends Screen {

    protected static final int GAP = 4;
    protected static final int TITLE_HEIGHT = 14;
    protected static final int FOOTER_HEIGHT = 20;

    private final Screen parent;
    private final String subtitle;

    protected final List<MfpWidget> widgets = new ArrayList<>();
    private final Tooltip tooltip = new Tooltip();

    protected int panelX;
    protected int panelY;
    protected int panelWidth;
    protected int panelHeight;

    protected ModalScreen(Screen parent, String title, String subtitle) {
        super(Component.literal(title));
        this.parent = parent;
        this.subtitle = subtitle;
    }

    /** How wide the dialog wants to be; clamped to the window. */
    protected int preferredWidth() {
        return 320;
    }

    protected int preferredHeight() {
        return 220;
    }

    @Override
    protected void init() {
        rebuild();
    }

    /** Lay the dialog out again from scratch. Called on open, on resize, and after any edit. */
    protected void rebuild() {
        widgets.clear();
        panelWidth = Math.min(preferredWidth(), width - 20);
        panelHeight = Math.min(preferredHeight(), height - 20);
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;

        TextButton close = new TextButton("Close", this::back);
        close.bounds(panelX + panelWidth - 6 - close.preferredWidth(),
                panelY + panelHeight - FOOTER_HEIGHT + 3, close.preferredWidth(), 14);
        widgets.add(close);

        build();
    }

    /** Add the dialog's own widgets. The panel bounds are already set when this is called. */
    protected abstract void build();

    /** Back to whatever opened this dialog, re-initialising it so any edit is reflected. */
    protected void back() {
        Minecraft.getInstance().setScreen(parent);
    }

    /** The area a subclass may use: inside the title bar and above the footer. */
    protected int contentY() {
        return panelY + TITLE_HEIGHT + GAP;
    }

    protected int contentBottom() {
        return panelY + panelHeight - FOOTER_HEIGHT;
    }

    protected int contentX() {
        return panelX + 6;
    }

    protected int contentWidth() {
        return panelWidth - 12;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, Theme.PANEL);
        MfpWidget.outline(graphics, panelX, panelY, panelWidth, panelHeight, Theme.BORDER_LIGHT);
        graphics.fill(panelX + 1, panelY + 1, panelX + panelWidth - 1, panelY + TITLE_HEIGHT, 0x40000000);
        graphics.drawString(font, title.getString(), panelX + 6, panelY + 4, Theme.TEXT_HEADER, false);
        if (subtitle != null) {
            int titleWidth = font.width(title.getString()) + 12;
            graphics.drawString(font, MfpWidget.fit(subtitle, panelWidth - titleWidth - 10),
                    panelX + titleWidth, panelY + 4, Theme.TEXT_DIM, false);
        }

        renderContent(graphics, mouseX, mouseY, partialTick);

        for (MfpWidget widget : widgets) {
            widget.render(graphics, mouseX, mouseY, partialTick);
        }
        for (MfpWidget widget : widgets) {
            tooltip.offer(widget.tooltip(mouseX, mouseY), mouseX, mouseY);
        }
        offerTooltips(mouseX, mouseY);

        super.render(graphics, mouseX, mouseY, partialTick);
        tooltip.renderAndClear(graphics);
    }

    /** Anything drawn under the widgets: labels, separators, a table header. */
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    /** A hook for tooltips that belong to something drawn rather than to a widget. */
    protected void offerTooltips(int mouseX, int mouseY) {}

    protected void offer(List<Component> lines, int mouseX, int mouseY) {
        tooltip.offer(lines, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // A snapshot, because a widget's action may rebuild the dialog — which clears this very
        // list — and a rebuild in the middle of dispatch would otherwise be a concurrent
        // modification rather than an edit taking effect.
        for (MfpWidget widget : List.copyOf(widgets)) {
            if (widget.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
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
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        for (MfpWidget widget : widgets) {
            if (widget instanceof ScrollPanel panel) {
                panel.mouseDragged(mouseY);
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        for (MfpWidget widget : widgets) {
            if (widget instanceof ScrollPanel panel) {
                panel.mouseReleased();
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Widgets first, so a focused text field can keep Escape for itself.
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

    @Override
    public void onClose() {
        back();
    }

    /** The world keeps running behind a planner dialog, as it does behind the planner itself. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    protected static String pathOf(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }
}
