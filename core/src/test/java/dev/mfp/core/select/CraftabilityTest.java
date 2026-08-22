package dev.mfp.core.select;

import dev.mfp.core.behaviour.GtTiers;
import dev.mfp.core.index.RecipeIndex;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.MachineConfig;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.plan.Preferences;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Craftable" is a question about parts, not about one recipe (M17 slice B).
 *
 * <p>The ceiling is only as good as its answer to "can I build this machine", and the two things
 * {@link MachinePicker} computes are a comparator's answers: {@code buildCost} is the tier of the
 * machine's own recipe, and {@code partsCost} is the dearest ingredient of that recipe <b>one level
 * deep, deliberately</b>. One level is enough to separate the pack's large chemical reactor from its
 * extreme one and is not enough in general — an HV assembler is not buildable by someone who cannot
 * yet make the coils inside it. These tests are about the depth.
 */
class CraftabilityTest {

    private static final MfpKey ORE = MfpKey.item("mfp", "ore");
    private static final MfpKey PLATE = MfpKey.item("mfp", "plate");
    private static final MfpKey WIDGET = MfpKey.item("mfp", "widget");
    private static final MfpKey COIL = MfpKey.item("mfp", "coil");
    private static final MfpKey EXOTIC = MfpKey.item("mfp", "exotic");

    /** The machines themselves, as items — a machine is an item like any other. */
    private static final MfpKey CHEAP_MACHINE = MfpKey.item("mfp", "cheap_machine");
    private static final MfpKey DEEP_MACHINE = MfpKey.item("mfp", "deep_machine");
    private static final MfpKey RIG = MfpKey.item("mfp", "rig");

    private static final int HV = 3;

    private static final class Fixture {
        private final RecipeIndex.Builder builder = RecipeIndex.builder();
        private final List<MfpMachine> machines = new ArrayList<>();

        private Fixture() {
            builder.beginProvider("test", 0);
        }

        /** A recipe of type {@code mfp:machine}, which the cheap and deep machines both run. */
        Fixture recipe(String id, int minTier, MfpKey in, MfpKey out) {
            return recipe(id, "mfp:machine", minTier, in, out);
        }

        Fixture recipe(String id, String type, int minTier, MfpKey in, MfpKey out) {
            builder.recipe(MfpRecipe.builder(id, type, "test")
                    .input(MfpIngredient.of(in, 1))
                    .output(MfpOutput.of(out, 1))
                    .duration(20)
                    .euIn(16)
                    .minTier(minTier)
                    .build());
            return this;
        }

        /** How a machine is built: a bench recipe, so nothing about its tier is in the way. */
        Fixture built(MfpKey machine, MfpKey from) {
            builder.recipe(MfpRecipe.builder("mfp:assemble_" + machine.path(), "mfp:bench", "test")
                    .input(MfpIngredient.of(from, 1))
                    .output(MfpOutput.of(machine, 1))
                    .duration(20)
                    .minTier(0)
                    .build());
            return this;
        }

        /** An item whose tier is a gate: a GregTech cover component or a circuit. */
        Fixture component(MfpKey key, int tier) {
            builder.componentTier(key, tier);
            return this;
        }

        Fixture machine(String id, int tier, boolean multiblock, String... types) {
            machines.add(new MfpMachine("mfp:" + id, id, tier, 512,
                    List.of(types), multiblock, List.of(), "test"));
            return this;
        }

        RecipeIndex build() {
            // A bench, so the machine-assembly recipes above are runnable rather than machineless.
            builder.machine(new MfpMachine("mfp:bench_block", "Bench", 0, 0,
                    List.of("mfp:bench"), false, List.of(), "test"));
            machines.forEach(builder::machine);
            return builder.build();
        }
    }

    private static Preferences atHv() {
        return new Preferences().defaultTier(HV);
    }

    private static List<String> recipeIds(ChooserResult result) {
        return result.lines().stream().map(line -> line.recipe().id()).toList();
    }

