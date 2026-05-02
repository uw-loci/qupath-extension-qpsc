package qupath.ext.qpsc.ui;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.scripting.QP;

/**
 * Handles user interactions for affine transformation validation and interactive stage alignment.
 * Guides the user through aligning selected tiles to the live stage view,
 * refines the affine transform, and returns the final transform via CompletableFuture.
 */
public class AffineTransformationController {

    private static final Logger logger = LoggerFactory.getLogger(AffineTransformationController.class);

    /**
     * A paired pixel coordinate and measured stage coordinate collected during alignment.
     *
     * @param pixelPoint  the tile centroid in full-resolution pixel coordinates
     * @param stagePoint  the measured stage position (um) after the user confirmed alignment
     * @param tileName    human-readable name of the tile used for this point
     */
    public record AlignmentPoint(Point2D pixelPoint, Point2D stagePoint, String tileName) {}

    /**
     * Alignment points collected during the most recent alignment session.
     * Populated by {@link #setupAffineTransformationAndValidationGUI} and its
     * refinement steps. The list is cleared at the start of each new session.
     *
     * <p>Thread-safety: all mutations happen on the FX application thread (inside
     * {@code Platform.runLater} or {@code CompletableFuture} callbacks that execute
     * on the FX thread), so no additional synchronization is needed.
     */
    private static final List<AlignmentPoint> alignmentPoints = new ArrayList<>();

    /** Maximum residual (um) before warning that the transform is degrading. */
    private static final double RESIDUAL_WARN_UM = 200.0;

    /**
     * Returns an unmodifiable snapshot of the alignment points collected during the
     * most recent alignment session.  Each point pairs a full-resolution pixel
     * coordinate with the measured stage position the user confirmed.
     *
     * @return unmodifiable list of alignment points (may be empty if no session has run)
     */
    public static List<AlignmentPoint> getAlignmentPoints() {
        return Collections.unmodifiableList(new ArrayList<>(alignmentPoints));
    }

    /**
     * Launches the full affine alignment GUI flow with manual stage navigation.
     *
     * @param macroPixelSizeMicrons Pixel size of macro image in microns
     * @param stageInvertedX        Whether the stage X axis is inverted
     * @param stageInvertedY        Whether the stage Y axis is inverted
     * @return CompletableFuture with the user's validated affine transform, or null if cancelled.
     */
    public static CompletableFuture<AffineTransform> setupAffineTransformationAndValidationGUI(
            double macroPixelSizeMicrons, boolean stageInvertedX, boolean stageInvertedY) {
        return setupAffineTransformationAndValidationGUI(macroPixelSizeMicrons, stageInvertedX, stageInvertedY, null);
    }

