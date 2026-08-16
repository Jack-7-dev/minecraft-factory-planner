package dev.mfp.core.model;

import java.util.Locale;
import java.util.Objects;

/**
 * Identity of anything that can flow through a production chain: an item, a fluid, or a pseudo
 * resource such as EU.
 *
 * <p>Modelling energy as just another key is deliberate (plan P3). A generator is a recipe that
 * outputs {@link #EU}, so "how many generators do I need?" is answered by the same solver that
 * answers "how much copper do I need?", rather than by a separate power-balancing pass.
 *
 * <p>{@code variant} distinguishes stacks that share an item id but are not interchangeable —
 * GregTech programmed circuits are the motivating case, since a recipe calling for circuit #4 is not
 * satisfied by circuit #7. It holds a stable hash of the distinguishing NBT, never the NBT itself,
 * so keys stay small and comparable. A {@code null} variant means "the plain form".
 */
public record MfpKey(String namespace, String path, Kind kind, String variant) {

    public enum Kind {
        ITEM,
        FLUID,
        /** Energy, measured in EU. */
        ENERGY,
        /** GregTech computation (CWU). */
        COMPUTATION,
        /** Anything else MFP invents to make the solver work. */
        PSEUDO
    }

    /** Electrical energy. Amounts of this key are EU; as a rate, EU per second. */
    public static final MfpKey EU = new MfpKey("mfp", "eu", Kind.ENERGY, null);

    /** GregTech computation. Amounts are CWU; as a rate, CWU per second. */
    public static final MfpKey CWU = new MfpKey("mfp", "cwu", Kind.COMPUTATION, null);

    public MfpKey {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
        if (namespace.isEmpty()) {
            throw new IllegalArgumentException("namespace must not be empty");
        }
        if (path.isEmpty()) {
            throw new IllegalArgumentException("path must not be empty");
        }
        if (variant != null && variant.isEmpty()) {
            throw new IllegalArgumentException("variant must be null rather than empty");
        }
        namespace = namespace.toLowerCase(Locale.ROOT);
        path = path.toLowerCase(Locale.ROOT);
    }

    public static MfpKey item(String namespace, String path) {
        return new MfpKey(namespace, path, Kind.ITEM, null);
    }

    public static MfpKey item(String namespace, String path, String variant) {
        return new MfpKey(namespace, path, Kind.ITEM, variant);
    }

    public static MfpKey fluid(String namespace, String path) {
        return new MfpKey(namespace, path, Kind.FLUID, null);
    }

    /** Parses {@code namespace:path}, defaulting the namespace to {@code minecraft} when absent. */
    public static MfpKey parse(String id, Kind kind) {
        Objects.requireNonNull(id, "id");
        int colon = id.indexOf(':');
        if (colon < 0) {
            return new MfpKey("minecraft", id, kind, null);
        }
        return new MfpKey(id.substring(0, colon), id.substring(colon + 1), kind, null);
    }

    /** This key without its variant, i.e. the plain form of the same item. */
    public MfpKey withoutVariant() {
        return variant == null ? this : new MfpKey(namespace, path, kind, null);
    }

    public MfpKey withVariant(String newVariant) {
        return Objects.equals(variant, newVariant) ? this : new MfpKey(namespace, path, kind, newVariant);
    }

    /** True for keys that MFP invented rather than read from the game. */
    public boolean isPseudo() {
        return kind == Kind.ENERGY || kind == Kind.COMPUTATION || kind == Kind.PSEUDO;
    }

    /** {@code namespace:path}, without the variant. */
    public String id() {
        return namespace + ':' + path;
    }

    @Override
    public String toString() {
        return variant == null ? id() : id() + '#' + variant;
    }
}
