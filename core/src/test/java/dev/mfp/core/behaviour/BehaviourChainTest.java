package dev.mfp.core.behaviour;

import dev.mfp.core.behaviour.gt.GtCoils;
import dev.mfp.core.behaviour.gt.ParallelHatchBehaviour;
import dev.mfp.core.behaviour.startcore.HellForgeBehaviour;
import dev.mfp.core.behaviour.startcore.SteamParallelBehaviour;
import dev.mfp.core.behaviour.startcore.ThreadingBehaviour;
import dev.mfp.core.model.Confidence;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.MachineConfig;
import dev.mfp.core.solver.BehaviourThroughputResolver;
import dev.mfp.core.solver.Throughput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end throughput for real machine shapes, hand-computed from the fork's source.
 *
 * <p>The GregTech blast furnace cases are the ones to check first if a number ever looks wrong: they
 * exercise the coil discount, its 900 K guard, the tier-dependent coil temperature and the
 * overclock, all in one chain.
 */
class BehaviourChainTest {

    private static final double TOLERANCE = 1e-6;

    private static final MfpKey DUST = MfpKey.item("mfp", "dust");
    private static final MfpKey INGOT = MfpKey.item("mfp", "ingot");

    /** A blast recipe shaped like {@code gtceu:electric_blast_furnace/nickel_zinc_ferrite}. */
    private static MfpRecipe blastRecipe() {
        return MfpRecipe.builder("test:blast", "gtceu:electric_blast_furnace", "test")
                .input(MfpIngredient.of(DUST, 1))
                .output(MfpOutput.of(INGOT, 1))
                .duration(400)
                .euIn(120)
                .minTier(2)
                .extra("gtceu:ebf_temp", 1500)
                .build();
    }

    private static MfpMachine machine(String id, boolean multiblock, int tier, String... modifiers) {
        return new MfpMachine(id, id, tier, GtTiers.voltage(tier),
                List.of("gtceu:electric_blast_furnace"), multiblock, List.of(modifiers), "test");
    }

    private static BehaviourThroughputResolver resolver(MfpMachine... machines) {
        return new BehaviourThroughputResolver(BehaviourRegistry.standard(), id -> {
            for (MfpMachine machine : machines) {
                if (machine.id().equals(id)) {
                    return machine;
                }
            }
            return null;
        });
    }

    @Test
    @DisplayName("EBF at the recipe's own tier: discount only, no overclock")
    void blastFurnaceAtRecipeTier() {
        MfpMachine ebf = machine("gtceu:electric_blast_furnace", true, -1, "ebf_oc", "batch_mode");
        // Kanthal at 2700 K, MV hatch: no tier bonus, so 1200 K of surplus is one discount step.
        MachineConfig config = MachineConfig.of(ebf.id(), GtTiers.MV)
                .withOption(GtCoils.OPTION_COIL, "kanthal");

        Throughput throughput = resolver(ebf).resolve(blastRecipe(), config);

        // 20 ticks per second over an unchanged 400 tick duration.
        assertEquals(20.0 / 400, throughput.craftsPerSecond(), TOLERANCE);
        // 120 EU/t x 0.95 x 20 ticks.
        assertEquals(120 * 0.95 * 20, throughput.euInPerSecond(), TOLERANCE);
    }

    @Test
    @DisplayName("EBF one tier up: the discount lands before the overclock, not after")
    void blastFurnaceOverclocked() {
        MfpMachine ebf = machine("gtceu:electric_blast_furnace", true, -1, "ebf_oc", "batch_mode");
        // HV hatch: coils read 2700 + 100 K, and one overclock is available.
        MachineConfig config = MachineConfig.of(ebf.id(), 3)
                .withOption(GtCoils.OPTION_COIL, "kanthal");

        Throughput throughput = resolver(ebf).resolve(blastRecipe(), config);

        // Discounted to 114 EU/t, which is still MV, so exactly one overclock fits under 512.
        assertEquals(20.0 / 200, throughput.craftsPerSecond(), TOLERANCE);
        assertEquals(114 * 4 * 20, throughput.euInPerSecond(), TOLERANCE);
        assertEquals(1, throughput.overclocks());
    }

