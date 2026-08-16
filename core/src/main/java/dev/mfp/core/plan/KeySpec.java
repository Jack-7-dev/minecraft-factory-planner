package dev.mfp.core.plan;

import dev.mfp.core.model.MfpKey;

/**
 * The {@code [fluid:|item:]namespace:path[#variant]} spelling a user types for a key.
 *
 * <p>Shared between the commands, the GUI and everything written to disk, so that the string a user
 * typed into chat, the string they typed into a text field and the string in a saved plan cannot
 * mean three different things.
 *
 * <p><b>In {@code core} because the plan codec needs it</b> (M9). A saved plan and an exported plan
 * string are the same JSON, and both spell their keys this way; leaving the parser on the game side
 * would have meant {@code core} could not read back what it wrote.
 *
 * <p><b>The variant is part of the spelling.</b> {@link MfpKey#toString()} writes
 * {@code gtceu:programmed_circuit#4}, and a parser that did not know about the {@code #} produced a
 * key whose <em>path</em> was {@code programmed_circuit#4} — a different key that matches nothing in
 * the index. Nothing pointed at it until plans started round-tripping through a string.
 */
public final class KeySpec {

    private static final String FLUID = "fluid:";
    private static final String ITEM = "item:";

    private KeySpec() {}

    /** Parses {@code namespace:path}, with an optional {@code fluid:} or {@code item:} prefix. */
    public static MfpKey parse(String spec) {
        String trimmed = spec.trim();
        if (trimmed.startsWith(FLUID)) {
            return withVariant(trimmed.substring(FLUID.length()), MfpKey.Kind.FLUID);
        }
        if (trimmed.startsWith(ITEM)) {
            return withVariant(trimmed.substring(ITEM.length()), MfpKey.Kind.ITEM);
        }
        return withVariant(trimmed, MfpKey.Kind.ITEM);
    }

    /** The spelling {@link #parse} reads back: {@code fluid:gtceu:steam}, {@code gtceu:steel_ingot}. */
    public static String of(MfpKey key) {
        String prefix = key.kind() == MfpKey.Kind.FLUID ? FLUID : "";
        return prefix + key;
    }

    private static MfpKey withVariant(String id, MfpKey.Kind kind) {
        int hash = id.indexOf('#');
        if (hash < 0) {
            return MfpKey.parse(id, kind);
        }
        return MfpKey.parse(id.substring(0, hash), kind).withVariant(id.substring(hash + 1));
    }
}
