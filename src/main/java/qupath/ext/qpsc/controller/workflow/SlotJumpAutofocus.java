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
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
import qupath.ext.qpsc.utilities.SafeZClearanceMonitor;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

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

    /**
     * Announces that autofocus did NOT run, and why.
     *
     * <p>Every skip path used to be {@code logger.info} only, and {@link #publish} reaches a
     * sink that ONLY the multi-slide panel registers -- so in a single-slide refinement the
     * message went nowhere at all. An operator then aligned against an out-of-focus tile with
     * no indication that focusing had been skipped. This always surfaces: to the panel when
     * there is one, and as a notification when there is not.
     *
     * @param what short reason, e.g. "microscope not connected"
     */
    /**
     * Flags a SUCCESS whose numbers do not describe a validated focus peak.
     *
     * <p>The server reports {@code n} and {@code span} for its FINAL attempt only, while the
     * total travel accumulates across up to six re-centred attempts. Two shapes are worth
     * saying out loud, because both look identical to a good result in the log otherwise:
     * <ul>
     *   <li><b>Too few samples.</b> A peak cannot be validated from a handful of points. On
     *       2026-08-14 a slot jump returned SUCCESS on {@code n=3} while moving 194.7 um.</li>
     *   <li><b>Travel far exceeding the sampled span.</b> Legitimate after several attempts,
     *       but it also means the final scan never saw the starting position, so nothing
     *       cross-checks the direction it walked.</li>
     * </ul>
     *
     * <p>This does not reject the result -- the same 2026-08-14 scan did land near true focus.
     * It makes a marginal success visible instead of indistinguishable from a clean one.
     */
    private static void warnIfImplausible(MicroscopeSocketClient.StreamingFocusResult result) {
        boolean fewSamples = result.nSamples < MIN_PLAUSIBLE_AF_SAMPLES;
        boolean movedBeyondScan = result.zSpan > 0 && Math.abs(result.zShift) > result.zSpan;
        if (!fewSamples && !movedBeyondScan) {
            return;
        }
        String why = fewSamples
                ? ("only " + result.nSamples + " samples in the final scan")
                : String.format("moved %.1f um but the final scan covered only %.1f um", result.zShift, result.zSpan);
        logger.warn(
                "Slot-jump AF reported SUCCESS but the result is weakly supported: {} (z {} -> {}). "
                        + "Check focus before aligning.",
                why,
                result.initialZ,
                result.finalZ);
        publish("Focus uncertain -- " + why, true);
    }

    /**
     * Below this many samples in the final scan, a reported peak is not meaningfully validated.
     * Normal scans in practice return 20-100 samples.
     */
    private static final int MIN_PLAUSIBLE_AF_SAMPLES = 10;

    /**
     * The focus Z saved on the open slide's per-slide alignment JSON, or null when that slide
     * has never been aligned.
     *
     * <p>Uses the same lookup key {@code checkForSlideAlignment} uses to find the alignment
     * itself, so the seed and the transform always come from the same record. Returns null on
     * any failure -- a missing seed costs a slower scan, whereas a seed read from the WRONG
     * slide's record would drive the stage somewhere arbitrary before scanning.
     */
    private static Double resolveSavedFocusZ() {
        try {
            QuPathGUI gui = QuPathGUI.getInstance();
            if (gui == null || gui.getProject() == null || gui.getImageData() == null) {
                return null;
            }
            String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
            if (imageName == null) {
                return null;
            }
            String key = AlignmentHelper.resolveMacroLookupKey(gui.getProject(), gui.getImageData(), imageName);
            return (key == null) ? null : AffineTransformManager.loadSlideFocusZ(gui.getProject(), key);
        } catch (Exception e) {
            logger.debug("Slot-jump AF: no saved focus Z available ({})", e.getMessage());
            return null;
        }
    }

    /**
     * Whether this slot jump may use approach-from-safe-Z, and with what bounds.
     *
     * @param safeZUm           retracted position, or NaN to use the standard scan
     * @param approachMaxUm     signed travel bound from the safe Z, or NaN
     * @param requireTissueGate commit only where tissue is detected
     */
    private record ApproachPlan(double safeZUm, double approachMaxUm, boolean requireTissueGate) {
        static ApproachPlan disabled() {
            return new ApproachPlan(Double.NaN, Double.NaN, false);
        }
    }

    /**
     * Licenses approach-from-safe-Z only when a Focus Approach Validation run has measured
     * this combination and still applies.
     *
     * <p>This is the gate, and it matters because the approach is the MORE DANGEROUS option --
     * it drives the objective the whole way toward the sample along a path nobody watches,
     * where the standard scan creeps in 30 um steps near the last focus. The two things that
     * make that defensible -- that the metric peaks at the sample rather than on glass, and how
     * far the sample is from the retraction point -- are measurements, not settings. Without
     * them the standard scan runs instead: slower, but it assumes nothing unverified about this
     * rig. Never make the approach the default for an unvalidated combination.
     *
     * <p>Deliberately NOT a hard block on the workflow. Refusing to acquire because a
     * characterisation is missing would strand a rig that has been focusing fine for months;
     * the operator is told (in the multi-slide assignment dialog) and the run proceeds on the
     * old path.
     */
    private static ApproachPlan resolveApproachPlan(String configPath, String modality, String objective) {
        try {
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
            String scope = mgr.getString("microscope", "name");
            if (scope == null || modality == null || objective == null) {
                return ApproachPlan.disabled();
            }
            var record = qupath.ext.qpsc.utilities.FocusApproachValidationStore.find(scope, modality, objective);
            if (record == null) {
                logger.info(
                        "Slot-jump AF: no focus-approach validation for {}/{}/{}; using the standard scan",
                        scope,
                        modality,
                        objective);
                return ApproachPlan.disabled();
            }
            if (!record.usable()) {
                logger.warn(
                        "Slot-jump AF: focus-approach validation FAILED for {}/{}; using the standard scan ({})",
                        modality,
                        objective,
                        String.join("; ", record.reasons()));
                return ApproachPlan.disabled();
            }
            Double currentSafeZ = mgr.getSafeZUm(null, modality);
            String stale = record.isStaleAgainst(currentSafeZ);
            if (stale != null) {
                logger.warn("Slot-jump AF: focus-approach validation is stale ({}); using the standard scan", stale);
                return ApproachPlan.disabled();
            }
            // Travel bound: the measured distance plus headroom for slide-to-slide variation,
            // SIGNED from the two positions the validation actually measured. The sign is what
            // lets the server avoid inferring which way retracts; an unsigned bound sends the
            // scan away from the sample on a rig where retract is the positive direction.
            double bound = record.signedApproachBoundUm(APPROACH_HEADROOM_FACTOR);
            if (Double.isNaN(bound)) {
                logger.info("Slot-jump AF: validation record has no usable approach bound; using the standard scan");
                return ApproachPlan.disabled();
            }
            logger.info(
                    "Slot-jump AF: approach-from-safe-Z licensed -- safe Z {} um, bound {} um, tissue gate {}",
                    currentSafeZ,
                    String.format("%.1f", bound),
                    record.requiresTissueGate());
            return new ApproachPlan(currentSafeZ, bound, record.requiresTissueGate());
        } catch (Exception e) {
            logger.debug(
                    "Slot-jump AF: could not resolve an approach plan ({}); using the standard scan", e.getMessage());
            return ApproachPlan.disabled();
        }
    }

    /**
     * Headroom on the validated approach distance. The validation measured ONE slide; the
     * measured slide-to-slide focus spread on a carrier was 236 um against approach distances
     * of a few hundred, so a bound with no headroom would fall short on a thicker slide. Kept
     * modest because this is also the travel cap.
     */
    private static final double APPROACH_HEADROOM_FACTOR = 1.4;

    private static void publishSkip(String what) {
        logger.warn("Slot-jump AF SKIPPED: {}", what);
        publish("Focus skipped -- " + what, true);
        if (statusSink == null) {
            Platform.runLater(() -> Dialogs.showWarningNotification(
                    "Autofocus skipped",
                    "Autofocus did not run before this alignment step (" + what
                            + "). The tile may be out of focus, which makes SIFT matching fail or misalign."));
        }
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
            publishSkip("microscope not connected");
            return CompletableFuture.completedFuture(null);
        }
        // Same guard as the Live Viewer Autofocus button: never AF during an acquisition.
        if (controller.isAcquisitionActive()) {
            publishSkip("an acquisition is in progress");
            return CompletableFuture.completedFuture(null);
        }

        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        if (configPath == null || configPath.isBlank()) {
            publishSkip("microscope config not set");
            return CompletableFuture.completedFuture(null);
        }
        MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
        String objective = TestAutofocusWorkflow.getCurrentObjective(configManager);
        if (objective == null) {
            publishSkip("could not determine the current objective");
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
            // This is a DOWNGRADE, not a fallback: SWEEP is a narrow drift check that reports
            // success without finding focus when Z is far off (measured on slot jumps at ~8 um
            // error, and 0.00 um "shift" on a fresh slide). Reporting it at INFO meant a slide
            // could be aligned out of focus with nothing on screen to say so.
            logger.warn(
                    "Slot-jump AF DOWNGRADED: method is {} but no Live Viewer stream is open; "
                            + "using the SWEEP drift check, which cannot find focus if Z is far off",
                    method);
            publish("Focus limited -- no live stream, drift check only", true);
            if (statusSink == null) {
                Platform.runLater(() -> Dialogs.showWarningNotification(
                        "Autofocus limited",
                        "Streaming autofocus needs the Live Viewer running. Without it only a narrow "
                                + "drift check ran, which cannot recover focus on a fresh slide. Open the "
                                + "Live Viewer and re-focus before aligning."));
            }
        }

        // Seed Z from THIS slide's saved focus before scanning. Without it the scan starts
        // wherever the previous slide left the stage, and slide-to-slide focus variation is
        // large and normal: across 8 slides in one carrier on 2026-08-14 the focus Z spread was
        // 236 um. One slot jump there had to hunt 195 um, exhausted all six 30 um attempts (every
        // one classifying edge_high, i.e. "the peak is above this window"), fell through to a
        // Brent bracket that never bracketed, and committed the top edge on 3 samples.
        //
        // The seed is only available once that slide has been aligned at least once -- a
        // first-time setup pass has nothing to seed from and still scans from where it lands.
        Double seedZ = resolveSavedFocusZ();
        if (seedZ != null) {
            try {
                double before = controller.getStagePositionZ();
                if (Math.abs(before - seedZ) > 1.0) {
                    logger.info("Slot-jump AF: seeding Z from this slide's saved focus: {} -> {} um", before, seedZ);
                    controller.moveStageZ(seedZ);
                }
            } catch (Exception e) {
                logger.warn(
                        "Slot-jump AF: could not seed Z from saved focus ({}); scanning from current Z",
                        e.getMessage());
            }
        }

        publish("Focusing...", false);
        CompletableFuture<Void> done = new CompletableFuture<>();
        final boolean runStreaming = useStreaming;
        final String modalityForStreaming = streamingModality;

        // Lock the Live Viewer's stage-movement controls while AF runs, and turn its
        // Autofocus button into a Cancel toggle -- the same affordance as a single-slide
        // scan. Cancel sends ABORTAF (aborts both the streaming and sweep paths); the
        // scan then returns ABORTED/cancelled and the completion below clears the lock.
        final MicroscopeSocketClient socketForCancel = controller.getSocketClient();
        LiveViewerWindow.beginExternalAutofocus(() -> {
            Thread abortThread = new Thread(
                    () -> {
                        try {
                            socketForCancel.abortStreamingFocus();
                        } catch (IOException e) {
                            logger.warn("Slot-jump AF cancel: ABORTAF failed: {}", e.getMessage());
                        }
                    },
                    "MultiSlide-SlotJumpAF-Cancel");
            abortThread.setDaemon(true);
            abortThread.start();
        });

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
                            ApproachPlan plan = resolveApproachPlan(configPath, modalityForStreaming, objective);
                            MicroscopeSocketClient.StreamingFocusResult result = controller
                                    .getSocketClient()
                                    .streamingFocus(
                                            configPath,
                                            null,
                                            modalityForStreaming,
                                            Double.NaN,
                                            false,
                                            SLOT_JUMP_MAX_AF_ATTEMPTS,
                                            plan.safeZUm(),
                                            plan.approachMaxUm(),
                                            plan.requireTissueGate());
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
                                warnIfImplausible(result);
                                // Feed the safe-Z clearance monitor: this is a real,
                                // measured sample plane for the insert currently loaded.
                                SafeZClearanceMonitor.recordFocus(result.finalZ);
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
                    // Release the Live Viewer stage-movement lock and restore the Autofocus
                    // button (whether AF succeeded, failed, or was cancelled).
                    LiveViewerWindow.endExternalAutofocus();
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
