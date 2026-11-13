package qupath.ext.qpsc.ui.acquisition;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A Z-stack or time-lapse section that is switched on shows a blue title bar, even collapsed. */
class SectionOnHighlightTest {

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

    private static <T> T onFx(java.util.concurrent.Callable<T> work) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                out.set(work.call());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        return out.get();
    }

    @Test
    void theTitleTurnsBlueWhenOnAndBackWhenOff() throws Exception {
        SimpleBooleanProperty on = new SimpleBooleanProperty(true);
        TitledPane pane = onFx(() -> {
            TitledPane p = new TitledPane("TIME-LAPSE OPTIONS", new Label("content"));
            p.setExpanded(false);
            AcquisitionDialogBuilder.highlightTitleWhenOn(p, on);
            new Scene(new VBox(p), 400, 200);
            p.applyCss();
            p.layout();
            return p;
        });
        // The skin listener re-applies on a later pulse.
        onFx(() -> null);
        String styleOn = onFx(() -> {
            Node title = pane.lookup(".title");
            return title == null ? null : title.getStyle();
        });
        assertEquals(AcquisitionDialogBuilder.SECTION_ON_TITLE_STYLE, styleOn);

        String styleOff = onFx(() -> {
            on.set(false);
            return pane.lookup(".title").getStyle();
        });
        assertEquals("", styleOff);
    }
}
