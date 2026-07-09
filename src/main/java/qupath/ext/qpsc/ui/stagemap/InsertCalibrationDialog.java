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
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.utilities.ConfigYamlEditor;

/**
 * Non-modal calibration window for a single stage insert. Lets the user set the
 * insert's reference points by typing a value or by driving the stage to that
 * point in the Live Viewer and capturing the current position. "Save to config"
 * writes the captured values back to the microscope YAML via
 * {@link ConfigYamlEditor}.
 *
 * <p>Capture styles, chosen by the insert's declared keys / kind:
 * <ul>
 *   <li><b>Coverslip corners</b> (petri dishes): four {@code coverslip_cN_x_um/_y_um}
 *       points. Drive each corner of the square coverslip to the center of the FOV
 *       and click "Capture corner" -- both X and Y are taken at once. A wireframe
 *       schematic shows the dish, coverslip, and central well with the active corner
 *       highlighted. Corners may be captured in any order; the imaging rectangle is
 *       their bounding box and axis inversion comes from the stage-polarity setting.</li>
 *   <li><b>Edge points</b> (slide holders / legacy): single-axis aperture / slide
 *       edges captured one axis at a time.</li>
 *   <li><b>Per-slot centers</b> (multi-slot holders, {@code num_slides > 1}): for each
 *       slot the operator drives to two <b>diagonal corners</b> of the slide (identifiable
 *       points, unlike the featureless middle) and the dialog stores their midpoint as
 *       {@code slideK_center_x_um/_y_um}. The midpoint of a rectangle's diagonal is its
 *       center regardless of rotation. These per-slot centers override the fixed
 *       {@code num_slides}/{@code slide_spacing_mm} pitch in {@code StageInsert.fromConfigMap}.</li>
 * </ul>
 *
 * <p>The window is intentionally <b>modeless</b> (and always-on-top) so the user
 * can keep operating the Live Viewer / joystick to move the stage while it is open
 * -- a modal dialog would freeze exactly the controls needed to drive to each point.
 * It stays open after Save so multiple points can be captured iteratively.
 */
public final class InsertCalibrationDialog {

    private static final Logger logger = LoggerFactory.getLogger(InsertCalibrationDialog.class);

    /** Stage axis a single-axis reference point is measured along. */
    private enum Axis {
        X,
        Y
    }

    /** A single-axis calibration field: its YAML key, a friendly label, and its axis. */
    private record CalField(String key, String label, Axis axis) {}

    /** A coverslip corner captured as an (X,Y) pair into two YAML keys. */
    private record CalCorner(String label, String xKey, String yKey) {}

    /** A key paired with the text field that edits it (used for save). */
    private record KeyedField(String key, TextField field) {}

    // Single-axis edge fields (slide holders / legacy dishes). Shown only when present.
    private static final List<CalField> KNOWN_FIELDS = List.of(
            new CalField("aperture_left_x_um", "Well / aperture LEFT edge", Axis.X),
            new CalField("aperture_right_x_um", "Well / aperture RIGHT edge", Axis.X),
            new CalField("aperture_top_y_um", "Well / aperture TOP edge", Axis.Y),
            new CalField("aperture_bottom_y_um", "Well / aperture BOTTOM edge", Axis.Y),
            new CalField("slide_left_x_um", "Slide LEFT edge", Axis.X),
            new CalField("slide_right_x_um", "Slide RIGHT edge", Axis.X),
            new CalField("slide_top_y_um", "Slide TOP edge", Axis.Y),
            new CalField("slide_bottom_y_um", "Slide BOTTOM edge", Axis.Y));

    // Coverslip corners (petri dishes). Shown when present.
    private static final List<CalCorner> KNOWN_CORNERS = List.of(
            new CalCorner("Coverslip corner 1", "coverslip_c1_x_um", "coverslip_c1_y_um"),
            new CalCorner("Coverslip corner 2", "coverslip_c2_x_um", "coverslip_c2_y_um"),
            new CalCorner("Coverslip corner 3", "coverslip_c3_x_um", "coverslip_c3_y_um"),
            new CalCorner("Coverslip corner 4", "coverslip_c4_x_um", "coverslip_c4_y_um"));

    private InsertCalibrationDialog() {}

