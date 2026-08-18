package dev.mfp.core.solver;

import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;

/**
 * A deliberately round five-step chain, so every expected value can be worked out by hand.
 *
 * <p>Durations are chosen to give clean crafts-per-second at twenty ticks per second, and the
 * quantities to give whole machine counts. Targeting one casing per second:
 *
 * <pre>
 *   line     per craft        duration  crafts/s/machine  needed rate  machines
 *   casing   2 plate -> 1     20 ticks  1                 1 craft/s    1
 *   plate    1 ingot -> 1     10 ticks  2                 2 crafts/s   1
 *   ingot    1 dust  -> 1     40 ticks  0.5               2 crafts/s   4
 *   dust     1 crushed -> 1   20 ticks  1                 2 crafts/s   2
 *   crushed  1 ore -> 2       40 ticks  0.5               1 craft/s    2
 *                                                          total       10
 * </pre>
 *
 * <p>Energy, at EU/t x 20 x machines: 320 + 160 + 320 + 320 + 320 = 1440 EU/s.
 */
final class Fixtures {

    static final MfpKey ORE = MfpKey.item("mfp", "ore");
    static final MfpKey CRUSHED = MfpKey.item("mfp", "crushed");
    static final MfpKey DUST = MfpKey.item("mfp", "dust");
    static final MfpKey INGOT = MfpKey.item("mfp", "ingot");
    static final MfpKey PLATE = MfpKey.item("mfp", "plate");
    static final MfpKey CASING = MfpKey.item("mfp", "casing");
    static final MfpKey STONE_DUST = MfpKey.item("mfp", "stone_dust");
    static final MfpKey GRAVEL = MfpKey.item("mfp", "gravel");
    static final MfpKey FUEL = MfpKey.item("mfp", "fuel");
    /** A pair of solvents that only make each other, so neither can be made at all. */
    static final MfpKey SOLVENT_A = MfpKey.fluid("mfp", "solvent_a");
    static final MfpKey SOLVENT_B = MfpKey.fluid("mfp", "solvent_b");
    static final MfpKey LUBRICANT = MfpKey.fluid("mfp", "lubricant");

    private Fixtures() {}

    static MfpRecipe casing() {
        return simple("mfp:casing", PLATE, 2, CASING, 1, 20, 16);
    }

    static MfpRecipe plate() {
        return simple("mfp:plate", INGOT, 1, PLATE, 1, 10, 8);
    }

    static MfpRecipe ingot() {
        return simple("mfp:ingot", DUST, 1, INGOT, 1, 40, 4);
    }

    static MfpRecipe dust() {
        return simple("mfp:dust", CRUSHED, 1, DUST, 1, 20, 8);
    }

    static MfpRecipe crushed() {
        return simple("mfp:crushed", ORE, 1, CRUSHED, 2, 40, 8);
    }

    /** Like {@link #crushed()} but also yields stone dust nobody asked for. */
    static MfpRecipe crushedWithByproduct() {
        return MfpRecipe.builder("mfp:crushed_byproduct", "mfp:crusher", "test")
                .input(MfpIngredient.of(ORE, 1))
                .output(MfpOutput.of(CRUSHED, 2))
                .output(MfpOutput.of(STONE_DUST, 1))
                .duration(40)
                .euIn(8)
                .build();
    }

    /** Turns stone dust into gravel; used to check a byproduct gets consumed rather than imported. */
    static MfpRecipe gravelFromStoneDust() {
        return simple("mfp:gravel", STONE_DUST, 1, GRAVEL, 1, 20, 4);
    }

    /**
     * A generator: burns fuel and yields energy.
     *
     * <p>At 32 EU/t and one craft per second that is 640 EU/s per machine, so covering the chain's
     * 1440 EU/s needs 2.25 machines.
     */
    static MfpRecipe generator() {
        return MfpRecipe.builder("mfp:generator", "mfp:generator", "test")
                .input(MfpIngredient.of(FUEL, 1))
                .duration(20)
                .euOut(32)
                .build();
    }

    /** Borrows lubricant and gives it all back, so none should ever flow. */
    static MfpRecipe lubricatedPress() {
        return MfpRecipe.builder("mfp:lubricated_press", "mfp:press", "test")
                .input(MfpIngredient.of(INGOT, 1))
                .input(MfpIngredient.of(LUBRICANT, 1000))
                .output(MfpOutput.of(PLATE, 1))
                .output(MfpOutput.of(LUBRICANT, 1000))
                .duration(10)
                .euIn(8)
                .build();
    }

    static MfpRecipe simple(String id, MfpKey in, double inAmount,
                            MfpKey out, double outAmount, double duration, long euIn) {
        return MfpRecipe.builder(id, "mfp:machine", "test")
                .input(MfpIngredient.of(in, inAmount))
                .output(MfpOutput.of(out, outAmount))
                .duration(duration)
                .euIn(euIn)
                .build();
    }
}
