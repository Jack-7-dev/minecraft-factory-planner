package dev.mfp.core.behaviour.gt;

import dev.mfp.core.behaviour.BehaviourContext;
import dev.mfp.core.behaviour.GtTiers;
import dev.mfp.core.behaviour.MachineBehaviour;
import dev.mfp.core.behaviour.OptionSpec;
import dev.mfp.core.behaviour.OverclockMaths;
import dev.mfp.core.behaviour.ThroughputResult;
import dev.mfp.core.model.Confidence;

import java.util.List;
import java.util.function.IntToDoubleFunction;

/**
 * The three coil multiblocks that overclock on the energy hatch and scale on the coil.
 *
 * <p>Unlike the blast furnace — whose coils decide <em>whether</em> a recipe runs, and which
 * {@link CoilOverclockBehaviour} therefore handles on its own — these three take an ordinary
 * non-perfect sub-tick overclock from the energy hatch and use the coil only to scale speed or
 * energy. Every recipe they can run, they can run on any coil.
 *
 * <table>
 *   <caption>What each one does with the coil tier, cupronickel being 0</caption>
 *   <tr><th>modifier</th><th>duration</th><th>EU/t</th><th>order</th></tr>
 *   <tr><td>{@code chemical_reactor_oc}</td><td>&times; 1/(0.75 + 0.25t)</td><td>&times; (1 - 0.05t)</td>
 *       <td>duration, <b>then</b> overclock, then discount</td></tr>
 *   <tr><td>{@code cracker_oc}</td><td>—</td><td>&times; (1 - 0.1t)</td>
 *       <td>overclock, then discount</td></tr>
 *   <tr><td>{@code pyrolyse_oven_oc}</td><td>&times; 4/3 or 2/(t+1)</td><td>—</td>
 *       <td>overclock, <b>then</b> duration</td></tr>
 * </table>
 *
 * <p><b>The order is not decoration.</b> The chemical reactor slows the recipe down <em>before</em>
 * overclocking it, so on cupronickel a 320-tick recipe overclocks from 426 rather than from 320 —
 * and since the overclock loop stops at the one-tick floor, the starting duration is what decides
 * how many overclocks are available. The pyrolyse oven applies its factor afterwards and the cracker
 * discounts EU/t after overclocking, which is why neither of those changes the overclock count. This
 * is the same left fold GregTech performs, so modelling it as three ordered steps is not an
 * approximation of the composition — it is the composition.
 *
 * <p>All three take {@code NON_PERFECT_OVERCLOCK_SUBTICK}: <b>duration halves per hatch tier, not
 * quarters</b>, and once it would fall below a tick the remaining overclocks buy parallelism instead
 * of being lost. So HV to EV is 2&times;, HV to IV is 4&times;, and unlike the perfect-overclock
 * multiblocks there is no tier at which these stop scaling.
 */
public final class CoilTierOverclockBehaviour implements MachineBehaviour {

    private final String modifierId;
    private final List<String> aliases;
    private final IntToDoubleFunction durationFactor;
    private final IntToDoubleFunction eutFactor;
    private final boolean durationBeforeOverclock;
    private final String coilEffect;

    private CoilTierOverclockBehaviour(String modifierId, List<String> aliases,
            IntToDoubleFunction durationFactor,
            IntToDoubleFunction eutFactor, boolean durationBeforeOverclock, String coilEffect) {
        this.modifierId = modifierId;
        this.aliases = List.copyOf(aliases);
        this.durationFactor = durationFactor;
        this.eutFactor = eutFactor;
        this.durationBeforeOverclock = durationBeforeOverclock;
        this.coilEffect = coilEffect;
    }

    /**
     * The Large Chemical Reactor and everything built on its recipe type.
     *
     * <p>Cupronickel runs at 75% speed and full price; every coil tier adds 25 percentage points of
     * speed and takes off 5% of the energy, up to tritanium's 250% and 65%. The machine's own
     * tooltip states it in exactly those terms.
     */
    public static CoilTierOverclockBehaviour chemicalReactor() {
        return new CoilTierOverclockBehaviour("chemical_reactor_oc", List.of(),
                tier -> 1.0 / (0.75 + tier * 0.25),
                tier -> 1.0 - tier * 0.05,
                true,
                "speed and a 5%-per-tier energy discount");
    }

    /** The Cracking Unit: coils discount energy only, 10% per tier. */
    public static CoilTierOverclockBehaviour cracker() {
        return new CoilTierOverclockBehaviour("cracker_oc", List.of(),
                tier -> 1.0,
                // The high end is the fork's own curve, which flattens rather than reaching free.
                tier -> Math.max(0.0001, 1.0 - (tier > 9 ? 0.9 + (tier - 9) * 0.025 : tier * 0.1)),
                false,
                "a 10%-per-tier energy discount");
    }

