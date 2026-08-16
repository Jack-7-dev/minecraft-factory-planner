package dev.mfp.core.model;

/**
 * How a chanced output's roll relates to the other chanced outputs of the same recipe.
 *
 * <p>Mirrors GregTech's {@code ChanceLogic}. This cannot be flattened into a single probability at
 * ingest time: the modes differ in how a <em>group</em> of outputs resolves, so the expected value
 * of one output depends on its siblings. {@link MfpOutput#expectedAmount()} is therefore only valid
 * for the modes that are independent per item; the group-aware ones are resolved by the solver.
 */
public enum ChanceMode {
    /** Not chanced at all. */
    ALWAYS,
    /** GregTech {@code OR}: each output rolls on its own. The common case. */
    INDEPENDENT,
    /** GregTech {@code AND}: the whole group succeeds or none of it does. */
    ALL_OR_NOTHING,
    /** GregTech {@code FIRST}: only the first output that succeeds is produced. */
    FIRST_ONLY,
    /** GregTech {@code XOR}: exactly one member of the group is produced. */
    EXCLUSIVE;

    /**
     * Whether {@code amount * chance} is the true expected yield for a single output, whatever its
     * siblings do.
     *
     * <p>True only for {@link #ALWAYS} and {@link #INDEPENDENT}. The other three all depend on the
     * group:
     *
     * <ul>
     *   <li>{@link #ALL_OR_NOTHING} emits every member only when <em>all</em> of them pass, so a
     *       member's real expectation is {@code amount * product of every chance in the group} —
     *       three outputs at 50% yield 12.5% each, not 50%. (M3 had this listed as independently
     *       expectable, which over-counted every multi-member AND group.)
     *   <li>{@link #FIRST_ONLY} makes later members conditional on the earlier ones failing.
     *   <li>{@link #EXCLUSIVE} renormalises the group so that exactly one member is produced.
     * </ul>
     *
     * <p>{@code ChanceResolver} computes all of these exactly given the whole group; this predicate
     * says only whether a single output can be read in isolation.
     */
    public boolean isIndependentlyExpectable() {
        return this == ALWAYS || this == INDEPENDENT;
    }

    /** Whether members of this mode's group resolve together and therefore need group context. */
    public boolean isGrouped() {
        return this == ALL_OR_NOTHING || this == FIRST_ONLY || this == EXCLUSIVE;
    }
}
