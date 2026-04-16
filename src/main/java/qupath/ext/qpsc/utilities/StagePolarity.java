package qupath.ext.qpsc.utilities;

/**
 * Represents the hardware polarity of the stage axes -- whether MicroManager's
 * {@code setXYPosition} command moves the physical stage in the same direction
 * as the lab frame (sample frame, in our model) or the opposite direction,
 * per axis.
 *
 * <p>This is a hardware property determined by how the stage encoder / device
 * adapter is wired. It is independent of how the camera is mounted or oriented
 * (see {@link CameraOrientation}).
 *
 * <p>To diagnose polarity on a real scope: issue
 * {@code core.setXYPosition(core.getXPosition() + 100, core.getYPosition())}
 * and observe which direction the physical stage actually moves in the lab
 * frame. If MM's "+X" command moves the stage toward lab -X, the X axis is
 * inverted.
 *
 * <p>Stored as an enum (not two booleans) so the UI can't accidentally end
 * up in an undefined state, and so the migration / diagnostic code can
 * reason about the whole-stage polarity as a single value.
 */
public enum StagePolarity {
    /** MM +X -> lab +X, MM +Y -> lab +Y. */
    NORMAL(false, false),
    /** MM +X -> lab -X, MM +Y -> lab +Y. */
    INVERT_X(true, false),
    /** MM +X -> lab +X, MM +Y -> lab -Y. */
    INVERT_Y(false, true),
    /** Both axes inverted. */
    INVERT_XY(true, true);

    public final boolean invertX;
    public final boolean invertY;

    StagePolarity(boolean invertX, boolean invertY) {
        this.invertX = invertX;
        this.invertY = invertY;
    }

    /**
     * Convert a sample-frame delta (um) to the corresponding MM-command delta
     * (um). For an inverted axis, the sign is flipped.
     *
     * @param sampleDx sample-frame X delta in um
     * @param sampleDy sample-frame Y delta in um
     * @return {@code double[2]} containing {@code [mmDx, mmDy]}
     */
    public double[] sampleToMmDelta(double sampleDx, double sampleDy) {
        return new double[] {invertX ? -sampleDx : sampleDx, invertY ? -sampleDy : sampleDy};
    }

    /**
     * Convert an MM-command delta (um) to the corresponding sample-frame
     * delta (um). For axis sign flips the operation is its own inverse.
     */
    public double[] mmToSampleDelta(double mmDx, double mmDy) {
        return sampleToMmDelta(mmDx, mmDy);
    }

    /**
     * Map two legacy independent boolean prefs to a single {@link StagePolarity}
     * value. Provided so existing preferences can be migrated in one place.
     */
    public static StagePolarity fromBooleans(boolean invertX, boolean invertY) {
        if (invertX && invertY) return INVERT_XY;
        if (invertX) return INVERT_X;
        if (invertY) return INVERT_Y;
        return NORMAL;
    }
}
