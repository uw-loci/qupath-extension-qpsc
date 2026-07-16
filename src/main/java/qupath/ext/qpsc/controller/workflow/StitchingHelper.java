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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import qupath.ext.qpsc.utilities.TileRegistrationSupport;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.roi.interfaces.ROI;

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
     * Per-acquisition output options that control how stitched channels are
     * grouped into files. Independent of the global OME-TIFF/OME-ZARR file-format
     * preference.
     *
     * @param organization how to group the stitched output:
     *     {@code OME_SINGLE} merges all (non-split) channels into one
     *     multi-dimensional file; {@code OME_PER_CHANNEL} writes every channel as
     *     its own file; {@code OME_PER_T} splits the combined mosaic into one file
     *     per timepoint.
     * @param splitChannelIds channel ids the user chose to write as their own
     *     separate file even under {@code OME_SINGLE}; the remaining channels are
     *     merged. Ignored when {@code organization == OME_PER_CHANNEL} (all split).
     */
    public record StitchingOptions(qupath.ext.qpsc.service.OutputFormat organization, Set<String> splitChannelIds) {
        public StitchingOptions {
            if (organization == null) {
                organization = qupath.ext.qpsc.service.OutputFormat.OME_SINGLE;
            }
            splitChannelIds = splitChannelIds == null ? Set.of() : Set.copyOf(splitChannelIds);
        }

        /** The behavior-preserving default: one combined multichannel file, no per-channel split. */
        public static StitchingOptions defaults() {
            return new StitchingOptions(qupath.ext.qpsc.service.OutputFormat.OME_SINGLE, Set.of());
        }

        /** True when channel {@code id} should be written as its own file. */
        public boolean isSplit(String id) {
            return organization == qupath.ext.qpsc.service.OutputFormat.OME_PER_CHANNEL || splitChannelIds.contains(id);
        }
    }

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
                null,
                StitchingOptions.defaults(),
                false);
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
     * @param pipelinedBatchAcquire when true (pipelined multi-slide ACQUIRE pass), the
     *     stitched-image import SUPPRESSES its viewer side effects (no open-entry / active-image
     *     switch / setProject-reopen) so a background import cannot yank the active viewer out
     *     from under the next slot, and the import runs synchronously w.r.t. the returned future
     *     so it reflects real import completion. When false, the classic auto-open behavior.
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
            ProjectImageEntry<BufferedImage> parentEntry,
            StitchingOptions options,
            boolean pipelinedBatchAcquire) {

        // Use sample.sampleName() for file naming (source image name), not sampleName (project folder name)
        String displayName = sample.sampleName();
        StitchingMetadata metadata = calculateMetadata(
                annotation,
                displayName,
                gui,
                project,
                fullResToStage,
                parentEntry,
                sample.modality(),
                sample.objective());
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
                dualProgressDialog,
                options,
                pipelinedBatchAcquire);
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
                null,
                StitchingOptions.defaults());
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
            Double stageBoundsY2Um,
            StitchingOptions options) {

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
                stageBoundsY2Um,
                sample,
                modeWithIndex);
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
                null,
                options,
                false);
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
    /**
     * Stitch each subdirectory target (a PPM angle or an IF channel) in parallel,
     * bounded by the user's "Stitching concurrency" preference (default 4).
     *
     * <p>Each target is fully independent -- its own per-target tile subdirectory,
     * its own {@code DirectTiffOutputWriter} invocation, its own output file -- so
     * they can stitch concurrently. The former "OME-TIFF must be sequential" guard
     * existed only because QuPath's shared {@code OMEPyramidWriter} NPE'd under
     * concurrency; that writer was replaced by the per-call
     * {@code DirectTiffOutputWriter}, so the guard no longer applies. The OME-ZARR
     * path already dispatched targets concurrently through this same
     * {@code processAngleWithIsolation} call, so this unifies both formats on one
     * bounded pool rather than the old ZARR-parallel / TIFF-sequential split.
     *
     * <p>Within a single annotation's batch all targets share identical flip
     * metadata, so the {@code volatile} static flip flags set inside
     * {@code stitchImagesAndUpdateProject} carry the same value across the
     * concurrent calls -- the same condition under which the pre-existing ZARR
     * parallel path already ran safely. Cross-annotation concurrency (which could
     * mix flip values) is intentionally NOT introduced here: the caller's
     * {@code STITCH_EXECUTOR} keeps one annotation batch running at a time.
     *
     * @return a list the SAME size and order as {@code targetSubdirs}; element i is
     *         the stitched output path for target i, or {@code null} if that target
     *         failed. Positional alignment lets callers that pair targets with
     *         outputs (e.g. channel split/merge) keep their indexing.
     */
    private static List<String> stitchTargetsBounded(
            List<String> targetSubdirs,
            String targetKind,
            Path tileBaseDir,
            String projectsFolder,
            String sampleName,
            String modeWithIndex,
            String annotationName,
            String compression,
            double pixelSize,
            int downsampleFactor,
            QuPathGUI gui,
            Project<BufferedImage> project,
            ModalityHandler handler,
            Map<String, Object> stitchParams) {

        int total = targetSubdirs.size();
        if (total == 0) {
            return new ArrayList<>();
        }

        // Content-based registration, when enabled, forces one target to go first: it measures the
        // grid and writes the solution the others reuse. That barrier is the whole point rather
        // than an inconvenience -- angles and channels are captured at the SAME stage position per
        // tile, so letting each solve its own grid would misregister them against each other, which
        // is worse than leaving them all on a shared nominal grid.
        boolean register = QPPreferenceDialog.getTileRegistrationEnabled();
        List<String> results = new ArrayList<>(total);
        List<String> remaining = targetSubdirs;

        if (register) {
            Path solutionFile = tileBaseDir.resolve(TileRegistrationSupport.solutionFileName());
            String reference = targetSubdirs.get(0);
            logger.info(
                    "Tile registration enabled: solving on {} {} first, then reusing that solve for "
                            + "the remaining {} {}(s)",
                    targetKind,
                    reference,
                    total - 1,
                    targetKind);

            results.add(stitchOne(
                    reference,
                    targetKind,
                    1,
                    total,
                    withRegistrationMode(stitchParams, TileRegistrationSupport.solveMode(solutionFile)),
                    tileBaseDir,
                    projectsFolder,
                    sampleName,
                    modeWithIndex,
                    annotationName,
                    compression,
                    pixelSize,
                    downsampleFactor,
                    gui,
                    project,
                    handler));

            // If the solve failed or the grid was unregisterable, no solution file exists. The
            // remaining targets then log a warning and stitch at nominal -- which still leaves every
            // target consistent with every other, because none of them moved.
            stitchParams = withRegistrationMode(stitchParams, TileRegistrationSupport.applyMode(solutionFile));
            remaining = targetSubdirs.subList(1, total);
        }

        int concurrency = Math.max(1, Math.min(remaining.size(), QPPreferenceDialog.getStitchingConcurrency()));
        if (remaining.isEmpty()) {
            return results;
        }
        logger.info(
                "Stitching {} {}(s) for {} with up to {} concurrent writer(s)",
                remaining.size(),
                targetKind,
                annotationName,
                concurrency);

        final Map<String, Object> params = stitchParams;
        final int offset = results.size();
        final List<String> batch = remaining;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "stitch-" + targetKind);
            t.setDaemon(true);
            return t;
        });
        try {
            List<CompletableFuture<String>> futures = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                String sub = batch.get(i);
                final int position = offset + i + 1;
                futures.add(CompletableFuture.supplyAsync(
                        () -> stitchOne(
                                sub,
                                targetKind,
                                position,
                                total,
                                params,
                                tileBaseDir,
                                projectsFolder,
                                sampleName,
                                modeWithIndex,
                                annotationName,
                                compression,
                                pixelSize,
                                downsampleFactor,
                                gui,
                                project,
                                handler),
                        pool));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            for (int i = 0; i < batch.size(); i++) {
                String out = null;
                try {
                    out = futures.get(i).get();
                } catch (Exception e) {
                    logger.error(
                            "Failed to retrieve stitch result for {} {}: {}", targetKind, batch.get(i), e.getMessage());
                }
                results.add(out);
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    /** Stitch one target, converting any failure into a null result so siblings still complete. */
    private static String stitchOne(
            String sub,
            String targetKind,
            int position,
            int total,
            Map<String, Object> stitchParams,
            Path tileBaseDir,
            String projectsFolder,
            String sampleName,
            String modeWithIndex,
            String annotationName,
            String compression,
            double pixelSize,
            int downsampleFactor,
            QuPathGUI gui,
            Project<BufferedImage> project,
            ModalityHandler handler) {
        logger.info("Stitching {} {} ({}/{})", targetKind, sub, position, total);
        try {
            return processAngleWithIsolation(
                    tileBaseDir,
                    sub,
                    projectsFolder,
                    sampleName,
                    modeWithIndex,
                    annotationName,
                    compression,
                    pixelSize,
                    downsampleFactor,
                    gui,
                    project,
                    handler,
                    stitchParams);
        } catch (Exception e) {
            logger.error("Failed to stitch {} {} ({}/{}): {}", targetKind, sub, position, total, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Copy the stitch parameters with a registration mode attached.
     *
     * <p>Copied rather than mutated because the map is shared across the concurrent targets, which
     * need different modes.
     */
    private static Map<String, Object> withRegistrationMode(Map<String, Object> stitchParams, Object mode) {
        Map<String, Object> copy = stitchParams == null ? new HashMap<>() : new HashMap<>(stitchParams);
        copy.put(TileProcessingUtilities.REGISTRATION_MODE_KEY, mode);
        return copy;
    }

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
            DualProgressDialog dualProgressDialog,
            StitchingOptions options,
            boolean pipelinedBatchAcquire) {

        final StitchingOptions stitchOptions = options != null ? options : StitchingOptions.defaults();

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
                    stitchOptions,
                    pipelinedBatchAcquire);
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
                            // Pipelined multi-slide acquire: suppress the import's viewer side
                            // effects and await real import completion (read in
                            // TileProcessingUtilities.stitchImagesAndUpdateProject).
                            stitchParams.put("pipelinedBatchAcquire", pipelinedBatchAcquire);
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

                            // Both OME-TIFF and OME-ZARR stitch their angles in
                            // parallel, bounded by the "Stitching concurrency"
                            // preference (default 4). Each angle is an independent
                            // writer to its own output file -- see stitchTargetsBounded.
                            logger.info(
                                    "Starting bounded-parallel stitching for {} angles (format={})",
                                    angleExposures.size(),
                                    stitchingConfig.outputFormat());

                            if (blockingDialog != null) {
                                blockingDialog.updateStatus(
                                        operationId,
                                        "Stitching " + angleExposures.size() + " angles for " + annotationName + "...");
                            }

                            List<String> angleSubdirs = new ArrayList<>(angleExposures.size());
                            for (AngleExposure ae : angleExposures) {
                                angleSubdirs.add(String.valueOf(ae.ticks()));
                            }
                            for (String outPath : stitchTargetsBounded(
                                    angleSubdirs,
                                    "angle",
                                    tileBaseDir,
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
                                    stitchParams)) {
                                if (outPath != null) {
                                    stitchedImages.add(outPath);
                                }
                            }

                            logger.info(
                                    "Completed bounded-parallel stitching of {} angles. Successfully stitched {} images.",
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
                            // Pipelined multi-slide acquire: suppress the import's viewer side
                            // effects and await real import completion (read in
                            // TileProcessingUtilities.stitchImagesAndUpdateProject).
                            stitchParams.put("pipelinedBatchAcquire", pipelinedBatchAcquire);

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
     * @param modeWithIndex Per-acquisition folder name (e.g. {@code "ppm_10x_1"}).
     *                      When non-null and parentEntry is also null, the BoundingBox
     *                      angle/channel entries share a synthesized
     *                      {@code base_image = <regionName>_<modeWithIndex>} so the
     *                      QuPath project sort groups one acquisition's N files
     *                      together. When null the legacy own-name fallback runs.
     */
    private static StitchingMetadata calculateMetadataForRegion(
            String regionName,
            String sampleName,
            QuPathGUI gui,
            Project<BufferedImage> project,
            Double stageBoundsX1Um,
            Double stageBoundsY1Um,
            Double stageBoundsX2Um,
            Double stageBoundsY2Um,
            SampleSetupResult sample,
            String modeWithIndex) {

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
        logger.info("Region stitching flip flags from StageImageTransform: flipX={}, flipY={}", flipX, flipY);

        // Stamp source microscope (config name) + active detector so the
        // stitched image carries enough metadata for cross-system alignment
        // and per-detector flip / WB lookup. Detector follows the user's
        // most-recent dropdown choice (the same source the WB and BG
        // dialogs use post-2026-04-27 fix).
        String sourceMicroscope = resolveSourceMicroscope();
        String detector = qupath.ext.qpsc.preferences.PersistentPreferences.getLastDetector();
        if (detector != null && detector.isEmpty()) detector = null;

        // Camera FOV in stage microns. Required by
        // autoRegisterBoundsTransformIfAvailable so it can compute the
        // correct ASYMMETRIC image bounds: TilingUtilities anchors the tile
        // grid with the first tile's CENTER on the annotation top-left, so
        // the top/left extension is always exactly half a FOV, while the
        // bottom/right extension can be more (ceiling rounding adds extra
        // tiles to cover the far edge). Without FOV, the auto-register
        // code's symmetric-halfFOV assumption put pixel(0,0) at the wrong
        // stage corner, which manifested 2026-05-15 as a half-FOV Y-axis
        // error on the OWS3 Move-to-Centroid path.
        Double fovXUm = null;
        Double fovYUm = null;
        try {
            qupath.ext.qpsc.utilities.MicroscopeConfigManager mgr =
                    qupath.ext.qpsc.utilities.MicroscopeConfigManager.getInstanceIfAvailable();
            if (mgr != null && sample != null) {
                double[] fov = mgr.getCameraFOV(sample.modality(), sample.objective(), sample.detector());
                if (fov != null && fov.length == 2) {
                    fovXUm = fov[0];
                    fovYUm = fov[1];
                }
            }
        } catch (Exception e) {
            logger.warn(
                    "Could not resolve camera FOV for bounded acquisition metadata "
                            + "(modality={}, objective={}, detector={}): {}",
                    sample != null ? sample.modality() : null,
                    sample != null ? sample.objective() : null,
                    sample != null ? sample.detector() : null,
                    e.getMessage());
        }

        // BoundingBox / Bounded acquisitions have no parent entry, so each
        // angle (or channel) entry would otherwise be its own base_image --
        // a single PPM acquisition lands as 4 unrelated rows in the project
        // sort. Synthesize a stable per-acquisition base_image so the N
        // entries land together. Format: "<regionName>_<modeWithIndex>"
        // (e.g. "bounds_ppm_10x_1"). modeWithIndex already encodes
        // modality + objective + acquisition counter, so re-acquiring the
        // same region yields a distinct base_image ("..._1" vs "..._2").
        String baseImageOverride = null;
        if (parentEntry == null && modeWithIndex != null && !modeWithIndex.isEmpty()) {
            String regionPart = (regionName == null || regionName.isEmpty()) ? "region" : regionName;
            baseImageOverride = regionPart + "_" + modeWithIndex;
        }

        return new StitchingMetadata(
                parentEntry,
                xOffset,
                yOffset,
                flipX,
                flipY,
                sampleName,
                sample != null ? sample.modality() : null,
                sample != null ? sample.objective() : null,
                null, // angle
                null, // annotationName
                null, // imageIndex
                stageBoundsX1Um,
                stageBoundsY1Um,
                stageBoundsX2Um,
                stageBoundsY2Um,
                fovXUm,
                fovYUm,
                detector,
                sourceMicroscope,
                baseImageOverride);
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
                    new double[] {roi.getBoundsX(), roi.getBoundsY()}, fullResToStage);
            double[] botRight = TransformationFunctions.transformQuPathFullResToStage(
                    new double[] {roi.getBoundsX() + roi.getBoundsWidth(), roi.getBoundsY() + roi.getBoundsHeight()},
                    fullResToStage);
            // Ensure min/max ordering (transform may have negative scales)
            stageBoundsX1 = Math.min(topLeft[0], botRight[0]);
            stageBoundsY1 = Math.min(topLeft[1], botRight[1]);
            stageBoundsX2 = Math.max(topLeft[0], botRight[0]);
            stageBoundsY2 = Math.max(topLeft[1], botRight[1]);
            logger.info(
                    "Sub-image annotation stage bounds: ({},{}) -> ({},{})",
                    String.format("%.1f", stageBoundsX1),
                    String.format("%.1f", stageBoundsY1),
                    String.format("%.1f", stageBoundsX2),
                    String.format("%.1f", stageBoundsY2));
        }

        String annotationName = annotation != null ? annotation.getName() : null;

        String sourceMicroscope = resolveSourceMicroscope();
        String detector = qupath.ext.qpsc.preferences.PersistentPreferences.getLastDetector();
        if (detector != null && detector.isEmpty()) detector = null;

        // Camera FOV in stage microns. Required by
        // autoRegisterBoundsTransformIfAvailable so it can anchor the image's
        // top/left edge at exactly half a FOV before the annotation (the tile
        // grid centers the first tile on the annotation top-left). Without it,
        // auto-register falls back to a symmetric-halfFOV assumption that is
        // wrong whenever the tile grid overshoots the annotation, putting the
        // image origin off by the rounding asymmetry -- the half-FOV-Y
        // Move-to-Centroid error reproduced on PPM 2026-06-01. The
        // BoundingBox-path builder already resolves this; the sub-image /
        // annotation path (this method) was missing it and shipped null.
        Double fovXUm = null;
        Double fovYUm = null;
        try {
            qupath.ext.qpsc.utilities.MicroscopeConfigManager mgr =
                    qupath.ext.qpsc.utilities.MicroscopeConfigManager.getInstanceIfAvailable();
            if (mgr != null && modality != null && objective != null) {
                double[] fov = mgr.getCameraFOV(modality, objective, detector);
                if (fov != null && fov.length == 2) {
                    fovXUm = fov[0];
                    fovYUm = fov[1];
                }
            }
        } catch (Exception e) {
            logger.warn(
                    "Could not resolve camera FOV for sub-image metadata "
                            + "(modality={}, objective={}, detector={}): {} -- "
                            + "auto-register will fall back to symmetric half-FOV",
                    modality,
                    objective,
                    detector,
                    e.getMessage());
        }

        return new StitchingMetadata(
                parentEntry,
                offset[0],
                offset[1],
                flipX,
                flipY,
                sampleName,
                modality,
                objective,
                null,
                annotationName,
                null,
                stageBoundsX1,
                stageBoundsY1,
                stageBoundsX2,
                stageBoundsY2,
                fovXUm,
                fovYUm,
                detector,
                sourceMicroscope);
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
            ModalityHandler handler,
            StitchingOptions options,
            boolean pipelinedBatchAcquire) {

        logger.info(
                "Stitching {} channels for: {} (organization={}, splitChannels={})",
                channelIds.size(),
                annotationName,
                options.organization(),
                options.splitChannelIds());

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
                        // Pipelined multi-slide acquire: suppress the import's viewer side
                        // effects and await real import completion (per-channel intermediates
                        // are not imported here, but the merged/fallback imports below are).
                        stitchParams.put("pipelinedBatchAcquire", pipelinedBatchAcquire);
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

                        // Channels are stitched in parallel, bounded by the "Stitching
                        // concurrency" preference (default 4). Each channel is an
                        // independent writer to its own output file. The result list is
                        // positionally aligned to acquiredChannelIds so the split/merge
                        // partition below keeps its index pairing.
                        if (blockingDialog != null) {
                            blockingDialog.updateStatus(
                                    operationId,
                                    String.format(
                                            "Stitching %d channels for %s...",
                                            acquiredChannelIds.size(), annotationName));
                        }
                        List<String> channelResults = stitchTargetsBounded(
                                acquiredChannelIds,
                                "channel",
                                tileBaseDir,
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
                        List<String> successfullyStitchedChannelIds = new ArrayList<>();
                        for (int i = 0; i < acquiredChannelIds.size(); i++) {
                            String outPath = channelResults.get(i);
                            if (outPath != null) {
                                stitchedImages.add(outPath);
                                successfullyStitchedChannelIds.add(acquiredChannelIds.get(i));
                                logger.info("Stitch completed for channel {}: {}", acquiredChannelIds.get(i), outPath);
                            }
                        }

                        logger.info(
                                "Completed multi-channel stitching: {} of {} channels succeeded",
                                stitchedImages.size(),
                                acquiredChannelIds.size());

                        // Partition successfully stitched channels into those the user
                        // chose to write as their own file (split) and those to merge into
                        // one multichannel file. stitchedImages[i] pairs with
                        // successfullyStitchedChannelIds[i]. Default options leave the split
                        // set empty, so every channel merges -- unchanged behavior.
                        List<String> mergeImages = new ArrayList<>();
                        List<String> mergeIds = new ArrayList<>();
                        List<String> splitImages = new ArrayList<>();
                        for (int i = 0; i < stitchedImages.size(); i++) {
                            String cid = successfullyStitchedChannelIds.get(i);
                            if (options.isSplit(cid)) {
                                splitImages.add(stitchedImages.get(i));
                            } else {
                                mergeImages.add(stitchedImages.get(i));
                                mergeIds.add(cid);
                            }
                        }
                        logger.info(
                                "Channel output grouping: {} to merge ({}), {} written as separate file(s)",
                                mergeIds.size(),
                                mergeIds,
                                splitImages.size());

                        // Channels the user split out become their own project entries.
                        if (!splitImages.isEmpty()) {
                            importPerChannelFallback(
                                    splitImages, metadata, gui, project, handler, pipelinedBatchAcquire);
                        }

                        // Merge the remaining (non-split) per-channel pyramids into a single
                        // multichannel pyramid, imported to the project as one entry. A single
                        // leftover channel cannot be merged (ChannelMerger needs >=2 sources),
                        // so it is imported directly as its own entry.
                        if (mergeImages.size() == 1) {
                            logger.info(
                                    "Only one channel to merge for {}; importing it directly as its own entry",
                                    annotationName);
                            importPerChannelFallback(
                                    mergeImages, metadata, gui, project, handler, pipelinedBatchAcquire);
                        } else if (mergeImages.size() >= 2) {
                            if (blockingDialog != null) {
                                blockingDialog.updateStatus(
                                        operationId,
                                        "Merging " + mergeImages.size() + " channels into multichannel pyramid...");
                            }
                            try {
                                Path firstStitch = Paths.get(mergeImages.get(0));
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
                                String sanitizedAnnotationName = ImageNameGenerator.sanitizeForFilename(annotationName);
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
                                String mergedStem =
                                        mergedBaseName.substring(0, mergedBaseName.length() - ".ome.tif".length());
                                logger.info(
                                        "Merged filename built from naming scheme: stem='{}' (sample={}, modality={}, objective={}, annotation={}, index={})",
                                        mergedStem,
                                        displayName,
                                        mergedModality,
                                        objective,
                                        sanitizedAnnotationName,
                                        candidateIndex);

                                List<Integer> channelColors = getDefaultChannelColors(mergeIds);
                                String mergedPath = ChannelMerger.merge(
                                        mergeImages,
                                        mergeIds,
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
                                    importMergedImageOnly(
                                            mergedPath, metadata, gui, project, handler, pipelinedBatchAcquire);
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
                                            mergeImages.size());
                                    importPerChannelFallback(
                                            mergeImages, metadata, gui, project, handler, pipelinedBatchAcquire);
                                }
                            } catch (Exception mergeEx) {
                                logger.error(
                                        "Multichannel merge failed for {}: {} -- falling back to per-channel import",
                                        annotationName,
                                        mergeEx.getMessage(),
                                        mergeEx);
                                importPerChannelFallback(
                                        mergeImages, metadata, gui, project, handler, pipelinedBatchAcquire);
                            }
                        } else {
                            logger.debug(
                                    "No channels to merge for {} (all split out or none stitched successfully)",
                                    annotationName);
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
            java.util.Map.entry("dapi", packRGB(0, 0, 255)), // Blue
            java.util.Map.entry("fitc", packRGB(0, 255, 0)), // Green
            java.util.Map.entry("gfp", packRGB(0, 255, 0)), // Green
            java.util.Map.entry("tritc", packRGB(255, 0, 0)), // Red
            java.util.Map.entry("cy3", packRGB(255, 165, 0)), // Orange
            java.util.Map.entry("cy5", packRGB(255, 0, 255)), // Magenta
            java.util.Map.entry("cy7", packRGB(128, 0, 128)), // Purple
            java.util.Map.entry("bf", packRGB(255, 255, 255)), // White
            java.util.Map.entry("brightfield", packRGB(255, 255, 255)));

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
            // The stage bounds from metadata are the ANNOTATION bounds. The
            // stitched image extends beyond them on both sides, but NOT
            // symmetrically:
            //   - TOP/LEFT extension is ALWAYS exactly half a camera FOV.
            //     TilingUtilities.processBoundingBoxTilingRequest centers the
            //     first tile on the annotation's top-left corner, so the
            //     image's top-left corner sits exactly half a FOV before the
            //     annotation's top-left.
            //   - BOTTOM/RIGHT extension is whatever the tile grid resolves
            //     to: ceil(width / xStep) (+1 for exact divisions) rows /
            //     columns, with each tile's frame extending one full FOV from
            //     its center. For non-exact fits the bottom/right side gets
            //     MORE than half a FOV. Verified 2026-05-15 against OWS3
            //     bounded-acquisition output (the annotation was offset half a
            //     FOV from the image's geometric center in Y but not X,
            //     producing a half-FOV Y error in Move-to-Centroid).
            //
            // Preferred path: anchor TOP/LEFT at exactly half FOV (which we
            // can do when metadata.fovXUm/fovYUm are known) and derive
            // BOTTOM/RIGHT from the actual stitched image extent. Fallback:
            // when FOV is unknown (legacy metadata), average symmetrically;
            // this matches the prior behaviour and is byte-identical when the
            // tile grid happens to fit exactly.
            double imageExtentX = widthPx * pixelSizeUm;
            double imageExtentY = heightPx * pixelSizeUm;
            double annotExtentX = metadata.stageBoundsX2Um - metadata.stageBoundsX1Um;
            double annotExtentY = metadata.stageBoundsY2Um - metadata.stageBoundsY1Um;

            double topLeftExtX;
            double topLeftExtY;
            if (metadata.fovXUm != null && metadata.fovYUm != null && metadata.fovXUm > 0 && metadata.fovYUm > 0) {
                topLeftExtX = metadata.fovXUm / 2.0;
                topLeftExtY = metadata.fovYUm / 2.0;
                logger.info(
                        "Anchoring image bounds at top/left = half FOV (fovX={}, fovY={} um); "
                                + "bottom/right derived from imageExtent ({}, {} um) - annotExtent ({}, {} um) - topLeftExt",
                        metadata.fovXUm,
                        metadata.fovYUm,
                        String.format("%.1f", imageExtentX),
                        String.format("%.1f", imageExtentY),
                        String.format("%.1f", annotExtentX),
                        String.format("%.1f", annotExtentY));
            } else {
                topLeftExtX = (imageExtentX - annotExtentX) / 2.0;
                topLeftExtY = (imageExtentY - annotExtentY) / 2.0;
                logger.info(
                        "Camera FOV not in metadata -- falling back to symmetric half-FOV assumption "
                                + "(topLeftExtX={}, topLeftExtY={} um). May be off by tile-grid rounding asymmetry.",
                        String.format("%.1f", topLeftExtX),
                        String.format("%.1f", topLeftExtY));
            }

            // imgX1/Y1 fixed by the tile-grid anchor; imgX2/Y2 derived from
            // the actual stitched image extent so the math stays self-consistent
            // even when bottom/right extension > half FOV.
            double imgX1 = metadata.stageBoundsX1Um - topLeftExtX;
            double imgY1 = metadata.stageBoundsY1Um - topLeftExtY;
            double imgX2 = imgX1 + imageExtentX;
            double imgY2 = imgY1 + imageExtentY;

            double bottomRightExtX = imgX2 - metadata.stageBoundsX2Um;
            double bottomRightExtY = imgY2 - metadata.stageBoundsY2Um;
            logger.info(
                    "Stage bounds adjustment: annotation ({},{}) -> ({},{}), "
                            + "image ({},{}) -> ({},{}) (topLeftExt=({},{}), bottomRightExt=({},{}))",
                    String.format("%.1f", metadata.stageBoundsX1Um),
                    String.format("%.1f", metadata.stageBoundsY1Um),
                    String.format("%.1f", metadata.stageBoundsX2Um),
                    String.format("%.1f", metadata.stageBoundsY2Um),
                    String.format("%.1f", imgX1),
                    String.format("%.1f", imgY1),
                    String.format("%.1f", imgX2),
                    String.format("%.1f", imgY2),
                    String.format("%.1f", topLeftExtX),
                    String.format("%.1f", topLeftExtY),
                    String.format("%.1f", bottomRightExtX),
                    String.format("%.1f", bottomRightExtY));

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

            logger.info(
                    "Auto-register alignment: flip=({},{}), scale=({},{}), origin=({},{})",
                    flipX,
                    flipY,
                    String.format("%.6f", scaleX),
                    String.format("%.6f", scaleY),
                    String.format("%.1f", originX),
                    String.format("%.1f", originY));

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
            // Stamp pixelFrame="sub": this transform is in the sub-image's own pixel frame,
            // not the macro frame. Layer 2 of the 2026-05-11 alignment-lookup restructure.
            // Workflows operating on macro-frame annotations refuse to load this; only the
            // Live Viewer's sub-image Go-To-Centroid path (which renders this sub-image) opts in.
            AffineTransformManager.saveSlideAlignment(
                    project,
                    alignmentKey,
                    modality,
                    transform,
                    null,
                    flipX,
                    flipY,
                    AffineTransformManager.PIXEL_FRAME_SUB,
                    metadata.objective,
                    metadata.detector);
            logger.info(
                    "Auto-registered stage alignment for '{}' (pixelFrame=sub) from BoundingBox metadata "
                            + "(bounds=({},{})->({},{}), image={}x{})",
                    alignmentKey,
                    metadata.stageBoundsX1Um,
                    metadata.stageBoundsY1Um,
                    metadata.stageBoundsX2Um,
                    metadata.stageBoundsY2Um,
                    widthPx,
                    heightPx);

            // Stamp the bounds + stitcher flips directly on the project entry as
            // metadata. This is the SELF-CONTAINED record of where the image lives
            // in stage coordinates: it lives with the entry, survives microscope
            // renames / cross-scope opens / alignment-directory restructures, and
            // lets Live Viewer Move-to-Centroid rebuild the transform on the fly
            // without depending on the alignment-JSON lookup chain (which has
            // historically been the weak link -- see the 2026-05-15 regression
            // where the cross-scope guard / derived-directory restructure caused
            // loadDerivedAlignment to return null for valid bounded acquisitions).
            try {
                ProjectImageEntry<BufferedImage> boundsEntry = project.getImageList().stream()
                        .filter(e -> {
                            try {
                                java.net.URI u = e.getURIs().iterator().hasNext()
                                        ? e.getURIs().iterator().next()
                                        : null;
                                return u != null
                                        && new java.io.File(u).getAbsolutePath().equals(importedFile.getAbsolutePath());
                            } catch (Exception ex) {
                                return false;
                            }
                        })
                        .findFirst()
                        .orElse(null);
                if (boundsEntry != null) {
                    ImageMetadataManager.setBoundingBoxStageBounds(
                            boundsEntry, imgX1, imgY1, imgX2, imgY2, flipX, flipY);
                    project.syncChanges();
                    logger.info(
                            "Stamped bounding-box stage bounds on entry '{}': ({},{}) -> ({},{}) [flip=({},{})]",
                            boundsEntry.getImageName(),
                            String.format("%.1f", imgX1),
                            String.format("%.1f", imgY1),
                            String.format("%.1f", imgX2),
                            String.format("%.1f", imgY2),
                            flipX,
                            flipY);
                } else {
                    logger.debug(
                            "Could not locate project entry for {} to stamp stage bounds metadata",
                            importedFile.getName());
                }
            } catch (Exception entryEx) {
                logger.warn("Failed to stamp bounding-box stage bounds metadata on entry: {}", entryEx.getMessage());
            }
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
            ModalityHandler handler,
            boolean pipelinedBatchAcquire) {
        if (stitchedImages == null || stitchedImages.isEmpty()) {
            return;
        }
        TileProcessingUtilities.runImportOnFxThread(pipelinedBatchAcquire, () -> {
            for (String path : stitchedImages) {
                File f = new File(path);
                if (!f.exists()) {
                    logger.warn("Fallback import: file does not exist, skipping: {}", path);
                    continue;
                }
                try {
                    qupath.lib.projects.ProjectImageEntry<java.awt.image.BufferedImage> imported = null;
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
                        imported = QPProjectFunctions.addImageToProjectWithMetadata(
                                project,
                                f,
                                metadata.parentEntry,
                                metadata.xOffset,
                                metadata.yOffset,
                                false,
                                false,
                                metadata.sampleName,
                                metadata.modality,
                                metadata.objective,
                                metadata.angle,
                                metadata.annotationName,
                                metadata.imageIndex,
                                handler,
                                metadata.detector,
                                metadata.sourceMicroscope,
                                metadata.baseImageOverride);
                    } else {
                        QPProjectFunctions.addImageToProject(f, project, false, false, handler);
                    }
                    // Stamp the acquiring scope so cross-scope sub-image
                    // acquisition can be refused later (review finding H3).
                    if (imported != null) {
                        ImageMetadataManager.setAcquiredOnMicroscope(imported, resolveSourceMicroscope());
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
            // In the pipelined multi-slide acquire pass, suppress the viewer/project refresh so
            // a background import cannot yank the active viewer out from under the next slot; the
            // entries are already persisted (addImageToProjectWithMetadata syncs). The driver owns
            // the open entry.
            if (!pipelinedBatchAcquire) {
                try {
                    gui.setProject(project);
                    gui.refreshProject();
                } catch (Exception refreshEx) {
                    logger.warn("Failed to refresh project after fallback import: {}", refreshEx.getMessage());
                }
            }
        });
    }

    private static void importMergedImageOnly(
            String mergedPath,
            StitchingMetadata metadata,
            QuPathGUI gui,
            Project<BufferedImage> project,
            ModalityHandler handler,
            boolean pipelinedBatchAcquire) {
        final File mergedFile = new File(mergedPath);

        TileProcessingUtilities.runImportOnFxThread(pipelinedBatchAcquire, () -> {
            try {
                logger.info("Importing merged multichannel file to project: {}", mergedFile.getName());

                qupath.lib.projects.ProjectImageEntry<java.awt.image.BufferedImage> imported = null;
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
                    imported = QPProjectFunctions.addImageToProjectWithMetadata(
                            project,
                            mergedFile,
                            metadata.parentEntry,
                            metadata.xOffset,
                            metadata.yOffset,
                            false,
                            false,
                            metadata.sampleName,
                            metadata.modality,
                            metadata.objective,
                            metadata.angle,
                            metadata.annotationName,
                            metadata.imageIndex,
                            handler,
                            metadata.detector,
                            metadata.sourceMicroscope,
                            metadata.baseImageOverride);
                } else {
                    QPProjectFunctions.addImageToProject(mergedFile, project, false, false, handler);
                }
                // Stamp the acquiring scope so cross-scope sub-image
                // acquisition can be refused later (review finding H3).
                if (imported != null) {
                    ImageMetadataManager.setAcquiredOnMicroscope(imported, resolveSourceMicroscope());
                }
                logger.info("Merged multichannel file imported to project: {}", mergedFile.getName());

                // Auto-register the pixel->stage alignment from the known
                // BoundingBox acquisition bounds (no-op when metadata lacks
                // bounds, e.g. annotation-based acquisitions). Must happen
                // after addImageToProjectWithMetadata so the merged file is
                // on disk and its pixel dimensions can be read by the server.
                autoRegisterBoundsTransformIfAvailable(mergedFile, metadata, project);

                // In the pipelined multi-slide acquire pass, suppress the viewer/project refresh
                // and the open-entry so a background import cannot yank the active viewer out from
                // under the next slot; the entry is already persisted
                // (addImageToProjectWithMetadata syncs). The driver owns the open entry.
                if (!pipelinedBatchAcquire) {
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
                }
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

        // Create a temporary isolation directory. UUID suffix guarantees two
        // parallel isolations cannot collide on the same path even when they
        // share an angle prefix or when an earlier attempt left an orphan
        // _temp_* directory on disk. Truncate the UUID to keep the path
        // short on Windows (260-char path limit) while still giving us
        // ~10^9 collision space.
        String uuidSuffix =
                java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String tempDirName = "_temp_" + angleStr.replace("-", "neg").replace(".", "_") + "_" + uuidSuffix;
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
            // Retry with exponential backoff (200ms, 400ms, 800ms, 1600ms, 3200ms);
            // if move-back still fails, fall back to a recursive copy so the
            // original angle directory is always restored -- without that
            // fallback, a stranded _temp_<angle>_<hash> directory leaves the
            // tiles unreachable for any future re-stitch.
            logger.info("Starting cleanup - restoring directory structure for angle {}", angleStr);
            try {
                if (Files.exists(tempAngleDir)) {
                    logger.info("Restoring directory from {} to {}", tempAngleDir, angleDir);
                    boolean fastMove =
                            MinorFunctions.moveDirectoryWithRetryAndCopyFallback(tempAngleDir, angleDir, 5, 200);
                    if (fastMove) {
                        logger.info("Successfully restored {} from isolation", angleStr);
                    } else {
                        logger.warn(
                                "Move-back exhausted retries for angle {}; restored via copy fallback. "
                                        + "Tiles are at {}.",
                                angleStr,
                                angleDir);
                    }
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

        // Post-processing outputs (.biref / .sum) are computed from the angles and share their
        // grid, but they stitch here, separately from the angle loop. If registration corrected the
        // angles and these were left at nominal, they would be the one output misregistered against
        // everything else -- and misregistered against the very angles they were derived from.
        if (QPPreferenceDialog.getTileRegistrationEnabled()) {
            stitchParams = withRegistrationMode(
                    stitchParams,
                    TileRegistrationSupport.applyMode(tileBaseDir.resolve(TileRegistrationSupport.solutionFileName())));
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

    /**
     * Convert a single .ome.zarr directory to .ome.tif alongside it.
     * Does NOT modify the project or delete the ZARR.
     *
     * <p>Public so "Make Project Portable" can convert ZARR-backed entries that
     * never had a background conversion run (e.g. images produced by re-stitch
     * recovery in OME_ZARR mode). Idempotent: returns {@code true} immediately
     * if the sibling .ome.tif already exists.
     *
     * @return true if the TIFF was written successfully
     */
    public static boolean convertSingleZarrToTiff(String zarrPath, String compression) {
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
            logger.info(
                    "  Converted {} -> {} ({} MB, {}m {}s)",
                    zarr.getFileName(),
                    tiff.getFileName(),
                    sizeMB,
                    elapsed / 60,
                    elapsed % 60);
            return true;

        } catch (Exception e) {
            logger.error("Failed to convert {} to TIFF: {}", zarr.getFileName(), e.getMessage(), e);
            // Clean up partial TIFF if it exists
            try {
                if (Files.exists(tiff)) Files.delete(tiff);
            } catch (IOException ignored) {
            }
            return false;
        }
    }
}
