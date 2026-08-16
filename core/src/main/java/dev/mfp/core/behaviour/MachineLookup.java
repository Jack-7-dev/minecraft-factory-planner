package dev.mfp.core.behaviour;

import dev.mfp.core.model.MfpMachine;

/**
 * Resolves a machine id to its definition.
 *
 * <p>A plan stores the machine it chose by id, not by reference, so that it can be saved and
 * reloaded against a possibly different index. Behaviours need the definition — its tier, whether it
 * is a multiblock, which modifiers it declares — so something has to bridge the two, and an
 * interface keeps {@code core} from depending on the index.
 */
@FunctionalInterface
public interface MachineLookup {

    /** Knows about no machines; every plan resolves as unconfigured. */
    MachineLookup NONE = id -> null;

    /** The machine with this id, or null when it is not in the index. */
    MfpMachine machine(String id);
}
