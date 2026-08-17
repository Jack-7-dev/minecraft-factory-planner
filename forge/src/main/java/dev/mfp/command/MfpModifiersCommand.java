package dev.mfp.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.mfp.behaviour.BehaviourConfig;
import dev.mfp.core.behaviour.BehaviourContext;
import dev.mfp.core.behaviour.BehaviourRegistry;
import dev.mfp.core.behaviour.BehaviourRegistry.ModifierStatus;
import dev.mfp.core.index.RecipeIndex;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.MachineConfig;
import dev.mfp.index.MfpIndexHolder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code /mfp modifiers} — the coverage audit: every recipe modifier in the loaded game, and
 * whether MFP models it.
 *
 * <p>Written after a modifier MFP had never implemented was found by accident, from a player
 * noticing that changing an energy hatch did nothing to a plan (STATUS §14). The bug was not that
 * {@code chemical_reactor_oc} was missing — it is that <em>nothing could have told us</em> it was
 * missing short of planning with that machine and disbelieving the answer. A gap in the behaviour
 * table is silent by construction: the chain is non-empty, so the line reports a confident number.
 *
 * <p>So this asks the question the other way round. It walks the machine catalog, not a plan, and
 * reports every modifier id any machine declares against what the registry answers to. That covers
 * machines nobody has planned with yet, which is where the remaining gaps must be — the ones on
 * well-trodden machines have already been found the expensive way.
 *
 * <p>It reads the game's own registrations, so a pack that adds machines is audited too, and it
 * needs no knowledge of GregTech's source. That matters more than it sounds: MFP's compile target
 * is a fork, and reading the fork's sources tells you nothing about what the <em>pack</em>
 * registered on top of it.
 */
final class MfpModifiersCommand {

    private MfpModifiersCommand() {}

