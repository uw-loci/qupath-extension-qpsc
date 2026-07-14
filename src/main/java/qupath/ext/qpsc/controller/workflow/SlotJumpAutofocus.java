package qupath.ext.qpsc.controller.workflow;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.controller.TestAutofocusWorkflow;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.liveviewer.LiveViewerWindow;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * Autofocus-on-slot-jump for the multi-slide alignment step.
 *
 * <p>Fires the Live Viewer's autofocus once the stage has reached a slot, BEFORE the operator
 * aligns, so the tissue is already in focus. Reuses the existing SWEEP autofocus socket call
 * ({@code testAdaptiveAutofocus} / TESTADAF) -- no command-server change. It honors the same
 * {@code LiveViewerAutofocusMethod} preference the Live Viewer button reads.
 *
 * <p><b>Settle-gate.</b> The caller ({@code AffineTransformationController}) issues the slot
 * auto-move via {@link MicroscopeController#moveStageXY} immediately before calling
 * {@link #runAfterSlotMove()}. That move is a BLOCKING socket round-trip (the {@code MOVE}
 * command returns its 8-byte ack only after the stage arrives -- see
 * {@code documentation/developer/SOCKET_PROTOCOL.md}), so the stage move is COMPLETE by the time
 * this method runs. Autofocus then runs on a daemon thread and the caller sequences the
 * tile-selection confirm dialog on the returned future, so AF never fires mid-move and the
 * operator cannot start aligning until focus finishes.
 *
 * <p>Autofocus is an aid, not a gate: the returned future ALWAYS completes (even on AF failure),
 * so the alignment step proceeds to tile selection regardless.
 *
 * <p>STREAMING method note: if the pref is STREAMING, this increment falls back to SWEEP and
 * logs it. Standing up a preview stream for a streaming focus during the batch is deferred --
 * see {@code TODO(increment: streaming-af-preview)}.
 */
public final class SlotJumpAutofocus {

    private static final Logger logger = LoggerFactory.getLogger(SlotJumpAutofocus.class);

    /** Sink for the AF-phase status line (Section B of the multi-slide panel). */
    public interface StatusSink {
        /**
         * @param message ASCII status text (e.g. "Focusing...", "Ready")
         * @param error true to render as a failure (amber), false for a normal phase
         */
        void update(String message, boolean error);
    }

    private static volatile StatusSink statusSink;

    private SlotJumpAutofocus() {}

    /** Registers the status sink (the panel's Section-B AF label). Pass null to clear. */
    public static void setStatusSink(StatusSink sink) {
        statusSink = sink;
    }

    /** Clears the status sink (call when the multi-slide panel closes). */
    public static void clearStatusSink() {
        statusSink = null;
    }

    private static void publish(String message, boolean error) {
        StatusSink sink = statusSink;
        if (sink == null) {
            return;
        }
        if (Platform.isFxApplicationThread()) {
            sink.update(message, error);
        } else {
            Platform.runLater(() -> sink.update(message, error));
        }
    }

    /**
     * Publishes the "Moving to slot..." phase, if AF-on-jump is enabled. Called by the alignment
     * controller just before the (blocking) slot auto-move. Note: because the auto-move blocks the
     * FX thread, this text may not repaint until the move returns; the "Focusing..." / "Ready"
     * phases (published from the AF daemon thread) render normally.
     */
    public static void publishMoving() {
        if (PersistentPreferences.isMultiSlideAutofocusOnJump()) {
            publish("Moving to slot...", false);
        }
    }

    /**
     * Runs autofocus after the slot move has completed, if AF-on-jump is enabled and the guards
     * pass. Returns a future that completes when AF finishes (or immediately when skipped). The
     * future ALWAYS completes -- AF failure is surfaced on the status line but never blocks the
     * alignment step.
     *
     * @return a future that completes (with null) once AF settles or is skipped
     */
    public static CompletableFuture<Void> runAfterSlotMove() {
        if (!PersistentPreferences.isMultiSlideAutofocusOnJump()) {
            return CompletableFuture.completedFuture(null);
        }

        MicroscopeController controller = MicroscopeController.getInstance();
        if (controller == null || !controller.isConnected()) {
            logger.info("Slot-jump AF skipped: microscope not connected");
            publish("Ready", false);
            return CompletableFuture.completedFuture(null);
        }
        // Same guard as the Live Viewer Autofocus button: never AF during an acquisition.
        if (controller.isAcquisitionActive()) {
            logger.info("Slot-jump AF skipped: acquisition in progress");
            publish("Ready", false);
            return CompletableFuture.completedFuture(null);
        }

        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        if (configPath == null || configPath.isBlank()) {
            logger.info("Slot-jump AF skipped: microscope config not set");
            publish("Focus skipped -- config unavailable", true);
            return CompletableFuture.completedFuture(null);
        }
        MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
        String objective = TestAutofocusWorkflow.getCurrentObjective(configManager);
        if (objective == null) {
            logger.info("Slot-jump AF skipped: could not determine current objective");
            publish("Focus skipped -- objective unavailable", true);
            return CompletableFuture.completedFuture(null);
        }
        String outputPath = TestAutofocusWorkflow.getDefaultOutputPath();

        String method = PersistentPreferences.getLiveViewerAutofocusMethod();
        if (!"SWEEP".equals(method)) {
            // STREAMING (or anything non-SWEEP): fall back to SWEEP for the batch. Reusing an
            // already-open Live Viewer streaming-focus path (and standing up a new preview stream)
            // is deferred -- there is no clean synchronous completion hook to gate the settle
            // sequence on, and the streaming controller lives inside the LiveViewerWindow instance.
            // TODO(increment: streaming-af-preview): reuse an open Live Viewer stream's
            // streaming-focus path when LiveViewerWindow.isStreamingActive() is true.
            logger.info(
                    "Slot-jump AF: pref method is {} but this increment uses SWEEP (streamOpen={}); "
                            + "falling back to SWEEP autofocus",
                    method,
                    LiveViewerWindow.isStreamingActive());
        }

        publish("Focusing...", false);
        CompletableFuture<Void> done = new CompletableFuture<>();

        Thread afThread = new Thread(
                () -> {
                    String errorMsg = null;
                    boolean cancelled = false;
                    try {
                        // Shared sweep run + parse + AF-history core (identical to the Live Viewer
                        // autofocus button). This thread + the StatusSink phases below are the only
                        // slot-jump-specific shell.
                        SweepAutofocusRunner.SweepResult result =
                                SweepAutofocusRunner.run(controller, configPath, outputPath, objective);
                        cancelled = result.cancelled();
                        if (!cancelled) {
                            logger.info(
                                    "Slot-jump AF complete: z_shift={} um ({} -> {})",
                                    result.zShift(),
                                    result.initialZ(),
                                    result.finalZ());
                        }
                    } catch (IOException ex) {
                        errorMsg = ex.getMessage();
                        logger.error("Slot-jump AF failed: {}", errorMsg, ex);
                    } catch (RuntimeException ex) {
                        errorMsg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                        logger.error("Slot-jump AF failed: {}", errorMsg, ex);
                    }
                    if (errorMsg != null || cancelled) {
                        publish("Focus failed -- align manually", true);
                    } else {
                        publish("Ready", false);
                    }
                    done.complete(null);
                },
                "MultiSlide-SlotJumpAF");
        afThread.setDaemon(true);
        afThread.start();
        return done;
    }
}
