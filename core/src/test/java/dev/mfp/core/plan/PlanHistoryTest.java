package dev.mfp.core.plan;

import dev.mfp.core.model.MfpKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Undo, and the state stack behind it (M15).
 *
 * <p>The claim being tested is not "a deque works". It is the one that decides whether undo is
 * trustworthy: <b>every plan-mutating gesture can be taken back, and nothing else appears in the
 * history</b>. So there is a test per kind of decision, a test that a re-solve which changed nothing
 * files nothing, and — because a gesture added next year is the way this quietly stops being true —
 * a test that enumerates {@link Plan}'s own mutators and fails when one appears that neither the
 * snapshot nor the exemption list knows about.
 */
class PlanHistoryTest {

    private static final MfpKey ORE = MfpKey.item("mfp", "ore");
    private static final MfpKey INGOT = MfpKey.item("mfp", "ingot");
    private static final MfpKey PLATE = MfpKey.item("mfp", "plate");

    private static Plan plan() {
        return new Plan().target(INGOT, 1);
    }

    /** What the planner does around every edit: record, mutate, record. */
    private static PlanHistory started(Plan plan) {
        PlanHistory history = new PlanHistory();
        history.record(plan);
        return history;
    }

    @Test
    @DisplayName("the first record is a baseline, not a step")
    void firstRecordIsBaseline() {
        Plan plan = plan();
        PlanHistory history = new PlanHistory();
        assertFalse(history.record(plan), "a plan's first state is not something to go back to");
        assertFalse(history.canUndo());
    }

    @Test
    @DisplayName("an edit is one step, and undo puts the plan back exactly")
    void undoRestoresTheState() {
        Plan plan = plan();
        PlanHistory history = started(plan);
        String before = PlanExport.export(plan);

        plan.setTarget(0, new TargetOutput(INGOT, 3));
        assertTrue(history.record(plan), "changing the target is an edit");
        assertTrue(history.canUndo());
        assertNotEquals(before, PlanExport.export(plan));

        assertTrue(history.undo(plan));
        assertEquals(before, PlanExport.export(plan), "undo restores the exported plan exactly");
        assertFalse(history.canUndo(), "one edit, one step back");
        assertTrue(history.canRedo());
    }

    @Test
    @DisplayName("undo restores into the same plan object the screens are holding")
    void undoRestoresInPlace() {
        Plan plan = plan();
        PlanHistory history = started(plan);
        plan.chooseRecipe(INGOT, "mfp:smelt");
        history.record(plan);

        Plan held = plan;
        history.undo(plan);
        assertSame(held, plan, "a screen that captured the plan must still be editing this one");
        assertTrue(plan.recipeChoices().isEmpty());
    }

    @Test
    @DisplayName("adding a consumer for a surplus is one step, and undo takes it back (M18)")
    void answeringASurplusIsUndoable() {
        Plan plan = plan();
        PlanHistory history = started(plan);
        String before = PlanExport.export(plan);

        plan.consumeWith(PLATE, "mfp:eat_plates");
        assertTrue(history.record(plan), "the milestone's one new mutator files a step");

        assertTrue(history.undo(plan));
        assertEquals(before, PlanExport.export(plan));
        assertTrue(plan.sinks().isEmpty());

        // And forwards again, because the point of the mirror is that it behaves like the pin it
        // mirrors: nothing about a sink is a special case for the history.
        assertTrue(history.redo(plan));
        assertEquals("mfp:eat_plates", plan.sink(PLATE));
    }

    @Test
    @DisplayName("redo puts the edit back, and a new edit throws the redo branch away")
    void redoAndBranching() {
        Plan plan = plan();
        PlanHistory history = started(plan);
        plan.setTarget(0, new TargetOutput(INGOT, 3));
        history.record(plan);
        String edited = PlanExport.export(plan);

        history.undo(plan);
        assertTrue(history.redo(plan));
        assertEquals(edited, PlanExport.export(plan));

        history.undo(plan);
        plan.setTarget(0, new TargetOutput(INGOT, 7));
        history.record(plan);
        assertFalse(history.canRedo(), "editing past an undo makes the branch unreachable");
    }

