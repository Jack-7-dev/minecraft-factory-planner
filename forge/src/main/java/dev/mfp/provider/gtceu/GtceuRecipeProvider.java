package dev.mfp.provider.gtceu;

import dev.mfp.MfpMod;
import dev.mfp.core.index.MfpRecipeSink;
import dev.mfp.integration.gtceu.GtceuCollector;
import dev.mfp.provider.CollectionContext;
import dev.mfp.provider.MfpRecipeProvider;

/**
 * GregTech recipes and machines.
 *
 * <p>Outranks the vanilla provider: GregTech proxies some vanilla recipe types into its own machines
 * and models them far more richly — with EU/t, a duration and a real machine — so where both
 * describe the same recipe id, this one should win.
 *
 * <p>This class carries <b>no GregTech imports</b>, on purpose. GregTech is a soft dependency, so a
 * class that mentions its types can only be loaded when it is present; keeping the SPI implementation
 * clean means the registry can hold an instance unconditionally and the first GregTech type is not
 * touched until {@link #collect} runs, behind {@link #isAvailable()}. All the real work lives in
 * {@code dev.mfp.integration.gtceu}.
 */
public final class GtceuRecipeProvider implements MfpRecipeProvider {

    public static final String ID = "gtceu";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean isAvailable() {
        return MfpMod.isGregTechLoaded();
    }

    @Override
    public void collect(MfpRecipeSink sink, CollectionContext context) {
        GtceuCollector.collect(sink, context.recipeManager(), ID);
    }
}
