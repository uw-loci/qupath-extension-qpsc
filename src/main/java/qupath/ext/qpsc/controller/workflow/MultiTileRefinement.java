package qupath.ext.qpsc.controller.workflow;

import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.controller.MultiSlideExistingImageWorkflow;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.ui.SiftAutoAlignHelper;
import qupath.ext.qpsc.utilities.DocumentationHelper;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;

/**
 * Multi-tile alignment refinement: corrects an existing alignment by an
 * arbitrary rotation + scale + translation (a rigid/similarity correction),
 * not just the translation offset that {@link SingleTileRefinement} solves.
 *
 * <p>Motivation: {@link SingleTileRefinement} keeps the initial transform's
 * rotation and scale and only shifts the origin so ONE picked tile lands
 * exactly ({@code addTranslationToScaledAffine}). If the slide sits rotated in
 * its holder slot -- the "freedom of movement in the quad holder" case -- a
 * single-tile translation is zero error at the picked tile but grows linearly
 * with distance from it (perfect at the tile, far off across the slide). A
 * glass slide is rigid, so its play in the slot is a rotation + translation
 * (and any residual scale is a pixel-size/objective mismatch); correcting it
 * needs at least two measured reference points.
 *
 * <p>Flow: the operator adds 2+ reference points. For each, they pick a tile;
 * the stage moves to that tile's predicted position (from the current
 * alignment); SIFT auto-aligns (or they nudge the stage manually); and the
 * measured stage position is captured. Both the predicted and measured
 * positions are in STAGE micrometers, so the correction between them is a
 * proper similarity with no reflection ambiguity
 * ({@link TransformationFunctions#computeStageSpaceSimilarity}). The correction
 * is composed onto the initial transform. The recovered rotation and scale are
 * shown live as a diagnostic (rotation != 0 -> slide play; scale != 1 ->
 * residual pixel-size mismatch).
 *
 * <p>Returns a {@link SingleTileRefinement.RefinementResult} so the workflow's
 * existing accepted/save handling is reused unchanged.
 */
public class MultiTileRefinement {
    private static final Logger logger = LoggerFactory.getLogger(MultiTileRefinement.class);

    /** Minimum reference points before a similarity can be solved. */
    private static final int MIN_POINTS = 2;

    /**
     * Plausibility thresholds for the recovered correction. A refinement of genuine slide
     * play in a holder slot is a few degrees of rotation and ~1.0 scale. A much larger
     * correction means the base alignment does not match the stage -- most importantly, a
     * rotation near 90/180/270 deg is the signature of a transform that is still axis-swapped
     * (e.g. a stale pre-fix per-slide JSON, or the diagonal-transform fix not taking). Warn at
     * the 2-point mark (the earliest the rotation/scale are known) so the operator can stop
     * before wasting more points. Warn-only, not a hard block -- the operator may know better.
     */
    private static final double ROTATION_WARN_DEG = 10.0;

    private static final double SCALE_WARN_LOW = 0.9;
    private static final double SCALE_WARN_HIGH = 1.1;

    /**
     * One measured correspondence for the similarity solve: the tile's QuPath centroid and
     * the stage position it actually landed at. The centroid (not a precomputed predicted
     * stage) is stored so the solve can recompute predicted positions against the ORIGINAL
     * transform for every point -- consistent even though each point is PREDICTED with a
     * progressively improved estimate (see {@link #computeWorkingEstimate}).
     */
    private record PointMeasure(double[] tileCentroidQP, double[] measuredStage, PathObject tile) {}

    /**
     * Multi-tile refinement using the trust-SIFT and confidence preferences.
     */
    public static CompletableFuture<SingleTileRefinement.RefinementResult> performRefinement(
            QuPathGUI gui, List<PathObject> annotations, AffineTransform initialTransform) {
        return performRefinement(
                gui,
                annotations,
                initialTransform,
                PersistentPreferences.isTrustSiftAlignment(),
                PersistentPreferences.getSiftConfidenceThreshold());
    }

