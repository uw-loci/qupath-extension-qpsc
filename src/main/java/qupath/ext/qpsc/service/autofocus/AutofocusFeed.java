package qupath.ext.qpsc.service.autofocus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;

/**
 * Every autofocus the microscope server runs, as it runs: the one place the operator's displays
 * (the Live Viewer's Autofocus button and Z-bar trace, the acquisition progress dialog) learn
 * that a search started, see each scored Z and hear how it ended.
 *
 * <p>Polls AFTRACE on the auxiliary socket every {@value #POLL_MS} ms, but only while at least
 * one listener is subscribed, and hands each event to every listener on the FX thread in server
 * order. A listener subscribed while a search is running is first given that search's events so
 * far, so a display opened mid-search draws the whole curve. An older server that does not
 * answer AFTRACE simply produces no events.
 */
public final class AutofocusFeed {

    private static final Logger logger = LoggerFactory.getLogger(AutofocusFeed.class);

    static final long POLL_MS = 400;

    /** Events kept for the search in progress, replayed to a display that opens mid-search. */
    private static final int MAX_RUN_EVENTS = 5000;

    private static final AutofocusFeed INSTANCE = new AutofocusFeed();

    public static AutofocusFeed getInstance() {
        return INSTANCE;
    }

    private final List<Consumer<AutofocusEvent>> listeners = new CopyOnWriteArrayList<>();
    private final ReadOnlyObjectWrapper<AutofocusEvent> activeSearch = new ReadOnlyObjectWrapper<>();

    // FX thread only: the latest search's events, for late subscribers.
    private final List<AutofocusEvent> currentRun = new ArrayList<>();

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> task;

    /** Last sequence number received; -1 until the first poll has synced with the server. */
    private long lastSeq = -1;

    private AutofocusFeed() {}

    /**
     * Starts delivering autofocus events to {@code listener} on the FX thread.
     *
     * @return call it to stop
     */
    public Runnable subscribe(Consumer<AutofocusEvent> listener) {
        // Replay and join on the FX thread, where events are delivered, so nothing published
        // in between reaches the listener twice or not at all.
        runOnFx(() -> {
            for (AutofocusEvent e : List.copyOf(currentRun)) {
                deliver(listener, e);
            }
            listeners.add(listener);
            startPolling();
        });
        return () -> runOnFx(() -> unsubscribe(listener));
    }

    private static void runOnFx(Runnable r) {
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    /** The start event of the search running on the microscope, or null. FX thread. */
    public ReadOnlyObjectProperty<AutofocusEvent> activeSearchProperty() {
        return activeSearch.getReadOnlyProperty();
    }

    private void unsubscribe(Consumer<AutofocusEvent> listener) {
        listeners.remove(listener);
        if (listeners.isEmpty()) {
            stopPolling();
        }
    }

    private synchronized void startPolling() {
        if (task != null) {
            return;
        }
        if (executor == null) {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Autofocus-Feed");
                t.setDaemon(true);
                return t;
            });
        }
        task = executor.scheduleWithFixedDelay(this::pollSafely, 0, POLL_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void stopPolling() {
        if (task != null && listeners.isEmpty()) {
            task.cancel(false);
            task = null;
        }
    }

    private void pollSafely() {
        try {
            poll();
        } catch (IOException e) {
            logger.debug("Autofocus feed: AFTRACE failed: {}", e.getMessage());
        } catch (RuntimeException e) {
            logger.warn("Autofocus feed: poll failed", e);
        }
    }

    private void poll() throws IOException {
        MicroscopeController mc = MicroscopeController.getInstance();
        if (mc == null || !mc.isConnected()) {
            return;
        }
        MicroscopeSocketClient client = mc.getSocketClient();
        if (lastSeq < 0) {
            sync(client);
            return;
        }
        List<AutofocusEvent> batch = new ArrayList<>();
        AutofocusTraceReply reply;
        do {
            reply = client.getAutofocusEvents(lastSeq);
            if (reply == null) {
                return;
            }
            if (reply.seq < lastSeq) {
                // The server restarted: its numbering began again.
                lastSeq = -1;
                return;
            }
            if (reply.first > lastSeq + 1 && lastSeq > 0) {
                logger.debug("Autofocus feed fell behind: events {}-{} were dropped", lastSeq + 1, reply.first - 1);
            }
            batch.addAll(reply.events);
            if (!reply.events.isEmpty()) {
                lastSeq = reply.events.get(reply.events.size() - 1).seq;
            }
        } while (reply.more && !reply.events.isEmpty());
        lastSeq = Math.max(lastSeq, reply.seq);
        publish(batch, reply.active);
    }

    /** First poll: catch up to the server's newest event, keeping only the running search's. */
    private void sync(MicroscopeSocketClient client) throws IOException {
        AutofocusTraceReply head = client.getAutofocusEvents(0);
        if (head == null) {
            return;
        }
        List<AutofocusEvent> runEvents = new ArrayList<>();
        if (head.active != null) {
            long since = Math.max(0, head.active.seq - 1);
            AutofocusTraceReply page;
            do {
                page = client.getAutofocusEvents(since);
                if (page == null) {
                    return;
                }
                for (AutofocusEvent e : page.events) {
                    if (e.run == head.active.run) {
                        runEvents.add(e);
                    }
                }
                if (!page.events.isEmpty()) {
                    since = page.events.get(page.events.size() - 1).seq;
                }
            } while (page.more && !page.events.isEmpty());
            lastSeq = Math.max(since, page.seq);
        } else {
            lastSeq = head.seq;
        }
        publish(runEvents, head.active);
    }

    private void publish(List<AutofocusEvent> batch, AutofocusEvent active) {
        if (batch.isEmpty() && sameRun(active, activeSearch.get())) {
            return;
        }
        Platform.runLater(() -> {
            for (AutofocusEvent e : batch) {
                if (e.isStart()) {
                    currentRun.clear();
                }
                if (currentRun.size() < MAX_RUN_EVENTS) {
                    currentRun.add(e);
                }
                for (Consumer<AutofocusEvent> l : listeners) {
                    deliver(l, e);
                }
            }
            activeSearch.set(active);
        });
    }

    private static boolean sameRun(AutofocusEvent a, AutofocusEvent b) {
        if (a == null || b == null) return a == b;
        return a.run == b.run;
    }

    private static void deliver(Consumer<AutofocusEvent> listener, AutofocusEvent e) {
        try {
            listener.accept(e);
        } catch (RuntimeException ex) {
            logger.warn("Autofocus feed listener failed on a {} event", e.type, ex);
        }
    }
}
