package qupath.ext.qpsc.modality.ppm.analysis;

import java.awt.image.BufferedImage;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.ppm.PPMPreferences;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.ext.qpsc.utilities.ImageMetadataManager.PPMAnalysisSet;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.fx.dialogs.Dialogs;

/**
 * Workflow for the PPM hue range filter overlay.
 *
 * <p>Sets up a {@link PPMHueRangeOverlay} on the current viewer and shows
 * a {@link PPMHueRangePanel} control window. The overlay highlights pixels
 * whose fiber angle falls within the user-specified range.</p>
 *
 * <p>The angle computation uses Java-side {@link PPMCalibration#hueToAngle(double)}
 * for interactive speed. The equivalent Python function is
 * {@code ppm_library.analysis.region_analysis.filter_angles_by_range()}.</p>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class PPMHueRangeWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(PPMHueRangeWorkflow.class);

    private static Stage controlWindow;
    private static PPMHueRangeOverlay activeOverlay;

    private PPMHueRangeWorkflow() {}

    /**
     * Main entry point. Shows the hue range filter control panel and sets up the overlay.
     */
    public static void run() {
        Platform.runLater(() -> {
            try {
                runOnFXThread();
            } catch (Exception e) {
                logger.error("Failed to run hue range filter workflow", e);
                Dialogs.showErrorMessage("PPM Hue Range Filter", "Error: " + e.getMessage());
            }
        });
    }

    private static void runOnFXThread() {
        QuPathGUI gui = QPEx.getQuPath();
        if (gui == null) {
            Dialogs.showErrorMessage("PPM Hue Range Filter", "QuPath is not available.");
            return;
        }

        ImageData<BufferedImage> imageData = gui.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage("PPM Hue Range Filter", "No image is open.");
            return;
        }

        ImageServer<BufferedImage> server = imageData.getServer();

        // Find calibration
        Project<BufferedImage> project = gui.getProject();
        ProjectImageEntry<BufferedImage> currentEntry =
                project != null ? project.getEntry(imageData) : null;

        PPMAnalysisSet analysisSet = null;
        if (currentEntry != null && project != null) {
            analysisSet = ImageMetadataManager.findPPMAnalysisSet(currentEntry, project);
        }

        String calibrationPath = null;
        if (analysisSet != null && analysisSet.hasCalibration()) {
            calibrationPath = analysisSet.calibrationPath;
        }
        if (calibrationPath == null && currentEntry != null) {
            calibrationPath = ImageMetadataManager.getPPMCalibration(currentEntry);
        }
        if (calibrationPath == null) {
            String activePath = PPMPreferences.getActiveCalibrationPath();
            if (activePath != null && !activePath.isEmpty()) {
                calibrationPath = activePath;
            }
        }
        if (calibrationPath == null) {
            Dialogs.showErrorMessage("PPM Hue Range Filter",
                    "No PPM calibration found. Run sunburst calibration first.");
            return;
        }

        // Load calibration
        PPMCalibration calibration;
        try {
            calibration = PPMCalibration.load(calibrationPath);
        } catch (Exception e) {
            Dialogs.showErrorMessage("PPM Hue Range Filter",
                    "Failed to load calibration: " + e.getMessage());
            return;
        }

        QuPathViewer viewer = gui.getViewer();

        // Remove any previous overlay
        cleanup();

        // Create overlay
        PPMHueRangeOverlay overlay = new PPMHueRangeOverlay(viewer);
        overlay.setCalibration(calibration);
        overlay.setServer(server);
        overlay.setOpacity(0.5);
        overlay.setActive(true);

        // Add to viewer
        viewer.getCustomOverlayLayers().add(overlay);
        activeOverlay = overlay;

        // Create control panel
        PPMHueRangePanel panel = new PPMHueRangePanel();

        // Wire stats updates
        overlay.setStatsListener((matching, total) ->
                Platform.runLater(() -> panel.updateStats(matching, total)));

        // Wire parameter changes
        panel.setOnParametersChanged(() -> {
            overlay.setAngleRange(panel.getAngleLow(), panel.getAngleHigh());
            overlay.setSaturationThreshold(panel.getSaturationThreshold());
            overlay.setValueThreshold(panel.getValueThreshold());
            overlay.setHighlightRGB(panel.getHighlightRGB());
            overlay.setOpacity(panel.getOverlayOpacity());
            overlay.recompute();
        });

        // Wire clear button
        panel.setOnClear(() -> {
            cleanup();
            viewer.repaint();
        });

        // Show control window
        if (controlWindow == null || !controlWindow.isShowing()) {
            controlWindow = new Stage();
            controlWindow.setTitle("PPM Hue Range Filter");
            controlWindow.initOwner(gui.getStage());
            controlWindow.setAlwaysOnTop(false);
            controlWindow.setOnCloseRequest(e -> cleanup());
        }
        controlWindow.setScene(new Scene(panel, 380, 420));
        controlWindow.show();
        controlWindow.toFront();

        // Trigger initial computation with default parameters
        overlay.setAngleRange(panel.getAngleLow(), panel.getAngleHigh());
        overlay.setSaturationThreshold(panel.getSaturationThreshold());
        overlay.setValueThreshold(panel.getValueThreshold());
        overlay.setHighlightRGB(panel.getHighlightRGB());
        overlay.setOpacity(panel.getOverlayOpacity());
        overlay.recompute();

        logger.info("PPM hue range filter overlay active on {}", server.getMetadata().getName());
    }

    /**
     * Removes the overlay and closes the control window.
     */
    private static void cleanup() {
        if (activeOverlay != null) {
            QuPathGUI gui = QPEx.getQuPath();
            if (gui != null) {
                QuPathViewer viewer = gui.getViewer();
                if (viewer != null) {
                    viewer.getCustomOverlayLayers().remove(activeOverlay);
                }
            }
            activeOverlay.dispose();
            activeOverlay = null;
        }
    }
}
