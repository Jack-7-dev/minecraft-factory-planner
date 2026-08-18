package dev.mfp.client.screen;

import dev.mfp.client.ClientIndex;
import dev.mfp.client.ClientPlanner;
import dev.mfp.client.MachineStacks;
import dev.mfp.client.widget.Cells;
import dev.mfp.client.widget.Fmt;
import dev.mfp.client.widget.MfpWidget;
import dev.mfp.client.widget.ScrollPanel;
import dev.mfp.client.widget.Table;
import dev.mfp.client.widget.TextButton;
import dev.mfp.client.widget.TextField;
import dev.mfp.client.widget.Theme;
import dev.mfp.core.behaviour.GtTiers;
import dev.mfp.core.behaviour.MachineBehaviour;
import dev.mfp.core.behaviour.OptionSpec;
import dev.mfp.core.behaviour.ThroughputResult;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.MachineConfig;
import dev.mfp.core.plan.MachineDefaults;
import dev.mfp.core.plan.Plan;
import dev.mfp.core.select.MachinePicker;
import dev.mfp.plan.PreferenceStore;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How one line's recipe is actually run: the machine, its tier, and what was built into it.
 *
 * <p><b>The structure options are not a hard-coded list.</b> They are asked of the behaviour chain
 * that will compute this line's throughput ({@code MachineBehaviour.options()}), so a machine from
 * {@code start_core} — or from a mod that does not exist yet — offers exactly the fields its own
 * behaviour reads. A dialog with a fixed table of coils and hatches would be wrong the day the pack
 * added a multiblock, and wrong invisibly: the number would still change, with no way to control it.
 *
 * <p>The consequence is worth stating plainly, because it is why this dialog matters more than a
 * settings screen usually would. A multiblock's tier comes from its energy hatch and its speed from
 * its coils (STATUS §4a.9, §4b.13), neither of which MFP can see. Until they are set here, the line
 * is honestly reported as approximate or unknown — so this is where a multiblock's numbers become
 * trustworthy, not where they get tuned.
 */
public final class MachineConfigScreen extends ModalScreen {

    private static final List<Table.Column> MACHINE_COLUMNS = List.of(
            new Table.Column("Machine", 2.4f),
            new Table.Column("Tier", 0.8f),
            new Table.Column("", 0.9f));

    private final Plan plan;
    private final MfpRecipe recipe;

    private MachineConfig config;
    private Table machines;
    private int settingsBottom;
    private final Map<OptionSpec, Integer> optionLabels = new LinkedHashMap<>();

    public MachineConfigScreen(Screen parent, MfpRecipe recipe, MachineConfig current) {
        super(parent, "Machine", pathOf(recipe.id()));
        this.recipe = recipe;
        this.plan = ClientPlanner.current() == null ? new Plan("scratch") : ClientPlanner.current().plan();
        this.config = current;
    }

    @Override
    protected int preferredWidth() {
        return 520;
    }

    @Override
    protected int preferredHeight() {
        return 300;
    }

