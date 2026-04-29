package qupath.ext.qpsc.modality;

import qupath.ext.qpsc.utilities.HardwareKey;

/**
 * Thrown by a modality handler when background correction is enabled for a
 * modality but no usable background-exposure calibration can be located for
 * the requested hardware combination.
 *
 * <p>The handler must throw rather than fall back to a generic preference value
 * (e.g. {@code PersistentPreferences.lastUnifiedExposureMs}) because running
 * acquisition at an exposure that does not match the captured background
 * blows up flat-field correction silently. The caller (the workflow) catches
 * this and surfaces a re-collect-backgrounds prompt to the user.
 *
 * <p>Carry the requested {@link HardwareKey} so the workflow can render a
 * helpful message ("collect backgrounds for objective X on detector Y").
 */
public class BackgroundCalibrationMismatchException extends RuntimeException {

    private final HardwareKey requested;

    public BackgroundCalibrationMismatchException(String message, HardwareKey requested) {
        super(message);
        this.requested = requested;
    }

    public HardwareKey requested() {
        return requested;
    }
}
