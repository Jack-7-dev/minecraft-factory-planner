package dev.mfp.core.select;

import dev.mfp.core.index.RecipeIndex;
import dev.mfp.core.model.MaterialForm;
import dev.mfp.core.model.MaterialForms;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The refinement term: does a recipe make its inputs more refined, or less?
 *
 * <p>The scenario is the one M6c exists for. GregTech's arc furnace turns a manufactured item back
 * into an ingot, and that recipe scores beautifully on every local criterion — one input, one
 * guaranteed main product, low tier — which is why {@code /mfp plan 1 gtceu:aluminium_ingot} used
 * to bottom out importing a portable scanner (STATUS §4b.16). Nothing here names a machine: the
 * recipes are identical apart from what form their input is.
 */
class RecipeScorerTest {

    private static final MfpKey ORE = MfpKey.item("mfp", "aluminium_ore");
    private static final MfpKey DUST = MfpKey.item("mfp", "aluminium_dust");
    private static final MfpKey INGOT = MfpKey.item("mfp", "aluminium_ingot");
    private static final MfpKey SCANNER = MfpKey.item("mfp", "portable_scanner");
    private static final MfpKey MYSTERY = MfpKey.item("mfp", "strange_lump");

    /** Everything the fork would classify, and one item it would not. */
    private static final RecipeScorer.Oracle FORMS = new RecipeScorer.Oracle() {
        @Override
        public MaterialForm form(MfpKey key) {
            if (key.equals(ORE)) {
                return MaterialForm.of("ore", "mfp:aluminium");
            }
            if (key.equals(DUST)) {
                return MaterialForm.of("dust", "mfp:aluminium");
            }
            if (key.equals(INGOT)) {
                return MaterialForm.of("ingot", "mfp:aluminium");
            }
            if (key.equals(SCANNER)) {
                return MaterialForm.manufactured();
            }
            return null;
        }
    };

    private static MfpRecipe makesIngotFrom(String id, MfpKey input) {
        return MfpRecipe.builder(id, "mfp:machine", "test")
                .input(MfpIngredient.of(input, 1))
                .output(MfpOutput.of(INGOT, 1))
                .duration(20)
                .build();
    }

    @Test
    @DisplayName("a recipe eating a manufactured item ranks below one eating dust")
    void recyclingIsDemotedBelowRefining() {
        List<RecipeScorer.Scored> ranked = RecipeScorer.rank(INGOT,
                List.of(makesIngotFrom("mfp:arc_scanner", SCANNER),
                        makesIngotFrom("mfp:smelt_dust", DUST)),
                java.util.Set.of(), FORMS);

        assertEquals("mfp:smelt_dust", ranked.get(0).recipe().id());
        assertTrue(ranked.get(1).reasons().contains("consumes something more refined than it makes"));
        assertTrue(ranked.get(0).reasons().contains("refines its inputs"));
    }

    /**
     * The bigger the drop, the bigger the penalty — so a recipe eating something further from the
     * ground is worse than one eating something nearer to it, rather than all recycling being one
     * undifferentiated class.
     */
    @Test
    void thePenaltyScalesWithHowFarBackTheRecipeGoes() {
        double fromScanner = RecipeScorer.score(INGOT,
                makesIngotFrom("mfp:arc_scanner", SCANNER), java.util.Set.of(), FORMS).score();
        double fromDust = RecipeScorer.score(INGOT,
                makesIngotFrom("mfp:smelt_dust", DUST), java.util.Set.of(), FORMS).score();
        double fromOre = RecipeScorer.score(INGOT,
                makesIngotFrom("mfp:smelt_ore", ORE), java.util.Set.of(), FORMS).score();

        assertTrue(fromOre > fromDust, "ore -> ingot is three steps of refining, dust -> ingot is one");
        assertTrue(fromDust > fromScanner);
    }

    /**
     * The rule that stops the two earlier attempts recurring (STATUS §4b.16).
     *
     * <p>An item the game says nothing about — vanilla coal, sand, a mob drop — must score nothing
     * either way. Both previous attempts failed by assuming something about exactly these items:
     * one read them as primitives and punished the honest deep chains instead.
     */
    @Test
    @DisplayName("an unclassified item contributes no term at all")
    void unclassifiedItemsAreSilent() {
        double withOracle = RecipeScorer.score(INGOT,
                makesIngotFrom("mfp:from_mystery", MYSTERY), java.util.Set.of(), FORMS).score();
        double withoutOracle = RecipeScorer.score(INGOT,
                makesIngotFrom("mfp:from_mystery", MYSTERY), java.util.Set.of()).score();

        assertEquals(withoutOracle, withOracle, 1e-9);
    }

