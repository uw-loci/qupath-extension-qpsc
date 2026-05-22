package qupath.ext.qpsc.controller;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.QPScopeChecks;
import qupath.ext.qpsc.controller.workflow.*;
import qupath.ext.qpsc.model.SampleSetupResult;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.notification.NotificationEvent;
import qupath.ext.qpsc.service.notification.NotificationPriority;
import qupath.ext.qpsc.service.notification.NotificationService;
import qupath.ext.qpsc.ui.*;
import qupath.ext.qpsc.ui.ExistingImageAcquisitionController.ExistingImageAcquisitionConfig;
import qupath.ext.qpsc.ui.ExistingImageAcquisitionController.RefinementChoice;
import qupath.ext.qpsc.utilities.*;
import qupath.ext.qpsc.utilities.AnnotationPreservationService;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * ExistingImageWorkflowV2 - Existing Image acquisition workflow.
 *
 * <p>This workflow uses the {@link ExistingImageAcquisitionController} consolidated
 * dialog to gather all configuration in a single step, then routes to appropriate
 * sub-workflows based on user selections.
 *
 * <p>Key features:
 * <ul>
 *   <li>Single consolidated dialog for all configuration</li>
 *   <li>Confidence-based alignment recommendations</li>
 *   <li>Refinement options presented upfront</li>
 *   <li>Green box detection parameters editable in advanced section</li>
 * </ul>
 *
 * <p>Workflow stages:
 * <ol>
 *   <li>Validate prerequisites</li>
 *   <li>Show consolidated dialog</li>
 *   <li>Check for slide-specific alignment (may skip to acquisition)</li>
 *   <li>Route sub-workflow (existing alignment path or manual alignment)</li>
 *   <li>Handle refinement if selected</li>
 *   <li>Perform acquisition</li>
 *   <li>Wait for stitching completion</li>
 * </ol>
 */
public class ExistingImageWorkflowV2 {
    private static final Logger logger = LoggerFactory.getLogger(ExistingImageWorkflowV2.class);

    /**
     * Starts the workflow.
     */
    public static void start() {
        new WorkflowOrchestrator().execute();
    }

    /**
     * Internal orchestrator class that manages the workflow execution.
     */
    private static class WorkflowOrchestrator {
        private final QuPathGUI gui;
        private final WorkflowState state;

        WorkflowOrchestrator() {
            this.gui = QuPathGUI.getInstance();
            this.state = new WorkflowState();
        }

        /**
         * Executes the complete workflow.
         */
        public void execute() {
            // Step 1: Validate prerequisites
            if (!validatePrerequisites()) {
                return;
            }

            // Step 1a: If the open entry's source_microscope disagrees with the
            // active microscope, surface that before any further setup runs.
            // The user can fix the tag in-place (treat as native) or proceed
            // explicitly with cross-scope alignment.
            if (!checkAndHandleSourceMismatch()) {
                return;
            }

            // Step 1.5: Preserve annotations if this is a standalone image (no project)
            // This handles the case where user drags image into QuPath, draws annotations,
            // then starts the workflow. Without preservation, annotations would be lost
            // when the new project is created.
            if (gui.getProject() == null) {
                ImageData<?> imageData = gui.getImageData();
                if (imageData != null
                        && imageData.getHierarchy() != null
                        && !imageData.getHierarchy().getAnnotationObjects().isEmpty()) {
                    logger.info("Standalone image with annotations detected - preserving for project creation");
                    boolean captured = state.annotationPreservation.captureAnnotations(gui);
                    if (captured) {
                        logger.info(
                                "Preserved {} annotations from standalone image",
                                state.annotationPreservation.getPreservedAnnotationCount());
                    }
                }
            }

            // Step 2: Check if annotations exist and show annotation dialog FIRST
            Set<String> existingClasses = getExistingAnnotationClasses();

            if (!existingClasses.isEmpty()) {
                // Annotations exist - show annotation selection dialog first
                logger.info(
                        "Found {} annotation classes in image, showing selection dialog first", existingClasses.size());

                List<String> preselected = PersistentPreferences.getSelectedAnnotationClasses();
                String defaultSampleName = getDefaultSampleName();

                // Show annotation selection dialog (modality options are in the combined dialog's Advanced Options)
                AnnotationAcquisitionDialog.showDialog(existingClasses, preselected)
                        .thenCompose(annotationResult -> {
                            if (!annotationResult.proceed || annotationResult.selectedClasses.isEmpty()) {
                                throw new CancellationException("Annotation selection cancelled");
                            }

                            // Store selected classes in state for later use
                            // Note: angle overrides will come from the combined dialog's Advanced Options
                            state.selectedAnnotationClasses = annotationResult.selectedClasses;
                            logger.info(
                                    "User selected {} classes: {}",
                                    annotationResult.selectedClasses.size(),
                                    annotationResult.selectedClasses);

                            // Get actual annotations for the selected classes. Carrying
                            // these across a flip switch is handled by
                            // ImageFlipHelper.mirrorAnnotationsToSibling, which mirrors the
                            // base's LIVE hierarchy onto the flipped sibling; the workflow
                            // re-reads by class post-routing.
                            List<PathObject> selectedAnnotations =
                                    getAnnotationsForClasses(annotationResult.selectedClasses);
                            return ExistingImageAcquisitionController.showDialog(
                                    defaultSampleName, selectedAnnotations);
                        })
                        .thenCompose(this::initializeFromConfig)
                        .thenCompose(this::checkExistingSlideAlignment)
                        .thenCompose(this::routeSubWorkflow)
                        .thenCompose(this::reReadAnnotationsAfterRouting)
                        .thenCompose(this::handleRefinement)
                        .thenCompose(this::performAcquisition)
                        .thenCompose(this::waitForCompletion)
                        .thenAccept(result -> {
                            // Gate the success-only side effects (beep, ACQUISITION_COMPLETE
                            // notification, aggressive tile cleanup) on a non-null result.
                            // The chain's null-state-propagation pattern (every internal
                            // method returns completedFuture(null) on cancel/short-circuit)
                            // means we can reach this branch after the pixel-size gate, the
                            // camera-ROI gate, or any internal cancel -- without distinguishing
                            // them from real success. Without this gate the operator could
                            // hear the success beep + see ACQUISITION_COMPLETE notification
                            // after cancelling at the gate. Review finding H5. cleanup() must
                            // still run on both paths so resource leaks (preserved annotations,
                            // currentTransform singleton) are cleared either way.
                            if (result == null) {
                                logger.info("Workflow short-circuited; skipping success notification");
                                cleanup();
                                return;
                            }
                            cleanupTilesAfterStitching();
                            cleanup();
                            showSuccessNotification();
                        })
                        .exceptionally(this::handleError);
            } else {
                // No annotations - proceed with consolidated dialog which will handle annotation creation
                logger.info("No annotations found in image, proceeding with consolidated dialog");

                String defaultSampleName = getDefaultSampleName();
                List<PathObject> emptyAnnotations = new ArrayList<>();

                ExistingImageAcquisitionController.showDialog(defaultSampleName, emptyAnnotations)
                        .thenCompose(this::initializeFromConfig)
                        .thenCompose(this::checkExistingSlideAlignment)
                        .thenCompose(this::routeSubWorkflow)
                        .thenCompose(this::reReadAnnotationsAfterRouting)
                        .thenCompose(this::handleRefinement)
                        .thenCompose(this::performAcquisition)
                        .thenCompose(this::waitForCompletion)
                        .thenAccept(result -> {
                            // Gate the success-only side effects (beep, ACQUISITION_COMPLETE
                            // notification, aggressive tile cleanup) on a non-null result.
                            // The chain's null-state-propagation pattern (every internal
                            // method returns completedFuture(null) on cancel/short-circuit)
                            // means we can reach this branch after the pixel-size gate, the
                            // camera-ROI gate, or any internal cancel -- without distinguishing
                            // them from real success. Without this gate the operator could
                            // hear the success beep + see ACQUISITION_COMPLETE notification
                            // after cancelling at the gate. Review finding H5. cleanup() must
                            // still run on both paths so resource leaks (preserved annotations,
                            // currentTransform singleton) are cleared either way.
                            if (result == null) {
                                logger.info("Workflow short-circuited; skipping success notification");
                                cleanup();
                                return;
                            }
                            cleanupTilesAfterStitching();
                            cleanup();
                            showSuccessNotification();
                        })
                        .exceptionally(this::handleError);
            }
        }

        /**
         * Gets all annotation class names from the current image.
         */
        private Set<String> getExistingAnnotationClasses() {
            ImageData<?> imageData = gui.getImageData();
            if (imageData == null || imageData.getHierarchy() == null) {
                return Collections.emptySet();
            }

            return imageData.getHierarchy().getAnnotationObjects().stream()
                    .filter(ann -> ann.getPathClass() != null)
                    .map(ann -> ann.getPathClass().getName())
                    .collect(Collectors.toSet());
        }

        /**
         * Counts annotations matching the selected classes.
         */
        private int countAnnotationsForClasses(List<String> selectedClasses) {
            return getAnnotationsForClasses(selectedClasses).size();
        }

        /**
         * Gets annotations matching the selected classes.
         */
        private List<PathObject> getAnnotationsForClasses(List<String> selectedClasses) {
            ImageData<?> imageData = gui.getImageData();
            if (imageData == null || imageData.getHierarchy() == null) {
                return new ArrayList<>();
            }

            return imageData.getHierarchy().getAnnotationObjects().stream()
                    .filter(ann -> ann.getPathClass() != null
                            && selectedClasses.contains(ann.getPathClass().getName()))
                    .collect(Collectors.toList());
        }

