package qupath.ext.qpsc.controller;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.DocumentationHelper;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.dialogs.Dialogs;

/**
 * AutofocusEditorWorkflow - Configuration editor for per-objective autofocus settings
 *
 * <p>This workflow provides a GUI for editing autofocus parameters stored in autofocus_{microscope}.yml.
 * The autofocus configuration is separate from the main microscope config and contains three parameters
 * per objective:
 * <ul>
 *   <li>n_steps: Number of Z positions to sample during autofocus</li>
 *   <li>search_range_um: Total Z range to search in micrometers</li>
 *   <li>n_tiles: Spatial frequency - autofocus runs every N tiles during acquisition</li>
 * </ul>
 *
 * <p>Key features:
 * <ul>
 *   <li>Reads objectives from main microscope config (respects preference setting)</li>
 *   <li>Loads existing autofocus settings if autofocus_{microscope}.yml exists</li>
 *   <li>"Write to file" button saves immediately but keeps dialog open</li>
 *   <li>"OK" button saves (if changed) and closes dialog</li>
 *   <li>"Cancel" button closes without saving unsaved changes</li>
 *   <li>Parameter validation with warnings for extreme values</li>
 * </ul>
 *
 * @author Mike Nelson
 */
public class AutofocusEditorWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(AutofocusEditorWorkflow.class);

    /**
     * Autofocus settings for a single objective
     */
    private static class AutofocusSettings {
        // Standard autofocus parameters
        String objective;
        int nSteps;
        double searchRangeUm;
        int nTiles;
        int interpStrength;
        String interpKind;
        String scoreMetric;
        double textureThreshold;
        double tissueAreaThreshold;

        // Sweep drift check parameters
        double sweepRangeUm;
        int sweepNSteps;

        AutofocusSettings(
                String objective,
                int nSteps,
                double searchRangeUm,
                int nTiles,
                int interpStrength,
                String interpKind,
                String scoreMetric,
                double textureThreshold,
                double tissueAreaThreshold,
                double sweepRangeUm,
                int sweepNSteps) {
            this.objective = objective;
            this.nSteps = nSteps;
            this.searchRangeUm = searchRangeUm;
            this.nTiles = nTiles;
            this.interpStrength = interpStrength;
            this.interpKind = interpKind;
            this.scoreMetric = scoreMetric;
            this.textureThreshold = textureThreshold;
            this.tissueAreaThreshold = tissueAreaThreshold;
            this.sweepRangeUm = sweepRangeUm;
            this.sweepNSteps = sweepNSteps;
        }

        // Validation with detailed feedback
        List<String> validate() {
            List<String> warnings = new ArrayList<>();

            if (nSteps <= 0) {
                warnings.add("n_steps must be positive");
            } else if (nSteps > 50) {
                warnings.add("n_steps > 50 may be unnecessarily slow (typical range: 5-20)");
            }

            if (searchRangeUm <= 0) {
                warnings.add("search_range_um must be positive");
            } else if (searchRangeUm > 1000) {
                warnings.add("search_range_um > 1000 um is very large (typical range: 10-50 um)");
            }

            if (nTiles <= 0) {
                warnings.add("n_tiles must be positive");
            } else if (nTiles > 20) {
                warnings.add("n_tiles > 20 may cause infrequent autofocus (typical range: 3-10)");
            }

            if (interpStrength <= 0) {
                warnings.add("interp_strength must be positive");
            } else if (interpStrength > 1000) {
                warnings.add("interp_strength > 1000 may be unnecessarily high (typical: 50-200)");
            }

            if (interpKind == null || interpKind.isEmpty()) {
                warnings.add("interp_kind must be specified");
            }

            if (scoreMetric == null || scoreMetric.isEmpty()) {
                warnings.add("score_metric must be specified");
            }

            if (textureThreshold <= 0) {
                warnings.add("texture_threshold must be positive");
            } else if (textureThreshold > 0.1) {
                warnings.add("texture_threshold > 0.1 is very high (typical range: 0.005-0.030)");
            }

            if (tissueAreaThreshold <= 0) {
                warnings.add("tissue_area_threshold must be positive");
            } else if (tissueAreaThreshold > 0.5) {
                warnings.add("tissue_area_threshold > 0.5 is very high (typical range: 0.05-0.30)");
            }

            // Sweep drift check validation
            if (sweepRangeUm <= 0) {
                warnings.add("sweep_range_um must be positive");
            } else if (sweepRangeUm > 50) {
                warnings.add("sweep_range_um > 50 um is very large (typical range: 6-20 um)");
            }

            if (sweepNSteps < 3) {
                warnings.add("sweep_n_steps must be at least 3 for peak detection");
            } else if (sweepNSteps > 20) {
                warnings.add("sweep_n_steps > 20 may be unnecessarily slow (typical range: 4-8)");
            }

            return warnings;
        }
    }

    /**
     * Main entry point for the autofocus editor workflow
     */
    public static void run() {
        Platform.runLater(() -> {
            try {
                showAutofocusEditorDialog();
            } catch (Exception e) {
                logger.error("Error in autofocus editor workflow", e);
                Dialogs.showErrorMessage(
                        "Autofocus Editor Error", "Failed to open autofocus editor: " + e.getMessage());
            }
        });
    }

    /**
     * Show the autofocus editor dialog
     */
    private static void showAutofocusEditorDialog() throws IOException {
        // Get microscope config path from preferences
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        if (configPath == null || configPath.isEmpty()) {
            Dialogs.showErrorMessage("Configuration Error", "No microscope configuration file set in preferences.");
            return;
        }

        File configFile = new File(configPath);
        if (!configFile.exists()) {
            Dialogs.showErrorMessage("Configuration Error", "Microscope configuration file not found: " + configPath);
            return;
        }

        // Extract microscope name from config filename (e.g., "config_PPM.yml" -> "PPM")
        String configFilename = configFile.getName();
        String microscopeName = extractMicroscopeName(configFilename);

        // Construct autofocus config path
        File configDir = configFile.getParentFile();
        File autofocusFile = new File(configDir, "autofocus_" + microscopeName + ".yml");

        logger.info("Autofocus editor using config: {}", autofocusFile.getAbsolutePath());

        // Load objectives from main config
        MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
        List<String> objectives = loadObjectivesFromConfig(configManager);

        if (objectives.isEmpty()) {
            Dialogs.showErrorMessage("Configuration Error", "No objectives found in microscope configuration.");
            return;
        }

        // Load existing autofocus settings (if file exists)
        Map<String, AutofocusSettings> existingSettings = loadAutofocusSettings(autofocusFile);

        // Create working copy with defaults for all objectives
        Map<String, AutofocusSettings> workingSettings = new LinkedHashMap<>();
        logger.info(
                "Creating working settings from {} objectives and {} existing settings",
                objectives.size(),
                existingSettings.size());
        logger.info("Objectives list: {}", objectives);
        logger.info("Existing settings keys: {}", existingSettings.keySet());

        for (String obj : objectives) {
            logger.info("Processing objective: '{}'", obj);
            if (existingSettings.containsKey(obj)) {
                AutofocusSettings existing = existingSettings.get(obj);
                logger.info("  FOUND in existingSettings: n_steps={}", existing.nSteps);
                workingSettings.put(
                        obj,
                        new AutofocusSettings(
                                obj,
                                existing.nSteps,
                                existing.searchRangeUm,
                                existing.nTiles,
                                existing.interpStrength,
                                existing.interpKind,
                                existing.scoreMetric,
                                existing.textureThreshold,
                                existing.tissueAreaThreshold,
                                existing.sweepRangeUm,
                                existing.sweepNSteps));
            } else {
                logger.info("  NOT FOUND in existingSettings - using defaults");
                // Use defaults: n_steps=9, search_range=15um, n_tiles=5, interp_strength=100,
                // interp_kind=quadratic, score_metric=normalized_variance,
                // texture_threshold=0.005, tissue_area_threshold=0.2
                // Sweep defaults: range=10.0um, n_steps=6
                workingSettings.put(
                        obj,
                        new AutofocusSettings(
                                obj, 9, 15.0, 5, 100, "quadratic", "normalized_variance", 0.005, 0.2, 10.0, 6));
            }
        }

        // Create dialog
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initModality(javafx.stage.Modality.NONE);
        qupath.lib.gui.QuPathGUI gui = qupath.lib.gui.QuPathGUI.getInstance();
        if (gui != null && gui.getStage() != null) {
            dialog.initOwner(gui.getStage());
        }
        dialog.setTitle("Autofocus Configuration Editor");
        dialog.setHeaderText("Edit autofocus parameters for " + microscopeName + " microscope\n"
                + "Settings will be saved to: " + autofocusFile.getName());
        dialog.setGraphic(DocumentationHelper.createHelpButton("autofocusEditor"));

        // Create UI
        VBox mainLayout = new VBox(10);
        mainLayout.setPadding(new Insets(10));

        // Objective selection
        Label objectiveLabel = new Label("Select Objective:");
        ComboBox<String> objectiveCombo = new ComboBox<>();
        objectiveCombo.getItems().addAll(objectives);
        objectiveCombo.setValue(objectives.get(0));

        // ===== ACQUISITION FREQUENCY SECTION =====
        GridPane acquisitionGrid = new GridPane();
        acquisitionGrid.setHgap(10);
        acquisitionGrid.setVgap(8);
        acquisitionGrid.setPadding(new Insets(5));

        Label nTilesLabel = new Label("n_tiles:");
        Spinner<Integer> nTilesSpinner = new Spinner<>(1, 50, 5, 1);
        nTilesSpinner.setEditable(true);
        nTilesSpinner.setPrefWidth(100);
        nTilesSpinner.setTooltip(
                new Tooltip("Spatial frequency: Autofocus runs every N tiles.\n\n" + "Lower values (1-3):\n"
                        + "  + More frequent autofocus\n"
                        + "  + Better tracking of uneven samples\n"
                        + "  - Significantly slower acquisition\n"
                        + "  - More wear on Z motor\n\n"
                        + "Higher values (5-10):\n"
                        + "  + Faster acquisition\n"
                        + "  + Less mechanical wear\n"
                        + "  - May lose focus on tilted samples\n\n"
                        + "Typical: 5 tiles (good balance)\n"
                        + "Use 1-3 for tilted or curved samples"));
        Label nTilesDesc = new Label("(Autofocus every N tiles during acquisition)");
        nTilesDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        acquisitionGrid.add(nTilesLabel, 0, 0);
        acquisitionGrid.add(nTilesSpinner, 1, 0);
        acquisitionGrid.add(nTilesDesc, 2, 0);

        TitledPane acquisitionPane = new TitledPane("Acquisition Frequency", acquisitionGrid);
        acquisitionPane.setCollapsible(false);

        // ===== TISSUE DETECTION SECTION =====
        GridPane tissueGrid = new GridPane();
        tissueGrid.setHgap(10);
        tissueGrid.setVgap(8);
        tissueGrid.setPadding(new Insets(5));

        Label textureThresholdLabel = new Label("texture_threshold:");
        TextField textureThresholdField = new TextField("0.005");
        textureThresholdField.setPrefWidth(100);
        textureThresholdField.setTooltip(new Tooltip("Minimum texture variance required for tissue detection.\n"
                + "Controls whether autofocus runs at a position.\n\n"
                + "Lower values (0.005-0.010):\n"
                + "  + More sensitive - detects smooth tissue\n"
                + "  + Accepts homogeneous samples\n"
                + "  - May accept out-of-focus areas\n\n"
                + "Higher values (0.015-0.030):\n"
                + "  + More selective - requires textured tissue\n"
                + "  + Rejects blurry or empty areas\n"
                + "  - May skip smooth but valid tissue\n\n"
                + "Typical: 0.005 for smooth tissue, 0.010-0.015 for textured"));
        Label textureThresholdDesc = new Label("(Min texture variance, typical: 0.005-0.030)");
        textureThresholdDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label tissueAreaThresholdLabel = new Label("tissue_area_threshold:");
        TextField tissueAreaThresholdField = new TextField("0.2");
        tissueAreaThresholdField.setPrefWidth(100);
        tissueAreaThresholdField.setTooltip(new Tooltip("Minimum fraction of image that must contain tissue.\n"
                + "Determines if enough tissue is present for autofocus.\n\n"
                + "Lower values (0.05-0.15):\n"
                + "  + Accepts sparse tissue coverage\n"
                + "  + Better for small or fragmented samples\n"
                + "  - May autofocus on debris\n\n"
                + "Higher values (0.20-0.30):\n"
                + "  + Requires substantial tissue presence\n"
                + "  + More reliable autofocus targets\n"
                + "  - May skip valid tissue at edges\n\n"
                + "Typical: 0.2 (20% coverage)"));
        Label tissueAreaThresholdDesc = new Label("(Min tissue coverage fraction, typical: 0.05-0.30)");
        tissueAreaThresholdDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        tissueGrid.add(textureThresholdLabel, 0, 0);
        tissueGrid.add(textureThresholdField, 1, 0);
        tissueGrid.add(textureThresholdDesc, 2, 0);

        tissueGrid.add(tissueAreaThresholdLabel, 0, 1);
        tissueGrid.add(tissueAreaThresholdField, 1, 1);
        tissueGrid.add(tissueAreaThresholdDesc, 2, 1);

        // Add score_metric to tissue/shared grid (used by both standard AF and sweep drift check)
        Label scoreMetricLabel = new Label("score_metric:");
        ComboBox<String> scoreMetricCombo = new ComboBox<>();
        scoreMetricCombo
                .getItems()
                .addAll("normalized_variance", "laplacian_variance", "sobel", "brenner_gradient", "p98_p2");
        scoreMetricCombo.setValue("normalized_variance");
        scoreMetricCombo.setPrefWidth(200);
        scoreMetricCombo.setTooltip(new Tooltip("Algorithm for measuring image sharpness.\n"
                + "Used by BOTH standard autofocus and sweep drift check.\n\n"
                + "normalized_variance (recommended):\n"
                + "  + Best sensitivity (145% signal at 20x)\n"
                + "  + Works well on dim and bright tissue\n"
                + "  + Amplifies changes on low-contrast samples\n\n"
                + "laplacian_variance (~5ms):\n"
                + "  + Good for 10x or lower magnification\n"
                + "  - Poor sensitivity at 20x+ (<2% signal)\n\n"
                + "sobel (~5ms):\n"
                + "  + Edge-sensitive metric (40% signal)\n"
                + "  + Good for high-contrast features\n\n"
                + "brenner_gradient (~3ms):\n"
                + "  + Fastest option (12% signal)\n"
                + "  - Lower sensitivity\n\n"
                + "p98_p2 (histogram spread):\n"
                + "  + Good sensitivity (70% signal)\n"
                + "  + Simple and robust"));
        Label scoreMetricDesc = new Label("(Focus metric - shared by standard AF and sweep)");
        scoreMetricDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        tissueGrid.add(scoreMetricLabel, 0, 2);
        tissueGrid.add(scoreMetricCombo, 1, 2);
        tissueGrid.add(scoreMetricDesc, 2, 2);

        TitledPane tissuePane = new TitledPane("Tissue Detection & Shared Settings", tissueGrid);
        tissuePane.setCollapsible(false);

        // ===== STANDARD AUTOFOCUS SECTION =====
        GridPane standardGrid = new GridPane();
        standardGrid.setHgap(10);
        standardGrid.setVgap(8);
        standardGrid.setPadding(new Insets(5));

        Label nStepsLabel = new Label("n_steps:");
        Spinner<Integer> nStepsSpinner = new Spinner<>(1, 100, 9, 1);
        nStepsSpinner.setEditable(true);
        nStepsSpinner.setPrefWidth(100);
        nStepsSpinner.setTooltip(
                new Tooltip("Number of Z positions sampled during autofocus.\n\n" + "Higher values (15-30):\n"
                        + "  + More accurate focus finding\n"
                        + "  + Better for thick samples\n"
                        + "  - Slower autofocus (~2-3x time)\n\n"
                        + "Lower values (5-11):\n"
                        + "  + Faster autofocus\n"
                        + "  + Adequate for thin, flat samples\n"
                        + "  - May miss optimal focus on thick samples\n\n"
                        + "Typical: 9-15 steps"));
        Label nStepsDesc = new Label("(Number of Z positions to sample)");
        nStepsDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label searchRangeLabel = new Label("search_range_um:");
        TextField searchRangeField = new TextField("15.0");
        searchRangeField.setPrefWidth(100);
        searchRangeField.setTooltip(
                new Tooltip("Total Z range to search, centered on current position.\n\n" + "Larger range (30-50um):\n"
                        + "  + Finds focus even when stage is far off\n"
                        + "  + Better for initial acquisition setup\n"
                        + "  - Slower if many steps used\n\n"
                        + "Smaller range (10-20um):\n"
                        + "  + Faster autofocus\n"
                        + "  + Works well when stage is pre-leveled\n"
                        + "  - May fail if sample is very tilted\n\n"
                        + "Typical: 15-25um for most samples"));
        Label searchRangeDesc = new Label("(Total Z range in micrometers)");
        searchRangeDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label interpStrengthLabel = new Label("interp_strength:");
        Spinner<Integer> interpStrengthSpinner = new Spinner<>(10, 1000, 100, 10);
        interpStrengthSpinner.setEditable(true);
        interpStrengthSpinner.setPrefWidth(100);
        interpStrengthSpinner.setTooltip(
                new Tooltip("Density of interpolated points in focus curve.\n\n" + "Higher values (150-200):\n"
                        + "  + Smoother focus curve fitting\n"
                        + "  + More precise peak finding\n"
                        + "  - Minimal speed impact\n\n"
                        + "Lower values (50-100):\n"
                        + "  + Slightly faster computation\n"
                        + "  + Usually sufficient for most samples\n\n"
                        + "Typical: 100 (good default)\n"
                        + "Increase to 150-200 if autofocus is inconsistent"));
        Label interpStrengthDesc = new Label("(Interpolation density factor)");
        interpStrengthDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label interpKindLabel = new Label("interp_kind:");
        ComboBox<String> interpKindCombo = new ComboBox<>();
        interpKindCombo.getItems().addAll("linear", "quadratic", "cubic");
        interpKindCombo.setValue("quadratic");
        interpKindCombo.setPrefWidth(150);
        interpKindCombo.setTooltip(new Tooltip("Interpolation method for focus curve fitting.\n\n" + "Linear:\n"
                + "  + Simple and fast\n"
                + "  - Less accurate peak detection\n\n"
                + "Quadratic (recommended):\n"
                + "  + Good balance of speed and accuracy\n"
                + "  + Smooth parabolic curve fitting\n"
                + "  + Works well for most samples\n\n"
                + "Cubic:\n"
                + "  + Most accurate curve fitting\n"
                + "  - Can be sensitive to noise\n"
                + "  - May overfit sparse data\n\n"
                + "Typical: quadratic for most applications"));
        Label interpKindDesc = new Label("(Interpolation method)");
        interpKindDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        // Add standard autofocus fields to grid
        // Note: score_metric is now in the shared section above
        standardGrid.add(nStepsLabel, 0, 0);
        standardGrid.add(nStepsSpinner, 1, 0);
        standardGrid.add(nStepsDesc, 2, 0);

        standardGrid.add(searchRangeLabel, 0, 1);
        standardGrid.add(searchRangeField, 1, 1);
        standardGrid.add(searchRangeDesc, 2, 1);

        standardGrid.add(interpStrengthLabel, 0, 2);
        standardGrid.add(interpStrengthSpinner, 1, 2);
        standardGrid.add(interpStrengthDesc, 2, 2);

        standardGrid.add(interpKindLabel, 0, 3);
        standardGrid.add(interpKindCombo, 1, 3);
        standardGrid.add(interpKindDesc, 2, 3);

        // ===== SWEEP DRIFT CHECK SECTION =====
        GridPane sweepGrid = new GridPane();
        sweepGrid.setHgap(10);
        sweepGrid.setVgap(8);
        sweepGrid.setPadding(new Insets(5));

        Label sweepRangeLabel = new Label("sweep_range_um:");
        TextField sweepRangeField = new TextField("10.0");
        sweepRangeField.setPrefWidth(100);
        sweepRangeField.setTooltip(new Tooltip("Total Z range for the sweep drift check.\n"
                + "The sweep samples positions from -range/2 to +range/2\n"
                + "around the current Z position.\n\n"
                + "Larger range (15-20um):\n"
                + "  + Catches larger drift\n"
                + "  + Better for samples with significant thermal drift\n"
                + "  - Slightly slower\n\n"
                + "Smaller range (6-10um):\n"
                + "  + Faster sweep\n"
                + "  + Higher precision within range\n"
                + "  - May miss large drift events\n\n"
                + "Typical: 10um (+/-5um) for most samples"));
        Label sweepRangeDesc = new Label("(Total Z range in um, centered on current position)");
        sweepRangeDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label sweepNStepsLabel = new Label("sweep_n_steps:");
        Spinner<Integer> sweepNStepsSpinner = new Spinner<>(3, 20, 6, 1);
        sweepNStepsSpinner.setEditable(true);
        sweepNStepsSpinner.setPrefWidth(100);
        sweepNStepsSpinner.setTooltip(new Tooltip("Number of Z positions sampled during sweep drift check.\n\n"
                + "More steps (8-12):\n"
                + "  + Better peak resolution\n"
                + "  + More reliable on noisy samples\n"
                + "  - Slower (~0.7s per step)\n\n"
                + "Fewer steps (4-6):\n"
                + "  + Faster sweep\n"
                + "  + Adequate for typical drift\n"
                + "  - Coarser peak detection\n\n"
                + "Typical: 6 steps (~3s total)"));
        Label sweepNStepsDesc = new Label("(Number of Z positions to sample)");
        sweepNStepsDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        sweepGrid.add(sweepRangeLabel, 0, 0);
        sweepGrid.add(sweepRangeField, 1, 0);
        sweepGrid.add(sweepRangeDesc, 2, 0);

        sweepGrid.add(sweepNStepsLabel, 0, 1);
        sweepGrid.add(sweepNStepsSpinner, 1, 1);
        sweepGrid.add(sweepNStepsDesc, 2, 1);

        // Status label for validation feedback
        Label statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: blue; -fx-font-weight: bold;");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(500);

        // Track current objective for saving changes before switching
        // Initialize to null to skip save on first load (prevents overwriting loaded settings with UI defaults)
        final String[] currentObjective = {null};

        // Save current UI values to working settings
        Runnable saveCurrentSettings = () -> {
            try {
                int nSteps = nStepsSpinner.getValue();
                double searchRange = Double.parseDouble(searchRangeField.getText());
                int nTiles = nTilesSpinner.getValue();
                int interpStrength = interpStrengthSpinner.getValue();
                String interpKind = interpKindCombo.getValue();
                String scoreMetric = scoreMetricCombo.getValue();
                double textureThreshold = Double.parseDouble(textureThresholdField.getText());
                double tissueAreaThreshold = Double.parseDouble(tissueAreaThresholdField.getText());
                double sweepRange = Double.parseDouble(sweepRangeField.getText());
                int sweepNSteps = sweepNStepsSpinner.getValue();

                workingSettings.put(
                        currentObjective[0],
                        new AutofocusSettings(
                                currentObjective[0],
                                nSteps,
                                searchRange,
                                nTiles,
                                interpStrength,
                                interpKind,
                                scoreMetric,
                                textureThreshold,
                                tissueAreaThreshold,
                                sweepRange,
                                sweepNSteps));
            } catch (NumberFormatException ex) {
                logger.warn("Invalid numeric input when saving settings");
            }
        };

        // Load settings from working copy for selected objective
        Runnable loadSettingsForObjective = () -> {
            // First save current UI state
            if (currentObjective[0] != null) {
                saveCurrentSettings.run();
            }

            // Update current objective
            String selectedObjective = objectiveCombo.getValue();
            currentObjective[0] = selectedObjective;

            logger.info("Loading settings for objective: {}", selectedObjective);
            logger.info(
                    "Working settings contains {} objectives: {}", workingSettings.size(), workingSettings.keySet());
            logger.info(
                    "Existing settings contains {} objectives: {}", existingSettings.size(), existingSettings.keySet());

            // Load from working settings
            AutofocusSettings settings = workingSettings.get(selectedObjective);

            if (settings != null) {
                logger.info(
                        "Found settings for {}: n_steps={}, search_range={}, texture_threshold={}, tissue_area_threshold={}",
                        selectedObjective,
                        settings.nSteps,
                        settings.searchRangeUm,
                        settings.textureThreshold,
                        settings.tissueAreaThreshold);

                nStepsSpinner.getValueFactory().setValue(settings.nSteps);
                searchRangeField.setText(String.valueOf(settings.searchRangeUm));
                nTilesSpinner.getValueFactory().setValue(settings.nTiles);
                interpStrengthSpinner.getValueFactory().setValue(settings.interpStrength);
                interpKindCombo.setValue(settings.interpKind);
                scoreMetricCombo.setValue(settings.scoreMetric);
                textureThresholdField.setText(String.valueOf(settings.textureThreshold));
                tissueAreaThresholdField.setText(String.valueOf(settings.tissueAreaThreshold));
                sweepRangeField.setText(String.valueOf(settings.sweepRangeUm));
                sweepNStepsSpinner.getValueFactory().setValue(settings.sweepNSteps);
                if (existingSettings.containsKey(selectedObjective)) {
                    statusLabel.setText("Loaded existing settings for " + selectedObjective);
                    logger.info("UI populated with existing settings for {}", selectedObjective);
                } else {
                    statusLabel.setText("Using default values for " + selectedObjective);
                    logger.info("UI populated with default values for {}", selectedObjective);
                }
            } else {
                logger.warn("No settings found in workingSettings for objective: {}", selectedObjective);
            }
        };

        objectiveCombo.setOnAction(e -> loadSettingsForObjective.run());
        loadSettingsForObjective.run(); // Load initial settings

        // "Write to file" button
        Button writeButton = new Button("Write to File");
        writeButton.setOnAction(e -> {
            try {
                // Save current UI state to working settings
                saveCurrentSettings.run();

                // Validate all settings
                boolean hasErrors = false;
                for (AutofocusSettings settings : workingSettings.values()) {
                    List<String> warnings = settings.validate();
                    if (!warnings.isEmpty()) {
                        boolean proceed = Dialogs.showConfirmDialog(
                                "Validation Warnings for " + settings.objective,
                                String.join("\n", warnings) + "\n\nContinue saving?");
                        if (!proceed) {
                            hasErrors = true;
                            break;
                        }
                    }
                }

                if (hasErrors) {
                    return;
                }

                // Save to file
                saveAutofocusSettings(autofocusFile, workingSettings);
                statusLabel.setText("Settings saved successfully to " + autofocusFile.getName());
                statusLabel.setStyle("-fx-text-fill: green; -fx-font-weight: bold;");
                logger.info("Autofocus settings saved to: {}", autofocusFile.getAbsolutePath());

            } catch (NumberFormatException ex) {
                Dialogs.showErrorMessage("Input Error", "Please enter valid numeric values.");
            } catch (IOException ex) {
                logger.error("Failed to save autofocus settings", ex);
                Dialogs.showErrorMessage("Save Error", "Failed to save settings: " + ex.getMessage());
            }
        });

        // "Test Standard Autofocus" button - will be placed inside standard section
        Button testStandardButton = new Button("Test Standard Autofocus");
        testStandardButton.setOnAction(e -> {
            try {
                // First, save current UI state to working settings
                saveCurrentSettings.run();

                // Validate current settings
                String currentObj = objectiveCombo.getValue();
                AutofocusSettings currentSettings = workingSettings.get(currentObj);

                if (currentSettings != null) {
                    List<String> warnings = currentSettings.validate();
                    if (!warnings.isEmpty()) {
                        Dialogs.showWarningNotification(
                                "Validation Warnings",
                                "Current settings have warnings:\n" + String.join("\n", warnings));
                    }
                }

                // Save to file first so test uses current settings
                saveAutofocusSettings(autofocusFile, workingSettings);
                statusLabel.setText("Settings saved - running standard autofocus test...");
                statusLabel.setStyle("-fx-text-fill: blue; -fx-font-weight: bold;");
                logger.info("Autofocus settings saved before standard test");

                // Determine output path for test results (same directory as config file)
                String testOutputPath = new File(configDir, "autofocus_tests").getAbsolutePath();
                logger.info("Using autofocus test output path: {}", testOutputPath);

                // Run the STANDARD test workflow with selected objective
                TestAutofocusWorkflow.runStandard(testOutputPath, currentObj);

                // Update status after launching test
                Platform.runLater(() -> {
                    statusLabel.setText("Standard autofocus test launched - check for results dialog");
                    statusLabel.setStyle("-fx-text-fill: blue; -fx-font-weight: bold;");
                });

            } catch (NumberFormatException ex) {
                Dialogs.showErrorMessage("Input Error", "Please enter valid numeric values before testing.");
            } catch (IOException ex) {
                logger.error("Failed to save autofocus settings before test", ex);
                Dialogs.showErrorMessage("Save Error", "Failed to save settings before test: " + ex.getMessage());
            } catch (Exception ex) {
                logger.error("Failed to start standard autofocus test", ex);
                Dialogs.showErrorMessage("Test Error", "Failed to start standard autofocus test: " + ex.getMessage());
            }
        });

        // "Test Sweep Drift Check" button - will be placed inside sweep section
        Button testSweepButton = new Button("Test Sweep Drift Check");
        testSweepButton.setOnAction(e -> {
            try {
                // First, save current UI state to working settings
                saveCurrentSettings.run();

                // Validate current settings
                String currentObj = objectiveCombo.getValue();
                AutofocusSettings currentSettings = workingSettings.get(currentObj);

                if (currentSettings != null) {
                    List<String> warnings = currentSettings.validate();
                    if (!warnings.isEmpty()) {
                        Dialogs.showWarningNotification(
                                "Validation Warnings",
                                "Current settings have warnings:\n" + String.join("\n", warnings));
                    }
                }

                // Save to file first so test uses current settings
                saveAutofocusSettings(autofocusFile, workingSettings);
                statusLabel.setText("Settings saved - running sweep drift check test...");
                statusLabel.setStyle("-fx-text-fill: blue; -fx-font-weight: bold;");
                logger.info("Autofocus settings saved before sweep test");

                // Determine output path for test results (same directory as config file)
                String testOutputPath = new File(configDir, "autofocus_tests").getAbsolutePath();
                logger.info("Using autofocus test output path: {}", testOutputPath);

                // Run the SWEEP test workflow with selected objective
                TestAutofocusWorkflow.runSweep(testOutputPath, currentObj);

                // Update status after launching test
                Platform.runLater(() -> {
                    statusLabel.setText("Sweep drift check test launched - check for results dialog");
                    statusLabel.setStyle("-fx-text-fill: blue; -fx-font-weight: bold;");
                });

            } catch (NumberFormatException ex) {
                Dialogs.showErrorMessage("Input Error", "Please enter valid numeric values before testing.");
            } catch (IOException ex) {
                logger.error("Failed to save autofocus settings before test", ex);
                Dialogs.showErrorMessage("Save Error", "Failed to save settings before test: " + ex.getMessage());
            } catch (Exception ex) {
                logger.error("Failed to start sweep drift check test", ex);
                Dialogs.showErrorMessage("Test Error", "Failed to start sweep drift check test: " + ex.getMessage());
            }
        });

        // Create Standard Autofocus TitledPane with test button inside
        VBox standardContent = new VBox(8);
        standardContent.getChildren().addAll(standardGrid, testStandardButton);
        TitledPane standardPane = new TitledPane("Standard Autofocus (Symmetric Z-Sweep)", standardContent);
        standardPane.setCollapsible(false);

        // Create Sweep Drift Check TitledPane with test button inside
        VBox sweepContent = new VBox(8);
        sweepContent.getChildren().addAll(sweepGrid, testSweepButton);
        TitledPane sweepPane = new TitledPane("Sweep Drift Check (In-Acquisition Focus Correction)", sweepContent);
        sweepPane.setCollapsible(false);

        // "Validate Autofocus" button -- runs both sweep + recovery test
        Button validateButton = new Button("Validate Autofocus Settings");
        validateButton.setTooltip(new Tooltip("Test your autofocus settings on the current tissue.\n\n"
                + "1. Manually focus on tissue using the Live Viewer first\n"
                + "2. Click this button to run a two-phase validation:\n"
                + "   - Phase 1: Sweep drift check from your focused position\n"
                + "   - Phase 2: Defocus 80% of search range, then full recovery\n"
                + "3. Results show how close each phase returns to your manual focus"));
        validateButton.setStyle("-fx-font-weight: bold;");
        validateButton.setOnAction(e -> {
            try {
                saveCurrentSettings.run();
                saveAutofocusSettings(autofocusFile, workingSettings);
                statusLabel.setText("Settings saved - running autofocus validation...");
                statusLabel.setStyle("-fx-text-fill: blue; -fx-font-weight: bold;");

                String currentObj = objectiveCombo.getValue();
                String testOutputPath = new File(configDir, "autofocus_tests").getAbsolutePath();

                // Run in background to avoid blocking UI
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        // Validate connection
                        if (!qupath.ext.qpsc.controller.MicroscopeController.getInstance()
                                .isConnected()) {
                            Platform.runLater(() -> Dialogs.showErrorMessage(
                                    "Connection Required", "Please connect to the microscope server first."));
                            return;
                        }

                        var socketClient = qupath.ext.qpsc.controller.MicroscopeController.getInstance()
                                .getSocketClient();
                        var result = socketClient.testAutofocusValidation(configPath, testOutputPath, currentObj);

                        Platform.runLater(() -> showValidationResult(result, statusLabel));

                    } catch (Exception ex) {
                        logger.error("Autofocus validation failed", ex);
                        Platform.runLater(() -> {
                            statusLabel.setText("Validation failed: " + ex.getMessage());
                            statusLabel.setStyle("-fx-text-fill: red; -fx-font-weight: bold;");
                            Dialogs.showErrorMessage(
                                    "Autofocus Validation Failed",
                                    "Error: " + ex.getMessage()
                                            + "\n\nMake sure you are focused on tissue before running the test.");
                        });
                    }
                });

            } catch (Exception ex) {
                Dialogs.showErrorMessage("Error", "Failed to start validation: " + ex.getMessage());
            }
        });

        // Write button row
        HBox buttonRow = new HBox(10, writeButton, validateButton);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        // Layout
        HBox objectiveRow = new HBox(10, objectiveLabel, objectiveCombo);
        objectiveRow.setAlignment(Pos.CENTER_LEFT);

        // Use ScrollPane to handle potentially tall content
        VBox sectionsBox = new VBox(10);
        sectionsBox.getChildren().addAll(acquisitionPane, tissuePane, standardPane, sweepPane);

        mainLayout
                .getChildren()
                .addAll(objectiveRow, new Separator(), sectionsBox, statusLabel, new Separator(), buttonRow);

        dialog.getDialogPane().setContent(mainLayout);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // OK button behavior - save if changed
        Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.setOnAction(e -> {
            writeButton.fire(); // Trigger save
        });

        dialog.showAndWait();
    }

    /**
     * Extract microscope name from config filename
     * E.g., "config_PPM.yml" -> "PPM"
     */
    private static String extractMicroscopeName(String configFilename) {
        // Remove extension
        String nameWithoutExt = configFilename.replaceFirst("\\.[^.]+$", "");

        // Remove "config_" prefix if present
        if (nameWithoutExt.startsWith("config_")) {
            return nameWithoutExt.substring(7);
        }

        return nameWithoutExt;
    }

    /**
     * Load list of objectives from hardware configuration section.
     */
    private static List<String> loadObjectivesFromConfig(MicroscopeConfigManager configManager) {
        List<String> objectives = new ArrayList<>();

        try {
            List<Map<String, Object>> hardwareObjectives = configManager.getHardwareObjectives();

            for (Map<String, Object> objectiveConfig : hardwareObjectives) {
                String objectiveId = (String) objectiveConfig.get("id");
                if (objectiveId != null && !objectives.contains(objectiveId)) {
                    objectives.add(objectiveId);
                }
            }
        } catch (Exception e) {
            logger.error("Error loading objectives from hardware config", e);
        }

        return objectives;
    }

    /**
     * Load autofocus settings from YAML file
     */
    private static Map<String, AutofocusSettings> loadAutofocusSettings(File autofocusFile) {
        Map<String, AutofocusSettings> settings = new LinkedHashMap<>();

        if (!autofocusFile.exists()) {
            logger.info("Autofocus config file does not exist yet: {}", autofocusFile.getAbsolutePath());
            return settings;
        }

        try {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(Files.newInputStream(autofocusFile.toPath()));

            if (data != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> afSettings = (List<Map<String, Object>>) data.get("autofocus_settings");

                if (afSettings != null) {
                    for (Map<String, Object> entry : afSettings) {
                        String objective = (String) entry.get("objective");
                        int nSteps = ((Number) entry.get("n_steps")).intValue();
                        double searchRange = ((Number) entry.get("search_range_um")).doubleValue();
                        int nTiles = ((Number) entry.get("n_tiles")).intValue();

                        // Optional parameters with defaults
                        int interpStrength = entry.containsKey("interp_strength")
                                ? ((Number) entry.get("interp_strength")).intValue()
                                : 100;
                        String interpKind =
                                entry.containsKey("interp_kind") ? (String) entry.get("interp_kind") : "quadratic";
                        String scoreMetric = entry.containsKey("score_metric")
                                ? (String) entry.get("score_metric")
                                : "laplacian_variance";
                        double textureThreshold = entry.containsKey("texture_threshold")
                                ? ((Number) entry.get("texture_threshold")).doubleValue()
                                : 0.005;
                        double tissueAreaThreshold = entry.containsKey("tissue_area_threshold")
                                ? ((Number) entry.get("tissue_area_threshold")).doubleValue()
                                : 0.2;

                        // Sweep drift check parameters with defaults
                        double sweepRangeUm = entry.containsKey("sweep_range_um")
                                ? ((Number) entry.get("sweep_range_um")).doubleValue()
                                : 10.0;
                        int sweepNSteps = entry.containsKey("sweep_n_steps")
                                ? ((Number) entry.get("sweep_n_steps")).intValue()
                                : 6;

                        // Legacy support: old adaptive_initial_step_um -> sweep_range_um
                        if (!entry.containsKey("sweep_range_um") && entry.containsKey("adaptive_initial_step_um")) {
                            sweepRangeUm = ((Number) entry.get("adaptive_initial_step_um")).doubleValue() * 2;
                        }

                        logger.info(
                                "Loaded from YAML - objective='{}', n_steps={}, search_range={}, sweep_range={}, sweep_n_steps={}",
                                objective,
                                nSteps,
                                searchRange,
                                sweepRangeUm,
                                sweepNSteps);

                        settings.put(
                                objective,
                                new AutofocusSettings(
                                        objective,
                                        nSteps,
                                        searchRange,
                                        nTiles,
                                        interpStrength,
                                        interpKind,
                                        scoreMetric,
                                        textureThreshold,
                                        tissueAreaThreshold,
                                        sweepRangeUm,
                                        sweepNSteps));
                    }
                }
            }

            logger.info("Loaded autofocus settings for {} objectives", settings.size());
        } catch (Exception e) {
            logger.error("Error loading autofocus settings from file", e);
        }

        return settings;
    }

    /**
     * Save autofocus settings to YAML file
     */
    private static void saveAutofocusSettings(File autofocusFile, Map<String, AutofocusSettings> settings)
            throws IOException {
        // Build YAML structure
        List<Map<String, Object>> afSettingsList = new ArrayList<>();

        for (AutofocusSettings setting : settings.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("objective", setting.objective);
            entry.put("n_steps", setting.nSteps);
            entry.put("search_range_um", setting.searchRangeUm);
            entry.put("n_tiles", setting.nTiles);
            entry.put("interp_strength", setting.interpStrength);
            entry.put("interp_kind", setting.interpKind);
            entry.put("score_metric", setting.scoreMetric);
            entry.put("texture_threshold", setting.textureThreshold);
            entry.put("tissue_area_threshold", setting.tissueAreaThreshold);
            entry.put("sweep_range_um", setting.sweepRangeUm);
            entry.put("sweep_n_steps", setting.sweepNSteps);
            afSettingsList.add(entry);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("autofocus_settings", afSettingsList);

        // Configure YAML dumper for clean output
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);

        Yaml yaml = new Yaml(options);

        // Write with header comment
        try (FileWriter writer = new FileWriter(autofocusFile, StandardCharsets.UTF_8)) {
            writer.write("# ========== AUTOFOCUS CONFIGURATION ==========\n");
            writer.write("# Autofocus parameters per objective\n");
            writer.write("# These settings control autofocus behavior during image acquisition\n");
            writer.write("#\n");
            writer.write("# STANDARD AUTOFOCUS (initial focus on first tissue position):\n");
            writer.write("#   n_steps: Number of Z positions to sample (higher = more accurate but slower)\n");
            writer.write("#   search_range_um: Total Z range to search in micrometers\n");
            writer.write("#   interp_strength: Interpolation density factor (typical: 50-200)\n");
            writer.write("#   interp_kind: Interpolation method - 'linear', 'quadratic', or 'cubic'\n");
            writer.write("#\n");
            writer.write("# SWEEP DRIFT CHECK (in-acquisition focus correction):\n");
            writer.write("#   sweep_range_um: Total Z range for drift check (default 10 = +/-5um)\n");
            writer.write("#   sweep_n_steps: Number of Z positions to sample (default 6)\n");
            writer.write("#\n");
            writer.write("# SHARED:\n");
            writer.write("#   n_tiles: Autofocus runs every N tiles (lower = more frequent)\n");
            writer.write("#   score_metric: 'normalized_variance' (recommended), 'laplacian_variance',\n");
            writer.write("#                 'sobel', 'brenner_gradient', or 'p98_p2'\n");
            writer.write("#   texture_threshold: Min texture variance for tissue detection (0.005-0.030)\n");
            writer.write("#   tissue_area_threshold: Min tissue coverage fraction (0.05-0.30)\n\n");

            yaml.dump(root, writer);
        }

        logger.info(
                "Saved autofocus settings for {} objectives to: {}", settings.size(), autofocusFile.getAbsolutePath());
    }

    /**
     * Shows the autofocus validation result dialog.
     */
    private static void showValidationResult(Map<String, String> result, Label statusLabel) {
        String sweepDelta = result.getOrDefault("sweep_delta_um", "?");
        String recoveryDelta = result.getOrDefault("recovery_delta_um", "?");
        String groundTruth = result.getOrDefault("ground_truth_z", "?");
        String sweepZ = result.getOrDefault("sweep_z", "?");
        String recoveryZ = result.getOrDefault("recovery_z", "?");
        String defocusDist = result.getOrDefault("defocus_distance_um", "?");

        boolean sweepOk = false;
        boolean recoveryOk = false;
        try {
            sweepOk = Double.parseDouble(sweepDelta) < 5.0;
        } catch (NumberFormatException ignored) {
        }
        try {
            recoveryOk = !"FAILED".equals(recoveryDelta) && Double.parseDouble(recoveryDelta) < 5.0;
        } catch (NumberFormatException ignored) {
        }

        String sweepStatus = sweepOk ? "[PASS]" : "[WARN]";
        String recoveryStatus = recoveryOk ? "[PASS]" : ("FAILED".equals(recoveryDelta) ? "[FAIL]" : "[WARN]");

        boolean allPass = sweepOk && recoveryOk;

        StringBuilder sb = new StringBuilder();
        sb.append("Autofocus Validation Results\n");
        sb.append("===========================\n\n");
        sb.append(String.format("Manual focus (ground truth): Z = %s um\n\n", groundTruth));
        sb.append(String.format(
                "Phase 1 - Sweep Drift Check %s\n" + "  Sweep found: Z = %s um (delta: %s um)\n\n",
                sweepStatus, sweepZ, sweepDelta));
        sb.append(String.format(
                "Phase 2 - Recovery from %s um defocus %s\n" + "  Autofocus recovered: Z = %s um (delta: %s um)\n\n",
                defocusDist, recoveryStatus, recoveryZ, recoveryDelta));

        if (allPass) {
            sb.append("Your autofocus settings are working well for this tissue.");
        } else {
            sb.append("SUGGESTIONS:\n");
            if (!sweepOk) {
                sb.append("  - Sweep drift check was inaccurate. Try a different score_metric\n");
                sb.append("    or increase sweep_n_steps.\n");
            }
            if (!recoveryOk) {
                sb.append("  - Full autofocus did not recover well. Try increasing n_steps\n");
                sb.append("    or search_range_um, or try a different score_metric.\n");
            }
        }

        Alert alert = new Alert(allPass ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING);
        alert.setTitle("Autofocus Validation");
        alert.setHeaderText(allPass ? "Settings validated successfully" : "Settings may need adjustment");

        TextArea textArea = new TextArea(sb.toString());
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        textArea.setPrefHeight(300);
        alert.getDialogPane().setContent(textArea);
        alert.getDialogPane().setMinWidth(500);
        alert.showAndWait();

        statusLabel.setText(allPass ? "Validation PASSED" : "Validation: check results");
        statusLabel.setStyle(
                allPass
                        ? "-fx-text-fill: green; -fx-font-weight: bold;"
                        : "-fx-text-fill: orange; -fx-font-weight: bold;");
    }
}
