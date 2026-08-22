package dev.mfp.core.select;

import dev.mfp.core.index.RecipeIndex;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.Line;
import dev.mfp.core.plan.LineDecision;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.plan.SolverMode;
import dev.mfp.core.solver.SolveResult;
import dev.mfp.core.solver.Solvers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A sink on the plan, from the decision to the solved line (M18).
 *
 * <p>The fixture is the shape the milestone is named after in miniature: making a plate gives off a
 * slag nothing wants, and one of the ways of eating that slag hands back the very ore the chain
 * started from. Everything here is about what happens to the plan when the user says so.
 */
class SinkExpansionTest {

    private static final MfpKey ORE = MfpKey.item("mfp", "ore");
    private static final MfpKey PLATE = MfpKey.item("mfp", "plate");
    private static final MfpKey SLAG = MfpKey.item("mfp", "slag");
    private static final MfpKey ASH = MfpKey.item("mfp", "ash");
    private static final MfpKey LYE = MfpKey.fluid("mfp", "lye");
    private static final MfpKey SALT = MfpKey.item("mfp", "salt");

    /** Ore in, plate out, and a slag nobody asked for. */
    private static final MfpRecipe SMELT = MfpRecipe.builder("mfp:smelt", "mfp:machine", "test")
            .input(MfpIngredient.of(ORE, 2))
            .output(MfpOutput.of(PLATE, 1))
            .output(MfpOutput.of(SLAG, 1))
            .duration(20)
            .euIn(16)
            .minTier(1)
            .build();

    /** Eats the slag and gives back ore — the sink the plan wants. */
    private static final MfpRecipe RECLAIM = MfpRecipe.builder("mfp:reclaim", "mfp:machine", "test")
            .input(MfpIngredient.of(SLAG, 1))
            .input(MfpIngredient.of(LYE, 100))
            .output(MfpOutput.of(ORE, 1))
            .duration(20)
            .euIn(16)
            .minTier(1)
            .build();

    /** Eats the slag and makes nothing anyone wants — the disposal route. */
    private static final MfpRecipe BURN = MfpRecipe.builder("mfp:burn", "mfp:machine", "test")
            .input(MfpIngredient.of(SLAG, 1))
            .output(MfpOutput.of(ASH, 1))
            .duration(20)
            .euIn(16)
            .minTier(1)
            .build();

    /** The lye the reclaimer needs, so a sink's own ingredients have somewhere to come from. */
    private static final MfpRecipe BOIL = MfpRecipe.builder("mfp:boil", "mfp:machine", "test")
            .input(MfpIngredient.of(SALT, 1))
            .output(MfpOutput.of(LYE, 100))
            .duration(20)
            .euIn(16)
            .minTier(1)
            .build();

    private static RecipeIndex index() {
        RecipeIndex.Builder builder = RecipeIndex.builder();
        builder.beginProvider("test", 0);
        for (MfpRecipe recipe : List.of(SMELT, RECLAIM, BURN, BOIL)) {
            builder.recipe(recipe);
        }
        builder.machine(new MfpMachine("mfp:lv_machine", "LV Machine", 1, 32,
                List.of("mfp:machine"), false, List.of(), "test"));
        return builder.build();
    }

    private static Plan planFor(MfpKey... raw) {
        Plan plan = new Plan("test").target(PLATE, 1);
        for (MfpKey key : raw) {
            plan.rawMaterial(key);
        }
        return plan;
    }

    private static List<String> ids(ChooserResult result) {
        return result.lines().stream().map(line -> line.recipe().id()).toList();
    }

    @Test
    @DisplayName("a plan with no sink throws the slag away")
    void baselineIsSurplus() {
        Plan plan = planFor(ORE);
        new RecipeChooser(index()).expandInto(plan);
        SolveResult solved = Solvers.solve(plan);

        assertTrue(solved.byproducts().containsKey(SLAG),
                "the case the milestone starts from: a dead end in the Byproducts tab");
    }

    @Test
    @DisplayName("a sink becomes a line above the one that feeds it, and eats the surplus")
    void theSinkIsALine() {
        Plan plan = planFor(ORE, SALT).consumeWith(SLAG, "mfp:burn");
        ChooserResult result = new RecipeChooser(index()).expandInto(plan);

        assertTrue(ids(result).contains("mfp:burn"));
        assertTrue(ids(result).indexOf("mfp:burn") < ids(result).indexOf("mfp:smelt"),
                "a consumer is emitted above the line that feeds it (§5.1)");

        SolveResult solved = Solvers.solve(plan);
        assertFalse(solved.byproducts().containsKey(SLAG),
                "the slag is eaten, so it is no longer surplus");
    }

    @Test
    @DisplayName("the surplus is never given a second producer")
    void noSecondSourceForTheSurplus() {
        Plan plan = planFor(ORE, SALT).consumeWith(SLAG, "mfp:burn");
        ChooserResult result = new RecipeChooser(index()).expandInto(plan);

        assertEquals(1, ids(result).stream().filter(id -> id.equals("mfp:smelt")).count());
        assertEquals(List.of("mfp:burn", "mfp:smelt"), ids(result),
                "nothing was expanded to make more slag for the sink to eat");
    }

