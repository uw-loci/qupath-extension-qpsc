package qupath.ext.qpsc.ui;

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
import qupath.ext.qpsc.modality.ppm.PPMPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.BackgroundSettingsReader;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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
    private BackgroundSettingsReader.BackgroundSettings existingBackgroundSettings;
    private Label backgroundValidationLabel;
    
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
                    boolean isValid = newVal != null && objectiveComboBox.getValue() != null && !outputPathField.getText().trim().isEmpty();
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
                    boolean isValid = modalityComboBox.getValue() != null && newVal != null && !outputPathField.getText().trim().isEmpty();
                    okButton.setDisable(!isValid);
                    // Update exposure controls when objective changes (if modality is also selected)
                    if (newVal != null && modalityComboBox.getValue() != null) {
                        updateExposureControlsWithBackground(modalityComboBox.getValue(), newVal);
                    } else {
                        clearExposureControls();
                    }
                });
                
                outputPathField.textProperty().addListener((obs, oldVal, newVal) -> {
                    boolean isValid = modalityComboBox.getValue() != null && objectiveComboBox.getValue() != null && !newVal.trim().isEmpty();
                    okButton.setDisable(!isValid);
                });
                
                // Set result converter
                dialog.setResultConverter(dialogButton -> {
                    if (dialogButton == okButtonType) {
                        return createResult();
                    }
                    return null;
                });
                
                // Show dialog
                dialog.showAndWait().ifPresentOrElse(
                    result -> {
                        if (result != null) {
                            // Save exposure changes to YAML config
                            saveExposureChangesToConfig(result.modality(), result.angleExposures());
                        }
                        future.complete(result);
                    },
                    () -> future.complete(null)
                );
                
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
        Label instructionLabel = new Label(
                "Position the microscope at a clean, blank area before starting acquisition.\n" +
                "Background images will be saved with no processing applied."
        );
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
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
            Set<String> availableModalities = configManager.getAvailableModalities();
            modalityComboBox.getItems().addAll(availableModalities);
        } catch (Exception e) {
            logger.error("Failed to load available modalities", e);
            // Fallback to common modality names
            modalityComboBox.getItems().addAll("ppm", "brightfield", "fluorescence");
        }
        modalityComboBox.setPromptText("Select modality...");
        
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
        wbModeComboBox.setValue("Per-angle (PPM)");  // Default for PPM
        wbModeComboBox.setTooltip(new Tooltip(
                "White balance mode for background acquisition:\n" +
                "  Off - No white balance correction\n" +
                "  Camera AWB - Camera auto white balance at 90deg, then off\n" +
                "  Simple (90deg) - Use 90deg R:G:B ratios, uniformly scaled per angle\n" +
                "  Per-angle (PPM) - Independent calibration per angle (default)\n\n" +
                "Backgrounds must be collected with the SAME mode used for acquisition."
        ));

        HBox wbModeRow = new HBox(10, wbModeLabel, wbModeComboBox);
        wbModeRow.setAlignment(Pos.CENTER_LEFT);

        // Listener to reload exposure values when WB mode changes
        wbModeComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            String modality = modalityComboBox.getValue();
            String objective = objectiveComboBox.getValue();
            if (modality != null && objective != null) {
                logger.info("WB mode changed to: {} - reloading exposure values", newVal);
                updateExposureControlsWithBackground(modality, objective);
            }
        });

        // Exposure controls (will be populated when modality AND objective are selected)
        Label exposureLabel = new Label("Exposure Times (ms):");
        exposureControlsPane = new VBox(10);
        
        // Background validation label
        backgroundValidationLabel = new Label();
        backgroundValidationLabel.setWrapText(true);
        backgroundValidationLabel.setVisible(false); // Hidden until needed
        
        content.getChildren().addAll(
                instructionLabel,
                new Separator(),
                modalityPane,
                wbModeRow,
                new Separator(),
                exposureLabel,
                backgroundValidationLabel,
                exposureControlsPane
        );
        
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
        logger.info("Updating exposure controls with background validation for modality: {}, objective: {}", modality, objective);
        
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
                Set<String> detectors = configManager.getAvailableDetectorsForModalityObjective(modality, objective);
                if (!detectors.isEmpty()) {
                    String detector = detectors.iterator().next();
                    // Look up backgrounds for the currently selected WB mode
                    String selectedWbMode = convertWbModeToProtocol(wbModeComboBox.getValue());
                    existingBackgroundSettings = BackgroundSettingsReader.findBackgroundSettings(
                            baseBackgroundFolder, modality, objective, detector, selectedWbMode);

                    if (existingBackgroundSettings != null) {
                        logger.info("Found existing background settings for wbMode '{}': {}",
                                selectedWbMode, existingBackgroundSettings);
                    } else {
                        logger.debug("No existing background settings found for {}/{}/{} (wbMode={})",
                                modality, objective, detector, selectedWbMode);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error searching for background settings", e);
        }
        
        // Get default angles and exposures
        logger.debug("Requesting rotation angles for modality: {}", modality);
        
        // Get detector for hardware-specific lookup
        String detector = null;
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
            Set<String> detectors = configManager.getAvailableDetectorsForModalityObjective(modality, objective);
            if (!detectors.isEmpty()) {
                detector = detectors.iterator().next();
            }
        } catch (Exception e) {
            logger.debug("Could not determine detector for hardware-specific lookup", e);
        }
        
        final String finalDetector = detector;
        // Capture existingBackgroundSettings in final variable for use in callback
        final BackgroundSettingsReader.BackgroundSettings capturedSettings = existingBackgroundSettings;
        logger.info("DEBUG: Before async call - existingBackgroundSettings = {}",
                capturedSettings != null ? "FOUND" : "NULL");

        getBackgroundCollectionDefaults(modality, objective, finalDetector).thenAccept(defaultExposures -> {
            Platform.runLater(() -> {
                boolean perAngleWbEnabled = isPerAngleWbMode();
                logger.info("DEBUG: Inside callback - capturedSettings = {}, perAngleWB = {}",
                        capturedSettings != null ? "FOUND" : "NULL", perAngleWbEnabled);
                logger.debug("Creating exposure controls for {} angles", defaultExposures.size());

                // When per-angle white balance is enabled, always use calibrated values from config
                // (the defaultExposures now contain per-channel calibrated values from imageprocessing YAML)
                // When per-angle WB is disabled, prefer previous background collection values if available
                List<AngleExposure> exposuresToUse;
                if (perAngleWbEnabled) {
                    // Per-angle WB mode: use calibrated per-channel exposures from imageprocessing YAML
                    exposuresToUse = defaultExposures;
                    logger.info("Per-angle WB enabled - using calibrated exposures from imageprocessing config");
                    showBackgroundValidationMessage("Per-angle white balance: Using calibrated exposure values from config.",
                            "-fx-text-fill: blue; -fx-font-weight: bold;");
                } else if (capturedSettings != null) {
                    // Standard mode with existing backgrounds: use previous collection values
                    exposuresToUse = capturedSettings.angleExposures;
                    logger.info("Loading exposure values from previous background collection: {}",
                            capturedSettings.settingsFilePath);
                    showBackgroundValidationMessage("Existing background images found. Values loaded from previous collection.",
                            "-fx-text-fill: orange; -fx-font-weight: bold;");
                } else {
                    // Standard mode, no previous backgrounds: use defaults from config/preferences
                    exposuresToUse = defaultExposures;
                    logger.info("No existing background settings - using defaults from config/preferences");
                    showBackgroundValidationMessage("No existing background images. Ready for new collection.",
                            "-fx-text-fill: green; -fx-font-weight: bold;");
                }
                
                // Clear and update current values
                currentAngleExposures.clear();
                currentAngleExposures.addAll(exposuresToUse);
                
                // Create exposure controls with angle editing for PPM
                GridPane exposureGrid = new GridPane();
                exposureGrid.setHgap(10);
                exposureGrid.setVgap(5);

                // Add headers for PPM modality
                if ("ppm".equals(modality)) {
                    Label angleHeader = new Label("Angle (°):");
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
                    int gridRow = "ppm".equals(modality) ? i + 1 : i; // Offset for header row in PPM

                    if ("ppm".equals(modality)) {
                        // For PPM: Editable angle field + exposure field + angle type label
                        TextField angleField = new TextField(String.format("%.1f", ae.ticks()));
                        angleField.setPrefWidth(80);
                        TextField exposureField = new TextField(String.valueOf(ae.exposureMs()));
                        exposureField.setPrefWidth(100);
                        Label angleTypeLabel = new Label(getPPMAngleTypeName(ae.ticks()));
                        angleTypeLabel.setStyle("-fx-text-fill: gray; -fx-font-style: italic;");

                        // Update current values when user changes angle
                        angleField.textProperty().addListener((obs, oldVal, newVal) -> {
                            try {
                                double newAngle = Double.parseDouble(newVal);
                                if (index < currentAngleExposures.size()) {
                                    AngleExposure oldAe = currentAngleExposures.get(index);
                                    currentAngleExposures.set(index, new AngleExposure(newAngle, oldAe.exposureMs()));

                                    // Update angle type label
                                    Platform.runLater(() -> angleTypeLabel.setText(getPPMAngleTypeName(newAngle)));
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
                                    currentAngleExposures.set(index, new AngleExposure(oldAe.ticks(), newExposure));

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
                        // For other modalities: Fixed angle label + exposure field (original behavior)
                        Label angleLabel = new Label(String.format("%.1f°:", ae.ticks()));
                        TextField exposureField = new TextField(String.valueOf(ae.exposureMs()));
                        exposureField.setPrefWidth(100);

                        // Update current values when user changes exposure
                        exposureField.textProperty().addListener((obs, oldVal, newVal) -> {
                            try {
                                double newExposure = Double.parseDouble(newVal);
                                if (index < currentAngleExposures.size()) {
                                    AngleExposure oldAe = currentAngleExposures.get(index);
                                    currentAngleExposures.set(index, new AngleExposure(oldAe.ticks(), newExposure));

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
        }).exceptionally(ex -> {
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
    
    private void updateObjectiveSelection(String modality) {
        logger.info("Updating objective selection for modality: {}", modality);
        
        objectiveComboBox.getItems().clear();
        
        try {
            String configPath = qupath.ext.qpsc.preferences.QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
            Set<String> availableObjectives = configManager.getAvailableObjectivesForModality(modality);
            
            if (!availableObjectives.isEmpty()) {
                objectiveComboBox.getItems().addAll(availableObjectives);
                objectiveComboBox.setDisable(false);
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
        // Try to get default background folder from config or preferences
        try {
            String configPath = qupath.ext.qpsc.preferences.QPPreferenceDialog.getMicroscopeConfigFileProperty();
            var configManager = MicroscopeConfigManager.getInstance(configPath);
            
            // Look for default background folder in modality configuration
            // Use the first available modality to get a default base folder
            Set<String> modalities = configManager.getAvailableModalities();
            String defaultPath = "C:/qpsc_data/background_tiles"; // fallback
            
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

    private BackgroundCollectionResult createResult() {
        try {
            String modality = modalityComboBox.getValue();
            String objective = objectiveComboBox.getValue();
            String outputPath = outputPathField.getText().trim();

            if (modality == null || objective == null || outputPath.isEmpty()) {
                return null;
            }

            // Validate exposure values and angles
            List<AngleExposure> finalExposures = new ArrayList<>();
            for (int i = 0; i < exposureFields.size(); i++) {
                try {
                    double exposure = Double.parseDouble(exposureFields.get(i).getText());
                    double angle;

                    // For PPM, read angle from angle field; for others, use current stored angle
                    if ("ppm".equals(modality) && i < angleFields.size()) {
                        angle = Double.parseDouble(angleFields.get(i).getText());
                    } else {
                        angle = currentAngleExposures.get(i).ticks();
                    }

                    finalExposures.add(new AngleExposure(angle, exposure));
                } catch (NumberFormatException e) {
                    Dialogs.showErrorMessage("Invalid Exposure",
                            "Please enter valid numeric values for all exposure times.");
                    return null;
                }
            }

            // Convert ComboBox selection to protocol string
            String wbMode = convertWbModeToProtocol(wbModeComboBox.getValue());
            boolean usePerAngleWB = "per_angle".equals(wbMode);

            logger.info("Background collection wbMode: {}", wbMode);

            return new BackgroundCollectionResult(modality, objective, finalExposures, outputPath, usePerAngleWB, wbMode);

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
    private CompletableFuture<List<AngleExposure>> getBackgroundCollectionDefaults(String modality, String objective, String detector) {
        CompletableFuture<List<AngleExposure>> future = new CompletableFuture<>();

        try {
            if ("ppm".equals(modality)) {
                // For PPM, get default angles and pair with preference-prioritized exposures
                List<AngleExposure> defaults = new ArrayList<>();

                // Standard PPM angles - these should be configurable in the dialog
                double[] angles = {-7.0, 0.0, 7.0, 90.0}; // minus, zero, plus, uncrossed

                for (double angle : angles) {
                    double exposure = getBackgroundExposureDefault(angle, modality, objective, detector);
                    defaults.add(new AngleExposure(angle, exposure));
                }

                future.complete(defaults);
            } else {
                // For other modalities, fall back to normal handler method
                ModalityHandler handler = ModalityRegistry.getHandler(modality);
                if (handler != null) {
                    handler.getRotationAngles(modality, objective, detector)
                        .thenAccept(future::complete)
                        .exceptionally(ex -> {
                            future.completeExceptionally(ex);
                            return null;
                        });
                } else {
                    future.completeExceptionally(new RuntimeException("No handler found for modality: " + modality));
                }
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
        boolean perAngleWbEnabled = isPerAngleWbMode();
        logger.debug("Getting background collection exposure default for angle {} with modality={}, objective={}, detector={}, perAngleWB={}",
                angle, modality, objective, detector, perAngleWbEnabled);

        // Priority 1: Check config file first (most likely to have good starting values)
        try {
            String configFileLocation = qupath.ext.qpsc.preferences.QPPreferenceDialog.getMicroscopeConfigFileProperty();
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
                                logger.info("Using per-channel config exposure (green) for angle {} (key={}): {}ms",
                                        angle, angleName, configExposure);
                                return configExposure;
                            }
                            // Fallback to 'all' key if present (single exposure mode)
                            Object allValue = perChannelMap.get("all");
                            if (allValue instanceof Number) {
                                double configExposure = ((Number) allValue).doubleValue();
                                logger.info("Using config exposure ('all') for angle {} (key={}): {}ms",
                                        angle, angleName, configExposure);
                                return configExposure;
                            }
                        }

                        // Handle simple numeric exposure
                        if (exposureValue instanceof Number) {
                            double configExposure = ((Number) exposureValue).doubleValue();
                            logger.info("Using config file exposure time for background collection angle {} (key={}): {}ms",
                                    angle, angleName, configExposure);
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
        if ("ppm".equals(modality) && !perAngleWbEnabled) {
            try {
                double preferencesValue = getPersistentPreferenceExposure(angle);
                if (preferencesValue > 0) {
                    logger.info("Using persistent preferences exposure time for background collection angle {}: {}ms", angle, preferencesValue);
                    return preferencesValue;
                }
            } catch (Exception e) {
                logger.debug("Failed to read persistent preferences for angle {}", angle, e);
            }
        }

        // Priority 3: Fallback default
        double fallback = perAngleWbEnabled ? 50.0 : 1.0; // Higher default for per-angle WB mode
        logger.info("Using fallback default exposure time for background collection angle {}: {}ms (perAngleWB={})",
                angle, fallback, perAngleWbEnabled);
        return fallback;
    }

    /**
     * Get common names for an angle that might be used in config files.
     */
    private String[] getAngleNames(double angle) {
        if (Math.abs(angle - 0.0) < 0.001) {
            return new String[]{"0", "zero", "0.0", "crossed"};
        } else if (angle > 0 && angle < 20) {
            return new String[]{"plus", "positive", String.valueOf(angle)};
        } else if (angle < 0 && angle > -20) {
            return new String[]{"minus", "negative", String.valueOf(angle)};
        } else if (angle >= 40 && angle <= 100) {
            return new String[]{"uncrossed", "90", "90.0", String.valueOf(angle)};
        }
        return new String[]{String.valueOf(angle)};
    }

    /**
     * Get exposure time from persistent preferences for a given angle.
     */
    private double getPersistentPreferenceExposure(double angle) {
        if (Math.abs(angle - 0.0) < 0.001) {
            return PPMPreferences.getZeroExposureMs();
        } else if (angle > 0 && angle < 20) {
            return PPMPreferences.getPlusExposureMs();
        } else if (angle < 0 && angle > -20) {
            return PPMPreferences.getMinusExposureMs();
        } else if (angle >= 40 && angle <= 100) {
            return PPMPreferences.getUncrossedExposureMs();
        }

        // Default fallback
        return 1.0;
    }

    /**
     * Get a descriptive name for a PPM angle type.
     */
    private String getPPMAngleTypeName(double angle) {
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
     * Check if the current WB mode selection is per-angle (PPM).
     */
    private boolean isPerAngleWbMode() {
        return wbModeComboBox != null && "Per-angle (PPM)".equals(wbModeComboBox.getValue());
    }

    /**
     * Convert UI display string to protocol string for socket communication.
     */
    private static String convertWbModeToProtocol(String displayValue) {
        if (displayValue == null) return "per_angle";
        return switch (displayValue) {
            case "Off" -> "off";
            case "Camera AWB" -> "camera_awb";
            case "Simple (90deg)" -> "simple";
            case "Per-angle (PPM)" -> "per_angle";
            default -> "per_angle";
        };
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
            Platform.runLater(() -> Dialogs.showWarningNotification("Configuration Save Failed", 
                    "Exposure time changes could not be saved to configuration file. " +
                    "Changes will be lost when QuPath restarts."));
        }
    }
}