        /**
         * Ensures annotations exist for acquisition, prompting for creation if needed.
         * Shows a user-friendly dialog with options when no annotations are detected.
         */
        /**
         * Centralized post-routing annotation re-read (review finding M9). Sits after
         * {@code routeSubWorkflow} returns and before {@code handleRefinement}: nulls
         * {@code state.annotations} and re-reads against the current open entry's
         * hierarchy. The slide-specific routing branch already nulled + re-read
         * internally; the other branches relied on inner helpers
         * ({@code ExistingAlignmentPath.ensureAnnotationsForTransform},
         * {@code AnnotationHelper.ensureAnnotationsExist}). This consolidates the
         * invariant -- "after routing, annotations are always read against the
         * post-flip hierarchy" -- in one place.
         *
         * <p>Carrying annotations across a flip switch is handled upstream by
         * {@code ImageFlipHelper.mirrorAnnotationsToSibling}, which makes the
         * flipped sibling's annotation set a deterministic mirror of the base's
         * LIVE hierarchy. This method only re-reads; it does not transfer.
         */
        private CompletableFuture<WorkflowState> reReadAnnotationsAfterRouting(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);
            state.annotations = null;
            return ensureAnnotationsExist(state);
        }

        private CompletableFuture<WorkflowState> ensureAnnotationsExist(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            // If we already have annotations from the annotation dialog, skip
            if (state.annotations != null && !state.annotations.isEmpty()) {
                return CompletableFuture.completedFuture(state);
            }

            // Determine valid annotation classes
            List<String> validClasses =
                    state.selectedAnnotationClasses != null && !state.selectedAnnotationClasses.isEmpty()
                            ? state.selectedAnnotationClasses
                            : PersistentPreferences.getSelectedAnnotationClasses();

            // CRITICAL: If no annotation classes are configured, we MUST show the dialog
            // to let the user either run tissue detection or create annotations manually.
            // We should NOT search for existing annotations when validClasses is empty because:
            // 1. It would find ANY annotations (including stale ones from previous runs)
            // 2. Those annotations might be from the wrong image (original vs flipped)
            // 3. It bypasses the user's ability to configure what they want
            if (validClasses == null || validClasses.isEmpty()) {
                logger.info("No annotation classes configured - showing dialog to guide user");
                return handleNoAnnotations(state, validClasses);
            }

            // Check for existing annotations with the specified classes
            state.annotations = AnnotationHelper.getCurrentValidAnnotations(gui, validClasses);

            if (!state.annotations.isEmpty()) {
                logger.info("Found {} existing annotations", state.annotations.size());
                return CompletableFuture.completedFuture(state);
            }

            // No annotations found - show warning dialog with options
            logger.info("No annotations found, showing warning dialog");
            return handleNoAnnotations(state, validClasses);
        }

        /**
         * Handles the case when no annotations are found by showing a dialog
         * that lets the user run tissue detection and/or draw annotations
         * manually, review the results, then confirm.
         */
        private CompletableFuture<WorkflowState> handleNoAnnotations(WorkflowState state, List<String> validClasses) {
            return UIFunctions.showAnnotationWarningDialog(gui, validClasses).thenCompose(action -> {
                switch (action) {
                    case ANNOTATIONS_CONFIRMED:
                        state.annotations = AnnotationHelper.getCurrentValidAnnotations(gui, validClasses);
                        logger.info("User confirmed {} annotations", state.annotations.size());
                        return CompletableFuture.completedFuture(state);

                    case CANCEL:
                        logger.info("User cancelled workflow due to no annotations");
                        throw new CancellationException("Workflow cancelled - no annotations available");

                    default:
                        throw new RuntimeException("Unexpected annotation action: " + action);
                }
            });
        }

        /**
         * Warns when the open entry's {@code source_microscope} disagrees with the
         * active microscope. Lets the user fix the tag in place (the common case:
         * the slide is actually native to the active scope but inherited a wrong
         * scanner tag from a default-fill) or explicitly proceed with cross-scope
         * alignment.
         *
         * <p>Returns {@code true} to proceed, {@code false} when the user cancels.
         * Must be called on the JavaFX thread; the dialog uses {@code showAndWait()}.
         */
        private boolean checkAndHandleSourceMismatch() {
            if (gui.getProject() == null || gui.getImageData() == null) return true;
            @SuppressWarnings("unchecked")
            Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
            ProjectImageEntry<BufferedImage> entry = project.getEntry(gui.getImageData());
            if (entry == null) return true;
            String source = entry.getMetadata().get(ImageMetadataManager.SOURCE_MICROSCOPE);
            if (source == null || source.isBlank()) {
                // Missing source is handled by the downstream hard-cancel gate in
                // ImageFlipHelper for flip-needing scopes; nothing to warn about here.
                return true;
            }
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
            String active = (mgr != null) ? mgr.getMicroscopeName() : null;
            if (active == null || active.isBlank() || source.equals(active)) return true;

            String acquiredOn = entry.getMetadata().get(ImageMetadataManager.ACQUIRED_ON_MICROSCOPE);
            boolean inconsistentTag = active.equals(acquiredOn);

            javafx.scene.control.Alert alert =
                    new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING);
            alert.setTitle("Source microscope mismatch");
            alert.setHeaderText(
                    String.format("Image is tagged source='%s', but acquisition is on '%s'.", source, active));
            StringBuilder body = new StringBuilder();
            if (inconsistentTag) {
                body.append("acquired_on_microscope='")
                        .append(acquiredOn)
                        .append(
                                "' -- the image was acquired on this scope, so the source tag is inconsistent and should be corrected.\n\n");
            }
            body.append("Fix source to '")
                    .append(active)
                    .append("' -- treat as native (same-scope identity, no flip).\n\n");
            body.append("Proceed (cross-scope) -- keep source='")
                    .append(source)
                    .append("' and use the saved alignment for that scanner.\n\n");
            body.append("Cancel -- abort the workflow.");
            alert.setContentText(body.toString());
            javafx.scene.control.ButtonType fix = new javafx.scene.control.ButtonType("Fix source to " + active);
            javafx.scene.control.ButtonType proceed = new javafx.scene.control.ButtonType("Proceed (cross-scope)");
            javafx.scene.control.ButtonType cancel = new javafx.scene.control.ButtonType(
                    "Cancel", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
            alert.getButtonTypes().setAll(fix, proceed, cancel);
            Optional<javafx.scene.control.ButtonType> result = alert.showAndWait();
            if (result.isEmpty() || result.get() == cancel) {
                logger.info(
                        "Source mismatch dialog: user cancelled (source='{}' active='{}' acquired_on='{}')",
                        source,
                        active,
                        acquiredOn);
                return false;
            }
            if (result.get() == fix) {
                entry.getMetadata().put(ImageMetadataManager.SOURCE_MICROSCOPE, active);
                try {
                    project.syncChanges();
                    logger.info(
                            "Source mismatch dialog: stamped source_microscope='{}' on '{}' (was '{}')",
                            active,
                            entry.getImageName(),
                            source);
                } catch (Exception e) {
                    logger.warn("Source mismatch dialog: failed to sync project after fix: {}", e.getMessage());
                }
            } else {
                logger.info(
                        "Source mismatch dialog: proceeding with cross-scope source='{}' active='{}'", source, active);
            }
            return true;
        }

        /**
         * Validates prerequisites before starting.
         */
        private boolean validatePrerequisites() {
            if (gui.getImageData() == null) {
                Platform.runLater(() -> Dialogs.showErrorMessage(
                        "No Image Open", "Please open an image before starting the workflow."));
                return false;
            }

            if (!MicroscopeController.getInstance().isConnected()) {
                Platform.runLater(() ->
                        Dialogs.showErrorMessage("Not Connected", "Please connect to the microscope server first."));
                return false;
            }

            return true;
        }

        /**
         * Gets the default sample name from the current image.
         * Uses the actual image file name, not metadata which may be project name.
         */
        private String getDefaultSampleName() {
            String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
            if (imageName != null) {
                return imageName;
            }
            return "Sample_" + System.currentTimeMillis();
        }

        /**
         * Initializes workflow state from the consolidated dialog config.
         */
        private CompletableFuture<WorkflowState> initializeFromConfig(ExistingImageAcquisitionConfig config) {
            if (config == null) {
                // Phase 12: explicit cancellation so handleError is the single
                // workflow terminal, not the null-state propagation pattern. The
                // .exceptionally chain treats CancellationException as user cancel
                // (logs "Workflow cancelled by user" without an error dialog).
                return CompletableFuture.failedFuture(
                        new CancellationException("Acquisition configuration dialog cancelled"));
            }

            logger.info("Initializing workflow from consolidated config");

            // Create sample setup result
            state.sample = new SampleSetupResult(
                    config.sampleName(),
                    config.projectsFolder(),
                    config.modality(),
                    config.objective(),
                    config.detector());

            // Store alignment choice
            state.alignmentChoice = new AlignmentSelectionController.AlignmentChoice(
                    config.useExistingAlignment(),
                    config.selectedTransform(),
                    config.alignmentConfidence(),
                    false // Not auto-selected since user explicitly chose
                    );

            // Store hardware selections
            state.modality = config.modality();
            state.objective = config.objective();
            state.detector = config.detector();

            // Store refinement choice
            state.refinementChoice = convertRefinementChoice(config.refinementChoice());

            // Note: Green box params are handled by ExistingAlignmentPath, not stored here

            // Store angle + channel-intensity overrides + focus channel + AF strategy
            state.angleOverrides = config.angleOverrides();
            state.channelIntensityOverrides =
                    config.channelIntensityOverrides() == null ? Map.of() : config.channelIntensityOverrides();
            state.focusChannelId = config.focusChannelId();
            state.afStrategy = config.afStrategy();
            state.innerAxis = config.innerAxis();

            // Store whether this is an existing project
            state.isExistingProject = config.isExistingProject();

            // Store JAI camera white balance settings
            state.enableWhiteBalance = config.enableWhiteBalance();
            state.perAngleWhiteBalance = config.perAngleWhiteBalance();
            state.wbMode = config.wbMode();

            // Re-fetch annotations for the selected classes from the current image.
            // The annotation dialog loaded them earlier but they weren't passed through
            // the config record -- re-read them from the hierarchy using the stored classes.
            if (state.selectedAnnotationClasses != null
                    && !state.selectedAnnotationClasses.isEmpty()
                    && (state.annotations == null || state.annotations.isEmpty())) {
                state.annotations = AnnotationHelper.getCurrentValidAnnotations(gui, state.selectedAnnotationClasses);
                logger.info(
                        "Loaded {} annotations for selected classes: {}",
                        state.annotations.size(),
                        state.selectedAnnotationClasses);
            }

            logger.info(
                    "Config initialized: sample={}, modality={}, useExisting={}, refinement={}, wbMode={}, annotations={}",
                    config.sampleName(),
                    config.modality(),
                    config.useExistingAlignment(),
                    config.refinementChoice(),
                    config.wbMode(),
                    state.annotations != null ? state.annotations.size() : 0);

            return CompletableFuture.completedFuture(state);
        }

