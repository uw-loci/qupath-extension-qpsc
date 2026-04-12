package qupath.ext.qpsc.utilities;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;

/**
 * Utility class for retrieving and working with macro images from QuPath projects.
 * Provides centralized access to macro image functionality used across different workflows.
 */
public class MacroImageUtility {
    private static final Logger logger = LoggerFactory.getLogger(MacroImageUtility.class);

    // Tolerance for slide boundary detection (in pixels)
    private static final int BOUNDARY_TOLERANCE = 2; // ~80um at 40um/pixel

    /**
     * Container for cropped macro image information.
     * Holds the cropped image and adjusted coordinates.
     */
    public static class CroppedMacroResult {
        private final BufferedImage croppedImage;
        private final int originalWidth;
        private final int originalHeight;
        private final int cropOffsetX;
        private final int cropOffsetY;

        public CroppedMacroResult(
                BufferedImage croppedImage, int originalWidth, int originalHeight, int cropOffsetX, int cropOffsetY) {
            this.croppedImage = croppedImage;
            this.originalWidth = originalWidth;
            this.originalHeight = originalHeight;
            this.cropOffsetX = cropOffsetX;
            this.cropOffsetY = cropOffsetY;
        }

        public BufferedImage getCroppedImage() {
            return croppedImage;
        }

        public int getOriginalWidth() {
            return originalWidth;
        }

        public int getOriginalHeight() {
            return originalHeight;
        }

        public int getCropOffsetX() {
            return cropOffsetX;
        }

        public int getCropOffsetY() {
            return cropOffsetY;
        }

        /**
         * Adjusts an ROI from original macro coordinates to cropped coordinates.
         */
        public ROI adjustROI(ROI originalROI) {
            if (originalROI == null) return null;

            double adjustedX = originalROI.getBoundsX() - cropOffsetX;
            double adjustedY = originalROI.getBoundsY() - cropOffsetY;

            // Ensure the adjusted ROI is within the cropped bounds
            if (adjustedX < 0
                    || adjustedY < 0
                    || adjustedX + originalROI.getBoundsWidth() > croppedImage.getWidth()
                    || adjustedY + originalROI.getBoundsHeight() > croppedImage.getHeight()) {
                logger.warn("Adjusted ROI extends beyond cropped image bounds");
            }

            return ROIs.createRectangleROI(
                    adjustedX,
                    adjustedY,
                    originalROI.getBoundsWidth(),
                    originalROI.getBoundsHeight(),
                    originalROI.getImagePlane());
        }

        /**
         * Converts coordinates from cropped image back to original macro image.
         */
        public double[] toOriginalCoordinates(double croppedX, double croppedY) {
            return new double[] {croppedX + cropOffsetX, croppedY + cropOffsetY};
        }
    }

    /**
     * Crops the macro image to just the slide area, removing surrounding holder/background.
     * Uses the selected scanner's configuration to determine crop bounds.
     *
     * @param macroImage The original macro image
     * @return CroppedMacroResult containing the cropped image and offset information
     * @throws IllegalStateException if no scanner is selected in preferences
     */
    public static CroppedMacroResult cropToSlideArea(BufferedImage macroImage) {
        String scannerName = PersistentPreferences.getSelectedScannerProperty();
        if (scannerName == null || scannerName.isEmpty()) {
            throw new IllegalStateException("No scanner selected in preferences. Cannot determine crop bounds. "
                    + "Please select a scanner in Edit > Preferences.");
        }
        return cropToSlideArea(macroImage, scannerName);
    }

