package qupath.ext.qpsc.service;

/**
 * Output granularity for OME-TIFF acquisitions produced by the single-point
 * acquisition refactor (Z-stack + time-lapse unification).
 *
 * <p>Maps to the three user-selectable output layouts described in the plan:
 * <ul>
 *   <li>{@link #OME_SINGLE} - one OME-TIFF containing all (T, Z, C, Y, X) frames
 *       for the acquisition. Best for short acquisitions that comfortably fit
 *       in a single file.</li>
 *   <li>{@link #OME_PER_T} - one OME-TIFF per timepoint, each containing
 *       (Z, C, Y, X). Default for fluorescence and brightfield acquisitions and
 *       the safe choice for long T runs. Also the default within each per-angle
 *       folder when PPM chooses its per-angle folder layout.</li>
 *   <li>{@link #OME_PER_CHANNEL} - one folder per channel (or per angle); each
 *       folder contains OME-TIFFs using the per-timepoint or single layout.</li>
 * </ul>
 *
 * <p>The PPM per-angle-folder layout is selected automatically by the PPM
 * modality handler and is not a user-facing enum value - PPM acquisitions
 * simply use {@link #OME_PER_T} within each per-angle folder.
 *
 * <p>The {@link #toWireValue()} string is the stable form used in the socket
 * payload between the Java extension and the Python microscope server and is
 * not locale-dependent.
 *
 * @author Mike Nelson
 * @since v0.4
 */
public enum OutputFormat {
    /** One OME-TIFF for the whole acquisition: (T, Z, C, Y, X). */
    OME_SINGLE("ome-single"),

    /** One OME-TIFF per timepoint: (Z, C, Y, X) per file. */
    OME_PER_T("ome-per-t"),

    /** One folder per channel (or angle); each folder contains OME-TIFF files. */
    OME_PER_CHANNEL("ome-per-channel");

    private final String wireValue;

    OutputFormat(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * Stable string value used in the socket payload between QPSC (Java) and
     * the Python microscope server. This value is part of the socket protocol
     * contract and must not change without coordinator sign-off.
     *
     * @return the wire-protocol identifier (e.g. {@code "ome-per-t"})
     */
    public String toWireValue() {
        return wireValue;
    }

    /**
     * Reverse lookup of {@link #toWireValue()}.
     *
     * @param wireValue the wire-protocol identifier received from the socket payload
     *                  or read from a persisted preference
     * @return the matching {@link OutputFormat}
     * @throws IllegalArgumentException if the input does not match any known wire value
     */
    public static OutputFormat fromWireValue(String wireValue) {
        if (wireValue == null) {
            throw new IllegalArgumentException("OutputFormat wire value must not be null");
        }
        for (OutputFormat fmt : values()) {
            if (fmt.wireValue.equals(wireValue)) {
                return fmt;
            }
        }
        throw new IllegalArgumentException("Unknown OutputFormat wire value: " + wireValue);
    }
}
