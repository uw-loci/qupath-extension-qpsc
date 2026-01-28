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

    /** PPM angle names and their rotation values in degrees */
    private static final Map<String, Double> PPM_ANGLES = new LinkedHashMap<>();
    static {
        PPM_ANGLES.put("uncrossed", 90.0);
        PPM_ANGLES.put("crossed", 0.0);
        PPM_ANGLES.put("positive", 7.0);
        PPM_ANGLES.put("negative", -7.0);
    }

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

        // Mode change listeners (JAI only)
        if (isJAI) {
            expIndividualRb.setOnAction(e -> {
                try {
                    boolean expInd = expIndividualRb.isSelected();
                    boolean gainInd = gainIndividualRb.isSelected();
                    controller.setCameraMode(expInd, gainInd);
                } catch (IOException ex) {
                    UIFunctions.notifyUserOfError("Failed to set exposure mode: " + ex.getMessage(), "Error");
                }
            });
            expUnifiedRb.setOnAction(e -> {
                try {
                    boolean expInd = expIndividualRb.isSelected();
                    boolean gainInd = gainIndividualRb.isSelected();
                    controller.setCameraMode(expInd, gainInd);
                } catch (IOException ex) {
                    UIFunctions.notifyUserOfError("Failed to set exposure mode: " + ex.getMessage(), "Error");
                }
            });
            gainIndividualRb.setOnAction(e -> {
                try {
                    boolean expInd = expIndividualRb.isSelected();
                    boolean gainInd = gainIndividualRb.isSelected();
                    controller.setCameraMode(expInd, gainInd);
                } catch (IOException ex) {
                    UIFunctions.notifyUserOfError("Failed to set gain mode: " + ex.getMessage(), "Error");
                }
            });
            gainUnifiedRb.setOnAction(e -> {
                try {
                    boolean expInd = expIndividualRb.isSelected();
                    boolean gainInd = gainIndividualRb.isSelected();
                    controller.setCameraMode(expInd, gainInd);
                } catch (IOException ex) {
                    UIFunctions.notifyUserOfError("Failed to set gain mode: " + ex.getMessage(), "Error");
                }
            });
        }

        content.getChildren().add(modeBox);

        // --- Per-Angle Settings Section (Flat Layout) ---
        Label settingsHeader = new Label(res.getString("camera.label.perAngleSettings"));
        settingsHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        Label settingsNote = new Label(res.getString("camera.label.settingsNote"));
        settingsNote.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");
        settingsNote.setWrapText(true);

        content.getChildren().addAll(new Separator(), settingsHeader, settingsNote);

        // Store field references for reload functionality
        Map<String, AngleFields> angleFieldsMap = new HashMap<>();

        // Global status label
        Label statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 11px;");

        // Create a flat grid with all angles visible
        GridPane anglesGrid = new GridPane();
        anglesGrid.setHgap(8);
        anglesGrid.setVgap(8);
        anglesGrid.setPadding(new Insets(10, 0, 10, 0));

        // Column constraints for better layout
        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setPrefWidth(80);
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setPrefWidth(55);
        ColumnConstraints buttonCol = new ColumnConstraints();
        buttonCol.setPrefWidth(100);

        anglesGrid.getColumnConstraints().addAll(
                labelCol,  // Angle name
                new ColumnConstraints(), fieldCol,  // All
                new ColumnConstraints(), fieldCol,  // R
                new ColumnConstraints(), fieldCol,  // G
                new ColumnConstraints(), fieldCol,  // B
                buttonCol  // Apply button
        );

        // Header row
        int row = 0;
        Label expHeader = new Label(res.getString("camera.label.exposureMs"));
        expHeader.setStyle("-fx-font-weight: bold;");
        anglesGrid.add(expHeader, 0, row);
        anglesGrid.add(new Label(res.getString("camera.label.all")), 1, row);
        anglesGrid.add(new Label(res.getString("camera.label.r")), 3, row);
        anglesGrid.add(new Label(res.getString("camera.label.g")), 5, row);
        anglesGrid.add(new Label(res.getString("camera.label.b")), 7, row);
        row++;

        // Create rows for each angle
        for (Map.Entry<String, Double> angleEntry : PPM_ANGLES.entrySet()) {
            String angleName = angleEntry.getKey();
            double angleDegrees = angleEntry.getValue();

            // Angle label
            Label angleLabel = new Label(String.format("%s (%.0f deg)", capitalize(angleName), angleDegrees));
            angleLabel.setStyle("-fx-font-weight: bold;");
            anglesGrid.add(angleLabel, 0, row);

            // Exposure fields
            TextField expAllField = createSmallField("0");
            TextField expRField = createSmallField("0");
            TextField expGField = createSmallField("0");
            TextField expBField = createSmallField("0");

            anglesGrid.add(expAllField, 2, row);
            anglesGrid.add(expRField, 4, row);
            anglesGrid.add(expGField, 6, row);
            anglesGrid.add(expBField, 8, row);

            // Apply button for this angle
            Button applyButton = new Button(res.getString("camera.button.apply"));
            applyButton.setOnAction(e -> applySettingsWithLiveModeHandling(
                    controller, angleName, angleDegrees,
                    expRField, expGField, expBField,
                    angleFieldsMap.get(angleName),
                    statusLabel, res));
            anglesGrid.add(applyButton, 9, row);

            row++;

            // Gain row for this angle
            Label gainLabel = new Label(res.getString("camera.label.gain"));
            gainLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");
            anglesGrid.add(gainLabel, 0, row);

            // Gain fields (no "all" field for gain)
            TextField gainRField = createSmallField("1.0");
            TextField gainGField = createSmallField("1.0");
            TextField gainBField = createSmallField("1.0");

            anglesGrid.add(gainRField, 4, row);
            anglesGrid.add(gainGField, 6, row);
            anglesGrid.add(gainBField, 8, row);

            // Store fields for this angle
            AngleFields fields = new AngleFields(expAllField, expRField, expGField, expBField,
                    gainRField, gainGField, gainBField);
            angleFieldsMap.put(angleName, fields);

            row++;

            // Add a small separator between angles
            Separator sep = new Separator();
            sep.setPadding(new Insets(3, 0, 3, 0));
            anglesGrid.add(sep, 0, row, 10, 1);
            row++;
        }

        content.getChildren().add(anglesGrid);

        // Status label
        content.getChildren().add(statusLabel);

        // Reload all button action
        reloadAllButton.setOnAction(e -> {
            String objective = objectiveCombo.getValue();
            String detector = detectorCombo.getValue();
            if (objective != null && detector != null) {
                reloadAllAnglesFromYAML(mgr, objective, detector, angleFieldsMap, statusLabel, res);
            }
        });

        // Objective/Detector change listeners - reload values
        objectiveCombo.setOnAction(e -> {
            String objective = objectiveCombo.getValue();
            String detector = detectorCombo.getValue();
            if (objective != null && detector != null) {
                reloadAllAnglesFromYAML(mgr, objective, detector, angleFieldsMap, statusLabel, res);
            }
        });
        detectorCombo.setOnAction(e -> {
            String objective = objectiveCombo.getValue();
            String detector = detectorCombo.getValue();
            if (objective != null && detector != null) {
                reloadAllAnglesFromYAML(mgr, objective, detector, angleFieldsMap, statusLabel, res);
            }
        });

        // Initial load of values
        String objective = objectiveCombo.getValue();
        String detector = detectorCombo.getValue();
        if (objective != null && detector != null) {
            reloadAllAnglesFromYAML(mgr, objective, detector, angleFieldsMap, statusLabel, res);
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
            // Parse values
            float[] exposures = new float[]{
                    Float.parseFloat(expRField.getText()),
                    Float.parseFloat(expGField.getText()),
                    Float.parseFloat(expBField.getText())
            };
            float[] gains = new float[]{
                    Float.parseFloat(fields.gainR.getText()),
                    Float.parseFloat(fields.gainG.getText()),
                    Float.parseFloat(fields.gainB.getText())
            };

            // Check if live mode is running
            boolean wasLive = false;
            try {
                wasLive = controller.isLiveModeRunning();
                if (wasLive) {
                    logger.info("Live mode is running - turning off for settings change");
                    controller.setLiveMode(false);
                    // Small delay to let the camera settle
                    Thread.sleep(100);
                }
            } catch (IOException e) {
                logger.warn("Could not check/set live mode: {} - proceeding anyway", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Apply the settings
            controller.applyCameraSettingsForAngle(angleName, exposures, gains, angleDegrees);

            // Turn live mode back on if it was running
            if (wasLive) {
                try {
                    // Small delay to let settings take effect
                    Thread.sleep(100);
                    controller.setLiveMode(true);
                    logger.info("Live mode restored after settings change");
                } catch (IOException e) {
                    logger.warn("Could not restore live mode: {}", e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            statusLabel.setText(String.format(res.getString("camera.status.applied"), angleName, angleDegrees));
            statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: green;");
            logger.info("Applied settings for angle {} at {} degrees", angleName, angleDegrees);

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
     */
    private static void reloadAllAnglesFromYAML(MicroscopeConfigManager mgr, String objective, String detector,
                                                Map<String, AngleFields> angleFieldsMap, Label statusLabel,
                                                ResourceBundle res) {
        logger.info("Reloading all angles from YAML for objective={}, detector={}", objective, detector);

        boolean anyLoaded = false;
        for (String angleName : PPM_ANGLES.keySet()) {
            AngleFields fields = angleFieldsMap.get(angleName);
            if (fields != null) {
                boolean loaded = loadAngleFromYAML(mgr, objective, detector, angleName, fields, null, res);
                if (loaded) anyLoaded = true;
            }
        }

        if (anyLoaded) {
            statusLabel.setText(res.getString("camera.status.reloaded"));
            statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: green;");
        } else {
            statusLabel.setText(String.format(res.getString("camera.error.noCalibration"), objective, detector));
            statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: orange;");
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

                if (gainValues.containsKey("r")) {
                    fields.gainR.setText(String.valueOf(gainValues.get("r")));
                }
                if (gainValues.containsKey("g")) {
                    fields.gainG.setText(String.valueOf(gainValues.get("g")));
                }
                if (gainValues.containsKey("b")) {
                    fields.gainB.setText(String.valueOf(gainValues.get("b")));
                }

                logger.debug("Loaded gains for {}: r={}, g={}, b={}",
                        angleName, gainValues.get("r"), gainValues.get("g"), gainValues.get("b"));
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
        final TextField gainR;
        final TextField gainG;
        final TextField gainB;

        AngleFields(TextField expAll, TextField expR, TextField expG, TextField expB,
                    TextField gainR, TextField gainG, TextField gainB) {
            this.expAll = expAll;
            this.expR = expR;
            this.expG = expG;
            this.expB = expB;
            this.gainR = gainR;
            this.gainG = gainG;
            this.gainB = gainB;
        }
    }
}
