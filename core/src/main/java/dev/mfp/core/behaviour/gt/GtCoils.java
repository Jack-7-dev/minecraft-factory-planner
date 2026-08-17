package dev.mfp.core.behaviour.gt;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Heating coil temperatures, in kelvin.
 *
 * <p>Which coil the player built into a blast furnace is not a property of the machine — it is a
 * build choice — so it arrives through {@code MachineConfig.structureOptions} and has to be turned
 * back into a temperature here.
 *
 * <p>Names are a convenience, not the contract. A pack can register coils this table has never
 * heard of, so a raw {@code coil_temperature} always wins and an unknown name is reported as
 * unknown rather than defaulted to cupronickel — quietly assuming the weakest coil would make every
 * high-tier blast recipe look impossible.
 */
public final class GtCoils {

    /** Structure option naming the coil, e.g. {@code "kanthal"}. */
    public static final String OPTION_COIL = "coil";
    /** Structure option giving the coil temperature directly, in kelvin. */
    public static final String OPTION_COIL_TEMPERATURE = "coil_temperature";

    private static final Map<String, Integer> TEMPERATURES = new LinkedHashMap<>();

    static {
        TEMPERATURES.put("cupronickel", 1800);
        TEMPERATURES.put("kanthal", 2700);
        TEMPERATURES.put("nichrome", 3600);
        TEMPERATURES.put("rtm_alloy", 4500);
        TEMPERATURES.put("hssg", 5400);
        TEMPERATURES.put("naquadah", 7200);
        TEMPERATURES.put("trinium", 9001);
        TEMPERATURES.put("tritanium", 10800);
        // Star-Technology's own three, from kubejs/startup_scripts/objects/blocks/coils.js. They
        // are here rather than left to the raw-temperature escape hatch because they are the pack's
        // entire endgame: without them the three hottest coils in the game MFP is built for are
        // "unknown", so a blast furnace using one reports no coil at all and a chemical reactor
        // falls back to assuming cupronickel — a 2.5x error on the machines a player has worked
        // hardest to build.
        //
        // Their tiers are not inferred from this list's order but declared by the pack, which
        // continues the sequence exactly: .tier(8), .tier(9), .tier(10) after tritanium's 7. That is
        // what makes appending them safe for tierOf as well as for temperatureOf; a pack that
        // numbered its coils differently would need the tier recorded rather than counted.
        TEMPERATURES.put("zalloy", 13499);
        TEMPERATURES.put("magmada_alloy", 16199);
        TEMPERATURES.put("abyssal_alloy", 18888);
    }

    private GtCoils() {}

    /** Temperature for a coil name, or -1 when the name is not one this table knows. */
    public static int temperatureOf(String coilName) {
        String key = normalise(coilName);
        return key == null ? -1 : TEMPERATURES.getOrDefault(key, -1);
    }

    /**
     * The coil's <em>tier</em> — its position in the table, cupronickel being 0 — or -1 if unknown.
     *
     * <p>A second reading of the same list, because GregTech uses both and they are not
     * interchangeable. The blast furnace cares only about kelvin; the chemical reactor, cracking
     * unit and pyrolyse oven scale on {@code CoilType.getTier()}, which is the enum ordinal and has
     * no arithmetic relationship to the temperature. Deriving one from the other would work today
     * and break the first time a pack inserts a coil in the middle.
     *
     * <p>Order is therefore load-bearing here, which is what {@link LinkedHashMap} is for.
     */
    public static int tierOf(String coilName) {
        String key = normalise(coilName);
        if (key == null) {
            return -1;
        }
        int tier = 0;
        for (String name : TEMPERATURES.keySet()) {
            if (name.equals(key)) {
                return tier;
            }
            tier++;
        }
        return -1;
    }

    /** A coil name as the table spells it: no namespace, no {@code _coil} suffix, lower case. */
    private static String normalise(String coilName) {
        if (coilName == null) {
            return null;
        }
        String key = coilName.toLowerCase(Locale.ROOT).trim();
        int colon = key.lastIndexOf(':');
        if (colon >= 0) {
            key = key.substring(colon + 1);
        }
        return key.replace("_coil_block", "").replace("_coil", "");
    }

    public static Set<String> names() {
        return TEMPERATURES.keySet();
    }
}
