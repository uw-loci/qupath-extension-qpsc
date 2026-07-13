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
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.ui.SiftAutoAlignHelper;
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

    /** One measured correspondence: predicted vs. actual stage position for a tile. */
    private record PointMeasure(double[] predictedStage, double[] measuredStage, PathObject tile) {}

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

        Stage stage = new Stage();
        stage.setTitle("Multi-Tile Alignment Refinement");
        stage.setAlwaysOnTop(true);
        stage.setResizable(false);

        VBox content = new VBox(12);
        content.setPadding(new Insets(18));
        content.setAlignment(Pos.CENTER_LEFT);
        content.setPrefWidth(460);

        Label header = new Label("Multi-Tile Alignment Refinement");
        header.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        Label instructions = new Label("Add 2 or more reference points spread across the slide. For each, pick a "
                + "tile; the stage moves to its predicted position, then SIFT (or a manual nudge) captures where it "
                + "actually is. This solves a rotation + scale correction -- unlike single-tile, which fixes only "
                + "the offset and cannot correct a rotated slide.\n\n"
                + "Spread the points out (far apart, not in a line) for the best rotation estimate.");
        instructions.setWrapText(true);

        Label pointsLabel = new Label("Points captured: 0");
        pointsLabel.setStyle("-fx-font-weight: bold;");

        Label diagLabel = new Label("Add at least 2 points to solve a correction.");
        diagLabel.setStyle("-fx-font-style: italic; -fx-text-fill: #666;");
        diagLabel.setWrapText(true);

        Button addButton = new Button("Add reference point");
        addButton.setDefaultButton(true);

        Button solveButton = new Button("Solve & Save");
        solveButton.setStyle("-fx-font-weight: bold; -fx-base: #4CAF50; -fx-text-fill: white;");
        solveButton.setDisable(true);

        Button cancelButton = new Button("Cancel");

        HBox buttons = new HBox(10, addButton, solveButton, cancelButton);
        buttons.setAlignment(Pos.CENTER_LEFT);

        content.getChildren().addAll(header, instructions, pointsLabel, diagLabel, buttons);
        stage.setScene(new Scene(content));

        Runnable refreshDiagnostics = () -> {
            pointsLabel.setText("Points captured: " + points.size());
            if (points.size() < MIN_POINTS) {
                diagLabel.setText("Add at least " + MIN_POINTS + " points to solve a correction.");
                solveButton.setDisable(true);
                return;
            }
            TransformationFunctions.SimilarityFit fit = solve(points);
            diagLabel.setText(String.format(
                    "Correction from %d points: rotation %.2f deg, scale %.4f, fit RMS %.1f um.%s",
                    fit.pointCount(),
                    fit.rotationDegrees(),
                    fit.scale(),
                    fit.rmsResidualUm(),
                    fit.rmsResidualUm() > 25.0 ? "  High RMS -- check the captured points before saving." : ""));
            solveButton.setDisable(false);
        };

        addButton.setOnAction(e -> {
            addButton.setDisable(true);
            solveButton.setDisable(true);
            cancelButton.setDisable(true);
            capturePoint(gui, initialTransform, points.size() + 1, trustSift, confidenceThreshold)
                    .whenComplete((measure, ex) -> Platform.runLater(() -> {
                        if (ex != null) {
                            logger.warn("Multi-tile point capture failed: {}", ex.getMessage());
                        } else if (measure != null) {
                            points.add(measure);
                            logger.info(
                                    "Captured multi-tile point {} on tile '{}': predicted=({}, {}), measured=({}, {})",
                                    points.size(),
                                    measure.tile().getName(),
                                    measure.predictedStage()[0],
                                    measure.predictedStage()[1],
                                    measure.measuredStage()[0],
                                    measure.measuredStage()[1]);
                        }
                        addButton.setDisable(false);
                        cancelButton.setDisable(false);
                        refreshDiagnostics.run();
                    }));
        });

        solveButton.setOnAction(e -> {
            TransformationFunctions.SimilarityFit fit = solve(points);
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
     * position, SIFT auto-align (or fall back to a manual capture dialog), and
     * return the predicted/measured correspondence. Completes with {@code null} if
     * the operator cancels the tile selection or skips the point.
     */
    private static CompletableFuture<PointMeasure> capturePoint(
            QuPathGUI gui,
            AffineTransform initialTransform,
            int pointNumber,
            boolean trustSift,
            double confidenceThreshold) {

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
                            TransformationFunctions.transformQuPathFullResToStage(tileCoords, initialTransform);
                    // DIAGNOSTIC (2026-07-12 diagonal-transform test): the actual QuPath->stage
                    // mapping for a real tile. With the fix, QuPath-X should drive stage-X (not
                    // stage-Y). The first predicted move should land the live view on the picked
                    // tile; 90-deg-off means the transform is still axis-swapped -- stop and re-check.
                    logger.info(
                            "Multi-tile point {}: tile '{}' QuPath centroid ({}, {}) -> predicted stage ({}, {})",
                            pointNumber,
                            tile.getName(),
                            tileCoords[0],
                            tileCoords[1],
                            predictedStage[0],
                            predictedStage[1]);
                    Platform.runLater(() -> centerViewerOnTile(gui, tile));

                    // Stage move + SIFT are socket I/O: run off the FX thread.
                    new Thread(
                                    () -> {
                                        try {
                                            MicroscopeController.getInstance()
                                                    .moveStageXY(predictedStage[0], predictedStage[1]);
                                            Thread.sleep(500);
                                            Platform.runLater(() -> SiftAutoAlignHelper.drawSearchRangeOnStageMap(
                                                    gui, tile, predictedStage[0], predictedStage[1]));

                                            if (trustSift) {
                                                double[] result = SiftAutoAlignHelper.autoAlign(gui, tile);
                                                if (result != null
                                                        && result.length >= 4
                                                        && result[3] >= confidenceThreshold) {
                                                    double[] measured = MicroscopeController.getInstance()
                                                            .getStagePositionXY();
                                                    logger.info(
                                                            "Multi-tile point {}: SIFT auto-accepted (confidence {})",
                                                            pointNumber,
                                                            result[3]);
                                                    future.complete(new PointMeasure(predictedStage, measured, tile));
                                                    return;
                                                }
                                                logger.info(
                                                        "Multi-tile point {}: SIFT below threshold / failed -- manual capture",
                                                        pointNumber);
                                            }
                                            Platform.runLater(() ->
                                                    showCaptureDialog(gui, tile, predictedStage, pointNumber, future));
                                        } catch (Exception ex) {
                                            logger.warn(
                                                    "Multi-tile point {} move/SIFT failed: {} -- manual capture",
                                                    pointNumber,
                                                    ex.getMessage());
                                            Platform.runLater(() ->
                                                    showCaptureDialog(gui, tile, predictedStage, pointNumber, future));
                                        }
                                    },
                                    "MultiTile-Point-" + pointNumber)
                            .start();
                });

        return future;
    }

    /**
     * Manual capture dialog: the operator nudges the stage (or re-runs SIFT) so the
     * live view matches the selected tile, then captures the stage position for this
     * point, or skips it.
     */
    private static void showCaptureDialog(
            QuPathGUI gui,
            PathObject tile,
            double[] predictedStage,
            int pointNumber,
            CompletableFuture<PointMeasure> future) {

        Stage dialog = new Stage();
        dialog.setTitle("Capture Point #" + pointNumber);
        dialog.setAlwaysOnTop(true);
        dialog.setResizable(false);

        VBox content = new VBox(12);
        content.setPadding(new Insets(16));
        content.setPrefWidth(420);

        Label header = new Label("Capture reference point #" + pointNumber);
        header.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");

        Label instructions = new Label("Nudge the stage (or use Stage Control) so the live view matches the "
                + "selected tile, then click Capture. Or run Auto-Align (SIFT) again. Skip to leave this point out.");
        instructions.setWrapText(true);

        Button siftButton = new Button("Auto-Align (SIFT)");
        siftButton.setOnAction(e -> {
            siftButton.setDisable(true);
            new Thread(
                            () -> {
                                try {
                                    double[] result = SiftAutoAlignHelper.autoAlign(gui, tile);
                                    String msg = (result != null && result.length >= 4)
                                            ? String.format(
                                                    "SIFT ran (confidence %.0f%%). Capture if it looks right.",
                                                    result[3] * 100)
                                            : "SIFT did not find a confident match; nudge manually.";
                                    Platform.runLater(() -> {
                                        Dialogs.showInfoNotification("Auto-Align", msg);
                                        siftButton.setDisable(false);
                                    });
                                } catch (Exception ex) {
                                    logger.warn("Manual-dialog SIFT failed: {}", ex.getMessage());
                                    Platform.runLater(() -> siftButton.setDisable(false));
                                }
                            },
                            "MultiTile-ManualSIFT-" + pointNumber)
                    .start();
        });

        Button captureButton = new Button("Capture position");
        captureButton.setDefaultButton(true);
        captureButton.setStyle("-fx-font-weight: bold; -fx-base: #4CAF50; -fx-text-fill: white;");
        captureButton.setOnAction(e -> {
            new Thread(
                            () -> {
                                try {
                                    double[] measured =
                                            MicroscopeController.getInstance().getStagePositionXY();
                                    Platform.runLater(() -> {
                                        dialog.close();
                                        future.complete(new PointMeasure(predictedStage, measured, tile));
                                    });
                                } catch (Exception ex) {
                                    logger.error("Failed to read stage position for capture", ex);
                                    Platform.runLater(() -> Dialogs.showErrorMessage(
                                            "Capture Error", "Could not read stage position: " + ex.getMessage()));
                                }
                            },
                            "MultiTile-Capture-" + pointNumber)
                    .start();
        });

        Button skipButton = new Button("Skip point");
        skipButton.setOnAction(e -> {
            logger.info("Multi-tile point {} skipped", pointNumber);
            dialog.close();
            future.complete(null);
        });

        HBox buttons = new HBox(10, siftButton, captureButton, skipButton);
        buttons.setAlignment(Pos.CENTER_LEFT);

        content.getChildren().addAll(header, instructions, buttons);
        dialog.setScene(new Scene(content));
        dialog.setOnCloseRequest(e -> {
            if (!future.isDone()) {
                future.complete(null);
            }
        });
        dialog.show();
    }

    /** Solves the stage-space similarity from the captured correspondences. */
    private static TransformationFunctions.SimilarityFit solve(List<PointMeasure> points) {
        double[][] predicted = new double[points.size()][];
        double[][] measured = new double[points.size()][];
        for (int i = 0; i < points.size(); i++) {
            predicted[i] = points.get(i).predictedStage();
            measured[i] = points.get(i).measuredStage();
        }
        return TransformationFunctions.computeStageSpaceSimilarity(predicted, measured);
    }

    /** Centers the viewer on the tile and selects it (mirrors SingleTileRefinement). */
    private static void centerViewerOnTile(QuPathGUI gui, PathObject tile) {
        var viewer = gui.getViewer();
        if (viewer != null && tile.getROI() != null) {
            viewer.setCenterPixelLocation(
                    tile.getROI().getCentroidX(), tile.getROI().getCentroidY());
            viewer.getHierarchy().getSelectionModel().setSelectedObject(tile);
        }
    }
}
