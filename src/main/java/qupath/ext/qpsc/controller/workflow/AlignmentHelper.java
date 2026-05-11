package qupath.ext.qpsc.controller.workflow;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.model.SampleSetupResult;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Helper class for alignment-related operations in the workflow.
 *
 * <p>This class provides utilities for:
 * <ul>
 *   <li>Checking for existing slide-specific alignments</li>
 *   <li>Calculating alignment confidence scores</li>
 *   <li>Loading saved alignment transforms</li>
 * </ul>
 *
 * <p>Slide-specific alignments allow users to skip the alignment process if they've
 * already aligned this specific slide in a previous session.
 *
 * <p>Confidence scoring is based on multiple factors:
 * <ul>
 *   <li>Alignment source (slide-specific vs general)</li>
 *   <li>Age of the alignment</li>
 *   <li>Number of control points used</li>
 *   <li>Transform residual error (if available)</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class AlignmentHelper {
    private static final Logger logger = LoggerFactory.getLogger(AlignmentHelper.class);

    // Confidence thresholds
    private static final double BASE_CONFIDENCE_SLIDE_SPECIFIC = 0.85;
    private static final double BASE_CONFIDENCE_GENERAL = 0.65;
    // Age penalty: 0.003 per day means ~30 days before reaching "aging" threshold
    // (0.85 base - 30*0.003 = 0.76, still > 0.7). Turns orange after ~50 days.
    private static final double CONFIDENCE_AGE_PENALTY_PER_DAY = 0.003;
    private static final double MAX_AGE_PENALTY = 0.3;

    /**
     * Result from checking for existing slide alignment.
     */
    public static class SlideAlignmentResult {
        private final AffineTransform transform;
        private final boolean refineRequested;
        private final double confidence;
        private final String source;

        public SlideAlignmentResult(AffineTransform transform, boolean refineRequested) {
            this(transform, refineRequested, 0.7, "Unknown");
        }

        public SlideAlignmentResult(
                AffineTransform transform, boolean refineRequested, double confidence, String source) {
            this.transform = transform;
            this.refineRequested = refineRequested;
            this.confidence = confidence;
            this.source = source;
        }

        public AffineTransform getTransform() {
            return transform;
        }

        public boolean isRefineRequested() {
            return refineRequested;
        }

        public double getConfidence() {
            return confidence;
        }

        public String getSource() {
            return source;
        }
    }

    /**
     * Calculates confidence score for a slide-specific alignment.
     *
     * @param isSlideSpecific Whether this is a slide-specific (vs general) alignment
     * @param createdDate The date string when alignment was created (may be null)
     * @return Confidence score between 0.0 and 1.0
     */
    public static double calculateConfidence(boolean isSlideSpecific, String createdDate) {
        // Start with base confidence based on alignment type
        double confidence = isSlideSpecific ? BASE_CONFIDENCE_SLIDE_SPECIFIC : BASE_CONFIDENCE_GENERAL;

        // Apply age penalty if date is available
        if (createdDate != null && !createdDate.isEmpty()) {
            try {
                // Parse date and calculate age in days
                java.time.LocalDate created = java.time.LocalDate.parse(createdDate.substring(0, 10));
                long daysOld = java.time.temporal.ChronoUnit.DAYS.between(created, java.time.LocalDate.now());

                // Apply penalty (capped at MAX_AGE_PENALTY)
                double agePenalty = Math.min(daysOld * CONFIDENCE_AGE_PENALTY_PER_DAY, MAX_AGE_PENALTY);
                confidence -= agePenalty;

                logger.debug(
                        "Alignment age: {} days, penalty: {}, final confidence: {}", daysOld, agePenalty, confidence);
            } catch (Exception e) {
                logger.debug("Could not parse alignment date: {}", createdDate);
            }
        }

        // Ensure confidence stays in valid range
        return Math.max(0.1, Math.min(1.0, confidence));
    }

    /**
     * Calculates confidence for a TransformPreset.
     *
     * @param preset The transform preset to evaluate
     * @return Confidence score between 0.0 and 1.0
     */
    public static double calculateConfidence(AffineTransformManager.TransformPreset preset) {
        if (preset == null) {
            return 0.0;
        }

        // General transforms start with lower confidence
        double confidence = BASE_CONFIDENCE_GENERAL;

        // Apply age penalty
        java.util.Date createdDate = preset.getCreatedDate();
        if (createdDate != null) {
            try {
                java.time.LocalDate created = createdDate
                        .toInstant()
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate();
                long daysOld = java.time.temporal.ChronoUnit.DAYS.between(created, java.time.LocalDate.now());
                double agePenalty = Math.min(daysOld * CONFIDENCE_AGE_PENALTY_PER_DAY, MAX_AGE_PENALTY);
                confidence -= agePenalty;
            } catch (Exception e) {
                logger.debug("Could not parse preset date: {}", createdDate);
            }
        }

        // Boost if green box params are saved (indicates successful prior use)
        if (preset.getGreenBoxParams() != null) {
            confidence += 0.05;
        }

        return Math.max(0.1, Math.min(1.0, confidence));
    }

    /**
     * Checks for existing slide-specific alignment.
     *
     * <p>This method:
     * <ol>
     *   <li>Attempts to load a saved alignment for the specific slide</li>
     *   <li>Calculates confidence score for the alignment</li>
     *   <li>Returns result with transform and confidence (refinement handled separately)</li>
     * </ol>
     *
     * <p>The alignment is slide-specific, meaning it's saved per image name and includes
     * any refinements made during previous acquisitions.
     *
     * <p><b>Note:</b> Refinement options are now handled by {@code RefinementSelectionController}
     * which provides a unified interface with confidence-based recommendations.
     *
     * @param gui QuPath GUI instance
     * @param sample Sample setup information including name and project location
     * @return CompletableFuture containing the SlideAlignmentResult or null if none found
     */
    public static CompletableFuture<SlideAlignmentResult> checkForSlideAlignment(
            QuPathGUI gui, SampleSetupResult sample) {

        CompletableFuture<SlideAlignmentResult> future = new CompletableFuture<>();

        // Get the actual image file name (not metadata name which may be project name)
        String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());

        if (imageName == null) {
            logger.warn("No image is currently open, cannot check for slide alignment");
            future.complete(null);
            return future;
        }

        // Resolve the lookup key to the parent macro entry name via base_image metadata.
        // When the open entry is a sub-image (a previously-stitched acquisition output),
        // its auto-registered alignment JSON describes the sub-image's own pixel frame
        // (scale = camera pixel size). Annotations in this workflow are in the macro
        // pixel frame, so we must load the macro alignment, not the sub-image's. Mirrors
        // the pattern in ForwardPropagationWorkflow.buildGroups (lines 100-104).
        Project<BufferedImage> project = gui.getProject();
        String lookupKey = resolveMacroLookupKey(project, gui.getImageData(), imageName);

        logger.info("Checking for slide-specific alignment for image: {} (lookupKey={})", imageName, lookupKey);

        // Try to load slide-specific alignment using the resolved macro key
        AffineTransform slideTransform = null;
        String createdDate = null;
        Boolean alignFlipX = null;
        Boolean alignFlipY = null;

        // First try from current project. Prefer the with-frame loader so the
        // alignment-time macro flip is captured -- Step B of the flip-relocation
        // refactor bakes this flip into the returned transform so all downstream
        // callers operate on unflipped-base pixel coords.
        AffineTransformManager.SlideAlignmentResult loadedResult = null;
        if (project != null) {
            loadedResult = AffineTransformManager.loadSlideAlignmentWithFrame(project, lookupKey);
            if (loadedResult != null) {
                slideTransform = loadedResult.getTransform();
                alignFlipX = loadedResult.getFlipMacroX();
                alignFlipY = loadedResult.getFlipMacroY();
            } else {
                slideTransform = AffineTransformManager.loadSlideAlignment(project, lookupKey);
            }
            createdDate = AffineTransformManager.getSlideAlignmentDate(project, lookupKey);
        } else {
            // Try from project directory if no project is open
            File projectDir = new File(sample.projectsFolder(), sample.sampleName());
            if (projectDir.exists()) {
                loadedResult = AffineTransformManager.loadSlideAlignmentWithFrameFromDirectory(projectDir, lookupKey);
                if (loadedResult != null) {
                    slideTransform = loadedResult.getTransform();
                    alignFlipX = loadedResult.getFlipMacroX();
                    alignFlipY = loadedResult.getFlipMacroY();
                } else {
                    slideTransform = AffineTransformManager.loadSlideAlignmentFromDirectory(projectDir, lookupKey);
                }
                createdDate = AffineTransformManager.getSlideAlignmentDateFromDirectory(projectDir, lookupKey);
            }
        }

        // Layer 2 gate: refuse a sub-frame transform. The workflow operates on macro-frame
        // annotation coords; loading a transform with scale = camera pixel size silently
        // shrinks every stage move by camera_px / macro_px.
        if (loadedResult != null
                && loadedResult.getPixelFrame() != null
                && !AffineTransformManager.PIXEL_FRAME_MACRO.equals(loadedResult.getPixelFrame())) {
            logger.error(
                    "Refusing slide alignment for '{}' -- pixelFrame='{}', expected '{}'.",
                    lookupKey,
                    loadedResult.getPixelFrame(),
                    AffineTransformManager.PIXEL_FRAME_MACRO);
            showPixelFrameMismatchDialog(lookupKey, loadedResult.getPixelFrame());
            future.complete(null);
            return future;
        }

        // Bake the alignment-frame flip into the returned transform so downstream
        // callers (AcquisitionManager, AnnotationOrderingService, ...) consume
        // unflipped-base pixel coords directly. baseToStage_new = baseToStage *
        // createFlip(alignFlipX, alignFlipY, baseWidth, baseHeight). Requires the
        // base image dimensions; read them from the currently-open image (which
        // is the unflipped base by convention after Step B).
        if (slideTransform != null
                && alignFlipX != null
                && alignFlipY != null
                && (alignFlipX || alignFlipY)
                && gui.getImageData() != null) {
            try {
                int baseWidth = gui.getImageData().getServer().getWidth();
                int baseHeight = gui.getImageData().getServer().getHeight();
                AffineTransform flip = qupath.ext.qpsc.controller.ForwardPropagationWorkflow.createFlip(
                        alignFlipX, alignFlipY, baseWidth, baseHeight);
                AffineTransform composed = new AffineTransform(slideTransform);
                composed.concatenate(flip);
                slideTransform = composed;
                logger.info(
                        "Baked alignment-frame flip ({}, {}) into slide transform; downstream uses unflipped-base pixel coords",
                        alignFlipX,
                        alignFlipY);
            } catch (Exception e) {
                logger.warn(
                        "Could not bake alignment flip into transform; downstream may misalign: {}", e.getMessage());
            }
        }

        if (slideTransform != null) {
            // Calculate confidence for this slide-specific alignment
            double confidence = calculateConfidence(true, createdDate);
            String source = "Slide-specific (" + imageName + ")";

            logger.info(
                    "Found slide-specific alignment with confidence: {} (source: {})",
                    String.format("%.2f", confidence),
                    source);

            // Return result - refinement choice is handled later by RefinementSelectionController
            future.complete(new SlideAlignmentResult(slideTransform, false, confidence, source));
        } else {
            logger.info("No slide-specific alignment found");
            future.complete(null);
        }

        return future;
    }

    /**
     * Gets a descriptive source string for a general transform.
     *
     * @param preset The transform preset
     * @return Human-readable source description
     */
    public static String getSourceDescription(AffineTransformManager.TransformPreset preset) {
        if (preset == null) {
            return "Unknown";
        }
        return "General (" + preset.getName() + ")";
    }

    /**
     * Resolves the slide-alignment lookup key for the currently-open entry.
     *
     * <p>When the entry is a sub-image (a stitched acquisition output), its own
     * alignment JSON describes the sub-image's pixel frame, not the macro frame.
     * Workflows operating on macro-frame annotations must look up the macro
     * alignment, so we resolve through the entry's {@code base_image} metadata
     * (set at import) before falling back to the stripped image filename.
     *
     * <p>Mirrors the pattern in {@code ForwardPropagationWorkflow.buildGroups}.
     *
     * @return the resolved lookup key; never null when {@code imageName} is non-null
     */
    /**
     * Shows the FX-safe pixel-frame-mismatch dialog. Pattern matches
     * {@code QPScopeChecks.showMismatchDialog}: blocking, OK-only, hard-cancel.
     */
    private static void showPixelFrameMismatchDialog(String lookupKey, String actualFrame) {
        String title = "Slide Alignment Pixel-Frame Mismatch -- Workflow Cancelled";
        String header = "The loaded slide alignment is in the wrong pixel frame.";
        StringBuilder body = new StringBuilder();
        body.append(String.format(
                "The slide alignment file matched for this workflow is tagged %n  pixelFrame = \"%s\"%n", actualFrame));
        body.append(String.format("but this workflow operates on macro-frame annotation coordinates,%n"));
        body.append(String.format(
                "which require an alignment tagged%n  pixelFrame = \"%s\".%n%n",
                AffineTransformManager.PIXEL_FRAME_MACRO));
        body.append("Sub-frame alignments are produced when a previously-stitched\n");
        body.append("acquisition output is auto-registered. They are valid for the\n");
        body.append("Live Viewer's Go-To-Centroid on that specific sub-image, but not\n");
        body.append("for tile planning or annotation-driven acquisition.\n\n");
        body.append(String.format("Lookup key used: %s%n%n", lookupKey));
        body.append("To fix:\n");
        body.append("  1. In QuPath's Project tab, open the macro slide entry\n");
        body.append("     (typically the entry whose name has no _<mag>x_<region>_\n");
        body.append("      suffix, often marked '(flipped XY)' for PPM).\n");
        body.append("  2. Re-run the workflow.\n\n");
        body.append("If the macro entry has no alignment yet, run 3-point Microscope\n");
        body.append("Alignment first to create one.\n\n");
        body.append("This workflow has been cancelled.");

        if (javafx.application.Platform.isFxApplicationThread()) {
            showPixelFrameMismatchDialogOnFx(title, header, body.toString());
            return;
        }
        java.util.concurrent.FutureTask<Void> task = new java.util.concurrent.FutureTask<>(() -> {
            showPixelFrameMismatchDialogOnFx(title, header, body.toString());
            return null;
        });
        javafx.application.Platform.runLater(task);
        try {
            task.get();
        } catch (Exception e) {
            logger.warn("Failed to display pixel-frame mismatch dialog: {}", e.getMessage());
        }
    }

    private static void showPixelFrameMismatchDialogOnFx(String title, String header, String body) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(body);
        alert.getButtonTypes().setAll(javafx.scene.control.ButtonType.OK);
        alert.getDialogPane().setMinWidth(620);
        alert.getDialogPane().setPrefWidth(720);
        alert.getDialogPane().setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        javafx.scene.control.Label contentLabel =
                (javafx.scene.control.Label) alert.getDialogPane().lookup(".content");
        if (contentLabel != null) {
            contentLabel.setWrapText(true);
            contentLabel.setMaxWidth(680);
            contentLabel.setStyle("-fx-font-family: 'monospace';");
        }
        javafx.scene.control.Label headerLabel =
                (javafx.scene.control.Label) alert.getDialogPane().lookup(".header-panel .label");
        if (headerLabel != null) {
            headerLabel.setWrapText(true);
            headerLabel.setMaxWidth(660);
        }
        alert.showAndWait();
    }

    public static String resolveMacroLookupKey(
            Project<BufferedImage> project, qupath.lib.images.ImageData<BufferedImage> imageData, String imageName) {
        String entryStripped = GeneralTools.stripExtension(imageName);
        if (project == null || imageData == null) {
            return entryStripped;
        }
        try {
            ProjectImageEntry<BufferedImage> entry = project.getEntry(imageData);
            String rawBase = ImageMetadataManager.getBaseImage(entry);
            return (rawBase != null && !rawBase.isEmpty()) ? rawBase : entryStripped;
        } catch (Exception e) {
            logger.debug("Could not resolve base_image for entry; falling back to image name: {}", e.getMessage());
            return entryStripped;
        }
    }
}
