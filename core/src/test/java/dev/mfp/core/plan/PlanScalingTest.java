package dev.mfp.core.plan;

import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scaling a whole plan so that one line lands on a whole number of machines.
 *
 * <p>The claim under test is the one the feature is built on: <b>a plan is linear in its targets</b>,
 * so "give me three towers instead of 2.60" is a single multiplication applied to every target and
 * nothing else. The tests that matter most are the refusals — a factor computed from a line that is
 * not running, or one that would scale a target away to nothing, is a number that would be presented
 * with total confidence and be wrong.
 */
class PlanScalingTest {

    private static final MfpKey ETHANOL = MfpKey.fluid("mfp", "ethanol");
    private static final MfpKey BIOMASS = MfpKey.fluid("mfp", "biomass");

    private static double factor(double current, double wanted) {
        OptionalDouble result = PlanScaling.factorFor(current, wanted);
        assertTrue(result.isPresent(), current + " -> " + wanted + " should give a factor");
        return result.getAsDouble();
    }

    @Test
    @DisplayName("three towers where the plan wanted 2.60 is the plan multiplied by 3/2.60")
    void factorFromTheWorkedExample() {
        double f = factor(2.60, 3);
        assertEquals(3 / 2.60, f, 1e-12, "the factor is the ratio of machine counts, nothing else");
        assertEquals(1000 * 3 / 2.60, 1000 * f, 1e-9,
                "so 1000 mB/s of ethanol becomes a bit over 1153 mB/s; the player's \"1155\" is "
                        + "their own rounding and must not be the number this produces");
    }

    @Test
    @DisplayName("asking for fewer machines than the plan wants scales it down")
    void factorGoesBothWays() {
        assertEquals(0.5, factor(4, 2), 1e-12,
                "the dialog is \"how many will I build\", and building two of four is a real answer");
    }

    @Test
    @DisplayName("a line running no machines cannot say what one machine would produce")
    void zeroMachinesRefusesAFactor() {
        assertTrue(PlanScaling.factorFor(0, 3).isEmpty(),
                "an idle line has no throughput per machine, so no multiple of it reaches three");
        assertTrue(PlanScaling.factorFor(Double.NaN, 3).isEmpty(),
                "a count that is not a number is a solve that failed, not a scaling opportunity");
        assertTrue(PlanScaling.factorFor(Double.POSITIVE_INFINITY, 3).isEmpty(),
                "and infinity divides to zero, which would silently empty the plan");
    }

    @Test
    @DisplayName("zero machines is not something to scale towards either")
    void zeroWantedRefusesAFactor() {
        assertTrue(PlanScaling.factorFor(2.6, 0).isEmpty(),
                "building none of them is deleting the line, which this operation does not do");
        assertTrue(PlanScaling.factorFor(2.6, -1).isEmpty(), "a negative build is not a build");
    }

    @Test
    @DisplayName("every target moves by the same factor, not just the one being looked at")
    void scalingMovesEveryTarget() {
        Plan plan = new Plan("two customers")
                .target(ETHANOL, 1000)
                .target(BIOMASS, 40);

        PlanScaling.scaleTargets(plan, 3 / 2.60);

        assertEquals(1000 * 3 / 2.60, plan.targets().get(0).perSecond(), 1e-9,
                "the target the user was staring at");
        assertEquals(40 * 3 / 2.60, plan.targets().get(1).perSecond(), 1e-9,
                "and the one they were not - a plan scaled by parts is a plan that no longer "
                        + "balances");
    }

    @Test
    @DisplayName("scaling returns the same plan rather than a copy, so the GUI edits what it draws")
    void scalingIsInPlace() {
        Plan plan = new Plan("ethanol").target(ETHANOL, 1000);
        assertTrue(plan == PlanScaling.scaleTargets(plan, 2),
                "a copy would leave the screen re-solving one object and rendering another");
    }

