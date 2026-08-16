package dev.mfp;

import dev.mfp.command.MfpCommand;
import dev.mfp.index.MfpIndexHolder;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for MFP (Minecraft Factory Planner).
 *
 * <p>MFP declares production targets ("5 casings per minute") and computes the machines, tiers,
 * EU/t and throughput needed to hit them. GregTech is the first supported ecosystem but is a
 * <em>soft</em> dependency: the mod loads and the solvers run without it.
 */
@Mod(MfpMod.MOD_ID)
public final class MfpMod {

    public static final String MOD_ID = "mfp";

    private static final Logger LOGGER = LoggerFactory.getLogger("MFP");

    /** Mod id of the GregTech build MFP integrates with (upstream GTCEu and the StarT fork share it). */
    private static final String GTCEU_MOD_ID = "gtceu";

    public MfpMod() {
        LOGGER.info("MFP initialising (GregTech {})", isGregTechLoaded() ? "present" : "absent");
        MinecraftForge.EVENT_BUS.register(this);
    }

    /**
     * Whether GregTech is available in this instance. Every entry into the {@code dev.mfp.integration.gtceu}
     * package must be guarded by this, so that class loading never touches absent GregTech types.
     */
    public static boolean isGregTechLoaded() {
        return ModList.get().isLoaded(GTCEU_MOD_ID);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        MfpCommand.register(event.getDispatcher());
    }

    /**
     * Recipes have been reloaded, so anything indexed from them is now stale.
     *
     * <p>Only invalidation happens here, never a rebuild. This event fires <em>during</em> reload,
     * and in a GregTech pack a great deal still happens afterwards — GregTech's own recipe lookups,
     * KubeJS edits, then procedurally generated recycling. Indexing at this point would capture a
     * half-assembled set. Marking it stale defers the work until the first query, by which time
     * everything has settled.
     */
    @SubscribeEvent
    public void onAddReloadListener(AddReloadListenerEvent event) {
        MfpIndexHolder.invalidate();
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        MfpIndexHolder.clear();
    }
}
