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
    }

    private GtCoils() {}

    /** Temperature for a coil name, or -1 when the name is not one this table knows. */
    public static int temperatureOf(String coilName) {
        if (coilName == null) {
            return -1;
        }
        String key = coilName.toLowerCase(Locale.ROOT).trim();
        int slash = key.lastIndexOf(':');
        if (slash >= 0) {
            key = key.substring(slash + 1);
        }
        key = key.replace("_coil_block", "").replace("_coil", "");
        return TEMPERATURES.getOrDefault(key, -1);
    }

    public static Set<String> names() {
        return TEMPERATURES.keySet();
    }
}
