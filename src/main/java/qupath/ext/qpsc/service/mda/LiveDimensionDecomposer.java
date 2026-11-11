package qupath.ext.qpsc.service.mda;

import qupath.ext.qpsc.model.AcquisitionPlan;
import qupath.ext.qpsc.model.TimeLapseOrder;

/**
 * Which position, timepoint, Z plane and angle or channel the server is on, from the live
 * count of images acquired and the plan's loop order (its inner axis, and its time order:
 * every timepoint visiting every position, each position's timepoints back to back, or each
 * Z plane's timepoints back to back).
 */
public final class LiveDimensionDecomposer {

    private LiveDimensionDecomposer() {}

    public record Decomposition(int tIdx, int posIdx, int chIdx, int zIdx, int angleIdx, boolean drifted) {}

    public static Decomposition decompose(long k, AcquisitionPlan plan) {
        if (k < 0 || plan == null || plan.totalImages() <= 0 || k > plan.totalImages()) {
            return new Decomposition(0, 0, 0, 0, 0, true);
        }
        // The live progress feed (progress.current) is a 1-based count of images
        // acquired, and the server emits current == total on the final tile WHILE
        // still RUNNING (the COMPLETED transition happens later). decompose() below
        // treats k as a 0-based aggregate index (valid 0..total-1), so k == total is
        // the terminal "all done" count, not desync. Clamp it to the last image so
        // the panel shows the final tile/angle/z at completion. Only k > total (the
        // server wrote MORE files than the plan predicted) is genuine drift, handled
        // by the guard above.
        if (k == plan.totalImages()) {
            k = plan.totalImages() - 1;
        }

        int innerCount;
        int outerCount;
        boolean innerIsZ;
        boolean innerIsChannel;
        boolean innerIsAngle;

        if (plan.ppm()) {
            if ("z".equalsIgnoreCase(plan.innerAxis())) {
                innerCount = plan.zCount();
                outerCount = plan.angleCount();
                innerIsZ = true;
                innerIsChannel = false;
                innerIsAngle = false;
            } else {
                innerCount = plan.angleCount();
                outerCount = plan.zCount();
                innerIsZ = false;
                innerIsChannel = false;
                innerIsAngle = true;
            }
        } else {
            if ("channel".equalsIgnoreCase(plan.innerAxis())) {
                innerCount = plan.chCount();
                outerCount = plan.zCount();
                innerIsZ = false;
                innerIsChannel = true;
                innerIsAngle = false;
            } else {
                innerCount = plan.zCount();
                outerCount = plan.chCount();
                innerIsZ = true;
                innerIsChannel = false;
                innerIsAngle = false;
            }
        }

        long imagesPerPosition = (long) innerCount * outerCount;
        long imagesPerTimepoint = (long) plan.nPositions() * imagesPerPosition;
        if (imagesPerPosition <= 0 || imagesPerTimepoint <= 0) {
            return new Decomposition(0, 0, 0, 0, 0, true);
        }

        int chIdx = 0;
        int zIdx = 0;
        int angleIdx = 0;
        int tIdx;
        int posIdx;
        TimeLapseOrder order = plan.timeOrder() == null ? TimeLapseOrder.REGION : plan.timeOrder();
        if (order == TimeLapseOrder.PLANE && plan.timepoints() > 1) {
            // for position: for plane: for timepoint: the angles or channels of that plane.
            int perVisit = plan.ppm() ? Math.max(1, plan.angleCount()) : Math.max(1, plan.chCount());
            long perPlane = (long) plan.timepoints() * perVisit;
            long perPos = perPlane * plan.zCount();
            posIdx = (int) (k / perPos);
            long kP = k % perPos;
            zIdx = (int) (kP / perPlane);
            long kZ = kP % perPlane;
            tIdx = (int) (kZ / perVisit);
            int visitIdx = (int) (kZ % perVisit);
            if (plan.ppm()) {
                angleIdx = visitIdx;
            } else {
                chIdx = visitIdx;
            }
        } else {
            long kP;
            if (order == TimeLapseOrder.POSITION) {
                // for position: for timepoint: the position's images.
                long perPosSeries = (long) plan.timepoints() * imagesPerPosition;
                posIdx = (int) (k / perPosSeries);
                long kS = k % perPosSeries;
                tIdx = (int) (kS / imagesPerPosition);
                kP = kS % imagesPerPosition;
            } else {
                // for timepoint: for position: the position's images.
                tIdx = (int) (k / imagesPerTimepoint);
                long kT = k % imagesPerTimepoint;
                posIdx = (int) (kT / imagesPerPosition);
                kP = kT % imagesPerPosition;
            }
            int outerIdx = (int) (kP / innerCount);
            int innerIdx = (int) (kP % innerCount);
            if (plan.ppm()) {
                if (innerIsZ) {
                    angleIdx = outerIdx;
                    zIdx = innerIdx;
                } else {
                    zIdx = outerIdx;
                    angleIdx = innerIdx;
                }
            } else {
                if (innerIsChannel) {
                    zIdx = outerIdx;
                    chIdx = innerIdx;
                } else {
                    chIdx = outerIdx;
                    zIdx = innerIdx;
                }
            }
        }

        boolean drifted = tIdx >= plan.timepoints()
                || posIdx >= plan.nPositions()
                || (plan.chCount() > 0 && chIdx >= plan.chCount())
                || zIdx >= plan.zCount()
                || (plan.angleCount() > 0 && angleIdx >= plan.angleCount());
        if (drifted) {
            return new Decomposition(0, 0, 0, 0, 0, true);
        }

        return new Decomposition(tIdx, posIdx, chIdx, zIdx, angleIdx, false);
    }
}
