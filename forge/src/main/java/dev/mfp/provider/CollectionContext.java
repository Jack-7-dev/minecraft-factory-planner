package dev.mfp.provider;

import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.Objects;

/**
 * The game state a provider reads from.
 *
 * <p>Passed in rather than looked up statically so providers stay testable and so there is exactly
 * one place that decides <em>when</em> collection happens. Timing matters more than it looks: a
 * GregTech pack assembles its final recipe set from datapacks, KubeJS edits and procedurally
 * generated recycling, so anything captured at mod init would be incomplete (plan §2.6).
 *
 * <p><b>Deliberately not a server-side type.</b> It used to hold a {@code MinecraftServer} and
 * derive the other two fields from it, which quietly made every provider server-only. The GUI
 * (M6a) plans on the client, against the recipe set the client was sent, so the two fields
 * providers actually read are held directly and the server is merely one way to obtain them.
 * That also keeps a dedicated server honest: a client planning session must use the recipes it was
 * told about, not the ones an integrated server happens to have in the same JVM.
 *
 * @param recipeManager the recipe manager holding the final, fully-reloaded recipe set
 * @param registries    registry access, needed to resolve tags into concrete items
 */
public record CollectionContext(RecipeManager recipeManager, RegistryAccess registries) {

    public CollectionContext {
        Objects.requireNonNull(recipeManager, "recipeManager");
        Objects.requireNonNull(registries, "registries");
    }

    public static CollectionContext of(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return new CollectionContext(server.getRecipeManager(), server.registryAccess());
    }
}
