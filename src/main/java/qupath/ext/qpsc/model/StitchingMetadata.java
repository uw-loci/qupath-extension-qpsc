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
            Integer imageIndex) {
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
    }

    /**
     * Convenience constructor for basic metadata.
     * Creates metadata with null for optional identification fields.
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
}
