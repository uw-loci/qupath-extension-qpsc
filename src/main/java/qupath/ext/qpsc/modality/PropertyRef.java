package qupath.ext.qpsc.modality;

/**
 * Pointer to a Micro-Manager device property, without a value.
 *
 * <p>Used by {@link Channel#intensityProperty()} to mark which entry in the
 * channel's {@code device_properties} list is the "primary intensity knob"
 * so a generic UI can expose it as a per-channel spinner without the QPSC
 * base layer knowing which specific hardware is behind the channel.
 *
 * <p>On instruments where a channel is driven by a multi-LED controller
 * ({@code DLED.Intensity-385nm}), this points at the per-wavelength property;
 * on instruments that use a transmitted lamp (brightfield channel in a
 * BF+IF modality), it points at {@code DiaLamp.Intensity} instead.
 *
 * @param device   the Micro-Manager device label (e.g. {@code "DLED"})
 * @param property the property name on that device (e.g. {@code "Intensity-385nm"})
 */
public record PropertyRef(String device, String property) {

    public PropertyRef {
        if (device == null || device.isBlank()) {
            throw new IllegalArgumentException("PropertyRef device must be non-blank");
        }
        if (property == null || property.isBlank()) {
            throw new IllegalArgumentException("PropertyRef property must be non-blank");
        }
    }

    @Override
    public String toString() {
        return device + "." + property;
    }
}
