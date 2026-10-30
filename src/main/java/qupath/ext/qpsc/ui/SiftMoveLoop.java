package qupath.ext.qpsc.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moves the stage onto a SIFT match and checks that it arrived.
 *
 * <p>A SIFT match says where the camera is relative to the tile. Which way the stage must travel to
 * close that gap depends on the rig: {@code -offset} lands on the PPM and the LC-PolScope, but on
 * OWS3 on 2026-10-27 X needed the opposite sign (SIFT moved +174 um where -163 um was right, and +312
 * where -302 was right), which doubles the error on that axis at 100% match confidence. So the first
 * move uses {@code -offset} and a second match measures what it did: the change in offset against
 * the distance moved gives each axis's real response, and any remaining correction uses that.
 *
 * <p>Nothing here knows about polarity, camera orientation or flips, which is the point.
 */
final class SiftMoveLoop {

    private static final Logger logger = LoggerFactory.getLogger(SiftMoveLoop.class);

    /** Corrections allowed after the first move. */
    static final int MAX_CORRECTIONS = 2;

    /** Below this move (um) an axis says too little about its own response to trust. */
    static final double MIN_MEASURABLE_MOVE_UM = 10;

    /** A response further than this from 1 um per um means the two matches disagree. */
    static final double MAX_RESPONSE_ERROR = 0.5;

    /** A fresh SIFT measurement from wherever the stage is now. */
    @FunctionalInterface
    interface Meter {
        /** @return the offset {@code [x, y]} in um, or null if nothing matched */
        double[] measure() throws Exception;
    }

    /** The stage. */
    interface Stage {
        double[] position() throws Exception;

        void moveTo(double x, double y) throws Exception;
    }

    /** How a move ended: {@code verified} means a match confirmed the landing, or none was needed. */
    record Landing(boolean verified, boolean axisReversed) {}

    private SiftMoveLoop() {}

    /**
     * Moves onto the target and verifies it.
     *
     * @param offset the first offset {@code [x, y]}, um
     * @param serverFlipX whether the server mirrored the reference in X
     * @param serverFlipY whether the server mirrored the reference in Y
     * @param toleranceUm a landing with both offset components within this is accepted
     * @param stage the stage
     * @param meter measures the offset from the current position
     * @return how it ended. After a lost match the stage is back where it started.
     */
    static Landing run(
            double[] offset, boolean serverFlipX, boolean serverFlipY, double toleranceUm, Stage stage, Meter meter)
            throws Exception {
        double[] start = stage.position();
        double[] first =
                SiftAutoAlignHelper.composeSiftOffsetToStageDelta(offset[0], offset[1], serverFlipX, serverFlipY);
        stage.moveTo(start[0] + first[0], start[1] + first[1]);
        if (withinTolerance(offset, toleranceUm)) {
            return new Landing(true, false);
        }

        double[] previousPosition = start;
        double[] previousOffset = offset;
        boolean reversed = false;
        for (int correction = 0; ; correction++) {
            double[] position = stage.position();
            double[] now = meter.measure();
            if (now == null) {
                logger.warn("SIFT verify: no match after the move; returning to where SIFT started");
                stage.moveTo(start[0], start[1]);
                return new Landing(false, reversed);
            }
            logger.info(
                    "SIFT verify {}: at ({}, {}) offset ({}, {}) um",
                    correction,
                    String.format("%.1f", position[0]),
                    String.format("%.1f", position[1]),
                    String.format("%.1f", now[0]),
                    String.format("%.1f", now[1]));
            if (withinTolerance(now, toleranceUm)) {
                return new Landing(true, reversed);
            }
            if (correction == MAX_CORRECTIONS) {
                logger.warn("SIFT verify: still {} um out after {} corrections", maxOf(now), correction);
                return new Landing(false, reversed);
            }

            double responseX = response(position[0] - previousPosition[0], now[0] - previousOffset[0]);
            double responseY = response(position[1] - previousPosition[1], now[1] - previousOffset[1]);
            if (isInconsistent(responseX) || isInconsistent(responseY)) {
                logger.warn(
                        "SIFT verify: offset changed {} and {} um per um moved in X and Y, not 1; "
                                + "returning to where SIFT started",
                        String.format("%.2f", responseX),
                        String.format("%.2f", responseY));
                stage.moveTo(start[0], start[1]);
                return new Landing(false, reversed);
            }

            double[] negated =
                    SiftAutoAlignHelper.composeSiftOffsetToStageDelta(now[0], now[1], serverFlipX, serverFlipY);
            double stepX = Double.isNaN(responseX) ? negated[0] : -now[0] * Math.signum(responseX);
            double stepY = Double.isNaN(responseY) ? negated[1] : -now[1] * Math.signum(responseY);
            boolean reversedNow = differs(stepX, negated[0]) || differs(stepY, negated[1]);
            if (reversedNow) {
                logger.warn(
                        "SIFT verify: this stage answers opposite to -offset (response X {}, Y {}); "
                                + "correcting from the measured response",
                        Double.isNaN(responseX) ? "unmeasured" : String.format("%.2f", responseX),
                        Double.isNaN(responseY) ? "unmeasured" : String.format("%.2f", responseY));
            }
            reversed |= reversedNow;
            stage.moveTo(position[0] + stepX, position[1] + stepY);
            previousPosition = position;
            previousOffset = now;
        }
    }

    /** Offset change per um moved along one axis, or NaN if the move was too small to tell. */
    private static double response(double moved, double offsetChange) {
        return Math.abs(moved) < MIN_MEASURABLE_MOVE_UM ? Double.NaN : offsetChange / moved;
    }

    private static boolean isInconsistent(double response) {
        return !Double.isNaN(response) && Math.abs(Math.abs(response) - 1) > MAX_RESPONSE_ERROR;
    }

    private static boolean differs(double a, double b) {
        return Math.signum(a) != Math.signum(b);
    }

    private static boolean withinTolerance(double[] offset, double toleranceUm) {
        return maxOf(offset) <= toleranceUm;
    }

    private static double maxOf(double[] offset) {
        return Math.max(Math.abs(offset[0]), Math.abs(offset[1]));
    }
}