    /**
     * The Pyrolyse Oven: coils change speed only, and cupronickel is a penalty.
     *
     * <p>Registered under both spellings of its id, and that is not defensive programming. GTCEu-ST
     * 1.7.0 — the build Star-Technology actually ships — names this modifier
     * {@code pyrolize_oven_oc}; 1.7.0b, the sibling checkout MFP compiles against, corrects it to
     * {@code pyrolyse_oven_oc}. Matching only the corrected spelling made this behaviour work
     * perfectly in the dev run and do <em>nothing</em> in the pack, which is the worst possible
     * place for the difference to fall: every check passed and every plan the player saw was wrong.
     * Found by {@code /mfp modifiers}, which reported the behaviour as registered-but-unused and the
     * pack's id as unmodelled in the same breath.
     */
    public static CoilTierOverclockBehaviour pyrolyseOven() {
        return new CoilTierOverclockBehaviour("pyrolyse_oven_oc", List.of("pyrolize_oven_oc"),
                tier -> tier == 0 ? 4.0 / 3.0 : 2.0 / (tier + 1),
                tier -> 1.0,
                false,
                "speed");
    }

    @Override
    public String id() {
        return modifierId;
    }

    /** Every id this rule is known by, the canonical one first. */
    public List<String> modifierIds() {
        List<String> all = new java.util.ArrayList<>();
        all.add(modifierId);
        all.addAll(aliases);
        return List.copyOf(all);
    }

    @Override
    public boolean appliesTo(BehaviourContext context) {
        for (String id : modifierIds()) {
            if (context.hasModifier(id)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<OptionSpec> options() {
        return List.of(
                OptionSpec.choice(GtCoils.OPTION_COIL, "Coil",
                        "The heating coil built into the machine. Here it sets " + coilEffect
                                + " — unlike the blast furnace, it does not decide which recipes run.",
                        List.copyOf(GtCoils.names())));
    }

    @Override
    public ThroughputResult apply(ThroughputResult accumulated, BehaviourContext context) {
        long recipeEut = context.recipeEut();
        if (recipeEut <= 0) {
            return accumulated;
        }

        // The same gate GregTech applies, on the recipe's bare voltage rather than voltage times
        // amperage — see ElectricOverclockBehaviour, and RecipeHelper.getRecipeEUtTier.
        long recipeVoltage = context.recipe().euIn() > 0 ? context.recipe().euIn() : context.recipe().euOut();
        int voltageTier = GtTiers.tierByVoltage(recipeVoltage);
        if (voltageTier > context.machineTier()) {
            return accumulated.cancel("recipe needs tier " + GtTiers.name(voltageTier)
                    + " but the machine is " + GtTiers.name(context.machineTier()));
        }

        int coilTier = GtCoils.tierOf(coilName(context));
        ThroughputResult result = accumulated;
        if (coilTier < 0) {
            // Cupronickel is the floor rather than a guess: it is the coil every one of these
            // machines can be built with, and assuming it errs towards too many machines rather
            // than too few, which is the safe direction for something the player is about to build.
            // The overclock itself is unaffected — it comes from the energy hatch — so the number is
            // still worth having, which is why this is not the blast furnace's flat refusal.
            coilTier = 0;
            result = result.degrade(Confidence.APPROXIMATE,
                    "no coil chosen, so cupronickel is assumed for " + coilEffect
                            + " (set the '" + GtCoils.OPTION_COIL + "' option)");
        }

        double duration = durationFactor.applyAsDouble(coilTier);
        if (durationBeforeOverclock && duration != 1.0) {
            result = result.andThen(duration, 1.0, 1.0, 0);
            // GregTech writes the slowed recipe out before overclocking it, and its duration is an
            // int — so the overclock loop below sees the truncated figure, not the exact one.
            result = result.quantiseDuration(context.recipe().durationTicks());
        }

        result = overclock(result, context, recipeEut);
        if (result.cancelled()) {
            return result;
        }

        if (!durationBeforeOverclock && duration != 1.0) {
            result = result.andThen(duration, 1.0, 1.0, 0);
        }
        double eut = eutFactor.applyAsDouble(coilTier);
        if (eut != 1.0) {
            result = result.andThen(1.0, eut, 1.0, 0);
        }
        return result;
    }

    private ThroughputResult overclock(ThroughputResult accumulated, BehaviourContext context, long recipeEut) {
        if (!context.hasMachineVoltage()) {
            return accumulated.degrade(Confidence.UNKNOWN,
                    "no machine tier chosen, so no overclock is modelled");
        }

        long maxVoltage = context.machineVoltage();
        double currentEut = accumulated.eut(recipeEut);
        int currentDuration = (int) Math.max(1, Math.floor(
                accumulated.durationTicks(context.recipe().durationTicks())));

        int overclocks = OverclockMaths.overclockCount((long) currentEut, maxVoltage);
        if (overclocks <= 0) {
            return accumulated;
        }

        OverclockMaths.Result result = OverclockMaths.subTickParallel((long) currentEut, currentDuration,
                overclocks, overclocks, maxVoltage, OverclockMaths.STD_DURATION_FACTOR,
                OverclockMaths.subTickParallelCeiling(currentDuration, overclocks));

        ThroughputResult folded = accumulated.andThen(result.durationMultiplier(), result.eutMultiplier(),
                result.parallels(), result.baseOverclocks());

        if (result.parallels() > 1) {
            return folded.degrade(Confidence.APPROXIMATE, result.parallels()
                    + " sub-tick parallels assume the machine is fed without interruption");
        }
        return folded;
    }

    private static String coilName(BehaviourContext context) {
        Object value = context.options().get(GtCoils.OPTION_COIL);
        return value == null ? null : String.valueOf(value);
    }
}
