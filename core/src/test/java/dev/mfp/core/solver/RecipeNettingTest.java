package dev.mfp.core.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecipeNettingTest {

    private static final double TOLERANCE = 1e-9;

    private static final MfpKey LUBRICANT = MfpKey.fluid("mfp", "lubricant");
    private static final MfpKey INGOT = MfpKey.item("mfp", "ingot");
    private static final MfpKey PLATE = MfpKey.item("mfp", "plate");
    private static final MfpKey COPPER_PLATE = MfpKey.item("mfp", "copper_plate");
    private static final MfpKey CIRCUIT = MfpKey.item("mfp", "circuit");

    private static MfpRecipe.Builder base() {
        return MfpRecipe.builder("mfp:test", "mfp:machine", "test").duration(20);
    }

    @Test
    void equalAmountsCancelCompletely() {
        NettedRecipe netted = RecipeNetting.net(base()
                .input(MfpIngredient.of(INGOT, 1))
                .input(MfpIngredient.of(LUBRICANT, 1000))
                .output(MfpOutput.of(PLATE, 1))
                .output(MfpOutput.of(LUBRICANT, 1000))
                .build());

        assertEquals(1, netted.inputs().size());
        assertEquals(INGOT, netted.inputs().get(0).primary());
        assertEquals(1, netted.outputs().size());
        assertEquals(PLATE, netted.outputs().get(0).key());
        assertTrue(netted.hasCatalysts());
        assertEquals(1000.0, netted.catalysts().get(LUBRICANT), TOLERANCE);
    }

    @Test
    void surplusInputRemainsAfterCancelling() {
        NettedRecipe netted = RecipeNetting.net(base()
                .input(MfpIngredient.of(LUBRICANT, 1000))
                .output(MfpOutput.of(LUBRICANT, 400))
                .output(MfpOutput.of(PLATE, 1))
                .build());

        // 600 mB is genuinely used up and must still be supplied.
        assertEquals(1, netted.inputs().size());
        assertEquals(600.0, netted.inputs().get(0).amount(), TOLERANCE);
        assertEquals(400.0, netted.catalysts().get(LUBRICANT), TOLERANCE);
        assertFalse(netted.outputs().stream().anyMatch(o -> o.key().equals(LUBRICANT)));
    }

    @Test
    void surplusOutputRemainsAfterCancelling() {
        NettedRecipe netted = RecipeNetting.net(base()
                .input(MfpIngredient.of(LUBRICANT, 400))
                .output(MfpOutput.of(LUBRICANT, 1000))
                .build());

        // The recipe is a net producer of 600 mB.
        assertTrue(netted.inputs().isEmpty());
        assertEquals(1, netted.outputs().size());
        assertEquals(600.0, netted.outputs().get(0).amount(), TOLERANCE);
    }

    /**
     * A tag input that merely <em>could</em> be the returned item is not the same as one that is.
     * Cancelling on a maybe would erase a real material requirement.
     */
    @Test
    void ambiguousInputsAreNeverNetted() {
        NettedRecipe netted = RecipeNetting.net(base()
                .input(MfpIngredient.ofAny(List.of(PLATE, COPPER_PLATE), 2))
                .output(MfpOutput.of(PLATE, 2))
                .build());

        assertEquals(1, netted.inputs().size());
        assertEquals(2.0, netted.inputs().get(0).amount(), TOLERANCE);
        assertEquals(1, netted.outputs().size());
        assertFalse(netted.hasCatalysts());
    }

    /** A chanced return is not reliable, so it cannot cancel a guaranteed input. */
    @Test
    void chancedOutputsAreNeverNetted() {
        NettedRecipe netted = RecipeNetting.net(base()
                .input(MfpIngredient.of(LUBRICANT, 1000))
                .output(MfpOutput.chanced(LUBRICANT, 1000, 0.5))
                .build());

        assertEquals(1, netted.inputs().size());
        assertEquals(1000.0, netted.inputs().get(0).amount(), TOLERANCE);
        assertEquals(1, netted.outputs().size());
        assertFalse(netted.hasCatalysts());
    }

    /** Non-consumed inputs already flow nothing; netting them would double-count the exemption. */
    @Test
    void nonConsumedInputsAreLeftAlone() {
        NettedRecipe netted = RecipeNetting.net(base()
                .input(MfpIngredient.notConsumed(CIRCUIT))
                .output(MfpOutput.of(CIRCUIT, 1))
                .build());

        assertEquals(1, netted.inputs().size());
        assertFalse(netted.inputs().get(0).consumed());
        assertEquals(1, netted.outputs().size());
        assertFalse(netted.hasCatalysts());
    }

    @Test
    void recipesWithoutOverlapAreUnchanged() {
        MfpRecipe recipe = base()
                .input(MfpIngredient.of(INGOT, 1))
                .output(MfpOutput.of(PLATE, 1))
                .build();
        NettedRecipe netted = RecipeNetting.net(recipe);

        assertEquals(recipe.inputs(), netted.inputs());
        assertEquals(recipe.outputs(), netted.outputs());
        assertFalse(netted.hasCatalysts());
    }
}
