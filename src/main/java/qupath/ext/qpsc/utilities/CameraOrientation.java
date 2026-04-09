package qupath.ext.qpsc.utilities;

/**
 * Represents the physical orientation of the camera / optical image relative
 * to the "canonical" orientation where sample +X appears at display +X (right)
 * and sample +Y appears at display +Y (down).
 *
 * <p>This captures how the camera sensor is mounted and any optical flips
 * (horizontal or vertical mirrors, rotated prisms) in the imaging path
 * between the sample and the displayed image. It does NOT describe stage
 * wiring polarity (see {@link StagePolarity}).
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
 * reflections), which is the complete set of rigid transformations that
 * preserve distances and the sign of determinants are all {@code ±1}.
 *
 * <p>For a rigid orthogonal matrix, the inverse equals the transpose, so
 * {@link #displayToSample(double, double)} applies the transpose of the
 * stored matrix directly without a separate lookup.
 *
 * <p><strong>Which value should I pick for my scope?</strong> Empirically:
 * configure {@link StagePolarity} first based on MM stage diagnostics, then
 * cycle through this enum until the Live Viewer arrows, joystick, and
 * double-click-to-center all produce the correct visual direction on your
 * scope. Most scopes are {@link #NORMAL}, {@link #FLIP_H}, {@link #FLIP_V},
 * or {@link #ROT_180}. The rotation cases ({@link #ROT_90_CW},
 * {@link #ROT_90_CCW}, {@link #TRANSPOSE}, {@link #ANTI_TRANSPOSE}) are
 * supported for arrow / joystick / click but are NOT yet handled by the
 * stitcher — acquisitions that need them will produce correct tiles at
 * correct stage positions but the stitched output will be mis-rotated and
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
