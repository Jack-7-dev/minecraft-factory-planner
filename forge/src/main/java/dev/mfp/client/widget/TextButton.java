package dev.mfp.client.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.List;

/** A small flat button. Sized to its label, so a toolbar can be laid out left to right. */
public final class TextButton extends MfpWidget {

    private static final int ICON = 16;

    private final Runnable action;
    private Runnable secondary;
    private String label;
    private String tooltip;
    private boolean enabled = true;
    private boolean centred;
    private net.minecraft.world.item.ItemStack icon = net.minecraft.world.item.ItemStack.EMPTY;

    public TextButton(String label, Runnable action) {
        this.label = label;
        this.action = action;
        this.height = 14;
        this.width = preferredWidth();
    }

    public TextButton tooltip(String text) {
        this.tooltip = text;
        return this;
    }

    public TextButton label(String text) {
        this.label = text;
        this.width = preferredWidth();
        return this;
    }

    /**
     * Centre the label instead of insetting it from the left.
     *
     * <p>The default six-pixel inset is right for a toolbar button, whose label is the width the
     * button was sized to, and wrong for a square one: a single glyph in a twelve-pixel box sits
     * hard against the right edge and reads as misaligned rather than as a button.
     */
    public TextButton centred() {
        this.centred = true;
        return this;
    }

    /**
     * Draw a stack before the label, the way every other row in MFP names a thing (M9.2).
     *
     * <p>The plan list was six rows of {@code gtceu:ethanol x 1/s}, which is unreadable at a glance
     * for the same reason an ingredient column of ids would be: a name is read, an icon is
     * recognised. An empty stack simply leaves the label where it was, so a plan whose product this
     * client has no item for still gets a row.
     */
    public TextButton icon(net.minecraft.world.item.ItemStack stack) {
        this.icon = stack == null ? net.minecraft.world.item.ItemStack.EMPTY : stack;
        this.width = preferredWidth();
        return this;
    }

    /**
     * What a right-click does, which on every cycling button in MFP is "step the other way".
     *
     * <p>A button that cycles a list is only pleasant while the list is short. Eight coils overshot
     * by one costs seven more left-clicks to come back to, and the tier ring is fifteen long — so the
     * gesture that fixes an overshoot has to be cheaper than the one that caused it. Right-click is
     * that gesture, and it is the one a player already expects from every other cycling control they
     * have used. Callers are responsible for making it the exact inverse of the forward step, wrap
     * and "not set" state included; a backward step that is not an inverse is worse than none.
     *
     * <p>Optional on purpose. A button with no secondary leaves a right-click unhandled and falling
     * through, which is what keeps a right-click over an ordinary button inert rather than turning
     * every button in the GUI into a second, undiscoverable action.
     */
    public TextButton secondary(Runnable action) {
        this.secondary = action;
        return this;
    }

    public TextButton enabled(boolean value) {
        this.enabled = value;
        return this;
    }

    public int preferredWidth() {
        return font().width(label) + 12 + (icon.isEmpty() ? 0 : ICON + 2);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean hovered = enabled && isMouseOver(mouseX, mouseY);
        graphics.fill(x, y, x + width, y + height, hovered ? Theme.TAB_SELECTED : Theme.TAB_IDLE);
        outline(graphics, x, y, width, height, hovered ? Theme.BORDER_LIGHT : Theme.BORDER);
        int colour = enabled ? (hovered ? Theme.TEXT_HEADER : Theme.TEXT) : Theme.TEXT_IDLE;
        int inset = icon.isEmpty() ? 0 : ICON + 2;
        if (!icon.isEmpty()) {
            // Vertically centred on the row rather than sitting on its top edge: a 16-pixel icon in
            // a 13-pixel row overhangs both ways, and centring it is what keeps a list of them level.
            graphics.renderItem(icon, x + 1, y + (height - ICON) / 2);
        }
        String drawn = fit(label, width - inset - (centred ? 2 : 8));
        int labelX = centred
                ? x + inset + (width - inset - font().width(drawn)) / 2
                : x + inset + 6;
        graphics.drawString(font(), drawn, labelX, y + (height - 8) / 2, colour, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!enabled || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        Runnable pressed = button == 0 ? action : button == 1 ? secondary : null;
        if (pressed == null) {
            return false;
        }
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        pressed.run();
        return true;
    }

    @Override
    public List<Component> tooltip(int mouseX, int mouseY) {
        return tooltip != null && isMouseOver(mouseX, mouseY)
                ? List.of(Component.literal(tooltip))
                : List.of();
    }
}
