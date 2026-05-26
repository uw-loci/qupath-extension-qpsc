package qupath.ext.qpsc.ui.liveviewer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
 * <p>Samples are binned in Z (0.1 um) and capped at {@link #MAX_SAMPLES}. The
 * oldest insertion is evicted when capacity is exceeded. Cleared on XY moves
 * larger than the configured threshold (see {@code StageControlPanel}).
 *
 * <p>The model tracks a running metric min/max for autoscaling. Min/max are
 * not recomputed on bin overwrite -- the autoscale may briefly stay wider than
 * the current data after rapid changes, which is acceptable for visualization.
 */
public final class FocusTraceModel {

    public static final int MAX_SAMPLES = 500;
    public static final double Z_BIN_UM = 0.1;

    private final NavigableMap<Double, Double> samples = new TreeMap<>();
    private final Deque<Double> insertionOrder = new ArrayDeque<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private double runningMin = Double.POSITIVE_INFINITY;
    private double runningMax = Double.NEGATIVE_INFINITY;

    public synchronized void addSample(double z, double metric) {
        if (Double.isNaN(z) || Double.isNaN(metric)) return;
        double binnedZ = Math.round(z / Z_BIN_UM) * Z_BIN_UM;
        if (!samples.containsKey(binnedZ)) {
            insertionOrder.addLast(binnedZ);
            while (samples.size() >= MAX_SAMPLES) {
                Double oldest = insertionOrder.pollFirst();
                if (oldest != null) samples.remove(oldest);
            }
        }
        samples.put(binnedZ, metric);
        if (metric < runningMin) runningMin = metric;
        if (metric > runningMax) runningMax = metric;
        notifyListeners();
    }

    public synchronized void clear() {
        if (samples.isEmpty() && insertionOrder.isEmpty()) return;
        samples.clear();
        insertionOrder.clear();
        runningMin = Double.POSITIVE_INFINITY;
        runningMax = Double.NEGATIVE_INFINITY;
        notifyListeners();
    }

    /** Snapshot of samples ordered by Z (ascending). Each entry is {z, metric}. */
    public synchronized List<double[]> snapshot() {
        List<double[]> out = new ArrayList<>(samples.size());
        for (Map.Entry<Double, Double> e : samples.entrySet()) {
            out.add(new double[] {e.getKey(), e.getValue()});
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
