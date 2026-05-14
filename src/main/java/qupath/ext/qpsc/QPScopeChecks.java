package qupath.ext.qpsc;

import java.util.*;
import java.util.concurrent.FutureTask;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.ObjectiveUtils;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

/**
 * QPScopeChecks contains helper functions to verify that the QuPath environment is ready
 * for launching the QP Scope extension. It performs a series of checks:
 *
 * 1. Checks whether a project is open in QuPath.
 * 2. Checks that the expected hardware (as defined by current preferences) is accessible.
 * 3. Checks that QuPath is in the correct state.
 * 4. Validates the microscope configuration file structure and content.
 *
 * For now, the hardware and state checks are dummy checks (always returning true), but they are
 * structured so that you can later add real logic. If any check fails, a warning dialog is shown.
 */
public class QPScopeChecks {

    private static final Logger logger = LoggerFactory.getLogger(QPScopeChecks.class);

    /**
     * Checks all the necessary conditions for the extension to start.
     *
     * @return true if all conditions are met; otherwise false.
     */
    public static boolean checkEnvironment() {
        // 1. Check if a project is open in QuPath.
        if (QuPathGUI.getInstance() == null || QuPathGUI.getInstance().getProject() == null) {
            Dialogs.showWarningNotification(
                    "No Project Open", "You must open a project in QuPath before launching the extension.");
            return false;
        }

        // 2. Check if the expected hardware is accessible (dummy check for now).
        if (!checkHardwareAccessible()) {
            Dialogs.showWarningNotification(
                    "Hardware Not Accessible",
                    "The hardware expected by the current preferences is not accessible.\n"
                            + "Please verify your hardware connection.");
            return false;
        }

        // 3. Check if QuPath is in the correct state (dummy check for now).
        if (!checkQuPathState()) {
            Dialogs.showWarningNotification(
                    "Incorrect QuPath State",
                    "QuPath is not in the expected state.\n"
                            + "Please ensure QuPath is properly configured before launching the extension.");
            return false;
        }

        // 4. Validate microscope configuration
        if (!validateMicroscopeConfig()) {
            return false; // Error dialogs are shown inside validateMicroscopeConfig
        }

        // 5. Validate stage limits configuration
        if (!validateStageLimitsConfig()) {
            return false; // Error dialogs are shown inside validateStageLimitsConfig
        }

        // All checks passed.
        return true;
    }

    /**
     * Validate the YAML config and return true if all required keys are present.
     */
    public static boolean validateMicroscopeConfig() {
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);

            // Use the comprehensive validation method from MicroscopeConfigManager
            List<String> errors = mgr.validateConfiguration();

            if (!errors.isEmpty()) {
                logger.error("Configuration validation failed: {}", errors);
                String errorMessage = "Configuration validation errors:\n\n- " + String.join("\n- ", errors);
                Dialogs.showWarningNotification("Configuration Errors", errorMessage);
                return false;
            }

