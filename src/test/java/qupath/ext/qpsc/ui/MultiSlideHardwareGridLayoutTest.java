package qupath.ext.qpsc.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Layout of the multi-slide dialog's hardware pickers.
 *
 * <p>These three pickers used to sit side by side on one line. Objective and detector values
 * carry their full config IDs, which do not fit three-across, so JavaFX shrank every child
 * until the dialog rendered a row reading literally:
 *
 * <pre>...  [... v]  ...  [20x Olympus Pol (LOCI_OBJECTIVE_OLYMPUS... v]  ...  [AP-3200T-USB...]</pre>
 *
 * <p>Every label had ellipsized to "..." and the modality combo had collapsed to "..." too --
 * the operator could not tell what any of it was. What makes it worth a test rather than a
 * glance is that nothing about it is visible from the source: the code said {@code new
 * Label("Modality:")} and the widget drew three dots. Only laying it out at a real width
 * shows the difference.
 */
class MultiSlideHardwareGridLayoutTest {

    /** Widest the dialog gets, minus padding -- the width the grid actually has to work in. */
    private static final double DIALOG_CONTENT_WIDTH = 720 - 28;

    private static final String LONG_OBJECTIVE = "20x Olympus Pol (LOCI_OBJECTIVE_OLYMPUS_20X_POL_001)";
    private static final String LONG_DETECTOR = "AP-3200T-USB (LOCI_DETECTOR_JAI_AP_3200T_USB_001)";

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

    /** Builds and lays out the grid on the FX thread, then hands it back. */
    private static GridPane laidOutGrid() throws Exception {
        AtomicReference<GridPane> ref = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ComboBox<String> modality = new ComboBox<>();
                modality.getItems().addAll("ppm", "brightfield");
                modality.setValue("ppm");
                ComboBox<String> objective = new ComboBox<>();
                objective.getItems().add(LONG_OBJECTIVE);
                objective.setValue(LONG_OBJECTIVE);
                ComboBox<String> detector = new ComboBox<>();
                detector.getItems().add(LONG_DETECTOR);
                detector.setValue(LONG_DETECTOR);

                GridPane grid = MultiSlideAssignmentDialog.buildHardwareGrid(modality, objective, detector);
                // A Scene is what makes CSS apply and real font metrics available; without
                // one the label reports its unstyled size and the test proves nothing.
                new Scene(grid);
                grid.resize(DIALOG_CONTENT_WIDTH, grid.prefHeight(DIALOG_CONTENT_WIDTH));
                grid.applyCss();
                grid.layout();
                ref.set(grid);
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS), "FX layout did not complete");
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        return ref.get();
    }

    @Test
    void everyLabelKeepsItsTextAtTheDialogWidth() throws Exception {
        GridPane grid = laidOutGrid();

        for (var node : grid.getChildren()) {
            if (node instanceof Label label) {
                double needed = label.prefWidth(-1);
                assertTrue(
                        label.getWidth() >= needed - 0.5,
                        "label '" + label.getText() + "' was squeezed to " + label.getWidth() + " but needs " + needed
                                + " -- it will render as \"...\"");
            }
        }
    }

    @Test
    void allThreeLabelsAreStillPresent() throws Exception {
        GridPane grid = laidOutGrid();

        var texts = grid.getChildren().stream()
                .filter(Label.class::isInstance)
                .map(n -> ((Label) n).getText())
                .toList();

        assertEquals(3, texts.size(), "expected one label per picker: " + texts);
        assertTrue(texts.contains("Modality:"), texts.toString());
        assertTrue(texts.contains("Objective:"), texts.toString());
        assertTrue(texts.contains("Detector:"), texts.toString());
    }

    @Test
    void theModalityComboIsNotCollapsed() throws Exception {
        GridPane grid = laidOutGrid();

        // The specific casualty of the old single-row layout: squeezed until its own value
        // rendered as "...". It shares the field column with the two long combos, so it gets
        // the same generous width now.
        ComboBox<?> modality = (ComboBox<?>) grid.getChildren().stream()
                .filter(ComboBox.class::isInstance)
                .findFirst()
                .orElseThrow();

        assertTrue(
                modality.getWidth() > 200,
                "modality combo is only " + modality.getWidth() + " wide; it will ellipsize");
    }

    @Test
    void theLongValueCombosGetMostOfTheWidth() throws Exception {
        GridPane grid = laidOutGrid();

        for (var node : grid.getChildren()) {
            if (node instanceof ComboBox<?> box) {
                assertTrue(
                        box.getWidth() > DIALOG_CONTENT_WIDTH * 0.75,
                        "combo got only " + box.getWidth() + " of " + DIALOG_CONTENT_WIDTH
                                + "; stacking them was supposed to give each the full width");
            }
        }
    }

    @Test
    void theOldSingleRowLayoutIsWhatFailed() throws Exception {
        // Negative control. Without this, the assertions above could be passing because
        // they are weak rather than because the layout is fixed. This reconstructs the
        // original HBox and shows it squeezing the labels below the width their own text
        // needs -- which is what JavaFX renders as "...".
        AtomicReference<javafx.scene.layout.HBox> ref = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            ComboBox<String> modality = new ComboBox<>();
            modality.getItems().add("ppm");
            modality.setValue("ppm");
            ComboBox<String> objective = new ComboBox<>();
            objective.getItems().add(LONG_OBJECTIVE);
            objective.setValue(LONG_OBJECTIVE);
            ComboBox<String> detector = new ComboBox<>();
            detector.getItems().add(LONG_DETECTOR);
            detector.setValue(LONG_DETECTOR);

            javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(
                    8,
                    new Label("Modality:"),
                    modality,
                    new Label("Objective:"),
                    objective,
                    new Label("Detector:"),
                    detector);
            new Scene(row);
            row.resize(DIALOG_CONTENT_WIDTH, row.prefHeight(DIALOG_CONTENT_WIDTH));
            row.applyCss();
            row.layout();
            ref.set(row);
            done.countDown();
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));

        long squeezed = ref.get().getChildren().stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .filter(l -> l.getWidth() < l.prefWidth(-1) - 0.5)
                .count();

        assertTrue(
                squeezed > 0,
                "the old single-row layout was supposed to squeeze its labels; if it no longer "
                        + "does, the assertions in this class are not testing what they claim");
    }

    @Test
    void theGridStaysTwoColumnsSoLabelsAndFieldsAlign() throws Exception {
        GridPane grid = laidOutGrid();

        assertEquals(2, grid.getColumnConstraints().size());
        // Six children in three rows: the old layout put all six in ONE row, which is
        // exactly what made them unreadable.
        assertEquals(6, grid.getChildren().size());
        for (var node : grid.getChildren()) {
            Integer col = GridPane.getColumnIndex(node);
            assertTrue(col != null && col <= 1, "unexpected column " + col);
        }
    }
}
