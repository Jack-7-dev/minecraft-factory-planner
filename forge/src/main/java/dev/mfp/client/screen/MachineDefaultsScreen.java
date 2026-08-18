package dev.mfp.client.screen;

import dev.mfp.client.ClientIndex;
import dev.mfp.client.ClientPlanner;
import dev.mfp.client.MachineStacks;
import dev.mfp.client.widget.Cells;
import dev.mfp.client.widget.MfpWidget;
import dev.mfp.client.widget.ScrollPanel;
import dev.mfp.client.widget.Table;
import dev.mfp.client.widget.TextButton;
import dev.mfp.client.widget.TextField;
import dev.mfp.client.widget.Theme;
import dev.mfp.core.behaviour.GtTiers;
import dev.mfp.core.behaviour.OptionSpec;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.plan.MachineDefaults;
import dev.mfp.core.plan.Preferences;
import dev.mfp.plan.PlanSession;
import dev.mfp.plan.PreferenceStore;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How the player's own machines are built: "my blast furnace has HSS-G coils and an EV hatch".
 *
 * <p>The standing default tier said which hatch <em>every</em> multiblock has, which is the coarsest
 * possible version of this and the only one that existed. What actually decides a multiblock's rate
 * is what was built inside it — the coils, the parallel hatch, the rotor, the pump — and those
 * belong to one machine rather than to all of them. Without this the only place to say any of it
 * was the per-line dialog, so a player with one blast furnace described it again for every recipe
 * they smelted in it, in every plan.
 *
 * <p><b>The fields are the machine's own.</b> They are asked of the behaviours that will compute
 * this machine's throughput ({@code BehaviourRegistry.optionsFor}), exactly as the per-line dialog
 * asks the resolved chain, so a multiblock from {@code start_core} — or from a mod that does not
 * exist yet — offers the fields its own behaviour reads and nothing else.
 *
 * <p>Nothing here overrules a plan: a line the user built out themselves keeps its build, and a
 * machine they chose stays chosen. This decides what a line starts as where they have not said.
 */
public final class MachineDefaultsScreen extends ModalScreen {

    /** How many machines are listed at once. Past this the answer is "search", not "scroll". */
    private static final int RESULT_LIMIT = 120;

    private static final List<Table.Column> MACHINE_COLUMNS = List.of(
            new Table.Column("Machine", 2.2f),
            new Table.Column("Build", 1.6f, "What you have said about this machine."));

    private final Preferences preferences = PreferenceStore.get();

    private String selectedId;
    private TextField search;
    private String query = "";
    private int matchCount;
    private int listY;
    private int settingsBottom;
    private final Map<OptionSpec, Integer> optionLabels = new LinkedHashMap<>();

    public MachineDefaultsScreen(Screen parent) {
        super(parent, "Machines", "how your own machines are built");
    }

    @Override
    protected int preferredWidth() {
        return 560;
    }

    @Override
    protected int preferredHeight() {
        return 320;
    }

    @Override
    protected void build() {
        optionLabels.clear();
        int columnWidth = (contentWidth() - GAP) / 2;
        buildMachineList(contentX(), contentY(), columnWidth);
        buildSettings(contentX() + columnWidth + GAP, contentY(), columnWidth);
    }

    // ---------------------------------------------------------------- machines

