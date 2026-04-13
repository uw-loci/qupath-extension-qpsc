package qupath.ext.qpsc.utilities;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.BackgroundValidationResult;
import qupath.ext.qpsc.modality.ChannelExposure;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.model.SampleSetupResult;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;

/**
 * Centralized builder for acquisition configuration that consolidates duplicated logic
 * from BoundedAcquisitionWorkflow and AcquisitionManager.
 */
public class AcquisitionConfigurationBuilder {

    private static final Logger logger = LoggerFactory.getLogger(AcquisitionConfigurationBuilder.class);

    /**
     * Configuration record containing all acquisition parameters
     */
    public record AcquisitionConfiguration(
            String objective,
            String detector,
            double WSI_pixelSize_um,
            boolean bgEnabled,
            String bgMethod,
            String bgFolder,
            int afTiles,
            int afSteps,
            double afRange,
            List<String> processingSteps,
            AcquisitionCommandBuilder commandBuilder) {}

    /**
     * Builds a complete acquisition configuration from the provided parameters.
     *
     * @param sample Sample setup result containing hardware configuration
     * @param configFileLocation Path to microscope configuration file
     * @param modalityWithIndex Imaging mode with index (e.g., "ppm_20x_1")
     * @param regionName Name of the annotation/region being acquired
     * @param angleExposures List of rotation angles and exposures
     * @param projectsFolder Base folder for projects (e.g., "D:/2025QPSC/data")
     * @param sampleName The actual sample name to use for paths (from ProjectInfo, not sample dialog)
     * @param explicitPixelSize Pixel size in micrometers
     * @param wbMode White balance mode (e.g., "camera_awb", "per_angle") for background validation, or null to skip
     * @return Complete acquisition configuration
     */
    public static AcquisitionConfiguration buildConfiguration(
            SampleSetupResult sample,
            String configFileLocation,
            String modalityWithIndex,
            String regionName,
            List<AngleExposure> angleExposures,
            String projectsFolder,
            String sampleName,
            double explicitPixelSize,
            String wbMode) {
        return buildConfiguration(
                sample,
                configFileLocation,
                modalityWithIndex,
                regionName,
                angleExposures,
                null,
                projectsFolder,
                sampleName,
                explicitPixelSize,
                wbMode);
    }

    /**
     * Channel-aware overload. When {@code channelExposures} is non-null and
     * non-empty, the resulting command builder will emit {@code --channels}
     * and {@code --channel-exposures} in place of {@code --angles}/{@code --exposures}.
     *
     * @param channelExposures per-channel acquisition sequence, or null for angle-based acquisition
     */
    public static AcquisitionConfiguration buildConfiguration(
            SampleSetupResult sample,
            String configFileLocation,
            String modalityWithIndex,
            String regionName,
            List<AngleExposure> angleExposures,
            List<ChannelExposure> channelExposures,
            String projectsFolder,
            String sampleName,
            double explicitPixelSize,
            String wbMode) {

        MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configFileLocation);

        // Use explicit hardware selections from sample setup
        String objective = sample.objective();
        String detector = sample.detector();
        String baseModality = sample.modality();

        // Get background correction settings (checks imageprocessing config first, then main config)
        boolean bgEnabled = configManager.isBackgroundCorrectionEnabled(baseModality);
        String bgMethod = configManager.getBackgroundCorrectionMethod(baseModality);
        String bgBaseFolder = configManager.getBackgroundCorrectionFolder(baseModality);

        // For monochrome (non-JAI) detectors, the user's
        // "Use background correction" checkbox in the acquisition dialog
        // overrides the YAML enabled flag. JAI cameras continue to use the
        // WB mode selector which bundles background correction.
        boolean isJai = configManager.isJAICamera(detector);
        if (!isJai) {
            boolean monoPref = qupath.ext.qpsc.preferences.PersistentPreferences.getUseBackgroundCorrectionMono();
            if (bgEnabled && !monoPref) {
                logger.info("Monochrome BG correction disabled by user preference for {}", detector);
                bgEnabled = false;
            } else if (!bgEnabled && monoPref && bgBaseFolder != null) {
                // Treat the checkbox as an opt-in even when the YAML flag
                // hasn't been flipped yet (e.g. prior to the first successful
                // background collection through the updated workflow).
                logger.info(
                        "Monochrome BG correction enabled by user preference even though YAML enabled=false");
                bgEnabled = true;
                if (bgMethod == null) {
                    bgMethod = "divide";
                }
            }
        }