        /**
         * Converts the dialog's refinement choice to the workflow's enum.
         */
        private RefinementSelectionController.RefinementChoice convertRefinementChoice(RefinementChoice choice) {
            return switch (choice) {
                case NONE -> RefinementSelectionController.RefinementChoice.NONE;
                case SINGLE_TILE -> RefinementSelectionController.RefinementChoice.SINGLE_TILE;
                case FULL_MANUAL -> RefinementSelectionController.RefinementChoice.FULL_MANUAL;
            };
        }

        /**
         * Checks for existing slide-specific alignment.
         */
        private CompletableFuture<WorkflowState> checkExistingSlideAlignment(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            logger.info("Checking for existing slide-specific alignment");

            return AlignmentHelper.checkForSlideAlignment(gui, state.sample).thenApply(slideResult -> {
                if (slideResult != null) {
                    state.useExistingSlideAlignment = true;
                    // The transform is in the pixel frame of the entry the workflow runs
                    // on; used as-is, no flip bake (see processSlideSpecificAlignment).
                    state.transform = slideResult.getTransform();
                    state.alignmentConfidence = slideResult.getConfidence();
                    state.alignmentSource = slideResult.getSource();
                    logger.info(
                            "Found slide-specific alignment - confidence: {}",
                            String.format("%.2f", state.alignmentConfidence));
                    return state;
                }
                // No alignment for the active scope. Check whether one exists for a
                // *different* scope that we can route through the macro frame to here.
                tryComposeCrossScopeAlignment(state);
                if (state.crossScope) {
                    // Review finding M8: surface the cross-scope decision so the operator
                    // can opt out before tiles get created against an approximate transform.
                    if (!confirmCrossScopeAlignment(state)) {
                        // Phase 12: throw rather than return null so handleError is the
                        // single workflow terminal.
                        throw new CancellationException("User cancelled cross-scope alignment");
                    }
                    // Review finding M2: refinement on the *target* scope mis-frames the
                    // composed transform's expected pixel input (the source per-slide
                    // alignment was built in the source scope's frame, not this one).
                    // Auto-downgrade so single-tile / full-manual can't silently corrupt
                    // a freshly composed transform. The user is told in the confirmation
                    // dialog and can run Microscope Alignment afterwards for a native
                    // target-scope alignment that future acquisitions can reuse without
                    // composition.
                    if (state.refinementChoice != null
                            && state.refinementChoice != RefinementSelectionController.RefinementChoice.NONE) {
                        logger.info(
                                "Auto-downgrading refinement from {} to NONE for cross-scope acquisition",
                                state.refinementChoice);
                        state.refinementChoice = RefinementSelectionController.RefinementChoice.NONE;
                    }
                }
                return state;
            });
        }

        /**
         * Modal Continue / Cancel confirmation for a freshly composed cross-scope
         * alignment (review finding M8). Returns {@code true} when the user chose to
         * proceed, {@code false} on cancel. Delegates to the shared
         * {@link AlignmentHelper#confirmContinueDialog(String, String, String)}.
         */
        private boolean confirmCrossScopeAlignment(WorkflowState state) {
            String title = "Cross-Scope Alignment Composed";
            String header = "Reusing an alignment from another microscope";
            StringBuilder body = new StringBuilder();
            body.append("No per-slide alignment exists for this microscope.\n");
            body.append("An alignment from a different microscope has been composed\n");
            body.append("through the shared macro frame:\n\n");
            body.append("  ").append(state.alignmentSource).append("\n\n");
            body.append("Cross-scope alignment is approximate. Linear scale and\n");
            body.append("rotation are preserved, but per-tile accuracy depends on\n");
            body.append("how closely both scopes agree on the macro frame.\n\n");
            body.append("Single-tile and full manual refinement are disabled for\n");
            body.append("cross-scope acquisitions -- refinement on the target scope\n");
            body.append("would mis-frame the composed transform.\n\n");
            body.append("Recommended: after acquisition, run Microscope Alignment on\n");
            body.append("this slide to build a native target-scope alignment that\n");
            body.append("future acquisitions can reuse without composition.\n\n");
            body.append("Continue with the cross-scope alignment?");
            return AlignmentHelper.confirmContinueDialog(title, header, body.toString());
        }

        /**
         * Cross-scope path: when no per-slide alignment exists for the active microscope
         * but one was built for another microscope on the same sample, attempt to compose
         * a {@code pixel -> activeStage} transform via the shared macro frame.
         *
         * <p>Mutates {@code state}: on success, sets {@link WorkflowState#useExistingSlideAlignment},
         * {@link WorkflowState#transform}, {@link WorkflowState#crossScope}, and the
         * source/target microscope diagnostics. On failure, leaves state untouched.
         */
        private void tryComposeCrossScopeAlignment(WorkflowState state) {
            try {
                String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                if (configPath == null || configPath.isEmpty()) {
                    return;
                }
                String activeMicroscope =
                        MicroscopeConfigManager.getInstance(configPath).getMicroscopeName();
                if (activeMicroscope == null || activeMicroscope.isEmpty() || "Unknown".equals(activeMicroscope)) {
                    return;
                }
                Project<BufferedImage> project = gui.getProject();
                if (project == null || project.getPath() == null) {
                    return;
                }
                String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
                if (imageName == null) {
                    return;
                }
                // Resolve to the parent macro entry name via base_image metadata so a sub-image
                // entry's name doesn't get used to pull in unrelated sub-image alignments.
                String lookupKey = AlignmentHelper.resolveMacroLookupKey(project, gui.getImageData(), imageName);
                java.io.File projectDir = project.getPath().toFile().getParentFile();
                List<AffineTransformManager.SlideAlignmentRecord> records =
                        AffineTransformManager.loadAllSlideAlignmentsFromDirectory(projectDir, lookupKey);
                if (records.isEmpty()) {
                    return;
                }

                // Deterministic candidate order: newest file wins on ties (review finding M7).
                // The original code iterated whatever order the filesystem returned, so two
                // operators on the same project could see different cross-scope compositions
                // chosen. Sort by lastModified desc and log the sorted list so field
                // diagnostics can confirm which candidate won.
                records = new ArrayList<>(records);
                records.sort((a, b) ->
                        Long.compare(b.getFile().lastModified(), a.getFile().lastModified()));
                if (logger.isInfoEnabled()) {
                    StringBuilder sb = new StringBuilder("Cross-scope candidates (newest first):");
                    for (AffineTransformManager.SlideAlignmentRecord r : records) {
                        sb.append("\n  ")
                                .append(r.getFile().getName())
                                .append(" (scope=")
                                .append(r.getMicroscope())
                                .append(", pixelFrame=")
                                .append(r.getPixelFrame())
                                .append(", lastModified=")
                                .append(r.getFile().lastModified())
                                .append(")");
                    }
                    logger.info(sb.toString());
                }

                AffineTransformManager mgr = new AffineTransformManager(new java.io.File(configPath).getParent());

                // Phase 11: count cross-scope candidates considered so we can surface a
                // non-modal info dialog when records existed but none could compose to
                // the active scope. Pairs with Phase 6's M8 success dialog.
                int crossScopeCandidatesConsidered = 0;
                for (AffineTransformManager.SlideAlignmentRecord record : records) {
                    String sourceMicroscope = record.getMicroscope();
                    if (sourceMicroscope == null || activeMicroscope.equals(sourceMicroscope)) {
                        continue;
                    }
                    crossScopeCandidatesConsidered++;
                    // Cross-scope composition assumes the source record is in macro pixel
                    // coords -- composing a sub-frame transform with a macro-frame preset
                    // produces the 2026-05-10 MH_Colon shrunk-grid class. The directory
                    // scanner already separates derived/ from flat alignmentFiles/, so
                    // this gate is defense-in-depth against hand-edited or hand-placed
                    // sub-frame files in the flat directory (review finding H4).
                    if (!AffineTransformManager.PIXEL_FRAME_MACRO.equals(record.getPixelFrame())) {
                        logger.warn(
                                "Skipping cross-scope candidate {}: pixelFrame={} (need 'macro')",
                                record.getFile().getName(),
                                record.getPixelFrame());
                        continue;
                    }
                    // Find a (sourceScanner) shared by a preset on the active scope AND a preset
                    // on the source scope. The macro source must agree -- the composition routes
                    // pixels through the shared macro frame.
                    List<String> targetScanners = mgr.getDistinctSourceScannersForMicroscope(activeMicroscope);
                    for (String scanner : targetScanners) {
                        AffineTransformManager.TransformPreset tgtPreset =
                                mgr.getBestPresetForPair(scanner, activeMicroscope);
                        AffineTransformManager.TransformPreset srcPreset =
                                mgr.getBestPresetForPair(scanner, sourceMicroscope);
                        if (tgtPreset == null || srcPreset == null) continue;
                        if (!tgtPreset.hasFlipState() || !srcPreset.hasFlipState()) continue;
                        try {
                            AffineTransform composed =
                                    CrossScopeTransformBuilder.compose(record.getTransform(), srcPreset, tgtPreset);
                            state.useExistingSlideAlignment = true;
                            state.transform = composed;
                            state.crossScope = true;
                            state.crossScopeSourceMicroscope = sourceMicroscope;
                            state.alignmentConfidence = 0.7;
                            state.alignmentSource = String.format(
                                    "Cross-scope %s -> %s via %s (src=%s, tgt=%s)",
                                    sourceMicroscope,
                                    activeMicroscope,
                                    scanner,
                                    srcPreset.getName(),
                                    tgtPreset.getName());
                            logger.info(
                                    "Cross-scope alignment composed: {} -> {} via {} (source per-slide file: {})",
                                    sourceMicroscope,
                                    activeMicroscope,
                                    scanner,
                                    record.getFile().getName());
                            return;
                        } catch (Exception e) {
                            logger.warn(
                                    "Cross-scope compose failed for src='{}' tgt='{}' scanner='{}': {}",
                                    srcPreset.getName(),
                                    tgtPreset.getName(),
                                    scanner,
                                    e.getMessage());
                        }
                    }
                }
                // Phase 11: non-modal info dialog when records existed for other scopes but
                // none could compose. Pairs with Phase 6's M8 success dialog. Skipped when
                // there were zero cross-scope candidates -- silence is correct there
                // (this is the common "fresh project on this scope" case).
                if (crossScopeCandidatesConsidered > 0 && !state.crossScope) {
                    final int considered = crossScopeCandidatesConsidered;
                    Platform.runLater(() -> Dialogs.showInfoNotification(
                            "No Cross-Scope Alignment Available",
                            considered
                                    + " per-slide alignment(s) from other microscopes were "
                                    + "considered, but none could be composed through a shared "
                                    + "scanner preset to the active scope. Manual alignment will "
                                    + "be required."));
                }
            } catch (Exception e) {
                logger.warn("Cross-scope alignment probe failed: {}", e.getMessage(), e);
            }
        }

