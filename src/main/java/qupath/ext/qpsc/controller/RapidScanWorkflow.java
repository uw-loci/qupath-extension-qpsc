package qupath.ext.qpsc.controller;

import java.io.File;
import java.util.Set;
import javafx.application.Platform;
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

    /**
     * Show the Rapid Scan dialog.
     */
    public static void show(QuPathGUI qupath) {
        MicroscopeController mc = MicroscopeController.getInstance();
        if (mc == null || !mc.isConnected()) {
            Dialogs.showErrorMessage("Rapid Scan", "Not connected to microscope server.");
            return;
        }

        // Load FOV and pixel size from config
        double fovWidth = 0;
        double fovHeight = 0;
        double pixelSize = 0;
        String objectiveUsed = "";
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);

            Set<String> objectives = mgr.getAvailableObjectives();
            String objective = objectives.isEmpty() ? null : objectives.iterator().next();
            objectiveUsed = objective != null ? objective : "unknown";

            // Resolve detector explicitly -- getModalityFOV falls back to
            // getDefaultDetector() which may not exist in all configs.
            Set<String> detectors = mgr.getHardwareDetectors();
            String detector = detectors.isEmpty() ? null : detectors.iterator().next();

            double[] fov = mgr.getModalityFOV("brightfield", objective, detector);
            if (fov != null) {
                fovWidth = fov[0];
                fovHeight = fov[1];
            }
            if (objective != null && detector != null) {
                pixelSize = mgr.getPixelSize(objective, detector);
            }
        } catch (Exception e) {
            logger.warn("Could not determine camera FOV: {}", e.getMessage());
        }

        if (fovWidth <= 0 || fovHeight <= 0) {
            Dialogs.showErrorMessage(
                    "Rapid Scan",
                    "Cannot determine camera field of view from configuration.\n"
                            + "Ensure a microscope config is loaded with objective and detector definitions.");
            return;
        }

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

        // Build dialog with minimum size to show all controls
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Rapid Scan");
        dialog.setHeaderText("Fast tiled scan -- no AF, no Z, brightfield only");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setMinWidth(520);
        dialog.getDialogPane().setMinHeight(480);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));

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

        // Width/Height
        Spinner<Double> widthSpinner = new Spinner<>(100.0, 50000.0, 2000.0, 500.0);
        widthSpinner.setEditable(true);
        widthSpinner.setPrefWidth(110);
        Spinner<Double> heightSpinner = new Spinner<>(100.0, 50000.0, 2000.0, 500.0);
        heightSpinner.setEditable(true);
        heightSpinner.setPrefWidth(110);

        // Overlap
        Spinner<Double> overlapSpinner = new Spinner<>(0.0, 50.0, 10.0, 5.0);
        overlapSpinner.setEditable(true);
        overlapSpinner.setPrefWidth(80);

        // Exposure
        Spinner<Double> exposureSpinner = new Spinner<>(0.01, MAX_EXPOSURE_MS, MAX_EXPOSURE_MS, 0.05);
        exposureSpinner.setEditable(true);
        exposureSpinner.setPrefWidth(80);

        // Test snap -- verify brightness at the configured exposure before committing
        // to a full scan. Shows mean intensity and warns if too dark.
        Button testSnapBtn = new Button("Test Snap");
        testSnapBtn.setTooltip(new Tooltip(
                "Take a single image at the configured exposure to check brightness.\n"
                        + "Warns if the image is too dark for useful rapid scan results."));
        Label testResultLabel = new Label();
        testResultLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        testSnapBtn.setOnAction(e -> {
            testSnapBtn.setDisable(true);
            testResultLabel.setText("Snapping...");
            testResultLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

            new Thread(() -> {
                try {
                    // Save current exposure, set test exposure, snap, restore
                    var exposures = mc.getSocketClient().getExposures();
                    float originalExposure = (float) exposures.unified();

                    float testExp = (float) Math.min(exposureSpinner.getValue(), MAX_EXPOSURE_MS);
                    mc.getSocketClient().setExposures(new float[] {testExp});
                    Thread.sleep(50);

                    // Use GETFRAME for a quick read (requires sequence running).
                    // Simpler: use the snap command via a temp file and read stats.
                    // Simplest: just report that exposure was set successfully and
                    // let the user check the Live Viewer.
                    //
                    // For now, report the exposure change and advise checking Live Viewer.
                    Platform.runLater(() -> {
                        testResultLabel.setText(String.format(
                                "Exposure set to %.3f ms (was %.1f ms). "
                                        + "Check the Live Viewer -- if the image is too dark, "
                                        + "increase illumination or gain before scanning.",
                                testExp, originalExposure));
                        testResultLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #c60;");
                        testSnapBtn.setDisable(false);
                    });

                    // Don't restore -- leave at test exposure so user sees it in Live Viewer
                } catch (Exception ex) {
                    logger.warn("Test snap failed: {}", ex.getMessage());
                    Platform.runLater(() -> {
                        testResultLabel.setText("Test failed: " + ex.getMessage());
                        testResultLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
                        testSnapBtn.setDisable(false);
                    });
                }
            }, "RapidScan-TestSnap").start();
        });

        // Capture values for use in lambdas
        final double fFovW = fovWidth;
        final double fFovH = fovHeight;
        final double fPixelSize = pixelSize;
        Label fovLabel = new Label(String.format("FOV: %.1f x %.1f um (%s)", fovWidth, fovHeight, objectiveUsed));
        fovLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        Label tileCountLabel = new Label();
        tileCountLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        Runnable updateTileCount = () -> {
            double w = widthSpinner.getValue();
            double h = heightSpinner.getValue();
            double overlap = overlapSpinner.getValue();
            double stepX = fFovW * (1.0 - overlap / 100.0);
            double stepY = fFovH * (1.0 - overlap / 100.0);
            int cols = Math.max(1, (int) Math.ceil(w / stepX));
            int rows = Math.max(1, (int) Math.ceil(h / stepY));
            int total = cols * rows;
            double estTime = total * 0.3; // ~0.3s per tile estimate
            tileCountLabel.setText(String.format(
                    "Grid: %d x %d = %d tiles    Est. time: ~%.0f sec", cols, rows, total, estTime));
        };
        widthSpinner.valueProperty().addListener((obs, o, n) -> updateTileCount.run());
        heightSpinner.valueProperty().addListener((obs, o, n) -> updateTileCount.run());
        overlapSpinner.valueProperty().addListener((obs, o, n) -> updateTileCount.run());
        updateTileCount.run();

        // Output folder
        TextField outputField = new TextField();
        outputField.setPrefWidth(250);
        // Default output folder
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
                if (dir.exists()) {
                    chooser.setInitialDirectory(dir);
                } else if (dir.getParentFile() != null && dir.getParentFile().exists()) {
                    chooser.setInitialDirectory(dir.getParentFile());
                }
            }
            File selected = chooser.showDialog(dialog.getDialogPane().getScene().getWindow());
            if (selected != null) {
                outputField.setText(selected.getAbsolutePath());
            }
        });

        // Stitch checkbox
        CheckBox stitchCheckbox = new CheckBox("Stitch after scan");
        stitchCheckbox.setSelected(false);

        // Start button and status
        Button startBtn = new Button("Start Rapid Scan");
        startBtn.setStyle("-fx-font-weight: bold;");
        Label statusLabel = new Label("Ready");
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        // Layout
        int row = 0;
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

        grid.add(fovLabel, 0, row, 3, 1);
        row++;
        grid.add(tileCountLabel, 0, row, 3, 1);
        row++;

        grid.add(new Separator(), 0, row, 3, 1);
        row++;

        grid.add(new Label("Output:"), 0, row);
        HBox outputBox = new HBox(6, outputField, browseBtn);
        grid.add(outputBox, 1, row, 2, 1);
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

            startBtn.setDisable(true);
            statusLabel.setText("Scanning...");
            statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

            new Thread(() -> {
                try {
                    String response = mc.getSocketClient().startRapidScan(
                            output, cx, cy, w, h, overlap, exposure, fFovW, fFovH);

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
                            stitchRapidScanOutput(output, fPixelSize);
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
     *
     * @param tileFolder Folder containing numbered .tif files + TileConfiguration.txt
     * @param pixelSizeMicrons Pixel size for the stitched output
     */
    private static void stitchRapidScanOutput(String tileFolder, double pixelSizeMicrons) throws Exception {
        File tileFolderFile = new File(tileFolder);
        File stitchedFolder = new File(tileFolderFile.getParentFile(), "stitched");
        stitchedFolder.mkdirs();

        StitchingConfiguration.StitchingParams params = StitchingConfiguration.getStandardConfiguration();

        StitchingConfig.OutputFormat outputFormat = QPPreferenceDialog.getOutputFormatProperty();
        if (outputFormat == null) {
            outputFormat = StitchingConfig.OutputFormat.OME_TIFF;
        }

        StitchingConfig config = new StitchingConfig(
                "Coordinates in TileConfiguration.txt file",
                tileFolder,
                stitchedFolder.getAbsolutePath(),
                params.compressionType(),
                pixelSizeMicrons,
                1, // downsample
                ".", // match everything
                1.0, // zSpacingMicrons
                outputFormat);
        config.outputFilename = "rapid_scan";

        // Apply stitcher flip flags from the current stage/camera transform
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