    /**
     * Crops the macro image to the specified slide area.
     *
     * @param macroImage The original macro image
     * @param slideXMin Minimum X coordinate of the slide area
     * @param slideXMax Maximum X coordinate of the slide area
     * @param slideYMin Minimum Y coordinate of the slide area
     * @param slideYMax Maximum Y coordinate of the slide area
     * @return CroppedMacroResult containing the cropped image and offset information
     */
    public static CroppedMacroResult cropToSlideArea(
            BufferedImage macroImage, int slideXMin, int slideXMax, int slideYMin, int slideYMax) {
        if (macroImage == null) {
            throw new IllegalArgumentException("Macro image cannot be null");
        }

        // Store original dimensions
        int originalWidth = macroImage.getWidth();
        int originalHeight = macroImage.getHeight();

        // Validate boundaries
        slideXMin = Math.max(0, slideXMin);
        slideXMax = Math.min(originalWidth, slideXMax);
        slideYMin = Math.max(0, slideYMin);
        slideYMax = Math.min(originalHeight, slideYMax);

        if (slideXMin >= slideXMax || slideYMin >= slideYMax) {
            logger.error("Invalid slide boundaries: X[{}-{}], Y[{}-{}]", slideXMin, slideXMax, slideYMin, slideYMax);
            throw new IllegalArgumentException("Invalid slide boundaries");
        }

        int cropWidth = slideXMax - slideXMin;
        int cropHeight = slideYMax - slideYMin;

        // Guard against double-cropping: if the image already matches the expected
        // crop dimensions (within 2px tolerance), return it unchanged. This happens
        // when a previously-cropped alignment image is passed through the same pipeline.
        if (Math.abs(originalWidth - cropWidth) <= 2 && Math.abs(originalHeight - cropHeight) <= 2) {
            logger.info(
                    "Image ({}x{}) already matches crop dimensions ({}x{}) - skipping crop",
                    originalWidth,
                    originalHeight,
                    cropWidth,
                    cropHeight);
            return new CroppedMacroResult(macroImage, originalWidth, originalHeight, 0, 0);
        }

        logger.info(
                "Cropping macro image from {}x{} to {}x{} (offset: {}, {})",
                originalWidth,
                originalHeight,
                cropWidth,
                cropHeight,
                slideXMin,
                slideYMin);

        // Perform the crop
        BufferedImage cropped = macroImage.getSubimage(slideXMin, slideYMin, cropWidth, cropHeight);

        // Create a copy to ensure it's independent of the original
        BufferedImage croppedCopy = new BufferedImage(cropWidth, cropHeight, macroImage.getType());
        Graphics2D g2d = croppedCopy.createGraphics();
        g2d.drawImage(cropped, 0, 0, null);
        g2d.dispose();

        return new CroppedMacroResult(croppedCopy, originalWidth, originalHeight, slideXMin, slideYMin);
    }
    /**
     * Crops the macro image based on scanner configuration.
     *
     * @param macroImage The original macro image
     * @param scannerName The scanner that produced this image (e.g., "Ocus40")
     * @return CroppedMacroResult containing the cropped image and offset information
     */
    public static CroppedMacroResult cropToSlideArea(BufferedImage macroImage, String scannerName) {
        if (macroImage == null) {
            throw new IllegalArgumentException("Macro image cannot be null");
        }

        // Load scanner-specific config
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        File configDir = new File(configPath).getParentFile();
        File scannerConfigFile = new File(configDir, "config_" + scannerName + ".yml");

        if (!scannerConfigFile.exists()) {
            String error = String.format(
                    "Scanner config file not found: %s. " + "Cannot determine crop bounds for scanner '%s'.",
                    scannerConfigFile.getAbsolutePath(), scannerName);
            logger.error(error);
            throw new IllegalStateException(error);
        }

        // Load the scanner config
        Map<String, Object> scannerConfig = MinorFunctions.loadYamlFile(scannerConfigFile.getAbsolutePath());

        // Check if cropping is needed
        Boolean requiresCropping = MinorFunctions.getYamlBoolean(scannerConfig, "macro", "requires_cropping");
        if (requiresCropping == null || !requiresCropping) {
            logger.info("Scanner '{}' does not require cropping, returning original image", scannerName);
            return new CroppedMacroResult(macroImage, macroImage.getWidth(), macroImage.getHeight(), 0, 0);
        }

        // Get slide bounds (YAML uses x_min_px / x_max_px / y_min_px / y_max_px keys)
        Integer xMin = MinorFunctions.getYamlInteger(scannerConfig, "macro", "slide_bounds", "x_min_px");
        Integer xMax = MinorFunctions.getYamlInteger(scannerConfig, "macro", "slide_bounds", "x_max_px");
        Integer yMin = MinorFunctions.getYamlInteger(scannerConfig, "macro", "slide_bounds", "y_min_px");
        Integer yMax = MinorFunctions.getYamlInteger(scannerConfig, "macro", "slide_bounds", "y_max_px");

        if (xMin == null || xMax == null || yMin == null || yMax == null) {
            String error = String.format(
                    "Scanner '%s' requires cropping but slide bounds are not properly configured. "
                            + "Please add x_min_px, x_max_px, y_min_px, y_max_px under macro.slide_bounds in config_%s.yml",
                    scannerName, scannerName);
            logger.error(error);
            throw new IllegalStateException(error);
        }

        logger.info(
                "Cropping macro image for scanner '{}' using bounds: X[{}-{}], Y[{}-{}]",
                scannerName,
                xMin,
                xMax,
                yMin,
                yMax);
        return cropToSlideArea(macroImage, xMin, xMax, yMin, yMax);
    }

