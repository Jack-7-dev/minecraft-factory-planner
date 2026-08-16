package dev.mfp.core.plan;

/** An entry on a floor: either a single {@link Line} or a nested {@link Subfloor}. */
public sealed interface LineNode permits Line, Subfloor {}
