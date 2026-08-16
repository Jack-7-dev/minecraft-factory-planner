package dev.mfp.core.index;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What happened while building an index.
 *
 * <p>This is the regression guard the plan asks for (§14): recipe counts per type, plus every skip
 * with its reason. A GregTech or pack update that changes recipe shape shows up here as a diff
 * rather than as quietly wrong numbers downstream.
 *
 * @param recipeCount      recipes accepted into the index
 * @param machineCount     machine definitions accepted
 * @param recipesByType    accepted recipe count per recipe type, ordered by count descending
 * @param recipesByProvider accepted recipe count per contributing provider
 * @param skips            recipes a provider could not convert
 * @param overridden       recipes replaced because a higher-priority provider supplied the same id
 * @param buildMillis      wall-clock time spent building
 */
public record IndexStats(
        int recipeCount,
        int machineCount,
        Map<String, Integer> recipesByType,
        Map<String, Integer> recipesByProvider,
        List<Skip> skips,
        int overridden,
        long buildMillis) {

    public IndexStats {
        // LinkedHashMap, not Map.copyOf: these maps are pre-sorted by count and Map.copyOf gives an
        // immutable map with unspecified iteration order, which would silently scramble every report.
        recipesByType = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(recipesByType, "recipesByType")));
        recipesByProvider = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(recipesByProvider, "recipesByProvider")));
        skips = List.copyOf(Objects.requireNonNull(skips, "skips"));
    }

    /** A recipe that could not be converted, and why. */
    public record Skip(String providerId, String recipeId, String reason) {}

    public boolean isClean() {
        return skips.isEmpty();
    }
}
