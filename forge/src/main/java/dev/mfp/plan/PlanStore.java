package dev.mfp.plan;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.plan.PlanCodec;
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
import java.util.List;

/**
 * Reads and writes the user's plans, so they outlive the session that built them.
 *
 * <p><b>Only what the user decided is written.</b> A plan holds targets and choices; its lines,
 * machine counts and rates are output, re-derived by the chooser and the solver on the first solve
 * after loading. Saving them would create a second copy of the answer that could disagree with the
 * one MFP computes on the next launch, which is the failure mode this whole codebase is arranged to
 * avoid.
 *
 * <p><b>Global, with the world it came from recorded.</b> Per-world would be more precise — a plan
 * references a pack's recipe ids — but losing every plan on starting a new save is the worse failure,
 * and the recorded world name gives the warnings somewhere to point when ids stop resolving.
 *
 * <p><b>Loading is lenient, and says what it dropped.</b> A pack update renames recipes and removes
 * machines; a plan that refused to open because one pinned recipe had gone would be useless exactly
 * when it is most wanted. A missing recipe drops its pin and the scorer chooses again, a missing
 * machine drops that configuration, and every drop is logged. What cannot be dropped — a target's
 * item — keeps the plan out of the list rather than loading it half-formed.
 */
public final class PlanStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("MFP");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Bumped when the shape changes in a way a reader must know about. */
    private static final int VERSION = 1;

    private PlanStore() {}

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("mfp").resolve("plans.json");
    }

    /**
     * Write every plan, atomically.
     *
     * <p>Through a temporary file and a rename, because the alternative is a crash mid-write leaving
     * a truncated file where the user's plans used to be. An empty list still writes: "I deleted them
     * all" is a decision, and leaving the old file would resurrect them on the next launch.
     */
    public static void save(List<Plan> plans, String worldName) {
        Path path = file();
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.addProperty("world", worldName == null ? "" : worldName);
        root.addProperty("savedAt", java.time.Instant.now().toString());

        JsonArray array = new JsonArray();
        for (Plan plan : plans) {
            array.add(write(plan));
        }
        root.add("plans", array);

        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("MFP saved {} plan(s) to {}", plans.size(), path);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("MFP could not save plans to {}", path, e);
        }
    }

    /** Every plan on disk. An unreadable file costs the file and nothing else. */
    public static List<Plan> load() {
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement rootElement = JsonParser.parseReader(reader);
            if (!rootElement.isJsonObject()) {
                LOGGER.warn("MFP plan file {} is not a JSON object; ignoring it", path);
                return List.of();
            }
            JsonObject root = rootElement.getAsJsonObject();
            String world = root.has("world") ? root.get("world").getAsString() : "";

            JsonElement array = root.get("plans");
            if (array == null || !array.isJsonArray()) {
                return List.of();
            }
            List<Plan> plans = new ArrayList<>();
            for (JsonElement element : array.getAsJsonArray()) {
                try {
                    plans.add(read(element.getAsJsonObject(), world));
                } catch (RuntimeException e) {
                    // One unreadable plan costs one plan (plan P8).
                    LOGGER.warn("MFP skipped a saved plan in {}: {}", path, e.toString());
                }
            }
            LOGGER.info("MFP loaded {} plan(s) from {}{}", plans.size(), path,
                    world.isBlank() ? "" : " (saved from '" + world + "')");
            return plans;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("MFP could not read plans from {}; ignoring it", path, e);
            return List.of();
        }
    }

    // ------------------------------------------------------------------ the shape

    // Both directions live in `dev.mfp.core.plan.PlanCodec`, not here. M9's export string is the
    // same JSON as this file (PLAN.md §11), and a second copy of the field list is a second copy
    // that drifts — the day it does, an exported plan opens as a different plan from the saved one.
    // What stays here is everything about the *file*: where it is, its version wrapper, the world it
    // came from, and writing it without ever leaving a truncated one behind.

    private static JsonObject write(Plan plan) {
        return PlanCodec.write(plan);
    }

    private static Plan read(JsonObject json, String world) {
        return PlanCodec.read(json, recipeId -> true,
                problem -> LOGGER.warn("MFP, reading a plan saved from '{}': {}", world, problem));
    }
}
