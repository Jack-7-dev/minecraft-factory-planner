package dev.mfp.client;

import dev.mfp.core.index.RecipeIndex;
import dev.mfp.index.LazyIndex;
import dev.mfp.provider.CollectionContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * The client's own {@link RecipeIndex}, built from the recipe set the server sent us.
 *
 * <p>The GUI plans entirely on the client: it indexes, chooses recipes and solves locally, with no
 * packets involved. That is possible because the recipe manager is synchronised to every client
 * anyway, and it is worth doing for two reasons. Editing a plan re-solves on every change,
 * which a request/response round trip would make sluggish; and a planner asks the server for
 * nothing it could not work out itself, so there is no reason for it to be server-authoritative.
 *
 * <p>Invalidated on {@code RecipesUpdatedEvent} — the client-side counterpart of the reload hook
 * (plan §6.4) — and cleared on disconnect so the next world does not inherit the last one's index.
 */
public final class ClientIndex {

    private static final LazyIndex INDEX = new LazyIndex("client", ClientIndex::context);

    private ClientIndex() {}

    /** The current index, building it first if the recipe set has changed. Empty with no world. */
    public static RecipeIndex get() {
        return INDEX.get();
    }

    public static RecipeIndex peek() {
        return INDEX.peek();
    }

    /** True when the next {@link #get()} will spend the best part of a second building. */
    public static boolean needsBuild() {
        return INDEX.isStale() || INDEX.peek().isEmpty();
    }

    public static void invalidate() {
        INDEX.invalidate();
    }

    public static void clear() {
        INDEX.clear();
    }

    private static CollectionContext context() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        return new CollectionContext(level.getRecipeManager(), level.registryAccess());
    }
}
