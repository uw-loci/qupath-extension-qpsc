package qupath.ext.qpsc.utilities;

import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import qupath.ext.qpsc.modality.Channel;
import qupath.ext.qpsc.modality.PresetRef;
import qupath.ext.qpsc.modality.PropertyRef;
import qupath.ext.qpsc.modality.PropertyWrite;

/**
 * MicroscopeConfigManager
 *
 * <p>Singleton for loading and querying your microscope YAML configuration:
 *   - Parses nested YAML into a {@code Map<String,Object>}.
 *   - Offers type safe getters (getDouble, getSection, getList, etc.).
 *   - Validates required keys and reports missing paths.
 *   - Supports new acquisition profile format with defaults and specific profiles
 */
public class MicroscopeConfigManager {
    private static final Logger logger = LoggerFactory.getLogger(MicroscopeConfigManager.class);

    // Singleton instance
    private static MicroscopeConfigManager instance;

    // Primary config data loaded from the chosen microscope YAML.
    // Volatile for thread-safe atomic swap during reload().
    private volatile Map<String, Object> configData;

    // Shared LOCI resource data loaded from resources_LOCI.yml
    private volatile Map<String, Object> resourceData;
    private volatile Map<String, String> lociSectionMap;
    private volatile String configPath;

    // External autofocus settings loaded from autofocus_{microscope}.yml
    // Maps objective ID -> autofocus parameters map
    private volatile Map<String, Map<String, Object>> autofocusData;

    // External imageprocessing settings loaded from imageprocessing_{microscope}.yml
    // Contains imaging_profiles and background_correction settings
    private volatile Map<String, Object> imageprocessingData;

    /**
     * Private constructor: loads microscope YAML, shared LOCI resources, external autofocus settings, and imageprocessing settings.
     *
     * @param configPath Filesystem path to the microscope YAML configuration file.
     */
    private MicroscopeConfigManager(String configPath) {
        this.configPath = configPath;
        this.configData = loadConfig(configPath);
        String resPath = computeResourcePath(configPath);
        this.resourceData = loadConfig(resPath);

        // Load external autofocus settings if available
        this.autofocusData = loadAutofocusConfig(configPath);

        // Load external imageprocessing settings if available
        this.imageprocessingData = loadImageprocessingConfig(configPath);

        // Dynamically build field-to-section map from the top-level of resources_LOCI.yml
        this.lociSectionMap = new HashMap<>();
        for (String section : resourceData.keySet()) {
            if (section.startsWith("ID_") || section.startsWith("id_")) {
                String field = section.substring(3) // remove "id_"
                        .replaceAll("_", "") // e.g. "OBJECTIVE_LENS" -> "OBJECTIVELENS"
                        .toLowerCase(); // "OBJECTIVELENS" -> "objectivelens"
                lociSectionMap.put(field, section);
            }
        }
        if (lociSectionMap.isEmpty()) logger.warn("No LOCI sections found in shared resources!");
    }

    /**
     * Initializes and returns the singleton instance. Must be called first with the path to the microscope YAML.
     *
     * @param configPath Path to the microscope YAML file.
     * @return Shared MicroscopeConfigManager instance.
     */
    public static synchronized MicroscopeConfigManager getInstance(String configPath) {
        if (instance == null) {
            instance = new MicroscopeConfigManager(configPath);
        }
        return instance;
    }

    /**
     * Returns the existing singleton instance, or null if not yet initialized.
     * Use this when you need the config manager but don't have a config path
     * (e.g., for copying configs to a project after setup is complete).
     *
     * @return the existing instance, or null
     */
    public static synchronized MicroscopeConfigManager getInstanceIfAvailable() {
        return instance;
    }

    /**
     * Retrieves an unmodifiable view of the entire configuration map currently loaded
     * from the microscope-specific YAML file.
     *
     * <p>This method provides a convenient way to inspect the entire loaded configuration,
     * which can be helpful for debugging or validation purposes.</p>
     *
     * @return An unmodifiable Map containing the full configuration data.
     */
    public Map<String, Object> getAllConfig() {
        return Collections.unmodifiableMap(configData);
    }

    /**
     * Reloads the microscope YAML, shared LOCI resources, external autofocus settings, and imageprocessing settings.
     *
     * @param configPath Path to the microscope YAML file.
     */
    public synchronized void reload(String configPath) {
        // Atomic swap: build new maps first, then assign all at once.
        // This prevents a race where a reader sees an empty map between
        // clear() and putAll() on a concurrent thread.
        Map<String, Object> newConfig = loadConfig(configPath);
        String resPath = computeResourcePath(configPath);
        Map<String, Object> newResources = loadConfig(resPath);
        Map<String, Map<String, Object>> newAutofocus = loadAutofocusConfig(configPath);
        Map<String, Object> newImgproc = loadImageprocessingConfig(configPath);

        this.configData = newConfig;
        this.resourceData = newResources;
        this.autofocusData = newAutofocus;
        this.imageprocessingData = newImgproc;
        this.configPath = configPath;

        // Rebuild lociSectionMap from new resources
        Map<String, String> newSectionMap = new HashMap<>();
        for (String section : newResources.keySet()) {
            if (section.startsWith("ID_") || section.startsWith("id_")) {
                String field = section.substring(3).replaceAll("_", "").toLowerCase();
                newSectionMap.put(field, section);
            }
        }
        this.lociSectionMap = newSectionMap;

        logger.info("Reloaded all config data from: {}", configPath);
    }

    /**
     * Retrieves a section from the resources file directly.
     * This bypasses the normal config lookup and goes straight to resources.
     *
     * @param sectionName The top-level section name in resources (e.g., "id_detector")
     * @return Map containing the section data, or null if not found
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getResourceSection(String sectionName) {
        Object section = resourceData.get(sectionName);
        return (section instanceof Map<?, ?>) ? (Map<String, Object>) section : null;
    }

    /**
     * Get the merged id_detector section: config-local entries override resources.
     *
     * <p>The setup wizard writes detector definitions directly into the microscope
     * config file (id_detector section), so these may not exist in resources_LOCI.yml.
     * This method merges both sources, preferring config-local definitions.
     *
     * @return Merged map of detector ID to config, or empty map if none found
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getMergedDetectorSection() {
        Map<String, Object> merged = new java.util.HashMap<>();

        // Start with resources (shared across microscopes)
        Object resSection = resourceData.get("id_detector");
        if (resSection instanceof Map<?, ?>) {
            merged.putAll((Map<String, Object>) resSection);
        }

        // Override with config-local id_detector (from setup wizard or manual edit)
        Object localSection = configData.get("id_detector");
        if (localSection instanceof Map<?, ?>) {
            merged.putAll((Map<String, Object>) localSection);
        }

        return merged;
    }

    /**
     * Computes the path to the shared LOCI resources file based on the microscope config path.
     * For example, transforms ".../microscopes/config_PPM.yml" -> ".../resources_LOCI.yml".
     *
     * @param configPath Path to the microscope YAML file.
     * @return Path to resources_LOCI.yml.
     */
    private static String computeResourcePath(String configPath) {
        Path cfg = Paths.get(configPath);
        // Get the parent folder of the config file
        Path baseDir = cfg.getParent();
        // Append "resources/resources_LOCI.yml"
        Path resourcePath =
                baseDir.resolve("resources").resolve("resources_LOCI.yml").toAbsolutePath();

        File resourceFile = resourcePath.toFile();
        if (!resourceFile.exists()) {
            Logger logger = LoggerFactory.getLogger(MicroscopeConfigManager.class);
            logger.warn("Could not find shared LOCI resource file at: {}", resourcePath);
            // Optionally, throw an error if this file is required:
            // throw new FileNotFoundException("Shared LOCI resource file not found: " + resourcePath);
        }
        return resourcePath.toString();
    }

    /**
     * Loads a YAML file into a Map.
     *
     * @param path Filesystem path to the YAML file.
     * @return Map of YAML data, or empty map on error.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadConfig(String path) {
        Yaml yaml = new Yaml();
        try (InputStream in = new FileInputStream(path)) {
            Object loaded = yaml.load(in);
            if (loaded instanceof Map) {
                return new LinkedHashMap<>((Map<String, Object>) loaded);
            } else {
                logger.error("YAML root is not a map: {}", path);
            }
        } catch (FileNotFoundException e) {
            logger.error("YAML file not found: {}", path, e);
        } catch (Exception e) {
            logger.error("Error parsing YAML: {}", path, e);
        }
        return new LinkedHashMap<>();
    }

    /**
     * Loads external autofocus configuration from autofocus_{microscope}.yml file.
     * This file contains per-objective autofocus parameters separate from the main config.
     *
     * @param configPath Path to the main microscope YAML file
     * @return Map of objective ID -> autofocus parameters, or empty map if file doesn't exist
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> loadAutofocusConfig(String configPath) {
        Map<String, Map<String, Object>> autofocusMap = new LinkedHashMap<>();

        try {
            File configFile = new File(configPath);
            if (!configFile.exists()) {
                logger.debug("Config file not found for autofocus lookup: {}", configPath);
                return autofocusMap;
            }

            // Extract microscope name from config filename (e.g., "config_PPM.yml" -> "PPM")
            String microscopeName = extractMicroscopeName(configFile.getName());

            // Construct autofocus config path in same directory
            File configDir = configFile.getParentFile();
            File autofocusFile = new File(configDir, "autofocus_" + microscopeName + ".yml");

            if (!autofocusFile.exists()) {
                logger.info("No external autofocus config file found: {}", autofocusFile.getAbsolutePath());
                return autofocusMap;
            }

            // Load and parse autofocus YAML
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(Files.newInputStream(autofocusFile.toPath()));

            if (data != null && data.containsKey("autofocus_settings")) {
                List<Map<String, Object>> settings = (List<Map<String, Object>>) data.get("autofocus_settings");

                for (Map<String, Object> entry : settings) {
                    String objective = (String) entry.get("objective");
                    if (objective != null) {
                        // Store all autofocus parameters for this objective
                        Map<String, Object> params = new LinkedHashMap<>();
                        params.put("n_steps", entry.get("n_steps"));
                        params.put("search_range_um", entry.get("search_range_um"));
                        params.put("n_tiles", entry.get("n_tiles"));
                        params.put("interp_strength", entry.get("interp_strength"));
                        params.put("interp_kind", entry.get("interp_kind"));
                        params.put("score_metric", entry.get("score_metric"));

                        autofocusMap.put(objective, params);
                    }
                }

                logger.debug(
                        "Loaded external autofocus settings for {} objectives from: {}",
                        autofocusMap.size(),
                        autofocusFile.getAbsolutePath());
            }

        } catch (Exception e) {
            logger.warn("Error loading external autofocus config", e);
        }

        return autofocusMap;
    }

    /**
     * Loads external imageprocessing configuration from imageprocessing_{microscope}.yml file.
     * This file contains imaging profiles (exposure, gain, white balance) and background correction settings
     * organized by modality -> objective -> detector hierarchy.
     *
     * @param configPath Path to the main microscope YAML file
     * @return Map containing imageprocessing data (imaging_profiles and background_correction), or empty map if file doesn't exist
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadImageprocessingConfig(String configPath) {
        Map<String, Object> imageprocessingMap = new LinkedHashMap<>();

        try {
            File configFile = new File(configPath);
            if (!configFile.exists()) {
                logger.debug("Config file not found for imageprocessing lookup: {}", configPath);
                return imageprocessingMap;
            }

            // Extract microscope name from config filename (e.g., "config_PPM.yml" -> "PPM")
            String microscopeName = extractMicroscopeName(configFile.getName());

            // Construct imageprocessing config path in same directory
            File configDir = configFile.getParentFile();
            File imageprocessingFile = new File(configDir, "imageprocessing_" + microscopeName + ".yml");

            if (!imageprocessingFile.exists()) {
                logger.info("No external imageprocessing config file found: {}", imageprocessingFile.getAbsolutePath());
                return imageprocessingMap;
            }

            // Load and parse imageprocessing YAML
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(Files.newInputStream(imageprocessingFile.toPath()));

            if (data != null) {
                // Store the entire imageprocessing config
                imageprocessingMap.putAll(data);

                logger.info("Loaded external imageprocessing config from: {}", imageprocessingFile.getAbsolutePath());
                if (data.containsKey("imaging_profiles")) {
                    logger.debug("  - Found imaging_profiles section");
                }
                if (data.containsKey("background_correction")) {
                    logger.debug("  - Found background_correction section");
                }
            }

        } catch (Exception e) {
            logger.warn("Error loading external imageprocessing config", e);
        }

        return imageprocessingMap;
    }

    /**
     * Extracts microscope name from config filename.
     * E.g., "config_PPM.yml" -> "PPM", "config_ppm.yml" -> "ppm"
     *
     * @param configFilename The config filename
     * @return Microscope name extracted from filename
     */
    private static String extractMicroscopeName(String configFilename) {
        // Remove extension
        String nameWithoutExt = configFilename.replaceFirst("\\.[^.]+$", "");

        // Remove "config_" prefix if present (case insensitive)
        if (nameWithoutExt.toLowerCase().startsWith("config_")) {
            return nameWithoutExt.substring(7);
        }

        return nameWithoutExt;
    }

