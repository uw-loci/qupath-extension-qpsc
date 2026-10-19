package qupath.ext.qpsc.controller;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.CameraOrientation;

/** The demo borrows the orientation preferences and gives them back. */
class DemoOrientationTest {

    private boolean invertX;
    private boolean invertY;
    private CameraOrientation camera;
    private String saved;

    @BeforeEach
    void remember() {
        invertX = QPPreferenceDialog.getStageInvertedXProperty();
        invertY = QPPreferenceDialog.getStageInvertedYProperty();
        camera = QPPreferenceDialog.getCameraOrientationProperty();
        saved = PersistentPreferences.getStringPreference(DemoOrientation.SAVED_KEY, "");
        PersistentPreferences.setStringPreference(DemoOrientation.SAVED_KEY, "");
    }

    @AfterEach
    void restore() {
        QPPreferenceDialog.setStageInvertedX(invertX);
        QPPreferenceDialog.setStageInvertedY(invertY);
        QPPreferenceDialog.setCameraOrientationProperty(camera);
        PersistentPreferences.setStringPreference(DemoOrientation.SAVED_KEY, saved);
    }

    @Test
    void anInvertedStageIsSetAsideForTheDemoAndRestoredAfter() {
        QPPreferenceDialog.setStageInvertedX(false);
        QPPreferenceDialog.setStageInvertedY(true);
        QPPreferenceDialog.setCameraOrientationProperty(CameraOrientation.NORMAL);

        assertNotNull(DemoOrientation.adopt());
        assertFalse(QPPreferenceDialog.getStageInvertedYProperty(), "the demo's stage is not inverted");
        assertNull(DemoOrientation.adopt(), "a second connect changes nothing and keeps the first saved state");

        assertFalse(
                DemoOrientation.restoreIfLeaving("/x/qpsc-demo-microscope/config_Demo.yml"),
                "switching to the demo itself restores nothing");
        assertTrue(DemoOrientation.restoreIfLeaving("/configs/config_PPM.yml"));
        assertTrue(QPPreferenceDialog.getStageInvertedYProperty(), "the real scope's polarity is back");
        assertFalse(DemoOrientation.restoreIfLeaving("/configs/config_PPM.yml"), "restored once only");
    }

    @Test
    void aMatchingInstallIsLeftAlone() {
        QPPreferenceDialog.setStageInvertedX(false);
        QPPreferenceDialog.setStageInvertedY(false);
        QPPreferenceDialog.setCameraOrientationProperty(CameraOrientation.NORMAL);
        assertNull(DemoOrientation.adopt());
        assertFalse(DemoOrientation.restoreIfLeaving("/configs/config_PPM.yml"));
    }
}
