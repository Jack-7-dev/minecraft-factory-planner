package dev.mfp.core.plan;

/**
 * A decision the user made that this line is the result of.
 *
 * <p>The plan already records all three — {@code recipeChoices}, {@code machineChoices},
 * {@code machineConfigs} — but nothing read them back per line, so after a re-solve the user could
 * not tell which of their decisions had survived and which the scorer had quietly taken back. That
 * is the failure {@link Plan#chooseRecipe} exists to prevent, and it is invisible unless the plan
 * says so on the row itself.
 *
 * <p>An enum rather than a formatted string so the wording and the colour stay the GUI's business
 * and the fact stays the model's.
 */
public enum LineDecision {

    /** The user pinned this recipe for something it makes. */
    RECIPE("recipe pinned"),

    /**
     * The recipe came from the user's standing defaults rather than from this plan.
     *
     * <p>A separate answer from {@link #RECIPE} because the two have different homes: a pin is
     * changed here, a standing default is changed everywhere, and a marker that conflated them would
     * invite the user to edit one and change the other.
     */
    STANDING_DEFAULT("your default recipe"),

    /** The user chose the machine for this recipe type; it applies to every line of the type. */
    MACHINE("machine chosen"),

    /** The user built this machine out — tier, parallels, coils, a limit — for this recipe. */
    CONFIG("machine configured"),

    /**
     * The build came from the player's standing description of that machine, not from this plan.
     *
     * <p>{@link #STANDING_DEFAULT} for builds, and separate from {@link #CONFIG} for the same
     * reason: this one is changed everywhere at once, so the marker has to say which screen owns it.
     */
    STANDING_BUILD("your build for this machine");

    private final String label;

    LineDecision(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
