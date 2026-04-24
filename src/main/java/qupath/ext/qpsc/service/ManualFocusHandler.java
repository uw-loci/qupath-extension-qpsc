package qupath.ext.qpsc.service;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;

/**
 * Shared handler for the manual focus dialog pattern used during acquisition monitoring.
 *
 * <p>When the microscope server requests manual focus intervention, the client must:
 * <ol>
 *   <li>Show a dialog on the JavaFX thread offering Retry/Skip/Cancel</li>
 *   <li>Block the monitoring thread until the dialog is closed</li>
 *   <li>Send keepalive pings every 30 seconds to prevent timeout</li>
 * </ol>
 *
 * <p>This class consolidates the identical pattern that was duplicated in
 * {@code BoundedAcquisitionWorkflow} and {@code AcquisitionManager}.</p>
 */
public final class ManualFocusHandler {

    private static final Logger logger = LoggerFactory.getLogger(ManualFocusHandler.class);

    /** Keepalive ping interval while waiting for user dialog response. */
    private static final int KEEPALIVE_INTERVAL_SECONDS = 30;

    private ManualFocusHandler() {}

    /**
     * Result of manual focus dialog indicating user's choice.
     */
    public enum ManualFocusResult {
        RETRY_AUTOFOCUS, // Run autofocus again after manual adjustment
        USE_CURRENT_FOCUS, // Accept current focus and continue
        CANCEL_ACQUISITION // Cancel the entire acquisition
    }

    /**
     * Provider for the manual focus dialog UI.
     * Implementations show a blocking dialog and return the user's choice.
     */
    @FunctionalInterface
    public interface FocusDialogProvider {
        ManualFocusResult showFocusDialog(int retriesRemaining);
    }

    /**
     * Optional callback interface for pausing/resuming progress timing during
     * manual focus dialogs. This prevents user wait time from inflating ETA estimates.
     */
    public interface TimingCallback {
        /** Called before the manual focus dialog is shown. */
        void pauseTiming();
        /** Called after the manual focus dialog is dismissed. */
        void resumeTiming();
    }

    /**
     * Handles a manual focus request from the microscope server.
     *
     * <p>If the "Skip Manual Autofocus" preference is enabled, automatically skips
     * without showing a dialog. Otherwise, shows a blocking dialog on the JavaFX
     * thread and sends keepalive pings while waiting.</p>
     *
     * @param socketClient     the socket client for sending focus responses
     * @param retriesRemaining number of autofocus retries remaining (shown in dialog)
     * @param guard            optional guard to prevent duplicate dialogs (may be null)
     * @param timingCallback   optional callback for pausing/resuming progress timing (may be null)
     * @param dialogProvider   provides the UI dialog for manual focus (e.g., UIFunctions::showManualFocusDialog)
     */
    public static void handle(
            MicroscopeSocketClient socketClient,
            int retriesRemaining,
            AtomicBoolean guard,
            TimingCallback timingCallback,
            FocusDialogProvider dialogProvider) {

        // Guard against duplicate dialogs for the same retry event
        if (guard != null && guard.getAndSet(true)) {
            return;
        }

        try {
            if (QPPreferenceDialog.getSkipManualAutofocus()) {
                logger.warn(
                        "Manual focus requested but 'No Manual Autofocus' enabled - "
                                + "skipping autofocus (retries remaining: {})",
                        retriesRemaining);
                try {
                    socketClient.skipAutofocusRetry();
                    logger.info("Autofocus skipped - using current focus position");
                } catch (IOException e) {
                    logger.error("Failed to send skip autofocus", e);
                }
            } else {
                logger.info("Manual focus requested - showing dialog (retries remaining: {})", retriesRemaining);

                if (timingCallback != null) {
                    timingCallback.pauseTiming();
                }

                CountDownLatch latch = new CountDownLatch(1);

                Platform.runLater(() -> {
                    try {
                        ManualFocusResult result = dialogProvider.showFocusDialog(retriesRemaining);
                        try {
                            switch (result) {
                                case RETRY_AUTOFOCUS:
                                    socketClient.acknowledgeManualFocus();
                                    logger.info("User chose to retry autofocus");
                                    break;
                                case USE_CURRENT_FOCUS:
                                    socketClient.skipAutofocusRetry();
                                    logger.info("User chose to use current focus");
                                    break;
                                case CANCEL_ACQUISITION:
                                    // Send ONLY cancelAcquisition. The server's
                                    // manual-focus wait now polls both the
                                    // focus-complete event and the cancel
                                    // event, so CANC alone transitions the
                                    // autofocus wait directly to user_choice
                                    // ="cancel" (which raises the cancelled
                                    // sentinel and sets state=CANCELLED).
                                    // Previously we sent SKPAF first, which
                                    // won the race and made the server treat
                                    // the cancel as "use current focus".
                                    socketClient.cancelAcquisition();
                                    logger.info("User chose to cancel acquisition");
                                    break;
                            }
                        } catch (IOException e) {
                            logger.error("Failed to send manual focus response", e);
                        }
                    } finally {
                        if (timingCallback != null) {
                            timingCallback.resumeTiming();
                        }
                        latch.countDown();
                    }
                });

                // Block until dialog is closed, pinging server to keep connection alive
                try {
                    while (!latch.await(KEEPALIVE_INTERVAL_SECONDS, TimeUnit.SECONDS)) {
                        try {
                            socketClient.getAcquisitionProgress();
                            logger.debug("Keepalive ping during manual focus dialog");
                        } catch (IOException e) {
                            logger.warn("Failed keepalive ping during manual focus", e);
                        }
                    }
                } catch (InterruptedException e) {
                    logger.error("Interrupted waiting for manual focus dialog", e);
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            if (guard != null) {
                guard.set(false);
            }
        }
    }
}
