package qupath.ext.qpsc.utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Service for preserving annotations when transitioning from standalone images to projects.
 *
 * <p>When a user drags an image into QuPath without a project and draws annotations,
 * then starts the Existing Image Acquisition workflow, this service:
 * <ol>
 *   <li>Captures annotation data before the project is created</li>
 *   <li>Stores annotations in memory during project creation</li>
 *   <li>Restores annotations to the new project with optional coordinate transformation</li>
 * </ol>
 *
 * <p>This solves the problem where annotations would be lost when:
 * <ul>
 *   <li>No project exists to save annotations to</li>
 *   <li>The image is re-imported into a new project</li>
 *   <li>Image flipping changes coordinate systems</li>
 * </ul>
 *
 * <p>Usage pattern:
 * <pre>
 * // Before project creation (when project is null)
 * AnnotationPreservationService.captureAnnotations(gui);
 *
 * // ... create project, import image ...
 *
 * // After project setup
 * AnnotationPreservationService.restoreAnnotations(gui, flipX, flipY);
 *
 * // Cleanup (or on error)
 * AnnotationPreservationService.clearPreservedAnnotations();
 * </pre>
 *
 * @author Mike Nelson
 * @since 4.2
 */
public class AnnotationPreservationService {
    private static final Logger logger = LoggerFactory.getLogger(AnnotationPreservationService.class);

    // Temporary storage for annotations during workflow
    // Using static storage since this is a per-session workflow operation
    private static List<PathObject> preservedAnnotations = null;
    private static int sourceImageWidth = 0;
    private static int sourceImageHeight = 0;

    /**
     * Captures annotations from the current image before project creation.
     *
     * <p>Call this BEFORE any project/image operations that would close the current image.
     * This method deep-copies all annotations to preserve them across image transitions.
     *
     * @param gui QuPath GUI instance
     * @return true if annotations were captured, false if no annotations exist or error occurred
     */
    public static boolean captureAnnotations(QuPathGUI gui) {
        if (gui == null) {
            logger.warn("Cannot capture annotations: GUI is null");
            return false;
        }

        ImageData<?> imageData = gui.getImageData();
        if (imageData == null) {
            logger.warn("Cannot capture annotations: No image data available");
            return false;
        }

        PathObjectHierarchy hierarchy = imageData.getHierarchy();
        if (hierarchy == null) {
            logger.warn("Cannot capture annotations: No hierarchy available");
            return false;
        }

        Collection<PathObject> annotations = hierarchy.getAnnotationObjects();
        if (annotations.isEmpty()) {
            logger.info("No annotations to preserve");
            return false;
        }

        // Store image dimensions for coordinate transformation
        sourceImageWidth = imageData.getServer().getWidth();
        sourceImageHeight = imageData.getServer().getHeight();

        // Deep copy all annotations to preserve them
        preservedAnnotations = new ArrayList<>();
        int preservedCount = 0;

        for (PathObject annotation : annotations) {
            try {
                // Use PathObjectTools to create a deep copy
                // Parameters: (original, transform, copyChildObjects, copyMeasurements)
                // Using null transform since we're just copying, not transforming yet
                PathObject copy = PathObjectTools.transformObject(annotation, null, true, true);
                if (copy != null) {
                    preservedAnnotations.add(copy);
                    preservedCount++;
                    logger.debug("Preserved annotation: {} (class: {})",
                            annotation.getDisplayedName(),
                            annotation.getPathClass() != null ? annotation.getPathClass().getName() : "unclassified");
                }
            } catch (Exception e) {
                logger.warn("Failed to preserve annotation {}: {}",
                        annotation.getDisplayedName(), e.getMessage());
            }
        }

        logger.info("Captured {} annotations from standalone image ({}x{} pixels)",
                preservedCount, sourceImageWidth, sourceImageHeight);

        return preservedCount > 0;
    }

