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
import qupath.ext.qpsc.ui.ThemeColors;

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
    private final javafx.scene.control.TextField xySpeedField;
    private final javafx.scene.control.TextField xyAccelField;
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
        zDeviceField.setTooltip(new javafx.scene.control.Tooltip("Micro-Manager device name for the Z/focus axis.\n"
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
        warningLabel.setStyle("-fx-text-fill: " + ThemeColors.WARNING + "; -fx-font-weight: bold;");

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

        // --- XY motion profile -------------------------------------------------
        // Held for the whole session rather than left wherever the device adapter put it.
        Label motionHeader = new Label("XY motion profile (optional)");
        motionHeader.setStyle("-fx-font-weight: bold;");

        Label motionWarning =
                new Label("WARNING: large, fast stage moves can have effects that do not announce themselves. "
                        + "An open-loop stage can skip steps while still reporting the position it was "
                        + "told to go to, so the coordinates stay self-consistent while the sample has "
                        + "moved under them -- which is how a multi-slide run can image the wrong part "
                        + "of every slide after the first. Capping speed and acceleration reduces that "
                        + "risk and gives a readback that reveals a controller restart.\n\n"
                        + "Leave both blank to change nothing. If you set them, TEST the result, and "
                        + "check with the stage manufacturer what this hardware is rated for rather "
                        + "than assuming a value is safe.");
        motionWarning.setWrapText(true);
        motionWarning.setStyle("-fx-text-fill: " + ThemeColors.WARNING + ";");

        Label motionHelp = new Label("Raw device-property values, as the stage adapter expects them "
                + "(on Prior these are 1-100 percent, where lower is gentler). Blank = leave alone.");
        motionHelp.setWrapText(true);

        GridPane motionGrid = new GridPane();
        motionGrid.setHgap(10);
        motionGrid.setVgap(6);
        motionGrid.add(new Label("MaxSpeed:"), 0, 0);
        xySpeedField = new javafx.scene.control.TextField();
        xySpeedField.setPrefWidth(120);
        xySpeedField.setPromptText("leave blank");
        motionGrid.add(xySpeedField, 1, 0);
        motionGrid.add(new Label("Acceleration:"), 0, 1);
        xyAccelField = new javafx.scene.control.TextField();
        xyAccelField.setPrefWidth(120);
        xyAccelField.setPromptText("leave blank");
        motionGrid.add(xyAccelField, 1, 1);

        content.getChildren()
                .addAll(
                        stageLabel,
                        stageIdCombo,
                        zDeviceLabel,
                        zDeviceField,
                        warningLabel,
                        grid,
                        motionHeader,
                        motionWarning,
                        motionHelp,
                        motionGrid);
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

        // These are written straight to a device property, so a typo becomes a real
        // instruction to the stage. Reject anything that is not a number here rather than
        // discovering it as an adapter rejection halfway through a run.
        String speedError = validateMotionValue(xySpeedField.getText(), "MaxSpeed");
        if (speedError != null) {
            return speedError;
        }
        String accelError = validateMotionValue(xyAccelField.getText(), "Acceleration");
        if (accelError != null) {
            return accelError;
        }

        return null;
    }

    /**
     * Checks one XY motion value: blank is fine, anything non-numeric is not.
     *
     * <p>Deliberately does NOT police the range. The scale is the adapter's, not ours --
     * Prior takes 1-100 percent, other adapters take um/s or a fixed enum -- so a limit
     * imposed here would be a guess about hardware this wizard is being used to describe.
     * What it can say is that the value has to be a number.
     *
     * @return an error message, or null when acceptable
     */
    private String validateMotionValue(String raw, String label) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            double value = Double.parseDouble(text);
            if (value <= 0) {
                return label + " must be greater than zero, or blank to leave the stage alone.";
            }
        } catch (NumberFormatException e) {
            return label + " must be a number (or blank to leave the stage alone): \"" + text + "\"";
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
        xySpeedField.setText(data.xyMaxSpeedValue == null ? "" : data.xyMaxSpeedValue);
        xyAccelField.setText(data.xyAccelerationValue == null ? "" : data.xyAccelerationValue);
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
        data.xyMaxSpeedValue = xySpeedField.getText().trim();
        data.xyAccelerationValue = xyAccelField.getText().trim();

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
