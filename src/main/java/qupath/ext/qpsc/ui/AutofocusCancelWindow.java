package qupath.ext.qpsc.ui;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.service.autofocus.AutofocusFeed;
import qupath.lib.gui.QuPathGUI;

/**
 * A small window that stays up while an autofocus started by a workflow runs, so it can always be
 * cancelled: the autofocus after a slot jump and the one before single-tile refinement had a
 * Cancel only on the Live Viewer's Autofocus button, and with the Live Viewer closed there was
 * none anywhere (the refinement dialog opens only once the focus is done).
 *
 * <p>Shows what the search is for, its live curve ({@link AutofocusCurvePane}), and
 * <b>Cancel autofocus</b>; the window's X does the same. Cancelling stops the search, the
 * microscope returns Z to where it started, and the workflow carries on as after a failed focus.
 * Show and close from any thread.
 */
public final class AutofocusCancelWindow {

    private static final Logger logger = LoggerFactory.getLogger(AutofocusCancelWindow.class);

    private final AtomicReference<Stage> stage = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    private AutofocusCancelWindow() {}

    /**
     * Opens the window.
     *
     * @param purpose one line saying what is being focused, e.g. "Focusing at the refinement point"
     * @param cancelAction stops the search; run once, off the FX thread is not required
     * @return the window, to {@link #close()} when the search ends
     */
    public static AutofocusCancelWindow show(String purpose, Runnable cancelAction) {
        AutofocusCancelWindow w = new AutofocusCancelWindow();
        Platform.runLater(() -> w.build(purpose, cancelAction));
        return w;
    }

    /** Closes the window; safe from any thread and more than once. */
    public void close() {
        if (closed.getAndSet(true)) {
            return;
        }
        Platform.runLater(() -> {
            Stage s = stage.get();
            if (s != null) {
                s.hide();
            }
        });
    }

    private void build(String purpose, Runnable cancelAction) {
        if (closed.get()) {
            return; // the search ended before the window could open
        }
        Stage s = new Stage();
        QpscWindows.track(s);
        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui != null && gui.getStage() != null) {
            s.initOwner(gui.getStage());
        }
        s.setTitle("Autofocus");

        Label what = new Label(purpose);
        what.setWrapText(true);
        Label hint =
                new Label("Cancel stops the search and returns Z to where it started; you can then focus by hand.");
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 10px; -fx-text-fill: " + ThemeColors.MUTED + ";");
        AutofocusCurvePane curve = new AutofocusCurvePane();
        Button cancel = new Button("Cancel autofocus");
        cancel.setDefaultButton(false);
        Runnable doCancel = () -> {
            if (cancelRequested.getAndSet(true)) {
                return;
            }
            logger.info("Autofocus cancelled from the autofocus window: {}", purpose);
            cancel.setDisable(true);
            cancel.setText("Cancelling...");
            hint.setText("Stopping the search; the stage returns to where it started.");
            cancelAction.run();
        };
        cancel.setOnAction(e -> doCancel.run());
        s.setOnCloseRequest(e -> {
            e.consume(); // the window closes when the search has stopped
            doCancel.run();
        });

        VBox root = new VBox(8, what, curve, cancel, hint);
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(12));
        root.setPrefWidth(360);
        s.setScene(new Scene(root));

        Runnable unsubscribe = AutofocusFeed.getInstance().subscribe(curve::accept);
        s.showingProperty().addListener((obs, was, showing) -> {
            if (!showing) unsubscribe.run();
        });
        stage.set(s);
        s.show();
        if (closed.get()) {
            s.hide(); // close() ran while this was being built
        }
    }
}
