package dev.mfp.core.plan;

import dev.mfp.core.model.MfpKey;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Items a factory does not make, because the world already supplies them without limit.
 *
 * <p>Expansion stops at these. Without them the chooser answers "how do I get water?" with a recipe,
 * and there is always a recipe — melting snow, condensing steam, freezing and thawing ice — none of
 * which anybody builds, because water comes out of a hole in the ground. A plan that costs an
 * infinite resource as though it had to be manufactured is wrong in a way that compounds: every
 * machine it invents draws power and demands inputs of its own.
 *
 * <p><b>The shipped list is deliberately tiny.</b> What is effectively infinite is a property of the
 * player's progress, not of the pack — cobblestone stops being worth making the moment a cobble
 * generator exists, and so does stone, and later so does very nearly everything. Guessing at that
 * boundary on the player's behalf would hide real production chains from someone who has not reached
 * it yet. So MFP ships the one case that is true in every save from the first minute, and everything
 * else is the user's to declare, per plan or in the config.
 */
public final class RawMaterials {

    /**
     * True on any world, at any point in the game.
     *
     * <p>Water is infinite from a two-block pool, which is the first thing a player builds. Note what
     * is <em>not</em> here: distilled water is manufactured, lava is only renewable much later and
     * only with a dripstone farm, and every ore is finite in the sense that matters.
     *
     * <p>Charcoal is here for a different reason: it is what the vanilla furnaces burn, and their
     * fuel is a <em>utility</em> rather than an ingredient. A furnace that reported a charcoal cost
     * and then grew a tree farm underneath every plan would be answering a question nobody asked;
     * charcoal is renewable, and a player running furnaces has a source. Take it back out in the
     * plan settings if the plan is about making charcoal — or just target charcoal, since a target is
     * never treated as raw.
     *
     * <p><b>Cobblestone was added here during M9 and taken straight back out</b>, and the reason is
     * worth keeping. It went in because expansion could not stop anywhere sensible below it and went
     * hunting through brine chemistry for a way to make stone — which looked like "nothing produces
     * cobblestone". It does: Star-Technology's stone barrel makes it from water and a lava catalyst,
     * one line, no chain underneath. So the premise was false, and what the symptom actually showed
     * was the scorer preferring deep chemistry to that one line (STATUS §9.13). Shipping the item as
     * raw would have hidden a real fault from every pack, and hidden a legitimate one-line recipe
     * from every player, to save one click in Defaults. A player for whom cobble genuinely is free —
     * which in a sieve-fed skyblock it is — declares it there, and since M9.4 that declaration
     * persists and reaches every plan they already have.
     */
    private static final Set<MfpKey> SHIPPED = Set.of(
            MfpKey.fluid("minecraft", "water")
            // MfpKey.item("minecraft", "charcoal")
            );

    private static final Set<MfpKey> SHIPPED_BEFORE_M9 = Set.of(
            MfpKey.fluid("minecraft", "water"),
            MfpKey.item("minecraft", "charcoal")
            );

    private static volatile Set<MfpKey> installed = SHIPPED;

    private RawMaterials() {}

    /** What every new plan starts with: the shipped list plus whatever the config added. */
    public static Set<MfpKey> defaults() {
        return installed;
    }

    /**
     * Add to the defaults, which is how a pack or a player declares their own infinite resources.
     *
     * <p>Additive rather than replacing, so a config file that says "cobblestone is free now" does
     * not have to restate water to keep it.
     */
    public static void install(Collection<MfpKey> extra) {
        Set<MfpKey> combined = new LinkedHashSet<>(SHIPPED);
        combined.addAll(extra);
        installed = Set.copyOf(combined);
    }

    /** Back to the shipped list, for tests and for a config that was emptied. */
    public static void resetToShipped() {
        installed = SHIPPED;
    }

    /** Whether this key is one MFP ships as raw, as opposed to one the user declared. */
    public static boolean isShipped(MfpKey key) {
        return SHIPPED.contains(key);
    }

    /** What was shipped before M9, which only the file migration has any business asking. */
    public static Set<MfpKey> shippedBeforeM9() {
        return SHIPPED_BEFORE_M9;
    }
}
