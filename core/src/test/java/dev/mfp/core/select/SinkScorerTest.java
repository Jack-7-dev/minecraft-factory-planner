package dev.mfp.core.select;

import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ranking that makes surplus a question worth asking (M18).
 *
 * <p>Every case here states which term it is about in its name, because the ordering is the whole
 * of the milestone that is not a screen: offering thirty ways to sink hydrogen sulfide in the
 * index's own order is not an answer, and the only thing separating this class from that is the
 * order it produces.
 */
class SinkScorerTest {

    private static final MfpKey SURPLUS = MfpKey.fluid("mfp", "surplus");
    private static final MfpKey WANTED = MfpKey.item("mfp", "wanted");
    private static final MfpKey ALSO_WANTED = MfpKey.item("mfp", "also_wanted");
    private static final MfpKey SLUDGE = MfpKey.item("mfp", "sludge");
    private static final MfpKey WATER = MfpKey.fluid("mfp", "water");
    private static final MfpKey EXOTIC = MfpKey.item("mfp", "exotic");

    /** A consumer of the surplus: eats {@code eats} of it and makes {@code out}. */
    private static MfpRecipe sink(String id, double eats, MfpKey out, MfpKey... alsoNeeds) {
        MfpRecipe.Builder builder = MfpRecipe.builder(id, "mfp:machine", "test")
                .input(MfpIngredient.of(SURPLUS, eats))
                .duration(20)
                .euIn(16);
        if (out != null) {
            builder.output(MfpOutput.of(out, 1));
        }
        for (MfpKey need : alsoNeeds) {
            builder.input(MfpIngredient.of(need, 1));
        }
        return builder.build();
    }

    /** A plan that wants {@link #WANTED} and is buying {@link #ALSO_WANTED}. */
    private static SinkScorer.Context context() {
        return new SinkScorer.Context() {
            @Override
            public boolean wanted(MfpKey key) {
                return key.equals(WANTED) || key.equals(ALSO_WANTED);
            }

            @Override
            public boolean imported(MfpKey key) {
                return key.equals(ALSO_WANTED);
            }

            @Override
            public boolean produced(MfpKey key) {
                return key.equals(WATER);
            }
        };
    }

    private static List<String> ids(List<SinkScorer.Scored> ranked) {
        return ranked.stream().map(scored -> scored.recipe().id()).toList();
    }

    @Test
    @DisplayName("a consumer that feeds the plan outranks one that merely destroys the surplus")
    void feedingBeatsDisposal() {
        MfpRecipe feeds = sink("mfp:feeds", 1, WANTED);
        MfpRecipe destroys = sink("mfp:destroys", 1, SLUDGE);

        List<SinkScorer.Scored> ranked =
                SinkScorer.rank(SURPLUS, List.of(destroys, feeds), context());

        assertEquals(List.of("mfp:feeds", "mfp:destroys"), ids(ranked));
        assertTrue(ranked.get(1).reasons().stream()
                        .anyMatch(reason -> reason.startsWith("disposal:")),
                "a disposal route says so rather than being unexplained");
    }

    @Test
    @DisplayName("feeding something the plan is buying outranks feeding something it already makes")
    void removingAnImportRanksHighest() {
        MfpRecipe feedsAnImport = sink("mfp:import", 1, ALSO_WANTED);
        MfpRecipe feedsTheFactory = sink("mfp:internal", 1, WANTED);

        List<SinkScorer.Scored> ranked =
                SinkScorer.rank(SURPLUS, List.of(feedsTheFactory, feedsAnImport), context());

        assertEquals(List.of("mfp:import", "mfp:internal"), ids(ranked));
        assertTrue(ranked.get(0).reasons().stream()
                .anyMatch(reason -> reason.contains("currently importing")));
    }

    @Test
    @DisplayName("a chanced useful output is worth half of a guaranteed one")
    void chancedOutputIsHalfCredit() {
        MfpRecipe guaranteed = sink("mfp:sure", 1, WANTED);
        MfpRecipe chanced = MfpRecipe.builder("mfp:maybe", "mfp:machine", "test")
                .input(MfpIngredient.of(SURPLUS, 1))
                .output(MfpOutput.chanced(WANTED, 1, 0.1))
                .duration(20)
                .build();

        List<SinkScorer.Scored> ranked =
                SinkScorer.rank(SURPLUS, List.of(chanced, guaranteed), context());

        assertEquals(List.of("mfp:sure", "mfp:maybe"), ids(ranked));
        assertEquals(SinkScorer.FEEDS_THE_PLAN * SinkScorer.CHANCED_FRACTION,
                ranked.get(0).score() - ranked.get(1).score(), 1e-9);
    }

