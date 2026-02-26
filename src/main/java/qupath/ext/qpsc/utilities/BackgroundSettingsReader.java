package qupath.ext.qpsc.utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.AngleExposure;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

/**
 * BackgroundSettingsReader - Utility for reading background collection settings files
 * 
 * <p>This class reads the background_settings.yml files created during background collection
 * and provides access to the stored acquisition parameters. This enables validation and
 * autofilling of background correction settings to ensure consistency.</p>
 */
public class BackgroundSettingsReader {
    
    private static final Logger logger = LoggerFactory.getLogger(BackgroundSettingsReader.class);
    
    /**
     * Container for background settings read from file
     */
    public static class BackgroundSettings {
        public final String modality;
        public final String objective;
        public final String detector;
        public final String magnification;
        public final List<AngleExposure> angleExposures;
        public final String settingsFilePath;
        /** White balance mode used during background collection (e.g., "per_angle", "simple", "camera_awb", "off"). May be null for older settings files. */
        public final String wbMode;

        public BackgroundSettings(String modality, String objective, String detector,
                String magnification, List<AngleExposure> angleExposures, String settingsFilePath,
                String wbMode) {
            this.modality = modality;
            this.objective = objective;
            this.detector = detector;
            this.magnification = magnification;
            this.angleExposures = angleExposures;
            this.settingsFilePath = settingsFilePath;
            this.wbMode = wbMode;
        }

        @Override
        public String toString() {
            return String.format("BackgroundSettings[modality=%s, objective=%s, detector=%s, angles=%d, wbMode=%s]",
                    modality, objective, detector, angleExposures.size(), wbMode);
        }
    }
    
    /**
     * Resolve the background folder path for a given hardware combination and WB mode.
     * This is the central path resolution method that all callers should use.
     *
     * <p>Resolution logic:
     * <ol>
     *   <li>If wbMode provided: check {@code basePath/wbMode/background_settings.yml} first</li>
     *   <li>Fall back to legacy {@code basePath/background_settings.yml}</li>
     *   <li>If neither exists and wbMode provided: return {@code basePath/wbMode} (for new collections)</li>
     * </ol>
     *
     * @param baseBackgroundFolder The base background correction folder from config
     * @param modality The modality name (e.g., "ppm")
     * @param objective The objective ID
     * @param detector The detector ID
     * @param wbMode White balance mode (e.g., "per_angle", "camera_awb"), or null for legacy behavior
     * @return Resolved folder path (never null if inputs are valid)
     */
    public static String resolveBackgroundFolder(String baseBackgroundFolder,
            String modality, String objective, String detector, String wbMode) {

        if (baseBackgroundFolder == null || modality == null || objective == null || detector == null) {
            logger.debug("Cannot resolve background folder - missing required parameters");
            return null;
        }

        String magnification = extractMagnificationFromObjective(objective);
        String basePath = new File(baseBackgroundFolder,
                detector + File.separator + modality + File.separator + magnification).getPath();

        if (wbMode != null && !wbMode.isEmpty()) {
            // Check WB-mode subfolder first
            File wbSettings = new File(basePath + File.separator + wbMode, "background_settings.yml");
            if (wbSettings.exists()) {
                String resolved = new File(basePath, wbMode).getPath();
                logger.debug("Resolved background folder to WB subfolder: {}", resolved);
                return resolved;
            }
        }

        // Fall back to legacy (flat) path
        File legacySettings = new File(basePath, "background_settings.yml");
        if (legacySettings.exists()) {
            logger.debug("Resolved background folder to legacy path: {}", basePath);
            return basePath;
        }

        // Neither exists -- return WB subfolder for new collections, or basePath if no wbMode
        if (wbMode != null && !wbMode.isEmpty()) {
            String newPath = new File(basePath, wbMode).getPath();
            logger.debug("No existing backgrounds found; returning WB subfolder for new collection: {}", newPath);
            return newPath;
        }

        logger.debug("No existing backgrounds found; returning legacy path: {}", basePath);
        return basePath;
    }

