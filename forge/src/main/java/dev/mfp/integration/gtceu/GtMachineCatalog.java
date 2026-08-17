package dev.mfp.integration.gtceu;

import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.modifier.RecipeModifier;
import com.gregtechceu.gtceu.api.recipe.modifier.RecipeModifierList;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import dev.mfp.core.index.MfpRecipeSink;
import dev.mfp.core.model.MfpMachine;
import dev.mfp.core.model.MfpRecipe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the machine catalog from {@code GTRegistries.MACHINES}.
 *
 * <p>This is what answers "which machines can run this recipe, and at what tier", which the default
 * machine choice and every overclock calculation depend on.
 *
 * <p>The important part is {@code modifierIds}. Star-Technology's most significant multiblocks come
 * from {@code start_core}, not GregTech, and carry their own recipe modifiers — so a hardcoded
 * table of known GregTech machines would miss exactly the machines that matter most in the target
 * pack. MFP records the modifier identifiers verbatim and resolves their throughput semantics
 * elsewhere, which lets an unrecognised modifier degrade into a flagged assumption rather than a
 * confidently wrong number (plan P5).
 *
 * <p>Modifiers compose in order — GregTech folds a {@code RecipeModifierList} left, and the pack's
 * boosted turbine chains with {@code andThen} — so the list is flattened depth-first and its order
 * preserved.
 */
final class GtMachineCatalog {

    private GtMachineCatalog() {}

    /** Recipe type GregTech gives machines that have no real recipes; nothing to plan against. */
    private static final String DUMMY_TYPE = "gtceu:dummy";

    static int collect(MfpRecipeSink sink, String providerId) {
        int count = 0;
        for (MachineDefinition definition : GTRegistries.MACHINES) {
            if (definition == null) {
                continue;
            }
            try {
                MfpMachine machine = convert(definition, providerId);
                if (machine != null) {
                    sink.machine(machine);
                    count++;
                }
            } catch (RuntimeException e) {
                sink.skip(String.valueOf(definition.getId()), "machine conversion failed: " + e);
            }
        }
        return count;
    }

    private static MfpMachine convert(MachineDefinition definition, String providerId) {
        List<String> recipeTypeIds = recipeTypesOf(definition);
        if (recipeTypeIds.isEmpty()) {
            // Casings, hatches, cosmetic blocks — real machines, but not ones that run recipes.
            return null;
        }

        boolean multiblock = definition instanceof MultiblockMachineDefinition;
        int tier = tierOf(definition, multiblock);

        String name = definition.getLangValue();
        if (name == null || name.isBlank()) {
            name = definition.getName();
        }

        return new MfpMachine(
                String.valueOf(definition.getId()),
                name,
                tier,
                GtCompat.voltageOfTier(tier),
                recipeTypeIds,
                multiblock,
                modifierIdsOf(definition),
                providerId);
    }

    /**
     * The machine's voltage tier, or {@link MfpRecipe#NO_TIER} when it does not have one.
     *
     * <p>A multiblock's voltage comes from the energy hatch the player installs, not from its
     * definition, which leaves {@code tier} at its default of zero. Taking that at face value would
     * describe a large electrolyzer as an 8 EU/t ULV machine and overclock it accordingly — a
     * confidently wrong number, which is the failure mode this project cares most about avoiding
     * (plan P5). Reporting "no tier" instead leaves the answer to the hatch selection in
     * {@code MachineConfig.structureOptions}. A multiblock that does declare a tier keeps it.
     */
    private static int tierOf(MachineDefinition definition, boolean multiblock) {
        int tier = definition.getTier();
        if (multiblock && tier <= 0) {
            return MfpRecipe.NO_TIER;
        }
        return tier;
    }

    private static List<String> recipeTypesOf(MachineDefinition definition) {
        GTRecipeType[] types = definition.getRecipeTypes();
        if (types == null) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (GTRecipeType type : types) {
            if (type == null || type.registryName == null) {
                continue;
            }
            String id = type.registryName.toString();
            if (!DUMMY_TYPE.equals(id)) {
                ids.add(id);
            }
        }
        return new ArrayList<>(ids);
    }

    /** Flatten the machine's modifier into the ordered list of identifiers that apply to a recipe. */
    private static List<String> modifierIdsOf(MachineDefinition definition) {
        List<String> ids = new ArrayList<>();
        flatten(definition.getRecipeModifier(), ids, 0);
        return ids;
    }

    private static void flatten(RecipeModifier modifier, List<String> ids, int depth) {
        if (modifier == null || modifier == RecipeModifier.NO_MODIFIER || depth > 8) {
            return;
        }
        if (modifier instanceof RecipeModifierList list) {
            for (RecipeModifier child : list.modifiers()) {
                flatten(child, ids, depth + 1);
            }
            return;
        }
        ids.add(stableId(modifier));
    }

