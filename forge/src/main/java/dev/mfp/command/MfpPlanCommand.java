package dev.mfp.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.mfp.core.behaviour.MachineBehaviour;
import dev.mfp.core.behaviour.OptionSpec;
import dev.mfp.core.behaviour.ThroughputResult;
import dev.mfp.behaviour.RawMaterialConfig;
import dev.mfp.core.index.RecipeIndex;
import dev.mfp.core.model.Confidence;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.Line;
import dev.mfp.core.plan.MachineConfig;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.plan.SolverMode;
import dev.mfp.core.plan.TargetOutput;
import dev.mfp.core.select.ChooserResult;
import dev.mfp.core.select.RecipeChooser;
import dev.mfp.core.select.RecipeScorer;
import dev.mfp.core.solver.BehaviourThroughputResolver;
import dev.mfp.core.solver.LineResult;
import dev.mfp.core.solver.SolveResult;
import dev.mfp.core.solver.Solvers;
import dev.mfp.index.MfpIndexHolder;
import dev.mfp.core.plan.KeySpec;
import dev.mfp.plan.PlanSession;
import dev.mfp.plan.PreferenceStore;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.List;
import java.util.Map;

/**
 * {@code /mfp plan}, {@code /mfp explain} and {@code /mfp alternatives}.
 *
 * <p>These exist before any GUI on purpose. The whole pipeline — selection, ordering, behaviour
 * resolution, solving — is driven and inspected through them, so a wrong number can be traced to a
 * rule without a single pixel being drawn. {@code /mfp explain} in particular is the debugging tool
 * for the entire project (plan P7): it prints the behaviour chain that produced each line's rate,
 * in the order the chain was applied.
 */
public final class MfpPlanCommand {

    private MfpPlanCommand() {}

    static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        // Rate first so the item id can be greedy: ids contain colons, which brigadier's word
        // argument will not accept.
        root.then(Commands.literal("plan")
                .then(Commands.argument("perSecond", DoubleArgumentType.doubleArg(0.0001))
                        .then(Commands.argument("item", StringArgumentType.greedyString())
                                .executes(ctx -> plan(ctx.getSource(),
                                        DoubleArgumentType.getDouble(ctx, "perSecond"),
                                        StringArgumentType.getString(ctx, "item")))))
                // Without a rate, so the fluid default is reachable from the command as well as from
                // the GUI - a bucket a second rather than the drip "1" would ask for.
                .then(Commands.argument("onlyItem", StringArgumentType.greedyString())
                        .executes(ctx -> plan(ctx.getSource(), -1,
                                StringArgumentType.getString(ctx, "onlyItem")))));

