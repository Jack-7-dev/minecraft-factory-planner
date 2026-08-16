package dev.mfp.index;

import dev.mfp.core.index.RecipeIndex;
import dev.mfp.provider.CollectionContext;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The server side's {@link RecipeIndex}, built lazily from the running server's recipe manager.
 *
 * <p>All of the interesting behaviour — and the reason it is lazy rather than hooked to a load
 * event — lives in {@link LazyIndex}. This class exists to give the commands a static entry point
 * and to keep the server's index distinct from the client's.
 */
public final class MfpIndexHolder {

    private static final AtomicReference<MinecraftServer> SERVER = new AtomicReference<>();

    private static final LazyIndex INDEX = new LazyIndex("server", () -> {
        MinecraftServer server = SERVER.get();
        return server == null ? null : CollectionContext.of(server);
    });

    private MfpIndexHolder() {}

    /** The current index, building it first if the recipe set has changed since the last build. */
    public static RecipeIndex get(MinecraftServer server) {
        SERVER.set(server);
        return INDEX.get();
    }

    /** Force a rebuild on next use. Called whenever the game's recipes may have changed. */
    public static void invalidate() {
        INDEX.invalidate();
    }

    /** The index as it stands, without triggering a build. Empty before the first query. */
    public static RecipeIndex peek() {
        return INDEX.peek();
    }

    public static boolean isStale() {
        return INDEX.isStale();
    }

    /** Drop everything on server shutdown, so a later world does not inherit stale data. */
    public static void clear() {
        INDEX.clear();
        SERVER.set(null);
    }
}
