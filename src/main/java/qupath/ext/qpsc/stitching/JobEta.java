package qupath.ext.qpsc.stitching;

import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * Time left for stitch jobs, from their targets' estimates. Pure.
 *
 * <p>A job stitches its targets (angles or channels, then derived outputs) on a pool of
 * {@code slots} writers: the running targets hold slots for their remaining time, and each waiting
 * target takes the first slot to free up. The job ends when the last slot does. The queue runs
 * jobs one after another on a single thread, so its time left is the running job's plus the
 * queued jobs' predictions.
 */
public final class JobEta {

    private JobEta() {}

    /**
     * When the last of {@code running} (remaining seconds) and {@code waiting} (whole durations,
     * started in order) finishes on {@code slots} writers. NaN when any input is unknown.
     */
    public static double makespan(double[] running, double[] waiting, int slots) {
        int k = Math.max(1, slots);
        PriorityQueue<Double> free = new PriorityQueue<>();
        double end = 0;
        double[] sorted = running.clone();
        Arrays.sort(sorted);
        for (double r : sorted) {
            if (Double.isNaN(r)) {
                return Double.NaN;
            }
            free.add(Math.max(0, r));
            end = Math.max(end, r);
        }
        // More running than slots cannot happen in practice; keep the soonest k.
        while (free.size() > k) {
            free.poll();
        }
        while (free.size() < k) {
            free.add(0.0);
        }
        for (double w : waiting) {
            if (Double.isNaN(w)) {
                return Double.NaN;
            }
            double start = free.poll();
            double finish = start + Math.max(0, w);
            free.add(finish);
            end = Math.max(end, finish);
        }
        return end;
    }

    /** Sum of the queue: the running jobs' time left, then every queued job's prediction. */
    public static double queueSeconds(double[] runningJobs, double[] queuedJobs) {
        double total = 0;
        for (double r : runningJobs) {
            if (Double.isNaN(r)) {
                return Double.NaN;
            }
            total = Math.max(total, r);
        }
        for (double q : queuedJobs) {
            if (Double.isNaN(q)) {
                return Double.NaN;
            }
            total += q;
        }
        return total;
    }
}