    @Override
    protected void build() {
        int columnWidth = (contentWidth() - GAP) / 2;
        buildMachineList(contentX(), contentY(), columnWidth);
        buildSettings(contentX() + columnWidth + GAP, contentY(), columnWidth);

        // A build described once, promoted to every line that runs the machine. The per-line dialog
        // is where the player already has the numbers in front of them, so making them retype the
        // same coils on the Machines screen would be the same work twice.
        MfpMachine machine = config.machineId() == null ? null : ClientIndex.get().machine(config.machineId());
        TextButton adopt = new TextButton("Use for every " + (machine == null
                ? "machine" : shortName(machine.displayName())), () -> {
            if (machine == null) {
                return;
            }
            PreferenceStore.get().machineDefaults(machine.id(),
                    new MachineDefaults(machine.multiblock() ? config.tier() : MachineDefaults.UNSET_TIER,
                            config.parallels() > 1 ? config.parallels() : MachineDefaults.UNSET_PARALLELS,
                            config.structureOptions()));
            PreferenceStore.save();
            ClientPlanner.refresh();
            rebuild();
        });
        adopt.enabled(machine != null);
        adopt.tooltip("Take this build - the hatch, the parallels and everything you set below the "
                + "machine - as how your copy of this machine is built, for every plan. The machine "
                + "limit is not included: how many you own is a fact about one factory.");
        adopt.bounds(contentX() + contentWidth() - 6 - adopt.preferredWidth() - 60,
                panelY + panelHeight - FOOTER_HEIGHT + 3, adopt.preferredWidth(), 14);
        widgets.add(adopt);

        TextButton reset = new TextButton("Use the default", () -> {
            // Forgetting the configuration is not the same as writing the default into it: the
            // default follows the recipe, so a plan that forgets is still right after a recipe
            // change, and one that froze the answer is not.
            plan.clearMachineConfig(recipe.id());
            ClientPlanner.refresh();
            back();
        });
        reset.tooltip("Forget this build and let MFP pick again: lowest tier that can run the "
                + "recipe, single blocks before multiblocks.");
        reset.bounds(contentX(), panelY + panelHeight - FOOTER_HEIGHT + 3, reset.preferredWidth(), 14);
        widgets.add(reset);
    }

    // ---------------------------------------------------------------- machines

    private void buildMachineList(int x, int y, int listWidth) {
        List<MfpMachine> candidates = MachinePicker.candidates(ClientIndex.get(), recipe);

        Table table = new Table(MACHINE_COLUMNS);
        for (MfpMachine machine : candidates) {
            boolean selected = machine.id().equals(config.machineId());
            table.addRow(List.of(
                            Cells.iconTwoLine(MachineStacks.icon(machine.id()), machine.displayName(),
                                    selected ? Theme.PINNED : Theme.TEXT,
                                    machine.multiblock() ? "multiblock" : pathOf(machine.id()),
                                    machineTooltip(machine)),
                            Cells.text(machine.tier() < 0 ? "hatch" : GtTiers.name(machine.tier()),
                                    Theme.TEXT_DIM,
                                    machine.tier() < 0
                                            ? List.of(Component.literal(
                                                    "A multiblock's voltage comes from its energy hatch, "
                                                            + "so the tier is yours to set.")
                                            .withStyle(ChatFormatting.GRAY))
                                            : List.of()),
                            Cells.button(selected ? "In use" : "Use",
                                    selected ? Theme.PINNED : Theme.TEXT, List.of(),
                                    () -> chooseMachine(machine))),
                    selected ? Theme.ROW_SELECTED : 0,
                    button -> {
                        if (button == 0) {
                            chooseMachine(machine);
                        }
                    });
        }
        this.machines = table;

        ScrollPanel scroll = new ScrollPanel();
        scroll.bounds(x, y + Table.HEADER_HEIGHT, listWidth,
                Math.max(20, contentBottom() - y - Table.HEADER_HEIGHT - GAP));
        table.layout(scroll.viewportWidth());
        scroll.content(table, table.contentHeight());
        widgets.add(scroll);
    }