    @Test
    @DisplayName("EBF rejects a recipe its coils cannot reach")
    void blastFurnaceRejectsColdCoils() {
        MfpMachine ebf = machine("gtceu:electric_blast_furnace", true, -1, "ebf_oc");
        MfpRecipe hot = MfpRecipe.builder("test:hot_blast", "gtceu:electric_blast_furnace", "test")
                .input(MfpIngredient.of(DUST, 1))
                .output(MfpOutput.of(INGOT, 1))
                .duration(400)
                .euIn(120)
                .minTier(2)
                .extra("gtceu:ebf_temp", 3000)
                .build();
        // Cupronickel reaches 1800 K, and an MV hatch adds nothing.
        MachineConfig config = MachineConfig.of(ebf.id(), GtTiers.MV)
                .withOption(GtCoils.OPTION_COIL, "cupronickel");

        Throughput throughput = resolver(ebf).resolve(hot, config);

        assertEquals(0.0, throughput.craftsPerSecond(), TOLERANCE);
        assertTrue(throughput.note().contains("1800 K"), throughput.note());
    }

    @Test
    @DisplayName("a hotter hatch raises the coils' reach by 100 K per tier above MV")
    void hatchTierRaisesCoilTemperature() {
        MfpMachine ebf = machine("gtceu:electric_blast_furnace", true, -1, "ebf_oc");
        MfpRecipe warm = MfpRecipe.builder("test:warm_blast", "gtceu:electric_blast_furnace", "test")
                .input(MfpIngredient.of(DUST, 1))
                .output(MfpOutput.of(INGOT, 1))
                .duration(400)
                .euIn(120)
                .minTier(2)
                .extra("gtceu:ebf_temp", 1850)
                .build();

        // Cupronickel alone falls 50 K short; an EV hatch adds 200 K and clears it.
        assertEquals(0.0, resolver(ebf).resolve(warm, MachineConfig.of(ebf.id(), GtTiers.MV)
                .withOption(GtCoils.OPTION_COIL, "cupronickel")).craftsPerSecond(), TOLERANCE);
        assertTrue(resolver(ebf).resolve(warm, MachineConfig.of(ebf.id(), 4)
                .withOption(GtCoils.OPTION_COIL, "cupronickel")).craftsPerSecond() > 0);
    }

    @Test
    @DisplayName("an unconfigured coil is unknown, not assumed")
    void blastFurnaceWithoutCoilIsUnknown() {
        MfpMachine ebf = machine("gtceu:electric_blast_furnace", true, -1, "ebf_oc");
        Throughput throughput = resolver(ebf)
                .resolve(blastRecipe(), MachineConfig.of(ebf.id(), GtTiers.MV));

        assertEquals(Confidence.UNKNOWN, throughput.confidence());
        assertTrue(throughput.note().contains("coil"), throughput.note());
    }

    @Test
    @DisplayName("batch mode is throughput-neutral and does not weaken confidence")
    void batchModeChangesNothing() {
        MfpMachine withBatch = machine("a", true, -1, "ebf_oc", "batch_mode");
        MfpMachine without = machine("b", true, -1, "ebf_oc");
        MachineConfig configA = MachineConfig.of("a", 3).withOption(GtCoils.OPTION_COIL, "kanthal");
        MachineConfig configB = MachineConfig.of("b", 3).withOption(GtCoils.OPTION_COIL, "kanthal");

        BehaviourThroughputResolver resolver = resolver(withBatch, without);
        assertEquals(resolver.resolve(blastRecipe(), configB).craftsPerSecond(),
                resolver.resolve(blastRecipe(), configA).craftsPerSecond(), TOLERANCE);
    }

