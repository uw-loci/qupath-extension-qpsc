package qupath.ext.qpsc.controller.workflow;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.MinorFunctions;
import qupath.ext.qpsc.utilities.TileProcessingUtilities;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;

/**
 * Helper class for annotation management in the workflow.
 *
 * <p>This class provides utilities for:
 * <ul>
 *   <li>Ensuring valid annotations exist before acquisition</li>
 *   <li>Running automatic tissue detection if configured</li>
 *   <li>Validating annotation classes and properties</li>
 *   <li>Auto-naming unnamed annotations</li>
 * </ul>
 *
 * <p>Valid annotation classes are "Tissue", "Scanned Area", and "Bounding Box".
 * These represent different types of regions that can be acquired.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class AnnotationHelper {
    private static final Logger logger = LoggerFactory.getLogger(AnnotationHelper.class);

    /**
     * Ensures annotations exist for acquisition.
     *
     * <p>This method follows this logic:
     * <ol>
     *   <li>Check for existing valid annotations based on selected classes</li>
     *   <li>If none found, check for configured tissue detection script</li>
     *   <li>If no script configured, prompt user to select one</li>
     *   <li>Run tissue detection to create annotations</li>
     *   <li>Ensure all annotations have names</li>
     * </ol>
     *
     * @param gui QuPath GUI instance
     * @param macroPixelSize Pixel size of macro image in micrometers
     * @param validClasses List of class names to consider valid
     * @return List of valid annotations (may be empty if none created)
     */
    public static List<PathObject> ensureAnnotationsExist(
            QuPathGUI gui, double macroPixelSize, List<String> validClasses) {
        logger.info("Ensuring annotations exist for acquisition with classes: {}", validClasses);

        // Get existing annotations using GUI hierarchy (not QP static context)
        // This is critical after image flip operations to get the correct annotations
        List<PathObject> annotations = getCurrentValidAnnotations(gui, validClasses);

        if (!annotations.isEmpty()) {
            logger.info("Found {} existing valid annotations", annotations.size());
            ensureAnnotationNames(annotations);
            return annotations;
        }

        logger.info("No existing annotations found, checking tissue detection script");

        // Run tissue detection if configured
        String tissueScript = QPPreferenceDialog.getTissueDetectionScriptProperty();

        if (tissueScript == null || tissueScript.isBlank()) {
            logger.info("No tissue detection script configured, prompting user");
            tissueScript = promptForTissueDetectionScript();
        }

        if (tissueScript != null && !tissueScript.isBlank()) {
            // Stain-based tissue detection (setColorDeconvolutionStains) requires
            // a set image type. When it is UNSET the script throws an opaque core
            // error ("Cannot set color deconvolution stains for image type Not
            // set"); surface an actionable message instead. Most often seen on a
            // flipped duplicate whose persisted type had not been written yet --
            // see QPProjectFunctions.createFlippedDuplicate.
            if (gui.getImageData() == null
                    || gui.getImageData().getImageType() == null
                    || gui.getImageData().getImageType() == qupath.lib.images.ImageData.ImageType.UNSET) {
                logger.warn("Image type is not set; cannot run stain-based tissue detection");
                Platform.runLater(() -> UIFunctions.notifyUserOfError(
                        "The image type is not set for the current image, so automatic tissue "
                                + "detection cannot run (stain-based detection needs a set type, e.g. H&E).\n\n"
                                + "Set the image type (Image tab -> Set image type), or draw annotations "
                                + "manually, then continue.",
                        "Tissue detection: image type not set"));
                return Collections.emptyList();
            }
            try {
                logger.info("Running tissue detection script: {}", tissueScript);

                // Get current image pixel size
                double pixelSize =
                        gui.getImageData().getServer().getPixelCalibration().getAveragedPixelSizeMicrons();

                // Calculate script paths and modify script with parameters
                Map<String, String> scriptPaths = MinorFunctions.calculateScriptPaths(tissueScript);
                String modifiedScript = TileProcessingUtilities.modifyTissueDetectScript(
                        tissueScript, String.valueOf(pixelSize), scriptPaths.get("jsonTissueClassfierPathString"));

                // Run the script
                gui.runScript(null, modifiedScript);
                logger.info("Tissue detection completed");

                // Re-collect annotations after tissue detection using GUI hierarchy
                annotations = getCurrentValidAnnotations(gui, validClasses);
                logger.info("Found {} annotations after tissue detection", annotations.size());

            } catch (Exception e) {
                logger.error("Error running tissue detection", e);
            }
        }

        if (annotations.isEmpty()) {
            logger.warn("Still no valid annotations after tissue detection");
            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                    "No valid annotations found. Please create annotations with one of these classes:\n"
                            + String.join(", ", validClasses),
                    "No Annotations"));
        } else {
            ensureAnnotationNames(annotations);
        }

        return annotations;
    }

    /**
     * Ensures annotations exist using the default selected classes from preferences.
     *
     * @param gui QuPath GUI instance
     * @param macroPixelSize Pixel size of macro image in micrometers
     * @return List of valid annotations (may be empty if none created)
     */
    public static List<PathObject> ensureAnnotationsExist(QuPathGUI gui, double macroPixelSize) {
        List<String> selectedClasses = PersistentPreferences.getSelectedAnnotationClasses();
        return ensureAnnotationsExist(gui, macroPixelSize, selectedClasses);
    }

    /**
     * Gets current valid annotations from the image hierarchy using custom class list.
     *
     * <p>IMPORTANT: This method uses the GUI's current image hierarchy directly,
     * ensuring we get annotations from the currently displayed image (including
     * after flip operations). This is more reliable than using QP.getAnnotationObjects()
     * which relies on the static scripting context that may not be synchronized.
     *
     * @param gui QuPath GUI instance
     * @param validClasses List of class names to consider valid
     * @return List of valid annotations
     */
    public static List<PathObject> getCurrentValidAnnotations(QuPathGUI gui, List<String> validClasses) {
        if (gui.getImageData() == null) {
            logger.warn("No image data available - cannot get annotations");
            return Collections.emptyList();
        }

        // Enhanced logging to help diagnose tile positioning issues.
        // Includes the project entry name + flip metadata in addition to the underlying server
        // path: a flipped duplicate's TransformedImageServer reports the same path as the
        // unflipped base SVS, so the path alone cannot tell us whether annotations are being
        // read in the flipped or unflipped pixel frame. The entry name + FLIP_X/Y disambiguate.
        String imageName = gui.getImageData().getServer().getPath();
        String entryDescription = "<no project>";
        if (gui.getProject() != null) {
            try {
                @SuppressWarnings("unchecked")
                qupath.lib.projects.Project<java.awt.image.BufferedImage> proj =
                        (qupath.lib.projects.Project<java.awt.image.BufferedImage>) gui.getProject();
                qupath.lib.projects.ProjectImageEntry<java.awt.image.BufferedImage> entry =
                        proj.getEntry(gui.getImageData());
                if (entry != null) {
                    boolean[] parity = qupath.ext.qpsc.utilities.ImageMetadataManager.bakedParity(entry);
                    entryDescription = String.format(
                            "name='%s' bakedParityX=%s bakedParityY=%s base_image='%s' original_image_id='%s'",
                            entry.getImageName(),
                            parity[0],
                            parity[1],
                            entry.getMetadata().get(qupath.ext.qpsc.utilities.ImageMetadataManager.BASE_IMAGE),
                            entry.getMetadata().get(qupath.ext.qpsc.utilities.ImageMetadataManager.ORIGINAL_IMAGE_ID));
                } else {
                    entryDescription = "<no matching project entry for current ImageData>";
                }
            } catch (Exception e) {
                entryDescription = "<entry lookup error: " + e.getMessage() + ">";
            }
        }
        logger.info("Retrieving annotations from image: server={} entry=[{}]", imageName, entryDescription);

        var hierarchy = gui.getImageData().getHierarchy();
        var allAnnotations = hierarchy.getAnnotationObjects();

        // If validClasses is null or empty, accept ALL annotations (don't filter by class)
        // This handles the case where no classes are selected yet but tissue detection created annotations
        var annotations = allAnnotations.stream()
                .filter(ann -> ann.getROI() != null && !ann.getROI().isEmpty());

        // Only filter by class if validClasses is provided and not empty
        if (validClasses != null && !validClasses.isEmpty()) {
            annotations = annotations.filter(ann -> ann.getPathClass() != null
                    && validClasses.contains(ann.getPathClass().getName()));
            logger.debug("Filtering by annotation classes: {}", validClasses);
        } else {
            logger.debug("No class filter - accepting all annotations with valid ROIs");
        }

        var finalAnnotations = annotations.collect(Collectors.toList());

        // Log annotation positions to help diagnose coordinate issues
        if (!finalAnnotations.isEmpty()) {
            PathObject firstAnn = finalAnnotations.get(0);
            logger.info(
                    "Found {} valid annotations. First annotation '{}' at position: ({}, {}) size: {}x{}",
                    finalAnnotations.size(),
                    firstAnn.getName() != null ? firstAnn.getName() : "unnamed",
                    firstAnn.getROI().getBoundsX(),
                    firstAnn.getROI().getBoundsY(),
                    firstAnn.getROI().getBoundsWidth(),
                    firstAnn.getROI().getBoundsHeight());
        } else {
            logger.debug(
                    "Found {} valid annotations from {} total (using GUI hierarchy)",
                    finalAnnotations.size(),
                    allAnnotations.size());
        }

        return finalAnnotations;
    }

    /**
     * Gets current valid annotations using selected classes from preferences.
     *
     * @param gui QuPath GUI instance
     * @return List of valid annotations
     */
    public static List<PathObject> getCurrentValidAnnotations(QuPathGUI gui) {
        List<String> selectedClasses = PersistentPreferences.getSelectedAnnotationClasses();
        return getCurrentValidAnnotations(gui, selectedClasses);
    }

    /**
     * Ensures all annotations have names.
     *
     * <p>Unnamed annotations are given auto-generated names based on their
     * class and centroid position. This ensures unique identification during
     * acquisition and file organization.
     *
     * @param annotations List of annotations to check and name
     */
    private static void ensureAnnotationNames(List<PathObject> annotations) {
        int unnamedCount = 0;
        for (PathObject ann : annotations) {
            if (ann.getName() == null || ann.getName().trim().isEmpty()) {
                String className =
                        ann.getPathClass() != null ? ann.getPathClass().getName() : "Annotation";

                // Create name based on class and position
                String name = String.format(
                        "%s_%d_%d",
                        className,
                        Math.round(ann.getROI().getCentroidX()),
                        Math.round(ann.getROI().getCentroidY()));

                ann.setName(name);
                logger.info("Auto-named annotation: {}", name);
                unnamedCount++;
            }
        }

        if (unnamedCount > 0) {
            logger.info("Auto-named {} annotations", unnamedCount);
        }
    }

    /**
     * Runs tissue detection and returns the resulting annotations.
     * This method does not check for existing annotations first - it always attempts to run detection.
     *
     * @param gui QuPath GUI instance
     * @param validClasses List of class names to consider valid
     * @return List of annotations created by tissue detection (may be empty if detection fails or is cancelled)
     */
    public static List<PathObject> runTissueDetection(QuPathGUI gui, List<String> validClasses) {
        logger.info("Running tissue detection");

        String tissueScript = QPPreferenceDialog.getTissueDetectionScriptProperty();

        if (tissueScript == null || tissueScript.isBlank()) {
            logger.info("No tissue detection script configured, prompting user");
            tissueScript = promptForTissueDetectionScript();
        }

        if (tissueScript != null && !tissueScript.isBlank()) {
            // Stain-based tissue detection (setColorDeconvolutionStains) requires
            // a set image type. When it is UNSET the script throws an opaque core
            // error ("Cannot set color deconvolution stains for image type Not
            // set"); surface an actionable message instead. Most often seen on a
            // flipped duplicate whose persisted type had not been written yet --
            // see QPProjectFunctions.createFlippedDuplicate.
            if (gui.getImageData() == null
                    || gui.getImageData().getImageType() == null
                    || gui.getImageData().getImageType() == qupath.lib.images.ImageData.ImageType.UNSET) {
                logger.warn("Image type is not set; cannot run stain-based tissue detection");
                Platform.runLater(() -> UIFunctions.notifyUserOfError(
                        "The image type is not set for the current image, so automatic tissue "
                                + "detection cannot run (stain-based detection needs a set type, e.g. H&E).\n\n"
                                + "Set the image type (Image tab -> Set image type), or draw annotations "
                                + "manually, then continue.",
                        "Tissue detection: image type not set"));
                return Collections.emptyList();
            }
            try {
                logger.info("Running tissue detection script: {}", tissueScript);

                // Get current image pixel size
                double pixelSize =
                        gui.getImageData().getServer().getPixelCalibration().getAveragedPixelSizeMicrons();

                // Calculate script paths and modify script with parameters
                Map<String, String> scriptPaths = MinorFunctions.calculateScriptPaths(tissueScript);
                String modifiedScript = TileProcessingUtilities.modifyTissueDetectScript(
                        tissueScript, String.valueOf(pixelSize), scriptPaths.get("jsonTissueClassfierPathString"));

                // Run the script
                gui.runScript(null, modifiedScript);
                logger.info("Tissue detection completed");

                // Collect annotations after tissue detection
                List<PathObject> annotations = getCurrentValidAnnotations(gui, validClasses);
                logger.info("Found {} annotations after tissue detection", annotations.size());

                if (!annotations.isEmpty()) {
                    ensureAnnotationNames(annotations);
                }

                return annotations;

            } catch (Exception e) {
                logger.error("Error running tissue detection", e);
                Platform.runLater(() -> UIFunctions.notifyUserOfError(
                        "Error running tissue detection: " + e.getMessage(), "Tissue Detection"));
            }
        }

        return Collections.emptyList();
    }

    // TODO this should probably be a part of another dialog.

    /**
     * Prompts user to select a tissue detection script.
     *
     * <p>Shows a dialog asking if the user wants to run automatic tissue detection,
     * and if so, allows them to select a Groovy script file.
     *
     * @return Path to selected script or null if cancelled
     */
    private static String promptForTissueDetectionScript() {
        CompletableFuture<String> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            var useDetection = Dialogs.showYesNoDialog(
                    "Tissue Detection",
                    "Would you like to run automatic tissue detection?\n\n"
                            + "This will create annotations for tissue regions.");

            if (!useDetection) {
                future.complete(null);
                return;
            }

            // Show file chooser for script selection
            javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
            fileChooser.setTitle("Select Tissue Detection Script");
            fileChooser
                    .getExtensionFilters()
                    .addAll(
                            new javafx.stage.FileChooser.ExtensionFilter("Groovy Scripts", "*.groovy"),
                            new javafx.stage.FileChooser.ExtensionFilter("All Files", "*.*"));

            File selectedFile = fileChooser.showOpenDialog(null);
            future.complete(selectedFile != null ? selectedFile.getAbsolutePath() : null);
        });

        try {
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Error selecting tissue detection script", e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Source-macro annotation transfer onto a rotated/flipped companion.
    //
    // A "(rotated N) (Camera View)" companion is created directly (multi-slide)
    // or on demand (single-slide flip) and starts empty: its annotations are a
    // deterministic function of the source macro's, transformed through the same
    // rotate+flip that produced its pixels. These helpers bring the source's
    // annotations onto such a companion so the operator's macro annotations are
    // visible to the acquisition dialogs. Shared by ExistingAlignmentPath (its
    // Tier-2 annotation gate) and ExistingImageWorkflowV2 (the pre-dialog
    // populate for multi-slide companions).
    // ------------------------------------------------------------------

    /**
     * Builds the affine mapping original macro pixels to the final (rotated THEN flipped)
     * frame. Rotation follows QuPath's {@code RotatedImageServer} quarter-rotation mapping;
     * the flip is applied in the rotated frame with its (swapped for 90/270) dimensions and
     * matches {@code ForwardPropagationWorkflow.createFlip}.
     *
     * @param rotationDeg 0 / 90 / 180 / 270
     * @param wr final (rotated-frame) width in pixels
     * @param hr final (rotated-frame) height in pixels
     */
    public static java.awt.geom.AffineTransform originalToFinalTransform(
            int rotationDeg, int wr, int hr, boolean flipX, boolean flipY) {
        // Original (un-rotated) dimensions: for 90/270 the axes are swapped.
        double wo = (rotationDeg == 90 || rotationDeg == 270) ? hr : wr;
        double ho = (rotationDeg == 90 || rotationDeg == 270) ? wr : hr;

        java.awt.geom.AffineTransform rotate;
        switch (rotationDeg) {
            case 90 -> rotate = new java.awt.geom.AffineTransform(0, 1, -1, 0, ho, 0); // x'=ho-y, y'=x
            case 180 -> rotate = new java.awt.geom.AffineTransform(-1, 0, 0, -1, wo, ho); // x'=wo-x, y'=ho-y
            case 270 -> rotate = new java.awt.geom.AffineTransform(0, -1, 1, 0, 0, wo); // x'=y, y'=wo-x
            default -> rotate = new java.awt.geom.AffineTransform(); // identity (0 deg)
        }

        // Flip in the rotated frame (dimensions wr x hr).
        java.awt.geom.AffineTransform flip = new java.awt.geom.AffineTransform();
        if (flipX && flipY) {
            flip.translate(wr, hr);
            flip.scale(-1, -1);
        } else if (flipX) {
            flip.translate(wr, 0);
            flip.scale(-1, 1);
        } else if (flipY) {
            flip.translate(0, hr);
            flip.scale(1, -1);
        }

        java.awt.geom.AffineTransform t = new java.awt.geom.AffineTransform(flip);
        t.concatenate(rotate); // apply rotate first, then flip
        return t;
    }

    /**
     * Finds the original macro entry (the source scan carrying the annotations) that the
     * given rotated/flipped entry derives from: an entry with NO {@code (rotated}/{@code
     * Camera View} marker whose extension-stripped name (or {@code base_image}) matches the
     * open entry's {@code base_image}. Returns null when none matches.
     */
    public static qupath.lib.projects.ProjectImageEntry<java.awt.image.BufferedImage> findSourceMacroEntry(
            qupath.lib.projects.Project<java.awt.image.BufferedImage> project,
            qupath.lib.projects.ProjectImageEntry<java.awt.image.BufferedImage> openEntry) {
        String baseImage = qupath.ext.qpsc.utilities.ImageMetadataManager.getBaseImage(openEntry);
        if (baseImage == null || baseImage.isEmpty()) {
            return null;
        }
        for (var entry : project.getImageList()) {
            String name = entry.getImageName();
            // Skip derived companions (rotated and/or camera-view) via METADATA, not the
            // name -- the source macro is the un-rotated, non-camera-view original.
            if (name == null
                    || qupath.ext.qpsc.utilities.ImageMetadataManager.getRotationDegrees(entry) != 0
                    || qupath.ext.qpsc.utilities.ImageMetadataManager.isCameraView(entry)) {
                continue;
            }
            String stripped = qupath.lib.common.GeneralTools.stripExtension(name);
            if (baseImage.equals(name)
                    || baseImage.equals(stripped)
                    || baseImage.equals(qupath.ext.qpsc.utilities.ImageMetadataManager.getBaseImage(entry))) {
                return entry;
            }
        }
        return null;
    }

    /**
     * When the open microscope-frame entry (a {@code (rotated N) (Camera View)} companion)
     * is EMPTY, transform the source macro's annotations through the same rotation+flip that
     * produced this entry, add them to the open entry's hierarchy, persist, and return them.
     * Returns null when there is no source, the source has no annotations, or the transform
     * yields nothing (caller then falls through to its own creation/dialog path).
     *
     * <p>Composite maps original macro pixels -&gt; rotated frame -&gt; flipped frame:
     * {@code transform = flip . rotate}. Rotation degrees are parsed from the open entry's
     * name; the final (rotated-frame) dimensions come from its live server.
     *
     * @param flipX net baked X parity of the companion (its {@code bakedParity[0]})
     * @param flipY net baked Y parity of the companion (its {@code bakedParity[1]})
     */
    public static List<PathObject> bringSourceAnnotationsOntoOpenEntry(
            QuPathGUI gui,
            qupath.lib.projects.Project<java.awt.image.BufferedImage> project,
            qupath.lib.projects.ProjectImageEntry<java.awt.image.BufferedImage> openEntry,
            boolean flipX,
            boolean flipY) {
        try {
            // Rotation comes from metadata (source of truth), never the entry name.
            int rotationDeg = qupath.ext.qpsc.utilities.ImageMetadataManager.getRotationDegrees(openEntry);
            // Final (rotated-frame) dimensions from the open entry's server.
            int wr = gui.getImageData().getServer().getWidth();
            int hr = gui.getImageData().getServer().getHeight();

            qupath.lib.projects.ProjectImageEntry<java.awt.image.BufferedImage> source =
                    findSourceMacroEntry(project, openEntry);
            if (source == null) {
                logger.info(
                        "bringSourceAnnotations: no source macro entry found for '{}'; leaving target empty",
                        openEntry.getImageName());
                return null;
            }

            qupath.lib.images.ImageData<java.awt.image.BufferedImage> sourceData = source.readImageData();
            List<PathObject> sourceAnnotations;
            try {
                sourceAnnotations = sourceData.getHierarchy().getAnnotationObjects().stream()
                        .filter(a -> a.getROI() != null && !a.getROI().isEmpty())
                        .collect(Collectors.toList());
            } finally {
                try {
                    sourceData.getServer().close();
                } catch (Exception ignore) {
                    // best-effort: reading annotations does not touch pixels
                }
            }
            if (sourceAnnotations.isEmpty()) {
                logger.info(
                        "bringSourceAnnotations: source macro '{}' has no annotations; leaving target empty",
                        source.getImageName());
                return null;
            }

            java.awt.geom.AffineTransform transform = originalToFinalTransform(rotationDeg, wr, hr, flipX, flipY);
            List<PathObject> transformed = new java.util.ArrayList<>();
            for (PathObject ann : sourceAnnotations) {
                PathObject copy = qupath.lib.objects.PathObjectTools.transformObject(ann, transform, true, true);
                if (copy != null && copy.getROI() != null && !copy.getROI().isEmpty()) {
                    transformed.add(copy);
                }
            }
            if (transformed.isEmpty()) {
                logger.warn(
                        "bringSourceAnnotations: transform produced no valid annotations from {} source object(s)",
                        sourceAnnotations.size());
                return null;
            }

            gui.getImageData().getHierarchy().addObjects(transformed);
            try {
                openEntry.saveImageData(gui.getImageData());
                project.syncChanges();
            } catch (Exception e) {
                logger.warn(
                        "bringSourceAnnotations: could not persist brought-through annotations on '{}': {}",
                        openEntry.getImageName(),
                        e.getMessage());
            }
            logger.info(
                    "bringSourceAnnotations: brought {} annotation(s) from source '{}' through rotate {}deg + "
                            + "flip(x={},y={}) onto '{}'",
                    transformed.size(),
                    source.getImageName(),
                    rotationDeg,
                    flipX,
                    flipY,
                    openEntry.getImageName());
            return transformed;
        } catch (Exception e) {
            logger.error(
                    "bringSourceAnnotations: failed to bring source annotations onto '{}': {}",
                    openEntry.getImageName(),
                    e.getMessage());
            return null;
        }
    }
}
