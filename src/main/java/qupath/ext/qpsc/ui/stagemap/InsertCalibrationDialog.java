package qupath.ext.qpsc.ui.stagemap;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.utilities.ConfigYamlEditor;

/**
 * Non-modal calibration window for a single stage insert. Lets the user set the
 * insert's reference points (well-edge / aperture / slide edges) either by
 * typing a value or by driving the stage to that point in the Live Viewer and
 * capturing the current position. "Save to config" writes the captured values
 * back to the microscope YAML via {@link ConfigYamlEditor}.
 *
 * <p>The window is intentionally <b>modeless</b> (and always-on-top) so the user
 * can keep operating the Live Viewer / joystick to move the stage while it is
 * open -- a modal dialog would freeze exactly the controls needed to drive to
 * each point. It stays open after Save so multiple points can be captured and
 * persisted iteratively.
 *
 * <p>The intended workflow for a petri-dish insert: center the objective on the
 * well's left edge in the Live Viewer, click "Use current X" on the LEFT row;
 * repeat for right / top / bottom; click Save. The Stage Map then derives the
 * well center and extent from those four points.
 *
 * <p>Only the reference fields actually present in the insert's config block are
 * shown, so the same window serves both petri-dish carriers (four aperture
 * edges) and slide holders (aperture + slide edges).
 */
public final class InsertCalibrationDialog {

    private static final Logger logger = LoggerFactory.getLogger(InsertCalibrationDialog.class);

    /** Stage axis a reference point is measured along. */
    private enum Axis {
        X,
        Y
    }

    /** A known calibration field: its YAML key, a friendly label, and its axis. */
    private record CalField(String key, String label, Axis axis) {}

    // Ordered list of the reference points the Stage Map understands. Only those
    // present in a given insert's config are shown.
    private static final List<CalField> KNOWN_FIELDS = List.of(
            new CalField("aperture_left_x_um", "Well / aperture LEFT edge", Axis.X),
            new CalField("aperture_right_x_um", "Well / aperture RIGHT edge", Axis.X),
            new CalField("aperture_top_y_um", "Well / aperture TOP edge", Axis.Y),
            new CalField("aperture_bottom_y_um", "Well / aperture BOTTOM edge", Axis.Y),
            new CalField("slide_left_x_um", "Slide LEFT edge", Axis.X),
            new CalField("slide_right_x_um", "Slide RIGHT edge", Axis.X),
            new CalField("slide_top_y_um", "Slide TOP edge", Axis.Y),
            new CalField("slide_bottom_y_um", "Slide BOTTOM edge", Axis.Y));

    private InsertCalibrationDialog() {}

    /**
     * Opens the calibration window (non-modal). Returns immediately.
     *
     * @param owner        the Stage Map window (window owner)
     * @param configPath   path to the microscope YAML to write back to
     * @param insertId     the insert configuration id (e.g. "dish35_well20")
     * @param insertName   display name for the window header
     * @param insertConfig the raw config map for this insert (to read current values)
     * @param onSaved      run on the FX thread after a successful save (e.g. reload the map);
     *                     may be null
     */
    public static void show(
            Stage owner,
            String configPath,
            String insertId,
            String insertName,
            Map<String, Object> insertConfig,
            Runnable onSaved) {

        // Determine which reference fields this insert actually declares.
        List<CalField> fields = new ArrayList<>();
        for (CalField f : KNOWN_FIELDS) {
            if (insertConfig != null && insertConfig.get(f.key()) instanceof Number) {
                fields.add(f);
            }
        }

        Stage win = new Stage();
        win.setTitle("Calibrate Insert: " + insertName);
        if (owner != null) {
            win.initOwner(owner);
        }
        // Modeless on purpose: the user must keep driving the stage (Live Viewer
        // / joystick) to each point while this is open. Always-on-top so it
        // stays reachable above the Live Viewer during capture.
        win.initModality(Modality.NONE);
        win.setAlwaysOnTop(true);

        Label header = new Label("Drive the stage to each point, then click \"Use current\".\n"
                + "For a petri dish the well edge is the easiest feature to focus on.");
        header.setStyle("-fx-font-size: 11px;");

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.addRow(0, bold("Reference point"), bold("Stage value (um)"), bold(""));

        Label currentPosLabel = new Label("Current stage: (press Refresh)");
        Button refreshBtn = new Button("Refresh");

        List<TextField> valueFields = new ArrayList<>();
        int row = 1;
        for (CalField f : fields) {
            Label nameLabel = new Label(f.label() + "  (" + f.axis() + ")");
            double current = ((Number) insertConfig.get(f.key())).doubleValue();
            TextField valueField = new TextField(formatUm(current));
            valueField.setPrefWidth(110);
            Button useCurrent = new Button("Use current " + f.axis());
            useCurrent.setTooltip(new Tooltip("Capture the live stage " + f.axis()
                    + " position into this field. Center the objective on the "
                    + f.label().toLowerCase() + " first."));
            final Axis axis = f.axis();
            useCurrent.setOnAction(e -> captureAxis(axis, valueField, currentPosLabel, useCurrent));
            grid.addRow(row++, nameLabel, valueField, useCurrent);
            valueFields.add(valueField);
        }

        refreshBtn.setOnAction(e -> refreshCurrentPosition(currentPosLabel, refreshBtn));
        HBox posRow = new HBox(8, currentPosLabel, refreshBtn);
        posRow.setAlignment(Pos.CENTER_LEFT);

        Label statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 11px;");

        Button saveBtn = new Button("Save to config");
        Button closeBtn = new Button("Close");
        saveBtn.setDisable(fields.isEmpty());
        HBox buttonRow = new HBox(8, saveBtn, closeBtn);
        buttonRow.setAlignment(Pos.CENTER_RIGHT);

        Path path = Paths.get(configPath);
        saveBtn.setOnAction(e -> doSave(path, insertId, fields, valueFields, statusLabel, onSaved));
        closeBtn.setOnAction(e -> win.close());

        Label tip = new Label("Values are stage coordinates in micrometers; you can also type them directly.\n"
                + "This window stays open and does not block the Live Viewer -- move the stage freely.");
        tip.setStyle("-fx-font-size: 10px; -fx-text-fill: #888;");

        VBox content = new VBox(8, header, grid, posRow, new Separator(), statusLabel, buttonRow, tip);
        content.setPadding(new Insets(12));

        win.setScene(new Scene(content));
        win.show();
    }

