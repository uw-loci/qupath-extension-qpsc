package qupath.ext.qpsc.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.BundledScopeInstaller;
import qupath.ext.qpsc.utilities.CameraOrientation;

/**
 * The demo microscope's camera orientation, set while the demo is active and put back
 * afterwards.
 *
 * <p>The camera orientation is a QuPath preference, set by Calibrate Directions on the real
 * microscope. The simulated camera is not flipped, so on an install calibrated for a flipped
 * camera the demo's arrows ran backwards and its mosaics came out mirrored (seen 2026-10-19).
 * The stage polarity used to be swapped here too; it is now {@code stage.polarity} in each
 * microscope's config, and the demo's config says {@code NORMAL}, so a switch carries it.
 *
 * <p>Connecting to the demo saves the current orientation and sets NORMAL; switching to any
 * other microscope restores it. Nothing is saved when it is already NORMAL.
 */
public final class DemoOrientation {

    private static final Logger logger = LoggerFactory.getLogger(DemoOrientation.class);

    /** The camera orientation name before the demo took over; empty when nothing is held. */
    static final String SAVED_KEY = "qpsc.demo.savedOrientation";

    private DemoOrientation() {}

    /**
     * Sets the demo's camera orientation (NORMAL), saving what it replaces.
     *
     * @return a sentence for the operator when something changed, else null
     */
    static String adopt() {
        CameraOrientation camera = QPPreferenceDialog.getCameraOrientationProperty();
        if (camera == CameraOrientation.NORMAL) {
            return null;
        }
        // Keep the first saved state: connecting to the demo twice must not save the demo's own.
        if (PersistentPreferences.getStringPreference(SAVED_KEY, "").isEmpty()) {
            PersistentPreferences.setStringPreference(SAVED_KEY, camera.name());
        }
        QPPreferenceDialog.setCameraOrientationProperty(CameraOrientation.NORMAL);
        logger.info(
                "Demo microscope: camera orientation {} replaced by the demo's (NORMAL); restored on switching "
                        + "to another microscope",
                camera);
        return "The camera orientation is set for the demo while it is active, "
                + "and restored when you switch back to your microscope.";
    }

    /**
     * Restores the camera orientation saved by {@link #adopt()} when {@code newConfigPath} is not
     * the demo. Call after a switch has loaded the new config.
     *
     * @return true when something was restored
     */
    public static boolean restoreIfLeaving(String newConfigPath) {
        if (BundledScopeInstaller.isBundleConfigPath(BundledScopeInstaller.Bundle.DEMO, newConfigPath)) {
            return false;
        }
        String saved = PersistentPreferences.getStringPreference(SAVED_KEY, "");
        if (saved.isEmpty()) {
            return false;
        }
        try {
            QPPreferenceDialog.setCameraOrientationProperty(CameraOrientation.valueOf(saved.trim()));
            logger.info("Restored the camera orientation {} that was set before the demo", saved);
            return true;
        } catch (RuntimeException e) {
            logger.warn("Could not restore the pre-demo camera orientation '{}': {}", saved, e.getMessage());
            return false;
        } finally {
            PersistentPreferences.setStringPreference(SAVED_KEY, "");
        }
    }
}