        // Resolve detector-specific background folder path (WB-mode aware)
        String bgFolder = null;
        if (bgEnabled && bgBaseFolder != null) {
            bgFolder = BackgroundSettingsReader.resolveBackgroundFolder(
                    bgBaseFolder, baseModality, objective, detector, wbMode);
            logger.info("Resolved background folder path: {}", bgFolder);
        }

        // Get autofocus parameters (skip entirely if user disabled all autofocus)
        boolean autofocusDisabled = QPPreferenceDialog.getDisableAllAutofocus();
        int afTiles = 5; // defaults
        int afSteps = 11;
        double afRange = 50.0;

        if (autofocusDisabled) {
            logger.warn("ALL AUTOFOCUS DISABLED by user preference. Focus drift will NOT be corrected.");
        } else {
            Map<String, Object> afParams = configManager.getAutofocusParams(objective);

            if (afParams != null) {
                if (afParams.get("n_tiles") instanceof Number) {
                    afTiles = ((Number) afParams.get("n_tiles")).intValue();
                }
                if (afParams.get("n_steps") instanceof Number) {
                    afSteps = ((Number) afParams.get("n_steps")).intValue();
                }
                if (afParams.get("search_range_um") instanceof Number) {
                    afRange = ((Number) afParams.get("search_range_um")).doubleValue();
                }
                logger.debug(
                        "Using objective-specific autofocus parameters for {}: tiles={}, steps={}, range={}um",
                        objective,
                        afTiles,
                        afSteps,
                        afRange);
            } else {
                logger.warn(
                        "No autofocus parameters found for objective {}. Using generic defaults: tiles={}, steps={}, range={}um. "
                                + "This may result in suboptimal focus quality. Consider adding autofocus configuration for this objective.",
                        objective,
                        afTiles,
                        afSteps,
                        afRange);

                // Show user warning for missing autofocus configuration
                showAutofocusConfigurationWarning(objective, afTiles, afSteps, afRange);
            }
        }

        // Determine processing pipeline based on detector properties
        List<String> processingSteps = new ArrayList<>();
        if (configManager.detectorRequiresDebayering(detector)) {
            processingSteps.add("debayer");
        }
        if (bgEnabled && bgFolder != null) {
            processingSteps.add("background_correction");
        }

        // Determine whether this modality is non-rotation (BF, fluorescence,
        // etc.) so the command builder can omit --angles and send only the
        // unified exposure via --exposures.
        ModalityHandler modalityHandler = ModalityRegistry.getHandler(baseModality);
        boolean isNonRotation = modalityHandler != null && modalityHandler.getDefaultAngleCount() <= 1;

        boolean channelBased = channelExposures != null && !channelExposures.isEmpty();

        // Defensive: when channel-based, force angleExposures to an empty list so any
        // stale angle data from upstream cannot be emitted alongside --channels. Mutual
        // exclusion is enforced in the command builder already, but this guarantees a
        // single source of truth even if a caller resolution path drifts.
        if (channelBased) {
            angleExposures = List.of();
        }

        // Build enhanced acquisition command
        // White balance mode is set later by the caller via .wbMode() from the dialog selection.
        // Use the sampleName parameter (from ProjectInfo) - NOT sample.sampleName() which may differ
        AcquisitionCommandBuilder acquisitionBuilder = AcquisitionCommandBuilder.builder()
                .yamlPath(configFileLocation)
                .projectsFolder(projectsFolder)
                .sampleLabel(sampleName)
                .scanType(modalityWithIndex)
                .regionName(regionName)
                .angleExposures(angleExposures)
                .nonRotation(isNonRotation)
                .hardware(objective, detector, explicitPixelSize)
                .processingPipeline(processingSteps);

        if (channelBased) {
            acquisitionBuilder.channelExposures(channelExposures);
        }

        // Only add autofocus parameters if autofocus is enabled
        if (!autofocusDisabled) {
            acquisitionBuilder.autofocus(afTiles, afSteps, afRange);
        }

