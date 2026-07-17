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
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.liveviewer.LiveViewerWindow;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * Autofocus-on-slot-jump for the multi-slide alignment step.
 *
 * <p>Fires the Live Viewer's autofocus once the stage has reached a slot, BEFORE the operator
 * aligns, so the tissue is already in focus. Honors the {@code LiveViewerAutofocusMethod}
 * preference the Live Viewer button reads: when it is STREAMING and a Live Viewer stream is open,
 * it runs the STREAMING focus scan ({@code streamingFocus} / STRMAFZ) -- a full focus SEARCH,
 * which is what a slot jump needs since each slide can be at a very different Z. When the
 * preference is SWEEP, or no stream is open, it falls back to the SWEEP call
 * ({@code testAdaptiveAutofocus} / TESTADAF), which is only a narrow-range DRIFT CHECK and will
 * report success without finding focus if the current Z is far off. Neither path changes the
 * command server.
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
 * <p>Streaming path keeps the live stream RUNNING (the server-side scan analyzes streamed frames),
 * unlike the SWEEP path which stops all live viewing for exclusive camera access. Both run on the
 * same {@code MultiSlide-SlotJumpAF} daemon thread and complete the returned future when done.
 */
public final class SlotJumpAutofocus {

    private static final Logger logger = LoggerFactory.getLogger(SlotJumpAutofocus.class);

    /**
     * Edge-retry attempt budget for the slot-jump streaming scan, passed as {@code --max-attempts}.
     *
     * <p>This is deliberately higher than the server default (MAX_EDGE_RETRIES + 1 = 3) BECAUSE this
     * is the slide-change case: we have just travelled to a fresh slide whose true focus depends on
     * its mounting-media thickness and can sit well beyond one narrow sweep from the previous slide's
     * Z. Rather than widen the per-scan {@code sweep_range_um} (which the config intentionally keeps
     * small, and which the operator is warned against enlarging globally), we let the designed
     * edge-retry march take MORE narrow steps toward focus. Extra attempts cost time only when focus
     * is actually far -- a near-seed scan still commits on attempt 1 and stops. Worst-case wall time
     * stays within the 180 s STRMAFZ read timeout (each attempt ~5-6 s, so 6 attempts + Brent ~40 s).
     */
    private static final int SLOT_JUMP_MAX_AF_ATTEMPTS = 6;

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
        // A slot jump can land far from the previous slide's focus, so a full focus SEARCH is
        // needed -- not a drift check. STREAMING autofocus (STRMAFZ) does a real scan; SWEEP
        // (TESTADAF) is only a narrow-range drift check that reports success WITHOUT finding focus
        // when the current Z is far off (observed on slot jumps: SWEEP landing ~8 um off, or
        // reporting 0.00 um shift on a fresh slide). So when the configured method is STREAMING
        // AND a Live Viewer stream is open, run the streaming scan -- which needs the stream
        // RUNNING, so it must NOT stop live viewing. Fall back to SWEEP only when the method is
        // SWEEP or no stream is open (streaming AF has no frames to analyze without a stream).
        boolean useStreaming = "STREAMING".equals(method) && LiveViewerWindow.isStreamingActive();

        String streamingModality = null;
        if (useStreaming) {
            // Resolve the active modality so the server applies the right focus metric / thresholds
            // (matches the Live Viewer autofocus button). Best-effort: null lets the server pick.
            try {
                MicroscopeSocketClient.CapabilityResult cap =
                        controller.getSocketClient().getCapabilities(null);
                if (cap != null && cap.modality != null && cap.modality.name != null) {
                    streamingModality = cap.modality.name;
                }
            } catch (Exception capEx) {
                logger.warn(
                        "Slot-jump streaming AF: GETCAP failed ({}); proceeding with modality=null",
                        capEx.getMessage());
            }
        } else if (!"SWEEP".equals(method)) {
            logger.info(
                    "Slot-jump AF: method is {} but no Live Viewer stream is open (streamOpen={}); "
                            + "using the SWEEP drift check as fallback",
                    method,
                    LiveViewerWindow.isStreamingActive());
        }

        publish("Focusing...", false);
        CompletableFuture<Void> done = new CompletableFuture<>();
        final boolean runStreaming = useStreaming;
        final String modalityForStreaming = streamingModality;

        Thread afThread = new Thread(
                () -> {
                    String errorMsg = null;
                    boolean cancelled = false;
                    try {
                        if (runStreaming) {
                            // Streaming scan: MUST keep the live stream running (no
                            // withAllLiveViewingOff). Blocking; returns a typed result. Objective is
                            // null -- the server auto-detects it from the live pixel size, exactly as
                            // the Live Viewer streaming-focus button does.
                            MicroscopeSocketClient.StreamingFocusResult result = controller
                                    .getSocketClient()
                                    .streamingFocus(
                                            configPath,
                                            null,
                                            modalityForStreaming,
                                            Double.NaN,
                                            false,
                                            SLOT_JUMP_MAX_AF_ATTEMPTS);
                            if (result.status == MicroscopeSocketClient.StreamingFocusResult.Status.ABORTED) {
                                cancelled = true;
                            } else if (result.status != MicroscopeSocketClient.StreamingFocusResult.Status.SUCCESS) {
                                errorMsg = result.reason == null ? result.status.name() : result.reason;
                                logger.warn("Slot-jump streaming AF did not succeed: {} ({})", result.status, errorMsg);
                            } else {
                                logger.info(
                                        "Slot-jump streaming AF complete: z_shift={} um ({} -> {}), n={}, span={}",
                                        result.zShift,
                                        result.initialZ,
                                        result.finalZ,
                                        result.nSamples,
                                        result.zSpan);
                            }
                        } else {
                            // Shared sweep run + parse + AF-history core (identical to the Live Viewer
                            // SWEEP autofocus button). Narrow-range drift check -- used only as the
                            // no-stream / SWEEP-pref fallback.
                            SweepAutofocusRunner.SweepResult result =
                                    SweepAutofocusRunner.run(controller, configPath, outputPath, objective);
                            cancelled = result.cancelled();
                            if (!cancelled) {
                                logger.info(
                                        "Slot-jump AF (SWEEP) complete: z_shift={} um ({} -> {})",
                                        result.zShift(),
                                        result.initialZ(),
                                        result.finalZ());
                            }
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