    /**
     * Attempt to find and read background settings for a given hardware combination and WB mode.
     *
     * @param baseBackgroundFolder The base background correction folder from config
     * @param modality The modality name (e.g., "ppm")
     * @param objective The objective ID
     * @param detector The detector ID
     * @param wbMode White balance mode for targeted lookup (e.g., "per_angle"), or null
     * @return BackgroundSettings if found and valid, null otherwise
     */
    public static BackgroundSettings findBackgroundSettings(String baseBackgroundFolder,
            String modality, String objective, String detector, String wbMode) {

        if (baseBackgroundFolder == null || modality == null || objective == null || detector == null) {
            logger.debug("Cannot search for background settings - missing required parameters");
            return null;
        }

        try {
            String magnification = extractMagnificationFromObjective(objective);
            String basePath = new File(baseBackgroundFolder,
                    detector + File.separator + modality + File.separator + magnification).getPath();

            // If wbMode given, check WB subfolder first
            if (wbMode != null && !wbMode.isEmpty()) {
                File wbSettings = new File(basePath + File.separator + wbMode, "background_settings.yml");
                if (wbSettings.exists()) {
                    logger.debug("Found WB-mode background settings at: {}", wbSettings.getAbsolutePath());
                    return readBackgroundSettings(wbSettings);
                }
            }

            // Fall back to legacy path
            File legacySettings = new File(basePath, "background_settings.yml");
            if (legacySettings.exists()) {
                logger.debug("Found legacy background settings at: {}", legacySettings.getAbsolutePath());
                return readBackgroundSettings(legacySettings);
            }

            logger.debug("No background settings found for {}/{}/{} (wbMode={})", modality, objective, detector, wbMode);
            return null;

        } catch (Exception e) {
            logger.error("Error searching for background settings", e);
            return null;
        }
    }

    /**
     * Attempt to find and read background settings for a given modality/objective/detector combination.
     * When no wbMode is specified, scans WB-mode subdirectories to find any available backgrounds.
     *
     * @param baseBackgroundFolder The base background correction folder from config
     * @param modality The modality name (e.g., "ppm")
     * @param objective The objective ID (e.g., "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001")
     * @param detector The detector ID (e.g., "LOCI_DETECTOR_JAI_001")
     * @return BackgroundSettings if found and valid, null otherwise
     */
    public static BackgroundSettings findBackgroundSettings(String baseBackgroundFolder,
            String modality, String objective, String detector) {

        if (baseBackgroundFolder == null || modality == null || objective == null || detector == null) {
            logger.debug("Cannot search for background settings - missing required parameters");
            return null;
        }

        try {
            // Extract magnification from objective
            String magnification = extractMagnificationFromObjective(objective);

            String basePath = new File(baseBackgroundFolder,
                    detector + File.separator + modality + File.separator + magnification).getPath();

            // Check legacy (flat) path first for backward compatibility
            File legacySettings = new File(basePath, "background_settings.yml");
            if (legacySettings.exists()) {
                logger.debug("Found legacy background settings at: {}", legacySettings.getAbsolutePath());
                return readBackgroundSettings(legacySettings);
            }

            // Scan WB-mode subdirectories
            File baseDir = new File(basePath);
            if (baseDir.isDirectory()) {
                File[] subdirs = baseDir.listFiles(File::isDirectory);
                if (subdirs != null) {
                    for (File subdir : subdirs) {
                        File wbSettings = new File(subdir, "background_settings.yml");
                        if (wbSettings.exists()) {
                            logger.debug("Found WB-mode background settings in subdirectory: {}", wbSettings.getAbsolutePath());
                            return readBackgroundSettings(wbSettings);
                        }
                    }
                }
            }

            logger.debug("No background settings found at legacy path or WB subdirectories under: {}", basePath);
            return null;

        } catch (Exception e) {
            logger.error("Error searching for background settings", e);
            return null;
        }
    }
    