    static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("modifiers")
                .executes(ctx -> report(ctx.getSource(), null))
                .then(Commands.argument("modifier", StringArgumentType.greedyString())
                        .executes(ctx -> report(ctx.getSource(),
                                StringArgumentType.getString(ctx, "modifier").trim()))));
    }

    private static int report(CommandSourceStack source, String only) {
        RecipeIndex index = MfpIndexHolder.get(source.getServer());
        BehaviourRegistry registry = BehaviourConfig.loadRegistry();

        // Sorted by id so two runs of this command diff cleanly; the machine lists inside keep
        // catalog order, which is the order a reader will find them in the game.
        Map<String, List<MfpMachine>> byModifier = new TreeMap<>();
        List<MfpMachine> bare = new ArrayList<>();

        for (MfpMachine machine : index.machines()) {
            List<String> ids = machine.modifierIds();
            if (ids == null || ids.isEmpty()) {
                bare.add(machine);
                continue;
            }
            for (String id : ids) {
                byModifier.computeIfAbsent(id, k -> new ArrayList<>()).add(machine);
            }
        }

        if (only != null) {
            return detail(source, registry, only, byModifier.getOrDefault(only, List.of()));
        }

        Map<ModifierStatus, List<String>> grouped = new LinkedHashMap<>();
        for (String id : byModifier.keySet()) {
            grouped.computeIfAbsent(registry.statusOf(id), k -> new ArrayList<>()).add(id);
        }

        send(source, "MFP recipe modifiers in this game: " + byModifier.size()
                + " distinct, across " + index.machines().size() + " machines", ChatFormatting.GOLD);

        // Unmodelled first and in red, because this command exists for that list and nothing else.
        // Everything below it is context for judging whether the list is plausibly complete.
        section(source, "unmodelled - a named rule nothing here answers to", ChatFormatting.RED,
                grouped.get(ModifierStatus.UNMODELLED), byModifier, true);
        section(source, "anonymous - no name to match on; shape-matched or not at all",
                ChatFormatting.YELLOW, grouped.get(ModifierStatus.ANONYMOUS), byModifier, true);
        section(source, "neutral - deliberately a no-op", ChatFormatting.GRAY,
                grouped.get(ModifierStatus.NEUTRAL), byModifier, false);
        section(source, "modelled", ChatFormatting.GREEN,
                grouped.get(ModifierStatus.MODELLED), byModifier, false);

        // A behaviour registered for a modifier this game never uses is not an error — MFP ships one
        // table for every pack — but it is worth seeing, because it is also what a renamed or
        // dropped modifier looks like from here.
        List<String> unused = new ArrayList<>(registry.modelledIds());
        unused.removeAll(byModifier.keySet());
        unused.sort(Comparator.naturalOrder());
        if (!unused.isEmpty()) {
            send(source, "  modelled but unused by any machine here: " + String.join(", ", unused),
                    ChatFormatting.GRAY);
        }

        if (!bare.isEmpty()) {
            send(source, "  " + bare.size() + " machine(s) declare no modifier at all"
                    + " (run the recipe as written unless a shape matcher claims them)",
                    ChatFormatting.GRAY);
        }

        unclaimed(source, index, registry);

        int unmodelled = grouped.getOrDefault(ModifierStatus.UNMODELLED, List.of()).size();
        send(source, unmodelled == 0
                        ? "MFP: every named modifier in this game is modelled."
                        : "MFP: " + unmodelled + " named modifier(s) unmodelled - run"
                                + " /mfp modifiers <id> for the machines affected.",
                unmodelled == 0 ? ChatFormatting.GREEN : ChatFormatting.RED);
        return unmodelled;
    }

    /**
     * Machines nothing in the registry claims at all — the id audit's blind spot.
     *
     * <p>An id can be accounted for while the machine carrying it is not. Anonymous modifiers are
     * the shape matcher's job by design, so {@code lambda:GTRecipeModifiers} being expected does not
     * mean every machine wearing it is understood; and a machine whose whole list is anonymous, with
     * no shape matcher claiming it, ends up with an empty chain. That is the case the resolver marks
     * UNKNOWN, so it is honest rather than wrong — but it is still a machine MFP cannot plan with,
     * and counting them is the only way to know whether that set is two machines or two hundred.
     *
     * <p>Needs a recipe, because {@code appliesTo} is a question about the pair. One recipe per
     * recipe type is enough: a behaviour that claims a machine for one recipe of a type and not
     * another is claiming it.
     */
    private static void unclaimed(CommandSourceStack source, RecipeIndex index,
            BehaviourRegistry registry) {
        Map<String, MfpRecipe> sampleByType = new LinkedHashMap<>();
        for (MfpRecipe recipe : index.all()) {
            sampleByType.putIfAbsent(recipe.recipeTypeId(), recipe);
        }

        List<MfpMachine> unclaimed = new ArrayList<>();
        int checked = 0;
        for (MfpMachine machine : index.machines()) {
            MfpRecipe sample = null;
            for (String typeId : machine.recipeTypeIds()) {
                sample = sampleByType.get(typeId);
                if (sample != null) {
                    break;
                }
            }
            if (sample == null) {
                // No recipe of any type it runs, so there is nothing to plan with it either way.
                continue;
            }
            checked++;
            int tier = machine.tier() >= 0 ? machine.tier() : 5;
            BehaviourContext context =
                    BehaviourContext.of(sample, machine, MachineConfig.of(machine.id(), tier));
            if (registry.chainFor(context).isEmpty()) {
                unclaimed.add(machine);
            }
        }

        if (unclaimed.isEmpty()) {
            send(source, "  every one of the " + checked + " machines with recipes is claimed by"
                    + " at least one behaviour", ChatFormatting.GREEN);
            return;
        }
        send(source, "  " + unclaimed.size() + " of " + checked + " machines with recipes are"
                + " claimed by nothing, so their rate is reported UNKNOWN:", ChatFormatting.YELLOW);
        for (MfpMachine machine : unclaimed) {
            send(source, "    " + machine.id() + "  " + machine.modifierIds(), ChatFormatting.WHITE);
        }
    }

    private static void section(CommandSourceStack source, String heading, ChatFormatting colour,
            List<String> ids, Map<String, List<MfpMachine>> byModifier, boolean listMachines) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        send(source, "  " + heading + " (" + ids.size() + "):", colour);
        for (String id : ids) {
            List<MfpMachine> machines = byModifier.get(id);
            send(source, "    " + id + "  - " + machines.size() + " machine(s)", colour);
            if (listMachines) {
                send(source, "        " + names(machines, 6), ChatFormatting.WHITE);
            }
        }
    }

    private static int detail(CommandSourceStack source, BehaviourRegistry registry, String id,
            List<MfpMachine> machines) {
        if (machines.isEmpty()) {
            send(source, "MFP: no machine in this game declares '" + id + "'"
                    + (registry.modelledIds().contains(id)
                            ? " (a behaviour is registered for it, so it is modelled if a pack adds one)"
                            : ""),
                    ChatFormatting.RED);
            return 0;
        }
        send(source, id + "  [" + registry.statusOf(id).name().toLowerCase(java.util.Locale.ROOT)
                + "]  - " + machines.size() + " machine(s)", ChatFormatting.GOLD);
        for (MfpMachine machine : machines) {
            // The whole declared list, in order, because a modifier's effect depends on what runs
            // before it — that ordering is the reason MFP records these as a list at all.
            send(source, "  " + machine.id() + "  " + machine.modifierIds(), ChatFormatting.WHITE);
        }
        return machines.size();
    }

    private static String names(List<MfpMachine> machines, int limit) {
        List<String> ids = new ArrayList<>();
        for (MfpMachine machine : machines) {
            if (ids.size() >= limit) {
                ids.add("... and " + (machines.size() - limit) + " more");
                break;
            }
            ids.add(machine.id());
        }
        return String.join(", ", ids);
    }

    private static void send(CommandSourceStack source, String message, ChatFormatting colour) {
        source.sendSuccess(() -> Component.literal(message).withStyle(colour), false);
    }
}
