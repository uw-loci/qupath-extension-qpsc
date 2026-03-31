package qupath.ext.qpsc.controller;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.BackgroundCollectionController;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.dialogs.Dialogs;

/**
 * BackgroundCollectionWorkflow - Simplified workflow for acquiring flat field correction backgrounds
 *
 * <p>This workflow provides an easy way to acquire background images for flat field correction:
 * <ol>
 *   <li>User selects modality and adjusts exposure times</li>
 *   <li>User positions microscope at clean, blank area</li>
 *   <li>Backgrounds are acquired for all angles with no processing</li>
 *   <li>Images are saved in the correct folder structure with proper names</li>
 * </ol>
 *
 * <p>Key features:
 * <ul>
 *   <li>No project creation or sample acquisition tracking</li>
 *   <li>No image processing (debayering, white balance, etc.)</li>
 *   <li>Direct save to background folder structure</li>
 *   <li>Exposure time persistence to YAML config</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 2.0
 */
public class BackgroundCollectionWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(BackgroundCollectionWorkflow.class);

    /**
     * Main entry point for background collection workflow.
     * Shows UI for modality selection and exposure adjustment, then acquires backgrounds.
     */
    public static void run() {
        logger.info("Starting background collection workflow");

        // showDialog() already handles Platform.runLater() internally --
        // no outer runLater needed (avoids double-nesting delay)
        BackgroundCollectionController.showDialog()
                .thenAccept(result -> {
                    if (result != null) {
                        logger.info(
                                "Background collection parameters received: modality={}, angles={}",
                                result.modality(),
                                result.angleExposures().size());

                        // Execute background acquisition
                        CompletableFuture.runAsync(() -> {
                                    executeBackgroundAcquisition(
                                            result.modality(),
                                            result.objective(),
                                            result.angleExposures(),
                                            result.outputPath(),
                                            result.wbMode());
                                })
                                .exceptionally(ex -> {
                                    logger.error("Background acquisition failed", ex);
                                    Platform.runLater(() -> {
                                        Dialogs.showErrorMessage(
                                                "Background Acquisition Error",
                                                "Failed to execute background acquisition: " + ex.getMessage());
                                    });
                                    return null;
                                });
                    } else {
                        logger.info("Background collection cancelled by user");
                    }
                })
                .exceptionally(ex -> {
                    logger.error("Error in background collection dialog", ex);
                    Platform.runLater(() -> Dialogs.showErrorMessage(
                            "Background Collection Error",
                            "Failed to show background collection dialog: " + ex.getMessage()));
                    return null;
                });
    }

    /**
     * Executes background acquisition directly (called from BackgroundCollectionController
     * when the dialog stays open during acquisition).
     */
    public static void executeBackgroundAcquisitionDirect(
            String modality, String objective, List<AngleExposure> angleExposures, String outputPath, String wbMode) {
        executeBackgroundAcquisition(modality, objective, angleExposures, outputPath, wbMode);
    }

    /**
     * Executes the background acquisition process via socket communication.
     *
     * @param modality The modality (e.g., "ppm")
     * @param objective The selected objective
     * @param angleExposures List of angle-exposure pairs
     * @param outputPath Base output path for background images
     * @param wbMode White balance mode: "camera_awb", "simple", "per_angle", or "off"
     */
    private static void executeBackgroundAcquisition(
            String modality, String objective, List<AngleExposure> angleExposures, String outputPath, String wbMode) {
        logger.info(
                "Executing background acquisition for modality '{}' with {} angles, wbMode={}",
                modality,
                angleExposures.size(),
                wbMode);

        // Stop live viewer before background acquisition -- the server will snap images
        // which stops continuous acquisition at the hardware level. Without this, the
        // Live:ON button stays lit while the camera is actually stopped, causing desync.
        MicroscopeController.LiveViewState liveViewState =
                MicroscopeController.getInstance().stopAllLiveViewing();

        try {
            // Get socket client from MicroscopeController
            MicroscopeSocketClient socketClient =
                    MicroscopeController.getInstance().getSocketClient();

            // Ensure connection
            if (!MicroscopeController.getInstance().isConnected()) {
                logger.info("Connecting to microscope server for background acquisition");
                MicroscopeController.getInstance().connect();
            }

            // Build parameters
            String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            var configManager = MicroscopeConfigManager.getInstance(configFileLocation);

            // Create proper folder structure
            String detector = null;
            if (objective != null) {
                Set<String> availableDetectors =
                        configManager.getAvailableDetectorsForModalityObjective(modality, objective);
                detector = availableDetectors.isEmpty()
                        ? null
                        : availableDetectors.iterator().next();
            }

            String finalOutputPath = outputPath;
            if (objective != null && detector != null) {
                String magnification = extractMagnificationFromObjective(objective);
                // Store backgrounds in WB-mode subfolder so different WB modes coexist
                if (wbMode != null && !wbMode.isEmpty()) {
                    finalOutputPath = java.nio.file.Paths.get(outputPath, detector, modality, magnification, wbMode)
                            .toString();
                } else {
                    finalOutputPath = java.nio.file.Paths.get(outputPath, detector, modality, magnification)
                            .toString();
                }
            }

            // Create output directory
            java.io.File outputDir = new java.io.File(finalOutputPath);
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                throw new IOException("Failed to create output directory: " + finalOutputPath);
            }

            // Format angles and exposures
            String angles = angleExposures.stream()
                    .map(ae -> String.valueOf(ae.ticks()))
                    .collect(java.util.stream.Collectors.joining(",", "(", ")"));
            String exposures = angleExposures.stream()
                    .map(ae -> String.valueOf(ae.exposureMs()))
                    .collect(java.util.stream.Collectors.joining(",", "(", ")"));

            logger.info("Starting background acquisition with angles: {}, exposures: {}", angles, exposures);

            // Call the synchronous background acquisition method
            // Returns map of final exposures actually used by Python (with adaptive exposure)
            Map<Double, Double> finalExposures = socketClient.startBackgroundAcquisition(
                    configFileLocation, finalOutputPath, modality, angles, exposures, wbMode, objective, detector);

            logger.info("Background acquisition completed successfully with {} final exposures", finalExposures.size());

            // Save background collection defaults using actual exposures from server
            saveBackgroundDefaults(
                    finalOutputPath, modality, objective, detector, angleExposures, finalExposures, wbMode);

            // Show success notification on UI thread
            Platform.runLater(() -> {
                Dialogs.showInfoNotification(
                        "Background Collection Complete",
                        String.format(
                                "Successfully acquired %d background images for %s modality",
                                angleExposures.size(), modality));
            });

        } catch (Exception e) {
            logger.error("Background acquisition failed", e);
            Platform.runLater(() -> {
                Dialogs.showErrorMessage(
                        "Background Acquisition Failed", "Failed to acquire background images: " + e.getMessage());
            });
        } finally {
            // Restore live viewer state after background acquisition completes
            try {
                Thread.sleep(300); // Let camera settle
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            MicroscopeController.getInstance().restoreLiveViewState(liveViewState);
        }
    }
    /**
     * Data class for background collection parameters.
     */
    public record BackgroundCollectionResult(
            String modality,
            String objective,
            List<AngleExposure> angleExposures,
            String outputPath,
            boolean usePerAngleWhiteBalance,
            String wbMode) {}

    /**
     * Save background collection defaults to a YAML file for future reference.
     * This file contains all the settings used for background acquisition, which is important
     * for ensuring consistent background correction parameters.
     *
     * @param outputPath The directory where background images were saved
     * @param modality The modality used (e.g., "ppm")
     * @param objective The objective ID used
     * @param detector The detector ID used
     * @param angleExposures The angle-exposure pairs originally requested (may differ from actual)
     * @param finalExposures The final exposures actually used by Python server (from adaptive exposure)
     * @throws IOException if file writing fails
     */
    private static void saveBackgroundDefaults(
            String outputPath,
            String modality,
            String objective,
            String detector,
            List<AngleExposure> angleExposures,
            Map<Double, Double> finalExposures,
            String wbMode)
            throws IOException {

        java.io.File settingsFile = new java.io.File(outputPath, "background_settings.yml");

        logger.info("Saving background collection settings to: {}", settingsFile.getAbsolutePath());

        DumperOptions options = new DumperOptions();
        options.setIndent(2);
        options.setPrettyFlow(true);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);

        // Load existing angle data if file exists
        Map<Double, Double> existingAngleExposureMap = new LinkedHashMap<>();
        if (settingsFile.exists()) {
            try (FileReader reader = new FileReader(settingsFile, StandardCharsets.UTF_8)) {
                Map<String, Object> existingData = yaml.load(reader);

                // Try to load from angle_exposures list (the format readers expect)
                if (existingData != null && existingData.containsKey("angle_exposures")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Double>> existingList =
                            (List<Map<String, Double>>) existingData.get("angle_exposures");
                    for (Map<String, Double> pair : existingList) {
                        existingAngleExposureMap.put(pair.get("angle"), pair.get("exposure"));
                    }
                    logger.info("Loaded {} existing angle-exposure pairs", existingAngleExposureMap.size());
                }
            } catch (Exception e) {
                logger.warn("Could not load existing settings, creating new file", e);
            }
        }

        // Add/update with new angles using FINAL exposures from server
        // Priority: Use finalExposures from Python (adaptive exposure)
        // Fallback: Use angleExposures if server didn't return exposures (old version)
        if (finalExposures != null && !finalExposures.isEmpty()) {
            logger.info("Using {} final exposures from Python server", finalExposures.size());
            existingAngleExposureMap.putAll(finalExposures);
        } else {
            logger.warn("No final exposures from server, using requested exposures (old server version?)");
            for (AngleExposure ae : angleExposures) {
                existingAngleExposureMap.put(ae.ticks(), ae.exposureMs());
            }
        }

        // Build YAML in the expected format
        Map<String, Object> yamlData = new LinkedHashMap<>();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        // Metadata
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("generated", timestamp);
        metadata.put("version", "1.0");
        metadata.put(
                "description",
                "QPSC Background Collection Settings - Contains settings used for background image acquisition");
        yamlData.put("metadata", metadata);

        // Hardware
        Map<String, Object> hardware = new LinkedHashMap<>();
        hardware.put("modality", modality);
        hardware.put("objective", objective != null ? objective : "unknown");
        hardware.put("detector", detector != null ? detector : "unknown");
        hardware.put("magnification", extractMagnificationFromObjective(objective));
        yamlData.put("hardware", hardware);

        // Create sorted lists from the merged data
        List<Map<String, Double>> angleExposureList = new ArrayList<>();
        List<Double> angles = new ArrayList<>();
        List<Double> exposures = new ArrayList<>();

        // Sort by angle for consistent output
        List<Double> sortedAngles = new ArrayList<>(existingAngleExposureMap.keySet());
        sortedAngles.sort(Double::compare);

        for (Double angle : sortedAngles) {
            Double exposure = existingAngleExposureMap.get(angle);

            angles.add(angle);
            exposures.add(exposure);

            Map<String, Double> pair = new LinkedHashMap<>();
            pair.put("angle", angle);
            pair.put("exposure", exposure);
            angleExposureList.add(pair);
        }

        // Acquisition summary - flat structure that readers expect
        Map<String, Object> acquisition = new LinkedHashMap<>();
        acquisition.put("total_angles", angles.size());
        acquisition.put("angles_degrees", angles);
        acquisition.put("exposures_ms", exposures);
        if (wbMode != null) {
            acquisition.put("wb_mode", wbMode);
        }
        yamlData.put("acquisition", acquisition);

        // The main angle_exposures list - this is what readers look for
        yamlData.put("angle_exposures", angleExposureList);

        // Notes
        List<String> notes = new ArrayList<>();
        notes.add("Use these exact settings for background correction to work properly");
        notes.add("If exposure times are changed, new background images must be acquired");
        notes.add("This file should be included when sharing background image sets");
        notes.add("Images are saved as: <angle>.tif (e.g., 0.0.tif, 90.0.tif)");
        yamlData.put("notes", notes);

        // Write YAML file
        try (FileWriter writer = new FileWriter(settingsFile, StandardCharsets.UTF_8)) {
            writer.write("# QPSC Background Collection Settings\n");
            writer.write("# Generated: " + timestamp + "\n");
            writer.write("# Keep this file with the background images for reference\n\n");

            yaml.dump(yamlData, writer);
        }

        logger.info("Background collection settings saved successfully with {} angles: {}", angles.size(), angles);
    }

    /**
     * Extract magnification from objective identifier.
     * Examples:
     * - "LOCI_OBJECTIVE_OLYMPUS_10X_001" -> "10x"
     * - "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001" -> "20x"
     * - "LOCI_OBJECTIVE_OLYMPUS_40X_POL_001" -> "40x"
     */
    private static String extractMagnificationFromObjective(String objective) {
        if (objective == null) return "unknown";

        // Look for pattern like "10X", "20X", "40X"
        java.util.regex.Pattern pattern =
                java.util.regex.Pattern.compile("(\\d+)X", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(objective);

        if (matcher.find()) {
            return matcher.group(1).toLowerCase() + "x"; // "20X" -> "20x"
        }

        // Fallback: return "unknown" if pattern not found
        return "unknown";
    }
}
