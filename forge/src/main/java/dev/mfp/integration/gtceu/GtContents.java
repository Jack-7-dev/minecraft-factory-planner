package dev.mfp.integration.gtceu;

import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.IRangedIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.IntProviderIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.provider.GameKeys;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns GregTech's ingredient wrappers into the keys and per-craft amounts MFP works in.
 *
 * <p>GregTech does not use vanilla {@code Ingredient} amounts. It wraps ingredients to carry a
 * count ({@code SizedIngredient}) or a random count range ({@code IntProviderIngredient}), so the
 * quantity has to be read off the wrapper rather than off the stacks.
 *
 * <p><b>Ranged ingredients must never be asked for their stacks.</b>
 * {@code IntProviderIngredient#getItems()} rolls its random count on first call and <em>caches</em>
 * the result on the ingredient — which is shared, live recipe state. Indexing would therefore both
 * consume randomness and pin the recipe to one roll for the rest of the session. MFP unwraps to the
 * inner ingredient for candidates and uses the distribution's mean for the amount, which is also
 * the right number for planning at steady state.
 */
final class GtContents {

    private GtContents() {}

    /** What one content resolved to: the items or fluids that satisfy it, and how many per craft. */
    /**
     * Candidates with GregTech's own items first.
     *
     * <p>A tag ingredient is genuinely ambiguous and MFP must not narrow it — but it has to expand
     * <em>something</em>, and the first candidate is what both the chooser and the solver take. Left
     * in registry order that is effectively a coin toss: a GregTech extractor recipe for plant balls
     * accepts wood pulp and Create's wood chips alike, and picking the Create item drags a second
     * mod's machinery into a GregTech chain for no reason the user asked for.
     *
     * <p>Order only, never a filter. Every candidate stays on the ingredient, the tooltip still says
     * how many are accepted, and the plan can be pointed at any of them.
     */
    private static List<MfpKey> preferGregTech(Set<MfpKey> candidates) {
        List<MfpKey> ordered = new ArrayList<>(candidates.size());
        for (MfpKey key : candidates) {
            if ("gtceu".equals(key.namespace())) {
                ordered.add(key);
            }
        }
        for (MfpKey key : candidates) {
            if (!"gtceu".equals(key.namespace())) {
                ordered.add(key);
            }
        }
        return ordered;
    }

    record Resolved(List<MfpKey> candidates, double amount) {

        boolean isEmpty() {
            return candidates.isEmpty();
        }
    }

    /**
     * Resolve an item ingredient.
     *
     * <p>The amount lives on whichever wrapper carries it. A ranged count wins over a fixed one
     * because it is the more specific statement: a {@code SizedIngredient} wrapping a range holds
     * only the nominal count, while the range describes what the recipe actually consumes.
     */
    static Resolved item(Ingredient ingredient) {
        double amount = 1.0;
        boolean amountFound = false;
        Ingredient current = ingredient;

        while (true) {
            if (current instanceof IntProviderIngredient ranged) {
                // Authoritative, and reading it avoids touching getItems() at all.
                amount = ranged.getMidRoll();
                amountFound = true;
                current = ranged.getInner();
            } else if (current instanceof SizedIngredient sized) {
                if (!amountFound) {
                    amount = sized.getAmount();
                }
                current = sized.getInner();
            } else {
                break;
            }
        }

        if (current == null || current.isEmpty()) {
            return new Resolved(List.of(), amount);
        }

        Set<MfpKey> candidates = new LinkedHashSet<>();
        for (ItemStack stack : current.getItems()) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            MfpKey key = GameKeys.of(stack);
            if (key != null) {
                candidates.add(key);
            }
        }
        return new Resolved(preferGregTech(candidates), amount);
    }

    /**
     * Resolve a fluid ingredient, in millibuckets per craft.
     *
     * <p>Read from the ingredient's declared values rather than {@code getStacks()}: the ranged
     * subclass rolls and caches there just as the item one does, and the values carry the tag
     * alternatives we want to keep ambiguous anyway.
     */
    static Resolved fluid(FluidIngredient ingredient) {
        double amount = ingredient instanceof IRangedIngredient ranged
                ? ranged.getMidRoll()
                : ingredient.getAmount();

        Set<MfpKey> candidates = new LinkedHashSet<>();
        for (FluidIngredient.Value value : ingredient.values) {
            if (value == null) {
                continue;
            }
            for (Fluid fluid : value.getFluids()) {
                if (fluid == null || fluid == Fluids.EMPTY) {
                    continue;
                }
                // Via a stack so the NBT variant is decided in exactly one place (GameKeys).
                MfpKey key = GameKeys.of(new FluidStack(fluid, 1, ingredient.getNbt()));
                if (key != null) {
                    candidates.add(key);
                }
            }
        }
        return new Resolved(preferGregTech(candidates), amount);
    }
}
