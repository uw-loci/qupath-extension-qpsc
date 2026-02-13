package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.lib.gui.prefs.PathPrefs;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Dialog for configuring JAI camera noise characterization.
 *
 * <p>This dialog allows the user to systematically test the camera's noise
 * performance across a grid of gain and exposure settings. Three presets
 * are available:
 * <ul>
 *   <li><b>Quick</b>: 16 configurations, approximately 5 minutes</li>
 *   <li><b>Full</b>: 42 configurations, approximately 15 minutes</li>
 *   <li><b>Custom</b>: User-specified gain and exposure values</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class NoiseCharacterizationDialog {
    private static final Logger logger = LoggerFactory.getLogger(NoiseCharacterizationDialog.class);

    // Output subfolder name under config directory
    private static final String NOISE_SUBFOLDER = "noise_characterization";

    // Persistent preferences
    private static final StringProperty presetProperty =
            PathPrefs.createPersistentPreference("noiseChar.preset", "quick");
    private static final IntegerProperty numFramesProperty =
            PathPrefs.createPersistentPreference("noiseChar.numFrames", 10);
    private static final BooleanProperty generatePlotsProperty =
            PathPrefs.createPersistentPreference("noiseChar.generatePlots", true);
    private static final StringProperty customGainsProperty =
            PathPrefs.createPersistentPreference("noiseChar.customGains", "1.0, 2.0, 4.0, 8.0");
    private static final StringProperty customExposuresProperty =
            PathPrefs.createPersistentPreference("noiseChar.customExposures", "5, 10, 20, 50, 100");

    // Preset configuration counts
    private static final int QUICK_CONFIGS = 16;  // 4 gains x 4 exposures
    private static final int FULL_CONFIGS = 42;   // 7 gains x 6 exposures

    /**
     * Result record for noise characterization parameters.
     */
    public record NoiseCharParams(
            String outputPath,
            String preset,
            List<Double> gains,
            List<Double> exposures,
            int numFrames,
            boolean generatePlots) {}

    /**
     * Shows the noise characterization dialog.
     *
     * @return CompletableFuture with configured parameters, or null if cancelled
     */
    public static CompletableFuture<NoiseCharParams> showDialog() {
        CompletableFuture<NoiseCharParams> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                Dialog<NoiseCharParams> dialog = new Dialog<>();
                dialog.setTitle("JAI Noise Characterization");
                dialog.setHeaderText("Characterize camera noise across gain/exposure settings");
                dialog.setResizable(true);

                // Main content
                VBox content = new VBox(15);
                content.setPadding(new Insets(20));
                content.setPrefWidth(550);

                // ========== PRESET SELECTION ==========
                TitledPane presetPane = createPresetPane();
                presetPane.setExpanded(true);

                // ========== CUSTOM SETTINGS (initially hidden) ==========
                TitledPane customPane = createCustomPane();
                boolean isCustom = "custom".equals(presetProperty.get());
                customPane.setExpanded(isCustom);
                customPane.setVisible(isCustom);
                customPane.setManaged(isCustom);

                // ========== GENERAL SETTINGS ==========
                TitledPane generalPane = createGeneralPane();
                generalPane.setExpanded(true);

                content.getChildren().addAll(presetPane, customPane, generalPane);

                // Wire up preset ComboBox to show/hide custom pane
                ComboBox<?> presetCombo = (ComboBox<?>) presetPane.getContent().lookup("#presetCombo");
                Label configCountLabel = (Label) generalPane.getContent().lookup("#configCount");

                if (presetCombo != null) {
                    presetCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
                        String selected = newVal != null ? newVal.toString() : "quick";
                        boolean showCustom = selected.startsWith("Custom");
                        customPane.setVisible(showCustom);
                        customPane.setManaged(showCustom);
                        customPane.setExpanded(showCustom);
                        updateConfigCount(configCountLabel, selected, customPane);
                    });
                    // Initial config count
                    updateConfigCount(configCountLabel,
                            presetCombo.getValue() != null ? presetCombo.getValue().toString() : "Quick",
                            customPane);
                }

                // Update config count when custom fields change
                TextField gainsField = (TextField) customPane.getContent().lookup("#customGains");
                TextField exposuresField = (TextField) customPane.getContent().lookup("#customExposures");
                if (gainsField != null && exposuresField != null) {
                    gainsField.textProperty().addListener((obs, o, n) ->
                            updateConfigCount(configCountLabel,
                                    presetCombo.getValue() != null ? presetCombo.getValue().toString() : "Quick",
                                    customPane));
                    exposuresField.textProperty().addListener((obs, o, n) ->
                            updateConfigCount(configCountLabel,
                                    presetCombo.getValue() != null ? presetCombo.getValue().toString() : "Quick",
                                    customPane));
                }

                // Buttons
                ButtonType runButton = new ButtonType("Run Characterization", ButtonBar.ButtonData.OK_DONE);
                ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
                dialog.getDialogPane().getButtonTypes().addAll(runButton, cancelButton);
                dialog.getDialogPane().setContent(content);

                // Get UI references for result conversion
                Spinner<?> framesSpinner = (Spinner<?>) generalPane.getContent().lookup("#numFrames");
                CheckBox plotsCheck = (CheckBox) generalPane.getContent().lookup("#generatePlots");
                Label outputLabel = (Label) generalPane.getContent().lookup("#outputPath");

                // Result converter
                dialog.setResultConverter(buttonType -> {
                    if (buttonType != runButton) return null;

                    String selectedPreset = presetCombo.getValue() != null ?
                            presetCombo.getValue().toString() : "Quick";
                    String preset;
                    if (selectedPreset.startsWith("Quick")) preset = "quick";
                    else if (selectedPreset.startsWith("Full")) preset = "full";
                    else preset = "custom";

                    int frames = framesSpinner != null ? (Integer) framesSpinner.getValue() : 10;
                    boolean plots = plotsCheck != null && plotsCheck.isSelected();
                    String outPath = outputLabel != null ? outputLabel.getText() : "";

                    List<Double> gainsList = null;
                    List<Double> exposuresList = null;

                    if ("custom".equals(preset)) {
                        gainsList = parseCommaSeparated(gainsField != null ? gainsField.getText() : "");
                        exposuresList = parseCommaSeparated(exposuresField != null ? exposuresField.getText() : "");

                        if (gainsList.isEmpty() || exposuresList.isEmpty()) {
                            // Show validation error
                            Alert alert = new Alert(Alert.AlertType.WARNING);
                            alert.setTitle("Invalid Input");
                            alert.setHeaderText("Custom Values Required");
                            alert.setContentText(
                                    "Please enter comma-separated gain and exposure values for Custom mode.");
                            alert.showAndWait();
                            return null;
                        }
                    }

                    // Save preferences
                    presetProperty.set(preset);
                    numFramesProperty.set(frames);
                    generatePlotsProperty.set(plots);
                    if (gainsField != null) customGainsProperty.set(gainsField.getText());
                    if (exposuresField != null) customExposuresProperty.set(exposuresField.getText());

                    logger.info("Noise characterization parameters:");
                    logger.info("  Preset: {}", preset);
                    logger.info("  Output: {}", outPath);
                    logger.info("  Frames: {}", frames);
                    logger.info("  Plots: {}", plots);
                    if (gainsList != null) logger.info("  Custom gains: {}", gainsList);
                    if (exposuresList != null) logger.info("  Custom exposures: {}", exposuresList);

                    return new NoiseCharParams(outPath, preset, gainsList, exposuresList, frames, plots);
                });

                // Show dialog
                dialog.showAndWait().ifPresentOrElse(
                        future::complete,
                        () -> {
                            logger.info("Noise characterization dialog cancelled");
                            future.complete(null);
                        }
                );

            } catch (Exception e) {
                logger.error("Error showing noise characterization dialog", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Creates the preset selection pane.
     */
    private static TitledPane createPresetPane() {
        VBox vbox = new VBox(10);
        vbox.setPadding(new Insets(10));

        Label descLabel = new Label(
                "Select a test preset to determine which gain/exposure combinations to test.\n" +
                "Quick mode tests fewer settings for a fast overview. Full mode tests a comprehensive grid.");
        descLabel.setWrapText(true);
        descLabel.setStyle("-fx-font-size: 11px;");

        HBox presetBox = new HBox(10);
        presetBox.setAlignment(Pos.CENTER_LEFT);

        Label presetLabel = new Label("Preset:");
        presetLabel.setPrefWidth(80);

        ComboBox<String> presetCombo = new ComboBox<>();
        presetCombo.setId("presetCombo");
        presetCombo.setPrefWidth(350);
        presetCombo.getItems().addAll(
                "Quick (16 configs, ~5 min)",
                "Full (42 configs, ~15 min)",
                "Custom"
        );

        // Restore saved preference
        String savedPreset = presetProperty.get();
        if ("full".equals(savedPreset)) {
            presetCombo.setValue("Full (42 configs, ~15 min)");
        } else if ("custom".equals(savedPreset)) {
            presetCombo.setValue("Custom");
        } else {
            presetCombo.setValue("Quick (16 configs, ~5 min)");
        }

        presetCombo.setTooltip(new Tooltip(
                "Quick: 4 gains x 4 exposures (fast overview)\n" +
                "Full: 7 gains x 6 exposures (comprehensive)\n" +
                "Custom: Specify your own gain and exposure values"));

        presetBox.getChildren().addAll(presetLabel, presetCombo);
        vbox.getChildren().addAll(descLabel, presetBox);

        TitledPane pane = new TitledPane("Test Preset", vbox);
        pane.setCollapsible(true);
        return pane;
    }

    /**
     * Creates the custom settings pane (visible only when Custom preset selected).
     */
    private static TitledPane createCustomPane() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        int row = 0;

        // Description
        Label descLabel = new Label(
                "Enter comma-separated values for gain and exposure settings to test.");
        descLabel.setWrapText(true);
        descLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        grid.add(descLabel, 0, row, 3, 1);
        row++;

        // Gains
        Label gainsLabel = new Label("Gains:");
        gainsLabel.setPrefWidth(100);

        TextField gainsField = new TextField(customGainsProperty.get());
        gainsField.setId("customGains");
        gainsField.setPrefWidth(300);
        gainsField.setPromptText("e.g. 1.0, 2.0, 4.0, 8.0");
        gainsField.setTooltip(new Tooltip("Comma-separated unified gain values to test (linear)"));

        Label gainsNote = new Label("(unified gain, linear)");
        gainsNote.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        grid.add(gainsLabel, 0, row);
        grid.add(gainsField, 1, row);
        grid.add(gainsNote, 2, row);
        row++;

        // Exposures
        Label exposuresLabel = new Label("Exposures (ms):");

        TextField exposuresField = new TextField(customExposuresProperty.get());
        exposuresField.setId("customExposures");
        exposuresField.setPrefWidth(300);
        exposuresField.setPromptText("e.g. 5, 10, 20, 50, 100");
        exposuresField.setTooltip(new Tooltip("Comma-separated exposure times in milliseconds to test"));

        Label exposuresNote = new Label("(milliseconds)");
        exposuresNote.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        grid.add(exposuresLabel, 0, row);
        grid.add(exposuresField, 1, row);
        grid.add(exposuresNote, 2, row);
        row++;

        // Validation label (shows config count)
        Label validationLabel = new Label("");
        validationLabel.setId("customValidation");
        validationLabel.setStyle("-fx-font-size: 11px;");
        grid.add(validationLabel, 0, row, 3, 1);

        // Live validation
        Runnable validate = () -> {
            List<Double> g = parseCommaSeparated(gainsField.getText());
            List<Double> e = parseCommaSeparated(exposuresField.getText());
            if (g.isEmpty() || e.isEmpty()) {
                validationLabel.setText("Enter valid comma-separated numbers");
                validationLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: red;");
            } else {
                int count = g.size() * e.size();
                validationLabel.setText(String.format("%d gains x %d exposures = %d configurations",
                        g.size(), e.size(), count));
                validationLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: green;");
            }
        };

        gainsField.textProperty().addListener((obs, o, n) -> validate.run());
        exposuresField.textProperty().addListener((obs, o, n) -> validate.run());
        validate.run(); // Initial validation

        TitledPane pane = new TitledPane("Custom Settings", grid);
        pane.setCollapsible(true);
        return pane;
    }

    /**
     * Creates the general settings pane (frames, plots, output path).
     */
    private static TitledPane createGeneralPane() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        int row = 0;

        // Frames per measurement
        Label framesLabel = new Label("Frames per test:");
        framesLabel.setPrefWidth(130);

        Spinner<Integer> framesSpinner = new Spinner<>(1, 50, numFramesProperty.get(), 1);
        framesSpinner.setId("numFrames");
        framesSpinner.setEditable(true);
        framesSpinner.setPrefWidth(100);
        framesSpinner.setTooltip(new Tooltip(
                "Number of frames to capture and average for each gain/exposure setting.\n" +
                "More frames = more accurate noise measurement but longer test time.\n" +
                "Default 10 provides good balance."));

        Label framesNote = new Label("(more = more accurate, slower)");
        framesNote.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        grid.add(framesLabel, 0, row);
        grid.add(framesSpinner, 1, row);
        grid.add(framesNote, 2, row);
        row++;

        // Generate plots
        Label plotsLabel = new Label("Generate plots:");

        CheckBox plotsCheck = new CheckBox();
        plotsCheck.setId("generatePlots");
        plotsCheck.setSelected(generatePlotsProperty.get());
        plotsCheck.setTooltip(new Tooltip(
                "Generate noise vs gain curves and SNR heatmap plots.\n" +
                "Requires matplotlib on the Python server."));

        Label plotsNote = new Label("(noise curves + SNR heatmap)");
        plotsNote.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        grid.add(plotsLabel, 0, row);
        grid.add(plotsCheck, 1, row);
        grid.add(plotsNote, 2, row);
        row++;

        // Output path (read-only, derived from config location)
        Label outputPathLabel = new Label("Output folder:");

        String outputPath = "";
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (configPath != null && !configPath.isEmpty()) {
                Path configDir = Paths.get(configPath).getParent();
                if (configDir != null) {
                    outputPath = configDir.resolve(NOISE_SUBFOLDER).toString();
                }
            }
        } catch (Exception e) {
            logger.warn("Could not derive output path from config: {}", e.getMessage());
        }

        Label outputLabel = new Label(outputPath);
        outputLabel.setId("outputPath");
        outputLabel.setStyle("-fx-font-size: 11px;");
        outputLabel.setTooltip(new Tooltip("Results saved to: " + outputPath));

        Label outputNote = new Label("(auto: config folder/" + NOISE_SUBFOLDER + ")");
        outputNote.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        grid.add(outputPathLabel, 0, row);
        grid.add(outputLabel, 1, row);
        grid.add(outputNote, 2, row);
        row++;

        // Total configs label
        Label configCountLabel = new Label("");
        configCountLabel.setId("configCount");
        configCountLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");
        grid.add(configCountLabel, 0, row, 3, 1);

        TitledPane pane = new TitledPane("General Settings", grid);
        pane.setCollapsible(true);
        return pane;
    }

    /**
     * Updates the config count label based on the selected preset.
     */
    private static void updateConfigCount(Label label, String presetText, TitledPane customPane) {
        if (label == null) return;

        int count;
        String timeEstimate;
        if (presetText.startsWith("Quick")) {
            count = QUICK_CONFIGS;
            timeEstimate = "~5 min";
        } else if (presetText.startsWith("Full")) {
            count = FULL_CONFIGS;
            timeEstimate = "~15 min";
        } else {
            // Custom - calculate from fields
            TextField gainsField = (TextField) customPane.getContent().lookup("#customGains");
            TextField exposuresField = (TextField) customPane.getContent().lookup("#customExposures");
            List<Double> gains = parseCommaSeparated(gainsField != null ? gainsField.getText() : "");
            List<Double> exposures = parseCommaSeparated(exposuresField != null ? exposuresField.getText() : "");
            count = gains.size() * exposures.size();
            // Rough estimate: ~20 seconds per config
            int minutes = Math.max(1, (count * 20) / 60);
            timeEstimate = "~" + minutes + " min";
        }

        label.setText(String.format("Total: %d configurations (%s)", count, timeEstimate));
    }

    /**
     * Parses a comma-separated string of numbers into a list of doubles.
     *
     * @param text comma-separated numbers (e.g. "1.0, 2.0, 4.0")
     * @return list of parsed doubles, or empty list if parsing fails
     */
    private static List<Double> parseCommaSeparated(String text) {
        List<Double> values = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return values;

        String[] parts = text.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            try {
                values.add(Double.parseDouble(trimmed));
            } catch (NumberFormatException e) {
                return new ArrayList<>(); // Return empty on any parse failure
            }
        }
        return values;
    }
}
