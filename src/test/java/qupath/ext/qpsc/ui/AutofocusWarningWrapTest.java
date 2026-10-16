package qupath.ext.qpsc.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The acquisition wizard's "Do not interact" warning sits beside the Validate AF button. It did
 * not wrap, so a narrow wizard cut it off. Laid out at a real width with real fonts, every word
 * must be drawn, on more than one line if needed, and the button must keep its full width.
 */
class AutofocusWarningWrapTest {

    private static final String WARNING = "Do not interact with the microscope stage position";

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

    @Test
    void theWarningWrapsInsteadOfBeingCutOff() throws Exception {
        AtomicReference<String> drawn = new AtomicReference<>();
        AtomicReference<double[]> sizes = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Button button = new Button("Validate AF");
                button.setStyle("-fx-font-size: 11px;");
                Label warning = new Label(WARNING);
                warning.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
                HBox row = AcquisitionWizardDialog.buttonWithWarningRow(button, warning);
                new Scene(row);
                double width = 260; // narrower than button + one-line warning
                row.applyCss(); // real font metrics before any size is asked for
                row.resize(width, row.prefHeight(width));
                row.layout();
                StringBuilder text = new StringBuilder();
                for (var node : warning.lookupAll(".text")) {
                    if (node instanceof Text t) {
                        text.append(t.getText());
                    }
                }
                drawn.set(text.toString());
                double oneLine = warning.getFont().getSize() * 1.6;
                sizes.set(new double[] {warning.getHeight(), oneLine, button.getWidth(), button.prefWidth(-1)});
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
        assertEquals(WARNING, drawn.get().replaceAll("\\s+", " ").trim(), "every word is drawn, nothing ellipsized");
        double[] s = sizes.get();
        assertTrue(s[0] > s[1], "the warning wrapped onto more than one line (height " + s[0] + ")");
        assertEquals(s[3], s[2], 0.5, "the button keeps its full width");
    }
}
