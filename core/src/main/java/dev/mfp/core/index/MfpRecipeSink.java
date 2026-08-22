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
     * Record that an item is a <em>tiered component</em>: one of the ten cover parts or a circuit,
     * whose tier is a gate rather than a cost (M17).
     *
     * <p>The distinction this exists for. Everything else MFP knows about tier is a voltage — what a
     * recipe needs, what a machine supplies — and a voltage can be paid for with a bigger hatch. A
     * GregTech component's tier is not that. An IV emitter has a shaped crafting recipe, so a rule
     * that asks only "is there a recipe at or below your tier whose inputs you can make" concludes
     * that a player at HV can hand-craft one, which is false: the tier is in the item, not in the
     * recipe that assembles it.
     *
     * <p>Optional, and answered by no provider without such data — a pack with no GregTech simply
     * has no components and the gate applies to nothing.
     *
     * @param tier the voltage tier of the component, {@code 0} for ULV
     */
    default void componentTier(MfpKey key, int tier) {}

    /**
     * Record that a recipe could not be converted.
     *
     * @param id     the recipe's identifier in its source mod, so it can be looked up
     * @param reason short human-readable explanation
     */
    void skip(String id, String reason);
}
