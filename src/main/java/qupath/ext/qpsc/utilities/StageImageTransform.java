package qupath.ext.qpsc.utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;

/**
 * Composes {@link StagePolarity} and {@link CameraOrientation} into a single
 * transform that converts between user-visible screen-space gestures and
 * stage-command coordinates.
 *
 * <p>This is the single source of truth for the relationship between "what
 * the user sees on screen" and "what stage command produces that view". All
 * subsystems that care about this relationship — arrow buttons, virtual
 * joystick, double-click-to-center, and the stitcher's stage-to-pixel
 * mapping — should go through this class instead of doing their own sign
 * math. This prevents the drift-between-subsystems bug class that produced
 * several separate sign regressions on OWS3 over a single day.
 *
 * <h2>Coordinate frames</h2>
 * <ul>
 *   <li><strong>Screen frame</strong> — displayed pixels. Positive Y grows
 *       downward (universal display convention).</li>
 *   <li><strong>Sample frame</strong> — the idealised "lab" frame where the
 *       sample physically sits. Intermediate frame between screen and MM
 *       command. Sample +X is to the right in the lab; sample +Y points
 *       "toward the back" of the microscope (away from the user) by the
 *       convention chosen here.</li>
 *   <li><strong>MM command frame</strong> — what
 *       {@code core.setXYPosition(x, y)} accepts. Related to sample frame
 *       only by {@link StagePolarity} (per-axis sign flips from wiring
 *       conventions).</li>
 * </ul>
 *
 * <h2>User-gesture semantics (interpretation "pan")</h2>
 *
 * <p>This class interprets all positive-direction screen gestures as "pan"
 * gestures: a positive screen delta means "move the field of view in that
 * direction so the user can see content that is currently off-screen in
 * that direction". This matches click-to-center's semantics naturally:
 * clicking a pixel below the center means "pan the view downward so this
 * clicked content moves up to the center".
 *
 * <p>The arrow buttons and virtual joystick, which are directional
 * controllers, use the same pan semantics when the "Sample Movement"
 * checkbox is checked (the more intuitive default). When the checkbox is
 * unchecked, the arrow handler flips the X sign itself (the historical
 * MicroManager behaviour for the "default" mode). This small UI-layer
 * flip is out-of-scope for this class — callers handle it themselves.
 *
 * <h2>Transform chain</h2>
 *
 * <p>For a user gesture (screen delta → MM command delta):
 * <pre>
 *   sampleDelta = cameraOrientation.displayToSample(screenDx, screenDy)
 *   mmDelta = stagePolarity.sampleToMmDelta(sampleDelta[0], sampleDelta[1])
 * </pre>
 *
 * <p>For the stitcher (stage position → displayed pixel position):
 * <pre>
 *   sample = stagePolarity.mmToSampleDelta(mmX, mmY)
 *   display = cameraOrientation.sampleToDisplay(sample[0], sample[1])
 * </pre>
 *
 * <p>Since the stitcher we use only supports independent per-axis sign
 * flips (not axis swaps), {@link #stitcherFlipFlags()} reduces the
 * composite to two booleans for the axis-aligned orientations and logs a
 * loud warning for the axis-swap orientations.
 */
public class StageImageTransform {

    private static final Logger logger = LoggerFactory.getLogger(StageImageTransform.class);

    private final StagePolarity stagePolarity;
    private final CameraOrientation cameraOrientation;

    public StageImageTransform(StagePolarity stagePolarity, CameraOrientation cameraOrientation) {
        if (stagePolarity == null) {
            throw new IllegalArgumentException("stagePolarity must not be null");
        }
        if (cameraOrientation == null) {
            throw new IllegalArgumentException("cameraOrientation must not be null");
        }
        this.stagePolarity = stagePolarity;
        this.cameraOrientation = cameraOrientation;
    }

    /**
     * Build a transform by reading the current QPSC preferences. This is
     * the usual entry point for live-view subsystems.
     */
    public static StageImageTransform current() {
        StagePolarity polarity = QPPreferenceDialog.getStagePolarityProperty();
        CameraOrientation orientation = QPPreferenceDialog.getCameraOrientationProperty();
        return new StageImageTransform(polarity, orientation);
    }

    public StagePolarity getStagePolarity() {
        return stagePolarity;
    }

    public CameraOrientation getCameraOrientation() {
        return cameraOrientation;
    }

    // ------------------------------------------------------------
    // Screen-space → MM-command-space (user gestures)
    // ------------------------------------------------------------

    /**
     * Convert a screen-space delta (in micrometres, positive Y = down) into
     * the corresponding MM-command delta (in micrometres).
     *
     * <p>The screen delta should express the "pan" intent: positive X =
     * pan right (look at content to the right of current view), positive Y
     * = pan down (look at content below current view). Click-to-center
     * expresses its offset this way naturally; arrow buttons and the
     * virtual joystick need to translate their button / knob coordinates
     * to this convention before calling.
     *
     * @param screenDxUm pan delta in X, in micrometres
     * @param screenDyUm pan delta in Y, in micrometres (positive = down)
     * @return {@code double[2]} containing {@code [mmDx, mmDy]}
     */
    public double[] screenPanDeltaToMmDelta(double screenDxUm, double screenDyUm) {
        double[] sampleDelta = cameraOrientation.displayToSample(screenDxUm, screenDyUm);
        return stagePolarity.sampleToMmDelta(sampleDelta[0], sampleDelta[1]);
    }