    /**
     * Retrieve a deeply nested value from the microscope configuration,
     * following references to resources_LOCI.yml dynamically if needed.
     * <p>
     * If a String value matching "LOCI-..." is encountered during traversal,
     * this method will search all top-level sections of resources_LOCI.yml to find
     * the corresponding entry and continue the lookup there.
     *
     * @param keys Sequence of keys (e.g., "modalities", "bf_10x", "objective", "id").
     * @return The value at the end of the key path, or null if not found.
     */
    @SuppressWarnings("unchecked")
    public Object getConfigItem(String... keys) {
        ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");
        // Capture volatile references once for consistent snapshot
        Map<String, Object> config = this.configData;
        Map<String, Object> resources = this.resourceData;
        Object current = config;

        // Diagnostic: detect empty configData early
        if (config.isEmpty()) {
            logger.error(
                    "configData is EMPTY when looking up {}. Config path: {}",
                    java.util.Arrays.toString(keys),
                    configPath);
        }

        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            // Standard descent into the Map
            if (current instanceof Map<?, ?> map && map.containsKey(key)) {
                current = map.get(key);

                // If this is a LOCI reference and more keys remain, switch to resources and continue
                if (current instanceof String id && id.startsWith("LOCI") && i + 1 < keys.length) {
                    logger.info(res.getString("configManager.switchingToResource"), id, Arrays.toString(keys), i, key);
                    String section = findResourceSectionForID(key, resources, res);
                    if (section == null) {
                        logger.warn(
                                res.getString("configManager.resourceSectionNotFound"), key, id, Arrays.toString(keys));
                        return null;
                    }
                    String normalized = id.replace('-', '_');
                    Object sectionObj = resources.get(section);
                    if (sectionObj instanceof Map<?, ?> secMap && secMap.containsKey(normalized)) {
                        current = ((Map<?, ?>) secMap).get(normalized);
                        logger.info(res.getString("configManager.foundResourceEntry"), section, normalized, current);
                        continue; // proceed with remaining keys
                    } else {
                        logger.warn(
                                res.getString("configManager.resourceEntryNotFound"),
                                normalized,
                                section,
                                Arrays.toString(keys));
                        return null;
                    }
                }
                continue;
            }
            // If at this point current is a Map, attempt descent
            if (current instanceof Map<?, ?> map2 && map2.containsKey(key)) {
                current = map2.get(key);
                continue;
            }
            // Not found -- log full context
            logger.warn(res.getString("configManager.keyNotFound"), key, i, current, Arrays.toString(keys));
            return null;
        }
        logger.debug(res.getString("configManager.lookupSuccess"), Arrays.toString(keys), current);
        return current;
    }

    /**
     * Helper to guess the correct resource section for a given parent field (e.g., "detector").
     * Dynamically searches all top-level keys in resources_LOCI.yml.
     *
     * @param parentField   The key referring to a hardware part ("detector", "objectiveLens", etc.)
     * @param resourceData  The parsed LOCI resource map
     * @param res           The strings ResourceBundle
     * @return Section name in resourceData (e.g., "id_detector"), or null if not found
     */
    private static String findResourceSectionForID(
            String parentField, Map<String, Object> resourceData, ResourceBundle res) {
        for (String section : resourceData.keySet()) {
            if (section.toLowerCase().contains(parentField.toLowerCase())) {
                return section;
            }
        }
        logger.warn(res.getString("configManager.sectionGuessFallback"), parentField, resourceData.keySet());
        // Fallback: just use first section, but warn!
        return resourceData.keySet().stream().findFirst().orElse(null);
    }

    /**
     * Retrieves a String value from the config or resources.
     *
     * @param keys Sequence of keys.
     * @return String value or null.
     */
    public String getString(String... keys) {
        Object v = getConfigItem(keys);
        return (v instanceof String) ? (String) v : null;
    }

    /**
     * Retrieves an Integer value from the config or resources.
     *
     * @param keys Sequence of keys.
     * @return Integer value or null.
     */
    public Integer getInteger(String... keys) {
        Object v = getConfigItem(keys);
        if (v instanceof Number n) return n.intValue();
        try {
            return (v != null) ? Integer.parseInt(v.toString()) : null;
        } catch (NumberFormatException e) {
            logger.warn("Expected int at {} but got {}", String.join("/", keys), v);
            return null;
        }
    }

    /**
     * Retrieves a Double value from the config or resources.
     *
     * @param keys Sequence of keys.
     * @return Double value or null.
     */
    public Double getDouble(String... keys) {
        Object v = getConfigItem(keys);
        if (v instanceof Number n) return n.doubleValue();
        try {
            return (v != null) ? Double.parseDouble(v.toString()) : null;
        } catch (NumberFormatException e) {
            logger.warn("Expected double at {} but got {}", String.join("/", keys), v);
            return null;
        }
    }

    /**
     * Retrieves a Boolean value from the config or resources.
     *
     * @param keys Sequence of keys.
     * @return Boolean value or null.
     */
    public Boolean getBoolean(String... keys) {
        Object v = getConfigItem(keys);
        if (v instanceof Boolean b) return b;
        return v != null && Boolean.parseBoolean(v.toString());
    }

    /**
     * Retrieves a List value from the config or resources.
     *
     * @param keys Sequence of keys.
     * @return List<Object> or null.
     */
    @SuppressWarnings("unchecked")
    public List<Object> getList(String... keys) {
        Object v = getConfigItem(keys);
        return (v instanceof List<?>) ? (List<Object>) v : null;
    }

    /**
     * Retrieves a nested Map section from the config or resources.
     *
     * @param keys Sequence of keys.
     * @return {@code Map<String,Object>} or null.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getSection(String... keys) {
        Object v = getConfigItem(keys);
        return (v instanceof Map<?, ?>) ? (Map<String, Object>) v : null;
    }

    /**
     * Gets a modality-specific configuration section.
     * @param key Top-level modality key (e.g., "PPM")
     * @return Map containing settings, or empty map if not found
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getModalityConfig(String key) {
        Object section = configData.get(key);
        if (section instanceof Map) {
            return (Map<String, Object>) section;
        }
        return new HashMap<>();
    }

    // ========== ACQUISITION PROFILE METHODS ==========

    /**
     * Check if a modality/objective/detector combination is valid based on hardware configuration.
     * With simplified config, all combinations are valid if the objective and detector exist.
     *
     * @param modality The modality name (checked against available modalities)
     * @param objective The objective ID (checked against hardware.objectives)
     * @param detector The detector ID (checked against hardware.detectors)
     * @return true if all components exist in configuration
     */
    public boolean isValidHardwareCombination(String modality, String objective, String detector) {
        // Check modality exists
        Set<String> availableModalities = getAvailableModalities();
        if (!availableModalities.contains(modality)) {
            logger.debug("Modality {} not found in available modalities: {}", modality, availableModalities);
            return false;
        }

        // Check objective exists in hardware
        Set<String> availableObjectives = getAvailableObjectives();
        if (!availableObjectives.contains(objective)) {
            logger.debug("Objective {} not found in hardware objectives: {}", objective, availableObjectives);
            return false;
        }

        // Check detector exists in hardware
        Set<String> availableDetectors = getHardwareDetectors();
        if (!availableDetectors.contains(detector)) {
            logger.debug("Detector {} not found in hardware detectors: {}", detector, availableDetectors);
            return false;
        }

        return true;
    }

    /**
     * Get a setting from the external imageprocessing configuration file.
     * Settings like exposures, gains, and white balance are stored in imageprocessing_{microscope}.yml.
     * Pixel sizes are now in the hardware section of the main config.
     *
     * @param modality The modality name
     * @param objective The objective ID
     * @param detector The detector ID
     * @param settingPath Path to the setting within the profile
     * @return The setting value, or null if not found
     */
    @SuppressWarnings("unchecked")
    public Object getProfileSetting(String modality, String objective, String detector, String... settingPath) {
        // Check external imageprocessing config
        if (imageprocessingData != null && imageprocessingData.containsKey("imaging_profiles")) {
            Map<String, Object> imagingProfiles = (Map<String, Object>) imageprocessingData.get("imaging_profiles");
            if (imagingProfiles != null && imagingProfiles.containsKey(modality)) {
                Map<String, Object> modalityProfiles = (Map<String, Object>) imagingProfiles.get(modality);
                if (modalityProfiles != null && modalityProfiles.containsKey(objective)) {
                    Map<String, Object> objectiveProfiles = (Map<String, Object>) modalityProfiles.get(objective);
                    if (objectiveProfiles != null && objectiveProfiles.containsKey(detector)) {
                        Map<String, Object> detectorProfile = (Map<String, Object>) objectiveProfiles.get(detector);
                        if (detectorProfile != null) {
                            Object value = getNestedValue(detectorProfile, settingPath);
                            if (value != null) {
                                logger.debug(
                                        "Found setting in imaging_profiles: {} for {}/{}/{}",
                                        Arrays.toString(settingPath),
                                        modality,
                                        objective,
                                        detector);
                                return value;
                            }
                        }
                    }
                }
            }
        }

        logger.debug("Setting not found: {} for {}/{}/{}", Arrays.toString(settingPath), modality, objective, detector);
        return null;
    }

    /**
     * Helper method to get nested value from a map using a path.
     *
     * @param map The map to search in
     * @param path The path to the value
     * @return The value at the path, or null if not found
     */
    @SuppressWarnings("unchecked")
    private Object getNestedValue(Map<String, Object> map, String... path) {
        Object current = map;
        for (String key : path) {
            if (current instanceof Map<?, ?>) {
                current = ((Map<?, ?>) current).get(key);
                if (current == null) return null;
            } else {
                return null;
            }
        }
        return current;
    }

    /**
     * Get exposure settings for a specific modality/objective/detector combination.
     *
     * @param modality The modality name
     * @param objective The objective ID
     * @param detector The detector ID
     * @return Map of exposure settings, or null if not found
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getModalityExposures(String modality, String objective, String detector) {
        Object exposures = getProfileSetting(modality, objective, detector, "exposures_ms");
        return (exposures instanceof Map<?, ?>) ? (Map<String, Object>) exposures : null;
    }

    /**
     * Get gain settings for a specific modality/objective/detector combination.
     *
     * @param modality The modality name
     * @param objective The objective ID
     * @param detector The detector ID
     * @return Map of gain settings, single gain value, or null if not found
     */
    public Object getModalityGains(String modality, String objective, String detector) {
        return getProfileSetting(modality, objective, detector, "gains");
    }

    /**
     * Get the final exposures saved by the most recent background collection,
     * if and only if that collection targeted the same modality/objective/detector.
     *
     * <p>The Python server writes these values to
     * {@code calibration_targets.background_exposures} in the imageprocessing YAML
     * after each successful background collection. The adaptive-exposure loop
     * tunes exposure time to hit the user's target intensity (e.g. 30000 counts
     * in 16-bit), so these values represent the camera settings that will
     * produce a well-exposed image under the same illumination conditions.
     *
     * <p>Acquisition should prefer these exposures over ModalityHandler defaults
     * whenever available and matching -- otherwise the tiled acquisition will
     * use a different exposure than the background was collected at, breaking
     * flat-field correction consistency.
     *
     * <p>The YAML structure written by
     * {@code save_background_exposures_to_yaml} is:
     * <pre>
     * calibration_targets:
     *   background_exposures:
     *     last_calibrated: 2026-04-09T15:30:00
     *     modality: Brightfield_10x
     *     objective: LOCI_OBJECTIVE_OLYMPUS_10X_001
     *     detector: HAMAMATSU_DCAM_01
     *     angles:
     *       &lt;angle_name&gt;:
     *         angle_degrees: 0.0
     *         exposure_ms: 12.3
     *         achieved_intensity: 29500
     * </pre>
     *
     * @param modality  modality name to match (e.g. {@code "Brightfield_10x"})
     * @param objective objective ID to match
     * @param detector  detector ID to match
     * @return map of angle degrees to exposure_ms, or {@code null} if no stored
     *         background exposures exist OR they were collected for a different
     *         modality/objective/detector combination
     */
    @SuppressWarnings("unchecked")
    public Map<Double, Double> getBackgroundExposures(String modality, String objective, String detector) {
        if (imageprocessingData == null) {
            return null;
        }
        Object calibTargets = imageprocessingData.get("calibration_targets");
        if (!(calibTargets instanceof Map<?, ?>)) {
            return null;
        }
        Object bgExp = ((Map<String, Object>) calibTargets).get("background_exposures");
        if (!(bgExp instanceof Map<?, ?>)) {
            return null;
        }
        Map<String, Object> bg = (Map<String, Object>) bgExp;

        // Only use these exposures if the stored collection matches the
        // current modality/objective/detector -- background_exposures is a
        // single-entry section that gets overwritten by each new collection,
        // so using a mismatched set would be worse than falling back to
        // defaults.
        Object storedModality = bg.get("modality");
        Object storedObjective = bg.get("objective");
        Object storedDetector = bg.get("detector");
        if (modality != null && storedModality != null && !modality.equals(storedModality.toString())) {
            logger.debug(
                    "Background exposures stored for modality '{}' but requested '{}' -- ignoring",
                    storedModality, modality);
            return null;
        }
        if (objective != null && storedObjective != null && !objective.equals(storedObjective.toString())) {
            logger.debug(
                    "Background exposures stored for objective '{}' but requested '{}' -- ignoring",
                    storedObjective, objective);
            return null;
        }
        if (detector != null && storedDetector != null && !detector.equals(storedDetector.toString())) {
            logger.debug(
                    "Background exposures stored for detector '{}' but requested '{}' -- ignoring",
                    storedDetector, detector);
            return null;
        }

        Object anglesObj = bg.get("angles");
        if (!(anglesObj instanceof Map<?, ?>)) {
            return null;
        }
        Map<String, Object> angles = (Map<String, Object>) anglesObj;
        if (angles.isEmpty()) {
            return null;
        }

        Map<Double, Double> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : angles.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> angleData) {
                Object degObj = angleData.get("angle_degrees");
                Object expObj = angleData.get("exposure_ms");
                if (degObj instanceof Number deg && expObj instanceof Number exp) {
                    result.put(deg.doubleValue(), exp.doubleValue());
                }
            }
        }
        if (result.isEmpty()) {
            return null;
        }
        logger.debug(
                "Loaded {} background exposure(s) from calibration_targets for {}/{}/{}",
                result.size(), modality, objective, detector);
        return result;
    }

    /**
     * Get pixel size for a specific modality/objective/detector combination.
     * Pixel sizes are stored in the hardware.objectives section of the main config.
     *
     * @param objective The objective ID
     * @param detector The detector ID
     * @return Pixel size in microns
     * @throws IllegalArgumentException if pixel size cannot be determined
     */
    public double getPixelSize(String objective, String detector) {
        Double pixelSize = getHardwarePixelSize(objective, detector);

        if (pixelSize != null && pixelSize > 0) {
            logger.debug("Pixel size for {}/{}: {} um", objective, detector, pixelSize);
            return pixelSize;
        }

        logger.error("No valid pixel size found for objective {} with detector {}", objective, detector);
        throw new IllegalArgumentException(String.format(
                "No valid pixel size found for objective '%s' with detector '%s'. "
                        + "Please check hardware configuration.",
                objective, detector));
    }

    /**
     * Get pixel size for a modality by searching through hardware objectives.
     * This is a convenience method for when you only have the modality name.
     * Returns the pixel size for the first available objective/detector combination.
     *
     * @param modalityName The modality name (e.g., "bf_10x", "ppm_20x", "bf_10x_1")
     * @return Pixel size in microns
     * @throws IllegalArgumentException if no pixel size can be determined
     */
    public double getPixelSizeForModality(String modalityName) {
        logger.debug("Finding pixel size for modality: {}", modalityName);

        // Handle indexed modality names (e.g., "bf_10x_1" -> "bf_10x")
        String baseModality = modalityName;
        if (baseModality.matches(".*_\\d+$")) {
            baseModality = baseModality.substring(0, baseModality.lastIndexOf('_'));
        }

        // Get available hardware and try to find a valid pixel size
        List<Map<String, Object>> objectives = getHardwareObjectives();
        Set<String> detectors = getHardwareDetectors();

        if (objectives.isEmpty()) {
            throw new IllegalArgumentException("No objectives defined in hardware configuration");
        }

        if (detectors.isEmpty()) {
            throw new IllegalArgumentException("No detectors defined in hardware configuration");
        }

        // Try each objective/detector combination until we find a valid pixel size
        for (Map<String, Object> objective : objectives) {
            String objectiveId = (String) objective.get("id");
            if (objectiveId == null) continue;

            for (String detectorId : detectors) {
                try {
                    double pixelSize = getPixelSize(objectiveId, detectorId);
                    if (pixelSize > 0) {
                        logger.debug(
                                "Found pixel size {} um for modality {} using {}/{}",
                                pixelSize,
                                modalityName,
                                objectiveId,
                                detectorId);
                        return pixelSize;
                    }
                } catch (IllegalArgumentException e) {
                    // Continue searching if this combination doesn't have pixel size
                    logger.debug("No pixel size for {}/{}: {}", objectiveId, detectorId, e.getMessage());
                }
            }
        }

        throw new IllegalArgumentException(String.format(
                "Cannot determine pixel size for modality '%s'. " + "No valid hardware configuration found.",
                modalityName));
    }

    /**
     * Get autofocus parameters for a specific objective from the external autofocus file.
     * Autofocus settings are stored in autofocus_{microscope}.yml.
     *
     * @param objective The objective ID
     * @return Map of autofocus parameters, or null if not found
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAutofocusParams(String objective) {
        logger.debug("Getting autofocus parameters for objective: {}", objective);

        // Check external autofocus file
        if (autofocusData != null && autofocusData.containsKey(objective)) {
            Map<String, Object> params = autofocusData.get(objective);
            logger.debug("Found autofocus params for {} in external autofocus file", objective);
            return params;
        }

        logger.warn(
                "No autofocus parameters found for objective: {} (check autofocus_{}.yml)",
                objective,
                getString("microscope", "name"));
        return null;
    }

    /**
     * Get a specific autofocus parameter as integer.
     *
     * @param objective The objective ID
     * @param parameter The parameter name
     * @return The parameter value as Integer, or null if not found
     */
    public Integer getAutofocusIntParam(String objective, String parameter) {
        Map<String, Object> params = getAutofocusParams(objective);
        if (params == null) {
            return null;
        }

        Object value = params.get(parameter);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }

        logger.error("Autofocus parameter {} not found for objective {}", parameter, objective);
        return null;
    }

    // ========== UPDATED METHODS FOR NEW NAMING ==========

    /**
     * Get default detector for the microscope.
     *
     * @return Default detector ID, or empty string if not found
     */
    public String getDefaultDetector() {
        String detector = getString("microscope", "default_detector");
        if (detector == null) {
            logger.error("No default detector configured");
            return "";
        }
        return detector;
    }

    /**
     * Get detector dimensions (width or height) from resources.
     *
     * @param detector Detector ID
     * @param dimension "width_px" or "height_px"
     * @return Dimension in pixels, or -1 if not found
     */
    @SuppressWarnings("unchecked")
    public int getDetectorDimension(String detector, String dimension) {
        Map<String, Object> detectorSection = getMergedDetectorSection();
        if (detectorSection != null && detectorSection.containsKey(detector)) {
            Map<String, Object> detectorData = (Map<String, Object>) detectorSection.get(detector);
            if (detectorData != null && detectorData.get(dimension) instanceof Number) {
                return ((Number) detectorData.get(dimension)).intValue();
            }
        }
        logger.warn("Detector {} {} not found", detector, dimension);
        return -1;
    }

    /**
     * Get detector dimensions from resources.
     *
     * @param detector Detector ID
     * @return Array of [width, height] in pixels, or null if not found
     */
    @SuppressWarnings("unchecked")
    public int[] getDetectorDimensions(String detector) {
        logger.debug("Getting dimensions for detector: {}", detector);

        Map<String, Object> detectorSection = getMergedDetectorSection();
        if (detectorSection != null && detectorSection.containsKey(detector)) {
            Map<String, Object> detectorData = (Map<String, Object>) detectorSection.get(detector);

            if (detectorData != null) {
                Integer width = null;
                Integer height = null;

                if (detectorData.get("width_px") instanceof Number) {
                    width = ((Number) detectorData.get("width_px")).intValue();
                }
                if (detectorData.get("height_px") instanceof Number) {
                    height = ((Number) detectorData.get("height_px")).intValue();
                }

                if (width != null && height != null && width > 0 && height > 0) {
                    logger.debug("Detector {} dimensions: {}x{}", detector, width, height);
                    return new int[] {width, height};
                }
            }
        }

        logger.error("Detector dimensions not found for: {}", detector);
        return null;
    }

    /**
     * Calculate field of view for a modality/objective/detector combination.
     *
     * @param modality The modality name
     * @param objective The objective ID
     * @param detector The detector ID
     * @return Array of [width, height] in microns, or null if cannot calculate
     */
    public double[] getModalityFOV(String modality, String objective, String detector) {
        logger.debug("Calculating FOV for modality: {}, objective: {}, detector: {}", modality, objective, detector);

        if (detector == null) {
            detector = getDefaultDetector();
            if (detector.isEmpty()) {
                logger.error("No detector specified and no default detector configured");
                return null;
            }
        }

        double pixelSize = getPixelSize(objective, detector);
        if (pixelSize <= 0) {
            logger.error("Invalid pixel size for {}/{}", objective, detector);
            return null;
        }

        int[] dimensions = getDetectorDimensions(detector);
        if (dimensions == null) {
            logger.error("Cannot calculate FOV - detector dimensions not found");
            return null;
        }

        double width = dimensions[0] * pixelSize;
        double height = dimensions[1] * pixelSize;
        logger.info(
                "FOV for {}/{}/{}: {} x {} um",
                modality,
                objective,
                detector,
                String.format("%.1f", width),
                String.format("%.1f", height));
        return new double[] {width, height};
    }

    /**
     * Calculate field of view, handling indexed modality names and throwing on failure.
     *
     * <p>This is a convenience wrapper around {@link #getModalityFOV} that strips
     * modality index suffixes (e.g., "bf_10x_1" -> "bf_10x") and converts null
     * returns to IOExceptions with descriptive messages.
     *
     * @param modality The modality name (may include index suffix)
     * @param objective The objective ID
     * @param detector The detector ID
     * @return Array of [width, height] in microns
     * @throws IOException if FOV cannot be calculated
     */
    public double[] getCameraFOV(String modality, String objective, String detector) throws IOException {
        // Handle indexed modality names (e.g., "bf_10x_1" -> "bf_10x")
        String baseModality = modality;
        if (baseModality.matches(".*_\\d+$")) {
            baseModality = baseModality.substring(0, baseModality.lastIndexOf('_'));
        }

        double[] fov = getModalityFOV(baseModality, objective, detector);
        if (fov == null) {
            throw new IOException(String.format(
                    "Cannot calculate FOV for modality '%s', objective '%s', detector '%s'",
                    modality, objective, detector));
        }
        return fov;
    }

    /**
     * Get rotation angles configuration for PPM modalities.
     * Returns empty list if none found.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getRotationAngles(String modality) {
        logger.debug("Getting rotation angles for modality: {}", modality);

        // Check modality-specific angles
        List<Object> angles = getList("modalities", modality, "rotation_angles");

        if (angles != null && !angles.isEmpty()) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object angle : angles) {
                if (angle instanceof Map) {
                    result.add((Map<String, Object>) angle);
                }
            }
            logger.debug("Found {} rotation angles for {}", result.size(), modality);
            return result;
        }

        logger.debug("No rotation angles found for {}", modality);
        return new ArrayList<>();
    }

    // ========== EXISTING METHODS THAT REMAIN UNCHANGED ==========

    /**
     * Validates that each of the provided key paths exists in the config or resources.
     *
     * @param requiredPaths Set of String[] representing nested key paths.
     * @return Set of missing paths (empty if all are present).
     */
    public Set<String[]> validateRequiredKeys(Set<String[]> requiredPaths) {
        Set<String[]> missing = new LinkedHashSet<>();
        for (String[] path : requiredPaths) {
            if (getConfigItem(path) == null) missing.add(path);
        }
        if (!missing.isEmpty()) {
            logger.error(
                    "Missing required configuration keys: {}",
                    missing.stream().map(p -> String.join("/", p)).collect(Collectors.toList()));
        }
        return missing;
    }

    /**
     * Container for slide boundary information
     */
    public static class SlideBounds {
        public final int xMin, xMax, yMin, yMax;

        public SlideBounds(int xMin, int xMax, int yMin, int yMax) {
            this.xMin = xMin;
            this.xMax = xMax;
            this.yMin = yMin;
            this.yMax = yMax;
        }

        public int getWidth() {
            return xMax - xMin;
        }

        public int getHeight() {
            return yMax - yMin;
        }

        @Override
        public String toString() {
            return String.format("SlideBounds[x:%d-%d, y:%d-%d]", xMin, xMax, yMin, yMax);
        }
    }

    /**
     * Writes the provided metadata map out as pretty-printed JSON for debugging or record-keeping.
     *
     * @param metadata   Map of properties to serialize.
     * @param outputPath Target JSON file path.
     * @throws IOException On write error.
     */
    public void writeMetadataAsJson(Map<String, Object> metadata, Path outputPath) throws IOException {
        try (Writer w = new FileWriter(outputPath.toFile(), StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(metadata, w);
        }
    }

    /**
     * Get list of available scanners from configuration
     */
    public List<String> getAvailableScanners() {
        List<String> scanners = new ArrayList<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> scannersMap = (Map<String, Object>) configData.get("scanners");
            if (scannersMap != null) {
                scanners.addAll(scannersMap.keySet());
            }
        } catch (Exception e) {
            logger.warn("No scanners section found in configuration");
        }
        return scanners;
    }

    /**
     * Check if Z coordinate is within stage bounds.
     *
     * @param z Z coordinate in microns
     * @return true if Z is within bounds
     */
    public boolean isWithinStageBounds(double z) {
        Double zLow = getDouble("stage", "limits", "z_um", "low");
        Double zHigh = getDouble("stage", "limits", "z_um", "high");

        if (zLow == null || zHigh == null) {
            logger.warn("Stage Z limits not configured properly");
            return true; // Allow movement if not configured
        }

        boolean valid = z >= zLow && z <= zHigh;
        if (!valid) {
            logger.warn("Z position {} outside stage bounds [{}, {}]", z, zLow, zHigh);
        }
        return valid;
    }

    /**
     * Check if XY coordinates are within stage bounds.
     *
     * @param x X coordinate in microns
     * @param y Y coordinate in microns
     * @return true if both X and Y are within bounds
     */
    public boolean isWithinStageBounds(double x, double y) {
        Double xLow = getDouble("stage", "limits", "x_um", "low");
        Double xHigh = getDouble("stage", "limits", "x_um", "high");
        Double yLow = getDouble("stage", "limits", "y_um", "low");
        Double yHigh = getDouble("stage", "limits", "y_um", "high");

        if (xLow == null || xHigh == null || yLow == null || yHigh == null) {
            logger.warn("Stage XY limits not configured properly");
            return true; // Allow movement if not configured
        }

        boolean valid = x >= xLow && x <= xHigh && y >= yLow && y <= yHigh;
        if (!valid) {
            logger.warn("Position ({}, {}) outside stage bounds: X[{}, {}], Y[{}, {}]", x, y, xLow, xHigh, yLow, yHigh);
        }
        return valid;
    }

    /**
     * Check if XYZ coordinates are within stage bounds.
     *
     * @param x X coordinate in microns
     * @param y Y coordinate in microns
     * @param z Z coordinate in microns
     * @return true if all coordinates are within bounds
     */
    public boolean isWithinStageBounds(double x, double y, double z) {
        // Check XY bounds
        boolean xyValid = isWithinStageBounds(x, y);

        // Check Z bounds
        boolean zValid = isWithinStageBounds(z);

        return xyValid && zValid;
    }

    /**
     * Get specific stage limit for more complex checks.
     *
     * @param axis Stage axis ('x', 'y', or 'z')
     * @param limitType Limit type ('low' or 'high')
     * @return Stage limit value in microns
     */
    public double getStageLimit(String axis, String limitType) {
        Double limit = getDouble("stage", "limits", axis + "_um", limitType);

        if (limit == null) {
            String msg = String.format(
                    "Stage %s %s limit not found in configuration. "
                            + "Check that your config file has stage.limits.%s_um.%s defined. "
                            + "The config data may be empty -- verify the config file path in Preferences.",
                    axis, limitType, axis, limitType);
            logger.error(msg);
            throw new IllegalStateException(msg);
        }

        return limit;
    }

    /**
     * Check if a scanner is configured
     */
    public boolean isScannerConfigured(String scannerName) {
        return getAvailableScanners().contains(scannerName);
    }

    /**
     * Get microscope name.
     *
     * @return Microscope name, or "Unknown" if not configured
     */
    public String getMicroscopeName() {
        String name = getString("microscope", "name");
        return name != null ? name : "Unknown";
    }

    /**
     * Get microscope type.
     *
     * @return Microscope type, or "Unknown" if not configured
     */
    public String getMicroscopeType() {
        String type = getString("microscope", "type");
        return type != null ? type : "Unknown";
    }

    /**
     * Get stage component IDs.
     *
     * @return Map with keys "xy", "z", "r" containing stage component IDs
     */
    public Map<String, String> getStageComponents() {
        Map<String, String> components = new HashMap<>();

        String stageId = getString("stage", "stage_id");
        components.put("xy", stageId);
        components.put("z", stageId); // Same stage handles Z for Prior

        // Get rotation stage from modality if available
        String rotationStage = getString("modalities", "ppm", "rotation_stage", "device");
        components.put("r", rotationStage);

        return components;
    }

    /**
     * Get slide dimensions.
     *
     * @param dimension "x" or "y"
     * @return Slide size in microns
     */
    public int getSlideDimension(String dimension) {
        Integer size = getInteger("slide_size_um", dimension);
        if (size == null) {
            logger.warn("Slide {} dimension not configured, using default", dimension);
            return dimension.equals("x") ? 40000 : 20000;
        }
        return size;
    }

    /**
     * Strip a trailing magnification suffix ({@code _10x}, {@code _20X}, etc.) from a
     * modality id to get the family form. Family names may contain underscores
     * (e.g. {@code BF_IF}), so we only strip a terminal {@code _<digits><x|X>} segment.
     */
    private static String modalityFamily(String modality) {
        if (modality == null) {
            return null;
        }
        int idx = modality.lastIndexOf('_');
        if (idx <= 0 || idx >= modality.length() - 2) {
            return modality;
        }
        char last = modality.charAt(modality.length() - 1);
        if (last != 'x' && last != 'X') {
            return modality;
        }
        for (int i = idx + 1; i < modality.length() - 1; i++) {
            if (!Character.isDigit(modality.charAt(i))) {
                return modality;
            }
        }
        return modality.substring(0, idx);
    }

    /**
     * Resolves the background_correction entry for a modality, trying the exact
     * key first and falling back to the family form (e.g. "ppm_20x" -> "ppm",
     * "BF_IF_10x" -> "BF_IF"). Config keys under background_correction use family
     * names without magnification; callers often pass magnification-suffixed variants.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getBackgroundCorrectionEntry(String modality) {
        if (imageprocessingData == null || !imageprocessingData.containsKey("background_correction")) {
            return null;
        }
        Map<String, Object> bgCorrection = (Map<String, Object>) imageprocessingData.get("background_correction");
        if (bgCorrection == null || modality == null) {
            return null;
        }
        if (bgCorrection.containsKey(modality)) {
            return (Map<String, Object>) bgCorrection.get(modality);
        }
        String family = modalityFamily(modality);
        if (!family.equals(modality) && bgCorrection.containsKey(family)) {
            logger.debug("Resolved background_correction modality '{}' -> family '{}'", modality, family);
            return (Map<String, Object>) bgCorrection.get(family);
        }
        return null;
    }

    /**
     * Get background correction folder for a specific modality.
     * Reads from imageprocessing_{microscope}.yml -> background_correction -> modality -> base_folder
     * Falls back to the family key (e.g. "ppm" for "ppm_20x") if the exact modality key is absent.
     * Returns null if not found.
     */
    public String getBackgroundCorrectionFolder(String modality) {
        logger.debug("Getting background correction folder for modality: {}", modality);
        Map<String, Object> modalityBg = getBackgroundCorrectionEntry(modality);
        if (modalityBg != null && modalityBg.containsKey("base_folder")) {
            String folder = modalityBg.get("base_folder").toString();
            logger.debug("Found background folder in imageprocessing config: {}", folder);
            return folder;
        }
        logger.warn("No background correction folder found for {}", modality);
        return null;
    }

    /**
     * Check if background correction is enabled for a modality.
     * Reads from imageprocessing_{microscope}.yml -> background_correction -> modality -> enabled
     * Falls back to the family key (e.g. "ppm" for "ppm_20x") if the exact modality key is absent.
     */
    public boolean isBackgroundCorrectionEnabled(String modality) {
        Map<String, Object> modalityBg = getBackgroundCorrectionEntry(modality);
        if (modalityBg != null && modalityBg.containsKey("enabled")) {
            Boolean enabled = (Boolean) modalityBg.get("enabled");
            logger.debug(
                    "Found background_correction.enabled in imageprocessing config for {}: {}", modality, enabled);
            return enabled != null && enabled;
        }
        return false;
    }

    /**
     * Get background correction method for a modality.
     * Reads from imageprocessing_{microscope}.yml -> background_correction -> modality -> method
     * Falls back to the family key (e.g. "ppm" for "ppm_20x") if the exact modality key is absent.
     * Returns null if not configured.
     */
    public String getBackgroundCorrectionMethod(String modality) {
        Map<String, Object> modalityBg = getBackgroundCorrectionEntry(modality);
        if (modalityBg != null && modalityBg.containsKey("method")) {
            String method = modalityBg.get("method").toString();
            logger.debug(
                    "Found background_correction.method in imageprocessing config for {}: {}", modality, method);
            return method;
        }
        return null;
    }

    /**
     * Get the timestamp of the most recent white balance calibration change.
     *
     * <p>Uses the global {@code wb_last_modified} field at the detector profile level,
     * which is updated by ANY WB operation (per-angle or simple). This means any WB
     * change invalidates all background images regardless of mode.
     *
     * @param wbMode The WB mode protocol name (ignored -- all modes use global timestamp)
     * @param modality The modality name (e.g., "ppm")
     * @param objective The objective ID
     * @param detector The detector ID
     * @return ISO timestamp string, or null if not found
     */
    public String getWbCalibrationTimestamp(String wbMode, String modality, String objective, String detector) {
        if (imageprocessingData == null) {
            return null;
        }

        try {
            // Primary: global wb_last_modified at detector profile level
            // Set by ANY WB calibration (per-angle or simple)
            Object globalTs = getProfileSetting(modality, objective, detector, "wb_last_modified");
            if (globalTs != null) {
                return globalTs.toString();
            }

            // Fallback for configs written before wb_last_modified was added:
            // check mode-specific timestamps
            if ("simple".equals(wbMode)) {
                Object result = getProfileSetting(modality, objective, detector, "simple_wb", "last_calibrated");
                if (result != null) {
                    return result.toString();
                }
            }
        } catch (Exception e) {
            logger.debug("Error reading WB calibration timestamp for mode '{}': {}", wbMode, e.getMessage());
        }

        return null;
    }

    /**
     * Get the Z pixel size for a given objective.
     *
     * <p>Reads {@code pixel_size_z_um} from the objective config in YAML.
     * Returns empty if not configured (2D-only mode).
     *
     * @param objective The objective ID
     * @return Z pixel size in micrometers, or empty if not configured
     */
    public java.util.OptionalDouble getPixelSizeZ(String objective) {
        List<Map<String, Object>> objectives = getHardwareObjectives();
        if (objectives == null || objectives.isEmpty()) {
            return java.util.OptionalDouble.empty();
        }
        for (Map<String, Object> obj : objectives) {
            if (objective.equals(obj.get("id"))) {
                Object zPixelSize = obj.get("pixel_size_z_um");
                if (zPixelSize instanceof Number && ((Number) zPixelSize).doubleValue() > 0) {
                    return java.util.OptionalDouble.of(((Number) zPixelSize).doubleValue());
                }
                return java.util.OptionalDouble.empty();
            }
        }
        return java.util.OptionalDouble.empty();
    }

    /**
     * Get all available modality names.
     */
    public Set<String> getAvailableModalities() {
        Map<String, Object> modalities = getSection("modalities");
        if (modalities != null) {
            logger.debug("Found {} modalities: {}", modalities.size(), modalities.keySet());
            return modalities.keySet();
        }

        logger.warn("No modalities section found in configuration");
        return new HashSet<>();
    }

    /**
     * Check if a modality exists and is valid.
     */
    public boolean isValidModality(String modality) {
        if (modality == null || modality.isEmpty()) {
            return false;
        }

        Map<String, Object> modalityConfig = getSection("modalities", modality);
        return modalityConfig != null && modalityConfig.containsKey("type");
    }

    /**
     * Checks whether any configured modality has a rotation stage.
     * Used to avoid polling rotation angle on microscopes without one.
     *
     * @return true if at least one modality defines a rotation_stage device
     */
    @SuppressWarnings("unchecked")
    public boolean hasRotationStage() {
        Map<String, Object> modalities = getSection("modalities");
        if (modalities == null) {
            return false;
        }
        for (Map.Entry<String, Object> entry : modalities.entrySet()) {
            if (entry.getValue() instanceof Map) {
                Map<String, Object> mod = (Map<String, Object>) entry.getValue();
                Object rotStage = mod.get("rotation_stage");
                if (rotStage instanceof Map) {
                    Object device = ((Map<String, Object>) rotStage).get("device");
                    if (device != null && !device.toString().isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ========== CHANNEL LIBRARY METHODS (multi-channel modalities, e.g. widefield IF) ==========

    /**
     * Returns the channel library defined under {@code modalities.<modality>.channels}
     * as immutable {@link Channel} records.
     *
     * <p>Each channel entry in YAML must provide {@code id} and {@code exposure_ms}, and
     * may provide {@code display_name}, {@code mm_setup_presets} (list of group/preset
     * maps), and {@code device_properties} (list of device/property/value maps).
     *
     * @param modality the modality name as keyed in the {@code modalities} block
     *                 (e.g. {@code "Fluorescence"})
     * @return ordered list of channels, or empty list if none declared
     */
    @SuppressWarnings("unchecked")
    public List<Channel> getModalityChannels(String modality) {
        Map<String, Object> modalityConfig = getSection("modalities", modality);
        if (modalityConfig == null) {
            return List.of();
        }
        Object channelsObj = modalityConfig.get("channels");
        if (!(channelsObj instanceof List<?> channelList)) {
            return List.of();
        }
        List<Channel> result = new ArrayList<>(channelList.size());
        for (Object entry : channelList) {
            if (!(entry instanceof Map<?, ?> channelMap)) {
                continue;
            }
            Channel channel = parseChannel((Map<String, Object>) channelMap, modality);
            if (channel != null) {
                result.add(channel);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Returns the list of channel ids declared under
     * {@code acquisition_profiles.<profile>.channels} (optional filter list).
     * If absent, the caller should use all channels from the modality library.
     */
    @SuppressWarnings("unchecked")
    public List<String> getProfileChannelIds(String profileKey) {
        Map<String, Object> profile = getSection("acquisition_profiles", profileKey);
        if (profile == null) {
            return List.of();
        }
        Object idsObj = profile.get("channels");
        if (!(idsObj instanceof List<?> idList)) {
            return List.of();
        }
        List<String> result = new ArrayList<>(idList.size());
        for (Object id : idList) {
            if (id != null) {
                result.add(id.toString());
            }
        }
        return List.copyOf(result);
    }

    /**
     * Returns per-channel overrides declared under
     * {@code acquisition_profiles.<profile>.channel_overrides} as a map keyed by
     * channel id. Values are the raw sub-maps so callers can pull whichever keys
     * (exposure_ms, etc.) they support.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> getProfileChannelOverrides(String profileKey) {
        Map<String, Object> profile = getSection("acquisition_profiles", profileKey);
        if (profile == null) {
            return Map.of();
        }
        Object overridesObj = profile.get("channel_overrides");
        if (!(overridesObj instanceof Map<?, ?> overridesMap)) {
            return Map.of();
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : overridesMap.entrySet()) {
            if (e.getKey() != null && e.getValue() instanceof Map<?, ?> sub) {
                result.put(e.getKey().toString(), (Map<String, Object>) sub);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Resolves the effective channel list for an acquisition profile.
     *
     * <p>Looks up the profile's modality, pulls the modality-level channel library,
     * filters it by the profile's optional {@code channels:} list (if present), and
     * applies per-channel {@code channel_overrides} (currently {@code exposure_ms}).
     * Returns an empty list if the profile is not channel-based (no library or no
     * effective channels after filtering).
     *
     * @param profileKey acquisition profile key (e.g. {@code "Fluorescence_20x"})
     * @return ordered, resolved channel list ready for the workflow to use
     */
    public List<Channel> getChannelsForProfile(String profileKey) {
        Map<String, Object> profile = getSection("acquisition_profiles", profileKey);
        if (profile == null) {
            return List.of();
        }
        Object modalityObj = profile.get("modality");
        if (modalityObj == null) {
            return List.of();
        }
        String modalityName = modalityObj.toString();
        List<Channel> library = getModalityChannels(modalityName);
        if (library.isEmpty()) {
            return List.of();
        }

        List<String> profileIds = getProfileChannelIds(profileKey);
        Map<String, Map<String, Object>> overrides = getProfileChannelOverrides(profileKey);

        // Filter by profile subset if declared; otherwise use full library order.
        List<Channel> selected;
        if (profileIds.isEmpty()) {
            selected = library;
        } else {
            Map<String, Channel> byId = new LinkedHashMap<>();
            for (Channel c : library) {
                byId.put(c.id(), c);
            }
            selected = new ArrayList<>(profileIds.size());
            for (String id : profileIds) {
                Channel c = byId.get(id);
                if (c == null) {
                    logger.warn(
                            "Profile '{}' references unknown channel id '{}' (not in modalities.{}.channels); skipping",
                            profileKey,
                            id,
                            modalityName);
                    continue;
                }
                selected.add(c);
            }
        }

        if (overrides.isEmpty()) {
            return List.copyOf(selected);
        }

        // Apply overrides (exposure_ms and device_properties). Each override yields a new Channel.
        List<Channel> result = new ArrayList<>(selected.size());
        for (Channel c : selected) {
            Map<String, Object> override = overrides.get(c.id());
            if (override == null) {
                result.add(c);
                continue;
            }
            double exposure = c.defaultExposureMs();
            Object expObj = override.get("exposure_ms");
            if (expObj instanceof Number n) {
                exposure = n.doubleValue();
            }
            List<PropertyWrite> mergedProperties = mergeDevicePropertyOverrides(
                    c.properties(), override.get("device_properties"), c.id(), profileKey);
            result.add(new Channel(
                    c.id(), c.displayName(), exposure, c.presets(), mergedProperties, c.settleMs()));
        }
        return List.copyOf(result);
    }

    /**
     * Merges profile-level {@code device_properties} overrides into a channel's
     * library-level device property list. Matching semantics (by (device, property)):
     *
     * <ul>
     *   <li>If the override matches an existing library entry, replace its value
     *       in place (preserving list order).</li>
     *   <li>If the override does not match any existing entry, append it at the
     *       end of the list.</li>
     * </ul>
     *
     * <p>This lets a profile like {@code BF_IF_10x} tune the BF channel's
     * transmitted-lamp intensity without having to redeclare the entire channel.
     *
     * @param libraryProperties immutable list of PropertyWrites from the modality channel library
     * @param overrideObj       raw YAML object from {@code channel_overrides.<id>.device_properties}
     *                          (expected to be a List of Maps); may be null / absent
     * @param channelIdForLog   channel id, for warning messages
     * @param profileKeyForLog  profile key, for warning messages
     * @return merged list (a new mutable ArrayList if overrides were applied, otherwise the original)
     */
    @SuppressWarnings("unchecked")
    private List<PropertyWrite> mergeDevicePropertyOverrides(
            List<PropertyWrite> libraryProperties,
            Object overrideObj,
            String channelIdForLog,
            String profileKeyForLog) {
        if (!(overrideObj instanceof List<?> overrideList) || overrideList.isEmpty()) {
            return libraryProperties;
        }
        List<PropertyWrite> merged = new ArrayList<>(libraryProperties);
        for (Object entry : overrideList) {
            if (!(entry instanceof Map<?, ?> pm)) {
                continue;
            }
            Object device = pm.get("device");
            Object property = pm.get("property");
            Object value = pm.get("value");
            if (device == null || property == null || value == null) {
                logger.warn(
                        "Profile '{}' channel '{}' device_properties override has missing "
                                + "device/property/value; skipping: {}",
                        profileKeyForLog,
                        channelIdForLog,
                        pm);
                continue;
            }
            String deviceStr = device.toString();
            String propertyStr = property.toString();
            String valueStr = value.toString();
            int matchIdx = -1;
            for (int i = 0; i < merged.size(); i++) {
                PropertyWrite existing = merged.get(i);
                if (existing.device().equals(deviceStr) && existing.property().equals(propertyStr)) {
                    matchIdx = i;
                    break;
                }
            }
            if (matchIdx >= 0) {
                merged.set(matchIdx, new PropertyWrite(deviceStr, propertyStr, valueStr));
            } else {
                merged.add(new PropertyWrite(deviceStr, propertyStr, valueStr));
            }
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private Channel parseChannel(Map<String, Object> channelMap, String modalityForLog) {
        Object idObj = channelMap.get("id");
        if (idObj == null || idObj.toString().isBlank()) {
            logger.warn("Skipping channel in modalities.{}.channels with missing 'id'", modalityForLog);
            return null;
        }
        String id = idObj.toString();
        String displayName = channelMap.containsKey("display_name")
                ? String.valueOf(channelMap.get("display_name"))
                : id;

        Object exposureObj = channelMap.get("exposure_ms");
        if (!(exposureObj instanceof Number)) {
            logger.warn(
                    "Skipping channel '{}' in modalities.{}.channels: missing or non-numeric 'exposure_ms'",
                    id,
                    modalityForLog);
            return null;
        }
        double exposureMs = ((Number) exposureObj).doubleValue();

        List<PresetRef> presets = new ArrayList<>();
        Object presetsObj = channelMap.get("mm_setup_presets");
        if (presetsObj instanceof List<?> presetList) {
            for (Object p : presetList) {
                if (p instanceof Map<?, ?> pm) {
                    Object group = pm.get("group");
                    Object preset = pm.get("preset");
                    if (group != null && preset != null) {
                        presets.add(new PresetRef(group.toString(), preset.toString()));
                    }
                }
            }
        }

        List<PropertyWrite> properties = new ArrayList<>();
        Object propsObj = channelMap.get("device_properties");
        if (propsObj instanceof List<?> propList) {
            for (Object p : propList) {
                if (p instanceof Map<?, ?> pm) {
                    Object device = pm.get("device");
                    Object property = pm.get("property");
                    Object value = pm.get("value");
                    if (device != null && property != null && value != null) {
                        properties.add(
                                new PropertyWrite(device.toString(), property.toString(), value.toString()));
                    }
                }
            }
        }

        double settleMs = 0;
        Object settleObj = channelMap.get("settle_ms");
        if (settleObj instanceof Number) {
            settleMs = ((Number) settleObj).doubleValue();
        }

        // Optional pointer into `device_properties` marking the primary intensity knob.
        // When present, the channel-picker UI exposes that entry as a per-channel
        // intensity spinner. Missing or malformed => no intensity control (null).
        PropertyRef intensityProperty = null;
        Object intensityObj = channelMap.get("intensity_property");
        if (intensityObj instanceof Map<?, ?> intensityMap) {
            Object dev = intensityMap.get("device");
            Object prop = intensityMap.get("property");
            if (dev != null && prop != null) {
                try {
                    intensityProperty = new PropertyRef(dev.toString(), prop.toString());
                } catch (IllegalArgumentException e) {
                    logger.warn(
                            "Ignoring malformed intensity_property on channel '{}' in modalities.{}: {}",
                            id,
                            modalityForLog,
                            e.getMessage());
                }
            }
        }

        try {
            return new Channel(id, displayName, exposureMs, presets, properties, intensityProperty, settleMs);
        } catch (IllegalArgumentException e) {
            logger.warn(
                    "Skipping invalid channel '{}' in modalities.{}.channels: {}",
                    id,
                    modalityForLog,
                    e.getMessage());
            return null;
        }
    }

    /**
     * Get slide dimensions.
     * Returns null array if not configured.
     */
    public int[] getSlideSize() {
        Integer width = getInteger("slide_size_um", "x");
        Integer height = getInteger("slide_size_um", "y");

        if (width == null || height == null) {
            logger.error("Slide size not configured");
            return null;
        }

        return new int[] {width, height};
    }

    /**
     * Load scanner-specific configuration.
     * This loads a separate YAML file for scanner configurations.
     */
    public Map<String, Object> loadScannerConfig(String scannerName) {
        logger.debug("Loading scanner config for: {}", scannerName);

        if (this.configPath == null) {
            logger.error("Cannot determine scanner config path - configPath not set");
            return new HashMap<>();
        }

        java.io.File configDir = new java.io.File(this.configPath).getParentFile();
        java.io.File scannerFile = new java.io.File(configDir, "config_" + scannerName + ".yml");

        if (!scannerFile.exists()) {
            logger.error("Scanner config not found: {}", scannerFile.getAbsolutePath());
            return new HashMap<>();
        }

        return MinorFunctions.loadYamlFile(scannerFile.getAbsolutePath());
    }

    /**
     * Get macro pixel size for a scanner.
     * Returns -1 if not found.
     */
    public double getScannerMacroPixelSize(String scannerName) {
        Map<String, Object> scannerConfig = loadScannerConfig(scannerName);
        Double pixelSize = MinorFunctions.getYamlDouble(scannerConfig, "macro", "pixel_size_um");

        if (pixelSize == null || pixelSize <= 0) {
            logger.error("No valid macro pixel size for scanner: {}", scannerName);
            return -1;
        }

        return pixelSize;
    }

    /**
     * Get scanner crop bounds if cropping is required.
     * Returns null if no cropping needed or bounds incomplete.
     */
    public Map<String, Integer> getScannerCropBounds(String scannerName) {
        Map<String, Object> scannerConfig = loadScannerConfig(scannerName);

        Boolean requiresCropping = MinorFunctions.getYamlBoolean(scannerConfig, "macro", "requires_cropping");
        if (requiresCropping == null || !requiresCropping) {
            return null;
        }

        Map<String, Integer> bounds = new HashMap<>();
        bounds.put("x_min", MinorFunctions.getYamlInteger(scannerConfig, "macro", "slide_bounds", "x_min_px"));
        bounds.put("x_max", MinorFunctions.getYamlInteger(scannerConfig, "macro", "slide_bounds", "x_max_px"));
        bounds.put("y_min", MinorFunctions.getYamlInteger(scannerConfig, "macro", "slide_bounds", "y_min_px"));
        bounds.put("y_max", MinorFunctions.getYamlInteger(scannerConfig, "macro", "slide_bounds", "y_max_px"));

        if (bounds.values().stream().anyMatch(Objects::isNull)) {
            logger.error("Incomplete crop bounds for scanner: {} - some values are null", scannerName);
            return null;
        }

        return bounds;
    }

    /**
     * Get all stage limits as a convenient structure.
     */
    public Map<String, Double> getAllStageLimits() {
        Map<String, Double> limits = new HashMap<>();

        for (String axis : Arrays.asList("x", "y", "z")) {
            for (String type : Arrays.asList("low", "high")) {
                String key = axis + "_" + type;
                Double limit = getDouble("stage", "limits", axis + "_um", type);
                if (limit == null) {
                    logger.error("Stage limit {} not found", key);
                    return null;
                }
                limits.put(key, limit);
            }
        }

        logger.debug("Retrieved all stage limits: {}", limits);
        return limits;
    }

    /**
     * Validate that all required configuration sections exist.
     * Returns list of missing sections.
     *
     * <p>SYNC: The required keys checked here must match the structure defined in
     * {@link qupath.ext.qpsc.ui.setupwizard.ConfigSchema}. When adding new required
     * keys, also update ConfigSchema, the Setup Wizard steps, and the bundled
     * YAML templates in resources/qupath/ext/qpsc/templates/.
     */
    public List<String> validateConfiguration() {
        List<String> errors = new ArrayList<>();

        // Check detailed required configuration keys
        Set<String[]> required = Set.of(
                // Basic microscope info
                new String[] {"microscope", "name"},
                new String[] {"microscope", "type"},

                // Stage configuration - used for bounds checking
                new String[] {"stage", "limits", "x_um", "low"},
                new String[] {"stage", "limits", "x_um", "high"},
                new String[] {"stage", "limits", "y_um", "low"},
                new String[] {"stage", "limits", "y_um", "high"},
                new String[] {"stage", "limits", "z_um", "low"},
                new String[] {"stage", "limits", "z_um", "high"},

                // Core sections
                new String[] {"modalities"},
                new String[] {"hardware"},
                new String[] {"slide_size_um"});

        // First check the basic required keys
        var missing = validateRequiredKeys(required);
        if (!missing.isEmpty()) {
            errors.addAll(missing.stream().map(arr -> String.join(".", arr)).toList());
        }

        // Check modalities section
        Set<String> availableModalities = getAvailableModalities();
        if (availableModalities.isEmpty()) {
            errors.add("No modalities defined in configuration");
        }

        // Check hardware section
        errors.addAll(validateHardwareSection(availableModalities));

        // Check PPM-specific requirements if PPM modality is present
        if (availableModalities.contains("ppm")) {
            errors.addAll(validatePPMConfiguration());
        }

        if (!errors.isEmpty()) {
            logger.error("Configuration validation failed. Errors: {}", errors);
        } else {
            logger.info("Configuration validation passed");
        }

        return errors;
    }

    /**
     * Validates the hardware section for completeness and consistency.
     */
    private List<String> validateHardwareSection(Set<String> availableModalities) {
        List<String> errors = new ArrayList<>();

        // Check hardware.objectives
        List<Map<String, Object>> objectives = getHardwareObjectives();
        if (objectives.isEmpty()) {
            errors.add("No objectives defined in hardware configuration");
            return errors;
        }

        // Check hardware.detectors
        Set<String> detectors = getHardwareDetectors();
        if (detectors.isEmpty()) {
            errors.add("No detectors defined in hardware configuration");
            return errors;
        }

        // Validate each objective has required fields
        for (Map<String, Object> objective : objectives) {
            String objectiveId = (String) objective.get("id");
            if (objectiveId == null || objectiveId.isBlank()) {
                errors.add("Objective missing required 'id' field");
                continue;
            }

            // Check pixel_size_xy_um exists
            Object pixelSizeMap = objective.get("pixel_size_xy_um");
            if (!(pixelSizeMap instanceof Map)) {
                errors.add(String.format("Objective %s missing pixel_size_xy_um mapping", objectiveId));
                continue;
            }

            // Check pixel sizes exist for all detectors
            @SuppressWarnings("unchecked")
            Map<String, Object> pixelSizes = (Map<String, Object>) pixelSizeMap;
            for (String detectorId : detectors) {
                Object pixelSize = pixelSizes.get(detectorId);
                if (!(pixelSize instanceof Number) || ((Number) pixelSize).doubleValue() <= 0) {
                    errors.add(String.format(
                            "Objective %s missing valid pixel size for detector %s", objectiveId, detectorId));
                }
            }
        }

        // Validate each detector exists in resources
        Map<String, Object> detectorSection = getMergedDetectorSection();
        for (String detectorId : detectors) {
            if (detectorSection == null || !detectorSection.containsKey(detectorId)) {
                errors.add(
                        String.format("Detector %s not found in config id_detector or resources_LOCI.yml", detectorId));
                continue;
            }

            // Validate detector dimensions
            @SuppressWarnings("unchecked")
            Map<String, Object> detectorData = (Map<String, Object>) detectorSection.get(detectorId);
            Integer width = detectorData != null && detectorData.get("width_px") instanceof Number
                    ? ((Number) detectorData.get("width_px")).intValue()
                    : null;
            Integer height = detectorData != null && detectorData.get("height_px") instanceof Number
                    ? ((Number) detectorData.get("height_px")).intValue()
                    : null;

            if (width == null || height == null || width <= 0 || height <= 0) {
                errors.add(String.format(
                        "Detector %s has invalid dimensions: width=%s, height=%s", detectorId, width, height));
            }
        }

        // Validate exposure settings exist for at least one modality/objective/detector combo
        boolean hasValidExposures = false;
        for (String modality : availableModalities) {
            for (Map<String, Object> objective : objectives) {
                String objectiveId = (String) objective.get("id");
                if (objectiveId == null) continue;

                for (String detectorId : detectors) {
                    Map<String, Object> exposures = getModalityExposures(modality, objectiveId, detectorId);
                    if (exposures != null && !exposures.isEmpty()) {
                        hasValidExposures = true;
                        logger.debug("Valid exposure settings found for {}/{}/{}", modality, objectiveId, detectorId);
                        break;
                    }
                }
                if (hasValidExposures) break;
            }
            if (hasValidExposures) break;
        }

        if (!hasValidExposures) {
            errors.add(
                    "No valid exposure settings found in imageprocessing config for any modality/objective/detector combination");
        }

        // Validate autofocus settings exist for each objective (from external autofocus file)
        for (Map<String, Object> objective : objectives) {
            String objectiveId = (String) objective.get("id");
            if (objectiveId == null) continue;

            Map<String, Object> autofocus = getAutofocusParams(objectiveId);
            if (autofocus != null) {
                Integer nSteps = getAutofocusIntParam(objectiveId, "n_steps");
                Integer searchRange = getAutofocusIntParam(objectiveId, "search_range_um");

                if (nSteps == null || nSteps <= 0 || searchRange == null || searchRange <= 0) {
                    logger.warn(
                            "Incomplete autofocus configuration for objective {}: n_steps={}, search_range_um={}",
                            objectiveId,
                            nSteps,
                            searchRange);
                    // Not an error - autofocus is optional
                }
            }
        }

        return errors;
    }

    /**
     * Validates PPM-specific configuration requirements.
     */
    private List<String> validatePPMConfiguration() {
        List<String> errors = new ArrayList<>();

        // Check rotation stage
        String rotationDevice = getString("modalities", "ppm", "rotation_stage", "device");
        if (rotationDevice == null) {
            errors.add("PPM modality missing rotation stage configuration");
        }

        // Check rotation angles
        List<Object> rotationAngles = getList("modalities", "ppm", "rotation_angles");
        if (rotationAngles == null || rotationAngles.isEmpty()) {
            errors.add("PPM modality missing rotation angles");
        }

        return errors;
    }

    /**
     * Gets the camera_type field from the detector's resource entry.
     * Known values today: "jai" (3-CCD prism RGB), "generic" (typically mono
     * sCMOS or debayered Bayer), "laser_scanning" (PMT).
     *
     * @param detectorId The detector identifier (e.g., "LOCI_DETECTOR_JAI_001")
     * @return the lowercase camera_type string, or null if unset/unknown
     */
    @SuppressWarnings("unchecked")
    public String getDetectorCameraType(String detectorId) {
        if (detectorId == null || detectorId.isEmpty()) {
            return null;
        }
        Map<String, Object> detectorSection = getMergedDetectorSection();
        if (detectorSection == null || !detectorSection.containsKey(detectorId)) {
            return null;
        }
        Map<String, Object> detectorData = (Map<String, Object>) detectorSection.get(detectorId);
        if (detectorData == null) {
            return null;
        }
        Object type = detectorData.get("camera_type");
        return (type instanceof String) ? ((String) type).toLowerCase() : null;
    }

    /**
     * Determines if a detector requires debayering based on configuration or detector properties.
     *
     * @param detectorId The detector identifier (e.g., "LOCI_DETECTOR_JAI_001")
     * @return true if debayering is required, false otherwise
     */
    public boolean detectorRequiresDebayering(String detectorId) {
        logger.debug("Checking debayering requirement for detector: {}", detectorId);

        Map<String, Object> detectorSection = getMergedDetectorSection();
        if (detectorSection == null || !detectorSection.containsKey(detectorId)) {
            logger.warn("Detector {} not found in resources, defaulting to requires debayering", detectorId);
            return true;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> detectorData = (Map<String, Object>) detectorSection.get(detectorId);

        // First check for explicit configuration
        Object debayerFlag = detectorData.get("requires_debayering");
        if (debayerFlag instanceof Boolean) {
            boolean requires = (Boolean) debayerFlag;
            logger.debug("Detector {} has explicit debayering flag: {}", detectorId, requires);
            return requires;
        }

        // Default to requiring debayering for standard Bayer pattern sensors
        logger.debug(
                "Detector {} defaulting to no deBayering as there is no indication for this in the config file",
                detectorId);
        return false;
    }

    // --- Per-detector optical flip ---

    /**
     * Gets a boolean property from a detector's resource configuration.
     *
     * @param detectorId  The detector identifier (e.g., "LOCI_DETECTOR_JAI_001")
     * @param property    The property name (e.g., "flip_x", "requires_debayering")
     * @param defaultValue Value to return if property is missing
     * @return The property value, or defaultValue if not found
     */
    @SuppressWarnings("unchecked")
    private boolean getDetectorBooleanProperty(String detectorId, String property, boolean defaultValue) {
        Map<String, Object> detectorSection = getMergedDetectorSection();
        if (detectorSection == null || !detectorSection.containsKey(detectorId)) {
            return defaultValue;
        }
        Map<String, Object> detectorData = (Map<String, Object>) detectorSection.get(detectorId);
        Object flag = detectorData != null ? detectorData.get(property) : null;
        return (flag instanceof Boolean) ? (Boolean) flag : defaultValue;
    }

    /**
     * Gets the optical flip-X setting for a detector.
     * <p>
     * Optical flip is a property of the light path between the sample and this
     * specific detector. Different detectors on the same microscope may have
     * different flip states (e.g., a brightfield camera may be flipped relative
     * to a laser scanning detector because they use different optical paths).
     * <p>
     * This is NOT stage axis inversion (stageInvertedX/Y), which is a separate
     * hardware property.
     *
     * @param detectorId The detector identifier (e.g., "LOCI_DETECTOR_JAI_001")
     * @return true if detector requires X flip, false otherwise (default false)
     */
    public boolean getDetectorFlipX(String detectorId) {
        return getDetectorBooleanProperty(detectorId, "flip_x", false);
    }

    /**
     * Gets the optical flip-Y setting for a detector.
     *
     * @param detectorId The detector identifier (e.g., "LOCI_DETECTOR_JAI_001")
     * @return true if detector requires Y flip, false otherwise (default false)
     * @see #getDetectorFlipX(String)
     */
    public boolean getDetectorFlipY(String detectorId) {
        return getDetectorBooleanProperty(detectorId, "flip_y", false);
    }

    /**
     * Check if the specified detector is a JAI 3-CCD camera.
     * JAI cameras support per-channel exposure control for white balance calibration.
     *
     * @param detectorId The detector identifier (e.g., "LOCI_DETECTOR_JAI_001")
     * @return true if this is a JAI camera, false otherwise
     */
    public boolean isJAICamera(String detectorId) {
        if (detectorId == null || detectorId.isEmpty()) {
            return false;
        }

        // Check by ID convention first (fast path)
        if (detectorId.toUpperCase().contains("JAI")) {
            logger.debug("Detector {} identified as JAI camera by ID convention", detectorId);
            return true;
        }

        // Alternative: check manufacturer field in resources_LOCI.yml
        try {
            Map<String, Object> detectorSection = getMergedDetectorSection();
            if (detectorSection != null && detectorSection.containsKey(detectorId)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> detectorData = (Map<String, Object>) detectorSection.get(detectorId);
                if (detectorData != null) {
                    String manufacturer = (String) detectorData.get("manufacturer");
                    if ("JAI".equalsIgnoreCase(manufacturer)) {
                        logger.debug("Detector {} identified as JAI camera by manufacturer field", detectorId);
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not check manufacturer for detector: {}", detectorId);
        }

        return false;
    }

    // ========== HARDWARE SECTION ACCESS METHODS ==========

    /**
     * Get the list of available objectives from the hardware section.
     *
     * @return List of objective configuration maps, or empty list if not found
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getHardwareObjectives() {
        List<Object> objectives = getList("hardware", "objectives");
        if (objectives == null) {
            logger.warn("No objectives found in hardware section");
            return Collections.emptyList();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object obj : objectives) {
            if (obj instanceof Map) {
                result.add((Map<String, Object>) obj);
            }
        }
        return result;
    }

    /**
     * Get the list of available detector IDs from the hardware section.
     *
     * @return Set of detector IDs, or empty set if not found
     */
    public Set<String> getHardwareDetectors() {
        List<Object> detectors = getList("hardware", "detectors");
        if (detectors == null) {
            logger.warn("No detectors found in hardware section");
            return Collections.emptySet();
        }

        Set<String> result = new HashSet<>();
        for (Object obj : detectors) {
            if (obj instanceof String) {
                result.add((String) obj);
            }
        }
        return result;
    }

    /**
     * Get pixel size for a specific objective/detector combination from the hardware section.
     *
     * @param objectiveId The objective ID
     * @param detectorId The detector ID
     * @return Pixel size in microns, or null if not found
     */
    @SuppressWarnings("unchecked")
    public Double getHardwarePixelSize(String objectiveId, String detectorId) {
        List<Map<String, Object>> objectives = getHardwareObjectives();

        for (Map<String, Object> objective : objectives) {
            String id = (String) objective.get("id");
            if (objectiveId.equals(id)) {
                Object pixelSizeMap = objective.get("pixel_size_xy_um");
                if (pixelSizeMap instanceof Map) {
                    Object pixelSize = ((Map<String, Object>) pixelSizeMap).get(detectorId);
                    if (pixelSize instanceof Number) {
                        return ((Number) pixelSize).doubleValue();
                    }
                }
            }
        }

        logger.debug(
                "No pixel size found in hardware section for objective {} with detector {}", objectiveId, detectorId);
        return null;
    }

    // ========== METHODS FOR UI DROPDOWNS ==========

    /**
     * Get available objectives on this microscope.
     * With the simplified hardware configuration, all objectives are available for all modalities.
     *
     * @return Set of objective IDs available on this microscope
     */
    public Set<String> getAvailableObjectives() {
        logger.debug("Finding available objectives");

        Set<String> objectives = new HashSet<>();
        List<Map<String, Object>> hardwareObjectives = getHardwareObjectives();

        if (hardwareObjectives.isEmpty()) {
            logger.warn("No objectives found in hardware configuration");
            return objectives;
        }

        // With simplified hardware config, all objectives are available for all modalities
        for (Map<String, Object> objectiveConfig : hardwareObjectives) {
            String id = (String) objectiveConfig.get("id");
            if (id != null) {
                objectives.add(id);
            }
        }

        logger.debug("Found {} objectives: {}", objectives.size(), objectives);
        return objectives;
    }

    /**
     * Get available detectors on this microscope.
     * With the simplified hardware configuration, all detectors are available for all
     * modality+objective combinations.
     *
     * @return Set of detector IDs available on this microscope
     */
    public Set<String> getAvailableDetectors() {
        logger.debug("Finding available detectors");

        // With simplified hardware config, all detectors are available for all combinations
        Set<String> detectors = getHardwareDetectors();

        logger.debug("Found {} detectors: {}", detectors.size(), detectors);
        return detectors;
    }
    /**
     * Check if white balance is enabled for a specific modality/objective/detector combination.
     *
     * @param modality The modality name
     * @param objective The objective ID
     * @param detector The detector ID
     * @return true if white balance is enabled, false otherwise
     */
    public boolean isWhiteBalanceEnabled(String modality, String objective, String detector) {
        Object wbSetting = getProfileSetting(modality, objective, detector, "white_balance", "enabled");

        if (wbSetting instanceof Boolean) {
            return (Boolean) wbSetting;
        }

        // Default to true if not specified
        logger.debug(
                "No white balance setting found for {}/{}/{}, defaulting to enabled", modality, objective, detector);
        return true;
    }

    /**
     * Get white balance gains for a specific modality/objective/detector combination.
     *
     * @param modality The modality name
     * @param objective The objective ID
     * @param detector The detector ID
     * @return Map of angle to RGB gains, or null if not found
     */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Double>> getWhiteBalanceGains(String modality, String objective, String detector) {
        Object wbGains = getProfileSetting(modality, objective, detector, "white_balance", "gains");

        if (wbGains instanceof Map<?, ?>) {
            return (Map<String, Map<String, Double>>) wbGains;
        }

        return null;
    }
    /**
     * Get friendly names for objectives from the resources file.
     *
     * @param objectiveIds Set of objective IDs to get names for
     * @return Map of objective ID to friendly name
     */
    public Map<String, String> getObjectiveFriendlyNames(Set<String> objectiveIds) {
        Map<String, String> friendlyNames = new HashMap<>();
        Map<String, Object> objectiveSection = getResourceSection("id_objective_lens");

        if (objectiveSection != null) {
            for (String objectiveId : objectiveIds) {
                if (objectiveSection.containsKey(objectiveId)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> objData = (Map<String, Object>) objectiveSection.get(objectiveId);
                    String name = (String) objData.get("name");
                    if (name != null) {
                        friendlyNames.put(objectiveId, name);
                    } else {
                        friendlyNames.put(objectiveId, objectiveId); // fallback
                    }
                }
            }
        }

        return friendlyNames;
    }

    /**
     * Get friendly names for detectors from the resources file.
     *
     * @param detectorIds Set of detector IDs to get names for
     * @return Map of detector ID to friendly name
     */
    public Map<String, String> getDetectorFriendlyNames(Set<String> detectorIds) {
        Map<String, String> friendlyNames = new HashMap<>();
        Map<String, Object> detectorSection = getMergedDetectorSection();

        if (detectorSection != null) {
            for (String detectorId : detectorIds) {
                if (detectorSection.containsKey(detectorId)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> detData = (Map<String, Object>) detectorSection.get(detectorId);
                    String name = (String) detData.get("name");
                    if (name != null) {
                        friendlyNames.put(detectorId, name);
                    } else {
                        friendlyNames.put(detectorId, detectorId); // fallback
                    }
                }
            }
        }

        return friendlyNames;
    }

    /**
     * Validate that a modality+objective+detector combination exists in the configuration.
     *
     * @param modalityName The base modality name
     * @param objectiveId The objective ID
     * @param detectorId The detector ID
     * @return true if this combination is valid (all components exist in config)
     */
    public boolean isValidModalityObjectiveDetectorCombination(
            String modalityName, String objectiveId, String detectorId) {
        return isValidHardwareCombination(modalityName, objectiveId, detectorId);
    }

    /**
     * Returns the filesystem path to the currently loaded microscope YAML config.
     *
     * @return the config path string
     */
    public String getConfigPath() {
        return configPath;
    }

    /**
     * Returns the autofocus strategy bound to a given modality in the v2
     * {@code autofocus_<scope>.yml} schema, or {@code null} if the YAML is v1,
     * the file is missing, or the modality has no binding. Used by the
     * acquisition dialog's Advanced panel to pre-select the AF strategy
     * dropdown to whatever the YAML says for the selected modality.
     *
     * <p>Modality lookup uses the same longest-prefix-wins, case-insensitive
     * matching as the Python-side v2 loader, so {@code Fluorescence_10x} maps
     * to the {@code fluorescence} binding (or {@code fl} if that prefix is
     * the longer match).
     *
     * @param modality the modality key (profile name or base modality name)
     * @return strategy name from the binding (e.g. {@code sparse_signal}),
     *         or {@code null} when unavailable
     */
    @SuppressWarnings("unchecked")
    public String getAutofocusStrategyForModality(String modality) {
        if (modality == null || modality.isBlank() || configPath == null) return null;
        File configFile = new File(configPath);
        if (!configFile.exists()) return null;
        File configDir = configFile.getParentFile();
        String microscopeName = extractMicroscopeName(configFile.getName());
        File autofocusFile = new File(configDir, "autofocus_" + microscopeName + ".yml");
        if (!autofocusFile.exists()) return null;
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(Files.newInputStream(autofocusFile.toPath()));
            if (data == null) return null;
            Object schemaVersionObj = data.get("schema_version");
            int schemaVersion = (schemaVersionObj instanceof Number)
                    ? ((Number) schemaVersionObj).intValue()
                    : 1;
            if (schemaVersion < 2) return null;
            Object modalitiesObj = data.get("modalities");
            if (!(modalitiesObj instanceof Map)) return null;
            Map<String, Object> modalityBindings = (Map<String, Object>) modalitiesObj;
            String modalityLower = modality.toLowerCase();
            String bestMatchKey = null;
            int bestLen = 0;
            for (String key : modalityBindings.keySet()) {
                String keyLower = key.toLowerCase();
                if (modalityLower.startsWith(keyLower) && keyLower.length() > bestLen) {
                    bestMatchKey = key;
                    bestLen = keyLower.length();
                }
            }
            if (bestMatchKey == null) return null;
            Object bindingObj = modalityBindings.get(bestMatchKey);
            if (!(bindingObj instanceof Map)) return null;
            Map<String, Object> binding = (Map<String, Object>) bindingObj;
            Object strategy = binding.get("strategy");
            return strategy == null ? null : strategy.toString();
        } catch (Exception e) {
            logger.debug("Could not read AF strategy binding for modality '{}': {}", modality, e.getMessage());
            return null;
        }
    }

    /**
     * Copies all loaded microscope configuration files into a
     * {@code microscope_configurations/} subdirectory of the given project directory.
     * <p>
     * Files copied (if they exist):
     * <ul>
     *   <li>Main microscope config (e.g. config_PPM.yml)</li>
     *   <li>Shared LOCI resources (resources_LOCI.yml)</li>
     *   <li>External autofocus config (autofocus_{microscope}.yml)</li>
     *   <li>External imageprocessing config (imageprocessing_{microscope}.yml)</li>
     * </ul>
     *
     * @param projectDir the QuPath project directory (parent of .qpproj file)
     */
    public void copyConfigsToProject(Path projectDir) {
        Path destDir = projectDir.resolve("microscope_configurations");
        try {
            Files.createDirectories(destDir);
        } catch (IOException e) {
            logger.warn("Failed to create microscope_configurations directory: {}", e.getMessage());
            return;
        }

        File configFile = new File(configPath);
        if (!configFile.exists()) {
            logger.warn("Config file does not exist, cannot copy: {}", configPath);
            return;
        }

        // Collect all config files that should be copied
        List<File> filesToCopy = new ArrayList<>();
        filesToCopy.add(configFile);

        // Shared LOCI resources
        String resPath = computeResourcePath(configPath);
        File resFile = new File(resPath);
        if (resFile.exists()) {
            filesToCopy.add(resFile);
        }

        // External autofocus and imageprocessing configs
        String microscopeName = extractMicroscopeName(configFile.getName());
        File configDir = configFile.getParentFile();
        File autofocusFile = new File(configDir, "autofocus_" + microscopeName + ".yml");
        if (autofocusFile.exists()) {
            filesToCopy.add(autofocusFile);
        }
        File imgprocFile = new File(configDir, "imageprocessing_" + microscopeName + ".yml");
        if (imgprocFile.exists()) {
            filesToCopy.add(imgprocFile);
        }

        // Copy each file
        int copied = 0;
        for (File src : filesToCopy) {
            try {
                Path dest = destDir.resolve(src.getName());
                Files.copy(src.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
                copied++;
            } catch (IOException e) {
                logger.warn("Failed to copy config file {}: {}", src.getName(), e.getMessage());
            }
        }

        logger.info("Copied {} microscope config files to {}", copied, destDir);
    }
}