    private List<Component> machineTooltip(MfpMachine machine) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(machine.id()).withStyle(ChatFormatting.WHITE));
        if (machine.tier() >= 0) {
            lines.add(Component.literal(GtTiers.name(machine.tier()) + ", "
                    + machine.maxVoltage() + " EU/t").withStyle(ChatFormatting.GRAY));
        }
        if (machine.multiblock()) {
            lines.add(Component.literal("multiblock - tier and structure are yours to describe")
                    .withStyle(ChatFormatting.GRAY));
        }
        if (!machine.modifierIds().isEmpty()) {
            lines.add(Component.literal("modifiers: " + machine.modifierIds())
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        return lines;
    }

    private void chooseMachine(MfpMachine machine) {
        int tier = machine.tier() >= 0 ? machine.tier() : Math.max(0, recipe.minTier());
        MachineDefaults build = PreferenceStore.get().machineDefaults(machine.id());
        // Seeded from the player's own description of that machine, where they have one. Switching
        // to a machine they have already described and being shown numbers for an empty one would
        // make the standing build look like it had not taken.
        MachineConfig picked = config.withMachine(machine.id(), tier);
        if (build.hasTier() && machine.tier() < 0 && tier < build.tier()) {
            picked = picked.withTier(build.tier());
        }
        apply(build.applyTo(picked));
        // Also the policy for this recipe type, so a later expansion's new line of the same type
        // starts where the user put this one (plan §8.2). The build itself stays per recipe.
        plan.chooseMachine(recipe.recipeTypeId(), machine.id());
    }

    // ---------------------------------------------------------------- settings

    private void buildSettings(int x, int y, int fieldWidth) {
        int cursorY = y + Table.HEADER_HEIGHT;
        int labelWidth = Math.min(120, fieldWidth / 2);
        int boxWidth = fieldWidth - labelWidth - GAP;

        // Tier -------------------------------------------------------------
        TextButton down = new TextButton("-", () -> stepTier(-1));
        down.tooltip("A lower tier: slower, cheaper, and it may not be able to run the recipe.");
        down.bounds(x + labelWidth, cursorY, 14, 14);
        down.enabled(config.tier() > Math.max(0, recipe.minTier()) && tierIsEditable());
        widgets.add(down);

        TextButton up = new TextButton("+", () -> stepTier(1));
        up.tooltip("A higher tier: more overclocks, and past the one-tick floor, parallel crafts.");
        up.bounds(x + labelWidth + 14 + GAP + 30, cursorY, 14, 14);
        up.enabled(config.tier() < GtTiers.MAX && tierIsEditable());
        widgets.add(up);

        cursorY += 14 + GAP;

        // Parallels --------------------------------------------------------
        TextField parallels = new TextField()
                .text(String.valueOf(config.parallels()))
                .filter(TextField.INTEGER)
                .maxLength(4)
                .tooltip("Run this many copies of the machine's recipe at once. Distinct from a "
                        + "parallel hatch, which is a structure option below - counting both would "
                        + "double the line.")
                .onCommit(value -> apply(config.withParallels(Math.max(1, parseInt(value, 1)))));
        parallels.bounds(x + labelWidth, cursorY, Math.min(60, boxWidth), 14);
        widgets.add(parallels);
        cursorY += 14 + GAP;

        // Machine limit ----------------------------------------------------
        TextField limit = new TextField()
                .text(config.hasLimit() ? Fmt.number(config.limit()) : "")
                .filter(TextField.NUMERIC)
                .maxLength(8)
                .placeholder("none")
                .tooltip("A cap on how many machines this line may use. The matrix engine cannot "
                        + "honour it - a limit is an inequality - and says so per line.")
                .onCommit(value -> {
                    String trimmed = value.trim();
                    apply(trimmed.isEmpty()
                            ? config.withoutLimit()
                            : config.withLimit(parseDouble(trimmed, 1), config.forceLimit()));
                });
        limit.bounds(x + labelWidth, cursorY, Math.min(60, boxWidth), 14);
        widgets.add(limit);

        // Two states, so its own inverse — wired to both buttons only so that a right-click here does
        // not read as a dead button beside the cycling ones below it.
        Runnable flipForce = () -> {
            if (config.hasLimit()) {
                apply(config.withLimit(config.limit(), !config.forceLimit()));
            }
        };
        TextButton force = new TextButton(config.forceLimit() ? "exactly" : "at most", flipForce);
        force.secondary(flipForce);
        force.enabled(config.hasLimit());
        force.tooltip("\"at most\" caps the line; \"exactly\" builds that many whether or not demand "
                + "needs them.");
        force.bounds(x + labelWidth + Math.min(60, boxWidth) + GAP, cursorY, force.preferredWidth(), 14);
        widgets.add(force);
        cursorY += 14 + GAP + 4;

        // Structure options, asked of the behaviours themselves ------------
        for (OptionSpec spec : optionsForThisMachine()) {
            cursorY = buildOption(spec, x, cursorY, labelWidth, boxWidth);
            if (cursorY > contentBottom() - 16) {
                break;
            }
        }
        this.settingsBottom = cursorY;
    }

    private int buildOption(OptionSpec spec, int x, int y, int labelWidth, int boxWidth) {
        Object current = config.structureOptions().get(spec.key());

        if (spec.kind() == OptionSpec.Kind.CHOICE) {
            // Cycled rather than dropped down: the lists are short (eight coils), and a dropdown is
            // a second overlapping layer this widget set has no business growing for one field.
            List<String> choices = spec.choices();
            String label = current == null ? "not set" : String.valueOf(current);
            TextButton cycle = new TextButton(label, () -> {
                int at = choices.indexOf(String.valueOf(current));
                int next = at + 1;
                apply(next >= choices.size()
                        ? config.withoutOption(spec.key())
                        : config.withOption(spec.key(), choices.get(next)));
            });
            cycle.secondary(() -> {
                // The ring the forward step walks, walked the other way: "not set" sits between the
                // last choice and the first, so a value not in the list — a coil a pack update
                // renamed — lands on the last exactly as it lands on the first going forwards.
                int at = choices.indexOf(String.valueOf(current));
                int previous = at < 0 ? choices.size() - 1 : at - 1;
                apply(previous < 0
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
                        // Blank clears the option rather than storing zero: "unset" is a state the
                        // behaviours act on, reporting an assumption instead of using a number.
                        apply(trimmed.isEmpty()
                                ? config.withoutOption(spec.key())
                                : config.withOption(spec.key(),
                                        TextField.clamp(parseInt(trimmed, spec.minimum()),
                                                spec.minimum(), spec.maximum())));
                    });
            field.bounds(x + labelWidth, y, Math.min(70, boxWidth), 14);
            widgets.add(field);
        }
        optionLabels.put(spec, y);
        return y + 14 + GAP;
    }

    /**
     * The options the behaviours that will actually run this line declare.
     *
     * <p>Asked of the resolved chain rather than of every registered behaviour, so the dialog offers
     * a coil selector for a blast furnace and not for a macerator.
     */
    private List<OptionSpec> optionsForThisMachine() {
        optionLabels.clear();
        var solved = ClientPlanner.current();
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
        // A single block's tier is the block you place; only a multiblock's is a choice, because it
        // comes from the energy hatch (STATUS §4a.9).
        return machine != null && machine.multiblock();
    }

    private void stepTier(int direction) {
        apply(config.withTier(Math.max(0, Math.min(GtTiers.MAX, config.tier() + direction))));
    }

    /**
     * Record a change and show what it did.
     *
     * <p>Mutate, re-solve, redraw — every edit in this dialog goes through here, so the verdict
     * beside the fields is always the one the new configuration produces rather than the previous
     * one. Callers do not re-solve themselves; solving twice per click is only waste.
     */
    private void apply(MachineConfig updated) {
        this.config = updated;
        plan.configureMachine(recipe.id(), updated);
        ClientPlanner.refresh();
        rebuild();
    }

    // ------------------------------------------------------------------ render

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int columnWidth = (contentWidth() - GAP) / 2;
        if (machines != null) {
            machines.renderHeader(graphics, contentX(), contentY(), columnWidth - 6);
        }

        int rightX = contentX() + columnWidth + GAP;
        graphics.fill(rightX, contentY(), rightX + columnWidth, contentY() + Table.HEADER_HEIGHT, 0x50000000);
        graphics.drawString(font, "This build", rightX + 3, contentY() + 2, Theme.TEXT_HEADER, false);

        int labelWidth = Math.min(120, columnWidth / 2);
        int cursorY = contentY() + Table.HEADER_HEIGHT;
        label(graphics, "Tier", rightX, cursorY);
        String tierText = config.tier() < 0 ? "-" : GtTiers.name(config.tier());
        graphics.drawString(font, tierText, rightX + labelWidth + 20, cursorY + 3,
                tierIsEditable() ? Theme.TEXT : Theme.TEXT_IDLE, false);
        cursorY += 14 + GAP;
        label(graphics, "Parallels", rightX, cursorY);
        cursorY += 14 + GAP;
        label(graphics, "Machine limit", rightX, cursorY);
        cursorY += 14 + GAP + 4;

        for (Map.Entry<OptionSpec, Integer> entry : optionLabels.entrySet()) {
            graphics.drawString(font, MfpWidget.fit(entry.getKey().label(), labelWidth - 4),
                    rightX, entry.getValue() + 3, Theme.TEXT_DIM, false);
        }

        if (optionLabels.isEmpty()) {
            graphics.drawString(font, MfpWidget.fit("no structure options for this machine",
                            columnWidth - 6), rightX, cursorY + 3, Theme.TEXT_IDLE, false);
            cursorY += 12;
        }
        renderVerdict(graphics, rightX, Math.max(cursorY, settingsBottom) + 6, columnWidth);
    }

    /**
     * What the current build actually computes, right where it is being edited.
     *
     * <p>Shown because the point of the dialog is the number, not the settings: a coil chosen with
     * no visible effect on duration or confidence is indistinguishable from a coil that was ignored.
     */
    private void renderVerdict(GuiGraphics graphics, int x, int y, int columnWidth) {
        var solved = ClientPlanner.current();
        if (solved == null || y > contentBottom() - 20) {
            return;
        }
        ThroughputResult throughput = solved.resolver().resolveResult(recipe, config);

        if (throughput.cancelled()) {
            graphics.drawString(font, MfpWidget.fit("cannot run: " + throughput.cancelReason(),
                    columnWidth - 6), x, y, Theme.ERROR, false);
            return;
        }
        String duration = Fmt.number(recipe.durationTicks()) + " t -> "
                + Fmt.number(throughput.durationTicks(recipe.durationTicks())) + " t";
        graphics.drawString(font, MfpWidget.fit(duration, columnWidth - 6), x, y,
                Theme.forConfidence(throughput.confidence()), false);

        int lineY = y + 10;
        if (recipe.euIn() > 0) {
            String power = recipe.euIn() + " -> "
                    + Fmt.number(throughput.eut(recipe.euIn() * Math.max(1, recipe.amperage()))) + " EU/t";
            graphics.drawString(font, MfpWidget.fit(power, columnWidth - 6), x, lineY, Theme.ENERGY_IN, false);
            lineY += 10;
        }
        for (String note : throughput.notes()) {
            if (lineY > contentBottom() - 10) {
                break;
            }
            graphics.drawString(font, MfpWidget.fit("- " + note, columnWidth - 6), x, lineY,
                    Theme.WARNING, false);
            lineY += 10;
        }
    }

    private void label(GuiGraphics graphics, String text, int x, int y) {
        graphics.drawString(font, text, x, y + 3, Theme.TEXT_DIM, false);
    }

    @Override
    protected void offerTooltips(int mouseX, int mouseY) {
        if (machines != null) {
            int columnWidth = (contentWidth() - GAP) / 2;
            offer(machines.headerTooltip(contentX(), contentY(), columnWidth - 6, mouseX, mouseY),
                    mouseX, mouseY);
        }
    }

    /** The machine's name, short enough to sit inside a button. */
    private static String shortName(String displayName) {
        return displayName.length() <= 18 ? displayName : displayName.substring(0, 17) + "…";
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double parseDouble(String text, double fallback) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
