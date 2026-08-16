package dev.mfp.provider;

import dev.mfp.core.index.MfpRecipeSink;

/**
 * Source of recipes for one mod or ecosystem.
 *
 * <p>This is the seam that keeps MFP from being a GregTech-only tool. GregTech is the first and most
 * important implementation, but nothing above this interface knows that: the index, the solvers and
 * the UI see only {@code MfpRecipe} and {@code MfpMachine}.
 *
 * <p>Implementations must be fail-soft. A provider that throws aborts its own collection; one that
 * reports a bad recipe through {@link MfpRecipeSink#skip} loses one recipe and keeps the rest
 * (plan P8). Prefer the latter — with tens of thousands of recipes, one malformed entry should cost
 * one entry.
 */
public interface MfpRecipeProvider {

    /** Stable identifier, e.g. {@code vanilla} or {@code gtceu}. Appears in reports and on recipes. */
    String id();

    /**
     * Rank used when two providers supply the same recipe id; higher wins, ties keep the incumbent.
     *
     * <p>This matters because GregTech proxies some vanilla recipes into its own machine types. The
     * mod that models a recipe most richly should outrank the one that models it thinly.
     */
    int priority();

    /** Whether this provider can run — normally a mod-loaded check. */
    boolean isAvailable();

    /** Convert everything this provider knows about into {@code sink}. */
    void collect(MfpRecipeSink sink, CollectionContext context);
}
