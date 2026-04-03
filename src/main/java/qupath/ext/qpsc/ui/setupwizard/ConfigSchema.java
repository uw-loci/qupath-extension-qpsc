package qupath.ext.qpsc.ui.setupwizard;

import java.util.*;

/**
 * Central definition of the expected YAML configuration structure.
 *
 * <p><b>SYNC MECHANISM:</b> This class is the single source of truth for config structure.
 * When the YAML config format changes (new required keys, renamed sections, etc.):
 * <ol>
 *   <li>Update the key paths and field definitions here</li>
 *   <li>Bump {@link #SCHEMA_VERSION}</li>
 *   <li>Update the corresponding wizard step</li>
 *   <li>Update the bundled templates in {@code resources/qupath/ext/qpsc/templates/}</li>
 *   <li>Run {@code ConfigSchemaTest} to verify everything is in sync</li>
 * </ol>
 *
 * <p>Referenced by:
 * <ul>
 *   <li>{@link ConfigFileWriter} - generates YAML matching this schema</li>
 *   <li>{@link SetupWizardDialog} - wizard steps collect data for each section</li>
 *   <li>{@code MicroscopeConfigManager.validateConfiguration()} - validates against same keys
 *       (keep in sync manually; see SYNC comments in that method)</li>
 * </ul>
 */
public final class ConfigSchema {

    private ConfigSchema() {}

    /**
     * Schema version. Bump this when the config structure changes.
     * The wizard writes this into generated configs as a comment for traceability.
     * Tests verify that templates match this version.
     */
    public static final int SCHEMA_VERSION = 2;

    // ======================== MAIN CONFIG (config_<name>.yml) ========================

    /** Required top-level keys in the main config file. */
    public static final String[][] REQUIRED_MAIN_KEYS = {
        {"microscope", "name"},
        {"microscope", "type"},
        {"modalities"},
        {"hardware"},
        {"slide_size_um"},
        {"stage", "limits", "x_um", "low"},
        {"stage", "limits", "x_um", "high"},
        {"stage", "limits", "y_um", "low"},
        {"stage", "limits", "y_um", "high"},
        {"stage", "limits", "z_um", "low"},
        {"stage", "limits", "z_um", "high"},
    };

    /** Hardware sub-keys required per objective. */
    public static final String[] OBJECTIVE_REQUIRED = {"id", "pixel_size_xy_um"};

    /** Supported modality types with their required sub-keys. */
    public static final Map<String, String[][]> MODALITY_REQUIRED_KEYS;

    static {
        Map<String, String[][]> m = new LinkedHashMap<>();
        m.put("brightfield", new String[][] {{"type"}});
        m.put("ppm", new String[][] {{"type"}, {"rotation_stage", "device"}, {"rotation_angles"}});
        m.put("fluorescence", new String[][] {{"type"}});
        m.put("shg", new String[][] {{"type"}, {"laser", "device"}, {"pmt", "device"}, {"pockels_cell", "device"}});
        MODALITY_REQUIRED_KEYS = Collections.unmodifiableMap(m);
    }

    /** Known microscope types for the wizard dropdown. */
    public static final List<String> MICROSCOPE_TYPES = List.of(
            "UprightBrightfield",
            "InvertedBrightfield",
            "UprightFluorescence",
            "InvertedFluorescence",
            "Multimodal",
            "Confocal",
            "Multiphoton");

    /** Known modality types for the wizard. */
    public static final List<String> MODALITY_TYPES =
            List.of("brightfield", "polarized", "fluorescence", "multiphoton");

    // ======================== AUTOFOCUS CONFIG ========================

    /** Required keys per objective in autofocus config. */
    public static final String[] AUTOFOCUS_REQUIRED_PER_OBJECTIVE = {"objective", "n_steps", "search_range_um"};

