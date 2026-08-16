package dev.mfp.core.index;

import dev.mfp.core.model.MaterialForm;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Every recipe MFP knows about, with the lookups a planner needs.
 *
 * <p>The index is immutable and rebuilt from scratch whenever the game's recipes change. That is
 * the right trade here: GregTech packs rewrite recipes at load through datapacks, KubeJS and
 * procedurally generated recycling, so an incrementally-patched index would be a source of subtle
 * staleness for no real gain.
 *
 * <p>Ordering is deterministic throughout — every result list is sorted by recipe id (plan P7).
 * Without that, "why did it pick that recipe?" becomes unanswerable and plans stop reproducing.
 */
public final class RecipeIndex {

    public static final RecipeIndex EMPTY = builder().build();

    private final List<MfpRecipe> all;
    private final Map<String, MfpRecipe> byId;
    private final Map<MfpKey, List<MfpRecipe>> byOutput;
    private final Map<MfpKey, List<MfpRecipe>> byInput;
    private final Map<String, MfpMachine> machinesById;
    private final Map<String, List<MfpMachine>> machinesByRecipeType;
    private final Map<MfpKey, MaterialForm> forms;
    private final IndexStats stats;

    private RecipeIndex(List<MfpRecipe> all,
                        Map<String, MfpRecipe> byId,
                        Map<MfpKey, List<MfpRecipe>> byOutput,
                        Map<MfpKey, List<MfpRecipe>> byInput,
                        Map<String, MfpMachine> machinesById,
                        Map<String, List<MfpMachine>> machinesByRecipeType,
                        Map<MfpKey, MaterialForm> forms,
                        IndexStats stats) {
        this.forms = forms;
        this.all = all;
        this.byId = byId;
        this.byOutput = byOutput;
        this.byInput = byInput;
        this.machinesById = machinesById;
        this.machinesByRecipeType = machinesByRecipeType;
        this.stats = stats;
    }

    /** Recipes that can produce {@code key}. This is what drives the "what makes this?" picker. */
    public List<MfpRecipe> producing(MfpKey key) {
        return byOutput.getOrDefault(key, List.of());
    }

    /**
     * Recipes that reference {@code key} as an input, including inputs that are required but not
     * consumed. Use {@link MfpRecipe#consumes} when the question is about material flow rather than
     * about where an item is used.
     */
    public List<MfpRecipe> consuming(MfpKey key) {
        return byInput.getOrDefault(key, List.of());
    }

    public MfpRecipe recipe(String id) {
        return byId.get(id);
    }

    public List<MfpRecipe> all() {
        return all;
    }

    public List<MfpMachine> machinesFor(String recipeTypeId) {
        return machinesByRecipeType.getOrDefault(recipeTypeId, List.of());
    }

    public MfpMachine machine(String id) {
        return machinesById.get(id);
    }

    /**
     * What form this item is, or null when nothing classified it.
     *
     * <p>Null is a real answer and not a gap to be filled in with a guess: it means the game itself
     * says nothing about the item, which is true of vanilla coal and of every mob drop, and the
     * scorer treats it as scoring nothing either way.
     */
    public MaterialForm form(MfpKey key) {
        return forms.get(key);
    }

    /** How many items were classified, for {@code /mfp index} to report. */
    public int formCount() {
        return forms.size();
    }

    public List<MfpMachine> machines() {
        return List.copyOf(machinesById.values());
    }

    public IndexStats stats() {
        return stats;
    }