        /**
         * Routes to the appropriate sub-workflow based on selections.
         * All paths delegate to the existing working implementations.
         *
         * <p>When a slide-specific alignment exists (including previously refined
         * alignments), we always use it as the starting point -- unless the user
         * explicitly requested a full manual re-alignment. This ensures that
         * single-tile refinement builds on top of the saved refined transform
         * instead of discarding it and recomputing from green-box detection.</p>
         */
        private CompletableFuture<WorkflowState> routeSubWorkflow(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            // Sub-acquisitions take priority over slide-specific alignment. Their
            // pixel coords are in the camera's pixel frame, while the slide-specific
            // alignment (resolved via base_image since 18a800d) is the parent
            // macro's transform in macro pixel coords. Applying the macro transform
            // to sub-image annotation coords shrinks every stage move by
            // camera_px / macro_px (the symptom class of the 2026-05-10 MH_Colon
            // incident). Sub-image annotations always go through offset-based
            // targeting, which builds a fresh sub-image-pixel -> stage transform
            // from xy_offset + pixel size.
            if (isSubAcquisition()) {
                logger.info("Routing to sub-acquisition offset-based targeting path");
                return processSubAcquisitionPath(state);
            }

            // If we have a slide-specific alignment (including previously refined ones),
            // use it directly -- unless the user wants a full manual re-alignment.
            // SINGLE_TILE refinement is handled later in handleRefinement() and needs
            // the saved transform as its starting point.
            if (state.useExistingSlideAlignment
                    && state.refinementChoice != RefinementSelectionController.RefinementChoice.FULL_MANUAL) {
                logger.info(
                        "Using slide-specific alignment (refinement={}) - delegating to existing workflow logic",
                        state.refinementChoice);
                return processSlideSpecificAlignment(state);
            }

            // Route based on alignment choice - both delegate to working implementations
            if (state.alignmentChoice != null && state.alignmentChoice.useExistingAlignment()) {
                logger.info("Routing to existing alignment path");
                return processExistingAlignmentPath(state);
            } else {
                logger.info("Routing to manual alignment path");
                return processManualAlignmentPath(state);
            }
        }

        /**
         * Processes slide-specific alignment (fast path when alignment already exists).
         * Reuses the working workflow logic for proper image validation and annotation handling.
         */
        private CompletableFuture<WorkflowState> processSlideSpecificAlignment(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            logger.info("Processing slide-specific alignment with existing transform");

            // M11 -- setCurrentTransform is intentionally deferred until after
            // validateAndFlipIfNeeded succeeds (see the .thenApply below). The previous
            // location installed it before project setup / flip validation, leaving a
            // window in which a Live Viewer click could read an unvalidated transform.
            // Phase 2 H6 clears the singleton on cleanup, but H6 + M11 together close
            // both ends of the window (install only after validation passes; clear on
            // any cleanup or error).

            // Delegate to ProjectHelper for proper project setup
            return ProjectHelper.setupProject(gui, state.sample, state.annotationPreservation)
                    .thenCompose(projectInfo -> {
                        if (projectInfo == null) {
                            throw new RuntimeException("Project setup failed");
                        }
                        state.projectInfo = projectInfo;

                        // Validate and flip image if needed (important for correct coordinates).
                        // Pass the active preset's flip state explicitly -- the 3-arg overload
                        // calls FlipResolver(null,null,null) which defaults to (false,false), so
                        // the workflow would never auto-open or create the flipped duplicate even
                        // when the saved alignment was built in a flipped frame. Observed on OWS3
                        // 2026-05-01: acquisition ran on the unflipped base while the saved
                        // transform was built in the flipped-X frame, sending the stage to the
                        // X-mirror of the intended location.
                        @SuppressWarnings("unchecked")
                        Project<BufferedImage> project = (Project<BufferedImage>) projectInfo.getCurrentProject();
                        if (state.crossScope) {
                            // Cross-scope: the composed transform consumes pixel coords in the
                            // open entry's current frame (the source per-slide alignment was built
                            // against this same entry on the source microscope). Flipping to match
                            // the *target* preset would mis-frame the composed transform's input.
                            logger.info(
                                    "Cross-scope alignment in use ({}); skipping flip swap and preset-frame verify",
                                    state.alignmentSource);
                            return CompletableFuture.completedFuture(true);
                        }
                        AffineTransformManager.TransformPreset presetForFlip =
                                state.alignmentChoice != null ? state.alignmentChoice.selectedTransform() : null;
                        boolean requiresFlipX = FlipResolver.resolveFlipX(null, presetForFlip, null);
                        boolean requiresFlipY = FlipResolver.resolveFlipY(null, presetForFlip, null);
                        String sampleNameForFlip = state.sample != null ? state.sample.sampleName() : null;
                        return ImageFlipHelper.validateAndFlipIfNeeded(
                                        gui, project, sampleNameForFlip, requiresFlipX, requiresFlipY)
                                .thenApply(validated -> {
                                    // Post-swap verification: confirm the open entry's pixel frame
                                    // actually matches the saved alignment's expected frame. The saved
                                    // per-slide fullRes->stage transform was built in the flip frame
                                    // of whatever entry was open at save time; running acquisition
                                    // from a mismatched frame produces an X-mirrored stage target
                                    // (verified 2026-05-02: Go-to-Centroid on the unflipped entry
                                    // lands at the X-mirror of where the same annotation lands on
                                    // the flipped duplicate, and acquisition follows the same wrong
                                    // path). validateAndFlipIfNeeded is supposed to swap to the
                                    // flipped entry but the swap can race the worker thread that
                                    // reads gui.getImageData() downstream.
                                    if (validated) {
                                        // No flip bake. The per-slide alignment JSON stores the
                                        // transform in the pixel frame of the entry the workflow
                                        // runs on -- the flipped sibling for flip-needing scopes,
                                        // the base otherwise -- because saveRefinedAlignment /
                                        // ManualAlignmentPath / ExistingAlignmentPath all write it
                                        // back from that same entry. validateAndFlipIfNeeded has
                                        // just put us on that entry, so the loaded transform is
                                        // used as-is. Baking a flip delta here double-flipped a
                                        // correct transform and drove the stage to the X/Y mirror
                                        // (PPM 2026-05-19: tile selected lower-left, stage jumped
                                        // upper-right). The Stage Map / Go-to-Centroid path also
                                        // uses this transform raw for these JSONs.
                                        // M11 -- install the transform only after validation passes.
                                        MicroscopeController.getInstance().setCurrentTransform(state.transform);
                                    }
                                    return validated;
                                });
                    })
                    .thenCompose(validated -> {
                        if (!validated) {
                            throw new RuntimeException("Image validation failed");
                        }

                        // Get pixel size from preferences (macro pixel size for annotation creation)
                        state.pixelSize = getPixelSizeFromPreferences();

                        // Use selected classes or preferences
                        state.selectedAnnotationClasses =
                                (state.selectedAnnotationClasses != null && !state.selectedAnnotationClasses.isEmpty())
                                        ? state.selectedAnnotationClasses
                                        : PersistentPreferences.getSelectedAnnotationClasses();

                        // CRITICAL: clear any annotations cached from the config dialog. Those
                        // were captured against the UNFLIPPED base entry (state.annotations is
                        // populated at config-dialog return, before validateAndFlipIfNeeded
                        // switches the open entry to the flipped sibling). If we left them in
                        // place, ensureAnnotationsExist short-circuits on `state.annotations !=
                        // null && !isEmpty`, and downstream tile creation reads ROI bounds from
                        // PathObjects that belong to the unflipped base's hierarchy. The
                        // resulting tiles get added to the flipped sibling's hierarchy at
                        // unflipped-frame coordinates -- i.e. at the XY-mirror of where the
                        // (correctly flipped) annotations actually are. User-visible symptom:
                        // "annotations in the right place, tiles somewhere else, not near
                        // the annotations." Force the re-read against the current hierarchy.
                        state.annotations = null;

                        // Use the new dialog-based annotation handling (non-blocking)
                        return ensureAnnotationsExist(state);
                    })
                    .thenApply(finalState -> {
                        logger.info(
                                "Slide-specific alignment ready with {} annotations", finalState.annotations.size());
                        return finalState;
                    });
        }

        /**
         * Gets pixel size from preferences (macro image pixel size).
         * Reuses the same logic as the working workflow.
         */
        private double getPixelSizeFromPreferences() {
            String pixelSizeStr = PersistentPreferences.getMacroImagePixelSizeInMicrons();

            if (pixelSizeStr == null || pixelSizeStr.trim().isEmpty()) {
                logger.error("Macro image pixel size is not configured in preferences");
                throw new IllegalStateException("Macro image pixel size is not configured.\n"
                        + "This value must be set before running the workflow.");
            }

            try {
                double pixelSize = Double.parseDouble(pixelSizeStr.trim());
                if (pixelSize <= 0) {
                    throw new IllegalStateException("Invalid macro image pixel size: " + pixelSize);
                }
                logger.debug("Using macro pixel size from preferences: {} um", pixelSize);
                return pixelSize;
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Invalid macro image pixel size format: '" + pixelSizeStr + "'");
            }
        }