    @Test
    @DisplayName("craftable is transitive: a machine whose part's part is out of reach is out of reach")
    void craftabilityIsTransitive() {
        // Three levels. The deep machine is assembled from a widget, the widget from a coil, and the
        // coil is only ever made at IV. Nothing about the deep machine's own recipe says any of
        // that: it is a bench craft, so buildCost is zero, and partsCost sees only the widget, which
        // is made at LV. One level deep answers "yes, build it"; the truth is three levels up.
        RecipeIndex index = new Fixture()
                // The target is one step above the plate, because a target is exempt from the
                // ceiling and a fixture that forgot that would pass for the wrong reason.
                .recipe("mfp:final", "mfp:cheap", 1, PLATE, EXOTIC)
                .recipe("mfp:plate_deep", 1, ORE, PLATE)
                .recipe("mfp:coil", 5, ORE, COIL)
                .recipe("mfp:widget", 1, COIL, WIDGET)
                .built(DEEP_MACHINE, WIDGET)
                .machine("deep_machine", 1, false, "mfp:machine")
                .machine("cheap_block", 1, false, "mfp:cheap")
                .build();

        Plan plan = new Plan("test").target(EXOTIC, 1).rawMaterial(ORE);
        RecipeChooser chooser = new RecipeChooser(index, atHv());
        ChooserResult result = chooser.expand(plan);

        // The plate has one route, it runs on the deep machine, and the deep machine cannot be had.
        assertTrue(result.unresolved().contains(PLATE), () -> "expected an import, got " + result);
        String reason = result.importReasons().get(PLATE);
        assertNotNull(reason, () -> "no reason recorded, only " + result.importReasons());
        assertTrue(reason.contains("mfp:coil") || reason.contains("mfp:deep_machine"),
                () -> "reason names neither the machine nor the part: " + reason);

        MfpKey missing = chooser.missingPartOf(index.machine("mfp:deep_machine"), plan);
        assertEquals(COIL, missing, () -> "expected the coil three levels up, got " + missing);
    }

    @Test
    @DisplayName("two levels is not enough on its own: the part between is craftable and the plan still is not")
    void twoLevelsIsNotTheAnswerEither() {
        // The same shape one level shallower, so the pair of tests brackets the depth: partsCost
        // sees the widget here and calls it cheap, which is true and beside the point.
        RecipeIndex index = new Fixture()
                .recipe("mfp:final", "mfp:cheap", 1, PLATE, EXOTIC)
                .recipe("mfp:plate_deep", 1, ORE, PLATE)
                .recipe("mfp:widget", 5, ORE, WIDGET)
                .built(DEEP_MACHINE, WIDGET)
                .machine("deep_machine", 1, false, "mfp:machine")
                .machine("cheap_block", 1, false, "mfp:cheap")
                .build();

        Plan plan = new Plan("test").target(EXOTIC, 1).rawMaterial(ORE);
        RecipeChooser chooser = new RecipeChooser(index, atHv());

        assertEquals(WIDGET, chooser.missingPartOf(index.machine("mfp:deep_machine"), plan));
        assertTrue(chooser.expand(plan).unresolved().contains(PLATE));
    }

    @Test
    @DisplayName("a machine the player can build wins over one they cannot, whatever the comparator thinks")
    void thePickerPrefersAMachineYouCanBuild() {
        // The extreme-versus-large case: two machines run the type, and the one that sorts first is
        // the one built out of the other. Under a ceiling that is not a tie to break, it is a
        // candidate to drop.
        RecipeIndex index = new Fixture()
                .recipe("mfp:plate", 1, ORE, PLATE)
                .recipe("mfp:coil", 5, ORE, COIL)
                .built(DEEP_MACHINE, COIL)
                .built(CHEAP_MACHINE, ORE)
                .machine("cheap_machine", 1, false, "mfp:machine")
                .machine("deep_machine", 1, false, "mfp:machine")
                .build();

        Plan plan = new Plan("test").target(PLATE, 1).rawMaterial(ORE);
        MachineConfig config = new RecipeChooser(index, atHv()).expand(plan)
                .lines().get(0).machine();
        assertEquals("mfp:cheap_machine", config.machineId());
    }

    @Test
    @DisplayName("a cycle in the buildability graph terminates")
    void aCycleTerminates() {
        // GregTech has these - a machine whose parts are made on machines of its own kind - and a
        // fixpoint that does not say so hangs on the pack rather than in a test.
        RecipeIndex index = new Fixture()
                .recipe("mfp:plate", 1, WIDGET, PLATE)
                .recipe("mfp:widget", 1, PLATE, WIDGET)
                .built(DEEP_MACHINE, PLATE)
                .built(CHEAP_MACHINE, WIDGET)
                .machine("cheap_machine", 1, false, "mfp:machine")
                .machine("deep_machine", 1, false, "mfp:machine")
                .build();

        Plan plan = new Plan("test").target(PLATE, 1);
        RecipeChooser chooser = new RecipeChooser(index, atHv());
        assertNull(chooser.missingPartOf(index.machine("mfp:deep_machine"), plan));
        assertFalse(chooser.expand(plan).lines().isEmpty());
    }