    public boolean isEmpty() {
        return all.isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Collects recipes from providers and freezes them into an index.
     *
     * <p>Providers announce themselves with {@link #beginProvider} before contributing, which is
     * how the builder attributes skips and resolves duplicate recipe ids.
     */
    public static final class Builder implements MfpRecipeSink {

        private final Map<String, MfpRecipe> byId = new LinkedHashMap<>();
        private final Map<String, Integer> recipePriority = new LinkedHashMap<>();
        private final Map<String, MfpMachine> machinesById = new LinkedHashMap<>();
        private final Map<MfpKey, MaterialForm> forms = new LinkedHashMap<>();
        private final List<IndexStats.Skip> skips = new ArrayList<>();
        private final long startNanos = System.nanoTime();

        private String currentProvider = "unknown";
        private int currentPriority;
        private int overridden;

        /** Declare which provider is contributing next, and how it ranks on duplicate recipe ids. */
        public Builder beginProvider(String providerId, int priority) {
            this.currentProvider = Objects.requireNonNull(providerId, "providerId");
            this.currentPriority = priority;
            return this;
        }

        @Override
        public void recipe(MfpRecipe recipe) {
            Objects.requireNonNull(recipe, "recipe");
            Integer existingPriority = recipePriority.get(recipe.id());
            if (existingPriority != null) {
                // Ties keep the incumbent, so collection order cannot change the outcome.
                if (currentPriority <= existingPriority) {
                    overridden++;
                    return;
                }
                overridden++;
            }
            byId.put(recipe.id(), recipe);
            recipePriority.put(recipe.id(), currentPriority);
        }

        @Override
        public void machine(MfpMachine machine) {
            Objects.requireNonNull(machine, "machine");
            machinesById.putIfAbsent(machine.id(), machine);
        }

        @Override
        public void form(MfpKey key, MaterialForm form) {
            Objects.requireNonNull(key, "key");
            // First classification wins, matching the provider-priority rule for machines: a later
            // provider re-describing an item MFP already understands is not new information.
            forms.putIfAbsent(key, Objects.requireNonNull(form, "form"));
        }

        @Override
        public void skip(String id, String reason) {
            skips.add(new IndexStats.Skip(currentProvider, id, reason));
        }

        public RecipeIndex build() {
            List<MfpRecipe> all = new ArrayList<>(byId.values());
            all.sort(Comparator.comparing(MfpRecipe::id));

            Map<MfpKey, List<MfpRecipe>> byOutput = new LinkedHashMap<>();
            Map<MfpKey, List<MfpRecipe>> byInput = new LinkedHashMap<>();
            Map<String, Integer> recipesByType = new LinkedHashMap<>();
            Map<String, Integer> recipesByProvider = new LinkedHashMap<>();

            for (MfpRecipe recipe : all) {
                Set<MfpKey> outputKeys = new LinkedHashSet<>();
                for (MfpOutput output : recipe.outputs()) {
                    outputKeys.add(output.key());
                }
                for (MfpKey key : outputKeys) {
                    byOutput.computeIfAbsent(key, k -> new ArrayList<>()).add(recipe);
                }

                // A tag ingredient is registered under every candidate, so a lookup for any one of
                // them finds the recipe. The user pins a concrete choice later; the index must not.
                Set<MfpKey> inputKeys = new LinkedHashSet<>();
                for (MfpIngredient input : recipe.inputs()) {
                    inputKeys.addAll(input.candidates());
                }
                for (MfpKey key : inputKeys) {
                    byInput.computeIfAbsent(key, k -> new ArrayList<>()).add(recipe);
                }

                recipesByType.merge(recipe.recipeTypeId(), 1, Integer::sum);
                recipesByProvider.merge(recipe.providerId(), 1, Integer::sum);
            }

            Map<String, List<MfpMachine>> machinesByRecipeType = new LinkedHashMap<>();
            List<MfpMachine> machines = new ArrayList<>(machinesById.values());
            machines.sort(Comparator.comparing(MfpMachine::id));
            for (MfpMachine machine : machines) {
                for (String typeId : machine.recipeTypeIds()) {
                    machinesByRecipeType.computeIfAbsent(typeId, k -> new ArrayList<>()).add(machine);
                }
            }

            long millis = (System.nanoTime() - startNanos) / 1_000_000L;
            IndexStats stats = new IndexStats(
                    all.size(),
                    machines.size(),
                    sortedByCountDescending(recipesByType),
                    sortedByCountDescending(recipesByProvider),
                    List.copyOf(skips),
                    overridden,
                    millis);

            return new RecipeIndex(
                    List.copyOf(all),
                    Map.copyOf(byId),
                    freeze(byOutput),
                    freeze(byInput),
                    Collections.unmodifiableMap(new LinkedHashMap<>(machinesById)),
                    freeze(machinesByRecipeType),
                    Collections.unmodifiableMap(new LinkedHashMap<>(forms)),
                    stats);
        }

        private static <K, V> Map<K, List<V>> freeze(Map<K, List<V>> source) {
            Map<K, List<V>> frozen = new LinkedHashMap<>(source.size());
            source.forEach((key, values) -> frozen.put(key, List.copyOf(values)));
            return Collections.unmodifiableMap(frozen);
        }

        /** Counts descending, then name ascending, so reports read well and stay stable. */
        private static Map<String, Integer> sortedByCountDescending(Map<String, Integer> counts) {
            List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
            entries.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                    .thenComparing(Map.Entry.comparingByKey()));
            Map<String, Integer> sorted = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> entry : entries) {
                sorted.put(entry.getKey(), entry.getValue());
            }
            return sorted;
        }
    }
}