        /**
         * Processes the existing alignment path (green box detection, transform creation, etc.).
         * Delegates to the existing working ExistingAlignmentPath class.
         */
        private CompletableFuture<WorkflowState> processExistingAlignmentPath(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            logger.info("Delegating to ExistingAlignmentPath for transform pipeline");

            // Delegate to the existing working implementation
            return new ExistingAlignmentPath(gui, state).execute().thenApply(legacyState -> {
                if (legacyState == null) {
                    // ExistingAlignmentPath returned null (e.g. no macro image, user cancelled).
                    // Phase 12: explicit cancellation routes through handleError instead of
                    // propagating null through the chain.
                    throw new CancellationException("ExistingAlignmentPath cancelled (no macro / user cancel)");
                }
                // Copy back relevant state from the working implementation
                state.transform = legacyState.transform;
                state.annotations = legacyState.annotations;
                state.projectInfo = legacyState.projectInfo;
                state.pixelSize = legacyState.pixelSize;
                return state;
            });
        }

        /**
         * Processes the manual alignment path.
         * Delegates to the existing working ManualAlignmentPath class.
         */
        private CompletableFuture<WorkflowState> processManualAlignmentPath(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            logger.info("Delegating to ManualAlignmentPath for alignment");

            // Delegate to the existing working implementation
            return new ManualAlignmentPath(gui, state).execute().thenApply(legacyState -> {
                // Copy back relevant state
                state.transform = legacyState.transform;
                state.annotations = legacyState.annotations;
                state.projectInfo = legacyState.projectInfo;
                state.pixelSize = legacyState.pixelSize;
                return state;
            });
        }

        /**
         * Checks whether the current image is a sub-acquisition (derived from a parent
         * image with known stage coordinates). Sub-acquisitions have xy_offset metadata
         * and a base_image that differs from their own name.
         */
        @SuppressWarnings("unchecked")
        private boolean isSubAcquisition() {
            if (gui.getProject() == null || gui.getImageData() == null) {
                return false;
            }

            Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
            ProjectImageEntry<BufferedImage> entry = project.getEntry(gui.getImageData());
            if (entry == null) {
                return false;
            }

            // Must have non-zero xy_offset
            double[] offset = ImageMetadataManager.getXYOffset(entry);
            if (offset[0] == 0 && offset[1] == 0) {
                return false;
            }

            // base_image must differ from own name (i.e., this is derived, not the root)
            String baseImage = ImageMetadataManager.getBaseImage(entry);
            if (baseImage == null || baseImage.isEmpty()) {
                return false;
            }

            String ownName = GeneralTools.stripExtension(entry.getImageName());
            return !baseImage.equals(ownName);
        }

        /**
         * Processes a sub-acquisition image using offset-based targeting.
         *
         * <p>Sub-acquisitions already know their physical stage position (stored as
         * xy_offset metadata). The pixel-to-stage transform is computed directly from:
         * <ul>
         *   <li>xy_offset (annotation top-left in stage micrometers)</li>
         *   <li>pixel size (from image calibration)</li>
         *   <li>half-FOV correction (tile grid starts half a FOV before annotation edge)</li>
         *   <li>flip correction (if image is displayed flipped via TransformedServer)</li>
         * </ul>
         *
         * <p>This bypasses the macro image / green box detection pipeline entirely,
         * enabling acquisition from any depth of sub-acquisition chain (A -> B -> C -> D).
         */
        private CompletableFuture<WorkflowState> processSubAcquisitionPath(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            // Phase 11: catch missing sample early with a clear message rather than
            // letting setupProject NPE on state.sample.projectsFolder(). The check
            // is defensive -- initializeFromConfig populates state.sample -- but the
            // resulting error is much clearer than the downstream NPE.
            if (state.sample == null) {
                CompletableFuture<WorkflowState> failed = new CompletableFuture<>();
                failed.completeExceptionally(
                        new IllegalStateException("processSubAcquisitionPath: state.sample is null -- "
                                + "initializeFromConfig did not run or returned null state. "
                                + "This is a workflow-chain bug, not a user error."));
                return failed;
            }

            logger.info("Processing sub-acquisition with offset-based targeting");

            // Read metadata from current entry BEFORE project setup / flip validation,
            // since those steps may switch to a different entry (TransformedServer).
            @SuppressWarnings("unchecked")
            Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
            ProjectImageEntry<BufferedImage> entry = project.getEntry(gui.getImageData());

            // Cross-scope sub-image gate (review finding H3). The sub-image's xy_offset
            // is in the ACQUIRING scope's stage frame -- meaningless on any other
            // microscope. Refuse the acquisition before any stage motion. Falls back
            // to the derived alignment JSON's filename when the per-entry field is
            // missing (legacy sub-images acquired before 2026-05-14).
            String acquiredOn = ImageMetadataManager.getAcquiredOnMicroscope(entry);
            if (acquiredOn == null || acquiredOn.isEmpty()) {
                String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
                if (imageName != null) {
                    acquiredOn = AffineTransformManager.getDerivedAlignmentMicroscope(project, imageName);
                }
            }
            String activeMicroscope = null;
            try {
                MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
                if (mgr != null) {
                    String name = mgr.getMicroscopeName();
                    if (name != null && !name.isEmpty() && !"Unknown".equals(name)) {
                        activeMicroscope = name;
                    }
                }
            } catch (Exception ignore) {
            }
            if (acquiredOn != null && activeMicroscope != null && !acquiredOn.equals(activeMicroscope)) {
                logger.error(
                        "Refusing sub-image acquisition: entry acquired on '{}' but active scope is '{}'",
                        acquiredOn,
                        activeMicroscope);
                showSubImageCrossScopeMismatchDialog(acquiredOn, activeMicroscope, entry.getImageName());
                CompletableFuture<WorkflowState> cancelled = new CompletableFuture<>();
                cancelled.completeExceptionally(
                        new CancellationException("Sub-image acquired on a different microscope"));
                return cancelled;
            }
            if (acquiredOn == null) {
                logger.warn(
                        "Sub-image cross-scope gate: open entry '{}' has no acquired_on_microscope metadata "
                                + "and no derived alignment JSON exposing one; proceeding without the gate.",
                        entry.getImageName());
            }

            double[] xyOffset = ImageMetadataManager.getXYOffset(entry);
            boolean flipX = ImageMetadataManager.isFlippedX(entry);
            boolean flipY = ImageMetadataManager.isFlippedY(entry);
            String entryModality = entry.getMetadata().get(ImageMetadataManager.MODALITY);
            String entryObjective = entry.getMetadata().get(ImageMetadataManager.OBJECTIVE);
            String entryDetector = ImageMetadataManager.getDetectorId(entry);

            logger.info(
                    "Sub-acquisition metadata: offset=({}, {}), flip=({}, {}), modality={}, objective={}, detector={}",
                    xyOffset[0],
                    xyOffset[1],
                    flipX,
                    flipY,
                    entryModality,
                    entryObjective,
                    entryDetector);

            // M5 -- missing entry-objective is a hard cancel. buildOffsetBasedTransform
            // falls back to MicroscopeController.getCameraFOV() (live MM state) when the
            // entry's objective metadata is null, silently producing a half-FOV correction
            // based on whatever objective MM happens to be on -- which may not match the
            // saved sub-image. Refuse the acquisition rather than risk a half-FOV shift.
            if (entryObjective == null || entryObjective.isEmpty()) {
                logger.error(
                        "Refusing sub-image acquisition: entry '{}' has no 'objective' metadata; "
                                + "cannot compute the half-FOV correction safely",
                        entry.getImageName());
                showMissingEntryObjectiveDialog(entry.getImageName());
                CompletableFuture<WorkflowState> cancelled = new CompletableFuture<>();
                cancelled.completeExceptionally(
                        new CancellationException("Sub-image entry missing objective metadata"));
                return cancelled;
            }

            // M4 -- entry-objective vs wizard-objective advisory. The entry's objective
            // drives the half-FOV correction in buildOffsetBasedTransform; the wizard's
            // objective drives the tile grid. A mismatch shifts tiles by half the
            // FOV-delta with no warning. Continue/Cancel advisory before any stage motion.
            if (state.objective != null
                    && !state.objective.equals(entryObjective)
                    && !confirmContinueWithEntryObjectiveMismatch(
                            entry.getImageName(), entryObjective, state.objective)) {
                logger.info("User cancelled at sub-image entry-objective mismatch advisory");
                CompletableFuture<WorkflowState> cancelled = new CompletableFuture<>();
                cancelled.completeExceptionally(new CancellationException("Sub-image entry-objective mismatch"));
                return cancelled;
            }

            // Delegate to ProjectHelper for proper project setup
            return ProjectHelper.setupProject(gui, state.sample, state.annotationPreservation)
                    .thenApply(projectInfo -> {
                        if (projectInfo == null) {
                            throw new RuntimeException("Project setup failed");
                        }
                        state.projectInfo = projectInfo;
                        return state;
                    })
                    .thenCompose(s -> {
                        // No validateAndFlipIfNeeded for sub-images: the sub-image
                        // pyramid is already in the active scope's stage-aligned
                        // frame (the stitcher baked any flip into TileConfiguration),
                        // and sub-image entries do not have flipped siblings. The
                        // (source_scanner, target_microscope) preset flip is a
                        // property of the parent macro -- not of the sub-image's
                        // own pixel coordinates. The flipX/flipY metadata read
                        // above is the sub-image's stage-relative orientation and
                        // is folded into the offset-based transform below.
                        AffineTransform transform = buildOffsetBasedTransform(
                                xyOffset, flipX, flipY, entryModality, entryObjective, entryDetector);

                        state.transform = transform;
                        MicroscopeController.getInstance().setCurrentTransform(transform);
                        logger.info("Offset-based transform created for sub-acquisition");

                        state.pixelSize = getPixelSizeFromPreferences();

                        // Use selected classes or preferences
                        state.selectedAnnotationClasses =
                                (state.selectedAnnotationClasses != null && !state.selectedAnnotationClasses.isEmpty())
                                        ? state.selectedAnnotationClasses
                                        : PersistentPreferences.getSelectedAnnotationClasses();

                        return ensureAnnotationsExist(state);
                    })
                    .thenApply(finalState -> {
                        logger.info("Sub-acquisition path ready with {} annotations", finalState.annotations.size());
                        return finalState;
                    });
        }

