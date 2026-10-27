package qupath.ext.qpsc.controller;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.CameraOrientation;

/** The demo borrows the camera orientation preference and gives it back. */
class DemoOrientationTest {

    private CameraOrientation camera;
    private String saved;

    @BeforeEach
    void remember() {
        camera = QPPreferenceDialog.getCameraOrientationProperty();
        saved = PersistentPreferences.getStringPreference(DemoOrientation.SAVED_KEY, "");
        PersistentPreferences.setStringPreference(DemoOrientation.SAVED_KEY, "");
    }

    @AfterEach
    void restore() {
        QPPreferenceDialog.setCameraOrientationProperty(camera);
        PersistentPreferences.setStringPreference(DemoOrientation.SAVED_KEY, saved);
    }

    @Test
    void aFlippedCameraIsSetAsideForTheDemoAndRestoredAfter() {
        QPPreferenceDialog.setCameraOrientationProperty(CameraOrientation.FLIP_V);

        assertNotNull(DemoOrientation.adopt());
        assertEquals(CameraOrientation.NORMAL, QPPreferenceDialog.getCameraOrientationProperty());
        assertNull(DemoOrientation.adopt(), "a second connect changes nothing and keeps the first saved state");

        assertFalse(
                DemoOrientation.restoreIfLeaving("/x/qpsc-demo-microscope/config_Demo.yml"),
                "switching to the demo itself restores nothing");
        assertTrue(DemoOrientation.restoreIfLeaving("/configs/config_PPM.yml"));
        assertEquals(
                CameraOrientation.FLIP_V,
                QPPreferenceDialog.getCameraOrientationProperty(),
                "the real scope's camera orientation is back");
        assertFalse(DemoOrientation.restoreIfLeaving("/configs/config_PPM.yml"), "restored once only");
    }

    @Test
    void aNormalCameraIsLeftAlone() {
        QPPreferenceDialog.setCameraOrientationProperty(CameraOrientation.NORMAL);
        assertNull(DemoOrientation.adopt());
        assertFalse(DemoOrientation.restoreIfLeaving("/configs/config_PPM.yml"));
    }
}
