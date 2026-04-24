package qupath.ext.qpsc.controller;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.QPScopeChecks;
import qupath.ext.qpsc.controller.workflow.StitchingHelper;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.model.SampleSetupResult;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.AngleResolutionService;
import qupath.ext.qpsc.service.ChannelResolutionService;
import qupath.ext.qpsc.service.AcquisitionMonitorService;
import qupath.ext.qpsc.service.ManualFocusHandler;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.service.notification.NotificationEvent;
import qupath.ext.qpsc.service.notification.NotificationPriority;
import qupath.ext.qpsc.service.notification.NotificationService;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.ui.UnifiedAcquisitionController;
import qupath.ext.qpsc.utilities.*;
import qupath.ext.qpsc.utilities.StitchingConfiguration;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.projects.Project;

/**
 * Bounded acquisition workflow using the unified acquisition dialog.
 * <p>
 * This workflow provides a streamlined user experience with all configuration
 * visible in a single dialog.
 * <p>
 * Workflow steps:
 * <ol>
 *   <li>Show unified acquisition dialog (sample, hardware, region, preview)</li>
 *   <li>Create/open a QuPath project</li>
 *   <li>Compute tiling grid and write TileConfiguration.txt</li>
 *   <li>Launch microscope acquisition via socket</li>
 *   <li>When acquisition finishes, perform stitching</li>
 *   <li>Handle tile cleanup per preference</li>
 * </ol>
 */
