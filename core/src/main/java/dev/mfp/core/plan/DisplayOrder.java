package dev.mfp.core.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The order the user arranged their lines in, which is <b>not</b> the order the solver walks.
 *
 * <p>This distinction is the whole point of the class. A floor's node order is semantic: the
 * sequential engine walks it once, carrying outstanding demand downward, so a line can only be fed
 * by lines below it ({@link Floor}, plan §8.1). Letting the user drag a row would therefore silently
 * change the answer, and would do it in the direction that looks like a bug — a plan reporting
 * imports for something it plainly produces.
 *
 * <p>So a hand order is a <em>second</em> ordering, applied at render time only, over the lines the
 * solver already produced. It is stored as a list of recipe ids because that is the only stable
 * identity a line has across a re-solve: lines are output, discarded and rebuilt on every edit
 * ({@link Plan#clearLines()}), so an index or an object reference would name nothing a moment later.
 *
 * <p>Ids may repeat — a plan can run the same recipe twice — so matching is positional within an id
 * rather than by lookup.
 */
public final class DisplayOrder {

    private DisplayOrder() {}

    /**
     * Reorders {@code items} to follow {@code handOrder}, keeping anything unlisted in place.
     *
     * <p>"In place" means relative to its neighbours in the solver's own order, not appended at the
     * end: a line the user has never touched appears where the solve put it, which is next to the
     * lines it feeds. Appending would make every re-expansion that adds a step drop that step to the
     * bottom of the table, far from the line that caused it.
     *
     * @param handOrder recipe ids in the order the user arranged them; empty for "no hand order"
     * @param items     the solver's own order
     * @param idOf      the recipe id of an item
     */
    public static <T> List<T> apply(List<String> handOrder, List<T> items, Function<T, String> idOf) {
        if (handOrder.isEmpty() || items.isEmpty()) {
            return List.copyOf(items);
        }

        Map<String, Deque<T>> byId = new HashMap<>();
        for (T item : items) {
            byId.computeIfAbsent(idOf.apply(item), key -> new ArrayDeque<>()).add(item);
        }

        List<T> result = new ArrayList<>(items.size());
        for (String id : handOrder) {
            Deque<T> queue = byId.get(id);
            if (queue != null && !queue.isEmpty()) {
                result.add(queue.poll());
            }
        }

        // Whatever the hand order did not name — a line a later expansion added, or one whose recipe
        // was replaced — goes back beside the neighbour it had in the solve.
        for (int i = 0; i < items.size(); i++) {
            T item = items.get(i);
            if (contains(result, item)) {
                continue;
            }
            int at = 0;
            for (int j = i - 1; j >= 0; j--) {
                int index = indexOf(result, items.get(j));
                if (index >= 0) {
                    at = index + 1;
                    break;
                }
            }
            result.add(at, item);
        }
        return List.copyOf(result);
    }

    /**
     * The same list with the entry at {@code index} moved by {@code delta}, or unchanged at the ends.
     *
     * <p>A swap rather than a remove-and-insert so that repeatedly pressing "up" walks a row past one
     * neighbour at a time, which is what the arrows appear to promise.
     */
    public static List<String> moved(List<String> ids, int index, int delta) {
        int target = index + delta;
        if (index < 0 || index >= ids.size() || target < 0 || target >= ids.size()) {
            return List.copyOf(ids);
        }
        List<String> moved = new ArrayList<>(ids);
        moved.set(index, ids.get(target));
        moved.set(target, ids.get(index));
        return List.copyOf(moved);
    }

    /**
     * The same list with the entry at {@code from} lifted out and dropped at {@code to}.
     *
     * <p>What a drag does, and deliberately not what {@link #moved} does: dragging a row six places
     * down is one move past six neighbours, not six swaps, and expressing it as swaps would give a
     * different answer whenever the rows in between are not distinct.
     */
    public static List<String> movedTo(List<String> ids, int from, int to) {
        if (from < 0 || from >= ids.size() || to < 0 || to >= ids.size() || from == to) {
            return List.copyOf(ids);
        }
        List<String> moved = new ArrayList<>(ids);
        moved.add(to, moved.remove(from));
        return List.copyOf(moved);
    }

    /** Reference identity, because two lines of the same recipe are different lines. */
    private static <T> boolean contains(List<T> list, T item) {
        return indexOf(list, item) >= 0;
    }

    private static <T> int indexOf(List<T> list, T item) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == item) {
                return i;
            }
        }
        return -1;
    }
}
