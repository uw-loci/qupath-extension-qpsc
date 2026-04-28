package qupath.ext.qpsc.controller.workflow;

import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.ExistingImageWorkflowV2.WorkflowState;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.ChannelExposure;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.AngleResolutionService;
import qupath.ext.qpsc.service.AnnotationOrderingService;
import qupath.ext.qpsc.service.ChannelResolutionService;
import qupath.ext.qpsc.service.ManualFocusHandler;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.AnnotationAcquisitionDialog;
import qupath.ext.qpsc.ui.DualProgressDialog;
import qupath.ext.qpsc.ui.SaturationSummaryDialog;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.AcquisitionConfigurationBuilder;
import qupath.ext.qpsc.utilities.AcquisitionSpaceCheck;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.ext.qpsc.utilities.LiveTileMeasurementPoller;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.MinorFunctions;
import qupath.ext.qpsc.utilities.StitchingConfiguration;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.ext.qpsc.utilities.ZFocusPredictionModel;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.scripting.QP;

/**
 * Manages the acquisition phase of the microscope workflow.
 *
 * <p>This class orchestrates the complete acquisition process including:
 * <ul>
 *   <li>Annotation validation with user confirmation</li>
 *   <li>Rotation angle configuration for multi-modal imaging</li>
 *   <li>Tile generation and transformation to stage coordinates</li>
 *   <li>Acquisition monitoring with progress tracking</li>
 *   <li>Automatic stitching queue management</li>
 *   <li>Error handling and user cancellation support</li>
 * </ul>
 *
 * <p>The acquisition process is performed sequentially for each annotation to ensure
 * proper resource management and allow for user intervention if needed.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class AcquisitionManager {
    private static final Logger logger = LoggerFactory.getLogger(AcquisitionManager.class);

    /** Maximum time to wait for acquisition completion (5 minutes) */
    private static final int ACQUISITION_TIMEOUT_MS = 300000;

    /** Filename for acquisition metadata written to the scan type directory */
    private static final String ACQUISITION_INFO_FILENAME = "acquisition_info.txt";

    /** Single-threaded executor for stitching operations to prevent overwhelming system resources */
    private static final ExecutorService STITCH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stitching-queue");
        t.setDaemon(true);
        return t;
    });

    /** Shared scheduled executor for live NDJSON tile-measurement polling. */
    private static final ScheduledExecutorService LIVE_POLL_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tile-measurement-live-poller");
        t.setDaemon(true);
        return t;
    });

    private final QuPathGUI gui;
    private final WorkflowState state;
    private DualProgressDialog dualProgressDialog;

    /** Z-focus prediction model for tilt correction across the slide */
    private final ZFocusPredictionModel zFocusModel = new ZFocusPredictionModel();

    /** Last known good Z from a completed acquisition -- persists across annotation resets
     *  so the next annotation's AF search is centered near reality, not the user's initial Z. */
    private Double lastAcquisitionZ = null;

    /** Parent image entry captured at session start -- stable reference for metadata inheritance.
     *  Do NOT look this up from gui.getImageData() later, as the viewer state can change
     *  when stitching dialogs close or other images are opened. */
    private ProjectImageEntry<BufferedImage> parentEntry = null;

    /** ImageData captured at session start -- provides stable access to the WSI server
     *  for tile reading (e.g., WSI tissue scoring) even after the viewer loses its image. */
    private ImageData<BufferedImage> capturedImageData = null;

    /**
     * Returns the hierarchy for the acquisition session image.
     * Uses capturedImageData (stable, viewer-independent) when available,
     * falling back to the live viewer only if no session was captured
     * (e.g., bounded acquisition with no parent image).
     */
    private PathObjectHierarchy getSessionHierarchy() {
        if (capturedImageData != null) {
            return capturedImageData.getHierarchy();
        }
        if (gui.getImageData() != null) {
            return gui.getImageData().getHierarchy();
        }
        return QP.getCurrentHierarchy();
    }

    /**
     * Checks whether the viewer is currently showing the acquisition session image.
     * Used to decide whether to fire UI refresh events (which are pointless if
     * the user is looking at a different image).
     */
    private boolean isSessionImageActive() {
        return capturedImageData != null && gui.getImageData() == capturedImageData;
    }

    /**
     * Creates a new acquisition manager.
     *
     * @param gui The QuPath GUI instance
     * @param state The current workflow state containing all necessary information
     */
    public AcquisitionManager(QuPathGUI gui, WorkflowState state) {
        this.gui = gui;
        this.state = state;
    }

    /**
     * Executes the complete acquisition phase.
     *
     * <p>This method orchestrates the entire acquisition workflow:
     * <ol>
     *   <li>Validates annotations with user confirmation</li>
     *   <li>Retrieves rotation angles for the imaging modality</li>
     *   <li>Prepares tiles for acquisition</li>
     *   <li>Processes each annotation sequentially</li>
     * </ol>
     *
     * @return CompletableFuture containing the updated workflow state, or null if cancelled/failed
     */
    public CompletableFuture<WorkflowState> execute() {
        logger.info("Starting acquisition phase");

        // Validate stitching settings -- loop to let user fix preferences if needed
        return StitchingConfiguration.validateWithRetryAsync()
                .thenCompose(valid -> {
                    if (!valid) {
                        throw new CancellationException("Cancelled due to invalid stitching settings");
                    }
                    return validateAnnotations();
                })
                .thenCompose(valid -> {
                    if (!valid) {
                        logger.info("Annotation validation failed or cancelled");
                        return CompletableFuture.completedFuture(null);
                    }
                    return getRotationAngles();
                })
                .thenCompose(this::prepareForAcquisition)
                .thenCompose(this::checkDiskSpace)
                .thenCompose(this::processAnnotations)
                .thenApply(success -> {
                    if (success) {
                        logger.info("Acquisition phase completed successfully");
                        // Save timing data to persistent preferences for future estimates
                        saveTimingDataToPreferences();
                        return state;
                    }
                    // User cancelled (e.g. dismissed annotation dialog) - not an error
                    throw new CancellationException("Acquisition cancelled by user");
                });
    }

    /**
     * Saves the mean per-file time from the current acquisition to persistent
     * preferences so future runs get a more accurate initial estimate. The
     * mean naturally includes autofocus overhead at its actual occurrence
     * frequency, so a single scalar is sufficient -- no AF decomposition.
     */
    private void saveTimingDataToPreferences() {
        if (dualProgressDialog == null) {
            logger.debug("No progress dialog - skipping timing data save");
            return;
        }

        long meanTileTimeMs = dualProgressDialog.getFinalTimingData();
        if (meanTileTimeMs <= 0) {
            logger.debug("Insufficient timing data to save");
            return;
        }

        String modality = state.sample.modality();
        String objective = state.sample.objective();

        PersistentPreferences.updateTimingData(meanTileTimeMs, modality, objective);

        logger.info("Saved timing data for {}/{}: {} ms/file", modality, objective, meanTileTimeMs);
    }

    /**
     * Validates annotations with user confirmation dialog.
     *
     * <p>Shows a dialog listing all valid annotations and allows the user to confirm
     * before proceeding with acquisition. If annotation classes were already selected
     * earlier in the workflow (e.g., by ExistingImageWorkflowV2), this method skips
     * the dialog and uses the pre-selected classes.
     *
     * @return CompletableFuture with true if user confirms, false if cancelled
     */
    private CompletableFuture<Boolean> validateAnnotations() {
        // If classes were already selected earlier in the workflow, skip the dialog
        if (state.selectedAnnotationClasses != null && !state.selectedAnnotationClasses.isEmpty()) {
            logger.info(
                    "Using {} pre-selected annotation classes: {}",
                    state.selectedAnnotationClasses.size(),
                    state.selectedAnnotationClasses);
            return CompletableFuture.completedFuture(true);
        }

        // Get all unique annotation classes in current image
        Set<PathClass> existingClasses = MinorFunctions.getExistingClassifications(QP.getCurrentImageData());
        Set<String> existingClassNames =
                existingClasses.stream().map(PathClass::toString).collect(Collectors.toSet());

        logger.info("Found {} unique annotation classes: {}", existingClassNames.size(), existingClassNames);

        // Get preferences
        List<String> preselected = PersistentPreferences.getSelectedAnnotationClasses();

        // Show annotation selection dialog
        // Note: Modality-specific options (like PPM angles) come from the main
        // acquisition dialog's Advanced Options, not this annotation selection dialog
        return AnnotationAcquisitionDialog.showDialog(existingClassNames, preselected)
                .thenApply(result -> {
                    if (!result.proceed || result.selectedClasses.isEmpty()) {
                        logger.info("Acquisition cancelled or no classes selected");
                        return false;
                    }

                    // Store selected classes in state
                    state.selectedAnnotationClasses = result.selectedClasses;
                    logger.info(
                            "User selected {} classes for acquisition: {}",
                            result.selectedClasses.size(),
                            result.selectedClasses);

                    return true;
                });
    }
    /**
     * Retrieves rotation angles configured for the imaging modality.
     *
     * <p>For polarized light imaging or other multi-angle acquisitions, this method
     * retrieves the configured rotation angles and decimal exposure times.
     * If the user provided angle overrides in the annotation dialog, those will be
     * applied following the BoundedAcquisitionWorkflow pattern.
     *
     * @return CompletableFuture containing list of rotation angles with exposure settings
     */
    private CompletableFuture<List<AngleExposure>> getRotationAngles() {
        // Resolution keys off the enhanced profile name (e.g. "Fluorescence_10x")
        // so acquisition_profiles[] lookups and per-profile channel-library reads
        // land on the right entry. state.sample.modality() is the base name
        // ("Fluorescence") and won't match.
        String profileKey = qupath.ext.qpsc.utilities.ObjectiveUtils.createEnhancedFolderName(
                state.sample.modality(), state.sample.objective());
        return AngleResolutionService.resolve(
                profileKey, state.sample.objective(), state.sample.detector(), state.angleOverrides, state.wbMode);
    }

    /**
     * Prepares annotations for acquisition by regenerating tiles.
     *
     * <p>This method:
     * <ul>
     *   <li>Retrieves current valid annotations</li>
     *   <li>Cleans up old tiles and directories</li>
     *   <li>Creates fresh tiles based on camera FOV</li>
     *   <li>Transforms tile coordinates to stage space</li>
     * </ul>
     *
     * @param angleExposures List of rotation angles, or null for single acquisition
     * @return CompletableFuture with angle exposures for next phase
     */
    private CompletableFuture<List<AngleExposure>> prepareForAcquisition(List<AngleExposure> angleExposures) {

        return CompletableFuture.supplyAsync(() -> {
            // Check for cancellation (null angleExposures indicates cancelled/failed)
            if (angleExposures == null) {
                logger.info("Skipping acquisition preparation - workflow was cancelled");
                return null;
            }

            logger.info("Preparing for acquisition with {} angles", angleExposures.size());

            // Capture parent entry NOW while the viewer definitely has the right image.
            // This anchors the entire session to this specific image so that switching
            // images in the viewer during acquisition does not crash or misdirect data.
            if (capturedImageData == null) {
                @SuppressWarnings("unchecked")
                Project<BufferedImage> captureProject =
                        (Project<BufferedImage>) state.projectInfo.getCurrentProject();
                if (gui.getViewer().hasServer() && gui.getImageData() != null && captureProject != null) {
                    capturedImageData = gui.getImageData();
                    parentEntry = captureProject.getEntry(capturedImageData);
                    logger.info(
                            "Captured parent entry for session: {}",
                            parentEntry != null ? parentEntry.getImageName() : "null");
                } else {
                    logger.warn(
                            "No parent entry available at prep start -- "
                                    + "tile display will use fallback hierarchy");
                }
            }

            // Save project entry state before acquisition starts
            try {
                var imageData = QP.getCurrentImageData();
                var entry = QP.getProjectEntry();
                if (imageData != null && entry != null) {
                    entry.saveImageData(imageData);
                    logger.info("Saved project entry state before acquisition");
                } else {
                    logger.warn("Could not save project entry state - imageData or entry is null");
                }
            } catch (Exception e) {
                logger.error("Failed to save project entry state before acquisition", e);
            }

            // Get current annotations using GUI hierarchy for reliable retrieval
            List<PathObject> currentAnnotations =
                    AnnotationHelper.getCurrentValidAnnotations(gui, state.selectedAnnotationClasses);
            if (currentAnnotations.isEmpty()) {
                throw new RuntimeException("No valid annotations for acquisition");
            }

            logger.info("Found {} annotations to acquire", currentAnnotations.size());

            // Clean up old tiles
            TileHelper.deleteAllTiles(getSessionHierarchy(), state.sample.modality());
            TileHelper.cleanupStaleFolders(state.projectInfo.getTempTileDirectory(), currentAnnotations);

            // Create fresh tiles
            TileHelper.createTilesForAnnotations(
                    currentAnnotations,
                    state.sample,
                    state.projectInfo.getTempTileDirectory(),
                    state.projectInfo.getImagingModeWithIndex(),
                    state.pixelSize);

            // Transform tile configurations to stage coordinates
            try {
                List<String> modifiedDirs = TransformationFunctions.transformTileConfiguration(
                        state.projectInfo.getTempTileDirectory(), state.transform);
                logger.info("Transformed tile configurations for: {}", modifiedDirs);
            } catch (IOException e) {
                logger.error("Failed to transform tile configurations", e);
                throw new RuntimeException("Failed to transform tile configurations", e);
            }

            state.annotations = currentAnnotations;
            return angleExposures;
        });
    }

    /**
     * Pre-flight disk space check. Sums tile counts across all annotations,
     * estimates bytes using detector dimensions, and warns the user if the
     * estimate exceeds free space at the save location. Honors the
     * "Warn On Low Disk Space" preference and the in-dialog opt-out checkbox.
     *
     * <p>Cancellation short-circuits the rest of the chain by returning a null
     * angle list, matching the existing null-propagation pattern used by
     * {@link #processAnnotations(List)}.
     */
    private CompletableFuture<List<AngleExposure>> checkDiskSpace(List<AngleExposure> angleExposures) {
        if (angleExposures == null || state.annotations == null || state.annotations.isEmpty()) {
            return CompletableFuture.completedFuture(angleExposures);
        }

        return CompletableFuture.supplyAsync(() -> {
            String tempTileDir = state.projectInfo.getTempTileDirectory();
            long totalTiles = 0;
            for (PathObject ann : state.annotations) {
                String tileDirPath = Paths.get(tempTileDir, ann.getName()).toString();
                totalTiles += MinorFunctions.countTifEntriesInTileConfig(List.of(tileDirPath));
            }

            if (totalTiles <= 0) {
                logger.warn(
                        "Skipping disk space check -- tile count is 0 across {} annotations", state.annotations.size());
                return angleExposures;
            }

            MicroscopeConfigManager configManager =
                    MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty());
            long bytesPerTile =
                    AcquisitionSpaceCheck.estimateBytesPerTilePerAngle(configManager, state.sample.detector());

            AcquisitionSpaceCheck.Result result = AcquisitionSpaceCheck.checkAndWarn(
                    Paths.get(tempTileDir), totalTiles, angleExposures.size(), bytesPerTile);

            if (!result.proceed()) {
                logger.warn("Acquisition cancelled by user after low-disk-space warning");
                return null;
            }
            return angleExposures;
        });
    }

    /**
     * Processes all annotations for acquisition.
     *
     * <p>Annotations are processed sequentially to:
     * <ul>
     *   <li>Provide clear progress feedback</li>
     *   <li>Allow cancellation between annotations</li>
     *   <li>Prevent resource exhaustion</li>
     *   <li>Enable immediate stitching after each acquisition</li>
     * </ul>
     *
     * @param angleExposures Rotation angles for multi-modal acquisition
     * @return CompletableFuture with true if all successful, false if any failed/cancelled
     */
    private CompletableFuture<Boolean> processAnnotations(List<AngleExposure> angleExposures) {

        // Check for cancellation (null angleExposures indicates cancelled/failed)
        if (angleExposures == null) {
            logger.info("Skipping annotation processing - workflow was cancelled");
            return CompletableFuture.completedFuture(false);
        }

        if (state.annotations.isEmpty()) {
            logger.warn("No annotations to process");
            return CompletableFuture.completedFuture(false);
        }

        // Lock stage movements before stopping live viewing to prevent user interference
        MicroscopeController.getInstance().setAcquisitionActive(true);

        // Stop live viewing before acquisition starts to prevent hardware conflicts
        MicroscopeController.LiveViewState liveState =
                MicroscopeController.getInstance().stopAllLiveViewing();

        // Reorder annotations to prioritize the one containing the refinement tile (if any)
        prioritizeRefinementAnnotation();

        // Sort remaining annotations by proximity to the first (for efficient travel and tilt model building)
        if (state.transform != null && state.annotations.size() > 1) {
            state.annotations = AnnotationOrderingService.sortByProximity(state.annotations, state.transform);
            logger.info("Annotations ordered by proximity for tilt model optimization");
        }

        // Reset Z tracking for this acquisition session.
        // The prediction model accumulates data across annotations (one point per
        // annotation) and fits a tilt plane.  After 4 annotations it can predict Z
        // for any position on the slide, handling long jumps correctly.
        // Before enough points accumulate, lastAcquisitionZ is the fallback.
        lastAcquisitionZ = null;
        zFocusModel.reset();

        // Defensive re-check: if capturedImageData was not set during prepareForAcquisition
        // (e.g. bounded acquisition with no parent image), try once more here.
        if (capturedImageData == null && gui.getViewer().hasServer() && gui.getImageData() != null) {
            @SuppressWarnings("unchecked")
            Project<BufferedImage> captureProject =
                    (Project<BufferedImage>) state.projectInfo.getCurrentProject();
            if (captureProject != null) {
                capturedImageData = gui.getImageData();
                parentEntry = captureProject.getEntry(capturedImageData);
                logger.info(
                        "Late-captured parent entry for metadata: {}",
                        parentEntry != null ? parentEntry.getImageName() : "null");
            }
        }

        // Show initial progress notification
        showAcquisitionStartNotification(angleExposures);

        // Create and show dual progress dialog on JavaFX Application Thread
        CompletableFuture<DualProgressDialog> dialogSetup = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                DualProgressDialog dialog = new DualProgressDialog(state.annotations.size(), true);
                dialog.setCancelCallback(v -> {
                    logger.info("User requested workflow cancellation via dual progress dialog");
                    try {
                        MicroscopeController.getInstance().getSocketClient().cancelAcquisition();
                    } catch (IOException e) {
                        logger.error("Failed to send cancel command", e);
                    }
                });
                dialog.show();
                dualProgressDialog = dialog; // Set field for other method access
                dialogSetup.complete(dialog);
            } catch (Exception e) {
                dialogSetup.completeExceptionally(e);
            }
        });

        // Wait for dialog setup to complete and get final reference
        final DualProgressDialog progressDialog = getDialogSafely(dialogSetup);

        // If dialog creation failed, restore live state and return early
        if (progressDialog == null) {
            MicroscopeController.getInstance().restoreLiveViewState(liveState);
            MicroscopeController.getInstance().setAcquisitionActive(false);
            return CompletableFuture.completedFuture(false);
        }

        // Set initial steps-per-position for the progress dialog based on
        // the angle count we already have. For channel-based acquisitions
        // (widefield IF) the correct value depends on channelExposures,
        // which isn't resolved until performSingleAnnotationAcquisition
        // runs; that method corrects this value once channels are known.
        int initialStepsPerPosition = (angleExposures != null && !angleExposures.isEmpty()) ? angleExposures.size() : 1;
        final int finalInitialSteps = initialStepsPerPosition;
        Platform.runLater(() -> progressDialog.setStepsPerPosition(finalInitialSteps));

        // Pre-compute file counts per annotation so the dialog can estimate
        // time for future annotations accurately (instead of assuming all are
        // the same size as the current one).
        List<Integer> perAnnotationFileCounts = new ArrayList<>();
        for (PathObject ann : state.annotations) {
            String tileDirPath = Paths.get(state.projectInfo.getTempTileDirectory(), ann.getName())
                    .toString();
            int tilesPerAngle = MinorFunctions.countExpectedTilesWithRetry(List.of(tileDirPath), 3, 200);
            if (tilesPerAngle == 0) {
                tilesPerAngle = estimateTileCount(ann);
            }
            perAnnotationFileCounts.add(tilesPerAngle * initialStepsPerPosition);
        }
        logger.info("Per-annotation file counts: {}", perAnnotationFileCounts);

        // Process each annotation sequentially
        CompletableFuture<Boolean> acquisitionChain = CompletableFuture.completedFuture(true);

        for (int i = 0; i < state.annotations.size(); i++) {
            final PathObject annotation = state.annotations.get(i);
            final int index = i + 1;
            final int total = state.annotations.size();
            // Future tile counts = file counts for annotations after this one
            final List<Integer> futureCountsForThisStep = perAnnotationFileCounts.subList(
                    Math.min(i + 1, perAnnotationFileCounts.size()), perAnnotationFileCounts.size());
            // Copy to avoid subList reference issues across async boundaries
            final List<Integer> futureCounts = new ArrayList<>(futureCountsForThisStep);

            acquisitionChain = acquisitionChain.thenCompose(previousSuccess -> {
                if (!previousSuccess) {
                    logger.debug("Skipping annotation {} -- previous acquisition failed", annotation.getName());
                    return CompletableFuture.completedFuture(false);
                }

                logger.info("Processing annotation {} of {}: {}", index, total, annotation.getName());

                // Tell the dialog about remaining annotations' sizes for accurate time estimates
                if (progressDialog != null) {
                    Platform.runLater(() -> progressDialog.setFutureTileCounts(futureCounts));
                }

                showProgressNotification(index, total, annotation.getName());

                return performSingleAnnotationAcquisition(annotation, angleExposures, progressDialog)
                        .thenApply(success -> {
                            if (success) {
                                // Capture final Z for tilt correction model
                                try {
                                    MicroscopeSocketClient socketClient =
                                            MicroscopeController.getInstance().getSocketClient();
                                    Double finalZ = socketClient.getLastAcquisitionFinalZ();
                                    if (finalZ != null) {
                                        // Persist across annotation resets for Z-hint fallback
                                        lastAcquisitionZ = finalZ;

                                        if (state.transform != null) {
                                            double[] stageCoords =
                                                    TransformationFunctions.transformQuPathFullResToStage(
                                                            new double[] {
                                                                annotation
                                                                        .getROI()
                                                                        .getCentroidX(),
                                                                annotation
                                                                        .getROI()
                                                                        .getCentroidY()
                                                            },
                                                            state.transform);
                                            zFocusModel.addDataPoint(stageCoords[0], stageCoords[1], finalZ);
                                            logger.info(
                                                    "Updated Z-focus model: {} points, residual error: {} um",
                                                    zFocusModel.getPointCount(),
                                                    String.format("%.2f", zFocusModel.calculateResidualError()));
                                        }
                                    }
                                    // Clear for next acquisition
                                    socketClient.clearLastAcquisitionFinalZ();
                                } catch (Exception e) {
                                    logger.warn("Could not update Z-focus model: {}", e.getMessage());
                                }

                                // Mark annotation complete in dual progress dialog
                                if (progressDialog != null) {
                                    Platform.runLater(() -> progressDialog.completeCurrentAnnotation());
                                }
                                // Launch stitching asynchronously after successful acquisition
                                launchStitching(annotation, angleExposures);
                            } else {
                                // Show error in dual progress dialog
                                if (progressDialog != null) {
                                    Platform.runLater(() ->
                                            progressDialog.showError("Failed to acquire " + annotation.getName()));
                                }
                            }
                            return success;
                        });
            });
        }

        return acquisitionChain.whenComplete((result, error) -> {
            // Release acquisition lock and restore live viewing now that all acquisitions are done
            // (stitching continues in background but does not use the stage)
            MicroscopeController.getInstance().restoreLiveViewState(liveState);
            MicroscopeController.getInstance().setAcquisitionActive(false);

            // Close dual progress dialog when workflow completes or fails
            if (progressDialog != null) {
                if (error != null) {
                    Platform.runLater(() -> progressDialog.showError("Workflow failed: " + error.getMessage()));
                } else if (!result && !progressDialog.isCancelled()) {
                    // Only show error if not user-initiated cancellation
                    Platform.runLater(() -> progressDialog.showError("Workflow stopped unexpectedly"));
                }
                // Dialog will auto-close after completion or error display
            }
        });
    }

    /**
     * Performs acquisition for a single annotation.
     *
     * <p>This method:
     * <ul>
     *   <li>Builds the acquisition command with all parameters</li>
     *   <li>Starts the acquisition on the microscope</li>
     *   <li>Monitors progress with cancellation support</li>
     * </ul>
     *
     * @param annotation The annotation to acquire
     * @param angleExposures Rotation angles for this acquisition
     * @return CompletableFuture with true if successful, false if failed/cancelled
     */
    private CompletableFuture<Boolean> performSingleAnnotationAcquisition(
            PathObject annotation, List<AngleExposure> angleExposures, DualProgressDialog progressDialog) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Starting acquisition for annotation: {}", annotation.getName());

                // Get configuration file path
                String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configFileLocation);

                // Extract modality base name
                String modalityWithIndex = state.projectInfo.getImagingModeWithIndex();
                String baseModality = state.sample.modality();

                // Get WSI pixel size using explicit hardware configuration
                double WSI_pixelSize_um;
                try {
                    WSI_pixelSize_um = configManager.getPixelSize(state.sample.objective(), state.sample.detector());
                    logger.debug(
                            "Using explicit hardware config: obj={}, det={}, px={}",
                            state.sample.objective(),
                            state.sample.detector(),
                            WSI_pixelSize_um);
                } catch (IllegalArgumentException e) {
                    throw new RuntimeException("Failed to get pixel size for selected hardware configuration: "
                            + baseModality + "/" + state.sample.objective() + "/" + state.sample.detector() + " - "
                            + e.getMessage());
                }

                // Use the actual sample name from projectInfo (derived from project folder)
                // This ensures the path matches where tiles were written
                String actualSampleName = state.projectInfo.getSampleName();

                // Resolution keys off the enhanced profile name (e.g. "Fluorescence_10x"),
                // NOT the base modality name ("Fluorescence"). Profiles are indexed by the
                // enhanced key and so is the per-profile channel library lookup.
                String profileKey = qupath.ext.qpsc.utilities.ObjectiveUtils.createEnhancedFolderName(
                        state.sample.modality(), state.sample.objective());

                // Refuse to start if the user actively deselected every channel on a
                // channel-based modality -- surfacing this as a clear error beats silently
                // falling back to library defaults (which is what the same empty map means
                // when it comes from a non-channel UI).
                if (ChannelResolutionService.isEmptySelectionForChannelBasedModality(
                        profileKey, state.sample.objective(), state.sample.detector(), state.angleOverrides)) {
                    throw new RuntimeException("No fluorescence channels selected. Enable at least one channel "
                            + "in the acquisition dialog, or uncheck 'Customize channel selection' "
                            + "to use the library defaults.");
                }

                // Resolve per-channel sequence for channel-based modalities (widefield IF).
                // Returns empty list for angle-based modalities, in which case
                // the builder falls through to the standard angle path. The focus
                // channel (if any) is moved to position 0 so it's collected first
                // per tile and AF runs against the same hardware state.
                List<ChannelExposure> channelExposures = ChannelResolutionService.resolve(
                        profileKey,
                        state.sample.objective(),
                        state.sample.detector(),
                        state.angleOverrides,
                        state.focusChannelId);

                // Build acquisition configuration using shared builder
                AcquisitionConfigurationBuilder.AcquisitionConfiguration config =
                        AcquisitionConfigurationBuilder.buildConfiguration(
                                state.sample,
                                configFileLocation,
                                modalityWithIndex,
                                annotation.getName(),
                                angleExposures,
                                channelExposures,
                                state.sample.projectsFolder().getAbsolutePath(),
                                actualSampleName, // Use actual sample name from project folder
                                WSI_pixelSize_um,
                                state.wbMode);

                // Apply user's explicit white balance mode choice (required)
                if (state.wbMode != null) {
                    config.commandBuilder().wbMode(state.wbMode);
                } else {
                    throw new IllegalStateException("White balance mode must be explicitly selected. "
                            + "The acquisition dialog should always provide a wbMode value.");
                }

                // Per-channel intensity overrides (widefield IF / BF+IF). Empty map
                // means "use YAML defaults" and produces no CLI flag.
                if (state.channelIntensityOverrides != null) {
                    config.commandBuilder().channelIntensityOverrides(state.channelIntensityOverrides);
                }

                // Focus channel for AF / first-collection ordering. Null means
                // "no preference" and produces no CLI flag.
                config.commandBuilder().focusChannel(state.focusChannelId);

                // Autofocus strategy override (from Advanced panel dropdown).
                // Null means "use YAML default" and produces no CLI flag.
                config.commandBuilder().afStrategy(state.afStrategy);

                logger.info("Acquisition parameters for {}:", annotation.getName());
                logger.info("  Config: {}", configFileLocation);
                logger.info("  Sample: {}", actualSampleName);
                logger.info(
                        "  Hardware: {} / {} @ {} um/px",
                        config.objective(),
                        config.detector(),
                        config.WSI_pixelSize_um());
                logger.info(
                        "  Autofocus: {} tiles, {} steps, {} um range",
                        config.afTiles(),
                        config.afSteps(),
                        config.afRange());
                logger.info("  Processing: {}", config.processingSteps());
                logger.info("  White balance: wbMode={}", state.wbMode);
                if (config.bgEnabled()) {
                    logger.info("  Background correction: {} method from {}", config.bgMethod(), config.bgFolder());
                }
                if (angleExposures != null && !angleExposures.isEmpty()) {
                    logger.info("  Angles: {}", angleExposures);
                }
                // Let the modality handler configure builder flags (e.g., no debayer for LSM)
                ModalityRegistry.getHandler(baseModality).configureCommandBuilder(config.commandBuilder());

                String commandString = config.commandBuilder().buildSocketMessage();
                MinorFunctions.saveAcquisitionCommand(
                        commandString,
                        state.sample.projectsFolder().getAbsolutePath(),
                        actualSampleName, // Use actual sample name from project folder
                        modalityWithIndex,
                        annotation.getName());

                // Write acquisition metadata for recovery workflow (idempotent per scan type)
                writeAcquisitionInfo(
                        state.projectInfo.getTempTileDirectory(),
                        actualSampleName,
                        getParentImageName(),
                        baseModality,
                        state.sample.objective(),
                        getParentFlipX(),
                        getParentFlipY(),
                        state.sample.detector());

                // Apply Z-focus prediction if model is ready (tilt correction)
                if (state.transform != null) {
                    double[] stageCoords = TransformationFunctions.transformQuPathFullResToStage(
                            new double[] {
                                annotation.getROI().getCentroidX(),
                                annotation.getROI().getCentroidY()
                            },
                            state.transform);
                    double distFromLast = zFocusModel.distanceFromLastPoint(stageCoords[0], stageCoords[1]);

                    if (zFocusModel.canPredict(distFromLast)) {
                        zFocusModel.predictZ(stageCoords[0], stageCoords[1]).ifPresent(predictedZ -> {
                            // Sanity check: if prediction deviates too far from the last
                            // known good Z, the model may be extrapolating badly.
                            // Fall back to lastAcquisitionZ which is always a safe hint.
                            double referenceZ = lastAcquisitionZ != null ? lastAcquisitionZ : predictedZ;
                            double deviation = Math.abs(predictedZ - referenceZ);
                            if (deviation > 20.0) {
                                config.commandBuilder().hintZ(referenceZ);
                                logger.warn(
                                        "Z prediction {} um deviates {} um from last known Z {} um -- "
                                                + "using last known Z as hint",
                                        String.format("%.2f", predictedZ),
                                        String.format("%.1f", deviation),
                                        String.format("%.2f", referenceZ));
                            } else {
                                config.commandBuilder().hintZ(predictedZ);
                                logger.info(
                                        "Z-focus prediction for {}: {} um (from {} points, residual={} um, dist={} um)",
                                        annotation.getName(),
                                        String.format("%.2f", predictedZ),
                                        zFocusModel.getPointCount(),
                                        String.format("%.1f", zFocusModel.calculateResidualError()),
                                        String.format("%.0f", distFromLast));
                            }
                        });
                    } else {
                        // No prediction available (first annotation or too far from known points).
                        // Prefer the last acquisition's final Z (which is near the actual focal
                        // plane) over the microscope's current Z (which may have drifted or been
                        // reset between acquisitions).
                        if (lastAcquisitionZ != null) {
                            config.commandBuilder().hintZ(lastAcquisitionZ);
                            logger.info(
                                    "Using last acquisition Z={} um as hint for {} (no prediction, carried forward)",
                                    String.format("%.2f", lastAcquisitionZ),
                                    annotation.getName());
                        } else {
                            try {
                                double currentZ = MicroscopeController.getInstance()
                                        .getSocketClient()
                                        .getStageXYZ()[2];
                                config.commandBuilder().hintZ(currentZ);
                                logger.info(
                                        "Using current Z={} um as hint for {} (first annotation)",
                                        String.format("%.2f", currentZ),
                                        annotation.getName());
                            } catch (Exception zEx) {
                                logger.debug("Could not get current Z for hint: {}", zEx.getMessage());
                            }
                        }
                    }
                }

                // Score tiles from WSI to find best first AF position.
                // The WSI already shows tissue content at each tile location --
                // use this to avoid focusing on blank/white regions.
                if (!QPPreferenceDialog.getDisableAllAutofocus()) {
                    try {
                        // Read WSI tissue scoring thresholds from autofocus config
                        Map<String, Object> afParams = configManager.getAutofocusParams(state.sample.objective());
                        double wsiTissueThreshold = 0.15;
                        int wsiWhiteThreshold = 230;
                        int wsiDarkThreshold = 20;
                        if (afParams != null) {
                            if (afParams.get("wsi_tissue_threshold") instanceof Number)
                                wsiTissueThreshold = ((Number) afParams.get("wsi_tissue_threshold")).doubleValue();
                            if (afParams.get("wsi_tissue_white_threshold") instanceof Number)
                                wsiWhiteThreshold = ((Number) afParams.get("wsi_tissue_white_threshold")).intValue();
                            if (afParams.get("wsi_tissue_dark_threshold") instanceof Number)
                                wsiDarkThreshold = ((Number) afParams.get("wsi_tissue_dark_threshold")).intValue();
                        }
                        int preferredTile = findBestAfTileFromWSI(
                                annotation, modalityWithIndex, wsiTissueThreshold, wsiWhiteThreshold, wsiDarkThreshold);
                        if (preferredTile >= 0) {
                            config.commandBuilder().preferredAfTile(preferredTile);
                            logger.info(
                                    "WSI tissue scoring: preferred AF tile = {} for {}",
                                    preferredTile,
                                    annotation.getName());
                        }
                    } catch (Exception e) {
                        logger.debug("WSI tissue scoring skipped: {}", e.getMessage());
                    }
                }

                // Start acquisition
                MicroscopeController.getInstance().startAcquisition(config.commandBuilder());

                // Monitor progress
                return monitorAcquisition(annotation, angleExposures, channelExposures, progressDialog);

            } catch (Exception e) {
                logger.error("Acquisition failed for {}", annotation.getName(), e);
                showAcquisitionError(annotation.getName(), e.getMessage());
                return false;
            }
        });
    }
    /**
     * Monitors acquisition progress with cancellation support.
     *
     * <p>This method:
     * <ul>
     *   <li>Calculates expected file count based on tiles and angles/channels</li>
     *   <li>Shows a progress bar with cancel button</li>
     *   <li>Polls the microscope server for status updates</li>
     *   <li>Handles user cancellation requests</li>
     * </ul>
     *
     * @param annotation The annotation being acquired
     * @param angleExposures Rotation angles for calculating expected files
     * @param channelExposures Channel list (for widefield IF); may be null or empty
     * @return true if completed successfully, false if cancelled/failed
     * @throws IOException if communication with microscope fails
     */
    private boolean monitorAcquisition(
            PathObject annotation,
            List<AngleExposure> angleExposures,
            List<qupath.ext.qpsc.modality.ChannelExposure> channelExposures,
            DualProgressDialog progressDialog)
            throws IOException {

        MicroscopeSocketClient socketClient = MicroscopeController.getInstance().getSocketClient();

        // Calculate expected files with retry logic to handle timing issues
        // Use tempTileDirectory from projectInfo which has the correct path (including actual sample name)
        String tileDirPath = Paths.get(state.projectInfo.getTempTileDirectory(), annotation.getName())
                .toString();

        // Try to count tiles with retry logic (3 attempts, 200ms delay)
        int tilesPerAngle = MinorFunctions.countExpectedTilesWithRetry(List.of(tileDirPath), 3, 200);

        // If count is still 0 after retries, estimate from annotation bounds
        if (tilesPerAngle == 0) {
            logger.warn("Could not count tiles from TileConfiguration file, estimating from annotation bounds");
            tilesPerAngle = estimateTileCount(annotation);
            logger.info("Estimated {} tiles for annotation {}", tilesPerAngle, annotation.getName());
        }

        // Steps per physical XY position: angle count (PPM) or channel
        // count (widefield IF), times the Z-plane count when Z-stack is
        // enabled. Python's acquisition workflow reports one progress
        // increment per snap, so total snaps per tile = channels *
        // n_z_planes (or angles * n_z_planes). Angles and channels are
        // mutually exclusive upstream so we take the max; if both are
        // zero/empty we fall back to 1 for a plain single-shot acquisition.
        int numChannelsForProgress = (channelExposures != null) ? channelExposures.size() : 0;
        int numAnglesForProgress = (angleExposures != null) ? angleExposures.size() : 0;
        int stepsPerPositionTmp = Math.max(1, Math.max(numChannelsForProgress, numAnglesForProgress));
        int nZPlanesForProgress = 1;
        if (PersistentPreferences.isZStackEnabled()) {
            double zRange = PersistentPreferences.getZStackRange();
            double zStep = PersistentPreferences.getZStackStep();
            if (zStep > 0) {
                nZPlanesForProgress = (int) Math.ceil(zRange / zStep) + 1;
                stepsPerPositionTmp *= nZPlanesForProgress;
            }
        }
        final int stepsPerPositionForProgress = stepsPerPositionTmp;
        final int expectedFiles = tilesPerAngle * stepsPerPositionForProgress;

        logger.info(
                "Expected files: {} ({} tiles x {} steps/position; angles={}, channels={}, z_planes={})",
                expectedFiles,
                tilesPerAngle,
                stepsPerPositionForProgress,
                numAnglesForProgress,
                numChannelsForProgress,
                nZPlanesForProgress);

        // Create progress counter
        AtomicInteger progressCounter = new AtomicInteger(0);

        // Start tracking this annotation in the dual progress dialog.
        // Correct the steps-per-position now that channels are
        // resolved -- the initial call in processAnnotations could
        // only see angles. Idempotent when the value is unchanged.
        if (progressDialog != null && !progressDialog.isCancelled()) {
            Platform.runLater(() -> {
                progressDialog.setStepsPerPosition(stepsPerPositionForProgress);
                progressDialog.startAnnotation(annotation.getName(), expectedFiles);
            });
        }

        // Flag to track if we've read the acquisition metadata file
        AtomicBoolean metadataRead = new AtomicBoolean(false);
        // Flag to track if we're currently handling a manual focus request (to avoid showing multiple dialogs)
        AtomicBoolean handlingManualFocus = new AtomicBoolean(false);

        // For tile viewer: track previous progress to detect new tiles
        final AtomicInteger lastTileProgress = new AtomicInteger(0);

        // Start live NDJSON poller so per-tile autofocus/saturation measurements appear
        // on the open slide's detections as acquisition progresses. The batch attachment
        // at the end still runs and catches anything the poller missed.
        java.nio.file.Path ndjsonPath = Paths.get(tileDirPath, "tile_measurements.ndjson");
        LiveTileMeasurementPoller livePoller =
                LiveTileMeasurementPoller.start(
                        ndjsonPath, annotation.getName(), LIVE_POLL_EXECUTOR,
                        getSessionHierarchy(), capturedImageData);

        try {
            // Monitor acquisition with regular status updates
            MicroscopeSocketClient.AcquisitionState finalState = socketClient.monitorAcquisition(
                    progress -> {
                        progressCounter.set(progress.current);
                        // Update dual progress dialog
                        if (progressDialog != null && !progressDialog.isCancelled()) {
                            Platform.runLater(() -> progressDialog.updateCurrentAnnotationProgress(progress.current));
                        }

                        // Show acquired tile in Live Viewer (if enabled)
                        if (qupath.ext.qpsc.ui.liveviewer.LiveViewerWindow.isShowTilesEnabled()
                                && progress.current > lastTileProgress.get()) {
                            lastTileProgress.set(progress.current);
                            findAndDisplayLatestTile(tileDirPath);
                        }

                        // Check for acquisition metadata file (only once)
                        if (!metadataRead.get() && progressDialog != null) {
                            java.nio.file.Path metadataPath =
                                    java.nio.file.Paths.get(tileDirPath, "acquisition_metadata.txt");
                            if (java.nio.file.Files.exists(metadataPath)) {
                                metadataRead.set(true);
                                try {
                                    java.util.List<String> lines = java.nio.file.Files.readAllLines(metadataPath);
                                    int timingWindowSize = 10;
                                    int afNTiles = 5; // Total AF positions (count, not interval)
                                    int totalTiles = 0;

                                    for (String line : lines) {
                                        if (line.startsWith("timing_window_size=")) {
                                            timingWindowSize =
                                                    Integer.parseInt(line.substring("timing_window_size=".length()));
                                        } else if (line.startsWith("af_n_tiles=")) {
                                            afNTiles = Integer.parseInt(line.substring("af_n_tiles=".length()));
                                        } else if (line.startsWith("total_tiles=")) {
                                            totalTiles = Integer.parseInt(line.substring("total_tiles=".length()));
                                        }
                                    }

                                    logger.info(
                                            "Read acquisition metadata: window={}, af_positions={}, total_tiles={}",
                                            timingWindowSize,
                                            afNTiles,
                                            totalTiles);

                                    // Update dialog with all timing parameters
                                    final int finalTimingWindow = timingWindowSize;
                                    final int finalAfNTiles = afNTiles;
                                    final int finalTotalTiles = totalTiles;
                                    Platform.runLater(() -> {
                                        progressDialog.setTimingWindowSize(finalTimingWindow);
                                        progressDialog.setAfNTiles(finalAfNTiles);
                                        if (finalTotalTiles > 0) {
                                            progressDialog.setTotalTilesForAnnotation(finalTotalTiles);
                                        }
                                    });
                                } catch (Exception e) {
                                    logger.warn("Failed to read acquisition metadata: {}", e.getMessage());
                                }
                            }
                        }
                    },
                    // Manual focus callback - delegates to shared ManualFocusHandler
                    retriesRemaining -> {
                        ManualFocusHandler.TimingCallback timing = progressDialog != null
                                ? new ManualFocusHandler.TimingCallback() {
                                    public void pauseTiming() {
                                        progressDialog.pauseTimingForManualFocus();
                                    }

                                    public void resumeTiming() {
                                        progressDialog.resumeTimingAfterManualFocus();
                                    }
                                }
                                : null;
                        ManualFocusHandler.handle(
                                socketClient,
                                retriesRemaining,
                                handlingManualFocus,
                                timing,
                                UIFunctions::showManualFocusDialog);
                    },
                    // Hardware error callback - shows dialog with error details
                    errorMessage -> {
                        logger.warn("Hardware error during acquisition: {}", errorMessage);
                        if (progressDialog != null) {
                            progressDialog.pauseTimingForManualFocus();
                        }
                        try {
                            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                            final String[] userChoice = {"cancel"};
                            Platform.runLater(() -> {
                                try {
                                    userChoice[0] = showHardwareErrorDialog(errorMessage);
                                } finally {
                                    latch.countDown();
                                }
                            });
                            latch.await();
                            socketClient.acknowledgeHardwareError(userChoice[0]);
                            logger.info("User chose '{}' for hardware error recovery", userChoice[0]);
                        } catch (Exception e) {
                            logger.error("Error handling hardware error dialog", e);
                            try {
                                socketClient.acknowledgeHardwareError("cancel");
                            } catch (IOException ex) {
                                logger.error("Failed to send cancel for hardware error", ex);
                            }
                        }
                        if (progressDialog != null) {
                            progressDialog.resumeTimingAfterManualFocus();
                        }
                    },
                    500, // Poll every 500ms for responsive UI
                    ACQUISITION_TIMEOUT_MS);

            // Check final state
            switch (finalState) {
                case COMPLETED:
                    logger.info("Acquisition completed successfully for {}", annotation.getName());

                    // Show saturation summary if any angles had saturation
                    String satSummary = socketClient.getFormattedSaturationSummary();
                    if (satSummary != null) {
                        logger.warn("Saturation detected during acquisition:\n{}", satSummary);
                        // Check for detailed saturation report file
                        String tileDir = java.nio.file.Paths.get(
                                        state.projectInfo.getTempTileDirectory(), annotation.getName())
                                .toString();
                        String reportPath = tileDir + java.io.File.separator + "saturation_report.json";
                        java.io.File reportFile = new java.io.File(reportPath);
                        if (reportFile.exists()) {
                            // Show detailed scrollable dialog with per-tile data
                            SaturationSummaryDialog.show(reportPath, satSummary, state.sample.modality());
                        } else {
                            // Fallback: use a proper dialog instead of a toast notification
                            // (toast notifications truncate long text)
                            Platform.runLater(() -> {
                                javafx.scene.control.Alert satAlert =
                                        new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING);
                                satAlert.setTitle("Saturation Detected");
                                satAlert.setHeaderText("Some tiles had saturated pixels");
                                javafx.scene.control.TextArea satText = new javafx.scene.control.TextArea(satSummary);
                                satText.setEditable(false);
                                satText.setWrapText(true);
                                satText.setPrefHeight(200);
                                satAlert.getDialogPane().setContent(satText);
                                satAlert.getDialogPane().setMinWidth(500);
                                satAlert.showAndWait();
                            });
                        }
                    }

                    // Read per-tile measurements and attach to detection objects
                    try {
                        String tileDir2 = java.nio.file.Paths.get(
                                        state.projectInfo.getTempTileDirectory(), annotation.getName())
                                .toString();
                        String measurementsPath = tileDir2 + java.io.File.separator + "tile_measurements.json";
                        java.io.File measurementsFile = new java.io.File(measurementsPath);
                        if (measurementsFile.exists()) {
                            String measurementsJson = new String(
                                    java.nio.file.Files.readAllBytes(measurementsFile.toPath()),
                                    java.nio.charset.StandardCharsets.UTF_8);
                            attachTileMeasurements(annotation, measurementsJson);
                        }
                    } catch (Exception e) {
                        logger.warn("Could not load tile measurements: {}", e.getMessage());
                    }

                    // Persist the populated tile measurements to the project so they
                    // survive close/reopen. Tile detections are otherwise ephemeral
                    // (regenerated from annotation bounds on next open) and the
                    // measurements just attached would be lost on the next session.
                    try {
                        var imageData = QP.getCurrentImageData();
                        var entry = QP.getProjectEntry();
                        if (imageData != null && entry != null) {
                            entry.saveImageData(imageData);
                            logger.info("Saved tile measurements for {} to project", annotation.getName());
                        }
                    } catch (Exception e) {
                        logger.warn("Could not persist tile measurements to project: {}", e.getMessage());
                    }

                    return true;

                case CANCELLED:
                    logger.info("Acquisition was cancelled for {}", annotation.getName());
                    // User clicked cancel - no error notification needed, dialog already shows state
                    if (progressDialog != null && progressDialog.isCancelled()) {
                        logger.info("Cancellation was initiated via dual progress dialog");
                    }
                    return false;

                case FAILED:
                    // Get detailed failure message from server
                    String failureMessage = socketClient.getLastFailureMessage();
                    String errorDetails = failureMessage != null ? failureMessage : "Unknown server error";
                    logger.error("Server acquisition failed: {}", errorDetails);
                    throw new RuntimeException("Acquisition failed on server: " + errorDetails);

                default:
                    logger.warn("Unexpected acquisition state: {}", finalState);
                    return false;
            }

        } catch (InterruptedException e) {
            logger.error("Acquisition monitoring interrupted", e);
            throw new RuntimeException(e);
        } finally {
            // Stop the live NDJSON poller -- runs one final synchronous tick
            // to catch any tail entries before the batch attachment runs.
            LiveTileMeasurementPoller.stop(livePoller);
        }
    }

    /**
     * Launches stitching for a completed acquisition.
     *
     * <p>Stitching is performed asynchronously on a dedicated thread to:
     * <ul>
     *   <li>Allow the next acquisition to start immediately</li>
     *   <li>Prevent UI blocking during intensive stitching operations</li>
     *   <li>Process stitching jobs sequentially to manage resources</li>
     * </ul>
     *
     * @param annotation The annotation that was acquired
     * @param angleExposures Rotation angles used in acquisition
     */
    private void launchStitching(PathObject annotation, List<AngleExposure> angleExposures) {

        // Get required parameters for stitching
        String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configFileLocation);

        // Use the same hardware-specific pixel size calculation as was used for acquisition
        String baseModality = state.sample.modality().replaceAll("(_\\d+)$", "");
        String objective = state.sample.objective();
        String detector = state.sample.detector();

        double WSI_pixelSize_um;
        try {
            WSI_pixelSize_um = configManager.getPixelSize(objective, detector);
            logger.info(
                    "Using stitching WSI pixel size for {}/{}/{}: {} um",
                    baseModality,
                    objective,
                    detector,
                    WSI_pixelSize_um);
        } catch (IllegalArgumentException e) {
            logger.error("Failed to determine pixel size for stitching: {}", e.getMessage());
            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                    "Cannot determine pixel size for hardware: " + baseModality + "/" + objective + "/" + detector
                            + "\n\nPlease check configuration. This should match the acquisition hardware settings.",
                    "Configuration Error"));
            return;
        }
        @SuppressWarnings("unchecked")
        Project<BufferedImage> project = (Project<BufferedImage>) state.projectInfo.getCurrentProject();

        ModalityHandler handler = ModalityRegistry.getHandler(state.sample.modality());

        // Calculate offset for this annotation (for metadata)
        double[] offset = TransformationFunctions.calculateAnnotationOffsetFromSlideCorner(annotation, state.transform);
        logger.info("Annotation {} offset from slide corner: ({}, {}) um", annotation.getName(), offset[0], offset[1]);

        // Derive projectsFolder from tempTileDirectory
        // tempTileDirectory structure: projectsFolder/sampleName/modeWithIndex
        String tempTileDir = state.projectInfo.getTempTileDirectory();
        java.nio.file.Path projectsFolder =
                java.nio.file.Paths.get(tempTileDir).getParent().getParent();
        logger.debug("Derived projectsFolder for stitching: {}", projectsFolder);

        // Create stitching future - use projectInfo.getSampleName() for correct folder path
        // Pass the dualProgressDialog so stitching status is shown in the unified progress window
        CompletableFuture<Void> stitchFuture = StitchingHelper.performAnnotationStitching(
                annotation,
                state.sample,
                state.projectInfo.getImagingModeWithIndex(),
                angleExposures,
                WSI_pixelSize_um,
                gui,
                project,
                STITCH_EXECUTOR,
                handler,
                MicroscopeController.getInstance().getCurrentTransform(),
                state.projectInfo.getSampleName(),
                projectsFolder.toString(),
                dualProgressDialog,
                parentEntry);

        state.stitchingFutures.add(stitchFuture);
        logger.info("Launched stitching for annotation: {}", annotation.getName());
    }

    /**
     * Estimates tile count based on annotation bounds and camera FOV.
     * This is used as a fallback when TileConfiguration files are not available.
     *
     * @param annotation The annotation to estimate tiles for
     * @return Estimated number of tiles (minimum 1)
     */
    /**
     * Shows a hardware error dialog with the error message in a scrollable text area.
     * Called on the FX Application Thread.
     *
     * @param errorMessage The hardware error details
     * @return User's choice: "retry", "skip", or "cancel"
     */
    private static String showHardwareErrorDialog(String errorMessage) {
        // System beep
        try {
            java.awt.Toolkit.getDefaultToolkit().beep();
        } catch (Exception e) {
            // Ignore
        }

        // Push notification
        qupath.ext.qpsc.service.notification.NotificationService.getInstance()
                .notify(
                        "Hardware Error - Acquisition Paused",
                        "A hardware communication error occurred. Acquisition is paused and waiting for user intervention.",
                        qupath.ext.qpsc.service.notification.NotificationPriority.URGENT,
                        qupath.ext.qpsc.service.notification.NotificationEvent.ACQUISITION_ERROR);

        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING);
        alert.setTitle("Hardware Error During Acquisition");
        alert.setHeaderText("A hardware communication error occurred.\n"
                + "The acquisition is paused. You can fix the issue in Micro-Manager\n"
                + "and retry, skip this tile, or cancel the acquisition.");

        // Scrollable error details
        javafx.scene.control.TextArea textArea = new javafx.scene.control.TextArea(errorMessage);
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setMaxWidth(Double.MAX_VALUE);
        textArea.setMaxHeight(200);
        textArea.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");

        javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(8);
        content.getChildren()
                .addAll(
                        new javafx.scene.control.Label("Error details:"),
                        textArea,
                        new javafx.scene.control.Label(
                                "Check the device connection in Micro-Manager before retrying."));
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setMinWidth(550);

        // Custom buttons
        javafx.scene.control.ButtonType retryButton =
                new javafx.scene.control.ButtonType("Retry", javafx.scene.control.ButtonBar.ButtonData.YES);
        javafx.scene.control.ButtonType skipButton =
                new javafx.scene.control.ButtonType("Skip Tile", javafx.scene.control.ButtonBar.ButtonData.NO);
        javafx.scene.control.ButtonType cancelButton = new javafx.scene.control.ButtonType(
                "Cancel Acquisition", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(retryButton, skipButton, cancelButton);

        java.util.Optional<javafx.scene.control.ButtonType> result = alert.showAndWait();
        if (result.isPresent()) {
            if (result.get() == retryButton) return "retry";
            if (result.get() == skipButton) return "skip";
        }
        return "cancel";
    }

    /**
     * Delegates to {@link qupath.ext.qpsc.ui.liveviewer.LiveViewerWindow#scanAndShowLatestTile(String)}
     * which handles both rotation (angle subdirectory) and non-rotation
     * (tiles directly in the annotation folder) layouts.
     */
    private void findAndDisplayLatestTile(String tileDirPath) {
        qupath.ext.qpsc.ui.liveviewer.LiveViewerWindow.scanAndShowLatestTile(tileDirPath);
    }

    private int estimateTileCount(PathObject annotation) {
        try {
            // Get annotation bounds in image pixels
            double annWidth = annotation.getROI().getBoundsWidth();
            double annHeight = annotation.getROI().getBoundsHeight();

            // Get image pixel size from cached session data (viewer-independent)
            ImageData<?> imgData = capturedImageData != null ? capturedImageData : gui.getImageData();
            if (imgData == null) {
                logger.debug("No image data available for tile count estimate");
                return 1;
            }
            double imagePixelSize = imgData.getServer()
                    .getPixelCalibration()
                    .getAveragedPixelSizeMicrons();

            // Convert to microns
            double annWidthMicrons = annWidth * imagePixelSize;
            double annHeightMicrons = annHeight * imagePixelSize;

            // Get camera FOV from configuration
            double[] fovMicrons = MicroscopeController.getInstance()
                    .getCameraFOVFromConfig(state.sample.modality(), state.sample.objective(), state.sample.detector());

            double fovWidthMicrons = fovMicrons[0];
            double fovHeightMicrons = fovMicrons[1];

            // Get overlap percentage
            double overlapPercent = QPPreferenceDialog.getTileOverlapPercentProperty();
            double effectiveWidth = fovWidthMicrons * (1 - overlapPercent / 100.0);
            double effectiveHeight = fovHeightMicrons * (1 - overlapPercent / 100.0);

            // Calculate tile grid
            int tilesX = (int) Math.ceil(annWidthMicrons / effectiveWidth);
            int tilesY = (int) Math.ceil(annHeightMicrons / effectiveHeight);
            int totalTiles = tilesX * tilesY;

            logger.info(
                    "Tile estimate: annotation {}x{} um, FOV {}x{} um, overlap {}%, grid {}x{} = {} tiles",
                    Math.round(annWidthMicrons),
                    Math.round(annHeightMicrons),
                    Math.round(fovWidthMicrons),
                    Math.round(fovHeightMicrons),
                    Math.round(overlapPercent),
                    tilesX,
                    tilesY,
                    totalTiles);

            // Return at least 1 tile to avoid division by zero
            return Math.max(1, totalTiles);

        } catch (Exception e) {
            logger.error("Failed to estimate tile count, defaulting to 1", e);
            return 1; // Safe default to prevent division by zero
        }
    }

    /**
     * Reorders annotations to prioritize the one containing the refinement tile.
     *
     * <p>If a refinement tile was selected during alignment, this method finds the
     * annotation that contains that tile and moves it to the front of the acquisition
     * list. This ensures the sample is already in focus from the alignment when
     * the first annotation is acquired.
     */
    private void prioritizeRefinementAnnotation() {
        if (state.refinementTile == null) {
            logger.debug("No refinement tile to prioritize");
            return;
        }

        // Find the annotation that contains the refinement tile
        PathObject parentAnnotation = state.refinementTile.getParent();

        if (parentAnnotation == null) {
            logger.warn("Refinement tile has no parent annotation");
            return;
        }

        // Check if this annotation is in our list
        int annotationIndex = state.annotations.indexOf(parentAnnotation);

        if (annotationIndex == -1) {
            logger.warn(
                    "Refinement tile's parent annotation '{}' not found in acquisition list",
                    parentAnnotation.getName());
            return;
        }

        if (annotationIndex == 0) {
            logger.info("Refinement annotation '{}' is already first in acquisition order", parentAnnotation.getName());
            return;
        }

        // Move the annotation to the front
        state.annotations.remove(annotationIndex);
        state.annotations.add(0, parentAnnotation);

        logger.info(
                "Prioritized annotation '{}' to be acquired first (contains refinement tile '{}')",
                parentAnnotation.getName(),
                state.refinementTile.getName());
    }

    // UI notification methods

    /**
     * Shows initial notification about acquisition start.
     */
    private void showAcquisitionStartNotification(List<AngleExposure> angleExposures) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Acquisition Progress");
            alert.setHeaderText("Starting acquisition workflow");
            alert.setContentText(String.format(
                    "Processing %d annotations with %d rotation angles each.\n"
                            + "This may take several minutes per annotation.",
                    state.annotations.size(),
                    angleExposures == null || angleExposures.isEmpty() ? 1 : angleExposures.size()));
            alert.show();

            // Auto-close after 3 seconds
            javafx.animation.PauseTransition pause =
                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));
            pause.setOnFinished(e -> alert.close());
            pause.play();
        });
    }

    /**
     * Shows progress notification for current annotation.
     */
    private void showProgressNotification(int current, int total, String annotationName) {
        Platform.runLater(() -> {
            String message = String.format("Acquiring annotation %d of %d: %s", current, total, annotationName);
            Dialogs.showInfoNotification("Acquisition Progress", message);
        });
    }

    /**
     * Safely retrieves the dialog from the CompletableFuture with timeout handling.
     */
    private DualProgressDialog getDialogSafely(CompletableFuture<DualProgressDialog> dialogSetup) {
        try {
            return dialogSetup.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("Failed to setup dual progress dialog", e);
            return null;
        }
    }

    /**
     * Shows error notification for failed acquisition.
     */
    private void showAcquisitionError(String annotationName, String errorMessage) {
        Platform.runLater(() -> UIFunctions.notifyUserOfError(
                "Acquisition failed for " + annotationName + ":\n\n" + errorMessage, "Acquisition Error"));
    }

    /**
     * Shows notification that acquisition was cancelled.
     * Note: This method is kept for potential future use but is no longer called
     * for user-initiated cancellations since the dialog already shows cancellation state.
     */
    private void showCancellationNotification() {
        // User initiated cancellation - no error notification needed
        // Dual progress dialog already displays cancellation state
        logger.info("Acquisition cancelled by user request");
    }

    /**
     * Returns the dual progress dialog for this acquisition manager.
     * This allows other components (like StitchingHelper) to update the dialog
     * with stitching status information.
     *
     * @return The DualProgressDialog, or null if not yet created
     */
    public DualProgressDialog getDualProgressDialog() {
        return dualProgressDialog;
    }

    // --- Acquisition metadata persistence for recovery workflow ---

    /**
     * Writes acquisition metadata to the scan type directory for use by the
     * Re-stitch Tiles recovery workflow.
     *
     * <p>Idempotent -- does nothing if the file already exists, since all
     * annotations within one scan type share the same metadata.
     *
     * @param scanTypeDir Path to the scan type directory (tempTileDirectory)
     * @param sampleName  The sample name used for output filenames
     * @param parentImageName Name of the parent (macro) image in the project
     * @param modality    Imaging modality (e.g., "ppm")
     * @param objective   Objective used (e.g., "20x")
     * @param flipX       Whether the parent image is optically flipped on X
     * @param flipY       Whether the parent image is optically flipped on Y
     * @param detectorId  The detector used for this acquisition (null if unknown)
     */
    private void writeAcquisitionInfo(
            String scanTypeDir,
            String sampleName,
            String parentImageName,
            String modality,
            String objective,
            boolean flipX,
            boolean flipY,
            String detectorId) {
        Path infoFile = Paths.get(scanTypeDir, ACQUISITION_INFO_FILENAME);
        if (Files.exists(infoFile)) {
            return;
        }
        try {
            Files.createDirectories(infoFile.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(infoFile, StandardCharsets.UTF_8)) {
                w.write("# Acquisition info -- written by QuPath QPSC Extension");
                w.newLine();
                w.write("# Used by Re-stitch Tiles to recover naming and metadata");
                w.newLine();
                w.write("sample_name=" + sampleName);
                w.newLine();
                w.write("parent_image=" + (parentImageName != null ? parentImageName : ""));
                w.newLine();
                w.write("modality=" + (modality != null ? modality : ""));
                w.newLine();
                w.write("objective=" + (objective != null ? objective : ""));
                w.newLine();
                w.write("flip_x=" + flipX);
                w.newLine();
                w.write("flip_y=" + flipY);
                w.newLine();
                w.write("detector_id=" + (detectorId != null ? detectorId : ""));
                w.newLine();
            }
            logger.info("Wrote acquisition info to: {}", infoFile);
        } catch (IOException e) {
            logger.warn("Could not write acquisition info: {}", e.getMessage());
        }
    }

    /**
     * Gets the parent image name using the cached entry (viewer-independent).
     *
     * @return parent image name, or null if unavailable
     */
    private String getParentImageName() {
        if (parentEntry != null) {
            return parentEntry.getImageName();
        }
        // Fallback: try the live viewer (only relevant before caching completes)
        if (gui.getViewer().hasServer() && gui.getImageData() != null) {
            try {
                @SuppressWarnings("unchecked")
                Project<BufferedImage> project = (Project<BufferedImage>) state.projectInfo.getCurrentProject();
                ProjectImageEntry<BufferedImage> entry = project.getEntry(gui.getImageData());
                return entry != null ? entry.getImageName() : null;
            } catch (Exception e) {
                logger.debug("Could not get parent image name: {}", e.getMessage());
            }
        }
        return null;
    }

    /**
     * Gets the flip-X status for the current acquisition context.
     *
     * <p>Fallback chain:
     * <ol>
     *   <li>Cached parent entry metadata (viewer-independent)</li>
     *   <li>Per-detector config from resources_LOCI.yml (hardware-specific optical flip)</li>
     *   <li>Global preference (legacy fallback for unconfigured systems)</li>
     * </ol>
     */
    private boolean getParentFlipX() {
        // 1. Try cached parent entry (viewer-independent, stable during acquisition)
        if (parentEntry != null) {
            return ImageMetadataManager.isFlippedX(parentEntry);
        }
        // 2. Try per-detector config (hardware optical flip)
        String detectorId = state.sample != null ? state.sample.detector() : null;
        if (detectorId != null) {
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
            if (mgr != null) {
                return mgr.getDetectorFlipX(detectorId);
            }
        }
        // 3. Fall back to global preference
        return QPPreferenceDialog.getFlipMacroXProperty();
    }

    /**
     * Gets the flip-Y status for the current acquisition context.
     *
     * @see #getParentFlipX()
     */
    private boolean getParentFlipY() {
        // 1. Try cached parent entry (viewer-independent, stable during acquisition)
        if (parentEntry != null) {
            return ImageMetadataManager.isFlippedY(parentEntry);
        }
        // 2. Try per-detector config
        String detectorId = state.sample != null ? state.sample.detector() : null;
        if (detectorId != null) {
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
            if (mgr != null) {
                return mgr.getDetectorFlipY(detectorId);
            }
        }
        // 3. Fall back to global preference
        return QPPreferenceDialog.getFlipMacroYProperty();
    }

    /**
     * Parses per-tile measurement JSON and attaches values to detection objects.
     *
     * <p>The JSON is an array of objects produced by the Python server, each with:
     * position_index, filename, z_um, af_performed, af_type, af_drift_um, af_failed,
     * tile_time_ms.
     *
     * <p>Detections are matched by their TileNumber measurement (set during tile
     * generation in {@link qupath.ext.qpsc.utilities.TilingUtilities}).
     *
     * @param annotation The annotation whose child detections should be updated
     * @param json       Raw JSON string (array of measurement objects)
     */
    @SuppressWarnings("unchecked")
    private void attachTileMeasurements(PathObject annotation, String json) {
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.lang.reflect.Type listType =
                    new com.google.gson.reflect.TypeToken<java.util.List<java.util.Map<String, Object>>>() {}.getType();
            java.util.List<java.util.Map<String, Object>> measurements = gson.fromJson(json, listType);

            if (measurements == null || measurements.isEmpty()) {
                logger.debug("No tile measurements to attach");
                return;
            }

            // Build lookup from position_index -> measurement entry
            java.util.Map<Integer, java.util.Map<String, Object>> measurementsByIndex = new java.util.HashMap<>();
            for (java.util.Map<String, Object> entry : measurements) {
                Number posIdx = (Number) entry.get("position_index");
                if (posIdx != null) {
                    measurementsByIndex.put(posIdx.intValue(), entry);
                }
            }

            // Find detection objects that belong to this annotation.
            // Tile detections are named "{index}_{annotationName}" where
            // annotationName contains the XY stage coordinates (e.g., "58394_50846").
            // They spatially intersect the annotation but are NOT hierarchical children.
            String annotationName = annotation.getName();
            var hierarchy = getSessionHierarchy();
            if (hierarchy == null) {
                logger.warn("No hierarchy available for tile measurement attachment");
                return;
            }

            java.util.List<PathObject> detections = hierarchy.getDetectionObjects().stream()
                    .filter(d -> {
                        String name = d.getName();
                        return name != null && name.contains(annotationName);
                    })
                    .collect(Collectors.toList());

            logger.debug(
                    "Found {} detections matching annotation '{}' (by name containment)",
                    detections.size(),
                    annotationName);

            int matched = 0;
            for (PathObject detection : detections) {
                // Match by TileNumber measurement (set during tile generation)
                Number tileNum = detection.getMeasurements().get("TileNumber");
                if (tileNum == null) continue;

                java.util.Map<String, Object> entry = measurementsByIndex.get(tileNum.intValue());
                if (entry == null) continue;

                applyMeasurementEntry(detection, entry);
                matched++;
            }

            logger.info(
                    "Attached tile measurements to {}/{} detections for annotation '{}'",
                    matched,
                    detections.size(),
                    annotation.getName());

            // Fire hierarchy update so measurement table refreshes -- but only if the
            // viewer is currently showing the session image. If the user switched images,
            // measurements are still on the correct PathObjects (in memory) and will
            // display when the user switches back.
            if (isSessionImageActive()) {
                hierarchy.fireHierarchyChangedEvent(hierarchy.getRootObject());
            }

        } catch (Exception e) {
            logger.warn("Failed to attach tile measurements: {}", e.getMessage());
        }
    }

    /**
     * Applies a single tile measurement record to a detection. Shared by the
     * batch post-acquisition path ({@link #attachTileMeasurements}) and the
     * live NDJSON tail poller used during acquisition.
     *
     * <p>The entry is expected to be a parsed JSON object with the same fields
     * the Python server writes to tile_measurements.json / tile_measurements.ndjson.
     */
    public static void applyMeasurementEntry(PathObject detection, java.util.Map<String, Object> entry) {
        Number zUm = (Number) entry.get("z_um");
        if (zUm != null) {
            detection.getMeasurements().put("z_position_um", zUm.doubleValue());
        }

        Boolean afPerformed = (Boolean) entry.get("af_performed");
        detection.getMeasurements().put("af_performed", (afPerformed != null && afPerformed) ? 1.0 : 0.0);

        // Encode af_type as numeric: 0=none, 1=sweep, 2=standard
        String afType = (String) entry.get("af_type");
        double afTypeVal = 0.0;
        if ("sweep".equals(afType)) {
            afTypeVal = 1.0;
        } else if ("standard".equals(afType)) {
            afTypeVal = 2.0;
        }
        detection.getMeasurements().put("af_type", afTypeVal);

        Number afDrift = (Number) entry.get("af_drift_um");
        if (afDrift != null) {
            detection.getMeasurements().put("af_drift_um", afDrift.doubleValue());
        }

        Boolean afFailed = (Boolean) entry.get("af_failed");
        detection.getMeasurements().put("af_failed", (afFailed != null && afFailed) ? 1.0 : 0.0);

        Number tileTime = (Number) entry.get("tile_time_ms");
        if (tileTime != null) {
            detection.getMeasurements().put("tile_time_ms", tileTime.doubleValue());
        }

        Number satR = (Number) entry.get("saturation_R_pct");
        if (satR != null) {
            detection.getMeasurements().put("saturation_R_pct", satR.doubleValue());
        }
        Number satG = (Number) entry.get("saturation_G_pct");
        if (satG != null) {
            detection.getMeasurements().put("saturation_G_pct", satG.doubleValue());
        }
        Number satB = (Number) entry.get("saturation_B_pct");
        if (satB != null) {
            detection.getMeasurements().put("saturation_B_pct", satB.doubleValue());
        }
        Number satWorst = (Number) entry.get("saturation_worst_pct");
        if (satWorst != null) {
            detection.getMeasurements().put("saturation_worst_pct", satWorst.doubleValue());
        }

        // Per-role saturation aggregates -- the user's primary filter for PPM:
        // saturation_role_low_pct lets you find tiles where small-angle channels
        // saturated, ignoring the (intentionally bright) uncrossed angle.
        Number satRoleLow = (Number) entry.get("saturation_role_low_pct");
        if (satRoleLow != null) {
            detection.getMeasurements().put("saturation_role_low_pct", satRoleLow.doubleValue());
        }
        Number satRoleHigh = (Number) entry.get("saturation_role_high_pct");
        if (satRoleHigh != null) {
            detection.getMeasurements().put("saturation_role_high_pct", satRoleHigh.doubleValue());
        }
        Number satRoleNormal = (Number) entry.get("saturation_role_normal_pct");
        if (satRoleNormal != null) {
            detection.getMeasurements().put("saturation_role_normal_pct", satRoleNormal.doubleValue());
        }

        // saturation_role: numeric code for filtering ("signal_low"=0, "signal_normal"=1,
        // "signal_high"=2, "calibration_reference"=3). String label is also stored in
        // metadata so the dialog and downstream tools can show the human-readable name.
        String roleLabel = (String) entry.get("saturation_role");
        if (roleLabel != null) {
            double roleCode;
            switch (roleLabel) {
                case "signal_low" -> roleCode = 0.0;
                case "signal_normal" -> roleCode = 1.0;
                case "signal_high" -> roleCode = 2.0;
                case "calibration_reference" -> roleCode = 3.0;
                default -> roleCode = 1.0;
            }
            detection.getMeasurements().put("saturation_role_code", roleCode);
            try {
                detection.getMetadata().put("saturation_role", roleLabel);
            } catch (Exception ignored) {
                // getMetadata() returns the read-only metadata map on some QuPath
                // versions; falling back to measurements only is fine.
            }
        }

        // Acquisition order + timestamp -- enables sort-by-acquisition-order in
        // QuPath to spot drift across a long run.
        Number acqOrder = (Number) entry.get("acq_order_index");
        if (acqOrder != null) {
            detection.getMeasurements().put("acq_order_index", acqOrder.doubleValue());
        }
        String acqTs = (String) entry.get("acq_timestamp_iso");
        if (acqTs != null) {
            try {
                detection.getMetadata().put("acq_timestamp_iso", acqTs);
            } catch (Exception ignored) {
                // see note above
            }
        }

        // Per-channel percentile / mean / std / dynamic_range stats. Forwarded
        // through verbatim from the NDJSON; the Python side names them
        // p1_R/G/B/gray, p99_R/G/B/gray, mean_*, std_*, dynamic_range_*.
        // Surfaces dim-tile under-exposure (low p99) alongside saturation in
        // the same QuPath measurement table.
        for (String prefix : new String[]{"p1_", "p99_", "mean_", "std_", "dynamic_range_"}) {
            for (String suffix : new String[]{"R", "G", "B", "gray"}) {
                String key = prefix + suffix;
                Number val = (Number) entry.get(key);
                if (val != null) {
                    detection.getMeasurements().put(key, val.doubleValue());
                }
            }
        }
        Boolean underexposed = (Boolean) entry.get("underexposed");
        if (underexposed != null) {
            detection.getMeasurements().put("underexposed", underexposed ? 1.0 : 0.0);
        }
    }

    /**
     * Scores tiles from the WSI to find the best tile for initial autofocus.
     *
     * <p>Reads the TileConfiguration_QP.txt (QuPath pixel coordinates) and checks
     * each tile's region in the WSI for tissue content. Returns the index of the
     * first tile (in acquisition order) that has sufficient tissue.
     *
     * @param annotation The annotation being acquired
     * @param modalityWithIndex e.g. "ppm_20x_1"
     * @return Index of the best AF tile, or -1 if scoring fails
     */
    private int findBestAfTileFromWSI(
            PathObject annotation,
            String modalityWithIndex,
            double minTissueScore,
            int whiteThreshold,
            int darkThreshold) {
        // Use the ImageData captured at session start -- gui.getImageData() may be null
        // after stitching dialogs close and the viewer loses its image reference.
        if (capturedImageData == null) return -1;

        var server = capturedImageData.getServer();
        double pixelSize = server.getPixelCalibration().getAveragedPixelSizeMicrons();
        if (pixelSize <= 0 || Double.isNaN(pixelSize)) return -1;

        // Read tile positions from TileConfiguration_QP.txt (pixel coordinates)
        Path tileDir = Paths.get(state.projectInfo.getTempTileDirectory(), annotation.getName());
        Path tileConfigQP = tileDir.resolve("TileConfiguration_QP.txt");
        if (!Files.exists(tileConfigQP)) {
            logger.debug("No TileConfiguration_QP.txt for {}", annotation.getName());
            return -1;
        }

        // Get frame size in pixels (from camera FOV)
        double[] fovMicrons;
        try {
            fovMicrons = MicroscopeController.getInstance()
                    .getCameraFOVFromConfig(state.sample.modality(), state.sample.objective(), state.sample.detector());
        } catch (Exception e) {
            return -1;
        }
        int frameW = (int) Math.round(fovMicrons[0] / pixelSize);
        int frameH = (int) Math.round(fovMicrons[1] / pixelSize);

        // Parse tile positions
        List<double[]> tilePositions = new ArrayList<>();
        List<Integer> tileIndices = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(tileConfigQP)) {
                // Format: "0.tif; ; (123.456, 789.012)"
                if (!line.contains(".tif")) continue;
                int parenStart = line.indexOf('(');
                int parenEnd = line.indexOf(')');
                if (parenStart < 0 || parenEnd < 0) continue;
                String[] coords = line.substring(parenStart + 1, parenEnd).split(",");
                double cx = Double.parseDouble(coords[0].trim());
                double cy = Double.parseDouble(coords[1].trim());
                int idx = Integer.parseInt(line.substring(0, line.indexOf('.')).trim());
                tilePositions.add(new double[] {cx, cy});
                tileIndices.add(idx);
            }
        } catch (Exception e) {
            logger.debug("Failed to parse tile config: {}", e.getMessage());
            return -1;
        }

        if (tilePositions.isEmpty()) return -1;

        // Score tiles in acquisition order (first N, then keep going if needed)
        int bestTile = -1;
        double bestScore = 0;

        // Use a lower-res downsample for speed (4x faster reads)
        double downsample = Math.max(1.0, pixelSize < 0.5 ? 4.0 : 2.0);

        for (int i = 0; i < tilePositions.size(); i++) {
            double cx = tilePositions.get(i)[0];
            double cy = tilePositions.get(i)[1];
            int tileIdx = tileIndices.get(i);

            // Convert centroid to top-left corner
            int x = Math.max(0, (int) (cx - frameW / 2.0));
            int y = Math.max(0, (int) (cy - frameH / 2.0));
            int w = Math.min(frameW, server.getWidth() - x);
            int h = Math.min(frameH, server.getHeight() - y);
            if (w <= 0 || h <= 0) continue;

            try {
                var request = qupath.lib.regions.RegionRequest.createInstance(server.getPath(), downsample, x, y, w, h);
                BufferedImage img = server.readRegion(request);
                if (img == null) continue;

                double tissueScore = scoreTissueContent(img, whiteThreshold, darkThreshold);
                if (tissueScore > bestScore) {
                    bestScore = tissueScore;
                    bestTile = tileIdx;
                }

                // Accept first tile that meets the threshold
                if (tissueScore >= minTissueScore) {
                    logger.info(
                            "WSI tissue scoring: tile {} has {}% tissue (threshold {}%)",
                            tileIdx,
                            String.format("%.1f", tissueScore * 100),
                            String.format("%.0f", minTissueScore * 100));
                    return tileIdx;
                }
            } catch (Exception e) {
                logger.debug("Failed to read WSI region for tile {}: {}", tileIdx, e.getMessage());
            }
        }

        // No tile met threshold -- return the one with most tissue (if any)
        if (bestTile >= 0 && bestScore > 0.02) {
            logger.info(
                    "WSI tissue scoring: no tile met {}% threshold, using best tile {} ({}%)",
                    (int) (minTissueScore * 100), bestTile, String.format("%.1f", bestScore * 100));
            return bestTile;
        }

        logger.info("WSI tissue scoring: insufficient tissue found in any tile");
        return -1;
    }

    /**
     * Scores a BufferedImage for tissue content.
     * Returns the fraction of pixels that appear to contain tissue (0.0 to 1.0).
     *
     * @param img The image to score
     * @param whiteThreshold Pixel mean RGB above this = white/blank (from YAML)
     * @param darkThreshold Pixel mean RGB below this = background/artifact (from YAML)
     */
    private static double scoreTissueContent(BufferedImage img, int whiteThreshold, int darkThreshold) {
        int w = img.getWidth();
        int h = img.getHeight();
        int totalPixels = w * h;
        if (totalPixels == 0) return 0;

        int tissuePixels = 0;
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int rgb = img.getRGB(px, py);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int mean = (r + g + b) / 3;
                if (mean < whiteThreshold && mean > darkThreshold) {
                    tissuePixels++;
                }
            }
        }
        return (double) tissuePixels / totalPixels;
    }
}
