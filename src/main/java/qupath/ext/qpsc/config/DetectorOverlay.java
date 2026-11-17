package qupath.ext.qpsc.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves a microscope config for the camera in use.
 *
 * <p>A config that serves more than one camera keeps the values that differ by camera in
 * {@code per_detector} blocks next to the shared ones, for example
 * <pre>
 * reconstruction:
 *   black_level: 51
 *   per_detector:
 *     LCPS_DETECTOR_FLIR_ORYX_001: {black_level: 99}
 * </pre>
 * {@link #apply} merges the active camera's block into the map that holds it, everywhere in
 * the tree: scalars are replaced, nested maps merged by these same rules, and a
 * {@code device_properties} list is merged by (device, property), an entry replacing the one
 * with the same pair and new pairs appended. The {@code per_detector} block itself stays, so a
 * writer can still find it. With no camera, or one the block does not name, the shared values
 * stand.
 *
 * <p>The server applies the same rules in {@code microscope_control.config.per_detector}; the
 * two must stay identical, since each side reads the config through its own copy.
 */
public final class DetectorOverlay {

    /** The key of a per-camera block. */
    public static final String KEY = "per_detector";

    private DetectorOverlay() {}

    /**
     * A copy of {@code config} with {@code detectorId}'s per-camera values merged in. The input
     * is not changed.
     *
     * @param config the config as read from disk
     * @param detectorId the active camera, or null for the shared values only
     * @return the resolved config
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> apply(Map<String, Object> config, String detectorId) {
        if (config == null) {
            return null;
        }
        return (Map<String, Object>) resolve(config, detectorId);
    }

    @SuppressWarnings("unchecked")
    private static Object resolve(Object node, String detectorId) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                copy.put(String.valueOf(e.getKey()), deepCopy(e.getValue()));
            }
            if (detectorId != null && copy.get(KEY) instanceof Map<?, ?> blocks) {
                Object own = blocks.get(detectorId);
                if (own instanceof Map<?, ?> overrides) {
                    merge(copy, (Map<String, Object>) deepCopy(overrides));
                }
            }
            for (Map.Entry<String, Object> e : copy.entrySet()) {
                if (!KEY.equals(e.getKey())) {
                    e.setValue(resolve(e.getValue(), detectorId));
                }
            }
            return copy;
        }
        if (node instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(resolve(item, detectorId));
            }
            return copy;
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    private static void merge(Map<String, Object> target, Map<String, Object> overrides) {
        for (Map.Entry<String, Object> e : overrides.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            Object existing = target.get(key);
            if ("device_properties".equals(key) && existing instanceof List<?> base && value instanceof List<?> over) {
                target.put(key, mergeDeviceProperties(base, over));
            } else if (existing instanceof Map<?, ?> base && value instanceof Map<?, ?> over) {
                Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) base);
                merge(merged, (Map<String, Object>) over);
                target.put(key, merged);
            } else {
                target.put(key, value);
            }
        }
    }

    private static List<Object> mergeDeviceProperties(List<?> base, List<?> overrides) {
        List<Object> merged = new ArrayList<>(base);
        for (Object o : overrides) {
            int at = indexOfPair(merged, o);
            if (at >= 0) {
                merged.set(at, o);
            } else {
                merged.add(o);
            }
        }
        return merged;
    }

    private static int indexOfPair(List<Object> list, Object entry) {
        if (!(entry instanceof Map<?, ?> e)) {
            return -1;
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) instanceof Map<?, ?> m
                    && Objects.equals(m.get("device"), e.get("device"))
                    && Objects.equals(m.get("property"), e.get("property"))) {
                return i;
            }
        }
        return -1;
    }

    private static Object deepCopy(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                copy.put(String.valueOf(e.getKey()), deepCopy(e.getValue()));
            }
            return copy;
        }
        if (node instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(deepCopy(item));
            }
            return copy;
        }
        return node;
    }
}