    /**
     * Launches the full affine alignment GUI flow:
     *  - Computes an initial scaling transform based on pixel size and flip
     *  - Guides the user to align the microscope stage to selected tiles
     *  - Refines the affine transform using measured stage coordinates
     *  - Returns the final AffineTransform (or null if cancelled)
     *
     * <p>When {@code existingTransformEstimate} is provided, the stage will automatically
     * move to the estimated position after the user selects a reference tile. Otherwise,
     * the user must manually navigate the stage.
     *
     * @param macroPixelSizeMicrons    Pixel size of macro image in microns
     * @param stageInvertedX           Whether the stage X axis is inverted
     * @param stageInvertedY           Whether the stage Y axis is inverted
     * @param existingTransformEstimate Optional existing fullRes-to-stage transform for auto-move (may be null)
     * @return CompletableFuture with the user's validated affine transform, or null if cancelled.
     */
    public static CompletableFuture<AffineTransform> setupAffineTransformationAndValidationGUI(
            double macroPixelSizeMicrons,
            boolean stageInvertedX,
            boolean stageInvertedY,
            AffineTransform existingTransformEstimate) {

        boolean hasEstimate = existingTransformEstimate != null;
        CompletableFuture<AffineTransform> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                // Clear alignment points from any previous session
                alignmentPoints.clear();

                logger.info(
                        "Starting affine transformation setup (pixelSize: {}, stageInvertedX: {}, stageInvertedY: {}, hasEstimate: {})",
                        macroPixelSizeMicrons,
                        stageInvertedX,
                        stageInvertedY,
                        hasEstimate);

                QuPathGUI gui = QuPathGUI.getInstance();

                // 1. Create initial scaling transform
                AffineTransform scalingTransform = TransformationFunctions.setupAffineTransformation(
                        macroPixelSizeMicrons, stageInvertedX, stageInvertedY);
                logger.info("Initial scaling transform: {}", scalingTransform);

                // 2. Prompt user to select a reference tile
                String tileSelectionPrompt;
                if (hasEstimate) {
                    tileSelectionPrompt =
                            "Select a REFERENCE tile (preferably near the center or edge of your region).\n"
                                    + "After selection:\n"
                                    + "1. The stage will automatically move to the estimated position\n"
                                    + "2. Verify alignment and fine-tune if needed\n"
                                    + "3. Click 'Confirm Selection' when the stage is properly positioned";
                } else {
                    tileSelectionPrompt =
                            "Select a REFERENCE tile (preferably near the center or edge of your region).\n"
                                    + "After selection:\n"
                                    + "1. Manually move the microscope stage to center this tile in the live view\n"
                                    + "2. Click 'Confirm Selection' when the stage is properly positioned";
                }

                UIFunctions.promptTileSelectionDialogAsync(tileSelectionPrompt)
                        .thenCompose(refTile -> {
                            if (refTile == null) {
                                logger.info("User cancelled reference tile selection.");
                                future.complete(null);
                                return CompletableFuture.completedFuture(null);
                            }

                            double[] qpRefCoords = {
                                refTile.getROI().getCentroidX(),
                                refTile.getROI().getCentroidY()
                            };
                            logger.info(
                                    "User selected tile '{}' at QuPath coords: {}",
                                    refTile.getName(),
                                    Arrays.toString(qpRefCoords));

                            // If we have an existing transform estimate, auto-move the stage
                            if (hasEstimate) {
                                double[] estimatedStageCoords = TransformationFunctions.transformQuPathFullResToStage(
                                        qpRefCoords, existingTransformEstimate);
                                logger.info(
                                        "Auto-moving stage to estimated position: {}",
                                        Arrays.toString(estimatedStageCoords));
                                MicroscopeController.getInstance()
                                        .moveStageXY(estimatedStageCoords[0], estimatedStageCoords[1]);
                            }

                            // 3. Non-modal confirmation -- user can interact with QuPath
                            //    and the Live Viewer while this is open
                            return UIFunctions.stageAlignmentConfirmAsync().thenCompose(aligned -> {
                                if (!aligned) {
                                    logger.info("User cancelled at manual alignment step.");
                                    future.complete(null);
                                    return CompletableFuture.completedFuture(null);
                                }

                                try {
                                    // 4. Get current stage position after user aligned
                                    double[] measuredStageCoords =
                                            MicroscopeController.getInstance().getStagePositionXY();
                                    logger.info(
                                            "Current stage coordinates after alignment: {}",
                                            Arrays.toString(measuredStageCoords));

                                    // 5. Calculate transform based on aligned position
                                    AffineTransform transform = TransformationFunctions.addTranslationToScaledAffine(
                                            scalingTransform, qpRefCoords, measuredStageCoords);
                                    logger.info("Calculated affine transform from alignment: {}", transform);

                                    // Record the initial reference alignment point
                                    alignmentPoints.add(new AlignmentPoint(
                                            new Point2D.Double(qpRefCoords[0], qpRefCoords[1]),
                                            new Point2D.Double(measuredStageCoords[0], measuredStageCoords[1]),
                                            refTile.getName() != null ? refTile.getName() : "reference"));

                                    // 6. Secondary refinement with geometric extremes
                                    Collection<PathObject> allTiles =
                                            gui.getViewer().getHierarchy().getDetectionObjects();
                                    PathObject topCenterTile = TransformationFunctions.getTopCenterTile(allTiles);
                                    PathObject leftCenterTile = TransformationFunctions.getLeftCenterTile(allTiles);

                                    CompletableFuture<AffineTransform> refinementFuture =
                                            CompletableFuture.completedFuture(transform);

                                    for (PathObject tile : Arrays.asList(topCenterTile, leftCenterTile)) {
                                        if (tile == null) continue;

                                        refinementFuture = refinementFuture.thenCompose(currentTransform -> {
                                            if (currentTransform == null) {
                                                return CompletableFuture.completedFuture(null);
                                            }
                                            return refineWithTileAsync(gui, tile, currentTransform);
                                        });
                                    }

                                    refinementFuture
                                            .thenAccept(finalTransform -> {
                                                if (finalTransform != null) {
                                                    future.complete(finalTransform);
                                                } else {
                                                    future.complete(null);
                                                }
                                            })
                                            .exceptionally(ex -> {
                                                logger.error("Refinement failed", ex);
                                                future.completeExceptionally(ex);
                                                return null;
                                            });

                                } catch (Exception e) {
                                    logger.error("Affine transformation setup failed", e);
                                    future.complete(null);
                                }

                                return CompletableFuture.completedFuture(null);
                            });
                        })
                        .exceptionally(ex -> {
                            logger.error("Tile selection failed", ex);
                            future.complete(null);
                            return null;
                        });

            } catch (Exception e) {
                logger.error("Affine transformation setup failed", e);
                Dialogs.showErrorNotification("Affine Transform Error", e.getMessage());
                future.complete(null);
            }
        });

        return future;
    }

    /**
     * Performs a single refinement step for one tile: selects it, moves the stage,
     * shows a non-modal confirmation, and recalculates the transform.
     */
    private static CompletableFuture<AffineTransform> refineWithTileAsync(
            QuPathGUI gui, PathObject tile, AffineTransform currentTransform) {

        CompletableFuture<AffineTransform> tileFuture = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                QP.resetSelection();
                QP.selectObjects(List.of(tile));
                gui.getViewer()
                        .setCenterPixelLocation(
                                tile.getROI().getCentroidX(), tile.getROI().getCentroidY());

                logger.info("Secondary alignment: moving to refinement tile '{}'", tile.getName());

                double[] tileCoords = {
                    tile.getROI().getCentroidX(), tile.getROI().getCentroidY()
                };
                double[] stageCoords =
                        TransformationFunctions.transformQuPathFullResToStage(tileCoords, currentTransform);

                MicroscopeController.getInstance().moveStageXY(stageCoords[0], stageCoords[1]);

                // Non-modal async confirmation
                UIFunctions.stageAlignmentConfirmAsync().thenAccept(ok -> {
                    if (!ok) {
                        logger.info("User cancelled during secondary alignment at tile '{}'.", tile.getName());
                        tileFuture.complete(null);
                        return;
                    }

                    try {
                        double[] measuredCoords =
                                MicroscopeController.getInstance().getStagePositionXY();
                        AffineTransform newTransform = TransformationFunctions.addTranslationToScaledAffine(
                                currentTransform, tileCoords, measuredCoords);
                        logger.info("Refined transform after tile '{}': {}", tile.getName(), newTransform);

                        // Record this refinement point
                        String tileName = tile.getName() != null ? tile.getName() : "refinement";
                        alignmentPoints.add(new AlignmentPoint(
                                new Point2D.Double(tileCoords[0], tileCoords[1]),
                                new Point2D.Double(measuredCoords[0], measuredCoords[1]),
                                tileName));

                        // Compute residuals for all previous points against the new transform
                        double maxResidualUm = computeMaxResidual(newTransform);
                        logResiduals(newTransform);

                        // Translation shift between transforms is informational, not an error.
                        // The first refinement always recenters translation; on a stage with
                        // axis inversion the shift can be ~slide-width um even when the new
                        // point is perfectly consistent with the previous one. The actual
                        // health metric is the residual: if predictions still match measured
                        // positions, the transform is good regardless of how its translation
                        // term moved.
                        checkTransformHealth(currentTransform, newTransform, tileName, maxResidualUm);

                        tileFuture.complete(newTransform);
                    } catch (Exception e) {
                        logger.error("Error getting stage position during refinement", e);
                        tileFuture.completeExceptionally(e);
                    }
                });

            } catch (Exception e) {
                logger.error("Error during tile refinement", e);
                tileFuture.completeExceptionally(e);
            }
        });

        return tileFuture;
    }

    /**
     * Computes and logs the back-projection residual error for every recorded alignment
     * point against the given transform. The residual is the Euclidean distance (in um)
     * between the predicted stage position and the actual measured stage position.
     *
     * @param transform the current affine transform (pixel -> stage)
     */
    private static void logResiduals(AffineTransform transform) {
        if (alignmentPoints.isEmpty()) {
            return;
        }
        logger.info("--- Residual errors for {} alignment point(s) ---", alignmentPoints.size());
        double maxResidual = 0;
        for (int i = 0; i < alignmentPoints.size(); i++) {
            AlignmentPoint pt = alignmentPoints.get(i);
            Point2D predicted = transform.transform(pt.pixelPoint(), null);
            double residualUm = predicted.distance(pt.stagePoint());
            maxResidual = Math.max(maxResidual, residualUm);
            logger.info("  Residual for point {} ('{}'): {} um", i, pt.tileName(), String.format("%.1f", residualUm));
        }
        logger.info("  Max residual: {} um", String.format("%.1f", maxResidual));
    }

    /**
     * Returns the worst (largest) back-projection residual across all recorded alignment points
     * against the supplied transform, in micrometers. Returns 0 if no points are recorded.
     */
    private static double computeMaxResidual(AffineTransform transform) {
        double max = 0;
        for (AlignmentPoint pt : alignmentPoints) {
            Point2D predicted = transform.transform(pt.pixelPoint(), null);
            max = Math.max(max, predicted.distance(pt.stagePoint()));
        }
        return max;
    }

    /**
     * Reports transform health after a refinement. The residual is the meaningful metric:
     * if back-projection residuals are small the transform is good regardless of how the
     * translation term moved between iterations. The translation delta is logged at info
     * level for traceability but no longer triggers a warning -- on a stage with axis
     * inversion the first refinement reliably produces a slide-width-scale translation
     * delta even when the alignment is perfect, which the previous warning misreported.
     *
     * @param previousTransform the transform before refinement
     * @param newTransform      the transform after refinement
     * @param tileName          name of the tile used for this refinement (for logging)
     * @param maxResidualUm     the worst back-projection residual across all alignment points
     */
    private static void checkTransformHealth(
            AffineTransform previousTransform,
            AffineTransform newTransform,
            String tileName,
            double maxResidualUm) {
        double dTx = Math.abs(newTransform.getTranslateX() - previousTransform.getTranslateX());
        double dTy = Math.abs(newTransform.getTranslateY() - previousTransform.getTranslateY());
        double shift = Math.sqrt(dTx * dTx + dTy * dTy);
        logger.info(
                "Transform after tile '{}': translation shift={} um, max residual={} um",
                tileName,
                String.format("%.1f", shift),
                String.format("%.1f", maxResidualUm));
        if (maxResidualUm > RESIDUAL_WARN_UM) {
            logger.warn(
                    "Alignment residual after tile '{}' is {} um (>{} um threshold). "
                            + "The transform does not consistently predict measured stage positions; "
                            + "consider re-running the alignment.",
                    tileName,
                    String.format("%.1f", maxResidualUm),
                    String.format("%.0f", RESIDUAL_WARN_UM));
        }
    }
}
