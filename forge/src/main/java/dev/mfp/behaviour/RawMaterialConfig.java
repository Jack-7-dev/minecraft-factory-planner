package dev.mfp.behaviour;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.plan.KeySpec;
import dev.mfp.core.plan.RawMaterials;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Loads the player's own infinite resources from {@code config/mfp/raw_materials.json}.
 *
 * <p>What is effectively infinite changes as a save progresses — a cobble generator makes
 * cobblestone free, a rock crusher makes gravel free, and by the late game a void miner makes most
 * ores free — so this cannot be shipped as a fixed list and cannot be inferred from the pack. MFP
 * ships water, which is true from the first minute, and this file is where the rest goes.
 *
 * <pre>
 * {
 *   "raw": [
 *     "minecraft:cobblestone",
 *     "fluid:minecraft:lava",
 *     "gtceu:rubber_log"
 *   ]
 * }
 * </pre>
 *
 * <p>Entries are item ids unless prefixed {@code fluid:}. A malformed entry costs that entry and
 * nothing else, which matters more here than usual: getting this file wrong should never stop a plan
 * from opening.
 */
public final class RawMaterialConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("MFP");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FLUID_PREFIX = "fluid:";

    private RawMaterialConfig() {}

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("mfp").resolve("raw_materials.json");
    }

    /**
     * Read the file and install what it names, replacing anything installed before.
     *
     * <p>Called on world load rather than once at startup, so editing the file and rejoining is
     * enough to see the change — the same loop a pack author already uses for behaviour overrides.
     */
    public static void reload() {
        List<MfpKey> keys = load();
        RawMaterials.install(keys);
        if (!keys.isEmpty()) {
            LOGGER.info("MFP treats {} extra item(s) as raw, from {}", keys.size(), file());
        }
    }

    /**
     * Write the player's own raw materials back, and install them.
     *
     * <p>Until M9 this file was read and never written, so a raw material declared in the GUI lived
     * until the world closed and no longer — the one decision on any MFP screen that did not
     * survive. It is written immediately for the same reason the preferences are (STATUS §8.9): four
     * item ids in a small file, against the cost of a user having to remember making the declaration
     * twice.
     *
     * <p><b>Only what the user added.</b> The shipped entries are not written out: they are what MFP
     * believes rather than what the player said, and freezing a copy of them into the file would mean
     * a later change to that list could never reach anyone who had once opened this screen.
     */
    public static void save(Collection<MfpKey> declared) {
        List<MfpKey> extras = declared.stream()
                .filter(key -> !RawMaterials.isShipped(key))
                .toList();
        RawMaterials.install(extras);

        Path path = file();
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        for (MfpKey key : extras) {
            array.add(KeySpec.of(key));
        }
        root.add("raw", array);
        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("MFP could not write raw materials to {}", path, e);
        }
    }

    /** What the player declared, as opposed to what MFP ships. */
    public static List<MfpKey> declared() {
        return RawMaterials.defaults().stream()
                .filter(key -> !RawMaterials.isShipped(key))
                .toList();
    }

    private static List<MfpKey> load() {
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                LOGGER.warn("MFP raw material file {} is not a JSON object; ignoring it", path);
                return List.of();
            }
            JsonElement array = root.getAsJsonObject().get("raw");
            if (array == null || !array.isJsonArray()) {
                LOGGER.warn("MFP raw material file {} has no 'raw' array; ignoring it", path);
                return List.of();
            }
            List<MfpKey> keys = new ArrayList<>();
            for (JsonElement element : array.getAsJsonArray()) {
                MfpKey key = parse(element, path);
                if (key != null) {
                    keys.add(key);
                }
            }
            return keys;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("MFP could not parse raw material file {}; ignoring it", path, e);
            return List.of();
        }
    }

    private static MfpKey parse(JsonElement element, Path path) {
        try {
            String id = element.getAsString().trim().toLowerCase(Locale.ROOT);
            if (id.startsWith(FLUID_PREFIX)) {
                return MfpKey.parse(id.substring(FLUID_PREFIX.length()), MfpKey.Kind.FLUID);
            }
            return MfpKey.parse(id, MfpKey.Kind.ITEM);
        } catch (RuntimeException e) {
            LOGGER.warn("MFP skipped a raw material entry in {}: {}", path, e.toString());
            return null;
        }
    }
}
