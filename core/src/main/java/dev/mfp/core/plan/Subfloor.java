package dev.mfp.core.plan;

import java.util.Objects;

/**
 * A nested floor, standing in for its defining line.
 *
 * <p>Subfloors are how a large plan stays readable: "make steel" is one row on the parent floor,
 * and opening it reveals the whole sub-chain. To the solver a subfloor behaves like a composite
 * recipe — it receives a slice of the parent's demand, solves internally, and reports its net
 * imports and exports back up.
 */
public record Subfloor(Floor floor) implements LineNode {

    public Subfloor {
        Objects.requireNonNull(floor, "floor");
        if (floor.definingLine() == null) {
            throw new IllegalArgumentException("a subfloor must contain at least one line to stand for");
        }
    }

    /** The recipe this subfloor collapses to on the parent floor. */
    public Line definingLine() {
        return floor.definingLine();
    }
}
