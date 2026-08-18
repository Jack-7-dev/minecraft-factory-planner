package dev.mfp.client.widget;

/**
 * How rates are shown. A pure display multiplication, applied once at render time.
 *
 * <p>Everything MFP stores is per second, without exception (plan P2). This enum exists so that
 * stays true: nothing writes a per-minute figure anywhere, it is only ever multiplied on the way to
 * the screen.
 *
 * <p>Stacks per minute needs one number the others do not — how many of the thing make a stack —
 * and it is the caller's to supply, because it depends on the item. A fluid's "stack" is a bucket,
 * which is the unit a player moves fluids in and the only reading of "stacks of water" that means
 * anything. Where no stack size is known the mode falls back to per minute rather than inventing
 * one; a made-up divisor would be a wrong number rendered as confidently as a right one.
 */
public enum Timescale {

    PER_SECOND("/s", 1.0),
    PER_MINUTE("/min", 60.0),
    STACKS_PER_MINUTE("st/min", 60.0);

    private final String suffix;
    private final double factor;

    Timescale(String suffix, double factor) {
        this.suffix = suffix;
        this.factor = factor;
    }

    public String suffix() {
        return suffix;
    }

    /** For rates with no stack size: energy, computation, crafts. */
    public double apply(double perSecond) {
        return apply(perSecond, 0);
    }

    /**
     * @param unitsPerStack items in a stack, or millibuckets in a bucket; 0 when unknown
     */
    public double apply(double perSecond, double unitsPerStack) {
        double scaled = perSecond * factor;
        if (this == STACKS_PER_MINUTE && unitsPerStack > 0) {
            return scaled / unitsPerStack;
        }
        return scaled;
    }

    /** The suffix actually used for a flow, which differs when a stack size was not available. */
    public String suffixFor(double unitsPerStack) {
        return this == STACKS_PER_MINUTE && unitsPerStack <= 0 ? PER_MINUTE.suffix : suffix;
    }

    public Timescale next() {
        Timescale[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /** The other way round the same ring, for the right-click that undoes an overshoot. */
    public Timescale previous() {
        Timescale[] values = values();
        return values[(ordinal() + values.length - 1) % values.length];
    }
}
