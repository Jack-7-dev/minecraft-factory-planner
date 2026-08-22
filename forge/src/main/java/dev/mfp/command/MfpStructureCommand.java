package dev.mfp.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.mfp.MfpMod;
import dev.mfp.integration.gtceu.GtStructure;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /mfp structure <machine id> [page]} — the headless half of M16.
 *
 * <p>The spike asked two things: where a recipe viewer reads a multiblock's structure from, and
 * whether the same data can be reached without a client. {@link GtStructure} answers the first in
 * its javadoc; this command <em>is</em> the answer to the second, because it prints the block list
 * for a named multiblock on a dedicated server, where no viewer, no dummy world and no render
 * thread exists. A claim that something is readable headless is worth exactly as much as the
 * transcript that shows it, so this exists to be run by {@code tools/packtest.sh} against the real
 * pack rather than only against the dev run's GregTech.
 *
 * <p>It is deliberately an inspection command and not yet a solver input. What to do with the list
 * is M17's and M19's decision; getting it out of the game is this milestone's.
 *
 * <p>With no page it prints page 0 and says how many there are. A page is one legal answer to the
 * structure's predicates, not the only one — see {@link GtStructure}.
 */
final class MfpStructureCommand {

    private MfpStructureCommand() {}

    static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        // Greedy, like every other id argument here, because Brigadier's unquoted word stops at the
        // colon in a resource location and "mfp structure gtceu:electric_blast_furnace" is what
        // anybody types. The page is peeled off the end of the string rather than made a second
        // argument, since a greedy argument cannot be followed by one.
        root.then(Commands.literal("structure")
                .executes(ctx -> list(ctx.getSource()))
                .then(Commands.argument("id", StringArgumentType.greedyString())
                        .executes(ctx -> report(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id")))));
    }

    private static int report(CommandSourceStack source, String argument) {
        String machineId = argument.trim();
        int page = 0;
        int space = machineId.lastIndexOf(' ');
        if (space > 0) {
            try {
                page = Integer.parseInt(machineId.substring(space + 1).trim());
                machineId = machineId.substring(0, space).trim();
            } catch (NumberFormatException ignored) {
                // Not a page; leave the whole string as the id and let the lookup report it.
            }
        }
        return report(source, machineId, page);
    }

    /**
     * With no argument, the coverage audit rather than a listing: <em>every</em> multiblock in the
     * game is read, and the ones that fail are named.
     *
     * <p>Two machines printing a parts list is an anecdote. The claim M16 has to leave behind is that
     * structures are readable headless <em>in general</em>, and the only honest way to say that is to
     * read all of them and report the exceptions — the same argument {@code /mfp modifiers} makes
     * about behaviours. A machine whose shape supplier throws on a server is exactly the client-only
     * case the spike was told to look for, so it is named rather than counted.
     */
    private static int list(CommandSourceStack source) {
        if (missingGregTech(source)) {
            return 0;
        }
        List<String> ids = GtStructure.multiblockIds();
        List<String> failed = new ArrayList<>();
        int read = 0;
        int shapes = 0;
        long blocks = 0;
        long start = System.nanoTime();

        for (String id : ids) {
            try {
                GtStructure.Shape shape = GtStructure.read(id, 0);
                if (shape == null) {
                    failed.add(id + ": no shape 0");
                    continue;
                }
                read++;
                shapes += shape.shapeCount();
                blocks += shape.blockCount() - shape.emptyCount();
            } catch (RuntimeException | LinkageError e) {
                failed.add(id + ": " + e);
            }
        }

        send(source, "MFP structures: " + read + " of " + ids.size() + " multiblocks read headless",
                failed.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.RED);
        send(source, "  " + shapes + " shapes, " + blocks + " placed blocks on page 0, in "
                + ((System.nanoTime() - start) / 1_000_000) + " ms", ChatFormatting.GRAY);
        for (String failure : failed) {
            send(source, "  FAILED " + failure, ChatFormatting.RED);
        }
        send(source, "  /mfp structure <id> [page] for one of them", ChatFormatting.GRAY);
        return read;
    }

    private static int report(CommandSourceStack source, String machineId, int page) {
        if (missingGregTech(source)) {
            return 0;
        }

        GtStructure.Shape shape;
        try {
            shape = GtStructure.read(machineId, page);
        } catch (RuntimeException e) {
            // A shape supplier that throws is the interesting case, not a reason to say "unknown":
            // it would mean the structure is not in fact readable outside a client for this machine.
            send(source, "Structure of " + machineId + " could not be built: " + e, ChatFormatting.RED);
            return 0;
        }

        if (shape == null) {
            int count = GtStructure.shapeCount(machineId);
            if (count == 0) {
                send(source, "No multiblock " + machineId + " (/mfp structure lists them)",
                        ChatFormatting.RED);
            } else {
                send(source, machineId + " has " + count + " shape(s); page " + page + " is not one",
                        ChatFormatting.RED);
            }
            return 0;
        }

        String pages = shape.shapeCount() == 1
                ? ""
                : "  (page " + shape.shapeIndex() + " of " + shape.shapeCount() + ")";
        send(source, "Structure of " + shape.machineId() + pages, ChatFormatting.GOLD);
        send(source, "  " + shape.sizeX() + " x " + shape.sizeY() + " x " + shape.sizeZ()
                + " = " + shape.blockCount() + " cells, " + shape.emptyCount() + " empty",
                ChatFormatting.GRAY);
        for (GtStructure.Part part : shape.parts()) {
            send(source, "  " + pad(part.count()) + " x " + part.itemId()
                    + "  (" + part.name() + ")", ChatFormatting.WHITE);
        }
        send(source, "  " + shape.parts().size() + " distinct blocks. One legal build of the "
                + "structure's predicates, not the only one.", ChatFormatting.GRAY);
        return shape.parts().size();
    }

    /** Right-aligned to four, so a casing count and a hatch count line up in a server log. */
    private static String pad(int count) {
        String text = Integer.toString(count);
        return " ".repeat(Math.max(0, 4 - text.length())) + text;
    }

    private static boolean missingGregTech(CommandSourceStack source) {
        if (MfpMod.isGregTechLoaded()) {
            return false;
        }
        send(source, "No GregTech loaded; multiblock structures come from its machine definitions.",
                ChatFormatting.RED);
        return true;
    }

    private static void send(CommandSourceStack source, String message, ChatFormatting colour) {
        source.sendSuccess(() -> Component.literal(message).withStyle(colour), false);
    }
}
