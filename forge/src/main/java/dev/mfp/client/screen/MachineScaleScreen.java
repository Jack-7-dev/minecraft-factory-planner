package dev.mfp.client.screen;

import dev.mfp.client.ClientIndex;
import dev.mfp.client.ClientPlan;
import dev.mfp.client.ClientPlanner;
import dev.mfp.client.KeyStacks;
import dev.mfp.client.MachineStacks;
import dev.mfp.client.widget.Fmt;
import dev.mfp.client.widget.MfpWidget;
import dev.mfp.client.widget.SlotWidget;
import dev.mfp.client.widget.Table;
import dev.mfp.client.widget.TextButton;
import dev.mfp.client.widget.TextField;
import dev.mfp.client.widget.Theme;
import dev.mfp.core.behaviour.GtTiers;
import dev.mfp.core.behaviour.MachineBehaviour;
import dev.mfp.core.behaviour.OptionSpec;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.Line;
import dev.mfp.core.plan.MachineConfig;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.plan.PlanScaling;
import dev.mfp.core.plan.TargetOutput;
import dev.mfp.core.solver.LineResult;
import dev.mfp.core.solver.SolveResult;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * "I am going to build three of these — what does that actually make?"
 *
 * <p>The planner answers the question the user asked: 1000 mB/s of ethanol needs 2.60 distillation
 * towers. Nobody builds 2.60 towers. They build three, and the third one either idles or the whole
 * chain gets bigger — and working out <em>how much</em> bigger, by hand, across a greenhouse, a
 * fermenter and a tower, is exactly the arithmetic this mod exists to remove. So this dialog takes
 * the count on a line's Machines column and turns it into the question the player is really asking:
 * pick the number of machines you are willing to place, describe the build you are placing (the
 * energy hatch tier, the coils), and the plan re-solves around that.
 *
 * <p>Nothing here is a per-line edit. A solved plan is linear in its targets, so making one line
 * three machines is the plan's targets multiplied by {@code 3 / 2.60} — and every other line follows
 * for free. Maxing out the greenhouse moves the tower and the ethanol with it, which is the whole
 * point; a feature that grew one line and left its suppliers behind would produce a plan that does
 * not balance.
 *
 * <p><b>The build is applied before the factor is computed, and this ordering is not incidental.</b>
 * Raising the tier changes what one machine produces, so a factor worked out against the old
 * throughput would be wrong the moment the user touches the thing this dialog exists to let them
 * touch. Apply therefore re-solves twice: once to find out what the new build does, once to scale.
 *
 * <p>Everything before Apply is a preview, and it is labelled as one. It predicts the new machine
 * count from the ratio of crafts per second between the old build and the new, which is right while
 * the plan is linear and is not the solver's own answer. {@link PlanScaling#hasMachineLimit} finds
 * the case where linearity fails outright — a capped line is an inequality, not a ray through the
 * origin — and the dialog says so rather than showing a confident wrong number.
 */
public final class MachineScaleScreen extends ModalScreen {

    private final Plan plan;
    private final MfpRecipe recipe;

    /**
     * The configuration the line had on the way in, kept for two jobs: predicting how much faster
     * the edited build runs, and putting the plan back exactly as it was if Apply cannot finish.
     */
    private final MachineConfig original;
    private final MachineConfig originalPlanConfig;
    private final double machinesNow;

    private MachineConfig config;
    private int wanted;
    private String failure;

    private final Map<OptionSpec, Integer> optionLabels = new LinkedHashMap<>();

    public MachineScaleScreen(Screen parent, Line line) {
        super(parent, "Max out", machineName(line.machine()));
        this.recipe = line.recipe();
        ClientPlan solved = ClientPlanner.current();
        this.plan = solved == null ? new Plan("scratch") : solved.plan();
        this.original = line.machine();
        this.config = line.machine();
        this.originalPlanConfig = plan.machineConfig(recipe.id());
        this.machinesNow = machineCountOf(solved, line);
        // The count the player would place anyway, so opening the dialog and pressing Apply is the
        // "round this line up and grow the plan to suit" that most visits are for.
        this.wanted = Math.max(1, (int) Math.ceil(machinesNow - 1e-9));
    }

    /**
     * A name for the machine, for a line that has not been given one.
     *
     * <p>A recipe can sit on a plan with {@link MachineConfig#UNSET} — nothing in the index runs it,
     * or expansion has not chosen yet — and the dialog still has to say what it is about.
     */
    private static String machineName(MachineConfig config) {
        return config.machineId() == null ? "no machine chosen" : MachineStacks.name(config.machineId());
    }

