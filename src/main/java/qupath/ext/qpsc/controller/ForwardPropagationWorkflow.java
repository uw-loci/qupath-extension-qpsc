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
     * Thrown when back-propagation cannot determine the camera FOV for a
     * sub-acquisition because the scope that captured it has no config file
     * accessible to this QuPath instance. The dialog uses {@link #subName} /
     * {@link #sourceScope} / {@link #expectedConfigFilename} to surface a
     * user-actionable warning naming the file the user needs to add.
     */
    public static class MissingSourceConfigException extends Exception {
        public final String subName;
        public final String sourceScope;
        public final String expectedConfigFilename;

        public MissingSourceConfigException(String subName, String sourceScope, String expectedConfigFilename) {
            super("No config file for source scope '" + sourceScope
                    + "' (expected '" + expectedConfigFilename + "') -- cannot resolve FOV for '"
                    + subName + "'.");
            this.subName = subName;
            this.sourceScope = sourceScope;
            this.expectedConfigFilename = expectedConfigFilename;
        }
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
     *
     * <p>Convenience overload assuming the alignment was built in the
     * unflipped frame ({@code alignFlipX = alignFlipY = false}); use the
     * explicit-flip overload when the alignment frame differs from the base
     * entry's pixel frame (Step B of the flip-relocation refactor).
     */
    public static int propagateForward(
            AffineTransform baseToStage, List<PathObject> sourceObjects, ProjectImageEntry<BufferedImage> subEntry)
            throws Exception {
        return propagateForward(baseToStage, false, false, 0, 0, sourceObjects, subEntry);
    }

    /**
     * Forward propagation with explicit alignment-frame flip.
     *
     * <p>{@code alignFlipX}/{@code alignFlipY} describe the macro flip that was
     * active when the alignment was built (the pixel frame {@code baseToStage}
     * expects as input). Source objects are interpreted in the **unflipped base
     * pixel frame**; if the alignment frame differs, an axis-flip is applied
     * before {@code baseToStage} as part of the composed transform chain.
     * {@code baseWidth}/{@code baseHeight} are required when either flip is
     * true (the flip transform is "translate(W,0); scale(-1,1)" and similar).
     * Pass 0 for both when no flip is needed.
     *
     * <p>This overload lets the workflow operate on the single unflipped base
     * entry without needing a separate "(flipped X|Y|XY)" duplicate in the
     * project.
     */
    public static int propagateForward(
            AffineTransform baseToStage,
            boolean alignFlipX,
            boolean alignFlipY,
            int baseWidth,
            int baseHeight,
            List<PathObject> sourceObjects,
            ProjectImageEntry<BufferedImage> subEntry)
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

        // The xy_offset in metadata is the annotation top-left in stage microns.
        // But the tile grid starts half a FOV before the annotation edge (TilingUtilities
        // line 99: startX = minX - frameWidth/2). Correct the offset to match the
        // actual image origin.
        //
        // Resolve FOV from (in priority order):
        //   1. Per-image metadata (fov_x_um / fov_y_um) -- always correct
        //   2. Config file (modality + objective + detector from metadata) -- works offline
        //   3. Live microscope connection -- fallback if nothing else available
        double[] fov = resolveFovForEntry(subEntry);
        if (fov == null) {
            String subSource = subEntry.getMetadata().get(ImageMetadataManager.SOURCE_MICROSCOPE);
            String activeScope = null;
            try {
                MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
                if (mgr != null) activeScope = mgr.getMicroscopeName();
            } catch (Exception ignore) {
            }
            if (subSource != null && !subSource.isEmpty()
                    && activeScope != null && !subSource.equals(activeScope)) {
                throw new MissingSourceConfigException(
                        subEntry.getImageName(), subSource, "config_" + subSource + ".yml");
            }
            throw new IllegalStateException(
                    "Cannot determine FOV for sub-image '" + subEntry.getImageName()
                    + "'. Add 'fov_x_um' / 'fov_y_um' metadata or ensure the active microscope "
                    + "config has objective+detector entries for this sub-acquisition.");
        }
        double halfFovX = fov[0] / 2.0;
        double halfFovY = fov[1] / 2.0;

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

        // Build combined transform: unflipped_base_pixels -> [optional flip into
        // alignment frame] -> stage_microns -> sub_pixels.
        //
        // Sub-acquisition pixels are stage-canonical -- we no longer flip
        // them based on the sub entry's FLIP_X/Y metadata (which is always 0
        // for sub-acquisitions and is being deprecated as a per-entry concept;
        // see Step B of the flip-relocation refactor).
        AffineTransform stageToSub = new AffineTransform();
        stageToSub.scale(1.0 / subPixelSize, 1.0 / subPixelSize);
        stageToSub.translate(-correctedOffsetX, -correctedOffsetY);

        AffineTransform combined = new AffineTransform(stageToSub);
        combined.concatenate(baseToStage);
        if (alignFlipX || alignFlipY) {
            if (baseWidth <= 0 || baseHeight <= 0) {
                throw new IllegalArgumentException(
                        "Forward propagation with alignFlip set requires baseWidth/baseHeight > 0");
            }
            AffineTransform alignFlip = createFlip(alignFlipX, alignFlipY, baseWidth, baseHeight);
            combined.concatenate(alignFlip);
            logger.info("Forward: pre-flipped unflipped-base pixels into alignment frame ({}, {})",
                    alignFlipX, alignFlipY);
        }

        List<PathObject> propagated = transformAndClip(sourceObjects, combined, subWidth, subHeight);

        if (!propagated.isEmpty()) {
            subHierarchy.addObjects(propagated);
            subEntry.saveImageData(subData);
        }

        return propagated.size();
    }

    /**
     * Back propagation: sub-image objects -> base image (unflipped base frame).
     *
     * <p>Transform chain: {@code sub_pixels -> stage_microns -> alignment_frame_pixels
     * -> [optional unflip] -> unflipped_base_pixels}. The optional unflip is
     * applied based on the saved alignment's flipMacroX/Y; on legacy alignments
     * with no recorded flip, no unflip is applied (output frame == alignment
     * frame, which matches the entry being written).
     *
     * @param baseToStage transform mapping alignment-frame pixel coords to stage
     * @param alignFlipX  alignment-time macro flip X (the frame baseToStage expects)
     * @param alignFlipY  alignment-time macro flip Y
     * @param sourceObjects sub-image-frame objects to back-propagate
     * @param subEntry      source sub-acquisition entry (provides offset, FOV)
     * @param baseData      target base ImageData; written objects are appended
     */
    public static int propagateBack(
            AffineTransform baseToStage,
            boolean alignFlipX,
            boolean alignFlipY,
            List<PathObject> sourceObjects,
            ProjectImageEntry<BufferedImage> subEntry,
            ImageData<BufferedImage> baseData)
            throws Exception {

        PathObjectHierarchy baseHierarchy = baseData.getHierarchy();

        ImageData<BufferedImage> subData = subEntry.readImageData();
        int subWidth = subData.getServer().getWidth();
        int subHeight = subData.getServer().getHeight();
        double subPixelSize = subData.getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
        if (Double.isNaN(subPixelSize) || subPixelSize <= 0) {
            throw new IllegalStateException("Sub-image has no valid pixel size");
        }

        int baseWidth = baseData.getServer().getWidth();
        int baseHeight = baseData.getServer().getHeight();

        // Ground-truth path: if the sub has a recorded source rectangle on the
        // unflipped base, use it directly. The annotation's relative position
        // within the sub maps to the same relative position within the base
        // rectangle. This bypasses every alignment / flip / half-FOV variable
        // and is correct as long as the rectangle was captured accurately at
        // acquisition time (or hand-stamped from a known source ROI).
        double[] sourceRoi = ImageMetadataManager.getSourceRoiPx(subEntry);
        if (sourceRoi != null) {
            double rx = sourceRoi[0], ry = sourceRoi[1], rw = sourceRoi[2], rh = sourceRoi[3];
            boolean[] roiFlip = ImageMetadataManager.getSourceRoiFlip(subEntry);
            boolean roiFlipX = roiFlip[0];
            boolean roiFlipY = roiFlip[1];
            logger.info("BackProp: using GROUND-TRUTH source rect from metadata: "
                    + "base px=({}, {}, {}x{}) flip=({}, {}) -- skipping alignment math.",
                    fmt(rx), fmt(ry), fmt(rw), fmt(rh), roiFlipX, roiFlipY);
            // Build sub_px -> base_px linear map. Flip flags reverse the
            // axis inside the rectangle (used when the rectangle was
            // derived from a flipped sibling parent's tile detections).
            AffineTransform combined = new AffineTransform();
            combined.translate(roiFlipX ? rx + rw : rx, roiFlipY ? ry + rh : ry);
            combined.scale(
                    (roiFlipX ? -1.0 : 1.0) * rw / subWidth,
                    (roiFlipY ? -1.0 : 1.0) * rh / subHeight);
            for (PathObject obj : sourceObjects) {
                ROI src = obj.getROI();
                if (src == null) continue;
                double sx = src.getBoundsX(), sy = src.getBoundsY();
                double sw = src.getBoundsWidth(), sh = src.getBoundsHeight();
                double[] in = new double[]{sx, sy, sx + sw, sy + sh};
                double[] out = new double[4];
                combined.transform(in, 0, out, 0, 2);
                double tx = Math.min(out[0], out[2]);
                double ty = Math.min(out[1], out[3]);
                double tw = Math.abs(out[2] - out[0]);
                double th = Math.abs(out[3] - out[1]);
                logger.info("  source obj '{}' src px=({}, {}, {}x{}) -> base px=({}, {}, {}x{}) [GT]",
                        obj.getDisplayedName(),
                        fmt(sx), fmt(sy), fmt(sw), fmt(sh),
                        fmt(tx), fmt(ty), fmt(tw), fmt(th));
            }
            List<PathObject> propagated = transformAndClip(sourceObjects, combined, baseWidth, baseHeight);
            logger.info("BackProp(GT): {} of {} object(s) survived clip onto base ({}x{})",
                    propagated.size(), sourceObjects.size(), baseWidth, baseHeight);
            if (!propagated.isEmpty()) baseHierarchy.addObjects(propagated);
            return propagated.size();
        }

        double[] xyOffset = ImageMetadataManager.getXYOffset(subEntry);

        // Apply same half-FOV correction as forward propagation (see resolveFovForEntry).
        // If FOV cannot be resolved we refuse to back-propagate -- silently dropping the
        // halfFOV correction shifts the result by hundreds of base pixels, which is worse
        // than failing visibly. When the missing piece is the source scope's config file,
        // surface that to the caller so the dialog can tell the user exactly what to add.
        double[] fov = resolveFovForEntry(subEntry);
        if (fov == null) {
            String subSource = subEntry.getMetadata().get(ImageMetadataManager.SOURCE_MICROSCOPE);
            String activeScope = null;
            try {
                MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
                if (mgr != null) activeScope = mgr.getMicroscopeName();
            } catch (Exception ignore) {
            }
            if (subSource != null && !subSource.isEmpty()
                    && activeScope != null && !subSource.equals(activeScope)) {
                throw new MissingSourceConfigException(
                        subEntry.getImageName(), subSource, "config_" + subSource + ".yml");
            }
            throw new IllegalStateException(
                    "Cannot determine FOV for sub-image '" + subEntry.getImageName()
                    + "'. Add 'fov_x_um' / 'fov_y_um' metadata or ensure the active microscope "
                    + "config has objective+detector entries for this sub-acquisition.");
        }
        double halfFovX = fov[0] / 2.0;
        double halfFovY = fov[1] / 2.0;
        double correctedOffsetX = xyOffset[0] - halfFovX;
        double correctedOffsetY = xyOffset[1] - halfFovY;

        logger.info("BackProp inputs for sub '{}':", subEntry.getImageName());
        logger.info("  sub: {}x{} px @ {} um/px", subWidth, subHeight, subPixelSize);
        logger.info("  base: {}x{} px", baseWidth, baseHeight);
        logger.info("  xy_offset (raw, stage um) = ({}, {})", xyOffset[0], xyOffset[1]);
        logger.info("  halfFOV (um) = ({}, {}); corrected offset = ({}, {})",
                halfFovX, halfFovY, correctedOffsetX, correctedOffsetY);
        logger.info("  baseToStage = {}", formatAffine(baseToStage));
        logger.info("  alignFlip = (X={}, Y={})", alignFlipX, alignFlipY);

        // Build inverse: sub_pixels -> stage_microns -> alignment_pixels -> [unflip]
        // Sub-acquisitions are stage-canonical; the previous "if (subFlipX || subFlipY)"
        // branch never fired for them and is removed as part of the per-entry FLIP
        // metadata deprecation (Step B).
        AffineTransform subToStage = new AffineTransform();
        subToStage.translate(correctedOffsetX, correctedOffsetY);
        subToStage.scale(subPixelSize, subPixelSize);

        AffineTransform stageToBase;
        try {
            stageToBase = baseToStage.createInverse();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot invert alignment transform", e);
        }

        AffineTransform combined = new AffineTransform(stageToBase);
        combined.concatenate(subToStage);
        if (alignFlipX || alignFlipY) {
            // alignment_frame_pixel -> unflipped_base_pixel
            // createFlip is an involution, so the same matrix flips and unflips.
            AffineTransform unflip = createFlip(alignFlipX, alignFlipY, baseWidth, baseHeight);
            AffineTransform withUnflip = new AffineTransform(unflip);
            withUnflip.concatenate(combined);
            combined = withUnflip;
            logger.info("  post-unflip applied (alignFlip=({}, {}))", alignFlipX, alignFlipY);
        }
        logger.info("  combined sub_px -> base_px = {}", formatAffine(combined));

        // Sanity-check: where do the four corners of the sub-image land in base-pixel coords?
        // If these four points fall well outside [0,baseWidth] x [0,baseHeight], the alignment
        // is for a different stage frame than the sub-acquisition (e.g. cross-scope mismatch).
        double[] cornerProbe = new double[] { 0, 0, subWidth, 0, subWidth, subHeight, 0, subHeight };
        double[] cornerOut = new double[8];
        combined.transform(cornerProbe, 0, cornerOut, 0, 4);
        logger.info("  sub-image corners in base px: TL=({}, {}) TR=({}, {}) BR=({}, {}) BL=({}, {})",
                fmt(cornerOut[0]), fmt(cornerOut[1]),
                fmt(cornerOut[2]), fmt(cornerOut[3]),
                fmt(cornerOut[4]), fmt(cornerOut[5]),
                fmt(cornerOut[6]), fmt(cornerOut[7]));
        boolean cornersOnBase = false;
        for (int i = 0; i < 4 && !cornersOnBase; i++) {
            double cx = cornerOut[i * 2];
            double cy = cornerOut[i * 2 + 1];
            if (cx >= 0 && cx <= baseWidth && cy >= 0 && cy <= baseHeight) cornersOnBase = true;
        }
        if (!cornersOnBase) {
            logger.warn("BackProp: NONE of the sub-image corners land inside the base image. "
                    + "This usually means the alignment is for the wrong stage frame "
                    + "(e.g. sub was acquired on a different scope than the loaded alignment).");
        }

        // Per-source-object transform diagnostics (caller-side; transformAndClip itself is shared
        // with forward-prop and stays quiet).
        for (PathObject obj : sourceObjects) {
            ROI src = obj.getROI();
            if (src == null) continue;
            double sx = src.getBoundsX();
            double sy = src.getBoundsY();
            double sw = src.getBoundsWidth();
            double sh = src.getBoundsHeight();
            double[] in = new double[] { sx, sy, sx + sw, sy + sh };
            double[] out = new double[4];
            combined.transform(in, 0, out, 0, 2);
            double tx = Math.min(out[0], out[2]);
            double ty = Math.min(out[1], out[3]);
            double tw = Math.abs(out[2] - out[0]);
            double th = Math.abs(out[3] - out[1]);
            boolean intersects = (tx + tw) >= 0 && tx <= baseWidth
                    && (ty + th) >= 0 && ty <= baseHeight;
            logger.info("  source obj '{}' src px=({}, {}, {}x{}) -> base px=({}, {}, {}x{}) -- {}",
                    obj.getDisplayedName(),
                    fmt(sx), fmt(sy), fmt(sw), fmt(sh),
                    fmt(tx), fmt(ty), fmt(tw), fmt(th),
                    intersects ? "intersects base" : "NO OVERLAP with base");
        }

        List<PathObject> propagated = transformAndClip(sourceObjects, combined, baseWidth, baseHeight);
        logger.info("BackProp: {} of {} object(s) survived transform+clip onto base ({}x{})",
                propagated.size(), sourceObjects.size(), baseWidth, baseHeight);

        if (!propagated.isEmpty()) {
            baseHierarchy.addObjects(propagated);
        }

        return propagated.size();
    }

    private static String formatAffine(AffineTransform t) {
        return String.format("[m00=%.6f m10=%.6f m01=%.6f m11=%.6f tx=%.3f ty=%.3f]",
                t.getScaleX(), t.getShearY(), t.getShearX(), t.getScaleY(),
                t.getTranslateX(), t.getTranslateY());
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }

    /**
     * Legacy zero-flip overload for callers not yet aware of the alignment-frame
     * flip pair. New callers should use the explicit-flip variant so back-prop
     * lands on the unflipped base regardless of how the alignment was saved.
     */
    private static int propagateBack(
            AffineTransform baseToStage,
            List<PathObject> sourceObjects,
            ProjectImageEntry<BufferedImage> subEntry,
            ImageData<BufferedImage> baseData)
            throws Exception {
        return propagateBack(baseToStage, false, false, sourceObjects, subEntry, baseData);
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
     * Back-propagation onto the unflipped base entry.
     *
     * <p>Step B of the flip-relocation refactor: there is now exactly one base
     * entry per slide (the unflipped original SVS). The flip required by the
     * saved alignment is applied as a transform step inside {@link #propagateBack},
     * so the back-propagated annotations always land in the unflipped-base
     * pixel frame regardless of which microscope captured the alignment.
     *
     * <p>The previous "fan out across every flipped sibling" behaviour is gone:
     * since the project no longer carries flipped duplicates, there is nothing
     * to fan to. The {@link FanOutResult} return type is preserved as a thin
     * legacy wrapper around the single-target write so existing callers
     * (PropagationManagerDialog) keep compiling unchanged.
     *
     * @param project the QuPath project (must contain the unflipped base entry)
     * @param baseName the base image name (without extension)
     * @param baseToStage the per-slide alignment for the active microscope
     * @param sourceObjects sub-image-frame objects to back-propagate
     * @param subEntry the sub-acquisition entry these objects came from
     * @param allowAutoCreate ignored under Step B (no siblings to create)
     * @return result with siblingsAutoCreated always 0
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

        // Cross-scope diagnostic: the sub may have been acquired on a different microscope
        // than the one currently active. The alignment file is scope-locked to the active
        // microscope, so an offset stored in another scope's stage frame will not invert
        // correctly through it.
        String subSourceScope = subEntry.getMetadata().get(ImageMetadataManager.SOURCE_MICROSCOPE);
        String activeScope = null;
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (configPath != null && !configPath.isEmpty()) {
                activeScope = MicroscopeConfigManager.getInstance(configPath).getMicroscopeName();
            }
        } catch (Exception e) {
            logger.debug("BackProp: could not read active scope name: {}", e.getMessage());
        }
        logger.info("BackProp fan-out: sub='{}' source_microscope='{}' active_microscope='{}' base='{}'",
                subEntry.getImageName(), subSourceScope, activeScope, baseName);

        // Cross-scope routing: when the sub was acquired on a different scope
        // than the active one, the xy_offset is in *that* scope's stage frame.
        // The base SVS pixel coords are absolute, so the correct alignment to
        // invert is the one built for the sub's source scope -- not the active
        // scope. Re-load the alignment for the sub's scope and override the
        // baseToStage parameter the caller passed in (which is active-scope
        // only, since the dialog has no way to know which sub will be picked).
        AffineTransformManager.SlideAlignmentResult slideResult = null;
        if (subSourceScope != null && activeScope != null && !subSourceScope.equals(activeScope)) {
            AffineTransformManager.SlideAlignmentResult crossScope =
                    AffineTransformManager.loadSlideAlignmentWithFrameForScope(project, baseName, subSourceScope);
            if (crossScope != null) {
                logger.info("BackProp: cross-scope routing -- replacing active-scope ({}) alignment with "
                        + "sub source-scope ({}) alignment for back-prop math.",
                        activeScope, subSourceScope);
                baseToStage = crossScope.getTransform();
                slideResult = crossScope;
            } else {
                logger.warn("BackProp: sub source_microscope='{}' differs from active scope='{}', "
                        + "but no '{}_{}_alignment.json' found. Will use the active-scope alignment "
                        + "and the result will likely be wrong.",
                        subSourceScope, activeScope, baseName, subSourceScope);
            }
        }

        // Resolve alignment-frame flip from the per-slide JSON (preferred) or active preset (fallback).
        boolean alignFlipX = false;
        boolean alignFlipY = false;
        if (slideResult == null) {
            slideResult = AffineTransformManager.loadSlideAlignmentWithFrame(project, baseName);
        }
        if (slideResult != null && slideResult.hasFlipFrame()) {
            alignFlipX = Boolean.TRUE.equals(slideResult.getFlipMacroX());
            alignFlipY = Boolean.TRUE.equals(slideResult.getFlipMacroY());
            logger.info("BackProp: alignment frame from slide JSON: flipX={}, flipY={}", alignFlipX, alignFlipY);
        } else {
            // Fallback: pick a preset whose target microscope matches the alignment we will be
            // inverting. For cross-scope routing that's the sub's source scope; otherwise the
            // active scope.
            String flipScope = (subSourceScope != null && !subSourceScope.isEmpty())
                    ? subSourceScope : activeScope;
            try {
                String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                if (configPath != null && !configPath.isEmpty() && flipScope != null) {
                    AffineTransformManager mgr =
                            new AffineTransformManager(new java.io.File(configPath).getParent());
                    for (String scanner : mgr.getDistinctSourceScannersForMicroscope(flipScope)) {
                        AffineTransformManager.TransformPreset p =
                                mgr.getBestPresetForPair(scanner, flipScope);
                        if (p != null && p.hasFlipState()) {
                            alignFlipX = Boolean.TRUE.equals(p.getFlipMacroX());
                            alignFlipY = Boolean.TRUE.equals(p.getFlipMacroY());
                            logger.warn("BackProp: per-slide JSON lacks flip frame; "
                                    + "falling back to preset '{}' (target scope='{}'): flipX={}, flipY={}",
                                    p.getName(), flipScope, alignFlipX, alignFlipY);
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("BackProp: could not resolve fallback flip from preset: {}", e.getMessage());
            }
        }

        // Find the unflipped base entry. The project must contain it -- with Step B
        // there are no flipped duplicates to auto-create as a substitute.
        ProjectImageEntry<BufferedImage> base = findUnflippedBase(project, baseName);
        if (base == null) {
            // Soft fallback: pick any base-like entry whose name doesn't include "(flipped".
            // This covers projects that have already been migrated but had the unflipped
            // base renamed for some reason. Hard fail if even that finds nothing.
            for (ProjectImageEntry<BufferedImage> e : project.getImageList()) {
                String name = e.getImageName();
                if (name == null) continue;
                String stripped = GeneralTools.stripExtension(name);
                if (stripped.equals(baseName) && !name.contains("(flipped")) {
                    base = e;
                    break;
                }
            }
        }
        if (base == null) {
            throw new IllegalStateException(
                    "No unflipped base entry for '" + baseName + "' found in project.");
        }

        ImageData<BufferedImage> baseData = base.readImageData();
        PathObjectHierarchy baseHierarchy = baseData.getHierarchy();

        // Auto-stamp ground-truth source rectangle from tile detections on
        // the parent entry the sub was generated against. No-op if the sub
        // already has a stamp, has no parent reference, or the parent has
        // no matching tile detections. Once stamped, the GT path inside
        // propagateBack picks it up and bypasses alignment / flip /
        // half-FOV math entirely.
        if (ImageMetadataManager.getSourceRoiPx(subEntry) == null) {
            try {
                autoStampSourceRoiFromTiles(project, subEntry, base, baseData);
            } catch (Exception e) {
                logger.warn("Auto-stamp from tile detections failed: {}", e.getMessage());
            }
        }

        int beforeCount = baseHierarchy.getAllObjects(false).size();
        int written = propagateBack(baseToStage, alignFlipX, alignFlipY, sourceObjects, subEntry, baseData);
        int afterCount = baseHierarchy.getAllObjects(false).size();
        int siblingsUpdated = 0;
        int totalWritten = 0;
        List<PathObject> writtenObjects = java.util.Collections.emptyList();
        if (afterCount > beforeCount) {
            // Capture the freshly-added objects so we can fan them out to legacy
            // flipped siblings without re-running the math.
            List<PathObject> all = new ArrayList<>(baseHierarchy.getAllObjects(false));
            writtenObjects = all.subList(beforeCount, all.size());
            writtenObjects = new ArrayList<>(writtenObjects); // detach view
            base.saveImageData(baseData);
            siblingsUpdated = 1;
            totalWritten += written;
            perSiblingLog.add(String.format("  %s -> %s: %d objects (alignFlip=(%s, %s))",
                    subEntry.getImageName(), base.getImageName(), written, alignFlipX, alignFlipY));
        } else {
            perSiblingLog.add(String.format("  %s -> %s: 0 objects (no overlap)",
                    subEntry.getImageName(), base.getImageName()));
        }

        // Fan-out to legacy flipped sibling entries (e.g. "(flipped X)",
        // "(flipped Y)", "(flipped XY)") that may still exist in pre-Step-B
        // projects. Step B prefers a single unflipped base entry, but until
        // every project is migrated the duplicates remain and the user
        // expects the back-propagated annotation to appear on them too.
        // Each duplicate is the same image content with axis flips, so the
        // transform is a simple per-axis mirror around base width/height.
        int baseWidth = baseData.getServer().getWidth();
        int baseHeight = baseData.getServer().getHeight();
        if (!writtenObjects.isEmpty()) {
            for (ProjectImageEntry<BufferedImage> sibling : project.getImageList()) {
                if (sibling == base) continue;
                String name = sibling.getImageName();
                if (name == null) continue;
                String stripped = GeneralTools.stripExtension(name);
                String rawBase = ImageMetadataManager.getBaseImage(sibling);
                String effectiveBase = (rawBase != null && !rawBase.isEmpty()) ? rawBase : stripped;
                if (!baseName.equals(effectiveBase)) continue;
                if (!name.contains("(flipped")) continue; // only fan to legacy duplicates

                boolean flipX = name.contains("(flipped XY)") || name.contains("(flipped X)");
                boolean flipY = name.contains("(flipped XY)") || name.contains("(flipped Y)");
                if (!flipX && !flipY) continue;

                AffineTransform mirror = createFlip(flipX, flipY, baseWidth, baseHeight);
                try {
                    ImageData<BufferedImage> sibData = sibling.readImageData();
                    PathObjectHierarchy sibHierarchy = sibData.getHierarchy();
                    int beforeSib = sibHierarchy.getAllObjects(false).size();
                    List<PathObject> mirrored = new ArrayList<>(writtenObjects.size());
                    for (PathObject obj : writtenObjects) {
                        try {
                            PathObject m = PathObjectTools.transformObject(obj, mirror, true, true);
                            if (m != null && m.getROI() != null && !m.getROI().isEmpty()) mirrored.add(m);
                        } catch (Exception trEx) {
                            logger.debug("Sibling fan-out: could not mirror object: {}", trEx.getMessage());
                        }
                    }
                    if (!mirrored.isEmpty()) {
                        sibHierarchy.addObjects(mirrored);
                        sibling.saveImageData(sibData);
                        int added = sibHierarchy.getAllObjects(false).size() - beforeSib;
                        siblingsUpdated++;
                        totalWritten += added;
                        perSiblingLog.add(String.format("  %s -> %s: %d objects (mirror=(%s, %s))",
                                subEntry.getImageName(), name, added, flipX, flipY));
                    } else {
                        perSiblingLog.add(String.format("  %s -> %s: 0 objects (mirror produced empty ROIs)",
                                subEntry.getImageName(), name));
                    }
                } catch (Exception sibEx) {
                    perSiblingLog.add(String.format("  %s -> %s: FAILED (%s)",
                            subEntry.getImageName(), name, sibEx.getMessage()));
                    logger.warn("Sibling fan-out to '{}' failed: {}", name, sibEx.getMessage());
                }
            }
        }

        try {
            project.syncChanges();
        } catch (Exception e) {
            logger.debug("project.syncChanges after sibling fan-out: {}", e.getMessage());
        }

        // allowAutoCreate is preserved in the signature for back-compat but is no longer used.
        if (allowAutoCreate) {
            logger.debug("propagateBackFanOut: allowAutoCreate is now a no-op under Step B (no flipped siblings).");
        }
        logger.info("Back-prop complete: {} sibling(s) updated, {} object(s) total",
                siblingsUpdated, totalWritten);
        return new FanOutResult(totalWritten, siblingsUpdated, 0, perSiblingLog);
    }

    /**
     * Auto-detect a ground-truth source rectangle for a sub-acquisition by
     * reading tile detections from the parent entry the sub was generated
     * against, and stamp it onto the sub as {@code source_roi_*_px}
     * metadata.
     *
     * <p>Background: when an acquisition is taken, {@code TilingUtilities}
     * writes detection objects on the parent entry's hierarchy with names
     * of the form {@code <tileIndex>_<annotationName>} and a
     * {@code TileNumber} measurement. The bounding-box union of those
     * tiles in the parent's pixel frame is the authoritative source
     * rectangle for the resulting sub-acquisition -- it bypasses the
     * alignment / flip / half-FOV math entirely.
     *
     * <p>If the parent is a legacy flipped sibling (e.g.
     * {@code "MetroHealth_142.svs (flipped X)"}), the bbox is mirrored
     * back to the unflipped-base coordinate frame so the stamp lines up
     * with the unflipped base entry that BACK propagation writes to. The
     * parent's flip pair is ALSO stored as {@code source_roi_flip_x/y} so
     * the GT path in {@link #propagateBack} knows the sub_px X/Y axes are
     * reversed inside the rectangle.
     *
     * <p>No-op when:
     * <ul>
     *   <li>the sub has no {@code original_image_id} or {@code annotation_name} metadata</li>
     *   <li>the parent entry cannot be located in the project</li>
     *   <li>the parent's hierarchy contains no tile detections matching the sub</li>
     * </ul>
     */
    private static boolean autoStampSourceRoiFromTiles(
            Project<BufferedImage> project,
            ProjectImageEntry<BufferedImage> sub,
            ProjectImageEntry<BufferedImage> unflippedBase,
            ImageData<BufferedImage> unflippedBaseData)
            throws Exception {

        String parentId = ImageMetadataManager.getOriginalImageId(sub);
        if (parentId == null || parentId.isEmpty()) {
            logger.debug("Auto-stamp: sub '{}' has no original_image_id; skipping.",
                    sub.getImageName());
            return false;
        }
        String annotationName = sub.getMetadata().get(ImageMetadataManager.ANNOTATION_NAME);
        if (annotationName == null || annotationName.isEmpty()) {
            logger.debug("Auto-stamp: sub '{}' has no annotation_name; skipping.",
                    sub.getImageName());
            return false;
        }

        ProjectImageEntry<BufferedImage> parent = null;
        for (ProjectImageEntry<BufferedImage> e : project.getImageList()) {
            if (parentId.equals(e.getID())) {
                parent = e;
                break;
            }
        }
        if (parent == null) {
            logger.info("Auto-stamp: parent ID '{}' (recorded on sub '{}') not found in project; "
                    + "skipping tile-detection ground-truth lookup.",
                    parentId, sub.getImageName());
            return false;
        }

        String suffix = "_" + annotationName;
        List<PathObject> matches = new ArrayList<>();
        ImageData<BufferedImage> parentData = parent.readImageData();
        for (PathObject obj : parentData.getHierarchy().getDetectionObjects()) {
            if (obj.getName() == null) continue;
            if (!obj.getName().endsWith(suffix)) continue;
            if (!obj.getMeasurements().containsKey("TileNumber")) continue;
            if (obj.getROI() == null) continue;
            matches.add(obj);
        }

        if (matches.isEmpty()) {
            logger.info("Auto-stamp: no tile detections matching '*{}' on parent '{}'; "
                    + "back-prop will fall back to alignment math.",
                    suffix, parent.getImageName());
            return false;
        }

        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (PathObject m : matches) {
            ROI r = m.getROI();
            minX = Math.min(minX, r.getBoundsX());
            minY = Math.min(minY, r.getBoundsY());
            maxX = Math.max(maxX, r.getBoundsX() + r.getBoundsWidth());
            maxY = Math.max(maxY, r.getBoundsY() + r.getBoundsHeight());
        }
        double rx = minX, ry = minY, rw = maxX - minX, rh = maxY - minY;

        boolean parentFlipX = "1".equals(parent.getMetadata().get(ImageMetadataManager.FLIP_X));
        boolean parentFlipY = "1".equals(parent.getMetadata().get(ImageMetadataManager.FLIP_Y));
        String parentName = parent.getImageName();
        if (parentName != null) {
            if (parentName.contains("(flipped XY)")) {
                parentFlipX = true;
                parentFlipY = true;
            } else if (parentName.contains("(flipped X)")) {
                parentFlipX = true;
            } else if (parentName.contains("(flipped Y)")) {
                parentFlipY = true;
            }
        }

        if (parentFlipX || parentFlipY) {
            int baseWidth = unflippedBaseData.getServer().getWidth();
            int baseHeight = unflippedBaseData.getServer().getHeight();
            AffineTransform mirror = createFlip(parentFlipX, parentFlipY, baseWidth, baseHeight);
            double[] in = new double[]{rx, ry, rx + rw, ry + rh};
            double[] out = new double[4];
            mirror.transform(in, 0, out, 0, 2);
            double mx = Math.min(out[0], out[2]);
            double my = Math.min(out[1], out[3]);
            double mw = Math.abs(out[2] - out[0]);
            double mh = Math.abs(out[3] - out[1]);
            logger.info("Auto-stamp: parent '{}' is flipped (X={}, Y={}); mirroring "
                    + "tile bbox ({}, {}, {}x{}) -> unflipped base ({}, {}, {}x{}); "
                    + "marking sub axes as flipped within rect.",
                    parent.getImageName(), parentFlipX, parentFlipY,
                    fmt(rx), fmt(ry), fmt(rw), fmt(rh),
                    fmt(mx), fmt(my), fmt(mw), fmt(mh));
            rx = mx; ry = my; rw = mw; rh = mh;
        } else {
            logger.info("Auto-stamp: parent '{}' is unflipped; tile bbox in base px: ({}, {}, {}x{})",
                    parent.getImageName(), fmt(rx), fmt(ry), fmt(rw), fmt(rh));
        }

        ImageMetadataManager.setSourceRoiPx(sub, rx, ry, rw, rh, parentFlipX, parentFlipY);
        try {
            project.syncChanges();
        } catch (Exception e) {
            logger.debug("syncChanges after auto-stamp: {}", e.getMessage());
        }
        logger.info("Auto-stamp: stamped source_roi_*_px on sub '{}' from {} tile detection(s) "
                + "on parent '{}' (rect=({}, {}, {}x{}) flip=({}, {})).",
                sub.getImageName(), matches.size(), parent.getImageName(),
                fmt(rx), fmt(ry), fmt(rw), fmt(rh), parentFlipX, parentFlipY);
        return true;
    }

    /** Locate the unflipped base entry for {@code baseName} (FLIP_X/Y both '0' or both absent). */
    private static ProjectImageEntry<BufferedImage> findUnflippedBase(
            Project<BufferedImage> project, String baseName) {
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            String imageName = entry.getImageName();
            if (imageName == null) continue;
            String stripped = GeneralTools.stripExtension(imageName);
            String rawBase = ImageMetadataManager.getBaseImage(entry);
            String effectiveBase = (rawBase != null && !rawBase.isEmpty()) ? rawBase : stripped;
            if (!baseName.equals(effectiveBase)) continue;
            // Skip sub-acquisitions
            boolean isBaseLike = stripped.equals(baseName)
                    || imageName.startsWith(baseName + ".");
            if (!isBaseLike) continue;
            // Skip flipped duplicates by name (legacy projects that haven't been migrated yet)
            if (imageName.contains("(flipped")) continue;
            String fx = entry.getMetadata().get(ImageMetadataManager.FLIP_X);
            String fy = entry.getMetadata().get(ImageMetadataManager.FLIP_Y);
            boolean isUnflipped = (fx == null || "0".equals(fx)) && (fy == null || "0".equals(fy));
            if (isUnflipped) return entry;
        }
        return null;
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

        // Source 2: config file (modality + objective + detector from metadata).
        // For cross-scope back-prop, the entry's objective/detector pair will
        // not exist in the active scope's config -- it only exists in the
        // scope that captured the sub. Try the active config first, then
        // fall back to the sub's source-scope config.
        if (entry != null) {
            Map<String, String> meta = entry.getMetadata();
            String modality = meta.get(ImageMetadataManager.MODALITY);
            String objective = meta.get(ImageMetadataManager.OBJECTIVE);
            String detector = meta.get(ImageMetadataManager.DETECTOR_ID);
            String sourceScope = meta.get(ImageMetadataManager.SOURCE_MICROSCOPE);
            if (modality != null && objective != null && detector != null) {
                try {
                    MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
                    if (mgr != null) {
                        double[] fov = mgr.getCameraFOV(modality, objective, detector);
                        if (fov != null && fov[0] > 0 && fov[1] > 0) {
                            logger.info("FOV from active config: {}x{} um (modality={}, obj={}, det={})",
                                    fov[0], fov[1], modality, objective, detector);
                            return fov;
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Active config could not resolve FOV: {}", e.getMessage());
                }
                // Cross-scope fallback: load source-scope's config.
                if (sourceScope != null && !sourceScope.isEmpty()) {
                    try {
                        String activeCfg = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                        if (activeCfg != null && !activeCfg.isEmpty()) {
                            java.io.File configDir = new java.io.File(activeCfg).getParentFile();
                            if (configDir != null && configDir.exists()) {
                                java.io.File scopeCfg = new java.io.File(configDir, "config_" + sourceScope + ".yml");
                                if (scopeCfg.exists()) {
                                    MicroscopeConfigManager detached =
                                            MicroscopeConfigManager.createDetached(scopeCfg.getAbsolutePath());
                                    if (detached != null) {
                                        double[] fov = detached.getCameraFOV(modality, objective, detector);
                                        if (fov != null && fov[0] > 0 && fov[1] > 0) {
                                            logger.info("FOV from source-scope ('{}') config: {}x{} um "
                                                    + "(modality={}, obj={}, det={})",
                                                    sourceScope, fov[0], fov[1], modality, objective, detector);
                                            return fov;
                                        }
                                    }
                                } else {
                                    logger.warn("Source-scope config '{}' not found at {}",
                                            sourceScope, scopeCfg.getAbsolutePath());
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Source-scope ('{}') config FOV lookup failed: {}",
                                sourceScope, e.getMessage());
                    }
                }
            }
        }

        // Source 3: live microscope (legacy fallback). Skip when the entry was
        // acquired on a scope different from the active one -- the live FOV
        // will be the wrong scope's optics.
        if (entry != null) {
            String entryScope = entry.getMetadata().get(ImageMetadataManager.SOURCE_MICROSCOPE);
            String activeScope = null;
            try {
                MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
                if (mgr != null) activeScope = mgr.getMicroscopeName();
            } catch (Exception ignore) {
            }
            if (entryScope != null && activeScope != null && !entryScope.equals(activeScope)) {
                logger.warn("Skipping live-microscope FOV fallback: entry source='{}' != active='{}'. "
                        + "FOV would be wrong scope; back-prop offset will be uncorrected.",
                        entryScope, activeScope);
                return null;
            }
        }
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