    @Test
    @DisplayName("undo is not itself an edit: the re-solve after it files nothing")
    void undoDoesNotRecordItself() {
        Plan plan = plan();
        PlanHistory history = started(plan);
        plan.setTarget(0, new TargetOutput(INGOT, 3));
        history.record(plan);

        history.undo(plan);
        // What ClientPlanner.refresh does at the end of the re-solve an undo triggers.
        assertFalse(history.record(plan), "an undo that recorded itself could never be undone past");
        assertTrue(history.canRedo(), "and it must not have thrown the redo branch away");
        assertEquals(0, history.undoDepth());
    }

    @Test
    @DisplayName("a re-solve that changed nothing is not a step")
    void unchangedResolveIsNotAStep() {
        Plan plan = plan();
        PlanHistory history = started(plan);
        // The Refresh button, a rebuild after a rejected edit, and every display-only toggle in the
        // planner: they all end here, and any one of them filling the history would make undo appear
        // not to work.
        assertFalse(history.record(plan));
        assertFalse(history.record(plan));
        assertFalse(history.canUndo());
    }

    @Test
    @DisplayName("solver-derived state is not an edit")
    void derivedSolverModeIsNotAnEdit() {
        Plan plan = plan();
        PlanHistory history = started(plan);
        // The chooser moves a plan to MATRIX when it observes a loop. That is the solve's own
        // observation about lines it just built, not something the user did, and a history entry for
        // it would make the first press of undo do nothing visible.
        plan.deriveSolverMode(SolverMode.MATRIX);
        assertFalse(history.record(plan));

        plan.solverMode(SolverMode.SIMPLEX);
        assertTrue(history.record(plan), "choosing an engine is an edit");
    }

    @Test
    @DisplayName("a hand order is an edit, and one drag is one step")
    void displayOrderIsAnEdit() {
        Plan plan = plan();
        PlanHistory history = started(plan);
        plan.displayOrder(List.of("mfp:a", "mfp:b"));
        assertTrue(history.record(plan));

        history.undo(plan);
        assertEquals(List.of(), plan.displayOrder());
    }

    @Test
    @DisplayName("renaming is an edit, and undo takes the name back")
    void renameIsAnEdit() {
        Plan plan = plan();
        PlanHistory history = started(plan);
        plan.name("Steel line");
        assertTrue(history.record(plan));

        history.undo(plan);
        assertFalse(plan.isNamed(), "the plan follows its target again");
        assertEquals(plan.derivedName(), plan.name());
    }

    @Test
    @DisplayName("every kind of decision survives the round trip")
    void everyDecisionRoundTrips() {
        Plan plan = plan();
        PlanHistory history = started(plan);

        plan.name("Everything")
                .target(PLATE, 2)
                .freeItem(ORE)
                .rawMaterial(PLATE)
                .clearRawMaterial(ORE)
                .preferItem(ORE)
                .chooseRecipe(INGOT, "mfp:ingot")
                .chooseMachine("mfp:machine", "mfp:hv_machine")
                .configureMachine("mfp:ingot", new MachineConfig("mfp:hv_machine", 3, 2,
                        null, false, Map.of("coil", "kanthal")))
                .blacklistRecipe("mfp:slow")
                .blockItem(PLATE)
                .allowItem(ORE)
                .defaultTier(3)
                .byproductFeeds(false)
                .autoResolve(false)
                .displayOrder(List.of("mfp:ingot"))
                .solverMode(SolverMode.SIMPLEX);
        assertTrue(history.record(plan));
        String full = PlanExport.export(plan);

        assertTrue(history.undo(plan));
        assertEquals(1, plan.targets().size(), "back to the plan it started as");
        assertTrue(plan.recipeChoices().isEmpty());
        assertTrue(plan.machineConfigs().isEmpty());
        assertTrue(plan.byproductFeeds());
        assertTrue(plan.autoResolve());
        assertEquals(Preferences.NO_DEFAULT_TIER, plan.defaultTier());
        assertEquals(SolverMode.AUTO, plan.solverMode());

        assertTrue(history.redo(plan));
        assertEquals(full, PlanExport.export(plan), "and forward to every one of them again");
    }

    @Test
    @DisplayName("depth is bounded, and the oldest step is the one that goes")
    void depthIsBounded() {
        Plan plan = plan();
        PlanHistory history = new PlanHistory(3);
        history.record(plan);
        for (int i = 1; i <= 10; i++) {
            plan.setTarget(0, new TargetOutput(INGOT, i));
            history.record(plan);
        }
        assertEquals(3, history.undoDepth());

        for (int i = 0; i < 3; i++) {
            assertTrue(history.undo(plan));
        }
        assertFalse(history.canUndo());
        // Ten edits, three kept: the plan goes back to the state before the eighth, not to its first.
        assertEquals(7.0, plan.targets().get(0).perSecond(), 1e-9);
    }