    /** A catalyst is not what the recipe is made from, so its form must not decide the direction. */
    @Test
    void aNonConsumedInputDoesNotCountAsAnInput() {
        MfpRecipe withCatalyst = MfpRecipe.builder("mfp:smelt_with_mould", "mfp:machine", "test")
                .input(MfpIngredient.of(DUST, 1))
                .input(new MfpIngredient(List.of(SCANNER), 1, false, 1.0))
                .output(MfpOutput.of(INGOT, 1))
                .duration(20)
                .build();

        assertTrue(RecipeScorer.score(INGOT, withCatalyst, java.util.Set.of(), FORMS)
                .reasons().contains("refines its inputs"));
    }

    /**
     * Which end of the ore chain to start from, where refinement gets it backwards on its own.
     *
     * <p>Ore → ingot is the longer climb, so the refinement term prefers to start at an ore block;
     * but in Star-Technology the ore block is the one form of the material nobody can obtain, which
     * is how a steel plan came to route its iron through basaltic mineral sand ore. Crushed beats
     * geode beats raw beats a plain ore block, and the margin has to survive refinement pulling the
     * other way.
     */
    @Test
    @DisplayName("smelting crushed ore beats smelting the ore block, despite the shorter climb")
    void prefersTheOreFormAPlayerCanObtain() {
        MfpKey oreBlock = MfpKey.item("mfp", "iron_ore");
        MfpKey crushed = MfpKey.item("mfp", "crushed_iron");
        MfpKey geode = MfpKey.item("mfp", "iron_geode");
        MfpKey rawOre = MfpKey.item("mfp", "raw_iron");

        RecipeScorer.Oracle ores = new RecipeScorer.Oracle() {
            @Override
            public MaterialForm form(MfpKey key) {
                if (key.equals(oreBlock)) {
                    return MaterialForm.of("ore", "mfp:iron");
                }
                if (key.equals(crushed)) {
                    return MaterialForm.of("crushedOre", "mfp:iron");
                }
                if (key.equals(geode)) {
                    return MaterialForm.of("geode", "mfp:iron");
                }
                if (key.equals(rawOre)) {
                    return MaterialForm.of("raw", "mfp:iron");
                }
                return key.equals(INGOT) ? MaterialForm.of("ingot", "mfp:iron") : null;
            }
        };

        List<RecipeScorer.Scored> ranked = RecipeScorer.rank(INGOT,
                List.of(makesIngotFrom("mfp:smelt_ore_block", oreBlock),
                        makesIngotFrom("mfp:smelt_raw", rawOre),
                        makesIngotFrom("mfp:smelt_geode", geode),
                        makesIngotFrom("mfp:smelt_crushed", crushed)),
                java.util.Set.of(), ores);

        assertEquals(List.of("mfp:smelt_crushed", "mfp:smelt_geode", "mfp:smelt_raw",
                        "mfp:smelt_ore_block"),
                ranked.stream().map(scored -> scored.recipe().id()).toList());
    }

    /** Nothing that is not an ore gets an opinion about ore forms. */
    @Test
    void theOreLadderIsSilentForEverythingElse() {
        double withForms = RecipeScorer.score(INGOT,
                makesIngotFrom("mfp:from_dust", DUST), java.util.Set.of(), FORMS).score();

        assertTrue(RecipeScorer.score(INGOT, makesIngotFrom("mfp:from_dust", DUST),
                        java.util.Set.of(), FORMS).reasons().stream()
                .noneMatch(reason -> reason.contains("ore")));
        assertEquals(withForms, RecipeScorer.score(INGOT,
                makesIngotFrom("mfp:from_dust", DUST), java.util.Set.of(), FORMS).score(), 1e-9);
    }

    /** The index carries the forms, and says nothing rather than guessing for an item it lacks. */
    @Test
    void theIndexRemembersFormsAndReturnsNullForTheRest() {
        RecipeIndex.Builder builder = RecipeIndex.builder();
        builder.beginProvider("test", 0);
        builder.recipe(makesIngotFrom("mfp:smelt_dust", DUST));
        builder.form(DUST, MaterialForm.of("dust", "mfp:aluminium"));
        RecipeIndex index = builder.build();

        assertEquals(2, MaterialForms.rankOf(index.form(DUST)));
        assertEquals(MaterialForms.UNRANKED, MaterialForms.rankOf(index.form(MYSTERY)));
        assertEquals(1, index.formCount());
    }

