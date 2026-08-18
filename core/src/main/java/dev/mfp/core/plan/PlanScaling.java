package dev.mfp.core.plan;

import dev.mfp.core.model.MfpKey;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * "Max out this machine": build a whole number of one machine and let the rest of the chain follow.
 *
 * <p>A player does not think in output rates, they think in blocks placed. A plan for 1000 mB/s of
 * ethanol that wants 2.60 distillation towers is a plan they cannot build — they will place three,
 * and the honest question is what three towers actually make. That is the sum this class does: take
 * the factor {@code wanted / current} and multiply every target by it, so the third tower's
 * capacity turns into ethanol rather than into idle time.
 *
 * <p>The whole thing rests on one property of a solved plan: <b>it is linear in its targets.</b>
 * Double every target and every line's machine count doubles with it, along with every ingredient
 * and every product. So scaling one line to a chosen size needs no per-line surgery at all — the
 * greenhouse feeding the tower grows by the same factor for free, which is precisely what makes
 * this a plan-wide operation rather than a per-line one.
 *
 * <p><b>This is only exact while the plan is linear.</b> A line whose {@link MachineConfig} carries
 * a machine limit ({@link MachineConfig#hasLimit()}, and more sharply
 * {@link MachineConfig#forceLimit()}) is an inequality — "at most four of these", "exactly four of
 * these" — and an inequality is not a ray through the origin. Doubling the targets of a plan
 * containing one does not double its answer: the capped line stops, and everything downstream of it
 * stops with it. {@link #hasMachineLimit} exists so a caller can say so rather than present a
 * preview that is quietly wrong, which is the failure mode MFP's confidence rules exist to avoid.
 * The preview is a preview in any case; the real numbers only ever come from a re-solve.
 */
public final class PlanScaling {

    private PlanScaling() {}

    /**
     * The multiplier that takes a line from {@code currentMachines} to {@code wantedMachines}.
     *
     * <p>Empty rather than an exception or a silent 1.0, following {@link Arithmetic} next door: the
     * caller is a dialog reading a text field, and "you cannot scale from here" is an answer it has
     * to render, not a bug it has to guard against. A line at zero machines is the case that forces
     * this — an idle line, or one whose recipe has no intrinsic rate, has no throughput per machine
     * to multiply, so there is no factor that makes it three machines rather than none.
     */
    public static OptionalDouble factorFor(double currentMachines, double wantedMachines) {
        if (!Double.isFinite(currentMachines) || !Double.isFinite(wantedMachines)) {
            return OptionalDouble.empty();
        }
        if (currentMachines <= 0 || wantedMachines <= 0) {
            return OptionalDouble.empty();
        }
        double factor = wantedMachines / currentMachines;
        return Double.isFinite(factor) && factor > 0 ? OptionalDouble.of(factor) : OptionalDouble.empty();
    }

    /**
     * Multiply every target's rate by {@code factor}, in place.
     *
     * <p>Mutates and returns the plan, the way {@link Plan}'s own setters do — the plan the GUI is
     * showing is the plan being edited, and handing back a copy would leave the screen re-solving
     * one object and drawing another.
     *
     * <p>Rejects a factor that is not a positive finite number instead of letting
     * {@link TargetOutput} throw from inside a loop. A factor of zero would empty the plan of its
     * reason to exist, and it would do so after some targets had already been rewritten; refusing at
     * the door keeps the plan untouched when the answer is "no".
     */
    public static Plan scaleTargets(Plan plan, double factor) {
        Objects.requireNonNull(plan, "plan");
        if (!Double.isFinite(factor) || factor <= 0) {
            throw new IllegalArgumentException("factor must be finite and positive: " + factor);
        }
        List<TargetOutput> targets = plan.targets();
        for (int i = 0; i < targets.size(); i++) {
            TargetOutput target = targets.get(i);
            plan.setTarget(i, new TargetOutput(target.key(), target.perSecond() * factor));
        }
        return plan;
    }

    /**
     * What the targets would become at {@code factor}, without touching the plan.
     *
     * <p>For the line in the dialog that reads "ethanol 1000 mB/s -> 1153.8 mB/s". The user is
     * choosing a number of machines, and the number they actually care about is the one on the other
     * side of that arrow; making them press Apply to find out turns a decision into a guess.
     */
    public static Map<MfpKey, Double> previewTargets(Plan plan, double factor) {
        Objects.requireNonNull(plan, "plan");
        Map<MfpKey, Double> scaled = new LinkedHashMap<>();
        for (TargetOutput target : plan.targets()) {
            scaled.merge(target.key(), target.perSecond() * factor, Double::sum);
        }
        return scaled;
    }

    /**
     * Any per-second map from a solve, scaled — products, byproducts, raw inputs.
     *
     * <p>Takes the map rather than the {@code SolveResult} so that {@code core.plan} does not have
     * to depend on {@code core.solver} for one multiplication, and so the same helper serves the
     * products row and the imports row.
     */
    public static Map<MfpKey, Double> scaleFlows(Map<MfpKey, Double> flows, double factor) {
        Objects.requireNonNull(flows, "flows");
        Map<MfpKey, Double> scaled = new LinkedHashMap<>();
        for (Map.Entry<MfpKey, Double> entry : flows.entrySet()) {
            scaled.put(entry.getKey(), entry.getValue() * factor);
        }
        return scaled;
    }

    /**
     * Whether anything in this plan makes the linear assumption false.
     *
     * <p>Both places a limit can be hiding are checked: the configuration the user saved against a
     * recipe id, and the configuration a line is actually carrying — expansion can hand a line a
     * build that never went through {@link Plan#configureMachine}, and a limit found only on the
     * line is exactly as fatal to the arithmetic as one found in the map.
     */
    public static boolean hasMachineLimit(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        for (MachineConfig config : plan.machineConfigs().values()) {
            if (config.hasLimit()) {
                return true;
            }
        }
        for (Line line : plan.allLines()) {
            if (line.machine().hasLimit()) {
                return true;
            }
        }
        return false;
    }
}
