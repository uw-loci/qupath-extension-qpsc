package qupath.ext.qpsc.ui;

import java.util.Collection;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.controller.workflow.AlignmentHelper;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.BackgroundValidityChecker;
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
            mgr.reload(configPath); // Pick up recent WB changes

            // For non-JAI cameras, WB must be configured through MicroManager
            if (!mgr.isJAICamera(detector)) {
                return new StepStatus(Status.NOT_APPLICABLE, "Set white balance in MicroManager for this camera");
            }

            // Check which WB modes have been calibrated
            boolean hasPerAngle = false;
            int angleCount = 0;
            boolean hasSimple = false;

            var exposures = mgr.getModalityExposures(modality, objective, detector);
            if (exposures instanceof java.util.Map<?, ?> expMap && !expMap.isEmpty()) {
                hasPerAngle = true;
                angleCount = expMap.size();
            }

            Object simpleWb = mgr.getProfileSetting(modality, objective, detector, "simple_wb");
            if (simpleWb != null) {
                hasSimple = true;
            }

            if (!hasPerAngle && !hasSimple) {
                var gains = mgr.getModalityGains(modality, objective, detector);
                if (gains instanceof java.util.Map<?, ?> gainMap && !gainMap.isEmpty()) {
                    return new StepStatus(Status.READY, "WB gains found (" + gainMap.size() + " angles)");
                }
                return new StepStatus(Status.WARNING, "No WB calibration -- run before acquisition");
            }

            // Build short message: "Per-angle (4) + Simple" or "Per-angle (4)" or "Simple"
            String msg;
            if (hasPerAngle && hasSimple) {
                msg = "Per-angle (" + angleCount + ") + Simple WB";
            } else if (hasPerAngle) {
                msg = "Per-angle WB (" + angleCount + " angles)";
            } else {
                msg = "Simple WB calibrated";
            }

            // Check if backgrounds are stale relative to WB
            String bgFolder = mgr.getBackgroundCorrectionFolder(modality);
            if (bgFolder != null) {
                var allResults = BackgroundValidityChecker.checkAllModes(bgFolder, modality, objective, detector, mgr);
                boolean anyStale = allResults.stream()
                        .anyMatch(r -> r.status() == BackgroundValidityChecker.ValidityStatus.CALIBRATION_STALE);
                if (anyStale) {
                    return new StepStatus(Status.WARNING, msg + " -- re-collect backgrounds");
                }
            }

            return new StepStatus(Status.READY, msg);

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
    public static StepStatus checkBackgroundCorrection(String modality, String objective, String detector) {
        if (modality == null || objective == null || detector == null) {
            return new StepStatus(Status.NOT_READY, "Select hardware first");
        }

        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
            // Reload to pick up any recent WB calibration changes
            mgr.reload(configPath);

            String bgFolder = mgr.getBackgroundCorrectionFolder(modality);
            if (bgFolder == null) {
                return new StepStatus(Status.WARNING, "No background correction folder configured for " + modality);
            }

            // Use BackgroundValidityChecker to cross-validate WB vs backgrounds
            var allModeResults = BackgroundValidityChecker.checkAllModes(bgFolder, modality, objective, detector, mgr);

            boolean anyValid = false;
            boolean anyStale = false;
            boolean anyMissing = false;
            int validCount = 0;
            int staleCount = 0;
            StringBuilder details = new StringBuilder();

            for (var result : allModeResults) {
                switch (result.status()) {
                    case VALID -> {
                        anyValid = true;
                        validCount++;
                    }
                    case CALIBRATION_STALE -> {
                        anyStale = true;
                        staleCount++;
                        details.append(result.mode().getDisplayName()).append(": stale. ");
                    }
                    case NO_BACKGROUNDS -> {
                        anyMissing = true;
                    }
                    case NOT_NEEDED -> {} // Ignore "off" mode
                }
            }

            if (anyStale) {
                return new StepStatus(Status.WARNING, "Backgrounds stale -- re-collect after WB change");
            }

            if (anyValid) {
                return new StepStatus(
                        Status.READY,
                        String.format("Backgrounds valid (%d mode%s)", validCount, validCount > 1 ? "s" : ""));
            }

            if (anyMissing) {
                return new StepStatus(Status.WARNING, "No backgrounds -- recommended before acquisition");
            }

            return new StepStatus(Status.WARNING, "Could not verify background status");

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
            Collection<AffineTransformManager.TransformPreset> allTransforms = transformMgr.getAllTransforms();

            if (allTransforms.isEmpty()) {
                return new StepStatus(
                        Status.NOT_READY, "No alignment transforms found - run Microscope Alignment first");
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
                return new StepStatus(
                        Status.READY, String.format("Alignment '%s' available (%d%% confidence)", name, pct));
            } else if (bestConfidence >= 0.4) {
                return new StepStatus(
                        Status.WARNING,
                        String.format("Alignment '%s' is aging (%d%% confidence) - consider recalibrating", name, pct));
            } else {
                return new StepStatus(
                        Status.WARNING,
                        String.format(
                                "Alignment '%s' is stale (%d%% confidence) - recalibration recommended", name, pct));
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
