package qupath.ext.qpsc.model;

import java.awt.image.BufferedImage;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Container for metadata to be applied to stitched images.
 * Includes both core metadata (offsets, flip status) and identification fields
 * (modality, objective, angle, annotation).
 */
public class StitchingMetadata {
    public final ProjectImageEntry<BufferedImage> parentEntry;
    public final double xOffset;
    public final double yOffset;
    public final boolean flipX;
    public final boolean flipY;
    public final String sampleName;

    // Additional identification fields
    public final String modality;
    public final String objective;
    public final String angle;
    public final String annotationName;
    public final Integer imageIndex;

    // Detector that captured this stitched image (e.g. "LOCI_DETECTOR_JAI_001").
    // Per-detector flip / WB / future re-processing all key off this. May be
    // null for legacy callers; downstream code should fall back to the
    // QuPath project entry's existing DETECTOR_ID metadata when null.
    public final String detector;

    // Source microscope / scanner that produced this image, e.g. "PPM",
    // "OWS3", "CAMM", "Ocus40". Recorded for cross-system alignment and
    // pick-on-one / image-on-another publication workflows. Resolved per
    // workflow: acquisitions take MicroscopeConfigManager.getMicroscopeName(),
    // imported macros take the user's MicroscopeSelectionDialog choice.
    public final String sourceMicroscope;

    // Optional stage bounds for the acquisition region, in stage micrometers.
    // Populated by BoundingBox acquisitions (and any other flow where the
    // stage coverage of the stitched image is known up front). When non-null,
    // downstream import sites use these bounds to auto-register a pixel->stage
    // affine transform via AffineTransformManager.saveSlideAlignment, so that
    // Live Viewer Move-to-centroid / click-to-center works on the resulting
    // image without a separate alignment step. null for annotation-based
    // acquisitions, which inherit alignment from the parent macro image.
    public final Double stageBoundsX1Um;
    public final Double stageBoundsY1Um;
    public final Double stageBoundsX2Um;
    public final Double stageBoundsY2Um;

    // Camera field of view at acquisition time. Stored per-image so coordinate
    // propagation works offline (different objective or no microscope connection).
    public final Double fovXUm;
    public final Double fovYUm;

    // Optional base_image override. When set, the import path stamps this
    // value into the new entry's BASE_IMAGE metadata *before* the
    // ImageMetadataManager fallback computes one from the entry's own file
    // name. Lets BoundingBox / Bounded acquisitions group their 4 (or N)
    // angle entries under a single base_image so the QuPath project sort
    // shows them as one collection per acquisition. Only consulted when
    // parentEntry is null (parent-rooted entries always inherit, no
    // ambiguity to resolve). null = use the inheritance / own-name fallback.
    public final String baseImageOverride;

    /**
     * Full constructor with all metadata fields.
     */
    public StitchingMetadata(
            ProjectImageEntry<BufferedImage> parentEntry,
            double xOffset,
            double yOffset,
            boolean flipX,
            boolean flipY,
            String sampleName,
            String modality,
            String objective,
            String angle,
            String annotationName,
            Integer imageIndex,
            Double stageBoundsX1Um,
            Double stageBoundsY1Um,
            Double stageBoundsX2Um,
            Double stageBoundsY2Um,
            Double fovXUm,
            Double fovYUm,
            String detector,
            String sourceMicroscope,
            String baseImageOverride) {
        this.parentEntry = parentEntry;
        this.xOffset = xOffset;
        this.yOffset = yOffset;
        this.flipX = flipX;
        this.flipY = flipY;
        this.sampleName = sampleName;
        this.modality = modality;
        this.objective = objective;
        this.angle = angle;
        this.annotationName = annotationName;
        this.imageIndex = imageIndex;
        this.stageBoundsX1Um = stageBoundsX1Um;
        this.stageBoundsY1Um = stageBoundsY1Um;
        this.stageBoundsX2Um = stageBoundsX2Um;
        this.stageBoundsY2Um = stageBoundsY2Um;
        this.fovXUm = fovXUm;
        this.fovYUm = fovYUm;
        this.detector = detector;
        this.sourceMicroscope = sourceMicroscope;
        this.baseImageOverride = baseImageOverride;
    }

