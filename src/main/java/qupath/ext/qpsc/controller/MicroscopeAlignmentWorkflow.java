package qupath.ext.qpsc.controller;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.model.SampleSetupResult;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.*;
import qupath.ext.qpsc.utilities.*;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.roi.interfaces.ROI;

/**
 * Enhanced workflow for creating and saving microscope alignment transforms.
 * This workflow does NOT perform acquisition - it only creates the transform
 * that can be used later in the Existing Image workflow.
 *
 * The saved transform is a GENERAL macro-to-stage transform that works
 * regardless of where the green box appears in future macro images.
 *
 * <h3>Key Improvements:</h3>
 * <ul>
 *   <li>Early bounds detection before alignment</li>
 *   <li>Complete transform validation (macro -&gt; stage)</li>
 *   <li>Ground truth validation support</li>
 *   <li>Proper scaling based on pixel size only</li>
 * </ul>
 *
 * @since 0.3.0
 * @author Mike Nelson
 */
public class MicroscopeAlignmentWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(MicroscopeAlignmentWorkflow.class);

    // Valid annotation classes for acquisition
    private static final List<String> VALID_ANNOTATION_CLASSES =
            Arrays.asList("Tissue", "Scanned Area", "Bounding Box");

    /**
     * Entry point for the microscope alignment workflow.
     * Creates or refines an affine transform between macro and main images.
     */
    public static void run() {
        logger.info("*******************************************");
        logger.info("Starting Microscope Alignment Workflow...");
        logger.info("********************************************");
        QuPathGUI gui = QuPathGUI.getInstance();

        // Check prerequisites
        if (gui.getImageData() == null) {
            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                    "No image is currently open. Please open an image first.", "No Image"));
            return;
        }

        // First, show microscope selection dialog
        MicroscopeSelectionDialog.showDialog()
                .thenAccept(microscopeSelection -> {
                    if (microscopeSelection == null) {
                        logger.info("User cancelled microscope selection");
                        return;
                    }

                    // Check if microscope supports macro images
                    if (!microscopeSelection.hasMacroSupport()) {
                        Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                "The selected microscope '" + microscopeSelection.microscopeName()
                                        + "' does not support macro images.\n\n"
                                        + "Manual alignment is the only option for non-macro image scanners.\n"
                                        + "Please use a different workflow or select a microscope with macro image support.",
                                "No Macro Image Support"));
                        return;
                    }

                    // Store the selected scanner for later use
                    String selectedScanner = microscopeSelection.microscopeName();
                    logger.info("Selected source microscope: {}", selectedScanner);

                    // Check for macro image - first from current image, then trace through project
                    BufferedImage macroImage = MacroImageUtility.retrieveMacroImage(gui);
                    if (macroImage == null && gui.getProject() != null) {
                        logger.info("No macro in current image (may be a flipped/derived entry), "
                                + "tracing through project metadata...");
                        @SuppressWarnings("unchecked")
                        Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
                        macroImage = MacroImageUtility.retrieveMacroImageFromProject(gui, project);
                    }
                    if (macroImage == null) {
                        Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                "No macro image found in the current image or its source images. "
                                        + "A macro image is required for alignment workflow.",
                                "No Macro Image"));
                        return;
                    }

                    // Initialize transform manager
                    String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                    AffineTransformManager transformManager =
                            new AffineTransformManager(new File(configPath).getParent());

                    // Get current microscope from config
                    MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
                    String currentMicroscope = mgr.getMicroscopeName();

                    // Pre-fill sample name from current project if one is open
                    String defaultSampleName = null;
                    if (gui.getProject() != null) {
                        File projectDir = gui.getProject().getPath().toFile().getParentFile();
                        if (projectDir != null) {
                            defaultSampleName = projectDir.getName();
                        }
                    }

                    // Continue with sample setup dialog...
                    SampleSetupController.showDialog(defaultSampleName)
                            .thenCompose(sampleSetup -> {
                                if (sampleSetup == null) {
                                    logger.info("User cancelled at sample setup");
                                    return CompletableFuture.completedFuture(null);
                                }

                                // Now show alignment dialog with proper parameters
                                return MacroImageController.showAlignmentDialog(
                                                gui, transformManager, currentMicroscope)
                                        .thenApply(alignConfig -> {
                                            if (alignConfig == null) {
                                                return null;
                                            }

                                            // Run detection NOW before project creation
                                            MacroImageResults macroImageResults = performDetection(
                                                    gui,
                                                    alignConfig,
                                                    selectedScanner,
                                                    microscopeSelection.configPath());

                                            // Package everything together with the selected scanner
                                            return new CombinedConfig(
                                                    sampleSetup,
                                                    alignConfig,
                                                    macroImageResults,
                                                    selectedScanner,
                                                    microscopeSelection.configPath());
                                        });
                            })
                            .thenAccept(combinedConfig -> {
                                if (combinedConfig == null) {
                                    logger.info("User cancelled alignment workflow");
                                    return;
                                }

                                // Process the alignment with project setup
                                processAlignmentWithProject(gui, combinedConfig, transformManager);
                            })
                            .exceptionally(ex -> {
                                // Check if this is a cancellation - if so, handle gracefully
                                if (ex.getCause() instanceof CancellationException) {
                                    logger.info("User cancelled the workflow");
                                    return null;
                                }

                                logger.error("Alignment workflow failed", ex);
                                Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                        "Workflow error: " + ex.getMessage(), "Alignment Error"));
                                return null;
                            });
                })
                .exceptionally(ex -> {
                    logger.error("Microscope selection failed", ex);
                    Platform.runLater(() -> UIFunctions.notifyUserOfError(
                            "Failed to select microscope: " + ex.getMessage(), "Selection Error"));
                    return null;
                });
    }

    /**
     * Container for sample setup, alignment config, detection results, and selected scanner.
     */
    private record CombinedConfig(
            SampleSetupResult sampleSetup,
            MacroImageController.AlignmentConfig alignmentConfig,
            MacroImageResults macroImageResults,
            String selectedScanner,
            String selectedScannerConfigPath) {}

    /**
     * Container for detection results that need to survive project creation.
     */
    private record MacroImageResults(
            GreenBoxDetector.DetectionResult greenBoxResult,
            MacroImageAnalyzer.MacroAnalysisResult tissueResult,
            AffineTransform greenBoxTransform,
            int macroWidth,
            int macroHeight,
            int cropOffsetX,
            int cropOffsetY,
            int originalMacroWidth,
            int originalMacroHeight,
            Rectangle dataBounds,
            BufferedImage processedMacroImag) {}

    /**
     * Performs all detection BEFORE project creation while macro image is still available.
     * ENHANCED: Now also detects data bounds early.
     */
    private static MacroImageResults performDetection(
            QuPathGUI gui,
            MacroImageController.AlignmentConfig config,
            String selectedScanner,
            String selectedScannerConfigPath) {

        logger.info("Performing detection while macro image is available");

        // Get the original macro image - with project fallback for flipped/derived entries
        BufferedImage originalMacroImage = MacroImageUtility.retrieveMacroImage(gui);
        if (originalMacroImage == null && gui.getProject() != null) {
            logger.info("No macro in current image, tracing through project metadata...");
            @SuppressWarnings("unchecked")
            Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
            originalMacroImage = MacroImageUtility.retrieveMacroImageFromProject(gui, project);
        }
        if (originalMacroImage == null) {
            logger.error("No macro image available");
            return null;
        }

        int originalMacroWidth = originalMacroImage.getWidth();
        int originalMacroHeight = originalMacroImage.getHeight();
        logger.info("Original macro dimensions: {}x{}", originalMacroWidth, originalMacroHeight);

        // ENHANCED: Detect data bounds EARLY
        // NOTE: This is just a placeholder - actual detection will happen after project creation
        Rectangle dataBounds = null;
        logger.info("Data bounds detection will occur after image is added to project with flips");

        // Crop the macro image using the SELECTED SCANNER settings
        logger.info("Using scanner '{}' for macro image processing", selectedScanner);

        // Load the scanner configuration directly to avoid singleton caching
        Map<String, Object> scannerConfig = MinorFunctions.loadYamlFile(selectedScannerConfigPath);

        // Check if scanner requires cropping
        boolean requiresCropping = false;
        MacroImageUtility.CroppedMacroResult croppedResult;

        // Check if this scanner has macro configuration
        @SuppressWarnings("unchecked")
        Map<String, Object> macroConfig = (Map<String, Object>) scannerConfig.get("macro");
        if (macroConfig != null) {
            Boolean cropRequired = MinorFunctions.getYamlBoolean(scannerConfig, "macro", "requires_cropping");
            requiresCropping = cropRequired != null && cropRequired;
        }

        if (requiresCropping) {
            // Get slide bounds from scanner config
            Integer xMin = MinorFunctions.getYamlInteger(scannerConfig, "macro", "slide_bounds", "x_min_px");
            Integer xMax = MinorFunctions.getYamlInteger(scannerConfig, "macro", "slide_bounds", "x_max_px");
            Integer yMin = MinorFunctions.getYamlInteger(scannerConfig, "macro", "slide_bounds", "y_min_px");
            Integer yMax = MinorFunctions.getYamlInteger(scannerConfig, "macro", "slide_bounds", "y_max_px");

            if (xMin != null && xMax != null && yMin != null && yMax != null) {
                croppedResult = MacroImageUtility.cropToSlideArea(originalMacroImage, xMin, xMax, yMin, yMax);
                logger.info(
                        "Cropped macro image using bounds from {}: X[{}-{}], Y[{}-{}]",
                        selectedScanner,
                        xMin,
                        xMax,
                        yMin,
                        yMax);
            } else {
                logger.warn("Scanner '{}' requires cropping but bounds are not properly configured", selectedScanner);
                croppedResult = new MacroImageUtility.CroppedMacroResult(
                        originalMacroImage, originalMacroWidth, originalMacroHeight, 0, 0);
            }
        } else {
            // No cropping needed
            croppedResult = new MacroImageUtility.CroppedMacroResult(
                    originalMacroImage, originalMacroWidth, originalMacroHeight, 0, 0);
            logger.info("Scanner '{}' does not require cropping", selectedScanner);
        }

        BufferedImage croppedMacroImage = croppedResult.getCroppedImage();

        int macroWidth = croppedMacroImage.getWidth();
        int macroHeight = croppedMacroImage.getHeight();
        logger.info(
                "Cropped macro dimensions: {}x{} (offset: {}, {})",
                macroWidth,
                macroHeight,
                croppedResult.getCropOffsetX(),
                croppedResult.getCropOffsetY());

        // Get flip settings - use entry metadata if available (handles flipped/derived images),
        // fall back to preferences for new images not yet in a project
        boolean flipX = QPPreferenceDialog.getFlipMacroXProperty();
        boolean flipY = QPPreferenceDialog.getFlipMacroYProperty();
        if (gui.getProject() != null && gui.getImageData() != null) {
            @SuppressWarnings("unchecked")
            Project<BufferedImage> proj = (Project<BufferedImage>) gui.getProject();
            var currentEntry = proj.getEntry(gui.getImageData());
            if (currentEntry != null && ImageMetadataManager.isFlipped(currentEntry)) {
                flipX = ImageMetadataManager.isFlippedX(currentEntry);
                flipY = ImageMetadataManager.isFlippedY(currentEntry);
                logger.info("Using flip state from entry metadata: flipX={}, flipY={}", flipX, flipY);
            }
        }

        // save a copy of the cropped macro image for future use/realignment
        BufferedImage processedMacroImage = null;
        if (flipX || flipY) {
            processedMacroImage = MacroImageUtility.flipMacroImage(croppedMacroImage, flipX, flipY);
        } else {
            processedMacroImage = croppedMacroImage;
        }

        GreenBoxDetector.DetectionResult greenBoxResult = null;
        AffineTransform greenBoxTransform = null;

        // Try green box detection if enabled
        if (config.useGreenBoxDetection()) {
            try {
                // APPLY FLIPS to match what user saw in preview
                BufferedImage imageForDetection = croppedMacroImage;
                if (flipX || flipY) {
                    imageForDetection = MacroImageUtility.flipMacroImage(croppedMacroImage, flipX, flipY);
                    logger.info("Applied flips for green box detection: X={}, Y={}", flipX, flipY);
                }

                greenBoxResult = GreenBoxDetector.detectGreenBox(imageForDetection, config.greenBoxParams());

                if (greenBoxResult != null && greenBoxResult.getConfidence() > 0.7) {
                    logger.info(
                            "Green box detected in flipped macro with confidence {}", greenBoxResult.getConfidence());

                    // The green box coordinates are now in FLIPPED cropped image space
                    ROI greenBoxFlipped = greenBoxResult.getDetectedBox();
                    logger.info(
                            "Green box in flipped cropped macro: ({}, {}, {}, {})",
                            greenBoxFlipped.getBoundsX(),
                            greenBoxFlipped.getBoundsY(),
                            greenBoxFlipped.getBoundsWidth(),
                            greenBoxFlipped.getBoundsHeight());

                    // Use data bounds if available, otherwise full image dimensions
                    int mainWidth = dataBounds != null
                            ? dataBounds.width
                            : gui.getImageData().getServer().getWidth();
                    int mainHeight = dataBounds != null
                            ? dataBounds.height
                            : gui.getImageData().getServer().getHeight();

                    logger.info("Creating green box transform for main image size: {}x{}", mainWidth, mainHeight);
                    logger.info(
                            "Green box size in macro: {}x{}",
                            greenBoxFlipped.getBoundsWidth(),
                            greenBoxFlipped.getBoundsHeight());

                    // Use the transform function for flipped coordinates
                    greenBoxTransform = TransformationFunctions.calculateMacroFlippedToFullResTransform(
                            greenBoxFlipped, mainWidth, mainHeight);
                }
            } catch (Exception e) {
                logger.error("Error during green box detection", e);
            }
        }

        // Try tissue detection - this might need the original image or can work with cropped
        MacroImageAnalyzer.MacroAnalysisResult tissueResult = null;
        try {
            tissueResult = MacroImageAnalyzer.analyzeMacroImage(
                    gui.getImageData(), config.thresholdMethod(), config.thresholdParams());

            if (tissueResult != null) {
                logger.info(
                        "Tissue analysis found {} regions",
                        tissueResult.getTissueRegions().size());
            }
        } catch (Exception e) {
            logger.error("Error during tissue analysis", e);
        }

        return new MacroImageResults(
                greenBoxResult,
                tissueResult,
                greenBoxTransform,
                macroWidth,
                macroHeight,
                croppedResult.getCropOffsetX(),
                croppedResult.getCropOffsetY(),
                originalMacroWidth,
                originalMacroHeight,
                dataBounds,
                processedMacroImage // Add this
                );
    }

    /**
     * Processes the alignment after detection is complete.
     * ENHANCED: Uses early-detected bounds for proper alignment.
     */
    private static void processAlignmentWithProject(
            QuPathGUI gui, CombinedConfig combinedConfig, AffineTransformManager transformManager) {

        Platform.runLater(() -> {
            try {
                var sampleSetup = combinedConfig.sampleSetup();
                var alignConfig = combinedConfig.alignmentConfig();
                var detectionResults = combinedConfig.macroImageResults();
                var selectedScanner = combinedConfig.selectedScanner();
                var selectedScannerConfigPath = combinedConfig.selectedScannerConfigPath();

                // Create a mutable holder for detection results that may be updated
                final MacroImageResults[] detectionResultsHolder = {detectionResults};

                // Get flip settings - use entry metadata if available (handles flipped/derived images),
                // fall back to preferences for new images not yet in a project
                boolean flipX = QPPreferenceDialog.getFlipMacroXProperty();
                boolean flipY = QPPreferenceDialog.getFlipMacroYProperty();
                if (gui.getProject() != null && gui.getImageData() != null) {
                    @SuppressWarnings("unchecked")
                    Project<BufferedImage> proj = (Project<BufferedImage>) gui.getProject();
                    var currentEntry = proj.getEntry(gui.getImageData());
                    if (currentEntry != null && ImageMetadataManager.isFlipped(currentEntry)) {
                        flipX = ImageMetadataManager.isFlippedX(currentEntry);
                        flipY = ImageMetadataManager.isFlippedY(currentEntry);
                        logger.info("Using flip state from entry metadata: flipX={}, flipY={}", flipX, flipY);
                    }
                }

                Map<String, Object> projectDetails;

                if (gui.getProject() == null) {
                    // Create new project
                    String imagePath =
                            MinorFunctions.extractFilePath(gui.getImageData().getServerPath());

                    if (imagePath == null) {
                        UIFunctions.notifyUserOfError("Cannot extract image path", "Import Error");
                        return;
                    }

                    Project<BufferedImage> project = QPProjectFunctions.createProject(
                            sampleSetup.projectsFolder().getAbsolutePath(), sampleSetup.sampleName());

                    if (project == null) {
                        UIFunctions.notifyUserOfError("Failed to create project", "Project Error");
                        return;
                    }

                    gui.setProject(project);

                    QPProjectFunctions.addImageToProject(
                            new File(imagePath),
                            project,
                            flipX,
                            flipY,
                            null // No modality info in alignment workflow - use auto-detection
                            );

                    gui.refreshProject();

                    // Find and open the entry
                    var entries = project.getImageList();
                    var newEntry = entries.stream()
                            .filter(e -> e.getImageName().equals(new File(imagePath).getName()))
                            .findFirst()
                            .orElse(null);

                    if (newEntry != null) {
                        gui.openImageEntry(newEntry);
                        logger.info("Reopened image with flips applied");
                    }

                    projectDetails = QPProjectFunctions.getCurrentProjectInformation(
                            sampleSetup.projectsFolder().getAbsolutePath(),
                            sampleSetup.sampleName(),
                            sampleSetup.modality());
                } else {
                    // Use existing project - derive folder and name from the project path
                    // instead of relying on the sample setup dialog values
                    File projectDir = gui.getProject().getPath().toFile().getParentFile();
                    String projectsFolderPath = projectDir.getParent();
                    String sampleName = projectDir.getName();
                    logger.info("Using existing project: name='{}', folder='{}'", sampleName, projectsFolderPath);

                    projectDetails = QPProjectFunctions.getCurrentProjectInformation(
                            projectsFolderPath, sampleName, sampleSetup.modality());
                }

                // Ensure the user is viewing the flipped image (if flipping is configured).
                // The Live Viewer and eyepiece show the optically flipped view, so the
                // QuPath image must match for the user to identify corresponding features
                // during alignment point selection.
                if (flipX || flipY) {
                    @SuppressWarnings("unchecked")
                    Project<BufferedImage> currentProject = (Project<BufferedImage>) gui.getProject();
                    if (currentProject != null) {
                        try {
                            Boolean flipResult = ImageFlipHelper.validateAndFlipIfNeeded(
                                            gui, currentProject, sampleSetup.sampleName())
                                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
                            if (Boolean.TRUE.equals(flipResult)) {
                                logger.info("Flipped image loaded for alignment (matches Live Viewer orientation)");
                            } else {
                                logger.warn(
                                        "Image flip validation returned false - alignment may use wrong orientation");
                            }
                        } catch (Exception e) {
                            logger.error("Failed to validate/create flipped image for alignment: {}", e.getMessage());
                        }
                    }
                }

                // Only run tissue detection if there are no existing annotations
                boolean hasExistingAnnotations =
                        !gui.getViewer().getHierarchy().getAnnotationObjects().isEmpty();
                if (hasExistingAnnotations) {
                    logger.info(
                            "Existing annotations found ({} total), skipping tissue detection",
                            gui.getViewer()
                                    .getHierarchy()
                                    .getAnnotationObjects()
                                    .size());
                } else {
                    runTissueDetectionScript(gui);
                }

                // Get stage axis inversion settings (not optical flip -- see CLAUDE.md)
                boolean stageInvertedX = QPPreferenceDialog.getStageInvertedXProperty();
                boolean stageInvertedY = QPPreferenceDialog.getStageInvertedYProperty();

                // Get macro pixel size using the SELECTED SCANNER config
                double macroPixelSize;
                try {
                    // Load the scanner configuration directly
                    Map<String, Object> scannerConfig = MinorFunctions.loadYamlFile(selectedScannerConfigPath);
                    Double pixelSize = MinorFunctions.getYamlDouble(scannerConfig, "macro", "pixel_size_um");

                    if (pixelSize == null || pixelSize <= 0) {
                        String error = String.format(
                                "Scanner '%s' has no valid macro pixel size configured. "
                                        + "This is required for accurate alignment. "
                                        + "Please add 'macro: pixel_size_um:' to the scanner configuration.",
                                selectedScanner);
                        logger.error(error);
                        UIFunctions.notifyUserOfError(
                                error + "\n\nCannot proceed with alignment.", "Configuration Error");
                        return;
                    }

                    macroPixelSize = pixelSize;
                    logger.info(
                            "Using macro pixel size {} um from scanner '{}' configuration",
                            macroPixelSize,
                            selectedScanner);
                } catch (Exception e) {
                    logger.error("Failed to get macro pixel size: {}", e.getMessage());
                    UIFunctions.notifyUserOfError(
                            e.getMessage() + "\n\nCannot proceed with alignment.", "Configuration Error");
                    return;
                }

                double mainPixelSize =
                        gui.getImageData().getServer().getPixelCalibration().getAveragedPixelSizeMicrons();

                // Create tiles for manual alignment
                String tempTileDirectory = (String) projectDetails.get("tempTileDirectory");
                String modeWithIndex = (String) projectDetails.get("imagingModeWithIndex");

                // Create tiles for alignment (in full resolution coordinates)
                createAlignmentTiles(
                        gui,
                        sampleSetup,
                        tempTileDirectory,
                        modeWithIndex,
                        stageInvertedX,
                        stageInvertedY,
                        null); // Pass null for bounds

                // Build an existing transform estimate for auto-move (if refining an existing transform)
                AffineTransform existingTransformEstimate = null;
                if (alignConfig.useExistingTransform()
                        && alignConfig.selectedTransform() != null
                        && detectionResultsHolder[0].greenBoxTransform() != null) {
                    AffineTransform macroToStage =
                            alignConfig.selectedTransform().getTransform();
                    AffineTransform macroFlippedToFullRes = detectionResultsHolder[0].greenBoxTransform();
                    existingTransformEstimate = TransformationFunctions.buildFullResToStageEstimate(
                            macroToStage, macroFlippedToFullRes, mainPixelSize);
                    if (existingTransformEstimate != null) {
                        logger.info("Built fullRes->stage estimate for auto-move during alignment");
                    } else {
                        logger.info("Could not build transform estimate, falling back to manual navigation");
                    }
                }

                // The scaling transform sign must account for BOTH stage inversion AND
                // optical flip. When the image is flipped in QuPath, the pixel coordinates
                // are reversed relative to physical slide coordinates, which has the same
                // effect as stage inversion on the scale factor. XOR combines them:
                //   flip=T, invert=F -> negate (coords are reversed)
                //   flip=F, invert=T -> negate (stage direction is reversed)
                //   flip=T, invert=T -> positive (they cancel out)
                //   flip=F, invert=F -> positive (normal)
                boolean effectiveInvertX = flipX ^ stageInvertedX;
                boolean effectiveInvertY = flipY ^ stageInvertedY;
                logger.info(
                        "Effective scale direction: invertX={} (flip={} ^ stageInvert={}), "
                                + "invertY={} (flip={} ^ stageInvert={})",
                        effectiveInvertX,
                        flipX,
                        stageInvertedX,
                        effectiveInvertY,
                        flipY,
                        stageInvertedY);

                // Setup manual transform - this returns a full-res->stage transform
                AffineTransformationController.setupAffineTransformationAndValidationGUI(
                                mainPixelSize, effectiveInvertX, effectiveInvertY, existingTransformEstimate)
                        .thenAccept(fullResToStageTransform -> {
                            if (fullResToStageTransform == null) {
                                logger.info("Transform setup cancelled");
                                removeAlignmentTiles(gui);
                                return;
                            }

                            logger.info("Alignment transform complete (full-res->stage): {}", fullResToStageTransform);

                            // Set the current transform for immediate use
                            MicroscopeController.getInstance().setCurrentTransform(fullResToStageTransform);

                            // Create and save the general macro->stage transform
                            saveGeneralTransform(
                                    gui,
                                    alignConfig,
                                    fullResToStageTransform,
                                    detectionResultsHolder[0],
                                    macroPixelSize,
                                    stageInvertedX,
                                    stageInvertedY,
                                    transformManager,
                                    selectedScanner);

                            removeAlignmentTiles(gui);
                        })
                        .exceptionally(ex -> {
                            logger.error("Error in transform setup", ex);
                            removeAlignmentTiles(gui);
                            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                    "Transform setup failed: " + ex.getMessage(), "Transform Error"));
                            return null;
                        });

            } catch (Exception e) {
                logger.error("Error processing alignment", e);
                UIFunctions.notifyUserOfError("Failed to process alignment: " + e.getMessage(), "Alignment Error");
            }
        });
    }

    /**
     * Creates tiles for alignment based on available annotations.
     * ENHANCED: Now accepts bounds parameter for proper tile placement.
     */
    private static void createAlignmentTiles(
            QuPathGUI gui,
            SampleSetupResult sampleSetup,
            String tempTileDirectory,
            String modeWithIndex,
            boolean stageInvertedX,
            boolean stageInvertedY,
            Rectangle dataBounds) {

        // First, try to get tissue annotations specifically
        var tissueAnnotations = gui.getViewer().getHierarchy().getAnnotationObjects().stream()
                .filter(a -> a.getClassification() != null && "Tissue".equals(a.getClassification()))
                .toList();

        // Log both optical flip (for image orientation) and stage inversion (for tile traversal)
        boolean flipX = QPPreferenceDialog.getFlipMacroXProperty();
        boolean flipY = QPPreferenceDialog.getFlipMacroYProperty();
        logger.info(
                "Creating alignment tiles: opticalFlip=({}, {}), stageInverted=({}, {})",
                flipX,
                flipY,
                stageInvertedX,
                stageInvertedY);

        // ENHANCED: Use data bounds for tile creation if available
        Rectangle boundsForTiling = dataBounds;
        if (boundsForTiling == null) {
            // Fallback to full image
            boundsForTiling = new Rectangle(
                    0,
                    0,
                    gui.getImageData().getServer().getWidth(),
                    gui.getImageData().getServer().getHeight());
        }
        logger.info(
                "Using data bounds for tiling: x={}, y={}, width={}, height={}",
                boundsForTiling.x,
                boundsForTiling.y,
                boundsForTiling.width,
                boundsForTiling.height);

        // If we have tissue annotations, use those for tiling
        if (!tissueAnnotations.isEmpty()) {
            logger.info("Found {} tissue annotations for tiling", tissueAnnotations.size());
            createTilesForAnnotations(
                    gui,
                    tissueAnnotations,
                    sampleSetup,
                    tempTileDirectory,
                    modeWithIndex,
                    stageInvertedX,
                    stageInvertedY);
            return;
        }

        // Otherwise fall back to any valid annotation class
        logger.info("No tissue annotations found, checking for other valid annotation types");
        var annotations = gui.getViewer().getHierarchy().getAnnotationObjects().stream()
                .filter(a -> a.getClassification() != null && VALID_ANNOTATION_CLASSES.contains(a.getClassification()))
                .toList();

        // Final fallback: use ALL annotations regardless of classification
        if (annotations.isEmpty()) {
            var allAnnotations =
                    new java.util.ArrayList<>(gui.getViewer().getHierarchy().getAnnotationObjects());
            if (!allAnnotations.isEmpty()) {
                logger.info(
                        "No classified annotations found, using all {} annotations for tiling", allAnnotations.size());
                createTilesForAnnotations(
                        gui,
                        allAnnotations,
                        sampleSetup,
                        tempTileDirectory,
                        modeWithIndex,
                        stageInvertedX,
                        stageInvertedY);
                return;
            }
            logger.warn("No annotations found for tiling");
            return;
        }

        logger.info("Found {} annotations for tiling (non-tissue)", annotations.size());
        createTilesForAnnotations(
                gui, annotations, sampleSetup, tempTileDirectory, modeWithIndex, stageInvertedX, stageInvertedY);
    }

    private static void createTilesForAnnotations(
            QuPathGUI gui,
            List<PathObject> annotations,
            SampleSetupResult sampleSetup,
            String tempTileDirectory,
            String modeWithIndex,
            boolean stageInvertedX,
            boolean stageInvertedY) {

        try {
            // Delegate to TilingUtilities with explicit stage inversion parameters
            // These control tile positioning in the grid to match stage coordinate system
            TilingUtilities.createTilesForAnnotations(
                    annotations, sampleSetup, tempTileDirectory, modeWithIndex, stageInvertedX, stageInvertedY);

            logger.info(
                    "Created detection tiles for alignment (stageInvertedX={}, stageInvertedY={})",
                    stageInvertedX,
                    stageInvertedY);

        } catch (IOException e) {
            logger.error("Failed to create tiles", e);
            UIFunctions.notifyUserOfError("Failed to create tiles: " + e.getMessage(), "Tiling Error");
        } catch (IllegalArgumentException e) {
            logger.error("Invalid tile configuration", e);
            UIFunctions.notifyUserOfError("Invalid tile configuration: " + e.getMessage(), "Configuration Error");
        }
    }

    /**
     * Removes all detection objects (alignment tiles) from the image hierarchy.
     * Called at the end of the alignment workflow to clean up temporary tiles.
     */
    private static void removeAlignmentTiles(QuPathGUI gui) {
        try {
            var hierarchy = gui.getViewer().getHierarchy();
            var detections = hierarchy.getDetectionObjects();
            if (!detections.isEmpty()) {
                logger.info("Removing {} alignment tiles from hierarchy", detections.size());
                hierarchy.removeObjects(detections, true);
            }
        } catch (Exception e) {
            logger.warn("Failed to remove alignment tiles: {}", e.getMessage());
        }
    }

    /**
     * Saves a general macro-to-stage transform that maps coordinates from the source scanner's
     * macro image to the target microscope's stage coordinates.
     *
     * <p>The transform is created by:</p>
     * <ol>
     *   <li>Finding the green box center in the macro image</li>
     *   <li>Finding the corresponding data region center in the full-resolution image</li>
     *   <li>Using the manual alignment to map that center to stage coordinates</li>
     *   <li>Creating a transform that aligns these centers with the macro pixel size as scale</li>
     * </ol>
     *
     * @param gui The QuPath GUI instance
     * @param config Alignment configuration including transform name and parameters
     * @param fullResToStageTransform The manually-aligned transform from full-res to stage coordinates
     * @param macroImageResults Detection results from the macro image analysis
     * @param macroPixelSize Physical size of macro pixels in micrometers (typically 81.0)
     * @param stageInvertedX Whether stage X axis is inverted (unused in current implementation)
     * @param stageInvertedY Whether stage Y axis is inverted (unused in current implementation)
     * @param transformManager Manager for saving transform presets
     * @param selectedScanner Name of the source scanner that created the macro image
     */
    private static void saveGeneralTransform(
            QuPathGUI gui,
            MacroImageController.AlignmentConfig config,
            AffineTransform fullResToStageTransform,
            MacroImageResults macroImageResults,
            double macroPixelSize,
            boolean stageInvertedX,
            boolean stageInvertedY,
            AffineTransformManager transformManager,
            String selectedScanner) {

        try {
            // Step 1: Get or detect data bounds (required for accurate alignment)
            Rectangle dataBounds = macroImageResults.dataBounds();
            if (dataBounds == null) {
                // Attempt to detect bounds now
                String scriptDir = new File(QPPreferenceDialog.getTissueDetectionScriptProperty()).getParent();
                if (scriptDir != null) {
                    logger.info("Detecting data bounds...");
                    dataBounds = UIFunctions.executeWithProgress(
                            "Processing Image",
                            "Detecting image boundaries...\nAnalyzing image data - this may take a moment for large images.",
                            () -> ImageProcessing.detectOcus40DataBounds(gui, scriptDir));
                }

                if (dataBounds == null) {
                    throw new IllegalStateException("Cannot create transform without data bounds detection");
                }
            }

            // Data dimensions represent the actual tissue area excluding padding
            int fullResWidth = dataBounds.width;
            int fullResHeight = dataBounds.height;
            logger.info(
                    "Using data bounds: {}x{} at ({}, {})", fullResWidth, fullResHeight, dataBounds.x, dataBounds.y);

            // Step 2: Verify green box detection exists
            if (macroImageResults.greenBoxResult() == null
                    || macroImageResults.greenBoxResult().getDetectedBox() == null) {
                throw new IllegalStateException("Green box detection is required for alignment");
            }

            ROI greenBox = macroImageResults.greenBoxResult().getDetectedBox();

            // Step 3: Log key measurements for debugging
            logger.info("=== TRANSFORM CALCULATION ===");
            logger.info("Macro pixel size: {} um/pixel", macroPixelSize);
            logger.info(
                    "Green box: ({}, {}) size {} x {} pixels",
                    greenBox.getBoundsX(),
                    greenBox.getBoundsY(),
                    greenBox.getBoundsWidth(),
                    greenBox.getBoundsHeight());
            logger.info("Data region: {} x {} pixels", fullResWidth, fullResHeight);

            // Calculate and log the scale relationship
            double fullResPixelSize =
                    gui.getImageData().getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
            double pixelScale = macroPixelSize / fullResPixelSize;
            logger.info("Scale relationship: {} macro pixels = 1 full-res pixel", pixelScale);

            // Step 4: Calculate center points
            // Green box center in macro image coordinates
            double greenBoxCenterX = greenBox.getBoundsX() + greenBox.getBoundsWidth() / 2.0;
            double greenBoxCenterY = greenBox.getBoundsY() + greenBox.getBoundsHeight() / 2.0;

            // Data region center in full-resolution image coordinates
            double fullResCenterX = dataBounds.x + dataBounds.width / 2.0;
            double fullResCenterY = dataBounds.y + dataBounds.height / 2.0;
            logger.info("Green box center: ({}, {})", greenBoxCenterX, greenBoxCenterY);
            logger.info("Data region center: ({}, {})", fullResCenterX, fullResCenterY);

            // Step 5: Transform data center to stage coordinates using manual alignment
            Point2D fullResCenter = new Point2D.Double(fullResCenterX, fullResCenterY);
            Point2D stageCenter = new Point2D.Double();
            fullResToStageTransform.transform(fullResCenter, stageCenter);
            logger.info("Data center maps to stage: ({}, {})", stageCenter.getX(), stageCenter.getY());

            // Step 6: Create the macro-to-stage transform
            // This transform will map macro pixel coordinates directly to stage micrometers
            AffineTransform macroToStageTransform = new AffineTransform();

            // The correct order for the transform:
            // 1. Translate green box center to origin (in pixels)
            // 2. Scale from pixels to micrometers
            // 3. Translate to stage position (in micrometers)

            // Since AffineTransform methods apply in reverse order, we do:
            // First: translate to stage position
            macroToStageTransform.translate(stageCenter.getX(), stageCenter.getY());
            // Second: scale pixels to micrometers
            macroToStageTransform.scale(macroPixelSize, macroPixelSize);
            // Third: move green box center to origin
            macroToStageTransform.translate(-greenBoxCenterX, -greenBoxCenterY);

            logger.info("Transform created:");
            logger.info(
                    "  Green box center ({}, {}) pixels -> Stage center ({}, {}) um",
                    greenBoxCenterX,
                    greenBoxCenterY,
                    stageCenter.getX(),
                    stageCenter.getY());

            // Step 7: Validate the transform
            // Log some key point transformations for debugging
            TransformationFunctions.logTransformDetails("Macro->Stage", macroToStageTransform);

            // Check if transform produces valid stage coordinates
            boolean isValid = TransformationFunctions.validateTransform(
                    macroToStageTransform,
                    macroImageResults.macroWidth(),
                    macroImageResults.macroHeight(),
                    -21000,
                    33000, // Stage X limits in micrometers
                    -9000,
                    11000 // Stage Y limits in micrometers
                    );

            if (!isValid) {
                logger.warn("Transform may produce out-of-bounds stage coordinates");
            }

            // Optional: Validate against ground truth if debug mode and data exists
            if (logger.isDebugEnabled()) {
                validateWithKnownGroundTruth(macroToStageTransform, fullResToStageTransform);
            }

            // Step 8: Save the transform
            String transformName = config.transformName();
            if (transformName == null || transformName.isBlank()) {
                transformName = String.format(
                        "%s_to_%s_%s",
                        selectedScanner,
                        MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty())
                                .getString("microscope", "name"),
                        new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()));
            }

            String description = String.format(
                    "Transform from %s macro to stage. Macro: %dx%d, Green box detected, Validation: %s",
                    selectedScanner,
                    macroImageResults.macroWidth(),
                    macroImageResults.macroHeight(),
                    isValid ? "PASSED" : "WARNING");

            AffineTransformManager.TransformPreset preset = new AffineTransformManager.TransformPreset(
                    transformName,
                    MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty())
                            .getString("microscope", "name"),
                    selectedScanner,
                    macroToStageTransform,
                    description,
                    config.greenBoxParams());

            transformManager.savePreset(preset);
            PersistentPreferences.setSavedTransformName(transformName);
            logger.info("Saved transform: {}", transformName);

            // Step 9: Notify user
            String finalTransformName = transformName;
            Platform.runLater(() -> {
                qupath.fx.dialogs.Dialogs.showInfoNotification(
                        "Transform Saved",
                        String.format("Successfully saved alignment transform: %s", finalTransformName));
            });

        } catch (Exception e) {
            logger.error("Failed to save transform", e);
            Platform.runLater(() -> {
                UIFunctions.notifyUserOfError("Failed to save transform: " + e.getMessage(), "Save Error");
            });
        }
    }

    /**
     * Validates transforms against known ground truth points if available.
     * This is primarily for debugging and development.
     */
    private static void validateWithKnownGroundTruth(
            AffineTransform macroToStageTransform, AffineTransform fullResToStageTransform) {

        // Validate macro->stage transform with known tissue points
        Map<Point2D, Point2D> macroGroundTruth = new HashMap<>();
        macroGroundTruth.put(new Point2D.Double(700, 43), new Point2D.Double(16286.456640000002, -8112.192));
        macroGroundTruth.put(new Point2D.Double(486, 202), new Point2D.Double(-1275, 4864));

        logger.info("=== MACRO->STAGE GROUND TRUTH VALIDATION ===");
        boolean macroValid = TransformationFunctions.validateTransformWithGroundTruth(
                macroToStageTransform, macroGroundTruth, 500.0); // 500um tolerance for macro

        // Validate full-res->stage transform with known points
        Map<Point2D, Point2D> fullResGroundTruth = new HashMap<>();
        fullResGroundTruth.put(
                new Point2D.Double(88945, 2680.11807528976), new Point2D.Double(16286.456640000002, -8112.192));
        fullResGroundTruth.put(new Point2D.Double(15934, 54469), new Point2D.Double(-1275, 4864));

        logger.info("=== FULL-RES->STAGE GROUND TRUTH VALIDATION ===");
        boolean fullResValid = TransformationFunctions.validateTransformWithGroundTruth(
                fullResToStageTransform, fullResGroundTruth, 100.0); // 100um tolerance for full-res

        if (!macroValid || !fullResValid) {
            logger.warn("Ground truth validation failed - transform may need adjustment");
        }
    }
    /**
     * Runs the tissue detection script if configured.
     */
    private static void runTissueDetectionScript(QuPathGUI gui) {
        String tissueScript = QPPreferenceDialog.getTissueDetectionScriptProperty();
        if (tissueScript == null || tissueScript.isBlank()) {
            logger.info("No tissue detection script configured");
            return;
        }

        logger.info("Running tissue detection script");

        try {
            // Get the pixel size for the script
            double pixelSize =
                    gui.getImageData().getServer().getPixelCalibration().getAveragedPixelSizeMicrons();

            // If we have a scanned area annotation, select it first so tissue detection
            // runs within it
            var scannedAreas = gui.getViewer().getHierarchy().getAnnotationObjects().stream()
                    .filter(a -> a.getClassification() != null
                            && ("Scanned Area".equals(a.getClassification())
                                    || "Bounding Box".equals(a.getClassification())))
                    .toList();

            if (!scannedAreas.isEmpty()) {
                // Select the scanned area annotations
                gui.getViewer().getHierarchy().getSelectionModel().selectObjects(scannedAreas);
                logger.info("Selected {} scanned area annotations for tissue detection", scannedAreas.size());
            }

            // Prepare the script with proper parameters
            Map<String, String> scriptPaths = MinorFunctions.calculateScriptPaths(tissueScript);
            String modifiedScript = TileProcessingUtilities.modifyTissueDetectScript(
                    tissueScript, String.valueOf(pixelSize), scriptPaths.get("jsonTissueClassfierPathString"));

            // Run the script
            gui.runScript(null, modifiedScript);
            logger.info("Tissue detection script completed");

            // Clear selection
            gui.getViewer().getHierarchy().getSelectionModel().clearSelection();

            // Log results
            var tissueAnnotations = gui.getViewer().getHierarchy().getAnnotationObjects().stream()
                    .filter(a -> a.getClassification() != null && "Tissue".equals(a.getClassification()))
                    .toList();
            logger.info("Found {} tissue annotations after script", tissueAnnotations.size());

        } catch (Exception e) {
            logger.error("Error running tissue detection script", e);
        }
    }
}
