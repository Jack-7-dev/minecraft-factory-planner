package dev.mfp.core.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mfp.core.model.MfpKey;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A plan as JSON, and back.
 *
 * <p><b>One codec, two destinations.</b> The saved file ({@code config/mfp/plans.json}) and the
 * exported plan string (M9.3) are the same object shape, written by the same code — because two
 * writers for one format drift, and the day they do it is an export that opens as a subtly different
 * plan. The file adds a wrapper with a version and a world name; the string adds compression and a
 * magic prefix ({@link PlanExport}). Neither knows anything about the fields.
 *
 * <p><b>Only what the user decided is written.</b> Lines, machine counts and rates are output,
 * re-derived by the chooser and the solver on the first solve after loading. Saving them would
 * create a second copy of the answer that could disagree with the one MFP computes next time.
 *
 * <p><b>Reading is lenient, and says what it dropped.</b> A pack renames recipes and removes
 * machines; a plan that refused to open because one pinned recipe had gone would be useless exactly
 * when it is most wanted. So a missing recipe drops its pin and the scorer chooses again, and every
 * drop is reported through {@link Problems} rather than thrown. What cannot be dropped — a target's
 * item — is the one thing that does throw, because a plan with no targets is not a plan.
 */
public final class PlanCodec {

    private PlanCodec() {}

    /** Somewhere for a lenient read to say what it could not keep. */
    public interface Problems {

        void report(String problem);

        /** Discards them, for the callers that only want the plan. */
        static Problems ignored() {
            return problem -> {};
        }

        /** Collects them into a list the caller already has. */
        static Problems collectingInto(List<String> sink) {
            return sink::add;
        }
    }

    // ------------------------------------------------------------------ writing

    public static JsonObject write(Plan plan) {
        JsonObject json = new JsonObject();
        // Only a name the user chose. An unnamed plan is named after what it makes, and writing that
        // out would freeze it — reopening the plan would find a name where there was none, and it
        // would stop following the target (M9.1).
        if (plan.isNamed()) {
            json.addProperty("name", plan.name());
        }
        if (!plan.solverModeDerived() && plan.solverMode() != SolverMode.AUTO) {
            // A derived mode belongs to lines that are not being saved; only a deliberate choice is.
            json.addProperty("solver", plan.solverMode().name().toLowerCase(Locale.ROOT));
        }

        JsonArray targets = new JsonArray();
        for (TargetOutput target : plan.targets()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("item", KeySpec.of(target.key()));
            entry.addProperty("perSecond", target.perSecond());
            targets.add(entry);
        }
        json.add("targets", targets);

        // The plan's exceptions to the standing raw materials, not the whole list (M9.4): a list
        // written out in full would go on saying "cobblestone is not free here" long after the
        // player declared that it is.
        json.add("rawMaterialsAdded", keys(plan.rawMaterialsAdded()));
        json.add("rawMaterialsRemoved", keys(plan.rawMaterialsRemoved()));
        json.add("freeItems", keys(plan.freeItems()));
        json.add("preferredItems", keys(plan.preferredItems()));
        json.add("hidden", strings(plan.blacklist()));
        // The plan's own half of the standing preferences (M8): what it blocks on top of them, and
        // what it allows in spite of them. Both are decisions, so both are written.
        json.add("blockedItems", keys(plan.blockedItems()));
        json.add("allowedItems", keys(plan.allowedItems()));
        if (!plan.byproductFeeds()) {
            // Written only when switched off, so the default can move later without every plan on
            // disk pinning the old one — the same reason the solver mode is only written when the
            // user chose it.
            json.addProperty("byproductFeeds", false);
        }
        if (!plan.tierCeiling()) {
            // Same rule and the same reason: the default is that the stated tier is a requirement,
            // and a plan on disk should not pin that decision for a version that changes it.
            json.addProperty("tierCeiling", false);
        }
        if (!plan.autoResolve()) {
            // Same rule, and it matters more here: a hand-built plan whose "the chooser stops below
            // the target" flag was lost would silently re-expand into an automatic one on reload,
            // burying every choice the user made under lines they never asked for.
            json.addProperty("autoResolve", false);
        }
        if (plan.defaultTier() != Preferences.NO_DEFAULT_TIER) {
            json.addProperty("defaultTier", plan.defaultTier());
        }
        // Recipe ids in the order the user dragged them. Display only, and saved for the same reason
        // the pins are: it is a decision, and a plan that reopened in a different order would have
        // quietly discarded it.
        json.add("displayOrder", strings(plan.displayOrder()));

        JsonObject recipeChoices = new JsonObject();
        plan.recipeChoices().forEach((key, recipeId) -> recipeChoices.addProperty(KeySpec.of(key), recipeId));
        json.add("recipeChoices", recipeChoices);

        // The other half of the same decision (M18): what the plan was told to eat, keyed by the
        // item it eats. Written unconditionally like the pins rather than only when non-empty,
        // because an empty object and an absent one read the same and the reader is lenient either
        // way — and because a sink dropped on reload would silently give the surplus back.
        JsonObject sinks = new JsonObject();
        plan.sinks().forEach((key, recipeId) -> sinks.addProperty(KeySpec.of(key), recipeId));
        json.add("sinks", sinks);

        JsonObject machineChoices = new JsonObject();
        plan.machineChoices().forEach(machineChoices::addProperty);
        json.add("machineChoices", machineChoices);

        JsonObject configs = new JsonObject();
        plan.machineConfigs().forEach((recipeId, config) -> configs.add(recipeId, write(config)));
        json.add("machineConfigs", configs);

        return json;
    }

