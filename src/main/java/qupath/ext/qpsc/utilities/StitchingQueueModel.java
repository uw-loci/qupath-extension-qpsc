package qupath.ext.qpsc.utilities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The stitching queue as data: every stitch job of this QuPath session, in the order it was
 * enqueued, with its state, its latest status line, the project entries it produced and
 * its timing. Pure Java so it is unit-tested without a display; the
 * {@code StitchingQueueWindow} renders it.
 *
 * <p>All methods are synchronized: jobs are enqueued from the acquisition worker, started and
 * updated from the stitching threads, and read from the FX thread.
 */
public final class StitchingQueueModel {

    /** Where a job is in its life. */
    public enum State {
        /** Enqueued by the acquisition; the single stitching thread has not reached it yet. */
        QUEUED,
        /** Stitching, merging or importing. */
        RUNNING,
        /** Imported; its outputs are listed. */
        DONE,
        /** Stitching or import failed; the error is the status line. */
        FAILED
    }

    /** One stitch job. Immutable snapshot; the model keeps its own mutable record. */
    public record Job(
            String id,
            String displayName,
            State state,
            String status,
            List<String> outputs,
            long enqueuedAtMs,
            long startedAtMs,
            long finishedAtMs) {

        /** Seconds the job has been running, or ran; 0 while queued. */
        public long elapsedSeconds(long nowMs) {
            if (startedAtMs <= 0) {
                return 0;
            }
            long end = finishedAtMs > 0 ? finishedAtMs : nowMs;
            return Math.max(0, (end - startedAtMs) / 1000);
        }

        public boolean isActive() {
            return state == State.QUEUED || state == State.RUNNING;
        }
    }

    private static final class Record {
        final String id;
        final String displayName;
        State state;
        String status;
        final List<String> outputs = new ArrayList<>();
        final long enqueuedAtMs;
        long startedAtMs;
        long finishedAtMs;

        Record(String id, String displayName, State state, String status, long now) {
            this.id = id;
            this.displayName = displayName;
            this.state = state;
            this.status = status;
            this.enqueuedAtMs = now;
        }

        Job snapshot() {
            return new Job(
                    id,
                    displayName,
                    state,
                    status,
                    Collections.unmodifiableList(new ArrayList<>(outputs)),
                    enqueuedAtMs,
                    startedAtMs,
                    finishedAtMs);
        }
    }

    private final LongSupplier clockMs;
    private final Map<String, Record> jobs = new LinkedHashMap<>();

    public StitchingQueueModel(LongSupplier clockMs) {
        this.clockMs = clockMs;
    }

    /**
     * A job the acquisition has handed to the stitching queue. Idempotent: enqueueing a known
     * id keeps its record (a finished job re-enqueued, as a re-stitch, starts over).
     */
    public synchronized void enqueue(String id, String displayName) {
        Record r = jobs.get(id);
        if (r == null || !r.snapshot().isActive()) {
            jobs.put(id, new Record(id, displayName, State.QUEUED, "Queued", clockMs.getAsLong()));
        }
    }

    /** The stitching thread has reached the job. Enqueues it first when it was never enqueued. */
    public synchronized void start(String id, String displayName) {
        Record r = jobs.get(id);
        if (r == null || !r.snapshot().isActive()) {
            r = new Record(id, displayName, State.QUEUED, "Queued", clockMs.getAsLong());
            jobs.put(id, r);
        }
        r.state = State.RUNNING;
        r.status = "Starting";
        r.startedAtMs = clockMs.getAsLong();
    }

    /** A progress line for a running job; ignored for unknown or finished jobs. */
    public synchronized void updateStatus(String id, String status) {
        Record r = jobs.get(id);
        if (r != null && r.snapshot().isActive()) {
            if (r.state == State.QUEUED) {
                r.state = State.RUNNING;
                r.startedAtMs = clockMs.getAsLong();
            }
            r.status = status;
        }
    }

    /** A project entry (by name) the job produced. */
    public synchronized void recordOutput(String id, String entryName) {
        Record r = jobs.get(id);
        if (r != null && entryName != null && !r.outputs.contains(entryName)) {
            r.outputs.add(entryName);
        }
    }

    /** The job's outputs are imported. */
    public synchronized void complete(String id) {
        Record r = jobs.get(id);
        if (r != null && r.snapshot().isActive()) {
            r.state = State.DONE;
            r.status = "Done";
            r.finishedAtMs = clockMs.getAsLong();
        }
    }

    /** The job failed; {@code error} becomes its status line. */
    public synchronized void fail(String id, String error) {
        Record r = jobs.get(id);
        if (r != null && r.snapshot().isActive()) {
            r.state = State.FAILED;
            r.status = error == null || error.isBlank() ? "Failed" : error;
            r.finishedAtMs = clockMs.getAsLong();
        }
    }

    /** Every job in enqueue order. */
    public synchronized List<Job> jobs() {
        List<Job> out = new ArrayList<>(jobs.size());
        for (Record r : jobs.values()) {
            out.add(r.snapshot());
        }
        return out;
    }

    public synchronized Job job(String id) {
        Record r = jobs.get(id);
        return r == null ? null : r.snapshot();
    }

    /** Queued plus running. */
    public synchronized int activeCount() {
        int n = 0;
        for (Record r : jobs.values()) {
            if (r.snapshot().isActive()) {
                n++;
            }
        }
        return n;
    }

    public synchronized int failedCount() {
        int n = 0;
        for (Record r : jobs.values()) {
            if (r.state == State.FAILED) {
                n++;
            }
        }
        return n;
    }

    /** True when no job is queued or running. */
    public synchronized boolean isIdle() {
        return activeCount() == 0;
    }

    /** Drop finished jobs (the window's Clear finished). Active ones stay. */
    public synchronized void clearFinished() {
        jobs.values().removeIf(r -> !r.snapshot().isActive());
    }

    /** One line for the window title or a log. */
    public synchronized String summary() {
        int queued = 0, running = 0, done = 0, failed = 0;
        for (Record r : jobs.values()) {
            switch (r.state) {
                case QUEUED -> queued++;
                case RUNNING -> running++;
                case DONE -> done++;
                case FAILED -> failed++;
            }
        }
        StringBuilder sb = new StringBuilder();
        if (running > 0) {
            sb.append(running).append(" stitching");
        }
        if (queued > 0) {
            sb.append(sb.length() > 0 ? ", " : "").append(queued).append(" queued");
        }
        if (done > 0) {
            sb.append(sb.length() > 0 ? ", " : "").append(done).append(" done");
        }
        if (failed > 0) {
            sb.append(sb.length() > 0 ? ", " : "").append(failed).append(" failed");
        }
        return sb.length() == 0 ? "No stitching jobs" : sb.toString();
    }
}
