package qupath.ext.qpsc.config;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/** Reads a YAML file into nested maps and looks values up by key path. */
public final class YamlMaps {

    private static final Logger logger = LoggerFactory.getLogger(YamlMaps.class);

    private YamlMaps() {}

    /**
     * Loads a YAML file directly into a Map structure.
     * This bypasses the MicroscopeConfigManager singleton to avoid caching issues.
     *
     * @param yamlPath Path to the YAML file
     * @return Map containing the YAML data, or empty map if loading fails
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadYamlFile(String yamlPath) {
        Yaml yaml = new Yaml();
        try (InputStream in = new FileInputStream(yamlPath)) {
            Object loaded = yaml.load(in);
            if (loaded instanceof Map) {
                return (Map<String, Object>) loaded;
            }
        } catch (Exception e) {
            logger.error("Error loading YAML file: {}", yamlPath, e);
        }
        return new HashMap<>();
    }

    /**
     * Gets a nested value from a YAML map structure.
     *
     * @param yamlData The loaded YAML data
     * @param keys Path to the value (e.g., "macro", "pixel_size_um")
     * @return The value at the specified path, or null if not found
     */
    @SuppressWarnings("unchecked")
    public static Object getYamlValue(Map<String, Object> yamlData, String... keys) {
        Object current = yamlData;

        for (String key : keys) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(key);
                if (current == null) {
                    return null;
                }
            } else {
                return null;
            }
        }

        return current;
    }

    /**
     * Gets a Double value from a YAML map structure.
     *
     * @param yamlData The loaded YAML data
     * @param keys Path to the value
     * @return The Double value, or null if not found or not a number
     */
    public static Double getYamlDouble(Map<String, Object> yamlData, String... keys) {
        Object value = getYamlValue(yamlData, keys);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return null;
    }

    /**
     * Gets a Boolean value from a YAML map structure.
     *
     * @param yamlData The loaded YAML data
     * @param keys Path to the value
     * @return The Boolean value, or null if not found
     */
    public static Boolean getYamlBoolean(Map<String, Object> yamlData, String... keys) {
        Object value = getYamlValue(yamlData, keys);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return null;
    }

    /**
     * Gets an Integer value from a YAML map structure.
     *
     * @param yamlData The loaded YAML data
     * @param keys Path to the value
     * @return The Integer value, or null if not found or not a number
     */
    public static Integer getYamlInteger(Map<String, Object> yamlData, String... keys) {
        Object value = getYamlValue(yamlData, keys);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }
}