    private static JsonObject write(MachineConfig config) {
        JsonObject json = new JsonObject();
        json.addProperty("machine", config.machineId());
        json.addProperty("tier", config.tier());
        json.addProperty("parallels", config.parallels());
        // Only when there is one. Gson drops a null property on write, so writing it unconditionally
        // makes "no limit" and "limit absent" the same file, and the reader has to guess.
        if (config.hasLimit()) {
            json.addProperty("limit", config.limit());
            json.addProperty("forceLimit", config.forceLimit());
        }

        json.add("options", OptionCodec.write(config.structureOptions()));
        return json;
    }

    private static JsonArray keys(Iterable<MfpKey> source) {
        JsonArray array = new JsonArray();
        source.forEach(key -> array.add(KeySpec.of(key)));
        return array;
    }

    private static JsonArray strings(Iterable<String> source) {
        JsonArray array = new JsonArray();
        source.forEach(array::add);
        return array;
    }

    // ------------------------------------------------------------------ reading

    public static Plan read(JsonObject json) {
        return read(json, recipeId -> true, Problems.ignored());
    }

    /**
     * Reads a plan, dropping anything this world cannot honour.
     *
     * @param knownRecipe answers whether a recipe id exists here. A plan arriving from someone
     *                    else's game names recipes this one may not have, and dropping the pin with a
     *                    word about it is the only useful answer: refusing the whole plan throws away
     *                    the ninety per cent that would have worked, and keeping the pin silently
     *                    would leave a decision on the plan that nothing can act on.
     */
    public static Plan read(JsonObject json, Predicate<String> knownRecipe, Problems problems) {
        Plan plan = new Plan();
        for (JsonElement element : array(json, "targets")) {
            JsonObject target = element.getAsJsonObject();
            plan.target(KeySpec.parse(target.get("item").getAsString()),
                    target.get("perSecond").getAsDouble());
        }
        if (plan.targets().isEmpty()) {
            throw new IllegalArgumentException("a plan with no targets is not a plan");
        }

        // After the targets, because of the one migration this needs. Every plan written before M9
        // carries a name, since plans were christened at creation from the target they started with
        // — so reading them all as *named* would leave every plan on disk stuck with the name M9.1
        // exists to unstick. A stored name identical to the one the plan would derive is
        // indistinguishable from no name at all, so it is treated as none.
        if (json.has("name") && !json.get("name").isJsonNull()) {
            String stored = json.get("name").getAsString();
            plan.name(stored.equals(plan.derivedName()) ? "" : stored);
        }

        // Files written before M9 carry the whole raw list rather than the exceptions to it. Read as
        // a difference against the standing list, which is what it always meant.
        if (json.has("rawMaterials")) {
            java.util.Set<MfpKey> saved = new java.util.LinkedHashSet<>();
            for (JsonElement element : array(json, "rawMaterials")) {
                saved.add(KeySpec.parse(element.getAsString()));
            }
            saved.forEach(plan::rawMaterial);
            // Only what was shipped when the file was written counts as a deliberate removal. An
            // entry added to the standing list since then is simply new, and must reach this plan —
            // see RawMaterials.shippedBeforeM9 for why that distinction is the whole point.
            RawMaterials.shippedBeforeM9().stream()
                    .filter(key -> !saved.contains(key))
                    .forEach(plan::clearRawMaterial);
        }
        for (JsonElement element : array(json, "rawMaterialsAdded")) {
            plan.rawMaterial(KeySpec.parse(element.getAsString()));
        }
        for (JsonElement element : array(json, "rawMaterialsRemoved")) {
            plan.clearRawMaterial(KeySpec.parse(element.getAsString()));
        }
        for (JsonElement element : array(json, "freeItems")) {
            plan.freeItem(KeySpec.parse(element.getAsString()));
        }
        for (JsonElement element : array(json, "preferredItems")) {
            plan.preferItem(KeySpec.parse(element.getAsString()));
        }
        for (JsonElement element : array(json, "hidden")) {
            plan.blacklistRecipe(element.getAsString());
        }
        for (JsonElement element : array(json, "blockedItems")) {
            plan.blockItem(KeySpec.parse(element.getAsString()));
        }
        for (JsonElement element : array(json, "allowedItems")) {
            plan.allowItem(KeySpec.parse(element.getAsString()));
        }
        if (json.has("tierCeiling")) {
            plan.tierCeiling(json.get("tierCeiling").getAsBoolean());
        }
        if (json.has("byproductFeeds")) {
            plan.byproductFeeds(json.get("byproductFeeds").getAsBoolean());
        }
        if (json.has("autoResolve")) {
            plan.autoResolve(json.get("autoResolve").getAsBoolean());
        }
        if (json.has("defaultTier")) {
            plan.defaultTier(json.get("defaultTier").getAsInt());
        }

        List<String> displayOrder = new java.util.ArrayList<>();
        for (JsonElement element : array(json, "displayOrder")) {
            // A recipe the pack has since removed costs its entry and nothing else: DisplayOrder
            // skips an id no line matches, and every unnamed line keeps the place the solve gave it.
            displayOrder.add(element.getAsString());
        }
        plan.displayOrder(displayOrder);

        object(json, "recipeChoices").entrySet().forEach(entry -> {
            String recipeId = entry.getValue().getAsString();
            if (knownRecipe.test(recipeId)) {
                plan.chooseRecipe(KeySpec.parse(entry.getKey()), recipeId);
            } else {
                problems.report("no recipe '" + recipeId + "' here, so the pin on "
                        + entry.getKey() + " was dropped");
            }
        });
        object(json, "sinks").entrySet().forEach(entry -> {
            String recipeId = entry.getValue().getAsString();
            if (knownRecipe.test(recipeId)) {
                plan.consumeWith(KeySpec.parse(entry.getKey()), recipeId);
            } else {
                problems.report("no recipe '" + recipeId + "' here, so nothing eats "
                        + entry.getKey() + " any more");
            }
        });
        object(json, "machineChoices").entrySet().forEach(entry ->
                plan.chooseMachine(entry.getKey(), entry.getValue().getAsString()));
        object(json, "machineConfigs").entrySet().forEach(entry -> {
            String recipeId = entry.getKey();
            if (knownRecipe.test(recipeId)) {
                plan.configureMachine(recipeId, readConfig(entry.getValue().getAsJsonObject()));
            } else {
                problems.report("no recipe '" + recipeId + "' here, so its machine was dropped");
            }
        });

        if (json.has("solver")) {
            try {
                plan.solverMode(SolverMode.valueOf(json.get("solver").getAsString().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // A mode this build does not have: AUTO is the right answer, not a refusal to load.
                problems.report("unknown solver mode '" + json.get("solver").getAsString()
                        + "'; this plan will choose its own");
            }
        }
        return plan;
    }

    private static MachineConfig readConfig(JsonObject json) {
        Map<String, Object> options = OptionCodec.read(object(json, "options"));
        return new MachineConfig(
                json.has("machine") && !json.get("machine").isJsonNull()
                        ? json.get("machine").getAsString() : null,
                json.has("tier") ? json.get("tier").getAsInt() : -1,
                json.has("parallels") ? json.get("parallels").getAsInt() : 1,
                limit(json),
                json.has("forceLimit") && json.get("forceLimit").getAsBoolean(),
                options);
    }

    /**
     * A machine cap, or null for none.
     *
     * <p>Null and zero are opposites here — null is "as many as it takes", zero is "you own none of
     * these, so this line makes nothing" — and defaulting an absent limit to zero silently capped
     * every line of every reloaded plan, which the matrix engine then reported as an ignored
     * inequality on lines the user had never limited.
     *
     * <p>A zero read from the file is dropped as well, which is a migration rather than a policy:
     * files written before that fix have {@code "limit": 0.0} on every config, and fixing only the
     * absent case would have left every plan already on disk broken. It costs nothing, because a cap
     * of zero machines describes a line that makes nothing and is not a plan anyone saves on purpose.
     */
    private static Double limit(JsonObject json) {
        JsonElement element = json.get("limit");
        if (element == null || element.isJsonNull()) {
            return null;
        }
        double limit = element.getAsDouble();
        return limit > 0 ? limit : null;
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
