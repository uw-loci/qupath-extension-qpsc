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
                        "In Micro-Manager's Device Property Browser: Gain Auto and Exposure Auto Off, Gamma off "
                                + "(Gamma Enabled 0, or Gamma 1.0), and a 12- or 16-bit pixel format. The status above "
                                + "rechecks when you come back to this window.",
                        "Recheck",
                        status::refresh),
                step(
                        "2. Calibrate on a clear, specimen-free field",
                        "Finds the extinction point and swing states, measures the black level, and on Apply "
                                + "writes the palette and offers the measured black level and an exposure from the "
                                + "usable range. Apply then offers the background, step 3.",
                        "Calibrate Liquid Crystals...",
                        LCCalibrationWorkflow::run),
                step(
                        "3. Background, after every calibration",
                        "Move to a specimen-free area and defocus 10-20 um, then collect the five-state "
                                + "background for each LC-PolScope profile you will acquire with. A background from "
                                + "an earlier calibration is refused by the acquisition.",
                        "Collect background...",
                        LCCalibrationWorkflow::startBackgroundCollection));

        VBox root = new VBox(14, status, steps);
        root.setPadding(new Insets(12));
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);

        Stage stage = new Stage();
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
        Label heading = new Label(title);
        heading.setStyle("-fx-font-weight: bold;");
        Label body = new Label(text);
        body.setWrapText(true);
        body.setMaxWidth(580);
        body.setStyle("-fx-font-size: 11px;");
        Button button = new Button(buttonText);
        button.setOnAction(e -> action.run());
        HBox row = new HBox(button);
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(4, heading, body, row);
    }
}