    @Test
    @DisplayName("a declared raw material ends the recursion")
    void aRawMaterialEndsIt() {
        // "These I own" is one statement in MFP, not two: the raw set is both "the world gives me
        // this" and "I have a supply of this", and there is deliberately no second list beside it.
        RecipeIndex index = new Fixture()
                .recipe("mfp:plate", 1, ORE, PLATE)
                .recipe("mfp:coil", 5, ORE, COIL)
                .built(DEEP_MACHINE, COIL)
                .machine("deep_machine", 1, false, "mfp:machine")
                .build();

        RecipeChooser chooser = new RecipeChooser(index, atHv());
        MfpMachine machine = index.machine("mfp:deep_machine");

        Plan without = new Plan("without").target(PLATE, 1).rawMaterial(ORE);
        assertEquals(COIL, chooser.missingPartOf(machine, without));

        Plan owning = new Plan("owning").target(PLATE, 1).rawMaterial(ORE).rawMaterial(COIL);
        assertNull(new RecipeChooser(index, atHv()).missingPartOf(machine, owning),
                "declaring the coil a raw material should end the search above it");
    }

    @Test
    @DisplayName("the unlock tier is the lowest that clears the whole chain, not the first obstacle")
    void theUnlockTierIsTheLowestThatClearsTheChain() {
        // Two obstacles at different tiers, the cheaper one first. Reporting the tier of the first
        // thing that failed would say 4; clearing it only reveals the next, and the answer is 5.
        RecipeIndex index = new Fixture()
                .recipe("mfp:plate", 1, EXOTIC, PLATE)
                .recipe("mfp:exotic", 4, WIDGET, EXOTIC)
                .recipe("mfp:widget", 5, ORE, WIDGET)
                .machine("hv_machine", HV, false, "mfp:machine")
                // A machine at exactly 5, so the answer is the recipe's own voltage rather than
                // the tier of the only machine that happens to exist.
                .machine("iv_machine", 5, false, "mfp:machine")
                .build();

        Plan plan = new Plan("test").target(PLATE, 1).rawMaterial(ORE);
        ChooserResult result = new RecipeChooser(index, atHv()).expand(plan);

        String reason = result.importReasons().get(EXOTIC);
        assertNotNull(reason, () -> "no reason recorded, only " + result.importReasons());
        assertTrue(reason.contains("nearest tier that can is 5"),
                () -> "expected the tier that clears the chain, got: " + reason);
        assertTrue(reason.contains(GtTiers.name(5)), () -> "the tier is unnamed: " + reason);
    }

    @Test
    @DisplayName("an untiered multiblock is two questions, and a fixture with each wrong separately")
    void aMultiblockIsTwoQuestions() {
        // Its tier is -1, which tierRank sorts last among equals: a sensible default for a
        // comparator and no answer at all for a ceiling. So both questions are asked, and they are
        // asked in different places - the hatch against the recipe, the structure against the
        // fixpoint - which is what these two halves pin.
        RecipeIndex index = new Fixture()
                // Buildable rig, but the recipe wants a hatch above the ceiling.
                .recipe("mfp:hot", "mfp:rig", 7, ORE, PLATE)
                // Affordable hatch, but the rig itself is built out of something out of reach.
                .recipe("mfp:cold", "mfp:rig", 1, ORE, WIDGET)
                .recipe("mfp:coil", 5, ORE, COIL)
                .built(RIG, COIL)
                .machine("rig", -1, true, "mfp:rig")
                .machine("hv_machine", HV, false, "mfp:machine")
                .build();

        Plan plan = new Plan("test").target(PLATE, 1).rawMaterial(ORE);
        RecipeChooser chooser = new RecipeChooser(index, atHv());

        // The structure question, answered by the fixpoint rather than by the machine's own tier.
        assertEquals(COIL, chooser.missingPartOf(index.machine("mfp:rig"), plan));

        // Both recipes are refused, and for different reasons, which is the point of the fixture.
        String hatch = chooser.beyondCeiling(index.recipe("mfp:hot"), plan);
        String structure = chooser.beyondCeiling(index.recipe("mfp:cold"), plan);
        assertNotNull(hatch, "a recipe needing a tier 7 hatch is not runnable at tier 3");
        assertTrue(hatch.contains("tier 7"), () -> "the hatch answer should be about voltage: " + hatch);
        assertNotNull(structure, "a multiblock you cannot build is not a machine you have");
        assertTrue(structure.contains("mfp:coil"),
                () -> "the structure answer should name the part: " + structure);
    }

