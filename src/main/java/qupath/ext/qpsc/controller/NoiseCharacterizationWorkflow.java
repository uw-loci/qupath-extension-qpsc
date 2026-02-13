package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.NoiseCharacterizationDialog;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

import java.awt.Desktop;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NoiseCharacterizationWorkflow - Systematic camera noise testing across gain/exposure grid.
 *
 * <p>This workflow enables users to characterize the JAI camera's noise performance
 * by systematically testing multiple gain and exposure combinations. The results
 * help identify optimal settings that maximize signal-to-noise ratio (SNR).
 *
 * <p>Workflow steps:
 * <ol>
 *   <li>User selects test preset (Quick/Full/Custom) and parameters</li>
 *   <li>Confirmation alert with time estimate and "cover lens" reminder</li>
 *   <li>Non-modal progress window with live progress bar</li>
 *   <li>Background thread executes characterization via socket</li>
 *   <li>Results displayed with option to open output folder</li>
 * </ol>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class NoiseCharacterizationWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(NoiseCharacterizationWorkflow.class);

    /**
     * Main entry point for noise characterization workflow.
     * Shows parameter dialog, then executes characterization.
     */
    public static void run() {
        logger.info("Starting JAI noise characterization workflow");

        Platform.runLater(() -> {
            try {
                // Ensure we're connected first
                if (!MicroscopeController.getInstance().isConnected()) {
                    boolean connect = Dialogs.showConfirmDialog(
                            "Not Connected",
                            "Not connected to microscope server.\n" +
                            "Would you like to connect now?");
                    if (connect) {
                        MicroscopeController.getInstance().connect();
                    } else {
                        return;
                    }
                }

                // Show dialog and collect parameters
                NoiseCharacterizationDialog.showDialog()
                    .thenAccept(params -> {
                        if (params != null) {
                            logger.info("Noise characterization parameters received");
                            // Show confirmation and then start
                            Platform.runLater(() -> showConfirmationAndStart(params));
                        } else {
                            logger.info("Noise characterization cancelled by user");
                        }
                    })
                    .exceptionally(ex -> {
                        logger.error("Error in noise characterization dialog", ex);
                        Platform.runLater(() -> Dialogs.showErrorMessage(
                                "Noise Characterization Error",
                                "Failed to show dialog: " + ex.getMessage()));
                        return null;
                    });

            } catch (Exception e) {
                logger.error("Failed to start noise characterization workflow", e);
                Dialogs.showErrorMessage("Noise Characterization Error",
                        "Failed to start workflow: " + e.getMessage());
            }
        });
    }

    /**
     * Shows a confirmation alert before starting the characterization.
     */
    private static void showConfirmationAndStart(NoiseCharacterizationDialog.NoiseCharParams params) {
        String presetLabel;
        switch (params.preset()) {
            case "quick" -> presetLabel = "Quick (16 configs, ~5 min)";
            case "full" -> presetLabel = "Full (42 configs, ~15 min)";
            default -> {
                int customCount = 0;
                if (params.gains() != null && params.exposures() != null) {
                    customCount = params.gains().size() * params.exposures().size();
                }
                presetLabel = "Custom (" + customCount + " configs)";
            }
        }

        boolean confirmed = Dialogs.showConfirmDialog(
                "Start Noise Characterization",
                "Ready to start JAI camera noise characterization.\n\n" +
                "Preset: " + presetLabel + "\n" +
                "Frames per test: " + params.numFrames() + "\n" +
                "Generate plots: " + (params.generatePlots() ? "Yes" : "No") + "\n" +
                "Output: " + params.outputPath() + "\n\n" +
                "IMPORTANT: For accurate noise measurements, ensure the camera\n" +
                "lens is covered or pointing at a uniform target.\n\n" +
                "Proceed?"
        );

        if (confirmed) {
            startWithProgress(params);
        } else {
            logger.info("Noise characterization cancelled at confirmation");
        }
    }

    /**
     * Creates non-modal progress window and starts characterization on background thread.
     */
    private static void startWithProgress(NoiseCharacterizationDialog.NoiseCharParams params) {
        Stage progressStage = new Stage();
        progressStage.setTitle("JAI Noise Characterization");
        progressStage.initModality(Modality.NONE);

        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui != null && gui.getStage() != null) {
            progressStage.initOwner(gui.getStage());
        }

        // Progress UI
        Label headerLabel = new Label("JAI Noise Characterization");
        headerLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(400);

        Label progressLabel = new Label("Starting characterization...");
        progressLabel.setStyle("-fx-font-size: 14px;");

        Label statusLabel = new Label("Preparing camera...");
        statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: gray;");

        Label infoLabel = new Label(
                "Testing gain/exposure combinations for noise analysis.\n" +
                "You can use other QuPath windows while this runs."
        );
        infoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");

        Button cancelButton = new Button("Cancel");
        AtomicBoolean cancelled = new AtomicBoolean(false);
        cancelButton.setOnAction(e -> {
            cancelled.set(true);
            progressStage.close();
            logger.info("Noise characterization cancelled by user");
        });

        VBox content = new VBox(12);
        content.setPadding(new Insets(20));
        content.setAlignment(Pos.CENTER_LEFT);
        content.getChildren().addAll(headerLabel, progressBar, progressLabel, statusLabel, infoLabel, cancelButton);

        Scene scene = new Scene(content, 470, 230);
        progressStage.setScene(scene);
        progressStage.setResizable(false);
        progressStage.show();

        // Run on background thread
        CompletableFuture.runAsync(() -> {
            executeCharacterization(params, progressStage, progressBar, progressLabel, statusLabel, cancelled);
        }).exceptionally(ex -> {
            logger.error("Noise characterization failed", ex);
            Platform.runLater(() -> {
                progressStage.close();
                Dialogs.showErrorMessage("Noise Characterization Error",
                        "Failed: " + ex.getMessage());
            });
            return null;
        });
    }

    /**
     * Executes the noise characterization via socket. Called from background thread.
     */
    private static void executeCharacterization(
            NoiseCharacterizationDialog.NoiseCharParams params,
            Stage progressStage, ProgressBar progressBar,
            Label progressLabel, Label statusLabel,
            AtomicBoolean cancelled) {

        logger.info("Executing noise characterization: preset={}, frames={}",
                params.preset(), params.numFrames());

        // Save and restore live view state
        boolean liveWasRunning = false;
        MicroscopeSocketClient socketClient = null;

        try {
            socketClient = MicroscopeController.getInstance().getSocketClient();

            // Check if live view is running and stop it
            try {
                liveWasRunning = socketClient.isLiveModeRunning();
                if (liveWasRunning) {
                    logger.info("Stopping live view for noise characterization");
                    socketClient.setLiveMode(false);
                    Thread.sleep(500); // Allow time to settle
                }
            } catch (Exception e) {
                logger.warn("Could not check/stop live view: {}", e.getMessage());
            }

            // Progress callback
            java.util.function.BiConsumer<Integer, Integer> progressCallback = (current, total) -> {
                if (cancelled.get()) return;
                double progress = (double) current / total;
                Platform.runLater(() -> {
                    progressBar.setProgress(progress);
                    progressLabel.setText(String.format("Progress: %d/%d configurations (%.0f%%)",
                            current, total, progress * 100));
                    statusLabel.setText(String.format("Testing configuration %d of %d...", current, total));
                });
            };

            // Run characterization
            MicroscopeSocketClient.NoiseCharacterizationResult result =
                    socketClient.runNoiseCharacterization(
                            params.outputPath(),
                            params.preset(),
                            params.gains(),
                            params.exposures(),
                            params.numFrames(),
                            params.generatePlots(),
                            progressCallback
                    );

            if (cancelled.get()) {
                logger.info("Noise characterization was cancelled");
                return;
            }

            logger.info("Noise characterization completed: {} configs tested", result.totalConfigs());
            logger.info("Best SNR at gain={}, exposure={}ms", result.bestGain(), result.bestExposureMs());

            // Show success
            final String resultPath = result.outputPath();
            final int totalConfigs = result.totalConfigs();
            final double bestGain = result.bestGain();
            final double bestExp = result.bestExposureMs();
            final boolean plotsGenerated = result.plotsGenerated();

            Platform.runLater(() -> {
                progressStage.close();

                StringBuilder resultMsg = new StringBuilder();
                resultMsg.append("Noise characterization completed successfully!\n\n");
                resultMsg.append(String.format("Tested %d gain/exposure configurations.\n\n", totalConfigs));

                if (bestGain > 0) {
                    resultMsg.append(String.format("Best SNR found at:\n"));
                    resultMsg.append(String.format("  Gain: %.1f\n", bestGain));
                    resultMsg.append(String.format("  Exposure: %.1f ms\n\n", bestExp));
                }

                resultMsg.append("Results saved to:\n").append(resultPath).append("\n\n");
                resultMsg.append("Output files:\n");
                resultMsg.append("  - noise_characterization.csv: Raw data\n");
                if (plotsGenerated) {
                    resultMsg.append("  - noise_vs_gain.png: Noise curves\n");
                    resultMsg.append("  - snr_heatmap.png: SNR heatmap\n");
                    resultMsg.append("  - noise_report.txt: Summary report\n");
                }
                resultMsg.append("\nOpen results folder?");

                boolean openFolder = Dialogs.showConfirmDialog(
                        "Noise Characterization Complete",
                        resultMsg.toString()
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
                logger.info("Noise characterization was cancelled during execution");
                return;
            }

            logger.error("Noise characterization failed", e);
            Platform.runLater(() -> {
                progressStage.close();
                Dialogs.showErrorMessage("Noise Characterization Failed",
                        "Failed to complete noise characterization:\n" + e.getMessage());
            });
        } finally {
            // Restore live view if it was running
            if (liveWasRunning && socketClient != null) {
                try {
                    logger.info("Restoring live view after noise characterization");
                    socketClient.setLiveMode(true);
                } catch (Exception e) {
                    logger.warn("Could not restore live view: {}", e.getMessage());
                }
            }
        }
    }
}
