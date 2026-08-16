package dev.mfp.core.plan;

/**
 * Why a line is in the plan, which determines what paces it.
 *
 * <p>The distinction is not cosmetic. A producing line runs as fast as <em>demand</em> requires; a
 * consuming line runs as fast as <em>supply</em> allows. Solving both the same way would either
 * conjure byproducts that do not exist or leave a disposal recipe idle.
 */
public enum ProductionType {
    /** Placed to make something. Paced by outstanding demand for its products. */
    PRODUCE,
    /**
     * Placed to eat a byproduct — GregTech chains generate plenty that must go somewhere. Paced by
     * how much of that byproduct is actually available upstream.
     */
    CONSUME
}
