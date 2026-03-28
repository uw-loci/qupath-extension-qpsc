package qupath.ext.qpsc.controller;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.stream.Collectors;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.roi.interfaces.ROI;

/**
 * Forward propagation: transfer annotations and detections from a base image
 * to its sub-images (acquired stitched images).
 *
 * <p>Coordinate flow:
 * <ol>
 *   <li>Base image annotation ROI (base image pixels)</li>
 *   <li>Transform to stage microns via alignment transform</li>
 *   <li>Transform to sub-image pixels via XY offset and pixel size</li>
 *   <li>Clip to sub-image bounds</li>
 * </ol>
 *
 * <p>This is the inverse of back-propagation (sub-image -> base image).
 */
public class ForwardPropagationWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(ForwardPropagationWorkflow.class);

    /**
     * Run the forward propagation workflow from the currently open image.
     */
    public static void run(QuPathGUI qupath) {
        if (qupath == null || qupath.getProject() == null) {
            Dialogs.showErrorMessage("Forward Propagation", "No project is open.");
            return;
        }

        Project<BufferedImage> project = qupath.getProject();
        ImageData<BufferedImage> currentImageData = qupath.getImageData();
        if (currentImageData == null) {
            Dialogs.showErrorMessage("Forward Propagation", "No image is open.");
            return;
        }

        ProjectImageEntry<BufferedImage> currentEntry = project.getEntry(currentImageData);
        if (currentEntry == null) {
            Dialogs.showErrorMessage("Forward Propagation", "Current image is not in the project.");
            return;
        }

        // Determine the base image name (either this image IS the base, or it has one in metadata)
        String currentName = GeneralTools.stripExtension(currentEntry.getImageName());
        String baseImageName = ImageMetadataManager.getBaseImage(currentEntry);
        if (baseImageName == null || baseImageName.isEmpty()) {
            baseImageName = currentName;
        }

        // The source (base) image should be the current image or we find it in the project
        ProjectImageEntry<BufferedImage> baseEntry;
        PathObjectHierarchy baseHierarchy;
        if (currentName.equals(baseImageName) || currentEntry.getImageName().startsWith(baseImageName)) {
            // Current image IS the base
            baseEntry = currentEntry;
            baseHierarchy = currentImageData.getHierarchy();
        } else {
            // Current image is a sub-image; find the base
            baseEntry = findEntryByBaseName(project, baseImageName);
            if (baseEntry == null) {
                Dialogs.showErrorMessage(
                        "Forward Propagation", "Could not find base image '" + baseImageName + "' in the project.");
                return;
            }
            try {
                baseHierarchy = baseEntry.readImageData().getHierarchy();
            } catch (Exception e) {
                Dialogs.showErrorMessage("Forward Propagation", "Could not read base image data: " + e.getMessage());
                return;
            }
        }

        // Collect all annotations and detections from the base image
        Collection<PathObject> allObjects = baseHierarchy.getAllObjects(false);
        List<PathObject> sourceObjects = allObjects.stream()
                .filter(o ->
                        !o.isRootObject() && o.getROI() != null && !o.getROI().isEmpty())
                .collect(Collectors.toList());

        if (sourceObjects.isEmpty()) {
            Dialogs.showInfoNotification("Forward Propagation", "No annotations or detections in the base image.");
            return;
        }

        // Collect unique classification classes
        Set<PathClass> classes = sourceObjects.stream()
                .map(PathObject::getPathClass)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
        // Add null class for unclassified
        boolean hasUnclassified = sourceObjects.stream().anyMatch(o -> o.getPathClass() == null);

        // Find sub-images that reference this base image
        final String fBaseImageName = baseImageName;
        List<ProjectImageEntry<BufferedImage>> subImages = project.getImageList().stream()
                .filter(e -> {
                    String bImg = ImageMetadataManager.getBaseImage(e);
                    String eName = GeneralTools.stripExtension(e.getImageName());
                    return bImg != null && bImg.equals(fBaseImageName) && !eName.equals(fBaseImageName);
                })
                .collect(Collectors.toList());

        if (subImages.isEmpty()) {
            Dialogs.showInfoNotification(
                    "Forward Propagation", "No sub-images found for base image '" + baseImageName + "'.");
            return;
        }

        // Show dialog for user to select classes and target images
        var selection = showSelectionDialog(classes, hasUnclassified, sourceObjects, subImages, baseImageName);
        if (selection == null) return; // Cancelled

        // Load the alignment transform for the base image
        AffineTransform baseToStage;
        try {
            baseToStage = AffineTransformManager.loadSlideAlignment(project, baseImageName);
            if (baseToStage == null) {
                Dialogs.showErrorMessage(
                        "Forward Propagation",
                        "No alignment transform found for '" + baseImageName + "'.\n"
                                + "Run Microscope Alignment first.");
                return;
            }
            logger.info("Loaded alignment for '{}': {}", baseImageName, baseToStage);
        } catch (Exception e) {
            Dialogs.showErrorMessage("Forward Propagation", "Could not load alignment: " + e.getMessage());
            return;
        }

        // Filter source objects by selected classes
        List<PathObject> filtered = sourceObjects.stream()
                .filter(o -> {
                    PathClass pc = o.getPathClass();
                    if (pc == null) return selection.includeUnclassified;
                    return selection.selectedClasses.contains(pc);
                })
                .collect(Collectors.toList());

        if (filtered.isEmpty()) {
            Dialogs.showInfoNotification("Forward Propagation", "No objects match the selected classes.");
            return;
        }

        // Propagate to each selected sub-image
        int totalPropagated = 0;
        int targetCount = 0;
        StringBuilder results = new StringBuilder();

        for (ProjectImageEntry<BufferedImage> subEntry : selection.selectedSubImages) {
            try {
                int count = propagateToSubImage(baseToStage, filtered, subEntry, project);
                totalPropagated += count;
                targetCount++;
                results.append(String.format("  %s: %d objects\n", subEntry.getImageName(), count));
                logger.info("Propagated {} objects to '{}'", count, subEntry.getImageName());
            } catch (Exception e) {
                logger.error("Failed to propagate to '{}': {}", subEntry.getImageName(), e.getMessage());
                results.append(String.format("  %s: FAILED (%s)\n", subEntry.getImageName(), e.getMessage()));
            }
        }

        Dialogs.showInfoNotification(
                "Forward Propagation",
                String.format("Propagated %d objects to %d sub-image(s).", totalPropagated, targetCount));
        logger.info(
                "Forward propagation complete: {} objects to {} targets\n{}", totalPropagated, targetCount, results);
    }

    /**
     * Propagate objects from the base image to a single sub-image.
     *
     * @return number of objects successfully propagated
     */
    private static int propagateToSubImage(
            AffineTransform baseToStage,
            List<PathObject> sourceObjects,
            ProjectImageEntry<BufferedImage> subEntry,
            Project<BufferedImage> project)
            throws Exception {

        // Read sub-image data
        ImageData<BufferedImage> subData = subEntry.readImageData();
        PathObjectHierarchy subHierarchy = subData.getHierarchy();

        // Get sub-image pixel calibration
        double subPixelSize = subData.getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
        if (Double.isNaN(subPixelSize) || subPixelSize <= 0) {
            throw new IllegalStateException("Sub-image has no valid pixel size calibration");
        }

        // Get sub-image XY offset in stage microns
        double[] xyOffset = ImageMetadataManager.getXYOffset(subEntry);

        // Get sub-image dimensions in pixels
        int subWidth = subData.getServer().getWidth();
        int subHeight = subData.getServer().getHeight();

        // Check for flip status on sub-image
        Map<String, String> subMeta = subEntry.getMetadata();
        boolean subFlipX = "1".equals(subMeta.get(ImageMetadataManager.FLIP_X));
        boolean subFlipY = "1".equals(subMeta.get(ImageMetadataManager.FLIP_Y));

        logger.info(
                "Sub-image '{}': pixel_size={} um, offset=({}, {}) um, size={}x{}, flip=({},{})",
                subEntry.getImageName(),
                subPixelSize,
                xyOffset[0],
                xyOffset[1],
                subWidth,
                subHeight,
                subFlipX,
                subFlipY);

        // Build the combined transform: base_pixels -> stage_microns -> sub_pixels
        // Step 1: base_pixels -> stage_microns (alignment transform)
        // Step 2: stage_microns -> sub_pixels = (stage - offset) / pixelSize
        //   which is: scale(1/px, 1/px) * translate(-offsetX, -offsetY)

        AffineTransform stageToSubPixels = new AffineTransform();
        // Scale: microns -> pixels
        stageToSubPixels.scale(1.0 / subPixelSize, 1.0 / subPixelSize);
        // Translate: subtract XY offset (in microns, before scaling)
        stageToSubPixels.translate(-xyOffset[0], -xyOffset[1]);

        // Combined: base_pixels -> sub_pixels
        AffineTransform combined = new AffineTransform(stageToSubPixels);
        combined.concatenate(baseToStage);

        // If sub-image is flipped, apply flip correction
        if (subFlipX || subFlipY) {
            AffineTransform flipCorrection = new AffineTransform();
            if (subFlipX && subFlipY) {
                flipCorrection.translate(subWidth, subHeight);
                flipCorrection.scale(-1, -1);
            } else if (subFlipX) {
                flipCorrection.translate(subWidth, 0);
                flipCorrection.scale(-1, 1);
            } else {
                flipCorrection.translate(0, subHeight);
                flipCorrection.scale(1, -1);
            }
            // Flip is applied AFTER the coordinate transform
            AffineTransform withFlip = new AffineTransform(flipCorrection);
            withFlip.concatenate(combined);
            combined = withFlip;
        }

        logger.debug("Combined transform (base->sub): {}", combined);

        // Transform and clip each object
        List<PathObject> propagated = new ArrayList<>();
        for (PathObject obj : sourceObjects) {
            try {
                PathObject transformed = PathObjectTools.transformObject(obj, combined, true, true);
                if (transformed == null || transformed.getROI() == null) continue;

                ROI roi = transformed.getROI();
                // Check if the ROI overlaps with the sub-image bounds
                double cx = roi.getCentroidX();
                double cy = roi.getCentroidY();
                if (cx >= -roi.getBoundsWidth()
                        && cx <= subWidth + roi.getBoundsWidth()
                        && cy >= -roi.getBoundsHeight()
                        && cy <= subHeight + roi.getBoundsHeight()) {
                    propagated.add(transformed);
                }
            } catch (Exception e) {
                logger.debug("Could not transform object '{}': {}", obj.getDisplayedName(), e.getMessage());
            }
        }

        if (!propagated.isEmpty()) {
            subHierarchy.addObjects(propagated);
            // Save the modified hierarchy
            subEntry.saveImageData(subData);
        }

        return propagated.size();
    }

    /**
     * Find a project entry by its base name (stripped of extension).
     */
    private static ProjectImageEntry<BufferedImage> findEntryByBaseName(
            Project<BufferedImage> project, String baseName) {
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            String name = GeneralTools.stripExtension(entry.getImageName());
            if (name.equals(baseName)) return entry;
        }
        return null;
    }

    // --- Selection Dialog ---

    private record PropagationSelection(
            Set<PathClass> selectedClasses,
            boolean includeUnclassified,
            List<ProjectImageEntry<BufferedImage>> selectedSubImages) {}

    private static PropagationSelection showSelectionDialog(
            Set<PathClass> availableClasses,
            boolean hasUnclassified,
            List<PathObject> sourceObjects,
            List<ProjectImageEntry<BufferedImage>> subImages,
            String baseImageName) {

        Dialog<PropagationSelection> dialog = new Dialog<>();
        dialog.setTitle("Forward Propagation");
        dialog.setHeaderText("Propagate objects from '" + baseImageName + "' to sub-images");

        // Object type summary
        long annotationCount =
                sourceObjects.stream().filter(PathObject::isAnnotation).count();
        long detectionCount =
                sourceObjects.stream().filter(PathObject::isDetection).count();
        Label summary =
                new Label(String.format("Source: %d annotations, %d detections", annotationCount, detectionCount));
        summary.setStyle("-fx-font-size: 11px;");

        // Class selection
        Label classLabel = new Label("Select object classes to propagate:");
        classLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");

        VBox classCheckboxes = new VBox(4);
        Map<PathClass, CheckBox> classBoxMap = new LinkedHashMap<>();

        if (hasUnclassified) {
            CheckBox unclassifiedBox = new CheckBox("Unclassified");
            unclassifiedBox.setSelected(true);
            classBoxMap.put(null, unclassifiedBox);
            classCheckboxes.getChildren().add(unclassifiedBox);
        }
        for (PathClass pc : availableClasses) {
            CheckBox cb = new CheckBox(pc.toString());
            cb.setSelected(true);
            classBoxMap.put(pc, cb);
            classCheckboxes.getChildren().add(cb);
        }

        // Sub-image selection
        Label subLabel = new Label("Select target sub-images:");
        subLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");

        VBox subCheckboxes = new VBox(4);
        Map<ProjectImageEntry<BufferedImage>, CheckBox> subBoxMap = new LinkedHashMap<>();
        for (ProjectImageEntry<BufferedImage> sub : subImages) {
            CheckBox cb = new CheckBox(sub.getImageName());
            cb.setSelected(true);
            subBoxMap.put(sub, cb);
            subCheckboxes.getChildren().add(cb);
        }

        ScrollPane subScroll = new ScrollPane(subCheckboxes);
        subScroll.setFitToWidth(true);
        subScroll.setPrefHeight(150);

        VBox content = new VBox(
                8, summary, new Separator(), classLabel, classCheckboxes, new Separator(), subLabel, subScroll);
        content.setPrefWidth(450);

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.setResultConverter(btn -> {
            if (btn != ButtonType.OK) return null;

            Set<PathClass> selectedClasses = new HashSet<>();
            boolean includeUnclassified = false;
            for (var entry : classBoxMap.entrySet()) {
                if (entry.getValue().isSelected()) {
                    if (entry.getKey() == null) {
                        includeUnclassified = true;
                    } else {
                        selectedClasses.add(entry.getKey());
                    }
                }
            }

            List<ProjectImageEntry<BufferedImage>> selectedSubs = subBoxMap.entrySet().stream()
                    .filter(e -> e.getValue().isSelected())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

            if (selectedClasses.isEmpty() && !includeUnclassified) return null;
            if (selectedSubs.isEmpty()) return null;

            return new PropagationSelection(selectedClasses, includeUnclassified, selectedSubs);
        });

        return dialog.showAndWait().orElse(null);
    }
}