    @Test
    @DisplayName("modifiers compose in the machine's declared order")
    void parallelHatchAppliesBeforeOverclock() {
        // The hatch multiplies EU/t before the overclock reads it, which can change how many
        // overclocks fit under the machine's voltage. Declaring them the other way round would
        // give a different answer, which is exactly why the chain is ordered.
        MfpMachine machine = machine("gtceu:mega", true, -1, "parallel_hatch", "ebf_oc");
        MachineConfig config = MachineConfig.of(machine.id(), 4)
                .withOption(GtCoils.OPTION_COIL, "kanthal")
                .withOption(ParallelHatchBehaviour.OPTION_PARALLELS, 4);

        List<MachineBehaviour> chain = resolver(machine).chainFor(blastRecipe(), config);

        assertEquals(List.of("parallel_hatch", "ebf_oc"),
                chain.stream().map(MachineBehaviour::id).toList());
    }

    @Test
    @DisplayName("an unrecognised machine runs the recipe as written and says it does not know")
    void unknownMachineIsFlagged() {
        MfpMachine odd = machine("pack:mystery", true, -1, "some_pack_modifier");
        Throughput throughput = resolver(odd).resolve(blastRecipe(), MachineConfig.of(odd.id(), 3));

        assertEquals(Confidence.UNKNOWN, throughput.confidence());
        assertEquals(20.0 / 400, throughput.craftsPerSecond(), TOLERANCE, "unchanged, not guessed");
        assertTrue(throughput.note().contains("some_pack_modifier"), throughput.note());
    }

    @Test
    @DisplayName("an override replaces the built-in behaviour rather than stacking with it")
    void overrideWins() {
        MfpMachine ebf = machine("gtceu:electric_blast_furnace", true, -1, "ebf_oc", "batch_mode");
        BehaviourRegistry registry = BehaviourRegistry.standard()
                .override(new BehaviourOverride("gtceu:electric_blast_furnace",
                        0.5, 2.0, 1.0, Confidence.APPROXIMATE, "measured in game"));

        Throughput throughput = new BehaviourThroughputResolver(registry, id -> ebf)
                .resolve(blastRecipe(), MachineConfig.of(ebf.id(), 3)
                        .withOption(GtCoils.OPTION_COIL, "kanthal"));

        assertEquals(20.0 / 200, throughput.craftsPerSecond(), TOLERANCE);
        assertEquals(120 * 2.0 * 20, throughput.euInPerSecond(), TOLERANCE,
                "the coil discount must not also apply");
        assertEquals(Confidence.APPROXIMATE, throughput.confidence());
    }

    // ------------------------------------------------------------ start_core

    private static MfpRecipe plainRecipe() {
        return MfpRecipe.builder("test:plain", "gtceu:electric_blast_furnace", "test")
                .input(MfpIngredient.of(DUST, 1))
                .output(MfpOutput.of(INGOT, 1))
                .duration(100)
                .euIn(30)
                .minTier(1)
                .build();
    }

    @Test
    @DisplayName("throughput boosting: four crafts over 1.6x the time, for 5% less power")
    void throughputBoosting() {
        MfpMachine superMulti = machine("start_core:super_macerator", true, -1,
                "throughput_boosting", "batch_mode");
        Throughput throughput = resolver(superMulti)
                .resolve(plainRecipe(), MachineConfig.of(superMulti.id(), 1));

        // 20 / (100 x 1.6) x 4 crafts = 0.5/s, against 0.2/s unmodified: net x2.5.
        assertEquals(20.0 / 160 * 4, throughput.craftsPerSecond(), TOLERANCE);
        assertEquals(30 * 0.95 * 20, throughput.euInPerSecond(), TOLERANCE);
        assertEquals(Confidence.APPROXIMATE, throughput.confidence(),
                "the boost assumes the machine is kept fed");
    }

