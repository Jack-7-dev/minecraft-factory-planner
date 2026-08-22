package dev.mfp.integration.gtceu;

import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.pattern.MultiblockShapeInfo;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.lowdragmc.lowdraglib.utils.BlockInfo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a multiblock's <em>structure</em> — the blocks you have to place to build it — out of the
 * machine definition, with no client and no recipe viewer (M16).
 *
 * <p><b>Where the number comes from.</b> The user's framing of this spike was "EMI gets these
 * numbers from somewhere, find out where". It does:
 * {@code MultiblockInfoEmiRecipe} → {@code PatternPreviewWidget.getPatternWidget(definition)} →
 * {@code MultiblockMachineDefinition.getMatchingShapes()}, and the widget's own parts list is
 * nothing more cunning than a count over {@code MultiblockShapeInfo.getBlocks()}. JEI and REI reach
 * the same widget by the same call. So the viewer is not a source of structure data at all — it is a
 * renderer of it, and the data underneath it is on the definition.
 *
 * <p><b>And the definition is on both sides.</b> {@code getMatchingShapes} either returns the shapes
 * the machine's builder declared, or falls back to walking {@code BlockPattern.getPreview} over
 * every allowed aisle repetition. Neither is annotated {@code @OnlyIn(Dist.CLIENT)}, neither takes a
 * {@code Level}, and machines are registered in common code — the dummy world the widget builds is
 * for rendering and for {@code getCloneItemStack}, not for working out what the blocks are. That is
 * the whole answer to the question the spike was scheduled to ask: <b>this is readable headless</b>,
 * so it can be a solver input rather than a decoration beside {@code MachineStacks}.
 *
 * <p><b>What a shape actually is, and what it is not.</b> A structure is a set of
 * <em>predicates</em>, and a shape is <b>one legal answer to them, not the only one</b>. Where the
 * fallback walk is used it takes the first candidate each predicate offers, so a predicate that
 * accepts any coil yields cupronickel because cupronickel is first. This is therefore an accurate
 * parts list for <em>a</em> valid build and an approximate one for the build the player has in mind;
 * a caller that prices it must say so, and that is why the shape index and count are returned rather
 * than page zero being quietly called "the" structure.
 *
 * <p>The pages are not one thing either, because the two sources of shapes enumerate different
 * choices. A machine whose builder declares {@code shapeInfos} explicitly gets a page per
 * <em>material</em> — the electric blast furnace's eight pages are its eight coil tiers, every one
 * of them 3x4x3. A machine that falls through to {@code BlockPattern.getPreview} gets a page per
 * <em>size</em>, one per allowed aisle repetition. Nothing on the definition says which, which is
 * worth knowing before a caller reads page zero as "the small one".
 *
 * <p><b>The hatches are placeholders.</b> A hatch predicate is filled with its first candidate, which
 * is the lowest tier it accepts — the blast furnace above reads {@code lv_energy_input_hatch}
 * whatever the recipe needs. MFP decides hatch tiers itself in {@code MachineConfig}, so a caller
 * pricing a structure must substitute its own choice for these rather than believe them. The casings,
 * coils and frames are the part this is authoritative about, and they are the bulk of the cost.
 *
 * <p>Blocks with no item form — air, and the fluid the preview uses to fill a tank — are counted
 * separately rather than dropped silently, so a caller can tell an empty cell from a missing one.
 *
 * <p>Loadable only when {@code MfpMod.isGregTechLoaded()}, like everything else in this package.
 */
public final class GtStructure {

    private GtStructure() {}

    /** One kind of block in a structure: the item you would put in a crafting grid, and how many. */
    public record Part(String itemId, String name, int count) {}

    /**
     * One shape of one multiblock. {@code shapeIndex} of {@code shapeCount} because a multiblock
     * usually has several, exactly as the viewer's "P:" button does — a page per coil material or a
     * page per size, depending on which of the two sources the shapes came from.
     */
    public record Shape(String machineId, int shapeIndex, int shapeCount,
                        int sizeX, int sizeY, int sizeZ,
                        List<Part> parts, int blockCount, int emptyCount) {}

    /** Every multiblock machine id in the game, sorted. Answers "what can I ask about". */
    public static List<String> multiblockIds() {
        List<String> ids = new ArrayList<>();
        for (MachineDefinition definition : GTRegistries.MACHINES) {
            if (definition instanceof MultiblockMachineDefinition) {
                ids.add(String.valueOf(definition.getId()));
            }
        }
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    /** How many shapes this machine has, or {@code 0} if it is not a multiblock at all. */
    public static int shapeCount(String machineId) {
        MultiblockMachineDefinition definition = definitionOf(machineId);
        return definition == null ? 0 : definition.getMatchingShapes().size();
    }

    /**
     * The parts list of one shape, or {@code null} if the id is not a multiblock or the page does
     * not exist.
     *
     * @throws RuntimeException whatever the definition's own shape supplier throws; a machine whose
     *         structure cannot be built is a finding, not something to swallow.
     */
    public static Shape read(String machineId, int shapeIndex) {
        MultiblockMachineDefinition definition = definitionOf(machineId);
        if (definition == null) {
            return null;
        }
        List<MultiblockShapeInfo> shapes = definition.getMatchingShapes();
        if (shapeIndex < 0 || shapeIndex >= shapes.size()) {
            return null;
        }

        BlockInfo[][][] blocks = shapes.get(shapeIndex).getBlocks();
        Map<String, Part> counts = new LinkedHashMap<>();
        int total = 0;
        int empty = 0;

        for (BlockInfo[][] aisle : blocks) {
            for (BlockInfo[] column : aisle) {
                for (BlockInfo info : column) {
                    total++;
                    BlockState state = info == null ? null : info.getBlockState();
                    Item item = state == null ? Items.AIR : state.getBlock().asItem();
                    if (item == Items.AIR) {
                        empty++;
                        continue;
                    }
                    ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                    String key = String.valueOf(id);
                    Part existing = counts.get(key);
                    counts.put(key, existing == null
                            ? new Part(key, item.getDescription().getString(), 1)
                            : new Part(key, existing.name(), existing.count() + 1));
                }
            }
        }

        List<Part> parts = new ArrayList<>(counts.values());
        // Commonest first: a structure is mostly casing, and the interesting parts are the tail.
        parts.sort(Comparator.comparingInt(Part::count).reversed().thenComparing(Part::itemId));

        return new Shape(String.valueOf(definition.getId()), shapeIndex, shapes.size(),
                blocks.length,
                blocks.length == 0 ? 0 : blocks[0].length,
                blocks.length == 0 || blocks[0].length == 0 ? 0 : blocks[0][0].length,
                parts, total, empty);
    }

    private static MultiblockMachineDefinition definitionOf(String machineId) {
        ResourceLocation id = ResourceLocation.tryParse(machineId);
        if (id == null) {
            return null;
        }
        MachineDefinition definition = GTRegistries.MACHINES.get(id);
        return definition instanceof MultiblockMachineDefinition multi ? multi : null;
    }
}