    @Test
    @DisplayName("a sink needing chains the plan does not have falls below one needing nothing")
    void newInputsCost() {
        MfpRecipe cheap = sink("mfp:cheap", 1, WANTED, WATER);
        MfpRecipe dear = sink("mfp:dear", 1, WANTED, EXOTIC, SLUDGE);

        List<SinkScorer.Scored> ranked = SinkScorer.rank(SURPLUS, List.of(dear, cheap), context());

        assertEquals(List.of("mfp:cheap", "mfp:dear"), ids(ranked));
        // Water is already made by the plan, so the cheap route is charged for nothing at all.
        assertTrue(ranked.get(0).reasons().stream()
                .anyMatch(reason -> reason.contains("needs nothing the plan has not already got")));
        assertTrue(ranked.get(1).reasons().stream()
                .anyMatch(reason -> reason.contains("needs 2 more things")));
    }

    @Test
    @DisplayName("a recipe that hands the surplus straight back ranks below every honest disposal")
    void givingItBackIsNotSinkingIt() {
        MfpRecipe roundTrip = MfpRecipe.builder("mfp:round_trip", "mfp:machine", "test")
                .input(MfpIngredient.of(SURPLUS, 1))
                .output(MfpOutput.of(WANTED, 1))
                .output(MfpOutput.of(SURPLUS, 1))
                .duration(20)
                .build();
        MfpRecipe destroys = sink("mfp:destroys", 1, SLUDGE);

        List<SinkScorer.Scored> ranked =
                SinkScorer.rank(SURPLUS, List.of(roundTrip, destroys), context());

        assertEquals(List.of("mfp:destroys", "mfp:round_trip"), ids(ranked),
                "feeding the plan does not redeem a recipe that consumes none of the surplus");
    }

    @Test
    @DisplayName("a recipe that only holds the item as a catalyst is not offered as a sink")
    void catalystsAreNotSinks() {
        MfpRecipe catalyst = MfpRecipe.builder("mfp:catalyst", "mfp:machine", "test")
                .input(MfpIngredient.notConsumed(SURPLUS))
                .input(MfpIngredient.of(WATER, 1))
                .output(MfpOutput.of(WANTED, 1))
                .duration(20)
                .build();

        assertTrue(SinkScorer.rank(SURPLUS, List.of(catalyst), context()).isEmpty(),
                "an index lookup answers 'mentions this'; a sink has to eat it");
    }

    @Test
    @DisplayName("unpackaging the surplus into another form of itself ranks below destroying it")
    void repackagingIsNotEating() {
        MfpKey smallSurplus = MfpKey.item("mfp", "surplus_small");
        MfpRecipe unpackage = sink("mfp:unpackage", 1, smallSurplus);
        MfpRecipe destroys = sink("mfp:destroys", 1, SLUDGE);

        SinkScorer.Context materials = new SinkScorer.Context() {
            @Override
            public String material(MfpKey key) {
                return key.equals(SURPLUS) || key.equals(smallSurplus) ? "ash" : "clay";
            }
        };

        List<SinkScorer.Scored> ranked =
                SinkScorer.rank(SURPLUS, List.of(unpackage, destroys), materials);

        assertEquals(List.of("mfp:destroys", "mfp:unpackage"), ids(ranked),
                "the plan's surplus does not shrink, it only gets a different name");
        assertTrue(ranked.get(1).reasons().stream()
                .anyMatch(reason -> reason.contains("only repackages it")));
    }

    @Test
    @DisplayName("but repackaging into something the plan wants beats destroying it")
    void repackagingLosesToAUseAndBeatsANonUse() {
        MfpRecipe intoWanted = sink("mfp:into_wanted", 1, WANTED);
        MfpRecipe destroys = sink("mfp:destroys", 1, SLUDGE);

        SinkScorer.Context materials = new SinkScorer.Context() {
            @Override
            public boolean wanted(MfpKey key) {
                return key.equals(WANTED);
            }

            @Override
            public String material(MfpKey key) {
                // WANTED is a different form of the same material - a plate of what the surplus is.
                return key.equals(SLUDGE) ? "clay" : "steel";
            }
        };

        List<SinkScorer.Scored> ranked =
                SinkScorer.rank(SURPLUS, List.of(destroys, intoWanted), materials);

        assertEquals(List.of("mfp:into_wanted", "mfp:destroys"), ids(ranked),
                "turning surplus ingots into the plates you are short of is repackaging, and right");
    }

