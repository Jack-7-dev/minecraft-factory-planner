package dev.mfp.client.screen;

import dev.mfp.client.ClientPlanner;
import dev.mfp.client.KeyStacks;
import dev.mfp.client.widget.Cells;
import dev.mfp.client.widget.ScrollPanel;
import dev.mfp.client.widget.SlotWidget;
import dev.mfp.client.widget.Table;
import dev.mfp.client.widget.Theme;
import dev.mfp.core.model.MfpIngredient;
import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpRecipe;
import dev.mfp.core.plan.Plan;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Which item to use for an input that accepts several.
 *
 * <p>A tag ingredient is genuinely ambiguous — GregTech's plant ball recipe takes wood pulp or
 * Create's wood chips, and both are correct — but a plan has to expand one of them, and until now
 * the choice was made silently by candidate order. The tooltip said "3 alternatives accepted" and
 * there was nowhere to go with that.
 *
 * <p>The choice is stored on the plan as a <em>preferred item</em> rather than against this
 * ingredient, because an ingredient has no identity of its own: the same tag appears in dozens of
 * recipes, and a user who says "wood pulp" here means it everywhere in this plan. It takes effect by
 * reordering the ingredient's candidates, so the chooser and the solver cannot end up disagreeing
 * about what the line eats.
 */
public final class IngredientChoiceScreen extends ModalScreen {

    private static final List<Table.Column> COLUMNS = List.of(
            new Table.Column("Item", 3.0f),
            new Table.Column("", 1.2f));

    private final Plan plan;
    private final MfpRecipe recipe;

    public IngredientChoiceScreen(Screen parent, MfpRecipe recipe) {
        super(parent, "Input items", recipe.id());
        this.recipe = recipe;
        this.plan = ClientPlanner.current() == null ? new Plan("scratch") : ClientPlanner.current().plan();
    }

    /** Whether this recipe has anything to choose between; the caller should not open an empty box. */
    public static boolean hasChoices(MfpRecipe recipe) {
        return recipe.inputs().stream().anyMatch(MfpIngredient::isAmbiguous);
    }

    @Override
    protected int preferredWidth() {
        return 320;
    }

    @Override
    protected int preferredHeight() {
        return 240;
    }

    @Override
    protected void build() {
        Table table = new Table(COLUMNS);
        for (MfpIngredient input : recipe.inputs()) {
            if (!input.isAmbiguous()) {
                continue;
            }
            for (MfpKey candidate : input.candidates()) {
                boolean current = candidate.equals(input.primary());
                boolean chosen = plan.preferredItems().contains(candidate);
                List<Component> tooltip = new ArrayList<>(KeyStacks.tooltip(candidate, null));
                if (current) {
                    tooltip.add(Component.literal("what this line uses now")
                            .withStyle(ChatFormatting.GRAY));
                }
                table.addRow(List.of(
                                Cells.icon(SlotWidget.of(candidate,
                                        KeyStacks.name(candidate).getString(), tooltip)),
                                Cells.button(chosen ? "Chosen" : "Use this",
                                        chosen ? Theme.PINNED : Theme.TEXT,
                                        List.of(Component.literal(
                                                        "Use " + KeyStacks.name(candidate).getString()
                                                                + " wherever this plan has the choice")
                                                .withStyle(ChatFormatting.GRAY)),
                                        () -> choose(candidate))),
                        current ? Theme.ROW_SELECTED : 0, null);
            }
        }

        ScrollPanel scroll = new ScrollPanel();
        scroll.bounds(contentX(), contentY(), contentWidth(),
                Math.max(20, contentBottom() - contentY() - GAP));
        table.layout(scroll.viewportWidth());
        scroll.content(table, table.contentHeight());
        widgets.add(scroll);
    }

    private void choose(MfpKey candidate) {
        // Clearing the others first keeps the plan's list honest: two preferences for the same
        // ingredient would resolve by iteration order, which is no choice at all.
        for (MfpIngredient input : recipe.inputs()) {
            if (input.isAmbiguous() && input.candidates().contains(candidate)) {
                input.candidates().forEach(plan::clearPreferredItem);
            }
        }
        plan.preferItem(candidate);
        ClientPlanner.refresh();
        back();
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }
}
