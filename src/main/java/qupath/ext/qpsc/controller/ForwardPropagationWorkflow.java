package qupath.ext.qpsc.controller;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.PropagationGroupItem;
import qupath.ext.qpsc.ui.PropagationManagerDialog;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
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
        PropagationManagerDialog.show(qupath, Direction.FORWARD);
    }

    /**
     * Show the propagation manager dialog with Back as default direction.
     */
    public static void runBack(QuPathGUI qupath) {
        PropagationManagerDialog.show(qupath, Direction.BACK);
    }

    /**
     * Build the per-base-image groups consumed by the Propagation Manager.
     *
     * <p>Each {@link PropagationGroupItem} bundles all base-like siblings
     * (unflipped + flipped duplicates) and all sub-acquisitions sharing a
     * single {@code base_image} metadata value. Groups with no sub-acquisitions
     * are omitted -- there is nothing to propagate to or from.
     *
     * @param project the QuPath project; null returns empty
     * @return ordered list of groups in project iteration order; never null
     */
    public static List<PropagationGroupItem> buildGroups(Project<BufferedImage> project) {
        if (project == null) return new ArrayList<>();

        Map<String, List<ProjectImageEntry<BufferedImage>>> baseVariants = new LinkedHashMap<>();
        Map<String, List<ProjectImageEntry<BufferedImage>>> subs = new LinkedHashMap<>();

        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            String imageName = entry.getImageName();
            if (imageName == null) continue;
            String entryStripped = GeneralTools.stripExtension(imageName);
            String rawBase = ImageMetadataManager.getBaseImage(entry);
            String baseName = (rawBase != null && !rawBase.isEmpty()) ? rawBase : entryStripped;

            boolean isBaseVariant = entryStripped.equals(baseName)
                    || imageName.startsWith(baseName + ".")
                    || (imageName.startsWith(baseName) && imageName.contains("(flipped"));
            if (isBaseVariant) {
                baseVariants.computeIfAbsent(baseName, k -> new ArrayList<>()).add(entry);
            } else {
                subs.computeIfAbsent(baseName, k -> new ArrayList<>()).add(entry);
            }
        }

        List<PropagationGroupItem> result = new ArrayList<>();
        for (Map.Entry<String, List<ProjectImageEntry<BufferedImage>>> e : subs.entrySet()) {
            String baseName = e.getKey();
            List<ProjectImageEntry<BufferedImage>> subList = e.getValue();
            List<ProjectImageEntry<BufferedImage>> siblings =
                    baseVariants.getOrDefault(baseName, new ArrayList<>());
            boolean alignmentFound = false;
            try {
                AffineTransform t = AffineTransformManager.loadSlideAlignment(project, baseName);
                alignmentFound = (t != null);
            } catch (Exception ignored) {
                alignmentFound = false;
            }
            result.add(new PropagationGroupItem(baseName, siblings, subList, alignmentFound));
        }
        logger.info("buildGroups: {} group(s) with sub-acquisitions", result.size());
        return result;
    }

    /**
     * Collect the {@link PathClass} values present in the source side of a
     * propagation, plus a flag for whether unclassified objects exist.
     *
     * @param direction propagation direction
     * @param groups groups to scan
     * @param selectedSubs predicate-style filter: only sub-images whose entry is
     *        present in this set are considered for BACK; ignored for FORWARD
     * @return [classes, hasUnclassified]
     */
    public static ClassScan collectClasses(
            Direction direction,
            List<PropagationGroupItem> groups,
            Set<ProjectImageEntry<BufferedImage>> selectedSubs) {
        Set<PathClass> classes = new TreeSet<>();
        boolean hasUnclassified = false;
        for (PropagationGroupItem grp : groups) {
            List<ProjectImageEntry<BufferedImage>> entries = (direction == Direction.FORWARD)
                    ? grp.getSiblings()
                    : grp.getSubAcquisitions();
            for (ProjectImageEntry<BufferedImage> entry : entries) {
                if (direction == Direction.BACK && selectedSubs != null && !selectedSubs.contains(entry)) continue;
                try {
                    var data = entry.readImageData();
                    for (PathObject obj : data.getHierarchy().getAllObjects(false)) {
                        if (obj.isRootObject() || obj.getROI() == null) continue;
                        if (obj.getPathClass() == null) hasUnclassified = true;
                        else classes.add(obj.getPathClass());
                    }
                } catch (Exception ex) {
                    logger.debug("collectClasses: could not read {}: {}", entry.getImageName(), ex.getMessage());
                }
            }
        }
        return new ClassScan(classes, hasUnclassified);
    }

    /** Result tuple for {@link #collectClasses}. */
    public static final class ClassScan {
        public final Set<PathClass> classes;
        public final boolean hasUnclassified;
        ClassScan(Set<PathClass> classes, boolean hasUnclassified) {
            this.classes = classes;
            this.hasUnclassified = hasUnclassified;
        }
    }

    /**
     * Show the propagation manager dialog.
     */
    public static void showManager(QuPathGUI qupath, Direction defaultDirection) {
        // Legacy entry point retained for compatibility; the dialog itself now lives in
        // ui.PropagationManagerDialog. Existing callers route through this method, which
        // simply delegates.
        PropagationManagerDialog.show(qupath, defaultDirection);
    }

    // ------------------------------------------------------------------
    // Core propagation logic
    // ------------------------------------------------------------------

    /** Load and filter objects from an image entry. */
    public static List<PathObject> loadFilteredObjects(
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
    public static int propagateForward(
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
