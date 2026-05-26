package qupath.ext.qpsc.ui.liveviewer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javafx.application.Platform;

/**
 * Static ring buffer of recent autofocus / Mark Z positions for the Live Viewer
 * Z-bar overlay.
 *
 * <p>Capacity is 3 -- the oldest tic is evicted on the fourth push, so any given
 * tic is visible during the next three AF/Mark events and disappears on the
 * fourth. Not persisted across sessions.
 *
 * <p>Listeners are notified on the FX thread. The service is thread-safe; pushes
 * may arrive from socket-thread AF callbacks or from the FX thread.
 */
public final class AfHistoryService {

    public static final int CAPACITY = 3;

    private static final Deque<Double> TICS = new ArrayDeque<>(CAPACITY);
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private AfHistoryService() {}

    /** Push a new tic; oldest evicted once capacity is exceeded. */
    public static synchronized void add(double z) {
        while (TICS.size() >= CAPACITY) {
            TICS.pollLast();
        }
        TICS.addFirst(z);
        notifyListeners();
    }

    /** Snapshot of tics, newest first. Index of the entry = its "age" (0 = newest). */
    public static synchronized List<Double> snapshot() {
        return new ArrayList<>(TICS);
    }

    public static synchronized void clear() {
        if (TICS.isEmpty()) return;
        TICS.clear();
        notifyListeners();
    }

    public static void addListener(Runnable listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    public static void removeListener(Runnable listener) {
        if (listener != null) LISTENERS.remove(listener);
    }

    private static void notifyListeners() {
        for (Runnable r : LISTENERS) {
            if (Platform.isFxApplicationThread()) {
                r.run();
            } else {
                Platform.runLater(r);
            }
        }
    }
}
