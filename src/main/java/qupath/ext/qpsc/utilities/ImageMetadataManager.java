package qupath.ext.qpsc.utilities;

import java.io.IOException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.lib.common.GeneralTools;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.scripting.QP;

/**
 * ImageMetadataManager
 *
 * <p>Manages metadata for images in multi-sample projects:
 *   - Image collection grouping
 *   - Position offsets
 *   - Flip status tracking
 *   - Sample name management
 *
 * <p>This enables support for multiple samples per project with proper
 * metadata tracking and validation.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class ImageMetadataManager {
    private static final Logger logger = LoggerFactory.getLogger(ImageMetadataManager.class);

    // Metadata keys - using underscores for consistency with QuPath conventions
    public static final String IMAGE_COLLECTION = "image_collection";
    public static final String XY_OFFSET_X = "xy_offset_x_microns";
    public static final String XY_OFFSET_Y = "xy_offset_y_microns";
    public static final String Z_OFFSET = "z_offset_microns";

    // FLIP_X / FLIP_Y record the OPTICAL FLIP status of this image.
    // These indicate whether the microscope's light path mirrors the camera image
    // relative to the physical slide. This is NOT stage axis inversion (which is a
    // separate hardware property stored in preferences as stageInvertedX/Y).
    // See CLAUDE.md "COORDINATE SYSTEM TERMINOLOGY" for full details.
    public static final String FLIP_X = "flip_x";
    public static final String FLIP_Y = "flip_y";
    public static final String SAMPLE_NAME = "sample_name";
    public static final String ORIGINAL_IMAGE_ID = "original_image_id";

    // Additional metadata keys for image identification
    public static final String MODALITY = "modality";
    public static final String OBJECTIVE = "objective";
    public static final String ANGLE = "angle";
    public static final String ANNOTATION_NAME = "annotation_name";
    public static final String IMAGE_INDEX = "image_index";
    public static final String BASE_IMAGE = "base_image";

    // Detector that captured this image (per-detector flip, WB lookup, etc.)
    public static final String DETECTOR_ID = "detector_id";

    // PPM analysis metadata
    public static final String PPM_CALIBRATION = "ppm_calibration";

    /**
     * Gets the next available image collection number for a project.
     * Scans all existing images to find the highest collection number and returns that + 1.
     *
     * @param project The QuPath project
     * @return The next available collection number (minimum 1)
     */
    public static int getNextImageCollectionNumber(Project<?> project) {
        if (project == null) {
            logger.warn("Project is null, returning collection number 1");
            return 1;
        }

        int maxCollection = 0;

        for (ProjectImageEntry<?> entry : project.getImageList()) {
            Map<String, String> metadata = entry.getMetadata();
            String collectionStr = metadata.get(IMAGE_COLLECTION);
            if (collectionStr != null) {
                try {
                    int collection = Integer.parseInt(collectionStr);
                    maxCollection = Math.max(maxCollection, collection);
                } catch (NumberFormatException e) {
                    logger.warn(
                            "Invalid image_collection value '{}' for entry: {}", collectionStr, entry.getImageName());
                }
            }
        }

        int nextCollection = maxCollection + 1;
        logger.debug("Next image collection number: {}", nextCollection);
        return nextCollection;
    }

    /**
     * Applies comprehensive metadata to a new image entry including all identification fields.
     *
     * @param entry The image entry to apply metadata to
     * @param parentEntry Optional parent entry for collection inheritance
     * @param xOffset X offset from slide corner in microns
     * @param yOffset Y offset from slide corner in microns
     * @param flipX Whether the image has been flipped horizontally
     * @param flipY Whether the image has been flipped vertically
     * @param sampleName The sample name
     * @param modality The imaging modality (e.g., "ppm", "bf")
     * @param objective The objective/magnification (e.g., "20x", "10x")
     * @param angle The angle for multi-angle acquisitions (null if not applicable)
     * @param annotationName The annotation name (null if not applicable)
     * @param imageIndex The image index number
     */
    public static void applyImageMetadata(
            ProjectImageEntry<?> entry,
            ProjectImageEntry<?> parentEntry,
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
        applyImageMetadata(
                entry,
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
                null);
    }

    /**
     * Applies comprehensive metadata to a new image entry including all identification fields
     * and the detector that captured the image.
     *
     * @param entry The image entry to apply metadata to
     * @param parentEntry Optional parent entry for collection inheritance
     * @param xOffset X offset from slide corner in microns
     * @param yOffset Y offset from slide corner in microns
     * @param flipX Whether the image has been flipped horizontally
     * @param flipY Whether the image has been flipped vertically
     * @param sampleName The sample name
     * @param modality The imaging modality (e.g., "ppm", "bf")
     * @param objective The objective/magnification (e.g., "20x", "10x")
     * @param angle The angle for multi-angle acquisitions (null if not applicable)
     * @param annotationName The annotation name (null if not applicable)
     * @param imageIndex The image index number
     * @param detectorId The detector that captured this image (null if unknown).
     *                   Stored for per-detector flip lookup in future workflows.
     */
    public static void applyImageMetadata(
            ProjectImageEntry<?> entry,
            ProjectImageEntry<?> parentEntry,
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
            String detectorId) {
        if (entry == null) {
            logger.error("Cannot apply metadata to null entry");
            return;
        }

        Map<String, String> metadata = entry.getMetadata();

        // Determine collection number
        String collectionNumber;
        if (parentEntry != null && parentEntry.getMetadata().get(IMAGE_COLLECTION) != null) {
            // Inherit from parent
            collectionNumber = parentEntry.getMetadata().get(IMAGE_COLLECTION);
            logger.info("Inheriting image_collection {} from parent: {}", collectionNumber, parentEntry.getImageName());
        } else {
            // Get next available number
            Project<?> project = QP.getProject();
            collectionNumber = String.valueOf(getNextImageCollectionNumber(project));
            logger.info("Assigning new image_collection: {}", collectionNumber);
        }

        // Determine base_image - inherit from parent chain or set from own name
        String baseImage = null;
        if (parentEntry != null) {
            // First try to inherit from parent's base_image (follows the chain)
            baseImage = parentEntry.getMetadata().get(BASE_IMAGE);
            if (baseImage != null && !baseImage.isEmpty()) {
                logger.info("Inheriting base_image '{}' from parent: {}", baseImage, parentEntry.getImageName());
            } else {
                // Parent doesn't have base_image - use parent's image name as base
                baseImage = GeneralTools.stripExtension(parentEntry.getImageName());
                logger.info("Setting base_image to parent's name: {}", baseImage);
            }
        } else {
            // No parent - check if entry already has base_image set
            baseImage = metadata.get(BASE_IMAGE);
            if (baseImage == null || baseImage.isEmpty()) {
                // Set to this image's own name (stripped of extension)
                baseImage = GeneralTools.stripExtension(entry.getImageName());
                logger.info("Setting base_image to own name: {}", baseImage);
            }
        }

        // Apply all metadata
        metadata.put(IMAGE_COLLECTION, collectionNumber);
        metadata.put(XY_OFFSET_X, String.valueOf(xOffset));
        metadata.put(XY_OFFSET_Y, String.valueOf(yOffset));
        metadata.put(Z_OFFSET, String.valueOf(0.0)); // Z offset stored for voxel support
        metadata.put(FLIP_X, flipX ? "1" : "0");
        metadata.put(FLIP_Y, flipY ? "1" : "0");

        if (baseImage != null && !baseImage.isEmpty()) {
            metadata.put(BASE_IMAGE, baseImage);
        }

        if (sampleName != null && !sampleName.isEmpty()) {
            metadata.put(SAMPLE_NAME, sampleName);
        }

        if (modality != null && !modality.isEmpty()) {
            metadata.put(MODALITY, modality);
        }

        if (objective != null && !objective.isEmpty()) {
            metadata.put(OBJECTIVE, objective);
        }

        if (angle != null && !angle.isEmpty()) {
            metadata.put(ANGLE, angle);
        }

        if (annotationName != null && !annotationName.isEmpty()) {
            metadata.put(ANNOTATION_NAME, annotationName);
        }

        if (imageIndex != null) {
            metadata.put(IMAGE_INDEX, String.valueOf(imageIndex));
        }

        if (detectorId != null && !detectorId.isEmpty()) {
            metadata.put(DETECTOR_ID, detectorId);
        }

        // If this is a flipped duplicate, store reference to original
        if (parentEntry != null && (flipX || flipY)) {
            metadata.put(ORIGINAL_IMAGE_ID, parentEntry.getID());
        }

        // Propagate metadata entries that start with the configured prefix from parent
        if (parentEntry != null) {
            propagatePrefixedMetadata(parentEntry, metadata);
        }

        logger.debug(
                "Applied metadata to {}: collection={}, base_image={}, offset=({},{}), flipX={}, flipY={}, sample={}, modality={}, objective={}, angle={}, annotation={}, index={}, detector={}",
                entry.getImageName(),
                collectionNumber,
                baseImage,
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
                detectorId);
    }

    /**
     * Propagates metadata entries from parent that start with the configured prefix.
     *
     * <p>This allows metadata such as OCR-extracted text fields to be automatically
     * inherited by child images during acquisition workflows. The prefix is configured
     * in Edit > Preferences under "Metadata Propagation Prefix" (default: "OCR").
     *
     * @param parentEntry The parent image entry to copy metadata from
     * @param targetMetadata The target metadata map to copy into
     */
    private static void propagatePrefixedMetadata(
            ProjectImageEntry<?> parentEntry, Map<String, String> targetMetadata) {
        if (parentEntry == null || targetMetadata == null) {
            return;
        }

        String prefix = QPPreferenceDialog.getMetadataPropagationPrefix();
        if (prefix == null || prefix.isEmpty()) {
            logger.debug("Metadata propagation prefix is empty, skipping propagation");
            return;
        }

        Map<String, String> parentMetadata = parentEntry.getMetadata();
        int propagatedCount = 0;

        for (Map.Entry<String, String> entry : parentMetadata.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.startsWith(prefix)) {
                String value = entry.getValue();
                if (value != null && !value.isEmpty()) {
                    targetMetadata.put(key, value);
                    propagatedCount++;
                    logger.debug("Propagated metadata: {} = {}", key, value);
                }
            }
        }

        if (propagatedCount > 0) {
            logger.info(
                    "Propagated {} metadata entries with prefix '{}' from parent: {}",
                    propagatedCount,
                    prefix,
                    parentEntry.getImageName());
        }
    }

    /**
     * Applies metadata to a new image entry based on its parent (if any).
     * If parent exists, inherits the image_collection value.
     * This is a convenience method that calls the full version with null for optional fields.
     *
     * @param entry The image entry to apply metadata to
     * @param parentEntry Optional parent entry for collection inheritance
     * @param xOffset X offset from slide corner in microns
     * @param yOffset Y offset from slide corner in microns
     * @param flipX Whether the image has been flipped horizontally
     * @param flipY Whether the image has been flipped vertically
     * @param sampleName The sample name
     */
    public static void applyImageMetadata(
            ProjectImageEntry<?> entry,
            ProjectImageEntry<?> parentEntry,
            double xOffset,
            double yOffset,
            boolean flipX,
            boolean flipY,
            String sampleName) {
        applyImageMetadata(
                entry, parentEntry, xOffset, yOffset, flipX, flipY, sampleName, null, null, null, null, null);
    }

    /**
     * Validates if an image can be used for acquisition based on flip requirements.
     *
     * @param entry The image entry to validate
     * @return true if valid for acquisition, false otherwise
     */
    public static boolean isValidForAcquisition(ProjectImageEntry<?> entry) {
        if (entry == null) {
            logger.error("Cannot validate null entry");
            return false;
        }

        // Get flip requirements from QPPreferenceDialog
        boolean requiresFlipX = QPPreferenceDialog.getFlipMacroXProperty();
        boolean requiresFlipY = QPPreferenceDialog.getFlipMacroYProperty();

        // If no flipping required, all images are valid
        if (!requiresFlipX && !requiresFlipY) {
            logger.debug("No flip required - image {} is valid", entry.getImageName());
            return true;
        }

        // Check if image is marked as flipped in the required axes
        boolean imageFlipX = isFlippedX(entry);
        boolean imageFlipY = isFlippedY(entry);

        if (requiresFlipX && !imageFlipX) {
            logger.warn("Image {} is not flipped on X but flipX is required in preferences", entry.getImageName());
            return false;
        }

        if (requiresFlipY && !imageFlipY) {
            logger.warn("Image {} is not flipped on Y but flipY is required in preferences", entry.getImageName());
            return false;
        }

        logger.debug(
                "Image {} validation passed (flipX={}, flipY={}, requiresFlipX={}, requiresFlipY={})",
                entry.getImageName(),
                imageFlipX,
                imageFlipY,
                requiresFlipX,
                requiresFlipY);
        return true;
    }

    /**
     * Initializes metadata for all images in a project that don't have it.
     * Ensures all images have proper collection, base_image, and sample metadata.
     *
     * @param project The project to initialize
     */
    public static void initializeProjectMetadata(Project<?> project) {
        if (project == null) {
            logger.warn("Cannot initialize metadata for null project");
            return;
        }

        logger.info(
                "Initializing metadata for project with {} images",
                project.getImageList().size());

        boolean anyChanges = false;

        for (ProjectImageEntry<?> entry : project.getImageList()) {
            Map<String, String> metadata = entry.getMetadata();

            if (metadata.get(IMAGE_COLLECTION) == null) {
                metadata.put(IMAGE_COLLECTION, "1");
                anyChanges = true;
                logger.debug("Initialized image_collection=1 for: {}", entry.getImageName());
            }

            // Initialize base_image if missing - set to image's own name (without extension)
            if (metadata.get(BASE_IMAGE) == null) {
                String baseImage = GeneralTools.stripExtension(entry.getImageName());
                metadata.put(BASE_IMAGE, baseImage);
                anyChanges = true;
                logger.debug("Initialized base_image='{}' for: {}", baseImage, entry.getImageName());
            }

            // Initialize other fields with defaults if missing
            if (metadata.get(XY_OFFSET_X) == null) {
                metadata.put(XY_OFFSET_X, "0.0");
                anyChanges = true;
            }
            if (metadata.get(XY_OFFSET_Y) == null) {
                metadata.put(XY_OFFSET_Y, "0.0");
                anyChanges = true;
            }
            if (metadata.get(FLIP_X) == null) {
                metadata.put(FLIP_X, "0");
                anyChanges = true;
            }
            if (metadata.get(FLIP_Y) == null) {
                metadata.put(FLIP_Y, "0");
                anyChanges = true;
            }
        }

        if (anyChanges) {
            try {
                project.syncChanges();
                logger.info("Successfully initialized project metadata");
            } catch (IOException e) {
                logger.error("Failed to sync project after metadata initialization", e);
            }
        }
    }

    /**
     * Gets the image collection number for an entry.
     *
     * @param entry The image entry
     * @return The collection number, or -1 if not set or invalid
     */
    public static int getImageCollection(ProjectImageEntry<?> entry) {
        if (entry == null) {
            return -1;
        }

        String collectionStr = entry.getMetadata().get(IMAGE_COLLECTION);
        if (collectionStr == null) {
            return -1;
        }

        try {
            return Integer.parseInt(collectionStr);
        } catch (NumberFormatException e) {
            logger.warn("Invalid image_collection value: {}", collectionStr);
            return -1;
        }
    }

    /**
     * Gets the XY offset for an image entry.
     *
     * @param entry The image entry
     * @return Array of [x, y] offsets in microns, or [0, 0] if not set
     */
    public static double[] getXYOffset(ProjectImageEntry<?> entry) {
        if (entry == null) {
            return new double[] {0, 0};
        }

        Map<String, String> metadata = entry.getMetadata();
        try {
            double x = Double.parseDouble(metadata.get(XY_OFFSET_X));
            double y = Double.parseDouble(metadata.get(XY_OFFSET_Y));
            return new double[] {x, y};
        } catch (Exception e) {
            logger.debug("Could not parse XY offset for {}: {}", entry.getImageName(), e.getMessage());
            return new double[] {0, 0};
        }
    }

    /**
     * Gets the XYZ offset for an image entry.
     *
     * @param entry The image entry
     * @return Array of [x, y, z] offsets in microns, or [0, 0, 0] if not set
     */
    public static double[] getXYZOffset(ProjectImageEntry<?> entry) {
        if (entry == null) {
            return new double[] {0, 0, 0};
        }

        Map<String, String> metadata = entry.getMetadata();
        try {
            double x = Double.parseDouble(metadata.getOrDefault(XY_OFFSET_X, "0"));
            double y = Double.parseDouble(metadata.getOrDefault(XY_OFFSET_Y, "0"));
            double z = Double.parseDouble(metadata.getOrDefault(Z_OFFSET, "0"));
            return new double[] {x, y, z};
        } catch (Exception e) {
            logger.debug("Could not parse XYZ offset for {}: {}", entry.getImageName(), e.getMessage());
            return new double[] {0, 0, 0};
        }
    }

    /**
     * Gets the Z offset for an image entry.
     *
     * @param entry The image entry
     * @return Z offset in microns, or 0.0 if not set
     */
    public static double getZOffset(ProjectImageEntry<?> entry) {
        if (entry == null) {
            return 0.0;
        }
        try {
            String val = entry.getMetadata().get(Z_OFFSET);
            return val != null ? Double.parseDouble(val) : 0.0;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Checks if an image entry is marked as flipped on the X axis.
     *
     * @param entry The image entry to check
     * @return true if the image is flipped horizontally, false otherwise
     */
    public static boolean isFlippedX(ProjectImageEntry<?> entry) {
        if (entry == null) {
            return false;
        }

        String flipXStr = entry.getMetadata().get(FLIP_X);
        return "1".equals(flipXStr);
    }

    /**
     * Checks if an image entry is marked as flipped on the Y axis.
     *
     * @param entry The image entry to check
     * @return true if the image is flipped vertically, false otherwise
     */
    public static boolean isFlippedY(ProjectImageEntry<?> entry) {
        if (entry == null) {
            return false;
        }

        String flipYStr = entry.getMetadata().get(FLIP_Y);
        return "1".equals(flipYStr);
    }

    /**
     * Checks if an image entry is marked as flipped on either axis.
     *
     * @param entry The image entry to check
     * @return true if the image is flipped on X or Y axis, false otherwise
     */
    public static boolean isFlipped(ProjectImageEntry<?> entry) {
        return isFlippedX(entry) || isFlippedY(entry);
    }

    /**
     * Gets the detector ID that captured this image.
     *
     * @param entry The image entry
     * @return The detector ID (e.g., "LOCI_DETECTOR_JAI_001"), or null if not set
     */
    public static String getDetectorId(ProjectImageEntry<?> entry) {
        if (entry == null) return null;
        return entry.getMetadata().get(DETECTOR_ID);
    }

    /**
     * Gets the sample name from an image entry.
     *
     * @param entry The image entry
     * @return The sample name, or null if not set
     */
    public static String getSampleName(ProjectImageEntry<?> entry) {
        if (entry == null) {
            return null;
        }

        return entry.getMetadata().get(SAMPLE_NAME);
    }

    /**
     * Gets the original image ID for a flipped duplicate.
     *
     * @param entry The image entry
     * @return The original image ID, or null if not a flipped duplicate
     */
    public static String getOriginalImageId(ProjectImageEntry<?> entry) {
        if (entry == null) {
            return null;
        }

        return entry.getMetadata().get(ORIGINAL_IMAGE_ID);
    }

    /**
     * Gets the base image name from an image entry.
     *
     * <p>The base image is the original source image from which this image
     * (and any intermediate images) was derived. This value is inherited
     * through the parent chain and remains consistent across all derived
     * images (flipped duplicates, stitched acquisitions, sub-acquisitions).
     *
     * @param entry The image entry
     * @return The base image name, or null if not set
     */
    public static String getBaseImage(ProjectImageEntry<?> entry) {
        if (entry == null) {
            return null;
        }

        return entry.getMetadata().get(BASE_IMAGE);
    }

    /**
     * Checks if an entry belongs to a specific image collection.
     *
     * @param entry The image entry to check
     * @param collectionNumber The collection number to compare against
     * @return true if the entry belongs to the specified collection
     */
    public static boolean isInCollection(ProjectImageEntry<?> entry, int collectionNumber) {
        return getImageCollection(entry) == collectionNumber;
    }

    /**
     * Gets all metadata as an unmodifiable map.
     *
     * @param entry The image entry
     * @return Unmodifiable map of all metadata, or empty map if entry is null
     */
    public static Map<String, String> getAllMetadata(ProjectImageEntry<?> entry) {
        if (entry == null) {
            return Collections.emptyMap();
        }

        return Collections.unmodifiableMap(entry.getMetadata());
    }

    /**
     * Updates a single metadata value.
     *
     * @param entry The image entry to update
     * @param key The metadata key
     * @param value The value to set
     * @param syncProject Whether to sync project changes immediately
     */
    public static void updateMetadataValue(ProjectImageEntry<?> entry, String key, String value, boolean syncProject) {
        if (entry == null || key == null) {
            logger.warn("Cannot update metadata with null entry or key");
            return;
        }

        entry.getMetadata().put(key, value);
        logger.debug("Updated metadata for {}: {} = {}", entry.getImageName(), key, value);

        if (syncProject) {
            Project<?> project = QP.getProject();
            if (project != null) {
                try {
                    project.syncChanges();
                } catch (IOException e) {
                    logger.error("Failed to sync project after metadata update", e);
                }
            }
        }
    }

    // ========================================================================
    // PPM Analysis Set Discovery
    // ========================================================================

    /**
     * Result of PPM analysis set discovery. Contains the co-registered images
     * that form a complete PPM analysis set: sum, birefringence, and optionally
     * individual angle images.
     */
    public static class PPMAnalysisSet {
        public final ProjectImageEntry<?> sumImage;
        public final ProjectImageEntry<?> birefImage;
        public final List<ProjectImageEntry<?>> angleImages;
        public final String calibrationPath;

        public PPMAnalysisSet(
                ProjectImageEntry<?> sumImage,
                ProjectImageEntry<?> birefImage,
                List<ProjectImageEntry<?>> angleImages,
                String calibrationPath) {
            this.sumImage = sumImage;
            this.birefImage = birefImage;
            this.angleImages = angleImages != null ? angleImages : List.of();
            this.calibrationPath = calibrationPath;
        }

        public boolean hasSumImage() {
            return sumImage != null;
        }

        public boolean hasBirefImage() {
            return birefImage != null;
        }

        public boolean hasCalibration() {
            return calibrationPath != null && !calibrationPath.isEmpty();
        }
    }

    /**
     * Finds the PPM analysis set for a given image entry.
     *
     * <p>Given any PPM image (sum, biref, or angle), finds its siblings by matching
     * on {@code image_collection}, {@code sample_name}, and {@code annotation_name}.
     * Biref images are identified by "_biref" in their angle metadata or image name;
     * sum images by "_sum".</p>
     *
     * <p>The calibration path is taken from the first image in the set that has
     * {@code ppm_calibration} metadata, or falls back to the active calibration
     * preference if none is set.</p>
     *
     * @param entry Any image entry in the PPM analysis set
     * @param project The QuPath project to search
     * @return The PPM analysis set, or null if the entry has no collection metadata
     */
    public static PPMAnalysisSet findPPMAnalysisSet(ProjectImageEntry<?> entry, Project<?> project) {
        if (entry == null || project == null) {
            return null;
        }

        int collection = getImageCollection(entry);
        if (collection < 0) {
            logger.warn("Image {} has no image_collection metadata", entry.getImageName());
            return null;
        }

        String sampleName = getSampleName(entry);
        String annotationName = entry.getMetadata().get(ANNOTATION_NAME);

        // Find all images in same collection + sample + annotation
        List<ProjectImageEntry<?>> siblings = new ArrayList<>();
        for (ProjectImageEntry<?> candidate : project.getImageList()) {
            if (getImageCollection(candidate) != collection) continue;

            String candSample = getSampleName(candidate);
            String candAnnotation = candidate.getMetadata().get(ANNOTATION_NAME);

            // Match sample name (both null = match)
            if (!Objects.equals(sampleName, candSample)) continue;

            // Match annotation name (both null = match)
            if (!Objects.equals(annotationName, candAnnotation)) continue;

            // Must be a PPM modality image
            String modality = candidate.getMetadata().get(MODALITY);
            if (modality == null || !modality.toLowerCase().startsWith("ppm")) continue;

            siblings.add(candidate);
        }

        // Classify siblings into sum, biref, and angle images
        ProjectImageEntry<?> sumImage = null;
        ProjectImageEntry<?> birefImage = null;
        List<ProjectImageEntry<?>> angleImages = new ArrayList<>();
        String calibrationPath = null;

        for (ProjectImageEntry<?> sibling : siblings) {
            String angle = sibling.getMetadata().get(ANGLE);
            String imageName = sibling.getImageName().toLowerCase();

            // Check for calibration on any sibling
            String siblingCalib = sibling.getMetadata().get(PPM_CALIBRATION);
            if (siblingCalib != null && !siblingCalib.isEmpty() && calibrationPath == null) {
                calibrationPath = siblingCalib;
            }

            if (isBirefImage(angle, imageName)) {
                birefImage = sibling;
            } else if (isSumImage(angle, imageName)) {
                sumImage = sibling;
            } else {
                angleImages.add(sibling);
            }
        }

        logger.debug(
                "PPM analysis set for collection {}: sum={}, biref={}, angles={}, calibration={}",
                collection,
                sumImage != null ? sumImage.getImageName() : "none",
                birefImage != null ? birefImage.getImageName() : "none",
                angleImages.size(),
                calibrationPath != null ? calibrationPath : "none");

        return new PPMAnalysisSet(sumImage, birefImage, angleImages, calibrationPath);
    }

    /**
     * Gets the PPM calibration path stored in this image's metadata.
     *
     * @param entry The image entry
     * @return The calibration file path, or null if not set
     */
    public static String getPPMCalibration(ProjectImageEntry<?> entry) {
        if (entry == null) return null;
        String path = entry.getMetadata().get(PPM_CALIBRATION);
        return (path != null && !path.isEmpty()) ? path : null;
    }

    /**
     * Sets the PPM calibration path on an image entry.
     *
     * @param entry The image entry
     * @param calibrationPath Absolute path to the .npz calibration file
     * @param syncProject Whether to sync project changes immediately
     */
    public static void setPPMCalibration(ProjectImageEntry<?> entry, String calibrationPath, boolean syncProject) {
        updateMetadataValue(entry, PPM_CALIBRATION, calibrationPath, syncProject);
    }

    /**
     * Stamps the given calibration path onto all PPM images in the specified
     * collection that do not already have a calibration set.
     *
     * @param project The QuPath project
     * @param collection The image collection number
     * @param calibrationPath The calibration file path to stamp
     * @return Number of images updated
     */
    public static int stampCalibrationOnCollection(Project<?> project, int collection, String calibrationPath) {
        if (project == null || calibrationPath == null || calibrationPath.isEmpty()) {
            return 0;
        }

        int updated = 0;
        for (ProjectImageEntry<?> entry : project.getImageList()) {
            if (getImageCollection(entry) != collection) continue;

            String modality = entry.getMetadata().get(MODALITY);
            if (modality == null || !modality.toLowerCase().startsWith("ppm")) continue;

            String existing = entry.getMetadata().get(PPM_CALIBRATION);
            if (existing == null || existing.isEmpty()) {
                entry.getMetadata().put(PPM_CALIBRATION, calibrationPath);
                updated++;
            }
        }

        if (updated > 0) {
            try {
                project.syncChanges();
                logger.info("Stamped calibration on {} PPM images in collection {}", updated, collection);
            } catch (IOException e) {
                logger.error("Failed to sync project after stamping calibration", e);
            }
        }

        return updated;
    }

    // ========================================================================
    // Private helpers for PPM image classification
    // ========================================================================

    private static boolean isBirefImage(String angle, String imageName) {
        if (angle != null && angle.toLowerCase().contains("biref")) return true;
        return imageName.contains("biref");
    }

    private static boolean isSumImage(String angle, String imageName) {
        if (angle != null && angle.toLowerCase().contains("sum")) return true;
        return imageName.contains("_sum");
    }
}
