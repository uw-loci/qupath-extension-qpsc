package qupath.ext.qpsc.modality.lcpolscope.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import qupath.ext.qpsc.modality.lcpolscope.workflow.LCCalibrationWorkflow;
import qupath.ext.qpsc.modality.lcpolscope.workflow.LCVoltageSweepWorkflow;
import qupath.ext.qpsc.ui.QpscWindows;
import qupath.lib.gui.QuPathGUI;

/**
 * One window for getting the LC-PolScope ready, in the order it has to happen: the camera, then
 * the calibration (which measures the black level and, on Apply, sets the exposure), then the
 * background. Doing them out of order is what the stale-background refusal and the camera checks
 * catch after the fact; here the order is the layout.
 *
 * <p>Not modal, so the operator can switch to Micro-Manager to change a camera property and come
 * back: the status box rechecks whenever the window regains focus.
 */
public final class LCPolScopeSetupWindow {

    private static Stage open;

    private LCPolScopeSetupWindow() {}

    /** Shows the window, or brings the open one forward. */
    public static void show() {
        if (open != null && open.isShowing()) {
            open.toFront();
            return;
        }
        LCPolScopeReadinessPane status = new LCPolScopeReadinessPane();

        VBox steps = new VBox(
                12,
                step(
                        "1. Camera",
                        "The camera must not adjust itself between the five frames. In Micro-Manager, open "
                                + "Devices > Device Property Browser, set Gain Auto and Exposure Auto to Off, turn "
                                + "gamma off (Gamma Enabled 0, or Gamma 1.0), and choose a 12-bit or 16-bit pixel "
                                + "format. The box above checks again when you come back to this window.",
                        "Recheck",
                        status::refresh),
                step(
                        "2. Calibrate on a clear, specimen-free field",
                        "Tunes the two liquid crystals for this slide. It finds the setting where empty glass is "
                                + "darkest (the extinction state) and the four settings just off it, one on each "
                                + "side for each crystal (the swing states), and measures the camera's black level. "
                                + "Apply saves these five settings (the palette) for acquisition, offers the measured "
                                + "black level and an exposure, and then offers the background, step 3. Calibrate "
                                + "again on every new slide. Apply a saved calibration... reuses a calibration file "
                                + "from an earlier run instead, for example one applied to the other LC-PolScope "
                                + "config file.",
                        "Calibrate Liquid Crystals...",
                        LCCalibrationWorkflow::run,
                        "Apply a saved calibration...",
                        LCCalibrationWorkflow::applySaved),
                step(
                        "3. Background, after every calibration",
                        "Move to an empty area of the slide, defocus 10-20 um, and collect the background for "
                                + "each LC-PolScope profile you will acquire with (one per objective, such as "
                                + "lcpolscope_20x). The acquisition refuses a background taken before the latest "
                                + "calibration.",
                        "Collect background...",
                        LCCalibrationWorkflow::startBackgroundCollection),
                step(
                        "Optional diagnostic: each crystal's voltage curve",
                        "Not needed before acquiring, and the calibration does not use it. On a clear field, it "
                                + "steps each crystal through its voltages and measures the retardance it really "
                                + "gives, next to the waves-to-volts table in Micro-Manager's Meadowlark adapter, "
                                + "with the applied calibration marked. Run it after calibrating if the "
                                + "calibration's Palette tab shows one crystal's swing states unevenly spaced around "
                                + "extinction. It puts the crystals and exposure back as it found them.",
                        "Measure LC voltage curves...",
                        LCVoltageSweepWorkflow::run));

        VBox root = new VBox(14, status, steps);
        root.setPadding(new Insets(12));
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);

        // Closed with every other QPSC window when the microscope is switched: its status
        // describes the configuration that was active when it opened.
        Stage stage = new Stage();
        QpscWindows.track(stage);
        stage.setTitle("LC-PolScope Setup");
        stage.setScene(new Scene(scroll, 640, 560));
        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui != null && gui.getStage() != null) {
            stage.initOwner(gui.getStage());
        }
        stage.focusedProperty().addListener((obs, was, now) -> {
            if (Boolean.TRUE.equals(now)) {
                status.refresh();
            }
        });
        stage.setOnHidden(e -> open = null);
        open = stage;
        stage.show();
    }

    private static VBox step(String title, String text, String buttonText, Runnable action) {
        return step(title, text, buttonText, action, null, null);
    }

    private static VBox step(
            String title, String text, String buttonText, Runnable action, String secondText, Runnable second) {
        Label heading = new Label(title);
        heading.setStyle("-fx-font-weight: bold;");
        Label body = new Label(text);
        body.setWrapText(true);
        body.setMaxWidth(580);
        body.setStyle("-fx-font-size: 11px;");
        Button button = new Button(buttonText);
        button.setOnAction(e -> action.run());
        HBox row = new HBox(8, button);
        if (secondText != null && second != null) {
            Button other = new Button(secondText);
            other.setOnAction(e -> second.run());
            row.getChildren().add(other);
        }
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(4, heading, body, row);
    }
}
