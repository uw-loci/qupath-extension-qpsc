package qupath.ext.qpsc.service;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.SaturationSummaryDialog;

/**
 * Shared acquisition monitoring service.
 *
 * <p>Wraps {@link MicroscopeSocketClient#monitorAcquisition} with consistent
 * error handling, hardware error recovery, saturation summary display, and
 * tile measurement loading. All three acquisition callers (BoundedAcquisitionWorkflow,
 * AcquisitionManager, WBComparisonWorkflow) should use this instead of calling
 * monitorAcquisition directly.
 *
 * <p>Design principles:
 * <ul>
 *   <li>Hardware error handling is ALWAYS present (default: log + auto-cancel)</li>
 *   <li>Saturation summary is ALWAYS shown on completion when detected</li>
 *   <li>Callers customize via optional callbacks (progress UI, timing, focus dialogs)</li>
 *   <li>Terminal state handling is consistent across all callers</li>
 * </ul>
 */
public final class AcquisitionMonitorService {

    private static final Logger logger = LoggerFactory.getLogger(AcquisitionMonitorService.class);

    private AcquisitionMonitorService() {} // static utility

    /**
     * Monitor an acquisition that has already been started via
     * {@link MicroscopeSocketClient#startAcquisition}.
     *
     * @param socketClient  the connected socket client
     * @param config        monitoring configuration (callbacks, timeouts)
     * @return the terminal state (COMPLETED, CANCELLED, FAILED)
     * @throws IOException if communication fails
     * @throws InterruptedException if monitoring thread is interrupted
     * @throws CancellationException if acquisition was cancelled
     * @throws RuntimeException if acquisition failed on the server
     */
    public static MicroscopeSocketClient.AcquisitionState monitorAndHandle(
            MicroscopeSocketClient socketClient,
            MonitorConfig config) throws IOException, InterruptedException {

        MicroscopeSocketClient.AcquisitionState finalState = socketClient.monitorAcquisition(
                config.progressCallback,
                config.manualFocusCallback,
                buildHardwareErrorCallback(socketClient, config),
                config.pollIntervalMs,
                config.timeoutMs);

        return handleTerminalState(socketClient, finalState, config);
    }

    /**
     * Build the hardware error callback. If the caller provides a custom one,
     * use it. Otherwise provide a default that logs and auto-cancels.
     */
    private static Consumer<String> buildHardwareErrorCallback(
            MicroscopeSocketClient socketClient, MonitorConfig config) {

        if (config.hardwareErrorCallback != null) {
            return config.hardwareErrorCallback;
        }

        // Default: log the error and auto-cancel. This is safe -- better than
        // hanging (which is what happens with no callback at all). Callers that
        // want interactive retry/skip/cancel should set hardwareError() on the config.
        return errorMessage -> {
            logger.error("Hardware error during acquisition (no interactive handler): {}", errorMessage);
            logger.error("Auto-cancelling. To enable retry/skip, provide a hardwareError callback.");
            try {
                socketClient.acknowledgeHardwareError("cancel");
            } catch (IOException ex) {
                logger.error("Failed to send cancel for hardware error", ex);
            }
        };
    }

    /**
     * Handle the terminal acquisition state consistently.
     */
    private static MicroscopeSocketClient.AcquisitionState handleTerminalState(
            MicroscopeSocketClient socketClient,
            MicroscopeSocketClient.AcquisitionState state,
            MonitorConfig config) {

        switch (state) {
            case COMPLETED:
                logger.info("Acquisition completed successfully");

                // Show saturation summary if detected
                if (config.tileDirPath != null) {
                    showSaturationSummaryIfNeeded(socketClient, config.tileDirPath, config.modality);
                }

                // Notify caller-specific completion handler
                if (config.onCompleted != null) {
                    config.onCompleted.run();
                }
                return state;

            case CANCELLED:
                logger.info("Acquisition was cancelled");
                if (config.onCancelled != null) {
                    config.onCancelled.run();
                }
                throw new CancellationException("Acquisition was cancelled");

            case FAILED:
                String failureMessage = socketClient.getLastFailureMessage();
                String details = failureMessage != null ? failureMessage : "Unknown server error";
                logger.error("Acquisition failed: {}", details);
                throw new RuntimeException("Acquisition failed: " + details);

            default:
                logger.warn("Unexpected acquisition state: {}", state);
                throw new RuntimeException("Unexpected acquisition state: " + state);
        }
    }

