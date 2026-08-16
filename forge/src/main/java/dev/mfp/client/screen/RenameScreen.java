package dev.mfp.client.screen;

import dev.mfp.client.ClientPlanner;
import dev.mfp.client.widget.TextButton;
import dev.mfp.client.widget.TextField;
import dev.mfp.client.widget.Theme;
import dev.mfp.core.plan.Plan;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

/**
 * Name a plan (M9.1).
 *
 * <p>A dialog of its own rather than a field buried in plan settings, because renaming is something
 * the user wants while looking at the list they cannot read — and because the interesting part is
 * the empty field. <b>Clearing the name is not the same as naming a plan nothing</b>: it hands the
 * plan back to being named after what it makes, which is the state every plan starts in and the one
 * that keeps following the target. The button below the field says exactly that, so it is a
 * discoverable action rather than a trick.
 */
public final class RenameScreen extends ModalScreen {

    private final Plan plan;
    private TextField name;

    public RenameScreen(Screen parent, Plan plan) {
        super(parent, "Rename plan", null);
        this.plan = plan;
    }

    @Override
    protected int preferredWidth() {
        return 300;
    }

    @Override
    protected int preferredHeight() {
        return 100;
    }

    @Override
    protected void build() {
        int cursorY = contentY() + 10;

        this.name = new TextField()
                .text(plan.isNamed() ? plan.name() : "")
                .maxLength(60)
                .placeholder(plan.derivedName())
                .tooltip("What this plan is called in the plan list.")
                .onCommit(value -> {
                    plan.name(value);
                    back();
                });
        name.bounds(contentX(), cursorY, contentWidth(), 14);
        name.focus();
        widgets.add(name);
        cursorY += 14 + GAP + 6;

        TextButton apply = new TextButton("Rename", () -> {
            plan.name(name.text());
            back();
        });
        apply.bounds(contentX(), cursorY, apply.preferredWidth(), 14);
        widgets.add(apply);

        TextButton auto = new TextButton("Name it after what it makes", () -> {
            plan.name("");
            back();
        });
        auto.enabled(plan.isNamed());
        auto.tooltip("Drop the name you gave it. The plan is then called after its first target, "
                + "and follows that target if you change it.");
        auto.bounds(contentX() + apply.preferredWidth() + GAP, cursorY, auto.preferredWidth(), 14);
        widgets.add(auto);
    }

    /** Back to the planner, which re-solves nothing: a name changes no number. */
    @Override
    protected void back() {
        ClientPlanner.saveAll(dev.mfp.client.MfpClient.worldName());
        super.back();
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.drawString(font, "Name", contentX(), contentY(), Theme.TEXT_DIM, false);
    }
}