    @Test
    @DisplayName("bulk processing trades a little speed for a lot of efficiency")
    void bulkProcessing() {
        MfpMachine bulk = machine("start_core:bulk_processing_array", true, -1, "bulk_processing");
        Throughput throughput = resolver(bulk).resolve(plainRecipe(), MachineConfig.of(bulk.id(), 1));

        // 16 crafts over 13x the duration.
        assertEquals(20.0 / 1300 * 16, throughput.craftsPerSecond(), TOLERANCE);
        // EU/t is untouched, so energy per craft falls by 13/16.
        assertEquals(30.0 * 20, throughput.euInPerSecond(), TOLERANCE);
    }

    @Test
    @DisplayName("hell forge turns temperature surplus into parallels, not overclocks")
    void hellForge() {
        MfpMachine forge = machine("start_core:hell_forge", true, -1, HellForgeBehaviour.ID);
        MachineConfig config = MachineConfig.of(forge.id(), GtTiers.MV)
                .withOption(HellForgeBehaviour.OPTION_TEMPERATURE, 3300);

        // 1800 K of surplus over the recipe's 1500 K is four doublings' worth at 450 K each.
        Throughput throughput = resolver(forge).resolve(blastRecipe(), config);

        assertEquals(20.0 / 400 * 16, throughput.craftsPerSecond(), TOLERANCE);
        // Unlike the EBF, power is untouched: sixteen crafts for one cycle's energy.
        assertEquals(120.0 * 20, throughput.euInPerSecond(), TOLERANCE);
    }

    @Test
    @DisplayName("threading: speed points buy halvings on a triangular curve")
    void threading() {
        MfpMachine threaded = machine("start_core:threaded_assembler", true, -1, ThreadingBehaviour.ID);
        // 600 speed points is 6 marks, and sum(1..3) = 6, so exactly three halvings.
        MachineConfig config = MachineConfig.of(threaded.id(), 1)
                .withOption(ThreadingBehaviour.OPTION_SPEED_POINTS, 600)
                .withOption(ThreadingBehaviour.OPTION_EFFICIENCY_POINTS, 30);

        Throughput throughput = resolver(threaded).resolve(plainRecipe(), config);

        // Duration x 1/8, one parallel, so 8x the rate.
        assertEquals(20.0 / 12.5, throughput.craftsPerSecond(), TOLERANCE);
        // 30 efficiency points halve the draw: 30 / (30 + 30).
        assertEquals(30 * 0.5 * 20, throughput.euInPerSecond(), TOLERANCE);
    }

    @Test
    @DisplayName("threading parallels are sub-linear: N parallels give sqrt(N) throughput")
    void threadingParallelsAreSubLinear() {
        MfpMachine threaded = machine("start_core:threaded_assembler", true, -1, ThreadingBehaviour.ID);
        // 60 parallel points is floor(60/20) + 1 = 4 parallels.
        MachineConfig config = MachineConfig.of(threaded.id(), 1)
                .withOption(ThreadingBehaviour.OPTION_PARALLEL_POINTS, 60);

        Throughput throughput = resolver(threaded).resolve(plainRecipe(), config);

        // Four crafts over sqrt(4) = 2x the duration, so twice the rate rather than four times.
        assertEquals(20.0 / 200 * 4, throughput.craftsPerSecond(), TOLERANCE);
    }

    @Test
    @DisplayName("steam machines refuse anything above LV")
    void steamRejectsHighTier() {
        MfpMachine steam = machine("start_core:steam_ore_factory", true, -1, SteamParallelBehaviour.ID);
        MachineConfig config = MachineConfig.of(steam.id(), 1)
                .withOption(SteamParallelBehaviour.OPTION_MAX_PARALLELS, 8);

        Throughput throughput = resolver(steam).resolve(blastRecipe(), config);

        assertEquals(0.0, throughput.craftsPerSecond(), TOLERANCE);
        assertNotNull(throughput.note());
    }

