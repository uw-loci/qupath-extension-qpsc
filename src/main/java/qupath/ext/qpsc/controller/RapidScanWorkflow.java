package qupath.ext.qpsc.controller;

import java.io.File;
import java.util.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.StageImageTransform;
import qupath.ext.qpsc.utilities.StitchingConfiguration;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

/**
 * Rapid Scan utility -- fast tiled brightfield acquisition.
 *
 * <p>Traces a serpentine path through a user-defined rectangle with no
 * autofocus, no Z movement, and exposure capped at 0.5ms. Tiles are saved
 * with a TileConfiguration.txt for optional stitching afterward.
 *
 * <p>This is a demonstration of streaming XY acquisition concepts.
 * Future versions may use true continuous motion with circular-buffer
 * frame grabbing.
 */
public class RapidScanWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(RapidScanWorkflow.class);
    private static final double MAX_EXPOSURE_MS = 0.5;

    /** Mutable holder for FOV/pixel size that updates when the objective changes. */
    private static class FovState {
        double fovW;
        double fovH;
        double pixelSize;
    }

    /**
     * Show the Rapid Scan dialog.
     */
    public static void show(QuPathGUI qupath) {
        MicroscopeController mc = MicroscopeController.getInstance();
        if (mc == null || !mc.isConnected()) {
            Dialogs.showErrorMessage("Rapid Scan", "Not connected to microscope server.");
            return;
        }

        // Load config for objective/detector/FOV lookup
        MicroscopeConfigManager mgr;
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            mgr = MicroscopeConfigManager.getInstance(configPath);
        } catch (Exception e) {
            Dialogs.showErrorMessage("Rapid Scan", "Cannot load microscope config: " + e.getMessage());
            return;
        }

        Set<String> objectives = mgr.getAvailableObjectives();
        if (objectives.isEmpty()) {
            Dialogs.showErrorMessage("Rapid Scan", "No objectives found in microscope configuration.");
            return;
        }
        Set<String> detectors = mgr.getHardwareDetectors();
        String detector = detectors.isEmpty() ? null : detectors.iterator().next();

        // Build objective display names
        Map<String, String> friendlyNames = mgr.getObjectiveFriendlyNames(objectives);

        // Get current stage position for defaults
        double currentX = 0;
        double currentY = 0;
        try {
            double[] pos = mc.getSocketClient().getStageXYZ();
            currentX = pos[0];
            currentY = pos[1];
        } catch (Exception e) {
            logger.debug("Could not get current position: {}", e.getMessage());
        }

        // Mutable state for FOV -- updated when objective selection changes
        FovState fovState = new FovState();

        // Build dialog
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Rapid Scan");
        dialog.setHeaderText("Fast tiled scan -- no AF, no Z, brightfield only");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setMinWidth(540);
        dialog.getDialogPane().setMinHeight(520);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));

        // Objective selector
        List<String> objectiveIds = new ArrayList<>(objectives);
        ComboBox<String> objectiveCombo = new ComboBox<>(FXCollections.observableArrayList(objectiveIds));
        objectiveCombo.setPrefWidth(220);
        objectiveCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : friendlyNames.getOrDefault(item, item));
            }
        });
        objectiveCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : friendlyNames.getOrDefault(item, item));
            }
        });

        // FOV and tile count labels
        Label fovLabel = new Label();
        fovLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");
        Label tileCountLabel = new Label();
        tileCountLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        // Center X/Y
        TextField centerXField = new TextField(String.format("%.1f", currentX));
        centerXField.setPrefWidth(120);
        TextField centerYField = new TextField(String.format("%.1f", currentY));
        centerYField.setPrefWidth(120);
        Button usePositionBtn = new Button("Use Current Position");
        usePositionBtn.setOnAction(e -> {
            try {
                double[] pos = mc.getSocketClient().getStageXYZ();
                centerXField.setText(String.format("%.1f", pos[0]));
                centerYField.setText(String.format("%.1f", pos[1]));
            } catch (Exception ex) {
                Dialogs.showErrorMessage("Rapid Scan", "Failed to read stage position: " + ex.getMessage());
            }
        });

        // Width/Height/Overlap/Exposure
        Spinner<Double> widthSpinner = new Spinner<>(100.0, 50000.0, 2000.0, 500.0);
        widthSpinner.setEditable(true);
        widthSpinner.setPrefWidth(110);
        Spinner<Double> heightSpinner = new Spinner<>(100.0, 50000.0, 2000.0, 500.0);
        heightSpinner.setEditable(true);
        heightSpinner.setPrefWidth(110);
        Spinner<Double> overlapSpinner = new Spinner<>(0.0, 50.0, 10.0, 5.0);
        overlapSpinner.setEditable(true);
        overlapSpinner.setPrefWidth(80);
        Spinner<Double> exposureSpinner = new Spinner<>(0.01, MAX_EXPOSURE_MS, MAX_EXPOSURE_MS, 0.05);
        exposureSpinner.setEditable(true);
        exposureSpinner.setPrefWidth(80);

        // Update FOV + tile count when objective or scan params change
        final String fDetector = detector;
        Runnable updateFovAndTiles = () -> {
            String selectedObj = objectiveCombo.getValue();
            if (selectedObj == null) return;

            double[] fov = mgr.getModalityFOV("brightfield", selectedObj, fDetector);
            if (fov != null) {
                fovState.fovW = fov[0];
                fovState.fovH = fov[1];
                try {
                    fovState.pixelSize = mgr.getPixelSize(selectedObj, fDetector);
                } catch (Exception ignored) {
                    fovState.pixelSize = 0;
                }
                String name = friendlyNames.getOrDefault(selectedObj, selectedObj);
                fovLabel.setText(String.format("FOV: %.1f x %.1f um  |  Pixel size: %.3f um  (%s)",
                        fov[0], fov[1], fovState.pixelSize, name));
            } else {
                fovLabel.setText("FOV: unknown for " + selectedObj);
                fovState.fovW = 0;
                fovState.fovH = 0;
            }

            // Update tile count
            if (fovState.fovW > 0 && fovState.fovH > 0) {
                double w = widthSpinner.getValue();
                double h = heightSpinner.getValue();
                double overlap = overlapSpinner.getValue();
                double stepX = fovState.fovW * (1.0 - overlap / 100.0);
                double stepY = fovState.fovH * (1.0 - overlap / 100.0);
                int cols = Math.max(1, (int) Math.ceil(w / stepX));
                int rows = Math.max(1, (int) Math.ceil(h / stepY));
                int total = cols * rows;
                double estTime = total * 0.3;
                tileCountLabel.setText(String.format(
                        "Grid: %d x %d = %d tiles    Est. time: ~%.0f sec", cols, rows, total, estTime));
            }
        };

        objectiveCombo.setOnAction(e -> updateFovAndTiles.run());
        widthSpinner.valueProperty().addListener((obs, o, n) -> updateFovAndTiles.run());
        heightSpinner.valueProperty().addListener((obs, o, n) -> updateFovAndTiles.run());
        overlapSpinner.valueProperty().addListener((obs, o, n) -> updateFovAndTiles.run());

        // Select first objective and trigger initial FOV computation
        objectiveCombo.getSelectionModel().selectFirst();
        updateFovAndTiles.run();

        // Test snap button
        Button testSnapBtn = new Button("Test Snap");
        testSnapBtn.setTooltip(new Tooltip(
                "Set camera to the configured exposure so you can check\n"
                        + "brightness in the Live Viewer before scanning."));
        Label testResultLabel = new Label();
        testResultLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");
        testSnapBtn.setOnAction(e -> {
            testSnapBtn.setDisable(true);
            testResultLabel.setText("Setting exposure...");
            new Thread(() -> {
                try {
                    var exposures = mc.getSocketClient().getExposures();
                    float orig = (float) exposures.unified();
                    float testExp = (float) Math.min(exposureSpinner.getValue(), MAX_EXPOSURE_MS);
                    mc.getSocketClient().setExposures(new float[] {testExp});
                    Platform.runLater(() -> {
                        testResultLabel.setText(String.format(
                                "Exposure set to %.3f ms (was %.1f ms). Check Live Viewer brightness.",
                                testExp, orig));
                        testResultLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #c60;");
                        testSnapBtn.setDisable(false);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        testResultLabel.setText("Failed: " + ex.getMessage());
                        testResultLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
                        testSnapBtn.setDisable(false);
                    });
                }
            }, "RapidScan-TestSnap").start();
        });

        // Output folder
        TextField outputField = new TextField();
        outputField.setPrefWidth(250);
        String projectsFolder = QPPreferenceDialog.getProjectsFolderProperty();
        if (projectsFolder != null && !projectsFolder.isEmpty()) {
            outputField.setText(projectsFolder + File.separator + "rapid_scans");
        }
        Button browseBtn = new Button("Browse...");
        browseBtn.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select Output Folder");
            String current = outputField.getText().trim();
            if (!current.isEmpty()) {
                File dir = new File(current);
                if (dir.exists()) chooser.setInitialDirectory(dir);
                else if (dir.getParentFile() != null && dir.getParentFile().exists())
                    chooser.setInitialDirectory(dir.getParentFile());
            }
            File selected = chooser.showDialog(dialog.getDialogPane().getScene().getWindow());
            if (selected != null) outputField.setText(selected.getAbsolutePath());
        });

        CheckBox stitchCheckbox = new CheckBox("Stitch after scan");

        Button startBtn = new Button("Start Rapid Scan");
        startBtn.setStyle("-fx-font-weight: bold;");
        Label statusLabel = new Label("Ready");
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        // Layout
        int row = 0;
        grid.add(new Label("Objective:"), 0, row);
        grid.add(objectiveCombo, 1, row, 2, 1);
        row++;

        grid.add(fovLabel, 0, row, 3, 1);
        row++;
        grid.add(tileCountLabel, 0, row, 3, 1);
        row++;

        grid.add(new Separator(), 0, row, 3, 1);
        row++;

        grid.add(new Label("Center X (um):"), 0, row);
        grid.add(centerXField, 1, row);
        grid.add(usePositionBtn, 2, row);
        row++;
        grid.add(new Label("Center Y (um):"), 0, row);
        grid.add(centerYField, 1, row);
        row++;

        grid.add(new Separator(), 0, row, 3, 1);
        row++;

        grid.add(new Label("Width (um):"), 0, row);
        grid.add(widthSpinner, 1, row);
        row++;
        grid.add(new Label("Height (um):"), 0, row);
        grid.add(heightSpinner, 1, row);
        row++;
        grid.add(new Label("Overlap (%):"), 0, row);
        grid.add(overlapSpinner, 1, row);
        row++;
        grid.add(new Label("Exposure (ms):"), 0, row);
        grid.add(exposureSpinner, 1, row);
        grid.add(new Label("max " + MAX_EXPOSURE_MS + " ms"), 2, row);
        row++;

        grid.add(testSnapBtn, 1, row);
        row++;
        grid.add(testResultLabel, 0, row, 3, 1);
        row++;

        grid.add(new Separator(), 0, row, 3, 1);
        row++;

        grid.add(new Label("Output:"), 0, row);
        grid.add(new HBox(6, outputField, browseBtn), 1, row, 2, 1);
        row++;
        grid.add(stitchCheckbox, 1, row, 2, 1);
        row++;

        grid.add(new Separator(), 0, row, 3, 1);
        row++;
        grid.add(startBtn, 0, row, 2, 1);
        row++;
        grid.add(statusLabel, 0, row, 3, 1);

        // Start action
        startBtn.setOnAction(e -> {
            if (fovState.fovW <= 0 || fovState.fovH <= 0) {
                statusLabel.setText("Cannot determine FOV for selected objective.");
                statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: red;");
                return;
            }
            String output = outputField.getText().trim();
            if (output.isEmpty()) {
                statusLabel.setText("Please specify an output folder.");
                statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: red;");
                return;
            }
            double cx, cy;
            try {
                cx = Double.parseDouble(centerXField.getText().trim());
                cy = Double.parseDouble(centerYField.getText().trim());
            } catch (NumberFormatException ex) {
                statusLabel.setText("Invalid center coordinates.");
                statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: red;");
                return;
            }

            double w = widthSpinner.getValue();
            double h = heightSpinner.getValue();
            double overlap = overlapSpinner.getValue();
            double exposure = Math.min(exposureSpinner.getValue(), MAX_EXPOSURE_MS);
            boolean doStitch = stitchCheckbox.isSelected();
            double pxSize = fovState.pixelSize;
            double fovW = fovState.fovW;
            double fovH = fovState.fovH;

            startBtn.setDisable(true);
            statusLabel.setText("Scanning...");
            statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

            new Thread(() -> {
                try {
                    String response = mc.getSocketClient().startRapidScan(
                            output, cx, cy, w, h, overlap, exposure, fovW, fovH);

                    Platform.runLater(() -> {
                        statusLabel.setText("Scan complete: " + response);
                        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: green;");
                    });

                    if (doStitch) {
                        Platform.runLater(() -> {
                            statusLabel.setText("Stitching...");
                            statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
                        });
                        try {
                            stitchRapidScanOutput(output, pxSize);
                            Platform.runLater(() -> {
                                statusLabel.setText("Scan + stitch complete");
                                statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: green;");
                            });
                        } catch (Exception stitchEx) {
                            logger.error("Stitching failed", stitchEx);
                            Platform.runLater(() -> {
                                statusLabel.setText("Scan OK, stitch failed: " + stitchEx.getMessage());
                                statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: orange;");
                            });
                        }
                    }
                } catch (Exception ex) {
                    logger.error("Rapid scan failed", ex);
                    Platform.runLater(() -> {
                        statusLabel.setText("Failed: " + ex.getMessage());
                        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: red;");
                    });
                } finally {
                    Platform.runLater(() -> startBtn.setDisable(false));
                }
            }, "RapidScan-Acquire").start();
        });

        dialog.getDialogPane().setContent(grid);
        dialog.setResizable(true);
        dialog.showAndWait();
    }

    /**
     * Stitch rapid scan tiles using the same pipeline as normal acquisitions.
     */
    private static void stitchRapidScanOutput(String tileFolder, double pixelSizeMicrons) throws Exception {
        File tileFolderFile = new File(tileFolder);
        File stitchedFolder = new File(tileFolderFile.getParentFile(), "stitched");
        stitchedFolder.mkdirs();

        StitchingConfiguration.StitchingParams params = StitchingConfiguration.getStandardConfiguration();
        StitchingConfig.OutputFormat outputFormat = QPPreferenceDialog.getOutputFormatProperty();
        if (outputFormat == null) outputFormat = StitchingConfig.OutputFormat.OME_TIFF;

        StitchingConfig config = new StitchingConfig(
                "Coordinates in TileConfiguration.txt file",
                tileFolder,
                stitchedFolder.getAbsolutePath(),
                params.compressionType(),
                pixelSizeMicrons,
                1, ".", 1.0, outputFormat);
        config.outputFilename = "rapid_scan";

        StageImageTransform siTransform = StageImageTransform.current();
        boolean[] stitcherFlags = siTransform.stitcherFlipFlags();
        qupath.ext.basicstitching.stitching.TileConfigurationTxtStrategy.flipStitchingX = stitcherFlags[0];
        qupath.ext.basicstitching.stitching.TileConfigurationTxtStrategy.flipStitchingY = stitcherFlags[1];
        logger.info("Rapid scan stitching: flipX={}, flipY={} (from {})",
                stitcherFlags[0], stitcherFlags[1], siTransform);
        try {
            String outPath = StitchingWorkflow.run(config);
            logger.info("Rapid scan stitched output: {}", outPath);
        } finally {
            qupath.ext.basicstitching.stitching.TileConfigurationTxtStrategy.flipStitchingX = false;
            qupath.ext.basicstitching.stitching.TileConfigurationTxtStrategy.flipStitchingY = false;
        }
    }
}
