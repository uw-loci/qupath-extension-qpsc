package qupath.ext.qpsc.ui;

import java.util.Collection;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.controller.workflow.AlignmentHelper;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.BackgroundSettingsReader;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * Checks the readiness of various calibration and alignment subsystems.
 * Used by the Acquisition Wizard to display status for each prerequisite step.
 */
public class CalibrationChecker {

    private static final Logger logger = LoggerFactory.getLogger(CalibrationChecker.class);

    /** Status levels for each wizard step. */
    public enum Status {
        /** Step is satisfied and ready. */
        READY,
        /** Step has a result but it may be stale or incomplete. */
        WARNING,
        /** Step has not been completed or is missing. */
        NOT_READY,
        /** Step is not applicable for the current configuration. */
        NOT_APPLICABLE
    }

    /** Result of a status check with a human-readable message. */
    public record StepStatus(Status status, String message) {}

    // ------------------------------------------------------------------
    // Server connection
    // ------------------------------------------------------------------

    /**
     * Checks whether the microscope server is currently connected.
     */
    public static StepStatus checkServerConnection() {
        try {
            if (MicroscopeController.getInstance().isConnected()) {
                return new StepStatus(Status.READY, "Connected to microscope server");
            } else {
                return new StepStatus(Status.NOT_READY, "Not connected to microscope server");
            }
        } catch (Exception e) {
            logger.debug("Error checking server connection", e);
            return new StepStatus(Status.NOT_READY, "Server check failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // White balance
    // ------------------------------------------------------------------

    /**
     * Checks whether white balance calibration is relevant and available
     * for the given hardware combination.
     *
     * @param modality  the selected modality (e.g. "ppm")
     * @param objective the selected objective ID
     * @param detector  the selected detector ID
     * @return step status
     */
    public static StepStatus checkWhiteBalance(String modality, String objective, String detector) {
        if (modality == null || objective == null || detector == null) {
            return new StepStatus(Status.NOT_READY, "Select hardware first");
        }

        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);

            // For non-JAI cameras, WB must be configured through MicroManager
            if (!mgr.isJAICamera(detector)) {
                return new StepStatus(Status.NOT_APPLICABLE,
                        "Set white balance in MicroManager for this camera");
            }

            // Check if WB calibration data (per-channel gains) exists
            // for this hardware combination in the imaging profiles.
            var wbGains = mgr.getWhiteBalanceGains(modality, objective, detector);
            if (wbGains != null && !wbGains.isEmpty()) {
                return new StepStatus(Status.READY,
                        "White balance calibration found (" + wbGains.size() + " angles)");
            }

            return new StepStatus(Status.WARNING,
                    "No white balance calibration found for JAI camera - recommended before acquisition");

        } catch (Exception e) {
            logger.debug("Error checking white balance status", e);
            return new StepStatus(Status.WARNING, "Could not verify white balance status");
        }
    }

    // ------------------------------------------------------------------
    // Background correction
    // ------------------------------------------------------------------

    /**
     * Checks whether background correction images exist for the given hardware.
     *
     * @param modality  the selected modality
     * @param objective the selected objective ID
     * @param detector  the selected detector ID
     * @return step status
     */
    public static StepStatus checkBackgroundCorrection(
            String modality, String objective, String detector) {
        if (modality == null || objective == null || detector == null) {
            return new StepStatus(Status.NOT_READY, "Select hardware first");
        }

        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);

            String bgFolder = mgr.getBackgroundCorrectionFolder(modality);
            if (bgFolder == null) {
                return new StepStatus(Status.WARNING,
                        "No background correction folder configured for " + modality);
            }

            // Try to find background settings for this hardware combination
            BackgroundSettingsReader.BackgroundSettings settings =
                    BackgroundSettingsReader.findBackgroundSettings(bgFolder, modality, objective, detector);

            if (settings != null) {
                return new StepStatus(Status.READY,
                        String.format("Background images found (%d angles)", settings.angleExposures.size()));
            }

            return new StepStatus(Status.WARNING,
                    "No background images found - recommended for flat field correction");

        } catch (Exception e) {
            logger.debug("Error checking background correction", e);
            return new StepStatus(Status.WARNING, "Could not verify background correction status");
        }
    }

    // ------------------------------------------------------------------
    // Microscope alignment
    // ------------------------------------------------------------------

    /**
     * Checks whether a microscope alignment transform exists.
     *
     * @return step status with details about the best available transform
     */
    public static StepStatus checkAlignment() {
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (configPath == null) {
                return new StepStatus(Status.NOT_READY, "No microscope config file set");
            }

            // Get the config directory for the transform manager
            java.io.File configFile = new java.io.File(configPath);
            String configDir = configFile.getParent();
            if (configDir == null) {
                return new StepStatus(Status.NOT_READY, "Invalid config file path");
            }

            AffineTransformManager transformMgr = new AffineTransformManager(configDir);
            Collection<AffineTransformManager.TransformPreset> allTransforms =
                    transformMgr.getAllTransforms();

            if (allTransforms.isEmpty()) {
                return new StepStatus(Status.NOT_READY,
                        "No alignment transforms found - run Microscope Alignment first");
            }

            // Find the best (most recent / highest confidence) transform
            AffineTransformManager.TransformPreset best = null;
            double bestConfidence = -1;
            for (var preset : allTransforms) {
                double conf = AlignmentHelper.calculateConfidence(preset);
                if (conf > bestConfidence) {
                    bestConfidence = conf;
                    best = preset;
                }
            }

            if (best == null) {
                return new StepStatus(Status.NOT_READY, "No usable alignment transforms found");
            }

            int pct = (int) (bestConfidence * 100);
            String name = best.getName();

            if (bestConfidence >= 0.7) {
                return new StepStatus(Status.READY,
                        String.format("Alignment '%s' available (%d%% confidence)", name, pct));
            } else if (bestConfidence >= 0.4) {
                return new StepStatus(Status.WARNING,
                        String.format("Alignment '%s' is aging (%d%% confidence) - consider recalibrating",
                                name, pct));
            } else {
                return new StepStatus(Status.WARNING,
                        String.format("Alignment '%s' is stale (%d%% confidence) - recalibration recommended",
                                name, pct));
            }

        } catch (Exception e) {
            logger.debug("Error checking alignment status", e);
            return new StepStatus(Status.WARNING, "Could not verify alignment status");
        }
    }

    // ------------------------------------------------------------------
    // Hardware discovery helpers
    // ------------------------------------------------------------------

    /**
     * Gets the set of available modalities from the microscope config.
     */
    public static Set<String> getAvailableModalities() {
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
            return mgr.getAvailableModalities();
        } catch (Exception e) {
            logger.debug("Error getting available modalities", e);
            return Set.of();
        }
    }

    /**
     * Gets the set of available objectives for a given modality.
     */
    public static Set<String> getAvailableObjectives(String modality) {
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
            return mgr.getAvailableObjectivesForModality(modality);
        } catch (Exception e) {
            logger.debug("Error getting available objectives", e);
            return Set.of();
        }
    }

    /**
     * Gets the set of available detectors for a given modality and objective.
     */
    public static Set<String> getAvailableDetectors(String modality, String objective) {
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
            return mgr.getAvailableDetectorsForModalityObjective(modality, objective);
        } catch (Exception e) {
            logger.debug("Error getting available detectors", e);
            return Set.of();
        }
    }

    /**
     * Gets the microscope name from the config.
     */
    public static String getMicroscopeName() {
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
            return mgr.getMicroscopeName();
        } catch (Exception e) {
            return "Unknown";
        }
    }

    /**
     * Checks if the given detector is a JAI camera.
     */
    public static boolean isJAICamera(String detector) {
        if (detector == null) return false;
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
            return mgr.isJAICamera(detector);
        } catch (Exception e) {
            return false;
        }
    }
}
