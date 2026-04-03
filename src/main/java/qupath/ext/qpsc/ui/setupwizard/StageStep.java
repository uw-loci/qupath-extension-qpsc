package qupath.ext.qpsc.ui.setupwizard;

import java.util.*;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Step 4: Stage Configuration.
 * Collects the stage ID (from catalog or custom) and XYZ travel limits.
 */
public class StageStep implements WizardStep {

    private static final Logger logger = LoggerFactory.getLogger(StageStep.class);

    private static final double LIMIT_MIN = -100000;
    private static final double LIMIT_MAX = 100000;
    private static final double LIMIT_STEP = 1000;

    private final WizardData data;
    private final VBox content;

    private final ResourceCatalog catalog;
    private final ComboBox<String> stageIdCombo;
    private final javafx.scene.control.TextField zDeviceField;
    private final Spinner<Double> xLowSpinner;
    private final Spinner<Double> xHighSpinner;
    private final Spinner<Double> yLowSpinner;
    private final Spinner<Double> yHighSpinner;
    private final Spinner<Double> zLowSpinner;
    private final Spinner<Double> zHighSpinner;

    @SuppressWarnings("unchecked")
    public StageStep(WizardData data, ResourceCatalog catalog) {
        this.data = data;
        this.catalog = catalog;

        content = new VBox(12);
        content.setPadding(new Insets(15));

        // Stage ID
        Label stageLabel = new Label("Stage ID:");
        stageIdCombo = new ComboBox<>();
        stageIdCombo.setEditable(true);
        stageIdCombo.setPromptText("Select from catalog or type a custom ID");

        // Populate from catalog
        Map<String, Map<String, Object>> stages = catalog.getStages();
        for (Map.Entry<String, Map<String, Object>> entry : stages.entrySet()) {
            stageIdCombo.getItems().add(entry.getKey());
        }

        // Z device field (auto-populated from catalog selection)
        Label zDeviceLabel = new Label("Z focus device (MM):");
        zDeviceField = new javafx.scene.control.TextField();
        zDeviceField.setPromptText("e.g., ZDrive, ZStage:Z:32 (auto-filled from catalog)");
        zDeviceField.setTooltip(new javafx.scene.control.Tooltip(
                "Micro-Manager device name for the Z/focus axis.\n"
                + "Auto-populated when selecting a stage from the catalog.\n"
                + "Leave blank for single-Z systems (uses MM Core default)."));

        // Auto-populate Z device when catalog stage is selected
        stageIdCombo.setOnAction(e -> {
            String selected = stageIdCombo.getValue();
            if (selected != null && stages.containsKey(selected)) {
                Map<String, Object> stageInfo = stages.get(selected);
                Object devices = stageInfo.get("devices");
                if (devices instanceof Map) {
                    Object zDevice = ((Map<String, Object>) devices).get("z");
                    if (zDevice != null && !zDevice.toString().isEmpty()) {
                        zDeviceField.setText(zDevice.toString());
                    }
                }
            }
        });

        // Warning label
        Label warningLabel = new Label("WARNING: Set limits conservatively to prevent hardware damage. "
                + "All values are in micrometers (um).");
        warningLabel.setWrapText(true);
        warningLabel.setStyle("-fx-text-fill: #cc6600; -fx-font-weight: bold;");

        // Limits grid
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10, 0, 0, 0));

        // Column headers
        Label axisHeader = new Label("Axis");
        axisHeader.setStyle("-fx-font-weight: bold;");
        Label lowHeader = new Label("Low (um)");
        lowHeader.setStyle("-fx-font-weight: bold;");
        Label highHeader = new Label("High (um)");
        highHeader.setStyle("-fx-font-weight: bold;");

        grid.add(axisHeader, 0, 0);
        grid.add(lowHeader, 1, 0);
        grid.add(highHeader, 2, 0);

        // X axis
        xLowSpinner = createLimitSpinner(data.stageLimitXLow);
        xHighSpinner = createLimitSpinner(data.stageLimitXHigh);
        grid.add(new Label("X:"), 0, 1);
        grid.add(xLowSpinner, 1, 1);
        grid.add(xHighSpinner, 2, 1);

        // Y axis
        yLowSpinner = createLimitSpinner(data.stageLimitYLow);
        yHighSpinner = createLimitSpinner(data.stageLimitYHigh);
        grid.add(new Label("Y:"), 0, 2);
        grid.add(yLowSpinner, 1, 2);
        grid.add(yHighSpinner, 2, 2);

        // Z axis
        zLowSpinner = createLimitSpinner(data.stageLimitZLow);
        zHighSpinner = createLimitSpinner(data.stageLimitZHigh);
        grid.add(new Label("Z:"), 0, 3);
        grid.add(zLowSpinner, 1, 3);
        grid.add(zHighSpinner, 2, 3);

        content.getChildren().addAll(stageLabel, stageIdCombo, zDeviceLabel, zDeviceField, warningLabel, grid);
    }

    private Spinner<Double> createLimitSpinner(double initialValue) {
        SpinnerValueFactory.DoubleSpinnerValueFactory factory =
                new SpinnerValueFactory.DoubleSpinnerValueFactory(LIMIT_MIN, LIMIT_MAX, initialValue, LIMIT_STEP);
        Spinner<Double> spinner = new Spinner<>(factory);
        spinner.setEditable(true);
        spinner.setPrefWidth(150);

        // Commit text on focus loss
        spinner.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                commitSpinnerValue(spinner);
            }
        });

        return spinner;
    }

    /**
     * Parse the text in a spinner editor and commit it to the value factory.
     * Falls back to the current value if parsing fails.
     */
    private void commitSpinnerValue(Spinner<Double> spinner) {
        try {
            String text = spinner.getEditor().getText().trim();
            double value = Double.parseDouble(text);
            spinner.getValueFactory().setValue(value);
        } catch (NumberFormatException e) {
            // Revert to current value
            spinner.getEditor().setText(String.valueOf(spinner.getValue()));
        }
    }

    @Override
    public String getTitle() {
        return "Stage";
    }

    @Override
    public String getDescription() {
        return "Configure the translation stage and its travel limits.";
    }

    @Override
    public Node getContent() {
        return content;
    }

    @Override
    public String validate() {
        String stageId = getStageIdText();
        if (stageId.isEmpty()) {
            return "Stage ID is required.";
        }

        // Commit any pending spinner edits
        commitSpinnerValue(xLowSpinner);
        commitSpinnerValue(xHighSpinner);
        commitSpinnerValue(yLowSpinner);
        commitSpinnerValue(yHighSpinner);
        commitSpinnerValue(zLowSpinner);
        commitSpinnerValue(zHighSpinner);

        if (xLowSpinner.getValue() >= xHighSpinner.getValue()) {
            return "X low limit must be less than X high limit.";
        }
        if (yLowSpinner.getValue() >= yHighSpinner.getValue()) {
            return "Y low limit must be less than Y high limit.";
        }
        if (zLowSpinner.getValue() >= zHighSpinner.getValue()) {
            return "Z low limit must be less than Z high limit.";
        }

        return null;
    }

    private String getStageIdText() {
        // ComboBox with editable: value may come from selection or typed text
        String val = stageIdCombo.getValue();
        if (val == null) {
            val = stageIdCombo.getEditor().getText();
        }
        return val == null ? "" : val.trim();
    }

    @Override
    public void onEnter() {
        // Restore from data
        if (!data.stageId.isEmpty()) {
            stageIdCombo.setValue(data.stageId);
        }
        if (!data.zStageDevice.isEmpty()) {
            zDeviceField.setText(data.zStageDevice);
        }
        xLowSpinner.getValueFactory().setValue(data.stageLimitXLow);
        xHighSpinner.getValueFactory().setValue(data.stageLimitXHigh);
        yLowSpinner.getValueFactory().setValue(data.stageLimitYLow);
        yHighSpinner.getValueFactory().setValue(data.stageLimitYHigh);
        zLowSpinner.getValueFactory().setValue(data.stageLimitZLow);
        zHighSpinner.getValueFactory().setValue(data.stageLimitZHigh);
    }

    @Override
    public void onLeave() {
        // Commit any pending spinner edits before saving
        commitSpinnerValue(xLowSpinner);
        commitSpinnerValue(xHighSpinner);
        commitSpinnerValue(yLowSpinner);
        commitSpinnerValue(yHighSpinner);
        commitSpinnerValue(zLowSpinner);
        commitSpinnerValue(zHighSpinner);

        data.stageId = getStageIdText();
        data.zStageDevice = zDeviceField.getText().trim();
        data.stageLimitXLow = xLowSpinner.getValue();
        data.stageLimitXHigh = xHighSpinner.getValue();
        data.stageLimitYLow = yLowSpinner.getValue();
        data.stageLimitYHigh = yHighSpinner.getValue();
        data.stageLimitZLow = zLowSpinner.getValue();
        data.stageLimitZHigh = zHighSpinner.getValue();

        logger.debug(
                "StageStep: saved stageId={}, X=[{}, {}], Y=[{}, {}], Z=[{}, {}]",
                data.stageId,
                data.stageLimitXLow,
                data.stageLimitXHigh,
                data.stageLimitYLow,
                data.stageLimitYHigh,
                data.stageLimitZLow,
                data.stageLimitZHigh);
    }
}
