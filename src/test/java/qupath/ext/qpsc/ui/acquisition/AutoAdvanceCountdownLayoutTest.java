package qupath.ext.qpsc.ui.acquisition;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.preferences.MultiSlideAcquisitionMode;

/**
 * The countdown must not change the size of the button it will press. It used to append
 * " (auto 5s)" to the label, which the Multi-Tile Alignment Refinement row of four buttons cut
 * off as "Solve & Save (au...". It now outlines the button outside its bounds and puts the
 * seconds in the window title.
 */
class AutoAdvanceCountdownLayoutTest {

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

    @AfterEach
    void disarm() {
        AutoAdvanceController.disarmSession();
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
    void countdownKeepsTheLabelAndTheButtonSizeAndShowsSecondsInTheTitle() throws Exception {
        AutoAdvanceController.armSession(MultiSlideAcquisitionMode.AUTOMATIC_WITH_OVERRIDE, 30);
        String style = "-fx-font-weight: bold; -fx-base: #4CAF50; -fx-text-fill: white;";
        Object[] r = onFx(() -> {
            Button solve = new Button("Solve & Save");
            solve.setStyle(style);
            Stage stage = new Stage();
            stage.setTitle("Multi-Tile Alignment Refinement");
            stage.setScene(new Scene(new HBox(10, new Button("Select tile"), solve, new Button("Skip refinement"))));
            stage.show();
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            double w = solve.prefWidth(-1);
            double h = solve.prefHeight(-1);
            AutoAdvanceController.attach(stage, solve);
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            Object[] during = {
                solve.getText(), solve.prefWidth(-1), solve.prefHeight(-1), stage.getTitle(), solve.getStyle()
            };
            stage.close();
            return new Object[] {w, h, during, solve.getStyle(), stage.getTitle()};
        });
        Object[] during = (Object[]) r[2];
        assertEquals("Solve & Save", during[0], "the label is never changed");
        assertEquals((double) r[0], (double) during[1], 0.01, "the outline adds no width");
        assertEquals((double) r[1], (double) during[2], 0.01, "the outline adds no height");
        assertEquals("Multi-Tile Alignment Refinement -- auto: Solve & Save in 30s", during[3]);
        assertTrue(((String) during[4]).contains("-fx-border-color"), "the button is outlined");
        assertEquals(style, r[3], "closing the window restores the button's own style");
        assertEquals("Multi-Tile Alignment Refinement", r[4], "and the title");
    }
}
