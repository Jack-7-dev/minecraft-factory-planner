package dev.mfp.core.select;

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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tier the player builds at, as a requirement rather than a preference (M17 slice A).
 *
 * <p>Every test here is about one distinction: <b>refusing</b> a recipe is not <b>charging</b> for
 * it. A scorer that charges four points a tier will take a LuV route whenever nothing else is within
 * sixty points, and will take it at any score at all when it is the only route — which is how the
 * pack's HV motor plan came back with fourteen lines the player cannot build.
 *
 * <p>The other half of the milestone's evidence is the fixtures that are <em>not</em> here. With no
 * tier stated the ceiling is inert, and that is asserted by leaving every existing chooser fixture
 * alone and keeping it green rather than by writing a test that says so.
 */
class TierCeilingTest {

    private static final MfpKey ORE = MfpKey.item("mfp", "ore");
    private static final MfpKey DUST = MfpKey.item("mfp", "dust");
    private static final MfpKey INGOT = MfpKey.item("mfp", "ingot");
    private static final MfpKey PLATE = MfpKey.item("mfp", "plate");
    private static final MfpKey EXOTIC = MfpKey.item("mfp", "exotic");

    /** The tier the player builds at throughout. Three, as the pack's own test world says. */
    private static final int HV = 3;

    private static MfpRecipe recipe(String id, int minTier, MfpKey in, MfpKey out) {
        return recipe(id, "mfp:machine", minTier, in, out);
    }

    private static MfpRecipe recipe(String id, String type, int minTier, MfpKey in, MfpKey out) {
        return MfpRecipe.builder(id, type, "test")
                .input(MfpIngredient.of(in, 1))
                .output(MfpOutput.of(out, 1))
                .duration(20)
                .euIn(16)
                .minTier(minTier)
                .build();
    }

    /**
     * Machines for {@code mfp:machine} at LV, HV and IV, and nothing at all for
     * {@code mfp:unmachined} — the {@code start:plasma_generator} case, a recipe type the index
     * knows and no machine runs.
     */
    private static RecipeIndex indexOf(MfpRecipe... recipes) {
        RecipeIndex.Builder builder = RecipeIndex.builder();
        builder.beginProvider("test", 0);
        for (MfpRecipe recipe : recipes) {
            builder.recipe(recipe);
        }
        builder.machine(new MfpMachine("mfp:lv_machine", "LV Machine", 1, 32,
                List.of("mfp:machine"), false, List.of(), "test"));
        builder.machine(new MfpMachine("mfp:hv_machine", "HV Machine", 3, 512,
                List.of("mfp:machine"), false, List.of(), "test"));
        builder.machine(new MfpMachine("mfp:iv_machine", "IV Machine", 5, 8192,
                List.of("mfp:machine", "mfp:iv_only"), false, List.of(), "test"));
        return builder.build();
    }

    private static Preferences atHv() {
        return new Preferences().defaultTier(HV);
    }

    private static List<String> recipeIds(ChooserResult result) {
        return result.lines().stream().map(line -> line.recipe().id()).toList();
    }

    @Test
    @DisplayName("an over-tier recipe loses to a lower one, and the ceiling is why")
    void overTierLosesToALowerOne() {
        // The over-tier route is deliberately the one the scorer prefers - fewer steps, straight
        // from the ore - so a test that passes because the cheap route happened to win proves
        // nothing. It wins here only because the other one is refused.
        //
        // And the ingot is an input rather than the target, because a target is exempt. Every test
        // below that expects a refusal is shaped this way for the same reason.
        RecipeIndex index = indexOf(
                recipe("mfp:plate", 1, INGOT, PLATE),
                recipe("mfp:direct", 7, ORE, INGOT),
                recipe("mfp:dust", 1, ORE, DUST),
                recipe("mfp:ingot", 1, DUST, INGOT));

        Plan plan = new Plan("test").target(PLATE, 1).rawMaterial(ORE);
        ChooserResult result = new RecipeChooser(index, atHv()).expand(plan);

        assertEquals(List.of("mfp:plate", "mfp:ingot", "mfp:dust"), recipeIds(result));
    }

    @Test
    @DisplayName("the only recipe being over-tier leaves the item imported, with the tier named")
    void theOnlyRouteBeingOverTierIsAnImport() {
        RecipeIndex index = indexOf(
                recipe("mfp:plate", 1, INGOT, PLATE),
                recipe("mfp:ingot", 7, ORE, INGOT));

        Plan plan = new Plan("test").target(PLATE, 1).rawMaterial(ORE);
        ChooserResult result = new RecipeChooser(index, atHv()).expand(plan);

        assertEquals(List.of("mfp:plate"), recipeIds(result));
        assertTrue(result.unresolved().contains(INGOT), () -> "expected an import, got " + result);
        String reason = result.importReasons().get(INGOT);
        assertNotNull(reason, () -> "no reason recorded, only " + result.importReasons());
        // The tier, not merely the fact. "You cannot build this" without a number is a dead end;
        // with one it is a decision - raise the tier, pin it, or route around.
        assertTrue(reason.contains("tier 7"), () -> "reason does not name the tier: " + reason);
        assertTrue(reason.contains("tier " + HV), () -> "reason does not name yours: " + reason);
    }

