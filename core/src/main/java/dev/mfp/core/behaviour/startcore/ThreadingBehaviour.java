package dev.mfp.core.behaviour.startcore;

import dev.mfp.core.behaviour.BehaviourContext;
import dev.mfp.core.behaviour.MachineBehaviour;
import dev.mfp.core.behaviour.OptionSpec;
import dev.mfp.core.behaviour.ThroughputResult;
import dev.mfp.core.model.Confidence;

import java.util.List;

/**
 * Star-Technology's threaded multiblocks, whose throughput the player tunes with stat blocks.
 *
 * <p>Three independent dials, decoded from {@code StarTThreadingCapableMachine}:
 *
 * <ul>
 *   <li><b>parallel points</b> → {@code floor(points / 20) + 1} crafts at once;
 *   <li><b>speed points</b> → duration halves on a <em>triangular</em> curve:
 *       {@code 2^-((-1 + sqrt(1 + 8 × points/100)) / 2)}. The first halving costs 100 points, the
 *       second another 200, the third another 300 — so doubling the points does far less than
 *       doubling the speed, and estimating this linearly would be badly wrong at the top end;
 *   <li><b>efficiency points</b> → EU/t multiplied by {@code 30 / (30 + points)}.
 * </ul>
 *
 * <p>The parallel dial also lengthens the cycle by {@code sqrt(parallels)}, so N parallels give
 * {@code sqrt(N)} throughput rather than N — the machine trades power efficiency for a sub-linear
 * speed gain.
 *
 * <p>With no stat blocks described this is the identity, which is exactly what an empty structure
 * does, so the answer is honest rather than merely unknown.
 */
public final class ThreadingBehaviour implements MachineBehaviour {

    public static final String ID = "threading_machine";

    /** Structure option: parallel stat points assigned to this machine. */
    public static final String OPTION_PARALLEL_POINTS = "threading_parallel_points";
    /** Structure option: speed stat points. */
    public static final String OPTION_SPEED_POINTS = "threading_speed_points";
    /** Structure option: efficiency stat points. */
    public static final String OPTION_EFFICIENCY_POINTS = "threading_efficiency_points";

    private static final double SPEED_POINTS_PER_MARK = 100.0;
    private static final double EFFICIENCY_POINTS_PER_MARK = 30.0;
    private static final int PARALLEL_POINTS_PER_STEP = 20;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean appliesTo(BehaviourContext context) {
        return context.hasModifier(ID);
    }

    @Override
    public List<OptionSpec> options() {
        return List.of(
                OptionSpec.integer(OPTION_PARALLEL_POINTS, "Parallel points",
                        "Stat points assigned to parallelism on this machine's stat block.", 0, 1000),
                OptionSpec.integer(OPTION_SPEED_POINTS, "Speed points",
                        "Stat points assigned to speed.", 0, 1000),
                OptionSpec.integer(OPTION_EFFICIENCY_POINTS, "Efficiency points",
                        "Stat points assigned to energy efficiency.", 0, 1000));
    }

    @Override
    public ThroughputResult apply(ThroughputResult accumulated, BehaviourContext context) {
        int parallelPoints = context.intOption(OPTION_PARALLEL_POINTS, 0);
        int speedPoints = context.intOption(OPTION_SPEED_POINTS, 0);
        int efficiencyPoints = context.intOption(OPTION_EFFICIENCY_POINTS, 0);

        int parallels = Math.floorDiv(parallelPoints, PARALLEL_POINTS_PER_STEP) + 1;
        double durationMultiplier = durationMultiplier(speedPoints) * Math.sqrt(parallels);
        double energyMultiplier = EFFICIENCY_POINTS_PER_MARK / (EFFICIENCY_POINTS_PER_MARK + efficiencyPoints);

        ThroughputResult folded = accumulated.andThen(durationMultiplier, energyMultiplier, parallels, 0);

        if (parallelPoints == 0 && speedPoints == 0 && efficiencyPoints == 0) {
            return folded.degrade(Confidence.APPROXIMATE,
                    "no threading stat blocks configured, so the bare structure is assumed (set '"
                            + OPTION_SPEED_POINTS + "', '" + OPTION_EFFICIENCY_POINTS + "', '"
                            + OPTION_PARALLEL_POINTS + "')");
        }
        return folded.degrade(Confidence.APPROXIMATE,
                "threading assumes all " + parallels + " parallels stay fed");
    }

    /**
     * How much the speed dial shortens a cycle.
     *
     * <p>Points buy <em>halvings</em>, and the halvings get progressively dearer: reaching the nth
     * costs the nth triangular number of marks, which is where the square root comes from.
     */
    private static double durationMultiplier(int speedPoints) {
        if (speedPoints <= 0) {
            return 1.0;
        }
        double marks = speedPoints / SPEED_POINTS_PER_MARK;
        double halvings = (-1 + Math.sqrt(1 + 8 * marks)) / 2;
        return Math.pow(2, -halvings);
    }
}
