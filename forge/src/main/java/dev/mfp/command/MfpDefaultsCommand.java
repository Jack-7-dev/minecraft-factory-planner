package dev.mfp.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.mfp.core.index.RecipeIndex;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.behaviour.OptionSpec;
import dev.mfp.core.plan.MachineDefaults;
import dev.mfp.core.plan.Preferences;
import dev.mfp.plan.PlanSession;
import dev.mfp.index.MfpIndexHolder;
import dev.mfp.core.plan.KeySpec;
import dev.mfp.plan.PreferenceStore;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /mfp defaults} — read and set the standing preferences (M8) without a GUI.
 *
 * <p>Here for the same reason {@code /mfp plan} came before the planner screen: the preferences
 * change which recipes and machines every plan uses, so they have to be settable and inspectable in
 * the headless check that has verified this project since M2. The Defaults screen writes the same
 * file and the same live instance.
 */
public final class MfpDefaultsCommand {

    private MfpDefaultsCommand() {}

    static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("defaults")
                .executes(ctx -> report(ctx.getSource()))

                .then(Commands.literal("tier")
                        .then(Commands.argument("tier", IntegerArgumentType.integer(0, 14))
                                .executes(ctx -> tier(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "tier"))))
                        .then(Commands.literal("clear")
                                .executes(ctx -> tier(ctx.getSource(), Preferences.NO_DEFAULT_TIER))))

                .then(Commands.literal("block")
                        .then(Commands.argument("item", StringArgumentType.greedyString())
                                .executes(ctx -> block(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "item"), true))))
                .then(Commands.literal("unblock")
                        .then(Commands.argument("item", StringArgumentType.greedyString())
                                .executes(ctx -> block(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "item"), false))))

                .then(Commands.literal("prefer")
                        .then(Commands.argument("item", StringArgumentType.greedyString())
                                .executes(ctx -> prefer(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "item")))))

                // One greedy argument holding both, split here. Brigadier's word and quotable
                // arguments reject the colon in an item id, so anything but the last argument would
                // refuse every id this command exists to take.
                .then(Commands.literal("recipe")
                        .then(Commands.argument("itemAndRecipe", StringArgumentType.greedyString())
                                .executes(ctx -> recipe(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "itemAndRecipe")))))

                // How every new plan starts (M11.3). A standing default rather than a constant
                // because it is a judgement that will change when the chooser is good enough.
                .then(Commands.literal("expansion")
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .executes(ctx -> expansion(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "mode")))))

                // How one machine of the player's is built (M12): the finest of the preferences and
                // the one that moves a multiblock's numbers most, so the headless check has to be
                // able to set it too.
                .then(Commands.literal("machine")
                        .then(Commands.argument("spec", StringArgumentType.greedyString())
                                .executes(ctx -> machine(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "spec")))))

                .then(Commands.literal("reload").executes(ctx -> {
                    PreferenceStore.reload();
                    return report(ctx.getSource());
                })));
    }

    private static int report(CommandSourceStack source) {
        Preferences preferences = PreferenceStore.get();
        send(source, "MFP standing defaults  (" + PreferenceStore.file() + ")", ChatFormatting.GOLD);
        send(source, "  tier: " + (preferences.defaultTier() == Preferences.NO_DEFAULT_TIER
                ? "unset - the lowest that can run each recipe"
                : String.valueOf(preferences.defaultTier())), ChatFormatting.WHITE);

        send(source, "  expansion: " + (preferences.autoResolve()
                ? "automatic - new plans pick their whole chain"
                : "by hand - new plans follow the default recipes below and stop where they run out"),
                ChatFormatting.WHITE);

        send(source, "  default recipes: " + preferences.defaultRecipes().size(), ChatFormatting.AQUA);
        preferences.defaultRecipes().forEach((key, recipeId) ->
                send(source, "    " + key + " <- " + recipeId, ChatFormatting.WHITE));

        send(source, "  preferred items: " + preferences.preferredItems().size(), ChatFormatting.AQUA);
        preferences.preferredItems().forEach(key ->
                send(source, "    " + key, ChatFormatting.WHITE));

        send(source, "  blocked items: " + preferences.blockedItems().size(), ChatFormatting.AQUA);
        preferences.blockedItems().forEach(key ->
                send(source, "    " + key, ChatFormatting.WHITE));

        send(source, "  machine builds: " + preferences.machineDefaults().size(), ChatFormatting.AQUA);
        preferences.machineDefaults().forEach((machineId, build) ->
                send(source, "    " + machineId + ": " + describe(build), ChatFormatting.WHITE));
        return 1;
    }

    private static int expansion(CommandSourceStack source, String mode) {
        boolean automatic = mode.equalsIgnoreCase("automatic") || mode.equalsIgnoreCase("auto")
                || mode.equalsIgnoreCase("on");
        PreferenceStore.get().autoResolve(automatic);
        PreferenceStore.save();
        send(source, "MFP: new plans expand " + (automatic ? "automatically" : "by hand")
                + " from here (each plan can still be switched with /mfp autoresolve)",
                ChatFormatting.GREEN);
        return 1;
    }

    private static int tier(CommandSourceStack source, int tier) {
        PreferenceStore.get().defaultTier(tier);
        PreferenceStore.save();
        send(source, tier == Preferences.NO_DEFAULT_TIER
                ? "MFP: default tier cleared" : "MFP: default tier is now " + tier,
                ChatFormatting.GREEN);
        return 1;
    }

    private static int block(CommandSourceStack source, String spec, boolean blocked) {
        MfpKey key = KeySpec.parse(spec);
        if (blocked) {
            PreferenceStore.get().blockItem(key);
        } else {
            PreferenceStore.get().unblockItem(key);
        }
        PreferenceStore.save();
        send(source, "MFP: " + key + (blocked
                        ? " is blocked - every recipe consuming it is now out of consideration"
                        : " is no longer blocked"),
                ChatFormatting.GREEN);
        return 1;
    }

    private static int prefer(CommandSourceStack source, String spec) {
        MfpKey key = KeySpec.parse(spec);
        PreferenceStore.get().preferItem(key);
        PreferenceStore.save();
        send(source, "MFP: " + key + " is preferred wherever an input accepts several",
                ChatFormatting.GREEN);
        return 1;
    }

    private static int recipe(CommandSourceStack source, String itemAndRecipe) {
        String[] parts = itemAndRecipe.trim().split("\\s+", 2);
        if (parts.length < 2) {
            send(source, "MFP: usage - /mfp defaults recipe <item> <recipe id | clear>",
                    ChatFormatting.RED);
            return 0;
        }
        return recipe(source, parts[0], parts[1].trim());
    }

    private static int recipe(CommandSourceStack source, String itemSpec, String recipeId) {
        MfpKey key = KeySpec.parse(itemSpec);
        if ("clear".equals(recipeId)) {
            PreferenceStore.get().clearDefaultRecipe(key);
            PreferenceStore.save();
            send(source, "MFP: no default recipe for " + key + " - the scorer chooses again",
                    ChatFormatting.GREEN);
            return 1;
        }

        // Checked against the index rather than taken on trust: a typo would otherwise sit in the
        // file doing nothing, and every plan would silently keep using the scorer's answer.
        RecipeIndex index = MfpIndexHolder.get(source.getServer());
        MfpRecipe recipe = index.recipe(recipeId);
        if (recipe == null) {
            send(source, "MFP: no such recipe: " + recipeId, ChatFormatting.RED);
            return 0;
        }
        if (!recipe.produces(key)) {
            send(source, "MFP: " + recipeId + " does not produce " + key, ChatFormatting.RED);
            return 0;
        }
        PreferenceStore.get().defaultRecipe(key, recipeId);
        PreferenceStore.save();
        send(source, "MFP: " + key + " is made by " + recipeId + " from now on", ChatFormatting.GREEN);
        return 1;
    }

    private static String describe(MachineDefaults build) {
        StringBuilder text = new StringBuilder();
        if (build.hasTier()) {
            text.append("tier ").append(build.tier());
        }
        if (build.hasParallels()) {
            text.append(text.isEmpty() ? "" : ", ").append(build.parallels()).append(" parallel");
        }
        build.structureOptions().forEach((key, value) ->
                text.append(text.isEmpty() ? "" : ", ").append(key).append('=').append(value));
        return text.isEmpty() ? "nothing stated" : text.toString();
    }

    /**
     * {@code /mfp defaults machine <machine id> [tier|parallels|<option> <value>|clear]}.
     *
     * <p>With no field it reports the build and, more usefully, the fields that machine actually
     * has — asked of the behaviours that will compute its rate, so the answer is the pack's rather
     * than a list written here that a pack update would falsify.
     */
    private static int machine(CommandSourceStack source, String spec) {
        String[] parts = spec.trim().split("\s+");
        RecipeIndex index = MfpIndexHolder.get(source.getServer());
        MfpMachine machine = index.machine(parts[0]);
        if (machine == null) {
            // Refused rather than stored: a build keyed on a typo sits in the file doing nothing,
            // and every plan goes on quietly reporting the machine as undescribed.
            send(source, "MFP: no such machine: " + parts[0], ChatFormatting.RED);
            return 0;
        }
        Preferences preferences = PreferenceStore.get();
        MachineDefaults build = preferences.machineDefaults(machine.id());

        if (parts.length == 1) {
            send(source, machine.id() + " (" + machine.displayName() + "): " + describe(build),
                    ChatFormatting.GOLD);
            send(source, "  tier" + (machine.multiblock() ? "" : " - fixed, this is a single block")
                    + ", parallels", ChatFormatting.WHITE);
            for (OptionSpec option : PlanSession.resolverFor(index).registry().optionsFor(machine)) {
                send(source, "  " + option.key() + (option.choices().isEmpty()
                                ? " - " + option.minimum() + ".." + option.maximum()
                                : " - " + option.choices()),
                        ChatFormatting.WHITE);
            }
            return 1;
        }

        if (parts.length == 2 && "clear".equals(parts[1])) {
            preferences.clearMachineDefaults(machine.id());
            PreferenceStore.save();
            send(source, "MFP: " + machine.id() + " is no longer described", ChatFormatting.GREEN);
            return 1;
        }
        if (parts.length < 3) {
            send(source, "MFP: usage - /mfp defaults machine <machine id> "
                    + "<tier|parallels|option key> <value|clear>", ChatFormatting.RED);
            return 0;
        }

        String field = parts[1];
        String value = parts[2];
        boolean clearing = "clear".equals(value);
        MachineDefaults updated;
        if ("tier".equals(field)) {
            updated = clearing ? build.withoutTier() : build.withTier(integer(value));
        } else if ("parallels".equals(field)) {
            updated = clearing ? build.withoutParallels() : build.withParallels(integer(value));
        } else if (clearing) {
            updated = build.withoutOption(field);
        } else {
            // Typed as the file types it: an option read as a number by one path and as a string by
            // the other is a build that compares unequal to itself across a save.
            Integer number = parseInteger(value);
            updated = build.withOption(field, number == null ? (Object) value : (Object) number);
        }
        preferences.machineDefaults(machine.id(), updated);
        PreferenceStore.save();
        send(source, "MFP: " + machine.id() + " is " + describe(
                preferences.machineDefaults(machine.id())), ChatFormatting.GREEN);
        return 1;
    }

    private static int integer(String value) {
        Integer parsed = parseInteger(value);
        return parsed == null ? 0 : parsed;
    }

    private static Integer parseInteger(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void send(CommandSourceStack source, String message, ChatFormatting colour) {
        source.sendSuccess(() -> Component.literal(message).withStyle(colour), false);
    }
}
