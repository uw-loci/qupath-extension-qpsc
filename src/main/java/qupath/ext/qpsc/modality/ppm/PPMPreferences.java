package qupath.ext.qpsc.modality.ppm;

import java.util.Map;
import javafx.beans.property.StringProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * Manages user preferences for PPM (Polarized light Microscopy) modality configuration.
 *
 * <p>This utility class stores and retrieves user preferences for PPM angle selection
 * and decimal exposure times. Preferences are persisted using QuPath's preference system
 * and automatically loaded from microscope configuration files when available.</p>
 *
 * <p><strong>Supported Preferences:</strong></p>
 * <ul>
 *   <li><strong>Angle Selection:</strong> Boolean flags for each of the four PPM angles</li>
 *   <li><strong>Exposure Times:</strong> Decimal exposure values in milliseconds for precise timing</li>
 *   <li><strong>Angle Overrides:</strong> Custom angle values for per-acquisition customization</li>
 *   <li><strong>Configuration Loading:</strong> Automatic loading of default exposures from YAML config</li>
 * </ul>
 *
 * <p>All exposure time values support decimal precision (e.g., 1.2ms, 500.0ms, 0.8ms)
 * for fine-grained exposure control across different illumination conditions.</p>
 *
 * @author Mike Nelson
 * @since 1.0
 * @see qupath.lib.gui.prefs.PathPrefs
 * @see qupath.ext.qpsc.utilities.MicroscopeConfigManager
 */
public class PPMPreferences {

    private static final Logger logger = LoggerFactory.getLogger(PPMPreferences.class);

    // Angle selection flags
    private static final StringProperty minusSelected =
            PathPrefs.createPersistentPreference("PPMMinusSelected", "true");
    private static final StringProperty zeroSelected = PathPrefs.createPersistentPreference("PPMZeroSelected", "true");
    private static final StringProperty plusSelected = PathPrefs.createPersistentPreference("PPMPlusSelected", "true");
    private static final StringProperty uncrossedSelected =
            PathPrefs.createPersistentPreference("PPMUncrossedSelected", "false");

    // Decimal exposure times in milliseconds for each angle (supports sub-millisecond precision)
    private static final StringProperty minusExposure =
            PathPrefs.createPersistentPreference("PPMMinusExposureMs", "500");
    private static final StringProperty zeroExposure = PathPrefs.createPersistentPreference("PPMZeroExposureMs", "800");
    private static final StringProperty plusExposure = PathPrefs.createPersistentPreference("PPMPlusExposureMs", "500");
    private static final StringProperty uncrossedExposure =
            PathPrefs.createPersistentPreference("PPMUncrossedExposureMs", "10");

    // Angle override preferences for per-acquisition customization
    private static final StringProperty overrideEnabled =
            PathPrefs.createPersistentPreference("PPMAngleOverrideEnabled", "false");
    private static final StringProperty overridePlusAngle =
            PathPrefs.createPersistentPreference("PPMOverridePlusAngle", "7.0");
    private static final StringProperty overrideMinusAngle =
            PathPrefs.createPersistentPreference("PPMOverrideMinusAngle", "-7.0");

    // Active calibration file path (set after successful sunburst calibration)
    private static final StringProperty activeCalibrationPath =
            PathPrefs.createPersistentPreference("PPMActiveCalibrationPath", "");

    // =============== Analysis Parameters ===============

    // Birefringence threshold: pixels below this intensity are excluded from analysis
    private static final StringProperty birefringenceThreshold =
            PathPrefs.createPersistentPreference("PPMBirefringenceThreshold", "100.0");

    // Number of bins for polarity plot rose diagrams (18 bins = 10 deg each)
    private static final StringProperty histogramBins = PathPrefs.createPersistentPreference("PPMHistogramBins", "18");

    // HSV saturation threshold for valid pixel filtering
    private static final StringProperty saturationThreshold =
            PathPrefs.createPersistentPreference("PPMSaturationThreshold", "0.2");