    /**
     * Restores previously captured annotations to the current image hierarchy.
     *
     * <p>Handles coordinate transformation if the image was flipped during import.
     * The flip transformation uses the same logic as QuPath's coordinate system.
     *
     * @param gui QuPath GUI instance
     * @param flipX Whether X coordinates should be flipped
     * @param flipY Whether Y coordinates should be flipped
     * @return true if annotations were restored successfully
     */
    public static boolean restoreAnnotations(QuPathGUI gui, boolean flipX, boolean flipY) {
        if (!hasPreservedAnnotations()) {
            logger.debug("No preserved annotations to restore");
            return false;
        }

        if (gui == null) {
            logger.warn("Cannot restore annotations: GUI is null");
            return false;
        }

        ImageData<?> imageData = gui.getImageData();
        if (imageData == null) {
            logger.warn("Cannot restore annotations: No image data available");
            return false;
        }

        PathObjectHierarchy hierarchy = imageData.getHierarchy();
        if (hierarchy == null) {
            logger.warn("Cannot restore annotations: No hierarchy available");
            return false;
        }

        // Get current image dimensions for transformation
        int targetWidth = imageData.getServer().getWidth();
        int targetHeight = imageData.getServer().getHeight();

        logger.info("Restoring {} annotations to image ({}x{} pixels, flipX={}, flipY={})",
                preservedAnnotations.size(), targetWidth, targetHeight, flipX, flipY);

        // Create flip transform if needed
        AffineTransform flipTransform = null;
        if (flipX || flipY) {
            flipTransform = createFlipTransform(flipX, flipY, targetWidth, targetHeight);
            logger.debug("Created flip transform: {}", flipTransform);
        }

        // Restore annotations
        List<PathObject> restoredAnnotations = new ArrayList<>();
        int restoredCount = 0;

        for (PathObject preserved : preservedAnnotations) {
            try {
                PathObject restored;
                if (flipTransform != null) {
                    // Transform annotation with flip
                    restored = PathObjectTools.transformObject(preserved, flipTransform, true, true);
                } else {
                    // Just copy without transformation
                    restored = PathObjectTools.transformObject(preserved, null, true, true);
                }

                if (restored != null) {
                    restoredAnnotations.add(restored);
                    restoredCount++;
                    logger.debug("Restored annotation: {} at ({}, {})",
                            restored.getDisplayedName(),
                            restored.getROI() != null ? restored.getROI().getCentroidX() : "N/A",
                            restored.getROI() != null ? restored.getROI().getCentroidY() : "N/A");
                }
            } catch (Exception e) {
                logger.warn("Failed to restore annotation {}: {}",
                        preserved.getDisplayedName(), e.getMessage());
            }
        }

        // Add all restored annotations to hierarchy at once (more efficient)
        if (!restoredAnnotations.isEmpty()) {
            hierarchy.addObjects(restoredAnnotations);
            // Fire hierarchy update to ensure UI reflects changes
            hierarchy.fireHierarchyChangedEvent(hierarchy.getRootObject());
        }

        logger.info("Successfully restored {} annotations", restoredCount);
        return restoredCount > 0;
    }

    /**
     * Clears any preserved annotations (cleanup after workflow completes or on error).
     *
     * <p>This should be called:
     * <ul>
     *   <li>After annotations are successfully restored</li>
     *   <li>When the workflow is cancelled</li>
     *   <li>When an error occurs during the workflow</li>
     * </ul>
     */
    public static void clearPreservedAnnotations() {
        if (preservedAnnotations != null) {
            int count = preservedAnnotations.size();
            preservedAnnotations.clear();
            preservedAnnotations = null;
            sourceImageWidth = 0;
            sourceImageHeight = 0;
            logger.debug("Cleared {} preserved annotations", count);
        }
    }

    /**
     * Checks if there are preserved annotations waiting to be restored.
     *
     * @return true if there are preserved annotations
     */
    public static boolean hasPreservedAnnotations() {
        return preservedAnnotations != null && !preservedAnnotations.isEmpty();
    }

    /**
     * Gets the count of preserved annotations.
     *
     * @return Number of preserved annotations, or 0 if none
     */
    public static int getPreservedAnnotationCount() {
        return preservedAnnotations != null ? preservedAnnotations.size() : 0;
    }

    /**
     * Gets the source image width from when annotations were captured.
     *
     * @return Source image width in pixels, or 0 if no annotations captured
     */
    public static int getSourceImageWidth() {
        return sourceImageWidth;
    }

    /**
     * Gets the source image height from when annotations were captured.
     *
     * @return Source image height in pixels, or 0 if no annotations captured
     */
    public static int getSourceImageHeight() {
        return sourceImageHeight;
    }

    /**
     * Creates a flip transform following QuPath coordinate system conventions.
     *
     * <p>Origin (0,0) is at top-left corner of the image.
     * This method creates the same transform used by TransformationFunctions.
     *
     * @param flipX Whether to flip horizontally
     * @param flipY Whether to flip vertically
     * @param imageWidth Image width in pixels
     * @param imageHeight Image height in pixels
     * @return AffineTransform for the specified flip operation
     */
    private static AffineTransform createFlipTransform(boolean flipX, boolean flipY,
                                                        double imageWidth, double imageHeight) {
        AffineTransform transform = new AffineTransform();

        if (flipX && flipY) {
            // Combined X and Y flip: scale by -1,-1 then translate by width,height
            transform.scale(-1.0, -1.0);
            transform.translate(-imageWidth, -imageHeight);
        } else if (flipX) {
            // Horizontal flip: scale by -1,1 then translate by width,0
            transform.scale(-1.0, 1.0);
            transform.translate(-imageWidth, 0.0);
        } else if (flipY) {
            // Vertical flip: scale by 1,-1 then translate by 0,height
            transform.scale(1.0, -1.0);
            transform.translate(0.0, -imageHeight);
        }
        // If no flips, return identity transform

        return transform;
    }
}