    /**
     * Show the saturation summary dialog if saturation was detected.
     */
    private static void showSaturationSummaryIfNeeded(
            MicroscopeSocketClient socketClient, String tileDirPath, String modality) {
        try {
            String satSummary = socketClient.getFormattedSaturationSummary();
            if (satSummary == null) return;

            logger.warn("Saturation detected during acquisition:\n{}", satSummary);
            String reportPath = tileDirPath + java.io.File.separator + "saturation_report.json";
            java.io.File reportFile = new java.io.File(reportPath);

            if (reportFile.exists()) {
                SaturationSummaryDialog.show(reportPath, satSummary, modality);
            } else {
                Platform.runLater(() -> {
                    javafx.scene.control.Alert alert =
                            new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING);
                    alert.setTitle("Saturation Detected");
                    alert.setHeaderText("Some tiles had saturated pixels");
                    javafx.scene.control.TextArea text = new javafx.scene.control.TextArea(satSummary);
                    text.setEditable(false);
                    text.setWrapText(true);
                    text.setPrefHeight(200);
                    alert.getDialogPane().setContent(text);
                    alert.getDialogPane().setMinWidth(500);
                    alert.showAndWait();
                });
            }
        } catch (Exception e) {
            logger.warn("Could not display saturation summary: {}", e.getMessage());
        }
    }

    /**
     * Optional timing callback for pausing/resuming progress timers
     * during manual focus or hardware error dialogs.
     */
    public interface TimingCallback {
        void pause();
        void resume();
    }

    /**
     * Configuration for acquisition monitoring. Use the builder pattern.
     */
    public static class MonitorConfig {
        Consumer<MicroscopeSocketClient.AcquisitionProgress> progressCallback;
        Consumer<Integer> manualFocusCallback;
        Consumer<String> hardwareErrorCallback;
        TimingCallback timingCallback;
        String tileDirPath;
        long pollIntervalMs = 500;
        long timeoutMs = 300_000;
        Runnable onCompleted;
        Runnable onCancelled;
        String modality;

        private MonitorConfig() {}

        public static MonitorConfig create() {
            return new MonitorConfig();
        }

        /** Callback for progress updates (tile count). */
        public MonitorConfig progress(Consumer<MicroscopeSocketClient.AcquisitionProgress> cb) {
            this.progressCallback = cb;
            return this;
        }

        /** Callback for manual focus requests. */
        public MonitorConfig manualFocus(Consumer<Integer> cb) {
            this.manualFocusCallback = cb;
            return this;
        }

        /** Custom hardware error callback (overrides the default dialog). */
        public MonitorConfig hardwareError(Consumer<String> cb) {
            this.hardwareErrorCallback = cb;
            return this;
        }

        /** Timing callback for pausing/resuming progress during dialogs. */
        public MonitorConfig timing(TimingCallback cb) {
            this.timingCallback = cb;
            return this;
        }

        /** Tile directory path (enables saturation summary on completion). */
        public MonitorConfig tileDir(String path) {
            this.tileDirPath = path;
            return this;
        }

        /** Polling interval in milliseconds (default 500). */
        public MonitorConfig pollInterval(long ms) {
            this.pollIntervalMs = ms;
            return this;
        }

        /** Timeout in milliseconds with no progress (default 300000 = 5 min). */
        public MonitorConfig timeout(long ms) {
            this.timeoutMs = ms;
            return this;
        }

        /** Called on successful completion (after saturation summary). */
        public MonitorConfig onCompleted(Runnable cb) {
            this.onCompleted = cb;
            return this;
        }

        /** Called on cancellation (before CancellationException is thrown). */
        public MonitorConfig onCancelled(Runnable cb) {
            this.onCancelled = cb;
            return this;
        }

        /** Modality identifier ("ppm_10x", etc.) for saturation dialog dispatch. */
        public MonitorConfig modality(String modality) {
            this.modality = modality;
            return this;
        }
    }
}

