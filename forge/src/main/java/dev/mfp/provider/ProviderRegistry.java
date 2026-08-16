package dev.mfp.provider;

import dev.mfp.core.index.RecipeIndex;
import dev.mfp.provider.gtceu.GtceuRecipeProvider;
import dev.mfp.provider.vanilla.VanillaRecipeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Holds the registered providers and runs them to produce an index. */
public final class ProviderRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("MFP");

    private static final List<MfpRecipeProvider> PROVIDERS = new ArrayList<>();

    static {
        register(new VanillaRecipeProvider());
        // Registered unconditionally; isAvailable() gates it on GregTech actually being loaded.
        // Neither class mentions a GregTech type, so this cannot fault in a class that is absent.
        register(new GtceuRecipeProvider());
    }

    private ProviderRegistry() {}

    public static void register(MfpRecipeProvider provider) {
        PROVIDERS.add(provider);
    }

    public static List<MfpRecipeProvider> available() {
        return PROVIDERS.stream().filter(MfpRecipeProvider::isAvailable).toList();
    }

    /**
     * Run every available provider and freeze the result.
     *
     * <p>Providers run lowest priority first so that higher-priority contributions overwrite rather
     * than being rejected as duplicates, and a provider that throws costs only its own recipes —
     * one broken integration must not leave the user with no index at all.
     */
    public static RecipeIndex collectAll(CollectionContext context) {
        RecipeIndex.Builder builder = RecipeIndex.builder();

        List<MfpRecipeProvider> providers = new ArrayList<>(available());
        providers.sort(Comparator.comparingInt(MfpRecipeProvider::priority));

        for (MfpRecipeProvider provider : providers) {
            builder.beginProvider(provider.id(), provider.priority());
            try {
                provider.collect(builder, context);
            } catch (RuntimeException | LinkageError e) {
                LOGGER.error("MFP provider '{}' failed; its recipes will be missing", provider.id(), e);
                builder.skip("<provider>", "provider threw: " + e);
            }
        }

        RecipeIndex index = builder.build();
        LOGGER.info("MFP indexed {} recipes and {} machines from {} provider(s) in {} ms ({} skipped)",
                index.stats().recipeCount(),
                index.stats().machineCount(),
                providers.size(),
                index.stats().buildMillis(),
                index.stats().skips().size());
        return index;
    }
}
