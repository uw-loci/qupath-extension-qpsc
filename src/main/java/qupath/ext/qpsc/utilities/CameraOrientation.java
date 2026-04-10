package qupath.ext.qpsc.utilities;

/**
 * Describes the <strong>net optical relationship</strong> between the sample
 * frame and the displayed image on a given microscope. Composed with
 * {@link StagePolarity} by {@link StageImageTransform} to form the complete
 * stage ⇔ display sign math used by the Live Viewer arrow buttons, virtual
 * joystick, double-click-to-centre, and the stitcher.
 *
 * <h2>What this enum is NOT</h2>
 *
 * <p>This enum is <em>not</em> a command to flip or rotate the displayed
 * image. Selecting {@link #FLIP_H} does not alter a single pixel that the
 * Live Viewer shows. It also does not describe whether anyone has physically
 * rotated or re-mounted the camera hardware.
 *
 * <h2>What this enum IS</h2>
 *
 * <p>A label describing how the displayed image is <em>already</em> oriented
 * relative to the sample frame. A scope can have a horizontal or vertical
 * image flip inherent to its optics — a dichroic, a mirror, an adapter, or
 * simply the sensor readout wiring — that no user ever installed. Software
 * needs to know about that flip in order to interpret stage commands and
 * tile positions correctly, but it cannot undo or cause a physical flip; it
 * can only match its own sign math to the reality.
 *
 * <p>Example: OWS3 (Nikon Ti2 + Hamamatsu Orca) needs {@link #FLIP_H},
 * because the Ti2 body's optical path produces a horizontally-flipped image
 * relative to the stage frame. Nobody touched the camera mount; the flip is
 * built into the scope. Before {@link #FLIP_H} was set, Live-view gestures
 * appeared to work (because the {@link StagePolarity} alone happened to
 * compensate) but the stitched output had its tiles laid out with X
 * mirrored. Setting {@link #FLIP_H} did not change the Live Viewer image —
 * it fixed the stitched output.
 *
 * <h2>Math</h2>
 *
 * <p>Each enum value corresponds to a 2x2 orthogonal matrix {@code M} that
 * maps a sample-frame delta {@code (dsx, dsy)} to the displayed-pixel-frame
 * delta {@code (ddx, ddy)}:
 * <pre>
 *   ddx = m00 * dsx + m01 * dsy
 *   ddy = m10 * dsx + m11 * dsy
 * </pre>
 *
 * <p>The 8 values form the dihedral group D4 (four rotations times two
 * reflections), which is the complete set of rigid transformations
 * preserving distances with determinant ±1.
 *
 * <p>For a rigid orthogonal matrix, the inverse equals the transpose, so
 * {@link #displayToSample(double, double)} applies the transpose of the
 * stored matrix directly without a separate lookup.
 *
 * <h2>Which value should I pick for my scope?</h2>
 *
 * <p>Empirically: start with {@link #NORMAL}. Test arrow buttons, joystick,
 * double-click-to-centre, AND a small 2×2 stitched acquisition. If only the
 * stitched output is wrong while live gestures look right, that is a strong
 * signal you need a {@link #FLIP_H}, {@link #FLIP_V}, or {@link #ROT_180}
 * value — and it does <em>not</em> mean your live image is wrong. Cycle
 * through the four axis-aligned values until both gestures and stitching
 * agree.
 *
 * <p>Most scopes end up at {@link #NORMAL}, {@link #FLIP_H}, {@link #FLIP_V},
 * or {@link #ROT_180}. The rotation cases ({@link #ROT_90_CW},
 * {@link #ROT_90_CCW}, {@link #TRANSPOSE}, {@link #ANTI_TRANSPOSE}) are
 * supported for arrow / joystick / click but are NOT yet handled by the
 * stitcher — acquisitions that need them will produce correct tile
 * positions on the sample but the stitched image will be mis-rotated and
 * log a warning.
 */
public enum CameraOrientation {
    /** Identity. Sample +X appears at display +X; sample +Y appears at display +Y. */
    NORMAL(1, 0, 0, 1),

    /** Horizontal mirror. Sample +X appears at display -X. */
    FLIP_H(-1, 0, 0, 1),

    /** Vertical mirror. Sample +Y appears at display -Y. */
    FLIP_V(1, 0, 0, -1),

    /** Rotated 180° (upside down). Equivalent to {@link #FLIP_H} composed with {@link #FLIP_V}. */
    ROT_180(-1, 0, 0, -1),

    /** Rotated 90° clockwise. Sample +X appears at display -Y (top); sample +Y appears at display +X (right). */
    ROT_90_CW(0, 1, -1, 0),

    /** Rotated 90° counter-clockwise (i.e. 270° clockwise). */
    ROT_90_CCW(0, -1, 1, 0),

    /** Diagonal transpose. Sample {@code (x,y)} appears at display {@code (y,x)}. */
    TRANSPOSE(0, 1, 1, 0),

    /** Anti-diagonal transpose. */
    ANTI_TRANSPOSE(0, -1, -1, 0);

    // Matrix entries: sample -> display is [[m00, m01], [m10, m11]]
    private final double m00, m01, m10, m11;

    CameraOrientation(double m00, double m01, double m10, double m11) {
        this.m00 = m00;
        this.m01 = m01;
        this.m10 = m10;
        this.m11 = m11;
    }

    /**
     * Apply the camera orientation: given a sample-frame delta, compute the
     * corresponding display-frame delta.
     */
    public double[] sampleToDisplay(double dsx, double dsy) {
        return new double[] {
            m00 * dsx + m01 * dsy,
            m10 * dsx + m11 * dsy
        };
    }

    /**
     * Inverse: given a display-frame delta (screen pixels, positive Y grows
     * downward), compute the corresponding sample-frame delta.
     *
     * <p>For orthogonal matrices the inverse equals the transpose, which we
     * apply directly here without looking up a separate inverse matrix.
     */
    public double[] displayToSample(double ddx, double ddy) {
        return new double[] {
            m00 * ddx + m10 * ddy,
            m01 * ddx + m11 * ddy
        };
    }

    /**
     * Whether this orientation swaps the X and Y axes (i.e. the matrix has
     * zero diagonal and non-zero off-diagonal). Used by subsystems that only
     * support axis-aligned sign flips (e.g. the stitcher) to detect cases
     * they cannot handle directly.
     */
    public boolean swapsAxes() {
        return m00 == 0 && m11 == 0;
    }

    /**
     * Whether MM's {@code +X} command, assuming {@link StagePolarity#NORMAL},
     * produces a display delta with a negative X component.
     *
     * <p>This is used by the stitcher flag computation to decide whether to
     * set its {@code flipStitchingX} flag. Not meaningful for orientations
     * that swap axes.
     */
    public boolean flipsX() {
        // sample +X -> display (m00, m10). We look at whether the display X
        // component of a sample +X unit vector is negative.
        return m00 < 0;
    }

    /**
     * Mirror of {@link #flipsX()} for Y.
     */
    public boolean flipsY() {
        // sample +Y -> display (m01, m11). Look at display Y component.
        return m11 < 0;
    }
}
