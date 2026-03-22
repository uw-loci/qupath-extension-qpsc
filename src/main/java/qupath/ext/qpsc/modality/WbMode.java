package qupath.ext.qpsc.modality;

/**
 * White balance modes supported by the QPSC acquisition system.
 *
 * <p>Each mode has a display name (shown in UI combo boxes) and a protocol name
 * (sent over the socket protocol and stored in YAML configuration files).
 *
 * <p>This enum is the single source of truth for WB mode names, replacing the
 * duplicated switch expressions previously in BackgroundCollectionController,
 * UnifiedAcquisitionController, and ExistingImageAcquisitionController.
 *
 * @author Mike Nelson
 * @since 4.1
 */
public enum WbMode {
    OFF("Off", "off", false),
    CAMERA_AWB("Camera AWB", "camera_awb", true),
    SIMPLE("Simple (90deg)", "simple", true),
    PER_ANGLE("Per-angle (PPM)", "per_angle", true);

    private final String displayName;
    private final String protocolName;
    private final boolean requiresBackgrounds;

    WbMode(String displayName, String protocolName, boolean requiresBackgrounds) {
        this.displayName = displayName;
        this.protocolName = protocolName;
        this.requiresBackgrounds = requiresBackgrounds;
    }

    /** Name shown in UI combo boxes (e.g., "Simple (90deg)"). */
    public String getDisplayName() {
        return displayName;
    }

    /** Name used in socket protocol and YAML files (e.g., "simple"). */
    public String getProtocolName() {
        return protocolName;
    }

    /** Whether this mode requires matching background images for acquisition. */
    public boolean requiresBackgrounds() {
        return requiresBackgrounds;
    }

    /**
     * Look up a WbMode by its UI display name.
     *
     * @param displayName the display name (e.g., "Per-angle (PPM)")
     * @return the matching WbMode
     * @throws IllegalArgumentException if no match found
     */
    public static WbMode fromDisplayName(String displayName) {
        if (displayName == null) {
            return PER_ANGLE; // default
        }
        for (WbMode mode : values()) {
            if (mode.displayName.equals(displayName)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown WB mode display name: " + displayName);
    }

    /**
     * Look up a WbMode by its protocol/YAML name.
     *
     * @param protocolName the protocol name (e.g., "per_angle")
     * @return the matching WbMode
     * @throws IllegalArgumentException if no match found
     */
    public static WbMode fromProtocolName(String protocolName) {
        if (protocolName == null) {
            return PER_ANGLE; // default
        }
        for (WbMode mode : values()) {
            if (mode.protocolName.equals(protocolName)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown WB mode protocol name: " + protocolName);
    }
}
