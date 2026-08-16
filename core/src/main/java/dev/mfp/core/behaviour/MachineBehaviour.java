package dev.mfp.core.behaviour;

/**
 * How one recipe modifier changes a recipe's throughput.
 *
 * <p><b>Behaviours chain.</b> A machine declares an ordered list of modifiers and GregTech folds
 * them left, each seeing the recipe as the previous one left it, so this interface takes the
 * accumulated {@link ThroughputResult} and returns a new one rather than describing its effect in
 * isolation. A single-lookup design would be unable to express {@code super_ebf}'s
 * {@code [ebf_oc, throughput_boosting, batch_mode]} at all.
 *
 * <p>This is the extensibility point that makes a forked, pack-modified GregTech survivable. The
 * target pack's most important multiblocks come from {@code start_core} and carry modifiers that do
 * not exist upstream, so nothing above this interface may assume a fixed table of machines. An
 * unrecognised machine falls through to a conservative default that says so, rather than to a
 * plausible guess (plan P5).
 */
public interface MachineBehaviour {

    /** Stable identifier, shown by {@code /mfp explain} so a number can be traced to a rule. */
    String id();

    /** Whether this behaviour applies to the machine and recipe in {@code context}. */
    boolean appliesTo(BehaviourContext context);

    /**
     * Fold this behaviour's effect into what the earlier ones produced.
     *
     * <p>Implementations must return {@code accumulated} unchanged when they have nothing to say,
     * and must not assume they run first — {@code accumulated.durationTicks(...)} is the duration
     * they actually operate on, which is rarely the recipe's own.
     */
    ThroughputResult apply(ThroughputResult accumulated, BehaviourContext context);

    /**
     * The build choices this behaviour reads from {@code MachineConfig.structureOptions}.
     *
     * <p>Empty by default, which is the honest answer for a behaviour whose rate follows from the
     * recipe and the machine alone. A behaviour that <em>does</em> read an option must declare it
     * here, because that is how the machine-config screen learns the option exists at all — see
     * {@link OptionSpec}. A behaviour reporting {@code APPROXIMATE} for a missing option and not
     * declaring it leaves the user told a number is a guess with no way to improve it.
     */
    default java.util.List<OptionSpec> options() {
        return java.util.List.of();
    }
}