    @Test
    @DisplayName("steam power is capped at 32 EU/t however many parallels run")
    void steamPowerIsCapped() {
        MfpMachine steam = machine("start_core:steam_ore_factory", true, -1, SteamParallelBehaviour.ID);
        MachineConfig config = MachineConfig.of(steam.id(), 1)
                .withOption(SteamParallelBehaviour.OPTION_MAX_PARALLELS, 8);

        // 30 EU/t x 0.8888 x 8 is well over 32, so the cap branch applies and the draw is pinned.
        Throughput throughput = resolver(steam).resolve(plainRecipe(), config);

        assertEquals(20.0 / 105 * 8, throughput.craftsPerSecond(), TOLERANCE);
        // Paid in steam at start_core's 3 mB per EU, and *not* also in EU: a machine that drew both
        // would be costed twice over, and the EU half of it does not exist.
        assertEquals(0.0, throughput.euInPerSecond(), TOLERANCE);
        assertEquals(32.0 * 20 * 3.0, throughput.steamPerSecond(), TOLERANCE);
    }

    /**
     * The number the pack's machines actually run at, which no configuration states.
     *
     * <p>{@code StarTSteamParallelMultiblockMachine}'s constructor calls {@code setMaxParallels(6)},
     * so an unconfigured steam multi is not an unknown — it is six parallels. This used to degrade to
     * {@link Confidence#UNKNOWN} and assume a single craft, which under-stated a steam ore factory's
     * throughput six-fold.
     */
    @Test
    @DisplayName("an unconfigured start_core steam multi runs its stock six parallels")
    void steamParallelsDefaultToTheMachinesOwn() {
        MfpMachine steam = machine("kubejs:steam_ore_factory", true, -1, SteamParallelBehaviour.ID);

        Throughput throughput = resolver(steam).resolve(plainRecipe(), MachineConfig.of(steam.id(), 1));

        assertEquals(20.0 / 105 * 6, throughput.craftsPerSecond(), TOLERANCE);
        assertEquals(Confidence.APPROXIMATE, throughput.confidence());
    }

    /**
     * GregTech's own steam multis, which have no usable modifier id at all.
     *
     * <p>{@code GTMultiMachines} passes {@code SteamParallelMultiblockMachine::recipeModifier}
     * inline, so every modifier declared in that class reports the same synthetic name. Matching on
     * the machine id is the only thing that can tell the steam grinder from a large chemical
     * reactor, which is why this behaviour is shape-matched.
     */
    @Test
    @DisplayName("GregTech's steam grinder is recognised by machine id, at 8 parallels and 2 mB/EU")
    void gregTechSteamMultisAreMatchedByMachineId() {
        MfpMachine grinder = machine("gtceu:steam_grinder", true, -1,
                "lambda:com.gregtechceu.gtceu.common.data.machines.GTMultiMachines");

        Throughput throughput = resolver(grinder).resolve(plainRecipe(), MachineConfig.of(grinder.id(), 1));

        // 1.5x duration rather than start_core's 1.05x, and the same 32 EU/t cap.
        assertEquals(20.0 / 150 * 8, throughput.craftsPerSecond(), TOLERANCE);
        assertEquals(32.0 * 20 * 2.0, throughput.steamPerSecond(), TOLERANCE);
    }

    /** A bronze machine burns 1 mB of steam per EU; its high-pressure form burns 2. */
    @Test
    void singleBlockSteamMachinesBurnSteamRatherThanEu() {
        MfpMachine bronze = machine("gtceu:lp_steam_macerator", false, 0);

        Throughput throughput = resolver(bronze).resolve(plainRecipe(), MachineConfig.of(bronze.id(), 0));

        assertEquals(0.0, throughput.euInPerSecond(), TOLERANCE);
        assertEquals(30.0 * 20 * 1.0, throughput.steamPerSecond(), TOLERANCE);
    }
}
