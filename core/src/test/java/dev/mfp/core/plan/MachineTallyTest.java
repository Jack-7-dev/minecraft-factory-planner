package dev.mfp.core.plan;

import dev.mfp.core.model.Confidence;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.MachineTally.MachineNeed;
import dev.mfp.core.solver.LineResult;
import dev.mfp.core.solver.SolveResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The machine tab's shopping list.
 *
 * <p>Every test here is a claim about <b>when two machines are one purchase</b>. That is the whole
 * content of the class: identity (same block or not) and arithmetic (sum then round, never the other
 * way round). The numbers are set by hand rather than solved for, because the interesting counts —
 * two fifths of a machine — are exactly the ones a tidy fixture would never produce.
 */
class MachineTallyTest {

    private static final MfpKey ORE = MfpKey.item("mfp", "ore");
    private static final MfpKey DUST = MfpKey.item("mfp", "dust");

    private static MfpRecipe recipe(String id) {
        return MfpRecipe.builder(id, "mfp:macerating", "test")
                .input(MfpIngredient.of(ORE, 1))
                .output(MfpOutput.of(DUST, 1))
                .duration(20)
                .euIn(16)
                .minTier(1)
                .build();
    }

    /** One solved line, with the only three fields this class reads set to order. */
    private static LineResult line(String recipeId, String machineId, int tier, double machineCount) {
        MachineConfig config = machineId == null ? MachineConfig.UNSET : MachineConfig.of(machineId, tier);
        Line line = new Line(recipe(recipeId), config);
        // craftsPerSecond has to be non-zero or the line reads as idle, which is its own test below.
        return new LineResult(line, 1.0, machineCount, 16, 0, 0,
                Map.of(), Map.of(), Map.of(), Confidence.EXACT, null);
    }

    private static SolveResult solved(LineResult... lines) {
        Map<Line, LineResult> byLine = new LinkedHashMap<>();
        for (LineResult result : lines) {
            byLine.put(result.line(), result);
        }
        return new SolveResult(List.of(lines), byLine, Map.of(), Map.of(), Map.of(), Map.of(),
                0, 0, 0, Confidence.EXACT, List.of(), SolverMode.SEQUENTIAL);
    }

    @Test
    @DisplayName("two lines on the same machine at the same tier are one row, rounded once")
    void sameMachineAndTierSumsBeforeRounding() {
        List<MachineNeed> needs = MachineTally.of(solved(
                line("mfp:a", "mfp:macerator", 1, 0.2),
                line("mfp:b", "mfp:macerator", 1, 0.2)));

        assertEquals(1, needs.size(), "the player crafts one kind of macerator, so there is one row");
        assertEquals(0.4, needs.get(0).count(), 1e-9, "the fractional counts add");
        assertEquals(1, needs.get(0).toBuild(),
                "rounding each line first would send the player to build two machines for 0.4 of one");
    }

    @Test
    @DisplayName("summing does not lose a machine when each line already needs most of one")
    void sumStillRoundsUpWhenEachLineIsLarge() {
        List<MachineNeed> needs = MachineTally.of(solved(
                line("mfp:a", "mfp:macerator", 1, 0.6),
                line("mfp:b", "mfp:macerator", 1, 0.6)));

        assertEquals(1.2, needs.get(0).count(), 1e-9);
        assertEquals(2, needs.get(0).toBuild(), "1.2 machines still needs two built");
    }

    @Test
    @DisplayName("the same machine at two tiers stays two rows, because they are two different items")
    void tierIsPartOfTheIdentity() {
        List<MachineNeed> needs = MachineTally.of(solved(
                line("mfp:a", "mfp:macerator", 1, 1.0),
                line("mfp:b", "mfp:macerator", 5, 3.0)));

        assertEquals(2, needs.size(), "merging them would count a block that does not exist");
        assertEquals(5, needs.get(0).tier(), "the bigger need comes first");
        assertEquals(1, needs.get(1).tier());
    }

    @Test
    @DisplayName("the biggest need comes first, since that is what a shopping list is for")
    void orderIsBiggestFirst() {
        List<MachineNeed> needs = MachineTally.of(solved(
                line("mfp:a", "mfp:small", 1, 0.5),
                line("mfp:b", "mfp:big", 1, 12.0),
                line("mfp:c", "mfp:middling", 1, 4.0)));

        assertEquals(List.of("mfp:big", "mfp:middling", "mfp:small"),
                needs.stream().map(MachineNeed::machineId).toList());
    }

    @Test
    @DisplayName("a plan with no lines needs no machines")
    void emptyPlanIsAnEmptyList() {
        assertTrue(MachineTally.of(solved()).isEmpty(), "an empty list, not a null and not a throw");
    }

    @Test
    @DisplayName("an idle line buys nothing")
    void idleLinesAreDropped() {
        LineResult idle = new LineResult(new Line(recipe("mfp:idle"), MachineConfig.of("mfp:macerator", 1)),
                0, 0, 0, 0, 0, Map.of(), Map.of(), Map.of(), Confidence.EXACT, null);
        assertTrue(idle.isIdle(), "the fixture is only interesting if the solver would call it idle");

        List<MachineNeed> needs = MachineTally.of(solved(idle, line("mfp:a", "mfp:mixer", 1, 1.0)));

        assertEquals(List.of("mfp:mixer"), needs.stream().map(MachineNeed::machineId).toList(),
                "nothing demanded the idle line's output, so it costs no machines");
    }

    @Test
    @DisplayName("a line with no machine chosen is skipped, not fatal")
    void unsetMachineDoesNotThrow() {
        List<MachineNeed> needs = MachineTally.of(solved(
                line("mfp:handmade", null, -1, 1.0),
                line("mfp:a", "mfp:mixer", 1, 2.0)));

        assertEquals(List.of("mfp:mixer"), needs.stream().map(MachineNeed::machineId).toList(),
                "MachineConfig.UNSET is a normal state, and a tab one row short beats a crash");
    }

    @Test
    @DisplayName("a running line always needs at least one machine, even with no intrinsic rate")
    void zeroRateRecipeStillNeedsOneMachine() {
        List<MachineNeed> needs = MachineTally.of(solved(line("mfp:a", "minecraft:crafting_table", -1, 0)));

        assertEquals(1, needs.get(0).toBuild(),
                "\"build 0 crafting tables, then craft in one\" is not a shopping list");
    }

    @Test
    @DisplayName("a row names the lines that asked for it, once each")
    void recipeIdsAreTheContributingLines() {
        List<MachineNeed> needs = MachineTally.of(solved(
                line("mfp:a", "mfp:macerator", 1, 1.0),
                line("mfp:b", "mfp:macerator", 1, 1.0),
                line("mfp:a", "mfp:macerator", 1, 1.0)));

        assertEquals(List.of("mfp:a", "mfp:b"), needs.get(0).recipeIds(),
                "the same recipe on two lines tells a tooltip reader nothing the once did not");
        assertEquals(3.0, needs.get(0).count(), 1e-9, "but all three lines still count towards the build");
    }
}
