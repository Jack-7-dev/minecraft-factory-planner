package dev.mfp.core.behaviour.gt;

import dev.mfp.core.behaviour.BehaviourContext;
import dev.mfp.core.behaviour.GtTiers;
import dev.mfp.core.behaviour.MachineBehaviour;
import dev.mfp.core.behaviour.OptionSpec;
import dev.mfp.core.behaviour.ThroughputResult;
import dev.mfp.core.model.Confidence;

import java.util.List;
import java.util.Set;

/**
 * Steam multiblocks: N parallels for a power draw that is capped, paid for in steam.
 *
 * <p>Decoded from {@code SteamParallelMultiblockMachine.recipeModifier}, which both GregTech's steam
 * multis and {@code start_core}'s subclass share:
 *
 * <pre>
 * eutMultiplier = (eut × 0.8888 × N ≤ 32) ? 0.8888 × N : 32 / eut
 * </pre>
 *
 * <p>The second branch is the interesting one — past the point where parallelism would exceed
 * 32 EU/t the draw is pinned to 32 regardless of how many parallels run, so extra parallels become
 * free. Treating the first branch as the whole rule would badly over-state what a steam setup needs.
 *
 * <p>Three things vary between the implementations and are constructor parameters rather than
 * subclass overrides, because they are the <em>only</em> differences: how many parallels the
 * structure runs, how much the duration stretches, and how much steam an EU costs.
 *
 * <p><b>The parallel count is a property of the machine, not a build choice.</b> GregTech reads it
 * from {@code ConfigHolder.machines.steamMultiParallelAmount} (8 by default, and 8 in
 * Star-Technology); {@code StarTSteamParallelMultiblockMachine}'s constructor calls
 * {@code setMaxParallels(6)}. Neither is visible from the machine registry, so the number is carried
 * here and the option exists to correct it — which is why an unset option is not an unknown.
 */
public class SteamMultiblockBehaviour implements MachineBehaviour {

    /** Structure option holding the machine's maximum parallel count. */
    public static final String OPTION_MAX_PARALLELS = "steam_max_parallels";

    private static final double STEAM_EFFICIENCY = 0.8888;
    private static final double STEAM_EUT_CAP = 32.0;

    private final String id;
    private final Set<String> machineIds;
    private final int defaultParallels;
    private final double durationMultiplier;
    private final double steamPerEu;

    /**
     * @param machineIds the machines this rule belongs to, matched by id because the modifier id is
     *                   unusable for every one of them (see {@link #gregTech()})
     */
    public SteamMultiblockBehaviour(String id, Set<String> machineIds, int defaultParallels,
                                    double durationMultiplier, double steamPerEu) {
        this.id = id;
        this.machineIds = Set.copyOf(machineIds);
        this.defaultParallels = defaultParallels;
        this.durationMultiplier = durationMultiplier;
        this.steamPerEu = steamPerEu;
    }

    /** For a subclass matched some other way, with one parallel count for every machine it claims. */
    protected SteamMultiblockBehaviour(String id, int defaultParallels, double durationMultiplier,
                                       double steamPerEu) {
        this.id = id;
        this.machineIds = Set.of();
        this.defaultParallels = defaultParallels;
        this.durationMultiplier = durationMultiplier;
        this.steamPerEu = steamPerEu;
    }

    /**
     * GregTech's steam multiblock rule, and the machines known to run it.
     *
     * <p>Named machines rather than a modifier id because neither of the two ways this rule reaches a
     * machine leaves a usable one. GregTech passes {@code SteamParallelMultiblockMachine::recipeModifier}
     * inline in {@code GTMultiMachines}, which shares a synthetic name with every other modifier
     * declared there; Star-Technology wraps the same call in a KubeJS arrow function, which Rhino
     * compiles to a proxy class named {@code $proxy153} — a number that changes every launch.
     *
     * <p><b>Eight parallels for all of them, including the ones a script asks for fewer.</b> The
     * count comes from {@code ConfigHolder.machines.steamMultiParallelAmount} — 8 by default and 8
     * in Star-Technology — and the constructor only overrides it when
     * {@code args[0] instanceof Integer}. Star-Technology registers the steam kiln from KubeJS as
     * {@code new SteamParallelMultiblockMachine(holder, 4)}, and Rhino hands whole JS numbers over as
     * {@code Double}, so that check fails and the 4 is silently discarded: the kiln runs 8 in game,
     * which is what the machine's own display says and what MFP now costs it at.
     *
     * <p>The consequence generalises, which is why this is one number rather than a table: <b>no
     * script-registered steam multiblock can set its own parallel count</b>, so any pack machine
     * matched here runs the configured amount. A pack that changes the config, or a machine that
     * somehow does override it, is corrected with {@code steam_max_parallels}.
     */
    public static SteamMultiblockBehaviour gregTech() {
        return new SteamMultiblockBehaviour("steam_multiblock",
                Set.of("gtceu:steam_grinder", "gtceu:steam_oven", "gtceu:steam_kiln"),
                8, 1.5, 2.0);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean appliesTo(BehaviourContext context) {
        return machineIds.contains(context.machineId());
    }

    @Override
    public boolean appliesToMachine(dev.mfp.core.model.MfpMachine machine) {
        // The same test: this rule was always about which machine it is, never about the recipe.
        return machine != null && machineIds.contains(machine.id());
    }

    @Override
    public List<OptionSpec> options() {
        return List.of(OptionSpec.integer(OPTION_MAX_PARALLELS, "Max parallels",
                "The most recipes this steam machine runs at once, as its structure allows.", 1, 1024));
    }

    @Override
    public ThroughputResult apply(ThroughputResult accumulated, BehaviourContext context) {
        int recipeTier = context.recipeTier();
        if (recipeTier > GtTiers.LV) {
            return accumulated.cancel("steam machines cannot run a tier "
                    + GtTiers.name(recipeTier) + " recipe");
        }

        long eut = context.recipeEut();
        if (eut <= 0) {
            return accumulated.asSteam(steamPerEu);
        }

        int configured = context.intOption(OPTION_MAX_PARALLELS, -1);
        int parallels = configured > 0 ? configured : defaultParallels;

        double boosted = eut * STEAM_EFFICIENCY * parallels;
        double eutMultiplier = boosted <= STEAM_EUT_CAP
                ? STEAM_EFFICIENCY * parallels
                : STEAM_EUT_CAP / eut;

        ThroughputResult result = accumulated
                .andThen(durationMultiplier, eutMultiplier, parallels, 0)
                .asSteam(steamPerEu);

        // Two separate assumptions, and only the first is the machine's own doing. A structure that
        // cannot keep every parallel fed runs slower than this says, which is a property of the
        // build rather than of the machine, so it is flagged either way.
        if (configured <= 0) {
            result = result.degrade(Confidence.APPROXIMATE,
                    "assumes this machine's stock " + parallels + " parallels; set '"
                            + OPTION_MAX_PARALLELS + "' if the pack changed it");
        }
        return result.degrade(Confidence.APPROXIMATE,
                "steam parallels assume all " + parallels + " stay fed");
    }
}