    /**
     * Get macro pixel size for a specific scanner.
     * @param scannerName The scanner name
     * @return The macro pixel size in microns
     * @throws IllegalStateException if scanner is not configured or pixel size is missing
     */
    public static double getMacroPixelSize(String scannerName) {
        // Load scanner-specific config
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        File configDir = new File(configPath).getParentFile();
        File scannerConfigFile = new File(configDir, "config_" + scannerName + ".yml");

        if (!scannerConfigFile.exists()) {
            String error = String.format(
                    "Scanner configuration file not found: %s\n"
                            + "Please ensure the scanner '%s' has a configuration file.",
                    scannerConfigFile.getAbsolutePath(), scannerName);
            logger.error(error);
            throw new IllegalStateException(error);
        }

        // Load the scanner config
        Map<String, Object> scannerConfig = MinorFunctions.loadYamlFile(scannerConfigFile.getAbsolutePath());
        Double pixelSize = MinorFunctions.getYamlDouble(scannerConfig, "macro", "pixel_size_um");

        if (pixelSize == null || pixelSize <= 0) {
            String error = String.format(
                    "Scanner '%s' has no valid macro pixel size configured.\n"
                            + "This is required for accurate alignment.\n"
                            + "Please add 'macro: pixel_size_um: <value>' to %s",
                    scannerName, scannerConfigFile.getName());
            logger.error(error);
            throw new IllegalStateException(error);
        }

        logger.info("Using macro pixel size {} um for scanner '{}'", pixelSize, scannerName);
        return pixelSize;
    }
    /**
     * Get macro pixel size for the scanner selected in preferences.
     * @return The macro pixel size in microns
     * @throws IllegalStateException if scanner is not configured or pixel size is missing
     */
    public static double getMacroPixelSize() {
        String scannerName = PersistentPreferences.getSelectedScannerProperty();
        if (scannerName == null || scannerName.isEmpty()) {
            String error = "No scanner selected in preferences. Please select a scanner in Edit > Preferences.";
            logger.error(error);
            throw new IllegalStateException(error);
        }
        return getMacroPixelSize(scannerName);
    }

    /**
     * Applies X and/or Y flips to a macro image.
     *
     * @param image The image to flip
     * @param flipX Whether to flip horizontally
     * @param flipY Whether to flip vertically
     * @return The flipped image
     */
    public static BufferedImage flipMacroImage(BufferedImage image, boolean flipX, boolean flipY) {
        if (!flipX && !flipY) {
            return image; // No flipping needed
        }

        int width = image.getWidth();
        int height = image.getHeight();

        BufferedImage flipped = new BufferedImage(width, height, image.getType());
        Graphics2D g2d = flipped.createGraphics();

        // Apply flips using affine transform
        if (flipX && flipY) {
            g2d.translate(width, height);
            g2d.scale(-1, -1);
        } else if (flipX) {
            g2d.translate(width, 0);
            g2d.scale(-1, 1);
        } else { // flipY only
            g2d.translate(0, height);
            g2d.scale(1, -1);
        }

        g2d.drawImage(image, 0, 0, null);
        g2d.dispose();

        logger.info("Applied flips to macro image: flipX={}, flipY={}", flipX, flipY);

        return flipped;
    }

