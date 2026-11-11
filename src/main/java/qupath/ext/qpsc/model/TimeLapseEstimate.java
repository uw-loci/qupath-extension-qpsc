package qupath.ext.qpsc.model;

/**
 * Time left in a time-lapse acquisition, for the progress windows.
 *
 * <p>The server repeats every position of a region at each timepoint, starting timepoints
 * {@code intervalSec} apart, or back to back when one takes longer than the interval. A
 * time per file times the files left therefore says nothing about the waits between
 * timepoints, and a count of one timepoint's files runs out after the first timepoint.
 */
public final class TimeLapseEstimate {

    private TimeLapseEstimate() {}

    /**
     * The timepoint (0-based) that {@code filesDone} of a region's files has reached.
     *
     * @param filesDone files of the region done so far
     * @param expectedFiles files of the region over all its timepoints
     * @param timepoints timepoints in the region
     */
    public static int timepointOf(int filesDone, int expectedFiles, int timepoints) {
        if (timepoints <= 1 || expectedFiles <= 0) {
            return 0;
        }
        int perTimepoint = Math.max(1, expectedFiles / timepoints);
        return Math.min(timepoints - 1, Math.max(0, filesDone) / perTimepoint);
    }

    /**
     * Time left for one region, in ms.
     *
     * <p>After the current timepoint, each remaining one costs the interval or its own
     * acquisition time, whichever is longer, and the last one its acquisition time. The next
     * start is no earlier than the current timepoint's start plus the interval, and no
     * earlier than the end of the current timepoint.
     *
     * @param timepoints timepoints in the region
     * @param intervalSec interval between timepoint starts, in seconds
     * @param filesPerTimepoint files one timepoint writes
     * @param filesDone files of the region done so far
     * @param msPerFile measured acquisition time per file (waits excluded)
     * @param msSinceTimepointStart time since the current timepoint started
     */
    public static long remainingMs(
            int timepoints,
            double intervalSec,
            int filesPerTimepoint,
            int filesDone,
            double msPerFile,
            long msSinceTimepointStart) {
        int perTp = Math.max(1, filesPerTimepoint);
        int tp = Math.min(Math.max(1, timepoints) - 1, Math.max(0, filesDone) / perTp);
        int leftInCurrent = Math.max(0, perTp * (tp + 1) - filesDone);
        double finishCurrent = leftInCurrent * msPerFile;
        int after = timepoints - tp - 1;
        if (after <= 0) {
            return Math.round(finishCurrent);
        }
        double intervalMs = intervalSec * 1000.0;
        double tpMs = perTp * msPerFile;
        double nextStart = Math.max(finishCurrent, intervalMs - msSinceTimepointStart);
        return Math.round(nextStart + (after - 1) * Math.max(intervalMs, tpMs) + tpMs);
    }

    /**
     * One run of all timepoints: the whole region ({@link TimeLapseOrder#REGION}), one
     * position, or one Z plane of a position. Each series runs its timepoints the interval
     * apart from its own start, and the next series starts straight after.
     *
     * @param count series in the region
     * @param filesPerTimepoint files one timepoint of one series writes
     */
    public record Series(int count, int filesPerTimepoint) {}

    /**
     * The series of a region for an order.
     *
     * @param filesPerTimepoint files one timepoint of the whole region writes
     * @param filesPerPosition files one position writes in one timepoint (every plane)
     * @param zPlanes planes in the Z-stack (1 without one)
     */
    public static Series series(TimeLapseOrder order, int filesPerTimepoint, int filesPerPosition, int zPlanes) {
        int perRegion = Math.max(1, filesPerTimepoint);
        int perPosition = Math.max(1, Math.min(filesPerPosition, perRegion));
        int positions = Math.max(1, perRegion / perPosition);
        int planes = Math.max(1, zPlanes);
        if (order == TimeLapseOrder.POSITION || (order == TimeLapseOrder.PLANE && planes == 1)) {
            return new Series(positions, perPosition);
        }
        if (order == TimeLapseOrder.PLANE) {
            return new Series(positions * planes, Math.max(1, perPosition / planes));
        }
        return new Series(1, perRegion);
    }

    /** The timepoint (0-based) {@code filesDone} has reached within its series. */
    public static int timepointOf(int filesDone, Series series, int timepoints) {
        if (timepoints <= 1) {
            return 0;
        }
        int perTp = Math.max(1, series.filesPerTimepoint());
        long perSeries = (long) perTp * timepoints;
        long done = Math.max(0, filesDone);
        if (done >= perSeries * series.count()) {
            return timepoints - 1;
        }
        return (int) ((done % perSeries) / perTp);
    }

    /**
     * Time left for one region, in ms: the rest of the current series (see
     * {@link #remainingMs(int, double, int, int, double, long)}), then every series after it
     * in full.
     */
    public static long remainingMs(
            Series series,
            int timepoints,
            double intervalSec,
            int filesDone,
            double msPerFile,
            long msSinceTimepointStart) {
        int perTp = Math.max(1, series.filesPerTimepoint());
        int perSeries = perTp * Math.max(1, timepoints);
        int done = Math.max(0, filesDone);
        int current = Math.min(series.count() - 1, done / perSeries);
        int doneInSeries = done - current * perSeries;
        long rest = remainingMs(timepoints, intervalSec, perTp, doneInSeries, msPerFile, msSinceTimepointStart);
        int after = Math.max(0, series.count() - current - 1);
        return rest + after * remainingMs(timepoints, intervalSec, perTp, 0, msPerFile, 0);
    }
}
