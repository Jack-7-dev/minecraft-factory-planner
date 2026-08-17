package dev.mfp.core.behaviour.gt;

import dev.mfp.core.behaviour.BehaviourContext;
import dev.mfp.core.behaviour.MachineBehaviour;
import dev.mfp.core.behaviour.OptionSpec;
import dev.mfp.core.behaviour.OverclockMaths;
import dev.mfp.core.behaviour.ThroughputResult;
import dev.mfp.core.model.Confidence;

import java.util.List;

/**
 * The Multi Smelter and the machines built on it: the recipe's own duration and EU/t are discarded.
 *
 * <p>Every other behaviour here scales what the recipe says. This one replaces it. GregTech rewrites
 * the recipe to a fixed 256 ticks and an EU/t derived from the coil alone, runs {@code 32 x level}
 * crafts at once, and only then overclocks — so a furnace recipe's 128 ticks and 16 EU/t are
 * <em>gone</em> before the hatch is consulted. Modelling this as "runs as written" is not a small
 * error on a slow machine; it is a different machine.
 *
 * <p>The sequence, exactly as {@code GTRecipeModifiers.multiSmelterParallel} composes it:
 *
 * <ol>
 *   <li>duration becomes {@code 128 x 2 x parallels / maxParallel}, and EU/t becomes
 *       {@code 4 x maxParallel / (8 x energyDiscount)};
 *   <li>a plain non-perfect overclock on <em>that</em> recipe, at the hatch's voltage;
 *   <li>contents multiply by the parallel count — note that EU/t does not, because step 1 already
 *       priced the full width;
 *   <li>a second, sub-tick overclock on the result, which finds no voltage headroom left and exists
 *       only to turn a sub-tick duration into more parallelism.
 * </ol>
 *
 * <p><b>Assumes the machine is fed.</b> {@code parallels} is GregTech's inventory-limited count and
 * the planner supplies whatever the chain calls for, so it is taken to reach {@code maxParallel} —
 * which is also what makes step 1's duration land on a flat 256 ticks. This is the same assumption
 * {@link OverclockMaths#subTickParallelCeiling} states, and a half-fed smelter will not reach these
 * numbers.
 *
 * <p><b>The coil does more here than anywhere else.</b> Cupronickel is level 1 and abyssal alloy is
 * level 40, so the throughput between them differs fortyfold before a single overclock. An unset
 * coil is therefore assumed to be cupronickel and reported {@link Confidence#APPROXIMATE}, in the
 * same shape as {@link CoilTierOverclockBehaviour} — but the assumption costs far more, so it says
 * so in the note.
 */
public final class MultiSmelterBehaviour implements MachineBehaviour {

    /**
     * GregTech's own id, misspelling included.
     *
     * <p>{@code IdentifiedRecipeModifier("multi_smellter_parallel", ...)} — two Ls. It is the string
     * the game reports and therefore the string that has to be matched; correcting it here would
     * simply mean matching nothing.
     */
    private static final String MODIFIER_ID = "multi_smellter_parallel";

    /** Base duration before the parallel ratio, from {@code 128 * 2.0 * parallels / maxParallel}. */
    private static final double BASE_DURATION = 128 * 2.0;

    @Override
    public String id() {
        return MODIFIER_ID;
    }

    @Override
    public boolean appliesTo(BehaviourContext context) {
        return context.hasModifier(MODIFIER_ID);
    }

    @Override
    public List<OptionSpec> options() {
        return List.of(
                OptionSpec.choice(GtCoils.OPTION_COIL, "Coil",
                        "The heating coil built into the machine. Here it sets how many items are"
                                + " smelted at once (32 per coil level) and the energy that costs —"
                                + " a fortyfold throughput range from cupronickel to abyssal alloy.",
                        List.copyOf(GtCoils.names())));
    }

    @Override
    public ThroughputResult apply(ThroughputResult accumulated, BehaviourContext context) {
        Object coilOption = context.options().get(GtCoils.OPTION_COIL);
        String coilName = coilOption == null ? null : String.valueOf(coilOption);
        int level = GtCoils.levelOf(coilName);
        int discount = GtCoils.energyDiscountOf(coilName);

        ThroughputResult result = accumulated;
        if (level < 0 || discount < 0) {
            level = GtCoils.levelOf("cupronickel");
            discount = GtCoils.energyDiscountOf("cupronickel");
            result = result.degrade(Confidence.APPROXIMATE, coilName == null
                    ? "no coil chosen, so the weakest is assumed - the coil sets this machine's"
                            + " whole throughput, so pick one"
                    : "coil '" + coilName + "' is not one MFP knows, so the weakest is assumed -"
                            + " the coil sets this machine's whole throughput");
        }

        long maxVoltage = context.machineVoltage();
        if (maxVoltage <= 0) {
            return result.degrade(Confidence.UNKNOWN,
                    "no energy hatch chosen, so the overclock is unknown");
        }

        int parallels = 32 * level;
        // GregTech's duration is 128 * 2 * parallels / maxParallel. A fed machine reaches
        // maxParallel, so the ratio is 1 and every recipe on this machine runs for 256 ticks
        // regardless of what it says or which coil is in the wall — the coil buys width, not speed.
        int duration = (int) BASE_DURATION;
        long eut = Math.max(1, (long) (4L * parallels / (8.0 * discount)));

        double baseDuration = context.recipe().durationTicks();
        double baseEut = context.recipe().euIn();
        if (baseDuration <= 0 || baseEut <= 0) {
            // Nothing to express the substitution as a multiple of. A furnace recipe always has
            // both, so this is a malformed recipe rather than a case worth guessing at.
            return result.degrade(Confidence.UNKNOWN,
                    "the recipe has no duration or energy for the smelter to replace");
        }

        // Step 1: the substitution, expressed as multipliers because that is the currency the chain
        // trades in. The recipe's own numbers are gone from here on.
        result = result.andThen(duration / baseDuration, eut / baseEut, 1.0, 0);

        // Step 2: a plain non-perfect overclock of the substituted recipe.
        int overclocks = OverclockMaths.overclockCount(eut, maxVoltage);
        OverclockMaths.Result plain = OverclockMaths.standard(eut, duration, overclocks, overclocks,
                maxVoltage, OverclockMaths.STD_DURATION_FACTOR);
        result = result.andThen(plain.durationMultiplier(), plain.eutMultiplier(), 1.0,
                plain.overclocks());

        // Step 3: the parallels. Contents only — step 1 already charged for the full width.
        result = result.andThen(1.0, 1.0, parallels, 0);

        // Step 4: the sub-tick pass. It re-reads EU/t after the overclock above, so it almost always
        // finds no headroom; its purpose is the case where step 2 stopped at the one-tick floor with
        // overclocks still unspent, which it converts into parallelism.
        long overclockedEut = (long) (eut * plain.eutMultiplier());
        int remaining = OverclockMaths.overclockCount(overclockedEut, maxVoltage);
        if (remaining > 0) {
            int overclockedDuration = (int) Math.max(1, Math.floor(duration * plain.durationMultiplier()));
            OverclockMaths.Result subTick = OverclockMaths.subTickParallel(overclockedEut,
                    overclockedDuration, remaining, remaining, maxVoltage,
                    OverclockMaths.STD_DURATION_FACTOR,
                    OverclockMaths.subTickParallelCeiling(overclockedDuration, remaining));
            result = result.andThen(subTick.durationMultiplier(), subTick.eutMultiplier(),
                    subTick.parallels(), subTick.overclocks());
        }

        return result;
    }
}
