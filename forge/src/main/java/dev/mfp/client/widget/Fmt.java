package dev.mfp.client.widget;

import java.util.Locale;

/** Number formatting for the planner. Short enough to fit a column, honest about magnitude. */
public final class Fmt {

    private Fmt() {}

    /**
     * A number at roughly three significant figures, with a k/M suffix once it stops fitting.
     *
     * <p>Whole numbers print without a decimal point: "2 machines" reads better than "2.00", and
     * the distinction between 2 and 2.5 is exactly the one the reader is looking for.
     */
    public static String number(double value) {
        double magnitude = Math.abs(value);
        if (!Double.isFinite(value)) {
            return "?";
        }
        if (magnitude >= 1e9) {
            return trimZeros(String.format(Locale.ROOT, "%.2f", value / 1e9)) + "G";
        }
        if (magnitude >= 1e6) {
            return trimZeros(String.format(Locale.ROOT, "%.2f", value / 1e6)) + "M";
        }
        if (magnitude >= 1e4) {
            return trimZeros(String.format(Locale.ROOT, "%.2f", value / 1e3)) + "k";
        }
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        if (magnitude >= 100) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        if (magnitude >= 10) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        if (magnitude >= 0.1) {
            return String.format(Locale.ROOT, "%.2f", value);
        }
        return trimZeros(String.format(Locale.ROOT, "%.4f", value));
    }

    /**
     * A per-second rate rendered in the chosen timescale, suffix included.
     *
     * <p>For quantities with no stack size — crafts, energy — so the suffix comes from
     * {@code suffixFor(0)}: a stacks-per-minute view falls back to per minute here rather than
     * labelling crafts as stacks, which would be a unit the number is not in.
     */
    public static String rate(double perSecond, Timescale scale) {
        return number(scale.apply(perSecond)) + scale.suffixFor(0);
    }

    /**
     * Machine count: the fractional truth, with the number actually built beside it.
     *
     * <p>Both are shown because both are true and they answer different questions. 2.5 is what the
     * plan needs; 3 is what you place. Showing only the ceiling hides how much headroom the third
     * machine has, and showing only the fraction does not tell you what to build.
     */
    public static String machines(double fractional, long toBuild) {
        String exact = number(fractional);
        return exact.equals(String.valueOf(toBuild)) ? exact : exact + " (" + toBuild + ")";
    }

    /** EU per tick, from the per-second figure the solver works in. */
    public static String eut(double euPerSecond) {
        return number(euPerSecond / 20.0);
    }

    /**
     * Steam in millibuckets per tick, from the per-second figure the solver works in.
     *
     * <p>Per tick rather than per second so it sits beside the EU/t it replaces: a steam machine's
     * draw is the same quantity in different units, and the two should be comparable at a glance.
     */
    public static String steam(double millibucketsPerSecond) {
        return number(millibucketsPerSecond / 20.0);
    }

    private static String trimZeros(String text) {
        if (text.indexOf('.') < 0) {
            return text;
        }
        String trimmed = text;
        while (trimmed.endsWith("0")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
