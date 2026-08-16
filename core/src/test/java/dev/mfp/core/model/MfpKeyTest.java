package dev.mfp.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MfpKeyTest {

    @Test
    void parseSplitsNamespaceAndPath() {
        MfpKey key = MfpKey.parse("gtceu:copper_ingot", MfpKey.Kind.ITEM);
        assertEquals("gtceu", key.namespace());
        assertEquals("copper_ingot", key.path());
        assertEquals("gtceu:copper_ingot", key.id());
    }

    @Test
    void parseDefaultsNamespaceToMinecraft() {
        assertEquals("minecraft", MfpKey.parse("stone", MfpKey.Kind.ITEM).namespace());
    }

    @Test
    void keysAreCaseInsensitive() {
        assertEquals(MfpKey.item("GTCEu", "Copper_Ingot"), MfpKey.item("gtceu", "copper_ingot"));
    }

    /**
     * The variant is what keeps GregTech programmed circuits apart. If these two ever compare equal,
     * every circuit-differentiated recipe becomes ambiguous.
     */
    @Test
    void variantDistinguishesOtherwiseIdenticalItems() {
        MfpKey circuit4 = MfpKey.item("gtceu", "programmed_circuit", "cfg4");
        MfpKey circuit7 = MfpKey.item("gtceu", "programmed_circuit", "cfg7");
        assertNotEquals(circuit4, circuit7);
        assertEquals(circuit4.withoutVariant(), circuit7.withoutVariant());
        assertNull(circuit4.withoutVariant().variant());
    }

    @Test
    void energyAndComputationArePseudo() {
        assertTrue(MfpKey.EU.isPseudo());
        assertTrue(MfpKey.CWU.isPseudo());
        assertFalse(MfpKey.item("minecraft", "stone").isPseudo());
    }

    @Test
    void kindSeparatesItemsFromFluids() {
        assertNotEquals(MfpKey.item("gtceu", "water"), MfpKey.fluid("gtceu", "water"));
    }

    @Test
    void emptyVariantIsRejectedSoThereIsOneRepresentationOfPlain() {
        assertThrows(IllegalArgumentException.class, () -> MfpKey.item("gtceu", "thing", ""));
    }

    @Test
    void toStringShowsVariant() {
        assertEquals("gtceu:programmed_circuit#cfg4",
                MfpKey.item("gtceu", "programmed_circuit", "cfg4").toString());
        assertEquals("mfp:eu", MfpKey.EU.toString());
    }
}
