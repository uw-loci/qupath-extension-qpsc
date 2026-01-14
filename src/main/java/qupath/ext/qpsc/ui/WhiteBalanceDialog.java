package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.prefs.PathPrefs;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/**
 * Dialog for configuring white balance calibration for the JAI camera.
 *
 * <p>This dialog provides two modes of white balance calibration:
 * <ul>
 *   <li><b>Simple White Balance</b>: Calibrates at a single exposure at the current angle</li>
 *   <li><b>PPM White Balance</b>: Calibrates at 4 standard PPM angles with different exposures</li>
 * </ul>
 *
 * <p>The dialog follows the same pattern as AutofocusBenchmarkDialog with
 * TitledPane sections for organizing the settings.
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
            PathPrefs.createPersistentPreference("wb.tolerance", 5.0);
    private static final StringProperty outputPathProperty =
            PathPrefs.createPersistentPreference("wb.outputPath", "");
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

    // Fixed PPM angles (standard values)
    public static final double POSITIVE_ANGLE = 7.0;
    public static final double NEGATIVE_ANGLE = -7.0;
    public static final double CROSSED_ANGLE = 0.0;
    public static final double UNCROSSED_ANGLE = 90.0;

    /**
     * Result record for Simple White Balance configuration.
     */
    public record SimpleWBParams(
            String outputPath,
            String camera,
            double baseExposureMs,
            double targetIntensity,
            double tolerance
    ) {}

    /**
     * Result record for PPM White Balance configuration.
     */
    public record PPMWBParams(
            String outputPath,
            String camera,
            double positiveAngle, double positiveExposureMs,
            double negativeAngle, double negativeExposureMs,
            double crossedAngle, double crossedExposureMs,
            double uncrossedAngle, double uncrossedExposureMs,
            double targetIntensity,
            double tolerance
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
                dialog.initModality(Modality.APPLICATION_MODAL);
                dialog.setTitle("White Balance Calibration");
                dialog.setHeaderText("Configure JAI camera white balance calibration");
                dialog.setResizable(true);

                // Main content container
                VBox content = new VBox(15);
                content.setPadding(new Insets(20));
                content.setPrefWidth(650);

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

                // Add all sections
                content.getChildren().addAll(
                        cameraPane,
                        sharedPane,
                        simplePane,
                        ppmPane
                );

                // ========== DIALOG BUTTONS ==========
                ButtonType runSimpleButton = new ButtonType("Run Simple WB", ButtonBar.ButtonData.OK_DONE);
                ButtonType runPPMButton = new ButtonType("Run PPM WB", ButtonBar.ButtonData.APPLY);
                ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

                dialog.getDialogPane().getButtonTypes().addAll(runSimpleButton, runPPMButton, cancelButton);
                dialog.getDialogPane().setContent(content);

                // Get references to UI elements for result conversion
                // Camera dropdown
                ComboBox<?> cameraCombo = (ComboBox<?>) cameraPane.getContent().lookup("#cameraCombo");
                // Shared settings
                TextField outputField = (TextField) sharedPane.getContent().lookup("#outputPath");
                Spinner<?> targetSpinner = (Spinner<?>) sharedPane.getContent().lookup("#targetIntensity");
                Spinner<?> toleranceSpinner = (Spinner<?>) sharedPane.getContent().lookup("#tolerance");
                // Simple WB
                Spinner<?> simpleExpSpinner = (Spinner<?>) simplePane.getContent().lookup("#simpleExposure");
                // PPM WB
                Spinner<?> posExpSpinner = (Spinner<?>) ppmPane.getContent().lookup("#positiveExposure");
                Spinner<?> negExpSpinner = (Spinner<?>) ppmPane.getContent().lookup("#negativeExposure");
                Spinner<?> crossExpSpinner = (Spinner<?>) ppmPane.getContent().lookup("#crossedExposure");
                Spinner<?> uncrossExpSpinner = (Spinner<?>) ppmPane.getContent().lookup("#uncrossedExposure");

                // Validation for PPM button
                Button ppmBtn = (Button) dialog.getDialogPane().lookupButton(runPPMButton);
                Runnable validatePPM = () -> {
                    boolean valid = outputField.getText() != null && !outputField.getText().isEmpty();
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

                    // Save shared preferences
                    outputPathProperty.set(outPath);
                    targetIntensityProperty.set(target);
                    toleranceProperty.set(tolerance);
                    cameraProperty.set(camera);

                    if (buttonType == runSimpleButton) {
                        double exposure = (Double) simpleExpSpinner.getValue();
                        simpleExposureProperty.set(exposure);

                        logger.info("User selected Simple White Balance:");
                        logger.info("  Output: {}", outPath);
                        logger.info("  Base exposure: {} ms", exposure);
                        logger.info("  Target: {}, Tolerance: {}", target, tolerance);

                        return WBDialogResult.simple(new SimpleWBParams(
                                outPath, camera, exposure, target, tolerance
                        ));

                    } else if (buttonType == runPPMButton) {
                        double posExp = (Double) posExpSpinner.getValue();
                        double negExp = (Double) negExpSpinner.getValue();
                        double crossExp = (Double) crossExpSpinner.getValue();
                        double uncrossExp = (Double) uncrossExpSpinner.getValue();

                        // Save PPM preferences
                        ppmPositiveExpProperty.set(posExp);
                        ppmNegativeExpProperty.set(negExp);
                        ppmCrossedExpProperty.set(crossExp);
                        ppmUncrossedExpProperty.set(uncrossExp);

                        logger.info("User selected PPM White Balance:");
                        logger.info("  Output: {}", outPath);
                        logger.info("  Positive ({}deg): {} ms", POSITIVE_ANGLE, posExp);
                        logger.info("  Negative ({}deg): {} ms", NEGATIVE_ANGLE, negExp);
                        logger.info("  Crossed ({}deg): {} ms", CROSSED_ANGLE, crossExp);
                        logger.info("  Uncrossed ({}deg): {} ms", UNCROSSED_ANGLE, uncrossExp);
                        logger.info("  Target: {}, Tolerance: {}", target, tolerance);

                        return WBDialogResult.ppm(new PPMWBParams(
                                outPath, camera,
                                POSITIVE_ANGLE, posExp,
                                NEGATIVE_ANGLE, negExp,
                                CROSSED_ANGLE, crossExp,
                                UNCROSSED_ANGLE, uncrossExp,
                                target, tolerance
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

        // Output folder
        Label outputLabel = new Label("Output Folder:");

        TextField outputField = new TextField();
        outputField.setId("outputPath");
        outputField.setPrefWidth(300);
        outputField.setText(outputPathProperty.get());
        outputField.setPromptText("Select output folder for calibration files");

        Button browseButton = new Button("Browse...");
        browseButton.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select Output Folder for White Balance Calibration");

            String currentPath = outputField.getText();
            if (currentPath != null && !currentPath.isEmpty()) {
                File currentDir = new File(currentPath);
                if (currentDir.exists()) {
                    chooser.setInitialDirectory(currentDir);
                }
            }

            File selectedDir = chooser.showDialog(outputField.getScene().getWindow());
            if (selectedDir != null) {
                outputField.setText(selectedDir.getAbsolutePath());
            }
        });

        HBox outputBox = new HBox(5, outputField, browseButton);
        outputBox.setAlignment(Pos.CENTER_LEFT);

        grid.add(outputLabel, 0, row);
        grid.add(outputBox, 1, row, 2, 1);

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
                "Simple white balance calibrates at a single exposure at the current PPM angle.\n" +
                "Use this for quick calibration or for non-PPM imaging."
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
     * Creates the PPM White Balance pane with 4 angle/exposure pairs.
     */
    private static TitledPane createPPMWBPane() {
        VBox vbox = new VBox(10);
        vbox.setPadding(new Insets(10));

        // Description/instruction
        Label descLabel = new Label(
                "PPM white balance calibrates at each of the 4 standard PPM angles.\n" +
                "Run 'Collect Background Images' first (without white balance) to determine\n" +
                "appropriate exposure times at each angle, then enter those values below."
        );
        descLabel.setWrapText(true);
        descLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        // Grid for angle/exposure pairs
        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(8);

        // Header row
        Label angleHeader = new Label("Angle");
        angleHeader.setStyle("-fx-font-weight: bold;");
        Label expHeader = new Label("Exposure (ms)");
        expHeader.setStyle("-fx-font-weight: bold;");

        grid.add(angleHeader, 0, 0);
        grid.add(expHeader, 1, 0);

        // Positive (7.0 deg)
        Label posLabel = new Label(String.format("Positive (%.1f deg):", POSITIVE_ANGLE));
        posLabel.setPrefWidth(150);
        double posDefault = Double.isNaN(ppmPositiveExpProperty.get()) ? 0.0 : ppmPositiveExpProperty.get();
        Spinner<Double> posSpinner = new Spinner<>(0.0, 500.0, posDefault, 1.0);
        posSpinner.setId("positiveExposure");
        posSpinner.setEditable(true);
        posSpinner.setPrefWidth(100);

        grid.add(posLabel, 0, 1);
        grid.add(posSpinner, 1, 1);

        // Negative (-7.0 deg)
        Label negLabel = new Label(String.format("Negative (%.1f deg):", NEGATIVE_ANGLE));
        double negDefault = Double.isNaN(ppmNegativeExpProperty.get()) ? 0.0 : ppmNegativeExpProperty.get();
        Spinner<Double> negSpinner = new Spinner<>(0.0, 500.0, negDefault, 1.0);
        negSpinner.setId("negativeExposure");
        negSpinner.setEditable(true);
        negSpinner.setPrefWidth(100);

        grid.add(negLabel, 0, 2);
        grid.add(negSpinner, 1, 2);

        // Crossed (0.0 deg)
        Label crossLabel = new Label(String.format("Crossed (%.1f deg):", CROSSED_ANGLE));
        double crossDefault = Double.isNaN(ppmCrossedExpProperty.get()) ? 0.0 : ppmCrossedExpProperty.get();
        Spinner<Double> crossSpinner = new Spinner<>(0.0, 500.0, crossDefault, 1.0);
        crossSpinner.setId("crossedExposure");
        crossSpinner.setEditable(true);
        crossSpinner.setPrefWidth(100);

        grid.add(crossLabel, 0, 3);
        grid.add(crossSpinner, 1, 3);

        // Uncrossed (90.0 deg)
        Label uncrossLabel = new Label(String.format("Uncrossed (%.1f deg):", UNCROSSED_ANGLE));
        double uncrossDefault = Double.isNaN(ppmUncrossedExpProperty.get()) ? 0.0 : ppmUncrossedExpProperty.get();
        Spinner<Double> uncrossSpinner = new Spinner<>(0.0, 500.0, uncrossDefault, 1.0);
        uncrossSpinner.setId("uncrossedExposure");
        uncrossSpinner.setEditable(true);
        uncrossSpinner.setPrefWidth(100);

        grid.add(uncrossLabel, 0, 4);
        grid.add(uncrossSpinner, 1, 4);

        // Note about fixed angles
        Label noteLabel = new Label("(Angles are fixed at standard PPM values)");
        noteLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        vbox.getChildren().addAll(descLabel, grid, noteLabel);

        TitledPane pane = new TitledPane("PPM White Balance (4 Angles)", vbox);
        pane.setCollapsible(true);
        return pane;
    }
}