public class BoundedAcquisitionWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(BoundedAcquisitionWorkflow.class);

    /**
     * Executor for running stitching jobs.
     * Daemon threads so they won't block JVM shutdown.
     */
    private static final ExecutorService STITCH_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "bounded-stitching-worker");
        t.setDaemon(true);
        return t;
    });

    /**
     * Entry point for the "boundedAcquisition" menu command.
     * Shows the unified dialog, then handles acquisition and stitching.
     */
    public static void run() {
        var res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

        // Ensure connection to microscope server
        try {
            if (!MicroscopeController.getInstance().isConnected()) {
                logger.info("Connecting to microscope server for stage control");
                MicroscopeController.getInstance().connect();
            }
        } catch (IOException e) {
            logger.error("Failed to connect to microscope server: {}", e.getMessage());
            UIFunctions.notifyUserOfError(
                    "Cannot connect to microscope server.\nPlease check server is running and try again.",
                    "Connection Error");
            return;
        }

        // Show the unified acquisition dialog
        UnifiedAcquisitionController.showDialog().thenAccept(result -> {
            if (result == null) {
                logger.info("Unified acquisition dialog cancelled");
                return;
            }

            logger.info(
                    "Starting bounded acquisition: sample={}, modality={}, " + "bounds=({},{}) to ({},{})",
                    result.sampleName(),
                    result.modality(),
                    result.x1(),
                    result.y1(),
                    result.x2(),
                    result.y2());

            // Read persistent prefs
            String prefProjectsFolder = QPPreferenceDialog.getProjectsFolderProperty();
            double overlapPercent = QPPreferenceDialog.getTileOverlapPercentProperty();
            // Stage polarity is read via the composite StagePolarity enum
            // so it matches what the rest of the refactored pipeline sees.
            qupath.ext.qpsc.utilities.StagePolarity stagePolarity = QPPreferenceDialog.getStagePolarityProperty();
            boolean stageInvertedX = stagePolarity.invertX;
            boolean stageInvertedY = stagePolarity.invertY;

            // Derive the profile key (enhanced modality with objective suffix, e.g.
            // "Fluorescence_10x"). This is the key that acquisition_profiles[] is
            // indexed by in the YAML, and therefore the key that must be passed to
            // angle/channel resolution services and the per-profile channel library
            // lookup. The base modality name (result.modality(), e.g. "Fluorescence")
            // is only for user display and handler registry prefix matching.
            final String enhancedModality =
                    ObjectiveUtils.createEnhancedFolderName(result.modality(), result.objective());
            logger.info("Using enhanced modality for project: {} -> {}", result.modality(), enhancedModality);

            // Create/open the QuPath project
            QuPathGUI qupathGUI = QPEx.getQuPath();
            Map<String, Object> pd;
            // If a project is already open, derive the output folder from its
            // actual location rather than the user preference (which is only
            // correct for creating brand-new projects).
            String projectsFolder;
            try {

                // Check if a project is already open
                Project<BufferedImage> existingProject = qupathGUI.getProject();
                if (existingProject != null) {
                    logger.info("Using existing project: {}", existingProject.getPath());
                    // Derive projectsFolder AND sampleName from the project's actual location
                    // Project structure: <projectsFolder>/<sampleName>/project.qpproj
                    java.nio.file.Path projectPath = existingProject.getPath();
                    if (projectPath != null
                            && projectPath.getParent() != null
                            && projectPath.getParent().getParent() != null) {
                        projectsFolder = projectPath.getParent().getParent().toString();
                        logger.info(
                                "Overriding projectsFolder from project path: {} -> {}",
                                prefProjectsFolder,
                                projectsFolder);
                    } else {
                        projectsFolder = prefProjectsFolder;
                    }
                    // Use the actual project folder name, NOT the user-entered sample name.
                    // The user may type a new name, but tiles must go inside the existing project.
                    String existingFolderName =
                            projectPath.getParent().getFileName().toString();
                    logger.info(
                            "Using existing project folder name '{}' (user entered '{}')",
                            existingFolderName,
                            result.sampleName());
                    pd = QPProjectFunctions.getCurrentProjectInformation(
                            projectsFolder, existingFolderName, enhancedModality);
                } else {
                    projectsFolder = prefProjectsFolder;
                    logger.info("Creating new project with sample name: {}", result.sampleName());
                    // Bounded acquisition works directly with stage coordinates -- no optical
                    // flipping is needed. (Flip prefs control macro image orientation for the
                    // ExistingImage workflow, which is not used here.)
                    pd = QPProjectFunctions.createAndOpenQuPathProject(
                            qupathGUI, projectsFolder, result.sampleName(), enhancedModality, false, false);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            String tempTileDir = (String) pd.get("tempTileDirectory");
            String modeWithIndex = (String) pd.get("imagingModeWithIndex");
            @SuppressWarnings("unchecked")
            Project<BufferedImage> project = (Project<BufferedImage>) pd.get("currentQuPathProject");

            // Derive actual sample name and projectsFolder from tempTileDir path structure:
            // tempTileDir = <projectsFolder>/<sampleName>/<modeWithIndex>
            // Get parent (sampleName dir) then get its name
            java.nio.file.Path tempTilePath = java.nio.file.Paths.get(tempTileDir);
            String actualSampleName = tempTilePath.getParent().getFileName().toString();
            String actualProjectsFolder = tempTilePath.getParent().getParent().toString();
            logger.debug(
                    "Derived sample name '{}' and projectsFolder '{}' from tempTileDir path",
                    actualSampleName,
                    actualProjectsFolder);

            // Get camera FOV using explicit hardware selections
            String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configFileLocation);

            // Copy microscope configs to project for provenance tracking
            if (project != null && project.getPath() != null) {
                try {
                    configManager.copyConfigsToProject(project.getPath().getParent());
                } catch (Exception e) {
                    logger.debug("Could not copy configs to project: {}", e.getMessage());
                }
            }

            double frameWidthMicrons, frameHeightMicrons;
            try {
                double[] fov = configManager.getModalityFOV(result.modality(), result.objective(), result.detector());
                if (fov == null) {
                    throw new IOException("Could not calculate FOV for the selected hardware configuration");
                }
                frameWidthMicrons = fov[0];
                frameHeightMicrons = fov[1];
                logger.info("Camera FOV: {} x {} microns", frameWidthMicrons, frameHeightMicrons);
            } catch (Exception e) {
                UIFunctions.notifyUserOfError("Failed to get camera FOV: " + e.getMessage(), "FOV Error");
                return;
            }

            // Get pixel size for stitching
            double WSI_pixelSize_um;
            try {
                WSI_pixelSize_um = configManager.getPixelSize(result.objective(), result.detector());
            } catch (IllegalArgumentException e) {
                UIFunctions.notifyUserOfError("Failed to get pixel size: " + e.getMessage(), "Configuration Error");
                return;
            }

            // Validate pixel size against MicroManager's active calibration
            if (!QPScopeChecks.validateObjectivePixelSize(
                    result.objective(), result.detector(), result.modality(), WSI_pixelSize_um)) {
                return; // user cancelled
            }

            // Validate stitching settings -- loop to let user fix preferences if needed
            if (!StitchingConfiguration.validateWithRetry()) {
                return; // user cancelled
            }

            // Create tile configuration
            TilingRequest request = new TilingRequest.Builder()
                    .outputFolder(tempTileDir)
                    .modalityName(modeWithIndex)
                    .frameSize(frameWidthMicrons, frameHeightMicrons)
                    .overlapPercent(overlapPercent)
                    .boundingBox(result.x1(), result.y1(), result.x2(), result.y2())
                    .stageInvertedAxes(stageInvertedX, stageInvertedY)
                    .createDetections(false)
                    .build();

            try {
                TilingUtilities.createTiles(request);
            } catch (IOException e) {
                UIFunctions.notifyUserOfError("Failed to create tile configuration: " + e.getMessage(), "Tiling Error");
                return;
            }

            ModalityHandler modalityHandler = ModalityRegistry.getHandler(enhancedModality);

            double finalWSI_pixelSize_um = WSI_pixelSize_um;

            // Refuse early if the user deselected every channel on a channel-based
            // modality. Same error surface as the one inside runAsync below, but
            // checked here so the workflow never enters the async chain.
            if (ChannelResolutionService.isEmptySelectionForChannelBasedModality(
                    enhancedModality, result.objective(), result.detector(), result.angleOverrides())) {
                MicroscopeController.getInstance().setAcquisitionActive(false);
                UIFunctions.notifyUserOfError(
                        "No fluorescence channels selected. Enable at least one channel, "
                                + "or uncheck 'Customize channel selection' to use the library defaults.",
                        "No Channels Selected");
                return;
            }

            // Resolve the channel sequence up front (synchronous). For angle-based
            // modalities this returns an empty list, so the workflow falls through
            // to the legacy angle path. The focus channel (if any) is moved to
            // position 0 so it's collected first per tile and the autofocus snap
            // shares hardware state with the first acquired image.
            final List<qupath.ext.qpsc.modality.ChannelExposure> channelExposures = ChannelResolutionService.resolve(
                    enhancedModality,
                    result.objective(),
                    result.detector(),
                    result.angleOverrides(),
                    result.focusChannelId());

            // Resolve angles via shared service. For channel-based modalities the
            // service short-circuits to an empty list (no rotation axis).
            CompletableFuture<List<qupath.ext.qpsc.modality.AngleExposure>> anglesFuture =
                    AngleResolutionService.resolve(
                            enhancedModality,
                            result.objective(),
                            result.detector(),
                            result.angleOverrides(),
                            result.wbMode());

            anglesFuture
                    .thenAccept(angleExposures -> {
                        List<Double> rotationAngles =
                                angleExposures.stream().map(ae -> ae.ticks()).collect(Collectors.toList());
                        logger.info("Rotation angles: {}", rotationAngles);
                        if (!channelExposures.isEmpty()) {
                            logger.info(
                                    "Channels: {}",
                                    channelExposures.stream()
                                            .map(qupath.ext.qpsc.modality.ChannelExposure::channelId)
                                            .collect(Collectors.toList()));
                        }

                        String boundsMode = "bounds";

                        // Sequence-step count: channels and angles are mutually exclusive
                        // axes, so the multiplier is whichever is populated. Fallback of 1
                        // for legacy single-snap modalities.
                        int sequenceSteps = !channelExposures.isEmpty()
                                ? channelExposures.size()
                                : Math.max(1, angleExposures.size());

                        // Disk space pre-check -- warn if estimated output exceeds free space at save location.
                        // The estimate is approximate (detector dims x 3 bytes x tiles x sequence-steps x headroom);
                        // actual size varies with modality, compression, and stitched output.
                        long tileCount = MinorFunctions.countTifEntriesInTileConfig(
                                List.of(Paths.get(tempTileDir, boundsMode).toString()));
                        if (tileCount > 0) {
                            long bytesPerTile = AcquisitionSpaceCheck.estimateBytesPerTilePerAngle(
                                    configManager, result.detector());
                            AcquisitionSpaceCheck.Result spaceResult = AcquisitionSpaceCheck.checkAndWarn(
                                    Paths.get(tempTileDir), tileCount, sequenceSteps, bytesPerTile);
                            if (!spaceResult.proceed()) {
                                logger.warn("Acquisition cancelled by user after low-disk-space warning");
                                return;
                            }
                        } else {
                            logger.warn(
                                    "Skipping disk space check -- tile count is 0 (TileConfiguration not readable at {})",
                                    tempTileDir);
                        }

                        // Lock stage movements and stop live viewing before acquisition starts
                        MicroscopeController.getInstance().setAcquisitionActive(true);
                        MicroscopeController.LiveViewState liveState =
                                MicroscopeController.getInstance().stopAllLiveViewing();

                        // Start socket-based acquisition
                        CompletableFuture<Void> acquisitionFuture = CompletableFuture.runAsync(() -> {
                            try {
                                // Build acquisition configuration
                                // Create a SampleSetupResult-like object for the builder
                                var sampleResult = new SampleSetupResult(
                                        result.sampleName(),
                                        result.projectsFolder(),
                                        result.modality(),
                                        result.objective(),
                                        result.detector());

                                // Channel/angle resolution happened up front (outside the async
                                // block). The captured lists are used directly below.

                                // For new projects, sample name from dialog is correct
                                AcquisitionConfigurationBuilder.AcquisitionConfiguration config =
                                        AcquisitionConfigurationBuilder.buildConfiguration(
                                                sampleResult,
                                                configFileLocation,
                                                modeWithIndex,
                                                boundsMode,
                                                angleExposures,
                                                channelExposures,
                                                actualProjectsFolder,
                                                actualSampleName,
                                                finalWSI_pixelSize_um,
                                                result.wbMode());

                                // Apply user's explicit white balance mode choice (required)
                                if (result.wbMode() != null) {
                                    config.commandBuilder().wbMode(result.wbMode());
                                } else {
                                    throw new IllegalStateException("White balance mode must be explicitly selected. "
                                            + "The acquisition dialog should always provide a wbMode value.");
                                }

                                // Per-channel intensity overrides (widefield IF). Empty/null map
                                // means "use YAML defaults" and produces no CLI flag.
                                config.commandBuilder().channelIntensityOverrides(result.channelIntensityOverrides());

                                // Focus channel for AF / first-collection ordering. Null means
                                // "no preference" and produces no CLI flag.
                                config.commandBuilder().focusChannel(result.focusChannelId());

                                // Autofocus strategy override (from Advanced panel dropdown).
                                // Null means "use YAML default" and produces no CLI flag.
                                config.commandBuilder().afStrategy(result.afStrategy());

                                logger.info(
                                        "Starting acquisition - Sample: {}, Mode: {}, Angles: {}, wbMode: {}",
                                        result.sampleName(),
                                        modeWithIndex,
                                        angleExposures.size(),
                                        result.wbMode());

                                // Let the modality handler configure builder flags (e.g., no debayer for LSM)
                                modalityHandler.configureCommandBuilder(config.commandBuilder());

                                String commandString = config.commandBuilder().buildSocketMessage();
                                MinorFunctions.saveAcquisitionCommand(
                                        commandString,
                                        actualProjectsFolder,
                                        actualSampleName,
                                        modeWithIndex,
                                        boundsMode);

                                MicroscopeController.getInstance().startAcquisition(config.commandBuilder());

                                // Monitor acquisition
                                MicroscopeSocketClient socketClient =
                                        MicroscopeController.getInstance().getSocketClient();

                                // Progress expectation: channels and angles are mutually exclusive,
                                // so use whichever axis is populated.
                                int expectedFilesPerTile = !channelExposures.isEmpty()
                                        ? channelExposures.size()
                                        : Math.max(1, angleExposures.size());
                                // Z-stack multiplier: each tile captures N Z-planes
                                if (PersistentPreferences.isZStackEnabled()) {
                                    double zRange = PersistentPreferences.getZStackRange();
                                    double zStep = PersistentPreferences.getZStackStep();
                                    if (zStep > 0) {
                                        int nZPlanes = (int) Math.ceil(zRange / zStep) + 1;
                                        expectedFilesPerTile *= nZPlanes;
                                    }
                                }
                                int expectedFiles = MinorFunctions.countTifEntriesInTileConfig(
                                                List.of(Paths.get(tempTileDir, boundsMode)
                                                        .toString()))
                                        * expectedFilesPerTile;

                                AtomicInteger progressCounter = new AtomicInteger(0);

                                UIFunctions.ProgressHandle progressHandle = null;
                                if (expectedFiles > 0) {
                                    // Pass expectedFilesPerTile as the steps-per-position
                                    // so the progress dialog displays physical XY
                                    // positions, not raw image file counts. For 4-channel
                                    // widefield IF with 10 tiles this shows "10 of 10
                                    // positions" instead of the previously-wrong "40 of 40".
                                    progressHandle = UIFunctions.showProgressBarAsync(
                                            progressCounter, expectedFiles, 300000, true, expectedFilesPerTile);

                                    final UIFunctions.ProgressHandle finalHandle = progressHandle;
                                    progressHandle.setCancelCallback(v -> {
                                        logger.info("User requested acquisition cancellation");
                                        try {
                                            socketClient.cancelAcquisition();
                                        } catch (IOException e) {
                                            logger.error("Failed to send cancel command", e);
                                        }
                                    });
                                }

                                Thread.sleep(1000);

                                // Show Tiles support: display each new tile in the Live Viewer
                                // as it is acquired. The tile directory layout is
                                // tempTileDir/<annotationName>/... where annotationName is
                                // boundsMode for this workflow (e.g. "bounds").
                                final String tileDirPath =
                                        Paths.get(tempTileDir, boundsMode).toString();
                                final AtomicInteger lastTileProgress = new AtomicInteger(0);

                                AcquisitionMonitorService.monitorAndHandle(socketClient,
                                        AcquisitionMonitorService.MonitorConfig.create()
                                                .progress(progress -> {
                                                    progressCounter.set(progress.current);
                                                    if (qupath.ext.qpsc.ui.liveviewer.LiveViewerWindow
                                                                    .isShowTilesEnabled()
                                                            && progress.current > lastTileProgress.get()) {
                                                        lastTileProgress.set(progress.current);
                                                        qupath.ext.qpsc.ui.liveviewer.LiveViewerWindow
                                                                .scanAndShowLatestTile(tileDirPath);
                                                    }
                                                })
                                                .manualFocus(retriesRemaining -> ManualFocusHandler.handle(
                                                        socketClient,
                                                        retriesRemaining,
                                                        null,
                                                        null,
                                                        UIFunctions::showManualFocusDialog))
                                                .tileDir(tileDirPath)
                                                .pollInterval(500)
                                                .timeout(300000));

                                if (progressHandle != null) {
                                    progressHandle.close();
                                }

                            } catch (java.util.concurrent.CancellationException e) {
                                // Cancellation is expected - don't log as error, just re-throw to prevent stitching
                                throw e;
                            } catch (Exception e) {
                                logger.error("Acquisition failed", e);
                                throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
                            }
                        });

                        // Restore live viewing and release acquisition lock when acquisition completes
                        // (stitching continues in background but does not use the stage)
                        acquisitionFuture.whenComplete((ignored0, ex0) -> {
                            MicroscopeController.getInstance().restoreLiveViewState(liveState);
                            MicroscopeController.getInstance().setAcquisitionActive(false);
                        });

                        // Handle stitching after acquisition
                        acquisitionFuture
                                .thenCompose(ignored -> {
                                    CompletableFuture<Void> stitchFuture = StitchingHelper.performRegionStitching(
                                                    boundsMode,
                                                    new SampleSetupResult(
                                                            result.sampleName(),
                                                            result.projectsFolder(),
                                                            result.modality(),
                                                            result.objective(),
                                                            result.detector()),
                                                    modeWithIndex,
                                                    angleExposures,
                                                    finalWSI_pixelSize_um,
                                                    qupathGUI,
                                                    project,
                                                    STITCH_EXECUTOR,
                                                    modalityHandler,
                                                    actualSampleName, // Use derived sample name for correct path
                                                    actualProjectsFolder, // Use derived projectsFolder for correct path
                                                    // Stage bounds carried through so the stitched
                                                    // output(s) get an automatic pixel->stage
                                                    // alignment registered at import time, which
                                                    // enables Live Viewer Move-to-centroid / click-
                                                    // to-center on the resulting image without a
                                                    // separate manual alignment step.
                                                    result.x1(),
                                                    result.y1(),
                                                    result.x2(),
                                                    result.y2())
                                            .thenRun(() -> {
                                                UIFunctions.playWorkflowCompletionBeep();

                                                String msg = angleExposures.size() > 1
                                                        ? "All angles stitched successfully"
                                                        : "Stitching complete";
                                                Platform.runLater(() -> qupath.fx.dialogs.Dialogs.showInfoNotification(
                                                        "Stitching complete", msg));

                                                NotificationService.getInstance()
                                                        .notify(
                                                                "Stitching Complete",
                                                                "Sample \"" + actualSampleName + "\" - "
                                                                        + angleExposures.size() + " angle(s)\n"
                                                                        + "Project: " + actualProjectsFolder,
                                                                NotificationPriority.DEFAULT,
                                                                NotificationEvent.STITCHING_COMPLETE);
                                            })
                                            .exceptionally(ex -> {
                                                logger.error("Stitching failed", ex);
                                                Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                                        "Stitching failed:\n" + ex.getMessage(), "Stitching Error"));

                                                NotificationService.getInstance()
                                                        .notify(
                                                                "Stitching Error",
                                                                "Sample \"" + actualSampleName + "\" failed\n"
                                                                        + "Error: " + ex.getMessage(),
                                                                NotificationPriority.HIGH,
                                                                NotificationEvent.STITCHING_ERROR);
                                                return null;
                                            });

                                    // Handle cleanup after stitching
                                    stitchFuture.thenRun(() -> {
                                        String handling = QPPreferenceDialog.getTileHandlingMethodProperty();
                                        if ("Delete".equals(handling)) {
                                            TileProcessingUtilities.deleteTilesAndFolder(tempTileDir);
                                        } else if ("Zip".equals(handling)) {
                                            TileProcessingUtilities.zipTilesAndMove(tempTileDir);
                                            TileProcessingUtilities.deleteTilesAndFolder(tempTileDir);
                                        }
                                    });

                                    return stitchFuture;
                                })
                                .exceptionally(ex -> {
                                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                                    if (cause instanceof java.util.concurrent.CancellationException) {
                                        logger.info("Acquisition workflow cancelled by user");
                                    } else {
                                        logger.error("Workflow failed", ex);
                                        String errorMessage =
                                                cause.getMessage() != null ? cause.getMessage() : ex.getMessage();
                                        Platform.runLater(
                                                () -> UIFunctions.notifyUserOfError(errorMessage, "Acquisition Error"));

                                        NotificationService.getInstance()
                                                .notify(
                                                        "Acquisition Error",
                                                        "Sample \"" + actualSampleName + "\" failed\n" + "Error: "
                                                                + errorMessage,
                                                        NotificationPriority.HIGH,
                                                        NotificationEvent.ACQUISITION_ERROR);
                                    }
                                    return null;
                                });
                    })
                    .exceptionally(ex -> {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        String message = cause.getMessage();

                        // User cancellation from dialogs (angle selection, background mismatch)
                        // can arrive as CancellationException or as a message containing
                        // specific cancel tokens.
                        boolean isCancellation = cause instanceof java.util.concurrent.CancellationException
                                || (message != null
                                        && (message.contains("BACKGROUND_MISMATCH_CANCELLED")
                                                || message.contains("ANGLE_SELECTION_CANCELLED")));

                        if (isCancellation) {
                            logger.info("Acquisition cancelled by user");
                            Platform.runLater(() -> qupath.fx.dialogs.Dialogs.showInfoNotification(
                                    "Acquisition Cancelled", "Acquisition was cancelled by user request"));
                        } else {
                            logger.error("Workflow failed", ex);
                            String msg = message != null ? message : "Unknown error occurred";
                            final String finalErrorMsg = msg;
                            Platform.runLater(() -> UIFunctions.notifyUserOfError(finalErrorMsg, "Acquisition Error"));
                        }
                        return null;
                    });
        });
    }
}
