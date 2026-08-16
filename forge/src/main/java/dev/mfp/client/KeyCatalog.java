package dev.mfp.client;

import dev.mfp.core.index.RecipeIndex;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Everything the index can make, searchable by name or by id.
 *
 * <p>What the item picker offers, and deliberately not "every item in the game": a planner target
 * the index cannot produce is a dead end, and offering one would mean the picker's happy path ends
 * in "nothing produces this". The list is therefore drawn from recipe <em>outputs</em>.
 *
 * <p>Built once per index and cached against the index instance, because it resolves a display name
 * per key — some thousands of registry and language lookups. That is fine once, alongside the
 * index build that precedes it, and far too slow to repeat on every keystroke of a search box.
 */
public final class KeyCatalog {

    /**
     * @param searchName lowercased display name, e.g. "steel ingot"
     * @param searchId   lowercased id, e.g. "gtceu:steel_ingot"
     */
    public record Entry(MfpKey key, String displayName, String searchName, String searchId) {}

    private static RecipeIndex builtFrom;
    private static List<Entry> entries = List.of();

    private KeyCatalog() {}

    /** The catalog for the current client index, building it on first use. */
    public static List<Entry> entries() {
        RecipeIndex index = ClientIndex.get();
        if (index != builtFrom) {
            entries = build(index);
            builtFrom = index;
        }
        return entries;
    }

    public static void clear() {
        builtFrom = null;
        entries = List.of();
    }

    /**
     * Entries matching {@code query}, best first, at most {@code limit} of them.
     *
     * <p>Ranked rather than merely filtered: a search for "steel" that lists three hundred things
     * containing the word, with the steel ingot somewhere in the middle, has not answered the
     * question. Exact matches come first, then names that start with the query, then the rest.
     */
    public static List<Entry> search(String query, int limit) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Entry> exact = new ArrayList<>();
        List<Entry> prefix = new ArrayList<>();
        List<Entry> contains = new ArrayList<>();

        for (Entry entry : entries()) {
            if (needle.isEmpty()) {
                contains.add(entry);
            } else if (entry.searchName().equals(needle) || entry.searchId().equals(needle)) {
                exact.add(entry);
            } else if (entry.searchName().startsWith(needle) || pathStartsWith(entry, needle)) {
                prefix.add(entry);
            } else if (entry.searchName().contains(needle) || entry.searchId().contains(needle)) {
                contains.add(entry);
            }
            if (exact.size() >= limit) {
                break;
            }
        }

        List<Entry> ranked = new ArrayList<>(exact);
        ranked.addAll(prefix);
        ranked.addAll(contains);
        return ranked.size() <= limit ? ranked : ranked.subList(0, limit);
    }

    private static boolean pathStartsWith(Entry entry, String needle) {
        int colon = entry.searchId().indexOf(':');
        return colon >= 0 && entry.searchId().startsWith(needle, colon + 1);
    }

    private static List<Entry> build(RecipeIndex index) {
        Set<MfpKey> keys = new LinkedHashSet<>();
        for (MfpRecipe recipe : index.all()) {
            for (MfpOutput output : recipe.outputs()) {
                // Pseudo-items are outputs of a sort — a generator "produces" EU — but they are not
                // things a factory is built to make, and offering them as targets invites a plan
                // whose only product is energy it also spends.
                if (output.key().kind() == MfpKey.Kind.ITEM || output.key().kind() == MfpKey.Kind.FLUID) {
                    keys.add(output.key());
                }
            }
        }

        List<Entry> built = new ArrayList<>(keys.size());
        for (MfpKey key : keys) {
            String name = KeyStacks.name(key).getString();
            built.add(new Entry(key, name, name.toLowerCase(Locale.ROOT),
                    key.id().toLowerCase(Locale.ROOT)));
        }
        built.sort((left, right) -> left.searchName().compareTo(right.searchName()));
        return List.copyOf(built);
    }
}
