package dev.mfp.core.select;

import dev.mfp.core.index.RecipeIndex;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which machine a line opens on, when nothing about the machines themselves separates them.
 *
 * <p>The interesting cases are all ties. Two multiblocks take their voltage from a hatch, so neither
 * has a tier; both are crafted at a bench, so neither has a build tier either. Everything the
 * comparator normally decides on is equal, and what is left has to be a fact about the machines
 * rather than about their names.
 */
class MachinePickerTest {

    private static final String TYPE = "mfp:reactor";

    private static final MfpKey HV_HULL = MfpKey.item("mfp", "hv_hull");
    private static final MfpKey IV_EMITTER = MfpKey.item("mfp", "iv_emitter");
    private static final MfpKey PLATE = MfpKey.item("mfp", "plate");

    /**
     * Star-Technology's chemical reactors, reduced to the shape that broke.
     *
     * <p>Both multiblocks, both shaped crafting recipes, and the late-game one is built <em>from</em>
     * the ordinary one. Alphabetically "extreme" comes first, which is how every chemical line in the
     * pack came to default to a machine several ages out of reach.
     */
    private static RecipeIndex index() {
        RecipeIndex.Builder builder = RecipeIndex.builder();
        builder.beginProvider("test", 0);

        // The parts, each made at the tier its name says.
        builder.recipe(MfpRecipe.builder("mfp:assemble_hull", "mfp:assembler", "test")
                .input(MfpIngredient.of(PLATE, 4)).output(MfpOutput.of(HV_HULL, 1))
                .duration(20).euIn(16).minTier(3).build());
        builder.recipe(MfpRecipe.builder("mfp:assemble_emitter", "mfp:assembler", "test")
                .input(MfpIngredient.of(PLATE, 4)).output(MfpOutput.of(IV_EMITTER, 1))
                .duration(20).euIn(16).minTier(5).build());

        // Both machines crafted at a bench, so both report a build tier of zero.
        builder.recipe(MfpRecipe.builder("mfp:craft_large", "minecraft:crafting", "test")
                .input(MfpIngredient.of(HV_HULL, 1))
                .output(MfpOutput.of(MfpKey.item("mfp", "large_reactor"), 1))
                .duration(0).minTier(0).build());
        builder.recipe(MfpRecipe.builder("mfp:craft_extreme", "minecraft:crafting", "test")
                .input(MfpIngredient.of(MfpKey.item("mfp", "large_reactor"), 1))
                .input(MfpIngredient.of(IV_EMITTER, 2))
                .output(MfpOutput.of(MfpKey.item("mfp", "extreme_reactor"), 1))
                .duration(0).minTier(0).build());

        // Untiered, as a multiblock is: its voltage comes from its energy hatch.
        builder.machine(new MfpMachine("mfp:extreme_reactor", "Extreme Reactor", -1, 0,
                List.of(TYPE), true, List.of(), "test"));
        builder.machine(new MfpMachine("mfp:large_reactor", "Large Reactor", -1, 0,
                List.of(TYPE), true, List.of(), "test"));
        return builder.build();
    }

    private static MfpRecipe reaction() {
        return MfpRecipe.builder("mfp:react", TYPE, "test")
                .input(MfpIngredient.of(PLATE, 1))
                .output(MfpOutput.of(MfpKey.item("mfp", "product"), 1))
                .duration(20).euIn(16).minTier(1).build();
    }

    @Test
    @DisplayName("between two bench-crafted multiblocks, the one made of cheaper parts wins")
    void theCheaperPartsWin() {
        RecipeIndex index = index();
        List<MfpMachine> ranked = MachinePicker.candidates(index, reaction());

        assertEquals(List.of("mfp:large_reactor", "mfp:extreme_reactor"),
                ranked.stream().map(MfpMachine::id).toList(),
                "alphabetical order would have put the extreme reactor first");
    }

    @Test
    @DisplayName("the build tier says nothing when both are crafted, and the parts say everything")
    void theBuildTierCannotSeparateThem() {
        RecipeIndex index = index();
        MfpMachine large = index.machine("mfp:large_reactor");
        MfpMachine extreme = index.machine("mfp:extreme_reactor");

        assertEquals(0, MachinePicker.buildCost(index, large));
        assertEquals(0, MachinePicker.buildCost(index, extreme),
                "which is why the tie fell through to the id before M11.3");

        assertEquals(3, MachinePicker.partsCost(index, large), "an HV hull");
        assertEquals(5, MachinePicker.partsCost(index, extreme), "IV emitters");
    }
}
