package qupath.ext.qpsc.ui.focus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.service.autofocus.AutofocusEvent;

/**
 * With every autofocus disabled, the progress dialogs say so instead of drawing the feed's
 * replay of an earlier search (from the Live Viewer or a previous run) as this acquisition's.
 */
class AutofocusCurvePaneOffTest {

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

    private static AutofocusEvent event(String type, Double z, Double score, String status) {
        AutofocusEvent e = new AutofocusEvent();
        e.type = type;
        e.method = "sweep";
        e.z = z;
        e.score = score;
        e.status = status;
        return e;
    }

    private static List<String> visibleLabels(AutofocusCurvePane pane) {
        List<String> out = new ArrayList<>();
        for (Node n : pane.getChildrenUnmodifiable()) {
            if (n instanceof Label l && l.isVisible() && !l.getText().isEmpty()) {
                out.add(l.getText());
            }
        }
        return out;
    }

    private static boolean plotVisible(AutofocusCurvePane pane) {
        for (Node n : pane.getChildrenUnmodifiable()) {
            if (n instanceof Pane p && p.getClass() == Pane.class) {
                return p.isVisible();
            }
        }
        throw new AssertionError("no plot pane");
    }

    @Test
    void anOffPaneSaysSoAndIgnoresAnEarlierSearch() throws Exception {
        AtomicReference<List<String>> labels = new AtomicReference<>();
        AtomicReference<Boolean> plot = new AtomicReference<>();
        onFx(() -> {
            AutofocusCurvePane pane = new AutofocusCurvePane();
            pane.showAutofocusOff();
            // The replay of a search that ran before this acquisition.
            pane.accept(event("start", null, null, null));
            pane.accept(event("sample", -40.0, 100.0, null));
            pane.accept(event("sample", -38.0, 140.0, null));
            pane.accept(event("end", -38.0, null, "ok"));
            labels.set(visibleLabels(pane));
            plot.set(plotVisible(pane));
        });
        assertEquals(List.of("Autofocus", AutofocusCurvePane.AUTOFOCUS_OFF_TEXT), labels.get());
        assertFalse(plot.get());
    }

    @Test
    void bothProgressDialogsCheckThePreferenceBeforeSubscribing() throws Exception {
        for (String file : new String[] {
            "src/main/java/qupath/ext/qpsc/ui/acquisition/DualProgressDialog.java",
            "src/main/java/qupath/ext/qpsc/ui/components/WorkflowDialogs.java"
        }) {
            String src = Files.readString(Path.of(file));
            int check = src.indexOf("getDisableAllAutofocus()");
            int off = src.indexOf("showAutofocusOff()");
            int subscribe = src.indexOf("AutofocusFeed.getInstance()");
            assertTrue(check > 0 && off > check && subscribe > off, file);
        }
    }
}
