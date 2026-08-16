package dev.mfp.core.solver;

import dev.mfp.core.model.ChanceMode;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Closes the over-counting the model carried from M3.
 *
 * <p>Each case is one where reading a single output's {@code amount × chance} gives the wrong
 * answer, which is precisely the reason the group has to be resolved as a unit.
 */
class ChanceResolverTest {

    private static final double TOLERANCE = 1e-9;

    private static final MfpKey A = MfpKey.item("mfp", "a");
    private static final MfpKey B = MfpKey.item("mfp", "b");
    private static final MfpKey C = MfpKey.item("mfp", "c");

    private static Map<MfpKey, Double> byKey(List<ChanceResolver.Resolved> resolved) {
        return resolved.stream().collect(Collectors.toMap(
                ChanceResolver.Resolved::key, ChanceResolver.Resolved::perCraft, Double::sum));
    }

    @Test
    @DisplayName("independent rolls are just amount times chance")
    void independent() {
        Map<MfpKey, Double> result = byKey(ChanceResolver.resolve(List.of(
                MfpOutput.chanced(A, 2, 0.5),
                MfpOutput.chanced(B, 1, 0.25))));

        assertEquals(1.0, result.get(A), TOLERANCE);
        assertEquals(0.25, result.get(B), TOLERANCE);
    }

    @Test
    @DisplayName("all-or-nothing: three outputs at 50% yield an eighth each, not a half")
    void allOrNothing() {
        // The M3 model would have said 0.5 each. The group only fires when every member passes.
        Map<MfpKey, Double> result = byKey(ChanceResolver.resolve(List.of(
                new MfpOutput(A, 1, 0.5, ChanceMode.ALL_OR_NOTHING, "g"),
                new MfpOutput(B, 1, 0.5, ChanceMode.ALL_OR_NOTHING, "g"),
                new MfpOutput(C, 1, 0.5, ChanceMode.ALL_OR_NOTHING, "g"))));

        assertEquals(0.125, result.get(A), TOLERANCE);
        assertEquals(0.125, result.get(B), TOLERANCE);
        assertEquals(0.125, result.get(C), TOLERANCE);
    }

    @Test
    @DisplayName("all-or-nothing is reported as approximate, since GregTech correlates the rolls")
    void allOrNothingIsHonestAboutCorrelation() {
        List<ChanceResolver.Resolved> resolved = ChanceResolver.resolve(List.of(
                new MfpOutput(A, 1, 0.5, ChanceMode.ALL_OR_NOTHING, "g"),
                new MfpOutput(B, 1, 0.5, ChanceMode.ALL_OR_NOTHING, "g")));

        assertTrue(resolved.stream().noneMatch(ChanceResolver.Resolved::exact));
    }

    @Test
    @DisplayName("first-only: later members are conditional on the earlier ones failing")
    void firstOnly() {
        Map<MfpKey, Double> result = byKey(ChanceResolver.resolve(List.of(
                new MfpOutput(A, 1, 0.5, ChanceMode.FIRST_ONLY, "g"),
                new MfpOutput(B, 1, 0.5, ChanceMode.FIRST_ONLY, "g"),
                new MfpOutput(C, 1, 0.5, ChanceMode.FIRST_ONLY, "g"))));

        assertEquals(0.5, result.get(A), TOLERANCE);
        assertEquals(0.25, result.get(B), TOLERANCE);
        assertEquals(0.125, result.get(C), TOLERANCE);
        // Summing the naive per-item expectations would claim 1.5 items per craft from a group
        // that produces at most one.
        assertTrue(result.values().stream().mapToDouble(Double::doubleValue).sum() <= 1.0);
    }

    @Test
    @DisplayName("exclusive: exactly one member is produced, weighted by chance")
    void exclusive() {
        Map<MfpKey, Double> result = byKey(ChanceResolver.resolve(List.of(
                new MfpOutput(A, 1, 0.2, ChanceMode.EXCLUSIVE, "g"),
                new MfpOutput(B, 1, 0.3, ChanceMode.EXCLUSIVE, "g"))));

        // GregTech renormalises the group to sum to one before selecting, so chances that sum to
        // half are scaled up rather than leaving half the crafts empty-handed.
        assertEquals(0.4, result.get(A), TOLERANCE);
        assertEquals(0.6, result.get(B), TOLERANCE);
        assertEquals(1.0, result.values().stream().mapToDouble(Double::doubleValue).sum(), TOLERANCE);
    }

    @Test
    @DisplayName("a group of one behaves like an ordinary roll under every mode")
    void singletonGroup() {
        Map<MfpKey, Double> result = byKey(ChanceResolver.resolve(List.of(
                new MfpOutput(A, 4, 0.25, ChanceMode.EXCLUSIVE, "g"))));

        assertEquals(1.0, result.get(A), TOLERANCE);
    }

    @Test
    @DisplayName("overclocking raises chanced yields by the recipe's boost")
    void chanceBoost() {
        MfpOutput boosted = new MfpOutput(A, 1, 0.25, 0.1, ChanceMode.INDEPENDENT, null);

        assertEquals(0.25, byKey(ChanceResolver.resolve(List.of(boosted), 0)).get(A), TOLERANCE);
        assertEquals(0.55, byKey(ChanceResolver.resolve(List.of(boosted), 3)).get(A), TOLERANCE);
        // Clamped: a chance cannot exceed certainty however many overclocks are stacked.
        assertEquals(1.0, byKey(ChanceResolver.resolve(List.of(boosted), 50)).get(A), TOLERANCE);
    }

    @Test
    @DisplayName("outputs with no boost are unaffected by overclocking")
    void unboostedOutputsIgnoreOverclocks() {
        MfpOutput plain = MfpOutput.chanced(A, 1, 0.3);
        assertEquals(0.3, byKey(ChanceResolver.resolve(List.of(plain), 5)).get(A), TOLERANCE);
        assertFalse(plain.hasChanceBoost());
    }
}