    /**
     * The solved count for the line that was clicked.
     *
     * <p>By object first, because that is the line the planner handed us and the plan may run the
     * same recipe twice; by recipe id only as a fallback, for a line that has already been through a
     * re-solve and so is no longer the object the current result knows about.
     */
    private static double machineCountOf(ClientPlan solved, Line line) {
        if (solved == null) {
            return 0;
        }
        LineResult direct = solved.solveResult().resultFor(line);
        if (direct != null) {
            return direct.machineCount();
        }
        LineResult matched = findLine(solved.solveResult(), line.recipe().id());
        return matched == null ? 0 : matched.machineCount();
    }

    private static LineResult findLine(SolveResult result, String recipeId) {
        for (LineResult candidate : result.lines()) {
            if (candidate.line().recipe().id().equals(recipeId)) {
                return candidate;
            }
        }
        return null;
    }

    @Override
    protected int preferredWidth() {
        return 480;
    }

    @Override
    protected int preferredHeight() {
        return 290;
    }

    // ------------------------------------------------------------------ layout

    private int leftWidth() {
        return Math.min(230, contentWidth() / 2);
    }

    private int labelWidth() {
        return 96;
    }

    private int controlsTop() {
        return contentY() + SlotWidget.ICON + GAP;
    }

    @Override
    protected void build() {
        optionLabels.clear();
        int x = contentX();
        int fieldX = x + labelWidth();
        int y = controlsTop();

        // How many to build ------------------------------------------------
        TextButton fewer = new TextButton("-", () -> setWanted(wanted - 1));
        fewer.tooltip("One machine fewer, and the whole plan shrinks with it.");
        fewer.bounds(fieldX, y, 14, 14);
        fewer.enabled(wanted > 1);
        widgets.add(fewer);

        // Whole machines only. A fractional count is what the planner already told them and what
        // they cannot build; the entire purpose of this box is to be a number of blocks placed.
        TextField count = new TextField()
                .text(String.valueOf(wanted))
                .filter(TextField.INTEGER)
                .maxLength(5)
                .tooltip("How many of this machine you will actually place. The plan's targets move "
                        + "to match, so every other line moves too.")
                .onCommit(value -> setWanted(parseInt(value, wanted)));
        count.bounds(fieldX + 16, y, 44, 14);
        widgets.add(count);

        TextButton more = new TextButton("+", () -> setWanted(wanted + 1));
        more.tooltip("One machine more: the third tower stops idling and the chain grows to feed it.");
        more.bounds(fieldX + 64, y, 14, 14);
        widgets.add(more);
        y += 18;

        // The build --------------------------------------------------------
        TextButton down = new TextButton("-", () -> stepTier(-1));
        down.tooltip("A lower tier: slower, cheaper, and it may not be able to run the recipe.");
        down.bounds(fieldX, y, 14, 14);
        down.enabled(tierIsEditable() && config.tier() > Math.max(0, recipe.minTier()));
        widgets.add(down);

        TextButton up = new TextButton("+", () -> stepTier(1));
        up.tooltip("A higher tier: more overclocks, so each machine does more and you need fewer.");
        up.bounds(fieldX + 50, y, 14, 14);
        up.enabled(tierIsEditable() && config.tier() < GtTiers.MAX);
        widgets.add(up);
        y += 18;

        TextField parallels = new TextField()
                .text(String.valueOf(config.parallels()))
                .filter(TextField.INTEGER)
                .maxLength(4)
                .tooltip("Copies of the recipe one machine runs at once. Distinct from a parallel "
                        + "hatch, which is a structure option below — counting both doubles the line.")
                .onCommit(value -> edit(config.withParallels(Math.max(1, parseInt(value, 1)))));
        parallels.bounds(fieldX, y, 44, 14);
        widgets.add(parallels);
        y += 18 + 2;

        // Structure options, asked of the behaviours rather than listed here (see
        // optionsForThisMachine): a hard-coded coil table would be wrong the day the pack adds a
        // multiblock, and wrong invisibly.
        for (OptionSpec spec : optionsForThisMachine()) {
            if (y > contentBottom() - 18) {
                break;
            }
            y = buildOption(spec, x, y, labelWidth(), leftWidth() - labelWidth());
        }

        TextButton apply = new TextButton("Build " + wanted, this::applyAndScale);
        apply.tooltip("Set this build on the line, then scale every target so the line lands on "
                + wanted + " machine" + (wanted == 1 ? "" : "s") + ".");
        apply.enabled(factor().isPresent());
        apply.bounds(contentX(), panelY + panelHeight - FOOTER_HEIGHT + 3,
                apply.preferredWidth(), 14);
        widgets.add(apply);
    }

