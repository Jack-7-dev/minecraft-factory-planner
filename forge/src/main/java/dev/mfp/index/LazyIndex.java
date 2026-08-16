package dev.mfp.index;

import dev.mfp.core.index.RecipeIndex;
import dev.mfp.provider.CollectionContext;
import dev.mfp.provider.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * An index that rebuilds itself on first use after being invalidated.
 *
 * <p>The laziness is a deliberate answer to a timing problem rather than laziness for performance.
 * A GregTech pack's recipe set is not final when datapacks finish loading: GregTech injects its own
 * lookups at the tail of recipe reload, KubeJS rewrites recipes after that, and recycling recipes
 * are generated from the resulting item set last of all (plan §2.6). Any fixed "index now" hook
 * risks running inside that sequence and capturing a partial set. Building on first query sidesteps
 * the ordering question entirely — by the time anyone asks a planning question, every load-time
 * mutation has happened.
 *
 * <p>There is one instance per side. They are kept apart rather than shared because on
 * singleplayer both live in the same JVM while reading different {@code RecipeManager}s, and a
 * client that quietly planned against the integrated server's recipes would behave differently on a
 * dedicated server — the kind of difference that only shows up in someone else's world.
 */
public final class LazyIndex {

    private static final Logger LOGGER = LoggerFactory.getLogger("MFP");

    private final String side;
    private final Supplier<CollectionContext> context;

    private volatile RecipeIndex index = RecipeIndex.EMPTY;
    private volatile boolean stale = true;

    /**
     * @param side    what to call this index in logs
     * @param context how to reach the recipe set, evaluated only when a build is actually needed
     */
    public LazyIndex(String side, Supplier<CollectionContext> context) {
        this.side = side;
        this.context = context;
    }

    /** The current index, building it first if the recipe set has changed since the last build. */
    public synchronized RecipeIndex get() {
        if (stale || index.isEmpty()) {
            CollectionContext ctx = context.get();
            if (ctx == null) {
                return RecipeIndex.EMPTY;
            }
            index = ProviderRegistry.collectAll(ctx);
            stale = false;
        }
        return index;
    }

    /** Force a rebuild on next use. Called whenever the game's recipes may have changed. */
    public synchronized void invalidate() {
        if (!stale) {
            LOGGER.debug("MFP {} index invalidated; will rebuild on next use", side);
        }
        stale = true;
    }

    /** The index as it stands, without triggering a build. Empty before the first query. */
    public RecipeIndex peek() {
        return index;
    }

    public boolean isStale() {
        return stale;
    }

    /** Drop everything, e.g. on disconnect, so a later world does not inherit stale data. */
    public synchronized void clear() {
        index = RecipeIndex.EMPTY;
        stale = true;
    }
}