    @Test
    @DisplayName("nothing to undo changes nothing")
    void emptyHistoryIsSafe() {
        Plan plan = plan();
        PlanHistory history = started(plan);
        String before = PlanExport.export(plan);
        assertFalse(history.undo(plan));
        assertFalse(history.redo(plan));
        assertEquals(before, PlanExport.export(plan));
    }

    /**
     * The mutators, enumerated — so a gesture added later without undo is a visible omission.
     *
     * <p>M15's acceptance is written against this list rather than against a screen, because the
     * screens are where a new gesture appears and the model is where it can be checked. Every
     * mutator on {@link Plan} is either <b>captured</b> — it changes what {@link PlanCodec} writes,
     * so the history sees it — or <b>derived</b>, meaning it belongs to the lines a solve produced
     * and undoing it would be undoing the solver rather than the user.
     *
     * <p>Adding a mutator to {@code Plan} without adding it to one of the two lists fails here with
     * its name, which is exactly the moment to decide which it is.
     */
    @Test
    @DisplayName("every Plan mutator is either captured by the history or deliberately derived")
    void everyMutatorIsAccountedFor() {
        Set<String> captured = new TreeSet<>(Set.of(
                "name", "target", "setTarget", "removeTarget",
                "freeItem", "clearFreeItem",
                "rawMaterial", "clearRawMaterial",
                "preferItem", "clearPreferredItem",
                "chooseRecipe", "clearRecipeChoice",
                "consumeWith", "clearSink",
                "chooseMachine",
                "configureMachine", "clearMachineConfig",
                "blacklistRecipe", "unblacklistRecipe",
                "blockItem", "unblockItem",
                "allowItem", "clearAllowedItem",
                "defaultTier", "byproductFeeds", "tierCeiling", "autoResolve",
                "displayOrder", "clearDisplayOrder",
                "solverMode"));

        Set<String> derived = new TreeSet<>(Set.of(
                // Lines are output: the solve that follows a restore rebuilds them.
                "add", "clearLines", "removeLines",
                // The chooser's own observation about the lines it just built (§5.2).
                "deriveSolverMode",
                // Not an edit of this plan at all — they produce or become another state.
                "copy", "snapshot", "restoreFrom"));

        Set<String> found = new TreeSet<>();
        for (Method method : Plan.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            // Mutators are fluent and return the plan; the one exception returns how much it
            // removed. A read that happens to take an argument — `recipeChoice(key)` — is not one.
            if (method.getReturnType() == Plan.class || method.getName().equals("removeLines")) {
                found.add(method.getName());
            }
        }

        Set<String> unaccounted = new TreeSet<>(found);
        unaccounted.removeAll(captured);
        unaccounted.removeAll(derived);
        assertTrue(unaccounted.isEmpty(),
                "new Plan mutator(s) " + unaccounted + " - decide whether undo should see them "
                        + "(add the field to PlanCodec, which is what the history reads) or whether "
                        + "they are solver output, and list them here either way");

        Set<String> gone = new TreeSet<>(captured);
        gone.removeAll(found);
        assertTrue(gone.isEmpty(), "no longer on Plan: " + gone);
    }

    @Test
    @DisplayName("what a full history costs is measured, not guessed")
    void memoryCostIsMeasured() {
        Plan plan = plan();
        PlanHistory history = started(plan);
        for (int i = 1; i <= PlanHistory.DEFAULT_DEPTH * 2; i++) {
            plan.chooseRecipe(MfpKey.item("mfp", "item" + i), "mfp:recipe" + i);
            history.record(plan);
        }
        assertEquals(PlanHistory.DEFAULT_DEPTH, history.undoDepth());
        // A snapshot holds decisions, never lines, so the cost follows the pins rather than the size
        // of the plan. Asserted as a ceiling rather than a number so the test says something when it
        // fails: forty pins over twenty-one states is tens of kilobytes, not megabytes.
        assertTrue(history.measuredBytes() < 200_000,
                "a full history measured " + history.measuredBytes() + " bytes of state");
    }
}