    // HSV value (brightness) threshold for valid pixel filtering
    private static final StringProperty valueThreshold =
            PathPrefs.createPersistentPreference("PPMValueThreshold", "0.2");

    // Dilation distance in micrometers for surface perpendicularity analysis
    private static final StringProperty dilationUm = PathPrefs.createPersistentPreference("PPMDilationUm", "50.0");

    // TACS angle threshold in degrees for PS-TACS classification
    private static final StringProperty tacsThresholdDeg =
            PathPrefs.createPersistentPreference("PPMTacsThresholdDeg", "30.0");

    static {
        // PPM exposure defaults are initialized with fallback values.
        // Use loadExposuresForProfile() to load profile-specific defaults.
        logger.debug("PPMPreferences initialized with fallback exposure values");
    }

    private PPMPreferences() {}

    public static boolean getMinusSelected() {
        return Boolean.parseBoolean(minusSelected.get());
    }

    public static void setMinusSelected(boolean selected) {
        minusSelected.set(String.valueOf(selected));
    }

    public static boolean getZeroSelected() {
        return Boolean.parseBoolean(zeroSelected.get());
    }

    public static void setZeroSelected(boolean selected) {
        zeroSelected.set(String.valueOf(selected));
    }

    public static boolean getPlusSelected() {
        return Boolean.parseBoolean(plusSelected.get());
    }

    public static void setPlusSelected(boolean selected) {
        plusSelected.set(String.valueOf(selected));
    }

    /**
     * Gets the decimal exposure time for the negative (minus) PPM angle.
     * @return exposure time in milliseconds (supports decimal values like 1.2ms)
     */
    public static double getMinusExposureMs() {
        return Double.parseDouble(minusExposure.get());
    }

    /**
     * Sets the decimal exposure time for the negative (minus) PPM angle.
     * @param ms exposure time in milliseconds (decimal values supported)
     */
    public static void setMinusExposureMs(double ms) {
        minusExposure.set(String.valueOf(ms));
    }

    /**
     * Gets the decimal exposure time for the zero-degree (crossed polarizers) PPM angle.
     * @return exposure time in milliseconds (supports decimal values like 800.5ms)
     */
    public static double getZeroExposureMs() {
        return Double.parseDouble(zeroExposure.get());
    }

    /**
     * Sets the decimal exposure time for the zero-degree (crossed polarizers) PPM angle.
     * @param ms exposure time in milliseconds (decimal values supported)
     */
    public static void setZeroExposureMs(double ms) {
        zeroExposure.set(String.valueOf(ms));
    }

    /**
     * Gets the decimal exposure time for the positive (plus) PPM angle.
     * @return exposure time in milliseconds (supports decimal values like 1.8ms)
     */
    public static double getPlusExposureMs() {
        return Double.parseDouble(plusExposure.get());
    }

    /**
     * Sets the decimal exposure time for the positive (plus) PPM angle.
     * @param ms exposure time in milliseconds (decimal values supported)
     */
    public static void setPlusExposureMs(double ms) {
        plusExposure.set(String.valueOf(ms));
    }

    public static boolean getUncrossedSelected() {
        return Boolean.parseBoolean(uncrossedSelected.get());
    }

    public static void setUncrossedSelected(boolean selected) {
        uncrossedSelected.set(String.valueOf(selected));
    }

    /**
     * Gets the decimal exposure time for the uncrossed (parallel polarizers) PPM angle.
     * @return exposure time in milliseconds (supports decimal values like 0.5ms)
     */
    public static double getUncrossedExposureMs() {
        return Double.parseDouble(uncrossedExposure.get());
    }

    /**
     * Sets the decimal exposure time for the uncrossed (parallel polarizers) PPM angle.
     * @param ms exposure time in milliseconds (decimal values supported)
     */
    public static void setUncrossedExposureMs(double ms) {
        uncrossedExposure.set(String.valueOf(ms));
    }

