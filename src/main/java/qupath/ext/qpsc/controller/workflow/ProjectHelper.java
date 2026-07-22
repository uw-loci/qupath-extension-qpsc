package qupath.ext.qpsc.controller.workflow;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.model.SampleSetupResult;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.AnnotationPreservationService;
import qupath.ext.qpsc.utilities.FlipResolver;
import qupath.ext.qpsc.utilities.MacroImageUtility;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.MinorFunctions;
import qupath.ext.qpsc.utilities.ObjectiveUtils;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Helper class for QuPath project setup operations.
 *
 * <p>This class manages:
 * <ul>
 *   <li>Creating new projects or using existing ones</li>
 *   <li>Importing images with appropriate flip settings</li>
 *   <li>Setting up temporary tile directories</li>
 *   <li>Managing project metadata</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class ProjectHelper {
    private static final Logger logger = LoggerFactory.getLogger(ProjectHelper.class);

    /**
     * Container for project setup information.
     *
     * <p>Contains all necessary paths and identifiers for the acquisition workflow.
     */
    public static class ProjectInfo {
        private final Map<String, Object> details;
        private final String sampleName;

        /**
         * Creates a new project info container.
         *
         * @param details Map containing project details
         * @param sampleName The actual sample name (derived from project folder for existing projects)
         */
        public ProjectInfo(Map<String, Object> details, String sampleName) {
            this.details = details;
            this.sampleName = sampleName;
        }

        /**
         * Gets the actual sample name.
         *
         * <p>For existing projects, this is derived from the project folder name,
         * which may differ from the user-entered sample name in the dialog.
         * This ensures tile paths match what was written during project setup.
         *
         * @return The actual sample name to use for file paths
         */
        public String getSampleName() {
            return sampleName;
        }

        /**
         * Gets the temporary directory for tile storage.
         *
         * @return Path to temporary tile directory
         */
        public String getTempTileDirectory() {
            return (String) details.get("tempTileDirectory");
        }

        /**
         * Gets the imaging mode with index suffix.
         *
         * @return Imaging mode string (e.g., "Brightfield_1")
         */
        public String getImagingModeWithIndex() {
            return (String) details.get("imagingModeWithIndex");
        }

        /**
         * Gets the current QuPath project object.
         *
         * @return The QuPath project
         */
        public Object getCurrentProject() {
            return details.get("currentQuPathProject");
        }

        /**
         * Gets all project details.
         *
         * @return Map of all project details
         */
        public Map<String, Object> getDetails() {
            return details;
        }
    }

    /**
     * Sets up or retrieves the QuPath project.
     *
     * <p>This method:
     * <ol>
     *   <li>Creates a new project if none exists</li>
     *   <li>Uses existing project if available</li>
     *   <li>Imports the current image with flip settings</li>
     *   <li>Sets up tile directories</li>
     * </ol>
     *
     * @param gui QuPath GUI instance
     * @param sample Sample setup information
     * @param preservation the workflow's per-instance annotation-preservation service
     *     (review finding M12; the static singleton was retired in Phase 10). When the
     *     service has captured annotations from a standalone image, this method restores
     *     them into the freshly-created project entry.
     * @return CompletableFuture containing project information
     */
    public static CompletableFuture<ProjectInfo> setupProject(
            QuPathGUI gui, SampleSetupResult sample, AnnotationPreservationService preservation) {

        CompletableFuture<ProjectInfo> future = new CompletableFuture<>();

        logger.info("Setting up project for sample: {}", sample.sampleName());

        Platform.runLater(() -> {
            try {
                // Project setup runs before any image entry exists, so resolver falls through to
                // detector config (if known) and finally the global pref.
                boolean flippedX = FlipResolver.resolveFlipX(null, null, null);
                boolean flippedY = FlipResolver.resolveFlipY(null, null, null);

                logger.debug("Flip settings - X: {}, Y: {}", flippedX, flippedY);

                if (gui.getProject() == null) {
                    logger.info("Creating new project");

                    // For new projects, use the user-entered sample name
                    final String actualSampleName = sample.sampleName();

                    // Use enhanced modality name for consistent folder structure
                    String enhancedModality =
                            ObjectiveUtils.createEnhancedFolderName(sample.modality(), sample.objective());
                    logger.info("Using enhanced modality for project: {} -> {}", sample.modality(), enhancedModality);

                    // Add the image WITHOUT flip - ImageFlipHelper.validateAndFlipIfNeeded()
                    // will create a separate flipped duplicate entry afterward.
                    // Passing flip=true here would apply a virtual TransformedServer flip
                    // AND set flip metadata, causing validateAndFlipIfNeeded to skip the
                    // duplicate creation (it sees "already flipped" and returns early).
                    final Map<String, Object> projectDetails = QPProjectFunctions.createAndOpenQuPathProject(
                            gui,
                            sample.projectsFolder().getAbsolutePath(),
                            actualSampleName,
                            enhancedModality,
                            false,
                            false);

                    // createAndOpenQuPathProject reopens the image on a background thread. Anything
                    // that reads the installed ImageData (annotation restore, macro dimensions) must
                    // wait for the install to commit -- reading getImageData() synchronously here
                    // races the background install and would see the OLD image. Chain the rest of
                    // new-project setup onto the install future instead of a fixed delay.
                    @SuppressWarnings("unchecked")
                    CompletableFuture<Boolean> installFutureRaw =
                            (CompletableFuture<Boolean>) projectDetails.get("imageInstallFuture");
                    CompletableFuture<Boolean> installFuture = installFutureRaw != null
                            ? installFutureRaw
                            : CompletableFuture.completedFuture(Boolean.TRUE);

                    installFuture.whenComplete((installed, installErr) -> Platform.runLater(() -> {
                        try {
                            if (installErr != null || !Boolean.TRUE.equals(installed)) {
                                logger.warn("Reopened image did not confirm install before annotation"
                                        + " restore; proceeding, restore may target a stale hierarchy");
                            }

                            // Restore preserved annotations if any (from standalone image workflow).
                            // This handles the case where user drew annotations before starting workflow.
                            if (preservation != null && preservation.hasPreservedAnnotations()) {
                                logger.info(
                                        "Restoring {} preserved annotations after project creation",
                                        preservation.getPreservedAnnotationCount());

                                // Restore WITHOUT flip - the image was imported unflipped.
                                // Annotations will be transformed when the flipped duplicate is created
                                // by ImageFlipHelper.validateAndFlipIfNeeded() later in the workflow.
                                boolean restored = preservation.restoreAnnotations(gui, false, false);

                                if (restored) {
                                    // Save the annotations to the project entry
                                    try {
                                        var project = gui.getProject();
                                        var imageData = gui.getImageData();
                                        if (project != null && imageData != null) {
                                            var entry = project.getEntry(imageData);
                                            if (entry != null) {
                                                entry.saveImageData(imageData);
                                                project.syncChanges();
                                                logger.info("Saved restored annotations to project");
                                            }
                                        }
                                    } catch (Exception e) {
                                        logger.warn("Failed to save restored annotations: {}", e.getMessage());
                                    }
                                }

                                // Clear preserved annotations after restoration
                                preservation.clearPreservedAnnotations();
                            }

                            // Save macro dimensions if available (also needs the installed image)
                            BufferedImage macroImage = MacroImageUtility.retrieveMacroImage(gui);
                            if (macroImage != null) {
                                projectDetails.put("macroWidth", macroImage.getWidth());
                                projectDetails.put("macroHeight", macroImage.getHeight());
                            }

                            // Install already awaited above -- no arbitrary settle needed.
                            finalizeAndComplete(gui, projectDetails, actualSampleName, future, 0);
                        } catch (Exception e) {
                            logger.error("Failed to finalize new-project setup after image install", e);
                            UIFunctions.notifyUserOfError(
                                    "Failed to setup project: " + e.getMessage(), "Project Error");
                            future.complete(null);
                        }
                    }));
                } else {
                    logger.info("Using existing project");

                    // CRITICAL: Derive paths from actual project location, not user preferences
                    // This ensures all folders (tiles, stitched images) are created WITHIN
                    // the existing project structure, not in a separate location
                    Path projectFilePath = gui.getProject().getPath();
                    Path projectDir = projectFilePath.getParent();
                    final String actualSampleName = projectDir.getFileName().toString();
                    Path projectsFolder = projectDir.getParent();

                    logger.info("Actual project location: {}", projectDir);
                    logger.info("Derived sample name from project folder: {}", actualSampleName);

                    // Use enhanced modality name for consistent folder structure
                    String enhancedModality =
                            ObjectiveUtils.createEnhancedFolderName(sample.modality(), sample.objective());
                    logger.info(
                            "Using enhanced modality for existing project: {} -> {}",
                            sample.modality(),
                            enhancedModality);

                    final Map<String, Object> projectDetails = QPProjectFunctions.getCurrentProjectInformation(
                            projectsFolder.toString(), actualSampleName, enhancedModality);

                    // Handle image import if needed
                    handleExistingProjectImageImport(gui, flippedX, flippedY);

                    // handleExistingProjectImageImport may kick off an async flip-reopen with no
                    // awaited install signal; keep the legacy 500ms settle for this path.
                    // TODO(existing-project-reopen): thread that reopen through openEntryAndAwaitInstall
                    // like the new-project path, then drop this settle.
                    finalizeAndComplete(gui, projectDetails, actualSampleName, future, 500);
                }

            } catch (Exception e) {
                logger.error("Failed to setup project", e);
                UIFunctions.notifyUserOfError("Failed to setup project: " + e.getMessage(), "Project Error");
                future.complete(null);
            }
        });

        return future;
    }

    /**
     * Shared tail for both setup paths: copy microscope config files into the project for
     * provenance, then complete {@code future} on a fresh FX tick.
     *
     * <p>Completion is deferred via {@link Platform#runLater(Runnable)} so it does not run
     * inside an animation/layout pulse. The downstream non-async CompletableFuture chain
     * (validateAndFlipImage -> createTransform -> detectDataBounds ->
     * UIFunctions.executeWithProgress) runs synchronously in whatever thread completes this
     * future, and {@code showAndWait} throws "not allowed during animation or layout
     * processing" if that runs inside a pulse -- which had silently degraded data-bounds
     * detection to the approximate green-box fallback.
     */
    private static void finalizeAndComplete(
            QuPathGUI gui,
            Map<String, Object> projectDetails,
            String actualSampleName,
            CompletableFuture<ProjectInfo> future,
            long settleMillis) {
        logger.info("Project setup complete with sample name: {}", actualSampleName);

        // Copy microscope configuration files into the project for provenance
        try {
            MicroscopeConfigManager configMgr = MicroscopeConfigManager.getInstanceIfAvailable();
            Path projDir = gui.getProject() != null && gui.getProject().getPath() != null
                    ? gui.getProject().getPath().getParent()
                    : null;
            if (configMgr != null && projDir != null) {
                configMgr.copyConfigsToProject(projDir);
            }
        } catch (Exception e) {
            logger.debug("Could not copy configs to project: {}", e.getMessage());
        }

        // settleMillis > 0 gives the GUI time to settle when the image was reopened WITHOUT an
        // awaited install signal (existing-project flip-reopen path). The new-project path passes
        // 0 because it already awaited the real install future before calling here.
        if (settleMillis > 0) {
            PauseTransition pause = new PauseTransition(Duration.millis(settleMillis));
            pause.setOnFinished(
                    e -> Platform.runLater(() -> future.complete(new ProjectInfo(projectDetails, actualSampleName))));
            pause.play();
        } else {
            Platform.runLater(() -> future.complete(new ProjectInfo(projectDetails, actualSampleName)));
        }
    }

    /**
     * Handles image import for existing projects.
     *
     * <p>If the current image is not in the project, imports it with
     * appropriate flip settings.
     *
     * @param gui QuPath GUI instance
     * @param flippedX Whether to flip X axis
     * @param flippedY Whether to flip Y axis
     */
    private static void handleExistingProjectImageImport(QuPathGUI gui, boolean flippedX, boolean flippedY) {

        var imageData = gui.getImageData();
        if (imageData != null && (flippedX || flippedY)) {
            ProjectImageEntry<BufferedImage> currentEntry = null;

            try {
                currentEntry = gui.getProject().getEntry(imageData);
            } catch (Exception e) {
                logger.debug("Could not get project entry for current image: {}", e.getMessage());
            }

            if (currentEntry == null) {
                logger.info("Current image not in project, adding with flips");
                String imagePath = MinorFunctions.extractFilePath(imageData.getServerPath());
                if (imagePath != null) {
                    try {
                        QPProjectFunctions.addImageToProject(
                                new File(imagePath),
                                gui.getProject(),
                                flippedX,
                                flippedY,
                                null // No modality info available here - use auto-detection
                                );

                        gui.refreshProject();

                        // Reopen the image with flips applied
                        var entries = gui.getProject().getImageList();
                        var newEntry = entries.stream()
                                .filter(e -> e.getImageName().equals(new File(imagePath).getName()))
                                .findFirst()
                                .orElse(null);

                        if (newEntry != null) {
                            gui.openImageEntry(newEntry);
                        }
                    } catch (Exception e) {
                        logger.error("Failed to add image to project", e);
                    }
                }
            }
        }
    }
}
