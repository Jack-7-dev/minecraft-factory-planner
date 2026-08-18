package dev.mfp.core.plan;

import java.util.ArrayList;
import java.util.Collection;
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
     * Drop these lines wherever they are, by identity.
     *
     * <p>By identity rather than by recipe id, because a plan may legitimately run the same recipe
     * on two lines — two banks of the same machine — and only one of them may be the one nothing
     * demands anything from.
     *
     * <p>A subfloor left with no lines in it goes too. A {@link Subfloor} is required to have a
     * defining line and would be an illegal object without one, so leaving an empty one behind is
     * not an option; and a subfloor whose entire contents were unnecessary is itself unnecessary.
     *
     * @return how many lines were actually removed
     */
    public int remove(Collection<Line> lines) {
        if (lines.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (java.util.Iterator<LineNode> iterator = nodes.iterator(); iterator.hasNext(); ) {
            LineNode node = iterator.next();
            if (node instanceof Line line) {
                if (containsIdentical(lines, line)) {
                    iterator.remove();
                    removed++;
                }
            } else if (node instanceof Subfloor subfloor) {
                removed += subfloor.floor().remove(lines);
                if (subfloor.floor().definingLine() == null) {
                    iterator.remove();
                }
            }
        }
        return removed;
    }

    private static boolean containsIdentical(Collection<Line> lines, Line line) {
        for (Line candidate : lines) {
            if (candidate == line) {
                return true;
            }
        }
        return false;
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
