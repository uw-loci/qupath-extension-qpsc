package qupath.ext.qpsc.modality;

/**
 * Reference to a Micro-Manager ConfigGroup preset (a named device state applied
 * via {@code core.setConfig(group, preset)}).
 *
 * <p>Used by {@link Channel} and acquisition profile setup steps to describe
 * hardware state in a vendor-agnostic way. Any device that exposes a named
 * preset (filter turrets, light paths, shutters, channel selectors, laser
 * combiners) can be driven through this primitive without the QPSC base layer
 * needing to know which vendor or device is behind it.
 *
 * @param group  the Micro-Manager ConfigGroup name (e.g. {@code "Filter Turret"})
 * @param preset the preset name within that group (e.g. {@code "Single photon LED-DA FI TR Cy5-B"})
 */
public record PresetRef(String group, String preset) {

    public PresetRef {
        if (group == null || group.isBlank()) {
            throw new IllegalArgumentException("PresetRef group must be non-blank");
        }
        if (preset == null || preset.isBlank()) {
            throw new IllegalArgumentException("PresetRef preset must be non-blank");
        }
    }

    @Override
    public String toString() {
        return group + "=" + preset;
    }
}
