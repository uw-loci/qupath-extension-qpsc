package qupath.ext.qpsc.ui;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.CameraOrientation;
import qupath.ext.qpsc.utilities.StageImageTransform;
import qupath.ext.qpsc.utilities.StagePolarity;

/**
 * Interactive "jog and confirm" calibration for camera orientation.
 *
 * <p>Physically moves the stage by a small XY delta, asks the user which way the image
 * appeared to pan, and solves for the {@link CameraOrientation} that reproduces the
 * observation under the user's <em>current</em> {@link StagePolarity}. Polarity is treated
 * as a hardware-wiring fact set once during stage configuration and is not changed by this
 * tool -- the codebase deliberately keeps polarity (read directly by tile traversal in
 * {@code TilingUtilities}, by alignment math in {@code MicroscopeAlignmentWorkflow}, etc.)
 * separate from optical flip/rotation (read via {@link StageImageTransform}). Conflating the
 * two was a documented bug class pre-2026-05 (see {@code COORDINATE_TRANSFORMS.md} "Flip vs
 * Inversion"). The Manual override panel still lets advanced users edit both if their
 * hardware notes call for it.
 *
 * <p>Launched from two places: the Setup Wizard ({@code StageCalibrationStep}) for first-time
 * installs, and a "Calibrate Directions" button in the Live Viewer's Navigate tab for ad-hoc
 * re-calibration. Both entry points share this same dialog and back-solve logic.
 *
 * <p>The dialog is non-modal + always-on-top so the user can keep watching the Live Viewer
 * while answering the direction questions. The original stage position is captured at open
 * and restored on close (Apply / Cancel / window-X / error), so the dialog is non-destructive.
 *
 * <p>Z polarity is intentionally not handled here -- see the TODO in
 * {@code claude-reports/TODO_LIST.md}.
 */
public final class StageDirectionCalibrationDialog {

    private static final Logger logger = LoggerFactory.getLogger(StageDirectionCalibrationDialog.class);

    private static final double DEFAULT_STEP_UM = 200.0;
    private static final double MIN_STEP_UM = 20.0;
    private static final double MAX_STEP_UM = 2000.0;
    private static final double STEP_INCREMENT_UM = 50.0;

    /** Outcome record returned to the caller after the user clicks Apply (or null on Cancel). */
    public record CalibrationResult(StagePolarity polarity, CameraOrientation orientation) {}

    /** Cardinal direction the user picked. Screen frame: positive Y = down. */
    public enum Direction {
        LEFT(-1, 0),
        RIGHT(1, 0),
        UP(0, -1),
        DOWN(0, 1);

        public final double sx;
        public final double sy;

        Direction(double sx, double sy) {
            this.sx = sx;
            this.sy = sy;
        }
    }

    private StageDirectionCalibrationDialog() {}

    // ----- Public entry point -----