    /**
     * A modifier identifier that means the same thing on the next launch.
     *
     * <p>{@code RecipeModifier.getId()} defaults to the implementing class's simple name, which is
     * fine for the modifiers GregTech wraps in {@code IdentifiedRecipeModifier} — {@code ebf_oc},
     * {@code parallel_hatch} — but many machines pass a bare method reference instead
     * ({@code SimpleGeneratorMachine::recipeModifier}, and six of {@code start_core}'s seven). For
     * those the class is a synthetic lambda whose name embeds a per-JVM-run counter and address:
     * {@code gtmachineutils$$lambda$5759/0x0000016432d4c8a0}. Recording that verbatim would give
     * every saved plan and behaviour override a key that stops matching after a restart.
     *
     * <p>Most such method references are <em>held in a static field</em> — the whole of
     * {@code StarTRecipeModifiers} is one constant per rule — and the field's name is both stable
     * and the name the rule is actually known by, so {@link #constantName} recovers it. That is what
     * makes {@code START_STEAM_PARALLEL} distinguishable from {@code THREADING_MACHINE} when both
     * report the same declaring class; without it all six collapse together and no behaviour can
     * safely claim any of them.
     *
     * <p>When there is no such field — GregTech mostly passes {@code Machine::recipeModifier}
     * straight into the builder — the id falls back to {@code lambda:<declaring class>}, which is
     * stable but <em>ambiguous</em>. A behaviour must not identify those machines by modifier id
     * alone; matching on the machine id is the reliable route, and is why the behaviour layer looks
     * there first.
     */
    private static String stableId(RecipeModifier modifier) {
        String className = modifier.getClass().getName();

        // A modifier written in the pack's own scripts. Rhino compiles the arrow function to a proxy
        // class called "$proxy153", and the number is a per-launch counter — so recording it verbatim
        // would give every saved plan and behaviour override a key that stops matching after a
        // restart, which is the same trap as the lambda names above. There is nothing to read inside
        // it either way, so all of them collapse to one honest id.
        if (isGeneratedProxy(className)) {
            return "js:script-defined";
        }
        int lambda = className.indexOf("$$Lambda");
        if (lambda >= 0) {
            String declaring = className.substring(0, lambda);
            String constant = constantName(declaring, modifier);
            return constant != null ? constant : "lambda:" + declaring;
        }
        try {
            String id = modifier.getId();
            // Checked again, because getId() defaults to the simple class name and a proxy the test
            // above did not recognise arrives here wearing it. gtceu:steam_kiln reached the
            // behaviour table as "$proxy139" this way, and a modifier id carrying a per-launch
            // counter is worse than no id: it looks specific, it is unmatchable, and it changes
            // every boot. Whatever spelling the proxy machinery picks, it must not get through.
            if (id != null && !id.isBlank()) {
                return isGeneratedProxy(id) ? "js:script-defined" : id;
            }
        } catch (RuntimeException ignored) {
            // Fall through: the class name is still a usable, stable identifier.
        }
        return className;
    }

    /**
     * Whether a class or id name is a runtime-generated proxy, and therefore per-launch garbage.
     *
     * <p>Matched on the shape {@code $proxy<digits>} rather than on an exact prefix, because the
     * name is chosen by whichever machinery built the proxy and there are several: Rhino's own
     * adapters, {@code java.lang.reflect.Proxy} (which produces {@code jdk.proxy1.$Proxy139}, with a
     * capital P and a package in front), and the simple name of either. The one thing they share is
     * a counter, which is exactly what makes them unusable as identifiers — so the counter is what
     * this looks for.
     */
    private static boolean isGeneratedProxy(String name) {
        int proxy = name.toLowerCase(Locale.ROOT).lastIndexOf("$proxy");
        if (proxy < 0) {
            return false;
        }
        String tail = name.substring(proxy + "$proxy".length());
        if (tail.isEmpty()) {
            return false;
        }
        for (int i = 0; i < tail.length(); i++) {
            if (!Character.isDigit(tail.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Resolved constant names per declaring class, since one class holds many machines' modifiers. */
    private static final Map<String, Map<RecipeModifier, String>> CONSTANTS = new HashMap<>();

    /**
     * The lower-cased name of the static field holding this modifier, or null if it is not in one.
     *
     * <p>Lower-cased so a field-held rule and one GregTech wrapped in an
     * {@code IdentifiedRecipeModifier} are named the same way: {@code ABSOLUTE_PARALLEL} and
     * {@code "absolute_parallel"} are the same rule written two ways, and a behaviour should not
     * have to know which form its pack happened to use.
     *
     * <p>Identity comparison, not equals: two method references to the same method are distinct
     * objects that need not compare equal, but the constant a machine was registered with is the
     * very object held in the field.
     */
    private static String constantName(String declaringClass, RecipeModifier modifier) {
        Map<RecipeModifier, String> byInstance =
                CONSTANTS.computeIfAbsent(declaringClass, GtMachineCatalog::scanConstants);
        return byInstance.get(modifier);
    }

    private static Map<RecipeModifier, String> scanConstants(String declaringClass) {
        Map<RecipeModifier, String> found = new IdentityHashMap<>();
        try {
            Class<?> owner = Class.forName(declaringClass, false, GtMachineCatalog.class.getClassLoader());
            for (Field field : owner.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        || !RecipeModifier.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof RecipeModifier held) {
                    found.putIfAbsent(held, field.getName().toLowerCase(Locale.ROOT));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // A class that cannot be read leaves every modifier it declares ambiguous, which is
            // exactly where this started. One unreadable class must not cost the whole catalog.
        }
        return found;
    }
}