    /** Writes each field back to the YAML and reports the outcome inline. */
    private static void doSave(
            Path path,
            String insertId,
            List<CalField> fields,
            List<TextField> valueFields,
            Label statusLabel,
            Runnable onSaved) {
        boolean anyChanged = false;
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            CalField f = fields.get(i);
            String text = valueFields.get(i).getText();
            if (text == null || text.isBlank()) continue;
            double value;
            try {
                value = Double.parseDouble(text.trim());
            } catch (NumberFormatException ex) {
                failures.add(f.key() + " (not a number: '" + text + "')");
                continue;
            }
            try {
                ConfigYamlEditor.Result r = ConfigYamlEditor.setInsertScalar(path, insertId, f.key(), value);
                if (r.changed) {
                    anyChanged = true;
                    logger.info("Calibration saved: {}", r.message);
                }
            } catch (Exception ex) {
                failures.add(f.key() + " (" + ex.getMessage() + ")");
                logger.error("Failed to write {}.{}: {}", insertId, f.key(), ex.getMessage(), ex);
            }
        }

        if (!failures.isEmpty()) {
            statusLabel.setText("Not saved: " + String.join("; ", failures));
            statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #c0392b;");
            return;
        }
        if (anyChanged) {
            statusLabel.setText("Saved. Map updated.");
            statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #1e8449;");
            if (onSaved != null) {
                onSaved.run();
            }
        } else {
            statusLabel.setText("No changes to save.");
            statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        }
    }

    /** Captures the live stage position on a background thread and fills the field. */
    private static void captureAxis(Axis axis, TextField target, Label currentPosLabel, Button button) {
        button.setDisable(true);
        Thread t = new Thread(
                () -> {
                    try {
                        double[] xy = MicroscopeController.getInstance().getStagePositionXY();
                        double value = axis == Axis.X ? xy[0] : xy[1];
                        Platform.runLater(() -> {
                            target.setText(formatUm(value));
                            currentPosLabel.setText(String.format("Current stage: X=%.1f  Y=%.1f um", xy[0], xy[1]));
                            button.setDisable(false);
                        });
                    } catch (Exception ex) {
                        logger.warn("Could not read stage position: {}", ex.getMessage());
                        Platform.runLater(() -> {
                            currentPosLabel.setText("Current stage: unavailable (" + ex.getMessage() + ")");
                            button.setDisable(false);
                        });
                    }
                },
                "Insert-Calibrate-Capture");
        t.setDaemon(true);
        t.start();
    }

    /** Refreshes the read-only current-position label. */
    private static void refreshCurrentPosition(Label currentPosLabel, Button button) {
        button.setDisable(true);
        Thread t = new Thread(
                () -> {
                    try {
                        double[] xy = MicroscopeController.getInstance().getStagePositionXY();
                        Platform.runLater(() -> {
                            currentPosLabel.setText(String.format("Current stage: X=%.1f  Y=%.1f um", xy[0], xy[1]));
                            button.setDisable(false);
                        });
                    } catch (Exception ex) {
                        Platform.runLater(() -> {
                            currentPosLabel.setText("Current stage: unavailable (" + ex.getMessage() + ")");
                            button.setDisable(false);
                        });
                    }
                },
                "Insert-Calibrate-Refresh");
        t.setDaemon(true);
        t.start();
    }

    private static Label bold(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
        return l;
    }

    private static String formatUm(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return String.format("%.1f", v);
    }
}
