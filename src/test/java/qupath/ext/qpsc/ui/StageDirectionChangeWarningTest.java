package qupath.ext.qpsc.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.utilities.CameraOrientation;

/** The calibration result page warns before it changes the camera orientation. */
class StageDirectionChangeWarningTest {

    @Test
    void anUnchangedResultNeedsNoWarning() {
        assertNull(StageDirectionCalibrationDialog.changeWarning(CameraOrientation.NORMAL, CameraOrientation.NORMAL));
    }

    @Test
    void aHalfTurnNamesTheViewVersusTissueMistake() {
        // OWS3 2026-10-27: NORMAL and ROT_180 applied two minutes apart.
        String w = StageDirectionCalibrationDialog.changeWarning(CameraOrientation.NORMAL, CameraOrientation.ROT_180);
        assertNotNull(w);
        assertTrue(w.contains("180 degrees"), w);
        assertTrue(w.contains("TISSUE"), w);
        String back = StageDirectionCalibrationDialog.changeWarning(CameraOrientation.FLIP_V, CameraOrientation.FLIP_H);
        assertTrue(back.contains("180 degrees"), "FLIP_V to FLIP_H is also a half turn: " + back);
    }

    @Test
    void anyOtherChangeStillWarns() {
        String w = StageDirectionCalibrationDialog.changeWarning(CameraOrientation.NORMAL, CameraOrientation.FLIP_V);
        assertNotNull(w);
        assertFalse(w.contains("180 degrees"), w);
        assertTrue(w.contains("Cancel to keep NORMAL"), w);
    }
}
