package qupath.ext.qpsc.ui.focus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleConsumer;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.service.autofocus.AutofocusEvent;

/** The "Scanning... stage at Z" line the autofocus window shows while a sweep has no scores yet. */
class AutofocusCurvePaneLiveZTest {

    @BeforeAll
    static void startToolkit() {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            Assumptions.assumeTrue(started.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
        } catch (IllegalStateException alreadyRunning) {
            // started by another test in this JVM
        } catch (UnsupportedOperationException | InterruptedException noDisplay) {
            Assumptions.abort("No JavaFX toolkit available: " + noDisplay);
        }
    }

    /** A Z feed the test drives by hand; records whether anyone is subscribed. */
    private static final class FakeZ implements AutofocusCurvePane.ZFeed {
        DoubleConsumer listener;
        int subscriptions;

        @Override
        public Runnable subscribe(DoubleConsumer onZ) {
            subscriptions++;
            listener = onZ;
            return () -> listener = null;
        }
    }

    private static AutofocusEvent event(String type) {
        AutofocusEvent e = new AutofocusEvent();
        e.type = type;
        e.method = "streaming";
        return e;
    }

    private static AutofocusEvent sample(double z, double score) {
        AutofocusEvent e = event("sample");
        e.z = z;
        e.score = score;
        return e;
    }

    private static void onFx(Runnable work) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS), "FX work timed out");
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    /** The visible label that starts with "Scanning", or null. */
    private static String scanningLine(AutofocusCurvePane pane) {
        for (Node n : pane.getChildrenUnmodifiable()) {
            if (n instanceof Label l && l.isVisible() && l.getText().startsWith("Scanning")) {
                return l.getText();
            }
        }
        return null;
    }

    @Test
    void showsTheStageZFromStartToEndOnly() throws Exception {
        FakeZ feed = new FakeZ();
        AtomicReference<AutofocusCurvePane> pane = new AtomicReference<>();
        AtomicReference<String> line = new AtomicReference<>();
        onFx(() -> {
            pane.set(AutofocusCurvePane.withLiveZ(feed));
            assertNull(scanningLine(pane.get()), "nothing before a search starts");
            assertEquals(0, feed.subscriptions, "no position polling before a search starts");

            pane.get().accept(event("start"));
            assertEquals(1, feed.subscriptions);
            line.set(scanningLine(pane.get()));
        });
        assertEquals(AutofocusCurvePane.scanningText(Double.NaN, false), line.get());

        feed.listener.accept(-123.456); // from the poller thread
        onFx(() -> line.set(scanningLine(pane.get())));
        assertTrue(line.get().contains("Z -123.46 um"), line.get());
        assertTrue(line.get().contains("appears when the sweep ends"), line.get());

        onFx(() -> {
            pane.get().accept(sample(-120.0, 5.0));
            line.set(scanningLine(pane.get()));
        });
        // The line keeps going between sweeps (a retry, or the next attempt)
        assertTrue(line.get().contains("Z -123.46 um"), line.get());
        feed.listener.accept(-118.0);
        onFx(() -> line.set(scanningLine(pane.get())));
        assertTrue(line.get().contains("Z -118.00 um") && line.get().contains("grows"), line.get());

        onFx(() -> {
            pane.get().accept(event("end"));
            line.set(scanningLine(pane.get()));
        });
        assertNull(line.get(), "hidden once the search ends");
        assertNull(feed.listener, "position updates stopped at the end");
    }

    @Test
    void disposeStopsTheUpdatesMidSearch() throws Exception {
        FakeZ feed = new FakeZ();
        onFx(() -> {
            AutofocusCurvePane pane = AutofocusCurvePane.withLiveZ(feed);
            pane.accept(event("start"));
            pane.dispose();
            assertNull(scanningLine(pane));
        });
        assertNull(feed.listener);
    }

    @Test
    void theDefaultPaneNeverReadsTheStage() throws Exception {
        AtomicReference<String> line = new AtomicReference<>("unset");
        onFx(() -> {
            AutofocusCurvePane pane = new AutofocusCurvePane();
            pane.accept(event("start"));
            line.set(scanningLine(pane));
        });
        assertNull(line.get());
    }

    @Test
    void fallbackSnapsFarFromTheSweepAreNotJoinedToIt() {
        // 2026-11-06: a sweep sampled every ~0.9 um up to -38.4, then the fallback snapped at
        // -7.8 and +41.6; the plot drew straight lines out to +41.6.
        java.util.List<double[]> byZ = new java.util.ArrayList<>();
        for (double z = -108.6; z <= -38.4; z += 0.9) {
            byZ.add(new double[] {z, 1.0});
        }
        byZ.add(new double[] {-7.8, 1.0});
        byZ.add(new double[] {41.6, 1.0});
        var runs = AutofocusCurvePane.contiguousRuns(byZ);
        assertEquals(3, runs.size());
        assertEquals(byZ.size() - 2, runs.get(0).size());
        assertEquals(1, runs.get(1).size());
        assertEquals(1, runs.get(2).size());
    }

    @Test
    void anEvenSweepIsOneLine() {
        java.util.List<double[]> byZ = new java.util.ArrayList<>();
        for (double z = 0; z <= 20; z += 1.0) {
            byZ.add(new double[] {z, z});
        }
        assertEquals(1, AutofocusCurvePane.contiguousRuns(byZ).size());
        assertEquals(
                1,
                AutofocusCurvePane.contiguousRuns(java.util.List.of(new double[] {1, 1}))
                        .size());
    }

    @Test
    void scanningTextIsAscii() {
        for (String s : new String[] {
            AutofocusCurvePane.scanningText(Double.NaN, false), AutofocusCurvePane.scanningText(-5.0, true)
        }) {
            assertFalse(s.chars().anyMatch(c -> c > 127), s);
        }
    }
}
