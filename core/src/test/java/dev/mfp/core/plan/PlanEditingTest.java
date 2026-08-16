package dev.mfp.core.plan;

import dev.mfp.core.behaviour.MachineBehaviour;
import dev.mfp.core.behaviour.BehaviourRegistry;
import dev.mfp.core.behaviour.OptionSpec;
import dev.mfp.core.index.RecipeIndex;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.select.MachinePicker;
import dev.mfp.core.select.RecipeChooser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the GUI needs from the model in order to edit a plan (M6b).
 *
 * <p>Every one of these is a claim the screens rely on and cannot check for themselves: the widget
 * layer has no automated test at all (STATUS §6.11), so the editing operations underneath it are
 * where the guarantees have to live. The recurring question is <b>what survives a re-solve</b> —
 * editing works by clearing the lines and expanding again, so anything the user chose that does not
 * survive that round trip silently reverts a moment after they set it.
 */
class PlanEditingTest {

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
        builder.machine(new MfpMachine("mfp:hv_machine", "HV Machine", 3, 512,
                List.of("mfp:machine"), false, List.of(), "test"));
        return builder.build();
    }

    @Test
    @DisplayName("targets can be re-rated and removed by index, not by key")
    void targetsAreEditableByIndex() {
        // By index because a plan may legitimately want the same item twice at different rates,
        // and an edit addressed by key would merge the two without saying so.
        Plan plan = new Plan("test").target(PLATE, 1).target(PLATE, 4);

        plan.setTarget(0, new TargetOutput(PLATE, 2));
        assertEquals(2, plan.targets().get(0).perSecond());
        assertEquals(4, plan.targets().get(1).perSecond());

        plan.removeTarget(0);
        assertEquals(1, plan.targets().size());
        assertEquals(4, plan.targets().get(0).perSecond());
    }

    @Test
    @DisplayName("a built machine configuration survives re-expansion")
    void machineConfigSurvivesReExpansion() {
        RecipeIndex index = index();
        Plan plan = new Plan("test").target(PLATE, 1).rawMaterial(ORE);
        new RecipeChooser(index).expandInto(plan);

        MachineConfig built = MachineConfig.of("mfp:hv_machine", 3)
                .withParallels(2)
                .withOption("coil", "kanthal");
        plan.configureMachine("mfp:plate", built);

        plan.clearLines();
        new RecipeChooser(index).expandInto(plan);

        Line plateLine = plan.allLines().stream()
                .filter(line -> line.recipe().id().equals("mfp:plate"))
                .findFirst().orElseThrow();
        assertEquals("mfp:hv_machine", plateLine.machine().machineId());
        assertEquals(2, plateLine.machine().parallels());
        assertEquals("kanthal", plateLine.machine().structureOptions().get("coil"));

        // And only that line: the configuration describes one build, not a policy for the type.
        Line ingotLine = plan.allLines().stream()
                .filter(line -> line.recipe().id().equals("mfp:ingot"))
                .findFirst().orElseThrow();
        assertEquals("mfp:lv_machine", ingotLine.machine().machineId());
    }

    @Test
    @DisplayName("a configuration naming a machine that cannot run the recipe is ignored, not obeyed")
    void impossibleConfigFallsBackToTheDefault() {
        RecipeIndex index = index();
        Plan plan = new Plan("test");
        plan.configureMachine("mfp:plate", MachineConfig.of("mfp:nonexistent", 5));

        MachineConfig picked = MachinePicker.pick(index, index.recipe("mfp:plate"), plan);
        assertEquals("mfp:lv_machine", picked.machineId());
    }

    @Test
    @DisplayName("a recipe pin can be dropped again")
    void pinsCanBeCleared() {
        Plan plan = new Plan("test").chooseRecipe(PLATE, "mfp:plate");
        assertEquals("mfp:plate", plan.recipeChoice(PLATE));
        plan.clearRecipeChoice(PLATE);
        assertNull(plan.recipeChoice(PLATE));
    }

    @Test
    @DisplayName("a derived matrix mode reverts on re-expansion; a chosen one does not")
    void derivedSolverModeIsNotSticky() {
        Plan derived = new Plan("test").deriveSolverMode(SolverMode.MATRIX);
        assertTrue(derived.solverModeDerived());
        derived.clearLines();
        assertEquals(SolverMode.AUTO, derived.solverMode());

        // The user's own choice is not a guess to be revisited: a re-solve that quietly moved them
        // back to AUTO would be the same untrustworthiness a reverting recipe pin has.
        Plan chosen = new Plan("test").solverMode(SolverMode.MATRIX);
        assertFalse(chosen.solverModeDerived());
        chosen.clearLines();
        assertEquals(SolverMode.MATRIX, chosen.solverMode());
    }

    @Test
    @DisplayName("every behaviour that reads a structure option declares it")
    void behavioursDeclareTheOptionsTheyRead() {
        // The machine-config screen builds its fields from these declarations, so a behaviour that
        // reads an option without declaring it tells the user their number is a guess and offers no
        // way to improve it. Checked against the registry rather than a list of classes, so a new
        // behaviour is covered the day it is registered.
        List<MachineBehaviour> declaring = BehaviourRegistry.standard().allBehaviours().stream()
                .filter(behaviour -> !behaviour.options().isEmpty())
                .toList();
        assertFalse(declaring.isEmpty(), "no behaviour declares any structure option");

        for (MachineBehaviour behaviour : declaring) {
            for (OptionSpec spec : behaviour.options()) {
                assertFalse(spec.key().isBlank(), behaviour.id() + " declares a blank option key");
                assertFalse(spec.label().isBlank(), spec.key() + " has no label");
                if (spec.kind() == OptionSpec.Kind.CHOICE) {
                    assertFalse(spec.choices().isEmpty(), spec.key() + " offers no choices");
                }
            }
        }
    }

    @Test
    @DisplayName("MachineConfig edits keep the settings they do not touch")
    void configEditsArePartial() {
        MachineConfig config = MachineConfig.of("mfp:lv_machine", 1)
                .withParallels(3)
                .withOption("coil", "kanthal")
                .withLimit(4, true);

        MachineConfig moved = config.withMachine("mfp:hv_machine", 3);
        assertEquals(3, moved.parallels());
        assertEquals("kanthal", moved.structureOptions().get("coil"));
        assertEquals(4.0, moved.limit());
        assertTrue(moved.forceLimit());

        assertFalse(moved.withoutLimit().hasLimit());
        assertFalse(moved.withoutOption("coil").structureOptions().containsKey("coil"));
        assertSame(MachineConfig.UNSET.machineId(), null);
    }
}
