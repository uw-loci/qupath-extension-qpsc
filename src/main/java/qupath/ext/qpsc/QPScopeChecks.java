package qupath.ext.qpsc;

import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.ObjectiveUtils;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;


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
            Dialogs.showWarningNotification("No Project Open",
                    "You must open a project in QuPath before launching the extension.");
            return false;
        }

        // 2. Check if the expected hardware is accessible (dummy check for now).
        if (!checkHardwareAccessible()) {
            Dialogs.showWarningNotification("Hardware Not Accessible",
                    "The hardware expected by the current preferences is not accessible.\n"
                            + "Please verify your hardware connection.");
            return false;
        }

        // 3. Check if QuPath is in the correct state (dummy check for now).
        if (!checkQuPathState()) {
            Dialogs.showWarningNotification("Incorrect QuPath State",
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
                String errorMessage = "Configuration validation errors:\n\n• " +
                        String.join("\n• ", errors);
                Dialogs.showWarningNotification("Configuration Errors", errorMessage);
                return false;
            }

            logger.info("Configuration validation passed");
            return true;
        } catch (Exception e) {
            logger.error("Error during configuration validation", e);
            Dialogs.showErrorNotification("Configuration Validation Error",
                    "Failed to validate configuration: " + e.getMessage());
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

    /**
     * Validates that the QPSC config pixel size matches MicroManager's active pixel size calibration.
     * If they differ by more than 25%, shows a confirmation dialog warning the user.
     *
     * @param objective  the QPSC objective identifier (e.g., "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001")
     * @param detector   the detector identifier
     * @param modality   the modality name
     * @param configPixelSize the pixel size from QPSC config (um/pixel)
     * @return true if validation passes or user chooses to continue; false if user cancels
     */
    public static boolean validateObjectivePixelSize(
            String objective, String detector, String modality, double configPixelSize) {

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

        logger.info("Pixel size check: config={} um ({}), MicroManager={} um, diff={} percent",
                configPixelSize, displayMag, mmPixelSize, String.format("%.1f", ratio * 100));

        if (ratio > 0.25) {
            String message = String.format(
                    "Selected objective (%s) expects pixel size %.4f um, "
                    + "but MicroManager reports %.4f um.\n\n"
                    + "This may indicate a different objective is active in MicroManager.\n\n"
                    + "Continue anyway?",
                    displayMag, configPixelSize, mmPixelSize);

            return Dialogs.showConfirmDialog("Objective Mismatch Warning", message);
        }

        return true;
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