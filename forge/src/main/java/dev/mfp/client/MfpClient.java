package dev.mfp.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.mfp.MfpMod;
import dev.mfp.client.screen.PlannerScreen;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.plan.TargetOutput;
import dev.mfp.core.plan.KeySpec;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.RecipesUpdatedEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side wiring: the keybind, the client commands, and the two events that keep the client's
 * index honest.
 *
 * <p>The commands here are <em>client</em> commands, which is not merely a convenience. They plan
 * against the client's own index (see {@link ClientIndex}), so they work identically on a dedicated
 * server where MFP is not installed. The server-side {@code /mfp plan} remains the headless
 * verification path and is unaffected.
 */
@Mod.EventBusSubscriber(modid = MfpMod.MOD_ID, value = Dist.CLIENT)
public final class MfpClient {

    private static KeyMapping openKey;

    private MfpClient() {}

    @Mod.EventBusSubscriber(modid = MfpMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {

        private ModBus() {}

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            openKey = new KeyMapping("key.mfp.open_planner", KeyConflictContext.IN_GAME,
                    InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, "key.categories.mfp");
            event.register(openKey);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || openKey == null) {
            return;
        }
        while (openKey.consumeClick()) {
            ClientPlanner.loadSaved();
            Minecraft.getInstance().setScreen(new PlannerScreen());
        }
    }

    /**
     * The world these plans were built against, recorded so a warning has somewhere to point.
     *
     * <p>The plans themselves are global — losing them on starting a new save is the worse failure —
     * but a recipe id that no longer resolves is much easier to understand when the file says which
     * world it came from.
     */
    public static String worldName() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSingleplayerServer() != null) {
            return minecraft.getSingleplayerServer().getWorldData().getLevelName();
        }
        return minecraft.getCurrentServer() != null ? minecraft.getCurrentServer().name : "";
    }

    /**
     * The client's recipe set has been replaced, so anything indexed from it is stale.
     *
     * <p>The client-side counterpart of the server's reload hook, and invalidation only for the
     * same reason (plan §6.4): this fires as the recipes arrive, and GregTech's own post-load work
     * has not necessarily happened yet.
     */
    @SubscribeEvent
    public static void onRecipesUpdated(RecipesUpdatedEvent event) {
        ClientIndex.invalidate();
        // The machine standing in for a recipe type is read off the index, so it goes stale with it,
        // and so does the recipe that was picked as the way to build each machine block.
        MachineStacks.clearCache();
        MachineParts.clearCache();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // Saved on the way out rather than on every edit: an edit re-solves the whole plan, and a
        // rate field being typed into would write the file on every keystroke.
        ClientPlanner.saveAll(worldName());
        // Item registries and recipes both belong to the connection we are leaving.
        ClientIndex.clear();
        ClientPlanner.clear();
        KeyStacks.clearCache();
        MachineStacks.clearCache();
        MachineParts.clearCache();
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        // Rate before the item id, so the id can be a greedy string: ids contain colons, which
        // brigadier's word argument will not accept.
        event.getDispatcher().register(Commands.literal("mfpplan")
                .then(Commands.argument("perSecond", DoubleArgumentType.doubleArg(0.0001))
                        .then(Commands.argument("item", StringArgumentType.greedyString())
                                .executes(ctx -> planAndOpen(ctx.getSource(),
                                        DoubleArgumentType.getDouble(ctx, "perSecond"),
                                        StringArgumentType.getString(ctx, "item")))))
                // Rate omitted: TargetOutput decides, which is a bucket a second for a fluid.
                .then(Commands.argument("onlyItem", StringArgumentType.greedyString())
                        .executes(ctx -> planAndOpen(ctx.getSource(), -1,
                                StringArgumentType.getString(ctx, "onlyItem")))));

        event.getDispatcher().register(Commands.literal("mfpgui").executes(ctx -> {
            ClientPlanner.loadSaved();
            open();
            return 1;
        }));
    }

    /** @param perSecond the rate asked for, or a negative number for "whatever the default is" */
    private static int planAndOpen(CommandSourceStack source, double perSecond, String itemSpec) {
        MfpKey key = KeySpec.parse(itemSpec);
        double wanted = perSecond < 0 ? TargetOutput.defaultRate(key) : perSecond;
        ClientPlanner.loadSaved();

        if (ClientIndex.needsBuild()) {
            source.sendSuccess(() -> Component.literal("MFP: indexing recipes, this takes a moment...")
                    .withStyle(ChatFormatting.GRAY), false);
        }

        // Solved on the client thread, which stutters the game for as long as it takes. That is
        // honest for a one-off action the user asked for, and it keeps the index reads on the
        // thread that owns the recipe manager.
        ClientPlan plan = ClientPlanner.plan(key, wanted);
        if (plan == null) {
            source.sendFailure(Component.literal("MFP: nothing in the index produces " + key
                    + "  (prefix with 'fluid:' for fluids)"));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("MFP: " + plan.solveResult().lines().size()
                        + " line(s) in "
                        + ClientPlan.millis(plan.chooseMicros() + plan.solveMicros()))
                        .withStyle(ChatFormatting.GRAY),
                false);
        open();
        return plan.solveResult().lines().size();
    }

    /**
     * Opens the planner on the next tick.
     *
     * <p>Deferred because a command is executed from the chat screen, which closes itself
     * immediately afterwards — a screen opened during the command would be the thing it closed.
     */
    private static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.setScreen(new PlannerScreen()));
    }
}
