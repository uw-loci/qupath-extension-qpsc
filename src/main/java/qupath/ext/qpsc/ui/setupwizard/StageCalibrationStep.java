package qupath.ext.qpsc.ui.setupwizard;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.StageDirectionCalibrationDialog;
import qupath.ext.qpsc.utilities.StageImageTransform;

/**
 * Wizard step that launches the interactive {@link StageDirectionCalibrationDialog} so the
 * user can calibrate stage polarity + camera orientation by physically jogging the stage and
 * observing the Live Viewer image, instead of trial-and-error tweaking the preferences.
 *
 * <p>This step is non-blocking: validate() always returns null. The user can run the
 * calibration, skip it (current preferences stay in place), or just click Next. The actual
 * calibration requires a connected microscope, so users who run the wizard before configuring
 * the server can complete it later via the "Calibrate Directions" button in the Live Viewer.
 */
public class StageCalibrationStep implements WizardStep {

    private static final Logger logger = LoggerFactory.getLogger(StageCalibrationStep.class);

    private final VBox content;
    private final TextArea currentTransform;
    private final Label statusLabel;

    public StageCalibrationStep(WizardData data, ResourceCatalog catalog) {
        content = new VBox(12);
        content.setPadding(new Insets(15));

        Label intro = new Label("This step lets you calibrate which way the stage moves the image. "
                + "The dialog jogs the stage by a small step in X then Y, asks you to report which "
                + "direction the image appeared to pan, and back-solves the correct stage polarity "
                + "and camera orientation.\n\n"
                + "This step is optional and requires a connected microscope server. If you haven't "
                + "configured the server yet, skip this step -- you can re-run the calibration any "
                + "time from the Live Viewer's \"Calibrate Directions\" button in the Navigate tab.");
        intro.setWrapText(true);

        statusLabel = new Label();
        statusLabel.setWrapText(true);

        Button runButton = new Button("Run calibration...");
        runButton.setOnAction(e -> StageDirectionCalibrationDialog.show(runButton.getScene().getWindow())
                .thenAccept(result -> javafx.application.Platform.runLater(this::refreshDisplay)));

        Button skipButton = new Button("Keep current values");
        skipButton.setOnAction(e -> {
            statusLabel.setText("Keeping current values. Re-run anytime from the Live Viewer.");
            statusLabel.setStyle("-fx-text-fill: gray;");
        });

        HBox actionRow = new HBox(8, runButton, skipButton);

        currentTransform = new TextArea();
        currentTransform.setEditable(false);
        currentTransform.setPrefRowCount(5);
        currentTransform.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        refreshDisplay();

        content.getChildren()
                .addAll(intro, actionRow, statusLabel, new Label("Current transform:"), currentTransform);
    }

    private void refreshDisplay() {
        currentTransform.setText(StageImageTransform.current().describe());
    }

    @Override
    public String getTitle() {
        return "Stage Calibration";
    }

    @Override
    public String getDescription() {
        return "Optionally jog the stage to back-solve stage polarity and camera orientation.";
    }

    @Override
    public Node getContent() {
        return content;
    }

    @Override
    public String validate() {
        // Non-blocking. The user can run the calibration, skip, or just hit Next.
        return null;
    }

    @Override
    public void onEnter() {
        refreshDisplay();
    }

    @Override
    public void onLeave() {
        logger.debug("StageCalibrationStep: current transform on leave: {}",
                StageImageTransform.current());
    }
}