    private int buildOption(OptionSpec spec, int x, int y, int labelWidth, int boxWidth) {
        Object current = config.structureOptions().get(spec.key());

        if (spec.kind() == OptionSpec.Kind.CHOICE) {
            List<String> choices = spec.choices();
            TextButton cycle = new TextButton(current == null ? "not set" : String.valueOf(current), () -> {
                int at = choices.indexOf(String.valueOf(current));
                int next = at + 1;
                edit(next >= choices.size()
                        ? config.withoutOption(spec.key())
                        : config.withOption(spec.key(), choices.get(next)));
            });
            cycle.secondary(() -> {
                // The same ring walked backwards, with "not set" sitting between the last choice and
                // the first, so a value the pack has since renamed lands somewhere either way.
                int at = choices.indexOf(String.valueOf(current));
                int previous = at < 0 ? choices.size() - 1 : at - 1;
                edit(previous < 0
                        ? config.withoutOption(spec.key())
                        : config.withOption(spec.key(), choices.get(previous)));
            });
            cycle.tooltip(spec.description() + " Right-click to go back.");
            cycle.bounds(x + labelWidth, y, Math.max(70, Math.min(boxWidth, cycle.preferredWidth())), 14);
            widgets.add(cycle);
        } else {
            TextField field = new TextField()
                    .text(current == null ? "" : String.valueOf(current))
                    .filter(TextField.INTEGER)
                    .maxLength(8)
                    .placeholder("not set")
                    .tooltip(spec.description())
                    .onCommit(value -> {
                        String trimmed = value.trim();
                        // Blank clears rather than storing zero: "unset" is a state the behaviours
                        // act on, reporting an assumption instead of inventing a number.
                        edit(trimmed.isEmpty()
                                ? config.withoutOption(spec.key())
                                : config.withOption(spec.key(),
                                        TextField.clamp(parseInt(trimmed, spec.minimum()),
                                                spec.minimum(), spec.maximum())));
                    });
            field.bounds(x + labelWidth, y, Math.min(70, boxWidth), 14);
            widgets.add(field);
        }
        optionLabels.put(spec, y);
        return y + 18;
    }

    /**
     * The options declared by the behaviours that will actually compute this line's throughput.
     *
     * <p>Duplicated from {@code MachineConfigScreen} rather than shared, and deliberately: the two
     * dialogs ask the behaviour chain the same question about different working configurations, and
     * the alternative — a static helper reaching into whichever screen is open — would couple them
     * through their state rather than through the registry that owns the answer.
     */
    private List<OptionSpec> optionsForThisMachine() {
        ClientPlan solved = ClientPlanner.current();
        if (solved == null) {
            return List.of();
        }
        List<OptionSpec> specs = new ArrayList<>();
        for (MachineBehaviour behaviour : solved.resolver().chainFor(recipe, config)) {
            for (OptionSpec spec : behaviour.options()) {
                if (specs.stream().noneMatch(existing -> existing.key().equals(spec.key()))) {
                    specs.add(spec);
                }
            }
        }
        return specs;
    }

    private boolean tierIsEditable() {
        MfpMachine machine = config.machineId() == null ? null : ClientIndex.get().machine(config.machineId());
        // A single block's tier is the block you placed; only a multiblock's is a choice, because it
        // comes from the energy hatch (STATUS §4a.9).
        return machine != null && machine.multiblock();
    }

    private void stepTier(int direction) {
        edit(config.withTier(Math.max(0, Math.min(GtTiers.MAX, config.tier() + direction))));
    }

    /**
     * Hold an edit locally and redraw.
     *
     * <p>Unlike the machine dialog, nothing here touches the plan until Apply. This screen's whole
     * job is a destructive change — it rewrites every target the plan has — so Close has to be a way
     * out that leaves the plan exactly as it was found.
     */
    private void edit(MachineConfig updated) {
        this.config = updated;
        this.failure = null;
        rebuild();
    }