            logger.info("Configuration validation passed");
            return true;
        } catch (Exception e) {
            logger.error("Error during configuration validation", e);
            Dialogs.showErrorNotification(
                    "Configuration Validation Error", "Failed to validate configuration: " + e.getMessage());
            return false;
        }
    }

    /**
     * Validates stage limits configuration.
     * Since comprehensive validation is now handled by validateConfiguration(),
     * this method is redundant and simply delegates to validateMicroscopeConfig().
     *
     * @return true if stage limits configuration is adequate
     */
    public static boolean validateStageLimitsConfig() {
        // Delegate to comprehensive validation
        return validateMicroscopeConfig();
    }

    /** Maximum allowed fractional difference between wizard and MM pixel size before workflow cancels. */
    private static final double PIXEL_SIZE_MISMATCH_THRESHOLD = 0.05;

    /** Maximum allowed fractional difference between live frame dims and configured sensor dims. */
    private static final double CAMERA_ROI_MISMATCH_THRESHOLD = 0.05;

    /**
     * Validates that the live camera frame dimensions match the configured sensor dimensions for
     * the given detector. A persistent ROI crop -- typically left behind by a prior streaming-AF
     * call that didn't restore the camera -- silently halves (or otherwise reduces) every captured
     * tile's physical FoV. The tile config still gets planned for the full-sensor FoV, so the
     * stitched mosaic ends up with gaps between tiles and alignment lands at the wrong stage X/Y.
     *
     * <p>If the live frame dimensions disagree with the configured sensor dimensions by more than
     * {@link #CAMERA_ROI_MISMATCH_THRESHOLD} (5%) in either axis, this method shows a warning
     * dialog explaining what to fix in MicroManager and returns false. The caller must abort the
     * workflow.
     *
     * @param detector the detector identifier (e.g., "LOCI_DETECTOR_JAI_001")
     * @return true if MM is unreachable, the detector has no configured dims, or the live frame
     *         matches the configured sensor within threshold; false if a mismatch was detected
     */
    public static boolean validateCameraRoi(String detector) {
        return validateCameraRoi(detector, null);
    }

    /**
     * Same as {@link #validateCameraRoi(String)} but routes the mismatch dialog through
     * {@link UIFunctions#showAlertOverParent} when {@code parentStage} is non-null. Use this
     * overload when the caller lives inside an always-on-top stage (e.g. the Acquisition Wizard);
     * a plain {@code showAndWait} alert sinks behind such a stage while still holding modal focus.
     */
    public static boolean validateCameraRoi(String detector, Window parentStage) {
        if (detector == null || detector.isEmpty()) {
            return true;
        }

        int[] configDims;
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
            configDims = mgr.getDetectorDimensions(detector);
        } catch (Exception e) {
            logger.warn("Could not read detector dims for ROI validation: {}", e.getMessage());
            return true;
        }
        if (configDims == null || configDims.length < 2 || configDims[0] <= 0 || configDims[1] <= 0) {
            logger.debug("No detector dims configured for '{}'; skipping ROI validation", detector);
            return true;
        }

        int liveW;
        int liveH;
        try {
            MicroscopeController controller = MicroscopeController.getInstance();
            if (controller == null || !controller.isConnected()) {
                logger.debug("Microscope not connected; skipping ROI validation");
                return true;
            }
            // getFrame() triggers a real camera snap. ~100-300ms cost per gate is acceptable
            // because the gate fires once per workflow invocation, not per tile.
            qupath.ext.qpsc.ui.liveviewer.FrameData frame =
                    controller.getSocketClient().getFrame();
            liveW = frame.width();
            liveH = frame.height();
        } catch (Exception e) {
            logger.warn("Could not query live frame dims for ROI validation: {}", e.getMessage());
            return true;
        }

        int configW = configDims[0];
        int configH = configDims[1];
        double diffX = Math.abs((double) configW - liveW) / configW;
        double diffY = Math.abs((double) configH - liveH) / configH;

        logger.info(
                "Camera ROI check: detector={}, config={}x{}, live={}x{}, diff=({}%, {}%)",
                detector,
                configW,
                configH,
                liveW,
                liveH,
                String.format("%.1f", diffX * 100),
                String.format("%.1f", diffY * 100));

        if (diffX <= CAMERA_ROI_MISMATCH_THRESHOLD && diffY <= CAMERA_ROI_MISMATCH_THRESHOLD) {
            return true;
        }

        StringBuilder body = new StringBuilder();
        body.append(String.format(
                "The live camera frame dimensions do not match the configured sensor dimensions "
                        + "(threshold is %.0f%%).%n%n",
                CAMERA_ROI_MISMATCH_THRESHOLD * 100));
        body.append("Configured sensor (from microscope resources YAML):\n");
        body.append(String.format("  Detector:    %s%n", detector));
        body.append(String.format("  Dimensions:  %d x %d px%n%n", configW, configH));
        body.append("Live camera (queried from MicroManager):\n");
        body.append(String.format("  Dimensions:  %d x %d px%n", liveW, liveH));
        body.append(String.format("  Difference:  %.1f%% width, %.1f%% height%n%n", diffX * 100, diffY * 100));
        body.append("The camera is cropped to a sub-region of the full sensor. This typically ");
        body.append("happens when a prior streaming Autofocus call cropped the ROI and did not ");
        body.append("restore it on exit -- the cropped state persists across sessions because ");
        body.append("MicroManager remembers the last ROI.\n\n");
        body.append("If you continue, every acquired tile will only cover part of the planned ");
        body.append("field of view. The stitched mosaic will have empty space between tiles and ");
        body.append("alignment will land at the wrong stage position.\n\n");
        body.append("To fix:\n");
        body.append("  1. Open MicroManager.\n");
        body.append("  2. Find the camera's ROI / SubROI property (or use the 'Clear ROI' / ");
        body.append("'Reset ROI' button in the toolbar).\n");
        body.append(String.format("  3. Set ROI to full sensor (%d x %d) or clear it.%n", configW, configH));
        body.append("  4. Restart this workflow.\n\n");
        body.append("This workflow has been cancelled.");

        showMismatchDialog(
                "Camera ROI Mismatch -- Workflow Cancelled",
                "MicroManager has the camera cropped to a sub-region of the full sensor.",
                body.toString(),
                parentStage);

        return false;
    }

    /**
     * Validates that the QPSC config pixel size matches MicroManager's active pixel size calibration.
     * If they differ by more than {@link #PIXEL_SIZE_MISMATCH_THRESHOLD} (5%), shows a warning dialog
     * with the wizard selection, MM-reported pixel size, the closest matching configured objective
     * for the MM value, and the diff percent, then returns false to cancel the workflow. The user
     * is expected to fix the mismatch (in MicroManager or in the wizard dropdown) and restart.
     *
     * @param objective  the QPSC objective identifier (e.g., "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001")
     * @param detector   the detector identifier
     * @param modality   the modality name
     * @param configPixelSize the pixel size from QPSC config (um/pixel)
     * @return true if MM is unreachable, returns no calibration, or matches within threshold;
     *         false if a mismatch was detected (workflow must abort)
     */
    public static boolean validateObjectivePixelSize(
            String objective, String detector, String modality, double configPixelSize) {
        return validateObjectivePixelSize(objective, detector, modality, configPixelSize, null);
    }

    /**
     * Same as {@link #validateObjectivePixelSize(String, String, String, double)} but routes the
     * mismatch dialog through {@link UIFunctions#showAlertOverParent} when {@code parentStage} is
     * non-null. Use this overload when the caller lives inside an always-on-top stage (e.g. the
     * Acquisition Wizard); a plain {@code showAndWait} alert sinks behind such a stage while still
     * holding modal focus.
     */
    public static boolean validateObjectivePixelSize(
            String objective, String detector, String modality, double configPixelSize, Window parentStage) {

        double mmPixelSize;
        try {
            MicroscopeController controller = MicroscopeController.getInstance();
            if (controller == null || !controller.isConnected()) {
                logger.debug("Microscope not connected, skipping pixel size validation");
                return true;
            }
            mmPixelSize = controller.getSocketClient().getMicroscopePixelSize();
        } catch (Exception e) {
            logger.warn("Could not query MicroManager pixel size, skipping validation: {}", e.getMessage());
            return true;
        }

        if (mmPixelSize <= 0.0) {
            logger.info("MicroManager returned no pixel size calibration (0.0), skipping validation");
            return true;
        }

        double ratio = Math.abs(configPixelSize - mmPixelSize) / configPixelSize;
        String magnification = ObjectiveUtils.extractMagnification(objective);
        String displayMag = (magnification != null) ? magnification : objective;

        logger.info(
                "Pixel size check: config={} um ({}), MicroManager={} um, diff={} percent",
                configPixelSize,
                displayMag,
                mmPixelSize,
                String.format("%.1f", ratio * 100));

        if (ratio <= PIXEL_SIZE_MISMATCH_THRESHOLD) {
            return true;
        }

        // Look up the closest matching objective for MM's reported pixel size, so the user can see
        // what objective is probably actually active in MM.
        String inferredId = null;
        String inferredMag = null;
        Double inferredPixelSize = null;
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
            double bestDiff = Double.MAX_VALUE;
            for (Map<String, Object> obj : mgr.getHardwareObjectives()) {
                String id = (String) obj.get("id");
                if (id == null) continue;
                Double px = mgr.getHardwarePixelSize(id, detector);
                if (px == null || px <= 0.0) continue;
                double diff = Math.abs(px - mmPixelSize);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    inferredId = id;
                    inferredPixelSize = px;
                    inferredMag = ObjectiveUtils.extractMagnification(id);
                }
            }
        } catch (Exception e) {
            logger.warn("Could not infer MM objective from pixel size: {}", e.getMessage());
        }

        StringBuilder body = new StringBuilder();
        body.append(String.format(
                "The pixel size reported by MicroManager differs from the wizard's selected objective by %.1f%% "
                        + "(threshold is %.0f%%).%n%n",
                ratio * 100, PIXEL_SIZE_MISMATCH_THRESHOLD * 100));
        body.append("Wizard selection:\n");
        body.append(String.format("  Objective:           %s%n", objective));
        if (magnification != null) {
            body.append(String.format("  Magnification:       %sx%n", magnification));
        }
        body.append(String.format("  Modality:            %s%n", modality));
        body.append(String.format("  Detector:            %s%n", detector));
        body.append(String.format("  Expected pixel size: %.4f um/px%n%n", configPixelSize));
        body.append("MicroManager (live):\n");
        body.append(String.format("  Reported pixel size: %.4f um/px%n", mmPixelSize));
        if (inferredId != null && inferredPixelSize != null) {
            body.append(String.format(
                    "  Closest configured:  %s%s (%.4f um/px)%n",
                    inferredId, inferredMag != null ? " (" + inferredMag + "x)" : "", inferredPixelSize));
        }
        body.append(String.format("  Difference:          %.1f%%%n%n", ratio * 100));
        body.append("Tile spacing for this acquisition will be planned for the wizard's selected objective. ");
        body.append("If the actual objective in MicroManager is different, every tile will be over- or ");
        body.append("under-sampled and the resulting mosaic will not be usable.\n\n");
        body.append("To fix: change the objective in MicroManager to match the wizard, OR change the ");
        body.append("wizard's objective dropdown to match what is physically on the microscope, then ");
        body.append("restart the workflow.\n\n");
        body.append("This workflow has been cancelled.");

        showMismatchDialog(
                "Objective Pixel-Size Mismatch -- Workflow Cancelled",
                "MicroManager's active objective does not match the wizard's selection.",
                body.toString(),
                parentStage);

        return false;
    }

    /**
     * Shows the mismatch warning, blocking until dismissed regardless of calling thread.
     * When {@code parent} is non-null, the alert is parented + co-floated via
     * {@link UIFunctions#showAlertOverParent} so it does not sink behind an always-on-top caller.
     */
    private static void showMismatchDialog(String title, String header, String body, Window parent) {
        if (Platform.isFxApplicationThread()) {
            showMismatchDialogOnFxThread(title, header, body, parent);
            return;
        }
        FutureTask<Void> task = new FutureTask<>(() -> {
            showMismatchDialogOnFxThread(title, header, body, parent);
            return null;
        });
        Platform.runLater(task);
        try {
            task.get();
        } catch (Exception e) {
            logger.warn("Failed to display objective-mismatch dialog: {}", e.getMessage());
        }
    }

    private static void showMismatchDialogOnFxThread(String title, String header, String body, Window parent) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(body);
        alert.getButtonTypes().setAll(ButtonType.OK);

        alert.getDialogPane().setMinWidth(620);
        alert.getDialogPane().setPrefWidth(720);
        alert.getDialogPane().setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);

        Label contentLabel = (Label) alert.getDialogPane().lookup(".content");
        if (contentLabel != null) {
            contentLabel.setWrapText(true);
            contentLabel.setMaxWidth(680);
            contentLabel.setStyle("-fx-font-family: 'monospace';");
        }
        Label headerLabel = (Label) alert.getDialogPane().lookup(".header-panel .label");
        if (headerLabel != null) {
            headerLabel.setWrapText(true);
            headerLabel.setMaxWidth(660);
        }

        if (parent != null) {
            UIFunctions.showAlertOverParent(alert, parent);
        } else {
            alert.showAndWait();
        }
    }

    /**
     * Dummy check to ensure hardware is accessible.
     * Replace with actual hardware checks when needed.
     *
     * @return true if hardware is accessible
     */
    private static boolean checkHardwareAccessible() {
        // TODO: Implement real hardware accessibility check.
        // For example:
        // - Check if PycroManager is running
        // - Check if MicroManager core is accessible
        // - Verify stage controller is responding
        // - Verify camera/detector is connected
        return true;
    }

    /**
     * Dummy check to ensure QuPath is in the correct state.
     * Replace with actual state validations if needed.
     *
     * @return true if QuPath is in the correct state
     */
    private static boolean checkQuPathState() {
        // TODO: Implement real QuPath state checks.
        // For example:
        // - Check if annotations exist
        // - Verify project directory structure
        // - Check available memory
        return true;
    }
}
