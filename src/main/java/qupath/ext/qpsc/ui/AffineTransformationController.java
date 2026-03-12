package qupath.ext.qpsc.ui;

import java.awt.geom.AffineTransform;
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
     * Launches the full affine alignment GUI flow with manual stage navigation.
     *
     * @param macroPixelSizeMicrons Pixel size of macro image in microns
     * @param stageInvertedX        Whether the stage X axis is inverted
     * @param stageInvertedY        Whether the stage Y axis is inverted
     * @return CompletableFuture with the user's validated affine transform, or null if cancelled.
     */
    public static CompletableFuture<AffineTransform> setupAffineTransformationAndValidationGUI(
            double macroPixelSizeMicrons, boolean stageInvertedX, boolean stageInvertedY) {
        return setupAffineTransformationAndValidationGUI(
                macroPixelSizeMicrons, stageInvertedX, stageInvertedY, null);
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
            double macroPixelSizeMicrons, boolean stageInvertedX, boolean stageInvertedY,
            AffineTransform existingTransformEstimate) {

        boolean hasEstimate = existingTransformEstimate != null;
        CompletableFuture<AffineTransform> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
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
                                double[] estimatedStageCoords =
                                        TransformationFunctions.transformQuPathFullResToStage(
                                                qpRefCoords, existingTransformEstimate);
                                logger.info("Auto-moving stage to estimated position: {}",
                                        Arrays.toString(estimatedStageCoords));
                                MicroscopeController.getInstance()
                                        .moveStageXY(estimatedStageCoords[0], estimatedStageCoords[1]);
                            }

                            // 3. Non-modal confirmation -- user can interact with QuPath
                            //    and the Live Viewer while this is open
                            return UIFunctions.stageAlignmentConfirmAsync()
                                    .thenCompose(aligned -> {
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
                                            AffineTransform transform =
                                                    TransformationFunctions.addTranslationToScaledAffine(
                                                            scalingTransform, qpRefCoords, measuredStageCoords);
                                            logger.info(
                                                    "Calculated affine transform from alignment: {}", transform);

                                            // 6. Secondary refinement with geometric extremes
                                            Collection<PathObject> allTiles =
                                                    gui.getViewer().getHierarchy().getDetectionObjects();
                                            PathObject topCenterTile =
                                                    TransformationFunctions.getTopCenterTile(allTiles);
                                            PathObject leftCenterTile =
                                                    TransformationFunctions.getLeftCenterTile(allTiles);

                                            CompletableFuture<AffineTransform> refinementFuture =
                                                    CompletableFuture.completedFuture(transform);

                                            for (PathObject tile :
                                                    Arrays.asList(topCenterTile, leftCenterTile)) {
                                                if (tile == null) continue;

                                                refinementFuture =
                                                        refinementFuture.thenCompose(currentTransform -> {
                                                            if (currentTransform == null) {
                                                                return CompletableFuture.completedFuture(
                                                                        null);
                                                            }
                                                            return refineWithTileAsync(
                                                                    gui, tile, currentTransform);
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
                                tile.getROI().getCentroidX(),
                                tile.getROI().getCentroidY());

                logger.info("Secondary alignment: moving to refinement tile '{}'", tile.getName());

                double[] tileCoords = {
                    tile.getROI().getCentroidX(),
                    tile.getROI().getCentroidY()
                };
                double[] stageCoords = TransformationFunctions.transformQuPathFullResToStage(
                        tileCoords, currentTransform);

                MicroscopeController.getInstance().moveStageXY(stageCoords[0], stageCoords[1]);

                // Non-modal async confirmation
                UIFunctions.stageAlignmentConfirmAsync().thenAccept(ok -> {
                    if (!ok) {
                        logger.info(
                                "User cancelled during secondary alignment at tile '{}'.",
                                tile.getName());
                        tileFuture.complete(null);
                        return;
                    }

                    try {
                        double[] measuredCoords =
                                MicroscopeController.getInstance().getStagePositionXY();
                        AffineTransform newTransform =
                                TransformationFunctions.addTranslationToScaledAffine(
                                        currentTransform, tileCoords, measuredCoords);
                        logger.info(
                                "Refined transform after tile '{}': {}",
                                tile.getName(),
                                newTransform);
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
}
