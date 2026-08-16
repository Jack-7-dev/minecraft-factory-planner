package dev.mfp.client.widget;

import dev.mfp.client.KeyStacks;
import dev.mfp.core.model.MfpKey;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * One item or fluid flowing at a rate: an icon, the rate beside it, and a tooltip.
 *
 * <p>The rate is drawn next to the icon rather than stamped on it as a stack count. Vanilla's
 * decoration text is right-aligned into a 16-pixel box, which is fine for "64" and unreadable for
 * "1.44k/s" — and a production table's whole job is those numbers.
 */
public final class SlotWidget extends MfpWidget {

    public static final int ICON = 16;
    private static final int GAP = 3;

    private final ItemStack stack;
    private final String label;
    private List<Component> tooltip;
    private Runnable action;

    private SlotWidget(ItemStack stack, String label, List<Component> tooltip) {
        this.stack = stack;
        this.label = label;
        this.tooltip = tooltip;
        this.height = ICON;
        this.width = preferredWidth();
    }

    /**
     * Make this flow answer a click (M11.2).
     *
     * <p>Set on the slot rather than wrapped around the panel because a panel holds a dozen flows
     * and only some of them lead anywhere: an import that nothing in the pack produces has no
     * recipes to offer, and a slot that highlights and does nothing is worse than one that does not
     * highlight.
     */
    public SlotWidget onClick(Runnable newAction, String hint) {
        this.action = newAction;
        List<Component> lines = new java.util.ArrayList<>(tooltip);
        lines.add(Component.literal(hint).withStyle(net.minecraft.ChatFormatting.YELLOW));
        this.tooltip = List.copyOf(lines);
        return this;
    }

    /** A flow of {@code perSecond} of {@code key}, rendered in the given timescale. */
    public static SlotWidget flow(MfpKey key, double perSecond, Timescale scale) {
        double unitsPerStack = KeyStacks.unitsPerStack(key);
        String label = Fmt.number(scale.apply(perSecond, unitsPerStack));
        String full = label + scale.suffixFor(unitsPerStack);
        // The per-second figure goes in the tooltip whatever the view mode, because it is the one
        // everything else in MFP is stated in and the only one two views can be compared through.
        List<Component> lines = new java.util.ArrayList<>(KeyStacks.tooltip(key, full));
        if (scale != Timescale.PER_SECOND) {
            lines.add(net.minecraft.network.chat.Component
                    .literal(Fmt.number(perSecond) + "/s")
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        }
        return new SlotWidget(KeyStacks.icon(key), label, List.copyOf(lines));
    }

    /** A key with arbitrary label text, for places where the number is not a rate. */
    public static SlotWidget of(MfpKey key, String label, List<Component> tooltip) {
        return new SlotWidget(KeyStacks.icon(key), label, tooltip);
    }

    /**
     * Any stack at all, for the things that are not keys.
     *
     * <p>A machine is the case this exists for: it has an item and a name but no {@link MfpKey},
     * since nothing in the plan flows machines around. Taking the stack rather than looking one up
     * keeps this widget ignorant of where icons come from — {@code KeyStacks} for a flow,
     * {@code MachineStacks} for a machine — and an empty stack still draws the "?" box.
     */
    public static SlotWidget stack(ItemStack stack, String label, List<Component> tooltip) {
        return new SlotWidget(stack, label, tooltip);
    }

    public int preferredWidth() {
        return ICON + GAP + font().width(label);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (action == null || button != 0 || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        action.run();
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (action != null && isMouseOver(mouseX, mouseY)) {
            // The only advertisement a clickable flow gets. A cursor does not change shape in a
            // Minecraft screen, so without this the affordance is invisible until it is found by
            // accident.
            graphics.fill(x - 1, y - 1, x + width + 1, y + ICON + 1, Theme.TAB_SELECTED);
        }
        if (stack.isEmpty()) {
            // Nothing registered under this id on this client. Say so rather than leaving a gap:
            // an invisible ingredient is worse than an obviously unknown one.
            graphics.fill(x + 2, y + 2, x + ICON - 2, y + ICON - 2, 0x40FFFFFF);
            graphics.drawString(font(), "?", x + 6, y + 4, Theme.TEXT_DIM, false);
        } else {
            graphics.renderItem(stack, x, y);
        }
        // Fitted to the width the slot was actually given, which is not always the width it asked
        // for. A rate is a few characters and never overflows; a display name is not, and an item
        // called "Extreme Chemical Reactor Casing" in the item picker used to be drawn straight
        // across the id column beside it.
        graphics.drawString(font(), fit(label, Math.max(0, width - ICON - GAP)),
                x + ICON + GAP, y + (ICON - 8) / 2, Theme.TEXT, false);
    }

    @Override
    public List<Component> tooltip(int mouseX, int mouseY) {
        // Only the icon is a tooltip target. The label beside it is already the whole answer, and
        // making it hover would put a tooltip over the next column's icons.
        // Except when the slot is clickable, where the click target is the whole of it and a
        // tooltip covering only part would leave the rest looking inert.
        boolean overIcon = mouseX >= x && mouseX < x + ICON && mouseY >= y && mouseY < y + ICON;
        return overIcon || (action != null && isMouseOver(mouseX, mouseY)) ? tooltip : List.of();
    }
}
