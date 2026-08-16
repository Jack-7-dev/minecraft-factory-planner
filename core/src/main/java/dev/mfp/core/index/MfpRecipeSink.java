package dev.mfp.core.index;

import dev.mfp.core.model.MaterialForm;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpRecipe;

/**
 * Where a provider puts what it collected.
 *
 * <p>Providers depend on this rather than on the index directly, which keeps the SPI free of any
 * knowledge of how recipes are stored and lets tests capture output with a trivial fake.
 *
 * <p>{@link #skip} exists because of plan P8: one unparseable recipe out of tens of thousands must
 * never fail the whole index. A provider that cannot convert a recipe reports it here and carries
 * on, and the skip surfaces in {@code /mfp index} as a count and a reason. Silently dropping it
 * would be the failure mode we most want to avoid — the recipe would simply be missing from every
 * plan, with nothing to indicate why.
 */
public interface MfpRecipeSink {

    /** Accept a converted recipe. Later duplicates of the same id lose unless the provider outranks. */
    void recipe(MfpRecipe recipe);

    /** Accept a machine definition. */
    void machine(MfpMachine machine);

    /**
     * Record what form an item is — ore, dust, ingot, or a manufactured thing.
     *
     * <p>Optional, and answered by no provider that has no such data: an index with no forms simply
     * scores the refinement term nowhere, which is the honest outcome for a pack without GregTech.
     * The default does nothing so a provider written before this existed still compiles.
     */
    default void form(MfpKey key, MaterialForm form) {}

    /**
     * Record that a recipe could not be converted.
     *
     * @param id     the recipe's identifier in its source mod, so it can be looked up
     * @param reason short human-readable explanation
     */
    void skip(String id, String reason);
}