    /**
     * Multi-tile refinement with explicit SIFT settings.
     *
     * @param trustSift attempt SIFT auto-align at each point before falling back to manual capture
     * @param confidenceThreshold minimum SIFT inlier ratio to auto-accept a point (0.0-1.0)
     */
    public static CompletableFuture<SingleTileRefinement.RefinementResult> performRefinement(
            QuPathGUI gui,
            List<PathObject> annotations,
            AffineTransform initialTransform,
            boolean trustSift,
            double confidenceThreshold) {

        CompletableFuture<SingleTileRefinement.RefinementResult> future = new CompletableFuture<>();
        logger.info(
                "Starting multi-tile refinement (trustSift={}, threshold={}, annotations={})",
                trustSift,
                confidenceThreshold,
                annotations == null ? 0 : annotations.size());
        Platform.runLater(() -> showPanel(gui, initialTransform, trustSift, confidenceThreshold, future));
        return future;
    }

    private static void showPanel(
            QuPathGUI gui,
            AffineTransform initialTransform,
            boolean trustSift,
            double confidenceThreshold,
            CompletableFuture<SingleTileRefinement.RefinementResult> future) {

        List<PointMeasure> points = new ArrayList<>();
        // The transform used to PREDICT the next point's stage position. Starts as the
        // initial alignment and is refined after every captured point (translation-only
        // after 1 point, full similarity after 2+), so each successive point is predicted
        // closer to the truth instead of repeating the initial transform's error.
        final AffineTransform[] workingEstimate = {new AffineTransform(initialTransform)};

        Stage stage = new Stage();
        stage.setTitle("Multi-Tile Alignment Refinement");
        // Dropped setAlwaysOnTop(true): floating over the multi-slide panel occluded
        // its Abort All. Own the QuPath main window instead so this co-floats.
        // TODO(increment: owner=panel) own the consolidated MS panel Stage once
        // reachable; the body fold into Section C is a later increment.
        if (gui != null && gui.getStage() != null) {
            stage.initOwner(gui.getStage());
        }
        stage.setResizable(false);

        VBox content = new VBox(12);
        content.setPadding(new Insets(18));
        content.setAlignment(Pos.CENTER_LEFT);
        content.setPrefWidth(460);

        Label header = new Label("Multi-Tile Alignment Refinement");
        header.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        // Numbered, color-coded alignment steps. Each step's color matches its button so the
        // operator can associate a step with the control that performs it. Spread points out (far
        // apart, not in a line) for the best rotation estimate; each captured point improves the
        // prediction for the next.
        Label stepsHeader = new Label("Alignment steps (repeat 1-3 for each point, then 4):");
        stepsHeader.setStyle("-fx-font-weight: bold;");
        Label step1 = stepLabel(
                "1.  Select a tile on the QuPath image where you want to image -- the blue \"Select tile\" "
                        + "button moves the stage to its predicted position.",
                "#1565C0");
        Label step2 =
                stepLabel("2.  Click the amber \"Auto-Align (SIFT)\" to snap the stage onto that tile.", "#E65100");
        Hyperlink siftHelp = new Hyperlink("SIFT struggling? Open the alignment docs, then adjust \"Settings...\"");
        siftHelp.setStyle("-fx-text-fill: #E65100;");
        siftHelp.setOnAction(e -> DocumentationHelper.openDocumentation("microscopeAlignment"));
        Label step2b = stepLabel(
                "2b. If it lands off-target, nudge the stage toward the tile via the Stage Map, then run "
                        + "SIFT again.",
                "#E65100");
        Label step3 =
                stepLabel("3.  Click the teal \"Add reference point\" once the live view matches the tile.", "#00695C");
        Label step4 = stepLabel("4.  With 2 or more points captured, click the green \"Solve & Save\".", "#2E7D32");
        VBox instructions = new VBox(4, stepsHeader, step1, step2, siftHelp, step2b, step3, step4);

        Label pointsLabel = new Label("Points captured: 0");
        pointsLabel.setStyle("-fx-font-weight: bold;");

        // The "Point N capture" slot from the folded wireframe: a per-point
        // SiftCapturePane is swapped in here while a point is being captured,
        // then cleared. Embedding it (rather than opening a separate always-on-top
        // Stage per point, as showCaptureDialog used to) is what eliminates the
        // stacked capture dialogs.
        VBox captureSlot = new VBox();
        captureSlot.setFillWidth(true);

        Label diagLabel = new Label("Add at least 2 points to solve a correction.");
        diagLabel.setStyle("-fx-font-style: italic; -fx-text-fill: #666;");
        diagLabel.setWrapText(true);

        Button addButton = new Button("Select tile");
        addButton.setDefaultButton(true);
        // Blue = step 1 "Select tile" in the numbered step list.
        addButton.setStyle("-fx-font-weight: bold; -fx-base: #1565C0; -fx-text-fill: white;");
        addButton.setTooltip(new Tooltip("Pick the next reference tile on the QuPath image; the stage moves to it."));

        Button solveButton = new Button("Solve & Save");
        solveButton.setStyle("-fx-font-weight: bold; -fx-base: #4CAF50; -fx-text-fill: white;");
        solveButton.setDisable(true);

        Button cancelButton = new Button("Cancel");

        HBox buttons = new HBox(10, addButton, solveButton, cancelButton);
        buttons.setAlignment(Pos.CENTER_LEFT);

        content.getChildren().addAll(header, instructions, pointsLabel, captureSlot, diagLabel, buttons);
        stage.setScene(new Scene(content));

        Runnable refreshDiagnostics = () -> {
            pointsLabel.setText("Points captured: " + points.size());
            if (points.size() < MIN_POINTS) {
                diagLabel.setText("Add at least " + MIN_POINTS + " points to solve a correction.");
                solveButton.setDisable(true);
                return;
            }
            TransformationFunctions.SimilarityFit fit = solve(points, initialTransform);
            boolean rotationSuspect = Math.abs(fit.rotationDegrees()) > ROTATION_WARN_DEG;
            boolean scaleSuspect = fit.scale() < SCALE_WARN_LOW || fit.scale() > SCALE_WARN_HIGH;
            if (rotationSuspect || scaleSuspect) {
                // The correction is far larger than slide play -> the base alignment does not
                // match the stage. Flag it hard (red) at the earliest point it is knowable.
                logger.warn(
                        "Multi-tile correction implausible after {} points: rotation {} deg, scale {} "
                                + "-- base alignment likely does not match the stage (axis swap / wrong orientation)",
                        fit.pointCount(),
                        String.format("%.2f", fit.rotationDegrees()),
                        String.format("%.4f", fit.scale()));
                diagLabel.setStyle("-fx-text-fill: #C62828; -fx-font-weight: bold;");
                diagLabel.setText(String.format(
                        "WARNING: correction is rotation %.1f deg, scale %.3f -- much larger than slide play. "
                                + "The base alignment likely does NOT match the stage (a rotation near 90/180/270 "
                                + "means the transform is still axis-swapped -- e.g. a stale per-slide alignment, or "
                                + "the wrong entry orientation). Re-check the alignment before saving; Solve is still "
                                + "available if you are sure.",
                        fit.rotationDegrees(), fit.scale()));
            } else {
                diagLabel.setStyle("-fx-font-style: italic; -fx-text-fill: #666;");
                diagLabel.setText(String.format(
                        "Correction from %d points: rotation %.2f deg, scale %.4f, fit RMS %.1f um.%s",
                        fit.pointCount(),
                        fit.rotationDegrees(),
                        fit.scale(),
                        fit.rmsResidualUm(),
                        fit.rmsResidualUm() > 25.0 ? "  High RMS -- check the captured points before saving." : ""));
            }
            solveButton.setDisable(false);
        };

        addButton.setOnAction(e -> {
            addButton.setDisable(true);
            solveButton.setDisable(true);
            cancelButton.setDisable(true);
            // Predict this point with the running estimate (refined by prior points), NOT
            // the raw initial transform.
            capturePoint(gui, workingEstimate[0], points.size() + 1, trustSift, confidenceThreshold, captureSlot)
                    .whenComplete((measure, ex) -> Platform.runLater(() -> {
                        if (ex != null) {
                            logger.warn("Multi-tile point capture failed: {}", ex.getMessage());
                        } else if (measure != null) {
                            points.add(measure);
                            // Fold the new point into the estimate used to predict the next one.
                            workingEstimate[0] = computeWorkingEstimate(points, initialTransform);
                            logger.info(
                                    "Captured multi-tile point {} on tile '{}': centroid=({}, {}), measured=({}, {}); "
                                            + "estimate updated for next prediction",
                                    points.size(),
                                    measure.tile().getName(),
                                    measure.tileCentroidQP()[0],
                                    measure.tileCentroidQP()[1],
                                    measure.measuredStage()[0],
                                    measure.measuredStage()[1]);
                        }
                        addButton.setDisable(false);
                        cancelButton.setDisable(false);
                        refreshDiagnostics.run();
                    }));
        });

        solveButton.setOnAction(e -> {
            TransformationFunctions.SimilarityFit fit = solve(points, initialTransform);
            AffineTransform refined = new AffineTransform(fit.correction());
            refined.concatenate(initialTransform);
            logger.info(
                    "Multi-tile refinement accepted: {} points, rotation={} deg, scale={}, RMS={} um",
                    fit.pointCount(),
                    String.format("%.3f", fit.rotationDegrees()),
                    String.format("%.5f", fit.scale()),
                    String.format("%.2f", fit.rmsResidualUm()));
            SiftAutoAlignHelper.clearSearchRangeOnStageMap();
            Dialogs.showInfoNotification(
                    "Multi-Tile Refinement",
                    String.format(
                            "Alignment refined from %d points (rotation %.2f deg, scale %.4f).",
                            fit.pointCount(), fit.rotationDegrees(), fit.scale()));
            stage.close();
            future.complete(new SingleTileRefinement.RefinementResult(
                    refined, points.get(0).tile(), true));
        });

        cancelButton.setOnAction(e -> {
            logger.info(
                    "Multi-tile refinement cancelled ({} points captured); preserving prior alignment", points.size());
            SiftAutoAlignHelper.clearSearchRangeOnStageMap();
            stage.close();
            future.complete(new SingleTileRefinement.RefinementResult(
                    initialTransform, points.isEmpty() ? null : points.get(0).tile(), false));
        });

        stage.setOnCloseRequest(e -> {
            if (!future.isDone()) {
                logger.info("Multi-tile refinement window closed; preserving prior alignment");
                SiftAutoAlignHelper.clearSearchRangeOnStageMap();
                future.complete(new SingleTileRefinement.RefinementResult(
                        initialTransform,
                        points.isEmpty() ? null : points.get(0).tile(),
                        false));
            }
        });

        stage.show();
    }