        // Only add background correction if enabled and configured
        if (bgEnabled && bgMethod != null && bgFolder != null) {
            // Perform background validation to determine which angles should have correction disabled.
            // Skipped entirely for channel-based acquisitions -- the per-angle disable concept
            // doesn't apply when there are no rotation angles.
            List<Double> disabledAngles = new ArrayList<>();
            try {
                BackgroundSettingsReader.BackgroundSettings backgroundSettings = channelBased
                        ? null
                        : BackgroundSettingsReader.findBackgroundSettings(
                                bgBaseFolder, baseModality, objective, detector, wbMode);

                if (backgroundSettings != null) {
                    ModalityHandler handler = ModalityRegistry.getHandler(baseModality);
                    BackgroundValidationResult validation =
                            handler.validateBackgroundSettings(backgroundSettings, angleExposures, wbMode);

                    // Only disable angles that have no background image at all
                    disabledAngles.addAll(validation.anglesWithoutBackground);
                    // Exposure mismatches are logged as warnings but do NOT disable correction.
                    // Flat field correction (divide method) is exposure-independent: the spatial
                    // illumination pattern (bg_pixel/bg_mean ratio) is constant regardless of
                    // exposure time. Disabling correction for exposure mismatch prevents critical
                    // vignetting correction from running.
                    if (!validation.angleswithExposureMismatches.isEmpty()) {
                        logger.warn(
                                "Exposure mismatch detected for angles {} "
                                        + "(background vs. acquisition exposures differ by >0.1ms). "
                                        + "Flat field correction will still be applied - "
                                        + "verify background image quality if artifacts appear.",
                                validation.angleswithExposureMismatches);
                    }

                    if (validation.wbModeMismatch) {
                        logger.warn(
                                "WB mode mismatch: background was collected with '{}' but acquisition uses '{}'",
                                validation.backgroundWbMode,
                                validation.currentWbMode);
                    }

                    logger.info(
                            "Background validation: {} angles will have correction disabled", disabledAngles.size());
                    if (!disabledAngles.isEmpty()) {
                        logger.info("Disabled angles: {}", disabledAngles);
                    }
                }
            } catch (Exception e) {
                logger.warn(
                        "Error during background validation, proceeding without angle-specific disabling: {}",
                        e.getMessage());
            }

            acquisitionBuilder.backgroundCorrection(true, bgMethod, bgFolder, disabledAngles);
        }

        // Z-stack configuration from persistent preferences
        if (PersistentPreferences.isZStackEnabled()) {
            double range = PersistentPreferences.getZStackRange();
            double step = PersistentPreferences.getZStackStep();
            double halfRange = range / 2.0;
            acquisitionBuilder.enableZStack(-halfRange, halfRange, step);

            String displayName = PersistentPreferences.getZStackProjection();
            String code =
                    switch (displayName) {
                        case "Min Intensity" -> "min";
                        case "Sum" -> "sum";
                        case "Mean" -> "mean";
                        case "Std Deviation" -> "std";
                        default -> "max";
                    };
            acquisitionBuilder.zProjection(code);
            logger.info("Z-stack enabled: range=+/-{} um, step={} um, projection={}", halfRange, step, code);
        }

        return new AcquisitionConfiguration(
                objective,
                detector,
                explicitPixelSize,
                bgEnabled,
                bgMethod,
                bgFolder,
                afTiles,
                afSteps,
                afRange,
                processingSteps,
                acquisitionBuilder);
    }

    /**
     * Helper method to extract magnification from objective name
     * Extracted from duplicated code in both workflows
     */
    private static String extractMagnificationFromObjective(String objective) {
        if (objective != null && objective.contains("_")) {
            String[] parts = objective.split("_");
            for (String part : parts) {
                if (part.toUpperCase().endsWith("X")) {
                    return part.toLowerCase();
                }
            }
        }
        return "unknown";
    }

    /**
     * Shows a warning dialog when autofocus parameters are missing for an objective.
     * This prevents silent degradation of focus quality.
     */
    private static void showAutofocusConfigurationWarning(
            String objective, int defaultTiles, int defaultSteps, double defaultRange) {
        javafx.application.Platform.runLater(() -> {
            javafx.scene.control.Alert warning =
                    new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING);
            warning.setTitle("Autofocus Configuration Missing");
            warning.setHeaderText("No autofocus parameters found for " + objective);

            StringBuilder message = new StringBuilder();
            message.append("No objective-specific autofocus parameters were found in the configuration.\n\n");
            message.append("Using generic defaults:\n");
            message.append(String.format("- Focus tiles: %d\n", defaultTiles));
            message.append(String.format("- Focus steps: %d\n", defaultSteps));
            message.append(String.format("- Search range: %.1f um\n\n", defaultRange));
            message.append(
                    "This may result in suboptimal focus quality, especially for high-magnification objectives.\n\n");
            message.append("Recommendation: Add autofocus configuration for ").append(objective);
            message.append(" in the microscope configuration file for optimal performance.");

            warning.setContentText(message.toString());
            warning.setResizable(true);
            warning.getDialogPane().setPrefWidth(500);
            warning.getDialogPane().setPrefHeight(350);

            warning.showAndWait();
        });
    }
}
