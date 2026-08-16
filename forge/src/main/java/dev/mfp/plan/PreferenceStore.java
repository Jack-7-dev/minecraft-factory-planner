package dev.mfp.plan;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.plan.KeySpec;
import dev.mfp.core.plan.Preferences;
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
import java.util.Map;

/**
 * The player's standing preferences on disk, at {@code config/mfp/preferences.json}.
 *
 * <p>Beside the plans rather than inside one, because that is what makes them standing: a default
 * recipe belongs to the player and not to the plan that happened to establish it. Global for the
 * same reason {@link PlanStore} is — losing them on starting a new save is the worse failure.
 *
 * <pre>
 * {
 *   "defaultTier": 3,
 *   "autoResolve": true,
 *   "recipes":  { "gtceu:steel_ingot": "gtceu:electric_blast_furnace/steel" },
 *   "items":    [ "minecraft:spruce_log" ],
 *   "blocked":  [ "mysticalagriculture:inferium_essence" ]
 * }
 * </pre>
 *
 * <p>Hand-editable, in the same {@code [fluid:]namespace:path} spelling {@code /mfp plan} accepts.
 * <b>Loading is lenient</b> exactly as plan loading is: a preference naming a recipe this pack no
 * longer has costs that entry, and the scorer chooses again. A preference file that refused to load
 * would take four unrelated decisions with it.
 */
public final class PreferenceStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("MFP");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Preferences current = Preferences.none();
    private static boolean loaded;

    private PreferenceStore() {}

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("mfp").resolve("preferences.json");
    }

    /**
     * The live preferences, loaded on first use.
     *
     * <p>One instance, handed to every chooser: the GUI edits it in place and {@link #save()} writes
     * it back, so there is never a second copy to disagree with the file.
     */
    public static Preferences get() {
        if (!loaded) {
            reload();
        }
        return current;
    }

    /** Re-read the file, replacing what is held. */
    public static Preferences reload() {
        loaded = true;
        current = load();
        if (!current.isEmpty()) {
            LOGGER.info("MFP loaded standing preferences from {}: {} default recipe(s), "
                            + "{} preferred item(s), {} blocked item(s), tier {}",
                    file(), current.defaultRecipes().size(), current.preferredItems().size(),
                    current.blockedItems().size(),
                    current.defaultTier() == Preferences.NO_DEFAULT_TIER ? "unset" : current.defaultTier());
        }
        return current;
    }

    /** Write the current preferences out, atomically. */
    public static void save() {
        if (!loaded) {
            // Never read, so nothing has been decided this session: writing would replace a file this
            // process has not seen.
            return;
        }
        Path path = file();
        JsonObject root = new JsonObject();
        if (current.defaultTier() != Preferences.NO_DEFAULT_TIER) {
            root.addProperty("defaultTier", current.defaultTier());
        }
        if (current.autoResolve()) {
            // Written only when switched on, because off is the default and a file that pinned it
            // would go on saying so after the default moves (M11.3).
            root.addProperty("autoResolve", true);
        }

        JsonObject recipes = new JsonObject();
        current.defaultRecipes().forEach((key, recipeId) -> recipes.addProperty(spec(key), recipeId));
        root.add("recipes", recipes);
        root.add("items", keys(current.preferredItems()));
        root.add("blocked", keys(current.blockedItems()));

        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("MFP could not save preferences to {}", path, e);
        }
    }

    private static Preferences load() {
        Preferences preferences = Preferences.none();
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return preferences;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement rootElement = JsonParser.parseReader(reader);
            if (!rootElement.isJsonObject()) {
                LOGGER.warn("MFP preference file {} is not a JSON object; ignoring it", path);
                return preferences;
            }
            JsonObject root = rootElement.getAsJsonObject();
            if (root.has("defaultTier")) {
                preferences.defaultTier(root.get("defaultTier").getAsInt());
            }
            if (root.has("autoResolve")) {
                preferences.autoResolve(root.get("autoResolve").getAsBoolean());
            }
            for (Map.Entry<String, JsonElement> entry : object(root, "recipes").entrySet()) {
                try {
                    preferences.defaultRecipe(KeySpec.parse(entry.getKey()), entry.getValue().getAsString());
                } catch (RuntimeException e) {
                    LOGGER.warn("MFP skipped a default recipe for '{}': {}", entry.getKey(), e.toString());
                }
            }
            for (JsonElement element : array(root, "items")) {
                key(element).ifPresent(preferences::preferItem);
            }
            for (JsonElement element : array(root, "blocked")) {
                key(element).ifPresent(preferences::blockItem);
            }
            return preferences;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("MFP could not read preferences from {}; ignoring it", path, e);
            return Preferences.none();
        }
    }

    private static java.util.Optional<MfpKey> key(JsonElement element) {
        try {
            return java.util.Optional.of(KeySpec.parse(element.getAsString()));
        } catch (RuntimeException e) {
            LOGGER.warn("MFP skipped a preference entry: {}", e.toString());
            return java.util.Optional.empty();
        }
    }

    private static JsonArray keys(Iterable<MfpKey> source) {
        JsonArray array = new JsonArray();
        source.forEach(key -> array.add(spec(key)));
        return array;
    }

    /** The spelling {@link KeySpec} parses and the commands accept. */
    private static String spec(MfpKey key) {
        return KeySpec.of(key);
    }

    private static JsonArray array(JsonObject json, String name) {
        JsonElement element = json.get(name);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static JsonObject object(JsonObject json, String name) {
        JsonElement element = json.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }
}
