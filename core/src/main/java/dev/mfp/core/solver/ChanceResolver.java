package dev.mfp.core.solver;

import dev.mfp.core.model.ChanceMode;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpOutput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Expected yield per craft for a recipe's outputs, resolving competing chance groups properly.
 *
 * <p>M3 modelled every output as {@code amount × chance} and admitted this was an over-estimate for
 * the modes where outputs compete. It is: three of GregTech's five chance logics couple the members
 * of a group, so a member's expectation depends on its siblings and cannot be read off the member
 * alone. Summing per-item expectations across such a group invents material.
 *
 * <p>Ported from the fork's {@code ChanceLogic}, one rule per mode:
 *
 * <ul>
 *   <li><b>ALWAYS / INDEPENDENT</b> ({@code OR}) — each output rolls on its own:
 *       {@code E = amount × p}.
 *   <li><b>ALL_OR_NOTHING</b> ({@code AND}) — the group emits only when every member passes, so
 *       {@code E_i = amount_i × ∏ p_j}. With three members at 50% that is 12.5% each, not 50%.
 *   <li><b>FIRST_ONLY</b> ({@code FIRST}) — the first member to pass is the only one produced, so
 *       later members are conditional on the earlier ones failing:
 *       {@code E_i = amount_i × p_i × ∏_{j<i} (1 − p_j)}. Order within the group is therefore
 *       semantic, which is why it is preserved all the way from ingestion.
 *   <li><b>EXCLUSIVE</b> ({@code XOR}) — GregTech renormalises the group's chances to sum to one
 *       and then selects exactly one member, so {@code E_i = amount_i × p_i / Σ p_j}. Note that
 *       this <em>scales up</em> a group whose chances sum to less than one: XOR always produces
 *       something.
 * </ul>
 *
 * <p>All four are exact, so a plan built on chanced GregTech recipes no longer has to carry a
 * blanket "this may be an over-estimate" caveat. What remains approximate is stated per output by
 * {@link Resolved#exact()}: an {@code AND} group is modelled as independent rolls, but GregTech
 * accumulates leftover chance between crafts, which correlates them.
 */
public final class ChanceResolver {

    private ChanceResolver() {}

    /**
     * One output's expected yield.
     *
     * @param key      what is produced
     * @param perCraft expected amount per craft, averaged over the chance rolls
     * @param exact    whether the expectation is exact rather than a stated approximation
     */
    public record Resolved(MfpKey key, double perCraft, boolean exact) {}

    /** Expected yields for a recipe's outputs, run at its base tier. */
    public static List<Resolved> resolve(List<MfpOutput> outputs) {
        return resolve(outputs, 0);
    }

    /**
     * Expected yields when the recipe is overclocked {@code overclocks} times.
     *
     * <p>The overclock count matters because GregTech raises chanced yields with it. Passing zero —
     * what an unconfigured plan does — gives the base chances, so the answer errs low rather than
     * flattering the machine.
     */
    public static List<Resolved> resolve(List<MfpOutput> outputs, int overclocks) {
        if (outputs.isEmpty()) {
            return List.of();
        }

        // Grouped modes resolve together; everything else is independent and can be read directly.
        Map<String, List<MfpOutput>> groups = new LinkedHashMap<>();
        List<Resolved> resolved = new ArrayList<>(outputs.size());

        for (MfpOutput output : outputs) {
            if (output.mode().isGrouped()) {
                groups.computeIfAbsent(groupKeyOf(output), k -> new ArrayList<>()).add(output);
            } else {
                resolved.add(new Resolved(output.key(),
                        output.amount() * output.chanceAt(overclocks), true));
            }
        }

        groups.values().forEach(group -> resolveGroup(group, overclocks, resolved));
        return resolved;
    }

    /**
     * The group an output belongs to.
     *
     * <p>Outputs with no explicit group key still group by mode, because a recipe that declares
     * {@code XOR} declares it for the whole capability rather than per item. Falling back to the
     * mode keeps a converter that omitted the key from silently turning a competing group into a
     * set of independent rolls, which would over-count.
     */
    private static String groupKeyOf(MfpOutput output) {
        return output.groupKey() != null ? output.mode() + "/" + output.groupKey() : output.mode().name();
    }

    private static void resolveGroup(List<MfpOutput> group, int overclocks, List<Resolved> into) {
        ChanceMode mode = group.get(0).mode();

        if (group.size() == 1) {
            // A group of one degenerates to an ordinary independent roll under every mode: there is
            // nothing to compete with, nothing to renormalise, and no sibling to be conditional on.
            MfpOutput only = group.get(0);
            into.add(new Resolved(only.key(), only.amount() * only.chanceAt(overclocks), true));
            return;
        }

        switch (mode) {
            case ALL_OR_NOTHING -> resolveAllOrNothing(group, overclocks, into);
            case FIRST_ONLY -> resolveFirstOnly(group, overclocks, into);
            case EXCLUSIVE -> resolveExclusive(group, overclocks, into);
            default -> group.forEach(output -> into.add(
                    new Resolved(output.key(), output.amount() * output.chanceAt(overclocks), true)));
        }
    }

    private static void resolveAllOrNothing(List<MfpOutput> group, int overclocks, List<Resolved> into) {
        double all = 1.0;
        for (MfpOutput output : group) {
            all *= output.chanceAt(overclocks);
        }
        for (MfpOutput output : group) {
            // Not exact: GregTech carries leftover chance between crafts, so the members' rolls are
            // correlated rather than independent. Treating them as independent is the conservative
            // reading — it cannot over-state the yield.
            into.add(new Resolved(output.key(), output.amount() * all, false));
        }
    }

    private static void resolveFirstOnly(List<MfpOutput> group, int overclocks, List<Resolved> into) {
        double reachedHere = 1.0;
        for (MfpOutput output : group) {
            double chance = output.chanceAt(overclocks);
            into.add(new Resolved(output.key(), output.amount() * chance * reachedHere, true));
            reachedHere *= 1.0 - chance;
        }
    }

    private static void resolveExclusive(List<MfpOutput> group, int overclocks, List<Resolved> into) {
        double total = 0.0;
        for (MfpOutput output : group) {
            total += output.chanceAt(overclocks);
        }
        if (total <= ItemFlows.EPSILON) {
            group.forEach(output -> into.add(new Resolved(output.key(), 0.0, true)));
            return;
        }
        for (MfpOutput output : group) {
            into.add(new Resolved(output.key(),
                    output.amount() * output.chanceAt(overclocks) / total, true));
        }
    }
}
