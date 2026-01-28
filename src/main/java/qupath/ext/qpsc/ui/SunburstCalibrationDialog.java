package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.dialogs.Dialogs;

import java.io.File;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Dialog for Sunburst Calibration parameters.
 *
 * This dialog collects parameters for creating a hue-to-angle calibration
 * from a PPM reference slide with sunburst pattern (oriented rectangles).
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class SunburstCalibrationDialog {

    private static final Logger logger = LoggerFactory.getLogger(SunburstCalibrationDialog.class);

    /**
     * Parameters for sunburst calibration.
     */
    public record SunburstCalibrationParams(
            String outputFolder,
            String modality,
            int expectedRectangles,
            double saturationThreshold,
            double valueThreshold,
            String calibrationName
    ) {}

    /**
     * Creates a label with a tooltip indicator (?) that shows additional help on hover.
     *
     * @param text Main label text
     * @param tooltipText Detailed help text for the tooltip
     * @return HBox containing the label and tooltip indicator
     */
    private static HBox createLabelWithTooltip(String text, String tooltipText) {
        Label mainLabel = new Label(text);

        Label helpIndicator = new Label(" (?)");
        helpIndicator.setStyle("-fx-text-fill: #0066cc; -fx-font-weight: bold; -fx-cursor: hand;");

        Tooltip tooltip = new Tooltip(tooltipText);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(400);
        tooltip.setShowDelay(javafx.util.Duration.millis(200));
        tooltip.setShowDuration(javafx.util.Duration.seconds(30));
        Tooltip.install(helpIndicator, tooltip);

        HBox container = new HBox(mainLabel, helpIndicator);
        container.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return container;
    }

    /**
     * Shows the sunburst calibration dialog.
     *
     * @return CompletableFuture with calibration parameters or null if cancelled
     */
    public static CompletableFuture<SunburstCalibrationParams> showDialog() {
        CompletableFuture<SunburstCalibrationParams> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            Dialog<SunburstCalibrationParams> dialog = new Dialog<>();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setTitle("PPM Reference Slide Calibration");

            // Header with instructions
            VBox headerBox = new VBox(10);
            headerBox.setPadding(new Insets(10));
            Label headerLabel = new Label("Create Hue-to-Angle Calibration from PPM Reference Slide");
            headerLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

            Label instructionLabel = new Label(
                "Position the PPM reference slide (sunburst pattern with colored rectangles)\n" +
                "under the microscope and ensure it is properly focused.\n\n" +
                "This workflow will:\n" +
                "  1. Acquire an image using the selected modality's exposure settings\n" +
                "  2. Detect oriented rectangles and extract their hue values\n" +
                "  3. Create a linear regression mapping hue to orientation angle (0-180 deg)\n" +
                "  4. Save the calibration for use in PPM analysis\n\n" +
                "Hover over (?) icons for detailed parameter descriptions."
            );
            instructionLabel.setWrapText(true);
            instructionLabel.setStyle("-fx-font-size: 11px;");

            headerBox.getChildren().addAll(headerLabel, new Separator(), instructionLabel);
            dialog.getDialogPane().setHeader(headerBox);

            // Buttons
            ButtonType okType = new ButtonType("Start Calibration", ButtonBar.ButtonData.OK_DONE);
            ButtonType restoreDefaultsType = new ButtonType("Restore Defaults", ButtonBar.ButtonData.LEFT);
            ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            dialog.getDialogPane().getButtonTypes().addAll(okType, restoreDefaultsType, cancelType);

            // Main content layout
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(10);
            grid.setPadding(new Insets(20));

            int row = 0;

            // === Output folder ===
            HBox outputLabelBox = createLabelWithTooltip("Calibration Folder:",
                "Root folder where calibration files will be saved.\n\n" +
                "Files are organized by modality:\n" +
                "  {folder}/{modality}/calibration_files\n\n" +
                "This allows multiple calibrations for different objectives\n" +
                "to coexist in the same parent folder."
            );
            outputLabelBox.getChildren().get(0).setStyle("-fx-font-weight: bold;");

            TextField outputField = new TextField();
            outputField.setPrefColumnCount(30);

            // Get default output from preferences (remembers last used folder)
            String configFile = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configFile);
            String defaultOutput = QPPreferenceDialog.getDefaultCalibrationFolder();
            outputField.setText(defaultOutput);

            Button browseBtn = new Button("Browse...");
            browseBtn.setOnAction(e -> {
                DirectoryChooser chooser = new DirectoryChooser();
                chooser.setTitle("Select Calibration Output Folder");
                File current = new File(outputField.getText());
                if (current.exists() && current.isDirectory()) {
                    chooser.setInitialDirectory(current);
                } else if (current.getParentFile() != null && current.getParentFile().exists()) {
                    chooser.setInitialDirectory(current.getParentFile());
                }
                File chosen = chooser.showDialog(dialog.getOwner());
                if (chosen != null) {
                    outputField.setText(chosen.getAbsolutePath());
                }
            });

            grid.add(outputLabelBox, 0, row);
            grid.add(outputField, 1, row);
            grid.add(browseBtn, 2, row);
            row++;

            grid.add(new Separator(), 0, row, 3, 1);
            row++;

            // === Modality selection ===
            HBox modalityLabelBox = createLabelWithTooltip("Modality:",
                "The PPM modality determines camera exposure settings.\n\n" +
                "The calibration will use the 90-degree (uncrossed) exposure\n" +
                "from this modality's profile, which typically provides\n" +
                "good signal for detecting the colored rectangles.\n\n" +
                "Different objectives may need separate calibrations\n" +
                "if their optical properties differ significantly."
            );
            modalityLabelBox.getChildren().get(0).setStyle("-fx-font-weight: bold;");

            ComboBox<String> modalityCombo = new ComboBox<>();

            var modalities = configManager.getAvailableModalities();
            if (modalities != null && !modalities.isEmpty()) {
                for (String mod : modalities) {
                    if (mod.toLowerCase().startsWith("ppm")) {
                        modalityCombo.getItems().add(mod);
                    }
                }
            }
            if (modalityCombo.getItems().isEmpty()) {
                modalityCombo.getItems().add("ppm_20x");
            }
            // Select last used modality if available, otherwise select first
            String lastModality = QPPreferenceDialog.getSunburstLastModality();
            if (lastModality != null && !lastModality.isEmpty() && modalityCombo.getItems().contains(lastModality)) {
                modalityCombo.getSelectionModel().select(lastModality);
            } else {
                modalityCombo.getSelectionModel().selectFirst();
            }

            grid.add(modalityLabelBox, 0, row);
            grid.add(modalityCombo, 1, row);
            row++;

            grid.add(new Separator(), 0, row, 3, 1);
            row++;

            // === Detection Settings Section ===
            Label detectionLabel = new Label("Detection Settings");
            detectionLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
            grid.add(detectionLabel, 0, row, 3, 1);
            row++;

            // Expected rectangles
            HBox rectanglesLabelBox = createLabelWithTooltip("Expected Rectangles:",
                "Number of colored rectangles on your calibration slide.\n\n" +
                "Common values:\n" +
                "  - 16: Standard sunburst slides (22.5 deg spacing)\n" +
                "  - 12: Some older slides (30 deg spacing)\n" +
                "  - 8: Simplified slides (45 deg spacing)\n\n" +
                "Valid range: 4-32\n\n" +
                "If detection finds a different count, a warning will\n" +
                "be shown but calibration will proceed. Large mismatches\n" +
                "may indicate slide positioning or threshold issues."
            );

            int savedRectangles = QPPreferenceDialog.getSunburstExpectedRectangles();
            Spinner<Integer> rectanglesSpinner = new Spinner<>(4, 32, savedRectangles, 4);
            rectanglesSpinner.setEditable(true);
            rectanglesSpinner.setPrefWidth(100);

            grid.add(rectanglesLabelBox, 0, row);
            grid.add(rectanglesSpinner, 1, row);
            row++;

            // Saturation threshold
            HBox saturationLabelBox = createLabelWithTooltip("Saturation Threshold:",
                "Minimum HSV saturation to classify a pixel as foreground (colored).\n\n" +
                "Range: 0.0 - 1.0 (default: 0.1)\n\n" +
                "In HSV color space, saturation measures color intensity:\n" +
                "  - 0.0 = grayscale (no color)\n" +
                "  - 1.0 = fully saturated color\n\n" +
                "Lower values (0.05-0.1): Include more pixels, may pick up noise\n" +
                "Higher values (0.2-0.3): Stricter, may miss pale rectangles\n\n" +
                "If rectangles are not detected, try lowering this value.\n" +
                "If too much background is included, try raising it."
            );

            double savedSaturation = QPPreferenceDialog.getSunburstSaturationThreshold();
            Spinner<Double> saturationSpinner = new Spinner<>(0.01, 0.5, savedSaturation, 0.01);
            saturationSpinner.setEditable(true);
            saturationSpinner.setPrefWidth(100);

            grid.add(saturationLabelBox, 0, row);
            grid.add(saturationSpinner, 1, row);
            row++;

            // Value threshold
            HBox valueLabelBox = createLabelWithTooltip("Value Threshold:",
                "Minimum HSV value (brightness) to classify a pixel as foreground.\n\n" +
                "Range: 0.0 - 1.0 (default: 0.1)\n\n" +
                "In HSV color space, value measures brightness:\n" +
                "  - 0.0 = black\n" +
                "  - 1.0 = maximum brightness\n\n" +
                "This helps exclude dark regions (slide edges, shadows).\n\n" +
                "Lower values (0.05-0.1): Include dimmer pixels\n" +
                "Higher values (0.2-0.3): Only bright pixels\n\n" +
                "If rectangles near slide edges aren't detected,\n" +
                "try lowering this value."
            );

            double savedValue = QPPreferenceDialog.getSunburstValueThreshold();
            Spinner<Double> valueSpinner = new Spinner<>(0.01, 0.5, savedValue, 0.01);
            valueSpinner.setEditable(true);
            valueSpinner.setPrefWidth(100);

            grid.add(valueLabelBox, 0, row);
            grid.add(valueSpinner, 1, row);
            row++;

            grid.add(new Separator(), 0, row, 3, 1);
            row++;

            // === Calibration name ===
            HBox nameLabelBox = createLabelWithTooltip("Calibration Name:",
                "Optional custom name for the calibration files.\n\n" +
                "If left empty, an automatic name is generated:\n" +
                "  sunburst_cal_YYYYMMDD_HHMMSS\n\n" +
                "Custom names can help identify calibrations:\n" +
                "  - slide_batch1\n" +
                "  - before_realignment\n" +
                "  - optimal_exposure\n\n" +
                "Allowed characters: letters, numbers, underscore, hyphen\n" +
                "Spaces and special characters are not allowed."
            );
            nameLabelBox.getChildren().get(0).setStyle("-fx-font-weight: bold;");

            TextField nameField = new TextField();
            nameField.setPrefColumnCount(20);
            nameField.setPromptText("(auto-generated if empty)");

            grid.add(nameLabelBox, 0, row);
            grid.add(nameField, 1, row);
            row++;

            grid.add(new Separator(), 0, row, 3, 1);
            row++;

            // === Output info ===
            Label outputInfoLabel = new Label(
                "Output files saved to: {folder}/{modality}/\n" +
                "  - {name}_image.tif    Acquired calibration image\n" +
                "  - {name}.npz          Calibration data (used by PPM analysis)\n" +
                "  - {name}_plot.png     Visual verification of calibration fit"
            );
            outputInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666; -fx-font-family: monospace;");
            outputInfoLabel.setWrapText(true);
            grid.add(outputInfoLabel, 0, row, 3, 1);

            dialog.getDialogPane().setContent(grid);
            dialog.getDialogPane().setPrefWidth(620);

            // Handle restore defaults button
            Button restoreButton = (Button) dialog.getDialogPane().lookupButton(restoreDefaultsType);
            restoreButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                rectanglesSpinner.getValueFactory().setValue(16);
                saturationSpinner.getValueFactory().setValue(0.1);
                valueSpinner.getValueFactory().setValue(0.1);
                nameField.clear();
                event.consume();
            });

            // Validation
            Button okButton = (Button) dialog.getDialogPane().lookupButton(okType);
            okButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                String output = outputField.getText().trim();

                if (output.isEmpty()) {
                    Dialogs.showErrorMessage("Invalid Output Folder",
                            "Please specify an output folder for calibration files.");
                    event.consume();
                    return;
                }

                if (modalityCombo.getSelectionModel().getSelectedItem() == null) {
                    Dialogs.showErrorMessage("No Modality Selected",
                            "Please select a modality for exposure settings.");
                    event.consume();
                    return;
                }

                if (rectanglesSpinner.getValue() < 4) {
                    Dialogs.showErrorMessage("Invalid Rectangle Count",
                            "Expected rectangles must be at least 4.");
                    event.consume();
                    return;
                }

                String name = nameField.getText().trim();
                if (!name.isEmpty() && !name.matches("[a-zA-Z0-9_\\-]+")) {
                    Dialogs.showErrorMessage("Invalid Calibration Name",
                            "Calibration name can only contain letters, numbers, underscores, and hyphens.");
                    event.consume();
                    return;
                }
            });

            // Result converter
            dialog.setResultConverter(button -> {
                if (button == okType) {
                    String name = nameField.getText().trim();
                    String folderPath = outputField.getText().trim();
                    String selectedModality = modalityCombo.getSelectionModel().getSelectedItem();
                    int rectangles = rectanglesSpinner.getValue();
                    double saturation = saturationSpinner.getValue();
                    double value = valueSpinner.getValue();

                    // Remember all settings for next time
                    QPPreferenceDialog.setLastCalibrationFolder(folderPath);
                    QPPreferenceDialog.setSunburstLastModality(selectedModality);
                    QPPreferenceDialog.setSunburstExpectedRectangles(rectangles);
                    QPPreferenceDialog.setSunburstSaturationThreshold(saturation);
                    QPPreferenceDialog.setSunburstValueThreshold(value);

                    return new SunburstCalibrationParams(
                            folderPath,
                            selectedModality,
                            rectangles,
                            saturation,
                            value,
                            name.isEmpty() ? null : name
                    );
                }
                return null;
            });

            Optional<SunburstCalibrationParams> result = dialog.showAndWait();
            if (result.isPresent()) {
                future.complete(result.get());
            } else {
                future.complete(null);
            }
        });

        return future;
    }
}