    /**
     * Opens the calibration window (non-modal). Returns immediately.
     *
     * @param owner        the Stage Map window (window owner)
     * @param configPath   path to the microscope YAML to write back to
     * @param insertId     the insert configuration id (e.g. "dish35_well20")
     * @param insertName   display name for the window header
     * @param insertConfig the raw config map for this insert (to read current values)
     * @param onSaved      run on the FX thread after a successful save (e.g. reload the map); may be null
     */
    public static void show(
            Stage owner,
            String configPath,
            String insertId,
            String insertName,
            Map<String, Object> insertConfig,
            Runnable onSaved) {

        List<CalCorner> corners = new ArrayList<>();
        for (CalCorner c : KNOWN_CORNERS) {
            if (isNumber(insertConfig, c.xKey()) || isNumber(insertConfig, c.yKey())) {
                corners.add(c);
            }
        }
        List<CalField> fields = new ArrayList<>();
        for (CalField f : KNOWN_FIELDS) {
            if (isNumber(insertConfig, f.key())) {
                fields.add(f);
            }
        }

        // Per-slot slide-center capture for a multi-slot holder. A slot's CENTER is not a
        // point a human can aim at (the middle of a slide is featureless), so the operator
        // instead drives to two DIAGONAL CORNERS of the slide -- crisp, identifiable points --
        // and we store their midpoint as slideK_center_x_um/_y_um (the midpoint of a
        // rectangle's diagonal is its center regardless of rotation). Driven by num_slides,
        // not the field-presence gate, since these keys do not exist before first capture.
        // Capturing them switches the holder to per-slot mode (StageInsert.fromConfigMap
        // prefers per-slot centers over the fixed pitch).
        int numSlides = (insertConfig != null && insertConfig.get("num_slides") instanceof Number n) ? n.intValue() : 0;
        boolean showPerSlot = numSlides > 1;

        Stage win = new Stage();
        win.setTitle("Calibrate Insert: " + insertName);
        if (owner != null) {
            win.initOwner(owner);
        }
        // Modeless on purpose: the user must keep driving the stage (Live Viewer /
        // joystick) to each point while this is open. Always-on-top so it stays
        // reachable above the Live Viewer during capture.
        win.initModality(Modality.NONE);
        win.setAlwaysOnTop(true);

        Label header = new Label(
                corners.isEmpty()
                        ? "Drive the stage to each point, then click \"Use current\"."
                        : "Drive each coverslip corner to the CENTER of the FOV, then click \"Capture corner\".\n"
                                + "Corners may be captured in any order.");
        header.setStyle("-fx-font-size: 11px;");

        Label currentPosLabel = new Label("Current stage: (press Refresh)");
        Button refreshBtn = new Button("Refresh");

        // Collect every editable (key, field) pair for save.
        List<KeyedField> keyed = new ArrayList<>();

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        int row = 0;

        // Optional dish wireframe, with the active corner highlighted on capture.
        DishWireframe wireframe = corners.isEmpty() ? null : new DishWireframe();
        // Optional slide-holder wireframe (N vertical slots), active slot highlighted.
        SlideHolderWireframe slideWireframe = showPerSlot ? new SlideHolderWireframe(numSlides) : null;

        if (!corners.isEmpty()) {
            grid.addRow(row++, bold("Coverslip corner"), bold("Stage X (um)"), bold("Stage Y (um)"), bold(""));
            for (int i = 0; i < corners.size(); i++) {
                CalCorner c = corners.get(i);
                Label nameLabel = new Label(c.label());
                TextField xField = new TextField(formatUm(numberOr(insertConfig, c.xKey(), 0)));
                TextField yField = new TextField(formatUm(numberOr(insertConfig, c.yKey(), 0)));
                xField.setPrefWidth(100);
                yField.setPrefWidth(100);
                Button capture = new Button("Capture corner");
                capture.setTooltip(new Tooltip("Center the objective on this coverslip corner in the Live "
                        + "Viewer, then click to capture both X and Y."));
                final int idx = i;
                capture.setOnAction(e -> {
                    if (wireframe != null) {
                        wireframe.setActive(idx);
                    }
                    captureCorner(xField, yField, currentPosLabel, capture);
                });
                grid.addRow(row++, nameLabel, xField, yField, capture);
                keyed.add(new KeyedField(c.xKey(), xField));
                keyed.add(new KeyedField(c.yKey(), yField));
            }
        }

        if (!fields.isEmpty()) {
            if (!corners.isEmpty()) {
                grid.add(new Separator(), 0, row++, 4, 1);
            }
            grid.addRow(row++, bold("Reference point"), bold("Stage value (um)"), bold(""));
            for (CalField f : fields) {
                Label nameLabel = new Label(f.label() + "  (" + f.axis() + ")");
                TextField valueField = new TextField(formatUm(numberOr(insertConfig, f.key(), 0)));
                valueField.setPrefWidth(110);
                Button useCurrent = new Button("Use current " + f.axis());
                useCurrent.setTooltip(new Tooltip("Capture the live stage " + f.axis()
                        + " position into this field. Center the objective on the "
                        + f.label().toLowerCase() + " first."));
                final Axis axis = f.axis();
                useCurrent.setOnAction(e -> captureAxis(axis, valueField, currentPosLabel, useCurrent));
                grid.addRow(row++, nameLabel, valueField, useCurrent);
                keyed.add(new KeyedField(f.key(), valueField));
            }
        }

        if (showPerSlot) {
            if (!corners.isEmpty() || !fields.isEmpty()) {
                grid.add(new Separator(), 0, row++, 4, 1);
            }
            grid.addRow(
                    row++,
                    bold("Per-slot center (drive to 2 diagonal corners; overrides fixed pitch)"),
                    bold("Stage X (um)"),
                    bold("Stage Y (um)"),
                    bold(""));
            for (int k = 1; k <= numSlides; k++) {
                final int slotIdx = k - 1;
                String centerXKey = "slide" + k + "_center_x_um";
                String centerYKey = "slide" + k + "_center_y_um";

                // Two transient diagonal-corner fields (not saved) + a read-only derived
                // center (saved). The center recomputes whenever either corner changes.
                TextField cAx = new TextField();
                TextField cAy = new TextField();
                TextField cBx = new TextField();
                TextField cBy = new TextField();
                TextField centerX = new TextField(
                        isNumber(insertConfig, centerXKey) ? formatUm(numberOr(insertConfig, centerXKey, 0)) : "");
                TextField centerY = new TextField(
                        isNumber(insertConfig, centerYKey) ? formatUm(numberOr(insertConfig, centerYKey, 0)) : "");
                for (TextField tf : new TextField[] {cAx, cAy, cBx, cBy, centerX, centerY}) {
                    tf.setPrefWidth(100);
                }
                centerX.setEditable(false);
                centerY.setEditable(false);
                centerX.setStyle("-fx-control-inner-background: #eef;");
                centerY.setStyle("-fx-control-inner-background: #eef;");

                Runnable recompute = () -> {
                    Double ax = parseOrNull(cAx.getText());
                    Double ay = parseOrNull(cAy.getText());
                    Double bx = parseOrNull(cBx.getText());
                    Double by = parseOrNull(cBy.getText());
                    if (ax != null && ay != null && bx != null && by != null) {
                        centerX.setText(formatUm((ax + bx) / 2.0));
                        centerY.setText(formatUm((ay + by) / 2.0));
                    }
                };
                cAx.textProperty().addListener((obs, o, v) -> recompute.run());
                cAy.textProperty().addListener((obs, o, v) -> recompute.run());
                cBx.textProperty().addListener((obs, o, v) -> recompute.run());
                cBy.textProperty().addListener((obs, o, v) -> recompute.run());

                Button captureA = new Button("Capture corner 1");
                Button captureB = new Button("Capture corner 2");
                captureA.setTooltip(new Tooltip("Center the objective on one corner of slide " + k
                        + " (e.g. top-left) in the Live Viewer, then click."));
                captureB.setTooltip(new Tooltip("Center the objective on the OPPOSITE corner of slide " + k
                        + " (e.g. bottom-right), then click."));
                captureA.setOnAction(e -> {
                    if (slideWireframe != null) {
                        slideWireframe.setActive(slotIdx);
                    }
                    captureCorner(cAx, cAy, currentPosLabel, captureA);
                });
                captureB.setOnAction(e -> {
                    if (slideWireframe != null) {
                        slideWireframe.setActive(slotIdx);
                    }
                    captureCorner(cBx, cBy, currentPosLabel, captureB);
                });

                grid.addRow(row++, new Label("Slide " + k + "  corner 1"), cAx, cAy, captureA);
                grid.addRow(row++, new Label("Slide " + k + "  corner 2"), cBx, cBy, captureB);
                grid.addRow(row++, new Label("Slide " + k + "  -> center"), centerX, centerY, new Label(""));

                keyed.add(new KeyedField(centerXKey, centerX));
                keyed.add(new KeyedField(centerYKey, centerY));
            }
        }

        refreshBtn.setOnAction(e -> refreshCurrentPosition(currentPosLabel, refreshBtn));
        HBox posRow = new HBox(8, currentPosLabel, refreshBtn);
        posRow.setAlignment(Pos.CENTER_LEFT);

        Label statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 11px;");

        Button saveBtn = new Button("Save to config");
        Button closeBtn = new Button("Close");
        saveBtn.setDisable(keyed.isEmpty());
        HBox buttonRow = new HBox(8, saveBtn, closeBtn);
        buttonRow.setAlignment(Pos.CENTER_RIGHT);

        Path path = Paths.get(configPath);
        saveBtn.setOnAction(e -> doSave(path, insertId, keyed, statusLabel, onSaved));
        closeBtn.setOnAction(e -> win.close());

        Label tip = new Label("Values are stage coordinates in micrometers; you can also type them directly.\n"
                + "This window stays open and does not block the Live Viewer -- move the stage freely.");
        tip.setStyle("-fx-font-size: 10px; -fx-text-fill: #888;");

        VBox content = new VBox(8, header);
        if (wireframe != null) {
            content.getChildren().add(wireframe.node());
        }
        if (slideWireframe != null) {
            content.getChildren().add(slideWireframe.node());
        }
        // Per-slot capture adds many rows (3 per slot); keep the window a sane height.
        if (showPerSlot) {
            ScrollPane gridScroll = new ScrollPane(grid);
            gridScroll.setFitToWidth(true);
            gridScroll.setPrefViewportHeight(360);
            content.getChildren().add(gridScroll);
        } else {
            content.getChildren().add(grid);
        }
        content.getChildren().addAll(posRow, new Separator(), statusLabel, buttonRow, tip);
        content.setPadding(new Insets(12));

        win.setScene(new Scene(content));
        win.show();
    }

