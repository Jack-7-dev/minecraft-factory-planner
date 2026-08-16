package dev.mfp.core.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How refined each form is, as a ladder of ranks.
 *
 * <p>Data rather than code, so a pack that adds a form can place it without a release. The numbers
 * are ordinal only — the distance between two ranks is what the scorer reads, never the rank
 * itself.
 *
 * <pre>
 *   0  ore, raw ore, raw ore block         what you dig up
 *   1  crushed, purified, refined, impure and pure dust   ore processing
 *   2  dust, gem
 *   3  ingot, block, nugget                the base case for parts
 *   4  plate, rod, wire, foil, gear, ring, bolt, screw, pipe, cable, tool heads
 *   6  a known composition with no form of its own        machines, tools, assembled parts
 * </pre>
 *
 * <p><b>An unlisted form scores nothing at all</b>, and that is the load-bearing part. Vanilla coal,
 * sand and every mob drop have no material data whatsoever, and mistaking them for either primitives
 * or manufactured goods is exactly how the two earlier attempts at this problem went wrong
 * (STATUS §4b.16): one scored unproducible tool inputs as depth zero and punished the honest deep
 * chains instead. Scoring them neutrally means the rule is silent wherever it has nothing to say.
 *
 * <p>Rank 5 is deliberately empty: it leaves room between "a part made from an ingot" and "a thing
 * assembled from parts" for a pack that wants to distinguish them.
 */
public final class MaterialForms {

    /** Returned for a form the ladder does not place. Never compared as a number. */
    public static final int UNRANKED = -1;

    /** The rank of an item whose composition is known but which has no form of its own. */
    public static final int MANUFACTURED = 6;

    private static final Map<String, Integer> RANKS = new LinkedHashMap<>();

    /** Not on the ore ladder at all, which is true of nearly everything. */
    public static final int NOT_AN_ORE_SOURCE = -1;

    private static final Map<String, Integer> ORE_SOURCES = new LinkedHashMap<>();

    static {
        // Which form of an ore a plan should start from — a different question from how refined the
        // form is, and the two answers disagree. Refinement says ore -> ingot is the bigger step, so
        // it prefers to start from the ore; obtainability says nobody in Star-Technology has ever
        // held an ore block, because the pack's ores drop crushed and its geodes come out of a rock
        // filtrator. Starting from a form of the material the player cannot obtain is how
        // /mfp plan gtceu:steel_ingot came to route iron through basaltic mineral sand ore, an item
        // nothing in the pack produces.
        //
        // Highest first, so a bigger number is a better place to start.
        // One band, not three. Splitting it — crushed above washed above centrifuged, on the
        // argument that purified and refined ore are made *from* crushed — was tried and reverted:
        // it flipped the aluminium plan onto a shapeless crafting recipe that wants a netherite
        // hammer, which is a worse answer than stopping one stage too late. The ore stages are close
        // enough in obtainability that the gap is not worth what widening it disturbs.
        oreSource(3, "crushedOre", "purifiedOre", "refinedOre");
        oreSource(2, "geode");
        oreSource(1, "raw", "rawOreBlock");
        oreSource(0, "ore");

        // A geode is an ore-stage item like the rest of this row: it is dug up, or filtered out of
        // rock, and everything is still ahead of it. Placing it here as well as on the ore-source
        // ladder matters — a form the refinement ladder does not place scores nothing for refining,
        // which would leave a geode chain behind a raw-ore chain that climbs three ranks.
        rank(0, "ore", "raw", "rawOreBlock", "geode", "surfaceRock", "rock");
        rank(1, "crushedOre", "purifiedOre", "refinedOre", "impureDust", "pureDust");
        rank(2, "dust", "smallDust", "tinyDust",
                "gem", "chippedGem", "flawedGem", "flawlessGem", "exquisiteGem");
        rank(3, "ingot", "hotIngot", "block", "nugget");
        rank(4, "plate", "densePlate", "doublePlate", "foil", "round",
                "rod", "longRod", "bolt", "screw", "ring", "spring", "smallSpring",
                "fineWire", "rotor", "gear", "smallGear", "lens", "frame", "turbineBlade",
                "buzzSawBlade", "screwdriverTip", "drillHead", "chainsawHead", "wrenchTip",
                "wireCutterHead");
    }

    private MaterialForms() {}

    /**
     * Place a form on the ladder, or move one. The escape hatch for a pack with its own forms.
     *
     * <p>Naming a form the ladder already knows replaces its rank rather than adding a second entry,
     * so a pack correcting MFP wins outright — the same reasoning as behaviour overrides (§7.3).
     */
    public static void rank(int rank, String... forms) {
        for (String form : forms) {
            RANKS.put(form, rank);
        }
    }

    /** Where this form sits, or {@link #UNRANKED} when the ladder has nothing to say about it. */
    public static int rankOf(MaterialForm form) {
        if (form == null) {
            return UNRANKED;
        }
        if (form.isManufactured()) {
            return MANUFACTURED;
        }
        Integer rank = RANKS.get(form.prefix());
        return rank == null ? UNRANKED : rank;
    }

    /**
     * Place a form on the ore-source ladder, or move one. The same escape hatch as {@link #rank}.
     *
     * <p>Separate from the refinement ladder because it answers a separate question. Refinement asks
     * how far up the chain a recipe moves its material; this asks which end of the chain a plan can
     * realistically start from, which is a fact about the pack's world generation rather than about
     * the material.
     */
    public static void oreSource(int preference, String... forms) {
        for (String form : forms) {
            ORE_SOURCES.put(form, preference);
        }
    }

    /**
     * How good a starting point this form is, or {@link #NOT_AN_ORE_SOURCE} for anything that is not
     * an ore at all. Higher is better: crushed, then geode, then raw, then a plain ore block.
     */
    public static int oreSourceRank(MaterialForm form) {
        if (form == null || form.isManufactured()) {
            return NOT_AN_ORE_SOURCE;
        }
        Integer preference = ORE_SOURCES.get(form.prefix());
        return preference == null ? NOT_AN_ORE_SOURCE : preference;
    }

    /** Every form the ladder places, in the order they were registered. Mainly for diagnostics. */
    public static Map<String, Integer> ranks() {
        return Map.copyOf(RANKS);
    }
}
