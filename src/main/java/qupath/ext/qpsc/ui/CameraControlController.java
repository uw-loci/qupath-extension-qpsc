package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

import java.io.IOException;
import java.util.*;

/**
 * CameraControlController - Camera settings control interface for JAI and other cameras.
 *
 * <p>This controller provides a dialog interface for viewing and applying camera
 * exposure and gain settings, particularly useful for white balance troubleshooting.
 * The dialog adapts its layout based on camera type (JAI 3-CCD vs others).
 *
 * <p>Key features:
 * <ul>
 *   <li>Display current camera name from hardware</li>
 *   <li>Objective/Detector selection to load calibration profiles</li>
 *   <li>Mode toggles for Individual vs Unified exposure/gain (JAI only)</li>
 *   <li>Per-angle calibration settings display with editable fields</li>
 *   <li>Apply buttons that set camera settings AND move rotation stage</li>
 *   <li>Automatic live mode handling (turn off during setting changes)</li>
 *   <li>Reload button to restore values from YAML calibration file</li>
 * </ul>
 *
 * <p>Note: Values can be edited for testing, but changes are NOT saved to YAML.
 * Use the Background Collection workflow for permanent calibration.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class CameraControlController {

    private static final Logger logger = LoggerFactory.getLogger(CameraControlController.class);

    /** Default PPM angle names and their rotation values, used when config is unavailable */
    private static final Map<String, Double> DEFAULT_PPM_ANGLES = new LinkedHashMap<>();
    static {
        DEFAULT_PPM_ANGLES.put("uncrossed", 90.0);
        DEFAULT_PPM_ANGLES.put("crossed", 0.0);
        DEFAULT_PPM_ANGLES.put("positive", 7.0);
        DEFAULT_PPM_ANGLES.put("negative", -7.0);
    }

    /** JAI analog gain limits per channel */
    private static final double GAIN_RED_MIN = 0.47;
    private static final double GAIN_RED_MAX = 4.0;
    private static final double GAIN_GREEN_MIN = 1.0;
    private static final double GAIN_GREEN_MAX = 64.0;
    private static final double GAIN_BLUE_MIN = 0.47;
    private static final double GAIN_BLUE_MAX = 4.0;

    /** JAI unified gain limits (applied equally to all channels) */
    private static final double GAIN_UNIFIED_MIN = 1.0;
    private static final double GAIN_UNIFIED_MAX = 8.0;

    /** Style for invalid input fields */
    private static final String STYLE_INVALID = "-fx-border-color: red; -fx-border-width: 2px;";
    private static final String STYLE_WARNING = "-fx-border-color: orange; -fx-border-width: 2px;";
    private static final String STYLE_NORMAL = "";

    /**
     * Displays the camera control dialog for viewing and applying camera settings.
     *
     * <p>This method creates and shows a non-modal dialog that allows users to view
     * and test camera exposure/gain settings loaded from calibration profiles.
     *
     * @throws RuntimeException if resource bundle loading fails or dialog creation encounters errors
     */
    public static void showCameraControlDialog() {
        logger.info("Initiating camera control dialog display");

        Platform.runLater(() -> {
            // Check connection first
            MicroscopeController controller = MicroscopeController.getInstance();
            if (!controller.isConnected()) {
                ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");
                UIFunctions.showAlertDialog(res.getString("camera.dialog.connectFirst"));
                logger.warn("Camera control dialog blocked - not connected to server");
                return;
            }

            try {
                createAndShowDialog();
            } catch (Exception e) {
                logger.error("Failed to create camera control dialog", e);
                UIFunctions.notifyUserOfError("Failed to open camera control dialog: " + e.getMessage(), "Error");
            }
        });
    }

    private static void createAndShowDialog() {
        logger.debug("Creating camera control dialog on JavaFX Application Thread");
        ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

        Dialog<Void> dlg = new Dialog<>();
        dlg.setTitle(res.getString("camera.dialog.title"));
        dlg.setHeaderText(res.getString("camera.dialog.header"));

        // Get controller and config
        MicroscopeController controller = MicroscopeController.getInstance();
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);

        // Main content
        VBox content = new VBox(10);
        content.setPadding(new Insets(15));
        content.setPrefWidth(650);

        // --- Camera Info Section ---
        Label cameraLabel = new Label();
        try {
            String cameraName = controller.getCameraName();
            cameraLabel.setText(res.getString("camera.label.camera") + " " + cameraName);
            cameraLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
            logger.info("Camera name: {}", cameraName);
        } catch (IOException e) {
            cameraLabel.setText(res.getString("camera.label.camera") + " Error: " + e.getMessage());
            cameraLabel.setStyle("-fx-text-fill: red;");
        }
        content.getChildren().add(cameraLabel);

        // --- Objective/Detector Selection ---
        GridPane selectionGrid = new GridPane();
        selectionGrid.setHgap(10);
        selectionGrid.setVgap(5);

        // Get available objectives and detectors from config
        // Use PPM modality to get objectives that have PPM calibration profiles
        Set<String> objectives = mgr.getAvailableObjectivesForModality("ppm");
        Set<String> detectors = mgr.getHardwareDetectors();

        ComboBox<String> objectiveCombo = new ComboBox<>();
        objectiveCombo.getItems().addAll(objectives);
        if (!objectives.isEmpty()) {
            objectiveCombo.setValue(objectives.iterator().next());
        }

        ComboBox<String> detectorCombo = new ComboBox<>();
        detectorCombo.getItems().addAll(detectors);
        if (!detectors.isEmpty()) {
            detectorCombo.setValue(detectors.iterator().next());
        }

        selectionGrid.add(new Label(res.getString("camera.label.objective")), 0, 0);
        selectionGrid.add(objectiveCombo, 1, 0);
        selectionGrid.add(new Label(res.getString("camera.label.detector")), 0, 1);
        selectionGrid.add(detectorCombo, 1, 1);

        // Reload from YAML button next to selectors
        Button reloadAllButton = new Button(res.getString("camera.button.reloadAll"));
        selectionGrid.add(reloadAllButton, 2, 0, 1, 2);
        GridPane.setValignment(reloadAllButton, javafx.geometry.VPos.CENTER);

        content.getChildren().add(selectionGrid);

        // --- Mode Toggles (JAI only) ---
        VBox modeBox = new VBox(5);
        modeBox.setPadding(new Insets(10, 0, 10, 0));

        ToggleGroup exposureModeGroup = new ToggleGroup();
        RadioButton expIndividualRb = new RadioButton(res.getString("camera.label.individual"));
        RadioButton expUnifiedRb = new RadioButton(res.getString("camera.label.unified"));
        expIndividualRb.setToggleGroup(exposureModeGroup);
        expUnifiedRb.setToggleGroup(exposureModeGroup);

        ToggleGroup gainModeGroup = new ToggleGroup();
        RadioButton gainIndividualRb = new RadioButton(res.getString("camera.label.individual"));
        RadioButton gainUnifiedRb = new RadioButton(res.getString("camera.label.unified"));
        gainIndividualRb.setToggleGroup(gainModeGroup);
        gainUnifiedRb.setToggleGroup(gainModeGroup);

        HBox expModeRow = new HBox(10, new Label(res.getString("camera.label.exposureMode")),
                expIndividualRb, expUnifiedRb);
        expModeRow.setAlignment(Pos.CENTER_LEFT);

        HBox gainModeRow = new HBox(10, new Label(res.getString("camera.label.gainMode")),
                gainIndividualRb, gainUnifiedRb);
        gainModeRow.setAlignment(Pos.CENTER_LEFT);

        modeBox.getChildren().addAll(expModeRow, gainModeRow);

        // Check camera type and set initial mode
        boolean isJAI = false;
        try {
            MicroscopeSocketClient.CameraModeResult modeResult = controller.getCameraMode();
            isJAI = modeResult.isJAI();
            if (isJAI) {
                expIndividualRb.setSelected(modeResult.exposureIndividual());
                expUnifiedRb.setSelected(!modeResult.exposureIndividual());
                gainIndividualRb.setSelected(modeResult.gainIndividual());
                gainUnifiedRb.setSelected(!modeResult.gainIndividual());
            } else {
                // Non-JAI camera - hide mode toggles
                modeBox.setVisible(false);
                modeBox.setManaged(false);
            }
        } catch (IOException e) {
            logger.warn("Could not get camera mode: {}", e.getMessage());
            modeBox.setVisible(false);
            modeBox.setManaged(false);
        }

        // Store field references for reload functionality (need to declare early for closures)
        final Map<String, AngleFields> angleFieldsMap = new HashMap<>();

        // Mode change listeners (JAI only)
        if (isJAI) {
            expIndividualRb.setOnAction(e -> {
                try {
                    boolean expInd = expIndividualRb.isSelected();
                    boolean gainInd = gainIndividualRb.isSelected();
                    controller.withLiveModeHandling(() ->
                            controller.setCameraMode(expInd, gainInd));
                    updateAllFieldStates(angleFieldsMap, expInd, gainInd);
                } catch (IOException ex) {
                    revertRadioButtonsToCamera(controller, expIndividualRb, expUnifiedRb,
                            gainIndividualRb, gainUnifiedRb, angleFieldsMap);
                    UIFunctions.notifyUserOfError("Failed to set exposure mode: " + ex.getMessage(), "Error");
                }
            });
            expUnifiedRb.setOnAction(e -> {
                try {
                    boolean expInd = expIndividualRb.isSelected();
                    boolean gainInd = gainIndividualRb.isSelected();
                    controller.withLiveModeHandling(() ->
                            controller.setCameraMode(expInd, gainInd));
                    updateAllFieldStates(angleFieldsMap, expInd, gainInd);
                } catch (IOException ex) {
                    revertRadioButtonsToCamera(controller, expIndividualRb, expUnifiedRb,
                            gainIndividualRb, gainUnifiedRb, angleFieldsMap);
                    UIFunctions.notifyUserOfError("Failed to set exposure mode: " + ex.getMessage(), "Error");
                }
            });
            gainIndividualRb.setOnAction(e -> {
                try {
                    boolean expInd = expIndividualRb.isSelected();
                    boolean gainInd = gainIndividualRb.isSelected();
                    controller.withLiveModeHandling(() ->
                            controller.setCameraMode(expInd, gainInd));
                    updateAllFieldStates(angleFieldsMap, expInd, gainInd);
                } catch (IOException ex) {
                    revertRadioButtonsToCamera(controller, expIndividualRb, expUnifiedRb,
                            gainIndividualRb, gainUnifiedRb, angleFieldsMap);
                    UIFunctions.notifyUserOfError("Failed to set gain mode: " + ex.getMessage(), "Error");
                }
            });
            gainUnifiedRb.setOnAction(e -> {
                try {
                    boolean expInd = expIndividualRb.isSelected();
                    boolean gainInd = gainIndividualRb.isSelected();
                    controller.withLiveModeHandling(() ->
                            controller.setCameraMode(expInd, gainInd));
                    updateAllFieldStates(angleFieldsMap, expInd, gainInd);
                } catch (IOException ex) {
                    revertRadioButtonsToCamera(controller, expIndividualRb, expUnifiedRb,
                            gainIndividualRb, gainUnifiedRb, angleFieldsMap);
                    UIFunctions.notifyUserOfError("Failed to set gain mode: " + ex.getMessage(), "Error");
                }
            });
        }

        content.getChildren().add(modeBox);

        // --- White Balance Mode Section (JAI only) ---
        VBox wbModeBox = new VBox(5);
        wbModeBox.setPadding(new Insets(5, 0, 5, 0));

        ComboBox<String> wbModeCombo = new ComboBox<>();
        wbModeCombo.getItems().addAll("Off", "Continuous", "Once");
        wbModeCombo.setValue("Off");

        // Apply WB mode immediately on selection change, disabling the combo box
        // while the command executes to prevent rapid repeated changes
        wbModeCombo.setOnAction(e -> {
            String selected = wbModeCombo.getValue();
            if (selected == null) return;

            int mode = switch (selected) {
                case "Continuous" -> 1;
                case "Once" -> 2;
                default -> 0;
            };

            wbModeCombo.setDisable(true);
            Thread thread = new Thread(() -> {
                try {
                    controller.withLiveModeHandling(() ->
                            controller.setWhiteBalanceMode(mode));
                    logger.info("Applied WB mode: {}", selected);
                } catch (IOException ex) {
                    Platform.runLater(() ->
                            UIFunctions.notifyUserOfError("Failed to set WB mode: " + ex.getMessage(), "Error"));
                    logger.error("Failed to set WB mode: {}", ex.getMessage());
                } finally {
                    Platform.runLater(() -> wbModeCombo.setDisable(false));
                }
            });
            thread.setDaemon(true);
            thread.start();
        });

        HBox wbModeRow = new HBox(10, new Label("White Balance Mode:"), wbModeCombo);
        wbModeRow.setAlignment(Pos.CENTER_LEFT);
        wbModeBox.getChildren().add(wbModeRow);

        if (isJAI) {
            content.getChildren().add(wbModeBox);
        }

        // --- Per-Angle Settings Section (Flat Layout) ---
        Label settingsHeader = new Label(res.getString("camera.label.perAngleSettings"));
        settingsHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        Label settingsNote = new Label(res.getString("camera.label.settingsNote"));
        settingsNote.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");
        settingsNote.setWrapText(true);

        // Note about stage rotation
        Label rotationNote = new Label("Note: Clicking 'Apply & Go' will rotate the polarization stage to the selected angle.");
        rotationNote.setStyle("-fx-font-size: 11px; -fx-text-fill: #cc6600; -fx-font-weight: bold;");
        rotationNote.setWrapText(true);

        // WB method provenance label - shows which calibration method produced current settings
        Label wbMethodLabel = new Label("WB: No calibration loaded");
        wbMethodLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #999999;");
        wbMethodLabel.setWrapText(true);

        content.getChildren().addAll(new Separator(), settingsHeader, settingsNote, rotationNote, wbMethodLabel);

        // Global status label
        Label statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 11px;");

        // Styles for row differentiation
        final String EXPOSURE_ROW_STYLE = "-fx-background-color: #f0f7ff; -fx-padding: 4px;";  // Light blue
        final String GAIN_ROW_STYLE = "-fx-background-color: #fff7f0; -fx-padding: 4px;";      // Light peach
        final String ANGLE_HEADER_STYLE = "-fx-background-color: #e8e8e8; -fx-padding: 6px;";

        // Load PPM angles from YAML config (falls back to defaults)
        Map<String, Double> ppmAngles = loadPpmAngles(mgr);

        // Use VBox with individual angle "cards" for clearer grouping
        VBox anglesContainer = new VBox(10);
        anglesContainer.setPadding(new Insets(10, 0, 10, 0));

        for (Map.Entry<String, Double> angleEntry : ppmAngles.entrySet()) {
            String angleName = angleEntry.getKey();
            double angleDegrees = angleEntry.getValue();

            // Create a bordered VBox for each angle "card"
            VBox angleCard = new VBox(0);
            angleCard.setStyle("-fx-border-color: #cccccc; -fx-border-width: 1px; -fx-border-radius: 4px; -fx-background-radius: 4px;");

            // --- Angle Header Row with Apply Button ---
            HBox headerRow = new HBox(10);
            headerRow.setAlignment(Pos.CENTER_LEFT);
            headerRow.setStyle(ANGLE_HEADER_STYLE);
            headerRow.setPadding(new Insets(6, 10, 6, 10));

            Label angleLabel = new Label(String.format("%s (%.0f deg)", capitalize(angleName), angleDegrees));
            angleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
            HBox.setHgrow(angleLabel, Priority.ALWAYS);

            Button applyButton = new Button("Apply & Go");
            applyButton.setTooltip(new Tooltip(String.format(
                    "Apply camera settings and rotate polarizer to %.0f deg", angleDegrees)));

            headerRow.getChildren().addAll(angleLabel, applyButton);
            angleCard.getChildren().add(headerRow);

            // --- Values Grid with column headers ---
            GridPane valuesGrid = new GridPane();
            valuesGrid.setHgap(8);
            valuesGrid.setVgap(0);
            valuesGrid.setPadding(new Insets(4, 10, 4, 10));

            // Column constraints
            ColumnConstraints typeCol = new ColumnConstraints();
            typeCol.setMinWidth(100);
            typeCol.setPrefWidth(110);
            ColumnConstraints valCol = new ColumnConstraints();
            valCol.setPrefWidth(55);

            valuesGrid.getColumnConstraints().addAll(typeCol, valCol, valCol, valCol, valCol);

            // Column header row
            int gridRow = 0;
            Label colAll = new Label(res.getString("camera.label.all"));
            colAll.setStyle("-fx-font-weight: bold; -fx-font-size: 10px; -fx-text-fill: #666666;");
            Label colR = new Label(res.getString("camera.label.r"));
            colR.setStyle("-fx-font-weight: bold; -fx-font-size: 10px; -fx-text-fill: #666666;");
            Label colG = new Label(res.getString("camera.label.g"));
            colG.setStyle("-fx-font-weight: bold; -fx-font-size: 10px; -fx-text-fill: #666666;");
            Label colB = new Label(res.getString("camera.label.b"));
            colB.setStyle("-fx-font-weight: bold; -fx-font-size: 10px; -fx-text-fill: #666666;");

            valuesGrid.add(new Label(""), 0, gridRow);
            valuesGrid.add(colAll, 1, gridRow);
            valuesGrid.add(colR, 2, gridRow);
            valuesGrid.add(colG, 3, gridRow);
            valuesGrid.add(colB, 4, gridRow);
            gridRow++;

            // --- Exposure Row (with colored background) ---
            Label expLabel = new Label(res.getString("camera.label.exposureMs"));
            expLabel.setStyle("-fx-font-weight: bold;");
            expLabel.setMinWidth(100);

            TextField expAllField = createSmallField("0");
            TextField expRField = createSmallField("0");
            TextField expGField = createSmallField("0");
            TextField expBField = createSmallField("0");

            // Create HBox for exposure row with background color
            HBox expRow = new HBox(8);
            expRow.setStyle(EXPOSURE_ROW_STYLE);
            expRow.setAlignment(Pos.CENTER_LEFT);
            expRow.setPadding(new Insets(4, 10, 4, 10));
            expLabel.setMinWidth(100);
            expAllField.setMaxWidth(55);
            expRField.setMaxWidth(55);
            expGField.setMaxWidth(55);
            expBField.setMaxWidth(55);
            expRow.getChildren().addAll(expLabel, expAllField, expRField, expGField, expBField);

            // --- Gain Row (with colored background) ---
            Label gainLabel = new Label(res.getString("camera.label.gain"));
            gainLabel.setStyle("-fx-font-weight: bold;");
            gainLabel.setMinWidth(100);
            gainLabel.setTooltip(new Tooltip("JAI analog gain ranges:\nR: 0.47-4.0\nG: 1.0-64.0\nB: 0.47-4.0"));

            TextField gainAllField = createSmallField("1.0");
            gainAllField.setTooltip(new Tooltip("Unified gain (applies to all channels)"));

            TextField gainRField = createGainField("1.0", GAIN_RED_MIN, GAIN_RED_MAX, "Red");
            TextField gainGField = createGainField("1.0", GAIN_GREEN_MIN, GAIN_GREEN_MAX, "Green");
            TextField gainBField = createGainField("1.0", GAIN_BLUE_MIN, GAIN_BLUE_MAX, "Blue");

            // Create HBox for gain row with background color
            HBox gainRow = new HBox(8);
            gainRow.setStyle(GAIN_ROW_STYLE);
            gainRow.setAlignment(Pos.CENTER_LEFT);
            gainRow.setPadding(new Insets(4, 10, 4, 10));
            gainLabel.setMinWidth(100);
            gainAllField.setMaxWidth(55);
            gainRField.setMaxWidth(55);
            gainGField.setMaxWidth(55);
            gainBField.setMaxWidth(55);
            gainRow.getChildren().addAll(gainLabel, gainAllField, gainRField, gainGField, gainBField);

            // Add rows to card
            angleCard.getChildren().addAll(expRow, gainRow);

            // Store fields for this angle
            AngleFields fields = new AngleFields(expAllField, expRField, expGField, expBField,
                    gainAllField, gainRField, gainGField, gainBField);
            angleFieldsMap.put(angleName, fields);

            // Wire up Apply button
            final TextField finalExpRField = expRField;
            final TextField finalExpGField = expGField;
            final TextField finalExpBField = expBField;
            applyButton.setOnAction(e -> applySettingsWithLiveModeHandling(
                    controller, angleName, angleDegrees,
                    finalExpRField, finalExpGField, finalExpBField,
                    angleFieldsMap.get(angleName),
                    statusLabel, res));

            anglesContainer.getChildren().add(angleCard);
        }

        content.getChildren().add(anglesContainer);

        // Status label
        content.getChildren().add(statusLabel);

        // Reload all button action
        final boolean isJAIFinal = isJAI;
        reloadAllButton.setOnAction(e -> {
            String objective = objectiveCombo.getValue();
            String detector = detectorCombo.getValue();
            if (objective != null && detector != null) {
                reloadAllAnglesFromYAML(mgr, objective, detector, angleFieldsMap, statusLabel, res,
                        isJAIFinal ? controller : null,
                        expIndividualRb, expUnifiedRb, gainIndividualRb, gainUnifiedRb,
                        wbMethodLabel);
            }
        });

        // Objective/Detector change listeners - reload values
        objectiveCombo.setOnAction(e -> {
            String objective = objectiveCombo.getValue();
            String detector = detectorCombo.getValue();
            if (objective != null && detector != null) {
                reloadAllAnglesFromYAML(mgr, objective, detector, angleFieldsMap, statusLabel, res,
                        isJAIFinal ? controller : null,
                        expIndividualRb, expUnifiedRb, gainIndividualRb, gainUnifiedRb,
                        wbMethodLabel);
            }
        });
        detectorCombo.setOnAction(e -> {
            String objective = objectiveCombo.getValue();
            String detector = detectorCombo.getValue();
            if (objective != null && detector != null) {
                reloadAllAnglesFromYAML(mgr, objective, detector, angleFieldsMap, statusLabel, res,
                        isJAIFinal ? controller : null,
                        expIndividualRb, expUnifiedRb, gainIndividualRb, gainUnifiedRb,
                        wbMethodLabel);
            }
        });

        // Initial load of values (sets mode radio buttons and field states from YAML)
        String objective = objectiveCombo.getValue();
        String detector = detectorCombo.getValue();
        if (objective != null && detector != null) {
            reloadAllAnglesFromYAML(mgr, objective, detector, angleFieldsMap, statusLabel, res,
                    isJAI ? controller : null,
                    expIndividualRb, expUnifiedRb, gainIndividualRb, gainUnifiedRb,
                    wbMethodLabel);
        }

        // Finalize dialog
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(550);

        dlg.getDialogPane().setContent(scrollPane);
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dlg.initModality(Modality.NONE);

        // Make dialog always on top
        dlg.setOnShown(event -> {
            Stage stage = (Stage) dlg.getDialogPane().getScene().getWindow();
            stage.setAlwaysOnTop(true);
        });

        dlg.show();
        logger.info("Camera control dialog displayed successfully");
    }

    /**
     * Creates a small text field for exposure/gain values.
     */
    private static TextField createSmallField(String initialValue) {
        TextField field = new TextField(initialValue);
        field.setPrefWidth(55);
        field.setMaxWidth(55);
        return field;
    }

    /**
     * Creates a gain text field with live validation.
     * Shows red border if value is outside valid range.
     *
     * @param initialValue Initial value
     * @param minVal Minimum valid value
     * @param maxVal Maximum valid value
     * @param channelName Channel name for tooltip (e.g., "Red")
     * @return TextField with validation
     */
    private static TextField createGainField(String initialValue, double minVal, double maxVal, String channelName) {
        TextField field = new TextField(initialValue);
        field.setPrefWidth(55);
        field.setMaxWidth(55);
        field.setTooltip(new Tooltip(String.format("%s gain (valid: %.2f-%.1f)", channelName, minVal, maxVal)));

        // Add live validation on text change
        field.textProperty().addListener((obs, oldVal, newVal) -> {
            validateGainField(field, minVal, maxVal);
        });

        // Initial validation
        validateGainField(field, minVal, maxVal);

        return field;
    }

    /**
     * Validates a gain field value and updates its style.
     */
    private static void validateGainField(TextField field, double minVal, double maxVal) {
        try {
            double value = Double.parseDouble(field.getText());
            if (value < minVal || value > maxVal) {
                field.setStyle(STYLE_INVALID);
            } else {
                field.setStyle(STYLE_NORMAL);
            }
        } catch (NumberFormatException e) {
            field.setStyle(STYLE_INVALID);
        }
    }

    /**
     * Applies camera settings for an angle, handling live mode automatically.
     * If live mode is running, it will be turned off, settings applied, then turned back on.
     */
    private static void applySettingsWithLiveModeHandling(
            MicroscopeController controller,
            String angleName, double angleDegrees,
            TextField expRField, TextField expGField, TextField expBField,
            AngleFields fields,
            Label statusLabel, ResourceBundle res) {

        try {
            // Parse exposure values - use unified field if individual fields are disabled
            float[] exposures;
            if (fields.expR.isDisabled()) {
                // Unified exposure mode - use the "All" field value for all channels
                float expAll = Float.parseFloat(fields.expAll.getText());
                exposures = new float[]{expAll, expAll, expAll};
            } else {
                // Individual exposure mode - use per-channel fields
                exposures = new float[]{
                        Float.parseFloat(expRField.getText()),
                        Float.parseFloat(expGField.getText()),
                        Float.parseFloat(expBField.getText())
                };
            }

            // Parse gain values - use unified field if individual fields are disabled
            float[] gains;
            if (fields.gainR.isDisabled()) {
                // Unified gain mode - send length-1 array so Python server
                // calls set_unified_gain() instead of set_analog_gains()
                float gainAll = Float.parseFloat(fields.gainAll.getText());
                gains = new float[]{gainAll};
            } else {
                // Individual gain mode - use per-channel fields
                gains = new float[]{
                        Float.parseFloat(fields.gainR.getText()),
                        Float.parseFloat(fields.gainG.getText()),
                        Float.parseFloat(fields.gainB.getText())
                };
            }

            // Check for out-of-range gains and build warning message
            StringBuilder gainWarnings = new StringBuilder();
            boolean unifiedGainMode = fields.gainR.isDisabled();
            if (unifiedGainMode) {
                // In unified mode, check against unified gain range (1.0-8.0)
                if (gains[0] < GAIN_UNIFIED_MIN || gains[0] > GAIN_UNIFIED_MAX) {
                    gainWarnings.append(String.format("Unified gain %.2f outside valid range %.1f-%.1f; ",
                            gains[0], GAIN_UNIFIED_MIN, GAIN_UNIFIED_MAX));
                }
            } else {
                // In individual mode, check each channel separately
                if (gains[0] < GAIN_RED_MIN || gains[0] > GAIN_RED_MAX) {
                    gainWarnings.append(String.format("R gain %.2f clamped to %.2f-%.1f; ",
                            gains[0], GAIN_RED_MIN, GAIN_RED_MAX));
                }
                if (gains[1] < GAIN_GREEN_MIN || gains[1] > GAIN_GREEN_MAX) {
                    gainWarnings.append(String.format("G gain %.2f clamped to %.1f-%.1f; ",
                            gains[1], GAIN_GREEN_MIN, GAIN_GREEN_MAX));
                }
                if (gains[2] < GAIN_BLUE_MIN || gains[2] > GAIN_BLUE_MAX) {
                    gainWarnings.append(String.format("B gain %.2f clamped to %.2f-%.1f; ",
                            gains[2], GAIN_BLUE_MIN, GAIN_BLUE_MAX));
                }
            }

            // Apply settings with live mode handling - stops live mode if running,
            // applies settings, then restores live mode
            controller.withLiveModeHandling(() ->
                    controller.applyCameraSettingsForAngle(angleName, exposures, gains, angleDegrees));

            // Show status with any gain warnings
            if (gainWarnings.length() > 0) {
                statusLabel.setText(String.format("Applied %s (%.0f deg) - WARNING: %s",
                        angleName, angleDegrees, gainWarnings.toString()));
                statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: orange;");
                logger.warn("Applied settings for {} with clamped gains: {}", angleName, gainWarnings);
            } else {
                statusLabel.setText(String.format(res.getString("camera.status.applied"), angleName, angleDegrees));
                statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: green;");
                logger.info("Applied settings for angle {} at {} degrees", angleName, angleDegrees);
            }

        } catch (NumberFormatException ex) {
            statusLabel.setText("Invalid number format");
            statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: red;");
        } catch (IOException ex) {
            String errorMsg = String.format(res.getString("camera.error.applyFailed"), ex.getMessage());
            statusLabel.setText(errorMsg);
            statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: red;");
            logger.error("Failed to apply settings for {}: {}", angleName, ex.getMessage());
        }
    }

    /**
     * Reloads calibration values for all angles from YAML configuration.
     *
     * <p>Re-reads the YAML from disk, loads all angle values, and sets the
     * exposure/gain mode radio buttons to match what the calibration profile
     * contains (per-channel exposures = individual exposure mode,
     * unified_gain present = unified gain mode).
     *
     * @param mgr Config manager
     * @param objective Objective ID
     * @param detector Detector ID
     * @param angleFieldsMap Map of angle name to UI fields
     * @param statusLabel Status label to update
     * @param res Resource bundle
     * @param controller MicroscopeController for setting camera mode (null to skip mode changes)
     * @param expIndividualRb Exposure individual radio button
     * @param expUnifiedRb Exposure unified radio button
     * @param gainIndividualRb Gain individual radio button
     * @param gainUnifiedRb Gain unified radio button
     * @param wbMethodLabel Label to display WB method provenance (null to skip)
     */
    @SuppressWarnings("unchecked")
    private static void reloadAllAnglesFromYAML(MicroscopeConfigManager mgr, String objective, String detector,
                                                Map<String, AngleFields> angleFieldsMap, Label statusLabel,
                                                ResourceBundle res, MicroscopeController controller,
                                                RadioButton expIndividualRb, RadioButton expUnifiedRb,
                                                RadioButton gainIndividualRb, RadioButton gainUnifiedRb,
                                                Label wbMethodLabel) {
        logger.info("Reloading all angles from YAML for objective={}, detector={}", objective, detector);

        // Re-read the YAML files from disk so we pick up any changes made by
        // external processes (e.g., Python white balance calibration writes to
        // imageprocessing_PPM.yml while Java keeps a cached copy in memory).
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        mgr.reload(configPath);

        boolean anyLoaded = false;
        for (String angleName : angleFieldsMap.keySet()) {
            AngleFields fields = angleFieldsMap.get(angleName);
            if (fields != null) {
                boolean loaded = loadAngleFromYAML(mgr, objective, detector, angleName, fields, null, res);
                if (loaded) anyLoaded = true;
            }
        }

        if (anyLoaded) {
            statusLabel.setText(res.getString("camera.status.reloaded"));
            statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: green;");

            // Detect the correct camera mode from the YAML profile structure and
            // set radio buttons + camera mode accordingly. This ensures "Apply & Go"
            // reads the correct fields (e.g., unified gain instead of per-channel).
            if (controller != null) {
                detectAndSetModeFromYAML(mgr, objective, detector, controller,
                        expIndividualRb, expUnifiedRb, gainIndividualRb, gainUnifiedRb,
                        angleFieldsMap, statusLabel);
            }

            // Update WB method provenance label from YAML gains data
            if (wbMethodLabel != null) {
                updateWbMethodLabel(mgr, objective, detector, wbMethodLabel);
            }
        } else {
            statusLabel.setText(String.format(res.getString("camera.error.noCalibration"), objective, detector));
            statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: orange;");
            if (wbMethodLabel != null) {
                wbMethodLabel.setText("WB: No calibration loaded");
                wbMethodLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #999999;");
            }
        }
    }

    /**
     * Detects the appropriate camera mode from YAML profile structure and sets
     * radio buttons and camera mode to match.
     *
     * <p>WB calibration writes per-channel exposures (r/g/b) with a unified_gain.
     * This method inspects the first available angle to detect:
     * <ul>
     *   <li>Per-channel exposures (r/g/b keys) -> exposure individual mode</li>
     *   <li>unified_gain key present -> gain unified mode</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private static void detectAndSetModeFromYAML(MicroscopeConfigManager mgr, String objective, String detector,
                                                  MicroscopeController controller,
                                                  RadioButton expIndividualRb, RadioButton expUnifiedRb,
                                                  RadioButton gainIndividualRb, RadioButton gainUnifiedRb,
                                                  Map<String, AngleFields> angleFieldsMap, Label statusLabel) {
        try {
            // Check exposure structure from first available angle
            boolean hasPerChannelExposures = false;
            boolean hasUnifiedGain = false;

            Map<String, Object> exposuresMap = mgr.getModalityExposures("ppm", objective, detector);
            if (exposuresMap != null) {
                for (String angleName : angleFieldsMap.keySet()) {
                    Object angleExp = exposuresMap.get(angleName);
                    if (angleExp instanceof Map<?, ?>) {
                        Map<String, Object> expValues = (Map<String, Object>) angleExp;
                        if (expValues.containsKey("r") && expValues.containsKey("g") && expValues.containsKey("b")) {
                            hasPerChannelExposures = true;
                        }
                        break; // Only need to check one angle
                    }
                }
            }

            // Check gain structure from first available angle
            for (String angleName : angleFieldsMap.keySet()) {
                Object gainsObj = mgr.getProfileSetting("ppm", objective, detector, "gains", angleName);
                if (gainsObj instanceof Map<?, ?>) {
                    Map<String, Object> gainValues = (Map<String, Object>) gainsObj;
                    if (gainValues.containsKey("unified_gain")) {
                        hasUnifiedGain = true;
                    }
                    break; // Only need to check one angle
                }
            }

            // Determine target mode
            boolean targetExpIndividual = hasPerChannelExposures;
            boolean targetGainIndividual = !hasUnifiedGain;

            boolean modeChanged = (expIndividualRb.isSelected() != targetExpIndividual)
                    || (gainIndividualRb.isSelected() != targetGainIndividual);

            if (modeChanged) {
                logger.info("Setting camera mode from YAML profile: exposure_individual={}, gain_individual={}",
                        targetExpIndividual, targetGainIndividual);

                // Update radio buttons (setSelected does not fire onAction)
                expIndividualRb.setSelected(targetExpIndividual);
                expUnifiedRb.setSelected(!targetExpIndividual);
                gainIndividualRb.setSelected(targetGainIndividual);
                gainUnifiedRb.setSelected(!targetGainIndividual);

                // Send mode change to camera, wrapping with live mode handling
                // to avoid JAI error 11018 if live streaming is active
                controller.withLiveModeHandling(() ->
                        controller.setCameraMode(targetExpIndividual, targetGainIndividual));
            }

            // Always update field states to match the detected mode, even if the camera
            // was already in the correct mode. Fields start all-enabled by default, so
            // skipping this when modeChanged==false leaves fields in the wrong state.
            updateAllFieldStates(angleFieldsMap, targetExpIndividual, targetGainIndividual);

        } catch (Exception e) {
            logger.warn("Could not detect/set mode from YAML: {}", e.getMessage());
            // Non-fatal - still update field states based on current radio button selections
            updateAllFieldStates(angleFieldsMap, expIndividualRb.isSelected(), gainIndividualRb.isSelected());
        }
    }

    /**
     * Loads calibration values for a single angle from YAML configuration.
     */
    @SuppressWarnings("unchecked")
    private static boolean loadAngleFromYAML(MicroscopeConfigManager mgr, String objective, String detector,
                                             String angleName, AngleFields fields, Label statusLabel,
                                             ResourceBundle res) {
        try {
            // Get exposures for this modality/objective/detector/angle
            // Expected structure: imaging_profiles.ppm.{objective}.{detector}.exposures_ms.{angle}
            Map<String, Object> exposuresMap = mgr.getModalityExposures("ppm", objective, detector);

            if (exposuresMap == null) {
                logger.debug("No exposures found for ppm/{}/{}", objective, detector);
                return false;
            }

            // Get values for this specific angle
            Object angleExposures = exposuresMap.get(angleName);
            if (angleExposures instanceof Map<?, ?>) {
                Map<String, Object> expValues = (Map<String, Object>) angleExposures;

                // Load exposure values
                if (expValues.containsKey("all")) {
                    fields.expAll.setText(String.valueOf(expValues.get("all")));
                }
                if (expValues.containsKey("r")) {
                    fields.expR.setText(String.valueOf(expValues.get("r")));
                }
                if (expValues.containsKey("g")) {
                    fields.expG.setText(String.valueOf(expValues.get("g")));
                }
                if (expValues.containsKey("b")) {
                    fields.expB.setText(String.valueOf(expValues.get("b")));
                }

                logger.debug("Loaded exposures for {}: all={}, r={}, g={}, b={}",
                        angleName, expValues.get("all"), expValues.get("r"),
                        expValues.get("g"), expValues.get("b"));
            }

            // Get gains for this specific angle
            Object gainsObj = mgr.getProfileSetting("ppm", objective, detector, "gains", angleName);
            if (gainsObj instanceof Map<?, ?>) {
                Map<String, Object> gainValues = (Map<String, Object>) gainsObj;

                if (gainValues.containsKey("unified_gain")) {
                    fields.gainAll.setText(String.valueOf(gainValues.get("unified_gain")));
                }
                if (gainValues.containsKey("r")) {
                    fields.gainR.setText(String.valueOf(gainValues.get("r")));
                }
                if (gainValues.containsKey("g")) {
                    fields.gainG.setText(String.valueOf(gainValues.get("g")));
                }
                if (gainValues.containsKey("b")) {
                    fields.gainB.setText(String.valueOf(gainValues.get("b")));
                }

                logger.debug("Loaded gains for {}: unified={}, r={}, g={}, b={}",
                        angleName, gainValues.get("unified_gain"),
                        gainValues.get("r"), gainValues.get("g"), gainValues.get("b"));
            }

            if (statusLabel != null) {
                statusLabel.setText(res.getString("camera.status.reloaded"));
                statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: green;");
            }

            return true;

        } catch (Exception e) {
            logger.warn("Failed to load calibration for {}: {}", angleName, e.getMessage());
            if (statusLabel != null) {
                statusLabel.setText(String.format(res.getString("camera.error.loadFailed"), e.getMessage()));
                statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: red;");
            }
            return false;
        }
    }

    /**
     * Updates the WB method provenance label by reading wb_method from the first
     * available angle in the gains section of the imaging profile.
     */
    @SuppressWarnings("unchecked")
    private static void updateWbMethodLabel(MicroscopeConfigManager mgr, String objective, String detector,
                                             Label wbMethodLabel) {
        String wbMethod = null;
        Map<String, Double> angles = loadPpmAngles(mgr);
        for (String angleName : angles.keySet()) {
            Object gainsObj = mgr.getProfileSetting("ppm", objective, detector, "gains", angleName);
            if (gainsObj instanceof Map<?, ?>) {
                Map<String, Object> gainValues = (Map<String, Object>) gainsObj;
                Object method = gainValues.get("wb_method");
                if (method != null) {
                    wbMethod = method.toString();
                }
                break; // Only need to check one angle
            }
        }

        if (wbMethod == null || wbMethod.equals("unknown")) {
            wbMethodLabel.setText("WB: Method unknown");
            wbMethodLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #999999;");
        } else if (wbMethod.startsWith("manual_")) {
            String displayName = wbMethod.equals("manual_simple") ? "Manual simple" : "Manual PPM";
            wbMethodLabel.setText("WB: " + displayName + " calibration (reproducible)");
            wbMethodLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: green; -fx-font-weight: bold;");
        } else if (wbMethod.equals("continuous")) {
            wbMethodLabel.setText("WB: Continuous auto (not reproducible)");
            wbMethodLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: orange; -fx-font-weight: bold;");
        } else if (wbMethod.equals("once")) {
            wbMethodLabel.setText("WB: One-shot auto (not reproducible)");
            wbMethodLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: orange; -fx-font-weight: bold;");
        } else {
            wbMethodLabel.setText("WB: " + wbMethod);
            wbMethodLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #999999;");
        }
    }

    /**
     * Loads PPM rotation angles from the YAML configuration.
     * Falls back to defaults if the config is unavailable or has no angles.
     *
     * @param mgr MicroscopeConfigManager instance
     * @return ordered map of angle name to tick value in degrees
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Double> loadPpmAngles(MicroscopeConfigManager mgr) {
        try {
            List<Map<String, Object>> rotationAngles = mgr.getRotationAngles("ppm");
            if (rotationAngles != null && !rotationAngles.isEmpty()) {
                Map<String, Double> angles = new LinkedHashMap<>();
                for (Map<String, Object> angle : rotationAngles) {
                    String name = (String) angle.get("name");
                    Number tick = (Number) angle.get("tick");
                    if (name != null && tick != null) {
                        angles.put(name, tick.doubleValue());
                    }
                }
                if (!angles.isEmpty()) {
                    logger.info("Loaded {} PPM angles from config: {}", angles.size(), angles.keySet());
                    return angles;
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load PPM angles from config: {}", e.getMessage());
        }

        logger.warn("No rotation angles found in config, using defaults");
        return new LinkedHashMap<>(DEFAULT_PPM_ANGLES);
    }

    /**
     * Capitalize first letter of a string.
     */
    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    /**
     * Container for angle-specific text fields.
     */
    private static class AngleFields {
        final TextField expAll;
        final TextField expR;
        final TextField expG;
        final TextField expB;
        final TextField gainAll;
        final TextField gainR;
        final TextField gainG;
        final TextField gainB;

        AngleFields(TextField expAll, TextField expR, TextField expG, TextField expB,
                    TextField gainAll, TextField gainR, TextField gainG, TextField gainB) {
            this.expAll = expAll;
            this.expR = expR;
            this.expG = expG;
            this.expB = expB;
            this.gainAll = gainAll;
            this.gainR = gainR;
            this.gainG = gainG;
            this.gainB = gainB;
        }

        /**
         * Updates field enabled/disabled state based on exposure and gain modes.
         */
        void updateFieldStates(boolean expIndividual, boolean gainIndividual) {
            // Exposure fields
            expAll.setDisable(expIndividual);
            expR.setDisable(!expIndividual);
            expG.setDisable(!expIndividual);
            expB.setDisable(!expIndividual);

            // Visual styling for disabled state
            String disabledStyle = "-fx-opacity: 0.4;";
            String enabledStyle = "";
            expAll.setStyle(expIndividual ? disabledStyle : enabledStyle);
            expR.setStyle(expIndividual ? enabledStyle : disabledStyle);
            expG.setStyle(expIndividual ? enabledStyle : disabledStyle);
            expB.setStyle(expIndividual ? enabledStyle : disabledStyle);

            // Gain fields
            gainAll.setDisable(gainIndividual);
            gainR.setDisable(!gainIndividual);
            gainG.setDisable(!gainIndividual);
            gainB.setDisable(!gainIndividual);

            gainAll.setStyle(gainIndividual ? disabledStyle : enabledStyle);
            gainR.setStyle(gainIndividual ? enabledStyle : disabledStyle);
            gainG.setStyle(gainIndividual ? enabledStyle : disabledStyle);
            gainB.setStyle(gainIndividual ? enabledStyle : disabledStyle);
        }
    }

    /**
     * Reverts radio buttons and field states to match the actual camera mode.
     * Called when a setCameraMode call fails (e.g., due to live mode error)
     * to keep UI in sync with the real camera state.
     *
     * @param controller MicroscopeController to query actual camera mode
     * @param expIndividualRb Exposure individual radio button
     * @param expUnifiedRb Exposure unified radio button
     * @param gainIndividualRb Gain individual radio button
     * @param gainUnifiedRb Gain unified radio button
     * @param angleFieldsMap Map of angle name to UI fields
     */
    private static void revertRadioButtonsToCamera(MicroscopeController controller,
                                                    RadioButton expIndividualRb, RadioButton expUnifiedRb,
                                                    RadioButton gainIndividualRb, RadioButton gainUnifiedRb,
                                                    Map<String, AngleFields> angleFieldsMap) {
        try {
            MicroscopeSocketClient.CameraModeResult mode = controller.getCameraMode();
            expIndividualRb.setSelected(mode.exposureIndividual());
            expUnifiedRb.setSelected(!mode.exposureIndividual());
            gainIndividualRb.setSelected(mode.gainIndividual());
            gainUnifiedRb.setSelected(!mode.gainIndividual());
            updateAllFieldStates(angleFieldsMap, mode.exposureIndividual(), mode.gainIndividual());
            logger.info("Reverted radio buttons to camera state: exp_individual={}, gain_individual={}",
                    mode.exposureIndividual(), mode.gainIndividual());
        } catch (IOException ex) {
            logger.warn("Could not query camera mode for revert: {}", ex.getMessage());
        }
    }

    /**
     * Updates all angle fields based on current mode settings.
     */
    private static void updateAllFieldStates(Map<String, AngleFields> angleFieldsMap,
                                              boolean expIndividual, boolean gainIndividual) {
        for (AngleFields fields : angleFieldsMap.values()) {
            fields.updateFieldStates(expIndividual, gainIndividual);
        }
    }
}