    // ------------------------------------------------------- the terminal-recipe rule (M10)

    /**
     * The stone barrel, reduced to its shape: water in, cobblestone out, a lava catalyst.
     *
     * <p>This is the comparison the term was built to settle (STATUS §9.13). Both recipes make
     * cobblestone; one consumes nothing the plan has to obtain and the other consumes stone, which
     * sends expansion off into the chemistry that makes stone. Before M10 the barrel lost by 23
     * points, on terms — input count, tier — that describe the recipe rather than what following it
     * would cost.
     */
    @Test
    @DisplayName("a recipe whose every consumed input is already raw is the end of a chain")
    void aRecipeConsumingOnlyRawMaterialsOutranksOneThatOpensANewChain() {
        MfpKey water = MfpKey.fluid("mfp", "water");
        MfpKey lava = MfpKey.fluid("mfp", "lava");
        MfpKey stone = MfpKey.item("mfp", "stone");
        MfpKey cobblestone = MfpKey.item("mfp", "cobblestone");
        RecipeScorer.Oracle waterIsRaw = new RecipeScorer.Oracle() {
            @Override
            public boolean isRaw(MfpKey key) {
                return key.equals(water);
            }
        };

        MfpRecipe barrel = MfpRecipe.builder("mfp:stone_barrel", "mfp:barrel", "test")
                .input(MfpIngredient.of(water, 1000))
                .input(MfpIngredient.notConsumed(lava))
                .output(MfpOutput.of(cobblestone, 1))
                .duration(20).build();
        MfpRecipe fromStone = MfpRecipe.builder("mfp:crush_stone", "mfp:machine", "test")
                .input(MfpIngredient.of(stone, 1))
                .output(MfpOutput.of(cobblestone, 1))
                .duration(20).build();

        List<RecipeScorer.Scored> ranked = RecipeScorer.rank(cobblestone,
                List.of(fromStone, barrel), java.util.Set.of(), waterIsRaw);

        assertEquals("mfp:stone_barrel", ranked.get(0).recipe().id(),
                () -> "expected the terminal recipe first, got " + ranked);
        assertTrue(ranked.get(0).reasons().contains("everything it consumes is already raw"));

        // And the term is about the plan's raw set, not the recipe: with nothing declared raw the
        // two rank as they always did. That is what keeps this from being §8.3's reverted
        // distance-to-raw metric in a new coat.
        assertEquals("mfp:crush_stone", RecipeScorer.rank(cobblestone,
                List.of(fromStone, barrel), java.util.Set.of(), RecipeScorer.Oracle.NONE)
                .get(0).recipe().id());
    }

    /**
     * The catalyst is why the rule needs "consumed" rather than "inputs".
     *
     * <p>The barrel's lava is never used up, so it is not something the plan has to obtain and it
     * cannot be the reason the recipe fails to terminate. Left in, the rule would have missed the
     * one recipe it was written for.
     */
    @Test
    void anInputThePlanNeverHasToObtainDoesNotStopARecipeBeingTerminal() {
        MfpKey water = MfpKey.fluid("mfp", "water");
        MfpKey unobtainable = MfpKey.item("mfp", "singularity");
        MfpKey product = MfpKey.item("mfp", "cobblestone");
        RecipeScorer.Oracle waterIsRaw = new RecipeScorer.Oracle() {
            @Override
            public boolean isRaw(MfpKey key) {
                return key.equals(water);
            }
        };

        MfpRecipe borrows = MfpRecipe.builder("mfp:borrows", "mfp:barrel", "test")
                .input(MfpIngredient.of(water, 1000))
                .input(MfpIngredient.notConsumed(unobtainable))
                .output(MfpOutput.of(product, 1))
                .duration(20).build();
        MfpRecipe eats = MfpRecipe.builder("mfp:eats", "mfp:barrel", "test")
                .input(MfpIngredient.of(water, 1000))
                .input(MfpIngredient.of(unobtainable, 1))
                .output(MfpOutput.of(product, 1))
                .duration(20).build();

        assertTrue(RecipeScorer.score(product, borrows, java.util.Set.of(), waterIsRaw)
                .reasons().contains("everything it consumes is already raw"));
        assertTrue(RecipeScorer.score(product, eats, java.util.Set.of(), waterIsRaw)
                .reasons().stream().noneMatch(reason -> reason.contains("already raw")));
    }
}
