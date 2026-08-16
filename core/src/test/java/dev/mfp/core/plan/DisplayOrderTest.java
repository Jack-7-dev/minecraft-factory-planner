package dev.mfp.core.plan;

import dev.mfp.core.index.RecipeIndex;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.select.RecipeChooser;
import dev.mfp.core.solver.SequentialSolver;
import dev.mfp.core.solver.SolveResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hand order (M7.6), and the one property it must never break.
 *
 * <p>A user-facing order is a <em>second</em> ordering. The floor's own order is topological and the
 * sequential engine depends on it (plan §8.1), so the interesting assertion here is not that the
 * rows move — it is that the lines do not.
 */
class DisplayOrderTest {

    private static final MfpKey ORE = MfpKey.item("mfp", "ore");
    private static final MfpKey INGOT = MfpKey.item("mfp", "ingot");
    private static final MfpKey PLATE = MfpKey.item("mfp", "plate");

    private static MfpRecipe recipe(String id, MfpKey in, MfpKey out) {
        return MfpRecipe.builder(id, "mfp:machine", "test")
                .input(MfpIngredient.of(in, 1))
                .output(MfpOutput.of(out, 1))
                .duration(20)
                .euIn(16)
                .minTier(1)
                .build();
    }

    private static RecipeIndex index() {
        RecipeIndex.Builder builder = RecipeIndex.builder();
        builder.beginProvider("test", 0);
        builder.recipe(recipe("mfp:plate", INGOT, PLATE));
        builder.recipe(recipe("mfp:ingot", ORE, INGOT));
        builder.machine(new MfpMachine("mfp:lv_machine", "LV Machine", 1, 32,
                List.of("mfp:machine"), false, List.of(), "test"));
        return builder.build();
    }

    @Test
    @DisplayName("a hand order round-trips, and the solver still sees topological order")
    void handOrderDoesNotMoveTheLines() {
        RecipeIndex index = index();
        Plan plan = new Plan("test").target(PLATE, 1).rawMaterial(ORE);
        new RecipeChooser(index).expandInto(plan);

        // The chooser emits consumers above producers, so the plate line comes first.
        assertEquals(List.of("mfp:plate", "mfp:ingot"),
                plan.allLines().stream().map(line -> line.recipe().id()).toList());

        plan.displayOrder(List.of("mfp:ingot", "mfp:plate"));
        assertEquals(List.of("mfp:ingot", "mfp:plate"), plan.displayOrder());

        // The lines themselves have not moved, which is what keeps the sequential engine correct.
        assertEquals(List.of("mfp:plate", "mfp:ingot"),
                plan.allLines().stream().map(line -> line.recipe().id()).toList());

        SolveResult solved = new SequentialSolver().solve(plan);
        assertEquals(List.of("mfp:plate", "mfp:ingot"),
                solved.lines().stream().map(result -> result.line().recipe().id()).toList());
        assertTrue(solved.isComplete(), "the plan still makes its target");

        // Only the rendered order changes.
        assertEquals(List.of("mfp:ingot", "mfp:plate"),
                DisplayOrder.apply(plan.displayOrder(), solved.lines(),
                                result -> result.line().recipe().id()).stream()
                        .map(result -> result.line().recipe().id()).toList());
    }

    @Test
    @DisplayName("a hand order survives clearing the lines, and copies with the plan")
    void handOrderIsUserIntent() {
        Plan plan = new Plan("test").target(PLATE, 1).displayOrder(List.of("mfp:ingot", "mfp:plate"));
        plan.clearLines();
        assertEquals(List.of("mfp:ingot", "mfp:plate"), plan.displayOrder());
        assertEquals(List.of("mfp:ingot", "mfp:plate"), plan.copy("copy").displayOrder());
    }

    @Test
    @DisplayName("a line the hand order does not name keeps its place beside its neighbours")
    void unnamedLinesStayWhereTheSolvePutThem() {
        // "c" was added by a later expansion, between b and d. Appending it would drop the new step
        // to the bottom of the table, away from the line that caused it.
        List<String> solved = List.of("a", "b", "c", "d");
        assertEquals(List.of("d", "b", "c", "a"),
                DisplayOrder.apply(List.of("d", "b", "a"), solved, Function.identity()));

        // Nothing named at all is the solver's own order, unchanged.
        assertEquals(solved, DisplayOrder.apply(List.of(), solved, Function.identity()));

        // A hand order naming a recipe this solve no longer contains costs that entry alone: b and a
        // still move, and c and d still follow the neighbour they had in the solve.
        assertEquals(List.of("b", "c", "d", "a"),
                DisplayOrder.apply(List.of("b", "gone", "a"), solved, Function.identity()));
    }

    @Test
    @DisplayName("moving swaps with the neighbour, and does nothing at the ends")
    void movingIsASwap() {
        List<String> order = List.of("a", "b", "c");
        assertEquals(List.of("b", "a", "c"), DisplayOrder.moved(order, 1, -1));
        assertEquals(List.of("a", "c", "b"), DisplayOrder.moved(order, 1, 1));
        assertEquals(order, DisplayOrder.moved(order, 0, -1));
        assertEquals(order, DisplayOrder.moved(order, 2, 1));
    }

    @Test
    @DisplayName("a fluid target defaults to a bucket a second, an item to one")
    void fluidTargetsDefaultToAThousand() {
        // A millibucket a second is a drip and is never what anyone means; the default belongs where
        // the target is created so the GUI and the commands cannot disagree about it.
        assertEquals(1000.0, TargetOutput.of(MfpKey.fluid("gtceu", "ethanol")).perSecond());
        assertEquals(1.0, TargetOutput.of(MfpKey.item("gtceu", "steel_ingot")).perSecond());
        assertEquals(1000.0, TargetOutput.defaultRate(MfpKey.fluid("gtceu", "steam")));
    }

    @Test
    @DisplayName("a line reports the decisions the user made about it")
    void decisionsAreReadableBackPerLine() {
        RecipeIndex index = index();
        Plan plan = new Plan("test").target(PLATE, 1).rawMaterial(ORE);
        plan.chooseRecipe(PLATE, "mfp:plate");
        plan.configureMachine("mfp:ingot", MachineConfig.of("mfp:lv_machine", 1));
        new RecipeChooser(index).expandInto(plan);

        Line plateLine = line(plan, "mfp:plate");
        Line ingotLine = line(plan, "mfp:ingot");

        assertEquals(java.util.Set.of(LineDecision.RECIPE), plan.decisionsFor(plateLine));
        assertEquals(java.util.Set.of(LineDecision.CONFIG), plan.decisionsFor(ingotLine));

        // A machine choice is per recipe type, so it marks every line of that type.
        plan.chooseMachine("mfp:machine", "mfp:lv_machine");
        assertTrue(plan.decisionsFor(plateLine).contains(LineDecision.MACHINE));
        assertTrue(plan.decisionsFor(ingotLine).contains(LineDecision.MACHINE));
    }

    private static Line line(Plan plan, String recipeId) {
        return plan.allLines().stream()
                .filter(candidate -> candidate.recipe().id().equals(recipeId))
                .findFirst().orElseThrow();
    }
}
