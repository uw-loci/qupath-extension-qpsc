package qupath.ext.qpsc.controller.workflow;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.ExistingImageWorkflowV2.WorkflowState;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.AffineTransformationController;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.ImageFlipHelper;
import qupath.ext.qpsc.utilities.MacroImageUtility;
import qupath.ext.qpsc.utilities.MinorFunctions;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Handles Path B: Manual alignment creation.
 *
 * <p>This path is used when:
 * <ul>
 *   <li>No existing alignment is available or suitable</li>
 *   <li>The user wants to create a new alignment from scratch</li>
 *   <li>No macro image is available for automatic detection</li>
 * </ul>
 *
 * <p>The workflow:
 * <ol>
 *   <li>Loads pixel size configuration</li>
 *   <li>Sets up the project</li>
 *   <li>Creates tiles for alignment</li>
 *   <li>Shows manual alignment UI</li>
 *   <li>Saves the created transform</li>
 * </ol>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class ManualAlignmentPath {
    private static final Logger logger = LoggerFactory.getLogger(ManualAlignmentPath.class);

    private final QuPathGUI gui;
    private final WorkflowState state;

    /**
     * Name of the flipped-sibling entry the workflow expects to be the open
     * viewer entry after {@link #validateAndFlipImage()}, or {@code null} when no
     * flip applies (already-flipped entry, cross-scope, no preset flip). Captured
     * before the flip so {@link #createManualAlignment()} can WAIT for the switch
     * to actually commit -- the switch is queued on the FX thread and can be
     * delayed behind other work, so its completion future is not a reliable
     * signal that {@code gui.getImageData()} already reflects the sibling.
     */
    private volatile String expectedFlippedEntryName;

    /**
     * Creates a new manual alignment path handler.
     *
     * @param gui QuPath GUI instance
     * @param state Current workflow state
     */
    public ManualAlignmentPath(QuPathGUI gui, WorkflowState state) {
        this.gui = gui;
        this.state = state;
    }

    /**
     * Executes the manual alignment path workflow.
     *
     * <p>This method orchestrates the complete Path B workflow including
     * project setup and manual transform creation.
     *
     * @return CompletableFuture containing the updated workflow state
     */
    public CompletableFuture<WorkflowState> execute() {
        logger.info("Path B: Manual alignment creation");

        return loadPixelSize()
                .thenCompose(pixelSize -> {
                    state.pixelSize = pixelSize;
                    return ProjectHelper.setupProject(gui, state.sample, state.annotationPreservation);
                })
                .thenCompose(projectInfo -> {
                    if (projectInfo == null) {
                        throw new RuntimeException("Project setup failed");
                    }
                    state.projectInfo = projectInfo;
                    return validateAndFlipImage();
                })
                .thenCompose(validated -> {
                    if (!validated) {
                        throw new RuntimeException("Image validation and flip preparation failed");
                    }
                    return createManualAlignment();
                });
    }

    /**
     * Validates and flips the full-resolution image if needed.
     *
     * <p>This step ensures the image has the correct orientation BEFORE
     * any operations that depend on it (annotations, tile creation, manual alignment UI).
     *
     * @return CompletableFuture with true if successful, false if failed
     */
    private CompletableFuture<Boolean> validateAndFlipImage() {
        @SuppressWarnings("unchecked")
        Project<BufferedImage> project = (Project<BufferedImage>) state.projectInfo.getCurrentProject();

        // Capture the name of the flipped sibling this flip will switch to, BEFORE
        // the switch, so createManualAlignment can wait for it to actually become
        // the open entry. Resolve the preset flip from the current (pre-flip) open
        // entry; if it is already a flipped sibling, cross-scope, or needs no flip,
        // leave the expected name null (no wait).
        expectedFlippedEntryName = resolveExpectedFlippedEntryName(project);

        return ImageFlipHelper.validateAndFlipIfNeeded(gui, project, state.sample)
                .thenApply(validated -> {
                    if (validated) {
                        logger.info("Image flip validation complete - ready for manual alignment");
                    }
                    return validated;
                });
    }

    /**
     * Resolves the name of the flipped sibling the pending flip will switch to, or
     * {@code null} if no flip applies. Uses the same preset resolution and suffix
     * naming as {@link ImageFlipHelper}, so the returned name matches the entry
     * {@code validateAndFlipIfNeeded} switches the viewer to.
     */
    private String resolveExpectedFlippedEntryName(Project<BufferedImage> project) {
        try {
            if (gui.getImageData() == null) {
                return null;
            }
            ProjectImageEntry<BufferedImage> openEntry = project.getEntry(gui.getImageData());
            if (openEntry == null) {
                return null;
            }
            String openName = openEntry.getImageName();
            // Already a flipped sibling -> validateAndFlipIfNeeded no-ops; no wait.
            if (ImageFlipHelper.isFlippedSiblingName(openName)) {
                return null;
            }
            boolean[] flip = ImageFlipHelper.resolveRequiredFlipFromPreset(openEntry);
            String suffix =
                    (flip[0] && flip[1]) ? "(flipped XY)" : flip[0] ? "(flipped X)" : flip[1] ? "(flipped Y)" : null;
            if (suffix == null) {
                return null;
            }
            return openName + " " + suffix;
        } catch (Exception e) {
            logger.warn("Could not resolve expected flipped-sibling name: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Loads pixel size for manual alignment.
     *
     * <p>Attempts to load from:
     * <ol>
     *   <li>Saved scanner configuration if available</li>
     *   <li>MacroImageUtility fallback configuration</li>
     * </ol>
     *
     * @return CompletableFuture containing the pixel size in micrometers
     */
    private CompletableFuture<Double> loadPixelSize() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Try saved scanner first
                String savedScanner = PersistentPreferences.getSelectedScanner();
                if (savedScanner != null && !savedScanner.isEmpty()) {
                    String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                    File configDir = new File(configPath).getParentFile();
                    File scannerConfigFile = new File(configDir, "config_" + savedScanner + ".yml");

                    if (scannerConfigFile.exists()) {
                        Map<String, Object> scannerConfig =
                                MinorFunctions.loadYamlFile(scannerConfigFile.getAbsolutePath());
                        Double pixelSize = MinorFunctions.getYamlDouble(scannerConfig, "macro", "pixel_size_um");

                        if (pixelSize != null && pixelSize > 0) {
                            logger.info("Loaded pixel size {} from scanner config", pixelSize);
                            return pixelSize;
                        }
                    }
                }

                // Try to get from MacroImageUtility - will throw if not configured
                return MacroImageUtility.getMacroPixelSize();
            } catch (Exception e) {
                logger.error("Failed to load pixel size", e);
                throw new RuntimeException("Cannot determine macro image pixel size.\n" + e.getMessage());
            }
        });
    }

    /**
     * Creates manual alignment through user interaction.
     *
     * <p>This method:
     * <ol>
     *   <li>Gets or creates annotations</li>
     *   <li>Creates tiles for visual reference</li>
     *   <li>Shows the manual alignment UI</li>
     *   <li>Saves the created transform</li>
     * </ol>
     *
     * @return CompletableFuture containing the updated workflow state
     */
    private CompletableFuture<WorkflowState> createManualAlignment() {
        // Wait for the flipped sibling to ACTUALLY be the open viewer entry before
        // touching annotations. The flip switch (validateAndFlipIfNeeded) is queued
        // on the FX thread and can be delayed behind other work; its completion
        // future fires while gui.getImageData() may still be the rotated base. If
        // we read/detect annotations and build tiles in that window, they land on
        // the base hierarchy while the Select-Tile picker (which follows the
        // eventual switch) shows the flipped sibling -- so it appears with the
        // operator's annotations but no tiles. Polling yields the FX thread so the
        // pending switch can commit.
        CompletableFuture<Void> ready = expectedFlippedEntryName != null
                ? awaitOpenEntry(expectedFlippedEntryName, 40)
                : CompletableFuture.completedFuture(null);
        return ready.thenCompose(v -> gatherAnnotationsAndProceed());
    }

    /**
     * Reads (or prompts for) annotations on the now-current entry, then proceeds
     * to tile creation + the manual alignment UI.
     */
    private CompletableFuture<WorkflowState> gatherAnnotationsAndProceed() {
        // Read annotations on the CURRENT entry (any class). We do NOT silently
        // auto-run tissue detection here: on the per-slide multi-slide path the
        // operator must SEE the same annotation dialog the standard Existing Image
        // workflow uses, so they can run detection / draw / review on the entry
        // that matches the live viewer.
        state.annotations = AnnotationHelper.getCurrentValidAnnotations(gui, null);
        if (!state.annotations.isEmpty()) {
            logger.info("Found {} existing annotation(s) on the alignment entry", state.annotations.size());
            return buildTilesAndShowAlignmentUI();
        }

        // No annotations on the displayed entry -- show the per-slide annotation
        // dialog (Run Tissue Detection / draw manually / review / confirm).
        logger.info("No annotations on the alignment entry; showing per-slide annotation dialog");
        return UIFunctions.showAnnotationWarningDialog(gui, null).thenCompose(action -> {
            if (action != UIFunctions.AnnotationAction.ANNOTATIONS_CONFIRMED) {
                throw new CancellationException("No annotations for manual alignment");
            }
            state.annotations = AnnotationHelper.getCurrentValidAnnotations(gui, null);
            if (state.annotations.isEmpty()) {
                throw new CancellationException("No annotations available for manual alignment");
            }
            logger.info("Proceeding with {} annotation(s) after annotation dialog", state.annotations.size());
            return buildTilesAndShowAlignmentUI();
        });
    }

    /**
     * Polls {@code gui.getImageData()} on the FX thread until the open project
     * entry's name equals {@code expectedName}, or {@code maxChecks} 150 ms ticks
     * elapse (then proceeds anyway). Polling deliberately yields the FX thread
     * between checks so a queued image switch can commit.
     */
    private CompletableFuture<Void> awaitOpenEntry(String expectedName, int maxChecks) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        pollOpenEntry(f, expectedName, maxChecks);
        return f;
    }

    private void pollOpenEntry(CompletableFuture<Void> f, String expectedName, int remaining) {
        javafx.application.Platform.runLater(() -> {
            String current = currentOpenEntryName();
            if (expectedName.equals(current)) {
                logger.info("Alignment entry '{}' is now the open viewer entry", expectedName);
                f.complete(null);
                return;
            }
            if (remaining <= 0) {
                logger.warn(
                        "Alignment entry '{}' did not become the open entry (current='{}') within timeout; "
                                + "proceeding anyway",
                        expectedName,
                        current);
                f.complete(null);
                return;
            }
            CompletableFuture.delayedExecutor(150, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .execute(() -> pollOpenEntry(f, expectedName, remaining - 1));
        });
    }

    /** Name of the project entry currently installed in the viewer, or null. */
    private String currentOpenEntryName() {
        var data = gui.getImageData();
        if (data == null) {
            return null;
        }
        if (gui.getProject() != null) {
            try {
                @SuppressWarnings("unchecked")
                Project<BufferedImage> proj = (Project<BufferedImage>) gui.getProject();
                ProjectImageEntry<BufferedImage> e = proj.getEntry(data);
                if (e != null) {
                    return e.getImageName();
                }
            } catch (Exception ignore) {
                // fall through to server metadata name
            }
        }
        return data.getServer() != null && data.getServer().getMetadata() != null
                ? data.getServer().getMetadata().getName()
                : null;
    }

    /**
     * Builds alignment tiles from {@code state.annotations} on the current open
     * entry and shows the manual alignment (Select-Tile) UI, then saves the
     * resulting transform. Precondition: {@code state.annotations} is non-empty
     * and the intended (flipped-sibling) entry is the open viewer entry.
     *
     * @return CompletableFuture containing the updated workflow state
     */
    private CompletableFuture<WorkflowState> buildTilesAndShowAlignmentUI() {
        // Use the same tile creation as acquisition - delegates to TilingUtilities
        // which reads stageInvertedX/Y from global preferences for consistent tile positioning
        logger.info("Creating tiles for manual alignment using global inversion preferences");

        // Use the 5-parameter version which reads inversion from preferences
        TileHelper.createTilesForAnnotations(
                state.annotations,
                state.sample,
                state.projectInfo.getTempTileDirectory(),
                state.projectInfo.getImagingModeWithIndex(),
                state.pixelSize);

        // Get stage inversion settings from preferences for the alignment UI.
        // The UI uses these to interpret the operator's click direction; the
        // transform class is the canonical reader so all call sites agree.
        qupath.ext.qpsc.utilities.StagePolarity stagePolarity = QPPreferenceDialog.getStagePolarityProperty();
        boolean stageInvertedX = stagePolarity.invertX;
        boolean stageInvertedY = stagePolarity.invertY;

        // Show manual alignment UI
        return AffineTransformationController.setupAffineTransformationAndValidationGUI(
                        state.pixelSize, stageInvertedX, stageInvertedY)
                .thenApply(transform -> {
                    if (transform == null) {
                        throw new CancellationException("Manual alignment cancelled by user");
                    }

                    logger.info("Manual transform created successfully");

                    state.transform = transform;
                    MicroscopeController.getInstance().setCurrentTransform(transform);

                    // Save slide-specific transform
                    @SuppressWarnings("unchecked")
                    Project<BufferedImage> project = (Project<BufferedImage>) state.projectInfo.getCurrentProject();

                    // Resolve the canonical macro lookup key (base_image when present, else
                    // the stripped filename). Keeps load and save in lockstep with
                    // AlignmentHelper.checkForSlideAlignment + saveRefinedAlignment, which
                    // both go through resolveMacroLookupKey. A flipped sibling entry's
                    // file name still resolves to the unflipped base via base_image.
                    String rawImageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
                    String imageName = rawImageName != null
                            ? AlignmentHelper.resolveMacroLookupKey(project, gui.getImageData(), rawImageName)
                            : null;

                    if (imageName != null) {
                        // Capture the macro flip frame this manual alignment was built in
                        // by reading the OPEN ENTRY's flip metadata. This must match exactly
                        // what AlignmentHelper.checkForSlideAlignment reads as
                        // `currentEntryFlipX/Y` on the next load (it calls
                        // ImageMetadataManager.isFlippedX/Y on the open entry); otherwise
                        // the bake-delta `bake = alignFlip XOR currentEntryFlip` either
                        // over-flips or under-flips. Using preset-driven flip here
                        // (resolveRequiredFlipFromPreset) can diverge from the entry's
                        // actual flip metadata -- e.g. when source_microscope is missing
                        // the preset resolver returns (false, false) even when the open
                        // entry is the flipped sibling -- and was the cause of the
                        // 2026-05-18 stage-mirror bug.
                        ProjectImageEntry<BufferedImage> openEntry = project.getEntry(gui.getImageData());
                        boolean flipMacroX = openEntry != null
                                && qupath.ext.qpsc.utilities.ImageMetadataManager.isFlippedX(openEntry);
                        boolean flipMacroY = openEntry != null
                                && qupath.ext.qpsc.utilities.ImageMetadataManager.isFlippedY(openEntry);
                        AffineTransformManager.saveSlideAlignment(
                                project,
                                imageName, // Use image name without extension for base_image compatibility
                                state.sample.modality(),
                                transform,
                                null,
                                flipMacroX,
                                flipMacroY,
                                AffineTransformManager.PIXEL_FRAME_MACRO,
                                state.sample.objective(),
                                state.sample.detector());
                        logger.info(
                                "Saved slide-specific transform for image: {} (flipMacroX={}, flipMacroY={}, objective={}, detector={})",
                                imageName,
                                flipMacroX,
                                flipMacroY,
                                state.sample.objective(),
                                state.sample.detector());
                    } else {
                        logger.warn("Cannot save slide-specific transform - no image name available");
                    }

                    return state;
                });
    }
}
