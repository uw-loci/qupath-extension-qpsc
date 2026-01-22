package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.lib.gui.prefs.PathPrefs;

import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Dialog for configuring white balance calibration for the JAI camera.
 *
 * <p>This dialog provides two modes of white balance calibration:
 * <ul>
 *   <li><b>Simple White Balance</b>: Standard method - calibrates once and applies the same
 *       correction to all PPM angles. This has been the default approach.</li>
 *   <li><b>PPM White Balance</b>: Experimental per-angle calibration at each of the 4 standard
 *       PPM angles. Requires updated JAI camera DLL with per-channel exposure support.</li>
 * </ul>
 *
 * <p>The dialog is non-modal to allow interaction with QuPath while it remains open.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class WhiteBalanceDialog {
    private static final Logger logger = LoggerFactory.getLogger(WhiteBalanceDialog.class);

    // Persistent preferences for white balance settings
    private static final DoubleProperty targetIntensityProperty =
            PathPrefs.createPersistentPreference("wb.targetIntensity", 180.0);
    private static final DoubleProperty toleranceProperty =
            PathPrefs.createPersistentPreference("wb.tolerance", 2.0);
    // White balance output subfolder name under config directory
    private static final String WB_SUBFOLDER = "white_balance_calibration";
    private static final StringProperty cameraProperty =
            PathPrefs.createPersistentPreference("wb.camera", "JAI AP-3200T-USB");

    // Simple WB preferences
    private static final DoubleProperty simpleExposureProperty =
            PathPrefs.createPersistentPreference("wb.simple.exposure", 20.0);

    // PPM WB preferences (no defaults for exposures - user must determine)
    private static final DoubleProperty ppmPositiveExpProperty =
            PathPrefs.createPersistentPreference("wb.ppm.positive.exposure", Double.NaN);
    private static final DoubleProperty ppmNegativeExpProperty =
            PathPrefs.createPersistentPreference("wb.ppm.negative.exposure", Double.NaN);
    private static final DoubleProperty ppmCrossedExpProperty =
            PathPrefs.createPersistentPreference("wb.ppm.crossed.exposure", Double.NaN);
    private static final DoubleProperty ppmUncrossedExpProperty =
            PathPrefs.createPersistentPreference("wb.ppm.uncrossed.exposure", Double.NaN);

    // PPM WB target intensity preferences per angle
    // Defaults based on optical properties of polarized light
    private static final DoubleProperty ppmPositiveTargetProperty =
            PathPrefs.createPersistentPreference("wb.ppm.positive.target", 160.0);
    private static final DoubleProperty ppmNegativeTargetProperty =
            PathPrefs.createPersistentPreference("wb.ppm.negative.target", 160.0);
    private static final DoubleProperty ppmCrossedTargetProperty =
            PathPrefs.createPersistentPreference("wb.ppm.crossed.target", 125.0);
    private static final DoubleProperty ppmUncrossedTargetProperty =
            PathPrefs.createPersistentPreference("wb.ppm.uncrossed.target", 245.0);

    // Advanced calibration settings
    // Max analog gain in dB (JAI supports 0-36.13 dB, but keep low to minimize noise)
    // 3 dB = 1.41x linear gain, 6 dB = 2x, 12 dB = 4x
    private static final DoubleProperty maxGainDbProperty =
            PathPrefs.createPersistentPreference("wb.advanced.maxGainDb", 3.0);
    // Exposure ratio threshold before applying gain compensation
    private static final DoubleProperty gainThresholdRatioProperty =
            PathPrefs.createPersistentPreference("wb.advanced.gainThresholdRatio", 2.0);
    // Maximum calibration iterations before giving up
    private static final IntegerProperty maxIterationsProperty =
            PathPrefs.createPersistentPreference("wb.advanced.maxIterations", 30);
    // Whether to perform black level calibration
    private static final BooleanProperty calibrateBlackLevelProperty =
            PathPrefs.createPersistentPreference("wb.advanced.calibrateBlackLevel", true);

    // Objective selection for PPM WB (objective-specific exposures)
    private static final StringProperty ppmObjectiveProperty =
            PathPrefs.createPersistentPreference("wb.ppm.objective", "");

    // Fixed PPM angles (standard values)
    public static final double POSITIVE_ANGLE = 7.0;
    public static final double NEGATIVE_ANGLE = -7.0;
    public static final double CROSSED_ANGLE = 0.0;
    public static final double UNCROSSED_ANGLE = 90.0;

    /**
     * Result record for advanced calibration settings (shared by both modes).
     */
    public record AdvancedWBParams(
            double maxGainDb,           // Max analog gain in dB (default 3.0)
            double gainThresholdRatio,  // Exposure ratio before using gain (default 2.0)
            int maxIterations,          // Max calibration iterations (default 30)
            boolean calibrateBlackLevel // Whether to calibrate black level (default true)
    ) {}

    /**
     * Result record for Simple White Balance configuration.
     */
    public record SimpleWBParams(
            String outputPath,
            String camera,
            double baseExposureMs,
            double targetIntensity,
            double tolerance,
            AdvancedWBParams advanced
    ) {}

    /**
     * Result record for PPM White Balance configuration.
     */
    public record PPMWBParams(
            String outputPath,
            String camera,
            String objective,
            String detector,
            double positiveAngle, double positiveExposureMs, double positiveTarget,
            double negativeAngle, double negativeExposureMs, double negativeTarget,
            double crossedAngle, double crossedExposureMs, double crossedTarget,
            double uncrossedAngle, double uncrossedExposureMs, double uncrossedTarget,
            double targetIntensity,  // Default fallback target (for backward compatibility)
            double tolerance,
            AdvancedWBParams advanced
    ) {}

    /**
     * Result wrapper that contains either Simple or PPM parameters.
     */
    public static class WBDialogResult {
        private final SimpleWBParams simpleParams;
        private final PPMWBParams ppmParams;

        private WBDialogResult(SimpleWBParams simpleParams, PPMWBParams ppmParams) {
            this.simpleParams = simpleParams;
            this.ppmParams = ppmParams;
        }

        public static WBDialogResult simple(SimpleWBParams params) {
            return new WBDialogResult(params, null);
        }

        public static WBDialogResult ppm(PPMWBParams params) {
            return new WBDialogResult(null, params);
        }

        public boolean isSimple() {
            return simpleParams != null;
        }

        public boolean isPPM() {
            return ppmParams != null;
        }

        public SimpleWBParams getSimpleParams() {
            return simpleParams;
        }

        public PPMWBParams getPPMParams() {
            return ppmParams;
        }
    }

    /**
     * Shows the white balance calibration dialog.
     *
     * @return CompletableFuture with configured parameters, or null if cancelled
     */
    public static CompletableFuture<WBDialogResult> showDialog() {
        CompletableFuture<WBDialogResult> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                Dialog<WBDialogResult> dialog = new Dialog<>();
                // Non-modal so user can interact with QuPath while dialog is open
                dialog.setTitle("White Balance Calibration");
                dialog.setHeaderText("Configure JAI camera white balance calibration");
                dialog.setResizable(true);

                // Main content container
                VBox content = new VBox(15);
                content.setPadding(new Insets(20));
                content.setPrefWidth(650);

                // Wrap content in ScrollPane for long content
                ScrollPane scrollPane = new ScrollPane(content);
                scrollPane.setFitToWidth(true);
                scrollPane.setPrefViewportHeight(500);
                scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
                scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

                // ========== CAMERA SELECTION SECTION ==========
                TitledPane cameraPane = createCameraSectionPane();
                cameraPane.setExpanded(true);

                // ========== SHARED SETTINGS SECTION ==========
                TitledPane sharedPane = createSharedSettingsPane();
                sharedPane.setExpanded(true);

                // ========== SIMPLE WHITE BALANCE SECTION ==========
                TitledPane simplePane = createSimpleWBPane();
                simplePane.setExpanded(true);

                // ========== PPM WHITE BALANCE SECTION ==========
                TitledPane ppmPane = createPPMWBPane();
                ppmPane.setExpanded(false);

                // ========== ADVANCED SETTINGS SECTION ==========
                TitledPane advancedPane = createAdvancedSettingsPane();
                advancedPane.setExpanded(false);

                // Add all sections
                content.getChildren().addAll(
                        cameraPane,
                        sharedPane,
                        simplePane,
                        ppmPane,
                        advancedPane
                );

                // ========== DIALOG BUTTONS ==========
                ButtonType runSimpleButton = new ButtonType("Run Simple WB", ButtonBar.ButtonData.OK_DONE);
                ButtonType runPPMButton = new ButtonType("Run PPM WB", ButtonBar.ButtonData.APPLY);
                ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

                dialog.getDialogPane().getButtonTypes().addAll(runSimpleButton, runPPMButton, cancelButton);
                dialog.getDialogPane().setContent(scrollPane);

                // Get references to UI elements for result conversion
                // Camera dropdown
                ComboBox<?> cameraCombo = (ComboBox<?>) cameraPane.getContent().lookup("#cameraCombo");
                // Shared settings
                TextField outputField = (TextField) sharedPane.getContent().lookup("#outputPath");
                Spinner<?> targetSpinner = (Spinner<?>) sharedPane.getContent().lookup("#targetIntensity");
                Spinner<?> toleranceSpinner = (Spinner<?>) sharedPane.getContent().lookup("#tolerance");
                // Simple WB
                Spinner<?> simpleExpSpinner = (Spinner<?>) simplePane.getContent().lookup("#simpleExposure");
                // PPM WB - exposures
                Spinner<?> posExpSpinner = (Spinner<?>) ppmPane.getContent().lookup("#positiveExposure");
                Spinner<?> negExpSpinner = (Spinner<?>) ppmPane.getContent().lookup("#negativeExposure");
                Spinner<?> crossExpSpinner = (Spinner<?>) ppmPane.getContent().lookup("#crossedExposure");
                Spinner<?> uncrossExpSpinner = (Spinner<?>) ppmPane.getContent().lookup("#uncrossedExposure");
                // PPM WB - objective
                ComboBox<?> ppmObjectiveCombo = (ComboBox<?>) ppmPane.getContent().lookup("#ppmObjective");
                // PPM WB - per-angle targets
                Spinner<?> posTargetSpinner = (Spinner<?>) ppmPane.getContent().lookup("#positiveTarget");
                Spinner<?> negTargetSpinner = (Spinner<?>) ppmPane.getContent().lookup("#negativeTarget");
                Spinner<?> crossTargetSpinner = (Spinner<?>) ppmPane.getContent().lookup("#crossedTarget");
                Spinner<?> uncrossTargetSpinner = (Spinner<?>) ppmPane.getContent().lookup("#uncrossedTarget");
                // Advanced settings
                Spinner<?> maxGainSpinner = (Spinner<?>) advancedPane.getContent().lookup("#maxGainDb");
                Spinner<?> gainThresholdSpinner = (Spinner<?>) advancedPane.getContent().lookup("#gainThresholdRatio");
                Spinner<?> maxIterSpinner = (Spinner<?>) advancedPane.getContent().lookup("#maxIterations");
                CheckBox blackLevelCheck = (CheckBox) advancedPane.getContent().lookup("#calibrateBlackLevel");

                // Validation for PPM button
                Button ppmBtn = (Button) dialog.getDialogPane().lookupButton(runPPMButton);
                Runnable validatePPM = () -> {
                    boolean valid = outputField.getText() != null && !outputField.getText().isEmpty();
                    // Check that objective is selected
                    valid = valid && ppmObjectiveCombo.getValue() != null;
                    // Check that all PPM exposures are set
                    valid = valid && posExpSpinner.getValue() != null && ((Double) posExpSpinner.getValue()) > 0;
                    valid = valid && negExpSpinner.getValue() != null && ((Double) negExpSpinner.getValue()) > 0;
                    valid = valid && crossExpSpinner.getValue() != null && ((Double) crossExpSpinner.getValue()) > 0;
                    valid = valid && uncrossExpSpinner.getValue() != null && ((Double) uncrossExpSpinner.getValue()) > 0;
                    ppmBtn.setDisable(!valid);
                };

                // Validation for Simple button
                Button simpleBtn = (Button) dialog.getDialogPane().lookupButton(runSimpleButton);
                Runnable validateSimple = () -> {
                    boolean valid = outputField.getText() != null && !outputField.getText().isEmpty();
                    valid = valid && simpleExpSpinner.getValue() != null && ((Double) simpleExpSpinner.getValue()) > 0;
                    simpleBtn.setDisable(!valid);
                };

                // Set up validation listeners
                outputField.textProperty().addListener((obs, o, n) -> { validateSimple.run(); validatePPM.run(); });
                simpleExpSpinner.valueProperty().addListener((obs, o, n) -> validateSimple.run());
                ppmObjectiveCombo.valueProperty().addListener((obs, o, n) -> validatePPM.run());
                posExpSpinner.valueProperty().addListener((obs, o, n) -> validatePPM.run());
                negExpSpinner.valueProperty().addListener((obs, o, n) -> validatePPM.run());
                crossExpSpinner.valueProperty().addListener((obs, o, n) -> validatePPM.run());
                uncrossExpSpinner.valueProperty().addListener((obs, o, n) -> validatePPM.run());

                // Initial validation
                validateSimple.run();
                validatePPM.run();

                // Convert result
                dialog.setResultConverter(buttonType -> {
                    String camera = cameraCombo.getValue() != null ? cameraCombo.getValue().toString() : "JAI AP-3200T-USB";
                    String outPath = outputField.getText();
                    double target = (Double) targetSpinner.getValue();
                    double tolerance = (Double) toleranceSpinner.getValue();

                    // Get advanced settings
                    double maxGain = (Double) maxGainSpinner.getValue();
                    double gainThreshold = (Double) gainThresholdSpinner.getValue();
                    int maxIter = (Integer) maxIterSpinner.getValue();
                    boolean calibrateBL = blackLevelCheck.isSelected();

                    // Save shared preferences (output path is auto-derived, not saved)
                    targetIntensityProperty.set(target);
                    toleranceProperty.set(tolerance);
                    cameraProperty.set(camera);

                    // Save advanced preferences
                    maxGainDbProperty.set(maxGain);
                    gainThresholdRatioProperty.set(gainThreshold);
                    maxIterationsProperty.set(maxIter);
                    calibrateBlackLevelProperty.set(calibrateBL);

                    AdvancedWBParams advanced = new AdvancedWBParams(
                            maxGain, gainThreshold, maxIter, calibrateBL
                    );

                    if (buttonType == runSimpleButton) {
                        double exposure = (Double) simpleExpSpinner.getValue();
                        simpleExposureProperty.set(exposure);

                        logger.info("User selected Simple White Balance:");
                        logger.info("  Output: {}", outPath);
                        logger.info("  Base exposure: {} ms", exposure);
                        logger.info("  Target: {}, Tolerance: {}", target, tolerance);
                        logger.info("  Advanced: maxGain={}dB, gainThreshold={}, maxIter={}, calibrateBL={}",
                                maxGain, gainThreshold, maxIter, calibrateBL);

                        return WBDialogResult.simple(new SimpleWBParams(
                                outPath, camera, exposure, target, tolerance, advanced
                        ));

                    } else if (buttonType == runPPMButton) {
                        double posExp = (Double) posExpSpinner.getValue();
                        double negExp = (Double) negExpSpinner.getValue();
                        double crossExp = (Double) crossExpSpinner.getValue();
                        double uncrossExp = (Double) uncrossExpSpinner.getValue();

                        // Get per-angle targets
                        double posTarget = (Double) posTargetSpinner.getValue();
                        double negTarget = (Double) negTargetSpinner.getValue();
                        double crossTarget = (Double) crossTargetSpinner.getValue();
                        double uncrossTarget = (Double) uncrossTargetSpinner.getValue();

                        // Get objective and derive detector
                        String selectedObjective = ppmObjectiveCombo.getValue() != null ?
                                ppmObjectiveCombo.getValue().toString() : null;
                        String selectedDetector = null;
                        if (selectedObjective != null) {
                            try {
                                String cfgPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                                if (cfgPath != null && !cfgPath.isEmpty()) {
                                    MicroscopeConfigManager cfgMgr = MicroscopeConfigManager.getInstance(cfgPath);
                                    Set<String> detectors = cfgMgr.getAvailableDetectorsForModalityObjective("ppm", selectedObjective);
                                    if (!detectors.isEmpty()) {
                                        selectedDetector = detectors.iterator().next();
                                    }
                                }
                            } catch (Exception e) {
                                logger.warn("Could not determine detector for objective {}: {}",
                                        selectedObjective, e.getMessage());
                            }
                        }

                        // Save PPM preferences - exposures
                        ppmPositiveExpProperty.set(posExp);
                        ppmNegativeExpProperty.set(negExp);
                        ppmCrossedExpProperty.set(crossExp);
                        ppmUncrossedExpProperty.set(uncrossExp);

                        // Save PPM preferences - targets
                        ppmPositiveTargetProperty.set(posTarget);
                        ppmNegativeTargetProperty.set(negTarget);
                        ppmCrossedTargetProperty.set(crossTarget);
                        ppmUncrossedTargetProperty.set(uncrossTarget);

                        // Save objective preference
                        if (selectedObjective != null) {
                            ppmObjectiveProperty.set(selectedObjective);
                        }

                        logger.info("User selected PPM White Balance:");
                        logger.info("  Objective: {}, Detector: {}", selectedObjective, selectedDetector);
                        logger.info("  Output: {}", outPath);
                        logger.info("  Positive ({}deg): {} ms, target={}", POSITIVE_ANGLE, posExp, posTarget);
                        logger.info("  Negative ({}deg): {} ms, target={}", NEGATIVE_ANGLE, negExp, negTarget);
                        logger.info("  Crossed ({}deg): {} ms, target={}", CROSSED_ANGLE, crossExp, crossTarget);
                        logger.info("  Uncrossed ({}deg): {} ms, target={}", UNCROSSED_ANGLE, uncrossExp, uncrossTarget);
                        logger.info("  Default target: {}, Tolerance: {}", target, tolerance);
                        logger.info("  Advanced: maxGain={}dB, gainThreshold={}, maxIter={}, calibrateBL={}",
                                maxGain, gainThreshold, maxIter, calibrateBL);

                        return WBDialogResult.ppm(new PPMWBParams(
                                outPath, camera, selectedObjective, selectedDetector,
                                POSITIVE_ANGLE, posExp, posTarget,
                                NEGATIVE_ANGLE, negExp, negTarget,
                                CROSSED_ANGLE, crossExp, crossTarget,
                                UNCROSSED_ANGLE, uncrossExp, uncrossTarget,
                                target, tolerance, advanced
                        ));
                    }

                    return null;
                });

                // Show dialog and complete future
                dialog.showAndWait().ifPresentOrElse(
                        future::complete,
                        () -> {
                            logger.info("White balance dialog cancelled");
                            future.complete(null);
                        }
                );

            } catch (Exception e) {
                logger.error("Error showing white balance dialog", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Creates the camera selection pane.
     */
    private static TitledPane createCameraSectionPane() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        Label cameraLabel = new Label("Camera:");
        cameraLabel.setPrefWidth(120);

        ComboBox<String> cameraCombo = new ComboBox<>();
        cameraCombo.setId("cameraCombo");
        cameraCombo.setPrefWidth(300);
        cameraCombo.getItems().add("JAI AP-3200T-USB");
        cameraCombo.setValue(cameraProperty.get());
        cameraCombo.setTooltip(new Tooltip("Select the camera for white balance calibration"));

        Label cameraNote = new Label("(Currently only JAI camera supported)");
        cameraNote.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        grid.add(cameraLabel, 0, 0);
        grid.add(cameraCombo, 1, 0);
        grid.add(cameraNote, 1, 1);

        TitledPane pane = new TitledPane("Camera Selection", grid);
        pane.setCollapsible(true);
        return pane;
    }

    /**
     * Creates the shared settings pane (target intensity, tolerance, output folder).
     */
    private static TitledPane createSharedSettingsPane() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        int row = 0;

        // Target intensity
        Label targetLabel = new Label("Target Intensity:");
        targetLabel.setPrefWidth(120);

        Spinner<Double> targetSpinner = new Spinner<>(0.0, 255.0, targetIntensityProperty.get(), 1.0);
        targetSpinner.setId("targetIntensity");
        targetSpinner.setEditable(true);
        targetSpinner.setPrefWidth(100);
        targetSpinner.setTooltip(new Tooltip("Target mean intensity for all channels (0-255)"));

        Label targetNote = new Label("(0-255, typically 180 for 70% reflectance)");
        targetNote.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        grid.add(targetLabel, 0, row);
        grid.add(targetSpinner, 1, row);
        grid.add(targetNote, 2, row);
        row++;

        // Tolerance
        Label toleranceLabel = new Label("Tolerance:");
        Spinner<Double> toleranceSpinner = new Spinner<>(0.1, 50.0, toleranceProperty.get(), 0.5);
        toleranceSpinner.setId("tolerance");
        toleranceSpinner.setEditable(true);
        toleranceSpinner.setPrefWidth(100);
        toleranceSpinner.setTooltip(new Tooltip("Acceptable deviation from target intensity"));

        Label toleranceNote = new Label("(channels within tolerance are considered balanced)");
        toleranceNote.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        grid.add(toleranceLabel, 0, row);
        grid.add(toleranceSpinner, 1, row);
        grid.add(toleranceNote, 2, row);
        row++;

        // Output folder - default to config file directory if no saved preference
        Label outputLabel = new Label("Output Folder:");

        TextField outputField = new TextField();
        outputField.setId("outputPath");
        outputField.setPrefWidth(300);
        outputField.setEditable(false); // Read-only - always derived from config location

        // Always derive output path from current config file location
        String wbOutputPath = "";
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (configPath != null && !configPath.isEmpty()) {
                Path configDir = Paths.get(configPath).getParent();
                if (configDir != null) {
                    // Create white_balance_calibration subfolder path
                    wbOutputPath = configDir.resolve(WB_SUBFOLDER).toString();
                }
            }
        } catch (Exception e) {
            logger.warn("Could not get output path from config location: {}", e.getMessage());
        }
        outputField.setText(wbOutputPath);
        outputField.setPromptText("Output folder (derived from config location)");

        // Output is read-only (derived from config location)
        // Add a note explaining this
        Label outputNote = new Label("(auto: config folder/" + WB_SUBFOLDER + ")");
        outputNote.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        grid.add(outputLabel, 0, row);
        grid.add(outputField, 1, row);
        grid.add(outputNote, 2, row);

        TitledPane pane = new TitledPane("Shared Settings", grid);
        pane.setCollapsible(true);
        return pane;
    }

    /**
     * Creates the Simple White Balance pane.
     */
    private static TitledPane createSimpleWBPane() {
        VBox vbox = new VBox(10);
        vbox.setPadding(new Insets(10));

        // Description
        Label descLabel = new Label(
                "Standard white balance method - calibrates once and applies the same correction\n" +
                "to all PPM angles. This has been the default approach and works well for most cases."
        );
        descLabel.setWrapText(true);
        descLabel.setStyle("-fx-font-size: 11px;");

        // Base exposure
        HBox expBox = new HBox(10);
        expBox.setAlignment(Pos.CENTER_LEFT);

        Label expLabel = new Label("Base Exposure (ms):");
        expLabel.setPrefWidth(140);

        double defaultExp = Double.isNaN(simpleExposureProperty.get()) ? 20.0 : simpleExposureProperty.get();
        Spinner<Double> expSpinner = new Spinner<>(0.1, 500.0, defaultExp, 1.0);
        expSpinner.setId("simpleExposure");
        expSpinner.setEditable(true);
        expSpinner.setPrefWidth(100);
        expSpinner.setTooltip(new Tooltip("Starting exposure for all channels"));

        expBox.getChildren().addAll(expLabel, expSpinner);

        vbox.getChildren().addAll(descLabel, expBox);

        TitledPane pane = new TitledPane("Simple White Balance", vbox);
        pane.setCollapsible(true);
        return pane;
    }

    /**
     * Creates the PPM White Balance pane with 4 angle/exposure/target triplets.
     */
    private static TitledPane createPPMWBPane() {
        VBox vbox = new VBox(10);
        vbox.setPadding(new Insets(10));

        // Description/instruction
        Label descLabel = new Label(
                "Per-angle white balance - calibrates separately at each of the 4 standard PPM angles.\n" +
                "Target intensities are pre-set based on optical properties (crossed is dim, uncrossed is bright).\n" +
                "Run 'Collect Background Images' first to determine exposure times at each angle."
        );
        descLabel.setWrapText(true);
        descLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        // Objective selection - calibration results are objective-specific
        HBox objectiveBox = new HBox(10);
        objectiveBox.setAlignment(Pos.CENTER_LEFT);

        Label objectiveLabel = new Label("Objective:");
        objectiveLabel.setPrefWidth(140);

        ComboBox<String> objectiveCombo = new ComboBox<>();
        objectiveCombo.setId("ppmObjective");
        objectiveCombo.setPrefWidth(200);
        objectiveCombo.setTooltip(new Tooltip("Select the objective for calibration (exposures are objective-specific)"));

        // Populate objectives from config
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (configPath != null && !configPath.isEmpty()) {
                MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
                Set<String> objectives = configManager.getAvailableObjectivesForModality("ppm");
                if (!objectives.isEmpty()) {
                    objectiveCombo.getItems().addAll(objectives.stream().sorted().toList());
                    // Restore previous selection or select first
                    String savedObjective = ppmObjectiveProperty.get();
                    if (savedObjective != null && !savedObjective.isEmpty() && objectives.contains(savedObjective)) {
                        objectiveCombo.setValue(savedObjective);
                    } else {
                        objectiveCombo.getSelectionModel().selectFirst();
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Could not load objectives for PPM WB dialog: {}", e.getMessage());
        }

        Label objectiveNote = new Label("(calibration is objective-specific)");
        objectiveNote.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        objectiveBox.getChildren().addAll(objectiveLabel, objectiveCombo, objectiveNote);

        // Grid for angle/exposure/target triplets
        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(8);

        // Header row
        Label angleHeader = new Label("Angle");
        angleHeader.setStyle("-fx-font-weight: bold;");
        Label expHeader = new Label("Exposure (ms)");
        expHeader.setStyle("-fx-font-weight: bold;");
        Label targetHeader = new Label("Target Intensity");
        targetHeader.setStyle("-fx-font-weight: bold;");

        grid.add(angleHeader, 0, 0);
        grid.add(expHeader, 1, 0);
        grid.add(targetHeader, 2, 0);

        // Positive (7.0 deg) - birefringence angle, moderate brightness
        Label posLabel = new Label(String.format("Positive (%.1f deg):", POSITIVE_ANGLE));
        posLabel.setPrefWidth(150);
        double posDefault = Double.isNaN(ppmPositiveExpProperty.get()) ? 0.0 : ppmPositiveExpProperty.get();
        Spinner<Double> posSpinner = new Spinner<>(0.0, 500.0, posDefault, 1.0);
        posSpinner.setId("positiveExposure");
        posSpinner.setEditable(true);
        posSpinner.setPrefWidth(100);
        Spinner<Double> posTargetSpinner = new Spinner<>(0.0, 255.0, ppmPositiveTargetProperty.get(), 5.0);
        posTargetSpinner.setId("positiveTarget");
        posTargetSpinner.setEditable(true);
        posTargetSpinner.setPrefWidth(80);
        posTargetSpinner.setTooltip(new Tooltip("Target intensity for birefringence angle (moderate)"));

        grid.add(posLabel, 0, 1);
        grid.add(posSpinner, 1, 1);
        grid.add(posTargetSpinner, 2, 1);

        // Negative (-7.0 deg) - birefringence angle, moderate brightness
        Label negLabel = new Label(String.format("Negative (%.1f deg):", NEGATIVE_ANGLE));
        double negDefault = Double.isNaN(ppmNegativeExpProperty.get()) ? 0.0 : ppmNegativeExpProperty.get();
        Spinner<Double> negSpinner = new Spinner<>(0.0, 500.0, negDefault, 1.0);
        negSpinner.setId("negativeExposure");
        negSpinner.setEditable(true);
        negSpinner.setPrefWidth(100);
        Spinner<Double> negTargetSpinner = new Spinner<>(0.0, 255.0, ppmNegativeTargetProperty.get(), 5.0);
        negTargetSpinner.setId("negativeTarget");
        negTargetSpinner.setEditable(true);
        negTargetSpinner.setPrefWidth(80);
        negTargetSpinner.setTooltip(new Tooltip("Target intensity for birefringence angle (moderate)"));

        grid.add(negLabel, 0, 2);
        grid.add(negSpinner, 1, 2);
        grid.add(negTargetSpinner, 2, 2);

        // Crossed (0.0 deg) - very dim due to blocked light
        Label crossLabel = new Label(String.format("Crossed (%.1f deg):", CROSSED_ANGLE));
        double crossDefault = Double.isNaN(ppmCrossedExpProperty.get()) ? 0.0 : ppmCrossedExpProperty.get();
        Spinner<Double> crossSpinner = new Spinner<>(0.0, 5000.0, crossDefault, 10.0);  // Higher max for crossed
        crossSpinner.setId("crossedExposure");
        crossSpinner.setEditable(true);
        crossSpinner.setPrefWidth(100);
        Spinner<Double> crossTargetSpinner = new Spinner<>(0.0, 255.0, ppmCrossedTargetProperty.get(), 5.0);
        crossTargetSpinner.setId("crossedTarget");
        crossTargetSpinner.setEditable(true);
        crossTargetSpinner.setPrefWidth(80);
        crossTargetSpinner.setTooltip(new Tooltip("Target intensity for crossed polarizers (dim)"));

        grid.add(crossLabel, 0, 3);
        grid.add(crossSpinner, 1, 3);
        grid.add(crossTargetSpinner, 2, 3);

        // Uncrossed (90.0 deg) - very bright
        Label uncrossLabel = new Label(String.format("Uncrossed (%.1f deg):", UNCROSSED_ANGLE));
        double uncrossDefault = Double.isNaN(ppmUncrossedExpProperty.get()) ? 0.0 : ppmUncrossedExpProperty.get();
        Spinner<Double> uncrossSpinner = new Spinner<>(0.0, 500.0, uncrossDefault, 0.1);  // Fine control for short exposure
        uncrossSpinner.setId("uncrossedExposure");
        uncrossSpinner.setEditable(true);
        uncrossSpinner.setPrefWidth(100);
        Spinner<Double> uncrossTargetSpinner = new Spinner<>(0.0, 255.0, ppmUncrossedTargetProperty.get(), 5.0);
        uncrossTargetSpinner.setId("uncrossedTarget");
        uncrossTargetSpinner.setEditable(true);
        uncrossTargetSpinner.setPrefWidth(80);
        uncrossTargetSpinner.setTooltip(new Tooltip("Target intensity for uncrossed polarizers (bright)"));

        grid.add(uncrossLabel, 0, 4);
        grid.add(uncrossSpinner, 1, 4);
        grid.add(uncrossTargetSpinner, 2, 4);

        // Note about fixed angles
        Label noteLabel = new Label(
                "(Angles are fixed at standard PPM values. Targets match optical properties.)"
        );
        noteLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        vbox.getChildren().addAll(descLabel, objectiveBox, grid, noteLabel);

        TitledPane pane = new TitledPane("PPM White Balance (4 Angles)", vbox);
        pane.setCollapsible(true);
        return pane;
    }

    /**
     * Creates the Advanced Settings pane with calibration algorithm parameters.
     */
    private static TitledPane createAdvancedSettingsPane() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        int row = 0;

        // Description
        Label descLabel = new Label(
                "Advanced calibration parameters. Default values work well for most cases."
        );
        descLabel.setWrapText(true);
        descLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        grid.add(descLabel, 0, row, 3, 1);
        row++;

        // Max Gain (dB)
        Label maxGainLabel = new Label("Max Gain (dB):");
        maxGainLabel.setPrefWidth(160);

        Spinner<Double> maxGainSpinner = new Spinner<>(0.0, 36.0, maxGainDbProperty.get(), 0.5);
        maxGainSpinner.setId("maxGainDb");
        maxGainSpinner.setEditable(true);
        maxGainSpinner.setPrefWidth(100);
        maxGainSpinner.setTooltip(new Tooltip(
                "Maximum analog gain in dB. Higher gain = more noise.\n" +
                "3 dB = 1.41x, 6 dB = 2x, 12 dB = 4x linear gain.\n" +
                "JAI camera supports 0-36 dB."
        ));

        Label maxGainNote = new Label("(3dB = 1.41x gain)");
        maxGainNote.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        grid.add(maxGainLabel, 0, row);
        grid.add(maxGainSpinner, 1, row);
        grid.add(maxGainNote, 2, row);
        row++;

        // Gain Threshold Ratio
        Label gainThresholdLabel = new Label("Gain Threshold Ratio:");

        Spinner<Double> gainThresholdSpinner = new Spinner<>(1.0, 10.0, gainThresholdRatioProperty.get(), 0.1);
        gainThresholdSpinner.setId("gainThresholdRatio");
        gainThresholdSpinner.setEditable(true);
        gainThresholdSpinner.setPrefWidth(100);
        gainThresholdSpinner.setTooltip(new Tooltip(
                "Exposure ratio between channels before applying gain compensation.\n" +
                "If brightest/darkest channel exposure ratio exceeds this, gain is used\n" +
                "to reduce exposure spread instead of relying purely on exposure."
        ));

        Label gainThresholdNote = new Label("(exposure ratio before using gain)");
        gainThresholdNote.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        grid.add(gainThresholdLabel, 0, row);
        grid.add(gainThresholdSpinner, 1, row);
        grid.add(gainThresholdNote, 2, row);
        row++;

        // Max Iterations
        Label maxIterLabel = new Label("Max Iterations:");

        Spinner<Integer> maxIterSpinner = new Spinner<>(5, 100, maxIterationsProperty.get(), 5);
        maxIterSpinner.setId("maxIterations");
        maxIterSpinner.setEditable(true);
        maxIterSpinner.setPrefWidth(100);
        maxIterSpinner.setTooltip(new Tooltip(
                "Maximum calibration iterations before giving up.\n" +
                "More iterations = better convergence but longer calibration time."
        ));

        Label maxIterNote = new Label("(iterations before giving up)");
        maxIterNote.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        grid.add(maxIterLabel, 0, row);
        grid.add(maxIterSpinner, 1, row);
        grid.add(maxIterNote, 2, row);
        row++;

        // Calibrate Black Level
        Label blackLevelLabel = new Label("Calibrate Black Level:");

        CheckBox blackLevelCheck = new CheckBox();
        blackLevelCheck.setId("calibrateBlackLevel");
        blackLevelCheck.setSelected(calibrateBlackLevelProperty.get());
        blackLevelCheck.setTooltip(new Tooltip(
                "Whether to calibrate black level before white balance.\n" +
                "Improves accuracy but adds ~10 seconds to calibration."
        ));

        Label blackLevelNote = new Label("(improves accuracy, adds time)");
        blackLevelNote.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        grid.add(blackLevelLabel, 0, row);
        grid.add(blackLevelCheck, 1, row);
        grid.add(blackLevelNote, 2, row);

        TitledPane pane = new TitledPane("Advanced Settings", grid);
        pane.setCollapsible(true);
        return pane;
    }
}
