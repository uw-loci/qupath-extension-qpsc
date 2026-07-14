package qupath.ext.qpsc.controller.workflow;

import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.ui.liveviewer.AfHistoryService;

/**
 * Shared, blocking runner for the SWEEP autofocus socket call ({@code testAdaptiveAutofocus} /
 * TESTADAF).
 *
 * <p>This is the single home for the run + result-parse + {@link AfHistoryService} bookkeeping
 * core that was previously copied between the Live Viewer autofocus button
 * ({@code LiveViewerWindow.handleSweepFocus}) and the multi-slide slot-jump autofocus
 * ({@code SlotJumpAutofocus.runAfterSlotMove}). It runs the sweep with all live viewing off (so the
 * server has exclusive camera access), parses the {@code cancelled}/{@code initial_z}/
 * {@code final_z}/{@code z_shift} fields, and records the achieved Z on the AF history tic bar.
 *
 * <p>Deliberately narrow: this method owns ONLY the socket run + parse + history core. Each caller
 * keeps its OWN thread, guards, status/UX shell, and its OWN {@code try/catch} + logging around the
 * call -- see the two call sites. It throws {@link IOException} exactly as the socket call does so
 * callers can catch it (and any {@link RuntimeException}) the same way they did when the body was
 * inline.
 *
 * <p><b>Raw-string result fields.</b> The record carries the {@code initial_z}/{@code final_z}/
 * {@code z_shift} values as the raw server strings, not parsed doubles, so each caller's status /
 * log text renders byte-for-byte identically to the inline version (the Live Viewer status line
 * formats these with {@code %s}). The one value that must be numeric -- {@code final_z} for the AF
 * history -- is parsed and recorded internally, with the same advisory {@code NumberFormatException}
 * swallow the inline code used.
 */
public final class SweepAutofocusRunner {

    private static final Logger logger = LoggerFactory.getLogger(SweepAutofocusRunner.class);

    private SweepAutofocusRunner() {}

    /**
     * Result of a completed sweep run. The three Z fields are the raw server strings (may be
     * {@code null} if the server omitted them); {@code cancelled} is {@code true} when the server
     * reported the sweep was cancelled (Z restored), in which case the Z fields are {@code null}.
     */
    public record SweepResult(String initialZ, String finalZ, String zShift, boolean cancelled) {}

    /**
     * Runs the sweep autofocus, blocking until the server responds. Records {@code final_z} on the
     * AF history when the run completes (not cancelled). Must be called off the FX thread (it makes
     * a blocking socket round-trip).
     *
     * @param controller the microscope controller (provides the socket client + live-viewing gate)
     * @param configPath path to the microscope YAML config
     * @param outputPath path the server writes its per-sweep output to
     * @param objective the current objective id
     * @return a {@link SweepResult} with the raw Z strings and the cancelled flag
     * @throws IOException if the socket call fails (propagated unchanged for the caller to handle)
     */
    public static SweepResult run(
            MicroscopeController controller, String configPath, String outputPath, String objective)
            throws IOException {
        // Holders carry the server result out of the withAllLiveViewingOff lambda boundary.
        boolean[] cancelledHolder = {false};
        String[] zHolder = new String[3]; // [0]=initial_z, [1]=final_z, [2]=z_shift

        // Run with all live viewing off so the server has exclusive camera access. Safe with no
        // live viewer open (it stops nothing and restores nothing).
        controller.withAllLiveViewingOff(() -> {
            Map<String, String> result =
                    controller.getSocketClient().testAdaptiveAutofocus(configPath, outputPath, objective);
            if ("true".equals(result.get("cancelled"))) {
                cancelledHolder[0] = true;
                return;
            }
            String initialZ = result.get("initial_z");
            String finalZ = result.get("final_z");
            String zShift = result.get("z_shift");
            zHolder[0] = initialZ;
            zHolder[1] = finalZ;
            zHolder[2] = zShift;
            // Record AF result on the Z-bar tic history (advisory only).
            if (finalZ != null) {
                try {
                    AfHistoryService.add(Double.parseDouble(finalZ));
                } catch (NumberFormatException ignored) {
                    // history is advisory only
                }
            }
        });

        return new SweepResult(zHolder[0], zHolder[1], zHolder[2], cancelledHolder[0]);
    }
}
