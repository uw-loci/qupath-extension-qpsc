package qupath.ext.qpsc.controller.workflow;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.ui.ThemeColors;
import qupath.ext.qpsc.ui.liveviewer.LiveViewerWindow;

/**
 * Puts the microscope into a state SIFT alignment can actually succeed in, before a
 * refinement dialog asks anyone to align anything.
 *
 * <h2>Why this exists</h2>
 * SIFT matches the live camera against a bright-field-like whole-slide image. Three things
 * silently defeat it, and none of them were checked:
 * <ul>
 *   <li><b>No live stream.</b> Nothing opened the Live Viewer, so there is no image to
 *       compare against and -- worse -- {@code SlotJumpAutofocus} quietly downgrades a
 *       streaming focus scan to the narrow SWEEP drift check, which reports success without
 *       finding focus when Z is far off.</li>
 *   <li><b>The wrong modality state.</b> Nothing verified the imaging state before alignment
 *       -- it depended entirely on the operator having set it by hand. On PPM, aligning at a
 *       near-extinction angle (dark by design) leaves SIFT nothing to match, and it reports
 *       low confidence with no indication that the angle was the cause. This removes the
 *       dependence on remembering; it is NOT a fix for a measured defect. Log analysis of the
 *       2026-08-14 runs found setup-pass focus curves at 38-59% amplitude with R^2 ~ 0.99,
 *       i.e. the operator HAD set the angle correctly. Do not cite this class as evidence
 *       that setup focus was systematically wrong -- that hypothesis was tested and refuted
 *       (see claude-reports/2026-08-24_multislide-log-analysis.md).</li>
 *   <li><b>No microscope at all.</b> Alignment proceeds against a stale frame.</li>
 * </ul>
 *
 * <h2>Contract</h2>
 * {@link #prepare} tries to correct the state rather than merely reporting it: it opens and
 * starts the Live Viewer, then asks the modality to apply its alignment reference state
 * (PPM's uncrossed angle with the matching exposure; the focus channel for channel-based
 * modalities). It returns what it did, or why it could not.
 *
 * <p>Callers decide what a failure means. In manual mode a failure is a visible warning and
 * the operator can fix it by hand. In an automatic multi-slide batch there is nobody to fix
 * it, so a failure is a hard block -- four silently mis-aligned slides is a worse outcome
 * than a run that refuses to start.
 *
 * <p>This performs socket I/O and hardware motion, so it must NOT be called on the FX thread;
 * {@link #prepareAsync} is provided for FX call sites.
 */
public final class AlignmentLivePrep {

    private static final Logger logger = LoggerFactory.getLogger(AlignmentLivePrep.class);

    /** How long to wait for the Live Viewer to report an active stream after being opened. */
    private static final long STREAM_WAIT_MS = 5_000;

    private static final long STREAM_POLL_MS = 100;

    private AlignmentLivePrep() {}

    /**
     * Outcome of a prep attempt.
     *
     * @param ok          true when the microscope is in a state alignment can use
     * @param summary     one line for the operator: what was applied, or what is wrong
     * @param stateApplied description of the modality state that was set, if any
     */
    public record Result(boolean ok, String summary, Optional<String> stateApplied) {

        static Result failed(String why) {
            return new Result(false, why, Optional.empty());
        }
    }

    /** Runs {@link #prepare} on a background thread. Safe to call from the FX thread. */
    public static CompletableFuture<Result> prepareAsync(String modality, String objective, String detector) {
        CompletableFuture<Result> future = new CompletableFuture<>();
        Thread t = new Thread(() -> future.complete(prepare(modality, objective, detector)), "Alignment-Live-Prep");
        t.setDaemon(true);
        t.start();
        return future;
    }

    /**
     * Connects the checks and corrections described in the class docs. Never throws -- a
     * failure is reported as a {@link Result}, because every caller has to make a
     * manual-vs-automatic decision about it anyway.
     *
     * @param modality  runtime modality name; when null only the live-stream checks run
     * @param objective objective ID
     * @param detector  detector ID
     * @return what was achieved, and whether alignment should proceed
     */
    public static Result prepare(String modality, String objective, String detector) {
        MicroscopeController controller = MicroscopeController.getInstance();
        if (controller == null || !controller.isConnected()) {
            return Result.failed("Microscope is not connected -- alignment cannot see the camera.");
        }
        if (controller.isAcquisitionActive()) {
            return Result.failed("An acquisition is running -- the camera is not available for alignment.");
        }

        // 1. Live stream. show() is a no-op when the window is already up and streaming.
        if (!LiveViewerWindow.isStreamingActive()) {
            logger.info("Alignment prep: Live Viewer not streaming; opening it and starting the stream");
            // ensureStreaming, not show(): show() starts the stream only when it had to open the
            // window, so an already-open-but-stopped viewer -- the state every step that needs
            // exclusive camera access leaves behind -- got toFront() and nothing else. The wait
            // below then timed out against a stream nobody had started, and reported it as
            // "check the camera", which sent the operator after a camera that was fine.
            LiveViewerWindow.ensureStreaming();
            if (!awaitStreaming()) {
                return Result.failed("Live view did not start within " + (STREAM_WAIT_MS / 1000)
                        + "s -- check the camera, then reopen this dialog.");
            }
        }

        // 2. Modality reference state. Failure here is reported, not swallowed: aligning at a
        // near-extinction PPM angle fails SIFT on every tile with no visible cause.
        Optional<String> applied = Optional.empty();
        if (modality != null && !modality.isBlank()) {
            ModalityHandler handler = ModalityRegistry.getHandler(modality);
            if (handler != null) {
                try {
                    applied = handler.applyAlignmentReferenceState(modality, objective, detector);
                } catch (Exception e) {
                    logger.warn("Alignment prep: could not apply reference state for {}: {}", modality, e.getMessage());
                    return Result.failed(
                            "Could not set the alignment imaging state for " + modality + ": " + e.getMessage());
                }
            }
        }

        // 3. Applying the reference state pauses and resumes live mode, so re-check that the
        // stream actually came back before declaring the rig ready.
        if (!LiveViewerWindow.isStreamingActive() && !awaitStreaming()) {
            return Result.failed("Live view stopped while setting up the camera and did not resume.");
        }

        String summary = applied.map(a -> "Live view running at " + a + ".").orElse("Live view running.");
        logger.info("Alignment prep OK: {}", summary);
        return new Result(true, summary, applied);
    }

