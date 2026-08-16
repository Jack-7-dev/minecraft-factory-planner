package dev.mfp.core.plan;

import dev.mfp.core.model.MfpKey;
import dev.mfp.core.model.MfpRecipe;

import java.util.Objects;

/**
 * One recipe running in a plan.
 *
 * <p>Identity is by object, not by value: a plan may legitimately contain the same recipe twice
 * (say, two banks of the same machine on different floors), and results are attributed per line.
 *
 * <p>Three fields carry more weight than their size suggests, all ported from Factory Planner:
 *
 * <ul>
 *   <li>{@link #percentage} runs the line at a fraction of what demand asks for.
 *   <li>{@link #priorityItem} decides which product paces a multi-output recipe. GregTech recipes
 *       routinely have three or more outputs, and without this the solver must guess whether to
 *       satisfy every demand (overproducing the rest) or match one and under-produce the others.
 *   <li>{@link #productionType} distinguishes a line placed to <em>make</em> something from one
 *       placed to <em>consume</em> a byproduct, which are paced by opposite constraints.
 * </ul>
 */
public final class Line implements LineNode {

    private final MfpRecipe recipe;
    private MachineConfig machine;
    private double percentage;
    private MfpKey priorityItem;
    private boolean active;
    private ProductionType productionType;

    public Line(MfpRecipe recipe) {
        this(recipe, MachineConfig.UNSET);
    }

    public Line(MfpRecipe recipe, MachineConfig machine) {
        this.recipe = Objects.requireNonNull(recipe, "recipe");
        this.machine = Objects.requireNonNull(machine, "machine");
        this.percentage = 100.0;
        this.active = true;
        this.productionType = ProductionType.PRODUCE;
    }

    public MfpRecipe recipe() {
        return recipe;
    }

    public MachineConfig machine() {
        return machine;
    }

    public Line machine(MachineConfig newMachine) {
        this.machine = Objects.requireNonNull(newMachine, "machine");
        return this;
    }

    /** 100 means "produce exactly what is demanded"; 50 means half of it. */
    public double percentage() {
        return percentage;
    }

    public Line percentage(double newPercentage) {
        if (newPercentage < 0 || !Double.isFinite(newPercentage)) {
            throw new IllegalArgumentException("percentage must be finite and non-negative");
        }
        this.percentage = newPercentage;
        return this;
    }

    /** Which product paces this line, or null to let the solver satisfy every demanded output. */
    public MfpKey priorityItem() {
        return priorityItem;
    }

    public Line priorityItem(MfpKey key) {
        this.priorityItem = key;
        return this;
    }

    /** Inactive lines are skipped entirely, as if they were not in the plan. */
    public boolean active() {
        return active;
    }

    public Line active(boolean newActive) {
        this.active = newActive;
        return this;
    }

    public ProductionType productionType() {
        return productionType;
    }

    public Line productionType(ProductionType type) {
        this.productionType = Objects.requireNonNull(type, "productionType");
        return this;
    }

    @Override
    public String toString() {
        return "Line[" + recipe.id() + "]";
    }
}