    /** Default autofocus parameters for a new objective (conservative values). */
    public static Map<String, Object> getDefaultAutofocusParams(String objectiveId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("objective", objectiveId);
        // SAFETY: calibrated=false prevents acquisition from proceeding until
        // the user has verified these values are safe for their hardware.
        // An incorrect search_range_um can crash the objective into the sample.
        params.put("calibrated", false);
        params.put("n_steps", 35);
        params.put("search_range_um", 50.0);
        params.put("n_tiles", 5);
        params.put("interp_strength", 100);
        params.put("interp_kind", "quadratic");
        params.put("score_metric", "laplacian_variance");
        params.put("texture_threshold", 0.010);
        params.put("tissue_area_threshold", 0.2);
        params.put("sweep_range_um", 10.0);
        params.put("sweep_n_steps", 5);
        return params;
    }

    // ======================== IMAGING PROFILES CONFIG ========================

    /** Default exposure (ms) for a new modality/objective/detector combo. */
    public static Map<String, Object> getDefaultImagingProfile() {
        Map<String, Object> profile = new LinkedHashMap<>();
        Map<String, Object> wb = new LinkedHashMap<>();
        wb.put("enabled", false);
        profile.put("white_balance", wb);
        Map<String, Object> exposures = new LinkedHashMap<>();
        exposures.put("single", 50);
        profile.put("exposures_ms", exposures);
        profile.put("gains", 1.0);
        return profile;
    }

    /**
     * Default imaging profile for laser scanning microscopy (SHG, multiphoton).
     * Uses acquisition_settings instead of exposures_ms because LSM controls
     * are fundamentally different from area cameras (dwell time, PMT gain, averaging).
     */
    public static Map<String, Object> getDefaultLSMImagingProfile() {
        Map<String, Object> profile = new LinkedHashMap<>();
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("pmt_gain", 0.5);
        settings.put("pixel_dwell_time_us", 4.0);
        settings.put("averaging", 2);
        settings.put("scan_mode", "unidirectional");
        settings.put("laser_power_percent", 30);
        settings.put("zoom_factor", 1.0);
        Map<String, Object> resolution = new LinkedHashMap<>();
        resolution.put("width", 512);
        resolution.put("height", 512);
        settings.put("scan_resolution_px", resolution);
        profile.put("acquisition_settings", settings);
        return profile;
    }

    // ======================== RESOURCES (resources_LOCI.yml) ========================

    /** Section names in resources_LOCI.yml. */
    public static final String RESOURCES_STAGES = "id_stage";

    public static final String RESOURCES_DETECTORS = "id_detector";
    public static final String RESOURCES_OBJECTIVES = "id_objective_lens";

    /** Required keys per detector in resources. */
    public static final String[] DETECTOR_RESOURCE_REQUIRED = {"width_px", "height_px"};

    /** Required keys per objective in resources. */
    public static final String[] OBJECTIVE_RESOURCE_REQUIRED = {"name", "magnification"};

    // ======================== VALIDATION HELPERS ========================

    /**
     * Check whether a nested map contains a value at the given key path.
     *
     * @param config the root map
     * @param keys   the key path (e.g., "microscope", "name")
     * @return true if a non-null value exists at the path
     */
    @SuppressWarnings("unchecked")
    public static boolean hasKey(Map<String, Object> config, String... keys) {
        Object current = config;
        for (String key : keys) {
            if (!(current instanceof Map)) return false;
            current = ((Map<String, Object>) current).get(key);
            if (current == null) return false;
        }
        return true;
    }

    /**
     * Validate a config map against the required main config keys.
     *
     * @param config the loaded YAML config map
     * @return list of missing key paths (empty if all present)
     */
    public static List<String> validateMainConfig(Map<String, Object> config) {
        List<String> missing = new ArrayList<>();
        for (String[] keyPath : REQUIRED_MAIN_KEYS) {
            if (!hasKey(config, keyPath)) {
                missing.add(String.join(".", keyPath));
            }
        }
        return missing;
    }
}