    @Test
    @DisplayName("a sink's own other ingredients are ordinary demands")
    void theSinksIngredientsExpand() {
        Plan plan = planFor(ORE, SALT).consumeWith(SLAG, "mfp:reclaim");
        ChooserResult result = new RecipeChooser(index()).expandInto(plan);

        assertTrue(ids(result).contains("mfp:boil"),
                "the lye the reclaimer needs is planned like any other input");
        assertTrue(result.unresolved().isEmpty());
    }

    @Test
    @DisplayName("a sink's ingredient the plan cannot make is an import, not a silent omission")
    void anUnmakeableIngredientIsAnImport() {
        Plan plan = planFor(ORE).consumeWith(SLAG, "mfp:reclaim");
        ChooserResult result = new RecipeChooser(index()).expandInto(plan);

        assertTrue(ids(result).contains("mfp:reclaim"));
        assertTrue(result.unresolved().contains(SALT),
                "the salt below the lye is reported exactly as any other unbuyable input is");
    }

    @Test
    @DisplayName("a plan carrying a sink is handed to the whole-plan engine")
    void sinksNeedAWholePlanEngine() {
        Plan plan = planFor(ORE, SALT).consumeWith(SLAG, "mfp:burn");
        new RecipeChooser(index()).expandInto(plan);

        assertEquals(SolverMode.SIMPLEX, plan.solverMode());
        assertTrue(plan.solverModeDerived(),
                "derived, so dropping the sink lets the plan go back to choosing for itself");
    }

    @Test
    @DisplayName("the line says it exists to consume something rather than to make it")
    void theLineIsMarked() {
        Plan plan = planFor(ORE, SALT).consumeWith(SLAG, "mfp:burn");
        new RecipeChooser(index()).expandInto(plan);

        Line sink = plan.allLines().stream()
                .filter(line -> line.recipe().id().equals("mfp:burn"))
                .findFirst()
                .orElseThrow();
        assertEquals(Set.of(LineDecision.SINK), plan.decisionsFor(sink));
    }

    @Test
    @DisplayName("a sink naming a recipe that does not eat the item changes nothing")
    void aStaleSinkIsDropped() {
        Plan plan = planFor(ORE, SALT).consumeWith(SLAG, "mfp:boil");
        ChooserResult result = new RecipeChooser(index()).expandInto(plan);

        assertFalse(ids(result).contains("mfp:boil"));
        assertEquals(List.of("mfp:smelt"), ids(result));
    }

    @Test
    @DisplayName("dropping the sink puts the plan back where it started")
    void clearingASinkIsReversible() {
        Plan plan = planFor(ORE, SALT);
        RecipeChooser chooser = new RecipeChooser(index());
        chooser.expandInto(plan);
        List<String> before = plan.allLines().stream().map(line -> line.recipe().id()).toList();

        plan.consumeWith(SLAG, "mfp:burn");
        plan.clearLines();
        chooser.expandInto(plan);
        assertTrue(plan.allLines().stream().anyMatch(line -> line.recipe().id().equals("mfp:burn")));

        plan.clearSink(SLAG);
        plan.clearLines();
        chooser.expandInto(plan);
        assertEquals(before, plan.allLines().stream().map(line -> line.recipe().id()).toList());
    }

    @Test
    @DisplayName("a sink adds lines and moves none: the target's own chain is untouched")
    void theTargetsChainIsUnmoved() {
        // Byproduct feeding on, which is where the pack's fault was: a sink placed inside the walk
        // is part of every candidate plan the feeding round builds, so its own ingredients become
        // demands that round tries to answer out of leftovers - and it re-picks the *target's*
        // chain around them. The ethylene measurement in STATUS §22 is the evidence; this is the
        // claim, and it is the one a future change would break first.
        Plan without = planFor(ORE, SALT);
        RecipeChooser chooser = new RecipeChooser(index());
        chooser.expandInto(without);
        List<String> before = without.allLines().stream().map(line -> line.recipe().id()).toList();

        Plan with = planFor(ORE, SALT).consumeWith(SLAG, "mfp:reclaim");
        chooser.expandInto(with);
        List<String> after = with.allLines().stream().map(line -> line.recipe().id()).toList();

        assertTrue(after.containsAll(before),
                "every line the plan had is still on it: " + before + " vs " + after);
        List<String> added = after.stream().filter(id -> !before.contains(id)).toList();
        assertEquals(List.of("mfp:reclaim", "mfp:boil"), added,
                "and the only new lines are the sink and what the sink itself needs");
    }

    @Test
    @DisplayName("the ranked sinks put the one that feeds the plan first")
    void rankingThroughTheChooser() {
        Plan plan = planFor(ORE, SALT);
        RecipeChooser chooser = new RecipeChooser(index());
        chooser.expandInto(plan);
        SolveResult solved = Solvers.solve(plan);

        List<SinkScorer.Scored> ranked = chooser.sinks(SLAG, plan, solved.rawInputs().keySet());
        assertEquals(List.of("mfp:reclaim", "mfp:burn"),
                ranked.stream().map(scored -> scored.recipe().id()).toList(),
                "reclaiming feeds the ore the plan is buying; burning feeds nothing");
    }
}