        /**
         * Builds an AffineTransform that maps the current image's pixel coordinates
         * to stage micrometers, using the sub-acquisition's offset metadata.
         *
         * <p>The transform accounts for:
         * <ul>
         *   <li>Image origin = xy_offset - half_FOV (tile grid starts half a FOV before annotation edge)</li>
         *   <li>Pixel scaling from image calibration</li>
         *   <li>Flip correction when the image is displayed via TransformedServer</li>
         * </ul>
         */
        private AffineTransform buildOffsetBasedTransform(
                double[] xyOffset, boolean flipX, boolean flipY, String modality, String objective, String detector) {

            double pixelSize =
                    gui.getImageData().getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
            int width = gui.getImageData().getServer().getWidth();
            int height = gui.getImageData().getServer().getHeight();

            if (Double.isNaN(pixelSize) || pixelSize <= 0) {
                throw new IllegalStateException("Image has no valid pixel calibration");
            }

            logger.info("Building offset-based transform: pixelSize={} um, image={}x{}", pixelSize, width, height);

            // Get half-FOV correction.
            // xy_offset is the annotation top-left in stage microns, but the tile grid
            // (and thus the stitched image origin) starts half a FOV before the annotation edge.
            // Prefer config-based FOV (uses the metadata's objective/detector) over live socket
            // query (which returns the CURRENT hardware state, which may differ).
            double halfFovX = 0;
            double halfFovY = 0;
            try {
                MicroscopeController mc = MicroscopeController.getInstance();
                if (mc != null && modality != null && objective != null && detector != null) {
                    double[] fov = mc.getCameraFOVFromConfig(modality, objective, detector);
                    halfFovX = fov[0] / 2.0;
                    halfFovY = fov[1] / 2.0;
                    logger.info("Half-FOV correction from config: ({}, {}) um", halfFovX, halfFovY);
                } else if (mc != null && mc.isConnected()) {
                    double[] fov = mc.getCameraFOV();
                    halfFovX = fov[0] / 2.0;
                    halfFovY = fov[1] / 2.0;
                    logger.info("Half-FOV correction from socket: ({}, {}) um", halfFovX, halfFovY);
                }
            } catch (Exception e) {
                logger.warn("Could not get FOV for half-FOV correction: {}", e.getMessage());
                logger.warn("Offset-based transform will use raw xy_offset without half-FOV adjustment");
            }

            double imageOriginX = xyOffset[0] - halfFovX;
            double imageOriginY = xyOffset[1] - halfFovY;

            logger.info(
                    "Image origin in stage coords: ({}, {}) um  [offset ({}, {}) - halfFOV ({}, {})]",
                    imageOriginX,
                    imageOriginY,
                    xyOffset[0],
                    xyOffset[1],
                    halfFovX,
                    halfFovY);

            // Build transform: pixel -> stage
            // translate(imageOrigin) * scale(pixelSize) maps unflipped pixels to stage
            AffineTransform pixelToStage = new AffineTransform();
            pixelToStage.translate(imageOriginX, imageOriginY);
            pixelToStage.scale(pixelSize, pixelSize);

            // If the image is flipped (displayed via TransformedServer after validateAndFlipImage),
            // pixel coordinates are in flipped space. Concatenate unflip to map back to original
            // pixel space before applying the scale + translate.
            if (flipX || flipY) {
                AffineTransform unflip = ForwardPropagationWorkflow.createFlip(flipX, flipY, width, height);
                pixelToStage.concatenate(unflip);
                logger.info("Applied flip correction: flipX={}, flipY={}", flipX, flipY);
            }

            // Validate: transform image corners and log for debugging
            double[] topLeft = {0, 0};
            double[] bottomRight = {width, height};
            double[] stageTopLeft = new double[2];
            double[] stageBottomRight = new double[2];
            pixelToStage.transform(topLeft, 0, stageTopLeft, 0, 1);
            pixelToStage.transform(bottomRight, 0, stageBottomRight, 0, 1);
            logger.info("Transform validation: pixel(0,0) -> stage({}, {})", stageTopLeft[0], stageTopLeft[1]);
            logger.info(
                    "Transform validation: pixel({},{}) -> stage({}, {})",
                    width,
                    height,
                    stageBottomRight[0],
                    stageBottomRight[1]);

            return pixelToStage;
        }

        /**
         * Handles refinement based on user's choice.
         *
         * <p>Gates pixel-size + camera-ROI validation here, before any refinement work
         * (tile creation, stage motion). Refinement allocates tiles using the wizard's
         * objective/detector FOV; if MicroManager is on a different objective, the FOV is
         * wrong and {@link qupath.ext.qpsc.utilities.TilingUtilities} throws an opaque
         * "too many tiles" error 60+ seconds in. The wizard now runs the same check at
         * launch time, but this gate stays as defense-in-depth for cases where the wizard
         * gate could not run (e.g. MM not connected at wizard time, or MM state changed
         * between wizard and refinement). The pre-acquisition gate further downstream is
         * a third layer of defense for state that changes during refinement.
         */
        private CompletableFuture<WorkflowState> handleRefinement(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            if (!validateMMAgainstSelection(state)) {
                logger.warn("Pixel-size or camera-ROI validation failed before refinement; cancelling workflow");
                // Phase 12: explicit cancellation -- the validate dialog already informed
                // the user, so handleError just logs "Workflow cancelled by user" and runs
                // cleanup.
                return CompletableFuture.failedFuture(
                        new CancellationException("Pre-refinement MM/wizard validation failed"));
            }

            switch (state.refinementChoice) {
                case NONE:
                    logger.info("Proceeding without refinement");
                    return CompletableFuture.completedFuture(state);

                case SINGLE_TILE:
                    logger.info("Performing single-tile refinement");
                    return performSingleTileRefinement(state);

                case FULL_MANUAL:
                    logger.info("Full manual alignment requested - switching to manual path");
                    return processManualAlignmentPath(state);

                default:
                    return CompletableFuture.completedFuture(state);
            }
        }

        /**
         * Verifies the wizard's objective/detector still match MicroManager. Returns false if a
         * mismatch dialog was shown and the workflow should cancel; true to proceed. Errors during
         * the check are logged as warnings and treated as pass-through -- the workflow has further
         * gates downstream.
         */
        private boolean validateMMAgainstSelection(WorkflowState state) {
            try {
                String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
                double configPixelSize = configManager.getPixelSize(state.objective, state.detector);
                if (!QPScopeChecks.validateObjectivePixelSize(
                        state.objective, state.detector, state.modality, configPixelSize)) {
                    return false;
                }
                if (!QPScopeChecks.validateCameraRoi(state.detector)) {
                    return false;
                }
                return true;
            } catch (IllegalArgumentException e) {
                // Phase 11: configuration error (e.g. missing objective in YAML, bad detector
                // key) -- the previous catch (Exception) swallowed these and let the workflow
                // proceed against a misconfigured MM/wizard combination, surfacing the failure
                // 60+ seconds later inside TilingUtilities. Propagate as a workflow cancellation
                // so the user sees a clear dialog at the gate.
                logger.error("Configuration error during MM/wizard validation: {}", e.getMessage());
                throw new IllegalStateException(
                        "Configuration error during MM/wizard validation: " + e.getMessage(), e);
            } catch (Exception e) {
                // Transient failures (socket I/O, MM not connected). Log and pass-through;
                // downstream gates run again before stage motion.
                logger.warn("Could not validate MM state against wizard selection: {}", e.getMessage());
                return true;
            }
        }

        /**
         * Performs single-tile refinement.
         *
         * <p>The viewer's open entry can drift back to the unflipped base
         * between routing and refinement. Observed on PPM 2026-05-20: the slow
         * white-background data-bounds classifier (~20 s) saturates the FX
         * thread, reordering the queued entry switch so the base is the open
         * entry when refinement begins and the flipped sibling only commits
         * afterwards. Tiles created while the base is open land on the base
         * entry's hierarchy and are invisible once the viewer settles on the
         * flipped sibling -- the tile-select dialog then shows zero tiles
         * (user-visible symptom: "it did not create any tiles"). So before
         * creating tiles we re-assert the flipped sibling as the open entry,
         * then re-read the annotations from it. {@code validateAndFlipIfNeeded}
         * is idempotent: a no-op when the sibling is already open.
         */
        private CompletableFuture<WorkflowState> performSingleTileRefinement(WorkflowState state) {
            if (state.annotations == null || state.annotations.isEmpty()) {
                logger.warn("No annotations available for refinement");
                return CompletableFuture.completedFuture(state);
            }

            return reassertFlippedSiblingForRefinement(state)
                    .thenCompose(this::reReadAnnotationsAfterRouting)
                    .thenCompose(this::createRefinementTilesAndRun);
        }

