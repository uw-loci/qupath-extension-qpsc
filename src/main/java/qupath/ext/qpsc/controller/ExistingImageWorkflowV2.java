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
                    boolean captured = AnnotationPreservationService.captureAnnotations(gui);
                    if (captured) {
                        logger.info(
                                "Preserved {} annotations from standalone image",
                                AnnotationPreservationService.getPreservedAnnotationCount());
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

                            // Get actual annotations for the selected classes
                            List<PathObject> selectedAnnotations =
                                    getAnnotationsForClasses(annotationResult.selectedClasses);
                            return ExistingImageAcquisitionController.showDialog(
                                    defaultSampleName, selectedAnnotations);
                        })
                        .thenCompose(this::initializeFromConfig)
                        .thenCompose(this::checkExistingSlideAlignment)
                        .thenCompose(this::routeSubWorkflow)
                        .thenCompose(this::handleRefinement)
                        .thenCompose(this::performAcquisition)
                        .thenCompose(this::waitForCompletion)
                        .thenAccept(result -> {
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
                        .thenCompose(this::ensureAnnotationsExist) // Call annotation helper if none exist
                        .thenCompose(this::handleRefinement)
                        .thenCompose(this::performAcquisition)
                        .thenCompose(this::waitForCompletion)
                        .thenAccept(result -> {
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
                return CompletableFuture.completedFuture(null);
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
                    state.transform = slideResult.getTransform();
                    state.alignmentConfidence = slideResult.getConfidence();
                    state.alignmentSource = slideResult.getSource();
                    logger.info(
                            "Found slide-specific alignment - confidence: {}",
                            String.format("%.2f", state.alignmentConfidence));
                }
                return state;
            });
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

            // Check if this is a sub-acquisition with offset metadata.
            // Sub-acquisitions can compute their pixel-to-stage transform directly
            // from xy_offset + pixel size, without needing macro/green box detection.
            if (isSubAcquisition()) {
                logger.info("Routing to sub-acquisition offset-based targeting path");
                return processSubAcquisitionPath(state);
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

            // Set the transform
            MicroscopeController.getInstance().setCurrentTransform(state.transform);

            // Delegate to ProjectHelper for proper project setup
            return ProjectHelper.setupProject(gui, state.sample)
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
                        AffineTransformManager.TransformPreset presetForFlip = state.alignmentChoice != null
                                ? state.alignmentChoice.selectedTransform()
                                : null;
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
                                        verifyOpenEntryMatchesPreset(
                                                gui, project, requiresFlipX, requiresFlipY);
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
                    // ExistingAlignmentPath returned null (e.g. no macro image, user cancelled)
                    return null;
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

            logger.info("Processing sub-acquisition with offset-based targeting");

            // Read metadata from current entry BEFORE project setup / flip validation,
            // since those steps may switch to a different entry (TransformedServer).
            @SuppressWarnings("unchecked")
            Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
            ProjectImageEntry<BufferedImage> entry = project.getEntry(gui.getImageData());

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

            // Delegate to ProjectHelper for proper project setup
            return ProjectHelper.setupProject(gui, state.sample)
                    .thenCompose(projectInfo -> {
                        if (projectInfo == null) {
                            throw new RuntimeException("Project setup failed");
                        }
                        state.projectInfo = projectInfo;

                        // Validate and flip image if needed -- pass preset flip state explicitly
                        // (see processSlideSpecificAlignment for the OWS3 incident this avoids).
                        @SuppressWarnings("unchecked")
                        Project<BufferedImage> proj = (Project<BufferedImage>) projectInfo.getCurrentProject();
                        AffineTransformManager.TransformPreset presetForFlip = state.alignmentChoice != null
                                ? state.alignmentChoice.selectedTransform()
                                : null;
                        boolean requiresFlipX = FlipResolver.resolveFlipX(null, presetForFlip, null);
                        boolean requiresFlipY = FlipResolver.resolveFlipY(null, presetForFlip, null);
                        String sampleNameForFlip2 = state.sample != null ? state.sample.sampleName() : null;
                        return ImageFlipHelper.validateAndFlipIfNeeded(
                                        gui, proj, sampleNameForFlip2, requiresFlipX, requiresFlipY)
                                .thenApply(validated -> {
                                    if (validated) {
                                        verifyOpenEntryMatchesPreset(gui, proj, requiresFlipX, requiresFlipY);
                                    }
                                    return validated;
                                });
                    })
                    .thenCompose(validated -> {
                        if (!validated) {
                            throw new RuntimeException("Image validation failed");
                        }

                        // Build transform from offset metadata.
                        // This must happen AFTER flip validation since the image dimensions
                        // and pixel coordinates are from the (possibly flipped) server.
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
         */
        private CompletableFuture<WorkflowState> handleRefinement(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

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
         * Performs single-tile refinement.
         */
        private CompletableFuture<WorkflowState> performSingleTileRefinement(WorkflowState state) {
            if (state.annotations == null || state.annotations.isEmpty()) {
                logger.warn("No annotations available for refinement");
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
                        if (result.transform != null) {
                            state.transform = result.transform;
                            MicroscopeController.getInstance().setCurrentTransform(result.transform);
                            logger.info("Updated transform with refined alignment");
                        }
                        state.refinementTile = result.selectedTile;
                        saveRefinedAlignment(state);
                        return state;
                    });
        }

        /**
         * Saves the refined alignment.
         */
        private void saveRefinedAlignment(WorkflowState state) {
            @SuppressWarnings("unchecked")
            Project<BufferedImage> project = state.projectInfo != null
                    ? (Project<BufferedImage>) state.projectInfo.getCurrentProject()
                    : gui.getProject();

            if (project == null || state.transform == null) {
                return;
            }

            // Get the actual image file name (not metadata name which may be project name)
            // Strip extension for consistency with base_image metadata lookups
            String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
            if (imageName != null) {
                imageName = qupath.lib.common.GeneralTools.stripExtension(imageName);
            }

            if (imageName != null) {
                AffineTransformManager.saveSlideAlignment(project, imageName, state.modality, state.transform, null);
                logger.info("Saved refined alignment for image: {}", imageName);
            }
        }

        /**
         * Performs the acquisition phase.
         */
        private CompletableFuture<WorkflowState> performAcquisition(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            // Validate pixel size against MicroManager before acquisition
            try {
                String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
                double configPixelSize = configManager.getPixelSize(state.objective, state.detector);
                if (!QPScopeChecks.validateObjectivePixelSize(
                        state.objective, state.detector, state.modality, configPixelSize)) {
                    return CompletableFuture.completedFuture(null); // user cancelled
                }
            } catch (Exception e) {
                logger.warn("Could not validate pixel size before acquisition: {}", e.getMessage());
                // Non-fatal -- proceed with acquisition
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
         */
        private void cleanup() {
            logger.info("Workflow completed - cleaning up");
            // Clear any preserved annotations (should already be restored, but cleanup just in case)
            AnnotationPreservationService.clearPreservedAnnotations();
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
            AnnotationPreservationService.clearPreservedAnnotations();

            cleanup();
            return null;
        }

        /**
         * Verifies that the QuPath GUI's currently-open project entry has the flip metadata
         * the saved alignment preset requires. Aborts the workflow with a clear modal if not.
         *
         * <p>The saved per-slide {@code fullRes->stage} transform was built in the pixel frame
         * of whatever entry was open at save time. Running acquisition from a mismatched frame
         * sends unflipped pixel coords through a flipped-frame transform (or vice versa),
         * producing an X-mirrored stage target. {@link ImageFlipHelper#validateAndFlipIfNeeded}
         * is supposed to swap to the matching entry, but the swap is asynchronous on the FX
         * thread and the worker thread that reads {@code gui.getImageData()} downstream can
         * race the swap and read stale data. Verified 2026-05-02 OWS3: Go-to-Centroid lands
         * at the X-mirror on the unflipped entry but at the correct location on the flipped
         * duplicate, and the acquisition follows whichever entry is actually open.
         *
         * <p>The user explicitly asked us to detect this case and either auto-switch reliably
         * or warn. Auto-switch's reliability is what's currently broken, so we surface a
         * clear instruction instead: switch manually and re-run.
         */
        private void verifyOpenEntryMatchesPreset(
                QuPathGUI gui,
                Project<BufferedImage> project,
                boolean requiresFlipX,
                boolean requiresFlipY) {
            if (gui == null || project == null) return;
            ImageData<BufferedImage> openData = gui.getImageData();
            ProjectImageEntry<BufferedImage> openEntry = openData != null ? project.getEntry(openData) : null;
            if (openEntry == null) {
                logger.warn(
                        "verifyOpenEntryMatchesPreset: no open entry found in project; skipping check");
                return;
            }
            boolean openFlipX = ImageMetadataManager.isFlippedX(openEntry);
            boolean openFlipY = ImageMetadataManager.isFlippedY(openEntry);
            if (openFlipX == requiresFlipX && openFlipY == requiresFlipY) {
                logger.info(
                        "verifyOpenEntryMatchesPreset: open entry='{}' flip=({}, {}) matches preset requirement",
                        openEntry.getImageName(),
                        openFlipX,
                        openFlipY);
                return;
            }

            // Find the sibling entry that DOES match the preset's flip frame so we can name
            // it in the error message. The user has confirmed that running from the matching
            // sibling produces the correct acquisition.
            String baseImage = ImageMetadataManager.getBaseImage(openEntry);
            if (baseImage == null || baseImage.isBlank()) {
                baseImage = qupath.lib.common.GeneralTools.stripExtension(openEntry.getImageName());
            }
            String matchingSiblingName = null;
            for (ProjectImageEntry<BufferedImage> sibling : project.getImageList()) {
                if (sibling.equals(openEntry)) continue;
                String siblingBase = ImageMetadataManager.getBaseImage(sibling);
                if (siblingBase == null || siblingBase.isBlank()) {
                    siblingBase = qupath.lib.common.GeneralTools.stripExtension(sibling.getImageName());
                }
                if (!baseImage.equals(siblingBase)) continue;
                if (ImageMetadataManager.isFlippedX(sibling) == requiresFlipX
                        && ImageMetadataManager.isFlippedY(sibling) == requiresFlipY) {
                    matchingSiblingName = sibling.getImageName();
                    break;
                }
            }

            String requirement = String.format(
                    "flipX=%s, flipY=%s", requiresFlipX, requiresFlipY);
            String openState = String.format(
                    "flipX=%s, flipY=%s", openFlipX, openFlipY);
            String message;
            if (matchingSiblingName != null) {
                message = String.format(
                        "The currently open image '%s' (%s) does not match the alignment's "
                                + "expected pixel frame (%s).%n%n"
                                + "Switch to '%s' in the QuPath project view, then re-run the "
                                + "acquisition. Acquiring from the wrong frame produces an X-mirrored "
                                + "stage target.",
                        openEntry.getImageName(),
                        openState,
                        requirement,
                        matchingSiblingName);
            } else {
                message = String.format(
                        "The currently open image '%s' (%s) does not match the alignment's "
                                + "expected pixel frame (%s) and no matching sibling was found "
                                + "in the project.%n%n"
                                + "Run Microscope Alignment to create the flipped duplicate, or open "
                                + "the correct flipped version of this slide and re-run.",
                        openEntry.getImageName(),
                        openState,
                        requirement);
            }
            logger.error(
                    "verifyOpenEntryMatchesPreset: MISMATCH -- open entry='{}' flip=({}, {}), "
                            + "preset requires flip=({}, {}); matching sibling='{}'",
                    openEntry.getImageName(),
                    openFlipX,
                    openFlipY,
                    requiresFlipX,
                    requiresFlipY,
                    matchingSiblingName);
            Platform.runLater(() -> Dialogs.showErrorMessage(
                    "Wrong image open for this alignment", message));
            throw new RuntimeException("Open entry's flip frame does not match alignment preset");
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
        public double pixelSize;
        public Map<String, Double> angleOverrides;
        public Map<String, Double> channelIntensityOverrides = Map.of();
        public String focusChannelId;
        public String afStrategy;
        public PathObject refinementTile;

        // V2-specific fields
        public String modality;
        public String objective;
        public String detector;
        public boolean isExistingProject;
        public boolean useExistingSlideAlignment;
        public double alignmentConfidence;
        public String alignmentSource;
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
