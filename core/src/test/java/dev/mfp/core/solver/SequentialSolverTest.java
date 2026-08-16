package dev.mfp.core.solver;

import static dev.mfp.core.solver.Fixtures.CASING;
import static dev.mfp.core.solver.Fixtures.CRUSHED;
import static dev.mfp.core.solver.Fixtures.DUST;
import static dev.mfp.core.solver.Fixtures.FUEL;
import static dev.mfp.core.solver.Fixtures.GRAVEL;
import static dev.mfp.core.solver.Fixtures.INGOT;
import static dev.mfp.core.solver.Fixtures.LUBRICANT;
import static dev.mfp.core.solver.Fixtures.ORE;
import static dev.mfp.core.solver.Fixtures.PLATE;
import static dev.mfp.core.solver.Fixtures.STONE_DUST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.Floor;
import dev.mfp.core.plan.Line;
import dev.mfp.core.plan.MachineConfig;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.plan.ProductionType;
import dev.mfp.core.plan.Subfloor;
import org.junit.jupiter.api.Test;

class SequentialSolverTest {

    private static final double TOLERANCE = 1e-6;

    private final SequentialSolver solver = new SequentialSolver();

    private static Plan fiveStepPlan() {
        Plan plan = new Plan("five step").target(CASING, 1.0);
        plan.add(new Line(Fixtures.casing()));
        plan.add(new Line(Fixtures.plate()));
        plan.add(new Line(Fixtures.ingot()));
        plan.add(new Line(Fixtures.dust()));
        plan.add(new Line(Fixtures.crushed()));
        return plan;
    }

    /** The M3 acceptance check: every machine count worked out by hand in {@link Fixtures}. */
    @Test
    void fiveStepChainMatchesHandComputedMachineCounts() {
        SolveResult result = solver.solve(fiveStepPlan());

        assertEquals(5, result.lines().size());
        assertEquals(1.0, result.lines().get(0).machineCount(), TOLERANCE, "casing");
        assertEquals(1.0, result.lines().get(1).machineCount(), TOLERANCE, "plate");
        assertEquals(4.0, result.lines().get(2).machineCount(), TOLERANCE, "ingot");
        assertEquals(2.0, result.lines().get(3).machineCount(), TOLERANCE, "dust");
        assertEquals(2.0, result.lines().get(4).machineCount(), TOLERANCE, "crushed");
        assertEquals(10, result.totalMachines());
    }

    @Test
    void fiveStepChainDeliversItsTargetAndImportsOnlyOre() {
        SolveResult result = solver.solve(fiveStepPlan());

        assertTrue(result.isComplete());
        assertEquals(1.0, result.products().get(CASING), TOLERANCE);
        assertEquals(1.0, result.rawInputs().get(ORE), TOLERANCE);
        assertFalse(result.rawInputs().containsKey(PLATE));
        assertFalse(result.rawInputs().containsKey(DUST));
    }

    @Test
    void intermediateRatesPropagateDownTheChain() {
        SolveResult result = solver.solve(fiveStepPlan());

        assertEquals(1.0, result.lines().get(0).craftsPerSecond(), TOLERANCE, "casing crafts/s");
        assertEquals(2.0, result.lines().get(1).craftsPerSecond(), TOLERANCE, "plate crafts/s");
        assertEquals(2.0, result.lines().get(2).craftsPerSecond(), TOLERANCE, "ingot crafts/s");
        assertEquals(2.0, result.lines().get(0).inputs().get(PLATE), TOLERANCE);
        assertEquals(2.0, result.lines().get(4).outputs().get(CRUSHED), TOLERANCE);
    }

    /**
     * Energy is summed as a draw, not carried through the demand map (plan §13.4).
     *
     * <p>It used to be an import like any other item — plan P3 taken all the way — and the arithmetic
     * is unchanged, but a plan that reported "you must import 1440 EU/s" was answering a question
     * nobody asked. What a player wants is what this factory will draw from the power they already
     * have.
     */
    @Test
    void energyIsReportedAsADrawRatherThanAnImport() {
        SolveResult result = solver.solve(fiveStepPlan());

        assertEquals(1440.0, result.euDrawPerSecond(), TOLERANCE);
        assertFalse(result.rawInputs().containsKey(MfpKey.EU));
        assertFalse(result.generatesPower());
    }

