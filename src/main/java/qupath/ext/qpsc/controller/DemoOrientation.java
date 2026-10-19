package qupath.ext.qpsc.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.BundledScopeInstaller;
import qupath.ext.qpsc.utilities.CameraOrientation;

/**
 * The demo microscope's stage and camera orientation, set while the demo is active and put
 * back afterwards.
 *
 * <p>Stage polarity and camera orientation are QuPath preferences, set once per install by
 * Calibrate Directions on the real microscope. The simulated stage is not inverted and its
 * camera is not flipped, so on an install calibrated for an inverted stage the demo's arrows
 * ran backwards and its stitched mosaics came out mirrored top to bottom: the tile at the
 * larger stage Y was placed at the top while each tile's own rows run the other way, which
 * shows as a step at every seam (seen 2026-10-19 with INVERT_Y left from another scope).
 *
 * <p>Connecting to the demo saves the current values and sets the demo's; switching to any
 * other microscope restores them. Nothing is saved when they already match the demo.
 */
public final class DemoOrientation {

    private static final Logger logger = LoggerFactory.getLogger(DemoOrientation.class);

    /** "invertX,invertY,CAMERA" as it was before the demo took over; empty when nothing is held. */
    static final String SAVED_KEY = "qpsc.demo.savedOrientation";

    private DemoOrientation() {}

    /**
     * Sets the demo's orientation (stage not inverted, camera NORMAL), saving what it replaces.
     *
     * @return a sentence for the operator when something changed, else null
     */
    static String adopt() {
        boolean invertX = QPPreferenceDialog.getStageInvertedXProperty();
        boolean invertY = QPPreferenceDialog.getStageInvertedYProperty();
        CameraOrientation camera = QPPreferenceDialog.getCameraOrientationProperty();
        if (!invertX && !invertY && camera == CameraOrientation.NORMAL) {
            return null;
        }
        // Keep the first saved state: connecting to the demo twice must not save the demo's own.
        if (PersistentPreferences.getStringPreference(SAVED_KEY, "").isEmpty()) {
            PersistentPreferences.setStringPreference(SAVED_KEY, invertX + "," + invertY + "," + camera.name());
        }
        var polarity = QPPreferenceDialog.getStagePolarityProperty();
        QPPreferenceDialog.setStageInvertedX(false);
        QPPreferenceDialog.setStageInvertedY(false);
        QPPreferenceDialog.setCameraOrientationProperty(CameraOrientation.NORMAL);
        logger.info(
                "Demo microscope: stage polarity {} and camera {} replaced by the demo's (NORMAL, NORMAL); "
                        + "restored on switching to another microscope",
                polarity,
                camera);
        return "Stage direction and camera orientation are set for the demo while it is active, "
                + "and restored when you switch back to your microscope.";
    }

    /**
     * Restores the orientation saved by {@link #adopt()} when {@code newConfigPath} is not the
     * demo. Call after a switch has loaded the new config.
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
        String[] parts = saved.split(",");
        try {
            QPPreferenceDialog.setStageInvertedX(Boolean.parseBoolean(parts[0]));
            QPPreferenceDialog.setStageInvertedY(Boolean.parseBoolean(parts[1]));
            QPPreferenceDialog.setCameraOrientationProperty(CameraOrientation.valueOf(parts[2]));
            logger.info(
                    "Restored the stage polarity {} and camera orientation {} that were set before the demo",
                    QPPreferenceDialog.getStagePolarityProperty(),
                    parts[2]);
            return true;
        } catch (RuntimeException e) {
            logger.warn("Could not restore the pre-demo orientation '{}': {}", saved, e.getMessage());
            return false;
        } finally {
            PersistentPreferences.setStringPreference(SAVED_KEY, "");
        }
    }
}