        root.then(Commands.literal("explain")
                .executes(ctx -> explain(ctx.getSource(), -1))
                .then(Commands.argument("line", IntegerArgumentType.integer(1))
                        .executes(ctx -> explain(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "line")))));

        // Pins the engine for every plan that follows, which is the only way to run the same chain
        // through two engines and diff them without a client. The four measured chains of STATUS 6c
        // are checked this way, and M10 accepted the simplex engine on exactly that comparison.
        // Byproduct feeding on and off, for the same reason as the solver override: the only way to
        // measure what the pass did is to run the same target with it off and diff.
        root.then(Commands.literal("byproducts")
                .then(Commands.argument("state", StringArgumentType.word())
                        .executes(ctx -> byproducts(ctx.getSource(),
                                StringArgumentType.getString(ctx, "state")))));

        // Hand-built plans, headlessly (M11.3). The GUI's version of this is a toggle in Settings and
        // a click on an import; without these two the mode could only be tried in a client, where
        // "the same numbers as the automatic plan" is a claim nobody can check by diffing.
        root.then(Commands.literal("autoresolve")
                .then(Commands.argument("state", StringArgumentType.word())
                        .executes(ctx -> autoresolve(ctx.getSource(),
                                StringArgumentType.getString(ctx, "state")))));

        root.then(Commands.literal("resolve")
                .executes(ctx -> resolve(ctx.getSource(), null))
                .then(Commands.argument("item", StringArgumentType.greedyString())
                        .executes(ctx -> resolve(ctx.getSource(),
                                StringArgumentType.getString(ctx, "item")))));

        // Pinning a specific recipe, which /mfp resolve cannot do: it answers an import with the
        // scorer's own top choice, and the recipe worth investigating is usually not that one. Every
        // machine-behaviour bug so far has been found by making one named recipe run on one named
        // machine and reading the working — the Large Chemical Reactor's missing overclock could
        // only be reached headlessly by editing the standing-defaults file, which is a poor
        // substitute for a command (STATUS §14).
        root.then(Commands.literal("pin")
                .executes(ctx -> pins(ctx.getSource()))
                .then(Commands.argument("spec", StringArgumentType.greedyString())
                        .executes(ctx -> pin(ctx.getSource(),
                                StringArgumentType.getString(ctx, "spec")))));

        // The other half of pinning. A coil, a parallel hatch or a points setting changes throughput
        // as much as the recipe does, and until now it could only be set in the GUI - so a headless
        // blast furnace had no coil at all and a headless chemical reactor always assumed
        // cupronickel, which left everything coil-dependent verifiable only by unit test
        // (STATUS §14.8).
        root.then(Commands.literal("option")
                .then(Commands.argument("spec", StringArgumentType.greedyString())
                        .executes(ctx -> option(ctx.getSource(),
                                StringArgumentType.getString(ctx, "spec")))));

        root.then(Commands.literal("solver")
                .then(Commands.argument("mode", StringArgumentType.word())
                        .executes(ctx -> solver(ctx.getSource(),
                                StringArgumentType.getString(ctx, "mode")))));

        root.then(Commands.literal("alternatives")
                .then(Commands.argument("item", StringArgumentType.greedyString())
                        .executes(ctx -> alternatives(ctx.getSource(),
                                StringArgumentType.getString(ctx, "item")))));
    }

    // ------------------------------------------------------------------ plan

    /** @param perSecond the rate asked for, or a negative number for "whatever the default is" */
    private static int plan(CommandSourceStack source, double perSecond, String itemSpec) {
        RecipeIndex index = MfpIndexHolder.get(source.getServer());
        MfpKey key = parseKey(itemSpec);
        if (perSecond < 0) {
            perSecond = TargetOutput.defaultRate(key);
        }

        if (index.producing(key).isEmpty()) {
            send(source, "MFP: nothing in the index produces " + key, ChatFormatting.RED);
            send(source, "  (prefix with 'fluid:' for fluids, e.g. /mfp plan 100 fluid:gtceu:oxygen)",
                    ChatFormatting.GRAY);
            return 0;
        }

        RawMaterialConfig.reload();
        // Re-read on every plan, like the raw materials and the behaviour overrides: editing
        // config/mfp/preferences.json and running the command again is the loop this has to support,
        // because it is the one a headless check uses.
        Plan plan = new Plan().target(key, perSecond)
                .byproductFeeds(byproductFeeds)
                .autoResolve(autoResolve());
        return choose(source, index, plan);
    }

    /**
     * Expand, solve, report and remember — the whole pipeline for one plan.
     *
     * <p>Shared by {@code /mfp plan} and {@code /mfp resolve} so that answering an import by hand
     * runs exactly what building the plan ran. Anything less and the two paths could drift, which
     * would make "a hand-built plan gives the same numbers" a statement about this method rather
     * than about the planner.
     */
    private static int choose(CommandSourceStack source, RecipeIndex index, Plan plan) {
        // Expansion appends, so a re-solved plan has to give up its lines first. A new plan has none
        // and this costs nothing; a plan being answered one import at a time has all of them.
        plan.clearLines();
        // Built before choosing, not after, because the chooser costs its own candidate plans when
        // it is deciding whether feeding a byproduct back was worth the energy (M11.1) and should
        // not be doing that on base rates when the behaviour-aware ones are right here.
        BehaviourThroughputResolver resolver = PlanSession.resolverFor(index);
        long startedChoosing = System.nanoTime();
        ChooserResult chooserResult = new RecipeChooser(index, PreferenceStore.reload())
                .withResolver(resolver)
                .expandInto(plan);
        long chosenAt = System.nanoTime();

        // The chooser has already moved the plan to MATRIX if it observed a loop while expanding,
        // so this dispatches on that decision rather than second-guessing it (plan §9.3) - unless
        // /mfp solver has pinned one, which is a diagnostic override and says so when it is on.
        if (engineOverride != null) {
            plan.solverMode(engineOverride);
        }
        SolveResult solved = Solvers.solve(plan, resolver);
        long solvedAt = System.nanoTime();
        PlanSession.store(source.getTextName(), plan, chooserResult, solved, resolver);

        report(source, plan, chooserResult, solved);
        // Split, because the two halves fail differently: a slow *solve* is a big matrix, and a slow
        // *choose* is the expansion running several times over — which is what a loop costs, since
        // every retry is another full walk of the graph (STATUS 9.10).
        send(source, "  chose in " + dev.mfp.client.ClientPlan.millis((chosenAt - startedChoosing) / 1_000)
                + ", solved in " + dev.mfp.client.ClientPlan.millis((solvedAt - chosenAt) / 1_000),
                ChatFormatting.DARK_GRAY);
        return solved.lines().size();
    }

    /** An engine pinned for this session's commands, or null to let the plan decide. */
    private static SolverMode engineOverride;

    /** Whether new plans feed their own byproducts back in (M11.1). On, like the plan default. */
    private static boolean byproductFeeds = true;

    private static int byproducts(CommandSourceStack source, String state) {
        byproductFeeds = !state.equalsIgnoreCase("off") && !state.equalsIgnoreCase("false");
        send(source, "MFP: byproduct feeding is " + (byproductFeeds ? "on" : "off")
                + " for every plan from here", ChatFormatting.GRAY);
        return 1;
    }

    /**
     * Whether new plans expand below the target on their own (M11.3), or null to follow the
     * standing preference — which is what it does until someone says otherwise in this session.
     */
    private static Boolean autoResolveOverride;

    private static boolean autoResolve() {
        return autoResolveOverride != null ? autoResolveOverride : PreferenceStore.get().autoResolve();
    }

    private static int autoresolve(CommandSourceStack source, String state) {
        autoResolveOverride = !state.equalsIgnoreCase("off") && !state.equalsIgnoreCase("false");
        send(source, "MFP: expansion below the target is " + (autoResolve() ? "automatic" : "by hand")
                + " for every plan from here"
                + (autoResolve() ? "" : " - answer imports with /mfp resolve <item>"),
                ChatFormatting.GRAY);
        return 1;
    }

    /**
     * Answer an import by choosing a recipe for it, the command-line half of M11.2.
     *
     * <p>What clicking an import on the planner's Imports tab does: pin the recipe and re-run the
     * whole pipeline. With no item named, every import the pack can make is answered at once — one
     * round, not to a fixed point, because watching the plan grow a layer at a time is the thing
     * being checked, and a command that silently ran to completion would be the automatic chooser
     * with extra steps.
     *
     * <p>The recipe pinned is the scorer's own first choice, which is deliberate: it makes
     * hand-building and automatic expansion comparable. If the two paths ever disagree while
     * choosing the same recipes, that is a bug in one of them, and this is how it shows up.
     *
     * <p>Without an item it answers what the plan could not make, never what it was told it need
     * not — a declared raw material is an answer already given. Naming one explicitly still works,
     * because a pin is the more specific statement and the walk now says so.
     */
    private static int resolve(CommandSourceStack source, String itemSpec) {
        PlanSession session = PlanSession.of(source.getTextName());
        if (session == null) {
            send(source, "MFP: no plan to add to yet - run /mfp plan first", ChatFormatting.RED);
            return 0;
        }
        RecipeIndex index = MfpIndexHolder.get(source.getServer());
        Plan plan = session.plan();
        RecipeChooser chooser = new RecipeChooser(index, PreferenceStore.get());

        List<MfpKey> wanted = itemSpec == null
                ? session.chooserResult().unresolved()
                : List.of(parseKey(itemSpec));

        List<String> answered = new java.util.ArrayList<>();
        for (MfpKey key : wanted) {
            // The player's standing way of making this outranks the scorer, exactly as it does
            // inside the walk. Without this the hand-built plan and the automatic one diverge on
            // the first item the user has an opinion about — which is the opposite of the point:
            // the pack's lubricant plan sieves its redstone dust by standing default, and answering
            // that import by hand quietly macerated refined ore instead.
            String standing = PreferenceStore.get().defaultRecipe(key);
            MfpRecipe byDefault = standing == null ? null : index.recipe(standing);
            if (byDefault != null && byDefault.produces(key) && !plan.blacklist().contains(standing)) {
                plan.chooseRecipe(key, standing);
                answered.add(KeySpec.of(key) + " <- " + standing + " (your standing default)");
                continue;
            }
            List<RecipeScorer.Scored> ranked = chooser.alternatives(key, plan);
            if (ranked.isEmpty()) {
                if (itemSpec != null) {
                    send(source, "MFP: no usable recipe makes " + key
                            + " - /mfp alternatives " + KeySpec.of(key) + " says why",
                            ChatFormatting.RED);
                }
                continue;
            }
            plan.chooseRecipe(key, ranked.get(0).recipe().id());
            answered.add(KeySpec.of(key) + " <- " + ranked.get(0).recipe().id());
        }

        if (answered.isEmpty()) {
            send(source, "MFP: nothing left to answer", ChatFormatting.GRAY);
            return 0;
        }
        send(source, "MFP: answered " + answered.size() + " import(s)", ChatFormatting.GREEN);
        answered.forEach(line -> send(source, "  " + line, ChatFormatting.GRAY));
        return choose(source, index, plan);
    }

    /** {@code /mfp pin} — what this plan has been told to use. */
    private static int pins(CommandSourceStack source) {
        PlanSession session = PlanSession.of(source.getTextName());
        if (session == null) {
            send(source, "MFP: no plan yet - run /mfp plan first", ChatFormatting.RED);
            return 0;
        }
        Map<MfpKey, String> choices = session.plan().recipeChoices();
        if (choices.isEmpty()) {
            send(source, "MFP: no pinned recipes in this plan", ChatFormatting.GRAY);
            send(source, "  /mfp pin <item> <recipe id>   pin one, and re-solve", ChatFormatting.GRAY);
            send(source, "  /mfp pin clear [item]         drop one, or all of them", ChatFormatting.GRAY);
            return 1;
        }
        send(source, choices.size() + " pinned recipe(s)", ChatFormatting.GOLD);
        choices.forEach((key, recipeId) ->
                send(source, "  " + KeySpec.of(key) + " <- " + recipeId, ChatFormatting.WHITE));
        return 1;
    }

    /**
     * {@code /mfp pin <item> <recipe id>}, and {@code /mfp pin clear [item]}.
     *
     * <p>One greedy argument split on whitespace rather than two arguments, because brigadier's
     * word reader stops at a colon and every id here has one.
     *
     * <p>The recipe is checked against the item before it is stored. A pin that names a recipe not
     * producing what it is pinned to does nothing at all when the walk reaches it — the chooser
     * falls through to the scorer, exactly as it does for a pin left stale by a pack update — and a
     * silent no-op is the worst possible answer to a diagnostic command.
     */
    private static int pin(CommandSourceStack source, String spec) {
        PlanSession session = PlanSession.of(source.getTextName());
        if (session == null) {
            send(source, "MFP: no plan to pin into - run /mfp plan first", ChatFormatting.RED);
            return 0;
        }
        RecipeIndex index = MfpIndexHolder.get(source.getServer());
        Plan plan = session.plan();
        String[] parts = spec.trim().split("\\s+");

        if (parts[0].equalsIgnoreCase("clear")) {
            if (parts.length == 1) {
                int had = plan.recipeChoices().size();
                plan.recipeChoices().keySet().forEach(plan::clearRecipeChoice);
                send(source, "MFP: cleared " + had + " pin(s)", ChatFormatting.GREEN);
            } else {
                MfpKey key = parseKey(parts[1]);
                plan.clearRecipeChoice(key);
                send(source, "MFP: cleared the pin on " + KeySpec.of(key), ChatFormatting.GREEN);
            }
            return choose(source, index, plan);
        }

        if (parts.length < 2) {
            send(source, "MFP: /mfp pin <item> <recipe id>, or /mfp pin clear [item]",
                    ChatFormatting.RED);
            return 0;
        }

        MfpKey key = parseKey(parts[0]);
        String recipeId = parts[1];
        MfpRecipe recipe = index.recipe(recipeId);
        if (recipe == null) {
            send(source, "MFP: no indexed recipe with id '" + recipeId + "'", ChatFormatting.RED);
            return 0;
        }
        if (!recipe.produces(key)) {
            send(source, "MFP: " + recipeId + " does not produce " + KeySpec.of(key)
                    + " - it makes " + recipe.outputs().stream().map(o -> KeySpec.of(o.key())).toList(),
                    ChatFormatting.RED);
            return 0;
        }

        plan.chooseRecipe(key, recipeId);
        send(source, "MFP: pinned " + KeySpec.of(key) + " <- " + recipeId, ChatFormatting.GREEN);
        return choose(source, index, plan);
    }

    /**
     * {@code /mfp option <line> [key] [value]} — the build choices inside a multiblock.
     *
     * <p>With a line alone it lists what that machine actually reads, which is not a fixed table:
     * the keys come from the behaviour chain, because a pack multiblock brings its own and a
     * hard-coded list would be wrong the moment {@code start_core} adds a machine (see
     * {@link OptionSpec}). {@code clear} as the key drops one back to unset.
     *
     * <p>The value is checked against the spec before it is stored, for the same reason
     * {@link #pin} checks its recipe: an unrecognised coil name reads back as -1 and the behaviour
     * quietly falls through to its "not configured" branch, so a typo would look exactly like a
     * working command that changed nothing.
     */
    private static int option(CommandSourceStack source, String spec) {
        PlanSession session = PlanSession.of(source.getTextName());
        if (session == null) {
            send(source, "MFP: no plan yet - run /mfp plan first", ChatFormatting.RED);
            return 0;
        }
        String[] parts = spec.trim().split("\\s+");
        List<LineResult> lines = session.solveResult().lines();

        int lineNumber;
        try {
            lineNumber = Integer.parseInt(parts[0]);
        } catch (NumberFormatException notANumber) {
            send(source, "MFP: /mfp option <line> [key] [value] - the line number from /mfp plan",
                    ChatFormatting.RED);
            return 0;
        }
        if (lineNumber < 1 || lineNumber > lines.size()) {
            send(source, "MFP: the plan has only " + lines.size() + " line(s)", ChatFormatting.RED);
            return 0;
        }

        Line line = lines.get(lineNumber - 1).line();
        MfpRecipe recipe = line.recipe();
        MachineConfig config = line.machine();
        List<OptionSpec> specs = session.resolver().chainFor(recipe, config).stream()
                .flatMap(behaviour -> behaviour.options().stream())
                .toList();

        if (parts.length == 1) {
            send(source, lineNumber + ". " + recipe.id(), ChatFormatting.AQUA);
            if (specs.isEmpty()) {
                send(source, "  this machine has no build choices that change throughput",
                        ChatFormatting.GRAY);
                return 1;
            }
            for (OptionSpec option : specs) {
                Object current = config.structureOptions().get(option.key());
                send(source, "  " + option.key() + " = " + (current == null ? "unset" : current)
                        + "   " + (option.kind() == OptionSpec.Kind.CHOICE
                                ? String.join(", ", option.choices())
                                : option.minimum() + ".." + option.maximum()),
                        ChatFormatting.WHITE);
            }
            return specs.size();
        }

        String key = parts[1];
        if (key.equalsIgnoreCase("clear")) {
            if (parts.length < 3) {
                send(source, "MFP: /mfp option " + lineNumber + " clear <key>", ChatFormatting.RED);
                return 0;
            }
            session.plan().configureMachine(recipe.id(), config.withoutOption(parts[2]));
            send(source, "MFP: cleared " + parts[2] + " on " + recipe.id(), ChatFormatting.GREEN);
            return choose(source, MfpIndexHolder.get(source.getServer()), session.plan());
        }

        OptionSpec target = specs.stream().filter(o -> o.key().equals(key)).findFirst().orElse(null);
        if (target == null) {
            send(source, "MFP: " + recipe.id() + "'s machine does not read '" + key + "' - it reads "
                    + specs.stream().map(OptionSpec::key).toList(), ChatFormatting.RED);
            return 0;
        }
        if (parts.length < 3) {
            send(source, "MFP: /mfp option " + lineNumber + " " + key + " <value>", ChatFormatting.RED);
            return 0;
        }

        String raw = parts[2];
        Object value;
        if (target.kind() == OptionSpec.Kind.INTEGER) {
            try {
                int number = Integer.parseInt(raw);
                if (number < target.minimum() || number > target.maximum()) {
                    send(source, "MFP: " + key + " must be between " + target.minimum() + " and "
                            + target.maximum(), ChatFormatting.RED);
                    return 0;
                }
                value = number;
            } catch (NumberFormatException notANumber) {
                send(source, "MFP: " + key + " takes a whole number, not '" + raw + "'",
                        ChatFormatting.RED);
                return 0;
            }
        } else {
            if (!target.choices().contains(raw)) {
                send(source, "MFP: no such " + key + ": '" + raw + "' - try "
                        + String.join(", ", target.choices()), ChatFormatting.RED);
                return 0;
            }
            value = raw;
        }

        session.plan().configureMachine(recipe.id(), config.withOption(key, value));
        send(source, "MFP: " + recipe.id() + " " + key + " = " + value, ChatFormatting.GREEN);
        return choose(source, MfpIndexHolder.get(source.getServer()), session.plan());
    }

    private static int solver(CommandSourceStack source, String mode) {
        if (mode.equalsIgnoreCase("auto") || mode.equalsIgnoreCase("off")) {
            engineOverride = null;
            send(source, "MFP: solver override cleared; plans pick their own engine again",
                    ChatFormatting.GRAY);
            return 1;
        }
        try {
            engineOverride = SolverMode.valueOf(mode.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            send(source, "MFP: no such engine: " + mode + " (sequential, matrix, simplex, auto)",
                    ChatFormatting.RED);
            return 0;
        }
        send(source, "MFP: every plan from here runs on the " + mode.toLowerCase(java.util.Locale.ROOT)
                + " engine", ChatFormatting.GRAY);
        return 1;
    }

    private static void report(CommandSourceStack source, Plan plan,
                               ChooserResult chooserResult, SolveResult solved) {
        send(source, plan.name(), ChatFormatting.GOLD);
        send(source, "  " + solved.lines().size() + " line(s), solved by the "
                + solved.engine().name().toLowerCase(java.util.Locale.ROOT) + " engine"
                + ", confidence " + solved.confidence(), ChatFormatting.GRAY);

        send(source, "  production:", ChatFormatting.AQUA);
        solved.lines().forEach(line -> send(source, "    " + describe(line), lineColour(line)));

        if (!solved.rawInputs().isEmpty()) {
            send(source, "  imports per second:", ChatFormatting.AQUA);
            print(source, solved.rawInputs());
        }
        if (!solved.byproducts().isEmpty()) {
            send(source, "  byproducts per second:", ChatFormatting.AQUA);
            print(source, solved.byproducts());
        }
        if (!chooserResult.byproductFeeds().isEmpty()) {
            // Not a warning: it is the chooser saying it found a use for something the plan was
            // throwing away, which is the whole point of M11.1 and is worth seeing.
            send(source, "  fed back into the plan: " + chooserResult.byproductFeeds(),
                    ChatFormatting.GREEN);
        }

        send(source, "  energy: draws " + trim(solved.euDrawPerSecond()) + " EU/s",
                ChatFormatting.YELLOW);
        if (solved.drawsSteam()) {
            // Beside the draw, not folded into it: steam machines take a boiler and a pipe, and a
            // player who reads only the EU figure would size a power line they do not need.
            send(source, "  steam: burns " + trim(solved.steamDrawPerSecond()) + " mB/s",
                    ChatFormatting.YELLOW);
        }
        if (solved.generatesPower()) {
            // Named as excluded rather than dropped: a line that generates is on screen with its own
            // figure, and a reader who saw the two numbers without this would assume they net.
            send(source, "    (" + trim(solved.euGeneratedPerSecond()) + " EU/s is generated by lines "
                    + "in this plan and is NOT subtracted - power generation is out of scope)",
                    ChatFormatting.GRAY);
        }

        // Chooser warnings first: a loop or a missing recipe explains solver warnings that follow.
        chooserResult.warnings(solved.engine().closesLoops()).forEach(warning -> send(source, "  ! " + warning, ChatFormatting.RED));
        solved.warnings().forEach(warning -> send(source, "  ! " + warning, ChatFormatting.YELLOW));

        if (solved.confidence() != Confidence.EXACT) {
            send(source, "  run /mfp explain to see which assumptions were made", ChatFormatting.GRAY);
        }
    }

    private static String describe(LineResult line) {
        MfpRecipe recipe = line.line().recipe();
        MachineConfig machine = line.line().machine();

        StringBuilder sb = new StringBuilder();
        sb.append(line.confidence() == Confidence.EXACT ? "" : "~");
        sb.append(trim(line.machineCount())).append(" x ");
        sb.append(machine.machineId() == null ? "(no machine)" : machine.machineId());
        if (machine.tier() >= 0) {
            sb.append(" [T").append(machine.tier()).append(']');
        }
        sb.append("  ").append(recipe.id());
        if (line.euInPerSecond() > 0) {
            sb.append("  ").append(trim(line.euInPerSecond() / 20)).append(" EU/t");
        }
        if (line.euOutPerSecond() > 0) {
            sb.append("  +").append(trim(line.euOutPerSecond() / 20)).append(" EU/t");
        }
        if (line.isSteamPowered()) {
            sb.append("  ").append(trim(line.steamPerSecond() / 20)).append(" mB/t steam");
        }
        return sb.toString();
    }

    private static ChatFormatting lineColour(LineResult line) {
        if (line.isIdle()) {
            return ChatFormatting.DARK_GRAY;
        }
        return switch (line.confidence()) {
            case EXACT -> ChatFormatting.WHITE;
            case APPROXIMATE -> ChatFormatting.YELLOW;
            case UNKNOWN -> ChatFormatting.RED;
        };
    }

    // --------------------------------------------------------------- explain

    private static int explain(CommandSourceStack source, int lineNumber) {
        PlanSession session = PlanSession.of(source.getTextName());
        if (session == null) {
            send(source, "MFP: no plan to explain yet - run /mfp plan first", ChatFormatting.RED);
            return 0;
        }

        List<LineResult> lines = session.solveResult().lines();
        if (lineNumber < 0) {
            send(source, "Explaining " + session.plan().name(), ChatFormatting.GOLD);
            for (int i = 0; i < lines.size(); i++) {
                explainLine(source, session, lines.get(i), i + 1, false);
            }
            send(source, "  /mfp explain <n> for one line in full", ChatFormatting.GRAY);
            return lines.size();
        }

        if (lineNumber > lines.size()) {
            send(source, "MFP: the plan has only " + lines.size() + " line(s)", ChatFormatting.RED);
            return 0;
        }
        explainLine(source, session, lines.get(lineNumber - 1), lineNumber, true);
        return 1;
    }

    private static void explainLine(CommandSourceStack source, PlanSession session,
                                    LineResult result, int number, boolean full) {
        Line line = result.line();
        MfpRecipe recipe = line.recipe();
        send(source, "  " + number + ". " + recipe.id(), ChatFormatting.AQUA);

        BehaviourThroughputResolver resolver = session.resolver();
        List<MachineBehaviour> chain = resolver.chainFor(recipe, line.machine());
        ThroughputResult throughput = resolver.resolveResult(recipe, line.machine());

        send(source, "     machine: " + (line.machine().machineId() == null
                ? "none chosen" : line.machine().machineId())
                + (line.machine().tier() >= 0 ? " tier " + line.machine().tier() : ""),
                ChatFormatting.GRAY);

        send(source, "     behaviours: " + (chain.isEmpty()
                        ? "none recognised"
                        : chain.stream().map(MachineBehaviour::id).toList()),
                chain.isEmpty() ? ChatFormatting.RED : ChatFormatting.GRAY);

        if (throughput.cancelled()) {
            send(source, "     cannot run: " + throughput.cancelReason(), ChatFormatting.RED);
            return;
        }

        double effectiveDuration = throughput.durationTicks(recipe.durationTicks());
        send(source, "     duration: " + trim(recipe.durationTicks()) + " -> "
                + trim(effectiveDuration) + " ticks  (x" + trim(throughput.durationMultiplier()) + ")",
                ChatFormatting.WHITE);
        if (recipe.euIn() > 0) {
            send(source, "     EU/t: " + recipe.euIn() + " -> "
                    + trim(throughput.eut(recipe.euIn() * Math.max(1, recipe.amperage())))
                    + "  (x" + trim(throughput.eutMultiplier()) + ")", ChatFormatting.WHITE);
        }
        if (throughput.contentMultiplier() != 1.0) {
            send(source, "     parallel crafts per cycle: x" + trim(throughput.contentMultiplier()),
                    ChatFormatting.WHITE);
        }
        if (throughput.overclocks() > 0) {
            send(source, "     overclocks: " + throughput.overclocks()
                    + " (also boosts chanced outputs)", ChatFormatting.WHITE);
        }
        send(source, "     machines: " + trim(result.machineCount())
                + " (build " + result.machinesToBuild() + ")", ChatFormatting.WHITE);

        if (throughput.confidence() != Confidence.EXACT) {
            send(source, "     confidence: " + throughput.confidence(), ChatFormatting.YELLOW);
        }
        throughput.notes().forEach(note -> send(source, "     - " + note, ChatFormatting.YELLOW));

        if (full) {
            send(source, "     consumes/s:", ChatFormatting.GRAY);
            print(source, result.inputs());
            send(source, "     produces/s:", ChatFormatting.GRAY);
            print(source, result.outputs());
            if (!result.byproducts().isEmpty()) {
                send(source, "     byproducts/s:", ChatFormatting.GRAY);
                print(source, result.byproducts());
            }
        }
    }

    // ---------------------------------------------------------- alternatives

    /**
     * Every way to make an item, ranked, with the scores and with what was left out.
     *
     * <p>This is the command that answers "why did it choose <em>that</em>?", and until M9.13 it
     * could not: it printed the ranking without the numbers, so two candidates that were a hair
     * apart looked the same as two that were fifty points apart, and it said nothing at all about
     * the recipes that never reached the ranking. Both halves matter, because a recipe can be absent
     * for four quite different reasons and only one of them is MFP's own doing.
     */
    private static int alternatives(CommandSourceStack source, String itemSpec) {
        RecipeIndex index = MfpIndexHolder.get(source.getServer());
        MfpKey key = parseKey(itemSpec);

        PlanSession session = PlanSession.of(source.getTextName());
        Plan plan = session == null ? new Plan("scratch") : session.plan();
        RecipeChooser chooser = new RecipeChooser(index, PreferenceStore.get());
        List<RecipeScorer.Scored> ranked = chooser.alternatives(key, plan);
        java.util.Map<dev.mfp.core.model.MfpRecipe, String> excluded =
                chooser.excludedAlternatives(key, plan);
        // Recipes the last plan's loop-avoidance pass banned. Not a property of the recipe, so it
        // comes from that expansion rather than from the chooser (ChooserResult.avoidedForCycles).
        java.util.Set<String> avoided = session == null
                ? java.util.Set.of()
                : java.util.Set.copyOf(session.chooserResult().avoidedForCycles());

        if (ranked.isEmpty() && excluded.isEmpty()) {
            send(source, "MFP: nothing in the index produces " + key, ChatFormatting.RED);
            return 0;
        }

        send(source, ranked.size() + " way(s) to make " + key + ", best first"
                + (plan.rawMaterials().contains(key) ? "  (raw in this plan, so nothing expands it)" : ""),
                ChatFormatting.GOLD);
        int shown = 0;
        for (RecipeScorer.Scored scored : ranked) {
            if (shown++ >= 15) {
                send(source, "  ... and " + (ranked.size() - 15) + " more", ChatFormatting.GRAY);
                break;
            }
            // The score, because the gap is the whole question: two recipes a point apart are a
            // tie the user should break, and fifty points apart is the scorer having an opinion.
            send(source, "  " + String.format(java.util.Locale.ROOT, "%7.1f", scored.score())
                            + "  " + scored.recipe().id()
                            + (avoided.contains(scored.recipe().id()) ? "  [avoided for a loop]" : "")
                            + "  " + scored.reasons(),
                    shown == 1 ? ChatFormatting.GREEN
                            : avoided.contains(scored.recipe().id()) ? ChatFormatting.YELLOW
                            : ChatFormatting.WHITE);
        }

        if (!excluded.isEmpty()) {
            send(source, "  excluded, and by whom:", ChatFormatting.AQUA);
            excluded.forEach((recipe, reason) ->
                    send(source, "    " + recipe.id() + " - " + reason, ChatFormatting.GRAY));
        }
        return ranked.size();
    }

    // ----------------------------------------------------------------- utils

    private static MfpKey parseKey(String spec) {
        return KeySpec.parse(spec);
    }

    private static void print(CommandSourceStack source, Map<MfpKey, Double> flows) {
        flows.forEach((key, amount) ->
                send(source, "      " + trim(amount) + " x " + key, ChatFormatting.WHITE));
    }

    private static String trim(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e12) {
            return String.valueOf((long) value);
        }
        return String.format(java.util.Locale.ROOT, "%.4g", value);
    }

    private static void send(CommandSourceStack source, String message, ChatFormatting colour) {
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(message).withStyle(colour), false);
    }
}
