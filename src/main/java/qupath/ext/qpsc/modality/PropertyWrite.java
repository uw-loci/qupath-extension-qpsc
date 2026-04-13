package qupath.ext.qpsc.modality;

/**
 * Direct Micro-Manager device property write, executed via
 * {@code core.setProperty(device, property, value)}.
 *
 * <p>Used by {@link Channel} definitions to describe fine-grained hardware
 * state that is not expressed through a ConfigGroup preset -- for example,
 * setting individual LED wavelength intensities on a multi-wavelength LED
 * controller where each wavelength is exposed as a separate property.
 *
 * <p>{@code value} is stored as a String so any Micro-Manager property type
 * (int, double, string, enum) round-trips through YAML and the socket
 * protocol without coercion. The target device is responsible for parsing.
 *
 * @param device   the Micro-Manager device label (e.g. {@code "DLED"})
 * @param property the property name on that device (e.g. {@code "Intensity-385nm"})
 * @param value    the property value as a string (e.g. {@code "25"}, {@code "Open"}, {@code "1.5"})
 */
public record PropertyWrite(String device, String property, String value) {

    public PropertyWrite {
        if (device == null || device.isBlank()) {
            throw new IllegalArgumentException("PropertyWrite device must be non-blank");
        }
        if (property == null || property.isBlank()) {
            throw new IllegalArgumentException("PropertyWrite property must be non-blank");
        }
        if (value == null) {
            throw new IllegalArgumentException("PropertyWrite value must be non-null");
        }
    }

    @Override
    public String toString() {
        return device + "." + property + "=" + value;
    }
}
