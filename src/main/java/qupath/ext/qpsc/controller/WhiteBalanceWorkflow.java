package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.WhiteBalanceDialog;
import qupath.fx.dialogs.Dialogs;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Workflow for running JAI camera white balance calibration.
 *
 * <p>This workflow guides users through white balance calibration for the JAI camera,
 * supporting both simple (single angle) and PPM (4 angle) calibration modes.
 *
 * <p>Workflow steps:
 * <ol>
 *   <li>Connect to microscope server</li>
 *   <li>Show configuration dialog for calibration parameters</li>
 *   <li>Send calibration command (WBSIMPLE or WBPPM) to Python server</li>
 *   <li>Monitor calibration progress</li>
 *   <li>Display results summary with per-channel exposures</li>
 * </ol>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class WhiteBalanceWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(WhiteBalanceWorkflow.class);

    /**
     * Runs the complete white balance calibration workflow.
     *
     * <p>This is the main entry point called from the QuPath menu.
     * The workflow is asynchronous and will not block the UI thread.
     *
     * @throws IOException if initial server connection fails
     */
    public static void run() throws IOException {
        logger.info("Starting White Balance Calibration workflow");

        // Use the singleton's client to avoid conflicts with auto-connect
        MicroscopeSocketClient client = MicroscopeController.getInstance().getSocketClient();

        // Ensure connected
        if (!client.isConnected()) {
            try {
                client.connect();
            } catch (IOException e) {
                throw new IOException("Failed to connect to microscope server: " + e.getMessage(), e);
            }
        }

        logger.info("Using existing microscope server connection");

        // Show configuration dialog
        WhiteBalanceDialog.showDialog()
                .thenAccept(result -> {
                    if (result == null) {
                        logger.info("White balance calibration cancelled by user");
                        // Don't close client - it's the singleton's shared connection
                        return;
                    }

                    // Camera AWB is a special case - no output directory needed
                    if (result.isCameraAWB()) {
                        Platform.runLater(() -> {
                            var params = result.getCameraAWBParams();
                            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                            confirm.setTitle("Confirm Camera AWB");
                            confirm.setHeaderText("Ready to run Camera Auto White Balance");
                            confirm.setContentText(String.format(
                                    "Mode: Camera AWB (one-shot auto)\n" +
                                    "Objective: %s\n" +
                                    "Rotation: %.1f deg (uncrossed)\n\n" +
                                    "IMPORTANT: Ensure a neutral gray/white target or blank slide\n" +
                                    "is in the field of view before continuing.\n\n" +
                                    "The camera will rotate to uncrossed position,\n" +
                                    "run auto white balance, then disable AWB.\n" +
                                    "Estimated time: ~10 seconds",
                                    params.objective() != null ? params.objective() : "unknown",
                                    params.rotationAngle()
                            ));

                            confirm.showAndWait().ifPresent(response -> {
                                if (response == ButtonType.OK) {
                                    runCameraAWBCalibration(client, params);
                                } else {
                                    logger.info("Camera AWB cancelled by user at confirmation");
                                }
                            });
                        });
                        return;
                    }

                    // Validate output directory (for Simple and PPM modes)
                    String outputPath = result.isSimple() ?
                            result.getSimpleParams().outputPath() :
                            result.getPPMParams().outputPath();

                    File outputDir = new File(outputPath);
                    if (!outputDir.exists()) {
                        if (!outputDir.mkdirs()) {
                            Platform.runLater(() -> {
                                Alert alert = new Alert(Alert.AlertType.ERROR);
                                alert.setTitle("Invalid Output Directory");
                                alert.setHeaderText("Could not create output directory");
                                alert.setContentText("Failed to create:\n" + outputPath);
                                alert.showAndWait();
                            });
                            return;
                        }
                        logger.info("Created output directory: {}", outputPath);
                    }

                    // Confirm and run calibration
                    Platform.runLater(() -> {
                        String modeDesc = result.isSimple() ? "Simple" : "PPM (4 angles)";
                        String timeEstimate = result.isSimple() ? "2-3 minutes" : "10-15 minutes";

                        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                        confirm.setTitle("Confirm White Balance Calibration");
                        confirm.setHeaderText("Ready to start " + modeDesc + " white balance calibration");

                        StringBuilder details = new StringBuilder();
                        if (result.isSimple()) {
                            var params = result.getSimpleParams();
                            details.append(String.format(
                                    "Mode: Simple White Balance\n" +
                                    "Objective: %s\n" +
                                    "Base Exposure: %.1f ms\n" +
                                    "Target Intensity: %.0f\n" +
                                    "Tolerance: %.1f\n",
                                    params.objective() != null ? params.objective() : "unknown",
                                    params.baseExposureMs(),
                                    params.targetIntensity(),
                                    params.tolerance()
                            ));
                        } else {
                            var params = result.getPPMParams();
                            details.append(String.format(
                                    "Mode: PPM White Balance (4 angles)\n" +
                                    "Objective: %s\n" +
                                    "Positive (%.1f deg): %.1f ms\n" +
                                    "Negative (%.1f deg): %.1f ms\n" +
                                    "Crossed (%.1f deg): %.1f ms\n" +
                                    "Uncrossed (%.1f deg): %.1f ms\n" +
                                    "Target Intensity: %.0f\n" +
                                    "Tolerance: %.1f\n",
                                    params.objective() != null ? params.objective() : "unknown",
                                    params.positiveAngle(), params.positiveExposureMs(),
                                    params.negativeAngle(), params.negativeExposureMs(),
                                    params.crossedAngle(), params.crossedExposureMs(),
                                    params.uncrossedAngle(), params.uncrossedExposureMs(),
                                    params.targetIntensity(),
                                    params.tolerance()
                            ));
                        }

                        details.append(String.format(
                                "\nOutput: %s\n" +
                                "Estimated time: %s\n\n" +
                                "IMPORTANT: Ensure a neutral gray/white target or blank slide\n" +
                                "is in the field of view before continuing.",
                                outputPath,
                                timeEstimate
                        ));

                        confirm.setContentText(details.toString());

                        confirm.showAndWait().ifPresent(response -> {
                            if (response == ButtonType.OK) {
                                if (result.isSimple()) {
                                    runSimpleCalibration(client, result.getSimpleParams());
                                } else {
                                    runPPMCalibration(client, result.getPPMParams());
                                }
                            } else {
                                logger.info("White balance cancelled by user at confirmation");
                            }
                        });
                    });
                })
                .exceptionally(ex -> {
                    logger.error("Error in white balance workflow", ex);
                    Platform.runLater(() -> {
                        Dialogs.showErrorMessage("White Balance Error",
                                "Error during calibration: " + ex.getMessage());
                    });
                    return null;
                });
    }

    /**
     * Runs simple white balance calibration.
     */
    private static void runSimpleCalibration(MicroscopeSocketClient client,
                                             WhiteBalanceDialog.SimpleWBParams params) {
        // Show progress dialog
        Stage progressStage = new Stage();
        progressStage.initModality(Modality.NONE);
        qupath.lib.gui.QuPathGUI gui = qupath.lib.gui.QuPathGUI.getInstance();
        if (gui != null && gui.getStage() != null) {
            progressStage.initOwner(gui.getStage());
        }
        progressStage.setTitle("Simple White Balance");
        progressStage.setResizable(false);

        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        root.setAlignment(Pos.CENTER);
        root.setPrefWidth(400);

        Label statusLabel = new Label("Running white balance calibration...");
        statusLabel.setStyle("-fx-font-size: 14px;");

        ProgressIndicator progress = new ProgressIndicator();
        progress.setStyle("-fx-min-width: 50px; -fx-min-height: 50px;");

        Label detailLabel = new Label("Adjusting per-channel exposures to balance colors");
        detailLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        root.getChildren().addAll(statusLabel, progress, detailLabel);

        Scene scene = new Scene(root);
        progressStage.setScene(scene);
        progressStage.show();

        // Run calibration in background thread
        Thread calibrationThread = new Thread(() -> {
            // Stop all live viewing before starting calibration
            MicroscopeController.LiveViewState liveViewState =
                    MicroscopeController.getInstance().stopAllLiveViewing();

            try {
                var advanced = params.advanced();
                String yamlPath = qupath.ext.qpsc.preferences.QPPreferenceDialog.getMicroscopeConfigFileProperty();

                logger.info("Simple WB calibration: objective={}, detector={}", params.objective(), params.detector());

                MicroscopeSocketClient.WhiteBalanceResult result = client.runSimpleWhiteBalance(
                        params.outputPath(),
                        params.baseExposureMs(),
                        params.targetIntensity(),
                        params.tolerance(),
                        advanced.maxGainDb(),
                        advanced.gainThresholdRatio(),
                        advanced.maxIterations(),
                        advanced.calibrateBlackLevel(),
                        advanced.baseGain(),
                        advanced.exposureSoftCapMs(),
                        advanced.boostedMaxGainDb(),
                        yamlPath,
                        params.objective(),
                        params.detector()
                );

                Platform.runLater(() -> {
                    progressStage.close();
                    showSimpleResults(result, params.outputPath());
                });

            } catch (Exception e) {
                logger.error("Simple white balance failed", e);
                Platform.runLater(() -> {
                    progressStage.close();
                    Dialogs.showErrorMessage("White Balance Failed",
                            "Calibration failed: " + e.getMessage());
                });
            } finally {
                // Restore live viewing state after calibration completes
                MicroscopeController.getInstance().restoreLiveViewState(liveViewState);
            }
            // Don't close client - it's the singleton's shared connection
        }, "WhiteBalanceCalibration");
        calibrationThread.setDaemon(true);
        calibrationThread.start();
    }

    /**
     * Runs PPM white balance calibration (4 angles).
     */
    private static void runPPMCalibration(MicroscopeSocketClient client,
                                          WhiteBalanceDialog.PPMWBParams params) {
        // Show progress dialog
        Stage progressStage = new Stage();
        progressStage.initModality(Modality.NONE);
        qupath.lib.gui.QuPathGUI guiPPM = qupath.lib.gui.QuPathGUI.getInstance();
        if (guiPPM != null && guiPPM.getStage() != null) {
            progressStage.initOwner(guiPPM.getStage());
        }
        progressStage.setTitle("PPM White Balance");
        progressStage.setResizable(false);

        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        root.setAlignment(Pos.CENTER);
        root.setPrefWidth(450);

        Label statusLabel = new Label("Running PPM white balance calibration...");
        statusLabel.setStyle("-fx-font-size: 14px;");

        ProgressIndicator progress = new ProgressIndicator();
        progress.setStyle("-fx-min-width: 50px; -fx-min-height: 50px;");

        Label detailLabel = new Label("Calibrating 4 PPM angles:\n" +
                "Positive (7 deg) -> Negative (-7 deg) -> Crossed (0 deg) -> Uncrossed (90 deg)");
        detailLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        detailLabel.setWrapText(true);

        root.getChildren().addAll(statusLabel, progress, detailLabel);

        Scene scene = new Scene(root);
        progressStage.setScene(scene);
        progressStage.show();

        // Run calibration in background thread
        Thread calibrationThread = new Thread(() -> {
            // Stop all live viewing before starting calibration
            MicroscopeController.LiveViewState liveViewState =
                    MicroscopeController.getInstance().stopAllLiveViewing();

            try {
                var advanced = params.advanced();
                String yamlPath = qupath.ext.qpsc.preferences.QPPreferenceDialog.getMicroscopeConfigFileProperty();

                logger.info("WB calibration: objective={}, detector={}", params.objective(), params.detector());

                Map<String, MicroscopeSocketClient.WhiteBalanceResult> results = client.runPPMWhiteBalance(
                        params.outputPath(),
                        params.positiveAngle(), params.positiveExposureMs(), params.positiveTarget(),
                        params.negativeAngle(), params.negativeExposureMs(), params.negativeTarget(),
                        params.crossedAngle(), params.crossedExposureMs(), params.crossedTarget(),
                        params.uncrossedAngle(), params.uncrossedExposureMs(), params.uncrossedTarget(),
                        params.targetIntensity(),
                        params.tolerance(),
                        advanced.maxGainDb(),
                        advanced.gainThresholdRatio(),
                        advanced.maxIterations(),
                        advanced.calibrateBlackLevel(),
                        advanced.baseGain(),
                        advanced.exposureSoftCapMs(),
                        advanced.boostedMaxGainDb(),
                        yamlPath,
                        params.objective(),
                        params.detector()
                );

                Platform.runLater(() -> {
                    progressStage.close();
                    showPPMResults(results, params.outputPath());
                });

            } catch (Exception e) {
                logger.error("PPM white balance failed", e);
                Platform.runLater(() -> {
                    progressStage.close();
                    Dialogs.showErrorMessage("PPM White Balance Failed",
                            "Calibration failed: " + e.getMessage());
                });
            } finally {
                // Restore live viewing state after calibration completes
                MicroscopeController.getInstance().restoreLiveViewState(liveViewState);
            }
            // Don't close client - it's the singleton's shared connection
        }, "PPMWhiteBalanceCalibration");
        calibrationThread.setDaemon(true);
        calibrationThread.start();
    }

    /**
     * Runs Camera AWB calibration (one-shot auto white balance at uncrossed position).
     */
    private static void runCameraAWBCalibration(MicroscopeSocketClient client,
                                                 WhiteBalanceDialog.CameraAWBParams params) {
        // Show progress dialog
        Stage progressStage = new Stage();
        progressStage.initModality(Modality.NONE);
        qupath.lib.gui.QuPathGUI gui = qupath.lib.gui.QuPathGUI.getInstance();
        if (gui != null && gui.getStage() != null) {
            progressStage.initOwner(gui.getStage());
        }
        progressStage.setTitle("Camera AWB");
        progressStage.setResizable(false);

        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        root.setAlignment(Pos.CENTER);
        root.setPrefWidth(400);

        Label statusLabel = new Label("Running camera auto white balance...");
        statusLabel.setStyle("-fx-font-size: 14px;");

        ProgressIndicator progress = new ProgressIndicator();
        progress.setStyle("-fx-min-width: 50px; -fx-min-height: 50px;");

        Label detailLabel = new Label("Rotating to uncrossed position, then running one-shot AWB");
        detailLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        root.getChildren().addAll(statusLabel, progress, detailLabel);

        Scene scene = new Scene(root);
        progressStage.setScene(scene);
        progressStage.show();

        // Run in background thread
        Thread calibrationThread = new Thread(() -> {
            // Stop all live viewing before starting
            MicroscopeController.LiveViewState liveViewState =
                    MicroscopeController.getInstance().stopAllLiveViewing();

            try {
                // Step 1: Rotate to uncrossed position (90deg)
                logger.info("Camera AWB: Rotating to {} deg (uncrossed)",
                        params.rotationAngle());
                Platform.runLater(() -> detailLabel.setText(
                        "Step 1/3: Rotating to uncrossed position..."));
                client.moveStageR(params.rotationAngle());

                // Brief pause for rotation to settle
                Thread.sleep(1000);

                // Step 2: Run one-shot auto white balance (mode=2)
                logger.info("Camera AWB: Running one-shot auto white balance");
                Platform.runLater(() -> detailLabel.setText(
                        "Step 2/3: Running camera auto white balance..."));
                client.setWhiteBalanceMode(2);  // 2 = Once (one-shot auto)

                // Wait for camera AWB to complete
                Thread.sleep(2000);

                // Step 3: Disable AWB so it doesn't change during acquisition
                logger.info("Camera AWB: Disabling auto white balance");
                Platform.runLater(() -> detailLabel.setText(
                        "Step 3/3: Disabling auto WB (camera remembers gains)..."));
                client.setWhiteBalanceMode(0);  // 0 = Off

                logger.info("Camera AWB calibration completed successfully");

                Platform.runLater(() -> {
                    progressStage.close();

                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("Camera AWB Complete");
                    alert.setHeaderText("Camera Auto White Balance Completed");
                    alert.setContentText(
                            "Camera AWB has been applied successfully.\n\n" +
                            "The camera's internal R/B gains have been adjusted\n" +
                            "based on the current scene at uncrossed position.\n\n" +
                            "These gains are stored in the camera and will persist\n" +
                            "until the camera is power-cycled or AWB is run again.\n\n" +
                            "Note: Camera AWB gains are NOT saved to YAML config.\n" +
                            "For reproducible results, use Simple or PPM WB instead."
                    );
                    alert.initModality(Modality.NONE);
                    alert.getButtonTypes().setAll(ButtonType.CLOSE);
                    alert.show();
                });

            } catch (Exception e) {
                logger.error("Camera AWB calibration failed", e);
                Platform.runLater(() -> {
                    progressStage.close();
                    Dialogs.showErrorMessage("Camera AWB Failed",
                            "Auto white balance failed: " + e.getMessage());
                });
            } finally {
                // Restore live viewing state
                MicroscopeController.getInstance().restoreLiveViewState(liveViewState);
            }
        }, "CameraAWBCalibration");
        calibrationThread.setDaemon(true);
        calibrationThread.start();
    }

    /**
     * Shows results dialog for simple white balance.
     */
    private static void showSimpleResults(MicroscopeSocketClient.WhiteBalanceResult result,
                                          String outputPath) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("White Balance Complete");
        alert.setHeaderText(result.converged ? "Calibration Converged" : "Calibration Complete (did not fully converge)");

        // Check if any gains are not 1.0 (meaning gain was applied)
        boolean hasGain = result.gainRed != 1.0 || result.gainGreen != 1.0 || result.gainBlue != 1.0;

        StringBuilder content = new StringBuilder();
        content.append("Per-Channel Results:\n\n");
        content.append(String.format("  Red:   %.2f ms", result.exposureRed));
        if (hasGain) content.append(String.format(" @ %.3fx gain", result.gainRed));
        content.append("\n");
        content.append(String.format("  Green: %.2f ms", result.exposureGreen));
        if (hasGain) content.append(String.format(" @ %.3fx gain", result.gainGreen));
        content.append("\n");
        content.append(String.format("  Blue:  %.2f ms", result.exposureBlue));
        if (hasGain) content.append(String.format(" @ %.3fx gain", result.gainBlue));
        content.append("\n\n");
        content.append(String.format("Converged: %s", result.converged ? "Yes" : "No"));

        alert.setContentText(content.toString());

        // Non-modal so user can reference results while continuing work
        configureResultDialog(alert, outputPath);
        alert.show();
    }

    /**
     * Shows results dialog for PPM white balance.
     */
    private static void showPPMResults(Map<String, MicroscopeSocketClient.WhiteBalanceResult> results,
                                       String outputPath) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("PPM White Balance Complete");

        boolean allConverged = results.values().stream().allMatch(r -> r.converged);
        alert.setHeaderText(allConverged ?
                "All Angles Converged" :
                "Calibration Complete (some angles did not converge)");

        // Check if any gains are not 1.0
        boolean hasGain = results.values().stream()
                .anyMatch(r -> r.gainRed != 1.0 || r.gainGreen != 1.0 || r.gainBlue != 1.0);

        StringBuilder content = new StringBuilder();
        if (hasGain) {
            // Show exposures and gains in separate tables
            content.append("Exposures (ms):\n");
            content.append(String.format("%-10s %7s %7s %7s  %s\n", "Angle", "Red", "Green", "Blue", "Conv"));
            content.append("-".repeat(45)).append("\n");
        } else {
            content.append("Per-Channel Exposures (ms):\n\n");
            content.append(String.format("%-10s %7s %7s %7s  %s\n", "Angle", "Red", "Green", "Blue", "Conv"));
            content.append("-".repeat(45)).append("\n");
        }

        String[] angleOrder = {"positive", "negative", "crossed", "uncrossed"};
        for (String name : angleOrder) {
            MicroscopeSocketClient.WhiteBalanceResult r = results.get(name);
            if (r != null) {
                content.append(String.format("%-10s %7.2f %7.2f %7.2f  %s\n",
                        capitalize(name),
                        r.exposureRed,
                        r.exposureGreen,
                        r.exposureBlue,
                        r.converged ? "Yes" : "No"
                ));
            }
        }

        if (hasGain) {
            content.append("\nGains (linear):\n");
            content.append(String.format("%-10s %7s %7s %7s\n", "Angle", "Red", "Green", "Blue"));
            content.append("-".repeat(38)).append("\n");
            for (String name : angleOrder) {
                MicroscopeSocketClient.WhiteBalanceResult r = results.get(name);
                if (r != null) {
                    content.append(String.format("%-10s %7.3f %7.3f %7.3f\n",
                            capitalize(name),
                            r.gainRed,
                            r.gainGreen,
                            r.gainBlue
                    ));
                }
            }
        }

        alert.setContentText(content.toString());

        // Use a monospace font for the table
        alert.getDialogPane().lookup(".content.label").setStyle("-fx-font-family: monospace;");

        // Non-modal so user can reference results while continuing work
        configureResultDialog(alert, outputPath);
        alert.show();
    }

    /**
     * Configures a WB result dialog to be non-modal with Close and Open Folder buttons.
     * The X (close) button works because CLOSE is in the button types.
     */
    private static void configureResultDialog(Alert alert, String outputPath) {
        alert.initModality(javafx.stage.Modality.NONE);

        // CLOSE button type enables the window X button
        alert.getButtonTypes().setAll(ButtonType.CLOSE);

        // Add Open Folder as a secondary action
        ButtonType openFolderButton = new ButtonType("Open Folder", ButtonBar.ButtonData.LEFT);
        alert.getButtonTypes().add(openFolderButton);

        // Handle Open Folder without closing the dialog
        alert.getDialogPane().lookupButton(openFolderButton).addEventFilter(
                javafx.event.ActionEvent.ACTION, event -> {
                    event.consume(); // Prevent dialog from closing
                    openFolder(outputPath);
                });
    }

    /**
     * Opens a folder in the system file explorer.
     */
    private static void openFolder(String path) {
        try {
            File folder = new File(path);
            if (folder.exists()) {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(folder);
                } else {
                    logger.warn("Desktop not supported, cannot open folder");
                }
            } else {
                logger.warn("Folder does not exist: {}", path);
            }
        } catch (IOException e) {
            logger.error("Failed to open folder: {}", path, e);
        }
    }

    /**
     * Capitalize first letter of a string.
     */
    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

}
