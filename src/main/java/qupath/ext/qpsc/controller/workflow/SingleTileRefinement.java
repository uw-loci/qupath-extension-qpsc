package qupath.ext.qpsc.controller.workflow;

import java.awt.geom.AffineTransform;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.controller.MultiSlideExistingImageWorkflow;
import qupath.ext.qpsc.ui.SiftAutoAlignHelper;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;

/**
 * Helper for single-tile alignment refinement.
 *
 * <p>This class provides functionality to refine an existing alignment by:
 * <ul>
 *   <li>Selecting a single tile from the annotations</li>
 *   <li>Moving the microscope to the estimated position</li>
 *   <li>Allowing manual adjustment of the stage position</li>
 *   <li>Calculating a refined transform based on the adjustment</li>
 * </ul>
 *
 * <p>Single-tile refinement improves alignment accuracy by correcting for
 * small errors in the initial transform.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class SingleTileRefinement {
    private static final Logger logger = LoggerFactory.getLogger(SingleTileRefinement.class);

    /**
     * Result of refinement containing both the transform and the selected tile.
     *
     * <p>{@code accepted} distinguishes a user-confirmed refinement (Capture
     * position, or SIFT auto-accept above the confidence threshold) from a
     * pass-through (Skip point, X-close, or tile-deselection): pass-through
     * returns the initial transform unchanged. Consumers must check
     * {@code accepted} before persisting state -- writing the per-slide JSON on a
     * skip overwrites its {@code flipMacroX/Y} provenance and the alignment
     * cannot then be reconstructed from the original preset (review finding H7).
     */
    public static class RefinementResult {
        public final AffineTransform transform;
        public final PathObject selectedTile;
        public final boolean accepted;

        /**
         * Pass-through constructor: {@code accepted} defaults to {@code false}.
         * Use when returning the initial transform unchanged (Skip / X-close /
         * cancelled tile selection).
         */
        public RefinementResult(AffineTransform transform, PathObject selectedTile) {
            this(transform, selectedTile, false);
        }

        public RefinementResult(AffineTransform transform, PathObject selectedTile, boolean accepted) {
            this.transform = transform;
            this.selectedTile = selectedTile;
            this.accepted = accepted;
        }
    }

    /**
     * Performs single-tile refinement of alignment.
     *
     * <p>This method:
     * <ol>
     *   <li>Prompts user to select a tile</li>
     *   <li>Moves microscope to estimated position</li>
     *   <li>Allows manual position adjustment</li>
     *   <li>Calculates refined transform</li>
     * </ol>
     *
     * @param gui QuPath GUI instance
     * @param annotations List of annotations containing tiles
     * @param initialTransform Initial transform to refine
     * @return CompletableFuture with RefinementResult containing refined transform and selected tile
     */
    /**
     * Overload for backward compatibility -- reads trust SIFT preference.
     */
    public static CompletableFuture<RefinementResult> performRefinement(
            QuPathGUI gui, List<PathObject> annotations, AffineTransform initialTransform) {
        return performRefinement(
                gui,
                annotations,
                initialTransform,
                qupath.ext.qpsc.preferences.PersistentPreferences.isTrustSiftAlignment(),
                qupath.ext.qpsc.preferences.PersistentPreferences.getSiftConfidenceThreshold());
    }

    /**
     * Performs single-tile refinement with optional SIFT auto-accept.
     *
     * @param trustSift If true, attempt SIFT auto-alignment without manual dialog
     * @param confidenceThreshold Minimum inlier ratio to auto-accept (0.0-1.0)
     */
    public static CompletableFuture<RefinementResult> performRefinement(
            QuPathGUI gui,
            List<PathObject> annotations,
            AffineTransform initialTransform,
            boolean trustSift,
            double confidenceThreshold) {

        CompletableFuture<RefinementResult> future = new CompletableFuture<>();

        logger.info("Starting single-tile refinement (trustSift={}, threshold={})", trustSift, confidenceThreshold);

        String classSummary = summarizeAnnotationClasses(annotations);

        // Select tile for refinement
        UIFunctions.promptTileSelectionDialogAsync("Select a tile for alignment refinement.\n"
                        + "Tiles created for " + annotations.size() + " annotation(s)"
                        + (classSummary.isEmpty() ? "" : " of class: " + classSummary) + ".\n"
                        + "The microscope will move to the estimated position for this tile.\n"
                        + (trustSift
                                ? "SIFT auto-alignment will attempt to match automatically."
                                : "You will then manually adjust the stage position to match."))
                .thenAccept(selectedTile -> {
                    if (selectedTile == null) {
                        logger.info("User cancelled tile selection");
                        future.complete(new RefinementResult(initialTransform, null));
                        return;
                    }

                    Platform.runLater(() -> {
                        try {
                            performTileRefinement(
                                    gui, selectedTile, initialTransform, future, trustSift, confidenceThreshold);
                        } catch (Exception e) {
                            logger.error("Error during refinement", e);
                            UIFunctions.notifyUserOfError(
                                    "Error during refinement: " + e.getMessage(), "Refinement Error");
                            future.complete(new RefinementResult(initialTransform, selectedTile));
                        }
                    });
                });

        return future;
    }

    /**
     * Returns a comma-separated, deduplicated list of annotation
     * classification names from {@code annotations}. Empty string if no
     * classifications are set.
     */
    private static String summarizeAnnotationClasses(List<PathObject> annotations) {
        if (annotations == null || annotations.isEmpty()) {
            return "";
        }
        return annotations.stream()
                .map(PathObject::getClassification)
                .filter(c -> c != null && !c.isBlank())
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * Performs the actual tile refinement process.
     *
     * <p>This method:
     * <ol>
     *   <li>Gets tile center coordinates</li>
     *   <li>Transforms to estimated stage position</li>
     *   <li>Validates stage boundaries</li>
     *   <li>Centers viewer on tile</li>
     *   <li>Moves microscope to position</li>
     *   <li>Shows refinement dialog</li>
     * </ol>
     *
     * @param gui QuPath GUI instance
     * @param selectedTile The tile selected for refinement
     * @param initialTransform Initial transform
     * @param future Future to complete with RefinementResult
     * @throws Exception if refinement fails
     */
    private static void performTileRefinement(
            QuPathGUI gui,
            PathObject selectedTile,
            AffineTransform initialTransform,
            CompletableFuture<RefinementResult> future,
            boolean trustSift,
            double confidenceThreshold)
            throws Exception {

        // Get tile coordinates (centroid)
        double[] tileCoords = {
            selectedTile.getROI().getCentroidX(), selectedTile.getROI().getCentroidY()
        };

        // Get frame dimensions from tile ROI (in pixels)
        double frameWidth = selectedTile.getROI().getBoundsWidth();
        double frameHeight = selectedTile.getROI().getBoundsHeight();

        logger.info(
                "Selected tile '{}' at coordinates: ({}, {}), frame size: {}x{}",
                selectedTile.getName(),
                tileCoords[0],
                tileCoords[1],
                frameWidth,
                frameHeight);

        // Post-Step-B: initialTransform is always in unflipped-base pixel frame
        // (AlignmentHelper.checkForSlideAlignment composed any alignment-time
        // flipMacroX/Y from the per-slide JSON into the transform before it
        // reached us). Tile centroids are read from the unflipped base entry, so
        // they go through unchanged. The previous "flip correction" block that
        // shifted by frameWidth/Height is no longer correct in this frame; it
        // was already inert in practice (FlipResolver(null,null,null) returns
        // false), but removing it avoids a future double-flip if a preset is
        // ever threaded through here.
        double[] estimatedStageCoords =
                TransformationFunctions.transformQuPathFullResToStage(tileCoords, initialTransform);

        // Center viewer on tile
        WorkflowHelpers.centerAndSelectTile(gui, selectedTile);

        // Move to estimated position
        logger.info("Moving to estimated position: ({}, {})", estimatedStageCoords[0], estimatedStageCoords[1]);
        MicroscopeController.getInstance().moveStageXY(estimatedStageCoords[0], estimatedStageCoords[1]);

        // Wait for stage to settle
        Thread.sleep(500);

        // Autofocus-on-jump before the operator aligns, so the tile is already in focus when
        // the refinement UI appears. Honors the same multiSlideAutofocusOnJump preference as the
        // slot jump (default on) and no-ops when disabled/disconnected. moveStageXY above is a
        // blocking socket round-trip, so the stage has arrived -- the settle-gate contract
        // SlotJumpAutofocus documents. AF runs off-thread; the returned future ALWAYS completes,
        // and we resume the refinement UI on the FX thread once focus settles (or is skipped).
        SlotJumpAutofocus.runAfterSlotMove()
                .whenComplete((ignored, afEx) -> Platform.runLater(() -> {
                    try {
                        continueRefinementAfterFocus(
                                gui,
                                selectedTile,
                                tileCoords,
                                estimatedStageCoords,
                                initialTransform,
                                future,
                                trustSift,
                                confidenceThreshold);
                    } catch (Exception ex) {
                        logger.error("Error resuming refinement after autofocus", ex);
                        UIFunctions.notifyUserOfError(
                                "Error during refinement: " + ex.getMessage(), "Refinement Error");
                        future.complete(new RefinementResult(initialTransform, selectedTile));
                    }
                }));
    }

    /**
     * Resumes single-tile refinement after the autofocus-on-jump settle: draws the SIFT search
     * range, then either runs the trust-SIFT auto-accept fast path or shows the manual refinement
     * dialog. Extracted from {@link #performTileRefinement} so the stage move and autofocus can
     * complete before the refinement UI is built.
     */
    private static void continueRefinementAfterFocus(
            QuPathGUI gui,
            PathObject selectedTile,
            double[] tileCoords,
            double[] estimatedStageCoords,
            AffineTransform initialTransform,
            CompletableFuture<RefinementResult> future,
            boolean trustSift,
            double confidenceThreshold)
            throws Exception {

        // Multi-slide alignment-step start: force the Stage Map to Camera View and zoom to the
        // tissue box per the batch toggles (no-op outside a multi-slide batch / when the map is
        // closed). The green-box + preset flow drives the stage HERE (not via the manual landmark
        // controller), so this is the alignment-start hook for it.
        MultiSlideExistingImageWorkflow.applyAlignStartViewAssists();

        // Show the SIFT search range on the Stage Map (no-op if it isn't open)
        // centered on the predicted position, so the user can see the area that
        // will be searched for the whole refinement, not just during the match.
        SiftAutoAlignHelper.drawSearchRangeOnStageMap(
                gui, selectedTile, estimatedStageCoords[0], estimatedStageCoords[1]);

        // If trust SIFT is enabled, try auto-alignment first
        if (trustSift) {
            logger.info("Trust SIFT enabled -- attempting automatic alignment");
            new Thread(
                            () -> {
                                try {
                                    // Shared trust-SIFT core (same run + threshold + measure the
                                    // embedded capture pane uses). Kept on a pre-dialog thread so a
                                    // successful auto-accept completes WITHOUT building the refinement
                                    // dialog -- no dialog flash.
                                    SiftCapturePane.AutoAlignOutcome outcome =
                                            SiftCapturePane.attemptAutoAccept(gui, selectedTile, confidenceThreshold);
                                    double[] result = outcome.siftResult();
                                    if (result != null && result.length >= 4) {
                                        double confidence = result[3]; // 4th element = confidence
                                        logger.info(
                                                "SIFT auto-align: offset=({}, {}), confidence={}",
                                                result[0],
                                                result[1],
                                                confidence);
                                        double[] refinedPos = outcome.measuredStageXY();
                                        if (refinedPos != null) {
                                            // Auto-accept: compute refined transform and complete
                                            AffineTransform refined =
                                                    TransformationFunctions.addTranslationToScaledAffine(
                                                            initialTransform, tileCoords, refinedPos);
                                            logger.info(
                                                    "SIFT auto-accepted: confidence {} >= threshold {}",
                                                    confidence,
                                                    confidenceThreshold);
                                            Platform.runLater(() -> {
                                                SiftAutoAlignHelper.clearSearchRangeOnStageMap();
                                                Dialogs.showInfoNotification(
                                                        "SIFT Auto-Align",
                                                        String.format(
                                                                "Alignment refined automatically (confidence %.0f%%)",
                                                                confidence * 100));
                                                future.complete(new RefinementResult(refined, selectedTile, true));
                                            });
                                            return;
                                        } else {
                                            logger.info(
                                                    "SIFT confidence {} below threshold {} -- falling back to manual",
                                                    confidence,
                                                    confidenceThreshold);
                                        }
                                    } else {
                                        logger.info("SIFT matching failed -- falling back to manual dialog");
                                    }
                                } catch (Exception e) {
                                    logger.warn("SIFT auto-align error: {} -- falling back to manual", e.getMessage());
                                }
                                // Fallback: show manual dialog
                                Platform.runLater(() ->
                                        showRefinementDialog(gui, tileCoords, initialTransform, selectedTile, future));
                            },
                            "SIFT-AutoRefine")
                    .start();
            return;
        }

        // Show refinement dialog (non-modal so user can interact with QuPath)
        showRefinementDialog(gui, tileCoords, initialTransform, selectedTile, future);
    }

    /**
     * Shows a non-modal refinement dialog for user interaction.
     *
     * <p>The dialog is non-modal so the user can interact with QuPath while
     * adjusting the stage position. Presents options to:
     * <ul>
     *   <li>Restore the target tile selection if accidentally changed</li>
     *   <li>Save the refined position after manual adjustment</li>
     *   <li>Skip refinement and use initial transform</li>
     *   <li>Create entirely new alignment</li>
     * </ul>
     *
     * @param gui QuPath GUI instance for restoring tile selection
     * @param tileCoords Original tile coordinates in QuPath
     * @param initialTransform Initial transform
     * @param selectedTile The tile that was selected for refinement
     * @param future Future to complete with result
     */
    private static void showRefinementDialog(
            QuPathGUI gui,
            double[] tileCoords,
            AffineTransform initialTransform,
            PathObject selectedTile,
            CompletableFuture<RefinementResult> future) {

        // Create non-modal stage
        Stage dialogStage = new Stage();
        dialogStage.setTitle("Refine Alignment");
        dialogStage.initModality(Modality.NONE); // Non-modal - allows QuPath interaction
        dialogStage.setResizable(false);
        // Dropped setAlwaysOnTop(true): floating over the multi-slide panel occluded
        // its Abort All. Own the QuPath main window instead so this co-floats.
        // TODO(increment: owner=panel) own the consolidated MS panel Stage once
        // reachable; the SiftCapturePane extraction (later increment) folds this in.
        if (gui != null && gui.getStage() != null) {
            dialogStage.initOwner(gui.getStage());
        }

        // Main content
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        content.setAlignment(Pos.CENTER_LEFT);
        content.setPrefWidth(500);

        // Instructions
        Label headerLabel = new Label("Refine Alignment");
        headerLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Label instructionLabel =
                new Label("The microscope has moved to the estimated position for the selected tile.\n\n"
                        + "Click 'Auto-Align (SIFT)' to refine the position automatically.\n"
                        + "If matching fails, use the microscope controls (or Stage Control dialog)\n"
                        + "to nudge the stage so the live view matches the tile, then run SIFT again.\n\n"
                        + "'Capture position' becomes available once SIFT has run successfully.\n"
                        + "If you want to keep the predicted position without refining, click 'Skip point'.");
        instructionLabel.setWrapText(true);

        // Shared capture pane: tile info + Restore + Auto-Align(SIFT) + color-coded
        // result label + Capture/Skip. gateCaptureOnSift=true preserves single-tile's
        // "Capture (Save) is disabled until Auto-Align (SIFT) has run successfully" gate,
        // so a quick click can't silently accept the predicted (unrefined) position.
        SiftCapturePane capturePane = new SiftCapturePane(gui, selectedTile, true);

        // "Create New Alignment" is a single-tile-only third outcome (switch to manual
        // alignment), so it stays outside the shared pane.
        Button newAlignmentButton = new Button("Create New Alignment");
        newAlignmentButton.setOnAction(e -> {
            logger.info("User requested new alignment");
            SiftAutoAlignHelper.clearSearchRangeOnStageMap();
            dialogStage.close();
            future.complete(new RefinementResult(null, selectedTile)); // Signal to switch to manual alignment
        });

        Label siftDescription = new Label(String.format(
                "SIFT searches a ~%.0fum region around the predicted tile position. "
                        + "It requires visible tissue features in the microscope field of view "
                        + "to match against the WSI. Cross-modality preprocessing (bit-depth "
                        + "normalization + CLAHE) handles the 16-bit-camera-vs-8-bit-H&E case. "
                        + "Click Settings to adjust parameters if matching fails.",
                qupath.ext.qpsc.preferences.PersistentPreferences.getSiftSearchMarginUm()));
        siftDescription.setWrapText(true);
        siftDescription.setStyle("-fx-font-size: 10px; -fx-text-fill: #888;");

        // Capture -> translation-only refined transform; Skip -> pass through. The pane
        // returns the measured stage position; the transform math stays here, unchanged
        // (addTranslationToScaledAffine), so single-tile's apply/save contract is
        // preserved exactly. Auto-run is left OFF here: the trust-SIFT auto-accept fast path
        // runs in performTileRefinement BEFORE this dialog is built (so a successful
        // auto-accept never flashes the dialog), sharing SiftCapturePane.attemptAutoAccept
        // -- the same run + threshold + measure core this pane's auto-run path uses.
        capturePane
                .capture(false, 0.0)
                .whenComplete((measured, ex) -> Platform.runLater(() -> {
                    if (ex != null) {
                        logger.warn("Single-tile capture pane failed: {}", ex.getMessage());
                        return;
                    }
                    SiftAutoAlignHelper.clearSearchRangeOnStageMap();
                    dialogStage.close();
                    if (measured != null) {
                        AffineTransform refinedTransform = TransformationFunctions.addTranslationToScaledAffine(
                                initialTransform, tileCoords, measured);
                        logger.info(
                                "Refined stage position: ({}, {}); calculated refined transform",
                                measured[0],
                                measured[1]);
                        Dialogs.showInfoNotification(
                                "Refine Alignment", "The alignment has been refined and saved for this slide.");
                        future.complete(new RefinementResult(refinedTransform, selectedTile, true));
                    } else {
                        logger.info("User skipped refinement");
                        future.complete(new RefinementResult(initialTransform, selectedTile));
                    }
                }));

        // When a multi-slide batch is running, this long-lived refinement dialog can cover the
        // batch panel's Abort All. Surface an "Abort Batch" affordance here so the whole batch
        // stays stoppable without hunting for the panel behind the dialog: it preserves this
        // slide's current alignment (treated as a skip), closes this dialog, then trips the batch
        // abort. Absent for single-slide runs (no batch action registered).
        Runnable batchAbort = MultiSlideExistingImageWorkflow.getBatchAbortAction();
        HBox newAlignmentBox;
        if (batchAbort != null) {
            Button abortBatchButton = new Button("Abort Batch");
            abortBatchButton.setStyle("-fx-base: #b00020; -fx-text-fill: white;");
            abortBatchButton.setTooltip(new Tooltip(
                    "Stop the whole multi-slide batch. Keeps this slide's current alignment; no further slots run."));
            abortBatchButton.setOnAction(e -> {
                logger.info("Refinement dialog: Abort Batch requested");
                SiftAutoAlignHelper.clearSearchRangeOnStageMap();
                dialogStage.close();
                future.complete(new RefinementResult(initialTransform, selectedTile));
                batchAbort.run();
            });
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            newAlignmentBox = new HBox(8, abortBatchButton, spacer, newAlignmentButton);
        } else {
            newAlignmentBox = new HBox(newAlignmentButton);
        }
        newAlignmentBox.setAlignment(Pos.CENTER_RIGHT);

        content.getChildren()
                .addAll(
                        headerLabel,
                        SiftAutoAlignHelper.buildFocusSaturationWarning(),
                        instructionLabel,
                        siftDescription,
                        capturePane,
                        newAlignmentBox);

        // Handle window close (X button)
        dialogStage.setOnCloseRequest(e -> {
            logger.info("Refinement dialog closed without selection");
            SiftAutoAlignHelper.clearSearchRangeOnStageMap();
            future.complete(new RefinementResult(initialTransform, selectedTile));
        });

        Scene scene = new Scene(content);
        dialogStage.setScene(scene);
        dialogStage.show();
    }
}
