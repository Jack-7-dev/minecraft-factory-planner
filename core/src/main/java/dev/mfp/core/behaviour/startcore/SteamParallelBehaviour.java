package dev.mfp.core.behaviour.startcore;

import dev.mfp.core.behaviour.BehaviourContext;
import dev.mfp.core.behaviour.gt.SteamMultiblockBehaviour;


/**
 * Star-Technology's steam multiblocks — the steam ore factory and high-pressure steam hammer.
 *
 * <p>{@code StarTSteamParallelMultiblockMachine} subclasses GregTech's steam multiblock and changes
 * exactly three things, so this subclasses {@link SteamMultiblockBehaviour} and changes the same
 * three: six parallels rather than the config's eight, a 1.05× duration rather than 1.5×, and steam
 * at 3 mB per EU rather than 2.
 *
 * <p>Matched on the modifier id, not the machine id, and that is the whole point of it: the pack
 * registers these machines from KubeJS under its own {@code kubejs:} namespace, so no list of
 * machine ids written here could name them. What it can rely on is that they were registered with
 * {@code StarTRecipeModifiers.START_STEAM_PARALLEL}, whose constant name the catalog recovers.
 */
public final class SteamParallelBehaviour extends SteamMultiblockBehaviour {

    /**
     * The lower-cased name of the {@code StarTRecipeModifiers} constant these machines declare.
     *
     * <p>Not a name MFP invented: {@code GtMachineCatalog} names a field-held modifier after its
     * field, so this string is the same rule the pack's machine definitions point at.
     */
    public static final String ID = "start_steam_parallel";

    /** Structure option holding the machine's maximum parallel count. */
    public static final String OPTION_MAX_PARALLELS = SteamMultiblockBehaviour.OPTION_MAX_PARALLELS;

    public SteamParallelBehaviour() {
        super(ID, 6, 1.05, 3.0);
    }

    @Override
    public boolean appliesTo(BehaviourContext context) {
        return context.hasModifier(ID);
    }
}