    private void buildMachineList(int x, int y, int listWidth) {
        if (search == null) {
            // Kept across rebuilds: a rebuild happens on every keystroke, and a fresh field would put
            // the caret back at the end of the text each time.
            search = new TextField()
                    .placeholder("search, e.g. blast or gtceu:")
                    .text(query)
                    .onChange(value -> {
                        query = value;
                        rebuild();
                    });
        }
        search.bounds(x, y, listWidth, 14);
        widgets.add(search);
        this.listY = y + 14 + GAP;

        List<MfpMachine> machines = matching();
        matchCount = machines.size();

        Table table = new Table(MACHINE_COLUMNS);
        for (MfpMachine machine : machines) {
            MachineDefaults defaults = preferences.machineDefaults(machine.id());
            boolean selected = machine.id().equals(selectedId);
            table.addRow(List.of(
                            Cells.iconTwoLine(MachineStacks.icon(machine.id()), machine.displayName(),
                                    selected ? Theme.PINNED : Theme.TEXT,
                                    machine.multiblock() ? "multiblock" : pathOf(machine.id()),
                                    tooltipFor(machine, defaults)),
                            Cells.text(summary(defaults),
                                    defaults.isEmpty() ? Theme.TEXT_IDLE : Theme.PINNED,
                                    tooltipFor(machine, defaults))),
                    selected ? Theme.ROW_SELECTED : 0,
                    button -> {
                        if (button == 0) {
                            selectedId = machine.id();
                            rebuild();
                        }
                    });
        }

        ScrollPanel scroll = new ScrollPanel();
        scroll.bounds(x, listY + Table.HEADER_HEIGHT, listWidth,
                Math.max(20, contentBottom() - listY - Table.HEADER_HEIGHT - GAP));
        table.layout(scroll.viewportWidth());
        scroll.content(table, table.contentHeight());
        widgets.add(scroll);
    }

    /**
     * The machines worth offering, most likely first.
     *
     * <p>Configured ones lead, then multiblocks. Not decoration: a single block's tier is the block
     * you place and its rate needs no description, so the machines this screen exists for are almost
     * exactly the multiblocks — and a list that opened on eight hundred alphabetically-sorted item
     * pipes would bury them.
     */
    private List<MfpMachine> matching() {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<MfpMachine> matches = new ArrayList<>();
        for (MfpMachine machine : ClientIndex.get().machines()) {
            if (needle.isEmpty()
                    || machine.displayName().toLowerCase(Locale.ROOT).contains(needle)
                    || machine.id().toLowerCase(Locale.ROOT).contains(needle)) {
                matches.add(machine);
            }
        }
        matches.sort((left, right) -> {
            int byConfigured = Boolean.compare(
                    preferences.machineDefaults(right.id()).isEmpty(),
                    preferences.machineDefaults(left.id()).isEmpty());
            if (byConfigured != 0) {
                return byConfigured;
            }
            int byMultiblock = Boolean.compare(right.multiblock(), left.multiblock());
            return byMultiblock != 0 ? byMultiblock
                    : left.displayName().compareToIgnoreCase(right.displayName());
        });
        return matches.size() > RESULT_LIMIT ? matches.subList(0, RESULT_LIMIT) : matches;
    }