    /**
     * Runs {@link #prepare} for a refinement dialog and reports the outcome on its live-state
     * label, applying the manual-vs-automatic policy.
     *
     * <p>The two modes diverge only in what a FAILURE means. Manual: the label turns red, the
     * operator reads what is wrong and fixes it, and the dialog stays usable -- they may know
     * something we do not. Automatic: nobody is reading it, so the dialog is closed and the
     * refinement failed. That is the "correct the state if you can, hard block if you cannot"
     * policy: four silently mis-aligned slides is a far worse outcome than a batch that stops.
     *
     * <p>Both refinement dialogs call this, so an automatic batch is blocked whichever
     * refinement mode it chose. Must be called on the FX thread.
     *
     * @param stage          the dialog, closed on a hard block
     * @param liveStateLabel status line, updated in place
     * @param setReady       enables/disables whatever control starts a capture; may be null
     * @param onHardBlock    completes the caller's future as failed; runs after the stage closes
     */
    public static void runForDialog(
            javafx.stage.Stage stage,
            javafx.scene.control.Label liveStateLabel,
            java.util.function.Consumer<Boolean> setReady,
            Runnable onHardBlock) {

        boolean automatic = qupath.ext.qpsc.ui.AutoAdvanceController.isArmed()
                && !qupath.ext.qpsc.ui.AutoAdvanceController.isOverriddenThisSlide();

        // Nothing can be captured until the rig state is known -- in automatic mode especially,
        // where the very next thing to happen would be an unattended stage move.
        if (setReady != null) {
            setReady.accept(false);
        }

        // Modality is session state; objective and detector come from the config the same way
        // slot-jump AF resolves them, so alignment prep and AF agree about the hardware.
        String modality = qupath.ext.qpsc.state.ModalityState.getInstance().getModality();
        String objective = null;
        String detector = null;
        String configPath = qupath.ext.qpsc.preferences.QPPreferenceDialog.getMicroscopeConfigFileProperty();
        if (configPath != null && !configPath.isBlank()) {
            var mgr = qupath.ext.qpsc.utilities.MicroscopeConfigManager.getInstance(configPath);
            objective = qupath.ext.qpsc.controller.TestAutofocusWorkflow.getCurrentObjective(mgr);
            detector = mgr.getActiveDetector();
        }

        prepareAsync(modality, objective, detector)
                .thenAccept(result -> javafx.application.Platform.runLater(() -> {
                    if (result.ok()) {
                        liveStateLabel.setText(result.summary());
                        liveStateLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + ThemeColors.SUCCESS + ";");
                        if (setReady != null) {
                            setReady.accept(true);
                        }
                        return;
                    }

                    // ERROR, not WARN: in an automatic batch the next thing that happens is the
                    // slide being abandoned, and the operator's only other notice is a dialog that
                    // vanishes when the run moves on.
                    logger.error("Alignment prep: live state not ready -- {}", result.summary());
                    liveStateLabel.setText(result.summary());
                    liveStateLabel.setStyle(
                            "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + ThemeColors.ERROR + ";");

                    if (!automatic) {
                        if (setReady != null) {
                            setReady.accept(true);
                        }
                        return;
                    }
                    qupath.fx.dialogs.Dialogs.showErrorMessage(
                            "Cannot align unattended",
                            "The microscope could not be put into a state alignment can use:\n\n"
                                    + result.summary()
                                    + "\n\nThe automatic run has been stopped rather than aligning against an "
                                    + "unusable live view. Fix the camera state, then re-run this slide.");
                    if (stage != null) {
                        stage.close();
                    }
                    if (onHardBlock != null) {
                        onHardBlock.run();
                    }
                }));
    }

    /** Polls until the Live Viewer reports an active stream, or the wait budget runs out. */
    private static boolean awaitStreaming() {
        long deadline = System.currentTimeMillis() + STREAM_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (LiveViewerWindow.isStreamingActive()) {
                return true;
            }
            try {
                Thread.sleep(STREAM_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return LiveViewerWindow.isStreamingActive();
    }
}
