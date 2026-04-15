package qupath.ext.qpsc.ui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.BackgroundCollectionWorkflow.BackgroundCollectionResult;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.modality.WbMode;
import qupath.ext.qpsc.modality.ppm.RotationManager;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.BackgroundSettingsReader;
import qupath.ext.qpsc.utilities.BackgroundValidityChecker;
import qupath.ext.qpsc.utilities.DocumentationHelper;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.dialogs.Dialogs;

/**
 * BackgroundCollectionController - UI for background image acquisition
 *
 * <p>Provides a dialog for:
 * <ul>
 *   <li>Selecting modality</li>
 *   <li>Adjusting exposure times for each angle</li>
 *   <li>Choosing output folder</li>
 *   <li>Persisting exposure changes back to YAML config</li>
 * </ul>
 */
public class BackgroundCollectionController {

    private static final Logger logger = LoggerFactory.getLogger(BackgroundCollectionController.class);

    private ComboBox<String> modalityComboBox;
    private ComboBox<String> objectiveComboBox;
    private TextField outputPathField;
    private ComboBox<String> wbModeComboBox;
    private VBox exposureControlsPane;
    private List<AngleExposure> currentAngleExposures = new ArrayList<>();
    private List<TextField> exposureFields = new ArrayList<>();
    private List<TextField> angleFields = new ArrayList<>(); // Track angle fields for PPM
    private TextField targetIntensityField;
    private HBox targetIntensityRow;
    private HBox wbModeRow;
    private BackgroundSettingsReader.BackgroundSettings existingBackgroundSettings;
    private Label backgroundValidationLabel;
    private VBox wbValidityPanel;

    /**
     * Shows the background collection dialog and returns the result.
     *
     * @return CompletableFuture with BackgroundCollectionResult, or null if cancelled
     */
    public static CompletableFuture<BackgroundCollectionResult> showDialog() {
        var controller = new BackgroundCollectionController();
        return controller.showDialogInternal();
    }

