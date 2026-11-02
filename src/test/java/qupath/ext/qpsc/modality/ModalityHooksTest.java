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
        qupath.ext.qpsc.ModalityUis.registerAll();
        for (String other : new String[] {"ppm", "bf", "fl", "shg", null}) {
            assertTrue(ModalityRegistry.getUi(other).confirmBeforeAcquiring(other));
            assertTrue(ModalityRegistry.getUi(other).confirmBatch(3));
        }
    }

    @Test
    void eachModalityGetsItsOwnUiHalf() {
        qupath.ext.qpsc.ModalityUis.registerAll();
        assertInstanceOf(qupath.ext.qpsc.modality.ppm.ui.PPMModalityUi.class, ModalityRegistry.getUi("ppm_20x"));
        assertInstanceOf(
                qupath.ext.qpsc.modality.lcpolscope.ui.LCPolScopeModalityUi.class,
                ModalityRegistry.getUi("lcpolscope"));
        assertInstanceOf(qupath.ext.qpsc.modality.ui.BrightfieldModalityUi.class, ModalityRegistry.getUi("bf"));
        assertInstanceOf(qupath.ext.qpsc.modality.widefield.ui.WidefieldModalityUi.class, ModalityRegistry.getUi("fl"));
        // BF+IF's handler extends the widefield one, so it gets the widefield picker.
        assertInstanceOf(
                qupath.ext.qpsc.modality.widefield.ui.WidefieldModalityUi.class, ModalityRegistry.getUi("bf_if"));
        // Laser scanning has no panel of its own.
        assertSame(ModalityUi.NONE, ModalityRegistry.getUi("shg"));
        assertSame(ModalityUi.NONE, ModalityRegistry.getUi((ModalityHandler) null));
    }

    @Test
    void onlyLcPolScopeHasARetardanceCeiling() {
        assertTrue(handler("lcpolscope").retardanceCeilingNm().isPresent());
        assertTrue(handler("ppm").retardanceCeilingNm().isEmpty());
    }
}
