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
import qupath.fx.dialogs.Dialogs;
import qupath.ext.qpsc.ui.CameraControlController;

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
            String calibrationName,
            int radiusInner,
            int radiusOuter
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
                "Position the PPM reference slide (sunburst/fan pattern)\n" +
                "under the microscope and ensure it is properly focused.\n\n" +
                "This workflow will:\n" +
                "  1. Acquire an image using the current camera settings\n" +
                "  2. Sample hue values along radial spokes from the pattern center\n" +
                "  3. Create a linear regression mapping hue to orientation angle (0-180 deg)\n" +
                "  4. Save the calibration for use in PPM analysis\n\n" +
                "Hover over (?) icons for detailed parameter descriptions."
            );
            instructionLabel.setWrapText(true);
            instructionLabel.setStyle("-fx-font-size: 11px;");

            // Important note about angle settings
            Label angleNote = new Label(
                "IMPORTANT: Use low-angle PPM settings (e.g., 7, 0, or -7 degrees) for calibration.\n" +
                "These angles provide the best color saturation for detecting the rectangles.\n" +
                "Use the Camera Control button below to set the camera to the correct angle."
            );
            angleNote.setWrapText(true);
            angleNote.setStyle("-fx-font-size: 11px; -fx-text-fill: #cc6600; -fx-font-weight: bold;");

            headerBox.getChildren().addAll(headerLabel, new Separator(), instructionLabel, angleNote);
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
                "Folder where calibration files will be saved.\n\n" +
                "All calibration output files are saved directly\n" +
                "into this folder."
            );
            outputLabelBox.getChildren().get(0).setStyle("-fx-font-weight: bold;");

            TextField outputField = new TextField();
            outputField.setPrefColumnCount(30);

            // Get default output from preferences (remembers last used folder)
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

            // === Camera Setup Section ===
            Label cameraSetupLabel = new Label("Camera Setup");
            cameraSetupLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
            grid.add(cameraSetupLabel, 0, row, 3, 1);
            row++;

            Label cameraInstructions = new Label(
                "Open Camera Control to set the polarizer to a low angle (7, 0, or -7 deg)\n" +
                "and verify the camera settings before running calibration."
            );
            cameraInstructions.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666;");
            cameraInstructions.setWrapText(true);
            grid.add(cameraInstructions, 0, row, 3, 1);
            row++;

            Button cameraControlBtn = new Button("Open Camera Control...");
            cameraControlBtn.setOnAction(e -> {
                CameraControlController.showCameraControlDialog();
            });
            grid.add(cameraControlBtn, 1, row);
            row++;

            grid.add(new Separator(), 0, row, 3, 1);
            row++;

            // === Detection Settings Section ===
            Label detectionLabel = new Label("Detection Settings");
            detectionLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
            grid.add(detectionLabel, 0, row, 3, 1);
            row++;

            // Number of spokes
            HBox rectanglesLabelBox = createLabelWithTooltip("Number of Spokes:",
                "Number of radial spokes (unique orientations) in the sunburst pattern.\n\n" +
                "Common values:\n" +
                "  - 16: Standard sunburst slides (11.25 deg spacing)\n" +
                "  - 12: Some older slides (15 deg spacing)\n" +
                "  - 8: Simplified slides (22.5 deg spacing)\n\n" +
                "Valid range: 4-32\n\n" +
                "The calibrator samples hue values along radial lines\n" +
                "at each spoke angle to build the hue-to-angle mapping."
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
                "In the debug mask image:\n" +
                "  WHITE = detected foreground (above threshold)\n" +
                "  BLACK = background (below threshold)\n\n" +
                "Lower values (0.05-0.1): Include more pixels, may pick up noise\n" +
                "Higher values (0.2-0.3): Stricter, may miss pale rectangles\n\n" +
                "If mask is all BLACK -> lower this value (threshold too high)\n" +
                "If mask is all WHITE -> raise this value (threshold too low)"
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
                "In the debug mask image:\n" +
                "  WHITE = detected foreground (above threshold)\n" +
                "  BLACK = background (below threshold)\n\n" +
                "Lower values (0.05-0.1): Include dimmer pixels\n" +
                "Higher values (0.2-0.3): Only bright pixels\n\n" +
                "If mask is all BLACK -> lower this value (threshold too high)\n" +
                "If mask is all WHITE -> raise this value (threshold too low)"
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

            // === Advanced Detection Settings (collapsible) ===
            TitledPane advancedPane = new TitledPane();
            advancedPane.setText("Advanced Radial Detection Settings");
            advancedPane.setExpanded(false);

            GridPane advGrid = new GridPane();
            advGrid.setHgap(10);
            advGrid.setVgap(10);
            advGrid.setPadding(new Insets(10));

            int advRow = 0;

            // Inner radius
            HBox innerRadiusLabelBox = createLabelWithTooltip("Inner Radius (px):",
                "Inner radius for radial sampling, in pixels from pattern center.\n\n" +
                "Sampling starts at this distance from the detected center.\n" +
                "Increase to skip noisy/dark pixels near the center of the pattern.\n\n" +
                "Default: 30, Range: 10-200"
            );
            Spinner<Integer> innerRadiusSpinner = new Spinner<>(10, 200, 30, 5);
            innerRadiusSpinner.setEditable(true);
            innerRadiusSpinner.setPrefWidth(100);
            advGrid.add(innerRadiusLabelBox, 0, advRow);
            advGrid.add(innerRadiusSpinner, 1, advRow);
            advRow++;

            // Outer radius
            HBox outerRadiusLabelBox = createLabelWithTooltip("Outer Radius (px):",
                "Outer radius for radial sampling, in pixels from pattern center.\n\n" +
                "Sampling ends at this distance. Should reach into the colored\n" +
                "spokes but not extend past them into the background.\n\n" +
                "Default: 150, Range: 50-500"
            );
            Spinner<Integer> outerRadiusSpinner = new Spinner<>(50, 500, 150, 10);
            outerRadiusSpinner.setEditable(true);
            outerRadiusSpinner.setPrefWidth(100);
            advGrid.add(outerRadiusLabelBox, 0, advRow);
            advGrid.add(outerRadiusSpinner, 1, advRow);

            advancedPane.setContent(advGrid);
            grid.add(advancedPane, 0, row, 3, 1);
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
                "Output files saved to: {folder}/\n" +
                "  - {name}_image.tif    Acquired calibration image\n" +
                "  - {name}.npz          Calibration data (used by PPM analysis)\n" +
                "  - {name}_plot.png     Visual verification of calibration fit"
            );
            outputInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666; -fx-font-family: monospace;");
            outputInfoLabel.setWrapText(true);
            grid.add(outputInfoLabel, 0, row, 3, 1);

            // Wrap in ScrollPane so expanding Advanced Settings doesn't
            // push content into the button row
            ScrollPane scrollPane = new ScrollPane(grid);
            scrollPane.setFitToWidth(true);
            scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
            scrollPane.setPrefViewportHeight(420);

            dialog.getDialogPane().setContent(scrollPane);
            dialog.getDialogPane().setPrefWidth(620);
            dialog.setResizable(true);

            // Handle restore defaults button
            Button restoreButton = (Button) dialog.getDialogPane().lookupButton(restoreDefaultsType);
            restoreButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                rectanglesSpinner.getValueFactory().setValue(16);
                saturationSpinner.getValueFactory().setValue(0.1);
                valueSpinner.getValueFactory().setValue(0.1);
                innerRadiusSpinner.getValueFactory().setValue(30);
                outerRadiusSpinner.getValueFactory().setValue(150);
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
                    int rectangles = rectanglesSpinner.getValue();
                    double saturation = saturationSpinner.getValue();
                    double value = valueSpinner.getValue();
                    int innerRadius = innerRadiusSpinner.getValue();
                    int outerRadius = outerRadiusSpinner.getValue();

                    // Remember all settings for next time
                    QPPreferenceDialog.setLastCalibrationFolder(folderPath);
                    QPPreferenceDialog.setSunburstExpectedRectangles(rectangles);
                    QPPreferenceDialog.setSunburstSaturationThreshold(saturation);
                    QPPreferenceDialog.setSunburstValueThreshold(value);

                    // Modality is always "ppm" for this calibration
                    return new SunburstCalibrationParams(
                            folderPath,
                            "ppm",
                            rectangles,
                            saturation,
                            value,
                            name.isEmpty() ? null : name,
                            innerRadius,
                            outerRadius
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
