package qupath.ext.qpsc.modality;

import java.util.List;

/**
 * Generic, vendor-agnostic description of a single imaging channel for
 * multi-channel modalities such as widefield immunofluorescence.
 *
 * <p>A channel is a self-contained acquisition step: a name, a default
 * exposure, and two ordered lists describing the hardware state that must be
 * in effect when the image is snapped. The acquisition workflow applies the
 * {@link PresetRef}s first (via {@code core.setConfig}), then the
 * {@link PropertyWrite}s (via {@code core.setProperty}), then sets the
 * exposure, then snaps.
 *
 * <p>All hardware is described in terms of Micro-Manager primitives, so the
 * QPSC base layer never needs to know about specific LED controllers, filter
 * wheels, light paths, or shutters. Any instrument whose illumination can be
 * configured through ConfigGroup presets and/or device property writes is
 * supported by exactly the same code path.
 *
 * @param id                 short stable identifier used in filenames and CLI flags (e.g. {@code "DAPI"})
 * @param displayName        human-readable label shown in the UI (e.g. {@code "DAPI (385 nm)"})
 * @param defaultExposureMs  default exposure in milliseconds if the user provides no override
 * @param presets            ConfigGroup presets to apply for this channel, in order. May be empty
 * @param properties         device property writes to apply for this channel, in order. May be empty
 * @param settleMs           optional dumb-sleep fallback (ms) after preset/property application.
 *                           Use for hardware whose isBusy() reports complete too early
 *                           (filter wheels, reflector turrets). 0 to skip
 */
public record Channel(
        String id,
        String displayName,
        double defaultExposureMs,
        List<PresetRef> presets,
        List<PropertyWrite> properties,
        double settleMs) {

    public Channel {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Channel id must be non-blank");
        }
        if (displayName == null || displayName.isBlank()) {
            displayName = id;
        }
        if (defaultExposureMs <= 0 || !Double.isFinite(defaultExposureMs)) {
            throw new IllegalArgumentException(
                    "Channel defaultExposureMs must be positive and finite, got: " + defaultExposureMs);
        }
        if (settleMs < 0 || !Double.isFinite(settleMs)) {
            settleMs = 0;
        }
        presets = presets == null ? List.of() : List.copyOf(presets);
        properties = properties == null ? List.of() : List.copyOf(properties);
    }

    /**
     * Convenience constructor omitting settleMs (defaults to 0).
     */
    public Channel(
            String id,
            String displayName,
            double defaultExposureMs,
            List<PresetRef> presets,
            List<PropertyWrite> properties) {
        this(id, displayName, defaultExposureMs, presets, properties, 0);
    }

    @Override
    public String toString() {
        return id + "(" + defaultExposureMs + "ms)";
    }
}
