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
            Double fovYUm) {
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
                null, null, null, null,
                null, null);
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