    private List<Component> tooltipFor(MfpMachine machine, MachineDefaults defaults) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(machine.displayName()).withStyle(ChatFormatting.WHITE));
        lines.add(Component.literal(machine.id()).withStyle(ChatFormatting.DARK_GRAY));
        if (defaults.isEmpty()) {
            lines.add(Component.literal("click to describe how yours is built")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(Component.literal(summary(defaults)).withStyle(ChatFormatting.YELLOW));
        }
        return lines;
    }

    private static String summary(MachineDefaults defaults) {
        if (defaults.isEmpty()) {
            return "-";
        }
        List<String> parts = new ArrayList<>();
        if (defaults.hasTier()) {
            parts.add(GtTiers.name(defaults.tier()));
        }
        if (defaults.hasParallels()) {
            parts.add(defaults.parallels() + "x");
        }
        defaults.structureOptions().forEach((key, value) -> parts.add(String.valueOf(value)));
        return String.join(", ", parts);
    }

    // ---------------------------------------------------------------- settings

    private MfpMachine selected() {
        return selectedId == null ? null : ClientIndex.get().machine(selectedId);
    }

    private void buildSettings(int x, int y, int fieldWidth) {
        MfpMachine machine = selected();
        if (machine == null) {
            return;
        }
        MachineDefaults defaults = preferences.machineDefaults(machine.id());
        int cursorY = y + Table.HEADER_HEIGHT;
        int labelWidth = Math.min(120, fieldWidth / 2);
        int boxWidth = fieldWidth - labelWidth - GAP;

        // Tier -------------------------------------------------------------
        // A single block's tier is the block you place, so only a multiblock's is describable: its
        // voltage comes from the energy hatch the player installed (STATUS §4a.9).
        TextButton tier = new TextButton(tierLabel(machine, defaults), () -> {
            if (!machine.multiblock()) {
                return;
            }
            int next = defaults.hasTier() ? defaults.tier() + 1 : 0;
            apply(machine, next > GtTiers.MAX ? defaults.withoutTier() : defaults.withTier(next));
        });
        tier.secondary(() -> {
            if (!machine.multiblock()) {
                return;
            }
            int previous = defaults.hasTier() ? defaults.tier() - 1 : GtTiers.MAX;
            apply(machine, previous < 0 ? defaults.withoutTier() : defaults.withTier(previous));
        });
        tier.enabled(machine.multiblock());
        tier.tooltip(machine.multiblock()
                ? "The energy hatch you actually built into this machine. It beats the general "
                        + "\"build at tier\" default, and a recipe that needs more still gets more. "
                        + "Right-click to go back."
                : "A single block's tier is the block you place, so there is nothing to describe.");
        tier.bounds(x + labelWidth, cursorY, Math.max(80, Math.min(boxWidth, tier.preferredWidth())), 14);
        widgets.add(tier);
        cursorY += 14 + GAP;

        // Parallels --------------------------------------------------------
        TextField parallels = new TextField()
                .text(defaults.hasParallels() ? String.valueOf(defaults.parallels()) : "")
                .filter(TextField.INTEGER)
                .maxLength(4)
                .placeholder("not set")
                .tooltip("Copies of the machine's recipe to run at once. Distinct from a parallel "
                        + "hatch, which is a build option below - counting both would double the line.")
                .onCommit(value -> {
                    String trimmed = value.trim();
                    apply(machine, trimmed.isEmpty()
                            ? defaults.withoutParallels()
                            : defaults.withParallels(Math.max(1, parseInt(trimmed, 1))));
                });
        parallels.bounds(x + labelWidth, cursorY, Math.min(60, boxWidth), 14);
        widgets.add(parallels);
        cursorY += 14 + GAP + 4;

        // Build options, asked of the behaviours themselves -----------------
        for (OptionSpec spec : optionsFor(machine)) {
            cursorY = buildOption(machine, defaults, spec, x, cursorY, labelWidth, boxWidth);
            if (cursorY > contentBottom() - 30) {
                break;
            }
        }
        this.settingsBottom = cursorY;

        TextButton forget = new TextButton("Forget this build", () -> {
            preferences.clearMachineDefaults(machine.id());
            saveAndResolve();
        });
        forget.enabled(!defaults.isEmpty());
        forget.tooltip("Stop describing this machine. Every line that was not built out by hand goes "
                + "back to what MFP would have picked on its own.");
        forget.bounds(x, panelY + panelHeight - FOOTER_HEIGHT + 3, forget.preferredWidth(), 14);
        widgets.add(forget);
    }

    private int buildOption(MfpMachine machine, MachineDefaults defaults, OptionSpec spec,
                            int x, int y, int labelWidth, int boxWidth) {
        Object current = defaults.structureOptions().get(spec.key());

        if (spec.kind() == OptionSpec.Kind.CHOICE) {
            List<String> choices = spec.choices();
            String label = current == null ? "not set" : String.valueOf(current);
            TextButton cycle = new TextButton(label, () -> {
                int next = choices.indexOf(String.valueOf(current)) + 1;
                apply(machine, next >= choices.size()
                        ? defaults.withoutOption(spec.key())
                        : defaults.withOption(spec.key(), choices.get(next)));
            });
            cycle.secondary(() -> {
                int at = choices.indexOf(String.valueOf(current));
                int previous = at < 0 ? choices.size() - 1 : at - 1;
                apply(machine, previous < 0
                        ? defaults.withoutOption(spec.key())
                        : defaults.withOption(spec.key(), choices.get(previous)));
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
                        // Blank clears it: "unset" is a state the behaviours act on, reporting an
                        // assumption rather than using a number.
                        apply(machine, trimmed.isEmpty()
                                ? defaults.withoutOption(spec.key())
                                : defaults.withOption(spec.key(),
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
     * The build choices this machine has, asked of the behaviours that will compute its rate.
     *
     * <p>Without a recipe, because the player is describing a machine rather than a line — see
     * {@code BehaviourRegistry.optionsFor}, which is the looser question and says so.
     */
    private List<OptionSpec> optionsFor(MfpMachine machine) {
        return PlanSession.resolverFor(ClientIndex.get()).registry().optionsFor(machine);
    }

    private String tierLabel(MfpMachine machine, MachineDefaults defaults) {
        if (!machine.multiblock()) {
            return machine.tier() < 0 ? "-" : GtTiers.name(machine.tier());
        }
        return defaults.hasTier() ? GtTiers.name(defaults.tier()) : "not set";
    }

    private void apply(MfpMachine machine, MachineDefaults updated) {
        preferences.machineDefaults(machine.id(), updated);
        saveAndResolve();
    }

    /**
     * Write the file and re-solve, in that order.
     *
     * <p>The re-solve is what makes the screen honest rather than a convenience: a coil chosen here
     * changes every line running that machine, and a build that did not visibly move the plan behind
     * the dialog is indistinguishable from one that was ignored.
     */
    private void saveAndResolve() {
        PreferenceStore.save();
        ClientPlanner.refresh();
        rebuild();
    }

    // ------------------------------------------------------------------ render

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int columnWidth = (contentWidth() - GAP) / 2;
        if (matchCount == 0) {
            graphics.drawString(font, MfpWidget.fit("no machine matches that", columnWidth),
                    contentX(), listY + 4, Theme.TEXT_IDLE, false);
        }

        int rightX = contentX() + columnWidth + GAP;
        MfpMachine machine = selected();
        graphics.fill(rightX, contentY(), rightX + columnWidth, contentY() + Table.HEADER_HEIGHT,
                0x50000000);
        graphics.drawString(font, machine == null ? "Your build" : MfpWidget.fit(
                        machine.displayName(), columnWidth - 6),
                rightX + 3, contentY() + 2, Theme.TEXT_HEADER, false);

        if (machine == null) {
            graphics.drawString(font, MfpWidget.fit("choose a machine to describe it",
                            columnWidth - 6), rightX, contentY() + Table.HEADER_HEIGHT + 4,
                    Theme.TEXT_IDLE, false);
            return;
        }

        int labelWidth = Math.min(120, columnWidth / 2);
        int cursorY = contentY() + Table.HEADER_HEIGHT;
        graphics.drawString(font, "Energy hatch", rightX, cursorY + 3, Theme.TEXT_DIM, false);
        cursorY += 14 + GAP;
        graphics.drawString(font, "Parallels", rightX, cursorY + 3, Theme.TEXT_DIM, false);
        cursorY += 14 + GAP + 4;

        for (Map.Entry<OptionSpec, Integer> entry : optionLabels.entrySet()) {
            graphics.drawString(font, MfpWidget.fit(entry.getKey().label(), labelWidth - 4),
                    rightX, entry.getValue() + 3, Theme.TEXT_DIM, false);
        }
        if (optionLabels.isEmpty()) {
            graphics.drawString(font, MfpWidget.fit("nothing else to describe on this machine",
                            columnWidth - 6), rightX, cursorY + 3, Theme.TEXT_IDLE, false);
            cursorY += 12;
        }

        graphics.drawString(font, MfpWidget.fit(
                        "applies to every line running this machine that you have not built by hand",
                        columnWidth - 6),
                rightX, Math.max(cursorY, settingsBottom) + 6, Theme.TEXT_IDLE, false);
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