    /**
     * Retrieves the macro image from the current QuPath image data.
     * Searches through associated images for entries containing "macro" and handles
     * various naming formats used by different scanner vendors.
     *
     * <p>When the current image is a flipped derivative (TransformedServer), the macro
     * from the current server may already have the flip applied, which breaks downstream
     * crop+flip processing. In this case, the method traces through project metadata to
     * retrieve the macro from the original (unflipped) source entry instead.
     *
     * @param gui The QuPath GUI instance
     * @return The macro image as BufferedImage, or null if not available or retrieval fails
     */
    @SuppressWarnings("unchecked")
    public static BufferedImage retrieveMacroImage(QuPathGUI gui) {
        // If current entry is a flipped derivative, get the macro from the source entry
        // to avoid a pre-flipped macro from TransformedServer
        if (gui.getProject() != null && gui.getImageData() != null) {
            Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
            ProjectImageEntry<BufferedImage> entry = project.getEntry(gui.getImageData());
            if (entry != null) {
                String originalId = ImageMetadataManager.getOriginalImageId(entry);
                if (originalId != null) {
                    logger.info("Current image is a derived entry - retrieving macro from source");
                    BufferedImage macro = retrieveMacroImageFromProject(gui, project);
                    if (macro != null) {
                        return macro;
                    }
                    // Fall through to direct retrieval if project chain fails
                }
            }
        }

        try {
            var imageData = gui.getImageData();
            if (imageData == null) return null;

            var associatedList = imageData.getServer().getAssociatedImageList();
            if (associatedList == null) return null;

            // Find macro image key
            String macroKey = null;
            for (String name : associatedList) {
                if (name.toLowerCase().contains("macro")) {
                    macroKey = name;
                    break;
                }
            }

            if (macroKey == null) return null;

            // Try to retrieve it
            Object image = imageData.getServer().getAssociatedImage(macroKey);
            if (image instanceof BufferedImage) {
                return (BufferedImage) image;
            }

            // Try variations if needed
            if (macroKey.startsWith("Series ")) {
                String seriesOnly = macroKey.split("\\s*\\(")[0].trim();
                image = imageData.getServer().getAssociatedImage(seriesOnly);
                if (image instanceof BufferedImage) {
                    return (BufferedImage) image;
                }
            }

            // Try simple "macro"
            image = imageData.getServer().getAssociatedImage("macro");
            if (image instanceof BufferedImage) {
                return (BufferedImage) image;
            }

        } catch (Exception e) {
            logger.error("Error retrieving macro image", e);
        }

        return null;
    }
    /**
     * Retrieves macro image with fallback to saved version.
     * First attempts to get the macro image from the current slide's associated images.
     * If not found, attempts to load a previously saved macro image using the IMAGE name.
     *
     * @param gui QuPath GUI instance
     * @param sampleName Sample name (parameter ignored - uses image name instead)
     * @return The macro image (either from slide or saved version), or null if neither is available
     */
    public static BufferedImage retrieveMacroImageWithFallback(QuPathGUI gui, String sampleName) {
        // First try to get from the current image
        BufferedImage macroImage = retrieveMacroImage(gui);

        if (macroImage == null && gui.getProject() != null && gui.getImageData() != null) {
            // Get the actual image file name (not metadata name which may be project name)
            String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());

            // Try to load saved macro image using IMAGE name (not sample name)
            logger.info("No macro image in slide, checking for saved version...");
            macroImage =
                    AffineTransformManager.loadSavedMacroImage((Project<BufferedImage>) gui.getProject(), imageName);

            if (macroImage != null) {
                logger.info("Successfully loaded saved macro image for image: {}", imageName);
            } else {
                logger.debug("No saved macro image found for image: {}", imageName);
                // Final fallback: trace project metadata chain (base_image, original_image_id)
                // This handles sub-acquisitions (e.g. biref images) that have a base_image
                // pointing to the parent entry which contains the macro.
                macroImage = retrieveMacroImageFromProject(gui, (Project<BufferedImage>) gui.getProject());
                if (macroImage != null) {
                    logger.info("Found macro image via project metadata chain for image: {}", imageName);
                }
            }
        }