    /**
     * A generator added to an unpowered plan idles, because nothing in the plan demands power.
     *
     * <p>Before M6c this line was sized by the chain's own demand — 2.25 generators burning 2.25
     * fuel a second — which was the showpiece of energy-as-an-item. It is deliberately gone: a
     * player's power comes from a grid they already built, and a planner that quietly adds a fuel
     * bill to a steel chain is inventing a factory they did not ask for.
     */
    @Test
    void aGeneratorIdlesBecauseNothingDemandsPower() {
        Plan plan = fiveStepPlan();
        plan.add(new Line(Fixtures.generator()));

        SolveResult result = solver.solve(plan);
        LineResult generator = result.lines().get(5);

        assertTrue(generator.isIdle());
        assertEquals(0.0, generator.machineCount(), TOLERANCE);
        assertFalse(result.rawInputs().containsKey(FUEL));
        assertEquals(1440.0, result.euDrawPerSecond(), TOLERANCE);
    }

    /** A byproduct already on hand must be used before anything demands it from outside. */
    @Test
    void byproductIsConsumedRatherThanImported() {
        Plan plan = new Plan("byproducts").target(GRAVEL, 1.0).target(CRUSHED, 2.0);
        plan.add(new Line(Fixtures.gravelFromStoneDust()));
        plan.add(new Line(Fixtures.crushedWithByproduct()));

        SolveResult result = solver.solve(plan);

        // The crusher yields 1 stone dust per craft and runs 1 craft/s, exactly covering the gravel
        // line's demand, so no stone dust is imported.
        assertFalse(result.rawInputs().containsKey(STONE_DUST));
        assertEquals(1.0, result.rawInputs().get(ORE), TOLERANCE);
        assertTrue(result.isComplete());
    }

    /**
     * Note what does <em>not</em> create a byproduct: asking for one crushed from a recipe that
     * makes two simply runs the line at half a craft per second. Machine counts stay fractional, so
     * there is no rounding surplus. Byproducts come from outputs nothing asked for.
     */
    @Test
    void undemandedOutputBecomesAByproduct() {
        Plan plan = new Plan("spill").target(CRUSHED, 2.0);
        plan.add(new Line(Fixtures.crushedWithByproduct()));

        SolveResult result = solver.solve(plan);

        assertEquals(2.0, result.products().get(CRUSHED), TOLERANCE);
        assertEquals(1.0, result.byproducts().get(STONE_DUST), TOLERANCE);
        assertEquals(1.0, result.lines().get(0).byproducts().get(STONE_DUST), TOLERANCE);
        assertFalse(result.byproducts().containsKey(CRUSHED));
    }

    @Test
    void fractionalDemandDoesNotCreateSurplus() {
        Plan plan = new Plan("fractional").target(CRUSHED, 1.0);
        plan.add(new Line(Fixtures.crushed()));

        SolveResult result = solver.solve(plan);

        assertEquals(0.5, result.lines().get(0).craftsPerSecond(), TOLERANCE);
        assertEquals(1.0, result.products().get(CRUSHED), TOLERANCE);
        assertFalse(result.byproducts().containsKey(CRUSHED));
    }