    private void setWanted(int value) {
        this.wanted = Math.max(1, value);
        this.failure = null;
        rebuild();
    }

    // ------------------------------------------------------------------ maths

    /**
     * What this line's machine count would become under the edited build, before any scaling.
     *
     * <p>The ratio of crafts per second between the two builds: a build twice as fast needs half the
     * machines for the same demand. It is a prediction rather than the solver's answer — a tier
     * change can alter a recipe's output multiplier and so the demand itself — which is why the
     * factor is recomputed from a real solve inside {@link #applyAndScale()} and this number never
     * reaches the plan.
     */
    private double predictedMachines() {
        ClientPlan solved = ClientPlanner.current();
        if (solved == null || machinesNow <= 0) {
            return machinesNow;
        }
        double before = solved.resolver().resolve(recipe, original).craftsPerSecond();
        double after = solved.resolver().resolve(recipe, config).craftsPerSecond();
        if (before <= 0 || after <= 0) {
            return 0;
        }
        return machinesNow * before / after;
    }

    private OptionalDouble factor() {
        return PlanScaling.factorFor(predictedMachines(), wanted);
    }

    /**
     * Set the build, find out what it does, then scale the plan onto the chosen machine count.
     *
     * <p>Two solves, in that order, because the first one is what makes the second one's factor mean
     * anything. Between them the {@link Line} the dialog was opened on is dead — expansion rebuilds
     * every line on each solve — so the line is found again by recipe id, and if the new build has
     * made the machine unable to run the recipe at all, there is no line to scale from and the plan
     * goes back the way it was rather than being multiplied by a number derived from nothing.
     */
    private void applyAndScale() {
        if (ClientPlanner.current() == null) {
            return;
        }
        plan.configureMachine(recipe.id(), config);
        ClientPlan afterBuild = ClientPlanner.refresh();

        LineResult fresh = afterBuild == null ? null : findLine(afterBuild.solveResult(), recipe.id());
        OptionalDouble factor = fresh == null
                ? OptionalDouble.empty()
                : PlanScaling.factorFor(fresh.machineCount(), wanted);

        if (factor.isEmpty()) {
            restoreBuild();
            this.failure = fresh == null
                    ? "That build cannot run this recipe, so the line left the plan. Nothing changed."
                    : "That build leaves the line idle, so there is no rate to scale from. "
                            + "Nothing changed.";
            rebuild();
            return;
        }

        PlanScaling.scaleTargets(plan, factor.getAsDouble());
        ClientPlanner.refresh();
        back();
    }

    /** Put the plan's own configuration back, including the case where it never had one. */
    private void restoreBuild() {
        if (originalPlanConfig == null) {
            plan.clearMachineConfig(recipe.id());
        } else {
            plan.configureMachine(recipe.id(), originalPlanConfig);
        }
        ClientPlanner.refresh();
    }

    // ----------------------------------------------------------------- render

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = contentX();
        if (config.machineId() != null) {
            graphics.renderItem(MachineStacks.icon(config.machineId()), x, contentY());
        }
        graphics.drawString(font, MfpWidget.fit(machineName(config), leftWidth() - 20),
                x + SlotWidget.ICON + 2, contentY() + 2, Theme.TEXT, false);
        graphics.drawString(font, MfpWidget.fit(pathOf(recipe.id()), leftWidth() - 20),
                x + SlotWidget.ICON + 2, contentY() + 11, Theme.TEXT_DIM, false);

        int fieldX = x + labelWidth();
        int y = controlsTop();
        label(graphics, "Build", x, y);
        graphics.drawString(font, "of " + Fmt.machines(machinesNow, (long) Math.ceil(machinesNow - 1e-9)),
                fieldX + 82, y + 3, Theme.TEXT_DIM, false);
        y += 18;
        label(graphics, "Tier", x, y);
        graphics.drawString(font, config.tier() < 0 ? "-" : GtTiers.name(config.tier()),
                fieldX + 18, y + 3, tierIsEditable() ? Theme.TEXT : Theme.TEXT_IDLE, false);
        y += 18;
        label(graphics, "Parallels", x, y);
        y += 18 + 2;