    /**
     * Convenience constructor that omits {@link #baseImageOverride}; legacy
     * call sites which don't group angle entries pass through here.
     */
    public StitchingMetadata(
            ProjectImageEntry<BufferedImage> parentEntry,
            double xOffset,
            double yOffset,
            boolean flipX,
            boolean flipY,
            String sampleName,
            String modality,
            String objective,
            String angle,
            String annotationName,
            Integer imageIndex,
            Double stageBoundsX1Um,
            Double stageBoundsY1Um,
            Double stageBoundsX2Um,
            Double stageBoundsY2Um,
            Double fovXUm,
            Double fovYUm,
            String detector,
            String sourceMicroscope) {
        this(
                parentEntry,
                xOffset,
                yOffset,
                flipX,
                flipY,
                sampleName,
                modality,
                objective,
                angle,
                annotationName,
                imageIndex,
                stageBoundsX1Um,
                stageBoundsY1Um,
                stageBoundsX2Um,
                stageBoundsY2Um,
                fovXUm,
                fovYUm,
                detector,
                sourceMicroscope,
                null);
    }

    /**
     * Backwards-compat constructor without detector / source microscope.
     */
    public StitchingMetadata(
            ProjectImageEntry<BufferedImage> parentEntry,
            double xOffset,
            double yOffset,
            boolean flipX,
            boolean flipY,
            String sampleName,
            String modality,
            String objective,
            String angle,
            String annotationName,
            Integer imageIndex,
            Double stageBoundsX1Um,
            Double stageBoundsY1Um,
            Double stageBoundsX2Um,
            Double stageBoundsY2Um,
            Double fovXUm,
            Double fovYUm) {
        this(
                parentEntry,
                xOffset,
                yOffset,
                flipX,
                flipY,
                sampleName,
                modality,
                objective,
                angle,
                annotationName,
                imageIndex,
                stageBoundsX1Um,
                stageBoundsY1Um,
                stageBoundsX2Um,
                stageBoundsY2Um,
                fovXUm,
                fovYUm,
                null,
                null);
    }

    /**
     * Full constructor without stage bounds (legacy identification-field flow).
     */
    public StitchingMetadata(
            ProjectImageEntry<BufferedImage> parentEntry,
            double xOffset,
            double yOffset,
            boolean flipX,
            boolean flipY,
            String sampleName,
            String modality,
            String objective,
            String angle,
            String annotationName,
            Integer imageIndex) {
        this(
                parentEntry,
                xOffset,
                yOffset,
                flipX,
                flipY,
                sampleName,
                modality,
                objective,
                angle,
                annotationName,
                imageIndex,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * Convenience constructor for basic metadata.
     * Creates metadata with null for optional identification fields and
     * no stage bounds (no auto-transform registration).
     */
    public StitchingMetadata(
            ProjectImageEntry<BufferedImage> parentEntry,
            double xOffset,
            double yOffset,
            boolean flipX,
            boolean flipY,
            String sampleName) {
        this(parentEntry, xOffset, yOffset, flipX, flipY, sampleName, null, null, null, null, null);
    }

    /**
     * Returns true when this metadata carries a fully-specified stage bounds
     * rectangle (all four corners non-null and the rectangle is non-degenerate).
     * Used by the import path to decide whether to auto-register an alignment.
     */
    public boolean hasStageBounds() {
        return stageBoundsX1Um != null
                && stageBoundsY1Um != null
                && stageBoundsX2Um != null
                && stageBoundsY2Um != null
                && stageBoundsX2Um > stageBoundsX1Um
                && stageBoundsY2Um > stageBoundsY1Um;
    }
}