    /**
     * Show the calibration dialog.
     *
     * @param owner parent window for ownership / always-on-top stacking; may be null
     * @return future that completes with the chosen {@link CalibrationResult} once the user
     *         clicks Apply, or {@code null} if they cancel. Preferences are written inside this
     *         method before the future completes; the result is informational for callers that
     *         want to refresh their UI.
     */
    public static CompletableFuture<CalibrationResult> show(Window owner) {
        CompletableFuture<CalibrationResult> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                new StageDirectionCalibrationDialog().build(owner, future);
            } catch (Exception e) {
                logger.error("Failed to open Stage Direction Calibration dialog", e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    // ----- Instance state -----

    private Stage stage;
    private VBox root;
    private Spinner<Double> stepSpinner;
    private Label phaseHeader;
    private Label phaseInstructions;
    private VBox phaseContent;
    private HBox buttonBar;
    private Button primaryButton;
    private Button secondaryButton;
    private Button cancelButton;

    private double startX;
    private double startY;
    private boolean haveStartPosition = false;
    private Direction xObserved;
    private Direction yObserved;
    private CalibrationResult solved;

    // Manual-override controls (created lazily)
    private ComboBox<StagePolarity> manualPolarityCombo;
    private ComboBox<CameraOrientation> manualOrientationCombo;

    private void build(Window owner, CompletableFuture<CalibrationResult> future) {
        stage = new Stage();
        stage.initModality(Modality.NONE);
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setTitle("Stage direction calibration");
        stage.setAlwaysOnTop(true);

        phaseHeader = new Label();
        phaseHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 14;");

        phaseInstructions = new Label();
        phaseInstructions.setWrapText(true);
        phaseInstructions.setMaxWidth(460);

        phaseContent = new VBox(10);

        primaryButton = new Button();
        primaryButton.setDefaultButton(true);
        secondaryButton = new Button();
        secondaryButton.setVisible(false);
        secondaryButton.setManaged(false);
        cancelButton = new Button("Cancel");
        cancelButton.setCancelButton(true);
        cancelButton.setOnAction(e -> closeWithCancel(future));

        buttonBar = new HBox(8, secondaryButton, primaryButton, cancelButton);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);

        root = new VBox(12, phaseHeader, phaseInstructions, phaseContent, buttonBar);
        root.setPadding(new Insets(16));
        root.setPrefWidth(500);

        stage.setOnCloseRequest(e -> closeWithCancel(future));
        stage.setScene(new Scene(root));
        stage.setResizable(false);
        stage.show();

        showIntroPhase(future);
    }

    // ----- Phase: intro -----

    private void showIntroPhase(CompletableFuture<CalibrationResult> future) {
        phaseHeader.setText("Camera orientation calibration");
        phaseInstructions.setText("This will move the stage by a small step in +X and then +Y. After each move, "
                + "watch the Live Viewer and report which way the image appeared to move. "
                + "The dialog solves for your camera orientation (optical flip/rotation) "
                + "under your current stage polarity setting -- it does NOT change polarity, "
                + "because polarity is a hardware-wiring fact read by tile traversal and "
                + "alignment math independently. The stage will be returned to its starting "
                + "position when the dialog closes.");

        stepSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                MIN_STEP_UM, MAX_STEP_UM, DEFAULT_STEP_UM, STEP_INCREMENT_UM));
        stepSpinner.setEditable(true);
        stepSpinner.setPrefWidth(110);

        Label stepLabel = new Label("Step size (um):");
        HBox stepRow = new HBox(8, stepLabel, stepSpinner);
        stepRow.setAlignment(Pos.CENTER_LEFT);

        Label polarityLabel = new Label(String.format(
                "Assumed stage polarity: %s (unchanged by this calibration)",
                QPPreferenceDialog.getStagePolarityProperty()));
        polarityLabel.setStyle("-fx-font-style: italic;");

        Label currentLabel = new Label("Current transform:");
        TextArea currentDesc = new TextArea(StageImageTransform.current().describe());
        currentDesc.setEditable(false);
        currentDesc.setPrefRowCount(5);
        currentDesc.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");

        phaseContent.getChildren().setAll(stepRow, polarityLabel, currentLabel, currentDesc);

        primaryButton.setText("Begin");
        primaryButton.setDisable(false);
        primaryButton.setOnAction(e -> startXMove(future));