    /**
     * Convenience for click-to-center. Given the current stage position and
     * the pixel offset of the click from the image center (screen-frame
     * pixels, positive Y = down), return the MM-command target position
     * that would center the clicked pixel.
     *
     * @param currentMmX current MM-command X
     * @param currentMmY current MM-command Y
     * @param offsetPixelX click offset from image center in screen pixels (positive = right of center)
     * @param offsetPixelY click offset from image center in screen pixels (positive = below center)
     * @param pixelSizeUmX X pixel size in µm / pixel
     * @param pixelSizeUmY Y pixel size in µm / pixel
     * @return {@code double[2]} containing {@code [newMmX, newMmY]}
     */
    public double[] clickOffsetToMmTarget(
            double currentMmX, double currentMmY,
            double offsetPixelX, double offsetPixelY,
            double pixelSizeUmX, double pixelSizeUmY) {
        double screenDx = offsetPixelX * pixelSizeUmX;
        double screenDy = offsetPixelY * pixelSizeUmY;
        double[] mmDelta = screenPanDeltaToMmDelta(screenDx, screenDy);
        return new double[] {currentMmX + mmDelta[0], currentMmY + mmDelta[1]};
    }

    // ------------------------------------------------------------
    // Stitcher-side flags
    // ------------------------------------------------------------

    /**
     * Compute the axis-aligned sign-flip flags that the external stitcher
     * extension needs to place stage coordinates at the correct pixel
     * positions in the stitched output.
     *
     * <p>The stitcher does a simple {@code pixel = stage / pixelSize} and
     * then lets this transform negate the raw value via its
     * {@code flipStitchingX/Y} flags. This method folds both the stage
     * polarity and the camera orientation into those two flags.
     *
     * <p><strong>Limitation:</strong> the stitcher cannot express
     * rotations that swap X and Y axes. For {@link CameraOrientation#ROT_90_CW},
     * {@link CameraOrientation#ROT_90_CCW}, {@link CameraOrientation#TRANSPOSE},
     * or {@link CameraOrientation#ANTI_TRANSPOSE}, this method logs an
     * error and returns the best axis-aligned approximation. The user can
     * still acquire tiles correctly (their positions on the sample are
     * accurate) but the stitched output orientation may be wrong. Full
     * rotation support would require extending the stitcher interface;
     * deferred for a future change.
     *
     * @return {@code boolean[2]} containing {@code [flipX, flipY]}
     */
    public boolean[] stitcherFlipFlags() {
        if (cameraOrientation.swapsAxes()) {
            logger.error(
                    "Camera orientation {} requires an axis swap which the "
                            + "stitcher does not currently support. Tiles will be "
                            + "acquired at the correct stage positions but the "
                            + "stitched image will be mis-rotated. Please use "
                            + "NORMAL, FLIP_H, FLIP_V, or ROT_180 until rotated "
                            + "stitching is implemented.",
                    cameraOrientation);
            // Best-effort fallback: treat the swap-axes cases as if they
            // were axis-aligned with the same sign signature. This at
            // least keeps the per-axis flags consistent.
            boolean flipX = cameraOrientation == CameraOrientation.ROT_90_CW
                    || cameraOrientation == CameraOrientation.ANTI_TRANSPOSE;
            boolean flipY = cameraOrientation == CameraOrientation.ROT_90_CCW
                    || cameraOrientation == CameraOrientation.ANTI_TRANSPOSE;
            return new boolean[] {
                    flipX ^ stagePolarity.invertX,
                    flipY ^ stagePolarity.invertY
            };
        }

        // Axis-aligned case: the final sign is the XOR of stage polarity
        // and camera flip on each axis. Example: stage NORMAL + camera
        // FLIP_H = flip X only. Stage INVERT_X + camera FLIP_H = no flip
        // (they cancel). This matches the 4 * 4 = 16 possible axis-aligned
        // configurations.
        boolean flipX = cameraOrientation.flipsX() ^ stagePolarity.invertX;
        boolean flipY = cameraOrientation.flipsY() ^ stagePolarity.invertY;
        return new boolean[] {flipX, flipY};
    }

    @Override
    public String toString() {
        return String.format(
                "StageImageTransform[stage=%s, camera=%s]",
                stagePolarity, cameraOrientation);
    }

    /**
     * Produce a longer, human-readable description of what this transform
     * actually does, for logging during debugging. Shows the effective sign
     * behaviour of the four compass directions plus the stitcher flags.
     *
     * <p>Example output:
     * <pre>
     *   StageImageTransform[stage=INVERT_Y, camera=NORMAL]:
     *     Arrow right (pan right) -> mmDelta (+1.00, +0.00)
     *     Arrow up    (pan up)    -> mmDelta (+0.00, +1.00)
     *     Click below center      -> mmDelta (+0.00, -1.00)
     *     Stitcher flip flags     -> flipX=false, flipY=true
     * </pre>
     */
    public String describe() {
        double[] right = screenPanDeltaToMmDelta(1, 0);
        double[] up = screenPanDeltaToMmDelta(0, -1);
        double[] below = screenPanDeltaToMmDelta(0, 1);
        boolean[] flags = stitcherFlipFlags();
        return String.format(
                "StageImageTransform[stage=%s, camera=%s]:%n"
                        + "  Arrow right (pan right) -> mmDelta (%+.2f, %+.2f)%n"
                        + "  Arrow up    (pan up)    -> mmDelta (%+.2f, %+.2f)%n"
                        + "  Click below center      -> mmDelta (%+.2f, %+.2f)%n"
                        + "  Stitcher flip flags     -> flipX=%s, flipY=%s",
                stagePolarity, cameraOrientation,
                right[0], right[1],
                up[0], up[1],
                below[0], below[1],
                flags[0], flags[1]);
    }
}
