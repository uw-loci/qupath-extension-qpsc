package qupath.ext.qpsc.controller.workflow;

import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.ui.SiftAutoAlignHelper;
import qupath.ext.qpsc.ui.ThemeColors;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;

/**
 * Reusable, embeddable capture UI for ONE alignment reference tile.
 *
 * <p>This is a JavaFX {@link Node} (not a top-level {@link javafx.stage.Stage}),
 * so it renders inside its host container instead of floating a separate
 * always-on-top window. It replaces two hand-rolled, near-identical capture
 * bodies: {@code MultiTileRefinement.showCaptureDialog} (the per-point capture
 * Stage, which stacked one window per reference point) and the inline SIFT
 * capture UI of {@link SingleTileRefinement#showRefinementDialog}.
 *
 * <p>Contents: a tile-info label, a "Restore target tile" button, an
 * "Auto-Align (SIFT)" button with a "Settings..." button, a persistent
 * color-coded SIFT result label, and Capture / Skip buttons.
 *
 * <p>Contract: {@link #capture(boolean, double)} starts the flow and returns a
 * {@link CompletableFuture} that completes with the measured stage position
 * {@code [x, y]} (micrometers) when the operator captures (or when a
 * trust-SIFT auto-align lands above the confidence threshold), or {@code null}
 * when the operator skips the point. The pane never computes an alignment
 * transform -- it only surfaces SIFT and hands back the measured stage XY, so
 * every transform / similarity computation stays in the caller (the
 * refinement classes), unchanged.
 *
 * <p>The pane does NOT move the stage: the caller moves to the predicted
 * position (a transform-frame computation) BEFORE embedding the pane. The pane
 * only re-runs SIFT (which nudges the stage to the match) and reads the stage
 * position on capture.
 */
