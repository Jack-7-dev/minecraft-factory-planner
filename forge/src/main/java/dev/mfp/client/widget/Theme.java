package dev.mfp.client.widget;

import dev.mfp.core.model.Confidence;

/**
 * The colours the planner draws with, in one place.
 *
 * <p>Confidence has its own colours rather than being folded into a generic "warning" colour,
 * because it is the one thing on screen the user must be able to read at a glance (plan P5). A
 * number MFP is sure of and a number it guessed must never look the same.
 */
public final class Theme {

    private Theme() {}

    public static final int PANEL = 0xF01B1B1F;
    public static final int PANEL_INNER = 0x40000000;
    public static final int BORDER = 0xFF3A3A42;
    public static final int BORDER_LIGHT = 0xFF55555F;
    public static final int ROW_ODD = 0x18FFFFFF;
    public static final int ROW_HOVER = 0x30FFFFFF;
    public static final int SEPARATOR = 0x22FFFFFF;

    public static final int TEXT = 0xFFE6E6E6;
    public static final int TEXT_DIM = 0xFF9A9AA2;
    public static final int TEXT_HEADER = 0xFFFFD764;
    public static final int TEXT_IDLE = 0xFF6A6A72;

    public static final int EXACT = 0xFFE6E6E6;
    public static final int APPROXIMATE = 0xFFFFC24D;
    public static final int UNKNOWN = 0xFFFF6B6B;

    public static final int ENERGY_IN = 0xFFFF9E5E;
    public static final int ENERGY_OUT = 0xFF7BE07B;
    public static final int WARNING = 0xFFFFC24D;
    public static final int ERROR = 0xFFFF6B6B;

    public static final int FIELD = 0xFF121216;
    public static final int ROW_SELECTED = 0x40FFD764;
    public static final int PINNED = 0xFF7BC8FF;

    public static final int TAB_SELECTED = 0xFF2E2E36;
    public static final int TAB_IDLE = 0xFF232329;

    public static int forConfidence(Confidence confidence) {
        return switch (confidence) {
            case EXACT -> EXACT;
            case APPROXIMATE -> APPROXIMATE;
            case UNKNOWN -> UNKNOWN;
        };
    }
}
