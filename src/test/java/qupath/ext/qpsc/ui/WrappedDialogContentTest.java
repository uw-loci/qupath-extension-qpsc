package qupath.ext.qpsc.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 2026-11-11: "Acquisition failed for 42606_60590: ... Position out of range: ..." was cut off
 * with an ellipsis. The dialog's own content label was set to wrap with only a maximum width,
 * so the dialog's height came from the label's one-line width: one line per paragraph. At the
 * real width the text wraps onto more lines than that, and the last one is cut off. The fixed
 * dialog is shown for real here and must draw every word. (The old setup could not be shown in
 * this test: under WSLg it stalls the JavaFX thread, which is a different symptom from the
 * Windows one, so it is not used as evidence either way.)
 */
class WrappedDialogContentTest {

    private static final String MESSAGE =
            "Acquisition failed for 42606_60590:\n\nAcquisition failed on server: Stage move"
                    + " still failing after user intervention: Position out of range: Position(x=-56790.211,"
                    + " y=-25904.784, z=70.28279518098908)";

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

    /** The text a label actually draws, whitespace collapsed. */
    private static String drawn(Label label) {
        StringBuilder text = new StringBuilder();
        for (var node : label.lookupAll(".text")) {
            if (node instanceof Text t) {
                text.append(t.getText());
            }
        }
        return text.toString().replaceAll("\\s+", " ").trim();
    }

    /** Shows the alert as the user would see it, reads what the label draws, and closes it. */
    private static String drawnWhenShown(java.util.function.Supplier<Object[]> build) throws Exception {
        Object[] built = onFx(() -> {
            Object[] b = build.get();
            Alert alert = (Alert) b[0];
            alert.initModality(javafx.stage.Modality.NONE);
            alert.show();
            return b;
        });
        Thread.sleep(1500); // a few pulses: the dialog sizes itself and lays out
        return onFx(() -> {
            String text = drawn((Label) built[1]);
            ((Alert) built[0]).close();
            return text;
        });
    }

    @Test
    void theWholeMessageIsDrawn() throws Exception {
        String drawn = drawnWhenShown(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setHeaderText("Acquisition");
            alert.getDialogPane().setMinWidth(500);
            alert.getDialogPane().setPrefWidth(600);
            Label label = UIFunctions.setWrappedContent(alert, MESSAGE, 550);
            return new Object[] {alert, label};
        });
        assertEquals(MESSAGE.replaceAll("\\s+", " "), drawn, "every word is drawn, nothing cut off");
    }
}