    @Test
    @DisplayName("the same key targeted twice keeps both targets rather than merging")
    void repeatedKeysAreScaledIndependently() {
        Plan plan = new Plan("two customers").target(ETHANOL, 1000).target(ETHANOL, 500);

        PlanScaling.scaleTargets(plan, 2);

        assertEquals(2, plan.targets().size(), "two customers for the same fluid are two targets");
        assertEquals(2000, plan.targets().get(0).perSecond(), 1e-9);
        assertEquals(1000, plan.targets().get(1).perSecond(), 1e-9);
    }

    @Test
    @DisplayName("a factor that would drive a target to zero is refused before anything is rewritten")
    void zeroFactorIsRefused() {
        Plan plan = new Plan("ethanol").target(ETHANOL, 1000).target(BIOMASS, 40);

        assertThrows(IllegalArgumentException.class, () -> PlanScaling.scaleTargets(plan, 0),
                "TargetOutput rejects a non-positive rate; catching it here means it is refused "
                        + "before the first target has been rewritten");
        assertThrows(IllegalArgumentException.class, () -> PlanScaling.scaleTargets(plan, -1));
        assertThrows(IllegalArgumentException.class,
                () -> PlanScaling.scaleTargets(plan, Double.NaN));

        assertEquals(1000, plan.targets().get(0).perSecond(), 1e-9,
                "and the plan is untouched, not half-scaled");
        assertEquals(40, plan.targets().get(1).perSecond(), 1e-9);
    }

    @Test
    @DisplayName("the preview says what the targets become without moving them")
    void previewLeavesThePlanAlone() {
        Plan plan = new Plan("ethanol").target(ETHANOL, 1000);

        Map<MfpKey, Double> preview = PlanScaling.previewTargets(plan, 3 / 2.60);

        assertEquals(1000 * 3 / 2.60, preview.get(ETHANOL), 1e-9,
                "which is the number on the right of the arrow in the dialog");
        assertEquals(1000, plan.targets().get(0).perSecond(), 1e-9,
                "a preview that edited the plan would apply itself by being looked at");
    }

    @Test
    @DisplayName("a solved flow map scales by the same factor as the targets do")
    void flowsScaleWithTheTargets() {
        Map<MfpKey, Double> scaled = PlanScaling.scaleFlows(Map.of(ETHANOL, 1000.0, BIOMASS, 40.0), 2);

        assertEquals(2000, scaled.get(ETHANOL), 1e-9);
        assertEquals(80, scaled.get(BIOMASS), 1e-9);
    }

    @Test
    @DisplayName("a plan with no machine limit anywhere is the linear case this maths assumes")
    void anUnlimitedPlanIsLinear() {
        Plan plan = new Plan("ethanol").target(ETHANOL, 1000);
        plan.add(new Line(recipe()));
        plan.configureMachine(recipe().id(), MachineConfig.of("mfp:distillery", 3));

        assertFalse(PlanScaling.hasMachineLimit(plan), "nothing here is capped");
    }

    @Test
    @DisplayName("a machine limit makes the plan non-linear, whether it sits on the plan or the line")
    void aLimitAnywhereBreaksLinearity() {
        Plan configured = new Plan("capped").target(ETHANOL, 1000);
        configured.configureMachine(recipe().id(),
                MachineConfig.of("mfp:distillery", 3).withLimit(4, false));
        assertTrue(PlanScaling.hasMachineLimit(configured),
                "\"at most four\" is an inequality, so doubling the targets does not double the "
                        + "answer and the preview would be a confident lie");

        Plan online = new Plan("capped").target(ETHANOL, 1000);
        online.add(new Line(recipe(), MachineConfig.of("mfp:distillery", 3).withLimit(4, true)));
        assertTrue(PlanScaling.hasMachineLimit(online),
                "expansion can hand a line a build that never went through configureMachine, and a "
                        + "limit found only there is exactly as fatal");
    }

    private static MfpRecipe recipe() {
        return MfpRecipe.builder("mfp:distil_ethanol", "mfp:distilling", "test")
                .input(MfpIngredient.of(BIOMASS, 100))
                .output(MfpOutput.of(ETHANOL, 60))
                .duration(200)
                .euIn(120)
                .build();
    }
}
