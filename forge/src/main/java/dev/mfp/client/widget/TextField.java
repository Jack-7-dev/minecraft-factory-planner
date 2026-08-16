package dev.mfp.client.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

/**
 * A single-line text field: search boxes and number entry.
 *
 * <p>Written rather than borrowed from vanilla's {@code EditBox} for the same reason the rest of
 * this package exists — the planner's screens dispatch events down their own widget list and expect
 * tooltips to be returned rather than drawn, and a vanilla widget participates in neither. It is
 * also the field that decides how editing <em>feels</em>: every keystroke in the rate box re-solves
 * the plan, so the field has to be able to say "the text changed" without also saying "the user has
 * finished", which is why {@link #onChange} and {@link #onCommit} are separate.
 *
 * <p>The filter is a predicate on the codepoint rather than a validating parse, so a half-typed
 * number ("1.", "") is allowed to exist. Rejecting those as you type makes a numeric field
 * impossible to edit in the middle.
 */
public final class TextField extends MfpWidget {

    private static final int PADDING = 4;

    /** Digits, one decimal point and nothing else. Enough for a rate or a machine limit. */
    public static final IntPredicate NUMERIC = codePoint ->
            (codePoint >= '0' && codePoint <= '9') || codePoint == '.';

    /** Digits only, for counts that cannot be fractional. */
    public static final IntPredicate INTEGER = codePoint -> codePoint >= '0' && codePoint <= '9';

    private String text = "";
    private String placeholder = "";
    private int cursor;
    private boolean focused;
    private int maxLength = 128;
    private IntPredicate filter = codePoint -> true;
    private Consumer<String> onChange = value -> {};
    private Consumer<String> onCommit = value -> {};
    private String tooltip;
    private long focusedAtMillis;

    public TextField() {
        this.height = 14;
    }

    public TextField text(String newText) {
        this.text = newText == null ? "" : newText;
        this.cursor = this.text.length();
        return this;
    }

    public String text() {
        return text;
    }

    public TextField placeholder(String newPlaceholder) {
        this.placeholder = newPlaceholder == null ? "" : newPlaceholder;
        return this;
    }

    public TextField filter(IntPredicate newFilter) {
        this.filter = newFilter;
        return this;
    }

    public TextField maxLength(int newMaxLength) {
        this.maxLength = newMaxLength;
        return this;
    }

    /** Called on every edit. Cheap work only: this fires per keystroke. */
    public TextField onChange(Consumer<String> listener) {
        this.onChange = listener;
        return this;
    }

    /** Called on Enter, and on losing focus with the text changed. Where a re-solve belongs. */
    public TextField onCommit(Consumer<String> listener) {
        this.onCommit = listener;
        return this;
    }

    public TextField tooltip(String text) {
        this.tooltip = text;
        return this;
    }

    public TextField focus() {
        this.focused = true;
        this.focusedAtMillis = System.currentTimeMillis();
        return this;
    }

    public boolean focused() {
        return focused;
    }

    /** The text as a number, or {@code fallback} for anything that is not one. */
    public double asDouble(double fallback) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public int asInt(int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(x, y, x + width, y + height, Theme.FIELD);
        outline(graphics, x, y, width, height, focused ? Theme.TEXT_HEADER : Theme.BORDER);

        int textY = y + (height - 8) / 2;
        int room = width - 2 * PADDING;
        if (text.isEmpty() && !focused) {
            graphics.drawString(font(), fit(placeholder, room), x + PADDING, textY, Theme.TEXT_IDLE, false);
            return;
        }

        // Long text scrolls so the cursor stays visible, rather than being ellipsised: what you are
        // typing is the part you need to see.
        String visible = text;
        int offset = 0;
        while (font().width(visible.substring(offset, cursor)) > room) {
            offset++;
        }
        visible = visible.substring(offset);
        while (font().width(visible) > room) {
            visible = visible.substring(0, visible.length() - 1);
        }
        graphics.drawString(font(), visible, x + PADDING, textY, Theme.TEXT, false);

        if (focused && ((System.currentTimeMillis() - focusedAtMillis) / 500) % 2 == 0) {
            int caretX = x + PADDING + font().width(text.substring(offset, cursor));
            graphics.fill(caretX, y + 3, caretX + 1, y + height - 3, Theme.TEXT);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean over = isMouseOver(mouseX, mouseY);
        if (over && button == 0) {
            focus();
            cursor = cursorAt(mouseX);
            return true;
        }
        if (!over && focused) {
            blur();
        }
        return false;
    }

    /** Give up focus, committing whatever was typed. Called when something else is clicked. */
    public void blur() {
        if (focused) {
            focused = false;
            onCommit.accept(text);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!focused) {
            return false;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                // Swallowed so Escape leaves the field rather than closing the screen underneath
                // it; a second press then closes, which is the behaviour every other GUI has.
                focused = false;
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                focused = false;
                onCommit.accept(text);
                return true;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (cursor > 0) {
                    text = text.substring(0, cursor - 1) + text.substring(cursor);
                    cursor--;
                    onChange.accept(text);
                }
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (cursor < text.length()) {
                    text = text.substring(0, cursor) + text.substring(cursor + 1);
                    onChange.accept(text);
                }
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                cursor = Math.max(0, cursor - 1);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                cursor = Math.min(text.length(), cursor + 1);
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                cursor = 0;
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                cursor = text.length();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!focused || text.length() >= maxLength || !filter.test(codePoint)) {
            return focused;
        }
        text = text.substring(0, cursor) + codePoint + text.substring(cursor);
        cursor++;
        onChange.accept(text);
        return true;
    }

    @Override
    public List<Component> tooltip(int mouseX, int mouseY) {
        return tooltip != null && !focused && isMouseOver(mouseX, mouseY)
                ? List.of(Component.literal(tooltip))
                : List.of();
    }

    private int cursorAt(double mouseX) {
        int relative = (int) Math.round(mouseX - x - PADDING);
        for (int i = 0; i <= text.length(); i++) {
            if (font().width(text.substring(0, i)) >= relative) {
                return i;
            }
        }
        return text.length();
    }

    /** Clamps a parsed number into a range, for callers that need one. */
    public static int clamp(int value, int minimum, int maximum) {
        return Mth.clamp(value, minimum, maximum);
    }
}