    /** Writes each field back to the YAML and reports the outcome inline. */
    private static void doSave(
            Path path, String insertId, List<KeyedField> keyed, Label statusLabel, Runnable onSaved) {
        boolean anyChanged = false;
        List<String> failures = new ArrayList<>();
        for (KeyedField kf : keyed) {
            String text = kf.field().getText();
            if (text == null || text.isBlank()) continue;
            double value;
            try {
                value = Double.parseDouble(text.trim());
            } catch (NumberFormatException ex) {
                failures.add(kf.key() + " (not a number: '" + text + "')");
                continue;
            }
            try {
                ConfigYamlEditor.Result r = ConfigYamlEditor.setInsertScalar(path, insertId, kf.key(), value);
                if (r.changed) {
                    anyChanged = true;
                    logger.info("Calibration saved: {}", r.message);
                }
            } catch (Exception ex) {
                failures.add(kf.key() + " (" + ex.getMessage() + ")");
                logger.error("Failed to write {}.{}: {}", insertId, kf.key(), ex.getMessage(), ex);
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

    /** Captures the live stage (X,Y) into both corner fields on a background thread. */
    private static void captureCorner(TextField xField, TextField yField, Label currentPosLabel, Button button) {
        button.setDisable(true);
        Thread t = new Thread(
                () -> {
                    try {
                        double[] xy = MicroscopeController.getInstance().getStagePositionXY();
                        Platform.runLater(() -> {
                            xField.setText(formatUm(xy[0]));
                            yField.setText(formatUm(xy[1]));
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
                "Insert-Calibrate-CaptureCorner");
        t.setDaemon(true);
        t.start();
    }

    /** Captures the live stage position (single axis) on a background thread. */
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

    /**
     * Small schematic of a coverslipped petri dish: outer dish outline, square
     * coverslip (its four corners are the calibration fiducials), and the central
     * circular well. The corner being captured is highlighted.
     */
    private static final class DishWireframe {
        private static final double SIZE = 190;
        private final Canvas canvas = new Canvas(SIZE, SIZE);
        private int active = -1;

        DishWireframe() {
            draw();
        }

        Canvas node() {
            return canvas;
        }

        void setActive(int idx) {
            this.active = idx;
            draw();
        }

        private void draw() {
            GraphicsContext g = canvas.getGraphicsContext2D();
            g.clearRect(0, 0, SIZE, SIZE);
            double cx = SIZE / 2, cy = SIZE / 2;
            double dishR = SIZE / 2 - 12; // 35mm dish
            double coverHalf = dishR * (22.0 / 35.0); // 22mm coverslip half-side
            double wellR = dishR * (14.0 / 35.0); // illustrative well

            // dish outline
            g.setStroke(Color.web("#9a9aa6"));
            g.setLineWidth(2);
            g.strokeOval(cx - dishR, cy - dishR, dishR * 2, dishR * 2);

            // coverslip square
            g.setStroke(Color.web("#1f6fb2"));
            g.setLineWidth(2.2);
            g.strokeRect(cx - coverHalf, cy - coverHalf, coverHalf * 2, coverHalf * 2);

            // well circle
            g.setFill(Color.web("#e8f5e9"));
            g.fillOval(cx - wellR, cy - wellR, wellR * 2, wellR * 2);
            g.setStroke(Color.web("#2e8b57"));
            g.setLineWidth(1.6);
            g.strokeOval(cx - wellR, cy - wellR, wellR * 2, wellR * 2);

            // corners 1..4 (TL, TR, BR, BL to match the placeholder ordering)
            double[][] pts = {
                {cx - coverHalf, cy - coverHalf},
                {cx + coverHalf, cy - coverHalf},
                {cx + coverHalf, cy + coverHalf},
                {cx - coverHalf, cy + coverHalf}
            };
            for (int i = 0; i < 4; i++) {
                boolean on = i == active;
                double r = on ? 8 : 5;
                g.setFill(on ? Color.web("#c0392b") : Color.web("#c0392b", 0.55));
                g.fillOval(pts[i][0] - r, pts[i][1] - r, r * 2, r * 2);
                g.setFill(Color.web("#7a1f17"));
                g.fillText(String.valueOf(i + 1), pts[i][0] + (i == 1 || i == 2 ? 6 : -12), pts[i][1] + 4);
            }
        }
    }

    /**
     * Small schematic of a multi-slot vertical slide holder: N vertical slide
     * rectangles side by side, with the slot being captured highlighted. Sibling
     * to {@link DishWireframe} for per-slot capture ergonomics.
     */
    private static final class SlideHolderWireframe {
        private static final double W = 260;
        private static final double H = 120;
        private final Canvas canvas = new Canvas(W, H);
        private final int n;
        private int active = -1;

        SlideHolderWireframe(int slots) {
            this.n = Math.max(1, slots);
            draw();
        }

        Canvas node() {
            return canvas;
        }

        void setActive(int idx) {
            this.active = idx;
            draw();
        }

        private void draw() {
            GraphicsContext g = canvas.getGraphicsContext2D();
            g.clearRect(0, 0, W, H);
            double margin = 10;
            double gap = 8;
            double slotW = (W - 2 * margin - (n - 1) * gap) / n;
            double slotH = H - 2 * margin;
            for (int i = 0; i < n; i++) {
                double x = margin + i * (slotW + gap);
                boolean on = i == active;
                g.setStroke(on ? Color.web("#c0392b") : Color.web("#1f6fb2"));
                g.setLineWidth(on ? 2.6 : 1.8);
                g.strokeRect(x, margin, slotW, slotH);
                g.setFill(Color.web("#7a1f17"));
                g.fillText(String.valueOf(i + 1), x + slotW / 2 - 3, margin + slotH / 2 + 4);
            }
        }
    }

    private static boolean isNumber(Map<String, Object> cfg, String key) {
        return cfg != null && cfg.get(key) instanceof Number;
    }

    private static double numberOr(Map<String, Object> cfg, String key, double dflt) {
        return (cfg != null && cfg.get(key) instanceof Number n) ? n.doubleValue() : dflt;
    }

    /** Parses a text field to a double, or null if blank / not a number. */
    private static Double parseOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
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
