package qupath.ext.qpsc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Enter in a text field must not start a run.
 *
 * <p>JavaFX makes an {@code OK_DONE} button the dialog's default button, and Enter anywhere in
 * the dialog presses the default button. On 2026-11-11 Enter in an acquisition setup field
 * started the acquisition.
 */
class EnterDoesNotStartARunTest {

    /** Every dialog whose OK_DONE button starts the microscope doing something. */
    private static final String[] RUN_DIALOGS = {
        "ui/acquisition/UnifiedAcquisitionController.java",
        "ui/acquisition/ExistingImageAcquisitionController.java",
        "ui/acquisition/SinglePointAcquisitionController.java",
        "ui/acquisition/AnnotationAcquisitionDialog.java",
        "ui/background/BackgroundCollectionController.java",
        "ui/focus/AutofocusBenchmarkDialog.java",
        "ui/calibration/NoiseCharacterizationDialog.java",
        "ui/calibration/WBComparisonDialog.java",
        "ui/calibration/WhiteBalanceDialog.java",
    };

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
    void theStartButtonIsNotTheDefault() throws Exception {
        AtomicReference<boolean[]> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Dialog<ButtonType> dialog = new Dialog<>();
                ButtonType start = new ButtonType("Start Acquisition", ButtonBar.ButtonData.OK_DONE);
                dialog.getDialogPane().getButtonTypes().addAll(start, ButtonType.CANCEL);
                Button button = (Button) dialog.getDialogPane().lookupButton(start);
                boolean before = button.isDefaultButton();
                UIFunctions.neverDefaultButton(button);
                result.set(new boolean[] {before, button.isDefaultButton()});
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        assertTrue(result.get()[0], "JavaFX made the OK_DONE button the default, which is the problem");
        assertFalse(result.get()[1]);
    }

    @Test
    void everyRunDialogTakesTheDefaultAwayFromItsStartButton() throws Exception {
        for (String file : RUN_DIALOGS) {
            String src = Files.readString(Path.of("src/main/java/qupath/ext/qpsc", file));
            assertEquals(1, src.split("UIFunctions\\.neverDefaultButton\\(", -1).length - 1, file);
        }
    }
}
