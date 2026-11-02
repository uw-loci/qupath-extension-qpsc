package qupath.ext.qpsc.modality;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Which modalities answer yes to the hooks that shared code asks instead of testing names. */
class ModalityHooksTest {

    private static ModalityHandler handler(String modality) {
        return ModalityRegistry.getHandler(modality);
    }

    @Test
    void onlyLcPolScopeReconstructsFromItsBackground() {
        assertTrue(handler("lcpolscope").reconstructsFromBackground());
        assertTrue(handler("LCPolScope_20x").reconstructsFromBackground());
        assertTrue(handler("lcps").reconstructsFromBackground());
        for (String other : new String[] {"ppm", "bf", "fl", "shg", "bf_if", null, ""}) {
            assertFalse(handler(other).reconstructsFromBackground(), String.valueOf(other));
        }
    }

    @Test
    void onlyPpmUsesARotationStage() {
        assertTrue(handler("ppm").usesRotationStage());
        assertTrue(handler("PPM_20x").usesRotationStage());
        for (String other : new String[] {"lcpolscope", "bf", "fl", "shg", "bf_if", null, ""}) {
            assertFalse(handler(other).usesRotationStage(), String.valueOf(other));
        }
    }

    @Test
    void otherModalitiesAskNothingBeforeAcquiring() {
        for (String other : new String[] {"ppm", "bf", "fl", "shg", null}) {
            assertTrue(handler(other).confirmBeforeAcquiring(other));
            assertTrue(handler(other).confirmBatch(3));
        }
    }

    @Test
    void onlyLcPolScopeHasARetardanceCeiling() {
        assertTrue(handler("lcpolscope").retardanceCeilingNm().isPresent());
        assertTrue(handler("ppm").retardanceCeilingNm().isEmpty());
    }
}
