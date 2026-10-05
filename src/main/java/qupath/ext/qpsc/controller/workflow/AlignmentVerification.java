package qupath.ext.qpsc.controller.workflow;

import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.ui.SiftAutoAlignHelper;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * Confirms, against the sample itself, that a stored alignment still describes where the
 * sample is -- before spending hours acquiring on it.
 *
 * <h2>Why nothing cheaper will do</h2>
 * The Prior XY stage is open loop. It reports the step count it was given, so a commanded
 * position and the position it reports back agree whether or not the stage actually
 * travelled that far, and a slip during a move leaves no discontinuity for a position
 * monitor to find. The 2026-10-02 four-slide run is the proof: the Micro-Manager CoreLog
 * shows 293,227 position reads across the acquire pass with no unexplained change, no
 * device re-initialisation and no serial fault -- a provably clean counter -- while three
 * of the four slides came out offset in Y, the last badly enough to image the slide label
 * for 1,714 tiles.
 *
 * <p>So the only instrument that can see this is an EXTERNAL reference. Matching the live
 * camera against the macro image is the one we have: it asks "is the sample where the
 * coordinates say", which is the actual question, rather than "did the controller do as it
 * was told", which it always did.
 *
 * <h2>Three outcomes, not two</h2>
 * A failure to match is not the same as a measured disagreement, and conflating them would
 * either miss the case that matters or stop good runs:
 * <ul>
 *   <li>{@link Status#CONFIRMED} -- matched, offset within tolerance. Proceed.</li>
 *   <li>{@link Status#MISALIGNED} -- matched, offset too large. This is a measurement, so
 *       it is trusted: the number says how far off the slide is.</li>
 *   <li>{@link Status#INCONCLUSIVE} -- could not match at all. This is the gross case --
 *       a slide metres off in frame terms lands on the label or on glass, where there is
 *       nothing to match -- but it is ALSO what defocus or a blank field looks like. So
 *       several points are tried before concluding it, and the caller is told which
 *       outcome it got so the operator can be told the difference.</li>
 * </ul>
 *
 * @since 0.9.3
 */
public final class AlignmentVerification {

    private static final Logger logger = LoggerFactory.getLogger(AlignmentVerification.class);

    /**
     * How far the camera may sit from the matched point before the alignment is called wrong.
     *
     * <p>Sized between the two things it must separate. A refined alignment's own residual
     * was 20-40 um on every slide of the 2026-10-02 run (SIFT point-2 offsets of (20.8, 8.0),
     * (-21.6, 21.1) and (36.3, -3.9) um), and the failure being caught was millimetres. 500 um
     * is an order of magnitude above the former and an order below the latter, and is also
     * about one and a half 20x fields, so anything under it still images the intended tissue.
     */
    public static final double MAX_OFFSET_UM = 500.0;

    /**
     * Minimum match confidence to treat the offset as a measurement rather than a guess.
     *
     * <p>Every confident match on that run was at least 0.958 (the weakest, 91 inliers);
     * most were 0.996 or better. Below this the number is not worth acting on, so the
     * result is inconclusive rather than misaligned.
     */
    public static final double MIN_CONFIDENCE = 0.9;

    /** How many points to try before concluding nothing can be matched. */
    private static final int MAX_ATTEMPTS = 3;

    public enum Status {
        /** Matched, and the alignment is good. */
        CONFIRMED,
        /** Matched, and the sample is not where the alignment says. */
        MISALIGNED,
        /** Nothing could be matched, so the alignment is unconfirmed either way. */
        INCONCLUSIVE,
        /** Not attempted (disabled, or nothing to check against). */
        SKIPPED
    }

    /**
     * The outcome of one verification.
     *
     * @param status     what was concluded
     * @param offsetUm   matched offset magnitude (um), NaN when nothing matched
     * @param inliers    inlier count of the match used, 0 when nothing matched
     * @param confidence confidence of the match used, 0 when nothing matched
     * @param attempts   how many points were tried
     * @param message    operator-facing sentence, suitable for a dialog
     */
    public record Verdict(
            Status status, double offsetUm, int inliers, double confidence, int attempts, String message) {

        /** True when the run should not continue on this alignment. */
        public boolean shouldStop() {
            return status == Status.MISALIGNED || status == Status.INCONCLUSIVE;
        }
    }

    private AlignmentVerification() {}

    /**
     * Decides what one SIFT measurement means. Pure, so it can be tested without hardware.
     *
     * @param measurement {@code [offsetX, offsetY, inliers, confidence]} from
     *     {@link SiftAutoAlignHelper#measureOffsetWithoutMoving}, or null when the server
     *     could not match
     * @param maxOffsetUm tolerance for a matched offset
     * @return CONFIRMED, MISALIGNED, or INCONCLUSIVE; never SKIPPED
     */
    public static Status judge(double[] measurement, double maxOffsetUm) {
        if (measurement == null || measurement.length < 4) {
            return Status.INCONCLUSIVE;
        }
        double dx = measurement[0];
        double dy = measurement[1];
        double confidence = measurement[3];
        if (!Double.isFinite(dx) || !Double.isFinite(dy)) {
            return Status.INCONCLUSIVE;
        }
        // A low-confidence match is not a measurement. Calling it MISALIGNED would stop a
        // run on a number we do not believe; calling it CONFIRMED would clear an alignment
        // on the same number. Neither is honest, so it counts as nothing matched.
        if (confidence < MIN_CONFIDENCE) {
            return Status.INCONCLUSIVE;
        }
        double offset = Math.hypot(dx, dy);
        return offset <= maxOffsetUm ? Status.CONFIRMED : Status.MISALIGNED;
    }

    /**
     * Verifies one slide's alignment by matching the camera against the macro image.
     *
     * <p>Moves the stage to each candidate point in turn and measures there. Stops at the
     * first confident answer, good or bad. Never throws: a verification that cannot be made
     * returns {@link Status#INCONCLUSIVE} with the reason in its message, because the caller
     * decides what an unconfirmed alignment costs.
     *
     * @param gui        QuPath GUI, for the macro image and project entry
     * @param annotation the region about to be acquired, used to choose where to look
     * @param transform  the alignment being checked (QuPath full-res to stage)
     * @param focusZUm   the slide's focus Z from setup, moved to before matching so the
     *                   camera is not matching a blurred field; null to leave Z alone
     * @param fovWidthUm  camera field width (um), for the size of the matched patch
     * @param fovHeightUm camera field height (um)
     * @return the verdict; never null
     */
    public static Verdict verify(
            QuPathGUI gui,
            PathObject annotation,
            AffineTransform transform,
            Double focusZUm,
            double fovWidthUm,
            double fovHeightUm) {

        if (gui == null || annotation == null || annotation.getROI() == null || transform == null) {
            return new Verdict(Status.SKIPPED, Double.NaN, 0, 0, 0, "Nothing to verify the alignment against.");
        }

        double pixelSize = pixelSizeUm(gui);
        if (!Double.isFinite(pixelSize) || pixelSize <= 0) {
            return new Verdict(
                    Status.SKIPPED,
                    Double.NaN,
                    0,
                    0,
                    0,
                    "The macro image has no pixel-size calibration, so it cannot be matched against the camera.");
        }

        List<double[]> points = candidatePoints(annotation.getROI());
        double patchW = fovWidthUm / pixelSize;
        double patchH = fovHeightUm / pixelSize;

        MicroscopeController mc = MicroscopeController.getInstance();
        int attempt = 0;
        for (double[] centre : points) {
            if (attempt >= MAX_ATTEMPTS) {
                break;
            }
            attempt++;
            try {
                ROI roi = ROIs.createRectangleROI(
                        centre[0] - patchW / 2.0,
                        centre[1] - patchH / 2.0,
                        patchW,
                        patchH,
                        annotation.getROI().getImagePlane());
                // A throwaway detection, never added to the hierarchy: the SIFT helper reads
                // only the ROI bounds, so this is the cheapest way to name a patch of macro
                // to match without needing the acquisition's tile detections to exist yet.
                PathObject patch = PathObjects.createDetectionObject(roi);

                double[] predicted = TransformationFunctions.transformQuPathFullResToStage(
                        new double[] {centre[0], centre[1]}, transform);
                logger.info(
                        "Alignment check {}/{}: moving to predicted stage ({}, {}) um for '{}'",
                        attempt,
                        Math.min(points.size(), MAX_ATTEMPTS),
                        String.format("%.1f", predicted[0]),
                        String.format("%.1f", predicted[1]),
                        annotation.getName());
                mc.moveStageXY(predicted[0], predicted[1]);
                if (focusZUm != null && Double.isFinite(focusZUm)) {
                    mc.moveStageZ(focusZUm);
                }

                double[] measurement = SiftAutoAlignHelper.measureOffsetWithoutMoving(gui, patch);
                Status status = judge(measurement, MAX_OFFSET_UM);
                if (status == Status.INCONCLUSIVE) {
                    logger.warn(
                            "Alignment check {}/{}: no usable match at this point{}",
                            attempt,
                            Math.min(points.size(), MAX_ATTEMPTS),
                            measurement == null
                                    ? ""
                                    : String.format(
                                            " (confidence %.3f, %d inliers)", measurement[3], (int) measurement[2]));
                    continue;
                }
                double offset = Math.hypot(measurement[0], measurement[1]);
                int inliers = (int) measurement[2];
                double confidence = measurement[3];
                if (status == Status.CONFIRMED) {
                    return new Verdict(
                            status,
                            offset,
                            inliers,
                            confidence,
                            attempt,
                            String.format(
                                    "Alignment confirmed against the sample: %.0f um off, within the %.0f um "
                                            + "tolerance (%d inliers, confidence %.3f).",
                                    offset, MAX_OFFSET_UM, inliers, confidence));
                }
                return new Verdict(
                        status,
                        offset,
                        inliers,
                        confidence,
                        attempt,
                        String.format(
                                "The sample is %.0f um from where this slide's alignment says it is, which is "
                                        + "beyond the %.0f um tolerance (%d inliers, confidence %.3f). Acquiring "
                                        + "now would image the wrong part of the slide.",
                                offset, MAX_OFFSET_UM, inliers, confidence));
            } catch (Exception e) {
                logger.warn("Alignment check {} could not be completed: {}", attempt, e.getMessage());
            }
        }

        return new Verdict(
                Status.INCONCLUSIVE,
                Double.NaN,
                0,
                0,
                attempt,
                String.format(
                        "The camera could not be matched to the macro image at any of %d points on this slide. "
                                + "That is what a slide displaced by more than a field looks like -- there is "
                                + "nothing recognisable under the objective -- but a badly defocused or blank "
                                + "field looks the same, so this is unconfirmed rather than proven wrong.",
                        attempt));
    }

    /**
     * Points to try, in order: the region's centroid first, then pulled toward its centre.
     *
     * <p>The centroid is where an operator drew the region, so it is the likeliest tissue.
     * The fallbacks exist because a bounding box's centroid can land in a gap, and a point
     * with no features is indistinguishable from a displaced slide -- trying more than one
     * place is what keeps that from being reported as a fault.
     */
    static List<double[]> candidatePoints(ROI roi) {
        List<double[]> points = new ArrayList<>();
        double cx = roi.getCentroidX();
        double cy = roi.getCentroidY();
        points.add(new double[] {cx, cy});

        double bx = roi.getBoundsX();
        double by = roi.getBoundsY();
        double bw = roi.getBoundsWidth();
        double bh = roi.getBoundsHeight();
        points.add(new double[] {bx + bw * 0.33, by + bh * 0.33});
        points.add(new double[] {bx + bw * 0.67, by + bh * 0.67});
        return points;
    }

    private static double pixelSizeUm(QuPathGUI gui) {
        try {
            return gui.getImageData().getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
        } catch (Exception e) {
            logger.debug("Could not read the macro pixel size: {}", e.getMessage());
            return Double.NaN;
        }
    }
}
