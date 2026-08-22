package dev.mfp.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.mfp.core.index.IndexStats;
import dev.mfp.core.index.RecipeIndex;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpOutput;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.index.MfpIndexHolder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * {@code /mfp} — inspection commands.
 *
 * <p>Deliberately built before any GUI. The whole pipeline (providers, conversion, index) is
 * verifiable through these, which is what makes the headless-first order in the plan work: if the
 * numbers here are wrong, no amount of UI will help.
 */
public final class MfpCommand {

    private MfpCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("mfp")
                .requires(source -> source.hasPermission(0));

        root.then(Commands.literal("index")
                .executes(ctx -> reportIndex(ctx.getSource(), 10))
                .then(Commands.argument("types", IntegerArgumentType.integer(0, 200))
                        .executes(ctx -> reportIndex(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "types")))));

        root.then(Commands.literal("reindex").executes(ctx -> {
            MfpIndexHolder.invalidate();
            ctx.getSource().sendSuccess(() -> Component.literal("MFP index invalidated."), false);
            return reportIndex(ctx.getSource(), 10);
        }));

        root.then(Commands.literal("skips").executes(ctx -> reportSkips(ctx.getSource())));

        root.then(Commands.literal("recipe")
                .then(Commands.argument("id", StringArgumentType.greedyString())
                        .executes(ctx -> reportRecipe(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id")))));

        // The machine catalog decides default tiers and every overclock calculation, so it needs to
        // be inspectable headlessly for the same reason the recipes do.
        root.then(Commands.literal("machines")
                .then(Commands.argument("recipeType", StringArgumentType.greedyString())
                        .executes(ctx -> reportMachines(ctx.getSource(),
                                StringArgumentType.getString(ctx, "recipeType")))));

        root.then(Commands.literal("machine")
                .then(Commands.argument("id", StringArgumentType.greedyString())
                        .executes(ctx -> reportMachine(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id")))));

        // Which of the game's recipe modifiers MFP actually models. A coverage audit rather than an
        // inspection: it is the only thing here that can find a missing behaviour before a plan
        // silently reports the wrong number for it.
        MfpModifiersCommand.register(root);

        // Where a multiblock's structure is declared, read without a client (M16).
        MfpStructureCommand.register(root);

        // The items whose tier is a gate rather than a cost (M17). A coverage audit like
        // `mfp modifiers`: the number quietly dropping to zero after a fork update is the shape of
        // failure that lets a plan offer an IV component to a player at HV.
        root.then(Commands.literal("components")
                .executes(ctx -> reportComponents(ctx.getSource())));


        // Planning, explanation and the recipe picker's ranked list.
        MfpPlanCommand.register(root);

        // The standing defaults every plan is expanded against (M8).
        MfpDefaultsCommand.register(root);

        dispatcher.register(root);
    }

    private static int reportIndex(CommandSourceStack source, int typeLimit) {
        RecipeIndex index = MfpIndexHolder.get(source.getServer());
        IndexStats stats = index.stats();

        send(source, "MFP index", ChatFormatting.GOLD);
        send(source, "  recipes:  " + stats.recipeCount(), ChatFormatting.WHITE);
        send(source, "  machines: " + stats.machineCount(), ChatFormatting.WHITE);
        send(source, "  built in: " + stats.buildMillis() + " ms", ChatFormatting.GRAY);

        if (stats.overridden() > 0) {
            send(source, "  overridden by priority: " + stats.overridden(), ChatFormatting.GRAY);
        }

        send(source, "  skipped:  " + stats.skips().size(),
                stats.isClean() ? ChatFormatting.GREEN : ChatFormatting.RED);
        if (!stats.isClean()) {
            send(source, "  run /mfp skips to see why", ChatFormatting.RED);
        }

        if (!stats.recipesByProvider().isEmpty()) {
            send(source, "  by provider:", ChatFormatting.AQUA);
            for (Map.Entry<String, Integer> entry : stats.recipesByProvider().entrySet()) {
                send(source, "    " + entry.getKey() + ": " + entry.getValue(), ChatFormatting.WHITE);
            }
        }

        if (typeLimit > 0 && !stats.recipesByType().isEmpty()) {
            int total = stats.recipesByType().size();
            String heading = total <= typeLimit
                    ? "  by recipe type (" + total + "):"
                    : "  by recipe type (top " + typeLimit + " of " + total + "):";
            send(source, heading, ChatFormatting.AQUA);

            int shown = 0;
            for (Map.Entry<String, Integer> entry : stats.recipesByType().entrySet()) {
                if (shown++ >= typeLimit) {
                    send(source, "    ... and " + (total - typeLimit) + " more", ChatFormatting.GRAY);
                    break;
                }
                send(source, "    " + entry.getKey() + ": " + entry.getValue(), ChatFormatting.WHITE);
            }
        }

        return stats.recipeCount();
    }

    /** Print one recipe exactly as MFP modelled it — the first stop when a number looks wrong. */
    private static int reportRecipe(CommandSourceStack source, String id) {
        MfpRecipe recipe = MfpIndexHolder.get(source.getServer()).recipe(id);
        if (recipe == null) {
            send(source, "MFP: no indexed recipe with id '" + id + "'", ChatFormatting.RED);
            return 0;
        }

        send(source, recipe.id(), ChatFormatting.GOLD);
        send(source, "  type: " + recipe.recipeTypeId() + "  (via " + recipe.providerId() + ")",
                ChatFormatting.GRAY);
        send(source, "  duration: " + (recipe.hasRate()
                ? trim(recipe.durationTicks()) + " ticks"
                : "instant (no intrinsic rate)"), ChatFormatting.GRAY);
        if (recipe.euIn() > 0 || recipe.euOut() > 0) {
            send(source, "  energy: in " + recipe.euIn() + " EU/t, out " + recipe.euOut()
                    + " EU/t, " + recipe.amperage() + "A", ChatFormatting.GRAY);
        }
        if (recipe.minTier() != MfpRecipe.NO_TIER) {
            send(source, "  min tier: " + recipe.minTier(), ChatFormatting.GRAY);
        }

        send(source, "  inputs:", ChatFormatting.AQUA);
        for (MfpIngredient input : recipe.inputs()) {
            String candidates = input.isAmbiguous()
                    ? input.primary() + " (+" + (input.candidates().size() - 1) + " alternatives)"
                    : input.primary().toString();
            String consumed = input.consumed() ? "" : "  [not consumed]";
            send(source, "    " + trim(input.amount()) + " x " + candidates + consumed,
                    ChatFormatting.WHITE);
        }

        send(source, "  outputs:", ChatFormatting.AQUA);
        for (MfpOutput output : recipe.outputs()) {
            String chance = output.isChanced()
                    ? "  (" + trim(output.chance() * 100) + "% " + output.mode() + ")"
                    : "";
            send(source, "    " + trim(output.amount()) + " x " + output.key() + chance,
                    ChatFormatting.WHITE);
        }

        if (!recipe.conditions().isEmpty()) {
            send(source, "  conditions (recorded, not evaluated):", ChatFormatting.YELLOW);
            recipe.conditions().forEach(c ->
                    send(source, "    " + c.type() + ": " + c.description(), ChatFormatting.WHITE));
        }
        if (!recipe.extra().isEmpty()) {
            send(source, "  extra: " + recipe.extra(), ChatFormatting.GRAY);
        }
        return 1;
    }

    /** Which machines can run a recipe type, in the order the default-machine policy sees them. */
    /**
     * Every tiered component MFP knows about, by tier.
     *
     * <p>An audit rather than a listing, and the same argument {@code mfp modifiers} makes: the
     * classification comes from GregTech's own tags, so a fork that renames or drops one costs MFP
     * a whole family silently, and the symptom - a plan offering a machine the player cannot build
     * - shows up nowhere near the cause. A count per tier is enough to see it happen.
     */
    private static int reportComponents(CommandSourceStack source) {
        RecipeIndex index = MfpIndexHolder.get(source.getServer());
        Map<dev.mfp.core.model.MfpKey, Integer> tiers = index.componentTiers();
        if (tiers.isEmpty()) {
            send(source, "MFP: no tiered components - either there is no GregTech here, or the "
                    + "component tags have moved and NOTHING is being gated by tier",
                    ChatFormatting.RED);
            return 0;
        }

        Map<Integer, List<String>> byTier = new java.util.TreeMap<>();
        tiers.forEach((key, tier) ->
                byTier.computeIfAbsent(tier, t -> new ArrayList<>()).add(String.valueOf(key)));

        send(source, "MFP tiered components: " + tiers.size() + " item(s) whose tier is a gate, "
                + "not a cost", ChatFormatting.GREEN);
        int standingTier = dev.mfp.plan.PreferenceStore.get().defaultTier();
        byTier.forEach((tier, keys) -> {
            keys.sort(Comparator.naturalOrder());
            String sample = keys.size() > 4
                    ? String.join(", ", keys.subList(0, 4)) + ", ... "
                    : String.join(", ", keys);
            send(source, "  T" + tier + " (" + dev.mfp.core.behaviour.GtTiers.name(tier) + "): "
                            + keys.size() + "  " + sample,
                    standingTier >= 0 && tier > standingTier
                            ? ChatFormatting.YELLOW : ChatFormatting.WHITE);
        });
        if (standingTier >= 0) {
            send(source, "  yellow is above the tier you build at, so refused outright - "
                    + "/mfp ceiling off to see them anyway", ChatFormatting.GRAY);
        } else {
            send(source, "  no default tier set, so none of this gates anything", ChatFormatting.GRAY);
        }
        return tiers.size();
    }

    private static int reportMachines(CommandSourceStack source, String recipeTypeId) {
        List<MfpMachine> machines = MfpIndexHolder.get(source.getServer()).machinesFor(recipeTypeId.trim());
        if (machines.isEmpty()) {
            send(source, "MFP: no machine runs recipe type '" + recipeTypeId + "'", ChatFormatting.RED);
            return 0;
        }

        // In the order the picker would offer them, not by tier and certainly not by id: this
        // command exists to answer "which machine will a line get, and why that one", and printing
        // some other order made a defaulting bug invisible from here (the pack's chemical reactors,
        // STATUS §12.9). The first row is the default.
        dev.mfp.core.index.RecipeIndex index = MfpIndexHolder.get(source.getServer());
        List<MfpMachine> ordered = new java.util.ArrayList<>(machines);
        ordered.sort(dev.mfp.core.select.MachinePicker.order(index,
                dev.mfp.plan.PreferenceStore.get().defaultTier()));

        send(source, machines.size() + " machine(s) for " + recipeTypeId
                + ", default first", ChatFormatting.GOLD);
        // And whether the player could actually build each one (M17 slice B). A listing that offers
        // a default the planner has just declared unbuildable is worse than either answer alone -
        // and the two numbers beside it are a comparator's shallow halves, which is exactly the
        // distinction worth showing here: buildCost and partsCost look one level, this looks all
        // the way down.
        dev.mfp.plan.PlanSession session = dev.mfp.plan.PlanSession.of(source.getTextName());
        dev.mfp.core.plan.Plan plan = session == null ? null : session.plan();
        dev.mfp.core.select.RecipeChooser chooser =
                new dev.mfp.core.select.RecipeChooser(index, dev.mfp.plan.PreferenceStore.get());
        for (MfpMachine machine : ordered) {
            int build = dev.mfp.core.select.MachinePicker.buildCost(index, machine);
            int parts = dev.mfp.core.select.MachinePicker.partsCost(index, machine);
            dev.mfp.core.model.MfpKey missing = chooser.missingPartOf(machine, plan);
            send(source, "  " + describe(machine)
                            + "  [built at " + (build < 0 ? "?" : "T" + build)
                            + ", from parts up to " + (parts == Integer.MAX_VALUE ? "?" : "T" + parts)
                            + "]"
                            + (missing == null ? ""
                                    : missing.toString().equals(machine.id())
                                            ? "  [you cannot build one at your tier]"
                                            : "  [you cannot build one: needs " + missing + "]"),
                    missing == null ? ChatFormatting.WHITE : ChatFormatting.YELLOW);
        }
        return machines.size();
    }

    private static int reportMachine(CommandSourceStack source, String id) {
        MfpMachine machine = MfpIndexHolder.get(source.getServer()).machine(id.trim());
        if (machine == null) {
            send(source, "MFP: no indexed machine with id '" + id + "'", ChatFormatting.RED);
            return 0;
        }

        send(source, machine.id(), ChatFormatting.GOLD);
        send(source, "  name: " + machine.displayName() + "  (via " + machine.providerId() + ")",
                ChatFormatting.GRAY);
        // A multiblock's definition carries no voltage because its voltage comes from whichever
        // energy hatch is built into it (plan 8.2), so "unpowered" is true of the definition and
        // false about the machine - and it was being printed against a super EBF drawing thousands
        // of EU/t. The distinction is the same one the machine picker makes.
        String power = machine.isPowered() ? "  (" + machine.maxVoltage() + " EU/t)"
                : machine.multiblock() ? "  (voltage from its energy hatch)"
                : "  (unpowered)";
        send(source, "  tier: " + (machine.tier() == MfpRecipe.NO_TIER ? "untiered" : machine.tier())
                + power, ChatFormatting.GRAY);
        send(source, "  form: " + (machine.multiblock() ? "multiblock" : "single block"), ChatFormatting.GRAY);

        send(source, "  recipe types:", ChatFormatting.AQUA);
        machine.recipeTypeIds().forEach(type -> send(source, "    " + type, ChatFormatting.WHITE));

        if (machine.modifierIds().isEmpty()) {
            send(source, "  modifiers: none", ChatFormatting.GRAY);
        } else {
            // Order is semantic: GregTech folds these left, so the list is the application order.
            send(source, "  modifiers (in order):", ChatFormatting.AQUA);
            machine.modifierIds().forEach(mod -> send(source, "    " + mod, ChatFormatting.WHITE));
        }
        return 1;
    }

    /** Lowest tier first, single blocks before multiblocks — the order §8.2 picks defaults in. */
    private static List<MfpMachine> sortedByTier(List<MfpMachine> machines) {
        List<MfpMachine> sorted = new ArrayList<>(machines);
        sorted.sort(Comparator.comparing(MfpMachine::multiblock)
                .thenComparingInt(MfpMachine::tier)
                .thenComparing(MfpMachine::id));
        return sorted;
    }

    private static String describe(MfpMachine machine) {
        StringBuilder sb = new StringBuilder(machine.id());
        if (machine.tier() != MfpRecipe.NO_TIER) {
            sb.append("  tier ").append(machine.tier());
        }
        if (machine.isPowered()) {
            sb.append("  ").append(machine.maxVoltage()).append(" EU/t");
        }
        if (machine.multiblock()) {
            sb.append("  [multiblock]");
        }
        if (!machine.modifierIds().isEmpty()) {
            sb.append("  ").append(machine.modifierIds());
        }
        return sb.toString();
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static int reportSkips(CommandSourceStack source) {
        IndexStats stats = MfpIndexHolder.get(source.getServer()).stats();
        if (stats.isClean()) {
            send(source, "MFP: no recipes were skipped.", ChatFormatting.GREEN);
            return 0;
        }

        send(source, "MFP skipped " + stats.skips().size() + " recipe(s):", ChatFormatting.RED);
        int shown = 0;
        for (IndexStats.Skip skip : stats.skips()) {
            if (shown++ >= 30) {
                send(source, "  ... and " + (stats.skips().size() - 30) + " more; see the log",
                        ChatFormatting.GRAY);
                break;
            }
            send(source, "  [" + skip.providerId() + "] " + skip.recipeId() + " - " + skip.reason(),
                    ChatFormatting.WHITE);
        }
        return stats.skips().size();
    }

    private static void send(CommandSourceStack source, String message, ChatFormatting colour) {
        source.sendSuccess(() -> Component.literal(message).withStyle(colour), false);
    }
}