    @Test
    void machineLimitCapsThroughputAndLeavesTargetUnsatisfied() {
        Plan plan = new Plan("limited").target(CASING, 1.0);
        plan.add(new Line(Fixtures.casing(),
                MachineConfig.UNSET.withLimit(0.5, false)));

        SolveResult result = solver.solve(plan);

        assertEquals(0.5, result.lines().get(0).machineCount(), TOLERANCE);
        assertEquals(0.5, result.products().get(CASING), TOLERANCE);
        assertEquals(0.5, result.unsatisfied().get(CASING), TOLERANCE);
        assertFalse(result.isComplete());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("not fully satisfied")));
    }

    /** forceLimit means "I have exactly this many and they run flat out", even past demand. */
    @Test
    void forceLimitRunsExactlyThatManyMachines() {
        Plan plan = new Plan("forced").target(CASING, 1.0);
        plan.add(new Line(Fixtures.casing(), MachineConfig.UNSET.withLimit(3.0, true)));

        SolveResult result = solver.solve(plan);

        assertEquals(3.0, result.lines().get(0).machineCount(), TOLERANCE);
        assertEquals(1.0, result.products().get(CASING), TOLERANCE);
        assertEquals(2.0, result.byproducts().get(CASING), TOLERANCE);
    }

    @Test
    void percentageScalesTheLine() {
        Plan plan = new Plan("half").target(CASING, 1.0);
        plan.add(new Line(Fixtures.casing()).percentage(50));

        SolveResult result = solver.solve(plan);

        assertEquals(0.5, result.lines().get(0).machineCount(), TOLERANCE);
        assertEquals(0.5, result.unsatisfied().get(CASING), TOLERANCE);
    }

    @Test
    void inactiveLineIsSkippedEntirely() {
        Plan plan = new Plan("disabled").target(CASING, 1.0);
        plan.add(new Line(Fixtures.casing()).active(false));

        SolveResult result = solver.solve(plan);

        assertTrue(result.lines().get(0).isIdle());
        assertEquals(0, result.totalMachines());
        assertEquals(1.0, result.unsatisfied().get(CASING), TOLERANCE);
    }

    /**
     * A multi-output line paced by one chosen product rather than by whichever demand is largest.
     */
    @Test
    void priorityItemPacesAMultiOutputLine() {
        MfpRecipe twoOutputs = MfpRecipe.builder("mfp:split", "mfp:machine", "test")
                .input(MfpIngredient.of(ORE, 1))
                .output(MfpOutput.of(CRUSHED, 1))
                .output(MfpOutput.of(STONE_DUST, 4))
                .duration(20)
                .build();

        Plan plan = new Plan("priority").target(CRUSHED, 4.0).target(STONE_DUST, 4.0);
        plan.add(new Line(twoOutputs).priorityItem(STONE_DUST));

        SolveResult result = solver.solve(plan);

        // Paced by stone dust: 4/s needs 1 craft/s, which yields only 1 crushed/s.
        assertEquals(1.0, result.lines().get(0).craftsPerSecond(), TOLERANCE);
        assertEquals(4.0, result.products().get(STONE_DUST), TOLERANCE);
        assertEquals(3.0, result.unsatisfied().get(CRUSHED), TOLERANCE);
    }

    /** Without a priority item the solver covers every demand, overproducing the rest. */
    @Test
    void withoutPriorityTheLargestDemandPacesTheLine() {
        MfpRecipe twoOutputs = MfpRecipe.builder("mfp:split", "mfp:machine", "test")
                .input(MfpIngredient.of(ORE, 1))
                .output(MfpOutput.of(CRUSHED, 1))
                .output(MfpOutput.of(STONE_DUST, 4))
                .duration(20)
                .build();

        Plan plan = new Plan("no priority").target(CRUSHED, 4.0).target(STONE_DUST, 4.0);
        plan.add(new Line(twoOutputs));

        SolveResult result = solver.solve(plan);

        assertEquals(4.0, result.lines().get(0).craftsPerSecond(), TOLERANCE);
        assertTrue(result.isComplete());
        assertEquals(12.0, result.byproducts().get(STONE_DUST), TOLERANCE);
    }

    @Test
    void consumingLineIsPacedBySupplyNotDemand() {
        Plan plan = new Plan("consume").target(CRUSHED, 2.0);
        plan.add(new Line(Fixtures.crushedWithByproduct()));
        plan.add(new Line(Fixtures.gravelFromStoneDust()).productionType(ProductionType.CONSUME));

        SolveResult result = solver.solve(plan);

        // Nothing asked for gravel; the line runs purely on the 1/s of stone dust available.
        LineResult gravelLine = result.lines().get(1);
        assertEquals(1.0, gravelLine.craftsPerSecond(), TOLERANCE);
        assertEquals(1.0, result.byproducts().get(GRAVEL), TOLERANCE);
        assertFalse(result.byproducts().containsKey(STONE_DUST));
    }

    /** A borrowed-and-returned fluid is a fixed cost, not a supply chain. */
    @Test
    void catalystNeverFlowsThroughThePlan() {
        Plan plan = new Plan("catalyst").target(PLATE, 2.0);
        plan.add(new Line(Fixtures.lubricatedPress()));

        SolveResult result = solver.solve(plan);

        assertFalse(result.rawInputs().containsKey(LUBRICANT));
        assertFalse(result.byproducts().containsKey(LUBRICANT));
        assertEquals(2.0, result.rawInputs().get(INGOT), TOLERANCE);
    }

    /** A subfloor is only a presentation grouping; the arithmetic must be identical. */
    @Test
    void subfloorProducesTheSameAnswerAsAFlatPlan() {
        Plan flat = new Plan("flat").target(CASING, 1.0);
        flat.add(new Line(Fixtures.casing()));
        flat.add(new Line(Fixtures.plate()));
        flat.add(new Line(Fixtures.ingot()));

        Plan nested = new Plan("nested").target(CASING, 1.0);
        nested.add(new Line(Fixtures.casing()));
        Floor inner = new Floor();
        inner.add(new Line(Fixtures.plate()));
        inner.add(new Line(Fixtures.ingot()));
        nested.add(new Subfloor(inner));

        SolveResult flatResult = solver.solve(flat);
        SolveResult nestedResult = new SequentialSolver().solve(nested);

        assertEquals(flatResult.totalMachines(), nestedResult.totalMachines());
        assertEquals(flatResult.rawInputs().get(DUST), nestedResult.rawInputs().get(DUST), TOLERANCE);
        assertEquals(flatResult.euDrawPerSecond(), nestedResult.euDrawPerSecond(), TOLERANCE);
        assertEquals(1.0, nestedResult.products().get(CASING), TOLERANCE);
    }

    /**
     * The documented failure mode. A single top-down pass cannot close a loop, so rather than
     * looping forever or quietly producing a wrong number, the solver leaves the item as an import
     * and says why.
     */
    @Test
    void cyclicPlanIsReportedRatherThanSilentlyWrong() {
        MfpKey widget = MfpKey.item("mfp", "widget");
        MfpKey a = MfpKey.item("mfp", "a");
        MfpKey b = MfpKey.item("mfp", "b");

        Plan plan = new Plan("cycle").target(widget, 1.0);
        plan.add(new Line(Fixtures.simple("mfp:widget", a, 1, widget, 1, 20, 0)));
        plan.add(new Line(Fixtures.simple("mfp:a", b, 1, a, 1, 20, 0)));
        plan.add(new Line(Fixtures.simple("mfp:b", a, 1, b, 1, 20, 0)));

        SolveResult result = solver.solve(plan);

        assertTrue(result.rawInputs().containsKey(a),
                "the loop should surface as an import of something the plan also makes");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("matrix solver")),
                "the user must be told a single pass cannot resolve this, got: " + result.warnings());
    }

    @Test
    void instantRecipesHaveNoMachineCount() {
        MfpRecipe handCrafted = MfpRecipe.builder("mfp:hand", "minecraft:crafting", "test")
                .input(MfpIngredient.of(INGOT, 9))
                .output(MfpOutput.of(CASING, 1))
                .build();

        Plan plan = new Plan("instant").target(CASING, 1.0);
        plan.add(new Line(handCrafted));

        SolveResult result = solver.solve(plan);

        assertEquals(0.0, result.lines().get(0).machineCount(), TOLERANCE);
        assertEquals(9.0, result.rawInputs().get(INGOT), TOLERANCE);
        assertTrue(result.isComplete());
    }

    @Test
    void chancedOutputsAreCountedAtTheirExpectedValue() {
        MfpRecipe chanced = MfpRecipe.builder("mfp:chanced", "mfp:machine", "test")
                .input(MfpIngredient.of(ORE, 1))
                .output(MfpOutput.chanced(STONE_DUST, 1, 0.25))
                .duration(20)
                .build();

        Plan plan = new Plan("chance").target(STONE_DUST, 1.0);
        plan.add(new Line(chanced));

        SolveResult result = solver.solve(plan);

        // 25% of one per craft means four crafts per second to average one output per second.
        assertEquals(4.0, result.lines().get(0).craftsPerSecond(), TOLERANCE);
        assertEquals(4.0, result.rawInputs().get(ORE), TOLERANCE);
    }
}
