package qupath.ext.qpsc.ui.setupwizard;

import java.io.InputStream;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * Reads the bundled resources_LOCI.yml catalog to populate wizard dropdowns
 * with known hardware (objectives, detectors, stages).
 */
public final class ResourceCatalog {

    private static final Logger logger = LoggerFactory.getLogger(ResourceCatalog.class);

    private final Map<String, Map<String, Object>> stages;
    private final Map<String, Map<String, Object>> detectors;
    private final Map<String, Map<String, Object>> objectives;

    private ResourceCatalog(
            Map<String, Map<String, Object>> stages,
            Map<String, Map<String, Object>> detectors,
            Map<String, Map<String, Object>> objectives) {
        this.stages = stages;
        this.detectors = detectors;
        this.objectives = objectives;
    }

    /**
     * Load the catalog from the bundled resources_LOCI.yml.
     *
     * @return the catalog, or an empty catalog if loading fails
     */
    @SuppressWarnings("unchecked")
    public static ResourceCatalog load() {
        try (InputStream is =
                ResourceCatalog.class.getResourceAsStream("/qupath/ext/qpsc/templates/resources_LOCI.yml")) {
            if (is == null) {
                logger.warn("Bundled resources_LOCI.yml not found, returning empty catalog");
                return empty();
            }
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(is);
            if (root == null) {
                return empty();
            }

            Map<String, Map<String, Object>> stages = extractSection(root, ConfigSchema.RESOURCES_STAGES);
            Map<String, Map<String, Object>> detectors = extractSection(root, ConfigSchema.RESOURCES_DETECTORS);
            Map<String, Map<String, Object>> objectives = extractSection(root, ConfigSchema.RESOURCES_OBJECTIVES);

            logger.info(
                    "Loaded resource catalog: {} stages, {} detectors, {} objectives",
                    stages.size(),
                    detectors.size(),
                    objectives.size());
            return new ResourceCatalog(stages, detectors, objectives);
        } catch (Exception e) {
            logger.error("Failed to load resource catalog", e);
            return empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> extractSection(Map<String, Object> root, String key) {
        Object section = root.get(key);
        if (section instanceof Map) {
            Map<String, Map<String, Object>> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) section).entrySet()) {
                if (entry.getValue() instanceof Map) {
                    result.put(entry.getKey(), (Map<String, Object>) entry.getValue());
                }
            }
            return result;
        }
        return new LinkedHashMap<>();
    }

    private static ResourceCatalog empty() {
        return new ResourceCatalog(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    /** Get all known stage entries. Keys are LOCI IDs. */
    public Map<String, Map<String, Object>> getStages() {
        return Collections.unmodifiableMap(stages);
    }

    /** Get all known detector entries. Keys are LOCI IDs. */
    public Map<String, Map<String, Object>> getDetectors() {
        return Collections.unmodifiableMap(detectors);
    }

    /** Get all known objective entries. Keys are LOCI IDs. */
    public Map<String, Map<String, Object>> getObjectives() {
        return Collections.unmodifiableMap(objectives);
    }

    /**
     * Get a human-readable label for a catalog entry.
     *
     * @param id   the LOCI ID
     * @param info the entry map
     * @return formatted label like "10x Olympus (LOCI_OBJECTIVE_OLYMPUS_10X_001)"
     */
    public static String formatLabel(String id, Map<String, Object> info) {
        Object name = info.get("name");
        if (name != null && !name.toString().isEmpty()) {
            return name + " (" + id + ")";
        }
        return id;
    }

    /**
     * Get a double value from an entry map, with a default.
     */
    public static double getDouble(Map<String, Object> info, String key, double defaultVal) {
        Object val = info.get(key);
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        return defaultVal;
    }

    /**
     * Get an integer value from an entry map, with a default.
     */
    public static int getInt(Map<String, Object> info, String key, int defaultVal) {
        Object val = info.get(key);
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        return defaultVal;
    }

    /**
     * Get a string value from an entry map, with a default.
     */
    public static String getString(Map<String, Object> info, String key, String defaultVal) {
        Object val = info.get(key);
        if (val != null) {
            return val.toString();
        }
        return defaultVal;
    }
}
