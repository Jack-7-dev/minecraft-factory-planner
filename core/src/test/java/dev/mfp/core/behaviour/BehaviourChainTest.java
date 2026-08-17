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
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * The discount must not buy an overclock, however far it drops the EU/t.
     *
     * <p>GregTech writes this as {@code oc.compose(discount)}, which reads as "discount first" — but
     * {@code oc} was already built from the <em>raw</em> recipe, because {@code getModifier} reads
     * EU/t at construction time to decide how many overclocks there are. The two multipliers commute
     * so the final EU/t is the same either way; the overclock <em>count</em> is not.
     *
     * <p>It bites hardest where the answer matters most. The discount compounds at 0.95 per 900 K of
     * surplus, so a hot coil reaches 0.735 — far more than enough to drop a recipe a voltage tier and
     * hand the furnace an overclock the game never gives it. 130 EU/t is HV by a margin of two; three
     * discount steps put it at 111, which is MV, and MFP used to overclock accordingly.
     */
    @Test
    @DisplayName("the coil discount does not buy an overclock the hatch cannot pay for")
    void blastFurnaceDiscountDoesNotBuyAnOverclock() {
        MfpMachine ebf = machine("gtceu:electric_blast_furnace", true, -1, "ebf_oc");
        MfpRecipe recipe = MfpRecipe.builder("test:edge_blast", "gtceu:electric_blast_furnace", "test")
                .input(MfpIngredient.of(DUST, 1))
                .output(MfpOutput.of(INGOT, 1))
                .duration(400)
                .euIn(130)
                .minTier(3)
                .extra("gtceu:ebf_temp", 1000)
                .build();
        // Nichrome at 3600 K plus 100 K for the HV hatch: 2700 K of surplus is three discount steps.
        MachineConfig config = MachineConfig.of(ebf.id(), 3)
                .withOption(GtCoils.OPTION_COIL, "nichrome");

        Throughput throughput = resolver(ebf).resolve(recipe, config);

        // An HV recipe in an HV furnace: no headroom, so no overclock and the duration is untouched.
        assertEquals(0, throughput.overclocks());
        assertEquals(20.0 / 400, throughput.craftsPerSecond(), TOLERANCE);
        // The discount still applies in full - it is only the overclock it must not pay for.
        assertEquals(130 * Math.pow(0.95, 3) * 20, throughput.euInPerSecond(), TOLERANCE);
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

        // Duration x 1/8, one parallel: 100 ticks becomes 12.5, which the game runs in 12.
        assertEquals(20.0 / 12, throughput.craftsPerSecond(), TOLERANCE);
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

    // ------------------------------------------------- whole ticks (STATUS 15)

    /**
     * Shaped like {@code gtceu:macerator/macerate_aluminium_refined_ore_to_dust}: 400 ticks at
     * 2 EU/t, which is ULV, so a deep overclock drives the duration under two ticks.
     */
    private static MfpRecipe maceratorRecipe(int durationTicks, long eut) {
        return MfpRecipe.builder("test:macerate", "gtceu:macerator", "test")
                .input(MfpIngredient.of(DUST, 1))
                .output(MfpOutput.of(INGOT, 1))
                .duration(durationTicks)
                .euIn(eut)
                .minTier(0)
                .build();
    }

    private static MfpMachine macerator(String id, boolean multiblock, int tier, String... modifiers) {
        return new MfpMachine(id, id, tier, GtTiers.voltage(tier),
                List.of("gtceu:macerator"), multiblock, List.of(modifiers), "test");
    }

    /**
     * A machine cannot run for a fraction of a tick.
     *
     * <p>{@code GTRecipe.duration} is an {@code int} and every modifier truncates it, so the
     * arithmetic's 1.5625 ticks is 1 tick in the game — a 56% difference in throughput, and the one
     * place where a plan built at UV stopped matching what the machine actually did.
     */
    @Test
    @DisplayName("a duration below two ticks truncates to one, not to the fraction")
    void deepOverclocksRunInWholeTicks() {
        MfpMachine uhv = macerator("gtceu:uhv_macerator", false, 9, "oc_non_perfect");

        // 400 ticks, eight overclocks: 400 x 0.5^8 = 1.5625, which the game runs in 1.
        Throughput throughput = resolver(uhv).resolve(maceratorRecipe(400, 2),
                MachineConfig.of(uhv.id(), 9));

        assertEquals(8, throughput.overclocks());
        assertEquals(20.0 / 1, throughput.craftsPerSecond(), TOLERANCE);
    }

    @Test
    @DisplayName("and a duration between three and four ticks truncates to three")
    void shallowerOverclocksTruncateToo() {
        MfpMachine uv = macerator("gtceu:uv_macerator", false, 8, "oc_non_perfect");

        // 400 x 0.5^7 = 3.125.
        Throughput throughput = resolver(uv).resolve(maceratorRecipe(400, 2),
                MachineConfig.of(uv.id(), 8));

        assertEquals(20.0 / 3, throughput.craftsPerSecond(), TOLERANCE);
    }

    @Test
    @DisplayName("a duration that divides exactly is left alone")
    void exactDurationsAreUntouched() {
        MfpMachine hv = macerator("gtceu:hv_macerator", false, 3, "oc_non_perfect");

        // 200 ticks, 4 EU/t is ULV so one overclock is spent leaving it: 200 x 0.5^2 = 50.
        Throughput throughput = resolver(hv).resolve(maceratorRecipe(200, 4),
                MachineConfig.of(hv.id(), 3));

        assertEquals(2, throughput.overclocks());
        assertEquals(20.0 / 50, throughput.craftsPerSecond(), TOLERANCE);
    }

    /**
     * The pack's Large Macerator, which is the machine that exposed all of this: {@code oc_perfect}
     * quarters the duration per overclock instead of halving it, so it reaches the one-tick floor
     * two tiers earlier than an ordinary macerator and then stops dead, because it is not the
     * sub-tick variant and has nothing to spend the remaining overclocks on.
     */
    @Test
    @DisplayName("perfect overclocking is four times faster and stops where duration runs out")
    void perfectOverclockingIsFasterAndThenCapped() {
        MfpMachine large = macerator("gtceu:t_large_macerator", true, -1, "oc_perfect", "batch_mode");
        MfpMachine plain = macerator("gtceu:macerator", false, 3, "oc_non_perfect");
        BehaviourThroughputResolver resolver = resolver(large, plain);
        MfpRecipe recipe = maceratorRecipe(200, 4);

        // HV: two overclocks either way. 200 x 0.25^2 = 12.5, truncated to 12, against 50.
        Throughput hv = resolver.resolve(recipe, MachineConfig.of(large.id(), 3));
        assertEquals(20.0 / 12, hv.craftsPerSecond(), TOLERANCE);
        assertEquals(20.0 / 50, resolver.resolve(recipe, MachineConfig.of(plain.id(), 3))
                .craftsPerSecond(), TOLERANCE);

        // EV buys a third overclock: 200 x 0.25^3 = 3.125, truncated to 3.
        assertEquals(20.0 / 3, resolver.resolve(recipe, MachineConfig.of(large.id(), 4))
                .craftsPerSecond(), TOLERANCE);

        // A fourth would be 0.78 ticks, so IV and LuV hatches buy nothing at all — and are not
        // charged for either, because the loop breaks before it raises the voltage.
        for (int tier = 5; tier <= 6; tier++) {
            Throughput capped = resolver.resolve(recipe, MachineConfig.of(large.id(), tier));
            assertEquals(3, capped.overclocks(), "tier " + tier);
            assertEquals(20.0 / 3, capped.craftsPerSecond(), TOLERANCE, "tier " + tier);
            assertEquals(4 * 64 * 20.0, capped.euInPerSecond(), TOLERANCE, "tier " + tier);
        }
    }

    // ------------------------------------------- the other coil multiblocks (STATUS 14)

    private static MfpRecipe reactorRecipe(int durationTicks, long eut) {
        return MfpRecipe.builder("test:sulfuric_acid", "gtceu:large_chemical_reactor", "test")
                .input(MfpIngredient.of(DUST, 1))
                .output(MfpOutput.of(INGOT, 1))
                .duration(durationTicks)
                .euIn(eut)
                .minTier(3)
                .build();
    }

    private static MfpMachine reactor(String... modifiers) {
        return new MfpMachine("gtceu:large_chemical_reactor", "LCR", -1, 0,
                List.of("gtceu:large_chemical_reactor"), true, List.of(modifiers), "test");
    }

    /**
     * The bug this behaviour was written for: the hatch did nothing at all.
     *
     * <p>{@code chemical_reactor_oc} was unimplemented, so the Large Chemical Reactor's chain was
     * {@code batch_mode} alone — and batch mode is throughput-neutral. Every hatch from HV upwards
     * therefore produced the same machine count, which is what the player saw in game.
     */
    @Test
    @DisplayName("the large chemical reactor speeds up with its energy hatch")
    void chemicalReactorOverclocksOnTheHatch() {
        MfpMachine lcr = reactor("default_environment_requirement", "chemical_reactor_oc", "batch_mode");
        BehaviourThroughputResolver resolver = resolver(lcr);
        // The pack's sulfuric acid recipe: 320 ticks at 480 EU/t, which is HV.
        MfpRecipe recipe = reactorRecipe(320, 480);

        // Kanthal is the coil tier at which the speed factor is exactly 1, so this isolates the
        // overclock. HV runs it at the recipe's own voltage: no overclock, 320 ticks.
        Throughput hv = resolver.resolve(recipe, MachineConfig.of(lcr.id(), 3)
                .withOption(GtCoils.OPTION_COIL, "kanthal"));
        assertEquals(0, hv.overclocks());
        assertEquals(20.0 / 320, hv.craftsPerSecond(), TOLERANCE);

        // Non-perfect, so a tier is a halving rather than a quartering: EV is 2x, IV is 4x.
        Throughput ev = resolver.resolve(recipe, MachineConfig.of(lcr.id(), 4)
                .withOption(GtCoils.OPTION_COIL, "kanthal"));
        assertEquals(1, ev.overclocks());
        assertEquals(20.0 / 160, ev.craftsPerSecond(), TOLERANCE);

        Throughput iv = resolver.resolve(recipe, MachineConfig.of(lcr.id(), 5)
                .withOption(GtCoils.OPTION_COIL, "kanthal"));
        assertEquals(20.0 / 80, iv.craftsPerSecond(), TOLERANCE);
        assertEquals(2 * hv.craftsPerSecond(), ev.craftsPerSecond(), TOLERANCE);
        assertEquals(4 * hv.craftsPerSecond(), iv.craftsPerSecond(), TOLERANCE);
    }

    /**
     * And it keeps scaling, because this one is the sub-tick variant.
     *
     * <p>The contrast with {@code perfectOverclockingIsFasterAndThenCapped} is the whole reason both
     * tests exist: the Large Macerator stops dead at the one-tick floor, the Large Chemical Reactor
     * spends what is left on parallelism and carries on.
     */
    @Test
    @DisplayName("and past one tick it buys parallels rather than stopping")
    void chemicalReactorKeepsScalingPastTheTickFloor() {
        MfpMachine lcr = reactor("chemical_reactor_oc");
        BehaviourThroughputResolver resolver = resolver(lcr);
        MfpRecipe recipe = reactorRecipe(320, 480);

        MachineConfig deep = MachineConfig.of(lcr.id(), 12).withOption(GtCoils.OPTION_COIL, "kanthal");
        Throughput throughput = resolver.resolve(recipe, deep);

        // Nine overclocks against 320 ticks. Eight of them are halvings the duration can absorb -
        // and the seventh lands on 2.5, which the game runs in 2 (§13), so the chain arrives at one
        // tick with a quarter more speed than the arithmetic promises. The ninth has nowhere to go
        // and becomes a second parallel rather than being thrown away, which is what separates this
        // machine from the Large Macerator above: 40 crafts a second, not 32 and not a cap.
        assertEquals(40.0, throughput.craftsPerSecond(), TOLERANCE);
        assertTrue(throughput.note().contains("parallel"), throughput.note());
    }

    @Test
    @DisplayName("the coil sets speed and energy, and cupronickel is a penalty")
    void chemicalReactorCoilsScaleSpeedAndEnergy() {
        MfpMachine lcr = reactor("chemical_reactor_oc");
        BehaviourThroughputResolver resolver = resolver(lcr);
        MfpRecipe recipe = reactorRecipe(300, 480);

        // 75% speed on cupronickel: 300 ticks becomes 400, and the energy is undiscounted.
        Throughput cupronickel = resolver.resolve(recipe, MachineConfig.of(lcr.id(), 3)
                .withOption(GtCoils.OPTION_COIL, "cupronickel"));
        assertEquals(20.0 / 400, cupronickel.craftsPerSecond(), TOLERANCE);
        assertEquals(480 * 20.0, cupronickel.euInPerSecond(), TOLERANCE);

        // Nichrome is tier 2: 125% speed, and 10% off the energy.
        Throughput nichrome = resolver.resolve(recipe, MachineConfig.of(lcr.id(), 3)
                .withOption(GtCoils.OPTION_COIL, "nichrome"));
        assertEquals(20.0 / 240, nichrome.craftsPerSecond(), TOLERANCE);
        assertEquals(480 * 0.9 * 20.0, nichrome.euInPerSecond(), TOLERANCE);
    }

    /**
     * The coil is a build choice the plan may not have made, and unlike the blast furnace it cannot
     * stop the recipe running — so the overclock is still answered and only the coil is assumed.
     */
    @Test
    @DisplayName("an unset coil assumes cupronickel and says so, rather than refusing")
    void chemicalReactorWithoutACoilAssumesTheWorstOne() {
        MfpMachine lcr = reactor("chemical_reactor_oc");
        Throughput throughput = resolver(lcr).resolve(reactorRecipe(300, 480),
                MachineConfig.of(lcr.id(), 3));

        assertEquals(20.0 / 400, throughput.craftsPerSecond(), TOLERANCE);
        assertEquals(Confidence.APPROXIMATE, throughput.confidence());
        assertTrue(throughput.note().contains("cupronickel"), throughput.note());
    }

    /**
     * The fault underneath the fault: a half-understood machine reported EXACT.
     *
     * <p>{@code BehaviourChain.fold} only sees that its list is non-empty, so batch mode alone was
     * enough to make the reactor look fully modelled. The registry is the only thing that can tell.
     */
    @Test
    @DisplayName("a modifier nothing models is reported, even when the rest of the chain ran")
    void anUnmodelledModifierIsNotSilent() {
        MfpMachine odd = reactor("batch_mode", "some_pack_modifier");
        Throughput throughput = resolver(odd).resolve(reactorRecipe(320, 480),
                MachineConfig.of(odd.id(), 3));

        assertEquals(Confidence.UNKNOWN, throughput.confidence());
        assertTrue(throughput.note().contains("some_pack_modifier"), throughput.note());
    }

    /**
     * And the counterweight: the warning has to stay rare enough to be read.
     *
     * <p>{@code lambda:...} is the id {@code GtMachineCatalog} mints for a modifier passed as a bare
     * method reference, and a large share of GregTech's single blocks carry one — the plain chemical
     * reactor is {@code [lambda:GTRecipeModifiers, oc_non_perfect]}. Warning on those would put a
     * notice on a good fraction of every plan, and the one that matters would go past unread.
     */
    @Test
    @DisplayName("an anonymous modifier is not reported, nor is a known no-op")
    void anonymousAndNeutralModifiersStaySilent() {
        MfpMachine plain = macerator("gtceu:hv_macerator", false, 3,
                "lambda:com.gregtechceu.gtceu.common.data.GTRecipeModifiers", "oc_non_perfect",
                "consume_eu_to_start");
        Throughput throughput = resolver(plain).resolve(maceratorRecipe(200, 4),
                MachineConfig.of(plain.id(), 3));

        assertEquals(Confidence.EXACT, throughput.confidence());
        assertNull(throughput.note());
    }

    /**
     * Star-Technology's own three coils, which are the pack's entire endgame.
     *
     * <p>Registered in KubeJS rather than by GregTech, and the pack declares their tiers explicitly
     * as 8, 9 and 10 — continuing the built-in sequence — which is what makes appending them to the
     * table correct for {@code tierOf} and not merely for {@code temperatureOf}.
     */
    @Test
    @DisplayName("the pack's own coils are known by name, not just by raw temperature")
    void packCoilsAreInTheTable() {
        assertEquals(13499, GtCoils.temperatureOf("zalloy"));
        assertEquals(16199, GtCoils.temperatureOf("kubejs:magmada_alloy_coil_block"));
        assertEquals(18888, GtCoils.temperatureOf("abyssal_alloy"));

        assertEquals(7, GtCoils.tierOf("tritanium"));
        assertEquals(8, GtCoils.tierOf("zalloy"));
        assertEquals(9, GtCoils.tierOf("magmada_alloy"));
        assertEquals(10, GtCoils.tierOf("abyssal_alloy"));
        assertEquals(-1, GtCoils.tierOf("no_such_coil"));
    }

    /**
     * The pyrolyse oven under the spelling the shipped pack actually uses.
     *
     * <p>GTCEu-ST 1.7.0 calls this modifier {@code pyrolize_oven_oc} and 1.7.0b calls it
     * {@code pyrolyse_oven_oc}. MFP compiles against the latter and Star-Technology ships the
     * former, so matching one spelling gave a behaviour that passed every test here and applied to
     * nothing in the game the user plays. Both spellings must reach the same rule, and this asserts
     * they produce the identical answer rather than merely that neither is unknown.
     */
    @Test
    @DisplayName("the pyrolyse oven is found under either spelling of its modifier id")
    void pyrolyseOvenAnswersToBothSpellings() {
        MfpRecipe recipe = reactorRecipe(320, 120);

        MfpMachine corrected = oven("pyrolyse_oven_oc");
        MfpMachine shipped = oven("pyrolize_oven_oc");

        Throughput a = resolver(corrected).resolve(recipe, MachineConfig.of(corrected.id(), 3)
                .withOption(GtCoils.OPTION_COIL, "kanthal"));
        Throughput b = resolver(shipped).resolve(recipe, MachineConfig.of(shipped.id(), 3)
                .withOption(GtCoils.OPTION_COIL, "kanthal"));

        assertEquals(a.craftsPerSecond(), b.craftsPerSecond(), TOLERANCE);
        assertEquals(a.euInPerSecond(), b.euInPerSecond(), TOLERANCE);
        assertEquals(a.confidence(), b.confidence());

        // And the shared answer is the oven's, not the fallback's. A 120 EU/t recipe on a tier-3
        // hatch buys exactly one non-perfect overclock (120 x 4 = 480, still inside HV's 512, and a
        // second would need 1920), halving 320 to 160; kanthal is tier 1, so the coil factor that
        // follows is 2/(1+1) = 1 and leaves it there. Running the recipe as written would give 320.
        assertEquals(Confidence.EXACT, b.confidence());
        assertEquals(20.0 / 160, b.craftsPerSecond(), TOLERANCE);
    }

    /**
     * The multi smelter throws the recipe away, which nothing else here does.
     *
     * <p>A furnace recipe's own duration and EU/t never reach the hatch: GregTech substitutes 256
     * ticks and a coil-derived EU/t, then runs {@code 32 x level} crafts at once. So the answer must
     * be independent of what the recipe says about time, and must scale with the coil rather than
     * with anything else. Both are asserted, because either one alone would pass on a model that
     * merely got the magnitude right by accident.
     */
    @Test
    @DisplayName("the multi smelter replaces the recipe's duration and energy with the coil's")
    void multiSmelterSubstitutesItsOwnRecipe() {
        MfpMachine smelter = smelter();
        BehaviourThroughputResolver resolver = resolver(smelter);

        // Cupronickel: level 1, so 32 at once; EU/t is 4 x 32 / (8 x 1) = 16, which at an LV hatch
        // is its own tier and buys no overclock. 32 crafts per 256 ticks.
        Throughput cupronickel = resolver.resolve(smelterRecipe(128, 16),
                MachineConfig.of(smelter.id(), 1).withOption(GtCoils.OPTION_COIL, "cupronickel"));
        assertEquals(20.0 / 256 * 32, cupronickel.craftsPerSecond(), TOLERANCE);
        assertEquals(16 * 20.0, cupronickel.euInPerSecond(), TOLERANCE);

        // A recipe ten times as long gives exactly the same answer, because its duration is gone.
        Throughput slow = resolver.resolve(smelterRecipe(1280, 16),
                MachineConfig.of(smelter.id(), 1).withOption(GtCoils.OPTION_COIL, "cupronickel"));
        assertEquals(cupronickel.craftsPerSecond(), slow.craftsPerSecond(), TOLERANCE);
        assertEquals(cupronickel.euInPerSecond(), slow.euInPerSecond(), TOLERANCE);

        // Nichrome is level 2, discount 2: 64 at once for 4 x 64 / (8 x 2) = 16 EU/t. Twice the
        // throughput for the same power - which is the whole reason to build a better coil here.
        Throughput nichrome = resolver.resolve(smelterRecipe(128, 16),
                MachineConfig.of(smelter.id(), 1).withOption(GtCoils.OPTION_COIL, "nichrome"));
        assertEquals(2 * cupronickel.craftsPerSecond(), nichrome.craftsPerSecond(), TOLERANCE);
        assertEquals(cupronickel.euInPerSecond(), nichrome.euInPerSecond(), TOLERANCE);
    }

    /** The coil is worth a fortyfold throughput range here, so not choosing one must be flagged. */
    @Test
    @DisplayName("a multi smelter with no coil chosen assumes the weakest and says so")
    void multiSmelterWithoutACoilIsApproximate() {
        MfpMachine smelter = smelter();
        Throughput throughput = resolver(smelter).resolve(smelterRecipe(128, 16),
                MachineConfig.of(smelter.id(), 1));

        assertEquals(20.0 / 256 * 32, throughput.craftsPerSecond(), TOLERANCE);
        assertEquals(Confidence.APPROXIMATE, throughput.confidence());
    }

    /**
     * Fusion's overclock is half an overclock: duration halves, but EU/t only doubles.
     *
     * <p>Asserted against the ordinary rule rather than in isolation, because the number that
     * matters is the ratio. A model that applied the usual four-times factor would report the same
     * duration and four times the draw, and a plasma line at UV is where every fusion recipe in this
     * pack lives — so the error would land on the most expensive part of a plan and nowhere cheap
     * enough to notice it first.
     */
    @Test
    @DisplayName("a fusion reactor doubles its draw per overclock, not quadruples it")
    void fusionOverclocksAtHalfPrice() {
        MfpMachine reactor = new MfpMachine("gtceu:uv_fusion_reactor", "Fusion Reactor MK3", -1, 0,
                List.of("gtceu:fusion_reactor"), true, List.of("fusion_overclock"), "test");

        // A LuV recipe (24576 EU/t) in a UV reactor: two tiers up, so two overclocks.
        MfpRecipe recipe = MfpRecipe.builder("test:helium_plasma", "gtceu:fusion_reactor", "test")
                .input(MfpIngredient.of(DUST, 1))
                .output(MfpOutput.of(INGOT, 1))
                .duration(64)
                .euIn(24576)
                .build();

        Throughput throughput = resolver(reactor).resolve(recipe, MachineConfig.of(reactor.id(), 8));

        // Duration 64 -> 16, the ordinary halving twice over.
        assertEquals(20.0 / 16, throughput.craftsPerSecond(), TOLERANCE);
        // EU/t 24576 -> 98304, which is x4 across two overclocks. The ordinary rule would give x16.
        assertEquals(24576 * 4 * 20.0, throughput.euInPerSecond(), TOLERANCE);
        assertEquals(Confidence.EXACT, throughput.confidence());
    }

    /** A reactor below the recipe's tier cannot run it, however much power is available. */
    @Test
    @DisplayName("a fusion recipe above the reactor's tier is refused, not slowed")
    void fusionRefusesAboveItsTier() {
        MfpMachine reactor = new MfpMachine("gtceu:luv_fusion_reactor", "Fusion Reactor MK1", -1, 0,
                List.of("gtceu:fusion_reactor"), true, List.of("fusion_overclock"), "test");

        MfpRecipe recipe = MfpRecipe.builder("test:naquadria_plasma", "gtceu:fusion_reactor", "test")
                .input(MfpIngredient.of(DUST, 1))
                .output(MfpOutput.of(INGOT, 1))
                .duration(64)
                .euIn(98304)
                .build();

        Throughput throughput = resolver(reactor).resolve(recipe, MachineConfig.of(reactor.id(), 6));

        assertEquals(0.0, throughput.craftsPerSecond(), TOLERANCE);
        assertEquals(Confidence.UNKNOWN, throughput.confidence());
    }

    private static MfpRecipe smelterRecipe(int durationTicks, long eut) {
        return MfpRecipe.builder("test:smelt_iron", "gtceu:multi_smelter", "test")
                .input(MfpIngredient.of(DUST, 1))
                .output(MfpOutput.of(INGOT, 1))
                .duration(durationTicks)
                .euIn(eut)
                .build();
    }

    private static MfpMachine smelter() {
        return new MfpMachine("gtceu:multi_smelter", "Multi Smelter", -1, 0,
                List.of("gtceu:multi_smelter"), true, List.of("multi_smellter_parallel"), "test");
    }

    private static MfpMachine oven(String modifierId) {
        return new MfpMachine("gtceu:pyrolyse_oven", "Pyrolyse Oven", -1, 0,
                List.of("gtceu:pyrolyse_oven"), true, List.of(modifierId), "test");
    }
}
