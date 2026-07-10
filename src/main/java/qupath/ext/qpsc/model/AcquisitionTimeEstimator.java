package qupath.ext.qpsc.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure (UI-free) time estimator for a batch of acquisition workflows -- e.g. the four slides of a
 * multi-slide run. Produces a per-workflow and total estimate so an all-workflow prediction can be
 * shown before/while the batch runs.
 *
 * <p>Deliberately decoupled from any dialog: {@link AllWorkflowProgressDialog} renders these
 * results, but the calculation lives here so it can be reused when the prediction is folded into
 * the multi-acquisition GUI. The model is intentionally simple and its constants are tunable; it is
 * an ESTIMATE, not a guarantee.
 *
 * <p>Per-workflow time = capture + autofocus + stitching, where:
 * <ul>
 *   <li>capture = nPositions * zCount * sum over frames of (exposure_s + {@link #PER_FRAME_OVERHEAD_S}),
 *       with one frame per angle (PPM) or per channel;</li>
 *   <li>autofocus = nPositions * {@link #AF_PER_POSITION_S};</li>
 *   <li>stitching = {@link #STITCH_BASE_S} + totalImages * {@link #STITCH_PER_IMAGE_S}.</li>
 * </ul>
 */
public final class AcquisitionTimeEstimator {

    /** Stage move + trigger + read/write overhead per captured frame (seconds). */
    public static final double PER_FRAME_OVERHEAD_S = 0.6;
    /** Rough autofocus cost amortized per tile position (seconds). */
    public static final double AF_PER_POSITION_S = 1.5;
    /** Fixed stitching setup cost per workflow (seconds). */
    public static final double STITCH_BASE_S = 8.0;
    /** Incremental stitching cost per image (seconds). */
    public static final double STITCH_PER_IMAGE_S = 0.05;

    private AcquisitionTimeEstimator() {}

    /**
     * One workflow's inputs. {@code exposuresMs} holds one exposure per frame within a position
     * (per angle for PPM, per channel otherwise); its size is the frame count per (position, z).
     *
     * @param name display name (e.g. the slide/sample)
     * @param nPositions number of tile positions
     * @param zCount z-planes per position (>= 1)
     * @param exposuresMs per-frame exposures in ms (one per angle/channel)
     */
    public record WorkflowEstimate(String name, int nPositions, int zCount, List<Double> exposuresMs) {
        public WorkflowEstimate {
            zCount = Math.max(1, zCount);
            exposuresMs = exposuresMs == null ? List.of() : List.copyOf(exposuresMs);
        }

        /** Total frames this workflow captures. */
        public long totalImages() {
            return (long) nPositions * zCount * exposuresMs.size();
        }
    }

    /** Result for one workflow plus a convenience accessor for its share of the batch. */
    public record WorkflowTime(String name, long totalImages, double seconds) {}

    /** The whole batch: per-workflow breakdown and the total. */
    public record BatchTime(List<WorkflowTime> workflows, double totalSeconds) {}

    /** Estimates one workflow's wall-clock seconds. */
    public static double estimateSeconds(WorkflowEstimate w) {
        if (w == null || w.nPositions() <= 0 || w.exposuresMs().isEmpty()) {
            return 0.0;
        }
        double framesExposure = 0.0;
        for (Double ms : w.exposuresMs()) {
            framesExposure += (ms == null ? 0.0 : ms) / 1000.0 + PER_FRAME_OVERHEAD_S;
        }
        double capture = (double) w.nPositions() * w.zCount() * framesExposure;
        double autofocus = (double) w.nPositions() * AF_PER_POSITION_S;
        double stitching = STITCH_BASE_S + w.totalImages() * STITCH_PER_IMAGE_S;
        return capture + autofocus + stitching;
    }

    /** Estimates the whole batch. */
    public static BatchTime estimateBatch(List<WorkflowEstimate> workflows) {
        List<WorkflowTime> perWorkflow = new ArrayList<>();
        double total = 0.0;
        if (workflows != null) {
            for (WorkflowEstimate w : workflows) {
                if (w == null) {
                    continue;
                }
                double s = estimateSeconds(w);
                total += s;
                perWorkflow.add(new WorkflowTime(w.name(), w.totalImages(), s));
            }
        }
        return new BatchTime(perWorkflow, total);
    }

    /** Formats seconds as a compact {@code Hh Mm Ss} / {@code Mm Ss} / {@code Ss} string. */
    public static String formatDuration(double seconds) {
        long s = Math.round(Math.max(0, seconds));
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) {
            return String.format("%dh %02dm %02ds", h, m, sec);
        }
        if (m > 0) {
            return String.format("%dm %02ds", m, sec);
        }
        return String.format("%ds", sec);
    }
}