        return macroImage;
    }

    /**
     * Retrieves macro image with fallback, using the current project entry to determine sample name.
     * Convenience method that automatically extracts the sample name from the current project entry.
     *
     * @param gui QuPath GUI instance
     * @return The macro image (either from slide or saved version), or null if neither is available
     */
    public static BufferedImage retrieveMacroImageWithFallback(QuPathGUI gui) {
        // Try to get sample name from current project entry
        String sampleName = null;
        if (gui.getProject() != null && QP.getProjectEntry() != null) {
            // Extract sample name from entry name (remove file extension if present)
            String entryName = QP.getProjectEntry().getImageName();
            if (entryName != null) {
                // Remove common image extensions
                sampleName = entryName.replaceFirst("\\.(tiff?|svs|ndpi|scn|vsi|mrxs)$", "");
                logger.debug("Extracted sample name from project entry: {}", sampleName);
            }
        }

        return retrieveMacroImageWithFallback(gui, sampleName);
    }
    /**
     * Retrieves a macro image by tracing through project metadata to find the source entry.
     *
     * <p>When the current image is a flipped duplicate or derived image (e.g. TransformedServer),
     * its associated images may not be exposed. This method traces the metadata chain
     * (base_image, original_image_id) to find the original entry that contains the macro.
     *
     * @param gui     QuPath GUI instance
     * @param project The current project
     * @return The macro image from the source entry, or null if not found
     */
    @SuppressWarnings("unchecked")
    public static BufferedImage retrieveMacroImageFromProject(QuPathGUI gui, Project<BufferedImage> project) {

        if (gui.getImageData() == null || project == null) {
            return null;
        }

        ProjectImageEntry<BufferedImage> currentEntry = project.getEntry(gui.getImageData());
        if (currentEntry == null) {
            return null;
        }

        // Strategy 1: Use base_image metadata (covers flipped entries AND sub-acquisitions)
        String baseImageName = ImageMetadataManager.getBaseImage(currentEntry);
        if (baseImageName != null && !baseImageName.isEmpty()) {
            logger.info(
                    "Tracing base_image='{}' to find macro for entry '{}'", baseImageName, currentEntry.getImageName());
            BufferedImage macro = findMacroByImageName(project, baseImageName);
            if (macro != null) {
                return macro;
            }
        }

        // Strategy 2: Use original_image_id (direct parent for flipped entries)
        String originalId = ImageMetadataManager.getOriginalImageId(currentEntry);
        if (originalId != null) {
            logger.info(
                    "Tracing original_image_id='{}' to find macro for entry '{}'",
                    originalId,
                    currentEntry.getImageName());
            for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
                if (originalId.equals(entry.getID())) {
                    BufferedImage macro = readMacroFromEntry(entry);
                    if (macro != null) {
                        return macro;
                    }
                    // The original might itself be derived; check its base_image
                    String origBase = ImageMetadataManager.getBaseImage(entry);
                    if (origBase != null && !origBase.isEmpty()) {
                        macro = findMacroByImageName(project, origBase);
                        if (macro != null) {
                            return macro;
                        }
                    }
                    break;
                }
            }
        }

        logger.debug("No source entry with macro found for image '{}'", currentEntry.getImageName());
        return null;
    }

    /**
     * Finds an entry by image name (with or without extension) and reads its macro.
     */
    private static BufferedImage findMacroByImageName(Project<BufferedImage> project, String targetName) {
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            String entryName = entry.getImageName();
            String strippedName = GeneralTools.stripExtension(entryName);
            if (targetName.equals(entryName) || targetName.equals(strippedName)) {
                BufferedImage macro = readMacroFromEntry(entry);
                if (macro != null) {
                    logger.info("Found macro ({}x{}) from entry '{}'", macro.getWidth(), macro.getHeight(), entryName);
                    return macro;
                }
            }
        }
        return null;
    }

    /**
     * Reads the macro associated image directly from a project entry.
     */
    private static BufferedImage readMacroFromEntry(ProjectImageEntry<BufferedImage> entry) {
        try {
            ImageData<BufferedImage> data = entry.readImageData();
            var server = data.getServer();
            var associatedList = server.getAssociatedImageList();
            if (associatedList == null) {
                return null;
            }
            for (String name : associatedList) {
                if (name.toLowerCase().contains("macro")) {
                    Object image = server.getAssociatedImage(name);
                    if (image instanceof BufferedImage macro) {
                        return macro;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to read macro from entry '{}': {}", entry.getImageName(), e.getMessage());
        }
        return null;
    }

    /**
     * Checks if a macro image is available, either from the current image's
     * associated images or by tracing through project metadata to a source entry.
     *
     * @param gui The QuPath GUI instance
     * @return true if a macro image can be obtained, false otherwise
     */
    @SuppressWarnings("unchecked")
    public static boolean isMacroImageAvailable(QuPathGUI gui) {
        if (gui.getImageData() == null) {
            return false;
        }

        // Check current image's associated images directly
        try {
            var associatedImages = gui.getImageData().getServer().getAssociatedImageList();
            if (associatedImages != null
                    && associatedImages.stream()
                            .anyMatch(name -> name.toLowerCase().contains("macro"))) {
                return true;
            }
        } catch (Exception e) {
            logger.debug("No associated images on current server: {}", e.getMessage());
        }

        // Fall back to project metadata tracing (for flipped/derived entries)
        if (gui.getProject() != null) {
            Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
            ProjectImageEntry<BufferedImage> currentEntry = project.getEntry(gui.getImageData());
            if (currentEntry != null) {
                String baseImage = ImageMetadataManager.getBaseImage(currentEntry);
                String originalId = ImageMetadataManager.getOriginalImageId(currentEntry);
                if (baseImage != null || originalId != null) {
                    return true;
                }
            }
        }

        return false;
    }
}
