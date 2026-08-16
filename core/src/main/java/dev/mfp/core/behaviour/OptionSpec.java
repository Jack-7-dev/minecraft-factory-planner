package dev.mfp.core.behaviour;

import java.util.List;
import java.util.Objects;

/**
 * One build choice a behaviour reads out of {@code MachineConfig.structureOptions}.
 *
 * <p>Declared by the behaviour rather than listed in the GUI, and that direction is the whole
 * point. The keys are open by design ({@code MachineConfig}), because throughput in the target pack
 * depends on what the player physically built inside a multiblock and every pack multiblock brings
 * its own; a modal holding a hard-coded table of them would be wrong the moment {@code start_core}
 * added a machine, and wrong in the worst way — silently, by offering no control over a number that
 * still changes the answer.
 *
 * <p>So the machine-config screen asks the behaviour chain what it reads and builds a field per
 * answer. A behaviour that declares nothing gets no fields, which is correct: it has no build
 * choices to make.
 *
 * @param key         the {@code structureOptions} key, as the behaviour reads it
 * @param label       a short human name for the field
 * @param description what the number means and where the player reads it off, shown as a tooltip
 * @param kind        how to edit it
 * @param choices     the allowed values for {@link Kind#CHOICE}, in a sensible order; empty otherwise
 * @param minimum     lowest sensible value for {@link Kind#INTEGER}
 * @param maximum     highest sensible value for {@link Kind#INTEGER}, or {@link Integer#MAX_VALUE}
 */
public record OptionSpec(
        String key,
        String label,
        String description,
        Kind kind,
        List<String> choices,
        int minimum,
        int maximum) {

    public enum Kind {
        /** A whole number: parallels, points, a temperature in kelvin. */
        INTEGER,
        /** One of a known set of names, such as a coil. */
        CHOICE
    }

    public OptionSpec {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(kind, "kind");
        choices = List.copyOf(Objects.requireNonNull(choices, "choices"));
    }

    public static OptionSpec integer(String key, String label, String description, int minimum, int maximum) {
        return new OptionSpec(key, label, description, Kind.INTEGER, List.of(), minimum, maximum);
    }

    public static OptionSpec choice(String key, String label, String description, List<String> choices) {
        return new OptionSpec(key, label, description, Kind.CHOICE, choices, 0, 0);
    }
}
