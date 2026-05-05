package qupath.ext.qpsc.controller.workflow;

import java.awt.geom.AffineTransform;
import java.io.IOException;
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
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
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
     */
    public static class RefinementResult {
        public final AffineTransform transform;
        public final PathObject selectedTile;

        public RefinementResult(AffineTransform transform, PathObject selectedTile) {
            this.transform = transform;
            this.selectedTile = selectedTile;
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
        centerViewerOnTile(gui, selectedTile);

        // Move to estimated position
        logger.info("Moving to estimated position: ({}, {})", estimatedStageCoords[0], estimatedStageCoords[1]);
        MicroscopeController.getInstance().moveStageXY(estimatedStageCoords[0], estimatedStageCoords[1]);

        // Wait for stage to settle
        Thread.sleep(500);

        // If trust SIFT is enabled, try auto-alignment first
        if (trustSift) {
            logger.info("Trust SIFT enabled -- attempting automatic alignment");
            new Thread(
                            () -> {
                                try {
                                    double[] result = SiftAutoAlignHelper.autoAlign(gui, selectedTile);
                                    if (result != null && result.length >= 4) {
                                        double confidence = result[3]; // 4th element = confidence
                                        logger.info(
                                                "SIFT auto-align: offset=({}, {}), confidence={}",
                                                result[0],
                                                result[1],
                                                confidence);
                                        if (confidence >= confidenceThreshold) {
                                            // Auto-accept: compute refined transform and complete
                                            double[] refinedPos = MicroscopeController.getInstance()
                                                    .getStagePositionXY();
                                            AffineTransform refined =
                                                    TransformationFunctions.addTranslationToScaledAffine(
                                                            initialTransform, tileCoords, refinedPos);
                                            logger.info(
                                                    "SIFT auto-accepted: confidence {} >= threshold {}",
                                                    confidence,
                                                    confidenceThreshold);
                                            Platform.runLater(() -> {
                                                Dialogs.showInfoNotification(
                                                        "SIFT Auto-Align",
                                                        String.format(
                                                                "Alignment refined automatically (confidence %.0f%%)",
                                                                confidence * 100));
                                                future.complete(new RefinementResult(refined, selectedTile));
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
     * Centers the QuPath viewer on the selected tile.
     *
     * @param gui QuPath GUI instance
     * @param tile Tile to center on
     */
    private static void centerViewerOnTile(QuPathGUI gui, PathObject tile) {
        var viewer = gui.getViewer();
        if (viewer != null && tile.getROI() != null) {
            double cx = tile.getROI().getCentroidX();
            double cy = tile.getROI().getCentroidY();
            viewer.setCenterPixelLocation(cx, cy);
            viewer.getHierarchy().getSelectionModel().setSelectedObject(tile);
            logger.debug("Centered viewer on tile at ({}, {})", cx, cy);
        }
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
        dialogStage.setAlwaysOnTop(true);

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
                        + "Please use the microscope controls (or Stage Control dialog) to adjust\n"
                        + "the stage position so that the live view matches the selected tile in QuPath.\n\n"
                        + "When the alignment is correct, click 'Save Refined Position'.");
        instructionLabel.setWrapText(true);

        // Tile info label
        String tileName = selectedTile.getName() != null ? selectedTile.getName() : "unnamed tile";
        Label tileInfoLabel = new Label("Target tile: " + tileName);
        tileInfoLabel.setStyle("-fx-font-style: italic; -fx-text-fill: #666;");

        // Restore tile button
        Button restoreButton = new Button("Restore Target Tile Selection");
        restoreButton.setTooltip(
                new javafx.scene.control.Tooltip("Re-select and center view on the original target tile\n"
                        + "if you accidentally changed the selection."));
        restoreButton.setOnAction(e -> {
            // Restore selection and center view
            centerViewerOnTile(gui, selectedTile);
            logger.info("Restored target tile selection: {}", tileName);
        });

        // Action buttons
        Button saveButton = new Button("Save Refined Position");
        saveButton.setDefaultButton(true);
        saveButton.setStyle(
                "-fx-font-weight: bold; -fx-font-size: 13px; " + "-fx-base: #4CAF50; -fx-text-fill: white;");
        saveButton.setOnAction(e -> {
            try {
                // Get refined position
                double[] refinedStageCoords = MicroscopeController.getInstance().getStagePositionXY();
                logger.info("Refined stage position: ({}, {})", refinedStageCoords[0], refinedStageCoords[1]);

                // Calculate refined transform
                AffineTransform refinedTransform = TransformationFunctions.addTranslationToScaledAffine(
                        initialTransform, tileCoords, refinedStageCoords);

                logger.info("Calculated refined transform");

                Dialogs.showInfoNotification(
                        "Refine Alignment", "The alignment has been refined and saved for this slide.");

                dialogStage.close();
                future.complete(new RefinementResult(refinedTransform, selectedTile));
            } catch (IOException ex) {
                logger.error("Failed to get stage position", ex);
                Dialogs.showErrorMessage("Error", "Failed to read stage position: " + ex.getMessage());
            }
        });

        Button skipButton = new Button("Skip Refinement");
        skipButton.setOnAction(e -> {
            logger.info("User skipped refinement");
            dialogStage.close();
            future.complete(new RefinementResult(initialTransform, selectedTile));
        });

        Button newAlignmentButton = new Button("Create New Alignment");
        newAlignmentButton.setOnAction(e -> {
            logger.info("User requested new alignment");
            dialogStage.close();
            future.complete(new RefinementResult(null, selectedTile)); // Signal to switch to manual alignment
        });

        Label siftDescription = new Label(String.format(
                "SIFT searches a ~%.0fum region around the predicted tile position. "
                        + "It requires visible tissue features in the microscope field of view "
                        + "to match against the WSI. Click Settings to adjust parameters if matching fails.",
                qupath.ext.qpsc.preferences.PersistentPreferences.getSiftSearchMarginUm()));
        siftDescription.setWrapText(true);
        siftDescription.setStyle("-fx-font-size: 10px; -fx-text-fill: #888;");

        Label autoAlignStatus = new Label();
        autoAlignStatus.setWrapText(true);
        autoAlignStatus.setStyle("-fx-font-size: 10px;");

        // Auto-Align (SIFT) + Settings... button row provided by the
        // shared helper so this dialog and the alignment-workflow confirm
        // dialog stay visually consistent.
        HBox siftButtonRow = SiftAutoAlignHelper.buildSiftButtonRow(gui, selectedTile, dialogStage, autoAlignStatus);

        // Layout
        HBox restoreBox = new HBox(restoreButton);
        restoreBox.setAlignment(Pos.CENTER_LEFT);

        HBox buttonBox = new HBox(10, saveButton, skipButton, newAlignmentButton);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);

        content.getChildren()
                .addAll(
                        headerLabel,
                        instructionLabel,
                        tileInfoLabel,
                        siftButtonRow,
                        siftDescription,
                        autoAlignStatus,
                        restoreBox,
                        buttonBox);

        // Handle window close (X button)
        dialogStage.setOnCloseRequest(e -> {
            logger.info("Refinement dialog closed without selection");
            future.complete(new RefinementResult(initialTransform, selectedTile));
        });

        Scene scene = new Scene(content);
        dialogStage.setScene(scene);
        dialogStage.show();
    }
}