        for (Map.Entry<OptionSpec, Integer> entry : optionLabels.entrySet()) {
            graphics.drawString(font, MfpWidget.fit(entry.getKey().label(), labelWidth() - 4),
                    x, entry.getValue() + 3, Theme.TEXT_DIM, false);
        }
        if (optionLabels.isEmpty()) {
            graphics.drawString(font, MfpWidget.fit("no structure options for this machine",
                    leftWidth() - 4), x, y + 3, Theme.TEXT_IDLE, false);
        }

        renderPreview(graphics, x + leftWidth() + GAP, contentY(),
                contentWidth() - leftWidth() - GAP);
    }

    /**
     * What the plan becomes: the number the player came here for.
     *
     * <p>Shown live and beside the count, because "three machines" is not an answer — "three
     * machines, 1153.8 mB/s of ethanol" is. Committing first and reading the table afterwards would
     * make every choice of machine count a guess followed by an undo.
     */
    private void renderPreview(GuiGraphics graphics, int x, int y, int columnWidth) {
        graphics.fill(x, y, x + columnWidth, y + Table.HEADER_HEIGHT, Theme.PANEL_INNER);
        graphics.drawString(font, "What that makes", x + 3, y + 2, Theme.TEXT_HEADER, false);
        int lineY = y + Table.HEADER_HEIGHT + 2;

        if (failure != null) {
            drawWrapped(graphics, failure, x, lineY, columnWidth, Theme.ERROR);
            return;
        }

        OptionalDouble factor = factor();
        if (factor.isEmpty()) {
            drawWrapped(graphics, "This line runs no machines in the current plan, so there is no "
                    + "rate per machine to scale from.", x, lineY, columnWidth, Theme.TEXT_IDLE);
            return;
        }
        double f = factor.getAsDouble();

        if (!config.equals(original)) {
            graphics.drawString(font, MfpWidget.fit("this build: "
                            + Fmt.number(predictedMachines()) + " machines for today's targets",
                    columnWidth - 4), x, lineY, Theme.TEXT_DIM, false);
            lineY += 10;
        }

        Map<MfpKey, Double> after = PlanScaling.previewTargets(plan, f);
        for (TargetOutput target : plan.targets()) {
            if (lineY > contentBottom() - 30) {
                break;
            }
            Double scaled = after.get(target.key());
            graphics.drawString(font, MfpWidget.fit(
                            MfpWidget.fit(KeyStacks.name(target.key()).getString(), columnWidth / 2)
                                    + " " + Fmt.number(target.perSecond()) + "/s -> "
                                    + Fmt.number(scaled == null ? target.perSecond() : scaled) + "/s",
                            columnWidth - 4),
                    x, lineY, Theme.TEXT, false);
            lineY += 10;
        }

        lineY += 4;
        graphics.drawString(font, MfpWidget.fit(
                        "whole plan: " + totalMachinesAt(f) + " machines", columnWidth - 4),
                x, lineY, Theme.TEXT_DIM, false);
        lineY += 12;

        if (PlanScaling.hasMachineLimit(plan)) {
            // The one case where the linear assumption is simply false. Said out loud rather than
            // silently applied, because the numbers above would look exactly as trustworthy as the
            // ones on a plan where they are right.
            drawWrapped(graphics, "A line in this plan has a machine limit. A limit is a cap, not a "
                            + "multiplier, so these figures are an estimate — the re-solve is the "
                            + "real answer.",
                    x, lineY, columnWidth, Theme.WARNING);
        }
    }

    /**
     * Machines across the plan at {@code factor}, each line rounded up separately.
     *
     * <p>Per line rather than by scaling the total, because the ceiling is applied per line in the
     * table and a total taken before rounding disagrees with the column it sits under.
     */
    private long totalMachinesAt(double factor) {
        ClientPlan solved = ClientPlanner.current();
        if (solved == null) {
            return 0;
        }
        long total = 0;
        for (LineResult result : solved.solveResult().lines()) {
            total += (long) Math.ceil(result.machineCount() * factor - 1e-9);
        }
        return total;
    }

    private void drawWrapped(GuiGraphics graphics, String text, int x, int y, int columnWidth, int colour) {
        int lineY = y;
        for (var wrapped : font.split(net.minecraft.network.chat.Component.literal(text), columnWidth - 4)) {
            if (lineY > contentBottom() - 10) {
                return;
            }
            graphics.drawString(font, wrapped, x, lineY, colour, false);
            lineY += 10;
        }
    }

    private void label(GuiGraphics graphics, String text, int x, int y) {
        graphics.drawString(font, text, x, y + 3, Theme.TEXT_DIM, false);
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
