package dev.mfp.client.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/**
 * A horizontal slider: a value dragged along a track, with its own label drawn on it.
 *
 * <p>It exists for the planner's manual GUI scale. At Minecraft's 3x and above the plan table is
 * zoomed far enough in that most columns ellipsise, and which scale is right is a judgement about
 * one particular monitor rather than a number that can be computed — so the control has to be one
 * the user drags while watching the table reflow, not a value typed into a field.
 *
 * <p>{@link MfpWidget} has no drag hook, because nothing before this needed one: a click either
 * happened or it did not. Rather than push a mouse-drag event down the whole tree for one widget,
 * the held state lives here — the click arms it, {@link #render} follows the cursor it is already
 * handed every frame, and the button being physically up is read straight from GLFW. The release
 * therefore cannot be missed by a screen that swapped its widgets out mid-drag.
 */
public final class Slider extends MfpWidget {

    private final double min;
    private final double max;
    private final DoubleConsumer onChange;

    private double value;
    private double step;
    private DoubleFunction<String> label = Fmt::number;
    private String tooltip;
    private boolean dragging;

    public Slider(double min, double max, double value, DoubleConsumer onChange) {
        this.min = Math.min(min, max);
        this.max = Math.max(min, max);
        this.onChange = onChange == null ? v -> {} : onChange;
        this.value = clampAndSnap(value);
        this.height = 14;
    }

    /** Snap to a multiple of {@code step} measured from {@code min}; zero or less is continuous. */
    public Slider step(double step) {
        this.step = step;
        this.value = clampAndSnap(value);
        return this;
    }

    /** What to draw on the track, given the current value — e.g. {@code v -> "Scale: " + v + "x"}. */
    public Slider label(DoubleFunction<String> label) {
        this.label = label == null ? Fmt::number : label;
        return this;
    }

    public Slider tooltip(String tooltip) {
        this.tooltip = tooltip;
        return this;
    }

    public double value() {
        return value;
    }

    /** Sets the value without firing the callback: for a screen restating what it already knows. */
    public Slider value(double value) {
        this.value = clampAndSnap(value);
        return this;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible()) {
            return;
        }
        if (dragging) {
            if (buttonHeld()) {
                moveTo(mouseX);
            } else {
                dragging = false;
            }
        }

        boolean hovered = dragging || isMouseOver(mouseX, mouseY);
        graphics.fill(x, y, x + width, y + height, Theme.FIELD);

        // The filled portion is the value read at a glance; the label is the value read exactly.
        // Drawing both means a row of these can be compared without reading any of them.
        int filled = (int) Math.round(fraction() * Math.max(0, width - 2));
        if (filled > 0) {
            graphics.fill(x + 1, y + 1, x + 1 + filled, y + height - 1, Theme.ROW_SELECTED);
        }
        outline(graphics, x, y, width, height, hovered ? Theme.BORDER_LIGHT : Theme.BORDER);

        String drawn = fit(label.apply(value), Math.max(0, width - 6));
        graphics.drawString(
                font(),
                drawn,
                x + (width - font().width(drawn)) / 2,
                y + (height - 8) / 2,
                hovered ? Theme.TEXT_HEADER : Theme.TEXT,
                false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        dragging = true;
        moveTo(mouseX);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scroll) {
        if (scroll == 0 || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        double nudge = step > 0 ? step : (max - min) / 20.0;
        set(value + Math.signum(scroll) * nudge);
        return true;
    }

    @Override
    public List<Component> tooltip(int mouseX, int mouseY) {
        return tooltip != null && isMouseOver(mouseX, mouseY)
                ? List.of(Component.literal(tooltip))
                : List.of();
    }

    /** Where the handle sits, as 0..1. A degenerate range reads as full rather than as NaN. */
    private double fraction() {
        return max > min ? (value - min) / (max - min) : 1.0;
    }

    private void moveTo(double mouseX) {
        int track = width - 2;
        double fraction = track > 0 ? (mouseX - x - 1) / track : 0.0;
        set(min + fraction * (max - min));
    }

    /**
     * Adopts a new value, telling the listener only if the <em>snapped</em> value actually moved.
     *
     * <p>The listener re-lays-out a whole screen, so a drag along a stepped slider must cost one
     * callback per step crossed rather than one per frame.
     */
    private void set(double candidate) {
        double snapped = clampAndSnap(candidate);
        if (snapped != value) {
            value = snapped;
            onChange.accept(snapped);
        }
    }

    private double clampAndSnap(double candidate) {
        double clamped = Mth.clamp(Double.isNaN(candidate) ? min : candidate, min, max);
        if (step <= 0) {
            return clamped;
        }
        return Mth.clamp(min + Math.round((clamped - min) / step) * step, min, max);
    }

    private static boolean buttonHeld() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        return GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }
}
