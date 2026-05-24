package qupath.ext.qpsc.ui.liveviewer;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient.StreamingFocusResult;
import qupath.ext.qpsc.ui.liveviewer.RefineFocusController.Outcome;
import qupath.ext.qpsc.ui.liveviewer.RefineFocusController.StatusCallback;
import qupath.ext.qpsc.utilities.AfFailureHint;

/**
 * Thin Java-side wrapper around the server's STRMAFZ (streaming autofocus) command.
 *
 * <p>Like the server-side TESTADAF sweep autofocus, this controller
 * delegates the whole scan to the server -- there is no per-frame loop
 * in Java. The server:
 *
 * <ol>
 *   <li>Looks up {@code sweep_range_um} per-objective from
 *       {@code autofocus_<scope>.yml}</li>
 *   <li>Runs the pre-flight blur-budget + saturation checks</li>
 *   <li>Starts continuous sequence acquisition, fires a non-blocking move
 *       across the scan range, pops every frame + reads Z during motion</li>
 *   <li>Parabolic-fits the (z, metric) pairs, commits the peak</li>
 * </ol>
 *
 * <p>This client class is trivial on purpose: the heavy lifting is server-
 * side, and there is no per-frame loop in Java. The only reason it exists
 * as a separate class (instead of inlining in LiveViewerWindow) is to
 * match the existing running/cancel/callback lifecycle pattern used by
 * {@link RefineFocusController}, so the Live Viewer can track state with
 * the same plumbing.
 *
 * <p><b>Cancellation:</b> supported via {@link #cancel()}, which sends the
 * ABORTAF command on the client's auxiliary socket (the primary socket is
 * blocked inside the STRMAFZ round-trip). The server polls a per-IP abort
 * signal between scan attempts and between frames, tears the scan down,
 * restores Z to the pre-scan position, and replies {@code ABORTED}. The
 * in-flight {@link #execute} call then finishes with {@link Outcome#CANCELLED}.
 *
 * <p><b>Fallback behavior:</b> if the server returns {@code UNAVAILABLE}
 * (pre-flight refusal -- exposure too long, saturated, no speed property,
 * too few samples), the controller reports FAILED with an explanatory
 * message. The caller is expected to fall back to the stepped Sweep
 * Autofocus (server-side TESTADAF).
 */
public class StreamingFocusController {

    private static final Logger logger = LoggerFactory.getLogger(StreamingFocusController.class);

    private volatile boolean running = false;
    private final MicroscopeSocketClient socketClient;

    public StreamingFocusController(MicroscopeSocketClient socketClient) {
        this.socketClient = socketClient;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Request cancellation of the in-progress scan.
     *
     * <p>Sends ABORTAF on the auxiliary socket (best-effort). The server
     * stops the scan, restores Z to the pre-scan position, and replies
     * {@code ABORTED}; the {@link #execute} call then finishes with
     * {@link Outcome#CANCELLED}. No-op when no scan is running.
     *
     * <p>The socket round-trip runs on a short-lived daemon thread so a
     * call from the JavaFX thread never blocks the UI.
     */
    public void cancel() {
        if (!running) {
            return;
        }
        Thread t = new Thread(
                () -> {
                    try {
                        socketClient.abortStreamingFocus();
                    } catch (IOException e) {
                        logger.warn("Failed to send streaming-AF abort: {}", e.getMessage());
                    }
                },
                "StreamingFocus-Cancel");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Run one streaming autofocus scan.
     *
     * @param objective    Objective identifier from the caller's context, or
     *                     null to let the server auto-detect via pixel size.
     * @param modality     Active imaging modality (e.g. "brightfield", "ppm",
     *                     "fluorescence", "laser_scanning") or null. Used by
     *                     the server to pick a modality-appropriate saturation
     *                     refusal threshold -- fluorescence and LSM need much
     *                     stricter thresholds than brightfield or PPM because
     *                     their signal pixels are rare and saturate into
     *                     unusability long before a bulk-pixel percentage
     *                     threshold would fire.
     * @param rangeOverrideUm Optional override for the yaml's sweep_range_um
     *                         (pass NaN or zero to use the yaml value).
     * @param callback     Status callback for progress / final outcome.
     */
    public void execute(String objective, String modality, double rangeOverrideUm, StatusCallback callback) {
        if (running) {
            callback.onStatusUpdate("Autofocus already running", Outcome.ERROR);
            return;
        }
        running = true;

        try {
            String yamlPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (yamlPath == null || yamlPath.isEmpty()) {
                finish(callback, "No microscope config yaml set in preferences", Outcome.ERROR);
                return;
            }

            callback.onStatusUpdate("Autofocus: scanning...", Outcome.IN_PROGRESS);
            long t0 = System.currentTimeMillis();
            StreamingFocusResult result = socketClient.streamingFocus(yamlPath, objective, modality, rangeOverrideUm);
            long elapsedMs = System.currentTimeMillis() - t0;

            switch (result.status) {
                case SUCCESS:
                    String okMsg = String.format(
                            "Autofocus: shifted %+.2f um, %d samples, span %.1f um (%.1fs)",
                            result.zShift, result.nSamples, result.zSpan, elapsedMs / 1000.0);
                    logger.info(
                            "Autofocus SUCCESS: initial={} final={} shift={} n={} span={} elapsed={}ms",
                            result.initialZ,
                            result.finalZ,
                            result.zShift,
                            result.nSamples,
                            result.zSpan,
                            elapsedMs);
                    finish(callback, okMsg, Outcome.SUCCESS);
                    return;

                case UNAVAILABLE:
                    String unavailableMsg = "Autofocus unavailable: " + result.reason + "\n\n"
                            + AfFailureHint.format(modality, result.reason);
                    logger.info("Autofocus UNAVAILABLE: {}", result.reason);
                    finish(callback, unavailableMsg, Outcome.FAILED);
                    return;

                case ABORTED:
                    logger.info("Autofocus ABORTED by user: {}", result.reason);
                    finish(callback, "Autofocus cancelled -- Z restored to start position", Outcome.CANCELLED);
                    return;

                case FAILED:
                default:
                    String failMsg = "Autofocus failed: " + result.reason + "\n\n"
                            + AfFailureHint.format(modality, result.reason);
                    logger.warn("Autofocus FAILED: {}", result.reason);
                    finish(callback, failMsg, Outcome.ERROR);
                    return;
            }
        } catch (IOException e) {
            logger.error("Autofocus IO error: {}", e.getMessage(), e);
            finish(callback, "Autofocus error: " + e.getMessage(), Outcome.ERROR);
        } catch (Exception e) {
            logger.error("Autofocus unexpected error", e);
            finish(callback, "Autofocus error: " + e.getMessage(), Outcome.ERROR);
        }
    }

    private void finish(StatusCallback callback, String message, Outcome outcome) {
        running = false;
        callback.onStatusUpdate(message, outcome);
    }
}
