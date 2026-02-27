package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.controller.workflow.StitchingHelper;
import qupath.ext.qpsc.ui.WBComparisonDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
import qupath.ext.qpsc.utilities.StitchingConfiguration;
import qupath.ext.qpsc.utilities.TilingRequest;
import qupath.ext.qpsc.utilities.TilingUtilities;
import qupath.ext.qpsc.utilities.TileProcessingUtilities;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * WBComparisonWorkflow - Compares white balance modes under identical conditions.
 *
 * <p>This workflow acquires the same tissue region with each selected WB mode
 * (camera_awb, simple, per_angle), producing multiple stitched images in a single
 * QuPath project for side-by-side visual comparison.
 *
 * <p>Workflow sequence for each WB mode:
 * <ol>
 *   <li>Calibrate white balance at blank position</li>
 *   <li>Collect background images at blank position</li>
 *   <li>Acquire tile grid at tissue position</li>
 *   <li>Stitch tiles into pyramidal images</li>
 * </ol>
 *
 * <p>If one mode fails, the workflow continues with remaining modes and reports
 * a summary at completion.
 *
 * @author Mike Nelson
 * @since 2.0
 */
public class WBComparisonWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(WBComparisonWorkflow.class);

    private static final long ACQUISITION_POLL_INTERVAL_MS = 2000;
    private static final long ACQUISITION_TIMEOUT_MS = 30 * 60 * 1000; // 30 minutes

    /**
     * Main entry point called from QPScopeController.
     */
    public static void run() {
        logger.info("Starting WB Comparison Test workflow");

        WBComparisonDialog.showDialog()
                .thenAccept(params -> {
                    if (params == null) {
                        logger.info("WB Comparison cancelled by user");
                        return;
                    }

                    logger.info("WB Comparison parameters: modes={}, grid={}x{}, blank=({},{},{}), tissue=({},{},{})",
                            params.selectedModes(), params.gridCols(), params.gridRows(),
                            params.blankX(), params.blankY(), params.blankZ(),
                            params.tissueX(), params.tissueY(), params.tissueZ());

                    CompletableFuture.runAsync(() -> executeWorkflow(params))
                            .exceptionally(ex -> {
                                logger.error("WB Comparison workflow failed", ex);
                                Platform.runLater(() -> Dialogs.showErrorMessage(
                                        "WB Comparison Error",
                                        "Workflow failed: " + ex.getMessage()));
                                return null;
                            });
                })
                .exceptionally(ex -> {
                    logger.error("Error in WB Comparison dialog", ex);
                    Platform.runLater(() -> Dialogs.showErrorMessage(
                            "WB Comparison Error",
                            "Failed to show dialog: " + ex.getMessage()));
                    return null;
                });
    }

    /**
     * Executes the full comparison workflow on a background thread.
     */
    private static void executeWorkflow(WBComparisonDialog.WBComparisonParams params) {
        // --- VALIDATE ---
        MicroscopeController mc = MicroscopeController.getInstance();
        MicroscopeSocketClient socketClient = mc.getSocketClient();

        try {
            if (!mc.isConnected()) {
                logger.info("Connecting to microscope server");
                mc.connect();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to connect to microscope server: " + e.getMessage(), e);
        }

        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);

        // Resolve hardware parameters
        String modality = resolveModality(configManager);
        String objective = resolveObjective(configManager, modality);
        String detector = resolveDetector(configManager, modality, objective);
        double pixelSize = configManager.getHardwarePixelSize(objective, detector);

        logger.info("Hardware: modality={}, objective={}, detector={}, pixelSize={}",
                modality, objective, detector, pixelSize);

        // Get FOV (frame size in microns)
        double[] fov = configManager.getModalityFOV(modality, objective, detector);
        if (fov == null) {
            throw new RuntimeException("Could not determine field of view from configuration");
        }
        double frameWidthUm = fov[0];
        double frameHeightUm = fov[1];
        logger.info("FOV: {} x {} um", frameWidthUm, frameHeightUm);

        // Get angle-exposure pairs from modality config
        List<AngleExposure> angleExposures = resolveAngleExposures(configManager, modality, objective, detector);
        logger.info("Angle-exposures: {}", angleExposures);

        ModalityHandler handler = ModalityRegistry.getHandler(modality);

        // --- SETUP ---
        // Stop live mode before starting
        MicroscopeController.LiveViewState liveViewState = mc.stopAllLiveViewing();

        try {
            // Create QuPath project
            Project<BufferedImage> project = QPProjectFunctions.createProject(
                    params.outputFolder(), params.sampleName());
            if (project == null) {
                throw new RuntimeException("Failed to create QuPath project");
            }
            logger.info("Created QuPath project at {}/{}", params.outputFolder(), params.sampleName());

            // Compute bounding box from tissue center + grid size + frame size
            double overlapFraction = params.overlapPercent() / 100.0;
            double stepX = frameWidthUm * (1.0 - overlapFraction);
            double stepY = frameHeightUm * (1.0 - overlapFraction);
            double totalWidth = stepX * params.gridCols();
            double totalHeight = stepY * params.gridRows();

            double bbX1 = params.tissueX() - totalWidth / 2.0;
            double bbY1 = params.tissueY() - totalHeight / 2.0;
            double bbX2 = params.tissueX() + totalWidth / 2.0;
            double bbY2 = params.tissueY() + totalHeight / 2.0;

            logger.info("Bounding box: ({}, {}) to ({}, {})", bbX1, bbY1, bbX2, bbY2);

            // Build the modality folder name (e.g., "ppm_20x_1")
            String modeWithIndex = AcquisitionCommandBuilder.builder()
                    .yamlPath(configPath)
                    .projectsFolder(params.outputFolder())
                    .sampleLabel(params.sampleName())
                    .scanType(modality)
                    .regionName("bounds")
                    .hardware(objective, detector, pixelSize)
                    .getEnhancedScanType();
            logger.info("Enhanced scan type: {}", modeWithIndex);

            // Generate tile grid once (reused for all modes)
            // TilingUtilities.processBoundingBoxTilingRequest() auto-appends a "bounds/"
            // subdirectory, so outputFolder should be one level above.
            String tilingOutputDir = Paths.get(params.outputFolder(), params.sampleName(),
                    modeWithIndex).toString();
            File tilingOutputDirFile = new File(tilingOutputDir);
            if (!tilingOutputDirFile.exists() && !tilingOutputDirFile.mkdirs()) {
                throw new RuntimeException("Failed to create tile directory: " + tilingOutputDir);
            }

            TilingRequest tilingRequest = new TilingRequest.Builder()
                    .outputFolder(tilingOutputDir)
                    .modalityName(modeWithIndex)
                    .frameSize(frameWidthUm, frameHeightUm)
                    .overlapPercent(params.overlapPercent())
                    .boundingBox(bbX1, bbY1, bbX2, bbY2)
                    .createDetections(false)
                    .build();

            try {
                TilingUtilities.createTiles(tilingRequest);
            } catch (IOException e) {
                throw new RuntimeException("Failed to generate tile grid: " + e.getMessage(), e);
            }
            logger.info("Tile grid generated at {}", tilingOutputDir);

            // TilingUtilities creates TileConfiguration.txt inside a "bounds/" subdirectory
            Path tileConfigPath = Paths.get(tilingOutputDir, "bounds", "TileConfiguration.txt");
            if (!Files.exists(tileConfigPath)) {
                throw new RuntimeException("TileConfiguration.txt not generated at " + tileConfigPath);
            }
            byte[] tileConfigContent;
            try {
                tileConfigContent = Files.readAllBytes(tileConfigPath);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read TileConfiguration.txt: " + e.getMessage(), e);
            }

            // --- PROCESS EACH MODE ---
            List<String> modes = params.selectedModes();
            Map<String, String> results = new LinkedHashMap<>(); // mode -> "success" or error message
            int modeIndex = 1;

            for (String wbMode : modes) {
                logger.info("=== Processing WB mode: {} ({}/{}) ===", wbMode, modeIndex, modes.size());

                try {
                    processMode(wbMode, modeIndex, params, socketClient, configPath, configManager,
                            modality, objective, detector, pixelSize, modeWithIndex,
                            angleExposures, handler, project, tileConfigContent,
                            bbX1, bbY1, bbX2, bbY2);
                    results.put(wbMode, "success");
                    logger.info("WB mode '{}' completed successfully", wbMode);
                } catch (Exception e) {
                    logger.error("WB mode '{}' failed", wbMode, e);
                    results.put(wbMode, e.getMessage());
                }

                modeIndex++;
            }

            // --- SUMMARY ---
            showSummary(results, params.sampleName(), params.outputFolder());

        } finally {
            // Restore live viewing state
            mc.restoreLiveViewState(liveViewState);
        }
    }

    /**
     * Processes a single WB mode: calibrate -> background -> acquire -> stitch.
     */
    private static void processMode(
            String wbMode, int modeIndex,
            WBComparisonDialog.WBComparisonParams params,
            MicroscopeSocketClient socketClient,
            String configPath,
            MicroscopeConfigManager configManager,
            String modality, String objective, String detector, double pixelSize,
            String modeWithIndex,
            List<AngleExposure> angleExposures,
            ModalityHandler handler,
            Project<BufferedImage> project,
            byte[] tileConfigContent,
            double bbX1, double bbY1, double bbX2, double bbY2
    ) throws Exception {

        String wbFolderName = "wb_" + wbMode;

        // Build output paths for this mode
        // Server constructs tile path as: {projects}/{sample}/{scan_type}/{region}/
        // So tiles end up at: {modeDir}/{modeWithIndex}/bounds/{angle}/
        String modeDir = Paths.get(params.outputFolder(), params.sampleName(),
                modeWithIndex, wbFolderName).toString();
        String bgDir = Paths.get(modeDir, "backgrounds").toString();
        String boundsDir = Paths.get(modeDir, modeWithIndex, "bounds").toString();

        // Create directories
        Files.createDirectories(Paths.get(bgDir));
        Files.createDirectories(Paths.get(boundsDir));

        // --- 3a. CALIBRATE at blank position ---
        logger.info("[{}] Moving to blank position ({}, {}, z={})", wbMode,
                params.blankX(), params.blankY(), params.blankZ());
        socketClient.moveStageXY(params.blankX(), params.blankY());
        socketClient.moveStageZ(params.blankZ());
        Thread.sleep(1000); // settle

        calibrateWhiteBalance(wbMode, socketClient, configPath, bgDir,
                params.targetIntensity(), objective, detector, angleExposures);

        // --- 3b. COLLECT BACKGROUNDS at blank position ---
        logger.info("[{}] Collecting backgrounds at blank position", wbMode);

        String angles = angleExposures.stream()
                .map(ae -> String.valueOf(ae.ticks()))
                .collect(Collectors.joining(",", "(", ")"));
        String exposures = angleExposures.stream()
                .map(ae -> String.valueOf(ae.exposureMs()))
                .collect(Collectors.joining(",", "(", ")"));

        Map<Double, Double> finalExposures = socketClient.startBackgroundAcquisition(
                configPath, bgDir, modality, angles, exposures, wbMode, objective, detector);
        logger.info("[{}] Background acquisition complete, {} exposures returned",
                wbMode, finalExposures.size());

        // Update angle-exposures with final values from server if available
        List<AngleExposure> modeAngleExposures;
        if (finalExposures != null && !finalExposures.isEmpty()) {
            modeAngleExposures = angleExposures.stream()
                    .map(ae -> {
                        Double finalExp = finalExposures.get(ae.ticks());
                        return finalExp != null ? new AngleExposure(ae.ticks(), finalExp) : ae;
                    })
                    .collect(Collectors.toList());
        } else {
            modeAngleExposures = angleExposures;
        }

        // --- 3c. ACQUIRE at tissue position ---
        logger.info("[{}] Moving to tissue position ({}, {}, z={})", wbMode,
                params.tissueX(), params.tissueY(), params.tissueZ());
        socketClient.moveStageXY(params.tissueX(), params.tissueY());
        socketClient.moveStageZ(params.tissueZ());
        Thread.sleep(1000); // settle after move

        // Copy TileConfiguration.txt to mode-specific bounds directory
        Path modeConfigPath = Paths.get(boundsDir, "TileConfiguration.txt");
        Files.write(modeConfigPath, tileConfigContent);
        logger.info("[{}] TileConfiguration.txt copied to {}", wbMode, boundsDir);

        // Build acquisition command
        // Server path: {projects}/{sample}/{scan_type}/{region}/ =
        //   {output}/{sample}/{modeWithIndex}/{wbFolderName}/{modeWithIndex}/bounds/
        AcquisitionCommandBuilder builder = AcquisitionCommandBuilder.builder()
                .yamlPath(configPath)
                .projectsFolder(Paths.get(params.outputFolder(), params.sampleName(), modeWithIndex).toString())
                .sampleLabel(wbFolderName)
                .scanType(modeWithIndex)
                .regionName("bounds")
                .hardware(objective, detector, pixelSize)
                .angleExposures(modeAngleExposures)
                .wbMode(wbMode)
                .backgroundCorrection(true, "divide", bgDir)
                .autofocus(params.afTiles(), params.afSteps(), params.afRange());

        // Establish fresh connection before acquisition to avoid stale connection state
        // from calibration/background operations (which may leave the socket in an
        // unstable state that causes EOFException during status polling)
        socketClient.disconnect();
        socketClient.connect();

        socketClient.startAcquisition(builder);
        logger.info("[{}] Acquisition started, polling for completion", wbMode);

        // Allow server time to fully initialize the acquisition before polling
        // (matches BoundedAcquisitionWorkflow pattern)
        Thread.sleep(1000);

        // Poll for completion using monitorAcquisition() which has retry logic
        // for initial connection failures (server closes connection after STARTED ack)
        MicroscopeSocketClient.AcquisitionState finalState = socketClient.monitorAcquisition(
                progress -> logger.debug("[{}] Acquisition progress: {}/{}", wbMode,
                        progress.current, progress.total),
                retriesRemaining -> {
                    // Auto-skip manual focus for WB comparison (automated workflow)
                    logger.warn("[{}] Autofocus failed, auto-skipping (retries remaining: {})",
                            wbMode, retriesRemaining);
                    try {
                        socketClient.skipAutofocusRetry();
                    } catch (IOException e) {
                        logger.error("[{}] Failed to send SKIPAF response", wbMode, e);
                    }
                },
                ACQUISITION_POLL_INTERVAL_MS,
                ACQUISITION_TIMEOUT_MS
        );

        if (finalState == MicroscopeSocketClient.AcquisitionState.FAILED) {
            String failMsg = socketClient.getLastFailureMessage();
            throw new RuntimeException("Acquisition failed for WB mode '" + wbMode + "': " +
                    (failMsg != null ? failMsg : "unknown error"));
        } else if (finalState == MicroscopeSocketClient.AcquisitionState.CANCELLED) {
            throw new RuntimeException("Acquisition cancelled for WB mode '" + wbMode + "'");
        } else if (finalState != MicroscopeSocketClient.AcquisitionState.COMPLETED) {
            throw new RuntimeException("Acquisition ended in unexpected state '" + finalState +
                    "' for WB mode '" + wbMode + "'");
        }
        logger.info("[{}] Acquisition completed", wbMode);

        // --- 3d. STITCH ---
        logger.info("[{}] Starting stitching", wbMode);
        stitchMode(wbMode, params, modeWithIndex, wbFolderName,
                modeAngleExposures, pixelSize, handler, project);
        logger.info("[{}] Stitching completed", wbMode);
    }

    /**
     * Calibrates white balance for the specified mode at the current (blank) position.
     */
    private static void calibrateWhiteBalance(
            String wbMode, MicroscopeSocketClient socketClient,
            String configPath, String outputPath,
            double targetIntensity, String objective, String detector,
            List<AngleExposure> angleExposures
    ) throws Exception {
        logger.info("[{}] Starting white balance calibration", wbMode);

        switch (wbMode) {
            case "camera_awb" -> {
                // Rotate to uncrossed (90 deg), run one-shot AWB, then disable
                double uncrossedAngle = 90.0;
                for (AngleExposure ae : angleExposures) {
                    if (ae.ticks() == 90.0) {
                        uncrossedAngle = ae.ticks();
                        break;
                    }
                }
                logger.info("[camera_awb] Rotating to {} deg for AWB", uncrossedAngle);
                socketClient.moveStageR(uncrossedAngle);
                Thread.sleep(1000);

                // Reset gains to neutral
                try {
                    socketClient.setGains(new float[]{1.0f, 1.0f, 1.0f});
                } catch (Exception e) {
                    logger.warn("[camera_awb] Could not reset gains: {}", e.getMessage());
                }

                // Run one-shot auto WB
                socketClient.setWhiteBalanceMode(2); // Once
                Thread.sleep(2000);
                socketClient.setWhiteBalanceMode(0); // Off
                logger.info("[camera_awb] Camera AWB calibration complete");
            }
            case "simple" -> {
                // Use a reasonable base exposure for simple WB
                double baseExposure = angleExposures.isEmpty() ? 10.0 : angleExposures.get(0).exposureMs();
                socketClient.runSimpleWhiteBalance(
                        outputPath, baseExposure, targetIntensity,
                        5.0, // tolerance
                        6.0, // maxGainDb
                        0.75, // gainThresholdRatio
                        15,   // maxIterations
                        false, // calibrateBlackLevel
                        1.0,  // baseGain
                        100.0, // exposureSoftCapMs
                        12.0, // boostedMaxGainDb
                        configPath, objective, detector
                );
                logger.info("[simple] Simple white balance calibration complete");
            }
            case "per_angle" -> {
                // Get the 4 standard PPM angles from config
                // Default PPM angles: positive=7, negative=-7, crossed=0, uncrossed=90
                double posAngle = 7.0, negAngle = -7.0, crossAngle = 0.0, uncrossAngle = 90.0;
                double posExp = 10.0, negExp = 10.0, crossExp = 10.0, uncrossExp = 10.0;

                for (AngleExposure ae : angleExposures) {
                    double a = ae.ticks();
                    if (a > 0 && a < 45) { posAngle = a; posExp = ae.exposureMs(); }
                    else if (a < 0 && a > -45) { negAngle = a; negExp = ae.exposureMs(); }
                    else if (Math.abs(a) < 0.1) { crossAngle = a; crossExp = ae.exposureMs(); }
                    else if (Math.abs(a - 90) < 1.0) { uncrossAngle = a; uncrossExp = ae.exposureMs(); }
                }

                socketClient.runPPMWhiteBalance(
                        outputPath,
                        posAngle, posExp, targetIntensity,
                        negAngle, negExp, targetIntensity,
                        crossAngle, crossExp, targetIntensity,
                        uncrossAngle, uncrossExp, targetIntensity,
                        targetIntensity,
                        5.0, // tolerance
                        6.0, // maxGainDb
                        0.75, // gainThresholdRatio
                        15,   // maxIterations
                        false, // calibrateBlackLevel
                        1.0,  // baseGain
                        100.0, // exposureSoftCapMs
                        12.0, // boostedMaxGainDb
                        configPath, objective, detector
                );
                logger.info("[per_angle] PPM white balance calibration complete");
            }
            default -> logger.warn("Unknown WB mode for calibration: {}", wbMode);
        }
    }


    /**
     * Stitches the acquired tiles for one WB mode.
     *
     * <p>Uses directory isolation to prevent cross-matching issues with the
     * TileConfigurationTxtStrategy .contains() matching logic. For example,
     * "0.0" would match "90.0" without isolation. Each angle directory is
     * temporarily moved into an isolated parent so only that angle is visible
     * to the stitcher. This matches the approach in StitchingHelper.</p>
     */
    private static void stitchMode(
            String wbMode,
            WBComparisonDialog.WBComparisonParams params,
            String modeWithIndex,
            String wbFolderName,
            List<AngleExposure> angleExposures,
            double pixelSize,
            ModalityHandler handler,
            Project<BufferedImage> project
    ) throws Exception {
        QuPathGUI gui = QuPathGUI.getInstance();
        var stitchConfig = StitchingConfiguration.getStandardConfiguration();

        // The tile folder structure (server creates scan_type level):
        // {outputFolder}/{sampleName}/{modeWithIndex}/{wbFolderName}/{modeWithIndex}/bounds/{angle}/
        // TileProcessingUtilities constructs: {projectsBase}/{sampleLabel}/{imagingMode}/{regionName}/
        String projectsBase = Paths.get(params.outputFolder(), params.sampleName(), modeWithIndex).toString();
        Path tileBaseDir = Paths.get(projectsBase, wbFolderName, modeWithIndex, "bounds");

        // Stitch each angle using directory isolation to prevent cross-matching
        for (AngleExposure ae : angleExposures) {
            String angleStr = String.valueOf(ae.ticks());
            logger.info("[{}] Stitching angle: {} (with isolation)", wbMode, angleStr);

            try {
                stitchAngleWithIsolation(tileBaseDir, angleStr, projectsBase, wbFolderName,
                        modeWithIndex, pixelSize, stitchConfig.compressionType(), gui, project, handler);
            } catch (Exception e) {
                logger.error("[{}] Failed to stitch angle {}: {}", wbMode, angleStr, e.getMessage());
                // Continue with other angles
            }
        }

        // Also stitch birefringence if present
        String birefAngle = angleExposures.get(0).ticks() + ".biref";
        Path birefPath = tileBaseDir.resolve(birefAngle);
        if (Files.isDirectory(birefPath)) {
            logger.info("[{}] Stitching birefringence (with isolation)", wbMode);
            try {
                stitchAngleWithIsolation(tileBaseDir, birefAngle, projectsBase, wbFolderName,
                        modeWithIndex, pixelSize, stitchConfig.compressionType(), gui, project, handler);
            } catch (Exception e) {
                logger.error("[{}] Failed to stitch birefringence: {}", wbMode, e.getMessage());
            }
        }
    }

    /**
     * Stitches a single angle directory using temporary isolation.
     *
     * <p>Moves the target angle directory into a temp parent so it is the only
     * subdirectory visible to the stitcher, preventing .contains() collisions
     * (e.g., "0.0" matching "90.0"). Restores the directory after stitching.</p>
     */
    private static void stitchAngleWithIsolation(
            Path tileBaseDir, String angleStr,
            String projectsBase, String wbFolderName,
            String modeWithIndex, double pixelSize,
            String compression, QuPathGUI gui,
            Project<BufferedImage> project, ModalityHandler handler
    ) throws IOException {
        Path angleDir = tileBaseDir.resolve(angleStr);
        if (!Files.exists(angleDir)) {
            logger.warn("Angle directory does not exist: {}", angleDir);
            return;
        }

        // Create a temporary isolation directory alongside the angle dirs
        String tempDirName = "_temp_" + angleStr.replace("-", "neg").replace(".", "_");
        Path tempIsolationDir = tileBaseDir.resolve(tempDirName);
        Path tempAngleDir = tempIsolationDir.resolve(angleStr);

        try {
            Files.createDirectories(tempIsolationDir);
            Files.move(angleDir, tempAngleDir);
            logger.info("Isolated {} into {}", angleStr, tempIsolationDir);

            // Stitch with combined region path so only the isolated angle is visible
            String combinedRegion = "bounds" + File.separator + tempDirName;
            TileProcessingUtilities.stitchImagesAndUpdateProject(
                    projectsBase,
                    wbFolderName,
                    modeWithIndex,
                    combinedRegion,
                    angleStr,
                    gui,
                    project,
                    compression,
                    pixelSize,
                    1, // downsample
                    handler,
                    null
            );
        } finally {
            // Always restore the directory structure
            try {
                if (Files.exists(tempAngleDir)) {
                    Files.move(tempAngleDir, angleDir);
                }
                if (Files.exists(tempIsolationDir)) {
                    Files.delete(tempIsolationDir);
                }
                logger.info("Restored {} from isolation", angleStr);
            } catch (IOException e) {
                logger.error("Failed to restore directory after isolation for {}: {}", angleStr, e.getMessage(), e);
            }
        }
    }

    /**
     * Shows a summary dialog with results for all WB modes.
     */
    private static void showSummary(Map<String, String> results, String sampleName, String outputFolder) {
        StringBuilder sb = new StringBuilder();
        sb.append("WB Comparison Test Complete\n\n");
        sb.append("Sample: ").append(sampleName).append("\n");
        sb.append("Output: ").append(outputFolder).append("\n\n");
        sb.append("Results:\n");

        long successCount = results.values().stream().filter("success"::equals).count();
        for (Map.Entry<String, String> entry : results.entrySet()) {
            String mode = entry.getKey();
            String result = entry.getValue();
            if ("success".equals(result)) {
                sb.append("  [OK] ").append(mode).append("\n");
            } else {
                sb.append("  [FAIL] ").append(mode).append(": ").append(result).append("\n");
            }
        }

        sb.append("\n").append(successCount).append("/").append(results.size()).append(" modes completed successfully.");

        String message = sb.toString();
        logger.info("WB Comparison summary:\n{}", message);

        Platform.runLater(() -> {
            if (successCount == results.size()) {
                Dialogs.showInfoNotification("WB Comparison Complete", message);
            } else {
                Dialogs.showWarningNotification("WB Comparison Complete", message);
            }
        });
    }

    // --- Helper methods for resolving config values ---

    private static String resolveModality(MicroscopeConfigManager configManager) {
        Set<String> modalities = configManager.getAvailableModalities();
        // Prefer PPM if available
        if (modalities.contains("ppm")) return "ppm";
        // Otherwise use first available
        if (!modalities.isEmpty()) return modalities.iterator().next();
        throw new RuntimeException("No modalities configured in YAML");
    }

    private static String resolveObjective(MicroscopeConfigManager configManager, String modality) {
        Set<String> objectives = configManager.getAvailableObjectivesForModality(modality);
        if (!objectives.isEmpty()) return objectives.iterator().next();
        throw new RuntimeException("No objectives available for modality: " + modality);
    }

    private static String resolveDetector(MicroscopeConfigManager configManager, String modality, String objective) {
        Set<String> detectors = configManager.getAvailableDetectorsForModalityObjective(modality, objective);
        if (!detectors.isEmpty()) return detectors.iterator().next();
        throw new RuntimeException("No detectors available for " + modality + "/" + objective);
    }

    private static List<AngleExposure> resolveAngleExposures(
            MicroscopeConfigManager configManager, String modality, String objective, String detector) {
        List<Map<String, Object>> rotationAngles = configManager.getRotationAngles(modality);
        if (rotationAngles.isEmpty()) {
            // Fallback: single angle at 0 degrees for non-PPM modalities
            return List.of(new AngleExposure(0.0, 10.0));
        }

        // Get default exposures from config
        Map<String, Object> exposureConfig = configManager.getModalityExposures(modality, objective, detector);

        List<AngleExposure> result = new ArrayList<>();
        for (Map<String, Object> angleConfig : rotationAngles) {
            // YAML uses "tick" (singular) for rotation angle values
            Object ticksObj = angleConfig.get("tick");
            if (ticksObj instanceof Number) {
                double ticks = ((Number) ticksObj).doubleValue();
                double exposure = 10.0; // default

                // Try to get exposure from config
                if (exposureConfig != null) {
                    Object expObj = exposureConfig.get(String.valueOf(ticks));
                    if (expObj instanceof Number) {
                        exposure = ((Number) expObj).doubleValue();
                    }
                }

                result.add(new AngleExposure(ticks, exposure));
            }
        }

        return result.isEmpty() ? List.of(new AngleExposure(0.0, 10.0)) : result;
    }
}