    /**
     * Read background settings from a specific file.
     * 
     * @param settingsFile The background_settings.yml file to read
     * @return BackgroundSettings if valid, null otherwise
     */
    public static BackgroundSettings readBackgroundSettings(File settingsFile) {
        logger.info("Reading background settings from: {}", settingsFile.getAbsolutePath());
        
        try (FileReader reader = new FileReader(settingsFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> yamlData = yaml.load(reader);
            
            if (yamlData == null) {
                logger.warn("YAML file is empty or invalid: {}", settingsFile.getAbsolutePath());
                return null;
            }
            
            // Extract hardware configuration
            Map<String, Object> hardware = getMap(yamlData, "hardware");
            String modality = getString(hardware, "modality");
            String objective = getString(hardware, "objective");
            String detector = getString(hardware, "detector");
            String magnification = getString(hardware, "magnification");

            // Extract white balance mode (may be absent in older settings files)
            Map<String, Object> acquisition = getMap(yamlData, "acquisition");
            String wbMode = getString(acquisition, "wb_mode");
            
            // Extract angle-exposure pairs from the structured list
            List<AngleExposure> angleExposures = new ArrayList<>();
            List<Map<String, Object>> angleExposureList = getMapList(yamlData, "angle_exposures");
            
            if (angleExposureList != null) {
                for (Map<String, Object> pair : angleExposureList) {
                    Double angle = getDouble(pair, "angle");
                    Double exposure = getDouble(pair, "exposure");
                    
                    if (angle != null && exposure != null) {
                        angleExposures.add(new AngleExposure(angle, exposure));
                        logger.debug("Parsed angle exposure: {}° = {}ms", angle, exposure);
                    }
                }
            }
            
            // Validate that we have the essential information
            if (modality == null || angleExposures.isEmpty()) {
                logger.warn("Background settings file is missing essential information: {}", settingsFile.getAbsolutePath());
                return null;
            }
            
            BackgroundSettings settings = new BackgroundSettings(modality, objective, detector,
                    magnification, angleExposures, settingsFile.getAbsolutePath(), wbMode);
            
            logger.info("Successfully read background settings: {}", settings);
            return settings;
            
        } catch (IOException e) {
            logger.error("Failed to read background settings file: {}", settingsFile.getAbsolutePath(), e);
            return null;
        } catch (Exception e) {
            logger.error("Error parsing YAML background settings file: {}", settingsFile.getAbsolutePath(), e);
            return null;
        }
    }
    
    /**
     * Check if the provided angle-exposure list matches the background settings.
     * 
     * @param settings The background settings to compare against
     * @param currentAngleExposures The current angle-exposure list
     * @param tolerance Tolerance for exposure time comparison (e.g., 0.1 for 0.1ms tolerance)
     * @return true if they match within tolerance, false otherwise
     */
    public static boolean validateAngleExposures(BackgroundSettings settings, 
            List<AngleExposure> currentAngleExposures, double tolerance) {
        
        if (settings == null || currentAngleExposures == null) {
            return false;
        }
        
        if (settings.angleExposures.size() != currentAngleExposures.size()) {
            logger.debug("Angle count mismatch: settings={}, current={}", 
                    settings.angleExposures.size(), currentAngleExposures.size());
            return false;
        }
        
        // Sort both lists by angle for comparison
        List<AngleExposure> sortedSettings = new ArrayList<>(settings.angleExposures);
        List<AngleExposure> sortedCurrent = new ArrayList<>(currentAngleExposures);
        sortedSettings.sort((a, b) -> Double.compare(a.ticks(), b.ticks()));
        sortedCurrent.sort((a, b) -> Double.compare(a.ticks(), b.ticks()));
        
        for (int i = 0; i < sortedSettings.size(); i++) {
            AngleExposure settingsAe = sortedSettings.get(i);
            AngleExposure currentAe = sortedCurrent.get(i);
            
            // Check angle match (exact)
            if (Math.abs(settingsAe.ticks() - currentAe.ticks()) > 0.001) {
                logger.debug("Angle mismatch at index {}: settings={}°, current={}°", 
                        i, settingsAe.ticks(), currentAe.ticks());
                return false;
            }
            
            // Check exposure match (within tolerance)
            if (Math.abs(settingsAe.exposureMs() - currentAe.exposureMs()) > tolerance) {
                logger.debug("Exposure mismatch at index {}: settings={}ms, current={}ms (tolerance={}ms)", 
                        i, settingsAe.exposureMs(), currentAe.exposureMs(), tolerance);
                return false;
            }
        }
        
        return true;
    }

    /**
     * Check if the provided angle-exposure list is compatible with background settings.
     * This method allows for subset validation - the user can select fewer angles than
     * what background images exist for, as long as the selected angles exist and have
     * matching exposures.
     *
     * @param settings The background settings to compare against
     * @param currentAngleExposures The current angle-exposure list (can be subset)
     * @param tolerance Tolerance for exposure time comparison (e.g., 0.1 for 0.1ms tolerance)
     * @return true if selected angles exist in background settings with matching exposures
     */
    public static boolean validateAngleExposuresSubset(BackgroundSettings settings,
            List<AngleExposure> currentAngleExposures, double tolerance) {

        if (settings == null || currentAngleExposures == null) {
            return false;
        }

        // Create a map of background angles for quick lookup
        Map<Double, Double> backgroundAngleToExposure = new HashMap<>();
        for (AngleExposure ae : settings.angleExposures) {
            backgroundAngleToExposure.put(ae.ticks(), ae.exposureMs());
        }

        // Check each selected angle
        for (AngleExposure currentAe : currentAngleExposures) {
            Double backgroundExposure = backgroundAngleToExposure.get(currentAe.ticks());

            if (backgroundExposure == null) {
                // Selected angle doesn't exist in background settings
                logger.debug("Selected angle {}° not found in background settings", currentAe.ticks());
                return false;
            }

            // Check exposure match (within tolerance)
            if (Math.abs(backgroundExposure - currentAe.exposureMs()) > tolerance) {
                logger.debug("Exposure mismatch for angle {}°: background={}ms, selected={}ms (tolerance={}ms)",
                        currentAe.ticks(), backgroundExposure, currentAe.exposureMs(), tolerance);
                return false;
            }
        }

        logger.debug("All selected angles ({}) match background settings", currentAngleExposures.size());
        return true;
    }

    /**
     * Extract magnification from objective identifier.
     * Examples:
     * - "LOCI_OBJECTIVE_OLYMPUS_10X_001" -> "10x"
     * - "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001" -> "20x"
     * - "LOCI_OBJECTIVE_OLYMPUS_40X_POL_001" -> "40x"
     */
    private static String extractMagnificationFromObjective(String objective) {
        if (objective == null) return "unknown";
        
        // Look for pattern like "10X", "20X", "40X"
        Pattern pattern = Pattern.compile("(\\d+)X", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(objective);
        
        if (matcher.find()) {
            return matcher.group(1).toLowerCase() + "x";  // "20X" -> "20x"
        }
        
        // Fallback: return "unknown" if pattern not found
        return "unknown";
    }
    
    /**
     * Safely extract a Map from YAML data
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> getMap(Map<String, Object> data, String key) {
        if (data == null || key == null) return null;
        Object value = data.get(key);
        return (value instanceof Map) ? (Map<String, Object>) value : null;
    }
    
    /**
     * Safely extract a String from YAML data
     */
    private static String getString(Map<String, Object> data, String key) {
        if (data == null || key == null) return null;
        Object value = data.get(key);
        return (value != null) ? value.toString() : null;
    }
    
    /**
     * Safely extract a Double from YAML data
     */
    private static Double getDouble(Map<String, Object> data, String key) {
        if (data == null || key == null) return null;
        Object value = data.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
    
    /**
     * Safely extract a List of Maps from YAML data
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> getMapList(Map<String, Object> data, String key) {
        if (data == null || key == null) return null;
        Object value = data.get(key);
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map) {
                    result.add((Map<String, Object>) item);
                }
            }
            return result;
        }
        return null;
    }
}