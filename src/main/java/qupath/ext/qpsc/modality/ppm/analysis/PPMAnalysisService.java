package qupath.ext.qpsc.modality.ppm.analysis;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.ppm.PPMPreferences;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.RegionRequest;

/**
 * Service for PPM fiber orientation analysis within QuPath.
 *
 * <p>Provides on-demand computation of:
 * <ul>
 *   <li>Fiber angles from sum image pixels + calibration (hue-to-angle)</li>
 *   <li>PPM-positive mask from birefringence image thresholding</li>
 *   <li>Combined masked angle arrays for annotation regions</li>
 *   <li>Circular statistics (mean angle, std) and histograms</li>
 * </ul>
 *
 * <h3>Java/Python Architecture Split</h3>
 * <p>The core hue-to-angle transform is a simple linear equation that runs in Java
 * for interactive speed (real-time slider updates). The same functions are also
 * available in {@code ppm_library.analysis.region_analysis} (Python) for standalone
 * use outside QuPath (notebooks, scripts, other tools).</p>
 *
 * <p>For complex analysis that requires Python-only libraries (e.g., advanced image
 * processing, ML-based segmentation), Appose integration will be added in future
 * phases. The Python library functions in {@code ppm_library} are the canonical
 * implementations for such features.</p>
 *
 * <p>Calibration files are cached after first load to avoid repeated disk I/O.</p>
 *
 * @author Mike Nelson
 * @since 1.0
 * @see PPMCalibration
 * @see qupath.ext.qpsc.utilities.ImageMetadataManager#findPPMAnalysisSet
 */