    @Test
    @DisplayName("the ceiling propagates: an item every route to which is over-tier poisons its consumers")
    void theCeilingPropagates() {
        // EXOTIC is only ever made at IV, and only mfp:plate_exotic uses it. Refusing the recipe is
        // not enough: without the closure the walk would pick mfp:plate_exotic, fail one level up,
        // and report EXOTIC as a mystery import rather than taking the other route to a plate.
        RecipeIndex index = indexOf(
                recipe("mfp:exotic", 5, ORE, EXOTIC),
                recipe("mfp:plate_exotic", 1, EXOTIC, PLATE),
                recipe("mfp:ingot", 1, ORE, INGOT),
                recipe("mfp:plate", 1, INGOT, PLATE));

        Plan plan = new Plan("test").target(PLATE, 1).rawMaterial(ORE);
        ChooserResult result = new RecipeChooser(index, atHv()).expand(plan);

        assertEquals(List.of("mfp:plate", "mfp:ingot"), recipeIds(result));
        assertTrue(result.isComplete(), () -> "rerouted plans have no imports: " + result);
    }

    @Test
    @DisplayName("a target above the ceiling is still expanded; the same item as an input is not")
    void aTargetIsExempt() {
        RecipeIndex index = indexOf(
                recipe("mfp:exotic", 5, ORE, EXOTIC),
                recipe("mfp:plate_exotic", 1, EXOTIC, PLATE));

        // Asking "how do I make this" has an answer, and refusing to answer the very thing that was
        // asked hands back an empty plan.
        ChooserResult asked = new RecipeChooser(index, atHv())
                .expand(new Plan("target").target(EXOTIC, 1).rawMaterial(ORE));
        assertEquals(List.of("mfp:exotic"), recipeIds(asked));

        // The same item one level down is a step in answering a different question, and is refused.
        ChooserResult below = new RecipeChooser(index, atHv())
                .expand(new Plan("input").target(PLATE, 1).rawMaterial(ORE));
        assertEquals(List.of("mfp:plate_exotic"), recipeIds(below));
        assertTrue(below.unresolved().contains(EXOTIC), () -> "expected an import, got " + below);
    }

    @Test
    @DisplayName("the exemption is a last resort, not a free pass: a target with a buildable route takes it")
    void aTargetPrefersARouteItCanBuild() {
        // The narrower half of the rule above, and it was a real fault. Exempting a target outright
        // exempts its *inputs* too, and the pack's tungsten plan answered with "melt down an IV
        // parallel hatch" - two lines, an unbuildable import, and hundreds of buildable routes
        // ignored. The scorer liked it because recycling one machine part yields six metals at once;
        // nothing but the ceiling was ever going to refuse it.
        RecipeIndex index = indexOf(
                // The recycling recipe: cheap, few inputs, and its one input is out of reach.
                recipe("mfp:recycle", 1, EXOTIC, INGOT),
                recipe("mfp:exotic", 7, ORE, EXOTIC),
                recipe("mfp:dust", 1, ORE, DUST),
                recipe("mfp:ingot", 1, DUST, INGOT));

        ChooserResult result = new RecipeChooser(index, atHv())
                .expand(new Plan("test").target(INGOT, 1).rawMaterial(ORE));

        assertFalse(recipeIds(result).contains("mfp:recycle"),
                () -> "a target took an unbuildable route with a buildable one available: "
                        + recipeIds(result));
        assertEquals(List.of("mfp:ingot", "mfp:dust"), recipeIds(result));
    }

    @Test
    @DisplayName("a pin, a standing default and the plan's own switch all outrank the ceiling")
    void theThreeEscapes() {
        RecipeIndex index = indexOf(
                recipe("mfp:plate", 1, INGOT, PLATE),
                recipe("mfp:direct", 7, ORE, INGOT),
                recipe("mfp:dust", 1, ORE, DUST),
                recipe("mfp:ingot", 1, DUST, INGOT));
        List<String> both = List.of("mfp:plate", "mfp:direct");

        // A pin is the user's statement about this plan and this item, which is more specific than
        // a standing statement about the save.
        Plan pinned = new Plan("pinned").target(PLATE, 1).rawMaterial(ORE)
                .chooseRecipe(INGOT, "mfp:direct");
        assertEquals(both, recipeIds(new RecipeChooser(index, atHv()).expand(pinned)));

        // A standing default is the same kind of statement, one level less specific and still the
        // user's own.
        Preferences withDefault = atHv().defaultRecipe(INGOT, "mfp:direct");
        assertEquals(both, recipeIds(new RecipeChooser(index, withDefault)
                .expand(new Plan("default").target(PLATE, 1).rawMaterial(ORE))));

        // And the switch, for the player asking what it would take rather than what they can build.
        Plan switchedOff = new Plan("off").target(PLATE, 1).rawMaterial(ORE).tierCeiling(false);
        assertEquals(both, recipeIds(new RecipeChooser(index, atHv()).expand(switchedOff)));
    }

