package qupath.ext.qpsc.ui.setupwizard;

import java.util.*;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Step 3: Pixel Size Matrix.
 * Displays a grid of objectives (rows) x detectors (columns) where the user
 * enters the pixel size in um for each combination.
 */
public class PixelSizeStep implements WizardStep {

    private static final Logger logger = LoggerFactory.getLogger(PixelSizeStep.class);

    private final WizardData data;
    private final VBox content;

    /** Maps "objectiveId::detectorId" to the corresponding TextField. */
    private final Map<String, TextField> cellFields = new LinkedHashMap<>();

    private GridPane grid;

    public PixelSizeStep(WizardData data, ResourceCatalog catalog) {
        this.data = data;

        content = new VBox(10);
        content.setPadding(new Insets(15));

        Label instructions = new Label("Enter the pixel size (um) for each objective/detector combination. "
                + "Hover over a cell for an estimated value based on sensor pixel size "
                + "and magnification.");
        instructions.setWrapText(true);

        content.getChildren().add(instructions);

        // Grid is built dynamically in onEnter()
    }

    /**
     * Rebuild the pixel size grid from current WizardData objectives and detectors.
     */
    private void rebuildGrid() {
        cellFields.clear();

        // Remove old grid if present
        content.getChildren().removeIf(n -> n instanceof ScrollPane);

        grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));

        List<Map<String, Object>> objectives = data.objectives;
        List<Map<String, Object>> detectors = data.detectors;

        if (objectives.isEmpty() || detectors.isEmpty()) {
            Label emptyLabel = new Label("No objectives or detectors configured. Go back to add hardware.");
            grid.add(emptyLabel, 0, 0);
            ScrollPane scroll = new ScrollPane(grid);
            scroll.setFitToWidth(true);
            VBox.setVgrow(scroll, Priority.ALWAYS);
            content.getChildren().add(scroll);
            return;
        }

        // Header row label
        ColumnConstraints headerCol = new ColumnConstraints();
        headerCol.setPrefWidth(150);
        grid.getColumnConstraints().add(headerCol);

        // Corner cell (empty)
        Label corner = new Label("Objective \\ Detector");
        corner.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
        grid.add(corner, 0, 0);

        // Detector header columns
        for (int d = 0; d < detectors.size(); d++) {
            Map<String, Object> det = detectors.get(d);
            String detName = String.valueOf(det.getOrDefault("name", det.get("id")));
            Label detLabel = new Label(detName);
            detLabel.setStyle("-fx-font-weight: bold;");
            GridPane.setHalignment(detLabel, HPos.CENTER);
            grid.add(detLabel, d + 1, 0);

            ColumnConstraints cc = new ColumnConstraints();
            cc.setPrefWidth(120);
            cc.setHgrow(Priority.SOMETIMES);
            grid.getColumnConstraints().add(cc);
        }

        // Objective rows
        for (int o = 0; o < objectives.size(); o++) {
            Map<String, Object> obj = objectives.get(o);
            String objId = String.valueOf(obj.get("id"));
            String objName = String.valueOf(obj.getOrDefault("name", objId));
            double magnification = obj.get("magnification") instanceof Number
                    ? ((Number) obj.get("magnification")).doubleValue()
                    : 1.0;

            Label rowLabel = new Label(objName);
            rowLabel.setStyle("-fx-font-weight: bold;");
            grid.add(rowLabel, 0, o + 1);

            for (int d = 0; d < detectors.size(); d++) {
                Map<String, Object> det = detectors.get(d);
                String detId = String.valueOf(det.get("id"));
                String key = objId + "::" + detId;

                TextField tf = new TextField();
                tf.setPrefWidth(100);
                tf.setPromptText("um");

                // Pre-fill from existing data
                Double existing = data.pixelSizes.get(key);
                if (existing != null && existing > 0) {
                    tf.setText(String.valueOf(existing));
                }

                // Tooltip with estimated value
                buildTooltip(tf, det, magnification);

                cellFields.put(key, tf);
                grid.add(tf, d + 1, o + 1);
            }
        }

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        content.getChildren().add(scroll);
    }

    /**
     * Build a tooltip showing an estimated pixel size if sensor pixel info is available.
     */
    private void buildTooltip(TextField tf, Map<String, Object> det, double magnification) {
        // Look for a "sensor_pixel_um" or "pixel_pitch_um" field in detector info
        double sensorPixel = ResourceCatalog.getDouble(det, "sensor_pixel_um", 0);
        if (sensorPixel <= 0) {
            sensorPixel = ResourceCatalog.getDouble(det, "pixel_pitch_um", 0);
        }
        if (sensorPixel > 0 && magnification > 0) {
            double estimate = sensorPixel / magnification;
            tf.setTooltip(new Tooltip(String.format(
                    "Estimate: %.4f um (sensor_pixel=%.2f / mag=%.1f)", estimate, sensorPixel, magnification)));
        } else {
            tf.setTooltip(new Tooltip("pixel_size = sensor_pixel_um / magnification"));
        }
    }

    @Override
    public String getTitle() {
        return "Pixel Sizes";
    }

    @Override
    public String getDescription() {
        return "Enter pixel size (um) for each objective and detector combination.";
    }

    @Override
    public Node getContent() {
        return content;
    }

    @Override
    public String validate() {
        for (Map.Entry<String, TextField> entry : cellFields.entrySet()) {
            String text = entry.getValue().getText().trim();
            if (text.isEmpty()) {
                return "All pixel size cells must be filled. Missing: " + entry.getKey();
            }
            try {
                double val = Double.parseDouble(text);
                if (val <= 0) {
                    return "Pixel size must be positive. Invalid value for: " + entry.getKey();
                }
            } catch (NumberFormatException e) {
                return "Invalid number for: " + entry.getKey() + " ('" + text + "')";
            }
        }
        return null;
    }

    @Override
    public void onEnter() {
        rebuildGrid();
    }

    @Override
    public void onLeave() {
        data.pixelSizes.clear();
        for (Map.Entry<String, TextField> entry : cellFields.entrySet()) {
            String text = entry.getValue().getText().trim();
            try {
                data.pixelSizes.put(entry.getKey(), Double.parseDouble(text));
            } catch (NumberFormatException e) {
                // Should not happen after validation, but default to 0
                data.pixelSizes.put(entry.getKey(), 0.0);
            }
        }
        logger.debug("PixelSizeStep: saved {} pixel size entries", data.pixelSizes.size());
    }
}