        /**
         * Re-asserts that the flipped sibling (when the active preset requires
         * one) is the open viewer entry, so refinement tile creation and the
         * tile-select dialog operate on the same hierarchy. No-op for
         * cross-scope alignments (their transform runs in the open entry's
         * frame), when no project is set up, or when the preset needs no flip.
         */
        private CompletableFuture<WorkflowState> reassertFlippedSiblingForRefinement(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);
            if (state.crossScope || state.projectInfo == null) {
                return CompletableFuture.completedFuture(state);
            }
            @SuppressWarnings("unchecked")
            Project<BufferedImage> project = (Project<BufferedImage>) state.projectInfo.getCurrentProject();
            if (project == null) {
                return CompletableFuture.completedFuture(state);
            }
            AffineTransformManager.TransformPreset presetForFlip =
                    state.alignmentChoice != null ? state.alignmentChoice.selectedTransform() : null;
            boolean requiresFlipX = FlipResolver.resolveFlipX(null, presetForFlip, null);
            boolean requiresFlipY = FlipResolver.resolveFlipY(null, presetForFlip, null);
            if (!requiresFlipX && !requiresFlipY) {
                return CompletableFuture.completedFuture(state);
            }
            String sampleNameForFlip = state.sample != null ? state.sample.sampleName() : null;
            logger.info("Re-asserting flipped sibling as open entry before refinement tile creation");
            return ImageFlipHelper.validateAndFlipIfNeeded(
                            gui, project, sampleNameForFlip, requiresFlipX, requiresFlipY)
                    .thenApply(validated -> {
                        if (!validated) {
                            logger.warn("Flip re-assert before refinement returned not-validated; "
                                    + "proceeding with the current open entry");
                        }
                        return state;
                    });
        }

        /**
         * Creates refinement tiles against the (now re-asserted) open entry and
         * runs single-tile refinement.
         */
        private CompletableFuture<WorkflowState> createRefinementTilesAndRun(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);
            if (state.annotations == null || state.annotations.isEmpty()) {
                logger.warn("No annotations available for refinement after sibling re-assert");
                return CompletableFuture.completedFuture(state);
            }

            // Create tiles if needed
            // CRITICAL: Annotations from flipped images are already in the flipped coordinate space.
            // The tile grid generation should NOT apply additional flip transformations because
            // that would cause a double-flip (annotations already flipped, then grid flips them again).
            //
            // The flip/invert parameters are for STAGE coordinate transformation (pixel -> stage),
            // NOT for tile grid generation from annotation bounds.
            //
            // TODO: This is part of the larger "flipped vs inverted" terminology issue.
            // See CLAUDE.md section "COORDINATE SYSTEM TERMINOLOGY" and TODO_LIST.md for the
            // comprehensive review needed to properly separate optical flipping from stage inversion.
            if (state.projectInfo != null) {
                logger.info("Creating tiles for refinement - annotations are in flipped image coordinate space");

                // Pass false for both axes because annotation coordinates are already in target space
                // The grid generation should use the annotation bounds directly without additional transformation
                TileHelper.createTilesForAnnotations(
                        state.annotations,
                        state.sample,
                        state.projectInfo.getTempTileDirectory(),
                        state.projectInfo.getImagingModeWithIndex(),
                        state.pixelSize,
                        false, // Don't flip X - annotations already in correct space
                        false // Don't flip Y - annotations already in correct space
                        );
            }

            return SingleTileRefinement.performRefinement(gui, state.annotations, state.transform)
                    .thenApply(result -> {
                        state.refinementTile = result.selectedTile;
                        // Only consume the refined transform + persist the JSON when the
                        // user actually accepted a refinement (Save Refined Position) or
                        // SIFT auto-accepted. Skip / X-close return the initial transform
                        // unchanged with accepted=false; re-installing it is a no-op but
                        // re-writing the per-slide JSON destroys its original flipMacroX/Y
                        // provenance (review finding H7).
                        if (!result.accepted) {
                            logger.info(
                                    "Refinement not accepted (skip / cancel / X-close); preserving prior alignment");
                            return state;
                        }
                        if (result.transform != null) {
                            state.transform = result.transform;
                            MicroscopeController.getInstance().setCurrentTransform(result.transform);
                            logger.info("Updated transform with refined alignment");
                        }
                        saveRefinedAlignment(state);
                        return state;
                    })
                    .whenComplete((s, ex) -> {
                        // Phase 11: on exception from refinement (not Skip / X-close, which
                        // are normal non-accepted paths), force-delete the refinement tile
                        // directory so a crashed refinement does not leave tiles polluting
                        // the project. Skip / X-close return normally with accepted=false;
                        // the workflow continues to acquisition which reuses the same dir,
                        // so we leave tiles in place on the non-exception path.
                        if (ex != null && state.projectInfo != null) {
                            String tempTileDir = state.projectInfo.getTempTileDirectory();
                            if (tempTileDir != null && !tempTileDir.isBlank()) {
                                try {
                                    TileCleanupHelper.performCleanup(tempTileDir, true);
                                } catch (Exception cleanupEx) {
                                    logger.warn(
                                            "Failed to clean refinement tiles after exception: {}",
                                            cleanupEx.getMessage());
                                }
                            }
                        }
                    });
        }

        /**
         * Saves the refined alignment.
         *
         * <p>No-op when the open entry is a sub-acquisition. The offset-based transform built
         * by {@link #processSubAcquisitionPath} is deterministic from {@code xy_offset} +
         * pixel size + half-FOV correction; persisting it under the parent macro's lookup
         * key (which {@link AlignmentHelper#resolveMacroLookupKey} would resolve to) writes
         * a sub-image-pixel-frame transform tagged {@code pixelFrame="macro"} and silently
         * corrupts every future macro-entry run. Found during the 2026-05-13 review as
         * direct fallout from the 2603535 routing fix.
         */
        private void saveRefinedAlignment(WorkflowState state) {
            if (isSubAcquisition()) {
                logger.debug("saveRefinedAlignment: open entry is a sub-acquisition; "
                        + "offset-based transform is deterministic and not persisted.");
                return;
            }

            @SuppressWarnings("unchecked")
            Project<BufferedImage> project = state.projectInfo != null
                    ? (Project<BufferedImage>) state.projectInfo.getCurrentProject()
                    : gui.getProject();

            if (project == null || state.transform == null) {
                return;
            }

            // Get the actual image file name (not metadata name which may be project name).
            // Resolve through base_image metadata so a sub-image entry doesn't cause us to
            // save a parallel sub-image-keyed JSON; we want the refined transform to land
            // back on the macro alignment file.
            String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
            String lookupKey = (imageName != null)
                    ? AlignmentHelper.resolveMacroLookupKey(project, gui.getImageData(), imageName)
                    : null;

            if (lookupKey != null) {
                // Record flipMacroX/Y to match the CURRENT OPEN ENTRY's frame. AlignmentHelper's
                // bake-delta on load is "bake = alignFlip XOR currentEntryFlip" (the
                // ImageMetadataManager.isFlippedX/Y read on the open entry). For the next load
                // to be idempotent (no spurious bake), the saved alignFlip must equal the frame
                // the transform actually consumes. The transform that came out of
                // SingleTileRefinement consumes pixel coords from the currently-open entry's
                // hierarchy, so the saved alignFlip should equal the open entry's flip
                // metadata -- not a hardcoded false (which mis-labelled flipped-sibling
                // alignments and caused the 2026-05-18 stage-mirror bug).
                boolean openEntryFlipX = false;
                boolean openEntryFlipY = false;
                try {
                    ProjectImageEntry<BufferedImage> openEntry = project.getEntry(gui.getImageData());
                    if (openEntry != null) {
                        openEntryFlipX = ImageMetadataManager.isFlippedX(openEntry);
                        openEntryFlipY = ImageMetadataManager.isFlippedY(openEntry);
                    }
                } catch (Exception e) {
                    logger.warn(
                            "Could not read open-entry flip metadata for saveRefinedAlignment; defaulting to (false, false): {}",
                            e.getMessage());
                }
                AffineTransformManager.saveSlideAlignment(
                        project,
                        lookupKey,
                        state.modality,
                        state.transform,
                        null,
                        openEntryFlipX,
                        openEntryFlipY,
                        AffineTransformManager.PIXEL_FRAME_MACRO,
                        state.objective,
                        state.detector);
                logger.info(
                        "Saved refined alignment for image: {} (lookupKey={}, flipMacroX={}, flipMacroY={}, objective={}, detector={})",
                        imageName,
                        lookupKey,
                        openEntryFlipX,
                        openEntryFlipY,
                        state.objective,
                        state.detector);
            }
        }

        /**
         * Performs the acquisition phase.
         */
        private CompletableFuture<WorkflowState> performAcquisition(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            // Defense-in-depth: the wizard and pre-refinement gates already ran the same
            // check; this catches the case where MM state changed during refinement (user
            // switched objective in MicroManager, applied a ROI crop, etc.).
            if (!validateMMAgainstSelection(state)) {
                logger.warn("Pixel-size or camera-ROI validation failed before acquisition; cancelling workflow");
                // Phase 12: explicit cancellation through handleError. The validate dialog
                // already informed the user.
                return CompletableFuture.failedFuture(
                        new CancellationException("Pre-acquisition MM/wizard validation failed"));
            }

            logger.info("Starting acquisition phase");

            return new AcquisitionManager(gui, state).execute().thenApply(legacyState -> {
                if (legacyState != null) {
                    // Copy stitching futures back from legacy state to V2 state
                    state.stitchingFutures.addAll(legacyState.stitchingFutures);
                }
                return state;
            });
        }

        /**
         * Waits for all stitching operations to complete.
         */
        private CompletableFuture<WorkflowState> waitForCompletion(WorkflowState state) {
            if (state == null || state.stitchingFutures.isEmpty()) {
                return CompletableFuture.completedFuture(state);
            }

            logger.info("Waiting for {} stitching operations to complete", state.stitchingFutures.size());

            return CompletableFuture.allOf(state.stitchingFutures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> state);
        }

        /**
         * Cleans up resources after workflow completion.
         *
         * <p>Clears {@code MicroscopeController.currentTransform} so the next workflow
         * (or any inter-workflow Live Viewer Go-To-Centroid click) cannot consume a
         * stale transform installed by this run. The Live Viewer panel has a
         * three-tier fallback (derived/, flat per-slide JSON, then in-session
         * {@code currentTransform}); clearing the singleton just pushes it to load
         * from disk on the next centroid click -- a net improvement over the
         * residue class of bug (cancelling a sub-image run, then clicking on the
         * macro, would otherwise drive the stage with the sub-image transform).
         * Review finding H6.
         */
        private void cleanup() {
            logger.info("Workflow completed - cleaning up");
            // Clear any preserved annotations (should already be restored, but cleanup just in case)
            state.annotationPreservation.clearPreservedAnnotations();
            MicroscopeController.getInstance().setCurrentTransform(null);
            // Phase 11: safety net for AcquisitionManager.processAnnotations throwing after
            // setAcquisitionActive(true) but before its whenComplete reset. Without this the
            // flag stays stuck across runs and PROBEZ / Live Viewer paths see acquisitionActive
            // forever.
            MicroscopeController.getInstance().setAcquisitionActive(false);
        }

        /**
         * Applies the tile-handling preference (Delete / Zip / Keep) to the
         * temporary tile directory. Runs only on the success path so tiles
         * remain available for diagnostics if acquisition or stitching failed.
         */
        private void cleanupTilesAfterStitching() {
            if (state == null || state.projectInfo == null) {
                return;
            }
            String tempTileDir = state.projectInfo.getTempTileDirectory();
            if (tempTileDir == null || tempTileDir.isBlank()) {
                return;
            }
            TileCleanupHelper.performCleanup(tempTileDir);
        }

        /**
         * Shows success notification and plays completion beep.
         */
        private void showSuccessNotification() {
            UIFunctions.playWorkflowCompletionBeep();

            Platform.runLater(() -> {
                Dialogs.showInfoNotification("Acquisition Complete", "All acquisitions have completed successfully.");
            });

            String sampleName = state.sample != null ? state.sample.sampleName() : "Unknown";
            NotificationService.getInstance()
                    .notify(
                            "Acquisition Complete",
                            "Sample \"" + sampleName + "\" - all acquisitions finished successfully",
                            NotificationPriority.DEFAULT,
                            NotificationEvent.ACQUISITION_COMPLETE);
        }

        /**
         * Handles errors during workflow execution.
         */
        private Void handleError(Throwable ex) {
            // Unwrap CompletionException to get the actual cause
            // This handles cases where cancel() is used instead of completeExceptionally()
            Throwable cause = ex;
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }

            if (cause instanceof CancellationException) {
                logger.info("Workflow cancelled by user");
            } else {
                logger.error("Workflow error", cause);
                final Throwable displayCause = cause;
                Platform.runLater(() -> {
                    Dialogs.showErrorMessage("Workflow Error", "An error occurred: " + displayCause.getMessage());
                });

                String sampleName = state.sample != null ? state.sample.sampleName() : "Unknown";
                NotificationService.getInstance()
                        .notify(
                                "Acquisition Error",
                                "Sample \"" + sampleName + "\" failed\n" + "Error: " + displayCause.getMessage(),
                                NotificationPriority.HIGH,
                                NotificationEvent.ACQUISITION_ERROR);
            }

            // Clear preserved annotations on error to prevent stale data
            state.annotationPreservation.clearPreservedAnnotations();

            // Phase 11: force-delete temporary tiles on error so a failed run does not
            // leave them on disk even when the user's preference is "Keep" (Keep is for
            // diagnostics on success; on error the tiles are usually incomplete /
            // corrupt). cleanupTilesAfterStitching honours the preference and runs only
            // on the success branch; this is the error-path equivalent.
            try {
                if (state != null && state.projectInfo != null) {
                    String tempTileDir = state.projectInfo.getTempTileDirectory();
                    if (tempTileDir != null && !tempTileDir.isBlank()) {
                        TileCleanupHelper.performCleanup(tempTileDir, true);
                    }
                }
            } catch (Exception cleanupEx) {
                logger.warn("Failed to clean up tiles on error path: {}", cleanupEx.getMessage());
            }

            cleanup();
            return null;
        }

        /**
         * Hard-cancel dialog for the cross-scope sub-image gate (review finding H3).
         * FX-safe, blocking, OK-only -- mirrors {@code AlignmentHelper.showPixelFrameMismatchDialog}.
         */
        private static void showSubImageCrossScopeMismatchDialog(
                String acquiredOn, String activeMicroscope, String entryName) {
            String title = "Sub-image Acquired on a Different Microscope -- Workflow Cancelled";
            String header = "This sub-image was acquired on a different microscope.";
            StringBuilder body = new StringBuilder();
            body.append(String.format(
                    "The open entry%n  '%s'%nwas acquired on microscope '%s',%n"
                            + "but the active microscope is '%s'.%n%n",
                    entryName, acquiredOn, activeMicroscope));
            body.append("Sub-image acquisitions use the entry's xy_offset metadata\n");
            body.append("for stage targeting. That offset is in the ACQUIRING scope's\n");
            body.append("stage frame and is not meaningful on any other microscope --\n");
            body.append("driving the stage from it would land at the wrong physical\n");
            body.append("location.\n\n");
            body.append("To fix, choose one of:\n");
            body.append(String.format("  1. Open this sub-image on '%s' to run the acquisition there.%n", acquiredOn));
            body.append("  2. Open the parent macro entry and run the workflow against\n");
            body.append("     a fresh annotation on the macro -- the cross-scope alignment\n");
            body.append("     path will compose a transform for this microscope.\n\n");
            body.append("This workflow has been cancelled.");

            Runnable show = () -> {
                javafx.scene.control.Alert alert =
                        new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING);
                alert.setTitle(title);
                alert.setHeaderText(header);
                alert.setContentText(body.toString());
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
            };
            if (Platform.isFxApplicationThread()) {
                show.run();
                return;
            }
            java.util.concurrent.FutureTask<Void> task = new java.util.concurrent.FutureTask<>(() -> {
                show.run();
                return null;
            });
            Platform.runLater(task);
            try {
                task.get();
            } catch (Exception e) {
                logger.warn("Failed to display sub-image cross-scope mismatch dialog: {}", e.getMessage());
            }
        }

        /**
         * Hard-cancel dialog when a sub-image entry has no {@code objective} metadata
         * (review finding M5). The half-FOV correction in {@link #buildOffsetBasedTransform}
         * cannot resolve safely without it -- the previous fallback to
         * {@code MicroscopeController.getCameraFOV()} returns whatever objective MM is on
         * right now, which may not match the saved sub-image.
         */
        private static void showMissingEntryObjectiveDialog(String entryName) {
            String title = "Sub-image Missing Objective -- Workflow Cancelled";
            String header = "This sub-image has no objective metadata.";
            StringBuilder body = new StringBuilder();
            body.append(String.format("The open entry%n  '%s'%nhas no 'objective' metadata.%n%n", entryName));
            body.append("Sub-image acquisitions use the entry's recorded objective to\n");
            body.append("compute a half-FOV correction for the tile-grid origin. Without\n");
            body.append("it we cannot tell which FOV the saved offset was built against;\n");
            body.append("the previous fallback to MicroManager's live state silently used\n");
            body.append("whatever objective happened to be in place, which may not match.\n\n");
            body.append("To fix, re-acquire the sub-image with the current workflow (which\n");
            body.append("stamps the objective on import), or hand-edit the project entry's\n");
            body.append("metadata to add the correct objective.\n\n");
            body.append("This workflow has been cancelled.");

            Runnable show = () -> {
                javafx.scene.control.Alert alert =
                        new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING);
                alert.setTitle(title);
                alert.setHeaderText(header);
                alert.setContentText(body.toString());
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
            };
            if (Platform.isFxApplicationThread()) {
                show.run();
                return;
            }
            java.util.concurrent.FutureTask<Void> task = new java.util.concurrent.FutureTask<>(() -> {
                show.run();
                return null;
            });
            Platform.runLater(task);
            try {
                task.get();
            } catch (Exception e) {
                logger.warn("Failed to display missing-entry-objective dialog: {}", e.getMessage());
            }
        }

        /**
         * Continue / Cancel advisory when the sub-image's recorded objective differs from
         * the wizard's current objective (review finding M4). Delegates to the shared
         * {@link AlignmentHelper#confirmContinueDialog(String, String, String)}.
         */
        private static boolean confirmContinueWithEntryObjectiveMismatch(
                String entryName, String entryObjective, String wizardObjective) {
            String title = "Sub-image Objective Mismatch";
            String header = "The sub-image was acquired at a different objective.";
            StringBuilder body = new StringBuilder();
            body.append(String.format(
                    "The open entry%n  '%s'%nwas acquired at objective '%s'.%n"
                            + "The wizard is configured for objective '%s'.%n%n",
                    entryName, entryObjective, wizardObjective));
            body.append("The entry's objective drives the half-FOV correction applied\n");
            body.append("to the tile-grid origin; the wizard's objective drives the\n");
            body.append("tile grid itself. A mismatch shifts every tile by half the\n");
            body.append("FOV-delta between the two objectives.\n\n");
            body.append("Recommended: cancel, switch the wizard to the entry's\n");
            body.append("objective, or re-acquire the sub-image at the desired\n");
            body.append("objective.\n\n");
            body.append("Continue anyway with this objective mismatch?");
            return AlignmentHelper.confirmContinueDialog(title, header, body.toString());
        }
    }

    /**
     * Workflow state container for V2.
     */
    public static class WorkflowState {
        public SampleSetupResult sample;
        public AlignmentSelectionController.AlignmentChoice alignmentChoice;
        public AffineTransform transform;
        public ProjectHelper.ProjectInfo projectInfo;
        public List<PathObject> annotations = new ArrayList<>();
        public List<CompletableFuture<Void>> stitchingFutures = new ArrayList<>();

        /**
         * Per-workflow annotation-preservation service (review finding M12).
         * Previously a static singleton; one instance per workflow now keeps
         * preserved annotations from leaking across concurrent or restarted runs.
         */
        public final AnnotationPreservationService annotationPreservation = new AnnotationPreservationService();

        public double pixelSize;
        public Map<String, Double> angleOverrides;
        public Map<String, Double> channelIntensityOverrides = Map.of();
        public String focusChannelId;
        public String afStrategy;
        public String innerAxis;
        public PathObject refinementTile;

        // V2-specific fields
        public String modality;
        public String objective;
        public String detector;
        public boolean isExistingProject;
        public boolean useExistingSlideAlignment;
        public double alignmentConfidence;
        public String alignmentSource;

        /**
         * Cross-scope acquisition: true when {@link #transform} was composed from a
         * per-slide alignment built for a *different* microscope plus a saved preset
         * pair sharing a source scanner. In this mode the open entry must be left
         * alone -- the composed transform expects pixel input in the entry's current
         * frame, and {@code verifyOpenEntryMatchesPreset} would mistakenly treat the
         * target microscope's preset flip as the alignment frame and abort.
         *
         * @see CrossScopeTransformBuilder
         */
        public boolean crossScope;

        public String crossScopeSourceMicroscope;
        public RefinementSelectionController.RefinementChoice refinementChoice =
                RefinementSelectionController.RefinementChoice.NONE;
        public GreenBoxDetector.DetectionParams greenBoxParams;
        public List<String> selectedAnnotationClasses = new ArrayList<>();

        // JAI camera white balance settings
        public boolean enableWhiteBalance = true;
        public boolean perAngleWhiteBalance = false;
        public String wbMode = "per_angle";
    }
}
