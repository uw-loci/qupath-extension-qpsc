package qupath.ext.qpsc.ui.acquisition;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.control.Spinner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.preferences.PersistentPreferences;

/**
 * A time-lapse cannot be set to one timepoint, and the timepoint box takes digits only.
 * On 2026-11-19 a Bounded Acquisition with time-lapse on kept the default of 1 timepoint: it
 * sent only --interval and took a single pass.
 */
class TimepointsSpinnerTest {

    private int saved;

    @BeforeAll
    static void startToolkit() {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            Assumptions.assumeTrue(started.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
        } catch (IllegalStateException alreadyRunning) {
            // Another test in this JVM started it; fine.
        } catch (UnsupportedOperationException | InterruptedException noDisplay) {
            Assumptions.abort("No JavaFX toolkit available: " + noDisplay);
        }
    }

    @BeforeEach
    void save() {
        saved = PersistentPreferences.getTimeLapseTimepoints();
    }

    @AfterEach
    void restore() {
        PersistentPreferences.setTimeLapseTimepoints(saved);
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> task) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                out.set(task.call());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(60, TimeUnit.SECONDS), "JavaFX thread did not answer");
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        return out.get();
    }

    @Test
    void aStoredOneIsRaisedToTwo() throws Exception {
        PersistentPreferences.setTimeLapseTimepoints(1);
        int shown = onFx(() -> AcquisitionDialogBuilder.newTimepointsSpinner().getValue());
        assertEquals(2, shown);
        assertEquals(2, PersistentPreferences.getTimeLapseTimepoints(), "the preference matches the box");
    }

    @Test
    void aStoredCountIsKept() throws Exception {
        PersistentPreferences.setTimeLapseTimepoints(12);
        assertEquals(12, (int)
                onFx(() -> AcquisitionDialogBuilder.newTimepointsSpinner().getValue()));
        assertEquals(12, PersistentPreferences.getTimeLapseTimepoints());
    }

    @Test
    void onlyDigitsCanBeTyped() throws Exception {
        PersistentPreferences.setTimeLapseTimepoints(5);
        String[] texts = onFx(() -> {
            Spinner<Integer> s = AcquisitionDialogBuilder.newTimepointsSpinner();
            s.getEditor().setText("10");
            String digits = s.getEditor().getText();
            s.getEditor().setText("10.");
            String withDot = s.getEditor().getText();
            s.getEditor().setText("5 tp");
            String withWord = s.getEditor().getText();
            s.commitValue();
            return new String[] {digits, withDot, withWord, String.valueOf(s.getValue())};
        });
        assertEquals("10", texts[0]);
        assertEquals("10", texts[1], "a dot is refused");
        assertEquals("10", texts[2], "letters are refused");
        assertEquals("10", texts[3], "the typed count is committed, not reverted");
    }
}
