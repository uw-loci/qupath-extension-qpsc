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
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
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
                    // Back: sub-images -> ALL base-like siblings (fan-out).
                    // The single `base` chosen by the dialog is no longer load-bearing;
                    // propagateBackFanOut resolves the canonical sibling itself from the
                    // active microscope's preset and delta-flips into every other sibling.
                    int groupTotal = 0;
                    boolean anyPropagated = false;
                    for (ProjectImageEntry<BufferedImage> sub : selectedSubs) {
                        try {
                            List<PathObject> subObjects =
                                    loadFilteredObjects(sub, selectedClasses, includeUnclassified);
                            if (subObjects.isEmpty()) continue;
                            FanOutResult fo = propagateBackFanOut(
                                    project, baseName, alignment, subObjects, sub, true);
                            groupTotal += fo.totalObjects;
                            totalCount += fo.totalObjects;
                            for (String line : fo.perSiblingLog) {
                                results.append(line).append("\n");
                            }
                            if (fo.siblingsAutoCreated > 0) {
                                String autoMsg = String.format(
                                        "  (auto-created %d sibling(s) for fan-out)\n",
                                        fo.siblingsAutoCreated);
                                results.append(autoMsg);
                                logger.info(autoMsg.trim());
                            }
                            anyPropagated = anyPropagated || fo.siblingsUpdated > 0;
                        } catch (Exception ex) {
                            String msg = String.format(
                                    "  %s -> [fan-out]: FAILED (%s)\n",
                                    sub.getImageName(), ex.getMessage());
                            results.append(msg);
                            logger.error(msg.trim(), ex);
                        }
                    }
                    if (groupTotal > 0 || anyPropagated) {
                        groupCount++;
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

        dialog.show();

        // Defer initial class refresh until after dialog is shown and layout is complete
        javafx.application.Platform.runLater(refreshClasses::run);
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
        //
        // Resolve FOV from (in priority order):
        //   1. Per-image metadata (fov_x_um / fov_y_um) -- always correct
        //   2. Config file (modality + objective + detector from metadata) -- works offline
        //   3. Live microscope connection -- fallback if nothing else available
        double halfFovX = 0;
        double halfFovY = 0;
        double[] fov = resolveFovForEntry(subEntry);
        if (fov != null) {
            halfFovX = fov[0] / 2.0;
            halfFovY = fov[1] / 2.0;
        } else {
            logger.warn("Could not determine FOV for sub-image '{}'. "
                    + "Offset correction will be skipped -- propagated objects may be shifted by half a FOV.",
                    subEntry.getImageName());
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

        // Apply same half-FOV correction as forward propagation (see resolveFovForEntry)
        double halfFovX = 0;
        double halfFovY = 0;
        double[] fov = resolveFovForEntry(subEntry);
        if (fov != null) {
            halfFovX = fov[0] / 2.0;
            halfFovY = fov[1] / 2.0;
        } else {
            logger.warn("Could not determine FOV for sub-image '{}'. "
                    + "Offset correction will be skipped.", subEntry.getImageName());
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

    /** Aggregate result of a multi-sibling back-prop fan-out. */
    public static class FanOutResult {
        public final int totalObjects;
        public final int siblingsUpdated;
        public final int siblingsAutoCreated;
        public final List<String> perSiblingLog;

        FanOutResult(int totalObjects, int siblingsUpdated, int siblingsAutoCreated, List<String> perSiblingLog) {
            this.totalObjects = totalObjects;
            this.siblingsUpdated = siblingsUpdated;
            this.siblingsAutoCreated = siblingsAutoCreated;
            this.perSiblingLog = perSiblingLog;
        }
    }

    /**
     * Back-propagation that fans out across every base-like sibling of
     * {@code baseName} (unflipped, flipped X, flipped Y, flipped XY).
     *
     * <p>Workflow:
     * <ol>
     *   <li>Resolve the canonical target by reading the active microscope's
     *       saved {@link AffineTransformManager.TransformPreset} for the source
     *       scanner used by this slide alignment, and finding the sibling whose
     *       FLIP_X/FLIP_Y match the preset's flipMacroX/flipMacroY. If absent,
     *       auto-create it via {@link QPProjectFunctions#createFlippedDuplicate}
     *       (when {@code allowAutoCreate} is true).</li>
     *   <li>Run the existing single-target {@code propagateBack} math against
     *       the canonical target.</li>
     *   <li>For every other sibling, take the just-propagated annotations
     *       (already in the canonical sibling's pixel frame) and re-flip them
     *       by the XOR delta of (canonical flip) vs (sibling flip).</li>
     * </ol>
     *
     * <p>Newly auto-created siblings start empty; this fan-out is the first
     * write into them. Any existing annotations on other siblings are preserved
     * and the propagated objects are appended.
     *
     * @param project the QuPath project (used to find / create siblings)
     * @param baseName the base image name (without extension)
     * @param baseToStage the per-slide alignment for the active microscope
     * @param sourceObjects sub-image-frame objects to back-propagate
     * @param subEntry the sub-acquisition entry these objects came from
     * @param allowAutoCreate whether missing siblings may be auto-created
     * @return aggregate result; never null
     */
    public static FanOutResult propagateBackFanOut(
            Project<BufferedImage> project,
            String baseName,
            AffineTransform baseToStage,
            List<PathObject> sourceObjects,
            ProjectImageEntry<BufferedImage> subEntry,
            boolean allowAutoCreate)
            throws Exception {

        List<String> perSiblingLog = new ArrayList<>();

        // 1. Determine the canonical (alignment-frame) flip for this active microscope.
        boolean canonicalFlipX = false;
        boolean canonicalFlipY = false;
        String activeMicroscope = null;
        AffineTransformManager.TransformPreset activePreset = null;
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (configPath != null && !configPath.isEmpty()) {
                MicroscopeConfigManager configMgr = MicroscopeConfigManager.getInstance(configPath);
                activeMicroscope = configMgr.getMicroscopeName();
                AffineTransformManager mgr =
                        new AffineTransformManager(new java.io.File(configPath).getParent());
                // The slide alignment was built for the active microscope. Pick the most-recent
                // preset for any source scanner -- they should all share the same target-microscope
                // flip frame, so any of them gives us the canonical flip. Prefer one with a
                // recorded flip state.
                List<String> scanners = mgr.getDistinctSourceScannersForMicroscope(activeMicroscope);
                for (String scanner : scanners) {
                    AffineTransformManager.TransformPreset p = mgr.getBestPresetForPair(scanner, activeMicroscope);
                    if (p != null && p.hasFlipState()) {
                        activePreset = p;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Could not load active microscope preset for canonical-flip resolution: {}", e.getMessage());
        }
        if (activePreset != null) {
            canonicalFlipX = Boolean.TRUE.equals(activePreset.getFlipMacroX());
            canonicalFlipY = Boolean.TRUE.equals(activePreset.getFlipMacroY());
            logger.info(
                    "BackProp fan-out: alignment expects flipX={} flipY={} based on preset '{}' (microscope={})",
                    canonicalFlipX, canonicalFlipY, activePreset.getName(), activeMicroscope);
        } else {
            logger.warn("BackProp fan-out: no active preset with flip state found; "
                    + "treating canonical frame as unflipped (legacy behavior).");
        }

        // 2. Find or create the canonical sibling.
        ProjectImageEntry<BufferedImage> canonical =
                ImageMetadataManager.findSiblingWithFlip(project, baseName, canonicalFlipX, canonicalFlipY);
        int siblingsAutoCreated = 0;

        if (canonical == null) {
            if (!allowAutoCreate) {
                throw new IllegalStateException(String.format(
                        "No sibling of '%s' with flipX=%s, flipY=%s exists, and auto-create is disabled.",
                        baseName, canonicalFlipX, canonicalFlipY));
            }
            // Find the unflipped root to seed the duplicate.
            ProjectImageEntry<BufferedImage> root =
                    ImageMetadataManager.findSiblingWithFlip(project, baseName, false, false);
            if (root == null) {
                // Project has no unflipped sibling; pick whatever exists and undo to root by name.
                List<ProjectImageEntry<BufferedImage>> siblings =
                        ImageMetadataManager.getSiblingsByBaseImage(project, baseName);
                if (siblings.isEmpty()) {
                    throw new IllegalStateException("No siblings of base '" + baseName + "' found in project.");
                }
                root = siblings.get(0);
                logger.warn("No unflipped sibling for '{}'; seeding canonical from '{}'",
                        baseName, root.getImageName());
            }
            String sampleName = ImageMetadataManager.getSampleName(root);
            if (sampleName == null) sampleName = baseName;
            logger.warn("Auto-creating canonical sibling for '{}' (flipX={}, flipY={}) from '{}'",
                    baseName, canonicalFlipX, canonicalFlipY, root.getImageName());
            canonical = QPProjectFunctions.createFlippedDuplicate(
                    project, root, canonicalFlipX, canonicalFlipY, sampleName);
            if (canonical == null) {
                throw new IllegalStateException(
                        "Failed to auto-create canonical sibling for base '" + baseName + "'.");
            }
            project.syncChanges();
            siblingsAutoCreated++;
        }

        // 3. Run the back-prop math against the canonical sibling.
        ImageData<BufferedImage> canonicalData = canonical.readImageData();
        PathObjectHierarchy canonicalHierarchy = canonicalData.getHierarchy();
        int beforeCount = canonicalHierarchy.getAllObjects(false).size();
        int writtenToCanonical = propagateBack(baseToStage, sourceObjects, subEntry, canonicalData);
        int afterCount = canonicalHierarchy.getAllObjects(false).size();
        int totalObjects = writtenToCanonical;
        int siblingsUpdated = 0;
        if (afterCount > beforeCount) {
            canonical.saveImageData(canonicalData);
            siblingsUpdated++;
            perSiblingLog.add(String.format("  %s -> %s: %d objects (canonical, flipX=%s, flipY=%s)",
                    subEntry.getImageName(), canonical.getImageName(),
                    writtenToCanonical, canonicalFlipX, canonicalFlipY));
        } else {
            perSiblingLog.add(String.format("  %s -> %s: 0 objects (canonical, no overlap)",
                    subEntry.getImageName(), canonical.getImageName()));
        }

        // 4. Capture the propagated objects from the canonical hierarchy so we can
        // delta-flip them into the other siblings.
        List<PathObject> canonicalPropagated;
        if (writtenToCanonical > 0) {
            // The objects we just wrote are the last `writtenToCanonical` additions.
            // Pull them by intersecting the hierarchy with the prior set.
            List<PathObject> all = new ArrayList<>(canonicalHierarchy.getAllObjects(false));
            canonicalPropagated = all.subList(Math.max(0, all.size() - writtenToCanonical), all.size());
        } else {
            canonicalPropagated = Collections.emptyList();
        }

        // 5. Fan out to remaining siblings.
        if (!canonicalPropagated.isEmpty()) {
            int baseWidth = canonicalData.getServer().getWidth();
            int baseHeight = canonicalData.getServer().getHeight();

            List<ProjectImageEntry<BufferedImage>> allSiblings =
                    ImageMetadataManager.getSiblingsByBaseImage(project, baseName);
            for (ProjectImageEntry<BufferedImage> sibling : allSiblings) {
                if (sibling == canonical) continue;

                String fxStr = sibling.getMetadata().get(ImageMetadataManager.FLIP_X);
                String fyStr = sibling.getMetadata().get(ImageMetadataManager.FLIP_Y);
                boolean siblingFx;
                boolean siblingFy;
                if (fxStr != null || fyStr != null) {
                    siblingFx = "1".equals(fxStr);
                    siblingFy = "1".equals(fyStr);
                } else {
                    String n = sibling.getImageName();
                    if (n == null) n = "";
                    boolean nameXY = n.contains("(flipped XY)");
                    siblingFx = nameXY || n.contains("(flipped X)");
                    siblingFy = nameXY || n.contains("(flipped Y)");
                }

                boolean deltaX = siblingFx ^ canonicalFlipX;
                boolean deltaY = siblingFy ^ canonicalFlipY;
                logger.info("Fan-out target: {} (flipX={}, flipY={}); delta from canonical: ({}, {})",
                        sibling.getImageName(), siblingFx, siblingFy, deltaX, deltaY);

                AffineTransform delta = (deltaX || deltaY)
                        ? createFlip(deltaX, deltaY, baseWidth, baseHeight)
                        : null;

                ImageData<BufferedImage> sibData = sibling.readImageData();
                PathObjectHierarchy sibHierarchy = sibData.getHierarchy();
                int sibBefore = sibHierarchy.getAllObjects(false).size();
                List<PathObject> toAdd = new ArrayList<>(canonicalPropagated.size());
                for (PathObject obj : canonicalPropagated) {
                    PathObject copy = (delta == null)
                            ? PathObjectTools.transformObject(obj, new AffineTransform(), true, true)
                            : PathObjectTools.transformObject(obj, delta, true, true);
                    if (copy == null || copy.getROI() == null || copy.getROI().isEmpty()) continue;
                    toAdd.add(copy);
                }
                if (!toAdd.isEmpty()) {
                    sibHierarchy.addObjects(toAdd);
                    sibling.saveImageData(sibData);
                    int added = sibHierarchy.getAllObjects(false).size() - sibBefore;
                    totalObjects += added;
                    if (added > 0) siblingsUpdated++;
                    perSiblingLog.add(String.format("  %s -> %s: %d objects (delta flipX=%s, flipY=%s)",
                            subEntry.getImageName(), sibling.getImageName(), added, deltaX, deltaY));
                } else {
                    perSiblingLog.add(String.format("  %s -> %s: 0 objects (after delta-flip)",
                            subEntry.getImageName(), sibling.getImageName()));
                }
            }
        }

        if (siblingsAutoCreated > 0) {
            project.syncChanges();
        }
        logger.info("Fan-out complete: {} siblings updated, {} auto-created, {} total objects",
                siblingsUpdated, siblingsAutoCreated, totalObjects);
        return new FanOutResult(totalObjects, siblingsUpdated, siblingsAutoCreated, perSiblingLog);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Transform objects using an affine transform and clip to image bounds.
     * Public for use by modality extensions (e.g., PPM).
     *
     * <p>Inclusion rules:
     * <ul>
     *   <li>If all 4 bounding box corners are within the image: include as-is</li>
     *   <li>If the annotation is larger than the target image (e.g., whole-tissue annotation):
     *       clip the ROI geometry to the image bounds and include the clipped version</li>
     *   <li>If no overlap with the image: exclude</li>
     * </ul>
     *
     * <p>Note: Clipping currently uses rectangular image bounds. Future enhancement:
     * clip to the actual tile coverage polygon from TileConfiguration.txt.
     */
    public static List<PathObject> transformAndClip(
            List<PathObject> objects, AffineTransform transform, int imgWidth, int imgHeight) {
        List<PathObject> result = new ArrayList<>();

        // Image bounds as a JTS geometry for intersection
        org.locationtech.jts.geom.GeometryFactory gf = new org.locationtech.jts.geom.GeometryFactory();
        org.locationtech.jts.geom.Geometry imageBounds = gf.createPolygon(new org.locationtech.jts.geom.Coordinate[] {
            new org.locationtech.jts.geom.Coordinate(0, 0),
            new org.locationtech.jts.geom.Coordinate(imgWidth, 0),
            new org.locationtech.jts.geom.Coordinate(imgWidth, imgHeight),
            new org.locationtech.jts.geom.Coordinate(0, imgHeight),
            new org.locationtech.jts.geom.Coordinate(0, 0)
        });

        for (PathObject obj : objects) {
            try {
                PathObject transformed = PathObjectTools.transformObject(obj, transform, true, true);
                if (transformed == null || transformed.getROI() == null) continue;

                ROI roi = transformed.getROI();
                double bx = roi.getBoundsX();
                double by = roi.getBoundsY();
                double bw = roi.getBoundsWidth();
                double bh = roi.getBoundsHeight();

                // Check if fully contained: all 4 corners within image
                boolean fullyContained = bx >= 0 && by >= 0 && (bx + bw) <= imgWidth && (by + bh) <= imgHeight;

                if (fullyContained) {
                    result.add(transformed);
                    continue;
                }

                // Check if there's any overlap with the image bounds
                try {
                    org.locationtech.jts.geom.Geometry roiGeom = roi.getGeometry();
                    if (roiGeom == null || !roiGeom.intersects(imageBounds)) continue;

                    // Clip the ROI to the image bounds
                    org.locationtech.jts.geom.Geometry clipped = roiGeom.intersection(imageBounds);
                    if (clipped.isEmpty()) continue;

                    ROI clippedRoi = qupath.lib.roi.GeometryTools.geometryToROI(clipped, roi.getImagePlane());
                    if (clippedRoi == null || clippedRoi.isEmpty()) continue;

                    // Create a new object with the clipped ROI
                    PathObject clippedObj;
                    if (transformed.isAnnotation()) {
                        clippedObj = qupath.lib.objects.PathObjects.createAnnotationObject(
                                clippedRoi, transformed.getPathClass());
                    } else if (transformed.isDetection()) {
                        clippedObj = qupath.lib.objects.PathObjects.createDetectionObject(
                                clippedRoi, transformed.getPathClass());
                    } else {
                        continue;
                    }
                    // Copy name and measurements
                    if (transformed.getName() != null) clippedObj.setName(transformed.getName());
                    if (transformed.isLocked()) clippedObj.setLocked(true);

                    result.add(clippedObj);
                    logger.debug("Clipped oversized annotation '{}' to image bounds", obj.getDisplayedName());
                } catch (Exception e) {
                    logger.debug("Could not clip annotation '{}': {}", obj.getDisplayedName(), e.getMessage());
                }
            } catch (Exception e) {
                logger.debug("Could not transform object: {}", e.getMessage());
            }
        }
        return result;
    }

    /**
     * Build a sub-image-pixel-to-stage-microns transform, including the half-FOV
     * correction for the tile grid offset. Public for use by modality extensions.
     *
     * @param subPixelSize sub-image pixel size in um/pixel
     * @param xyOffset XY offset from metadata [x, y] in stage microns
     * @return AffineTransform mapping sub-image pixels to stage microns
     */
    public static AffineTransform buildSubToStageTransform(double subPixelSize, double[] xyOffset) {
        return buildSubToStageTransform(subPixelSize, xyOffset, (ProjectImageEntry<BufferedImage>) null);
    }

    /**
     * Overload that resolves FOV from the sub-image entry metadata/config,
     * enabling offline use without a microscope connection.
     */
    public static AffineTransform buildSubToStageTransform(
            double subPixelSize, double[] xyOffset, ProjectImageEntry<BufferedImage> subEntry) {
        double halfFovX = 0;
        double halfFovY = 0;
        double[] fov = resolveFovForEntry(subEntry);
        if (fov != null) {
            halfFovX = fov[0] / 2.0;
            halfFovY = fov[1] / 2.0;
        }
        double correctedX = xyOffset[0] - halfFovX;
        double correctedY = xyOffset[1] - halfFovY;
        return new AffineTransform(subPixelSize, 0, 0, subPixelSize, correctedX, correctedY);
    }

    /**
     * Create a flip transform for the given image dimensions.
     * Public for use by modality extensions (e.g., PPM).
     */
    public static AffineTransform createFlip(boolean flipX, boolean flipY, int width, int height) {
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

    /**
     * Resolve the camera field of view for a project image entry.
     *
     * <p>Tries three sources in priority order:
     * <ol>
     *   <li>Per-image metadata ({@code fov_x_um}, {@code fov_y_um}) -- always correct</li>
     *   <li>Config file via modality + objective + detector from metadata -- works offline</li>
     *   <li>Live microscope connection -- fallback for legacy images without metadata</li>
     * </ol>
     *
     * @param entry The project image entry (may be null)
     * @return [fovX, fovY] in microns, or null if unavailable
     */
    private static double[] resolveFovForEntry(ProjectImageEntry<BufferedImage> entry) {
        // Source 1: per-image metadata (best -- recorded at acquisition time)
        if (entry != null) {
            Map<String, String> meta = entry.getMetadata();
            String fxStr = meta.get(ImageMetadataManager.FOV_X_UM);
            String fyStr = meta.get(ImageMetadataManager.FOV_Y_UM);
            if (fxStr != null && fyStr != null) {
                try {
                    double fx = Double.parseDouble(fxStr);
                    double fy = Double.parseDouble(fyStr);
                    if (fx > 0 && fy > 0) {
                        logger.debug("FOV from metadata: {}x{} um", fx, fy);
                        return new double[]{fx, fy};
                    }
                } catch (NumberFormatException e) {
                    logger.debug("Invalid FOV metadata: {}, {}", fxStr, fyStr);
                }
            }
        }

        // Source 2: config file (modality + objective + detector from metadata)
        if (entry != null) {
            Map<String, String> meta = entry.getMetadata();
            String modality = meta.get(ImageMetadataManager.MODALITY);
            String objective = meta.get(ImageMetadataManager.OBJECTIVE);
            String detector = meta.get(ImageMetadataManager.DETECTOR_ID);
            if (modality != null && objective != null && detector != null) {
                try {
                    MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
                    if (mgr == null) throw new IllegalStateException("Config not loaded");
                    double[] fov = mgr.getCameraFOV(modality, objective, detector);
                    if (fov != null && fov[0] > 0 && fov[1] > 0) {
                        logger.debug("FOV from config: {}x{} um (modality={}, obj={}, det={})",
                                fov[0], fov[1], modality, objective, detector);
                        return fov;
                    }
                } catch (Exception e) {
                    logger.debug("Could not get FOV from config: {}", e.getMessage());
                }
            }
        }

        // Source 3: live microscope (legacy fallback)
        try {
            MicroscopeController mc = MicroscopeController.getInstance();
            if (mc != null && mc.isConnected()) {
                double[] fov = mc.getCameraFOV();
                if (fov != null && fov[0] > 0 && fov[1] > 0) {
                    logger.debug("FOV from live microscope: {}x{} um", fov[0], fov[1]);
                    return fov;
                }
            }
        } catch (Exception e) {
            logger.debug("Could not get FOV from microscope: {}", e.getMessage());
        }

        return null;
    }
}
