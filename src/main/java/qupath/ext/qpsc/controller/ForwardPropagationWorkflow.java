package qupath.ext.qpsc.controller;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.stream.Collectors;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTreeCell;
import javafx.scene.layout.*;
import javafx.stage.Stage;
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
 * Bidirectional propagation manager for transferring annotations and detections
 * between base images and their sub-images.
 *
 * <p>Forward: base image objects -> sub-images (using alignment + offset + pixel size)
 * <p>Back: sub-image objects -> base image (using inverse transform)
 *
 * <p>Both directions use the same alignment transform; forward applies it directly,
 * back applies its inverse.
 */
public class ForwardPropagationWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(ForwardPropagationWorkflow.class);

    /** Direction of propagation. */
    public enum Direction {
        FORWARD,
        BACK
    }

    /**
     * Show the propagation manager dialog with Forward as default direction.
     */
    public static void run(QuPathGUI qupath) {
        showManager(qupath, Direction.FORWARD);
    }

    /**
     * Show the propagation manager dialog with Back as default direction.
     */
    public static void runBack(QuPathGUI qupath) {
        showManager(qupath, Direction.BACK);
    }

    /**
     * Show the propagation manager dialog.
     */
    @SuppressWarnings("unchecked")
    public static void showManager(QuPathGUI qupath, Direction defaultDirection) {
        if (qupath == null || qupath.getProject() == null) {
            Dialogs.showErrorMessage("Propagation Manager", "No project is open.");
            return;
        }

        Project<BufferedImage> project = qupath.getProject();

        // Build image groups: base_image_name -> list of ALL entries sharing that base
        // Base-like entries (originals, flipped versions) and sub-images are both tracked
        Map<String, List<ProjectImageEntry<BufferedImage>>> baseVariants = new LinkedHashMap<>();
        Map<String, List<ProjectImageEntry<BufferedImage>>> groups = new LinkedHashMap<>();
        // For backward compat, pick a default base entry per group
        Map<String, ProjectImageEntry<BufferedImage>> baseEntries = new LinkedHashMap<>();

        logger.info("=== Building image groups for propagation ===");
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            String rawBaseName = ImageMetadataManager.getBaseImage(entry);
            String imageName = entry.getImageName();
            String entryName = GeneralTools.stripExtension(imageName);

            String baseName;
            if (rawBaseName != null && !rawBaseName.isEmpty()) {
                baseName = rawBaseName;
            } else {
                baseName = entryName;
            }

            logger.info("  Entry: '{}' -> base_image='{}', effectiveBase='{}'", imageName, rawBaseName, baseName);

            // Is this a base-like entry (original or flipped variant)?
            boolean isBaseVariant = entryName.equals(baseName) || imageName.startsWith(baseName + ".");
            if (isBaseVariant) {
                baseVariants.computeIfAbsent(baseName, k -> new ArrayList<>()).add(entry);
                // Default base entry: prefer flipped (alignment coordinate space)
                boolean isFlipped = ImageMetadataManager.isFlippedX(entry) || ImageMetadataManager.isFlippedY(entry);
                if (isFlipped || !baseEntries.containsKey(baseName)) {
                    baseEntries.put(baseName, entry);
                }
                logger.debug("    -> base variant (flipped={})", isFlipped);
            } else {
                // Sub-image
                groups.computeIfAbsent(baseName, k -> new ArrayList<>()).add(entry);
                logger.debug("    -> sub-image of '{}'", baseName);
            }
        }

        logger.info("Found {} group(s): {}", groups.size(), groups.keySet());
        for (var bv : baseVariants.entrySet()) {
            logger.info(
                    "  Base '{}': {} variant(s): {}",
                    bv.getKey(),
                    bv.getValue().size(),
                    bv.getValue().stream().map(ProjectImageEntry::getImageName).collect(Collectors.joining(", ")));
        }

        // Filter to groups that actually have sub-images
        if (groups.isEmpty()) {
            Dialogs.showInfoNotification(
                    "Propagation Manager",
                    "No image groups found. Sub-images need 'base_image' metadata (set during acquisition).");
            return;
        }

        // Build the dialog
        Stage dialog = new Stage();
        dialog.setTitle("Propagation Manager");
        // Non-modal so the user can still interact with QuPath while the dialog is open
        if (qupath.getStage() != null) dialog.initOwner(qupath.getStage());

        // Direction toggle
        ToggleGroup dirGroup = new ToggleGroup();
        RadioButton forwardBtn = new RadioButton("Forward (Base -> Sub-images)");
        forwardBtn.setToggleGroup(dirGroup);
        forwardBtn.setTooltip(new Tooltip("Copy objects FROM the base image TO sub-images"));
        RadioButton backBtn = new RadioButton("Back (Sub-images -> Base)");
        backBtn.setToggleGroup(dirGroup);
        backBtn.setTooltip(new Tooltip("Copy objects FROM sub-images TO the base image"));
        if (defaultDirection == Direction.FORWARD) forwardBtn.setSelected(true);
        else backBtn.setSelected(true);

        HBox dirBox = new HBox(12, new Label("Direction:"), forwardBtn, backBtn);
        dirBox.setAlignment(Pos.CENTER_LEFT);
        dirBox.setPadding(new Insets(4, 0, 4, 0));

        // Image group tree with checkboxes
        TreeView<String> groupTree = new TreeView<>();
        groupTree.setShowRoot(false);
        TreeItem<String> root = new TreeItem<>("Project");
        groupTree.setRoot(root);

        // Base image selector: let user choose which base variant (original vs flipped)
        // to use as the source/target for propagation
        Map<String, ComboBox<String>> baseSelectors = new LinkedHashMap<>();
        Map<String, Map<String, ProjectImageEntry<BufferedImage>>> baseVariantLookup = new LinkedHashMap<>();
        VBox baseSelectorBox = new VBox(4);

        for (String baseName : groups.keySet()) {
            List<ProjectImageEntry<BufferedImage>> variants = baseVariants.getOrDefault(baseName, List.of());
            if (variants.size() > 1) {
                // Multiple variants (original + flipped) -- let user pick
                ComboBox<String> combo = new ComboBox<>();
                Map<String, ProjectImageEntry<BufferedImage>> lookup = new LinkedHashMap<>();
                for (ProjectImageEntry<BufferedImage> v : variants) {
                    String label = v.getImageName();
                    combo.getItems().add(label);
                    lookup.put(label, v);
                }
                baseVariantLookup.put(baseName, lookup);
                // Default to flipped if available (matches alignment coordinate space)
                ProjectImageEntry<BufferedImage> defaultBase = baseEntries.get(baseName);
                if (defaultBase != null) combo.setValue(defaultBase.getImageName());
                else combo.getSelectionModel().selectFirst();

                combo.setMaxWidth(Double.MAX_VALUE);
                combo.setStyle("-fx-font-size: 10px;");
                Label lbl = new Label("Base image for '" + baseName + "':");
                lbl.setStyle("-fx-font-size: 10px; -fx-font-weight: bold;");
                baseSelectorBox.getChildren().addAll(lbl, combo);
                baseSelectors.put(baseName, combo);

                // Update baseEntries when user changes selection
                combo.setOnAction(ev -> {
                    ProjectImageEntry<BufferedImage> selected = lookup.get(combo.getValue());
                    if (selected != null) baseEntries.put(baseName, selected);
                });
            }
        }

        // Map to track checkbox states
        Map<ProjectImageEntry<BufferedImage>, CheckBoxTreeItem<String>> subItems = new LinkedHashMap<>();

        for (var groupEntry : groups.entrySet()) {
            String baseName = groupEntry.getKey();
            List<ProjectImageEntry<BufferedImage>> subs = groupEntry.getValue();

            CheckBoxTreeItem<String> baseItem = new CheckBoxTreeItem<>(baseName + " (" + subs.size() + " sub-images)");
            baseItem.setSelected(true);
            baseItem.setExpanded(true);

            for (ProjectImageEntry<BufferedImage> sub : subs) {
                CheckBoxTreeItem<String> subItem = new CheckBoxTreeItem<>(sub.getImageName());
                subItem.setSelected(true);
                subItems.put(sub, subItem);
                baseItem.getChildren().add(subItem);
            }

            root.getChildren().add(baseItem);
        }

        // Use CheckBoxTreeCell for the tree
        groupTree.setCellFactory(CheckBoxTreeCell.forTreeView());
        groupTree.setPrefHeight(200);

        // Class filter section
        Label classLabel = new Label("Object classes to propagate:");
        classLabel.setStyle("-fx-font-weight: bold;");

        VBox classBox = new VBox(3);
        // Populate classes dynamically based on direction and selection
        // For now, add a "Refresh Classes" action when direction changes
        Map<PathClass, CheckBox> classCheckboxes = new LinkedHashMap<>();
        CheckBox unclassifiedCheckbox = new CheckBox("Unclassified");
        unclassifiedCheckbox.setSelected(true);

        Runnable refreshClasses = () -> {
            classBox.getChildren().clear();
            classCheckboxes.clear();

            boolean isForward = forwardBtn.isSelected();
            Set<PathClass> allClasses = new TreeSet<>();
            boolean hasUnclassified = false;

            if (isForward) {
                // Forward: collect classes from base images
                for (var gEntry : groups.entrySet()) {
                    ProjectImageEntry<BufferedImage> base = baseEntries.get(gEntry.getKey());
                    if (base == null) continue;
                    try {
                        var data = base.readImageData();
                        for (PathObject obj : data.getHierarchy().getAllObjects(false)) {
                            if (obj.isRootObject() || obj.getROI() == null) continue;
                            if (obj.getPathClass() == null) hasUnclassified = true;
                            else allClasses.add(obj.getPathClass());
                        }
                    } catch (Exception e) {
                        logger.debug("Could not read base image: {}", e.getMessage());
                    }
                }
            } else {
                // Back: collect classes from selected sub-images
                for (var sEntry : subItems.entrySet()) {
                    if (!sEntry.getValue().isSelected()) continue;
                    try {
                        var data = sEntry.getKey().readImageData();
                        for (PathObject obj : data.getHierarchy().getAllObjects(false)) {
                            if (obj.isRootObject() || obj.getROI() == null) continue;
                            if (obj.getPathClass() == null) hasUnclassified = true;
                            else allClasses.add(obj.getPathClass());
                        }
                    } catch (Exception e) {
                        logger.debug("Could not read sub-image: {}", e.getMessage());
                    }
                }
            }

            if (hasUnclassified) {
                unclassifiedCheckbox.setSelected(true);
                classBox.getChildren().add(unclassifiedCheckbox);
            }
            for (PathClass pc : allClasses) {
                CheckBox cb = new CheckBox(pc.toString());
                cb.setSelected(true);
                classCheckboxes.put(pc, cb);
                classBox.getChildren().add(cb);
            }
            if (classBox.getChildren().isEmpty()) {
                classBox.getChildren().add(new Label("(no objects found in source images)"));
            }
        };

        // Refresh classes on direction change
        dirGroup.selectedToggleProperty().addListener((obs, old, newVal) -> refreshClasses.run());

        // Status/results label
        Label statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-font-size: 11px;");

        // Action buttons
        Button propagateBtn = new Button("Propagate");
        propagateBtn.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        propagateBtn.setOnAction(e -> {
            Direction dir = forwardBtn.isSelected() ? Direction.FORWARD : Direction.BACK;

            // Collect selected classes
            Set<PathClass> selectedClasses = classCheckboxes.entrySet().stream()
                    .filter(en -> en.getValue().isSelected())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            boolean includeUnclassified = unclassifiedCheckbox.isSelected();

            if (selectedClasses.isEmpty() && !includeUnclassified) {
                statusLabel.setText("No classes selected.");
                statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: orange;");
                return;
            }

            statusLabel.setText("Propagating...");
            statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

            // Run propagation
            int totalCount = 0;
            int groupCount = 0;
            StringBuilder results = new StringBuilder();

            logger.info("Propagation started: direction={}, {} groups available", dir, groups.size());

            for (var gEntry : groups.entrySet()) {
                String baseName = gEntry.getKey();

                ProjectImageEntry<BufferedImage> base = baseEntries.get(baseName);
                if (base == null) {
                    logger.warn("  Group '{}': base entry not found in project", baseName);
                    results.append(baseName).append(": base not found\n");
                    continue;
                }

                // Get selected sub-images: check each child's checkbox individually
                // (don't rely on parent CheckBoxTreeItem.isSelected which has JavaFX timing issues)
                List<ProjectImageEntry<BufferedImage>> selectedSubs = gEntry.getValue().stream()
                        .filter(sub -> {
                            CheckBoxTreeItem<String> item = subItems.get(sub);
                            return item != null && item.isSelected();
                        })
                        .collect(Collectors.toList());

                logger.info(
                        "  Group '{}': base='{}', {}/{} sub-images selected",
                        baseName,
                        base.getImageName(),
                        selectedSubs.size(),
                        gEntry.getValue().size());

                if (selectedSubs.isEmpty()) continue;

                // Load alignment
                AffineTransform alignment;
                try {
                    alignment = AffineTransformManager.loadSlideAlignment(project, baseName);
                    if (alignment == null) {
                        results.append(baseName).append(": no alignment found\n");
                        continue;
                    }
                } catch (Exception ex) {
                    results.append(baseName)
                            .append(": alignment error: ")
                            .append(ex.getMessage())
                            .append("\n");
                    continue;
                }

                if (dir == Direction.FORWARD) {
                    // Forward: base -> sub-images
                    try {
                        List<PathObject> sourceObjects =
                                loadFilteredObjects(base, selectedClasses, includeUnclassified);
                        if (sourceObjects.isEmpty()) {
                            results.append(baseName).append(": no matching objects\n");
                            continue;
                        }
                        for (ProjectImageEntry<BufferedImage> sub : selectedSubs) {
                            try {
                                int count = propagateForward(alignment, sourceObjects, sub);
                                totalCount += count;
                                String msg = String.format(
                                        "  %s -> %s: %d objects\n", base.getImageName(), sub.getImageName(), count);
                                results.append(msg);
                                logger.info(msg.trim());
                            } catch (Exception ex) {
                                String msg = String.format(
                                        "  %s -> %s: FAILED (%s)\n",
                                        base.getImageName(), sub.getImageName(), ex.getMessage());
                                results.append(msg);
                                logger.error(msg.trim());
                            }
                        }
                        groupCount++;
                    } catch (Exception ex) {
                        results.append(baseName)
                                .append(": read error: ")
                                .append(ex.getMessage())
                                .append("\n");
                    }
                } else {
                    // Back: sub-images -> base
                    try {
                        ImageData<BufferedImage> baseData = base.readImageData();
                        PathObjectHierarchy baseHierarchy = baseData.getHierarchy();
                        int beforeCount = baseHierarchy.getAllObjects(false).size();

                        for (ProjectImageEntry<BufferedImage> sub : selectedSubs) {
                            try {
                                List<PathObject> subObjects =
                                        loadFilteredObjects(sub, selectedClasses, includeUnclassified);
                                if (subObjects.isEmpty()) continue;
                                int count = propagateBack(alignment, subObjects, sub, baseData);
                                totalCount += count;
                                String msg = String.format(
                                        "  %s -> %s: %d objects\n", sub.getImageName(), base.getImageName(), count);
                                results.append(msg);
                                logger.info(msg.trim());
                            } catch (Exception ex) {
                                String msg = String.format(
                                        "  %s -> %s: FAILED (%s)\n",
                                        sub.getImageName(), base.getImageName(), ex.getMessage());
                                results.append(msg);
                                logger.error(msg.trim());
                            }
                        }

                        // Save base image data if objects were added
                        int afterCount = baseHierarchy.getAllObjects(false).size();
                        if (afterCount > beforeCount) {
                            base.saveImageData(baseData);
                        }
                        groupCount++;
                    } catch (Exception ex) {
                        results.append(baseName)
                                .append(": base read error: ")
                                .append(ex.getMessage())
                                .append("\n");
                    }
                }
            }

            String dirLabel = dir == Direction.FORWARD ? "forward" : "back";
            statusLabel.setText(String.format(
                    "Done: %d objects propagated %s across %d group(s).\n%s",
                    totalCount, dirLabel, groupCount, results));
            statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + (totalCount > 0 ? "green" : "#666") + ";");
            logger.info(
                    "Propagation {} complete: {} objects, {} groups\n{}", dirLabel, totalCount, groupCount, results);
        });

        Button refreshBtn = new Button("Refresh Classes");
        refreshBtn.setOnAction(e -> refreshClasses.run());

        Button closeBtn = new Button("Close");
        closeBtn.setOnAction(e -> dialog.close());

        HBox buttonBar = new HBox(8, propagateBtn, refreshBtn, closeBtn);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);

        // Layout
        ScrollPane classScroll = new ScrollPane(classBox);
        classScroll.setFitToWidth(true);
        classScroll.setPrefHeight(100);

        ScrollPane statusScroll = new ScrollPane(statusLabel);
        statusScroll.setFitToWidth(true);
        statusScroll.setPrefHeight(80);

        VBox content = new VBox(8, dirBox, new Separator());
        if (!baseSelectorBox.getChildren().isEmpty()) {
            content.getChildren().addAll(baseSelectorBox, new Separator());
        }
        content.getChildren()
                .addAll(
                        new Label("Sub-images:"),
                        groupTree,
                        new Separator(),
                        classLabel,
                        classScroll,
                        new Separator(),
                        statusScroll,
                        buttonBar);
        content.setPadding(new Insets(10));

        dialog.setScene(new Scene(content, 550, 600));
        dialog.setMinWidth(400);
        dialog.setMinHeight(400);

        // Initial class load
        refreshClasses.run();

        dialog.show();
    }

    // ------------------------------------------------------------------
    // Core propagation logic
    // ------------------------------------------------------------------

    /** Load and filter objects from an image entry. */
    private static List<PathObject> loadFilteredObjects(
            ProjectImageEntry<BufferedImage> entry, Set<PathClass> selectedClasses, boolean includeUnclassified)
            throws Exception {

        ImageData<BufferedImage> data = entry.readImageData();
        return data.getHierarchy().getAllObjects(false).stream()
                .filter(o ->
                        !o.isRootObject() && o.getROI() != null && !o.getROI().isEmpty())
                .filter(o -> {
                    PathClass pc = o.getPathClass();
                    if (pc == null) return includeUnclassified;
                    return selectedClasses.contains(pc);
                })
                .collect(Collectors.toList());
    }

    /**
     * Forward propagation: base image objects -> sub-image.
     * Transform: base_pixels -> stage_microns -> sub_pixels
     */
    private static int propagateForward(
            AffineTransform baseToStage, List<PathObject> sourceObjects, ProjectImageEntry<BufferedImage> subEntry)
            throws Exception {

        ImageData<BufferedImage> subData = subEntry.readImageData();
        PathObjectHierarchy subHierarchy = subData.getHierarchy();

        double subPixelSize = subData.getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
        if (Double.isNaN(subPixelSize) || subPixelSize <= 0) {
            throw new IllegalStateException("Sub-image has no valid pixel size");
        }

        double[] xyOffset = ImageMetadataManager.getXYOffset(subEntry);
        int subWidth = subData.getServer().getWidth();
        int subHeight = subData.getServer().getHeight();

        Map<String, String> subMeta = subEntry.getMetadata();
        boolean subFlipX = "1".equals(subMeta.get(ImageMetadataManager.FLIP_X));
        boolean subFlipY = "1".equals(subMeta.get(ImageMetadataManager.FLIP_Y));

        // The xy_offset in metadata is the annotation top-left in stage microns.
        // But the tile grid starts half a FOV before the annotation edge (TilingUtilities
        // line 99: startX = minX - frameWidth/2). Correct the offset to match the
        // actual image origin.
        double halfFovX = 0;
        double halfFovY = 0;
        try {
            MicroscopeController mc = MicroscopeController.getInstance();
            if (mc != null && mc.isConnected()) {
                double[] fov = mc.getCameraFOV();
                halfFovX = fov[0] / 2.0;
                halfFovY = fov[1] / 2.0;
            }
        } catch (Exception e) {
            logger.debug("Could not get FOV for offset correction: {}", e.getMessage());
        }

        double correctedOffsetX = xyOffset[0] - halfFovX;
        double correctedOffsetY = xyOffset[1] - halfFovY;
        if (halfFovX > 0) {
            logger.info(
                    "Offset correction: ({}, {}) -> ({}, {}) (half FOV = {}, {})",
                    xyOffset[0],
                    xyOffset[1],
                    correctedOffsetX,
                    correctedOffsetY,
                    halfFovX,
                    halfFovY);
        }

        // Build combined transform: base_pixels -> stage_microns -> sub_pixels
        AffineTransform stageToSub = new AffineTransform();
        stageToSub.scale(1.0 / subPixelSize, 1.0 / subPixelSize);
        stageToSub.translate(-correctedOffsetX, -correctedOffsetY);

        AffineTransform combined = new AffineTransform(stageToSub);
        combined.concatenate(baseToStage);

        if (subFlipX || subFlipY) {
            AffineTransform flip = createFlip(subFlipX, subFlipY, subWidth, subHeight);
            AffineTransform withFlip = new AffineTransform(flip);
            withFlip.concatenate(combined);
            combined = withFlip;
        }

        List<PathObject> propagated = transformAndClip(sourceObjects, combined, subWidth, subHeight);

        if (!propagated.isEmpty()) {
            subHierarchy.addObjects(propagated);
            subEntry.saveImageData(subData);
        }

        return propagated.size();
    }

    /**
     * Back propagation: sub-image objects -> base image.
     * Transform: sub_pixels -> stage_microns -> base_pixels
     */
    private static int propagateBack(
            AffineTransform baseToStage,
            List<PathObject> sourceObjects,
            ProjectImageEntry<BufferedImage> subEntry,
            ImageData<BufferedImage> baseData)
            throws Exception {

        PathObjectHierarchy baseHierarchy = baseData.getHierarchy();

        double subPixelSize =
                subEntry.readImageData().getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
        if (Double.isNaN(subPixelSize) || subPixelSize <= 0) {
            throw new IllegalStateException("Sub-image has no valid pixel size");
        }

        double[] xyOffset = ImageMetadataManager.getXYOffset(subEntry);
        int subWidth = subEntry.readImageData().getServer().getWidth();
        int subHeight = subEntry.readImageData().getServer().getHeight();

        Map<String, String> subMeta = subEntry.getMetadata();
        boolean subFlipX = "1".equals(subMeta.get(ImageMetadataManager.FLIP_X));
        boolean subFlipY = "1".equals(subMeta.get(ImageMetadataManager.FLIP_Y));

        int baseWidth = baseData.getServer().getWidth();
        int baseHeight = baseData.getServer().getHeight();

        // Apply same half-FOV correction as forward propagation
        double halfFovX = 0;
        double halfFovY = 0;
        try {
            MicroscopeController mc = MicroscopeController.getInstance();
            if (mc != null && mc.isConnected()) {
                double[] fov = mc.getCameraFOV();
                halfFovX = fov[0] / 2.0;
                halfFovY = fov[1] / 2.0;
            }
        } catch (Exception e) {
            logger.debug("Could not get FOV for offset correction: {}", e.getMessage());
        }
        double correctedOffsetX = xyOffset[0] - halfFovX;
        double correctedOffsetY = xyOffset[1] - halfFovY;

        // Build inverse: sub_pixels -> stage_microns -> base_pixels
        AffineTransform subToStage = new AffineTransform();
        subToStage.translate(correctedOffsetX, correctedOffsetY);
        subToStage.scale(subPixelSize, subPixelSize);

        if (subFlipX || subFlipY) {
            AffineTransform unflip = createFlip(subFlipX, subFlipY, subWidth, subHeight);
            AffineTransform withUnflip = new AffineTransform(subToStage);
            withUnflip.concatenate(unflip);
            subToStage = withUnflip;
        }

        // Invert baseToStage to get stageToBase
        AffineTransform stageToBase;
        try {
            stageToBase = baseToStage.createInverse();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot invert alignment transform", e);
        }

        AffineTransform combined = new AffineTransform(stageToBase);
        combined.concatenate(subToStage);

        List<PathObject> propagated = transformAndClip(sourceObjects, combined, baseWidth, baseHeight);

        if (!propagated.isEmpty()) {
            baseHierarchy.addObjects(propagated);
        }

        return propagated.size();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Transform objects and clip to image bounds. */
    private static List<PathObject> transformAndClip(
            List<PathObject> objects, AffineTransform transform, int imgWidth, int imgHeight) {
        List<PathObject> result = new ArrayList<>();
        for (PathObject obj : objects) {
            try {
                PathObject transformed = PathObjectTools.transformObject(obj, transform, true, true);
                if (transformed == null || transformed.getROI() == null) continue;

                ROI roi = transformed.getROI();
                double cx = roi.getCentroidX();
                double cy = roi.getCentroidY();
                // Keep if centroid is within bounds (with margin for partial overlap)
                if (cx >= -roi.getBoundsWidth()
                        && cx <= imgWidth + roi.getBoundsWidth()
                        && cy >= -roi.getBoundsHeight()
                        && cy <= imgHeight + roi.getBoundsHeight()) {
                    result.add(transformed);
                }
            } catch (Exception e) {
                logger.debug("Could not transform object: {}", e.getMessage());
            }
        }
        return result;
    }

    /** Create a flip transform for the given image dimensions. */
    private static AffineTransform createFlip(boolean flipX, boolean flipY, int width, int height) {
        AffineTransform flip = new AffineTransform();
        if (flipX && flipY) {
            flip.translate(width, height);
            flip.scale(-1, -1);
        } else if (flipX) {
            flip.translate(width, 0);
            flip.scale(-1, 1);
        } else if (flipY) {
            flip.translate(0, height);
            flip.scale(1, -1);
        }
        return flip;
    }
}
