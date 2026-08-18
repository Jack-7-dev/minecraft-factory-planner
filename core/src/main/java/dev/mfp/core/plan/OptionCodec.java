package dev.mfp.core.plan;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one reading and writing of {@code structureOptions} on disk.
 *
 * <p>Shared because the same map is now stored in two files — inside a plan, where it describes one
 * line's machine, and inside the preferences, where it describes a machine the player owns. Two
 * copies of this would be two chances to normalise a number differently, and the failure that makes
 * is silent: a coil written by one and read by the other compares unequal, so a build reloads as a
 * build the player did not describe.
 */
public final class OptionCodec {

    private OptionCodec() {
    }

    public static JsonObject write(Map<String, Object> options) {
        JsonObject json = new JsonObject();
        options.forEach((name, value) -> {
            if (value instanceof Number number) {
                json.addProperty(name, number);
            } else if (value instanceof Boolean flag) {
                json.addProperty(name, flag);
            } else if (value != null) {
                json.addProperty(name, String.valueOf(value));
            }
        });
        return json;
    }

    /**
     * Read them back as the same types they were written as.
     *
     * <p>Numbers are normalised to Integer or Double rather than left as gson's own lazy number.
     * Everything that <em>reads</em> an option goes through {@code Number}, so the arithmetic was
     * never affected — but a config read back from a file compared unequal to the one written, which
     * is exactly the claim M9's round trip makes.
     */
    public static Map<String, Object> read(JsonObject options) {
        Map<String, Object> read = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : options.entrySet()) {
            JsonElement value = entry.getValue();
            if (!(value instanceof JsonPrimitive primitive)) {
                continue;
            }
            if (primitive.isNumber()) {
                double number = primitive.getAsDouble();
                read.put(entry.getKey(),
                        number == Math.rint(number) && Math.abs(number) <= Integer.MAX_VALUE
                                ? (Object) (int) number
                                : (Object) number);
            } else if (primitive.isBoolean()) {
                read.put(entry.getKey(), primitive.getAsBoolean());
            } else {
                read.put(entry.getKey(), primitive.getAsString());
            }
        }
        return read;
    }
}