    /**
     * Captures one reference point: prompt for a tile, move to its predicted stage
     * position, then hand off to an embedded {@link SiftCapturePane} (SIFT auto-align
     * or manual nudge + capture) hosted in {@code captureSlot}. Returns the
     * predicted/measured correspondence, or {@code null} if the operator cancels the
     * tile selection or skips the point.
     *
     * <p>The pane is embedded in the refinement panel's own capture slot rather than
     * opened as a separate always-on-top Stage per point, which removes the stacked
     * capture dialogs. The trust-SIFT auto-accept gate now lives in the pane
     * ({@code capture(trustSift, confidenceThreshold)}) -- same predicate as before
     * ({@code confidence >= confidenceThreshold}).
     */
    private static CompletableFuture<PointMeasure> capturePoint(
            QuPathGUI gui,
            AffineTransform estimate,
            int pointNumber,
            boolean trustSift,
            double confidenceThreshold,
            VBox captureSlot) {

        CompletableFuture<PointMeasure> future = new CompletableFuture<>();

        qupath.ext.qpsc.ui.UIFunctions.promptTileSelectionDialogAsync("Select reference tile #" + pointNumber
                        + " for multi-tile refinement.\n"
                        + "The microscope will move to its predicted position, then SIFT (or a manual nudge) "
                        + "captures where it actually is.")
                .thenAccept(tile -> {
                    if (tile == null || tile.getROI() == null) {
                        logger.info("Multi-tile: tile selection cancelled for point {}", pointNumber);
                        future.complete(null);
                        return;
                    }
                    double[] tileCoords = {
                        tile.getROI().getCentroidX(), tile.getROI().getCentroidY()
                    };
                    double[] predictedStage =
                            TransformationFunctions.transformQuPathFullResToStage(tileCoords, estimate);
                    // DIAGNOSTIC (2026-07-12 diagonal-transform test): the actual QuPath->stage
                    // mapping for a real tile, using the running estimate. With the diagonal fix,
                    // QuPath-X should drive stage-X (not stage-Y). The first point's move (raw
                    // initial transform) should land near the picked tile; 90-deg-off means the
                    // transform is still axis-swapped -- stop and re-check.
                    logger.info(
                            "Multi-tile point {}: tile '{}' QuPath centroid ({}, {}) -> predicted stage ({}, {})",
                            pointNumber,
                            tile.getName(),
                            tileCoords[0],
                            tileCoords[1],
                            predictedStage[0],
                            predictedStage[1]);
                    Platform.runLater(() -> WorkflowHelpers.centerAndSelectTile(gui, tile));

                    // Stage move is socket I/O: run off the FX thread. Then embed the shared
                    // capture pane on the FX thread and let it drive SIFT / manual capture.
                    new Thread(
                                    () -> {
                                        try {
                                            MicroscopeController.getInstance()
                                                    .moveStageXY(predictedStage[0], predictedStage[1]);
                                            Thread.sleep(500);
                                            // Autofocus-on-jump before capture, so the tile is in
                                            // focus when the capture pane appears. Honors the same
                                            // multiSlideAutofocusOnJump preference as the slot jump
                                            // (default on; no-op when disabled/disconnected). We are
                                            // off the FX thread here, so block until AF settles -- the
                                            // returned future always completes.
                                            SlotJumpAutofocus.runAfterSlotMove().join();
                                            Platform.runLater(() -> {
                                                // Multi-slide alignment-step start: force Camera View
                                                // + zoom to tissue once, at the first point (no-op
                                                // outside a batch / when the map is closed).
                                                if (pointNumber == 1) {
                                                    MultiSlideExistingImageWorkflow.applyAlignStartViewAssists();
                                                }
                                                SiftAutoAlignHelper.drawSearchRangeOnStageMap(
                                                        gui, tile, predictedStage[0], predictedStage[1]);
                                                hostCapturePane(
                                                        captureSlot,
                                                        gui,
                                                        tile,
                                                        tileCoords,
                                                        trustSift,
                                                        confidenceThreshold,
                                                        future);
                                            });
                                        } catch (Exception ex) {
                                            logger.warn(
                                                    "Multi-tile point {} stage move failed: {} -- manual capture",
                                                    pointNumber,
                                                    ex.getMessage());
                                            // Move failed: present the pane in manual mode (no auto-SIFT).
                                            Platform.runLater(() -> hostCapturePane(
                                                    captureSlot,
                                                    gui,
                                                    tile,
                                                    tileCoords,
                                                    false,
                                                    confidenceThreshold,
                                                    future));
                                        }
                                    },
                                    "MultiTile-Point-" + pointNumber)
                            .start();
                });

        return future;
    }

