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
 * Dialog for Starburst Calibration parameters.
 *
 * This dialog collects parameters for creating a hue-to-angle calibration
 * from a starburst/sunburst calibration slide with oriented rectangles.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class StarburstCalibrationDialog {

    private static final Logger logger = LoggerFactory.getLogger(StarburstCalibrationDialog.class);

    /**
     * Parameters for starburst calibration.
     */
    public record StarburstCalibrationParams(
            String outputFolder,
            String modality,
            int expectedRectangles,
            double saturationThreshold,
            double valueThreshold,
            String calibrationName
    ) {}

    /**
     * Shows the starburst calibration dialog.
     *
     * @return CompletableFuture with calibration parameters or null if cancelled
     */
    public static CompletableFuture<StarburstCalibrationParams> showDialog() {
        CompletableFuture<StarburstCalibrationParams> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            Dialog<StarburstCalibrationParams> dialog = new Dialog<>();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setTitle("Starburst Calibration");

            // Header with instructions
            VBox headerBox = new VBox(10);
            headerBox.setPadding(new Insets(10));
            Label headerLabel = new Label("Create Hue-to-Angle Calibration from Starburst Slide");
            headerLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

            Label instructionLabel = new Label(
                "Position the calibration slide (starburst/sunburst pattern with colored rectangles)\n" +
                "under the microscope and ensure it is properly focused.\n\n" +
                "This workflow will:\n" +
                "  1. Acquire an image using the selected modality's exposure settings\n" +
                "  2. Detect oriented rectangles and extract their hue values\n" +
                "  3. Create a linear regression mapping hue to orientation angle\n" +
                "  4. Save the calibration for use in PPM analysis\n\n" +
                "The calibration slide typically has 16 rectangles arranged in a sunburst pattern."
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

            // Output folder
            Label outputLabel = new Label("Calibration Folder:");
            outputLabel.setStyle("-fx-font-weight: bold;");
            TextField outputField = new TextField();
            outputField.setPrefColumnCount(30);

            // Get default output from config - use background correction folder as base
            String configFile = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configFile);
            String defaultOutput = configManager.getBackgroundCorrectionFolder("ppm");

            if (defaultOutput != null && !defaultOutput.isEmpty()) {
                // Use a "calibration" subfolder alongside background folder
                File bgFolder = new File(defaultOutput);
                File calibFolder = new File(bgFolder.getParent(), "calibration");
                defaultOutput = calibFolder.getAbsolutePath();
            } else {
                defaultOutput = System.getProperty("user.home");
            }
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

            grid.add(outputLabel, 0, row);
            grid.add(outputField, 1, row);
            grid.add(browseBtn, 2, row);
            row++;

            grid.add(new Separator(), 0, row, 3, 1);
            row++;

            // Modality selection
            Label modalityLabel = new Label("Modality:");
            modalityLabel.setStyle("-fx-font-weight: bold;");
            ComboBox<String> modalityCombo = new ComboBox<>();

            // Get available PPM modalities from config
            var modalities = configManager.getAvailableModalities();
            if (modalities != null && !modalities.isEmpty()) {
                // Filter to PPM modalities
                for (String mod : modalities) {
                    if (mod.toLowerCase().startsWith("ppm")) {
                        modalityCombo.getItems().add(mod);
                    }
                }
            }
            if (modalityCombo.getItems().isEmpty()) {
                modalityCombo.getItems().add("ppm_20x");
            }
            modalityCombo.getSelectionModel().selectFirst();

            Label modalityHelp = new Label("Select the PPM modality for exposure settings");
            modalityHelp.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

            grid.add(modalityLabel, 0, row);
            grid.add(modalityCombo, 1, row);
            row++;
            grid.add(modalityHelp, 1, row, 2, 1);
            row++;

            grid.add(new Separator(), 0, row, 3, 1);
            row++;

            // Detection Settings Section
            Label detectionLabel = new Label("Detection Settings:");
            detectionLabel.setStyle("-fx-font-weight: bold;");
            grid.add(detectionLabel, 0, row, 3, 1);
            row++;

            // Expected rectangles
            Spinner<Integer> rectanglesSpinner = new Spinner<>(4, 32, 16, 4);
            rectanglesSpinner.setEditable(true);
            rectanglesSpinner.setPrefWidth(100);

            Label rectanglesHelp = new Label("Standard starburst slides have 16 rectangles");
            rectanglesHelp.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

            grid.add(new Label("Expected Rectangles:"), 0, row);
            grid.add(rectanglesSpinner, 1, row);
            row++;
            grid.add(rectanglesHelp, 1, row, 2, 1);
            row++;

            // Saturation threshold
            Spinner<Double> saturationSpinner = new Spinner<>(0.01, 0.5, 0.1, 0.01);
            saturationSpinner.setEditable(true);
            saturationSpinner.setPrefWidth(100);

            Label saturationHelp = new Label("Minimum color saturation to detect as foreground");
            saturationHelp.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

            grid.add(new Label("Saturation Threshold:"), 0, row);
            grid.add(saturationSpinner, 1, row);
            row++;
            grid.add(saturationHelp, 1, row, 2, 1);
            row++;

            // Value threshold
            Spinner<Double> valueSpinner = new Spinner<>(0.01, 0.5, 0.1, 0.01);
            valueSpinner.setEditable(true);
            valueSpinner.setPrefWidth(100);

            Label valueHelp = new Label("Minimum brightness to detect as foreground");
            valueHelp.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

            grid.add(new Label("Value Threshold:"), 0, row);
            grid.add(valueSpinner, 1, row);
            row++;
            grid.add(valueHelp, 1, row, 2, 1);
            row++;

            grid.add(new Separator(), 0, row, 3, 1);
            row++;

            // Calibration name (optional)
            Label nameLabel = new Label("Calibration Name:");
            nameLabel.setStyle("-fx-font-weight: bold;");
            TextField nameField = new TextField();
            nameField.setPrefColumnCount(20);
            nameField.setPromptText("(auto-generated if empty)");

            Label nameHelp = new Label("Optional: Custom name for calibration files");
            nameHelp.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

            grid.add(nameLabel, 0, row);
            grid.add(nameField, 1, row);
            row++;
            grid.add(nameHelp, 1, row, 2, 1);
            row++;

            grid.add(new Separator(), 0, row, 3, 1);
            row++;

            // Info about output
            Label outputInfoLabel = new Label(
                "Output files will be saved to: {folder}/{modality}/\n" +
                "  - {name}_image.tif: Acquired calibration image\n" +
                "  - {name}.npz: Calibration data (hue-to-angle mapping)\n" +
                "  - {name}_plot.png: Visualization of calibration results"
            );
            outputInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
            outputInfoLabel.setWrapText(true);
            grid.add(outputInfoLabel, 0, row, 3, 1);

            dialog.getDialogPane().setContent(grid);
            dialog.getDialogPane().setPrefWidth(600);

            // Handle restore defaults button
            Button restoreButton = (Button) dialog.getDialogPane().lookupButton(restoreDefaultsType);
            restoreButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                rectanglesSpinner.getValueFactory().setValue(16);
                saturationSpinner.getValueFactory().setValue(0.1);
                valueSpinner.getValueFactory().setValue(0.1);
                nameField.clear();
                event.consume();  // Prevent dialog from closing
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

                // Validate modality selection
                if (modalityCombo.getSelectionModel().getSelectedItem() == null) {
                    Dialogs.showErrorMessage("No Modality Selected",
                            "Please select a modality for exposure settings.");
                    event.consume();
                    return;
                }

                // Check rectangles value
                if (rectanglesSpinner.getValue() < 4) {
                    Dialogs.showErrorMessage("Invalid Rectangle Count",
                            "Expected rectangles must be at least 4.");
                    event.consume();
                    return;
                }

                // Validate calibration name (if provided) - no special characters
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
                    return new StarburstCalibrationParams(
                            outputField.getText().trim(),
                            modalityCombo.getSelectionModel().getSelectedItem(),
                            rectanglesSpinner.getValue(),
                            saturationSpinner.getValue(),
                            valueSpinner.getValue(),
                            name.isEmpty() ? null : name
                    );
                }
                return null;
            });

            Optional<StarburstCalibrationParams> result = dialog.showAndWait();
            if (result.isPresent()) {
                future.complete(result.get());
            } else {
                future.complete(null);
            }
        });

        return future;
    }
}