    @Test
    @DisplayName("the configured hatch never exceeds the ceiling")
    void theHatchObeysTheCeiling() {
        // Fault 3 of the milestone, pinned where it happens rather than where it prints: the [T7] in
        // the pack's motor plan was not the machine's tier, it was the hatch MFP configured to make
        // an over-tier recipe run - MFP quietly assuming the player owned a ZPM energy hatch.
        RecipeIndex index = indexOf(
                recipe("mfp:ingot", 1, ORE, INGOT),
                recipe("mfp:direct", 7, ORE, PLATE));

        Plan plan = new Plan("test").target(INGOT, 1).rawMaterial(ORE);
        MachineConfig config = MachinePicker.pick(index, index.recipe("mfp:ingot"), plan, atHv());
        assertTrue(config.tier() <= HV,
                () -> "configured tier " + config.tier() + " is above the ceiling");

        // And the machine itself: the IV machine runs this type and is no longer offered, where
        // before the ceiling it merely sorted last.
        assertEquals("mfp:hv_machine", config.machineId());
    }

    @Test
    @DisplayName("a recipe whose type no machine in the index runs is never picked, at any tier")
    void aRecipeWithNoMachineIsNeverPicked() {
        // Unbuildable at every tier is the limiting case of unbuildable at this one.
        // start:plasma_generator has no machine in the pack and still ranked twelfth of the
        // twenty-nine ways to make nitrogen.
        RecipeIndex index = indexOf(
                recipe("mfp:plate", 1, INGOT, PLATE),
                recipe("mfp:phantom", "mfp:unmachined", 0, ORE, INGOT),
                recipe("mfp:dust", 1, ORE, DUST),
                recipe("mfp:ingot", 1, DUST, INGOT));

        Plan plan = new Plan("test").target(PLATE, 1).rawMaterial(ORE);
        ChooserResult result = new RecipeChooser(index, atHv()).expand(plan);

        assertFalse(recipeIds(result).contains("mfp:phantom"),
                () -> "picked a recipe nothing can run: " + recipeIds(result));
        assertEquals(List.of("mfp:plate", "mfp:ingot", "mfp:dust"), recipeIds(result));
    }

    @Test
    @DisplayName("one bound covers every predicate")
    void oneBoundForEveryPredicate() {
        // The milestone's claim, and it is only true while these are equal: the blacklist's closure
        // and the ceiling's are the same mechanism making the same claim about how far a refusal
        // reaches, so a chain the blacklist would follow six deep is one the ceiling follows six
        // deep. Two constants because they live either side of a class boundary; asserted here
        // rather than trusted to the comment beside each of them.
        assertEquals(RecipeChooser.MAX_BLOCK_ROUNDS, TierCeiling.MAX_ROUNDS);
    }

    @Test
    @DisplayName("with no tier stated the ceiling changes nothing, including the machineless case")
    void inertWithNoTierStated() {
        RecipeIndex index = indexOf(
                recipe("mfp:plate", 1, INGOT, PLATE),
                recipe("mfp:phantom", "mfp:unmachined", 0, ORE, INGOT),
                recipe("mfp:direct", 7, ORE, INGOT),
                recipe("mfp:dust", 1, ORE, DUST),
                recipe("mfp:ingot", 1, DUST, INGOT));

        Plan plan = new Plan("test").target(PLATE, 1).rawMaterial(ORE);
        ChooserResult stated = new RecipeChooser(index, atHv()).expand(plan);
        ChooserResult unstated = new RecipeChooser(index,
                new Preferences().defaultTier(Preferences.NO_DEFAULT_TIER)).expand(plan);

        // Not "the same plan" - the point is that the two differ, and that the unstated one is free
        // to reach for anything. Asserted from the other end: the ceiling refuses something here,
        // so a run that refused nothing is evidence the filter is off rather than merely unexercised.
        assertFalse(recipeIds(stated).contains("mfp:direct"));
        assertTrue(recipeIds(unstated).contains("mfp:direct")
                        || recipeIds(unstated).contains("mfp:phantom"),
                () -> "with no tier stated nothing should be refused, but got " + recipeIds(unstated));
    }
}