    /**
     * Loads exposure defaults from the configuration for the specified objective/detector combination.
     * Updates the preference values with the profile-specific exposures if found.
     *
     * @param objective the objective identifier (e.g., "LOCI_OBJECTIVE_OLYMPUS_10X_001")
     * @param detector the detector identifier (e.g., "LOCI_DETECTOR_JAI_001")
     * @return true if exposures were loaded successfully, false otherwise
     */
    public static boolean loadExposuresForProfile(String objective, String detector) {
        try {
            MicroscopeConfigManager mgr =
                    MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty());

            // Use MicroscopeConfigManager's helper method which checks imageprocessing config first
            Map<String, Object> exposures = mgr.getModalityExposures("ppm", objective, detector);

            if (exposures != null) {
                // Load exposures for each angle
                loadExposureForAngle(exposures, "negative", "setMinusExposureMs");
                loadExposureForAngle(exposures, "crossed", "setZeroExposureMs");
                loadExposureForAngle(exposures, "positive", "setPlusExposureMs");
                loadExposureForAngle(exposures, "uncrossed", "setUncrossedExposureMs");

                logger.info("Loaded PPM exposures for {}/{} from configuration", objective, detector);
                return true;
            }

            logger.warn("No PPM exposures found for objective {} and detector {}", objective, detector);
            return false;

        } catch (Exception e) {
            logger.warn("Failed to load PPM exposures for {}/{}", objective, detector, e);
            return false;
        }
    }

    /**
     * Helper method to extract exposure value for a specific angle and set the corresponding preference.
     */
    private static void loadExposureForAngle(Map<?, ?> exposures, String angleName, String setterMethod) {
        Object exposureObj = exposures.get(angleName);
        if (exposureObj instanceof Number) {
            // Simple numeric exposure (for TELEDYNE detector)
            double ms = ((Number) exposureObj).doubleValue();
            setExposureValue(setterMethod, ms);
        } else if (exposureObj instanceof Map<?, ?> exposureMap) {
            // Per-channel exposure map from WB calibration (JAI detector)
            // Priority: "all" (unified) -> "g" (green channel as reference)
            // Green is the median channel and matches what background collection uses,
            // ensuring exposure consistency between acquisition and background correction.
            Object allObj = exposureMap.get("all");
            if (allObj instanceof Number) {
                double ms = ((Number) allObj).doubleValue();
                setExposureValue(setterMethod, ms);
            } else {
                Object greenObj = exposureMap.get("g");
                if (greenObj instanceof Number) {
                    double ms = ((Number) greenObj).doubleValue();
                    logger.info(
                            "Using green channel exposure for {} (per-channel WB calibration): {}ms", angleName, ms);
                    setExposureValue(setterMethod, ms);
                }
            }
        }
    }

    /**
     * Helper method to call the appropriate setter method by name.
     */
    private static void setExposureValue(String setterMethod, double ms) {
        switch (setterMethod) {
            case "setMinusExposureMs" -> setMinusExposureMs(ms);
            case "setZeroExposureMs" -> setZeroExposureMs(ms);
            case "setPlusExposureMs" -> setPlusExposureMs(ms);
            case "setUncrossedExposureMs" -> setUncrossedExposureMs(ms);
            default -> logger.warn("Unknown setter method: {}", setterMethod);
        }
    }

    // =============== Analysis Parameter Accessors ===============

    public static StringProperty birefringenceThresholdProperty() {
        return birefringenceThreshold;
    }

    public static double getBirefringenceThreshold() {
        return Double.parseDouble(birefringenceThreshold.get());
    }

    public static void setBirefringenceThreshold(double threshold) {
        birefringenceThreshold.set(String.valueOf(threshold));
    }

    public static StringProperty histogramBinsProperty() {
        return histogramBins;
    }

    public static int getHistogramBins() {
        return Integer.parseInt(histogramBins.get());
    }

    public static void setHistogramBins(int bins) {
        histogramBins.set(String.valueOf(bins));
    }

    public static StringProperty saturationThresholdProperty() {
        return saturationThreshold;
    }

    public static double getSaturationThreshold() {
        return Double.parseDouble(saturationThreshold.get());
    }

    public static void setSaturationThreshold(double threshold) {
        saturationThreshold.set(String.valueOf(threshold));
    }

    public static StringProperty valueThresholdProperty() {
        return valueThreshold;
    }

    public static double getValueThreshold() {
        return Double.parseDouble(valueThreshold.get());
    }

    public static void setValueThreshold(double threshold) {
        valueThreshold.set(String.valueOf(threshold));
    }

    public static StringProperty dilationUmProperty() {
        return dilationUm;
    }

    public static double getDilationUm() {
        return Double.parseDouble(dilationUm.get());
    }

    public static void setDilationUm(double um) {
        dilationUm.set(String.valueOf(um));
    }

    public static StringProperty tacsThresholdDegProperty() {
        return tacsThresholdDeg;
    }

    public static double getTacsThresholdDeg() {
        return Double.parseDouble(tacsThresholdDeg.get());
    }

    public static void setTacsThresholdDeg(double deg) {
        tacsThresholdDeg.set(String.valueOf(deg));
    }

    // =============== Calibration Preferences ===============

    /**
     * Returns the property for the active calibration path.
     * Used by QPPreferenceDialog to register the preference in the UI.
     */
    public static StringProperty activeCalibrationPathProperty() {
        return activeCalibrationPath;
    }

    /**
     * Gets the path to the currently active PPM sunburst calibration file (.npz).
     * This is set after a successful sunburst calibration and stamped onto
     * PPM images at acquisition time as per-image metadata.
     *
     * @return path to the active calibration file, or empty string if not set
     */
    public static String getActiveCalibrationPath() {
        return activeCalibrationPath.get();
    }

    /**
     * Sets the path to the currently active PPM sunburst calibration file (.npz).
     *
     * @param path absolute path to the calibration file
     */
    public static void setActiveCalibrationPath(String path) {
        activeCalibrationPath.set(path != null ? path : "");
        logger.info("Active PPM calibration set to: {}", path);
    }

    /**
     * Returns true if an active calibration path is set and the file exists.
     */
    public static boolean hasActiveCalibration() {
        String path = getActiveCalibrationPath();
        if (path == null || path.isEmpty()) {
            return false;
        }
        return new java.io.File(path).exists();
    }

    // =============== Angle Override Preferences ===============

    /**
     * Gets whether angle override is enabled for per-acquisition customization.
     * @return true if angle override is enabled, false otherwise
     */
    public static boolean getAngleOverrideEnabled() {
        return Boolean.parseBoolean(overrideEnabled.get());
    }

    /**
     * Sets whether angle override is enabled for per-acquisition customization.
     * @param enabled true to enable angle override, false to disable
     */
    public static void setAngleOverrideEnabled(boolean enabled) {
        overrideEnabled.set(String.valueOf(enabled));
    }

    /**
     * Gets the custom positive angle override value.
     * @return positive angle in degrees (default 7.0)
     */
    public static double getOverridePlusAngle() {
        return Double.parseDouble(overridePlusAngle.get());
    }

    /**
     * Sets the custom positive angle override value.
     * @param angle positive angle in degrees
     */
    public static void setOverridePlusAngle(double angle) {
        overridePlusAngle.set(String.valueOf(angle));
    }

    /**
     * Gets the custom negative angle override value.
     * @return negative angle in degrees (default -7.0)
     */
    public static double getOverrideMinusAngle() {
        return Double.parseDouble(overrideMinusAngle.get());
    }

    /**
     * Sets the custom negative angle override value.
     * @param angle negative angle in degrees
     */
    public static void setOverrideMinusAngle(double angle) {
        overrideMinusAngle.set(String.valueOf(angle));
    }
}
