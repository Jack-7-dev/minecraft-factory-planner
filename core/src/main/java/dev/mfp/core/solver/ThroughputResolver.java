package dev.mfp.core.solver;

import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.MachineConfig;

/**
 * Turns a recipe plus a machine configuration into a per-machine rate.
 *
 * <p>This is the seam where GregTech's overclocking, coil bonuses, parallel hatches and the target
 * pack's custom modifiers will plug in. The solver deliberately knows nothing about any of that: it
 * asks for crafts per second and gets an answer with a confidence attached.
 *
 * <p>Keeping it behind an interface means the solver's arithmetic can be tested against exact,
 * hand-computed rates, without dragging in tier tables or modifier chains.
 */
@FunctionalInterface
public interface ThroughputResolver {

    /** The base resolver: run the recipe exactly as written, with no overclocking or modifiers. */
    ThroughputResolver BASE = new BaseThroughputResolver();

    Throughput resolve(MfpRecipe recipe, MachineConfig machine);
}
