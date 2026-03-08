package qupath.ext.qpsc.modality.ppm.analysis;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Back-propagates annotations from sub-images to parent/base images.
 *
 * <p>Sub-images acquired via the Existing Image workflow are registered
 * to a parent image through an alignment transform and XY offset metadata.
 * This workflow transforms annotations drawn on sub-images back to the
 * parent image's coordinate space, preserving classification and measurements.</p>
 *
 * <p>The coordinate transform chain:</p>
 * <pre>
 * sub-image pixel  --(subPixelSize + xyOffset)--&gt;  stage microns
 *     --(alignment inverse)--&gt;  flipped parent pixel
 *     --(flip transform)--&gt;  original base pixel
 * </pre>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class PPMBackPropagationWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(PPMBackPropagationWorkflow.class);

    /**
     * Groups entries by image collection for back-propagation.
     */
    static class CollectionGroup {
        int collection;
        String sampleName;
        ProjectImageEntry<BufferedImage> flippedParent;
        ProjectImageEntry<BufferedImage> originalBase;
        List<ProjectImageEntry<BufferedImage>> subImages = new ArrayList<>();
    }

    /**
     * Main entry point.
     */
    public static void run() {
        Platform.runLater(() -> {
            try {
                runOnFXThread();
            } catch (Exception e) {
                logger.error("Failed to run back-propagation workflow", e);
                Dialogs.showErrorMessage("Back-Propagation", "Error: " + e.getMessage());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void runOnFXThread() {
        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui == null) {
            Dialogs.showErrorMessage("Back-Propagation", "QuPath GUI not available.");
            return;
        }

        Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
        if (project == null) {
            Dialogs.showErrorMessage("Back-Propagation", "No project is open.");
            return;
        }

        // Step 1: Discover collections with sub-images and parent images
        List<CollectionGroup> collections = discoverCollections(project);
        if (collections.isEmpty()) {
            Dialogs.showInfoNotification("Back-Propagation",
                    "No image collections with sub-images and parent images found.");
            return;
        }

        // Step 2: Collect annotation classes from all sub-images
        List<String> allClasses = collectAnnotationClasses(collections);
        if (allClasses.isEmpty()) {
            Dialogs.showInfoNotification("Back-Propagation",
                    "No classified annotations found on sub-images.");
            return;
        }

        // Step 3: Show configuration dialog
        // -- Target selection --
        ToggleGroup targetGroup = new ToggleGroup();
        RadioButton toOriginalRadio = new RadioButton("Original base image (default)");
        RadioButton toFlippedRadio = new RadioButton("Flipped XY parent");
        toOriginalRadio.setToggleGroup(targetGroup);
        toFlippedRadio.setToggleGroup(targetGroup);
        toOriginalRadio.setSelected(true);

        // Check if any collection is missing the original base
        boolean anyMissingOriginal = collections.stream()
                .anyMatch(g -> g.originalBase == null);
        if (anyMissingOriginal) {
            toOriginalRadio.setText("Original base image (some collections missing original)");
        }

        // -- Annotation class checkboxes --
        List<CheckBox> classCheckBoxes = new ArrayList<>();
        VBox classContent = new VBox(4);
        for (String cls : allClasses) {
            CheckBox cb = new CheckBox(cls);
            cb.setSelected(true);
            classCheckBoxes.add(cb);
            classContent.getChildren().add(cb);
        }
        ScrollPane classScroll = new ScrollPane(classContent);
        classScroll.setFitToWidth(true);
        classScroll.setPrefHeight(Math.min(150, allClasses.size() * 28 + 10));

        HBox classButtons = new HBox(8);
        Button checkAll = new Button("Check All");
        Button checkNone = new Button("Check None");
        checkAll.setOnAction(e -> classCheckBoxes.forEach(cb -> cb.setSelected(true)));
        checkNone.setOnAction(e -> classCheckBoxes.forEach(cb -> cb.setSelected(false)));
        classButtons.getChildren().addAll(checkAll, checkNone);

        // -- Options --
        CheckBox includeMeasurementsCheck = new CheckBox("Include measurements");
        includeMeasurementsCheck.setSelected(true);
        CheckBox lockCheck = new CheckBox("Lock propagated annotations");
        lockCheck.setSelected(true);

        // -- Build dialog content --
        int totalSubImages = collections.stream().mapToInt(g -> g.subImages.size()).sum();
        Label summaryLabel = new Label(String.format(
                "Found %d collection(s) with %d sub-images total.",
                collections.size(), totalSubImages));
        summaryLabel.setFont(Font.font("System", 11));

        Label targetLabel = new Label("Target image:");
        targetLabel.setFont(Font.font("System", FontWeight.BOLD, 11));

        Label classLabel = new Label("Annotation classes to propagate:");
        classLabel.setFont(Font.font("System", FontWeight.BOLD, 11));

        Label optionsLabel = new Label("Options:");
        optionsLabel.setFont(Font.font("System", FontWeight.BOLD, 11));

        VBox dialogContent = new VBox(8);
        dialogContent.setPadding(new Insets(10));
        dialogContent.getChildren().addAll(
                summaryLabel, new Separator(),
                targetLabel, toOriginalRadio, toFlippedRadio, new Separator(),
                classLabel, classButtons, classScroll, new Separator(),
                optionsLabel, includeMeasurementsCheck, lockCheck);

        boolean confirmed = Dialogs.showConfirmDialog("Back-Propagate Annotations", dialogContent);
        if (!confirmed) {
            return;
        }

        // Extract user selections
        boolean toOriginal = toOriginalRadio.isSelected();
        Set<String> selectedClasses = new LinkedHashSet<>();
        for (CheckBox cb : classCheckBoxes) {
            if (cb.isSelected()) {
                selectedClasses.add(cb.getText());
            }
        }
        if (selectedClasses.isEmpty()) {
            Dialogs.showWarningNotification("Back-Propagation", "No annotation classes selected.");
            return;
        }
        boolean includeMeasurements = includeMeasurementsCheck.isSelected();
        boolean lockAnnotations = lockCheck.isSelected();

        // Step 4: Execute back-propagation
        int propagated = executePropagation(
                project, collections, toOriginal, selectedClasses,
                includeMeasurements, lockAnnotations);

        if (propagated > 0) {
            Dialogs.showInfoNotification("Back-Propagation",
                    String.format("Successfully propagated %d annotation(s) to parent images.",
                            propagated));
        } else {
            Dialogs.showInfoNotification("Back-Propagation",
                    "No annotations were propagated. Check the log for details.");
        }
    }

    // ========================================================================
    // Discovery
    // ========================================================================

    /**
     * Discovers image collections that have both sub-images and parent images.
     *
     * <p>Sub-images are identified by having {@code annotation_name} metadata
     * (set during Existing Image acquisition). Parent images are identified
     * by having flip metadata ({@code flip_x} and/or {@code flip_y} set to "1").</p>
     *
     * @param project the QuPath project to scan
     * @return list of collection groups with parent and sub-image references
     */
    @SuppressWarnings("unchecked")
    static List<CollectionGroup> discoverCollections(Project<BufferedImage> project) {
        Map<Integer, CollectionGroup> groups = new LinkedHashMap<>();

        for (ProjectImageEntry<?> rawEntry : project.getImageList()) {
            ProjectImageEntry<BufferedImage> entry =
                    (ProjectImageEntry<BufferedImage>) rawEntry;
            int collection = ImageMetadataManager.getImageCollection(entry);
            if (collection < 0) continue;

            CollectionGroup group = groups.computeIfAbsent(collection, k -> {
                CollectionGroup g = new CollectionGroup();
                g.collection = k;
                return g;
            });

            // Check if this is a flipped parent
            if (ImageMetadataManager.isFlipped(entry)) {
                group.flippedParent = entry;
                group.sampleName = ImageMetadataManager.getSampleName(entry);

                // Find original base via original_image_id
                String origId = ImageMetadataManager.getOriginalImageId(entry);
                if (origId != null) {
                    for (ProjectImageEntry<?> candidate : project.getImageList()) {
                        if (candidate.getID().equals(origId)) {
                            group.originalBase =
                                    (ProjectImageEntry<BufferedImage>) candidate;
                            break;
                        }
                    }
                }
                continue;
            }

            // Check if this is a sub-image (has annotation_name metadata)
            String annotationName = entry.getMetadata()
                    .get(ImageMetadataManager.ANNOTATION_NAME);
            if (annotationName != null && !annotationName.isEmpty()) {
                group.subImages.add(entry);
            }
        }

        // Filter: only keep groups that have both parent and sub-images
        List<CollectionGroup> result = groups.values().stream()
                .filter(g -> g.flippedParent != null && !g.subImages.isEmpty())
                .collect(Collectors.toList());

        for (CollectionGroup g : result) {
            logger.info("Collection {}: {} sub-images, parent='{}', original='{}'",
                    g.collection, g.subImages.size(),
                    g.flippedParent.getImageName(),
                    g.originalBase != null ? g.originalBase.getImageName() : "none");
        }

        return result;
    }

    /**
     * Collects all annotation classes found across sub-images in the
     * given collections. Loads image data from each sub-image to discover
     * classified annotations.
     *
     * @param collections the discovered collection groups
     * @return deduplicated list of annotation class names
     */
    static List<String> collectAnnotationClasses(List<CollectionGroup> collections) {
        Set<String> classes = new LinkedHashSet<>();

        for (CollectionGroup group : collections) {
            for (ProjectImageEntry<BufferedImage> subImage : group.subImages) {
                try {
                    ImageData<BufferedImage> imgData = subImage.readImageData();
                    for (PathObject ann : imgData.getHierarchy().getAnnotationObjects()) {
                        PathClass pc = ann.getPathClass();
                        if (pc != null) {
                            classes.add(pc.toString());
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Could not read annotations from {}: {}",
                            subImage.getImageName(), e.getMessage());
                }
            }
        }

        logger.info("Found {} annotation classes across sub-images: {}", classes.size(), classes);
        return new ArrayList<>(classes);
    }

    // ========================================================================
    // Execution
    // ========================================================================

    /**
     * Executes back-propagation for the given collections.
     *
     * <p>For each collection, loads the alignment transform, computes the
     * coordinate mapping from sub-image pixels to the target image pixels,
     * and transfers annotations of the selected classes.</p>
     *
     * @param project the QuPath project
     * @param collections collection groups to process
     * @param toOriginal true to target the original base image, false for flipped parent
     * @param selectedClasses annotation class names to propagate
     * @param includeMeasurements whether to copy measurements to propagated annotations
     * @param lockAnnotations whether to lock the propagated annotations
     * @return total number of annotations propagated
     */
    @SuppressWarnings("unchecked")
    static int executePropagation(
            Project<BufferedImage> project,
            List<CollectionGroup> collections,
            boolean toOriginal,
            Set<String> selectedClasses,
            boolean includeMeasurements,
            boolean lockAnnotations) {

        int totalPropagated = 0;

        for (CollectionGroup group : collections) {
            // Determine target entry
            ProjectImageEntry<BufferedImage> targetEntry;
            boolean applyFlip;

            if (toOriginal && group.originalBase != null) {
                targetEntry = group.originalBase;
                applyFlip = true;
            } else {
                if (toOriginal && group.originalBase == null) {
                    logger.warn("No original base for collection {}. "
                            + "Using flipped parent instead.", group.collection);
                }
                targetEntry = group.flippedParent;
                applyFlip = false;
            }

            // Load alignment transform
            AffineTransform alignment = AffineTransformManager.loadSlideAlignment(
                    (Project<BufferedImage>) project, group.sampleName);
            if (alignment == null) {
                logger.warn("No alignment transform for sample '{}'. "
                        + "Skipping collection {}.", group.sampleName, group.collection);
                continue;
            }

            // Invert: stage microns -> flipped parent pixels
            AffineTransform stageToFlippedParent;
            try {
                stageToFlippedParent = alignment.createInverse();
            } catch (NoninvertibleTransformException e) {
                logger.error("Cannot invert alignment for '{}'. "
                        + "Skipping collection {}.", group.sampleName, group.collection);
                continue;
            }

            // Load target image data (for dimensions and to add annotations)
            ImageData<BufferedImage> targetData;
            try {
                targetData = targetEntry.readImageData();
            } catch (Exception e) {
                logger.error("Cannot load target image '{}': {}",
                        targetEntry.getImageName(), e.getMessage());
                continue;
            }

            // Compute stage -> target transform
            AffineTransform stageToTarget;
            if (applyFlip) {
                boolean flipX = ImageMetadataManager.isFlippedX(group.flippedParent);
                boolean flipY = ImageMetadataManager.isFlippedY(group.flippedParent);
                double w = targetData.getServer().getWidth();
                double h = targetData.getServer().getHeight();

                AffineTransform flipTransform = createFlipTransform(flipX, flipY, w, h);
                stageToTarget = new AffineTransform(flipTransform);
                stageToTarget.concatenate(stageToFlippedParent);
            } else {
                stageToTarget = stageToFlippedParent;
            }

            // Process each sub-image in this collection
            List<PathObject> propagated = new ArrayList<>();

            for (ProjectImageEntry<BufferedImage> subEntry : group.subImages) {
                try {
                    int count = propagateFromSubImage(
                            subEntry, stageToTarget, selectedClasses,
                            includeMeasurements, lockAnnotations, propagated);
                    if (count > 0) {
                        logger.info("Propagated {} annotations from '{}'",
                                count, subEntry.getImageName());
                    }
                } catch (Exception e) {
                    logger.warn("Error processing sub-image '{}': {}",
                            subEntry.getImageName(), e.getMessage());
                }
            }

            // Add propagated annotations to target and save
            if (!propagated.isEmpty()) {
                targetData.getHierarchy().addObjects(propagated);
                try {
                    targetEntry.saveImageData(targetData);
                    totalPropagated += propagated.size();
                    logger.info("Saved {} propagated annotations to '{}' "
                            + "for collection {}",
                            propagated.size(), targetEntry.getImageName(),
                            group.collection);
                } catch (Exception e) {
                    logger.error("Failed to save target image '{}': {}",
                            targetEntry.getImageName(), e.getMessage());
                }
            }
        }

        return totalPropagated;
    }

    /**
     * Propagates matching annotations from a single sub-image.
     *
     * @param subEntry the sub-image project entry
     * @param stageToTarget transform from stage microns to target image pixels
     * @param selectedClasses classes to include
     * @param includeMeasurements whether to copy measurements
     * @param lockAnnotations whether to lock annotations
     * @param outAnnotations list to add transformed annotations to
     * @return number of annotations propagated from this sub-image
     */
    private static int propagateFromSubImage(
            ProjectImageEntry<BufferedImage> subEntry,
            AffineTransform stageToTarget,
            Set<String> selectedClasses,
            boolean includeMeasurements,
            boolean lockAnnotations,
            List<PathObject> outAnnotations) throws Exception {

        ImageData<BufferedImage> subData = subEntry.readImageData();
        double subPixelSize = subData.getServer()
                .getPixelCalibration().getPixelWidthMicrons();

        if (Double.isNaN(subPixelSize) || subPixelSize <= 0) {
            logger.warn("Invalid pixel size ({}) for '{}'. Skipping.",
                    subPixelSize, subEntry.getImageName());
            return 0;
        }

        double[] xyOffset = ImageMetadataManager.getXYOffset(subEntry);
        String annotationName = subEntry.getMetadata()
                .get(ImageMetadataManager.ANNOTATION_NAME);
        if (annotationName == null) {
            annotationName = "unknown";
        }

        // Build sub pixel -> stage transform:
        //   stageX = subPixelX * subPixelSize + xyOffsetX
        //   stageY = subPixelY * subPixelSize + xyOffsetY
        AffineTransform subToStage = new AffineTransform(
                subPixelSize, 0, 0, subPixelSize, xyOffset[0], xyOffset[1]);

        // Combined: sub pixel -> target pixel
        AffineTransform subToTarget = new AffineTransform(stageToTarget);
        subToTarget.concatenate(subToStage);

        int count = 0;
        for (PathObject ann : subData.getHierarchy().getAnnotationObjects()) {
            PathClass pc = ann.getPathClass();
            if (pc == null || !selectedClasses.contains(pc.toString())) {
                continue;
            }

            PathObject transformed = PathObjectTools.transformObject(
                    ann, subToTarget, false, includeMeasurements);
            if (transformed == null) {
                continue;
            }

            // Name: prefix with annotation_name (acquisition region identifier)
            String originalName = ann.getName();
            if (originalName != null && !originalName.isEmpty()) {
                transformed.setName(annotationName + ": " + originalName);
            } else {
                // Name by centroid in the target image pixel space
                double cx = transformed.getROI().getCentroidX();
                double cy = transformed.getROI().getCentroidY();
                transformed.setName(String.format("%s: (%.0f, %.0f)",
                        annotationName, cx, cy));
            }

            if (lockAnnotations) {
                transformed.setLocked(true);
            }

            outAnnotations.add(transformed);
            count++;
        }

        return count;
    }

    // ========================================================================
    // Programmatic API (for batch workflow integration)
    // ========================================================================

    /**
     * Back-propagates annotations for a single collection. Intended for
     * programmatic use from the batch analysis workflow.
     *
     * @param project the QuPath project
     * @param collectionNumber the image collection number
     * @param annotationClasses class names to propagate
     * @param toOriginal true for original base, false for flipped parent
     * @param includeMeasurements whether to copy measurements
     * @return number of annotations propagated
     */
    public static int propagateForCollection(
            Project<BufferedImage> project,
            int collectionNumber,
            Set<String> annotationClasses,
            boolean toOriginal,
            boolean includeMeasurements) {

        List<CollectionGroup> allCollections = discoverCollections(project);
        List<CollectionGroup> matching = allCollections.stream()
                .filter(g -> g.collection == collectionNumber)
                .collect(Collectors.toList());

        if (matching.isEmpty()) {
            logger.warn("No collection {} found for back-propagation", collectionNumber);
            return 0;
        }

        return executePropagation(project, matching, toOriginal,
                annotationClasses, includeMeasurements, true);
    }

    // ========================================================================
    // Transform helpers
    // ========================================================================

    /**
     * Creates an AffineTransform that maps flipped image pixels to original
     * image pixels (or vice versa -- the transform is self-inverse).
     *
     * @param flipX whether the image is flipped on the X axis
     * @param flipY whether the image is flipped on the Y axis
     * @param width image width in pixels
     * @param height image height in pixels
     * @return the flip AffineTransform, or identity if no flip
     */
    static AffineTransform createFlipTransform(
            boolean flipX, boolean flipY, double width, double height) {
        AffineTransform t = new AffineTransform();
        if (flipX && flipY) {
            t.scale(-1, -1);
            t.translate(-width, -height);
        } else if (flipX) {
            t.scale(-1, 1);
            t.translate(-width, 0);
        } else if (flipY) {
            t.scale(1, -1);
            t.translate(0, -height);
        }
        return t;
    }
}
