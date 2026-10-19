package qupath.ext.qpsc.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager.ObjectiveMmPreset;

/** The turret confirmation's text says what will move and what declining means. */
class ObjectivePresetConfirmDialogTest {

    @Test
    void theMessageNamesTheMoveAndTheConsequenceOfDeclining() {
        String m = ObjectivePresetConfirmDialog.message(
                "CAMM_OBJ_20X", new ObjectiveMmPreset("Objective", "20X", true), "10X");
        assertTrue(m.contains("preset '20X' of group 'Objective' (now '10X')"), m);
        assertTrue(m.contains("MOVES THE OBJECTIVE TURRET"), m);
        assertTrue(m.contains("Don't move leaves the turret where it is"), m);
        assertTrue(ObjectivePresetConfirmDialog.message("X", new ObjectiveMmPreset("O", "20X", true), "")
                .contains("(now no matching preset)"));
    }

    @Test
    void clampKeepsAWindowOnScreen() {
        assertEquals(0, ObjectivePresetConfirmDialog.clamp(-50, 0, 1000));
        assertEquals(1000, ObjectivePresetConfirmDialog.clamp(1200, 0, 1000));
        assertEquals(0, ObjectivePresetConfirmDialog.clamp(10, 0, -5), "taller than the screen: pin to the top");
    }
}
