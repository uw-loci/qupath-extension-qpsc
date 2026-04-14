package qupath.ext.qpsc.ui.liveviewer;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient.SmoothFocusResult;
import qupath.ext.qpsc.ui.liveviewer.RefineFocusController.Outcome;
import qupath.ext.qpsc.ui.liveviewer.RefineFocusController.StatusCallback;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * Thin Java-side wrapper around the server's SMOOTHZ command.
 *
 * <p>Unlike {@link SweepFocusController}, which runs a stepped loop client-
 * side issuing move + snap commands one at a time, the Smooth controller
 * delegates the whole scan to the server. The server:
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
 * {@link SweepFocusController} and {@link RefineFocusController}, so the
 * Live Viewer can track Smooth state with the same plumbing.
 *
 * <p><b>Cancellation:</b> not currently supported. The server-side scan is
 * short (~1 second on PPM) and best-effort cancellation would mean sending
 * a separate command mid-scan which the server does not currently handle.
 * Adding it is a future improvement if long-range Smooth scans (20-50 um)
 * become common.
 *
 * <p><b>Fallback behavior:</b> if the server returns {@code UNAVAILABLE}
 * (pre-flight refusal -- exposure too long, saturated, no speed property,
 * too few samples), the controller reports SUCCESS with an explanatory
 * message but does <i>not</i> raise an error. The caller is expected to
 * fall back to the stepped Sweep Focus path, which is now 3-4x faster than
 * before thanks to the busy-poll wait in the stage hardware layer.
 */
public class SmoothFocusController {

    private static final Logger logger = LoggerFactory.getLogger(SmoothFocusController.class);

    private volatile boolean running = false;
    private final MicroscopeSocketClient socketClient;

    public SmoothFocusController(MicroscopeSocketClient socketClient) {
        this.socketClient = socketClient;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Run one Smooth focus scan.
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
    public void execute(String objective, String modality,
                        double rangeOverrideUm, StatusCallback callback) {
        if (running) {
            callback.onStatusUpdate("Smooth Focus already running", Outcome.ERROR);
            return;
        }
        running = true;

        try {
            String yamlPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (yamlPath == null || yamlPath.isEmpty()) {
                finish(callback, "No microscope config yaml set in preferences", Outcome.ERROR);
                return;
            }

            callback.onStatusUpdate("Smooth Focus: scanning...", Outcome.IN_PROGRESS);
            long t0 = System.currentTimeMillis();
            SmoothFocusResult result = socketClient.smoothFocus(
                    yamlPath, objective, modality, rangeOverrideUm);
            long elapsedMs = System.currentTimeMillis() - t0;

            switch (result.status) {
                case SUCCESS:
                    String okMsg = String.format(
                            "Smooth Focus: shifted %+.2f um, %d samples, span %.1f um (%.1fs)",
                            result.zShift, result.nSamples, result.zSpan, elapsedMs / 1000.0);
                    logger.info("Smooth Focus SUCCESS: initial={} final={} shift={} n={} span={} elapsed={}ms",
                            result.initialZ, result.finalZ, result.zShift,
                            result.nSamples, result.zSpan, elapsedMs);
                    finish(callback, okMsg, Outcome.SUCCESS);
                    return;

                case UNAVAILABLE:
                    // This is a pre-flight refusal, not an error. Log it
                    // and report back with a SUCCESS-style message so the
                    // caller can show it as informational. The caller
                    // should then fall back to stepped Sweep Focus.
                    String unavailableMsg = "Smooth Focus unavailable: " + result.reason;
                    logger.info("Smooth Focus UNAVAILABLE: {}", result.reason);
                    finish(callback, unavailableMsg, Outcome.FAILED);
                    return;

                case FAILED:
                default:
                    String failMsg = "Smooth Focus failed: " + result.reason;
                    logger.warn("Smooth Focus FAILED: {}", result.reason);
                    finish(callback, failMsg, Outcome.ERROR);
                    return;
            }
        } catch (IOException e) {
            logger.error("Smooth Focus IO error: {}", e.getMessage(), e);
            finish(callback, "Smooth Focus error: " + e.getMessage(), Outcome.ERROR);
        } catch (Exception e) {
            logger.error("Smooth Focus unexpected error", e);
            finish(callback, "Smooth Focus error: " + e.getMessage(), Outcome.ERROR);
        }
    }

    private void finish(StatusCallback callback, String message, Outcome outcome) {
        running = false;
        callback.onStatusUpdate(message, outcome);
    }
}
