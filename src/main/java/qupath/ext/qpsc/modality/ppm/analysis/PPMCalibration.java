package qupath.ext.qpsc.modality.ppm.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java-side reader for PPM sunburst calibration files (.npz).
 *
 * <p>The calibration file is produced by ppm_library's RadialCalibrator and contains
 * a linear regression mapping hue values to fiber orientation angles. The transform is:
 * <pre>
 *   hue_shifted = (hue_raw - hue_offset) % 1.0
 *   angle = inv_slope * hue_shifted + inv_intercept
 *   angle = angle % 180
 * </pre>
 *
 * <p>This class reads the .npz file (a zip of numpy .npy arrays) and extracts the
 * three coefficients needed for the hue-to-angle conversion, making it possible to
 * compute fiber angles entirely in Java without Python.</p>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class PPMCalibration {

    private static final Logger logger = LoggerFactory.getLogger(PPMCalibration.class);

    // Numpy .npy magic bytes
    private static final byte[] NPY_MAGIC = {(byte) 0x93, 'N', 'U', 'M', 'P', 'Y'};

    private final double invSlope;
    private final double invIntercept;
    private final double hueOffset;
    private final double rSquared;
    private final Path sourcePath;

    private PPMCalibration(double invSlope, double invIntercept, double hueOffset, double rSquared, Path sourcePath) {
        this.invSlope = invSlope;
        this.invIntercept = invIntercept;
        this.hueOffset = hueOffset;
        this.rSquared = rSquared;
        this.sourcePath = sourcePath;
    }

    /**
     * Loads a PPM calibration from a .npz file.
     *
     * @param path path to the calibration .npz file
     * @return the loaded calibration
     * @throws IOException if the file cannot be read or is invalid
     */
    public static PPMCalibration load(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("Calibration file not found: " + path);
        }

        Map<String, double[]> arrays = readNpzScalars(path);

        double invSlope = getScalar(arrays, "inv_slope");
        double invIntercept = getScalar(arrays, "inv_intercept");
        double hueOffset = getScalar(arrays, "hue_offset");
        double rSquared = getScalar(arrays, "r_squared");

        PPMCalibration cal = new PPMCalibration(invSlope, invIntercept, hueOffset, rSquared, path);

        logger.info(
                "Loaded PPM calibration from {}: angle = {:.4f} * hue_shifted + {:.4f}, hue_offset={:.4f}, R2={:.4f}"
                        .replace("{:.4f}", "%.4f")
                        .formatted(path.getFileName(), invSlope, invIntercept, hueOffset, rSquared));

        return cal;
    }

    /**
     * Loads a PPM calibration from a file path string.
     *
     * @param path path to the calibration .npz file
     * @return the loaded calibration
     * @throws IOException if the file cannot be read or is invalid
     */
    public static PPMCalibration load(String path) throws IOException {
        return load(Path.of(path));
    }

    /**
     * Converts a raw hue value (0-1) to a fiber orientation angle (0-180 degrees).
     *
     * @param hue raw hue value from HSV conversion (0.0 to 1.0)
     * @return fiber angle in degrees (0.0 to 180.0)
     */
    public double hueToAngle(double hue) {
        double hueShifted = ((hue - hueOffset) % 1.0 + 1.0) % 1.0;
        double angle = invSlope * hueShifted + invIntercept;
        return ((angle % 180.0) + 180.0) % 180.0;
    }

    /**
     * Converts an array of raw hue values to fiber orientation angles.
     * Operates in-place on the output array for efficiency on large regions.
     *
     * @param hues input hue values (0-1)
     * @param angles output angle array (must be same length as hues)
     */
    public void hueToAngle(float[] hues, float[] angles) {
        for (int i = 0; i < hues.length; i++) {
            double hueShifted = ((hues[i] - hueOffset) % 1.0 + 1.0) % 1.0;
            double angle = invSlope * hueShifted + invIntercept;
            angles[i] = (float) (((angle % 180.0) + 180.0) % 180.0);
        }
    }

    /**
     * Converts a fiber angle to the expected raw hue value.
     * Useful for computing hue range from angle range.
     *
     * @param angle fiber angle in degrees
     * @return expected hue value (0.0 to 1.0)
     */
    public double angleToHue(double angle) {
        // slope and intercept are the forward transform: hue_shifted = slope * angle + intercept
        // We need to invert inv_slope/inv_intercept: hue_shifted = (angle - inv_intercept) / inv_slope
        double hueShifted = (angle - invIntercept) / invSlope;
        return ((hueShifted + hueOffset) % 1.0 + 1.0) % 1.0;
    }

    public double getInvSlope() {
        return invSlope;
    }

    public double getInvIntercept() {
        return invIntercept;
    }

    public double getHueOffset() {
        return hueOffset;
    }

    public double getRSquared() {
        return rSquared;
    }

    public Path getSourcePath() {
        return sourcePath;
    }

    @Override
    public String toString() {
        return String.format(
                "PPMCalibration[angle = %.4f * hue_shifted + %.4f, hue_offset=%.4f, R2=%.4f, source=%s]",
                invSlope, invIntercept, hueOffset, rSquared, sourcePath != null ? sourcePath.getFileName() : "none");
    }

    // ========================================================================
    // NPZ/NPY reading utilities
    // ========================================================================

    /**
     * Reads scalar float64 values from a .npz file.
     * Only extracts entries that are scalar (0-d) or 1-element arrays.
     */
    private static Map<String, double[]> readNpzScalars(Path npzPath) throws IOException {
        Map<String, double[]> result = new HashMap<>();

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(npzPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (!name.endsWith(".npy")) {
                    continue;
                }

                // Strip the .npy suffix to get the array name
                String arrayName = name.substring(0, name.length() - 4);

                try {
                    double[] values = readNpyArray(zis);
                    if (values != null) {
                        result.put(arrayName, values);
                    }
                } catch (Exception e) {
                    logger.debug("Skipping NPZ entry {} (not a readable float64 array): {}", name, e.getMessage());
                }
            }
        }

        return result;
    }

    /**
     * Reads a .npy array from an input stream, returning the values as double[].
     * Supports scalar and 1-D float64 arrays.
     */
    private static double[] readNpyArray(InputStream is) throws IOException {
        // Read and validate magic bytes
        byte[] magic = is.readNBytes(6);
        if (magic.length < 6) {
            throw new IOException("Truncated NPY header");
        }
        for (int i = 0; i < NPY_MAGIC.length; i++) {
            if (magic[i] != NPY_MAGIC[i]) {
                throw new IOException("Invalid NPY magic bytes");
            }
        }

        // Read version
        int majorVersion = is.read();
        int minorVersion = is.read();
        if (majorVersion < 0 || minorVersion < 0) {
            throw new IOException("Truncated NPY version");
        }

        // Read header length
        int headerLen;
        if (majorVersion == 1) {
            byte[] lenBytes = is.readNBytes(2);
            headerLen = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
        } else {
            byte[] lenBytes = is.readNBytes(4);
            headerLen = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
        }

        // Read header (Python dict as ASCII)
        byte[] headerBytes = is.readNBytes(headerLen);
        String header = new String(headerBytes, StandardCharsets.UTF_8).trim();

        // Parse dtype
        boolean isFloat64 = header.contains("'<f8'") || header.contains("'float64'");
        boolean isFloat32 = header.contains("'<f4'") || header.contains("'float32'");
        if (!isFloat64 && !isFloat32) {
            // Try to read as int64 for center coordinates etc.
            if (header.contains("'<i8'") || header.contains("'int64'")) {
                return readIntArray(is, header, 8);
            }
            return null; // Skip non-float arrays
        }

        int bytesPerElement = isFloat64 ? 8 : 4;

        // Parse shape from header
        int numElements = parseShapeToElementCount(header);
        if (numElements < 0) {
            return null;
        }

        // Read data
        byte[] data = is.readNBytes(numElements * bytesPerElement);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        double[] values = new double[numElements];
        for (int i = 0; i < numElements; i++) {
            values[i] = isFloat64 ? buf.getDouble() : buf.getFloat();
        }

        return values;
    }

    /**
     * Reads an integer array and converts to double[].
     */
    private static double[] readIntArray(InputStream is, String header, int bytesPerElement) throws IOException {
        int numElements = parseShapeToElementCount(header);
        if (numElements < 0) return null;

        byte[] data = is.readNBytes(numElements * bytesPerElement);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        double[] values = new double[numElements];
        for (int i = 0; i < numElements; i++) {
            values[i] = buf.getLong();
        }
        return values;
    }

    /**
     * Parses the 'shape' tuple from a numpy header string and returns total element count.
     * Returns -1 if shape cannot be parsed.
     */
    private static int parseShapeToElementCount(String header) {
        // Find 'shape': (...) in the header
        int shapeIdx = header.indexOf("'shape':");
        if (shapeIdx < 0) {
            shapeIdx = header.indexOf("\"shape\":");
        }
        if (shapeIdx < 0) return -1;

        int parenOpen = header.indexOf('(', shapeIdx);
        int parenClose = header.indexOf(')', parenOpen);
        if (parenOpen < 0 || parenClose < 0) return -1;

        String shapeStr = header.substring(parenOpen + 1, parenClose).trim();

        // Scalar: shape is ()
        if (shapeStr.isEmpty()) {
            return 1;
        }

        // Parse comma-separated dimensions
        String[] dims = shapeStr.split(",");
        int total = 1;
        for (String dim : dims) {
            dim = dim.trim();
            if (!dim.isEmpty()) {
                total *= Integer.parseInt(dim);
            }
        }
        return total;
    }

    /**
     * Extracts a scalar value from the parsed arrays map.
     */
    private static double getScalar(Map<String, double[]> arrays, String name) throws IOException {
        double[] values = arrays.get(name);
        if (values == null || values.length == 0) {
            throw new IOException("Missing required calibration field: " + name);
        }
        return values[0];
    }
}
