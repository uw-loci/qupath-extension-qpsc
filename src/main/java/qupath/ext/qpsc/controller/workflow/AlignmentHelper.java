package qupath.ext.qpsc.controller.workflow;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.model.SampleSetupResult;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.ImageFlipHelper;
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

        // Objective-context advisory (review finding H8, 2026-05-14). When the saved
        // alignment records the objective it was built against, compare against the
        // wizard's choice now. The pixel-size gate catches mismatches between MM live
        // and the wizard, but cannot catch "transform refined at 10x reused with the
        // wizard set to 20x" when both objectives happen to match MM's live pixel size.
        // Refinement adds a translation that's tied to the 10x tile center; replaying
        // at 20x reuses that translation, losing the per-tile correction. Surfaces
        // only when both sides have values -- legacy JSONs (no objective field) load
        // with null and the dialog is silent.
        if (loadedResult != null
                && loadedResult.getObjective() != null
                && sample != null
                && sample.objective() != null
                && !loadedResult.getObjective().equals(sample.objective())) {
            logger.warn(
                    "Slide alignment objective mismatch: saved='{}', wizard='{}' -- prompting user",
                    loadedResult.getObjective(),
                    sample.objective());
            if (!confirmContinueWithObjectiveMismatch(lookupKey, loadedResult.getObjective(), sample.objective())) {
                logger.info("User cancelled at objective-mismatch advisory");
                future.complete(null);
                return future;
            }
        }

        // Legacy-JSON flip-frame advisory (review finding M6). Pre-Phase-3 JSONs do not
        // record flipMacroX / flipMacroY. We cannot tell which frame the transform was
        // built in. When the active scope's saved presets require a flip (i.e. flipping
        // matters for some scanner-on-this-scope pairing), reusing a legacy alignment
        // risks applying a flipped-frame transform to unflipped pixel coords (or vice
        // versa) -- the Move-to-Centroid X-mirror class. Continue/Cancel advisory only;
        // legacy data may still be usable when the alignment-time frame happened to
        // match what the workflow will run in now.
        if (loadedResult != null && !loadedResult.hasFlipFrame() && ImageFlipHelper.isActiveScopeFlipNeeding()) {
            logger.warn(
                    "Legacy slide alignment for '{}' has no flipMacroX/Y; active scope is flip-needing -- prompting user",
                    lookupKey);
            if (!confirmContinueWithLegacyFlipFrame(lookupKey)) {
                logger.info("User cancelled at legacy-flip-frame advisory");
                future.complete(null);
                return future;
            }
        }

        // Bake the alignment-frame -> current-entry-frame flip delta into the returned
        // transform so downstream callers (AcquisitionManager, AnnotationOrderingService,
        // tile creation, refinement) can feed pixel coords from the CURRENT open entry's
        // hierarchy directly.
        //
        // Step B's original design assumed the workflow always ran with the UNFLIPPED
        // BASE as the open entry (validateAndFlipIfNeeded would create / switch to the
        // flipped sibling for the visual-UX of the alignment step, but downstream
        // operated on base-frame coords). If alignFlipX/Y was true, we unconditionally
        // baked it in so saved(flipped) became baked(unflipped) -> stage.
        //
        // That assumption breaks when the user opens the flipped sibling directly:
        // ImageFlipHelper.validateAndFlipIfNeeded no-ops on already-flipped entries, the
        // workflow stays on the sibling, and the hierarchy yields FLIPPED-frame coords.
        // An unconditional bake then double-flips and the stage lands at the X/Y mirror
        // (verified 2026-05-15 from OWS3 logs: tile at flipped-frame (94746, 36206)
        // moved to stage (5474, -7625) when the correct target was ~(22180, -9982)).
        //
        // Fix: bake only the DELTA -- bakeX = alignFlipX XOR currentEntryFlipX, same Y.
        // Both-on or both-off => no bake; saved transform's native frame already matches
        // the current entry's coord space.
        boolean currentEntryFlipX = false;
        boolean currentEntryFlipY = false;
        try {
            if (project != null && gui.getImageData() != null) {
                ProjectImageEntry<BufferedImage> openEntry = project.getEntry(gui.getImageData());
                if (openEntry != null) {
                    currentEntryFlipX = ImageMetadataManager.isFlippedX(openEntry);
                    currentEntryFlipY = ImageMetadataManager.isFlippedY(openEntry);
                }
            }
        } catch (Exception e) {
            logger.debug(
                    "Could not read current-entry flip metadata for bake delta; assuming unflipped: {}",
                    e.getMessage());
        }
        if (slideTransform != null && alignFlipX != null && alignFlipY != null && gui.getImageData() != null) {
            boolean bakeX = alignFlipX != currentEntryFlipX;
            boolean bakeY = alignFlipY != currentEntryFlipY;
            if (!bakeX && !bakeY) {
                logger.info(
                        "Alignment frame ({}, {}) matches current entry frame ({}, {}); skipping bake",
                        alignFlipX,
                        alignFlipY,
                        currentEntryFlipX,
                        currentEntryFlipY);
            } else
                try {
                    int baseWidth = gui.getImageData().getServer().getWidth();
                    int baseHeight = gui.getImageData().getServer().getHeight();
                    AffineTransform flip = qupath.ext.qpsc.controller.ForwardPropagationWorkflow.createFlip(
                            bakeX, bakeY, baseWidth, baseHeight);
                    AffineTransform composed = new AffineTransform(slideTransform);
                    composed.concatenate(flip);
                    slideTransform = composed;
                    logger.info(
                            "Baked frame-delta flip ({}, {}) into slide transform "
                                    + "(alignFrame=({}, {}), currentEntryFrame=({}, {}))",
                            bakeX,
                            bakeY,
                            alignFlipX,
                            alignFlipY,
                            currentEntryFlipX,
                            currentEntryFlipY);
                } catch (Exception e) {
                    logger.warn(
                            "Could not bake alignment flip into transform; downstream may misalign: {}",
                            e.getMessage());
                }
        }

        // Fallback when no JSON exists: derive a pixel-to-stage transform from
        // the open entry's BoundingBox metadata. QPSC-acquired stitches stamp
        // STAGE_BOUNDS_* + STITCHER_FLIP_* on the entry; the same metadata that
        // powers Go-to-Centroid yields a complete slide-specific alignment with
        // no macro or manual setup needed. Without this fallback the workflow
        // would force the user into Manual alignment even though the image's
        // stage coordinates are already known.
        String boundsSource = null;
        if (slideTransform == null && project != null && gui.getImageData() != null) {
            try {
                ProjectImageEntry<BufferedImage> openEntry = project.getEntry(gui.getImageData());
                int widthPx = gui.getImageData().getServer().getWidth();
                int heightPx = gui.getImageData().getServer().getHeight();
                AffineTransform bbTransform =
                        ImageMetadataManager.buildBoundingBoxPixelToStageTransform(openEntry, widthPx, heightPx);
                if (bbTransform != null) {
                    slideTransform = bbTransform;
                    boundsSource = "BoundingBox metadata (" + imageName + ")";
                    logger.info("Derived slide-specific alignment from BoundingBox entry metadata for {}", imageName);
                }
            } catch (Exception e) {
                logger.debug("Could not derive BoundingBox-based alignment: {}", e.getMessage());
            }
        }

        if (slideTransform != null) {
            // Calculate confidence for this slide-specific alignment
            double confidence = calculateConfidence(true, createdDate);
            String source = boundsSource != null ? boundsSource : "Slide-specific (" + imageName + ")";

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

    /**
     * Advisory dialog for the objective-mismatch case (review finding H8). Modal, blocking,
     * Continue / Cancel buttons. Returns {@code true} when the user chose to continue with the
     * loaded alignment, {@code false} when they cancelled (or the dialog failed to show, which
     * also short-circuits the workflow conservatively).
     */
    /**
     * Continue / Cancel advisory for a legacy alignment JSON (review finding M6).
     * Pre-Phase-3 JSONs do not record {@code flipMacroX} / {@code flipMacroY}; when the
     * active scope requires a flip for some saved preset, reusing such an alignment
     * risks applying a flipped-frame transform to unflipped pixel coords or vice versa.
     */
    private static boolean confirmContinueWithLegacyFlipFrame(String lookupKey) {
        String title = "Legacy Alignment -- Flip Frame Unknown";
        String header = "This alignment was saved before flip-frame tracking.";
        StringBuilder body = new StringBuilder();
        body.append(String.format(
                "The slide alignment for%n  '%s'%nwas saved before the flip frame was recorded%n"
                        + "(no 'flipMacroX' / 'flipMacroY' field on the JSON).%n%n",
                lookupKey));
        body.append("The active microscope's saved presets require a flipped sibling\n");
        body.append("for at least one source scanner -- flipping matters for this\n");
        body.append("scope. We cannot tell whether the saved transform was built in\n");
        body.append("the flipped or unflipped frame. Reusing it risks an X-mirrored\n");
        body.append("Move-to-Centroid / acquisition target.\n\n");
        body.append("Recommended: cancel and re-run Microscope Alignment for this\n");
        body.append("slide; the new JSON will record the frame and downstream\n");
        body.append("workflows will compose it correctly.\n\n");
        body.append("Continue anyway with the legacy alignment?");
        return confirmContinueDialog(title, header, body.toString());
    }

    private static boolean confirmContinueWithObjectiveMismatch(
            String lookupKey, String savedObjective, String wizardObjective) {
        String title = "Alignment Objective Mismatch";
        String header = "The saved alignment was built at a different objective.";
        StringBuilder body = new StringBuilder();
        body.append(String.format(
                "The slide alignment for%n  '%s'%nwas saved at objective '%s'.%n"
                        + "The wizard is configured for objective '%s'.%n%n",
                lookupKey, savedObjective, wizardObjective));
        body.append("Continuing reuses the saved transform, including any refinement\n");
        body.append("translation that was tied to the saved objective's tile geometry.\n");
        body.append("On a different objective the per-tile correction is approximate;\n");
        body.append("the linear (scale+rotation) part of the transform is still valid\n");
        body.append("but you may see small per-tile drift relative to the annotations.\n\n");
        body.append("Recommended: cancel, switch the wizard to the saved objective, or\n");
        body.append("re-run alignment refinement at the new objective.\n\n");
        body.append("Continue anyway with the saved alignment?");
        return confirmContinueDialog(title, header, body.toString());
    }

    /**
     * FX-safe modal Continue / Cancel dialog. Returns {@code true} when the user
     * chose Continue, {@code false} when they cancelled (or the dialog failed to
     * show, which also short-circuits the workflow conservatively).
     *
     * <p>Extracted from the H8 objective-mismatch dialog so other advisories
     * (M8 cross-scope, M4 sub-image objective, M5 missing objective, M6 legacy
     * JSON) share the same FX-safe pattern.
     */
    public static boolean confirmContinueDialog(String title, String header, String body) {
        final boolean[] result = {false};
        Runnable show = () -> {
            javafx.scene.control.Alert alert =
                    new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING);
            alert.setTitle(title);
            alert.setHeaderText(header);
            alert.setContentText(body);
            alert.getButtonTypes().setAll(javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
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
            result[0] = alert.showAndWait()
                    .filter(b -> b == javafx.scene.control.ButtonType.OK)
                    .isPresent();
        };
        if (javafx.application.Platform.isFxApplicationThread()) {
            show.run();
            return result[0];
        }
        java.util.concurrent.FutureTask<Void> task = new java.util.concurrent.FutureTask<>(() -> {
            show.run();
            return null;
        });
        javafx.application.Platform.runLater(task);
        try {
            task.get();
            return result[0];
        } catch (Exception e) {
            logger.warn("Failed to display Continue/Cancel dialog '{}': {}", title, e.getMessage());
            return false;
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