public class PPMAnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(PPMAnalysisService.class);

    // Cache loaded calibrations by file path
    private static final Map<String, PPMCalibration> calibrationCache = new ConcurrentHashMap<>();

    private PPMAnalysisService() {}

    // ========================================================================
    // Calibration loading
    // ========================================================================

    /**
     * Gets the PPM calibration for an image entry, checking image metadata first
     * then falling back to the active calibration preference.
     *
     * @param entry the image entry
     * @return the calibration, or null if none is available
     */
    public static PPMCalibration getCalibrationForImage(ProjectImageEntry<?> entry) {
        // 1. Check per-image metadata
        String path = ImageMetadataManager.getPPMCalibration(entry);

        // 2. Fall back to active preference
        if (path == null || path.isEmpty()) {
            path = PPMPreferences.getActiveCalibrationPath();
        }

        if (path == null || path.isEmpty()) {
            logger.warn("No PPM calibration available for {}", entry != null ? entry.getImageName() : "null");
            return null;
        }

        return loadCalibration(path);
    }

    /**
     * Loads a calibration file, using the cache if already loaded.
     *
     * @param path path to the .npz calibration file
     * @return the calibration, or null if loading fails
     */
    public static PPMCalibration loadCalibration(String path) {
        return calibrationCache.computeIfAbsent(path, p -> {
            try {
                return PPMCalibration.load(p);
            } catch (IOException e) {
                logger.error("Failed to load PPM calibration from {}: {}", p, e.getMessage());
                return null;
            }
        });
    }

    /**
     * Clears the calibration cache (e.g., after recalibration).
     */
    public static void clearCalibrationCache() {
        calibrationCache.clear();
    }

    // ========================================================================
    // Angle computation from sum image
    // ========================================================================

    /**
     * Result of angle computation for a region. Contains per-pixel angles and
     * a validity mask indicating which pixels had sufficient saturation/value.
     */
    public static class AngleResult {
        /** Fiber angles in degrees (0-180). NaN where invalid. */
        public final float[] angles;
        /** True for pixels with sufficient saturation and value for measurement. */
        public final boolean[] valid;
        /** Width of the region. */
        public final int width;
        /** Height of the region. */
        public final int height;

        public AngleResult(float[] angles, boolean[] valid, int width, int height) {
            this.angles = angles;
            this.valid = valid;
            this.width = width;
            this.height = height;
        }

        /**
         * Gets the angle at pixel (x, y) relative to the region.
         */
        public float getAngle(int x, int y) {
            return angles[y * width + x];
        }

        /**
         * Returns true if the pixel at (x, y) is valid.
         */
        public boolean isValid(int x, int y) {
            return valid[y * width + x];
        }

        /**
         * Counts the number of valid pixels.
         */
        public int countValid() {
            int count = 0;
            for (boolean v : valid) {
                if (v) count++;
            }
            return count;
        }
    }

    /**
     * Computes fiber angles for a region of a sum image.
     *
     * <p>Reads pixels from the image server, converts RGB to HSV, extracts hue,
     * and applies the calibration to convert hue to angle. Pixels with low
     * saturation or value are marked invalid.</p>
     *
     * @param server the sum image server (RGB)
     * @param request the region to read
     * @param calibration the PPM calibration
     * @param saturationThreshold minimum saturation for valid measurements (0-1)
     * @param valueThreshold minimum value/brightness for valid measurements (0-1)
     * @return angle computation result with per-pixel angles and validity
     * @throws IOException if the image region cannot be read
     */
    public static AngleResult computeAngles(
            ImageServer<BufferedImage> server,
            RegionRequest request,
            PPMCalibration calibration,
            float saturationThreshold,
            float valueThreshold)
            throws IOException {

        BufferedImage img = server.readRegion(request);
        int w = img.getWidth();
        int h = img.getHeight();
        int n = w * h;

        float[] hues = new float[n];
        boolean[] valid = new boolean[n];
        float[] hsb = new float[3];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                int rgb = img.getRGB(x, y);

                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                Color.RGBtoHSB(r, g, b, hsb);
                hues[idx] = hsb[0];
                valid[idx] = hsb[1] >= saturationThreshold && hsb[2] >= valueThreshold;
            }
        }

        // Convert hue to angle
        float[] angles = new float[n];
        calibration.hueToAngle(hues, angles);

        // Set invalid pixels to NaN
        for (int i = 0; i < n; i++) {
            if (!valid[i]) {
                angles[i] = Float.NaN;
            }
        }

        return new AngleResult(angles, valid, w, h);
    }

    // ========================================================================
    // PPM-positive mask from birefringence image
    // ========================================================================

    /**
     * Result of birefringence thresholding. Contains a binary mask indicating
     * PPM-positive (collagen) regions.
     */
    public static class BirefMaskResult {
        /** True for pixels above the birefringence threshold (PPM-positive). */
        public final boolean[] ppmPositive;
        /** Width of the region. */
        public final int width;
        /** Height of the region. */
        public final int height;

        public BirefMaskResult(boolean[] ppmPositive, int width, int height) {
            this.ppmPositive = ppmPositive;
            this.width = width;
            this.height = height;
        }

        /**
         * Returns true if pixel at (x, y) is PPM-positive.
         */
        public boolean isPPMPositive(int x, int y) {
            return ppmPositive[y * width + x];
        }

        /**
         * Counts PPM-positive pixels.
         */
        public int countPositive() {
            int count = 0;
            for (boolean v : ppmPositive) {
                if (v) count++;
            }
            return count;
        }
    }

    /**
     * Computes a PPM-positive mask from a birefringence image region.
     *
     * <p>The birefringence image is a single-channel (or grayscale) uint16 image
     * where higher values indicate stronger birefringence. Pixels above the
     * threshold are considered PPM-positive (collagen-containing).</p>
     *
     * @param server the birefringence image server
     * @param request the region to read
     * @param threshold minimum birefringence value to be PPM-positive (0-65535 for uint16)
     * @return the PPM-positive mask
     * @throws IOException if the image region cannot be read
     */
    public static BirefMaskResult computePPMPositiveMask(
            ImageServer<BufferedImage> server, RegionRequest request, double threshold) throws IOException {

        BufferedImage img = server.readRegion(request);
        int w = img.getWidth();
        int h = img.getHeight();
        int n = w * h;

        boolean[] mask = new boolean[n];

        // Handle both 16-bit grayscale and RGB biref images
        var raster = img.getRaster();
        int numBands = raster.getNumBands();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double value;
                if (numBands == 1) {
                    // Single-channel (typical for biref images)
                    value = raster.getSampleDouble(x, y, 0);
                } else {
                    // Multi-channel: use max across channels as biref intensity
                    double maxVal = 0;
                    for (int band = 0; band < numBands; band++) {
                        maxVal = Math.max(maxVal, raster.getSampleDouble(x, y, band));
                    }
                    value = maxVal;
                }
                mask[y * w + x] = value >= threshold;
            }
        }

        return new BirefMaskResult(mask, w, h);
    }

    // ========================================================================
    // Combined: angles masked by PPM-positive regions
    // ========================================================================

    /**
     * Result combining angle computation with PPM-positive masking.
     * Only pixels that are both valid (sufficient color) and PPM-positive
     * (above birefringence threshold) are included.
     */
    public static class MaskedAngleResult {
        /** Fiber angles in degrees (0-180). NaN where not valid+PPM-positive. */
        public final float[] angles;
        /** True where pixel is both color-valid AND PPM-positive. */
        public final boolean[] combinedMask;
        /** Width of the region. */
        public final int width;
        /** Height of the region. */
        public final int height;
        /** Number of pixels in the combined mask. */
        public final int validCount;

        public MaskedAngleResult(float[] angles, boolean[] combinedMask, int width, int height, int validCount) {
            this.angles = angles;
            this.combinedMask = combinedMask;
            this.width = width;
            this.height = height;
            this.validCount = validCount;
        }

        /**
         * Extracts all valid angles as a compact array (no NaNs).
         */
        public float[] getValidAngles() {
            float[] result = new float[validCount];
            int idx = 0;
            for (int i = 0; i < angles.length; i++) {
                if (combinedMask[i]) {
                    result[idx++] = angles[i];
                }
            }
            return result;
        }

        /**
         * Computes a histogram of valid angles.
         *
         * @param bins number of histogram bins (default 18 = 10-degree bins)
         * @return array of counts per bin, covering 0 to 180 degrees
         */
        public int[] computeHistogram(int bins) {
            int[] counts = new int[bins];
            double binWidth = 180.0 / bins;
            for (int i = 0; i < angles.length; i++) {
                if (combinedMask[i]) {
                    int bin = (int) (angles[i] / binWidth);
                    if (bin >= bins) bin = bins - 1;
                    counts[bin]++;
                }
            }
            return counts;
        }

        /**
         * Computes the circular mean angle of valid pixels.
         * Uses circular statistics to handle the 0/180 wrap-around correctly.
         *
         * @return mean angle in degrees (0-180), or NaN if no valid pixels
         */
        public double computeCircularMeanAngle() {
            if (validCount == 0) return Double.NaN;

            // Fiber angles are axial (0-180), so double them to use circular stats
            double sinSum = 0, cosSum = 0;
            for (int i = 0; i < angles.length; i++) {
                if (combinedMask[i]) {
                    double rad2 = Math.toRadians(angles[i] * 2.0);
                    sinSum += Math.sin(rad2);
                    cosSum += Math.cos(rad2);
                }
            }

            double meanRad2 = Math.atan2(sinSum / validCount, cosSum / validCount);
            double meanAngle = Math.toDegrees(meanRad2) / 2.0;
            return ((meanAngle % 180.0) + 180.0) % 180.0;
        }

        /**
         * Computes the circular standard deviation of valid angle values.
         * Uses circular statistics for axial data (0-180 degree range).
         *
         * @return circular std deviation in degrees, or NaN if no valid pixels
         */
        public double computeCircularStdDev() {
            if (validCount == 0) return Double.NaN;

            double sinSum = 0, cosSum = 0;
            for (int i = 0; i < angles.length; i++) {
                if (combinedMask[i]) {
                    double rad2 = Math.toRadians(angles[i] * 2.0);
                    sinSum += Math.sin(rad2);
                    cosSum += Math.cos(rad2);
                }
            }

            double R = Math.sqrt(sinSum * sinSum + cosSum * cosSum) / validCount;
            // Circular variance = 1 - R, circular std = sqrt(-2 * ln(R))
            if (R < 1e-10) return 90.0; // Maximum dispersion
            double circStd = Math.toDegrees(Math.sqrt(-2.0 * Math.log(R))) / 2.0;
            return circStd;
        }
    }

    /**
     * Computes angles from a sum image, masked by a PPM-positive region from the
     * corresponding birefringence image.
     *
     * <p>Since biref and sum images are co-registered (same pixel grid), the same
     * RegionRequest can be used for both image servers.</p>
     *
     * @param sumServer the sum image server (RGB)
     * @param birefServer the birefringence image server (grayscale)
     * @param request the region to analyze (same coordinates for both images)
     * @param calibration the PPM calibration
     * @param birefThreshold birefringence threshold for PPM-positive detection
     * @param saturationThreshold minimum saturation for valid hue measurement
     * @param valueThreshold minimum value for valid hue measurement
     * @return combined result with angles only in PPM-positive regions
     * @throws IOException if either image region cannot be read
     */
    public static MaskedAngleResult computeMaskedAngles(
            ImageServer<BufferedImage> sumServer,
            ImageServer<BufferedImage> birefServer,
            RegionRequest request,
            PPMCalibration calibration,
            double birefThreshold,
            float saturationThreshold,
            float valueThreshold)
            throws IOException {

        AngleResult angleResult = computeAngles(sumServer, request, calibration, saturationThreshold, valueThreshold);
        BirefMaskResult birefMask = computePPMPositiveMask(birefServer, request, birefThreshold);

        int n = angleResult.width * angleResult.height;

        // Verify dimensions match (co-registered images should be same size)
        if (birefMask.width != angleResult.width || birefMask.height != angleResult.height) {
            logger.warn(
                    "Dimension mismatch between sum ({}x{}) and biref ({}x{}) - using intersection",
                    angleResult.width,
                    angleResult.height,
                    birefMask.width,
                    birefMask.height);
            int minW = Math.min(angleResult.width, birefMask.width);
            int minH = Math.min(angleResult.height, birefMask.height);
            n = minW * minH;
        }

        // Combine masks: must be both color-valid AND PPM-positive
        boolean[] combinedMask = new boolean[n];
        int validCount = 0;
        for (int i = 0; i < n; i++) {
            combinedMask[i] = angleResult.valid[i] && birefMask.ppmPositive[i];
            if (combinedMask[i]) validCount++;
        }

        // Set non-combined pixels to NaN
        float[] maskedAngles = new float[n];
        System.arraycopy(angleResult.angles, 0, maskedAngles, 0, n);
        for (int i = 0; i < n; i++) {
            if (!combinedMask[i]) {
                maskedAngles[i] = Float.NaN;
            }
        }

        return new MaskedAngleResult(maskedAngles, combinedMask, angleResult.width, angleResult.height, validCount);
    }
}
