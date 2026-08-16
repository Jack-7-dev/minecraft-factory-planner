package dev.mfp.behaviour;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mfp.core.behaviour.BehaviourOverride;
import dev.mfp.core.behaviour.BehaviourRegistry;
import dev.mfp.core.model.Confidence;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Loads machine behaviour overrides from {@code config/mfp/behaviours/*.json}.
 *
 * <p>The point of this file is that MFP can be wrong about a machine and still be fixable without a
 * new release. The target pack is a fork with dozens of bespoke multiblocks and more will arrive;
 * a pack author who can measure a machine in game should be able to write the numbers down.
 *
 * <p>Datapack-shaped on purpose — one JSON file per concern, several files merged — so a pack could
 * eventually ship these itself rather than asking players to hand-edit a config.
 *
 * <pre>
 * {
 *   "overrides": [
 *     {
 *       "machine": "start_core:super_*",
 *       "duration": 1.6,
 *       "eut": 0.95,
 *       "content": 4,
 *       "confidence": "APPROXIMATE",
 *       "note": "measured on a super macerator, 2026-08"
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p>A malformed file costs that file and nothing else. Refusing to start, or silently loading half
 * a file, would both be worse than one logged warning and a plan that falls back to the built-in
 * behaviour.
 */
public final class BehaviourConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("MFP");

    private BehaviourConfig() {}

    public static Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve("mfp").resolve("behaviours");
    }

    /** A registry with the shipped behaviours plus whatever the config directory adds. */
    public static BehaviourRegistry loadRegistry() {
        BehaviourRegistry registry = BehaviourRegistry.standard();
        List<BehaviourOverride> overrides = load();
        if (!overrides.isEmpty()) {
            registry.overrides(overrides);
            LOGGER.info("MFP loaded {} machine behaviour override(s) from {}",
                    overrides.size(), directory());
        }
        return registry;
    }

    /** Every override in the config directory, in file-name order so the result is reproducible. */
    public static List<BehaviourOverride> load() {
        Path directory = directory();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }

        List<BehaviourOverride> overrides = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(path -> overrides.addAll(loadFile(path)));
        } catch (IOException e) {
            LOGGER.warn("MFP could not read behaviour overrides from {}", directory, e);
        }
        return overrides;
    }

    private static List<BehaviourOverride> loadFile(Path path) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                LOGGER.warn("MFP behaviour file {} is not a JSON object; ignoring it", path);
                return List.of();
            }
            JsonElement array = root.getAsJsonObject().get("overrides");
            if (array == null || !array.isJsonArray()) {
                LOGGER.warn("MFP behaviour file {} has no 'overrides' array; ignoring it", path);
                return List.of();
            }
            return parse(array.getAsJsonArray(), path);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("MFP could not parse behaviour file {}; ignoring it", path, e);
            return List.of();
        }
    }

    private static List<BehaviourOverride> parse(JsonArray array, Path path) {
        List<BehaviourOverride> overrides = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                LOGGER.warn("MFP behaviour file {} has a non-object entry; skipping it", path);
                continue;
            }
            try {
                overrides.add(parse(element.getAsJsonObject()));
            } catch (RuntimeException e) {
                // One bad entry costs one machine, not the file (plan P8).
                LOGGER.warn("MFP skipped a behaviour override in {}: {}", path, e.toString());
            }
        }
        return overrides;
    }

    private static BehaviourOverride parse(JsonObject json) {
        String machine = json.get("machine").getAsString();
        double duration = optionalDouble(json, "duration", 1.0);
        double eut = optionalDouble(json, "eut", 1.0);
        double content = optionalDouble(json, "content", 1.0);
        String note = json.has("note") ? json.get("note").getAsString() : null;

        // Defaults to approximate: an override is someone's measurement, and claiming certainty on
        // its behalf would defeat the point of tracking confidence at all (plan P5).
        Confidence confidence = Confidence.APPROXIMATE;
        if (json.has("confidence")) {
            confidence = Confidence.valueOf(json.get("confidence").getAsString().toUpperCase(Locale.ROOT));
        }

        return new BehaviourOverride(machine, duration, eut, content, confidence, note);
    }

    private static double optionalDouble(JsonObject json, String key, double fallback) {
        return json.has(key) ? json.get(key).getAsDouble() : fallback;
    }
}