    @Test
    @DisplayName("a component's tier is a gate: a hand-craft recipe does not get you an IV emitter")
    void aComponentsTierIsAGate() {
        // The fault the first version shipped. gtceu:iv_emitter has a shaped crafting recipe and a
        // tier 1 assembler one, so "some recipe at or below your tier, every input likewise"
        // concludes that a player at HV can have one - and the pack's extreme chemical reactor,
        // which takes two of them and an IV circuit, was offered at HV on exactly that reasoning.
        // Here the coil is a tier 5 component made by a bench recipe out of nothing but ore.
        RecipeIndex index = new Fixture()
                .recipe("mfp:final", "mfp:cheap", 1, PLATE, EXOTIC)
                .recipe("mfp:plate_deep", 1, ORE, PLATE)
                .built(COIL, ORE)
                .component(COIL, 5)
                .built(DEEP_MACHINE, COIL)
                .machine("deep_machine", 1, false, "mfp:machine")
                .machine("cheap_block", 1, false, "mfp:cheap")
                .build();

        Plan plan = new Plan("test").target(EXOTIC, 1).rawMaterial(ORE);
        RecipeChooser chooser = new RecipeChooser(index, atHv());

        // Every ingredient of the coil's own recipe is free, and it is still out of reach.
        assertEquals(COIL, chooser.missingPartOf(index.machine("mfp:deep_machine"), plan));
        assertTrue(chooser.expand(plan).unresolved().contains(PLATE),
                "a machine built from an out-of-tier component is not a machine you have");

        // And it says so as a component rather than as a hole in the pack: "nothing at or below
        // tier 3 makes that" would be true and would read as something a pack update might fix.
        String why = chooser.beyondCeiling(index.recipe("mfp:plate_deep"), plan);
        assertNotNull(why);
        assertTrue(why.contains("component"), () -> "not named as a component: " + why);
        assertTrue(why.contains("tier 5"), () -> "the component's own tier is unnamed: " + why);
    }

    @Test
    @DisplayName("a component below the ceiling is not gated, and one the player owns is not either")
    void theGateIsADefaultNotADecree() {
        RecipeIndex index = new Fixture()
                .recipe("mfp:final", "mfp:cheap", 1, PLATE, EXOTIC)
                .recipe("mfp:plate_deep", 1, ORE, PLATE)
                .built(COIL, ORE)
                .component(COIL, 5)
                .built(WIDGET, ORE)
                .component(WIDGET, 2)
                .built(DEEP_MACHINE, COIL)
                .built(CHEAP_MACHINE, WIDGET)
                .machine("deep_machine", 1, false, "mfp:machine")
                .machine("cheap_machine", 1, false, "mfp:machine")
                .machine("cheap_block", 1, false, "mfp:cheap")
                .build();

        Plan plan = new Plan("test").target(EXOTIC, 1).rawMaterial(ORE);
        // An MV component at HV is simply a component you have.
        assertNull(new RecipeChooser(index, atHv())
                .missingPartOf(index.machine("mfp:cheap_machine"), plan));

        // And declaring the IV one a raw material is the player telling MFP something about their
        // save that no tag can know - the same statement that makes an ore raw. The gate loses to
        // it, as every other part of the ceiling loses to the user's own statements.
        Plan owning = new Plan("owning").target(EXOTIC, 1).rawMaterial(ORE).rawMaterial(COIL);
        assertNull(new RecipeChooser(index, atHv())
                .missingPartOf(index.machine("mfp:deep_machine"), owning));
    }

    @Test
    @DisplayName("with no tier stated nothing is judged craftable or otherwise")
    void inertWithNoTierStated() {
        RecipeIndex index = new Fixture()
                .recipe("mfp:plate", 1, ORE, PLATE)
                .recipe("mfp:coil", 5, ORE, COIL)
                .built(DEEP_MACHINE, COIL)
                .machine("deep_machine", 1, false, "mfp:machine")
                .build();

        Plan plan = new Plan("test").target(PLATE, 1).rawMaterial(ORE);
        RecipeChooser chooser = new RecipeChooser(index, Preferences.none());

        assertNull(chooser.missingPartOf(index.machine("mfp:deep_machine"), plan));
        assertEquals(List.of("mfp:plate"), recipeIds(chooser.expand(plan)));
    }
}
