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
import qupath.ext.basicstitching.assembly.ChannelMerger;
import qupath.ext.basicstitching.assembly.PyramidImageWriter;
import qupath.ext.basicstitching.config.StitchingConfig;
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
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.ext.qpsc.utilities.ImageNameGenerator;
import qupath.ext.qpsc.utilities.MinorFunctions;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
import qupath.ext.qpsc.utilities.StageImageTransform;
import qupath.ext.qpsc.utilities.StitchingConfiguration;
import qupath.ext.qpsc.utilities.TileProcessingUtilities;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.roi.interfaces.ROI;
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
        StitchingMetadata metadata = calculateMetadata(
                annotation, displayName, gui, project, fullResToStage, parentEntry,
                sample.modality(), sample.objective());
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
        return performRegionStitching(
                regionName,
                sample,
                modeWithIndex,
                angleExposures,
                pixelSize,
                gui,
                project,
                executor,
                handler,
                sampleName,
                projectsFolder,
                null,
                null,
                null,
                null);
    }

    /**
     * Region-stitching overload that accepts the known stage bounds of the
     * acquisition region. When all four bounds are non-null, the resulting
     * stitched image(s) get an automatic pixel->stage alignment registered
     * via {@link AffineTransformManager#saveSlideAlignment} so that Live
     * Viewer Move-to-centroid / click-to-center works without a separate
     * alignment step. Pass nulls for all four bounds to get the legacy
     * behaviour (no auto-registration).
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
            String projectsFolder,
            Double stageBoundsX1Um,
            Double stageBoundsY1Um,
            Double stageBoundsX2Um,
            Double stageBoundsY2Um) {

        // Use user-entered sample name for display/naming, not the project folder name.
        String displayName = (sample != null
                        && sample.sampleName() != null
                        && !sample.sampleName().isEmpty())
                ? sample.sampleName()
                : sampleName;
        StitchingMetadata metadata = calculateMetadataForRegion(
                regionName,
                displayName,
                gui,
                project,
                stageBoundsX1Um,
                stageBoundsY1Um,
                stageBoundsX2Um,
                stageBoundsY2Um);
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
                    handler);
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
                            boolean useParallel = stitchingConfig.outputFormat().stitchAsZarr();

                            String mode = useParallel ? "parallel" : "sequential";
                            logger.info(
                                    "Starting {} stitching for {} angles (format={})",
                                    mode,
                                    angleExposures.size(),
                                    stitchingConfig.outputFormat());

                            if (blockingDialog != null) {
                                blockingDialog.updateStatus(
                                        operationId,
                                        "Stitching " + angleExposures.size() + " angles (" + mode + ") for "
                                                + annotationName + "...");
                            }

                            if (useParallel) {
                                // ZARR: dispatch all angles concurrently
                                List<CompletableFuture<String>> angleFutures = new ArrayList<>();

                                for (int i = 0; i < angleExposures.size(); i++) {
                                    AngleExposure angleExposure = angleExposures.get(i);
                                    String angleStr = String.valueOf(angleExposure.ticks());
                                    final int angleIndex = i;

                                    logger.info(
                                            "Launching parallel stitch for angle {} ({}/{})",
                                            angleStr,
                                            i + 1,
                                            angleExposures.size());

                                    angleFutures.add(CompletableFuture.supplyAsync(() -> {
                                        try {
                                            return processAngleWithIsolation(
                                                    tileBaseDir,
                                                    angleStr,
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
                                        } catch (Exception e) {
                                            logger.error(
                                                    "Failed to stitch angle {} ({}/{}): {}",
                                                    angleStr,
                                                    angleIndex + 1,
                                                    angleExposures.size(),
                                                    e.getMessage(),
                                                    e);
                                            return null;
                                        }
                                    }));
                                }

                                CompletableFuture.allOf(angleFutures.toArray(new CompletableFuture[0]))
                                        .join();

                                for (int i = 0; i < angleFutures.size(); i++) {
                                    try {
                                        String outPath = angleFutures.get(i).get();
                                        if (outPath != null) {
                                            stitchedImages.add(outPath);
                                            logger.info(
                                                    "Parallel stitch completed for angle {}: {}",
                                                    angleExposures.get(i).ticks(),
                                                    outPath);
                                        }
                                    } catch (Exception e) {
                                        logger.error(
                                                "Failed to get result for angle {}: {}",
                                                angleExposures.get(i).ticks(),
                                                e.getMessage());
                                    }
                                }
                            } else {
                                // TIFF: sequential to avoid BioFormats concurrency bug
                                for (int i = 0; i < angleExposures.size(); i++) {
                                    AngleExposure angleExposure = angleExposures.get(i);
                                    String angleStr = String.valueOf(angleExposure.ticks());

                                    logger.info("Stitching angle {} ({}/{})", angleStr, i + 1, angleExposures.size());

                                    try {
                                        String outPath = processAngleWithIsolation(
                                                tileBaseDir,
                                                angleStr,
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
                                            logger.info(
                                                    "Stitch completed for angle {}: {}",
                                                    angleExposure.ticks(),
                                                    outPath);
                                        }
                                    } catch (Exception e) {
                                        logger.error(
                                                "Failed to stitch angle {} ({}/{}): {}",
                                                angleStr,
                                                i + 1,
                                                angleExposures.size(),
                                                e.getMessage(),
                                                e);
                                    }
                                }
                            }

                            logger.info(
                                    "Completed {} stitching of {} angles. Successfully stitched {} images.",
                                    mode,
                                    angleExposures.size(),
                                    stitchedImages.size());

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

                            // OME_TIFF_VIA_ZARR: queue background conversion of all
                            // ZARR outputs to OME-TIFF. The user already has working
                            // ZARR images in the project; this produces single-file
                            // TIFFs and swaps them in when done.
                            if (stitchingConfig.outputFormat() == StitchingConfig.OutputFormat.OME_TIFF_VIA_ZARR
                                    && !stitchedImages.isEmpty()) {
                                // Use LZW for TIFF (ZARR compression types don't apply to TIFF)
                                queueBackgroundZarrToTiffConversion(
                                        stitchedImages, "LZW", annotationName);
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
                                        String.format("Stitching failed for %s: %s", targetName, e.getMessage()),
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
                                        String.format("Stitching failed for %s: %s", targetName, e.getMessage()),
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
            String regionName,
            String sampleName,
            QuPathGUI gui,
            Project<BufferedImage> project,
            Double stageBoundsX1Um,
            Double stageBoundsY1Um,
            Double stageBoundsX2Um,
            Double stageBoundsY2Um) {

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

        // The flip state of the stitched output is determined by the
        // stitcher (StageImageTransform.stitcherFlipFlags), NOT by the
        // macro flip preferences. The macro flip preferences control scanner/
        // macro image display; the stitcher flip accounts for stage polarity
        // + camera orientation. These can differ (e.g. OWS3 has inverted
        // stage axes -> stitcher flips, but no scanner -> macro flip = false).
        boolean[] stitcherFlips = StageImageTransform.current().stitcherFlipFlags();
        boolean flipX = stitcherFlips[0];
        boolean flipY = stitcherFlips[1];
        logger.info("Region stitching flip flags from StageImageTransform: flipX={}, flipY={}",
                flipX, flipY);

        // Stamp source microscope (config name) + active detector so the
        // stitched image carries enough metadata for cross-system alignment
        // and per-detector flip / WB lookup. Detector follows the user's
        // most-recent dropdown choice (the same source the WB and BG
        // dialogs use post-2026-04-27 fix).
        String sourceMicroscope = resolveSourceMicroscope();
        String detector = qupath.ext.qpsc.preferences.PersistentPreferences.getLastDetector();
        if (detector != null && detector.isEmpty()) detector = null;

        return new StitchingMetadata(
                parentEntry,
                xOffset,
                yOffset,
                flipX,
                flipY,
                sampleName,
                null, // modality (unused in region path)
                null, // objective
                null, // angle
                null, // annotationName
                null, // imageIndex
                stageBoundsX1Um,
                stageBoundsY1Um,
                stageBoundsX2Um,
                stageBoundsY2Um,
                null, // fovXUm -- not available in region path
                null, // fovYUm
                detector,
                sourceMicroscope);
    }

    /**
     * Resolve the source-microscope identifier for stitched-image metadata.
     * Reads {@code MicroscopeConfigManager.getMicroscopeName()} from the
     * active config; on any failure returns {@code null} so the metadata
     * field is omitted rather than populated with a wrong value.
     */
    private static String resolveSourceMicroscope() {
        try {
            String configPath = qupath.ext.qpsc.preferences.QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (configPath == null || configPath.isEmpty()) return null;
            String name = qupath.ext.qpsc.utilities.MicroscopeConfigManager.getInstance(configPath)
                    .getMicroscopeName();
            return (name == null || name.isEmpty() || "Unknown".equals(name)) ? null : name;
        } catch (Exception e) {
            logger.debug("Could not resolve source microscope name for stitching metadata: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Calculates metadata for a stitched sub-image based on its parent annotation.
     * Computes the annotation's stage bounds from the parent's alignment transform
     * so that the sub-image gets its own auto-registered pixel-to-stage alignment.
     */
    private static StitchingMetadata calculateMetadata(
            PathObject annotation,
            String sampleName,
            QuPathGUI gui,
            Project<BufferedImage> project,
            AffineTransform fullResToStage,
            ProjectImageEntry<BufferedImage> capturedParentEntry,
            String modality,
            String objective) {

        // Use pre-captured parent entry if available (stable across the whole session).
        ProjectImageEntry<BufferedImage> parentEntry = capturedParentEntry;
        if (parentEntry == null && gui.getViewer().hasServer() && gui.getImageData() != null) {
            parentEntry = project.getEntry(gui.getImageData());
        }

        // Calculate offset (annotation top-left in stage coords)
        double[] offset = TransformationFunctions.calculateAnnotationOffsetFromSlideCorner(annotation, fullResToStage);

        // Stitcher flip flags (same as bounding-box path -- the stitcher
        // determines the output image orientation, not the parent's metadata)
        boolean[] stitcherFlips = StageImageTransform.current().stitcherFlipFlags();
        boolean flipX = stitcherFlips[0];
        boolean flipY = stitcherFlips[1];

        // Compute annotation stage bounds so the sub-image gets its own
        // auto-registered alignment via autoRegisterBoundsTransformIfAvailable.
        Double stageBoundsX1 = null, stageBoundsY1 = null;
        Double stageBoundsX2 = null, stageBoundsY2 = null;
        if (annotation != null && annotation.getROI() != null && fullResToStage != null) {
            ROI roi = annotation.getROI();
            double[] topLeft = TransformationFunctions.transformQuPathFullResToStage(
                    new double[]{roi.getBoundsX(), roi.getBoundsY()}, fullResToStage);
            double[] botRight = TransformationFunctions.transformQuPathFullResToStage(
                    new double[]{roi.getBoundsX() + roi.getBoundsWidth(),
                                 roi.getBoundsY() + roi.getBoundsHeight()}, fullResToStage);
            // Ensure min/max ordering (transform may have negative scales)
            stageBoundsX1 = Math.min(topLeft[0], botRight[0]);
            stageBoundsY1 = Math.min(topLeft[1], botRight[1]);
            stageBoundsX2 = Math.max(topLeft[0], botRight[0]);
            stageBoundsY2 = Math.max(topLeft[1], botRight[1]);
            logger.info("Sub-image annotation stage bounds: ({},{}) -> ({},{})",
                    String.format("%.1f", stageBoundsX1), String.format("%.1f", stageBoundsY1),
                    String.format("%.1f", stageBoundsX2), String.format("%.1f", stageBoundsY2));
        }

        String annotationName = annotation != null ? annotation.getName() : null;

        String sourceMicroscope = resolveSourceMicroscope();
        String detector = qupath.ext.qpsc.preferences.PersistentPreferences.getLastDetector();
        if (detector != null && detector.isEmpty()) detector = null;

        return new StitchingMetadata(
                parentEntry, offset[0], offset[1], flipX, flipY, sampleName,
                modality, objective, null, annotationName, null,
                stageBoundsX1, stageBoundsY1, stageBoundsX2, stageBoundsY2,
                null, null, detector, sourceMicroscope);
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
            logger.info("resolveChannelIdsForStitching: early exit -- handler={} sample={}", handler, sample);
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
                    "Failed to resolve channel library for stitching (profile={}): {}", profileKey, e.getMessage(), e);
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
            ModalityHandler handler) {

        logger.info("Stitching {} channels for: {}", channelIds.size(), annotationName);

        return CompletableFuture.runAsync(
                () -> {
                    try {
                        if (blockingDialog != null) {
                            blockingDialog.updateStatus(
                                    operationId, "Initializing multi-channel stitching for " + annotationName + "...");
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

                        Path tileBaseDir = Paths.get(projectsFolder, sampleName, modeWithIndex, annotationName);

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
                                        "Skipping channel '{}' -- subdirectory does not exist: {}", cid, channelSubdir);
                            }
                        }
                        logger.info(
                                "Library declared {} channel(s); found {} on disk: {}",
                                channelIds.size(),
                                acquiredChannelIds.size(),
                                acquiredChannelIds);

                        if (acquiredChannelIds.isEmpty()) {
                            logger.error("No channel subdirectories found in {}; nothing to stitch", tileBaseDir);
                            if (blockingDialog != null) {
                                blockingDialog.failOperation(operationId, "No channel tiles found");
                            }
                            if (dualProgressDialog != null) {
                                dualProgressDialog.failStitchingOperation(operationId, "No channel tiles found");
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
                            logger.info("Stitching channel {} ({}/{})", channelId, i + 1, acquiredChannelIds.size());
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
                                    logger.info("Stitch completed for channel {}: {}", channelId, outPath);
                                }
                            } catch (Exception e) {
                                logger.error(
                                        "Failed to stitch channel {} ({}/{}): {}",
                                        channelId,
                                        i + 1,
                                        acquiredChannelIds.size(),
                                        e.getMessage(),
                                        e);
                            }
                        }

                        logger.info(
                                "Completed multi-channel stitching: {} of {} channels succeeded",
                                stitchedImages.size(),
                                acquiredChannelIds.size());

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

                                // Build merged filename via the standard naming scheme
                                // (ImageNameGenerator), with angle/channel slot null since
                                // the merged file collapses channels into one pyramid.
                                // Substitute the long modality token with the short registry
                                // prefix ("Fluorescence" -> "fl") so the merged file matches
                                // the conventional short-name layout used elsewhere. Combos
                                // like BF+IF use their multi-token prefix verbatim
                                // ("bf_if").
                                String shortModalityPrefix = ModalityRegistry.getShortPrefix(handler);
                                String displayName = (metadata != null
                                                && metadata.sampleName != null
                                                && !metadata.sampleName.isEmpty())
                                        ? metadata.sampleName
                                        : sampleName;
                                String[] modalityParts = ImageNameGenerator.parseImagingMode(modeWithIndex);
                                String parsedModality = modalityParts[0];
                                String objective = modalityParts[1];
                                int imageIndex = ImageNameGenerator.extractImageIndex(modeWithIndex);
                                String mergedModality = parsedModality;
                                if (shortModalityPrefix != null
                                        && !shortModalityPrefix.isBlank()
                                        && parsedModality != null
                                        && parsedModality.toLowerCase().startsWith(shortModalityPrefix.toLowerCase())) {
                                    mergedModality = shortModalityPrefix;
                                }
                                String sanitizedAnnotationName =
                                        ImageNameGenerator.sanitizeForFilename(annotationName);
                                int candidateIndex = imageIndex;
                                String mergedBaseName = ImageNameGenerator.generateImageName(
                                        displayName,
                                        candidateIndex,
                                        mergedModality,
                                        objective,
                                        sanitizedAnnotationName,
                                        null,
                                        ".ome.tif");
                                File mergedCandidate = new File(mergedDir, mergedBaseName);
                                while (mergedCandidate.exists()) {
                                    candidateIndex++;
                                    mergedBaseName = ImageNameGenerator.generateImageName(
                                            displayName,
                                            candidateIndex,
                                            mergedModality,
                                            objective,
                                            sanitizedAnnotationName,
                                            null,
                                            ".ome.tif");
                                    mergedCandidate = new File(mergedDir, mergedBaseName);
                                }
                                String mergedStem = mergedBaseName.substring(
                                        0, mergedBaseName.length() - ".ome.tif".length());
                                logger.info(
                                        "Merged filename built from naming scheme: stem='{}' (sample={}, modality={}, objective={}, annotation={}, index={})",
                                        mergedStem,
                                        displayName,
                                        mergedModality,
                                        objective,
                                        sanitizedAnnotationName,
                                        candidateIndex);

                                List<Integer> channelColors = getDefaultChannelColors(
                                        successfullyStitchedChannelIds);
                                String mergedPath = ChannelMerger.merge(
                                        stitchedImages,
                                        successfullyStitchedChannelIds,
                                        channelColors,
                                        mergedDir,
                                        mergedStem,
                                        compression,
                                        stitchingConfig.outputFormat());

                                if (mergedPath != null) {
                                    logger.info("Multichannel merge succeeded for {}: {}", annotationName, mergedPath);

                                    // Per-channel intermediates were never imported to the
                                    // project (skipProjectImport=true above), so we only need
                                    // to import the merged file as the single canonical entry.
                                    importMergedImageOnly(mergedPath, metadata, gui, project, handler);
                                } else {
                                    // Merge failed. Because skipProjectImport=true kept the
                                    // per-channel pyramids out of the project, the user would
                                    // otherwise see nothing at all in the project tree. Import
                                    // each per-channel file as a fallback so at least the raw
                                    // channels are usable; mark the failure in the log so the
                                    // next diagnosis pass can chase down why the merge returned
                                    // null (bad pixel dims, corrupted pyramid, lost file,
                                    // ChannelMerger skipped due to <2 sources, etc.).
                                    logger.warn(
                                            "Multichannel merge returned null for {} -- falling back to importing {} per-channel file(s)",
                                            annotationName,
                                            stitchedImages.size());
                                    importPerChannelFallback(stitchedImages, metadata, gui, project, handler);
                                }
                            } catch (Exception mergeEx) {
                                logger.error(
                                        "Multichannel merge failed for {}: {} -- falling back to per-channel import",
                                        annotationName,
                                        mergeEx.getMessage(),
                                        mergeEx);
                                importPerChannelFallback(stitchedImages, metadata, gui, project, handler);
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
                                            "Channel stitching failed for %s: %s", annotationName, e.getMessage()),
                                    "Stitching Error"));
                        }
                    }
                },
                executor);
    }

    // -- Default fluorescence channel colors (packed ARGB) --

    private static final java.util.Map<String, Integer> DEFAULT_CHANNEL_COLORS = java.util.Map.ofEntries(
            // Common fluorescence filter names
            java.util.Map.entry("dapi",  packRGB(0, 0, 255)),       // Blue
            java.util.Map.entry("fitc",  packRGB(0, 255, 0)),       // Green
            java.util.Map.entry("gfp",   packRGB(0, 255, 0)),       // Green
            java.util.Map.entry("tritc", packRGB(255, 0, 0)),       // Red
            java.util.Map.entry("cy3",   packRGB(255, 165, 0)),     // Orange
            java.util.Map.entry("cy5",   packRGB(255, 0, 255)),     // Magenta
            java.util.Map.entry("cy7",   packRGB(128, 0, 128)),     // Purple
            java.util.Map.entry("bf",    packRGB(255, 255, 255)),    // White
            java.util.Map.entry("brightfield", packRGB(255, 255, 255))
    );

    private static int packRGB(int r, int g, int b) {
        return (255 << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Returns default display colors for a list of channel IDs.
     * Matches by case-insensitive channel name (DAPI->blue, FITC->green, etc.).
     * Unknown channels get {@code null} (source default preserved by the merger).
     */
    private static List<Integer> getDefaultChannelColors(List<String> channelIds) {
        List<Integer> colors = new ArrayList<>();
        for (String id : channelIds) {
            Integer color = DEFAULT_CHANNEL_COLORS.get(id.toLowerCase());
            colors.add(color); // null is fine -- merger falls back to source color
        }
        return colors;
    }

    /**
     * Imports the merged multichannel file into the QuPath project as a single entry.
     * Per-channel intermediate files are NOT imported (callers pass
     * {@code skipProjectImport=true} when invoking the per-channel stitch loop), so
     * there is nothing to remove afterward -- the merged file is the only project
     * entry created by the channel-based path.
     */
    /**
     * Register an automatic pixel->stage affine alignment for a just-imported
     * stitched file, when the source {@link StitchingMetadata} carries a
     * fully-specified stage bounds rectangle (BoundingBox acquisitions).
     *
     * <p>This is the "auto-register on import" path that lets Live Viewer
     * Move-to-centroid / click-to-center work on any BoundingBox output
     * without a manual alignment step. The resulting alignment is keyed by
     * the image's on-disk file name (matching
     * {@code QPProjectFunctions.getActualImageFileName}), which is the same
     * key {@link qupath.ext.qpsc.ui.liveviewer.StageControlPanel} uses when
     * it calls {@link AffineTransformManager#loadSlideAlignment}.
     *
     * <p>No-op when {@code metadata.hasStageBounds()} is false, when the file
     * is missing, or when opening the server for pixel dimensions fails.
     * Errors are logged and swallowed so they never break the import flow.
     */
    public static void autoRegisterBoundsTransformIfAvailable(
            File importedFile, StitchingMetadata metadata, Project<BufferedImage> project) {
        if (metadata == null || !metadata.hasStageBounds()) {
            return;
        }
        if (project == null || importedFile == null || !importedFile.exists()) {
            return;
        }
        try {
            int widthPx;
            int heightPx;
            double pixelSizeUm;
            // Prefer reading pixel dimensions from the actual stitched file so
            // the transform reflects any rounding the tile grid introduced.
            try (qupath.lib.images.servers.ImageServer<java.awt.image.BufferedImage> server =
                    qupath.lib.images.servers.ImageServers.buildServer(importedFile.toURI())) {
                widthPx = server.getWidth();
                heightPx = server.getHeight();
                pixelSizeUm = server.getPixelCalibration().getAveragedPixelSizeMicrons();
            }
            // The stage bounds from metadata are the ANNOTATION bounds, but the
            // stitched image extends half a camera FOV beyond them in each direction
            // (TilingUtilities centers the first tile on the annotation edge).
            // Compute the actual image extent and adjust the origin accordingly.
            double imageExtentX = widthPx * pixelSizeUm;
            double imageExtentY = heightPx * pixelSizeUm;
            double annotExtentX = metadata.stageBoundsX2Um - metadata.stageBoundsX1Um;
            double annotExtentY = metadata.stageBoundsY2Um - metadata.stageBoundsY1Um;
            double halfFovX = (imageExtentX - annotExtentX) / 2.0;
            double halfFovY = (imageExtentY - annotExtentY) / 2.0;

            // Adjust bounds to reflect the actual image origin (half FOV before annotation edge)
            double imgX1 = metadata.stageBoundsX1Um - halfFovX;
            double imgY1 = metadata.stageBoundsY1Um - halfFovY;
            double imgX2 = metadata.stageBoundsX2Um + halfFovX;
            double imgY2 = metadata.stageBoundsY2Um + halfFovY;

            logger.info("Stage bounds adjustment: annotation ({},{}) -> ({},{}), "
                    + "image ({},{}) -> ({},{}) (halfFOV={},{})",
                    String.format("%.1f", metadata.stageBoundsX1Um),
                    String.format("%.1f", metadata.stageBoundsY1Um),
                    String.format("%.1f", metadata.stageBoundsX2Um),
                    String.format("%.1f", metadata.stageBoundsY2Um),
                    String.format("%.1f", imgX1), String.format("%.1f", imgY1),
                    String.format("%.1f", imgX2), String.format("%.1f", imgY2),
                    String.format("%.1f", halfFovX), String.format("%.1f", halfFovY));

            // Account for image flips: when the stitched image is displayed
            // flipped (via TransformedServerBuilder), QuPath pixel coordinates
            // are in the flipped space. The alignment must use negative scales
            // and the opposite-corner origin so that pixel (0,0) in the flipped
            // view maps to the correct stage position.
            boolean flipX = metadata.flipX;
            boolean flipY = metadata.flipY;

            double originX = flipX ? imgX2 : imgX1;
            double originY = flipY ? imgY2 : imgY1;
            double scaleX = (flipX ? -1 : 1) * (imgX2 - imgX1) / widthPx;
            double scaleY = (flipY ? -1 : 1) * (imgY2 - imgY1) / heightPx;

            AffineTransform transform = new AffineTransform(scaleX, 0, 0, scaleY, originX, originY);

            logger.info("Auto-register alignment: flip=({},{}), scale=({},{}), origin=({},{})",
                    flipX, flipY,
                    String.format("%.6f", scaleX), String.format("%.6f", scaleY),
                    String.format("%.1f", originX), String.format("%.1f", originY));

            if (scaleX == 0 || scaleY == 0) {
                logger.warn("Degenerate alignment transform (zero scale), skipping");
                return;
            }
            // Key the alignment by the file name WITHOUT extension. This
            // matches QPProjectFunctions.getActualImageFileName (which calls
            // GeneralTools.stripExtension) and what AlignmentHelper /
            // StageControlPanel use for lookup via loadSlideAlignment.
            String alignmentKey = qupath.lib.common.GeneralTools.stripExtension(importedFile.getName());
            String modality = metadata.modality != null ? metadata.modality : "BoundingBox";
            // Persist the macro-flip frame the transform was built for. Lines above
            // encode metadata.flipX/Y into the transform's origin and scale signs;
            // recording the same booleans on the JSON lets AlignmentHelper bake the
            // matching flip into state.transform on reload, so all downstream callers
            // (SingleTileRefinement, AcquisitionManager, ...) consume unflipped-base
            // pixel coords. Without this, PPM auto-registered alignments load as if
            // unflipped and SingleTileRefinement targets the XY-mirrored stage point.
            AffineTransformManager.saveSlideAlignment(
                    project, alignmentKey, modality, transform, null, flipX, flipY);
            logger.info(
                    "Auto-registered stage alignment for '{}' from BoundingBox metadata "
                            + "(bounds=({},{})->({},{}), image={}x{})",
                    alignmentKey,
                    metadata.stageBoundsX1Um,
                    metadata.stageBoundsY1Um,
                    metadata.stageBoundsX2Um,
                    metadata.stageBoundsY2Um,
                    widthPx,
                    heightPx);
        } catch (Exception e) {
            logger.warn("Failed to auto-register stage alignment for {}: {}", importedFile.getName(), e.getMessage());
        }
    }

    /**
     * Fallback path used when {@link ChannelMerger#merge} returns null or
     * throws: imports each per-channel pyramid as its own project entry so
     * the user still has usable output. The channel-merge path sets
     * {@code skipProjectImport=true} during the stitch loop, so without this
     * fallback a merge failure would leave the user with nothing in the
     * project tree at all -- files on disk, but no entries.
     */
    private static void importPerChannelFallback(
            List<String> stitchedImages,
            StitchingMetadata metadata,
            QuPathGUI gui,
            Project<BufferedImage> project,
            ModalityHandler handler) {
        if (stitchedImages == null || stitchedImages.isEmpty()) {
            return;
        }
        Platform.runLater(() -> {
            for (String path : stitchedImages) {
                File f = new File(path);
                if (!f.exists()) {
                    logger.warn("Fallback import: file does not exist, skipping: {}", path);
                    continue;
                }
                try {
                    if (metadata != null) {
                        // Pass false/false for flip flags here -- the stitcher
                        // already baked the flip into the pyramid via
                        // TileConfigurationTxtStrategy.flipStitchingX/Y. Passing
                        // metadata.flipX/Y again applies a second
                        // TransformedServerBuilder flip on import, double-
                        // flipping the channel TIFF relative to the BF
                        // single-pass path (which has always passed false here).
                        // metadata.flipX/Y is still saved by applyImageMetadata
                        // for downstream consumers that need to know the
                        // pyramid's stage-relative orientation.
                        QPProjectFunctions.addImageToProjectWithMetadata(
                                project, f,
                                metadata.parentEntry,
                                metadata.xOffset, metadata.yOffset,
                                false, false,
                                metadata.sampleName,
                                metadata.modality, metadata.objective,
                                metadata.angle, metadata.annotationName,
                                metadata.imageIndex, handler,
                                metadata.detector, metadata.sourceMicroscope);
                    } else {
                        QPProjectFunctions.addImageToProject(f, project, false, false, handler);
                    }
                    // Every per-channel file gets its own alignment keyed by
                    // its own file name, so Move-to-centroid works on any of
                    // them individually (user may open any channel directly).
                    autoRegisterBoundsTransformIfAvailable(f, metadata, project);
                    logger.info("Fallback imported per-channel file: {}", f.getName());
                } catch (Exception e) {
                    logger.error("Fallback import failed for {}: {}", f.getName(), e.getMessage(), e);
                }
            }
            try {
                gui.setProject(project);
                gui.refreshProject();
            } catch (Exception refreshEx) {
                logger.warn("Failed to refresh project after fallback import: {}", refreshEx.getMessage());
            }
        });
    }

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
                    // Pass false/false for flip flags -- the per-channel
                    // pyramids were already written with the stitcher's
                    // flipStitchingX/Y baked in, and ChannelMerger preserves
                    // pixel layout. Passing metadata.flipX/Y here ran a
                    // second TransformedServerBuilder flip on the merged
                    // file, rotating multi-channel acquisitions 180 deg
                    // relative to the BF single-pass path on any scope
                    // whose stitcher flags are non-trivial (e.g. OWS3 with
                    // inverted stage polarity). flipX/Y is still applied to
                    // metadata for downstream consumers.
                    QPProjectFunctions.addImageToProjectWithMetadata(
                            project, mergedFile,
                            metadata.parentEntry,
                            metadata.xOffset, metadata.yOffset,
                            false, false,
                            metadata.sampleName,
                            metadata.modality, metadata.objective,
                            metadata.angle, metadata.annotationName,
                            metadata.imageIndex, handler,
                            metadata.detector, metadata.sourceMicroscope);
                } else {
                    QPProjectFunctions.addImageToProject(mergedFile, project, false, false, handler);
                }
                logger.info("Merged multichannel file imported to project: {}", mergedFile.getName());

                // Auto-register the pixel->stage alignment from the known
                // BoundingBox acquisition bounds (no-op when metadata lacks
                // bounds, e.g. annotation-based acquisitions). Must happen
                // after addImageToProjectWithMetadata so the merged file is
                // on disk and its pixel dimensions can be read by the server.
                autoRegisterBoundsTransformIfAvailable(mergedFile, metadata, project);

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
                        "Failed to import merged multichannel file {}: {}", mergedFile.getName(), e.getMessage(), e);
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
            // Always restore the directory structure.
            // On Windows, BioFormats readers / TIFF writers may still hold file handles
            // briefly after close, causing AccessDeniedException on Files.move.
            // Retry with exponential backoff (200ms, 400ms, 800ms, 1600ms, 3200ms).
            logger.info("Starting cleanup - restoring directory structure for angle {}", angleStr);
            try {
                if (Files.exists(tempAngleDir)) {
                    logger.info("Restoring directory from {} to {}", tempAngleDir, angleDir);
                    MinorFunctions.moveWithRetry(tempAngleDir, angleDir, 5, 200);
                    logger.info("Successfully restored {} from isolation", angleStr);
                } else {
                    logger.warn("Temporary angle directory no longer exists: {}", tempAngleDir);
                }
                if (Files.exists(tempIsolationDir)) {
                    logger.info("Cleaning up temporary isolation directory: {}", tempIsolationDir);
                    MinorFunctions.deleteWithRetry(tempIsolationDir, 5, 200);
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

    // -----------------------------------------------------------------------
    // Background ZARR-to-TIFF conversion (OME_TIFF_VIA_ZARR mode)
    // -----------------------------------------------------------------------

    /**
     * Queue background conversion of ZARR stitched images to OME-TIFF.
     *
     * <p>Runs on a daemon thread so it does not block the user. Each ZARR
     * directory is opened as an ImageServer and written to OME-TIFF via
     * {@link PyramidImageWriter}. The TIFF file is placed alongside the ZARR
     * (same directory, same base name, .ome.tif extension).
     *
     * <p>This method does NOT modify the project or delete the ZARR.
     * The project continues to use the ZARR as the working image.
     * Use "Make Project Portable" (Extensions > QP Scope > Utilities) to
     * swap project entries from ZARR to TIFF and clean up intermediates.
     *
     * @param zarrPaths   Absolute paths to .ome.zarr directories produced by stitching
     * @param compression TIFF compression type (e.g. "LZW", "J2K")
     * @param label       Human-readable label for logging (e.g. annotation name)
     */
    private static void queueBackgroundZarrToTiffConversion(
            List<String> zarrPaths,
            String compression,
            String label) {

        List<String> toConvert = zarrPaths.stream()
                .filter(p -> p.endsWith(".ome.zarr"))
                .toList();

        if (toConvert.isEmpty()) {
            logger.info("No ZARR files to convert for {}", label);
            return;
        }

        logger.info("=== Queuing background ZARR -> TIFF conversion for {} ({} files) ===",
                label, toConvert.size());
        for (String p : toConvert) {
            logger.info("  Will convert: {}", p);
        }

        Thread conversionThread = new Thread(() -> {
            int succeeded = 0;
            int failed = 0;
            long totalStart = System.currentTimeMillis();

            for (String zarrPath : toConvert) {
                try {
                    boolean ok = convertSingleZarrToTiff(zarrPath, compression);
                    if (ok) succeeded++;
                    else failed++;
                } catch (Exception e) {
                    failed++;
                    logger.error("Background ZARR->TIFF conversion failed for {}: {}",
                            zarrPath, e.getMessage(), e);
                }
            }

            long elapsed = (System.currentTimeMillis() - totalStart) / 1000;
            logger.info("=== Background ZARR -> TIFF conversion complete for {} ===", label);
            logger.info("  Converted: {}/{}, failed: {}, elapsed: {}m {}s",
                    succeeded, toConvert.size(), failed, elapsed / 60, elapsed % 60);

            if (failed > 0) {
                logger.warn("  {} ZARR files had conversion failures. "
                        + "ZARR images remain usable in QuPath.", failed);
            }

            final int s = succeeded;
            final int f = failed;
            final long e = elapsed;
            Platform.runLater(() -> {
                String msg;
                if (f == 0) {
                    msg = String.format(
                            "Background TIFF conversion complete: %d files (%dm %ds). "
                            + "Use 'Make Project Portable' to finalize.", s, e / 60, e % 60);
                    qupath.fx.dialogs.Dialogs.showInfoNotification(
                            "ZARR to TIFF Conversion", msg);
                } else {
                    msg = String.format(
                            "Background conversion: %d/%d succeeded, %d failed. "
                            + "Check log for details.", s, s + f, f);
                    UIFunctions.notifyUserOfError(msg, "ZARR to TIFF Conversion");
                }
            });
        }, "zarr-to-tiff-converter");

        conversionThread.setDaemon(true);
        conversionThread.start();
    }

    /**
     * Convert a single .ome.zarr directory to .ome.tif alongside it.
     * Does NOT modify the project or delete the ZARR.
     *
     * @return true if the TIFF was written successfully
     */
    private static boolean convertSingleZarrToTiff(String zarrPath, String compression) {
        Path zarr = Path.of(zarrPath);
        if (!Files.isDirectory(zarr)) {
            logger.warn("ZARR path is not a directory, skipping: {}", zarrPath);
            return false;
        }

        String tiffPathStr = zarrPath.replaceAll("\\.ome\\.zarr$", ".ome.tif");
        Path tiff = Path.of(tiffPathStr);

        // Skip if TIFF already exists (idempotent)
        if (Files.exists(tiff)) {
            logger.info("TIFF already exists, skipping conversion: {}", tiff.getFileName());
            return true;
        }

        logger.info("Converting ZARR -> TIFF: {}", zarr.getFileName());
        long start = System.currentTimeMillis();

        try {
            var server = qupath.lib.images.servers.ImageServers.buildServer(zarr.toUri());
            try {
                String baseName = zarr.getFileName().toString().replace(".ome.zarr", "");
                String result = PyramidImageWriter.write(
                        server,
                        zarr.getParent().toString(),
                        baseName,
                        compression,
                        1.0,
                        StitchingConfig.OutputFormat.OME_TIFF);

                if (result == null) {
                    logger.error("PyramidImageWriter returned null for {}", zarr.getFileName());
                    return false;
                }
            } finally {
                server.close();
            }

            long elapsed = (System.currentTimeMillis() - start) / 1000;
            long sizeMB = Files.size(tiff) / (1024 * 1024);
            logger.info("  Converted {} -> {} ({} MB, {}m {}s)",
                    zarr.getFileName(), tiff.getFileName(), sizeMB, elapsed / 60, elapsed % 60);
            return true;

        } catch (Exception e) {
            logger.error("Failed to convert {} to TIFF: {}", zarr.getFileName(), e.getMessage(), e);
            // Clean up partial TIFF if it exists
            try {
                if (Files.exists(tiff)) Files.delete(tiff);
            } catch (IOException ignored) {}
            return false;
        }
    }
}
