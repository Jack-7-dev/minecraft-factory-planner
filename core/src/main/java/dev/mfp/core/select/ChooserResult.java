package dev.mfp.core.select;

import dev.mfp.core.model.MfpKey;
import dev.mfp.core.plan.Line;

import java.util.List;
import java.util.Objects;

/**
 * What the chooser worked out: the lines to solve, and what it could not settle.
 *
 * <p>{@code cycles} is the field that matters most. The sequential engine reports "imported despite
 * being produced" for two quite different problems — a genuine loop, and lines emitted in the wrong
 * order — and it cannot tell them apart. The chooser can: it tracks the recipe path as it descends,
 * so re-entering a recipe already on that path <em>is</em> a loop, observed rather than inferred.
 * Deciding {@link #requiresMatrixSolver()} here rather than from the solver's warning is what stops
 * a bug in line ordering from masquerading as a modelling limitation.
 *
 * @param lines           lines in solve order: consumers before the lines that feed them
 * @param cycles          recipe-id paths that close a loop, one entry per detected cycle
 * @param unresolved      keys nothing in the index makes, so the plan must import them
 * @param rawMaterials    keys expansion deliberately stopped at
 * @param truncatedAt     keys abandoned because expansion hit its depth limit
 * @param avoidedForCycles recipe ids expansion steered around because they closed a loop.
 *                         <b>Deliberately not a warning</b> (M9.12): steering around a loop is
 *                         ordinary work, the list runs to hundreds of chemical recipes the plan
 *                         never wanted, and it said nothing the plan's own imports do not — where
 *                         avoidance actually cost the plan something, {@code importReasons} names
 *                         that item and how many of its recipes were passed over. Kept on the result
 *                         because {@code /mfp explain} and the picker can still answer "why not this
 *                         recipe?" from it
 * @param importReasons   why a key had to be imported, when the cause is something the user decided
 * @param byproductFeeds  items the plan was re-expanded to consume rather than throw away (M11.1),
 *                        which is worth reporting because it is the chooser having changed its mind
 *                        about a recipe for a reason the first walk could not have seen
 */
public record ChooserResult(
        List<Line> lines,
        List<List<String>> cycles,
        List<MfpKey> unresolved,
        List<MfpKey> rawMaterials,
        List<MfpKey> truncatedAt,
        List<String> avoidedForCycles,
        java.util.Map<MfpKey, String> importReasons,
        List<MfpKey> byproductFeeds) {

    public ChooserResult {
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        cycles = List.copyOf(Objects.requireNonNull(cycles, "cycles"));
        unresolved = List.copyOf(Objects.requireNonNull(unresolved, "unresolved"));
        rawMaterials = List.copyOf(Objects.requireNonNull(rawMaterials, "rawMaterials"));
        truncatedAt = List.copyOf(Objects.requireNonNull(truncatedAt, "truncatedAt"));
        avoidedForCycles = List.copyOf(Objects.requireNonNull(avoidedForCycles, "avoidedForCycles"));
        importReasons = java.util.Map.copyOf(Objects.requireNonNull(importReasons, "importReasons"));
        byproductFeeds = List.copyOf(Objects.requireNonNull(byproductFeeds, "byproductFeeds"));
    }

    /** Without the byproduct-feeding record, which is every caller before M11.1. */
    public ChooserResult(List<Line> lines, List<List<String>> cycles, List<MfpKey> unresolved,
                         List<MfpKey> rawMaterials, List<MfpKey> truncatedAt,
                         List<String> avoidedForCycles,
                         java.util.Map<MfpKey, String> importReasons) {
        this(lines, cycles, unresolved, rawMaterials, truncatedAt, avoidedForCycles, importReasons,
                List.of());
    }

    /** Without any user-caused import reasons, which is what every caller before M8 built. */
    public ChooserResult(List<Line> lines, List<List<String>> cycles, List<MfpKey> unresolved,
                         List<MfpKey> rawMaterials, List<MfpKey> truncatedAt,
                         List<String> avoidedForCycles) {
        this(lines, cycles, unresolved, rawMaterials, truncatedAt, avoidedForCycles, java.util.Map.of());
    }

    /** The same result, recording which recipes were skipped to reach it. */
    public ChooserResult withAvoided(List<String> avoided) {
        return new ChooserResult(lines, cycles, unresolved, rawMaterials, truncatedAt, avoided,
                importReasons, byproductFeeds);
    }

    /** The same result, recording which leftovers it was re-expanded to consume (M11.1). */
    public ChooserResult withByproductFeeds(List<MfpKey> fed) {
        return new ChooserResult(lines, cycles, unresolved, rawMaterials, truncatedAt,
                avoidedForCycles, importReasons, fed);
    }

    /**
     * Whether this plan needs the matrix engine.
     *
     * <p>True exactly when a real loop was observed during expansion. A top-down pass cannot close a
     * loop however the lines are ordered.
     */
    public boolean requiresMatrixSolver() {
        return !cycles.isEmpty();
    }

    public boolean isComplete() {
        return cycles.isEmpty() && unresolved.isEmpty() && truncatedAt.isEmpty();
    }

    /** Human-readable problems, for the command layer and later the GUI. */
    public List<String> warnings() {
        return warnings(false);
    }

    /**
     * The same problems, phrased for what actually happened to the loop.
     *
     * <p>A loop the matrix engine closed is not a problem, it is a fact about the factory — and
     * telling a user to "use the matrix solver" on a plan the status bar says was solved by the
     * matrix solver is the kind of contradiction that makes every other warning less believable.
     *
     * @param loopClosed whether the engine that produced the numbers can resolve loops
     */
    public List<String> warnings(boolean loopClosed) {
        List<String> warnings = new java.util.ArrayList<>();
        for (List<String> cycle : cycles) {
            warnings.add("production loop: " + String.join(" -> ", cycle)
                    + (loopClosed
                            ? " - closed by the whole-plan engine"
                            : " - the sequential engine cannot resolve this, use the matrix or "
                                    + "simplex solver"));
        }
        List<MfpKey> unexplained = new java.util.ArrayList<>(unresolved);
        unexplained.removeAll(importReasons.keySet());
        if (!unexplained.isEmpty()) {
            warnings.add("nothing in the index produces: " + unexplained);
        }
        // Reported apart from the above because they are different reports: one is a fact about the
        // pack and the other is the consequence of a decision the user made and can take back.
        importReasons.forEach((key, reason) ->
                warnings.add("imported " + key + ": " + reason));
        if (!truncatedAt.isEmpty()) {
            warnings.add("expansion stopped at the depth limit for: " + truncatedAt);
        }
        return warnings;
    }
}
