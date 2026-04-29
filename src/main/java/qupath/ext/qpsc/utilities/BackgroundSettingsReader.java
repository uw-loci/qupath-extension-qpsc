package qupath.ext.qpsc.utilities;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import qupath.ext.qpsc.modality.AngleExposure;

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

        public BackgroundSettings(
                String modality,
                String objective,
                String detector,
                String magnification,
                List<AngleExposure> angleExposures,
                String settingsFilePath,
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
            return String.format(
                    "BackgroundSettings[modality=%s, objective=%s, detector=%s, angles=%d, wbMode=%s]",
                    modality, objective, detector, angleExposures.size(), wbMode);
        }
    }

    /**
     * Resolve the background folder path for a given hardware combination and WB mode.
     * This is the central path resolution method that all callers should use.
     *
     * <p>The returned path is the canonical collection/lookup location for the
     * given WB mode, regardless of whether a {@code background_settings.yml}
     * currently exists there:
     * <ul>
     *   <li>Color WB modes ({@code per_angle}, {@code simple}, etc.): returns
     *       {@code basePath/<wbMode>}.</li>
     *   <li>{@code off} (monochrome): returns the flat {@code basePath} directly,
     *       since mono detectors don't use a WB subdirectory.</li>
     * </ul>
     *
     * <p>There is no legacy fallback to a stale top-level file from a
     * pre-per-mode-split setup -- those are ignored by design.</p>
     *
     * @param baseBackgroundFolder The base background correction folder from config
     * @param modality The modality name (e.g., "ppm")
     * @param objective The objective ID
     * @param detector The detector ID
     * @param wbMode White balance mode (e.g., "per_angle", "simple", "off"). Required.
     * @return Resolved folder path, or null if inputs are invalid
     * @throws IllegalArgumentException if {@code wbMode} is null or empty
     */
    public static String resolveBackgroundFolder(
            String baseBackgroundFolder, String modality, String objective, String detector, String wbMode) {

        if (baseBackgroundFolder == null || modality == null || objective == null || detector == null) {
            logger.debug("Cannot resolve background folder - missing required parameters");
            return null;
        }
        if (wbMode == null || wbMode.isEmpty()) {
            throw new IllegalArgumentException(
                    "resolveBackgroundFolder requires a non-empty wbMode. Passing null is no longer supported.");
        }

        String magnification = extractMagnificationFromObjective(objective);
        String basePath = new File(
                        baseBackgroundFolder,
                        detector + File.separator + modalityFamily(modality) + File.separator + magnification)
                .getPath();

        if ("off".equalsIgnoreCase(wbMode)) {
            // Monochrome / no-WB: flat path, no subdirectory
            logger.debug("Resolved background folder for 'off' WB mode: {}", basePath);
            return basePath;
        }

        // Color WB modes: always under <wbMode> subfolder
        String resolved = new File(basePath, wbMode).getPath();
        logger.debug("Resolved background folder for wbMode='{}': {}", wbMode, resolved);
        return resolved;
    }

    /**
     * Find and read background settings for a given hardware combination and WB mode.
     *
     * <p>Resolution is strictly mode-specific:
     * <ul>
     *   <li>For color WB modes ("per_angle", "simple"): reads
     *       {@code basePath/<wbMode>/background_settings.yml}.</li>
     *   <li>For monochrome ("off"): reads {@code basePath/background_settings.yml}
     *       directly (mono detectors don't use a WB subdirectory).</li>
     * </ul>
     *
     * <p>There is no fallback between these paths: a stale top-level file left
     * over from a pre-per-mode-split setup will not be read for a color mode
     * lookup, and a per-mode subfolder file will not be read for an "off" lookup.
     * This prevents the 2026-04-15 class of bug where a legacy flat file
     * silently shadowed fresh per-mode backgrounds.</p>
     *
     * @param baseBackgroundFolder The base background correction folder from config
     * @param modality The modality name (e.g., "ppm")
     * @param objective The objective ID (e.g., "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001")
     * @param detector The detector ID (e.g., "LOCI_DETECTOR_JAI_001")
     * @param wbMode White balance mode (e.g., "per_angle", "simple", "off"). Required.
     * @return BackgroundSettings if found and valid, null otherwise
     * @throws IllegalArgumentException if {@code wbMode} is null or empty
     */
    public static BackgroundSettings findBackgroundSettings(
            String baseBackgroundFolder, String modality, String objective, String detector, String wbMode) {

        if (baseBackgroundFolder == null
                || baseBackgroundFolder.isEmpty()
                || modality == null
                || objective == null
                || detector == null) {
            logger.debug("Cannot search for background settings - missing or empty base folder / required parameters");
            return null;
        }
        if (wbMode == null || wbMode.isEmpty()) {
            throw new IllegalArgumentException(
                    "findBackgroundSettings requires a non-empty wbMode ('per_angle', 'simple', or 'off'). "
                            + "Passing null is no longer supported; callers must resolve their WB mode first.");
        }

        try {
            String magnification = extractMagnificationFromObjective(objective);
            String basePath = new File(
                            baseBackgroundFolder,
                            detector + File.separator + modalityFamily(modality) + File.separator + magnification)
                    .getPath();

            File target;
            if ("off".equalsIgnoreCase(wbMode)) {
                // Monochrome / no-WB: flat path, no subdirectory
                target = new File(basePath, "background_settings.yml");
            } else {
                // Color WB modes: <wbMode> subdirectory
                target = new File(basePath + File.separator + wbMode, "background_settings.yml");
            }

            if (target.exists()) {
                logger.debug("Found background settings for wbMode='{}' at: {}", wbMode, target.getAbsolutePath());
                return readBackgroundSettings(target);
            }

            logger.debug(
                    "No background settings found for {}/{}/{} (wbMode={})", modality, objective, detector, wbMode);
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

        try (FileReader reader = new FileReader(settingsFile, StandardCharsets.UTF_8)) {
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
                        logger.debug("Parsed angle exposure: {}deg = {}ms", angle, exposure);
                    }
                }
            }

            // Validate that we have the essential information
            if (modality == null || angleExposures.isEmpty()) {
                logger.warn(
                        "Background settings file is missing essential information: {}",
                        settingsFile.getAbsolutePath());
                return null;
            }

            BackgroundSettings settings = new BackgroundSettings(
                    modality,
                    objective,
                    detector,
                    magnification,
                    angleExposures,
                    settingsFile.getAbsolutePath(),
                    wbMode);

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
     * Find all background settings for a given hardware combination across
     * every supported WB mode.
     *
     * <p>Scans only the canonical per-mode locations:
     * <ul>
     *   <li>Every {@code basePath/<wbMode>/background_settings.yml} for color
     *       WB modes (keyed by the subdirectory name).</li>
     *   <li>The flat {@code basePath/background_settings.yml} for {@code off}
     *       (monochrome), keyed under {@code "off"}.</li>
     * </ul>
     *
     * <p>Stale top-level files whose recorded {@code wbMode} is neither
     * {@code off} nor a valid subdirectory name are ignored with a warning --
     * they are most likely pre-per-mode-split legacy artifacts that would
     * otherwise pollute validity checks (2026-04-15 incident).
     *
     * @param baseBackgroundFolder The base background correction folder from config
     * @param modality The modality name (e.g., "ppm")
     * @param objective The objective ID
     * @param detector The detector ID
     * @return Map of WB mode protocol name to BackgroundSettings (never null, may be empty)
     */
    public static Map<String, BackgroundSettings> findAllBackgroundSettings(
            String baseBackgroundFolder, String modality, String objective, String detector) {

        Map<String, BackgroundSettings> result = new HashMap<>();

        if (baseBackgroundFolder == null
                || baseBackgroundFolder.isEmpty()
                || modality == null
                || objective == null
                || detector == null) {
            return result;
        }

        try {
            String magnification = extractMagnificationFromObjective(objective);
            String basePath = new File(
                            baseBackgroundFolder,
                            detector + File.separator + modalityFamily(modality) + File.separator + magnification)
                    .getPath();

            // Scan WB-mode subdirectories. Subdirectory name IS the WB mode.
            File baseDir = new File(basePath);
            if (baseDir.isDirectory()) {
                File[] subdirs = baseDir.listFiles(File::isDirectory);
                if (subdirs != null) {
                    for (File subdir : subdirs) {
                        File wbSettings = new File(subdir, "background_settings.yml");
                        if (wbSettings.exists()) {
                            BackgroundSettings settings = readBackgroundSettings(wbSettings);
                            if (settings != null) {
                                result.put(subdir.getName(), settings);
                                logger.debug(
                                        "Found WB-mode background settings in '{}' subdirectory", subdir.getName());
                            }
                        }
                    }
                }
            }

            // Flat path is reserved for monochrome / 'off' WB. Only accept it
            // if the file explicitly records wbMode='off' (or null for a
            // clean mono collection that never had WB set). Anything else is
            // a legacy artifact that we refuse to read.
            File flatSettings = new File(basePath, "background_settings.yml");
            if (flatSettings.exists()) {
                BackgroundSettings settings = readBackgroundSettings(flatSettings);
                if (settings != null) {
                    String recorded = settings.wbMode;
                    if (recorded == null || recorded.isEmpty() || "off".equalsIgnoreCase(recorded)) {
                        result.put("off", settings);
                        logger.debug("Found 'off' (monochrome) background settings at flat path");
                    } else {
                        logger.warn(
                                "Ignoring stale legacy background_settings.yml at {} (recorded wbMode='{}'). "
                                        + "Move or delete this file -- the current per-mode subfolder "
                                        + "({}/{}/background_settings.yml) is authoritative.",
                                flatSettings.getAbsolutePath(),
                                recorded,
                                basePath,
                                recorded);
                    }
                }
            }

            logger.info(
                    "Found background settings for {} WB modes under {}: {}", result.size(), basePath, result.keySet());

        } catch (Exception e) {
            logger.error("Error scanning for background settings", e);
        }

        return result;
    }

    /**
     * Check if the provided angle-exposure list matches the background settings.
     *
     * @param settings The background settings to compare against
     * @param currentAngleExposures The current angle-exposure list
     * @param tolerance Tolerance for exposure time comparison (e.g., 0.1 for 0.1ms tolerance)
     * @return true if they match within tolerance, false otherwise
     */
    public static boolean validateAngleExposures(
            BackgroundSettings settings, List<AngleExposure> currentAngleExposures, double tolerance) {

        if (settings == null || currentAngleExposures == null) {
            return false;
        }

        if (settings.angleExposures.size() != currentAngleExposures.size()) {
            logger.debug(
                    "Angle count mismatch: settings={}, current={}",
                    settings.angleExposures.size(),
                    currentAngleExposures.size());
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
                logger.debug(
                        "Angle mismatch at index {}: settings={}deg, current={}deg",
                        i,
                        settingsAe.ticks(),
                        currentAe.ticks());
                return false;
            }

            // Check exposure match (within tolerance)
            if (Math.abs(settingsAe.exposureMs() - currentAe.exposureMs()) > tolerance) {
                logger.debug(
                        "Exposure mismatch at index {}: settings={}ms, current={}ms (tolerance={}ms)",
                        i,
                        settingsAe.exposureMs(),
                        currentAe.exposureMs(),
                        tolerance);
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
    public static boolean validateAngleExposuresSubset(
            BackgroundSettings settings, List<AngleExposure> currentAngleExposures, double tolerance) {

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
                logger.debug("Selected angle {}deg not found in background settings", currentAe.ticks());
                return false;
            }

            // Check exposure match (within tolerance)
            if (Math.abs(backgroundExposure - currentAe.exposureMs()) > tolerance) {
                logger.debug(
                        "Exposure mismatch for angle {}deg: background={}ms, selected={}ms (tolerance={}ms)",
                        currentAe.ticks(),
                        backgroundExposure,
                        currentAe.exposureMs(),
                        tolerance);
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
     *
     * <p>Delegates to {@link ObjectiveUtils#extractMagnification} for the
     * actual extraction so the regex lives in one place; this wrapper
     * supplies the "unknown" path-segment fallback that the reader uses to
     * keep file paths well-formed even when extraction fails.
     */
    private static String extractMagnificationFromObjective(String objective) {
        String mag = ObjectiveUtils.extractMagnification(objective);
        return mag == null ? "unknown" : mag;
    }

    /**
     * Strip a trailing magnification suffix from a modality id to get the
     * on-disk family form. Delegates to {@link HardwareKey#stripMagnificationSuffix}
     * so the regex lives in one place across the codebase.
     */
    private static String modalityFamily(String modality) {
        return HardwareKey.stripMagnificationSuffix(modality);
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
