package dev.mfp.core.select;

import dev.mfp.core.behaviour.BehaviourRegistry;
import dev.mfp.core.behaviour.OptionSpec;
import dev.mfp.core.behaviour.gt.GtCoils;
import dev.mfp.core.index.RecipeIndex;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.MachineConfig;
import dev.mfp.core.plan.MachineDefaults;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.plan.Preferences;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The standing build of one machine: "my blast furnace has HSS-G coils and an EV hatch".
 *
 * <p>Every test here is the same claim from a different side — <b>a build beats the general default
 * and loses to the plan</b> — because that ordering is the only thing that makes it safe to apply
 * a decision the player made weeks ago to a line they are looking at now.
 */
class MachineDefaultsTest {

    private static final String TYPE = "mfp:blasting";
    private static final MfpKey DUST = MfpKey.item("mfp", "dust");
    private static final MfpKey INGOT = MfpKey.item("mfp", "ingot");

    private static final String FURNACE = "mfp:blast_furnace";

    private static RecipeIndex index() {
        RecipeIndex.Builder builder = RecipeIndex.builder();
        builder.beginProvider("test", 0);
        builder.recipe(blasting());
        // A multiblock, so its voltage is the hatch's and there is a build to describe.
        builder.machine(new MfpMachine(FURNACE, "Blast Furnace", -1, 0,
                List.of(TYPE), true, List.of("ebf_oc"), "test"));
        return builder.build();
    }

    private static MfpRecipe blasting() {
        return MfpRecipe.builder("mfp:blast_ingot", TYPE, "test")
                .input(MfpIngredient.of(DUST, 1))
                .output(MfpOutput.of(INGOT, 1))
                .duration(200).euIn(120).minTier(1).build();
    }

    private static MachineConfig picked(Preferences preferences) {
        return MachinePicker.pick(index(), blasting(), new Plan("test"), preferences);
    }

    @Test
    @DisplayName("a machine's own build lands on a line nobody has configured")
    void theBuildReachesTheLine() {
        Preferences preferences = Preferences.none().machineDefaults(FURNACE,
                MachineDefaults.NONE.withOption(GtCoils.OPTION_COIL, "hss_g").withParallels(4));

        MachineConfig config = picked(preferences);

        assertEquals(FURNACE, config.machineId());
        assertEquals("hss_g", config.structureOptions().get(GtCoils.OPTION_COIL));
        assertEquals(4, config.parallels());
    }

    @Test
    @DisplayName("the machine's own hatch beats the tier the player builds at in general")
    void theMachineTierBeatsTheDefaultTier() {
        Preferences preferences = Preferences.none().defaultTier(3)
                .machineDefaults(FURNACE, MachineDefaults.NONE.withTier(5));

        assertEquals(5, picked(preferences).tier(),
                "\"my blast furnace has an IV hatch\" is the more specific of the two statements");

        assertEquals(3, picked(Preferences.none().defaultTier(3)).tier(),
                "and with nothing said about this machine, the general answer still applies");
    }

    @Test
    @DisplayName("a recipe the machine's hatch cannot run still gets a hatch that can")
    void theRecipeMinimumStillWins() {
        Preferences preferences = Preferences.none()
                .machineDefaults(FURNACE, MachineDefaults.NONE.withTier(0));
        MfpRecipe demanding = MfpRecipe.builder("mfp:blast_hard", TYPE, "test")
                .input(MfpIngredient.of(DUST, 1))
                .output(MfpOutput.of(INGOT, 1))
                .duration(200).euIn(1920).minTier(4).build();

        assertEquals(4, MachinePicker.pick(index(), demanding, new Plan("test"), preferences).tier(),
                "a hatch too small to start the recipe is not a plan, it is a machine that idles");
    }

    @Test
    @DisplayName("a line the player built out by hand keeps its own build")
    void thePlanBeatsTheStandingBuild() {
        Preferences preferences = Preferences.none().machineDefaults(FURNACE,
                MachineDefaults.NONE.withTier(5).withOption(GtCoils.OPTION_COIL, "hss_g"));
        Plan plan = new Plan("built").configureMachine("mfp:blast_ingot",
                MachineConfig.of(FURNACE, 2).withOption(GtCoils.OPTION_COIL, "cupronickel"));

        MachineConfig config = MachinePicker.pick(index(), blasting(), plan, preferences);

        assertEquals(2, config.tier());
        assertEquals("cupronickel", config.structureOptions().get(GtCoils.OPTION_COIL),
                "the coils they described for this line, not the ones they usually have");
    }

    @Test
    @DisplayName("a build with nothing left in it is stored as no build at all")
    void anEmptyBuildIsForgotten() {
        Preferences preferences = Preferences.none()
                .machineDefaults(FURNACE, MachineDefaults.NONE.withParallels(4));
        assertFalse(preferences.machineDefaults().isEmpty());

        preferences.machineDefaults(FURNACE,
                preferences.machineDefaults(FURNACE).withoutParallels());

        assertTrue(preferences.machineDefaults().isEmpty(),
                "otherwise the Machines list fills with entries that say nothing");
        assertTrue(preferences.isEmpty());
        assertNull(picked(preferences).structureOptions().get(GtCoils.OPTION_COIL));
    }

    @Test
    @DisplayName("a machine offers the build options its own declared modifiers read")
    void theMachineDeclaresItsOwnOptions() {
        BehaviourRegistry registry = BehaviourRegistry.standard();

        List<String> ebf = registry.optionsFor(index().machine(FURNACE)).stream()
                .map(OptionSpec::key).toList();
        assertTrue(ebf.contains(GtCoils.OPTION_COIL), () -> "the blast furnace offered " + ebf);

        // Nothing declared, so nothing to ask about: a screen that offered a coil for a macerator
        // would be inviting the player to describe a structure it does not have.
        MfpMachine plain = new MfpMachine("mfp:macerator", "Macerator", 1, 32,
                List.of("mfp:macerating"), false, List.of(), "test");
        assertEquals(List.of(), registry.optionsFor(plain));
    }
}
