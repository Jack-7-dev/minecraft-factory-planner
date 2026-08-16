package dev.mfp.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MfpRecipeTest {

    private static final MfpKey IRON = MfpKey.item("minecraft", "iron_ingot");
    private static final MfpKey DUST = MfpKey.item("gtceu", "iron_dust");
    private static final MfpKey CIRCUIT = MfpKey.item("gtceu", "programmed_circuit", "cfg4");

    /**
     * GregTech encodes "not consumable" as chance 0, which reads like "never produced". If MFP ever
     * treats a non-consumed input as consumed, every circuit-gated recipe demands circuits it never
     * actually uses up, and the whole plan inflates.
     */
    @Test
    void nonConsumedInputContributesNoFlow() {
        MfpIngredient circuit = MfpIngredient.notConsumed(CIRCUIT);
        assertFalse(circuit.consumed());
        assertEquals(0.0, circuit.effectiveAmount());
    }

    @Test
    void consumedInputFlowsItsAmount() {
        assertEquals(2.0, MfpIngredient.of(DUST, 2).effectiveAmount());
    }

    @Test
    void chancedInputFlowsItsExpectedAmount() {
        MfpIngredient sometimes = new MfpIngredient(List.of(DUST), 4, true, 0.25);
        assertEquals(1.0, sometimes.effectiveAmount());
    }

    @Test
    void tagIngredientKeepsEveryCandidate() {
        MfpIngredient anyPlate = MfpIngredient.ofAny(List.of(IRON, DUST), 1);
        assertTrue(anyPlate.isAmbiguous());
        assertEquals(IRON, anyPlate.primary());
        assertEquals(2, anyPlate.candidates().size());
    }

    @Test
    void chancedOutputExpectationIsAmountTimesChance() {
        assertEquals(0.5, MfpOutput.chanced(DUST, 1, 0.5).expectedAmount());
        assertTrue(MfpOutput.chanced(DUST, 1, 0.5).isExpectationExact());
    }

    /**
     * XOR/FIRST outputs compete within their group, so a per-item expectation over-counts. The
     * model must advertise that rather than let the solver quietly sum them.
     */
    @Test
    void competingOutputModesAreNotIndependentlyExpectable() {
        MfpOutput exclusive = new MfpOutput(DUST, 1, 0.5, ChanceMode.EXCLUSIVE, "group1");
        assertFalse(exclusive.isExpectationExact());
        assertTrue(new MfpOutput(DUST, 1, 0.5, ChanceMode.INDEPENDENT, null).isExpectationExact());
    }

    @Test
    void alwaysOutputCannotBeChanced() {
        assertThrows(IllegalArgumentException.class,
                () -> new MfpOutput(DUST, 1, 0.5, ChanceMode.ALWAYS, null));
    }

    @Test
    void handCraftingHasNoIntrinsicRate() {
        MfpRecipe crafting = MfpRecipe.builder("minecraft:iron_block", "minecraft:crafting", "vanilla")
                .input(MfpIngredient.of(IRON, 9))
                .output(MfpOutput.of(MfpKey.item("minecraft", "iron_block"), 1))
                .build();
        assertFalse(crafting.hasRate());
        assertEquals(MfpRecipe.INSTANT, crafting.durationTicks());
    }

    @Test
    void poweredRecipeReportsRateAndEnergy() {
        MfpRecipe macerate = MfpRecipe.builder("gtceu:macerator/iron", "gtceu:macerator", "gtceu")
                .input(MfpIngredient.of(IRON, 1))
                .output(MfpOutput.of(DUST, 1))
                .duration(150)
                .euIn(2)
                .amperage(1)
                .build();
        assertTrue(macerate.hasRate());
        assertFalse(macerate.isGenerator());
        assertEquals(300.0, macerate.totalEnergyIn());
    }

    @Test
    void generatorsAreRecipesThatOutputEnergy() {
        MfpRecipe turbine = MfpRecipe.builder("gtceu:steam_turbine/steam", "gtceu:steam_turbine", "gtceu")
                .input(MfpIngredient.of(MfpKey.fluid("minecraft", "steam"), 1000))
                .output(MfpOutput.of(MfpKey.EU, 1))
                .duration(20)
                .euOut(32)
                .build();
        assertTrue(turbine.isGenerator());
        assertTrue(turbine.produces(MfpKey.EU));
    }

    /** Anything the adapter cannot model must survive as metadata rather than being dropped (P4). */
    @Test
    void unmodelledDataSurvivesInExtra() {
        MfpRecipe ebf = MfpRecipe.builder("gtceu:ebf/steel", "gtceu:electric_blast_furnace", "gtceu")
                .extra("ebf_temp", 1000)
                .build();
        assertEquals(1000, ebf.intExtra("ebf_temp", -1));
        assertEquals(-1, ebf.intExtra("vacuum_level", -1));
    }

    @Test
    void consumesIgnoresNonConsumedInputs() {
        MfpRecipe recipe = MfpRecipe.builder("gtceu:assembler/thing", "gtceu:assembler", "gtceu")
                .input(MfpIngredient.notConsumed(CIRCUIT))
                .input(MfpIngredient.of(IRON, 1))
                .build();
        assertTrue(recipe.consumes(IRON));
        assertFalse(recipe.consumes(CIRCUIT));
    }

    @Test
    void confidenceDegradesWhenCombined() {
        assertEquals(Confidence.UNKNOWN, Confidence.EXACT.and(Confidence.UNKNOWN));
        assertEquals(Confidence.APPROXIMATE, Confidence.APPROXIMATE.and(Confidence.EXACT));
        assertEquals(Confidence.EXACT, Confidence.EXACT.and(Confidence.EXACT));
    }
}
