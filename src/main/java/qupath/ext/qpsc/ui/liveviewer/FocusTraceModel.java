package qupath.ext.qpsc.ui.liveviewer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javafx.application.Platform;

/**
 * Observable per-session store of (Z, focus-metric) samples for the Live
 * Viewer's sideways focus-trace overlay.
 *
 * <p><b>Local replacement of stale data.</b> When a new sample arrives at Z =
 * z, any existing sample within {@link #REPLACEMENT_WINDOW_UM} of z that is
 * older than {@link #STALE_AGE_MS} is removed before the new sample lands.
 * This solves a class of "old data hides new data" problems -- e.g. a
 * streaming autofocus run drops dense samples (often at very different metric
 * magnitudes than manual fine focus), and after a brief settling time the
 * older manual samples in the AF range are flushed and the autoscale snaps
 * to the new data. Recent samples (same scroll session) are preserved so the
 * trace can accumulate without thrashing.
 *
 * <p>The running metric min/max are <b>recomputed</b> on every mutation so the
 * autoscale shrinks back down once stale high-magnitude samples are evicted.
 *
 * <p>Samples are binned in Z (0.1 um) and capped at {@link #MAX_SAMPLES}.
 * Oldest by timestamp is evicted when capacity is exceeded. Cleared entirely
 * on XY moves larger than the configured threshold (see
 * {@code StageControlPanel}).
 */
public final class FocusTraceModel {

    public static final int MAX_SAMPLES = 500;
    public static final double Z_BIN_UM = 0.1;
    /** Stale samples within this many um of a new sample are evicted. */
    public static final double REPLACEMENT_WINDOW_UM = 1.0;
    /** A sample is considered "stale" once it is older than this many ms. */
    public static final long STALE_AGE_MS = 5_000;

    private static final class Sample {
        final double metric;
        final long timestampMs;

        Sample(double metric, long timestampMs) {
            this.metric = metric;
            this.timestampMs = timestampMs;
        }
    }

    private final NavigableMap<Double, Sample> samples = new TreeMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private double runningMin = Double.POSITIVE_INFINITY;
    private double runningMax = Double.NEGATIVE_INFINITY;

    public synchronized void addSample(double z, double metric) {
        if (Double.isNaN(z) || Double.isNaN(metric)) return;
        long now = System.currentTimeMillis();

        // Evict stale neighbors within the replacement window. Recent samples
        // are kept so a continuous scroll/AF session can accumulate detail;
        // older samples in the same Z neighborhood get displaced by new data.
        double winLo = z - REPLACEMENT_WINDOW_UM;
        double winHi = z + REPLACEMENT_WINDOW_UM;
        NavigableMap<Double, Sample> neighbors = samples.subMap(winLo, true, winHi, true);
        neighbors.entrySet().removeIf(e -> (now - e.getValue().timestampMs) > STALE_AGE_MS);

        // Bin Z so very-close samples coalesce (replaces value, refreshes ts).
        double binnedZ = Math.round(z / Z_BIN_UM) * Z_BIN_UM;
        samples.put(binnedZ, new Sample(metric, now));

        // Cap at MAX_SAMPLES, evicting by oldest timestamp first.
        while (samples.size() > MAX_SAMPLES) {
            Map.Entry<Double, Sample> oldest = null;
            for (Map.Entry<Double, Sample> e : samples.entrySet()) {
                if (oldest == null || e.getValue().timestampMs < oldest.getValue().timestampMs) {
                    oldest = e;
                }
            }
            if (oldest == null) break;
            samples.remove(oldest.getKey());
        }

        recomputeMinMax();
        notifyListeners();
    }

    public synchronized void clear() {
        if (samples.isEmpty()) return;
        samples.clear();
        runningMin = Double.POSITIVE_INFINITY;
        runningMax = Double.NEGATIVE_INFINITY;
        notifyListeners();
    }

    /** Snapshot of samples ordered by Z (ascending). Each entry is {z, metric}. */
    public synchronized List<double[]> snapshot() {
        List<double[]> out = new ArrayList<>(samples.size());
        for (Map.Entry<Double, Sample> e : samples.entrySet()) {
            out.add(new double[] {e.getKey(), e.getValue().metric});
        }
        return out;
    }

    public synchronized double getRunningMin() {
        return runningMin;
    }

    public synchronized double getRunningMax() {
        return runningMax;
    }

    public synchronized boolean isEmpty() {
        return samples.isEmpty();
    }

    public void addListener(Runnable listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        if (listener != null) listeners.remove(listener);
    }

    private void recomputeMinMax() {
        if (samples.isEmpty()) {
            runningMin = Double.POSITIVE_INFINITY;
            runningMax = Double.NEGATIVE_INFINITY;
            return;
        }
        double mn = Double.POSITIVE_INFINITY;
        double mx = Double.NEGATIVE_INFINITY;
        for (Sample s : samples.values()) {
            if (s.metric < mn) mn = s.metric;
            if (s.metric > mx) mx = s.metric;
        }
        runningMin = mn;
        runningMax = mx;
    }

    private void notifyListeners() {
        for (Runnable r : listeners) {
            if (Platform.isFxApplicationThread()) {
                r.run();
            } else {
                Platform.runLater(r);
            }
        }
    }
}