    @Test
    @DisplayName("a pack with no material classification is not judged on repackaging at all")
    void repackagingIsSilentWithoutMaterials() {
        MfpRecipe one = sink("mfp:one", 1, SLUDGE);
        assertEquals(SinkScorer.NEEDS_NOTHING_NEW,
                SinkScorer.score(SURPLUS, one, SinkScorer.Context.NONE).score(), 1e-9);
    }

    @Test
    @DisplayName("a recipe that separates the surplus into other materials is a real sink")
    void separationIsNotRepackaging() {
        MfpKey smallSurplus = MfpKey.item("mfp", "surplus_small");
        MfpRecipe separate = MfpRecipe.builder("mfp:separate", "mfp:machine", "test")
                .input(MfpIngredient.of(SURPLUS, 1))
                .output(MfpOutput.of(smallSurplus, 1))
                .output(MfpOutput.of(SLUDGE, 1))
                .duration(20)
                .build();

        SinkScorer.Context materials = new SinkScorer.Context() {
            @Override
            public String material(MfpKey key) {
                return key.equals(SURPLUS) || key.equals(smallSurplus) ? "ash" : "clay";
            }
        };

        assertEquals(SinkScorer.NEEDS_NOTHING_NEW,
                SinkScorer.score(SURPLUS, separate, materials).score(), 1e-9,
                "one output of the same material and one of another is a centrifuge, not a packer");
    }

    @Test
    @DisplayName("of two ways of feeding the plan, the one that eats more per machine ranks first")
    void rateBreaksTheTie() {
        MfpRecipe hungry = sink("mfp:hungry", 8, WANTED);
        MfpRecipe trickle = sink("mfp:trickle", 1, WANTED);

        SinkScorer.Context rates = new SinkScorer.Context() {
            @Override
            public boolean wanted(MfpKey key) {
                return key.equals(WANTED);
            }

            @Override
            public double consumedPerSecond(MfpRecipe recipe, MfpKey key) {
                return recipe.id().equals("mfp:hungry") ? 8 : 1;
            }
        };

        List<SinkScorer.Scored> ranked = SinkScorer.rank(SURPLUS, List.of(trickle, hungry), rates);

        assertEquals(List.of("mfp:hungry", "mfp:trickle"), ids(ranked));
        // Eight times less is three halvings, so twelve points — the term is a tie-break and says so
        // by being smaller than any of the judgements above it.
        assertEquals(12, ranked.get(0).score() - ranked.get(1).score(), 1e-9);
    }

    @Test
    @DisplayName("an unknown rate is left alone rather than ranked last")
    void unknownRateIsNotAPenalty() {
        MfpRecipe known = sink("mfp:known", 1, SLUDGE);
        MfpRecipe unknown = sink("mfp:unknown", 1, SLUDGE);

        SinkScorer.Context oneRate = new SinkScorer.Context() {
            @Override
            public double consumedPerSecond(MfpRecipe recipe, MfpKey key) {
                return recipe.id().equals("mfp:known") ? 4 : 0;
            }
        };

        List<SinkScorer.Scored> ranked = SinkScorer.rank(SURPLUS, List.of(known, unknown), oneRate);
        assertEquals(ranked.get(0).score(), ranked.get(1).score(), 1e-9,
                "with only one rate known there is nothing to be hungry relative to");
    }

    @Test
    @DisplayName("the rate term stops at the window, so an item with thousands of consumers is cheap")
    void rateWindowIsBounded() {
        java.util.List<MfpRecipe> many = new java.util.ArrayList<>();
        for (int i = 0; i < SinkScorer.RATE_WINDOW + 20; i++) {
            many.add(sink("mfp:sink_" + i, 1, SLUDGE));
        }
        Set<String> asked = new java.util.LinkedHashSet<>();
        SinkScorer.Context counting = new SinkScorer.Context() {
            @Override
            public double consumedPerSecond(MfpRecipe recipe, MfpKey key) {
                asked.add(recipe.id());
                return 1;
            }
        };

        SinkScorer.rank(SURPLUS, many, counting);
        assertEquals(SinkScorer.RATE_WINDOW, asked.size());
        assertFalse(asked.contains("mfp:sink_" + (SinkScorer.RATE_WINDOW + 10)));
    }
}