    private CompletableFuture<BackgroundCollectionResult> showDialogInternal() {
        CompletableFuture<BackgroundCollectionResult> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                // Create dialog
                Dialog<BackgroundCollectionResult> dialog = new Dialog<>();
                dialog.setTitle("Background Collection");
                dialog.setHeaderText("Configure background image acquisition for flat field correction");
                Button adviceButton = new Button("Advice");
                adviceButton.setTooltip(new Tooltip("Best practices for background image collection"));
                adviceButton.setStyle("-fx-font-size: 10px;");
                adviceButton.setOnAction(ev -> CalibrationAdviceDialog.showBackgroundAdvice());
                Button helpButton = DocumentationHelper.createHelpButton("backgroundCollection");
                javafx.scene.layout.HBox graphicBox = new javafx.scene.layout.HBox(8, adviceButton);
                if (helpButton != null) graphicBox.getChildren().add(helpButton);
                dialog.setGraphic(graphicBox);

                // Reload config to pick up any recent WB calibration changes
                // (WB writes directly to the YAML; the in-memory cache may be stale)
                try {
                    String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                    if (configPath != null) {
                        MicroscopeConfigManager.getInstance(configPath).reload(configPath);
                        logger.info("Reloaded config before background collection dialog");
                    }
                } catch (Exception ex) {
                    logger.debug("Could not reload config: {}", ex.getMessage());
                }

                // Create UI - wrap content in ScrollPane to handle variable height
                VBox content = createDialogContent();
                ScrollPane scrollPane = new ScrollPane(content);
                scrollPane.setFitToWidth(true);
                scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
                scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

                dialog.getDialogPane().setContent(scrollPane);
                dialog.getDialogPane().setPrefWidth(620);
                dialog.getDialogPane().setPrefHeight(580);
                dialog.getDialogPane().setMinHeight(400);
                dialog.setResizable(true);

                // Add buttons
                ButtonType okButtonType = new ButtonType("Acquire Backgrounds", ButtonBar.ButtonData.OK_DONE);
                dialog.getDialogPane().getButtonTypes().addAll(okButtonType, ButtonType.CANCEL);

                // Disable OK button initially
                Button okButton = (Button) dialog.getDialogPane().lookupButton(okButtonType);
                okButton.setDisable(true);

                // Enable OK when valid modality and objective are selected
                modalityComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
                    boolean isValid = newVal != null
                            && objectiveComboBox.getValue() != null
                            && !outputPathField.getText().trim().isEmpty();
                    okButton.setDisable(!isValid);
                    if (newVal != null) {
                        updateObjectiveSelection(newVal);
                        // Only update exposure controls if both modality and objective are selected
                        if (objectiveComboBox.getValue() != null) {
                            updateExposureControlsWithBackground(newVal, objectiveComboBox.getValue());
                        }
                    } else {
                        // Clear objectives when modality is cleared
                        objectiveComboBox.getItems().clear();
                        objectiveComboBox.setDisable(true);
                        clearExposureControls();
                    }
                });

                // Add objective change listener
                objectiveComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
                    boolean isValid = modalityComboBox.getValue() != null
                            && newVal != null
                            && !outputPathField.getText().trim().isEmpty();
                    okButton.setDisable(!isValid);
                    // Update exposure controls when objective changes (if modality is also selected)
                    if (newVal != null && modalityComboBox.getValue() != null) {
                        updateExposureControlsWithBackground(modalityComboBox.getValue(), newVal);
                    } else {
                        clearExposureControls();
                    }
                });

                outputPathField.textProperty().addListener((obs, oldVal, newVal) -> {
                    boolean isValid = modalityComboBox.getValue() != null
                            && objectiveComboBox.getValue() != null
                            && !newVal.trim().isEmpty();
                    okButton.setDisable(!isValid);
                });

                // If modality was pre-selected from preferences, trigger objective
                // population now (listeners were not yet attached when setValue was called
                // inside createDialogContent, so we need to manually kick off the chain).
                if (modalityComboBox.getValue() != null) {
                    logger.info(
                            "Background dialog: modality pre-selected='{}', triggering objective population",
                            modalityComboBox.getValue());
                    updateObjectiveSelection(modalityComboBox.getValue());
                    // updateObjectiveSelection sets the objective value if a match is found,
                    // which triggers the objectiveComboBox listener (line ~121) that calls
                    // updateExposureControlsWithBackground. Only call it explicitly if the
                    // listener didn't fire (objective still null after population).
                    if (objectiveComboBox.getValue() != null) {
                        logger.info(
                                "Background dialog: objective pre-selected='{}' via preferences",
                                objectiveComboBox.getValue());
                        // Listener already called updateExposureControlsWithBackground;
                        // just enable the OK button if output path is set.
                        okButton.setDisable(outputPathField.getText().trim().isEmpty());
                    } else {
                        logger.warn(
                                "Background dialog: no objective was pre-selected "
                                        + "(lastObjective='{}' not found in available objectives)",
                                PersistentPreferences.getLastObjective());
                    }
                }

                // Add a status label at the bottom for acquisition progress
                Label acquiringLabel = new Label();
                acquiringLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #1565C0;");
                acquiringLabel.setVisible(false);
                acquiringLabel.setWrapText(true);
                // Add it below the scroll pane
                VBox dialogContent = new VBox(8, scrollPane, acquiringLabel);
                dialog.getDialogPane().setContent(dialogContent);

                // Intercept OK button to run acquisition while keeping dialog open
                okButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                    // Prevent dialog from closing
                    event.consume();

                    BackgroundCollectionResult result = createResult();
                    if (result == null) return;

                    // Remember the output path for next time
                    saveOutputPath(result.outputPath());

                    // Save exposure changes to YAML config
                    saveExposureChangesToConfig(result.modality(), result.angleExposures());

                    // Disable all controls during acquisition
                    okButton.setDisable(true);
                    Button cancelBtn = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
                    if (cancelBtn != null) cancelBtn.setDisable(true);
                    acquiringLabel.setText("Acquiring background images, please wait...");
                    acquiringLabel.setVisible(true);

                    // Run acquisition in background
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                                qupath.ext.qpsc.controller.BackgroundCollectionWorkflow
                                        .executeBackgroundAcquisitionDirect(
                                                result.modality(),
                                                result.objective(),
                                                result.angleExposures(),
                                                result.outputPath(),
                                                result.wbMode(),
                                                result.targetIntensity());
                            })
                            .thenRun(() -> {
                                Platform.runLater(() -> {
                                    acquiringLabel.setText("Background collection complete!");
                                    acquiringLabel.setStyle(
                                            "-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #2E7D32;");
                                    // Complete with null -- acquisition already ran above.
                                    // Completing with result would trigger run()'s thenAccept
                                    // and execute the acquisition a second time.
                                    future.complete(null);
                                    // Close dialog after a brief pause
                                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.5))
                                            .onFinishedProperty()
                                            .set(e2 -> dialog.close());
                                    javafx.animation.PauseTransition pause =
                                            new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.5));
                                    pause.setOnFinished(e2 -> dialog.close());
                                    pause.play();
                                });
                            })
                            .exceptionally(ex -> {
                                Platform.runLater(() -> {
                                    acquiringLabel.setText("Acquisition failed: " + ex.getMessage());
                                    acquiringLabel.setStyle(
                                            "-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #C62828;");
                                    okButton.setDisable(false);
                                    if (cancelBtn != null) cancelBtn.setDisable(false);
                                });
                                return null;
                            });
                });

                // Set result converter for cancel
                dialog.setResultConverter(dialogButton -> null);

                // Show dialog
                dialog.showAndWait().ifPresent(result -> {});
                // If future wasn't completed by the OK handler (user cancelled), complete with null
                if (!future.isDone()) {
                    future.complete(null);
                }

            } catch (Exception e) {
                logger.error("Error creating background collection dialog", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    private VBox createDialogContent() {
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));

        // Instructions
        Label instructionLabel =
                new Label("Position the microscope at a clean, blank area before starting acquisition.\n"
                        + "Background images will be saved with no processing applied.");
        instructionLabel.setWrapText(true);
        instructionLabel.setStyle("-fx-font-style: italic;");

        // Modality selection
        GridPane modalityPane = new GridPane();
        modalityPane.setHgap(10);
        modalityPane.setVgap(10);

        Label modalityLabel = new Label("Modality:");
        modalityComboBox = new ComboBox<>();
        // Get available modalities from configuration
        try {
            String configPath = qupath.ext.qpsc.preferences.QPPreferenceDialog.getMicroscopeConfigFileProperty();
            logger.info("Background dialog: configPath = '{}'", configPath);
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
            Set<String> availableModalities = configManager.getAvailableModalities();
            logger.info("Background dialog: found {} modalities: {}", availableModalities.size(), availableModalities);
            modalityComboBox.getItems().addAll(availableModalities);
        } catch (Exception e) {
            logger.error("Failed to load available modalities", e);
            // Fallback to common modality names
            modalityComboBox.getItems().addAll("ppm", "brightfield", "fluorescence");
        }
        modalityComboBox.setPromptText("Select modality...");

        // Pre-select last-used modality (e.g. from wizard)
        String lastModality = PersistentPreferences.getLastModality();
        logger.info(
                "Background dialog: lastModality from preferences = '{}', comboBox items = {}",
                lastModality,
                modalityComboBox.getItems());
        if (lastModality != null
                && !lastModality.isEmpty()
                && modalityComboBox.getItems().contains(lastModality)) {
            modalityComboBox.setValue(lastModality);
            logger.info("Background dialog: pre-selected modality '{}'", lastModality);
        } else {
            logger.warn("Background dialog: could not pre-select modality '{}' (not in items list)", lastModality);
        }

        modalityPane.add(modalityLabel, 0, 0);
        modalityPane.add(modalityComboBox, 1, 0);

        // Objective selection
        Label objectiveLabel = new Label("Objective:");
        objectiveComboBox = new ComboBox<>();
        objectiveComboBox.setPromptText("Select objective...");
        objectiveComboBox.setDisable(true); // Disabled until modality is selected

        modalityPane.add(objectiveLabel, 0, 1);
        modalityPane.add(objectiveComboBox, 1, 1);

        // Output path selection
        Label outputLabel = new Label("Output folder:");
        outputPathField = new TextField();
        outputPathField.setPromptText("Select folder for background images...");
        outputPathField.setPrefWidth(300);

        Button browseButton = new Button("Browse...");
        browseButton.setOnAction(e -> browseForOutputFolder());

        HBox outputPane = new HBox(10, outputPathField, browseButton);
        outputPane.setAlignment(Pos.CENTER_LEFT);

        modalityPane.add(outputLabel, 0, 2);
        modalityPane.add(outputPane, 1, 2);

        // Set default output path
        setDefaultOutputPath();

        // WB mode dropdown - replaces checkbox with full mode selection
        Label wbModeLabel = new Label("White Balance Mode:");
        wbModeComboBox = new ComboBox<>();
        wbModeComboBox.getItems().addAll("Off", "Camera AWB", "Simple (90deg)", "Per-angle (PPM)");
        String savedWBMode = PersistentPreferences.getLastWhiteBalanceMode();
        if (wbModeComboBox.getItems().contains(savedWBMode)) {
            wbModeComboBox.setValue(savedWBMode);
        } else {
            wbModeComboBox.setValue("Per-angle (PPM)");
        }
        WbMode.applyColorCellFactory(wbModeComboBox);
        wbModeComboBox.setTooltip(
                new Tooltip("White balance mode for background acquisition:\n" + "  Off - No white balance correction\n"
                        + "  Camera AWB - Set in MicroManager before acquisition; clear by restarting MM (wait 30s)\n"
                        + "  Simple (90deg) - Use 90deg R:G:B ratios, uniformly scaled per angle\n"
                        + "  Per-angle (PPM) - Independent calibration per angle (default)\n\n"
                        + "Backgrounds must be collected with the SAME mode used for acquisition."));

        wbModeRow = new HBox(10, wbModeLabel, wbModeComboBox);
        wbModeRow.setAlignment(Pos.CENTER_LEFT);

        // Listener to reload exposure values when WB mode changes and persist selection
        wbModeComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                PersistentPreferences.setLastWhiteBalanceMode(newVal);
            }
            String modality = modalityComboBox.getValue();
            String objective = objectiveComboBox.getValue();
            if (modality != null && objective != null) {
                logger.info("WB mode changed to: {} - reloading exposure values", newVal);
                updateExposureControlsWithBackground(modality, objective);
            }
        });

        // Target intensity field (for monochrome cameras only -- RGB cameras use WB calibration)
        Label targetLabel = new Label("Target Intensity:");
        targetLabel.setTooltip(new Tooltip(
                "Target median pixel value for adaptive background exposure.\n"
                + "For 16-bit cameras: typical range 30000-55000.\n"
                + "For 8-bit cameras: typical range 150-240.\n"
                + "Set to 0 to use server default."));
        targetIntensityField = new TextField();
        targetIntensityField.setPrefWidth(100);
        targetIntensityField.setPromptText("e.g., 51200");
        targetIntensityRow = new HBox(10, targetLabel, targetIntensityField);
        targetIntensityRow.setAlignment(Pos.CENTER_LEFT);
        // Pre-fill from detector config
        loadTargetIntensityFromConfig();
        // Initially hidden until we know the camera type
        updateTargetIntensityVisibility();
        // Hide WB mode selector and per-mode validity panel for monochrome cameras
        updateWbControlsVisibility();

        // Exposure controls (will be populated when modality AND objective are selected)
        Label exposureLabel = new Label("Exposure Times (ms):");
        exposureControlsPane = new VBox(10);

        // Background validation label
        backgroundValidationLabel = new Label();
        backgroundValidationLabel.setWrapText(true);
        backgroundValidationLabel.setVisible(false); // Hidden until needed

        // Per-WB-mode background validity panel
        wbValidityPanel = new VBox(2);
        wbValidityPanel.setPadding(new Insets(5, 0, 5, 0));

        content.getChildren()
                .addAll(
                        instructionLabel,
                        new Separator(),
                        modalityPane,
                        wbModeRow,
                        wbValidityPanel,
                        targetIntensityRow,
                        new Separator(),
                        exposureLabel,
                        backgroundValidationLabel,
                        exposureControlsPane);

        return content;
    }

    /**
     * Clear exposure controls when no valid modality/objective combination is selected
     */
    private void clearExposureControls() {
        logger.debug("Clearing exposure controls");
        exposureControlsPane.getChildren().clear();
        exposureFields.clear();
        angleFields.clear(); // Clear angle fields as well
        currentAngleExposures.clear();
        existingBackgroundSettings = null;
        backgroundValidationLabel.setVisible(false);
    }

    /**
     * Update exposure controls with background validation for the given modality and objective
     */
    private void updateExposureControlsWithBackground(String modality, String objective) {
        logger.info(
                "Updating exposure controls with background validation for modality: {}, objective: {}",
                modality,
                objective);

        clearExposureControls();

        // Get modality handler
        ModalityHandler handler = ModalityRegistry.getHandler(modality);
        if (handler == null) {
            logger.warn("No handler found for modality: {}", modality);
            return;
        }

        // Try to find existing background settings
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
            // Reload config to pick up any changes from white balance calibration
            configManager.reload(configPath);
            logger.debug("Reloaded config manager to pick up latest calibration values");
            String baseBackgroundFolder = configManager.getBackgroundCorrectionFolder(modality);

            if (baseBackgroundFolder != null) {
                // Get detector for this modality/objective combination
                Set<String> detectors = configManager.getAvailableDetectors();
                if (!detectors.isEmpty()) {
                    String detector = detectors.iterator().next();
                    // Look up backgrounds for the currently selected WB mode
                    String selectedWbMode =
                            WbMode.fromDisplayName(wbModeComboBox.getValue()).getProtocolName();
                    existingBackgroundSettings = BackgroundSettingsReader.findBackgroundSettings(
                            baseBackgroundFolder, modality, objective, detector, selectedWbMode);

                    if (existingBackgroundSettings != null) {
                        logger.info(
                                "Found existing background settings for wbMode '{}': {}",
                                selectedWbMode,
                                existingBackgroundSettings);
                    } else {
                        logger.debug(
                                "No existing background settings found for {}/{}/{} (wbMode={})",
                                modality,
                                objective,
                                detector,
                                selectedWbMode);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error searching for background settings", e);
        }

        // Update per-WB-mode validity panel
        updateWbValidityPanel(modality, objective);

        // Get default angles and exposures
        logger.debug("Requesting rotation angles for modality: {}", modality);

        // Get detector for hardware-specific lookup
        String detector = null;
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
            Set<String> detectors = configManager.getAvailableDetectors();
            if (!detectors.isEmpty()) {
                detector = detectors.iterator().next();
            }
        } catch (Exception e) {
            logger.debug("Could not determine detector for hardware-specific lookup", e);
        }

        final String finalDetector = detector;
        // Capture existingBackgroundSettings in final variable for use in callback
        final BackgroundSettingsReader.BackgroundSettings capturedSettings = existingBackgroundSettings;
        logger.info(
                "DEBUG: Before async call - existingBackgroundSettings = {}",
                capturedSettings != null ? "FOUND" : "NULL");

        getBackgroundCollectionDefaults(modality, objective, finalDetector)
                .thenAccept(defaultExposures -> {
                    Platform.runLater(() -> {
                        boolean perAngleWbEnabled =
                                WbMode.fromDisplayName(wbModeComboBox.getValue()) == WbMode.PER_ANGLE;
                        logger.info(
                                "DEBUG: Inside callback - capturedSettings = {}, perAngleWB = {}",
                                capturedSettings != null ? "FOUND" : "NULL",
                                perAngleWbEnabled);
                        logger.debug("Creating exposure controls for {} angles", defaultExposures.size());

                        // Always prefer calibrated/config exposures over stale background_settings.yml.
                        // After WB calibration, the config YAML has the latest per-channel exposures;
                        // old background_settings.yml values would be stale and cause exposure mismatches
                        // during acquisition validation.
                        List<AngleExposure> exposuresToUse = defaultExposures;
                        if (perAngleWbEnabled) {
                            logger.info(
                                    "Per-angle WB enabled - using calibrated exposures from imageprocessing config");
                            showBackgroundValidationMessage(
                                    "Per-angle white balance: Using calibrated exposure values from config.",
                                    "-fx-text-fill: blue; -fx-font-weight: bold;");
                        } else if (capturedSettings != null) {
                            // Existing backgrounds found but we still use config/calibrated values
                            // so that new collection uses latest WB calibration exposures
                            logger.info(
                                    "Existing backgrounds found at {} but using latest config/calibrated exposures",
                                    capturedSettings.settingsFilePath);
                            showBackgroundValidationMessage(
                                    "Using calibrated exposure values from config. "
                                            + "Previous background images exist and will be overwritten.",
                                    "-fx-text-fill: blue; -fx-font-weight: bold;");
                        } else {
                            logger.info("No existing background settings - using defaults from config/preferences");
                            showBackgroundValidationMessage(
                                    "No existing background images. Ready for new collection.",
                                    "-fx-text-fill: green; -fx-font-weight: bold;");
                        }

                        // Clear and update current values
                        currentAngleExposures.clear();
                        currentAngleExposures.addAll(exposuresToUse);

                        // Non-rotation modalities (brightfield, fluorescence):
                        // Show a simple exposure field with no angle controls.
                        // The server collects a single background at current position.
                        if (exposuresToUse.isEmpty()) {
                            double defaultExp = 50.0; // Starting point for adaptive exposure
                            try {
                                String cfgPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                                var cfgMgr = MicroscopeConfigManager.getInstance(cfgPath);
                                var exps = cfgMgr.getModalityExposures(modality, objective, finalDetector);
                                if (exps != null) {
                                    Object single = exps.get("single");
                                    if (single instanceof Number n) defaultExp = n.doubleValue();
                                }
                            } catch (Exception ex) {
                                logger.debug("Could not load default exposure from config: {}", ex.getMessage());
                            }

                            Label expLabel = new Label("Starting Exposure (ms):");
                            TextField expField = new TextField(String.format("%.1f", defaultExp));
                            expField.setPrefWidth(100);
                            exposureFields.add(expField);
                            HBox expRow = new HBox(10, expLabel, expField, new Label("ms"));
                            expRow.setAlignment(Pos.CENTER_LEFT);

                            Label note = new Label("Adaptive exposure will converge to the target intensity.");
                            note.setStyle("-fx-text-fill: gray; -fx-font-style: italic; -fx-font-size: 11px;");
                            note.setWrapText(true);

                            exposureControlsPane.getChildren().addAll(expRow, note);
                            logger.debug("Single-exposure control added for non-rotation modality");
                            return;
                        }

                        // Create exposure controls with angle editing for multi-angle modalities
                        boolean isMultiAngle = exposuresToUse.size() > 1;
                        GridPane exposureGrid = new GridPane();
                        exposureGrid.setHgap(10);
                        exposureGrid.setVgap(5);

                        // Add headers for multi-angle modalities
                        if (isMultiAngle) {
                            Label angleHeader = new Label("Angle (deg):");
                            angleHeader.setStyle("-fx-font-weight: bold;");
                            Label exposureHeader = new Label("Exposure:");
                            exposureHeader.setStyle("-fx-font-weight: bold;");

                            exposureGrid.add(angleHeader, 0, 0);
                            exposureGrid.add(exposureHeader, 1, 0);
                            exposureGrid.add(new Label(""), 2, 0); // placeholder for "ms" column
                            exposureGrid.add(new Label("Type:"), 3, 0);
                        }

                        for (int i = 0; i < exposuresToUse.size(); i++) {
                            AngleExposure ae = exposuresToUse.get(i);
                            final int index = i;
                            int gridRow = isMultiAngle ? i + 1 : i; // Offset for header row

                            if (isMultiAngle) {
                                // Multi-angle: Editable angle field + exposure field + angle type label
                                TextField angleField = new TextField(String.format("%.1f", ae.ticks()));
                                angleField.setPrefWidth(80);
                                TextField exposureField = new TextField(String.valueOf(ae.exposureMs()));
                                exposureField.setPrefWidth(100);
                                Label angleTypeLabel = new Label(getAngleTypeName(ae.ticks()));
                                angleTypeLabel.setStyle("-fx-text-fill: gray; -fx-font-style: italic;");

                                // Update current values when user changes angle
                                angleField.textProperty().addListener((obs, oldVal, newVal) -> {
                                    try {
                                        double newAngle = Double.parseDouble(newVal);
                                        if (index < currentAngleExposures.size()) {
                                            AngleExposure oldAe = currentAngleExposures.get(index);
                                            currentAngleExposures.set(
                                                    index, new AngleExposure(newAngle, oldAe.exposureMs()));

                                            // Update angle type label
                                            Platform.runLater(() -> angleTypeLabel.setText(getAngleTypeName(newAngle)));
                                        }
                                    } catch (IllegalArgumentException e) {
                                        // Invalid input (unparseable or out of range), ignore during typing
                                        // NumberFormatException is a subclass of IllegalArgumentException
                                    }
                                });

                                // Update current values when user changes exposure
                                exposureField.textProperty().addListener((obs, oldVal, newVal) -> {
                                    try {
                                        double newExposure = Double.parseDouble(newVal);
                                        if (index < currentAngleExposures.size()) {
                                            AngleExposure oldAe = currentAngleExposures.get(index);
                                            currentAngleExposures.set(
                                                    index, new AngleExposure(oldAe.ticks(), newExposure));
                                        }
                                    } catch (IllegalArgumentException e) {
                                        // Invalid input (unparseable or out of range), ignore during typing
                                        // NumberFormatException is a subclass of IllegalArgumentException
                                    }
                                });

                                exposureGrid.add(angleField, 0, gridRow);
                                exposureGrid.add(exposureField, 1, gridRow);
                                exposureGrid.add(new Label("ms"), 2, gridRow);
                                exposureGrid.add(angleTypeLabel, 3, gridRow);

                                exposureFields.add(exposureField);
                                angleFields.add(angleField);

                            } else {
                                // Single-angle: Fixed angle label + exposure field
                                Label angleLabel = new Label(String.format("%.1f deg:", ae.ticks()));
                                TextField exposureField = new TextField(String.valueOf(ae.exposureMs()));
                                exposureField.setPrefWidth(100);

                                // Update current values when user changes exposure
                                exposureField.textProperty().addListener((obs, oldVal, newVal) -> {
                                    try {
                                        double newExposure = Double.parseDouble(newVal);
                                        if (index < currentAngleExposures.size()) {
                                            AngleExposure oldAe = currentAngleExposures.get(index);
                                            currentAngleExposures.set(
                                                    index, new AngleExposure(oldAe.ticks(), newExposure));
                                        }
                                    } catch (IllegalArgumentException e) {
                                        // Invalid input (unparseable or out of range), ignore during typing
                                        // NumberFormatException is a subclass of IllegalArgumentException
                                    }
                                });

                                exposureGrid.add(angleLabel, 0, gridRow);
                                exposureGrid.add(exposureField, 1, gridRow);
                                exposureGrid.add(new Label("ms"), 2, gridRow);

                                exposureFields.add(exposureField);
                            }
                        }

                        // Add the grid to the exposure controls pane
                        exposureControlsPane.getChildren().add(exposureGrid);
                        logger.debug("Exposure controls added to dialog");
                    });
                })
                .exceptionally(ex -> {
                    logger.error("Failed to get rotation angles for modality: {}", modality, ex);
                    Platform.runLater(() -> {
                        Label errorLabel = new Label("Failed to load exposure settings for " + modality);
                        errorLabel.setStyle("-fx-text-fill: red;");
                        exposureControlsPane.getChildren().add(errorLabel);
                    });
                    return null;
                });
    }

    /**
     * Note: validateCurrentSettings() method removed as background collection shouldn't
     * validate against existing backgrounds since the purpose is to create new ones.
     */

    /**
     * Show or hide background validation message
     */
    private void showBackgroundValidationMessage(String message, String style) {
        backgroundValidationLabel.setText(message);
        backgroundValidationLabel.setStyle(style);
        backgroundValidationLabel.setVisible(true);
    }

    /**
     * Update the per-WB-mode background validity panel.
     * Shows which WB modes have valid backgrounds and which need attention.
     */
    private void updateWbValidityPanel(String modality, String objective) {
        wbValidityPanel.getChildren().clear();

        if (modality == null || objective == null) {
            return;
        }

        // Skip entirely for monochrome cameras -- WB modes don't apply.
        if (!isRgbCamera()) {
            wbValidityPanel.setVisible(false);
            wbValidityPanel.setManaged(false);
            return;
        }

        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
            // Reload to pick up wb_last_modified changes from recent WB calibration
            configManager.reload(configPath);
            String baseBackgroundFolder = configManager.getBackgroundCorrectionFolder(modality);

            if (baseBackgroundFolder == null) {
                return;
            }

            Set<String> detectors = configManager.getAvailableDetectors();
            if (detectors.isEmpty()) {
                return;
            }
            String detector = detectors.iterator().next();

            List<BackgroundValidityChecker.WbModeValidity> validities = BackgroundValidityChecker.checkAllModes(
                    baseBackgroundFolder, modality, objective, detector, configManager);

            Label header = new Label("Background Status Per WB Mode:");
            header.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
            wbValidityPanel.getChildren().add(header);

            for (var validity : validities) {
                String prefix;
                String statusColor;
                switch (validity.status()) {
                    case NOT_NEEDED, VALID -> {
                        prefix = "[OK]";
                        statusColor = "#2E7D32";
                    }
                    default -> {
                        prefix = "[!!]";
                        statusColor = "#E65100";
                    }
                }
                // Use the WB mode's own color for the mode name portion
                String modeColor = validity.mode().getColor();
                Label prefixLabel = new Label("  " + prefix + "  ");
                prefixLabel.setStyle("-fx-text-fill: " + statusColor + "; -fx-font-size: 11px;");
                Label modeLabel = new Label(validity.mode().getDisplayName());
                modeLabel.setStyle("-fx-text-fill: " + modeColor + "; -fx-font-size: 11px; -fx-font-weight: bold;");
                Label msgLabel = new Label(" -- " + validity.message());
                msgLabel.setStyle("-fx-text-fill: " + statusColor + "; -fx-font-size: 11px;");
                javafx.scene.layout.HBox lineBox = new javafx.scene.layout.HBox(0, prefixLabel, modeLabel, msgLabel);
                lineBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                wbValidityPanel.getChildren().add(lineBox);
            }
        } catch (Exception e) {
            logger.warn("Failed to check WB mode validity: {}", e.getMessage());
        }
    }

    private void updateObjectiveSelection(String modality) {
        logger.info("Updating objective selection for modality: {}", modality);

        objectiveComboBox.getItems().clear();

        try {
            String configPath = qupath.ext.qpsc.preferences.QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
            Set<String> availableObjectives = configManager.getAvailableObjectives();

            if (!availableObjectives.isEmpty()) {
                objectiveComboBox.getItems().addAll(availableObjectives);
                objectiveComboBox.setDisable(false);
                // Pre-select last-used objective (e.g. from wizard)
                String lastObjective = PersistentPreferences.getLastObjective();
                logger.info(
                        "Background dialog objectives: available={}, lastObjective='{}', match={}",
                        availableObjectives,
                        lastObjective,
                        availableObjectives.contains(lastObjective));
                if (lastObjective != null && !lastObjective.isEmpty() && availableObjectives.contains(lastObjective)) {
                    objectiveComboBox.setValue(lastObjective);
                }
                logger.info("Loaded {} objectives for modality {}", availableObjectives.size(), modality);
            } else {
                logger.warn("No objectives found for modality: {}", modality);
                objectiveComboBox.setDisable(true);
            }
        } catch (Exception e) {
            logger.error("Failed to load objectives for modality: {}", modality, e);
            objectiveComboBox.setDisable(true);
        }
    }

    private void browseForOutputFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Background Images Output Folder");

        // Set initial directory to current value if valid
        String currentPath = outputPathField.getText().trim();
        if (!currentPath.isEmpty()) {
            File currentDir = new File(currentPath);
            if (currentDir.exists()) {
                chooser.setInitialDirectory(currentDir);
            }
        }

        Stage stage = (Stage) outputPathField.getScene().getWindow();
        File selectedDir = chooser.showDialog(stage);

        if (selectedDir != null) {
            outputPathField.setText(selectedDir.getAbsolutePath());
        }
    }

    private void setDefaultOutputPath() {
        // Priority 1: Last-used path from preferences
        String lastUsed = PersistentPreferences.getLastBackgroundOutputPath();
        if (lastUsed != null && !lastUsed.isEmpty() && new java.io.File(lastUsed).exists()) {
            outputPathField.setText(lastUsed);
            return;
        }

        // Priority 2: Config-derived default
        try {
            String configPath = qupath.ext.qpsc.preferences.QPPreferenceDialog.getMicroscopeConfigFileProperty();
            var configManager = MicroscopeConfigManager.getInstance(configPath);

            Set<String> modalities = configManager.getAvailableModalities();
            String defaultPath = "C:/qpsc_data/background_tiles";

            if (!modalities.isEmpty()) {
                String firstModality = modalities.iterator().next();
                String bgFolder = configManager.getBackgroundCorrectionFolder(firstModality);
                if (bgFolder != null) {
                    defaultPath = bgFolder;
                }
            }

            outputPathField.setText(defaultPath);

        } catch (Exception e) {
            logger.warn("Could not determine default background path", e);
            outputPathField.setText("C:/qpsc_data/background_tiles");
        }
    }

    /** Load target intensity from detector config (background_target_intensity field). */
    @SuppressWarnings("unchecked")
    private void loadTargetIntensityFromConfig() {
        try {
            String configPath = qupath.ext.qpsc.preferences.QPPreferenceDialog.getMicroscopeConfigFileProperty();
            var configManager = MicroscopeConfigManager.getInstance(configPath);
            Set<String> detectors = configManager.getHardwareDetectors();
            if (detectors == null || detectors.isEmpty()) return;

            String detId = detectors.iterator().next();
            var merged = configManager.getMergedDetectorSection();
            if (merged instanceof java.util.Map<?, ?> detMap) {
                Object detCfg = detMap.get(detId);
                if (detCfg instanceof java.util.Map<?, ?> cfg) {
                    Object target = cfg.get("background_target_intensity");
                    if (target instanceof Number n && n.doubleValue() > 0) {
                        targetIntensityField.setText(String.valueOf(n.intValue()));
                        logger.debug("Loaded background_target_intensity={} for {}", n, detId);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not load background target intensity: {}", e.getMessage());
        }
    }

    /**
     * Returns true if the active microscope's detector is an RGB camera (JAI).
     * Monochrome cameras have no per-channel exposures, no AWB, and no per-angle
     * white balance concepts -- UI elements related to those should be hidden.
     */
    @SuppressWarnings("unchecked")
    private boolean isRgbCamera() {
        try {
            String configPath = qupath.ext.qpsc.preferences.QPPreferenceDialog.getMicroscopeConfigFileProperty();
            var configManager = MicroscopeConfigManager.getInstance(configPath);
            Set<String> detectors = configManager.getHardwareDetectors();
            if (detectors != null && !detectors.isEmpty()) {
                String detId = detectors.iterator().next();
                var merged = configManager.getMergedDetectorSection();
                if (merged instanceof java.util.Map<?, ?> detMap) {
                    Object detCfg = detMap.get(detId);
                    if (detCfg instanceof java.util.Map<?, ?> cfg) {
                        Object cameraTypeObj = cfg.get("camera_type");
                        String cameraType = cameraTypeObj != null ? cameraTypeObj.toString() : "generic";
                        return "jai".equalsIgnoreCase(cameraType);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not determine camera type: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Show target intensity field only for non-RGB cameras.
     * RGB cameras (JAI) use WB calibration to determine background exposure.
     */
    private void updateTargetIntensityVisibility() {
        boolean isRgb = isRgbCamera();
        // Hide for RGB cameras (WB calibration determines background exposure)
        targetIntensityRow.setVisible(!isRgb);
        targetIntensityRow.setManaged(!isRgb);
    }

    /**
     * Hide WB mode selector and per-mode validity panel for monochrome cameras.
     * Monochrome cameras have no color channels, so "Camera AWB", "Simple (90deg)",
     * and "Per-angle (PPM)" modes are all meaningless -- only "Off" applies. We force
     * the stored mode to "Off" and hide the controls entirely so the user isn't
     * presented with inapplicable color-coded options.
     */
    private void updateWbControlsVisibility() {
        boolean isRgb = isRgbCamera();
        if (wbModeRow != null) {
            wbModeRow.setVisible(isRgb);
            wbModeRow.setManaged(isRgb);
        }
        if (wbValidityPanel != null) {
            wbValidityPanel.setVisible(isRgb);
            wbValidityPanel.setManaged(isRgb);
        }
        // For monochrome cameras, force WB mode to "Off" so downstream code sees a
        // consistent value regardless of whatever was saved in preferences.
        if (!isRgb && wbModeComboBox != null && !"Off".equals(wbModeComboBox.getValue())) {
            wbModeComboBox.setValue("Off");
        }
    }

    /** Save the last-used output path so it persists across dialog invocations. */
    private void saveOutputPath(String path) {
        if (path != null && !path.isEmpty()) {
            PersistentPreferences.setLastBackgroundOutputPath(path);
            logger.info("Saved background output path to preferences: {}", path);
        }
    }

    private BackgroundCollectionResult createResult() {
        try {
            String modality = modalityComboBox.getValue();
            String objective = objectiveComboBox.getValue();
            String outputPath = outputPathField.getText().trim();

            if (modality == null || objective == null || outputPath.isEmpty()) {
                return null;
            }

            // Drift guard: if the global objective state has moved since this dialog
            // opened (e.g. Live Viewer "Refresh from MM", another dialog's submit),
            // surface it and keep the global in sync with what we're about to send.
            // Root cause of the 2026-04-15 WB/Background mislabel was exactly this
            // class of silent drift between dialog caches and global preferences.
            String globalObjective = PersistentPreferences.getLastObjective();
            if (globalObjective != null
                    && !globalObjective.isEmpty()
                    && !globalObjective.equals(objective)) {
                logger.warn(
                        "Background collection objective drift: dialog combo='{}' but "
                                + "PersistentPreferences.getLastObjective()='{}'. Using dialog value. "
                                + "If this was unintentional, click Cancel and re-open the dialog.",
                        objective,
                        globalObjective);
            }
            PersistentPreferences.setLastObjective(objective);

            // Validate exposure values and angles
            List<AngleExposure> finalExposures = new ArrayList<>();
            if (currentAngleExposures.isEmpty() && !exposureFields.isEmpty()) {
                // Non-rotation modality (BF, fluorescence): just the starting exposure, no angles
                try {
                    double exposure = Double.parseDouble(exposureFields.get(0).getText());
                    // Empty finalExposures list signals "no angles" to the workflow
                    // The exposure is passed via the exposures string to the server
                    finalExposures.add(new AngleExposure(0, exposure));
                } catch (NumberFormatException e) {
                    Dialogs.showErrorMessage(
                            "Invalid Exposure", "Please enter a valid numeric exposure value.");
                    return null;
                }
            } else {
                for (int i = 0; i < exposureFields.size(); i++) {
                    try {
                        double exposure = Double.parseDouble(exposureFields.get(i).getText());
                        double angle;

                        // For multi-angle modalities, read angle from editable field
                        if (!angleFields.isEmpty() && i < angleFields.size()) {
                            angle = Double.parseDouble(angleFields.get(i).getText());
                        } else {
                            angle = currentAngleExposures.get(i).ticks();
                        }

                        finalExposures.add(new AngleExposure(angle, exposure));
                    } catch (NumberFormatException e) {
                        Dialogs.showErrorMessage(
                                "Invalid Exposure", "Please enter valid numeric values for all exposure times.");
                        return null;
                    }
                }
            }

            // Convert ComboBox selection to protocol string
            String wbMode = WbMode.fromDisplayName(wbModeComboBox.getValue()).getProtocolName();
            boolean usePerAngleWB = "per_angle".equals(wbMode);

            logger.info("Background collection wbMode: {}", wbMode);

            // Parse target intensity (0 = use server default)
            double targetIntensity = 0;
            String targetStr = targetIntensityField.getText().trim();
            if (!targetStr.isEmpty()) {
                try {
                    targetIntensity = Double.parseDouble(targetStr);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid target intensity '{}', using server default", targetStr);
                }
            }

            return new BackgroundCollectionResult(
                    modality, objective, finalExposures, outputPath, usePerAngleWB, wbMode, targetIntensity);

        } catch (Exception e) {
            logger.error("Error creating result", e);
            Dialogs.showErrorMessage("Error", "Failed to create background collection parameters: " + e.getMessage());
            return null;
        }
    }

    /**
     * Gets default angle-exposure values for background collection, prioritizing preferences first.
     * This is different from acquisition workflows which prioritize background files first.
     *
     * @param modality the modality name (e.g., "ppm")
     * @param objective the objective ID
     * @param detector the detector ID
     * @return future with default angle-exposure pairs for background collection
     */
    private CompletableFuture<List<AngleExposure>> getBackgroundCollectionDefaults(
            String modality, String objective, String detector) {
        CompletableFuture<List<AngleExposure>> future = new CompletableFuture<>();

        try {
            ModalityHandler handler = ModalityRegistry.getHandler(modality);

            // For multi-angle modalities, load profile-specific defaults first
            handler.prepareForAcquisition(modality, objective, detector);

            if (handler.getDefaultAngleCount() > 1) {
                // Multi-angle modality: get all configured angles directly (no dialog popup)
                RotationManager rotationManager = new RotationManager(modality, objective, detector);
                rotationManager
                        .getDefaultAnglesWithExposure(modality)
                        .thenApply(angles -> {
                            // Override exposures with background collection defaults from config
                            List<AngleExposure> defaults = new ArrayList<>();
                            for (AngleExposure ae : angles) {
                                double exposure =
                                        getBackgroundExposureDefault(ae.ticks(), modality, objective, detector);
                                defaults.add(new AngleExposure(ae.ticks(), exposure));
                            }
                            return defaults;
                        })
                        .thenAccept(future::complete)
                        .exceptionally(ex -> {
                            future.completeExceptionally(ex);
                            return null;
                        });
            } else {
                // Single-angle modality (brightfield, fluorescence, etc.):
                // No rotation angles. Return empty list -- the dialog will show
                // a single exposure field without angle controls, and the server
                // handles the no-angles case by collecting one background image.
                future.complete(List.of());
            }
        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Gets default exposure time for background collection with config-file-first priority.
     * When per-angle white balance is enabled, looks for per-channel (r, g, b) exposures.
     * Priority order: 1. Config file (per-channel if WB enabled), 2. Preferences, 3. Fallback default
     */
    private double getBackgroundExposureDefault(double angle, String modality, String objective, String detector) {
        boolean perAngleWbEnabled = WbMode.fromDisplayName(wbModeComboBox.getValue()) == WbMode.PER_ANGLE;
        logger.debug(
                "Getting background collection exposure default for angle {} with modality={}, objective={}, detector={}, perAngleWB={}",
                angle,
                modality,
                objective,
                detector,
                perAngleWbEnabled);

        // Priority 1: Check config file first (most likely to have good starting values)
        try {
            String configFileLocation =
                    qupath.ext.qpsc.preferences.QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configFileLocation);
            Map<String, Object> exposures = configManager.getModalityExposures(modality, objective, detector);

            if (exposures != null) {
                // Try common angle names
                String[] angleNames = getAngleNames(angle);
                for (String angleName : angleNames) {
                    if (exposures.containsKey(angleName)) {
                        Object exposureValue = exposures.get(angleName);

                        // Handle per-channel exposures (Map with r, g, b keys)
                        if (exposureValue instanceof Map<?, ?> perChannelMap) {
                            // Per-channel exposure - extract green channel as reference value
                            // (green is typically the median and used for display purposes)
                            Object greenValue = perChannelMap.get("g");
                            if (greenValue instanceof Number) {
                                double configExposure = ((Number) greenValue).doubleValue();
                                logger.info(
                                        "Using per-channel config exposure (green) for angle {} (key={}): {}ms",
                                        angle,
                                        angleName,
                                        configExposure);
                                return configExposure;
                            }
                            // Fallback to 'all' key if present (single exposure mode)
                            Object allValue = perChannelMap.get("all");
                            if (allValue instanceof Number) {
                                double configExposure = ((Number) allValue).doubleValue();
                                logger.info(
                                        "Using config exposure ('all') for angle {} (key={}): {}ms",
                                        angle,
                                        angleName,
                                        configExposure);
                                return configExposure;
                            }
                        }

                        // Handle simple numeric exposure
                        if (exposureValue instanceof Number) {
                            double configExposure = ((Number) exposureValue).doubleValue();
                            logger.info(
                                    "Using config file exposure time for background collection angle {} (key={}): {}ms",
                                    angle,
                                    angleName,
                                    configExposure);
                            return configExposure;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to read config file exposure settings for background collection", e);
        }

        // Priority 2: Check persistent preferences as fallback (only if per-angle WB is NOT enabled)
        // When per-angle WB is enabled, we want to use calibrated values, not generic preferences
        ModalityHandler bgHandler = ModalityRegistry.getHandler(modality);
        if (bgHandler.getDefaultAngleCount() > 1 && !perAngleWbEnabled) {
            try {
                double preferencesValue = bgHandler.getDefaultExposureForAngle(angle);
                if (preferencesValue > 0) {
                    logger.info(
                            "Using persistent preferences exposure time for background collection angle {}: {}ms",
                            angle,
                            preferencesValue);
                    return preferencesValue;
                }
            } catch (Exception e) {
                logger.debug("Failed to read persistent preferences for angle {}", angle, e);
            }
        }

        // Priority 3: Fallback default
        double fallback = perAngleWbEnabled ? 50.0 : 1.0; // Higher default for per-angle WB mode
        logger.info(
                "Using fallback default exposure time for background collection angle {}: {}ms (perAngleWB={})",
                angle,
                fallback,
                perAngleWbEnabled);
        return fallback;
    }

    /**
     * Get common names for an angle that might be used in config files.
     */
    private String[] getAngleNames(double angle) {
        if (Math.abs(angle - 0.0) < 0.001) {
            return new String[] {"0", "zero", "0.0", "crossed"};
        } else if (angle > 0 && angle < 20) {
            return new String[] {"plus", "positive", String.valueOf(angle)};
        } else if (angle < 0 && angle > -20) {
            return new String[] {"minus", "negative", String.valueOf(angle)};
        } else if (angle >= 40 && angle <= 100) {
            return new String[] {"uncrossed", "90", "90.0", String.valueOf(angle)};
        }
        return new String[] {String.valueOf(angle)};
    }

    /**
     * Get a descriptive name for a PPM angle type.
     */
    private String getAngleTypeName(double angle) {
        if (Math.abs(angle - 0.0) < 0.001) {
            return "crossed";
        } else if (angle > 0 && angle < 45) {
            return "positive";
        } else if (angle < 0 && angle > -45) {
            return "negative";
        } else if (angle >= 45 && angle <= 135) {
            return "uncrossed";
        } else {
            return "custom";
        }
    }

    /**
     * Saves the modified exposure times back to the YAML configuration file.
     */
    private void saveExposureChangesToConfig(String modality, List<AngleExposure> angleExposures) {
        try {
            logger.info("Saving exposure changes to config for modality: {}", modality);

            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            var configManager = MicroscopeConfigManager.getInstance(configPath);

            // TODO: Implement exposure time persistence to YAML config
            // This will require updating the MicroscopeConfigManager to support writing values back
            logger.warn("Exposure time persistence not yet implemented - changes will be lost on restart");

        } catch (Exception e) {
            logger.error("Failed to save exposure changes to config", e);
            Platform.runLater(() -> Dialogs.showWarningNotification(
                    "Configuration Save Failed",
                    "Exposure time changes could not be saved to configuration file. "
                            + "Changes will be lost when QuPath restarts."));
        }
    }
}