        secondaryButton.setVisible(false);
        secondaryButton.setManaged(false);
    }

    // ----- Phase: X move + question -----

    private void startXMove(CompletableFuture<CalibrationResult> future) {
        double step = stepSpinner.getValue();
        runMove(future, step, 0, () -> showAnswerPhase(future, /* xPhase = */ true));
    }

    private void startYMove(CompletableFuture<CalibrationResult> future) {
        double step = stepSpinner.getValue();
        runMove(future, 0, step, () -> showAnswerPhase(future, /* xPhase = */ false));
    }

    /**
     * Issue an mm-frame stage move on a background thread. Captures the start position the
     * first time it is called, so the dialog can restore it on close.
     */
    private void runMove(CompletableFuture<CalibrationResult> future, double mmDx, double mmDy, Runnable onSuccess) {
        phaseHeader.setText("Moving stage...");
        phaseInstructions.setText(String.format(
                "Sending +%.1f um in %s. Watch the Live Viewer image.",
                Math.abs(mmDx == 0 ? mmDy : mmDx), mmDx == 0 ? "Y" : "X"));
        phaseContent.getChildren().setAll(centered(new ProgressIndicator()));
        primaryButton.setDisable(true);
        secondaryButton.setVisible(false);
        secondaryButton.setManaged(false);

        Thread t = new Thread(
                () -> {
                    try {
                        MicroscopeController ctrl = MicroscopeController.getInstance();
                        if (!haveStartPosition) {
                            double[] pos = ctrl.getStagePositionXY();
                            startX = pos[0];
                            startY = pos[1];
                            haveStartPosition = true;
                            logger.info("Calibration start position: ({}, {})", startX, startY);
                        }
                        double[] current = ctrl.getStagePositionXY();
                        ctrl.moveStageXY(current[0] + mmDx, current[1] + mmDy);
                        Platform.runLater(onSuccess);
                    } catch (IOException ex) {
                        logger.warn("Calibration move failed: {}", ex.toString());
                        Platform.runLater(() -> showMoveError(future, ex.getMessage()));
                    } catch (Exception ex) {
                        logger.warn("Calibration move error", ex);
                        Platform.runLater(() -> showMoveError(future, ex.toString()));
                    }
                },
                "StageDirCalibration-Move");
        t.setDaemon(true);
        t.start();
    }

    private void showMoveError(CompletableFuture<CalibrationResult> future, String message) {
        phaseHeader.setText("Move failed");
        phaseInstructions.setText("The stage move could not be issued. Make sure the microscope "
                + "server is connected and the requested step is within the stage limits.\n\n"
                + "Error: " + message);
        phaseContent.getChildren().clear();

        primaryButton.setText("Retry");
        primaryButton.setDisable(false);
        primaryButton.setOnAction(e -> showIntroPhase(future));
    }

    private void showAnswerPhase(CompletableFuture<CalibrationResult> future, boolean xPhase) {
        String axisName = xPhase ? "+X" : "+Y";
        int stepNum = xPhase ? 1 : 2;
        phaseHeader.setText("Step " + stepNum + " of 2: which way did the image move?");
        phaseInstructions.setText(String.format(
                "Look at the Live Viewer. After the %s move, the image content appeared to "
                        + "pan in which direction?",
                axisName));

        ToggleGroup group = new ToggleGroup();
        RadioButton leftBtn = new RadioButton("Left");
        leftBtn.setToggleGroup(group);
        leftBtn.setUserData(Direction.LEFT);
        RadioButton rightBtn = new RadioButton("Right");
        rightBtn.setToggleGroup(group);
        rightBtn.setUserData(Direction.RIGHT);
        RadioButton upBtn = new RadioButton("Up");
        upBtn.setToggleGroup(group);
        upBtn.setUserData(Direction.UP);
        RadioButton downBtn = new RadioButton("Down");
        downBtn.setToggleGroup(group);
        downBtn.setUserData(Direction.DOWN);

        VBox radios = new VBox(6, leftBtn, rightBtn, upBtn, downBtn);
        phaseContent.getChildren().setAll(radios);

        primaryButton.setText("Next");
        primaryButton.setDisable(true);
        group.selectedToggleProperty().addListener((obs, was, now) -> primaryButton.setDisable(now == null));
        primaryButton.setOnAction(e -> {
            Direction picked = (Direction) group.getSelectedToggle().getUserData();
            if (xPhase) {
                xObserved = picked;
                startYMove(future);
            } else {
                yObserved = picked;
                showResultPhase(future);
            }
        });
    }

    // ----- Phase: result -----

    private void showResultPhase(CompletableFuture<CalibrationResult> future) {
        StagePolarity currentPolarity = QPPreferenceDialog.getStagePolarityProperty();
        solved = backSolve(xObserved, yObserved, currentPolarity);
        if (solved == null) {
            phaseHeader.setText("No camera orientation matches");
            phaseInstructions.setText("No camera orientation can reproduce both observations under "
                    + "your current stage polarity (" + currentPolarity + "). This usually means "
                    + "either (a) the answers were inconsistent (re-test with a larger step), or "
                    + "(b) your stage polarity is wrong -- positive X or Y commands physically "
                    + "move the stage carrier in the opposite direction of what the polarity claims. "
                    + "Use Manual override to set polarity + orientation directly, or verify polarity "
                    + "via the manual MicroManager-script check documented in PREFERENCES.md.");
            phaseContent.getChildren().clear();
            primaryButton.setText("Re-test");
            primaryButton.setDisable(false);
            primaryButton.setOnAction(e -> {
                xObserved = null;
                yObserved = null;
                showIntroPhase(future);
            });
            secondaryButton.setText("Manual override");
            secondaryButton.setVisible(true);
            secondaryButton.setManaged(true);
            secondaryButton.setOnAction(e -> showManualOverridePhase(future));
            return;
        }

        phaseHeader.setText("Camera orientation result");
        phaseInstructions.setText("Apply to write the camera orientation to your preferences. "
                + "Stage polarity is left unchanged. Use Manual override if you need to edit "
                + "both polarity and orientation together.");

        TextArea previewDesc =
                new TextArea(new StageImageTransform(solved.polarity(), solved.orientation()).describe());
        previewDesc.setEditable(false);
        previewDesc.setPrefRowCount(5);
        previewDesc.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");

        Label observedLabel = new Label(String.format(
                "Observed: +X -> %s, +Y -> %s%n" + "Polarity (unchanged): %s%n" + "Camera orientation: %s",
                xObserved, yObserved, solved.polarity(), solved.orientation()));
        observedLabel.setWrapText(true);

        phaseContent.getChildren().setAll(observedLabel, previewDesc);

        primaryButton.setText("Apply");
        primaryButton.setDisable(false);
        primaryButton.setOnAction(e -> applyAndClose(future, solved));

        secondaryButton.setText("Manual override");
        secondaryButton.setVisible(true);
        secondaryButton.setManaged(true);
        secondaryButton.setOnAction(e -> showManualOverridePhase(future));
    }

    // ----- Phase: manual override -----

    private void showManualOverridePhase(CompletableFuture<CalibrationResult> future) {
        phaseHeader.setText("Manual override");
        phaseInstructions.setText("Pick the stage polarity and camera orientation you want to "
                + "save. Polarity is a hardware-wiring fact -- only change it if you have verified "
                + "via direct stage observation (e.g. via the MicroManager-script procedure in "
                + "PREFERENCES.md) that positive commands move the stage carrier the wrong way. "
                + "The preview below shows the resulting screen <-> stage sign math.");

        manualPolarityCombo = new ComboBox<>();
        manualPolarityCombo.getItems().setAll(StagePolarity.values());
        manualPolarityCombo.setValue(
                solved == null ? QPPreferenceDialog.getStagePolarityProperty() : solved.polarity());

        manualOrientationCombo = new ComboBox<>();
        manualOrientationCombo.getItems().setAll(CameraOrientation.values());
        manualOrientationCombo.setValue(
                solved == null ? QPPreferenceDialog.getCameraOrientationProperty() : solved.orientation());

        TextArea preview = new TextArea();
        preview.setEditable(false);
        preview.setPrefRowCount(5);
        preview.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        Runnable refreshPreview = () -> preview.setText(
                new StageImageTransform(manualPolarityCombo.getValue(), manualOrientationCombo.getValue()).describe());
        refreshPreview.run();
        manualPolarityCombo.valueProperty().addListener((obs, was, now) -> refreshPreview.run());
        manualOrientationCombo.valueProperty().addListener((obs, was, now) -> refreshPreview.run());

        HBox polarityRow = new HBox(8, new Label("Stage polarity:"), manualPolarityCombo);
        polarityRow.setAlignment(Pos.CENTER_LEFT);
        HBox orientationRow = new HBox(8, new Label("Camera orientation:"), manualOrientationCombo);
        orientationRow.setAlignment(Pos.CENTER_LEFT);

        phaseContent.getChildren().setAll(polarityRow, orientationRow, preview);

        primaryButton.setText("Apply");
        primaryButton.setDisable(false);
        primaryButton.setOnAction(e -> applyAndClose(
                future, new CalibrationResult(manualPolarityCombo.getValue(), manualOrientationCombo.getValue())));

        secondaryButton.setText("Back");
        secondaryButton.setVisible(true);
        secondaryButton.setManaged(true);
        secondaryButton.setOnAction(e -> {
            if (solved == null) {
                // Came from the no-match path; go back to intro instead of result.
                showIntroPhase(future);
            } else {
                showResultPhase(future);
            }
        });
    }

    // ----- Apply / close -----

    private void applyAndClose(CompletableFuture<CalibrationResult> future, CalibrationResult result) {
        try {
            QPPreferenceDialog.setStageInvertedX(result.polarity().invertX);
            QPPreferenceDialog.setStageInvertedY(result.polarity().invertY);
            QPPreferenceDialog.setCameraOrientationProperty(result.orientation());
            logger.info(
                    "Stage direction calibration applied: {}",
                    new StageImageTransform(result.polarity(), result.orientation()).describe());
        } catch (Exception ex) {
            logger.error("Failed to persist calibration result", ex);
            Alert alert = new Alert(
                    Alert.AlertType.ERROR,
                    "Could not save calibration to preferences:\n" + ex.getMessage(),
                    ButtonType.OK);
            UIFunctions.showAlertOverParent(alert, stage);
        }
        restoreStagePositionAsync(() -> {
            stage.close();
            future.complete(result);
        });
    }

    private void closeWithCancel(CompletableFuture<CalibrationResult> future) {
        restoreStagePositionAsync(() -> {
            if (!future.isDone()) {
                future.complete(null);
            }
            stage.close();
        });
    }

    /**
     * Move the stage back to the captured start position, then run the given continuation on
     * the FX thread. No-op (just runs the continuation) if no start position was captured.
     */
    private void restoreStagePositionAsync(Runnable then) {
        if (!haveStartPosition) {
            Platform.runLater(then);
            return;
        }
        Thread t = new Thread(
                () -> {
                    try {
                        MicroscopeController.getInstance().moveStageXY(startX, startY);
                    } catch (Exception ex) {
                        logger.warn(
                                "Failed to restore stage to start position ({}, {}): {}",
                                startX,
                                startY,
                                ex.toString());
                    } finally {
                        Platform.runLater(then);
                    }
                },
                "StageDirCalibration-Restore");
        t.setDaemon(true);
        t.start();
    }

    // ----- Back-solve -----

    /**
     * Walked in this order so the first match wins. {@link CameraOrientation#NORMAL} comes
     * first so a scope whose net optical path is already aligned with the sample frame returns
     * NORMAL; the axis-aligned flips come next; the axis-swapping rotations come last (those
     * are only reachable when the user reports panX vertical and panY horizontal).
     */
    private static final CameraOrientation[] ORIENTATION_PREFERENCE = {
        CameraOrientation.NORMAL,
        CameraOrientation.FLIP_H,
        CameraOrientation.FLIP_V,
        CameraOrientation.ROT_180,
        CameraOrientation.ROT_90_CW,
        CameraOrientation.ROT_90_CCW,
        CameraOrientation.TRANSPOSE,
        CameraOrientation.ANTI_TRANSPOSE,
    };

    /**
     * Solve for the {@link CameraOrientation} that, under the given {@link StagePolarity},
     * reproduces the observed image-pan directions for stage commands {@code mmDelta=(+1, 0)}
     * and {@code (0, +1)}. Polarity is treated as fixed input -- it is intentionally NOT
     * searched, so the resulting {@link CalibrationResult}'s polarity always equals
     * {@code polarity}.
     *
     * <p>Returns {@code null} when no orientation matches. With a valid perpendicular
     * observation and any polarity, exactly one orientation matches; a null return therefore
     * indicates either an inconsistent observation pair (both axes panning the same direction)
     * or that the user's polarity is wrong (the stage carrier actually moves opposite to what
     * the polarity claims, so the observed pan can't be reached by any orientation under this
     * polarity).
     *
     * <p>Visible for unit testing.
     */
    public static CalibrationResult backSolve(Direction xObserved, Direction yObserved, StagePolarity polarity) {
        if (xObserved == null || yObserved == null || polarity == null) {
            return null;
        }
        for (CameraOrientation co : ORIENTATION_PREFERENCE) {
            double[] xPredicted = predictedImagePan(polarity, co, 1, 0);
            double[] yPredicted = predictedImagePan(polarity, co, 0, 1);
            if (matches(xPredicted, xObserved) && matches(yPredicted, yObserved)) {
                return new CalibrationResult(polarity, co);
            }
        }
        return null;
    }

    /**
     * Predict the screen-pan direction that should result from issuing the given mm-command
     * delta, under the candidate stage polarity and camera orientation. Inverse of
     * {@link StageImageTransform#screenPanDeltaToMmDelta(double, double)}.
     */
    private static double[] predictedImagePan(StagePolarity sp, CameraOrientation co, double mmDx, double mmDy) {
        double[] sample = sp.mmToSampleDelta(mmDx, mmDy);
        return co.sampleToDisplay(sample[0], sample[1]);
    }

    private static boolean matches(double[] vec, Direction dir) {
        return Math.abs(vec[0] - dir.sx) < 1e-6 && Math.abs(vec[1] - dir.sy) < 1e-6;
    }

    // ----- Misc -----

    private static StackPane centered(javafx.scene.Node node) {
        StackPane pane = new StackPane(node);
        pane.setPadding(new Insets(20));
        pane.setAlignment(Pos.CENTER);
        return pane;
    }
}