class SiftCapturePane extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(SiftCapturePane.class);

    private final QuPathGUI gui;
    private final PathObject tile;

    /**
     * When {@code true}, the Capture button starts disabled and is enabled only
     * after a SIFT run returns a valid offset. This mirrors single-tile
     * refinement's "Save is disabled until Auto-Align (SIFT) has run
     * successfully" gate, so a quick click cannot silently accept the predicted
     * (unrefined) position. Multi-tile refinement leaves it {@code false}
     * (manual nudge + capture is a first-class path there).
     */
    private final boolean gateCaptureOnSift;

    private final Label siftResultLabel;
    private final Button siftButton;
    private final Button captureButton;
    private final Button skipButton;

    /** Points the operator at the next action within the pane: SIFT first, then capture. */
    private final qupath.ext.qpsc.ui.AttentionPulse pulse = new qupath.ext.qpsc.ui.AttentionPulse();

    private final CompletableFuture<double[]> resultFuture = new CompletableFuture<>();

    /**
     * SIFT confidence at or above which a match is trusted, captured from the first
     * {@link #capture} call so the MANUAL "Auto-Align (SIFT)" button applies the same bar. It
     * used to pass 0.0, which meant a hand-run SIFT had no gate at all -- fine while the only
     * thing that followed was a human deciding whether to press Capture, wrong now that an
     * automatic batch decides from the same result.
     */
    private volatile double confidenceThreshold =
            qupath.ext.qpsc.preferences.PersistentPreferences.getSiftConfidenceThreshold();

    /**
     * @param gui QuPath GUI (for the WSI server / SIFT and tile centering)
     * @param tile the target reference tile
     * @param gateCaptureOnSift disable Capture until a SIFT run has produced a valid offset
     */
    SiftCapturePane(QuPathGUI gui, PathObject tile, boolean gateCaptureOnSift) {
        this(gui, tile, gateCaptureOnSift, "Capture position");
    }

    /**
     * @param captureLabel label for the capture/accept button -- "Capture position" for single-tile,
     *     "Add reference point" for multi-tile (so the button reads as its numbered alignment step).
     */
    SiftCapturePane(QuPathGUI gui, PathObject tile, boolean gateCaptureOnSift, String captureLabel) {
        super(10);
        this.gui = gui;
        this.tile = tile;
        this.gateCaptureOnSift = gateCaptureOnSift;

        setPadding(new Insets(10));
        setAlignment(Pos.CENTER_LEFT);
        setStyle("-fx-border-color: -fx-box-border; -fx-border-width: 1; -fx-border-radius: 4; -fx-padding: 10;");

        String tileName = tile != null && tile.getName() != null ? tile.getName() : "unnamed tile";
        Label tileInfoLabel = new Label("Target tile: " + tileName);
        tileInfoLabel.setStyle("-fx-font-style: italic; -fx-text-fill: " + ThemeColors.MUTED + ";");

        Button restoreButton = new Button("Restore target tile");
        restoreButton.setTooltip(
                new Tooltip("Re-select and center the view on the original target tile if the selection changed."));
        restoreButton.setOnAction(e -> {
            WorkflowHelpers.centerAndSelectTile(gui, tile);
            logger.info("Restored target tile selection: {}", tileName);
        });

        HBox tileRow = new HBox(10, tileInfoLabel, restoreButton);
        tileRow.setAlignment(Pos.CENTER_LEFT);

        // Persistent SIFT result. autoAlign returns [offsetX, offsetY, inliers, confidence];
        // surface all of it here. Coloring lifted from the former per-point capture dialog:
        // green (confident) / amber (weak) / red (no match).
        siftResultLabel = new Label("SIFT: not run for this point yet.");
        siftResultLabel.setWrapText(true);
        siftResultLabel.setStyle("-fx-font-style: italic; -fx-text-fill: " + ThemeColors.MUTED + ";");

        siftButton = new Button("Auto-Align (SIFT)");
        // Amber = the "SIFT" step in the numbered alignment-step list (matches its step label).
        siftButton.setStyle("-fx-font-weight: bold; -fx-base: #E65100; -fx-text-fill: white;");
        siftButton.setTooltip(new Tooltip("Run SIFT to snap the stage to the selected tile automatically."));
        siftButton.setOnAction(e -> runSift(false, confidenceThreshold));

        Button settingsButton = new Button("Settings...");
        settingsButton.setStyle("-fx-font-size: 10px;");
        settingsButton.setOnAction(e -> SiftAutoAlignHelper.showSettingsDialog(
                settingsButton.getScene() != null ? settingsButton.getScene().getWindow() : null));

        captureButton = new Button(captureLabel);
        captureButton.setDefaultButton(true);
        // Teal = the "Add reference point" / capture step in the numbered step list.
        captureButton.setStyle("-fx-font-weight: bold; -fx-base: #00695C; -fx-text-fill: white;");
        captureButton.setTooltip(new Tooltip("Record the current stage position for this reference point."));
        captureButton.setOnAction(e -> captureStagePosition());
        captureButton.setDisable(gateCaptureOnSift);

        skipButton = new Button("Skip point");
        skipButton.setTooltip(new Tooltip("Leave this point out and continue with the points captured so far."));
        skipButton.setOnAction(e -> {
            logger.info("SiftCapturePane: point skipped for tile '{}'", tileName);
            if (!resultFuture.isDone()) {
                resultFuture.complete(null);
            }
        });

        HBox siftRow = new HBox(8, siftButton, settingsButton);
        siftRow.setAlignment(Pos.CENTER_LEFT);

        HBox actionRow = new HBox(10, captureButton, skipButton);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(tileRow, siftRow, siftResultLabel, actionRow);

        // Guide the next action: pulse SIFT first; runSift moves the pulse to Capture on a good
        // match. Clear the pulse whenever the pane finishes (capture / skip / auto-accept).
        pulse.highlight(siftButton, "#E65100");
        resultFuture.whenComplete((r, ex) -> Platform.runLater(pulse::clear));
    }

    /**
     * Starts the capture flow and returns the future that completes with the
     * measured stage {@code [x, y]} (or {@code null} on skip).
     *
     * @param autoRunSift if {@code true}, immediately run SIFT once; if the match
     *     lands at or above {@code confidenceThreshold} the pane auto-accepts
     *     (completes the future with the measured stage position) without waiting
     *     for a manual Capture. This is the trust-SIFT path.
     * @param confidenceThreshold minimum SIFT confidence (0.0-1.0) to auto-accept
     * @return the result future (idempotent -- returns the same future on repeat calls)
     */
    CompletableFuture<double[]> capture(boolean autoRunSift, double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
        // An automatic batch runs SIFT whether or not the operator trusts it by default. The
        // "Trust SIFT alignment" preference is OFF by default and means "always let me confirm"
        // -- a sensible answer for someone sitting at the microscope, and an unsatisfiable one
        // for an unattended run, where nobody will ever press Auto-Align. Left as it was, the
        // pane simply waited: no SIFT, no countdown, no notification, the capture button
        // pulsing at an empty room. Running SIFT does not ACCEPT anything; the confidence gate
        // below still decides that.
        boolean armed = qupath.ext.qpsc.ui.AutoAdvanceController.isArmed()
                && !qupath.ext.qpsc.ui.AutoAdvanceController.isOverriddenThisSlide();
        if (autoRunSift || armed) {
            runSift(autoRunSift, confidenceThreshold);
        }
        return resultFuture;
    }

    /** The result future, for hosts that embed the pane and wire completion separately. */
    CompletableFuture<double[]> resultFuture() {
        return resultFuture;
    }

    /**
     * Runs SIFT auto-align off the FX thread, renders the typed
     * {@code [offsetX, offsetY, inliers, confidence]} result into the color-coded
     * label, and -- when {@code autoAccept} is set -- completes the future with the
     * measured stage position if confidence is at or above {@code threshold}. The
     * confidence gate ({@code result[3] >= threshold}) is identical to the gate the
     * two refinement classes applied inline before this pane existed.
     */
    private void runSift(boolean autoAccept, double threshold) {
        siftButton.setDisable(true);
        // Stop pulsing the SIFT button while SIFT is actually running -- a glowing button reads as
        // "click me again," which is the opposite of what we want mid-run. The pulse is re-pointed
        // (at Capture on a good match, or back at SIFT on no match) when the run returns.
        pulse.clear();
        siftResultLabel.setStyle("-fx-font-style: italic; -fx-text-fill: " + ThemeColors.MUTED + ";");
        siftResultLabel.setText("SIFTing for gold...");
        new Thread(
                        () -> {
                            try {
                                // Shared trust-SIFT core. When not auto-accepting, an unreachable
                                // threshold yields the raw SIFT result for the label without reading
                                // (or accepting) a stage position -- exactly the previous
                                // "accept = autoAccept && ..." semantics.
                                AutoAlignOutcome outcome =
                                        attemptAutoAccept(gui, tile, autoAccept ? threshold : Double.POSITIVE_INFINITY);
                                double[] result = outcome.siftResult();
                                double[] measured = outcome.measuredStageXY();
                                Platform.runLater(() -> {
                                    renderSiftResult(result);
                                    siftButton.setDisable(false);
                                    boolean validMatch = result != null && result.length >= 2;
                                    if (gateCaptureOnSift && validMatch) {
                                        captureButton.setDisable(false);
                                    }
                                    if (measured != null && !resultFuture.isDone()) {
                                        logger.info("SiftCapturePane: SIFT auto-accepted (confidence {})", result[3]);
                                        resultFuture.complete(measured);
                                    } else {
                                        if (validMatch) {
                                            // Good match -- point the operator at Capture / Add reference point.
                                            pulse.highlight(captureButton, "#00695C");
                                        } else {
                                            // No usable match -- keep pointing at SIFT (nudge + re-run).
                                            pulse.highlight(siftButton, "#E65100");
                                        }
                                        // Whether this pane has to drive itself is a property of the
                                        // BATCH, not of how SIFT happened to be started. Gating it on
                                        // autoAccept meant a run with "Trust SIFT alignment" off -- the
                                        // default -- or a SIFT the operator started by hand left the
                                        // pane pulsing at nobody: no capture, no hand-back, no
                                        // notification. The confidence bar is unchanged either way; only
                                        // who presses the button differs.
                                        boolean armed = qupath.ext.qpsc.ui.AutoAdvanceController.isArmed()
                                                && !qupath.ext.qpsc.ui.AutoAdvanceController.isOverriddenThisSlide();
                                        boolean confident =
                                                validMatch && result.length >= 4 && result[3] >= confidenceThreshold;
                                        if (armed && confident) {
                                            // Count down onto Capture rather than completing outright,
                                            // so AUTOMATIC_WITH_OVERRIDE still gives the operator the
                                            // window to step in that every other step gives them.
                                            logger.info(
                                                    "SiftCapturePane: SIFT confidence {} >= {}; counting down onto "
                                                            + "\"{}\"",
                                                    result[3],
                                                    confidenceThreshold,
                                                    captureButton.getText());
                                            qupath.ext.qpsc.ui.AutoAdvanceController.attach(
                                                    (javafx.stage.Stage)
                                                            getScene().getWindow(),
                                                    captureButton);
                                        } else if (armed) {
                                            // Capture is a human judgement from here -- pressing it on an
                                            // unverified position would record a bad correspondence -- so
                                            // hand the slide back and say so, rather than leaving the
                                            // panel silently idle.
                                            qupath.ext.qpsc.ui.AutoAdvanceController.requestOperatorAttention(
                                                    "Multi-tile alignment refinement",
                                                    validMatch
                                                            ? "SIFT confidence below the auto-accept threshold"
                                                            : "SIFT found no usable match on the reference tile");
                                        }
                                    }
                                });
                            } catch (Exception ex) {
                                logger.warn("SiftCapturePane SIFT failed: {}", ex.getMessage());
                                Platform.runLater(() -> {
                                    siftResultLabel.setStyle("-fx-text-fill: " + ThemeColors.ERROR + ";");
                                    siftResultLabel.setText(
                                            "SIFT failed: " + ex.getMessage() + " -- nudge manually, then Capture.");
                                    siftButton.setDisable(false);
                                    pulse.highlight(siftButton, "#E65100");
                                    if (qupath.ext.qpsc.ui.AutoAdvanceController.isArmed()) {
                                        // Same hand-back as a below-threshold match. Without it a SIFT
                                        // that THREW (socket dropped, region file missing) leaves the
                                        // panel idle with nothing driving it -- the only thing that
                                        // eventually notices is the 20-minute setup watchdog.
                                        qupath.ext.qpsc.ui.AutoAdvanceController.requestOperatorAttention(
                                                "Multi-tile alignment refinement",
                                                "SIFT could not run: " + ex.getMessage());
                                    }
                                });
                            }
                        },
                        "SiftCapturePane-SIFT")
                .start();
    }

    /**
     * Outcome of {@link #attemptAutoAccept}: the raw SIFT result and, when the match was accepted,
     * the measured stage position.
     *
     * @param siftResult raw {@code [offsetX, offsetY, inliers, confidence]} from
     *     {@link SiftAutoAlignHelper#autoAlign} (may be {@code null} on no match)
     * @param measuredStageXY the stage {@code [x, y]} read after an accepted match, or {@code null}
     *     when confidence was below threshold (or there was no match)
     */
    record AutoAlignOutcome(double[] siftResult, double[] measuredStageXY) {
        boolean accepted() {
            return measuredStageXY != null;
        }
    }

    /**
     * Shared trust-SIFT core: runs {@link SiftAutoAlignHelper#autoAlign}, applies the confidence
     * gate ({@code result[3] >= threshold}), and -- only when the gate passes -- reads the resulting
     * stage position. This is the single home for the run + threshold + measure logic that this
     * pane's auto-accept path and {@link SingleTileRefinement}'s pre-dialog trust-SIFT fast path
     * both use. Blocking (SIFT match + a stage read); call it off the FX thread.
     *
     * @param gui QuPath GUI (for the WSI server / SIFT)
     * @param tile the target reference tile
     * @param threshold minimum SIFT confidence (0.0-1.0) to accept; pass an unreachable value (e.g.
     *     {@link Double#POSITIVE_INFINITY}) to run SIFT for its raw result without accepting or
     *     reading a stage position
     * @return an {@link AutoAlignOutcome} carrying the raw result and (when accepted) the measured
     *     stage {@code [x, y]}
     * @throws Exception if the SIFT match or stage read fails (propagated for the caller to handle,
     *     exactly as the inline versions did inside their {@code catch (Exception)} blocks)
     */
    static AutoAlignOutcome attemptAutoAccept(QuPathGUI gui, PathObject tile, double threshold) throws Exception {
        double[] result = SiftAutoAlignHelper.autoAlign(gui, tile);
        boolean validMatch = result != null && result.length >= 2;
        if (validMatch) {
            // SIFT has just moved the stage in XY, and the field it lands on is often slightly
            // softer than the one focus was set on -- the sample is not flat. Nothing after this
            // re-focuses, so a Z captured here is the Z that gets saved and later seeds
            // acquisition. Re-focus BEFORE anything reads the position, so both the auto-accept
            // path below and a manual Capture get a focused Z.
            refocusAfterSiftMove();
        }
        boolean accept = validMatch && result.length >= 4 && result[3] >= threshold;
        double[] measured = accept ? MicroscopeController.getInstance().getStagePositionXY() : null;
        return new AutoAlignOutcome(result, measured);
    }

    /**
     * Short focus correction after a SIFT move. Best-effort: any failure leaves the stage where
     * SIFT put it, which is exactly what happened before this existed.
     *
     * <p>Uses the SWEEP drift check rather than a full search, because the premise here is that
     * focus is already close -- SIFT moved within a field or two of a plane that was in focus a
     * moment ago. That premise is what makes SWEEP the right tool and a full streaming scan the
     * wrong one: SWEEP is quick and reports success without finding focus when Z is far off,
     * which is a bad property in general and a harmless one when the answer is nearby.
     */
    private static void refocusAfterSiftMove() {
        if (qupath.ext.qpsc.preferences.QPPreferenceDialog.getDisableAllAutofocus()) {
            logger.debug("Post-SIFT refocus skipped: autofocus is disabled in preferences");
            return;
        }
        MicroscopeController controller = MicroscopeController.getInstance();
        if (controller == null || !controller.isConnected() || controller.isAcquisitionActive()) {
            return;
        }
        String configPath = qupath.ext.qpsc.preferences.QPPreferenceDialog.getMicroscopeConfigFileProperty();
        if (configPath == null || configPath.isBlank()) {
            return;
        }
        try {
            var mgr = qupath.ext.qpsc.utilities.MicroscopeConfigManager.getInstance(configPath);
            String objective = qupath.ext.qpsc.controller.TestAutofocusWorkflow.getCurrentObjective(mgr);
            String outputPath = qupath.ext.qpsc.controller.TestAutofocusWorkflow.getDefaultOutputPath();
            SweepAutofocusRunner.SweepResult sweep =
                    SweepAutofocusRunner.run(controller, configPath, outputPath, objective);
            if (sweep.cancelled()) {
                logger.info("Post-SIFT refocus cancelled by the operator");
            } else {
                logger.info(
                        "Post-SIFT refocus: Z {} -> {} (shift {})", sweep.initialZ(), sweep.finalZ(), sweep.zShift());
            }
        } catch (Exception e) {
            logger.warn("Post-SIFT refocus failed ({}); capturing at the position SIFT left", e.getMessage());
        }
    }

    /** Reads the stage position off the FX thread and completes with it (manual Capture). */
    private void captureStagePosition() {
        new Thread(
                        () -> {
                            try {
                                double[] measured =
                                        MicroscopeController.getInstance().getStagePositionXY();
                                Platform.runLater(() -> {
                                    if (!resultFuture.isDone()) {
                                        resultFuture.complete(measured);
                                    }
                                });
                            } catch (Exception ex) {
                                logger.error("SiftCapturePane failed to read stage position for capture", ex);
                                Platform.runLater(() -> qupath.fx.dialogs.Dialogs.showErrorMessage(
                                        "Capture Error", "Could not read stage position: " + ex.getMessage()));
                            }
                        },
                        "SiftCapturePane-Capture")
                .start();
    }

    /** Color-codes the persistent SIFT label from the typed autoAlign result. */
    private void renderSiftResult(double[] result) {
        if (result != null && result.length >= 4) {
            double conf = result[3];
            siftResultLabel.setStyle(
                    conf >= 0.5
                            ? "-fx-text-fill: " + ThemeColors.SUCCESS + "; -fx-font-weight: bold;"
                            : "-fx-text-fill: " + ThemeColors.WARNING + "; -fx-font-weight: bold;");
            siftResultLabel.setText(String.format(
                    "SIFT: confidence %.0f%%, %d inliers, moved (%.1f, %.1f) um. "
                            + "Capture if the live view matches the tile.",
                    conf * 100, (int) result[2], result[0], result[1]));
        } else {
            siftResultLabel.setStyle("-fx-text-fill: " + ThemeColors.ERROR + "; -fx-font-weight: bold;");
            siftResultLabel.setText("SIFT: no confident match. Nudge the stage manually, "
                    + "then Capture -- or try Auto-Align again.");
        }
    }
}
