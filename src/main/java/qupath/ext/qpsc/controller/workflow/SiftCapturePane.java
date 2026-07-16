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
        tileInfoLabel.setStyle("-fx-font-style: italic; -fx-text-fill: #666;");

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
        siftResultLabel.setStyle("-fx-font-style: italic; -fx-text-fill: #555;");

        siftButton = new Button("Auto-Align (SIFT)");
        // Amber = the "SIFT" step in the numbered alignment-step list (matches its step label).
        siftButton.setStyle("-fx-font-weight: bold; -fx-base: #E65100; -fx-text-fill: white;");
        siftButton.setTooltip(new Tooltip("Run SIFT to snap the stage to the selected tile automatically."));
        siftButton.setOnAction(e -> runSift(false, 0.0));

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
        if (autoRunSift) {
            runSift(true, confidenceThreshold);
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
        siftResultLabel.setStyle("-fx-font-style: italic; -fx-text-fill: #555;");
        siftResultLabel.setText("SIFT: running...");
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
                                    } else if (validMatch) {
                                        // Good match -- point the operator at Capture / Add reference point.
                                        pulse.highlight(captureButton, "#00695C");
                                    } else {
                                        // No usable match -- keep pointing at SIFT (nudge + re-run).
                                        pulse.highlight(siftButton, "#E65100");
                                    }
                                });
                            } catch (Exception ex) {
                                logger.warn("SiftCapturePane SIFT failed: {}", ex.getMessage());
                                Platform.runLater(() -> {
                                    siftResultLabel.setStyle("-fx-text-fill: #c0362c;");
                                    siftResultLabel.setText(
                                            "SIFT failed: " + ex.getMessage() + " -- nudge manually, then Capture.");
                                    siftButton.setDisable(false);
                                    pulse.highlight(siftButton, "#E65100");
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
        boolean accept = result != null && result.length >= 4 && result[3] >= threshold;
        double[] measured = accept ? MicroscopeController.getInstance().getStagePositionXY() : null;
        return new AutoAlignOutcome(result, measured);
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
                            ? "-fx-text-fill: #1c8552; -fx-font-weight: bold;"
                            : "-fx-text-fill: #a5640c; -fx-font-weight: bold;");
            siftResultLabel.setText(String.format(
                    "SIFT: confidence %.0f%%, %d inliers, moved (%.1f, %.1f) um. "
                            + "Capture if the live view matches the tile.",
                    conf * 100, (int) result[2], result[0], result[1]));
        } else {
            siftResultLabel.setStyle("-fx-text-fill: #c0362c; -fx-font-weight: bold;");
            siftResultLabel.setText("SIFT: no confident match. Nudge the stage manually, "
                    + "then Capture -- or try Auto-Align again.");
        }
    }
}
