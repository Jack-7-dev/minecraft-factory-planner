package dev.mfp.core.model;

import java.util.Objects;

/**
 * What form an item is: ore, dust, ingot, plate — or a manufactured thing made of known materials.
 *
 * <p>This is the classification the game itself keeps, not a guess made from an item id. GregTech
 * registers every material item under a {@code TagPrefix} and a {@code Material}, and separately
 * records a composition for the manufactured items it generates recycling recipes from. Both are
 * read at ingestion and recorded here; nothing in {@code core} knows how they were obtained.
 *
 * <p>It exists for one question, asked by {@code RecipeScorer}: <b>does this recipe consume
 * something more refined than it produces?</b> A recipe that does is going backwards, which is what
 * recycling is, and it is not a way to obtain the material — the thing it eats had to be made from
 * that material first. Stated as forms the rule needs no per-machine cases at all.
 *
 * @param prefix   the form's canonical name — "ore", "dust", "ingot", "plate" — or null for an item
 *                 with a known composition but no form of its own
 * @param material the material it is made of, or null when unknown
 * @param composed whether the game knows what this item is made of. True with a null prefix is
 *                 exactly "a manufactured thing made of known materials", which is the class this
 *                 rule exists to demote — identified by the same data that generates its recycling
 *                 recipe in the first place.
 */
public record MaterialForm(String prefix, String material, boolean composed) {

    /** An item with a form of its own: an ingot of steel, a plate of aluminium. */
    public static MaterialForm of(String prefix, String material) {
        return new MaterialForm(Objects.requireNonNull(prefix, "prefix"), material, true);
    }

    /** A manufactured item: the game knows what it is made of, but it is not a form of anything. */
    public static MaterialForm manufactured() {
        return new MaterialForm(null, null, true);
    }

    public boolean isManufactured() {
        return prefix == null && composed;
    }
}
