package dev.mfp.core.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An ordered list of lines, solved top to bottom.
 *
 * <p><b>Order is semantic, not cosmetic.</b> The sequential engine walks a floor once, carrying
 * outstanding demand downward, so a line can only be fed by lines <em>below</em> it. Finished goods
 * belong at the top and raw materials at the bottom. Put a smelter above the miner that feeds it and
 * the ore arrives too late to be consumed, showing up as an unsatisfied import instead.
 *
 * <p>That single-pass limitation is exactly what the matrix engine exists to remove.
 */
public final class Floor {

    private final List<LineNode> nodes = new ArrayList<>();

    public Floor() {}

    public Floor add(LineNode node) {
        nodes.add(Objects.requireNonNull(node, "node"));
        return this;
    }

    public Floor addAll(List<? extends LineNode> newNodes) {
        newNodes.forEach(this::add);
        return this;
    }

    public List<LineNode> nodes() {
        return List.copyOf(nodes);
    }

    /** Remove every node, leaving the floor empty. */
    public Floor clear() {
        nodes.clear();
        return this;
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /**
     * The line this floor stands for when it appears as a subfloor.
     *
     * <p>Factory Planner's convention: a subfloor's first line is the recipe it collapses to, and
     * the rest of the floor is how that recipe's inputs are made.
     */
    public Line definingLine() {
        for (LineNode node : nodes) {
            if (node instanceof Line line) {
                return line;
            }
        }
        return null;
    }

    /** Every line on this floor and below it, in traversal order. */
    public List<Line> allLines() {
        List<Line> lines = new ArrayList<>();
        collectLines(lines);
        return lines;
    }

    private void collectLines(List<Line> into) {
        for (LineNode node : nodes) {
            if (node instanceof Line line) {
                into.add(line);
            } else if (node instanceof Subfloor subfloor) {
                subfloor.floor().collectLines(into);
            }
        }
    }
}
