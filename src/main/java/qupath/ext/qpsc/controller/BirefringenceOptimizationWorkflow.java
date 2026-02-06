package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.BirefringenceOptimizationDialog;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

import java.awt.Desktop;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BirefringenceOptimizationWorkflow - Find optimal polarizer angle for maximum birefringence signal
 *
 * This workflow provides a way to systematically test polarizer angles and identify
 * the angle that maximizes birefringence signal contrast:
 * <ol>
 *   <li>User positions microscope on tissue with visible birefringence</li>
 *   <li>Dialog collects test parameters (angle range, step size, exposure mode)</li>
 *   <li>Test acquires paired images (+theta, -theta) across angle range</li>
 *   <li>Analysis computes signal difference and finds optimal angle</li>
 *   <li>Results displayed with visualization plot</li>
 * </ol>
 *
 * The test supports three exposure modes:
 * <ul>
 *   <li><strong>Interpolate</strong> (default) - Use calibration exposures from sensitivity test</li>
 *   <li><strong>Calibrate</strong> - Measure exposures on background first, then acquire</li>
 *   <li><strong>Fixed</strong> - Use same exposure for all angles (fastest, may saturate)</li>
 * </ul>
 *
 * Results include:
 * <ul>
 *   <li>Optimal angle (raw difference method)</li>
 *   <li>Optimal angle (normalized difference method)</li>
 *   <li>Signal metrics at optimal angles</li>
 *   <li>Visualization plot showing signal vs angle</li>
 *   <li>Sanity check at 0 degrees (should be near zero)</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class BirefringenceOptimizationWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(BirefringenceOptimizationWorkflow.class);

    /**
     * Main entry point for birefringence optimization workflow.
     * Shows UI for parameter input, then executes test.
     */
    public static void run() {
        logger.info("Starting birefringence optimization workflow");

        Platform.runLater(() -> {
            try {
                // Show dialog and collect parameters
                BirefringenceOptimizationDialog.showDialog()
                    .thenAccept(params -> {
                        if (params != null) {
                            logger.info("Birefringence optimization parameters received");
                            // Start optimization with progress dialog (all on FX thread initially)
                            startOptimizationWithProgress(params);
                        } else {
                            logger.info("Birefringence optimization cancelled by user");
                        }
                    })
                    .exceptionally(ex -> {
                        logger.error("Error in birefringence optimization dialog", ex);
                        Platform.runLater(() -> Dialogs.showErrorMessage("Optimization Error",
                                "Failed to show optimization dialog: " + ex.getMessage()));
                        return null;
                    });

            } catch (Exception e) {
                logger.error("Failed to start birefringence optimization workflow", e);
                Dialogs.showErrorMessage("Optimization Error",
                        "Failed to start birefringence optimization: " + e.getMessage());
            }
        });
    }

    /**
     * Creates non-modal progress window on FX thread, then runs optimization on background thread.
     * The progress window does NOT block other dialogs, allowing users to use Stage Control.
     *
     * @param params Optimization parameters from dialog
     */
    private static void startOptimizationWithProgress(BirefringenceOptimizationDialog.BirefringenceParams params) {
        // Must be called on FX thread - create non-modal progress window
        Stage progressStage = new Stage();
        progressStage.setTitle("Birefringence Optimization");
        progressStage.initModality(Modality.NONE);  // Non-modal - allows other dialogs

        // Set owner to QuPath main window
        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui != null && gui.getStage() != null) {
            progressStage.initOwner(gui.getStage());
        }

        // Create progress UI
        Label headerLabel = new Label("PPM Birefringence Optimization");
        headerLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Label progressLabel = new Label("Starting optimization...");
        progressLabel.setStyle("-fx-font-size: 14px;");

        Label statusLabel = new Label("");
        statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: gray;");

        Label infoLabel = new Label(
                "This window will update as the test progresses.\n" +
                "You can use Stage Control and other windows while waiting."
        );
        infoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");

        Button cancelButton = new Button("Cancel");
        AtomicBoolean cancelled = new AtomicBoolean(false);
        cancelButton.setOnAction(e -> {
            cancelled.set(true);
            progressStage.close();
            logger.info("Birefringence optimization cancelled by user");
        });

        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        content.setAlignment(Pos.CENTER_LEFT);
        content.getChildren().addAll(headerLabel, progressLabel, statusLabel, infoLabel, cancelButton);

        Scene scene = new Scene(content, 450, 200);
        progressStage.setScene(scene);
        progressStage.setResizable(false);
        progressStage.show();

        // Now run the actual optimization on a background thread
        CompletableFuture.runAsync(() -> {
            executeOptimization(params, progressStage, progressLabel, statusLabel, cancelled);
        }).exceptionally(ex -> {
            logger.error("Birefringence optimization failed", ex);
            Platform.runLater(() -> {
                progressStage.close();
                Dialogs.showErrorMessage("Birefringence Optimization Error",
                        "Failed to execute optimization: " + ex.getMessage());
            });
            return null;
        });
    }

    /**
     * Executes the birefringence optimization via socket communication with progress updates.
     * Called from background thread - all UI updates must use Platform.runLater.
     *
     * @param params Optimization parameters from dialog
     * @param progressStage Progress window to close when done (created on FX thread)
     * @param progressLabel Label to update with progress (created on FX thread)
     * @param statusLabel Label to update with status messages (created on FX thread)
     * @param cancelled AtomicBoolean flag that is set to true if user cancels
     */
    private static void executeOptimization(BirefringenceOptimizationDialog.BirefringenceParams params,
                                            Stage progressStage, Label progressLabel, Label statusLabel,
                                            AtomicBoolean cancelled) {
        logger.info("Executing birefringence optimization: {} to {} deg, step {}",
                params.minAngle(), params.maxAngle(), params.angleStep());

        try {
            // Get microscope configuration
            String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();

            // Use shared MicroscopeController connection (don't create new one)
            MicroscopeSocketClient socketClient = MicroscopeController.getInstance().getSocketClient();

            // Ensure we're connected
            if (!MicroscopeController.getInstance().isConnected()) {
                logger.info("Not connected to microscope server, connecting...");
                MicroscopeController.getInstance().connect();
            }

            logger.info("Sending birefringence optimization command:");
            logger.info("  Config: {}", configFileLocation);
            logger.info("  Output: {}", params.outputFolder());
            logger.info("  Angle range: {} to {} deg", params.minAngle(), params.maxAngle());
            logger.info("  Step size: {} deg", params.angleStep());
            logger.info("  Exposure mode: {}", params.exposureMode());
            if (params.fixedExposureMs() != null) {
                logger.info("  Fixed exposure: {} ms", params.fixedExposureMs());
            }
            logger.info("  Target intensity: {}", params.targetIntensity());

            // Create progress callback to update the dialog
            java.util.function.BiConsumer<Integer, Integer> progressCallback = (current, total) -> {
                if (cancelled.get()) return;
                int percent = (int) ((current * 100.0) / total);
                Platform.runLater(() -> {
                    progressLabel.setText(String.format("Progress: %d%% (%d/%d angle pairs)", percent, current, total));
                });
            };

            // Create status callback
            java.util.function.Consumer<String> statusCallback = (message) -> {
                if (cancelled.get()) return;
                Platform.runLater(() -> statusLabel.setText(message));
            };

            // Create stage move callback for calibrate mode
            java.util.function.Supplier<Boolean> stageMoveCallback = () -> {
                if (cancelled.get()) return false;

                // Use CountDownLatch to wait for user response on FX thread
                CountDownLatch latch = new CountDownLatch(1);
                AtomicBoolean userConfirmed = new AtomicBoolean(false);

                Platform.runLater(() -> {
                    // Create NON-MODAL stage move dialog (allows using Stage Control)
                    Stage stageMoveStage = new Stage();
                    stageMoveStage.setTitle("Move Stage to Tissue");
                    stageMoveStage.initModality(Modality.NONE);  // Non-modal!

                    // Set owner to QuPath main window
                    QuPathGUI gui = QuPathGUI.getInstance();
                    if (gui != null && gui.getStage() != null) {
                        stageMoveStage.initOwner(gui.getStage());
                    }

                    // Create content
                    Label headerLabel = new Label("Background Calibration Complete!");
                    headerLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

                    Label instructionLabel = new Label(
                            "Please move the stage to a region with BIREFRINGENT TISSUE\n" +
                            "(e.g., collagen fibers, crystalline structures).\n\n" +
                            "You can use the Stage Control window to move the stage.\n\n" +
                            "Click Continue when positioned on tissue, or Cancel to abort."
                    );
                    instructionLabel.setStyle("-fx-font-size: 13px;");
                    instructionLabel.setWrapText(true);

                    Button continueButton = new Button("Continue");
                    continueButton.setDefaultButton(true);
                    continueButton.setOnAction(e -> {
                        userConfirmed.set(true);
                        stageMoveStage.close();
                        latch.countDown();
                    });

                    Button cancelButton = new Button("Cancel");
                    cancelButton.setCancelButton(true);
                    cancelButton.setOnAction(e -> {
                        userConfirmed.set(false);
                        stageMoveStage.close();
                        latch.countDown();
                    });

                    HBox buttonBox = new HBox(10);
                    buttonBox.setAlignment(Pos.CENTER_RIGHT);
                    buttonBox.getChildren().addAll(cancelButton, continueButton);

                    VBox content = new VBox(15);
                    content.setPadding(new Insets(20));
                    content.setAlignment(Pos.CENTER_LEFT);
                    content.getChildren().addAll(headerLabel, instructionLabel, buttonBox);

                    Scene scene = new Scene(content, 450, 200);
                    stageMoveStage.setScene(scene);
                    stageMoveStage.setResizable(false);

                    // Handle window close (X button) as cancel
                    stageMoveStage.setOnCloseRequest(e -> {
                        userConfirmed.set(false);
                        latch.countDown();
                    });

                    stageMoveStage.show();
                    stageMoveStage.toFront();  // Bring to front
                });

                try {
                    latch.await();  // Wait for user response
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }

                return userConfirmed.get();
            };

            // Update status for calibrate mode
            if ("calibrate".equals(params.exposureMode())) {
                Platform.runLater(() -> {
                    progressLabel.setText("Phase 1: Background Calibration");
                    statusLabel.setText("Calibrating exposures on background area...");
                });
            }

            // Call the socket client method with all callbacks
            String resultPath = socketClient.runBirefringenceOptimization(
                    configFileLocation,
                    params.outputFolder(),
                    params.minAngle(),
                    params.maxAngle(),
                    params.angleStep(),
                    params.exposureMode(),
                    params.fixedExposureMs(),
                    params.targetIntensity(),
                    progressCallback,
                    stageMoveCallback,
                    statusCallback
            );

            if (cancelled.get()) {
                logger.info("Optimization was cancelled by user");
                return;
            }

            logger.info("Birefringence optimization completed successfully");
            logger.info("Results saved to: {}", resultPath);

            // Close progress dialog and show success (on FX thread)
            Platform.runLater(() -> {
                progressStage.close();

                boolean openFolder = Dialogs.showConfirmDialog(
                        "Optimization Complete",
                        "Birefringence optimization completed successfully!\n\n" +
                        "Results saved to:\n" + resultPath + "\n\n" +
                        "The results directory contains:\n" +
                        "  - birefringence_results.json: Optimal angles and metrics\n" +
                        "  - birefringence_analysis.png: Visualization plot\n\n" +
                        "Would you like to open the results folder?"
                );

                if (openFolder) {
                    try {
                        File resultsDir = new File(resultPath);
                        if (resultsDir.exists()) {
                            Desktop.getDesktop().open(resultsDir);
                        }
                    } catch (Exception e) {
                        logger.error("Failed to open results folder", e);
                        Dialogs.showErrorMessage("Error",
                                "Failed to open results folder: " + e.getMessage());
                    }
                }
            });

        } catch (Exception e) {
            if (cancelled.get()) {
                logger.info("Optimization was cancelled during execution");
                return;
            }

            logger.error("Birefringence optimization failed", e);

            // Close progress dialog and show error (on FX thread)
            Platform.runLater(() -> {
                progressStage.close();
                Dialogs.showErrorMessage("Optimization Failed",
                        "Failed to complete birefringence optimization:\n" + e.getMessage());
            });
        }
        // Note: Don't disconnect - we're using the shared MicroscopeController connection
    }
}
