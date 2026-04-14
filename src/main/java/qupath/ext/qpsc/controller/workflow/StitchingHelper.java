package qupath.ext.qpsc.controller.workflow;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.Channel;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.model.SampleSetupResult;
import qupath.ext.qpsc.model.StitchingMetadata;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.DualProgressDialog;
import qupath.ext.qpsc.ui.StitchingBlockingDialog;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
import qupath.ext.qpsc.utilities.StitchingConfiguration;
import qupath.ext.qpsc.utilities.TileProcessingUtilities;
import qupath.ext.basicstitching.assembly.ChannelMerger;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Helper class for image stitching operations.
 *
 * <p>This class manages:
 * <ul>
 *   <li>Stitching acquired tiles into pyramidal OME-TIFF images</li>
 *   <li>Handling multi-angle acquisitions (e.g., polarized light)</li>
 *   <li>Updating the QuPath project with stitched images and metadata</li>
 *   <li>Error handling and user notification</li>
 * </ul>
 *
 * <p>Stitching is performed asynchronously to avoid blocking the UI or
 * subsequent acquisitions.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class StitchingHelper {
    private static final Logger logger = LoggerFactory.getLogger(StitchingHelper.class);

    /**
     * Performs stitching for a single annotation across all rotation angles.
     *
     * <p>For multi-angle acquisitions (e.g., polarized light), this method
     * performs a single batch stitching operation that processes all angles
     * at once. The BasicStitching extension will create separate output files
     * for each angle, which are then renamed and imported into the project.</p>
     *
     * <p>For single acquisitions without rotation angles, standard stitching
     * is performed using the annotation name as the matching pattern.</p>
     *
     * @param annotation The annotation that was acquired
     * @param sample Sample setup information
     * @param modeWithIndex Imaging mode with index suffix
     * @param angleExposures Rotation angles with exposure settings (empty for single acquisition)
     * @param pixelSize Pixel size in micrometers
     * @param gui QuPath GUI instance
     * @param project QuPath project to update
     * @param executor Executor service for async execution
     * @param handler Modality handler for file naming
     * @param sampleName The actual sample folder name (from ProjectInfo, may differ from sample.sampleName())
     * @param projectsFolder The actual projects folder path (from ProjectInfo, may differ from sample.projectsFolder())
     * @return CompletableFuture that completes when all stitching is done
     */
    public static CompletableFuture<Void> performAnnotationStitching(
            PathObject annotation,
            SampleSetupResult sample,
            String modeWithIndex,
            List<AngleExposure> angleExposures,
            double pixelSize,
            QuPathGUI gui,
            Project<BufferedImage> project,
            ExecutorService executor,
            ModalityHandler handler,
            String sampleName,
            String projectsFolder) {

        // Get transform from current controller
        AffineTransform fullResToStage = MicroscopeController.getInstance().getCurrentTransform();

        return performAnnotationStitching(
                annotation,
                sample,
                modeWithIndex,
                angleExposures,
                pixelSize,
                gui,
                project,
                executor,
                handler,
                fullResToStage,
                sampleName,
                projectsFolder,
                null,
                null);
    }

    /**
     * Performs stitching for a single annotation across all rotation angles.
     * Enhanced version that accepts transform for metadata calculation.
     *
     * @param annotation The annotation that was acquired
     * @param sample Sample setup information
     * @param modeWithIndex Imaging mode with index suffix
     * @param angleExposures Rotation angles with exposure settings (empty for single acquisition)
     * @param pixelSize Pixel size in micrometers
     * @param gui QuPath GUI instance
     * @param project QuPath project to update
     * @param executor Executor service for async execution
     * @param handler Modality handler for file naming
     * @param fullResToStage Transform from full-res pixels to stage coordinates
     * @param sampleName The actual sample folder name (from ProjectInfo, may differ from sample.sampleName())
     * @param projectsFolder The actual projects folder path (from ProjectInfo, may differ from sample.projectsFolder())
     * @param dualProgressDialog Optional progress dialog for showing stitching status alongside acquisition progress (can be null)
     * @param parentEntry Pre-captured parent image entry for metadata inheritance (can be null to fall back to gui lookup)
     * @return CompletableFuture that completes when all stitching is done
     */
    public static CompletableFuture<Void> performAnnotationStitching(
            PathObject annotation,
            SampleSetupResult sample,
            String modeWithIndex,
            List<AngleExposure> angleExposures,
            double pixelSize,
            QuPathGUI gui,
            Project<BufferedImage> project,
            ExecutorService executor,
            ModalityHandler handler,
            AffineTransform fullResToStage,
            String sampleName,
            String projectsFolder,
            DualProgressDialog dualProgressDialog,
            ProjectImageEntry<BufferedImage> parentEntry) {

        // Use sample.sampleName() for file naming (source image name), not sampleName (project folder name)
        String displayName = sample.sampleName();
        StitchingMetadata metadata =
                calculateMetadata(annotation, displayName, gui, project, fullResToStage, parentEntry);
        return performStitchingInternal(
                annotation.getName(),
                sample,
                metadata,
                modeWithIndex,
                angleExposures,
                pixelSize,
                gui,
                project,
                executor,
                handler,
                sampleName,
                projectsFolder,
                dualProgressDialog);
    }

    /**
     * Performs stitching for a region identified by name (for BoundedAcquisitionWorkflow).
     * This is used when there's no actual PathObject annotation, just a region name like "bounds".
     *
     * @param regionName The name of the region (e.g., "bounds" for BoundedAcquisitionWorkflow)
     * @param sample Sample setup information
     * @param modeWithIndex Imaging mode with index suffix
     * @param angleExposures Rotation angles with exposure settings (empty for single acquisition)
     * @param pixelSize Pixel size in micrometers
     * @param gui QuPath GUI instance
     * @param project QuPath project to update
     * @param executor Executor service for async execution
     * @param handler Modality handler for file naming
     * @param sampleName The actual sample folder name (from ProjectInfo, may differ from sample.sampleName())
     * @param projectsFolder The actual projects folder path (from ProjectInfo, may differ from sample.projectsFolder())
     * @return CompletableFuture that completes when all stitching is done
     */
    public static CompletableFuture<Void> performRegionStitching(
            String regionName,
            SampleSetupResult sample,
            String modeWithIndex,
            List<AngleExposure> angleExposures,
            double pixelSize,
            QuPathGUI gui,
            Project<BufferedImage> project,
            ExecutorService executor,
            ModalityHandler handler,
            String sampleName,
            String projectsFolder) {

        // Use user-entered sample name for display/naming, not the project folder name.
        String displayName = (sample != null
                        && sample.sampleName() != null
                        && !sample.sampleName().isEmpty())
                ? sample.sampleName()
                : sampleName;
        StitchingMetadata metadata = calculateMetadataForRegion(regionName, displayName, gui, project);
        return performStitchingInternal(
                regionName,
                sample,
                metadata,
                modeWithIndex,
                angleExposures,
                pixelSize,
                gui,
                project,
                executor,
                handler,
                sampleName,
                projectsFolder,
                null);
    }

    /**
     * Shared implementation backing both {@link #performAnnotationStitching} and
     * {@link #performRegionStitching}. Dispatches between three branches based on
     * the modality's axis shape:
     *
     * <ol>
     *   <li>channel-based (widefield IF, BF+IF) -> {@link #stitchChannelDirectories}</li>
     *   <li>multi-angle (PPM and similar) -> per-angle directory isolation loop,
     *       parallel for OME-ZARR (independent chunks) and sequential for OME-TIFF
     *       (BioFormats TiffWriter concurrency bug)</li>
     *   <li>single-pass (brightfield, fluorescence without channel library, single-angle
     *       PPM degenerate case) -> direct call to {@link TileProcessingUtilities#stitchImagesAndUpdateProject}</li>
     * </ol>
     *
     * <p>Callers pre-compute {@link StitchingMetadata} and pass it in along with an
     * opaque {@code targetName} (annotation name for the annotation path, region name
     * for the bounded-region path). The rest of the pipeline is identical across
     * both callers.
     */
    private static CompletableFuture<Void> performStitchingInternal(
            String targetName,
            SampleSetupResult sample,
            StitchingMetadata metadata,
            String modeWithIndex,
            List<AngleExposure> angleExposures,
            double pixelSize,
            QuPathGUI gui,
            Project<BufferedImage> project,
            ExecutorService executor,
            ModalityHandler handler,
            String sampleName,
            String projectsFolder,
            DualProgressDialog dualProgressDialog) {

        // Create blocking dialog on JavaFX thread before starting stitching
        final String operationId = sampleName + " - " + targetName;
        final StitchingBlockingDialog[] dialogRef = {null};
        final CountDownLatch dialogLatch = new CountDownLatch(1);
        try {
            Platform.runLater(() -> {
                try {
                    dialogRef[0] = StitchingBlockingDialog.show(operationId, operationId);
                    // Also register with DualProgressDialog if provided
                    if (dualProgressDialog != null) {
                        dualProgressDialog.registerStitchingOperation(operationId, operationId);
                    }
                } finally {
                    dialogLatch.countDown();
                }
            });
            // Wait for dialog creation to complete (max 5 seconds)
            if (!dialogLatch.await(5, TimeUnit.SECONDS)) {
                logger.warn("Timeout waiting for stitching blocking dialog creation");
            }
        } catch (Exception e) {
            logger.warn("Failed to create stitching blocking dialog", e);
        }
        final StitchingBlockingDialog blockingDialog = dialogRef[0];

        // Channel-based modalities (widefield IF, BF+IF) write per-channel tiles into
        // per-channel subdirectories named after the channel id. Detect this by asking
        // the handler for its channel library; if non-empty, take the channel branch
        // which mirrors the multi-angle isolation flow but using channel ids as the
        // subdirectory names.
        List<String> channelIdsForStitching = resolveChannelIdsForStitching(handler, sample);
        logger.info(
                "Stitcher branch selector: modality='{}' objective='{}' -> resolved {} channel(s): {}, angleExposures.size={}",
                sample != null ? sample.modality() : null,
                sample != null ? sample.objective() : null,
                channelIdsForStitching.size(),
                channelIdsForStitching,
                angleExposures != null ? angleExposures.size() : 0);
        if (!channelIdsForStitching.isEmpty()) {
            return stitchChannelDirectories(
                    targetName,
                    channelIdsForStitching,
                    metadata,
                    operationId,
                    blockingDialog,
                    dualProgressDialog,
                    sampleName,
                    projectsFolder,
                    modeWithIndex,
                    pixelSize,
                    gui,
                    project,
                    executor,
                    handler,
                    sample != null ? sample.modality() : null);
        }

        if (angleExposures != null && angleExposures.size() > 1) {
            logger.info("Stitching {} angles for target: {}", angleExposures.size(), targetName);

            // For multi-angle acquisitions, do ONE batch stitch with "." as matching string
            return CompletableFuture.runAsync(
                    () -> {
                        try {
                            String annotationName = targetName;

                            if (blockingDialog != null) {
                                blockingDialog.updateStatus(
                                        operationId,
                                        "Initializing multi-angle stitching for " + annotationName + "...");
                            }

                            logger.info(
                                    "Performing batch stitching for {} with {} angles",
                                    annotationName,
                                    angleExposures.size());
                            logger.info(
                                    "Metadata - offset: ({}, {}) um, flipX: {}, flipY: {}, parent: {}",
                                    metadata.xOffset,
                                    metadata.yOffset,
                                    metadata.flipX,
                                    metadata.flipY,
                                    metadata.parentEntry != null ? metadata.parentEntry.getImageName() : "none");

                            // Get standard stitching configuration
                            StitchingConfiguration.StitchingParams stitchingConfig =
                                    StitchingConfiguration.getStandardConfiguration();
                            String compression = stitchingConfig.compressionType();

                            // Create enhanced parameters map for UtilityFunctions
                            // NOTE: For multi-angle acquisitions, do NOT pass blockingDialog to individual angle
                            // processing calls to prevent premature dialog closure. Dialog will be completed
                            // manually after all angles/biref/sum are processed.
                            Map<String, Object> stitchParams = new HashMap<>();
                            stitchParams.put("metadata", metadata);
                            // Do NOT include blockingDialog or operationId for multi-angle case

                            if (blockingDialog != null) {
                                blockingDialog.updateStatus(
                                        operationId,
                                        "Processing " + angleExposures.size() + " angles for " + annotationName
                                                + "...");
                            }

                            // Process each angle individually using directory isolation to prevent cross-matching
                            logger.info(
                                    "Processing {} angle directories using isolation approach", angleExposures.size());

                            List<String> stitchedImages = new ArrayList<>();
                            Path tileBaseDir = Paths.get(projectsFolder, sampleName, modeWithIndex, annotationName);

                            logger.info(
                                    "Starting multi-angle processing for {} angles in directory: {}",
                                    angleExposures.size(),
                                    tileBaseDir);

                            // Log initial directory state
                            try {
                                if (Files.exists(tileBaseDir)) {
                                    long dirCount = Files.list(tileBaseDir)
                                            .filter(Files::isDirectory)
                                            .count();
                                    logger.info("Initial tile base directory contains {} subdirectories", dirCount);
                                    logger.debug("Subdirectories:");
                                    Files.list(tileBaseDir)
                                            .filter(Files::isDirectory)
                                            .forEach(path -> logger.debug("  - {}", path.getFileName()));
                                } else {
                                    logger.warn("Tile base directory does not exist: {}", tileBaseDir);
                                }
                            } catch (IOException e) {
                                logger.warn("Could not list initial tile base directory: {}", e.getMessage());
                            }

                            // OME-ZARR: parallel angle stitching (each chunk is
                            // independent, no shared writer state).
                            // OME-TIFF: sequential (BioFormats TiffWriter NPEs when
                            // multiple writers run concurrently; a global semaphore
                            // in PyramidImageWriter serializes writes as defense).
                            boolean useParallel =
                                    stitchingConfig.outputFormat() == StitchingConfig.OutputFormat.OME_ZARR;

                            String mode = useParallel ? "parallel" : "sequential";
                            logger.info("Starting {} stitching for {} angles (format={})",
                                    mode, angleExposures.size(), stitchingConfig.outputFormat());

                            if (blockingDialog != null) {
                                blockingDialog.updateStatus(
                                        operationId,
                                        "Stitching " + angleExposures.size() + " angles ("
                                                + mode + ") for " + annotationName + "...");
                            }

                            if (useParallel) {
                                // ZARR: dispatch all angles concurrently
                                List<CompletableFuture<String>> angleFutures = new ArrayList<>();

                                for (int i = 0; i < angleExposures.size(); i++) {
                                    AngleExposure angleExposure = angleExposures.get(i);
                                    String angleStr = String.valueOf(angleExposure.ticks());
                                    final int angleIndex = i;

                                    logger.info("Launching parallel stitch for angle {} ({}/{})",
                                            angleStr, i + 1, angleExposures.size());

                                    angleFutures.add(CompletableFuture.supplyAsync(() -> {
                                        try {
                                            return processAngleWithIsolation(
                                                    tileBaseDir, angleStr,
                                                    projectsFolder, sampleName,
                                                    modeWithIndex, annotationName,
                                                    compression, pixelSize,
                                                    stitchingConfig.downsampleFactor(),
                                                    gui, project, handler, stitchParams);
                                        } catch (Exception e) {
                                            logger.error("Failed to stitch angle {} ({}/{}): {}",
                                                    angleStr, angleIndex + 1,
                                                    angleExposures.size(), e.getMessage(), e);
                                            return null;
                                        }
                                    }));
                                }

                                CompletableFuture.allOf(
                                        angleFutures.toArray(new CompletableFuture[0])).join();

                                for (int i = 0; i < angleFutures.size(); i++) {
                                    try {
                                        String outPath = angleFutures.get(i).get();
                                        if (outPath != null) {
                                            stitchedImages.add(outPath);
                                            logger.info("Parallel stitch completed for angle {}: {}",
                                                    angleExposures.get(i).ticks(), outPath);
                                        }
                                    } catch (Exception e) {
                                        logger.error("Failed to get result for angle {}: {}",
                                                angleExposures.get(i).ticks(), e.getMessage());
                                    }
                                }
                            } else {
                                // TIFF: sequential to avoid BioFormats concurrency bug
                                for (int i = 0; i < angleExposures.size(); i++) {
                                    AngleExposure angleExposure = angleExposures.get(i);
                                    String angleStr = String.valueOf(angleExposure.ticks());

                                    logger.info("Stitching angle {} ({}/{})",
                                            angleStr, i + 1, angleExposures.size());

                                    try {
                                        String outPath = processAngleWithIsolation(
                                                tileBaseDir, angleStr,
                                                projectsFolder, sampleName,
                                                modeWithIndex, annotationName,
                                                compression, pixelSize,
                                                stitchingConfig.downsampleFactor(),
                                                gui, project, handler, stitchParams);
                                        if (outPath != null) {
                                            stitchedImages.add(outPath);
                                            logger.info("Stitch completed for angle {}: {}",
                                                    angleExposure.ticks(), outPath);
                                        }
                                    } catch (Exception e) {
                                        logger.error("Failed to stitch angle {} ({}/{}): {}",
                                                angleStr, i + 1, angleExposures.size(),
                                                e.getMessage(), e);
                                    }
                                }
                            }

                            logger.info("Completed {} stitching of {} angles. Successfully stitched {} images.",
                                    mode, angleExposures.size(), stitchedImages.size());

                            // Process modality-specific post-processing directories (e.g., biref, sum)
                            processPostProcessingDirectories(
                                    handler,
                                    tileBaseDir,
                                    annotationName,
                                    projectsFolder,
                                    sampleName,
                                    modeWithIndex,
                                    compression,
                                    pixelSize,
                                    stitchingConfig.downsampleFactor(),
                                    gui,
                                    project,
                                    stitchParams,
                                    blockingDialog,
                                    operationId,
                                    stitchedImages);

                            // Return path of last successfully processed image
                            String outPath =
                                    stitchedImages.isEmpty() ? null : stitchedImages.get(stitchedImages.size() - 1);

                            logger.info("Batch stitching completed for {}, output: {}", annotationName, outPath);

                            // Complete the blocking dialog now that ALL angles/post-processing are done
                            if (blockingDialog != null) {
                                logger.info("Completing stitching dialog operation after all images processed");
                                blockingDialog.completeOperation(operationId);
                            }
                            // Also complete in DualProgressDialog if provided
                            if (dualProgressDialog != null) {
                                dualProgressDialog.completeStitchingOperation(operationId);
                            }

                        } catch (Exception e) {
                            logger.error("Stitching failed for {}", targetName, e);

                            // Mark operation as failed
                            if (blockingDialog != null) {
                                blockingDialog.failOperation(operationId, e.getMessage());
                            }
                            // Also mark failed in DualProgressDialog if provided
                            if (dualProgressDialog != null) {
                                dualProgressDialog.failStitchingOperation(operationId, e.getMessage());
                            }
                            if (blockingDialog == null) {
                                Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                        String.format(
                                                "Stitching failed for %s: %s", targetName, e.getMessage()),
                                        "Stitching Error"));
                            }
                        }
                    },
                    executor);
        } else {
            // Single stitch for non-rotational acquisition (no angles)
            return CompletableFuture.runAsync(
                    () -> {
                        try {
                            String annotationName = targetName;

                            if (blockingDialog != null) {
                                blockingDialog.updateStatus(
                                        operationId, "Initializing single stitching for " + annotationName + "...");
                            }

                            logger.info("Stitching single acquisition for {}", annotationName);
                            logger.info(
                                    "Metadata - offset: ({}, {}) um, flipX: {}, flipY: {}, parent: {}",
                                    metadata.xOffset,
                                    metadata.yOffset,
                                    metadata.flipX,
                                    metadata.flipY,
                                    metadata.parentEntry != null ? metadata.parentEntry.getImageName() : "none");

                            String compression = String.valueOf(QPPreferenceDialog.getCompressionTypeProperty());

                            // Create enhanced parameters map. Pass blockingDialog/operationId so
                            // TileProcessingUtilities can complete the dialog itself after project
                            // import, which is the correct point to close it -- otherwise the
                            // dialog stays up through the async import and the user sees a stale
                            // "processing..." message.
                            Map<String, Object> stitchParams = new HashMap<>();
                            stitchParams.put("metadata", metadata);
                            stitchParams.put("blockingDialog", blockingDialog);
                            stitchParams.put("operationId", operationId);

                            if (blockingDialog != null) {
                                blockingDialog.updateStatus(
                                        operationId, "Processing single acquisition for " + annotationName + "...");
                            }

                            // Check if we have exactly one angle (tiles are in angle subfolder)
                            String matchingString = annotationName;
                            if (angleExposures != null && angleExposures.size() == 1) {
                                // Single angle case - tiles are in angle subfolder (e.g., "5.0")
                                matchingString =
                                        String.valueOf(angleExposures.get(0).ticks());
                                logger.info("Single angle acquisition - looking in subfolder: {}", matchingString);
                            }

                            String outPath = TileProcessingUtilities.stitchImagesAndUpdateProject(
                                    projectsFolder,
                                    sampleName,
                                    modeWithIndex,
                                    annotationName,
                                    matchingString, // Use angle folder name as matching string for single-angle
                                    // acquisitions
                                    gui,
                                    project,
                                    compression,
                                    pixelSize,
                                    1,
                                    handler,
                                    stitchParams // Pass metadata
                                    );

                            logger.info("Stitching completed for {}, output: {}", annotationName, outPath);

                            // Note: Dialog completion is handled in TileProcessingUtilities after project import
                            // But we complete the DualProgressDialog here since TileProcessingUtilities doesn't have
                            // access to it
                            if (dualProgressDialog != null) {
                                dualProgressDialog.completeStitchingOperation(operationId);
                            }

                        } catch (Exception e) {
                            logger.error("Stitching failed for {}", targetName, e);

                            // Mark operation as failed
                            if (blockingDialog != null) {
                                blockingDialog.failOperation(operationId, e.getMessage());
                            }
                            // Also mark failed in DualProgressDialog if provided
                            if (dualProgressDialog != null) {
                                dualProgressDialog.failStitchingOperation(operationId, e.getMessage());
                            }
                            if (blockingDialog == null) {
                                Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                        String.format(
                                                "Stitching failed for %s: %s", targetName, e.getMessage()),
                                        "Stitching Error"));
                            }
                        }
                    },
                    executor);
        }
    }


    /**
     * Calculates metadata for a region-based acquisition (BoundedAcquisitionWorkflow).
     * Since there's no actual annotation, we create default metadata.
     *
     * @param sampleName The actual sample folder name (from ProjectInfo)
     */
    private static StitchingMetadata calculateMetadataForRegion(
            String regionName, String sampleName, QuPathGUI gui, Project<BufferedImage> project) {

        // Get parent entry (the current open image) - may be null in Bounded Acquisition
        ProjectImageEntry<BufferedImage> parentEntry = null;
        // In Bounded Acquisition, there's typically no current image open
        // Use QuPath's proper method to check for open image
        if (gui.getViewer().hasServer() && gui.getImageData() != null) {
            try {
                parentEntry = project.getEntry(gui.getImageData());
            } catch (Exception e) {
                logger.warn("Could not get parent entry from project: {}", e.getMessage());
                parentEntry = null;
            }
        }

        // For Bounded Acquisition, we don't have actual annotation coordinates
        // The offset should be 0,0 since it's a full-slide acquisition
        double xOffset = 0.0;
        double yOffset = 0.0;

        // Check flip status from parent or preferences
        boolean flipX = false;
        boolean flipY = false;
        if (parentEntry != null) {
            flipX = ImageMetadataManager.isFlippedX(parentEntry);
            flipY = ImageMetadataManager.isFlippedY(parentEntry);
        } else {
            // If no parent, use preferences
            flipX = QPPreferenceDialog.getFlipMacroXProperty();
            flipY = QPPreferenceDialog.getFlipMacroYProperty();
        }

        return new StitchingMetadata(parentEntry, xOffset, yOffset, flipX, flipY, sampleName);
    }

    /**
     * Calculates metadata for a stitched image based on its parent annotation.
     *
     * @param sampleName The actual sample folder name (from ProjectInfo)
     */
    private static StitchingMetadata calculateMetadata(
            PathObject annotation,
            String sampleName,
            QuPathGUI gui,
            Project<BufferedImage> project,
            AffineTransform fullResToStage,
            ProjectImageEntry<BufferedImage> capturedParentEntry) {

        // Use pre-captured parent entry if available (stable across the whole session).
        // Fall back to gui lookup only if no pre-captured entry was provided.
        ProjectImageEntry<BufferedImage> parentEntry = capturedParentEntry;
        if (parentEntry == null && gui.getViewer().hasServer() && gui.getImageData() != null) {
            parentEntry = project.getEntry(gui.getImageData());
        }

        // Calculate offset from slide corner
        double[] offset = TransformationFunctions.calculateAnnotationOffsetFromSlideCorner(annotation, fullResToStage);

        // Check flip status from parent or preferences
        boolean flipX = false;
        boolean flipY = false;
        if (parentEntry != null) {
            flipX = ImageMetadataManager.isFlippedX(parentEntry);
            flipY = ImageMetadataManager.isFlippedY(parentEntry);
        } else {
            // If no parent, use preferences
            flipX = QPPreferenceDialog.getFlipMacroXProperty();
            flipY = QPPreferenceDialog.getFlipMacroYProperty();
        }

        return new StitchingMetadata(parentEntry, offset[0], offset[1], flipX, flipY, sampleName);
    }

    /**
     * Queries the modality handler for its channel library and returns the list of
     * channel ids to stitch. Returns an empty list for angle-based modalities. The
     * channel ids double as subdirectory names under the annotation folder (mirroring
     * how PPM uses angle-tick strings as subdirectory names).
     *
     * <p>Channel library lookup is keyed by the enhanced profile name (e.g.
     * {@code "Fluorescence_10x"}), NOT the base modality name
     * ({@code "Fluorescence"}), because {@code acquisition_profiles[]} and the
     * per-profile channel library are indexed by the enhanced key. Passing the
     * base modality here was the second instance of the bug we fixed in
     * {@code AngleResolutionService} / {@code BoundedAcquisitionWorkflow}; the
     * stitcher would silently fall through to the angle-based path on an empty
     * {@code bounds/} folder (because the tiles were in {@code bounds/FITC/}
     * etc.) and the user would see only one channel in the final image.
     */
    private static List<String> resolveChannelIdsForStitching(ModalityHandler handler, SampleSetupResult sample) {
        if (handler == null || sample == null) {
            logger.info(
                    "resolveChannelIdsForStitching: early exit -- handler={} sample={}",
                    handler,
                    sample);
            return List.of();
        }
        String profileKey = qupath.ext.qpsc.utilities.ObjectiveUtils.createEnhancedFolderName(
                sample.modality(), sample.objective());
        logger.info(
                "resolveChannelIdsForStitching: handler={} modality='{}' objective='{}' -> profileKey='{}'",
                handler.getClass().getSimpleName(),
                sample.modality(),
                sample.objective(),
                profileKey);
        try {
            List<Channel> channels = handler.getChannels(profileKey, sample.objective(), sample.detector())
                    .join();
            int count = channels == null ? 0 : channels.size();
            logger.info(
                    "resolveChannelIdsForStitching: handler.getChannels('{}') returned {} channel(s)",
                    profileKey,
                    count);
            if (channels == null || channels.isEmpty()) {
                return List.of();
            }
            List<String> ids = new ArrayList<>(channels.size());
            for (Channel c : channels) {
                ids.add(c.id());
            }
            logger.info("resolveChannelIdsForStitching: resolved ids={}", ids);
            return ids;
        } catch (Exception e) {
            logger.warn(
                    "Failed to resolve channel library for stitching (profile={}): {}",
                    profileKey,
                    e.getMessage(),
                    e);
            return List.of();
        }
    }

    /**
     * Stitches each channel subdirectory sequentially, mirroring the multi-angle
     * flow but keyed by channel id instead of angle tick. Produces one
     * single-channel pyramidal OME-TIFF per channel; a downstream combine step
     * (future work) is expected to merge these into a single multichannel image.
     */
    private static CompletableFuture<Void> stitchChannelDirectories(
            String annotationName,
            List<String> channelIds,
            StitchingMetadata metadata,
            String operationId,
            StitchingBlockingDialog blockingDialog,
            DualProgressDialog dualProgressDialog,
            String sampleName,
            String projectsFolder,
            String modeWithIndex,
            double pixelSize,
            QuPathGUI gui,
            Project<BufferedImage> project,
            ExecutorService executor,
            ModalityHandler handler,
            String longModalityName) {

        logger.info(
                "Stitching {} channels for: {}", channelIds.size(), annotationName);

        return CompletableFuture.runAsync(
                () -> {
                    try {
                        if (blockingDialog != null) {
                            blockingDialog.updateStatus(
                                    operationId,
                                    "Initializing multi-channel stitching for " + annotationName + "...");
                        }

                        StitchingConfiguration.StitchingParams stitchingConfig =
                                StitchingConfiguration.getStandardConfiguration();
                        String compression = stitchingConfig.compressionType();

                        Map<String, Object> stitchParams = new HashMap<>();
                        stitchParams.put("metadata", metadata);
                        // Skip per-channel project import: only the merged
                        // multichannel file becomes a project entry. The
                        // per-channel files are still stitched and renamed on
                        // disk for forensics, but never appear in the project
                        // tree -- which avoids the qpdata "save?" prompt that
                        // QuPath fires when an entry is created and then
                        // removed in the same run.
                        stitchParams.put("skipProjectImport", Boolean.TRUE);

                        Path tileBaseDir =
                                Paths.get(projectsFolder, sampleName, modeWithIndex, annotationName);

                        // Filter to only channels that were actually acquired. The library
                        // may list DAPI/FITC/TRITC/Cy5 but the user may have picked only a
                        // subset -- the Python side creates per-channel subdirs on demand,
                        // so any missing subdir means "not acquired for this run". Trying
                        // to stitch a non-existent subdir just logs a warning and wastes
                        // time, so drop them before the loop.
                        List<String> acquiredChannelIds = new ArrayList<>();
                        for (String cid : channelIds) {
                            Path channelSubdir = tileBaseDir.resolve(cid);
                            if (Files.isDirectory(channelSubdir)) {
                                acquiredChannelIds.add(cid);
                            } else {
                                logger.debug(
                                        "Skipping channel '{}' -- subdirectory does not exist: {}",
                                        cid,
                                        channelSubdir);
                            }
                        }
                        logger.info(
                                "Library declared {} channel(s); found {} on disk: {}",
                                channelIds.size(),
                                acquiredChannelIds.size(),
                                acquiredChannelIds);

                        if (acquiredChannelIds.isEmpty()) {
                            logger.error(
                                    "No channel subdirectories found in {}; nothing to stitch",
                                    tileBaseDir);
                            if (blockingDialog != null) {
                                blockingDialog.failOperation(operationId, "No channel tiles found");
                            }
                            if (dualProgressDialog != null) {
                                dualProgressDialog.failStitchingOperation(
                                        operationId, "No channel tiles found");
                            }
                            return;
                        }

                        List<String> stitchedImages = new ArrayList<>();

                        logger.info(
                                "Starting channel processing for {} channels in directory: {}",
                                acquiredChannelIds.size(),
                                tileBaseDir);

                        // Channels are stitched sequentially. BioFormats TIFF writer concurrency
                        // is the constraint (the global semaphore in PyramidImageWriter already
                        // serializes writes, so parallel dispatch would just queue). Sequential
                        // is simpler and keeps file I/O pressure manageable for IF with ~4 channels.
                        List<String> successfullyStitchedChannelIds = new ArrayList<>();
                        for (int i = 0; i < acquiredChannelIds.size(); i++) {
                            String channelId = acquiredChannelIds.get(i);
                            if (blockingDialog != null) {
                                blockingDialog.updateStatus(
                                        operationId,
                                        String.format(
                                                "Stitching channel %s (%d/%d) for %s...",
                                                channelId, i + 1, acquiredChannelIds.size(), annotationName));
                            }
                            logger.info(
                                    "Stitching channel {} ({}/{})",
                                    channelId, i + 1, acquiredChannelIds.size());
                            try {
                                String outPath = processAngleWithIsolation(
                                        tileBaseDir,
                                        channelId, // channel id doubles as the subdir name
                                        projectsFolder,
                                        sampleName,
                                        modeWithIndex,
                                        annotationName,
                                        compression,
                                        pixelSize,
                                        stitchingConfig.downsampleFactor(),
                                        gui,
                                        project,
                                        handler,
                                        stitchParams);
                                if (outPath != null) {
                                    stitchedImages.add(outPath);
                                    successfullyStitchedChannelIds.add(channelId);
                                    logger.info(
                                            "Stitch completed for channel {}: {}", channelId, outPath);
                                }
                            } catch (Exception e) {
                                logger.error(
                                        "Failed to stitch channel {} ({}/{}): {}",
                                        channelId, i + 1, acquiredChannelIds.size(), e.getMessage(), e);
                            }
                        }

                        logger.info(
                                "Completed multi-channel stitching: {} of {} channels succeeded",
                                stitchedImages.size(), acquiredChannelIds.size());

                        // Merge per-channel pyramids into a single multichannel pyramid,
                        // which becomes THE output of a channel-based acquisition: imported
                        // to the project as one entry, with the intermediate per-channel
                        // files removed from the project view (they stay on disk for
                        // debugging but should not clutter the project tree).
                        if (stitchedImages.size() >= 1) {
                            if (stitchedImages.size() >= 2 && blockingDialog != null) {
                                blockingDialog.updateStatus(
                                        operationId,
                                        "Merging " + stitchedImages.size() + " channels into multichannel pyramid...");
                            }
                            try {
                                Path firstStitch = Paths.get(stitchedImages.get(0));
                                String mergedDir = firstStitch.getParent().toString();

                                // Target filename: PollenIF_fl_001.ome.tif style,
                                // matching the per-channel naming convention but with the
                                // channel token removed and the long modality name (e.g.
                                // "Fluorescence") replaced by the short registry prefix
                                // ("fl"). Modality combos like BF+IF use their multi-token
                                // prefix verbatim ("bf_if").
                                String shortModalityPrefix = ModalityRegistry.getShortPrefix(handler);
                                String mergedStem = deriveMergedStem(
                                        firstStitch.getFileName().toString(),
                                        successfullyStitchedChannelIds.get(0),
                                        annotationName,
                                        longModalityName,
                                        shortModalityPrefix);

                                String mergedPath = ChannelMerger.merge(
                                        stitchedImages,
                                        successfullyStitchedChannelIds,
                                        mergedDir,
                                        mergedStem,
                                        compression,
                                        stitchingConfig.outputFormat());

                                if (mergedPath != null) {
                                    logger.info(
                                            "Multichannel merge succeeded for {}: {}",
                                            annotationName, mergedPath);

                                    // Per-channel intermediates were never imported to the
                                    // project (skipProjectImport=true above), so we only need
                                    // to import the merged file as the single canonical entry.
                                    importMergedImageOnly(mergedPath, metadata, gui, project, handler);
                                } else {
                                    logger.warn(
                                            "Multichannel merge returned null for {} -- per-channel files remain as the output",
                                            annotationName);
                                }
                            } catch (Exception mergeEx) {
                                logger.error(
                                        "Multichannel merge failed for {}: {}",
                                        annotationName, mergeEx.getMessage(), mergeEx);
                            }
                        } else {
                            logger.debug(
                                    "Skipping multichannel merge: only {} channel(s) stitched successfully",
                                    stitchedImages.size());
                        }

                        if (blockingDialog != null) {
                            blockingDialog.completeOperation(operationId);
                        }
                        if (dualProgressDialog != null) {
                            dualProgressDialog.completeStitchingOperation(operationId);
                        }
                    } catch (Exception e) {
                        logger.error("Channel stitching failed for {}", annotationName, e);
                        if (blockingDialog != null) {
                            blockingDialog.failOperation(operationId, e.getMessage());
                        }
                        if (dualProgressDialog != null) {
                            dualProgressDialog.failStitchingOperation(operationId, e.getMessage());
                        }
                        if (blockingDialog == null) {
                            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                    String.format(
                                            "Channel stitching failed for %s: %s",
                                            annotationName,
                                            e.getMessage()),
                                    "Stitching Error"));
                        }
                    }
                },
                executor);
    }

    /**
     * Derives the merged-file stem by stripping the trailing channel segment from
     * the first per-channel filename and (when a short prefix is supplied) replacing
     * the long modality token with the short registry prefix.
     *
     * <p>For example:
     * <pre>
     *   PollenIF_Fluorescence_FITC_001.ome.tif + channel FITC + long "Fluorescence" + short "fl"
     *      -> PollenIF_fl_001
     *   PollenIF_BF_IF_BF_001.ome.tif + channel BF + long "BF_IF" + short "bf_if"
     *      -> PollenIF_bf_if_001
     * </pre>
     *
     * <p>Falls back to {@code <annotationName>_merged} if the filename doesn't
     * contain the channel id (e.g. custom naming scheme) so we never produce a
     * garbage stem. If the long-to-short replacement isn't possible (long name not
     * found in stem, or short prefix is null), the long form is kept verbatim.
     */
    private static String deriveMergedStem(
            String firstPerChannelFilename,
            String firstChannelId,
            String annotationName,
            String longModalityName,
            String shortModalityPrefix) {
        // Strip file extension (.ome.tif, .ome.zarr, .tif, ...)
        String stem = firstPerChannelFilename;
        int dotIdx = stem.indexOf('.');
        if (dotIdx > 0) {
            stem = stem.substring(0, dotIdx);
        }
        // Look for "_<channelId>_" and remove the "_<channelId>" segment.
        String token = "_" + firstChannelId + "_";
        int tokenIdx = stem.indexOf(token);
        String stripped;
        if (tokenIdx >= 0) {
            stripped = stem.substring(0, tokenIdx) + "_" + stem.substring(tokenIdx + token.length());
        } else {
            // Handle "_<channelId>" at the end with no trailing counter.
            String tailToken = "_" + firstChannelId;
            if (stem.endsWith(tailToken)) {
                stripped = stem.substring(0, stem.length() - tailToken.length());
            } else {
                return annotationName + "_merged";
            }
        }

        // Replace the long modality token with the short registry prefix when both
        // are known. Use "_<long>_" boundaries so we don't accidentally match a
        // substring of the sample name.
        if (longModalityName != null && !longModalityName.isBlank()
                && shortModalityPrefix != null && !shortModalityPrefix.isBlank()
                && !longModalityName.equalsIgnoreCase(shortModalityPrefix)) {
            String longBounded = "_" + longModalityName + "_";
            String shortBounded = "_" + shortModalityPrefix + "_";
            if (stripped.contains(longBounded)) {
                stripped = stripped.replace(longBounded, shortBounded);
            }
        }
        return stripped;
    }

    /**
     * Imports the merged multichannel file into the QuPath project as a single entry.
     * Per-channel intermediate files are NOT imported (callers pass
     * {@code skipProjectImport=true} when invoking the per-channel stitch loop), so
     * there is nothing to remove afterward — the merged file is the only project
     * entry created by the channel-based path.
     */
    private static void importMergedImageOnly(
            String mergedPath,
            StitchingMetadata metadata,
            QuPathGUI gui,
            Project<BufferedImage> project,
            ModalityHandler handler) {
        final File mergedFile = new File(mergedPath);

        Platform.runLater(() -> {
            try {
                logger.info("Importing merged multichannel file to project: {}", mergedFile.getName());

                if (metadata != null) {
                    QPProjectFunctions.addImageToProjectWithMetadata(
                            project,
                            mergedFile,
                            metadata.parentEntry,
                            metadata.xOffset,
                            metadata.yOffset,
                            false, // already correctly oriented
                            false,
                            metadata.sampleName,
                            handler);
                } else {
                    QPProjectFunctions.addImageToProject(
                            mergedFile, project, false, false, handler);
                }
                logger.info("Merged multichannel file imported to project: {}", mergedFile.getName());

                // Refresh the project view and open the merged entry.
                gui.setProject(project);
                gui.refreshProject();

                project.getImageList().stream()
                        .filter(e -> {
                            try {
                                java.net.URI u = e.getURIs().iterator().hasNext()
                                        ? e.getURIs().iterator().next()
                                        : null;
                                return u != null
                                        && new File(u).getAbsolutePath().equals(mergedFile.getAbsolutePath());
                            } catch (Exception ex) {
                                return false;
                            }
                        })
                        .findFirst()
                        .ifPresent(entry -> {
                            logger.info("Opening merged image entry: {}", entry.getImageName());
                            try {
                                gui.openImageEntry(entry);
                            } catch (Exception openEx) {
                                logger.warn("Failed to open merged entry: {}", openEx.getMessage());
                            }
                        });
            } catch (Exception e) {
                logger.error(
                        "Failed to import merged multichannel file {}: {}",
                        mergedFile.getName(),
                        e.getMessage(),
                        e);
            }
        });
    }

    /**
     * Processes a single angle directory in isolation to prevent cross-matching issues
     * with the TileConfigurationTxtStrategy contains() logic.
     *
     * <p>Note: despite the "Angle" name, this method is used for any named step
     * directory -- the {@code angleStr} parameter is the subdirectory name and is
     * opaque to the method. The channel-based path calls it with channel ids in
     * place of angle-tick strings.
     *
     * @param projectsFolder The root projects folder path
     * @param sampleName The actual sample folder name (from ProjectInfo)
     */
    private static String processAngleWithIsolation(
            Path tileBaseDir,
            String angleStr,
            String projectsFolder,
            String sampleName,
            String modeWithIndex,
            String regionName,
            String compression,
            double pixelSize,
            int downsampleFactor,
            QuPathGUI gui,
            Project<BufferedImage> project,
            ModalityHandler handler,
            Map<String, Object> stitchParams)
            throws IOException {

        logger.info("Processing angle {} with directory isolation for region {}", angleStr, regionName);
        logger.info("Tile base directory: {}", tileBaseDir);

        Path angleDir = tileBaseDir.resolve(angleStr);
        if (!Files.exists(angleDir)) {
            logger.warn("Angle directory does not exist: {}", angleDir);
            return null;
        }

        // Create a temporary isolation directory
        String tempDirName = "_temp_" + angleStr.replace("-", "neg").replace(".", "_");
        Path tempIsolationDir = tileBaseDir.resolve(tempDirName);
        Path tempAngleDir = tempIsolationDir.resolve(angleStr);

        logger.info("Temporary isolation directory: {}", tempIsolationDir);
        logger.info("Target angle directory: {}", angleDir);

        try {
            // Create temporary structure and move angle directory
            Files.createDirectories(tempIsolationDir);
            logger.info("Created temporary isolation directory: {}", tempIsolationDir);

            // Move the target angle directory to isolation
            Files.move(angleDir, tempAngleDir);
            logger.info("Moved {} to isolation: {}", angleDir, tempAngleDir);

            // CRITICAL FIX: Pass the combined region path that includes both region and temp directory
            // The stitching method will look in: projects/sample/mode/[regionName/tempDirName]/
            // which now contains only the single angle directory we want to process
            String combinedRegion = regionName + File.separator + tempDirName;
            String outPath = TileProcessingUtilities.stitchImagesAndUpdateProject(
                    projectsFolder,
                    sampleName,
                    modeWithIndex,
                    combinedRegion, // FIXED: Use combined region path so path resolves correctly
                    angleStr, // Now this will only match the single directory in isolation
                    gui,
                    project,
                    compression,
                    pixelSize,
                    downsampleFactor,
                    handler,
                    stitchParams);

            logger.info("Isolation processing completed for angle {}, output: {}", angleStr, outPath);
            logger.info("Final stitched file path: {}", outPath);
            return outPath;

        } finally {
            // Always restore the directory structure
            logger.info("Starting cleanup - restoring directory structure for angle {}", angleStr);
            try {
                if (Files.exists(tempAngleDir)) {
                    logger.info("Restoring directory from {} to {}", tempAngleDir, angleDir);
                    Files.move(tempAngleDir, angleDir);
                    logger.info("Successfully restored {} from isolation", angleStr);
                } else {
                    logger.warn("Temporary angle directory no longer exists: {}", tempAngleDir);
                }
                if (Files.exists(tempIsolationDir)) {
                    logger.info("Cleaning up temporary isolation directory: {}", tempIsolationDir);
                    Files.delete(tempIsolationDir);
                    logger.info("Successfully cleaned up temporary isolation directory");
                } else {
                    logger.warn("Temporary isolation directory no longer exists: {}", tempIsolationDir);
                }
                logger.info("Directory structure restoration completed for angle {}", angleStr);
            } catch (IOException e) {
                logger.error(
                        "Failed to restore directory structure after isolation for angle {}: {}",
                        angleStr,
                        e.getMessage(),
                        e);
                // Log the current state for debugging
                logger.error(
                        "Current state - tempAngleDir exists: {}, tempIsolationDir exists: {}, originalAngleDir exists: {}",
                        Files.exists(tempAngleDir),
                        Files.exists(tempIsolationDir),
                        Files.exists(angleDir));
            }
        }
    }

    /**
     * Scans for and processes modality-specific post-processing directories.
     *
     * <p>After all angle directories are stitched, this method checks for additional
     * output directories created by the Python acquisition side (e.g., birefringence
     * and sum images for PPM). The suffixes to scan for are provided by the modality
     * handler via {@link ModalityHandler#getPostProcessingDirectorySuffixes()}.</p>
     *
     * @param handler          the modality handler providing directory suffixes
     * @param tileBaseDir      base directory containing angle subdirectories
     * @param regionName       name of the region/annotation being processed
     * @param projectsFolder   projects folder path
     * @param sampleName       sample name
     * @param modeWithIndex    imaging mode with index
     * @param compression      compression type for output
     * @param pixelSize        pixel size in microns
     * @param downsampleFactor downsample factor
     * @param gui              QuPath GUI
     * @param project          QuPath project
     * @param stitchParams     stitching parameters
     * @param blockingDialog   optional blocking dialog for status updates
     * @param operationId      operation ID for dialog progress tracking
     * @param stitchedImages   list to append successfully stitched image paths to
     */
    private static void processPostProcessingDirectories(
            ModalityHandler handler,
            Path tileBaseDir,
            String regionName,
            String projectsFolder,
            String sampleName,
            String modeWithIndex,
            String compression,
            double pixelSize,
            int downsampleFactor,
            QuPathGUI gui,
            Project<BufferedImage> project,
            Map<String, Object> stitchParams,
            StitchingBlockingDialog blockingDialog,
            String operationId,
            List<String> stitchedImages) {

        List<String> suffixes = handler.getPostProcessingDirectorySuffixes();
        if (suffixes.isEmpty()) {
            return;
        }

        for (String suffix : suffixes) {
            String suffixLabel = suffix.startsWith(".") ? suffix.substring(1) : suffix;

            if (blockingDialog != null) {
                blockingDialog.updateStatus(
                        operationId, "Checking for " + suffixLabel + " results for " + regionName + "...");
            }

            // Scan for directory ending with this suffix
            String extraAngleStr = null;
            Path extraPath = null;

            try {
                if (Files.exists(tileBaseDir)) {
                    extraPath = Files.list(tileBaseDir)
                            .filter(Files::isDirectory)
                            .filter(path -> path.getFileName().toString().endsWith(suffix))
                            .findFirst()
                            .orElse(null);

                    if (extraPath != null) {
                        extraAngleStr = extraPath.getFileName().toString();
                        logger.info("Found {} directory: {}", suffixLabel, extraAngleStr);
                    }
                }
            } catch (IOException e) {
                logger.warn("Could not scan for {} directories: {}", suffixLabel, e.getMessage());
            }

            if (extraPath != null && Files.exists(extraPath)) {
                logger.info("Found {} directory: {}", suffixLabel, extraPath);

                // Log directory contents
                try {
                    long fileCount = Files.list(extraPath).count();
                    logger.info("{} directory contains {} files", suffixLabel, fileCount);
                    logger.debug("{} directory contents:", suffixLabel);
                    Files.list(extraPath).forEach(path -> logger.debug("  - {}", path));
                } catch (IOException e) {
                    logger.warn("Could not list {} directory contents: {}", suffixLabel, e.getMessage());
                }

                if (blockingDialog != null) {
                    blockingDialog.updateStatus(
                            operationId, "Processing " + suffixLabel + " image for " + regionName + "...");
                }

                try {
                    logger.info("Starting {} isolation processing for angle string: {}", suffixLabel, extraAngleStr);
                    String outPath = processAngleWithIsolation(
                            tileBaseDir,
                            extraAngleStr,
                            projectsFolder,
                            sampleName,
                            modeWithIndex,
                            regionName,
                            compression,
                            pixelSize,
                            downsampleFactor,
                            gui,
                            project,
                            handler,
                            stitchParams);

                    if (outPath != null) {
                        stitchedImages.add(outPath);
                        logger.info("Successfully processed {} image - output: {}", suffixLabel, outPath);
                    } else {
                        logger.error("{} processing returned null output path", suffixLabel);
                    }
                } catch (Exception e) {
                    logger.error(
                            "Failed to stitch {} image for angle {}: {}",
                            suffixLabel,
                            extraAngleStr,
                            e.getMessage(),
                            e);
                }
            } else {
                logger.info("No {} directory found in {}", suffixLabel, tileBaseDir);
            }
        }
    }
}