    /**
     * Embeds a fresh {@link SiftCapturePane} into {@code captureSlot} and wires its
     * result to {@code future}: a captured/auto-accepted stage position becomes a
     * {@link PointMeasure} (with the tile's QuPath centroid preserved for the solve),
     * a skip completes with {@code null}. Clears the slot when the pane settles. Must
     * be called on the FX thread.
     */
    /** A wrapped, bold, color-coded step label (color matches the step's button). */
    private static Label stepLabel(String text, String colorHex) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setStyle("-fx-text-fill: " + colorHex + "; -fx-font-weight: bold;");
        return l;
    }

    private static void hostCapturePane(
            VBox captureSlot,
            QuPathGUI gui,
            PathObject tile,
            double[] tileCoords,
            boolean autoRunSift,
            double confidenceThreshold,
            CompletableFuture<PointMeasure> future) {
        // gateCaptureOnSift=false: multi-tile allows a manual nudge + capture without
        // a prior SIFT run (unlike single-tile's Save-after-SIFT gate).
        SiftCapturePane pane = new SiftCapturePane(gui, tile, false, "Add reference point");
        captureSlot.getChildren().setAll(pane);
        pane.capture(autoRunSift, confidenceThreshold)
                .whenComplete((measured, ex) -> Platform.runLater(() -> {
                    captureSlot.getChildren().clear();
                    if (ex != null) {
                        logger.warn("Multi-tile capture pane failed: {}", ex.getMessage());
                        future.complete(null);
                    } else if (measured != null) {
                        future.complete(new PointMeasure(tileCoords, measured, tile));
                    } else {
                        future.complete(null);
                    }
                }));
    }

    /**
     * Solves the stage-space similarity from the captured correspondences. Predicted
     * positions are recomputed against the ORIGINAL {@code initialTransform} (not the
     * running estimate) so every point is compared on the same footing, regardless of the
     * progressively-refined estimate each was predicted with.
     */
    private static TransformationFunctions.SimilarityFit solve(
            List<PointMeasure> points, AffineTransform initialTransform) {
        double[][] predicted = new double[points.size()][];
        double[][] measured = new double[points.size()][];
        for (int i = 0; i < points.size(); i++) {
            predicted[i] = TransformationFunctions.transformQuPathFullResToStage(
                    points.get(i).tileCentroidQP(), initialTransform);
            measured[i] = points.get(i).measuredStage();
        }
        return TransformationFunctions.computeStageSpaceSimilarity(predicted, measured);
    }

    /**
     * The transform used to PREDICT the next reference point, refined by the points captured
     * so far. With 1 point it applies a translation-only correction (shift the initial
     * transform so that point lands exactly), which already removes most of the initial
     * offset. With 2+ points it applies the full similarity (rotation + scale + translation).
     * Composed onto {@code initialTransform} so it maps QuPath pixels -> stage.
     */
    private static AffineTransform computeWorkingEstimate(List<PointMeasure> points, AffineTransform initialTransform) {
        if (points.isEmpty()) {
            return new AffineTransform(initialTransform);
        }
        if (points.size() == 1) {
            double[] centroid = points.get(0).tileCentroidQP();
            double[] predicted = TransformationFunctions.transformQuPathFullResToStage(centroid, initialTransform);
            double[] measured = points.get(0).measuredStage();
            AffineTransform est =
                    AffineTransform.getTranslateInstance(measured[0] - predicted[0], measured[1] - predicted[1]);
            est.concatenate(initialTransform);
            return est;
        }
        TransformationFunctions.SimilarityFit fit = solve(points, initialTransform);
        AffineTransform est = new AffineTransform(fit.correction());
        est.concatenate(initialTransform);
        return est;
    }
}
