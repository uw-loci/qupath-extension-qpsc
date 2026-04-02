package qupath.ext.qpsc.controller.workflow;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.RegionRequest;

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

        // Select tile for refinement
        UIFunctions.promptTileSelectionDialogAsync("Select a tile for alignment refinement.\n"
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

        // Get flip status from image metadata - the actual flip state of THIS image,
        // not from global preferences which may have changed
        boolean flipX = false;
        boolean flipY = false;
        ProjectImageEntry<?> currentEntry = gui.getProject() != null && gui.getImageData() != null
                ? gui.getProject().getEntry(gui.getImageData())
                : null;
        if (currentEntry != null) {
            flipX = ImageMetadataManager.isFlippedX(currentEntry);
            flipY = ImageMetadataManager.isFlippedY(currentEntry);
            logger.debug("Using flip status from image metadata: flipX={}, flipY={}", flipX, flipY);
        } else {
            // Fallback to global preferences if no image entry available
            flipX = QPPreferenceDialog.getFlipMacroXProperty();
            flipY = QPPreferenceDialog.getFlipMacroYProperty();
            logger.debug("No image entry, using global preferences: flipX={}, flipY={}", flipX, flipY);
        }

        logger.info(
                "Selected tile '{}' at coordinates: ({}, {}), frame size: {}x{}, flips: X={}, Y={}",
                selectedTile.getName(),
                tileCoords[0],
                tileCoords[1],
                frameWidth,
                frameHeight,
                flipX,
                flipY);

        // FLIP CORRECTION for alignment prediction.
        //
        // The alignment transform maps [flipped WSI pixels] -> [stage microns].
        // It was calibrated while viewing the flipped WSI.
        //
        // When to apply correction:
        //   - Viewing the ORIGINAL unflipped WSI: tile centroids are in unflipped space,
        //     but the transform expects flipped space. Shift by one frame to compensate.
        //   - Viewing the flipped WSI: coordinates are already in the correct space.
        //   - Viewing a sub-image (20x, 40x, etc.): coordinates map to stage via
        //     xy_offset + pixel_size, NOT via the WSI alignment. No correction needed.
        //     (Sub-images inherit flip_x/flip_y from parent but are not themselves flipped.)
        //
        // Decision: apply correction ONLY when the current image is the unflipped
        // original WSI (no flip metadata, and it's a base-level image, not a sub-image).
        double[] correctedCoords = {tileCoords[0], tileCoords[1]};
        boolean currentImageIsFlipped = flipX || flipY;
        String baseImageName = currentEntry != null ? ImageMetadataManager.getBaseImage(currentEntry) : null;
        String currentName =
                currentEntry != null ? qupath.lib.common.GeneralTools.stripExtension(currentEntry.getImageName()) : "";
        boolean isBaseImage = baseImageName == null
                || baseImageName.equals(currentName)
                || (currentEntry != null && currentEntry.getImageName().startsWith(baseImageName + "."));

        if (!currentImageIsFlipped && isBaseImage) {
            // Viewing unflipped original WSI -- need flip correction for the transform
            boolean prefFlipX = QPPreferenceDialog.getFlipMacroXProperty();
            boolean prefFlipY = QPPreferenceDialog.getFlipMacroYProperty();
            if (prefFlipX) {
                correctedCoords[0] -= frameWidth;
                logger.debug("Applied flipX correction: X {} -> {}", tileCoords[0], correctedCoords[0]);
            }
            if (prefFlipY) {
                correctedCoords[1] += frameHeight;
                logger.debug("Applied flipY correction: Y {} -> {}", tileCoords[1], correctedCoords[1]);
            }
            logger.info("Applied flip correction (unflipped base WSI)");
        } else {
            logger.info(
                    "No flip correction: currentFlipped={}, isBase={}, image='{}'",
                    currentImageIsFlipped,
                    isBaseImage,
                    currentName);
        }

        // Transform corrected coordinates to stage position
        double[] estimatedStageCoords =
                TransformationFunctions.transformQuPathFullResToStage(correctedCoords, initialTransform);

        if (flipX || flipY) {
            // Also log what the uncorrected position would have been for comparison
            double[] uncorrectedStageCoords =
                    TransformationFunctions.transformQuPathFullResToStage(tileCoords, initialTransform);
            logger.info(
                    "Stage position: corrected=({}, {}), uncorrected=({}, {})",
                    String.format("%.1f", estimatedStageCoords[0]),
                    String.format("%.1f", estimatedStageCoords[1]),
                    String.format("%.1f", uncorrectedStageCoords[0]),
                    String.format("%.1f", uncorrectedStageCoords[1]));
        }

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
                                    double[] result = performSiftAutoAlign(gui, selectedTile);
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

        // Auto-Align button (SIFT feature matching)
        Button autoAlignButton = new Button("Auto-Align (SIFT)");
        autoAlignButton.setStyle(
                "-fx-font-weight: bold; -fx-border-color: #4A90D9; " + "-fx-border-width: 2; -fx-border-radius: 3;");
        autoAlignButton.setTooltip(
                new javafx.scene.control.Tooltip("Automatically align by matching the microscope view to the WSI tile\n"
                        + "using SIFT feature detection. Searches within ~160um of the\n"
                        + "predicted position. Requires tissue with visible features."));

        Label siftDescription = new Label(String.format(
                "SIFT searches a ~%.0fum region around the predicted tile position. "
                        + "It requires visible tissue features in the microscope field of view "
                        + "to match against the WSI. Click Settings to adjust parameters if matching fails.",
                qupath.ext.qpsc.preferences.PersistentPreferences.getSiftSearchMarginUm()));
        siftDescription.setWrapText(true);
        siftDescription.setStyle("-fx-font-size: 10px; -fx-text-fill: #888;");

        // SIFT Settings button
        Button siftSettingsButton = new Button("Settings...");
        siftSettingsButton.setStyle("-fx-font-size: 10px;");
        siftSettingsButton.setOnAction(e -> showSiftSettingsDialog());

        Label autoAlignStatus = new Label();
        autoAlignStatus.setWrapText(true);
        autoAlignStatus.setStyle("-fx-font-size: 10px;");

        autoAlignButton.setOnAction(e -> {
            autoAlignButton.setDisable(true);
            autoAlignStatus.setText("Running SIFT matching...");
            autoAlignStatus.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

            new Thread(
                            () -> {
                                try {
                                    double[] offset = performSiftAutoAlign(gui, selectedTile);
                                    Platform.runLater(() -> {
                                        autoAlignButton.setDisable(false);
                                        if (offset != null && offset.length >= 2) {
                                            String confStr = offset.length >= 4
                                                    ? String.format(" (%.0f%% confidence)", offset[3] * 100)
                                                    : "";
                                            autoAlignStatus.setText(String.format(
                                                    "Aligned! Offset: (%.1f, %.1f) um%s. Verify and Save.",
                                                    offset[0], offset[1], confStr));
                                            autoAlignStatus.setStyle("-fx-font-size: 10px; -fx-text-fill: green;");
                                        } else {
                                            autoAlignStatus.setText("SIFT matching failed. Align manually.");
                                            autoAlignStatus.setStyle("-fx-font-size: 10px; -fx-text-fill: orange;");
                                        }
                                    });
                                } catch (Exception ex) {
                                    logger.error("Auto-align failed: {}", ex.getMessage());
                                    Platform.runLater(() -> {
                                        autoAlignButton.setDisable(false);
                                        autoAlignStatus.setText("Error: " + ex.getMessage());
                                        autoAlignStatus.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
                                    });
                                }
                            },
                            "SIFT-AutoAlign")
                    .start();
        });

        // Layout
        HBox restoreBox = new HBox(restoreButton);
        restoreBox.setAlignment(Pos.CENTER_LEFT);

        HBox buttonBox = new HBox(10, saveButton, skipButton, newAlignmentButton);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);

        HBox siftButtonRow = new HBox(8, autoAlignButton, siftSettingsButton);
        siftButtonRow.setAlignment(Pos.CENTER_LEFT);

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

    /**
     * Perform SIFT-based auto-alignment.
     *
     * <p>Extracts a region from the WSI around the selected tile (160um margin),
     * saves it as a temp file, sends it to the Python server for SIFT matching
     * against a fresh microscope snapshot, and moves the stage by the resulting offset.
     *
     * @param gui QuPath GUI (for accessing the image server)
     * @param selectedTile The tile being refined
     * @return Offset in microns [x, y], or null if matching failed
     */
    /**
     * Shows a dialog for tuning SIFT matching parameters.
     * Changes are saved to persistent preferences and take effect on the next SIFT run.
     */
    private static void showSiftSettingsDialog() {
        var prefs = qupath.ext.qpsc.preferences.PersistentPreferences.class;
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("SIFT Matching Settings");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setPrefWidth(400);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(15));

        int row = 0;

        // Min pixel size
        Spinner<Double> minPxSpinner =
                new Spinner<>(0.1, 5.0, qupath.ext.qpsc.preferences.PersistentPreferences.getSiftMinPixelSize(), 0.1);
        minPxSpinner.setEditable(true);
        minPxSpinner.setPrefWidth(90);
        grid.add(new Label("Min pixel size (um):"), 0, row);
        grid.add(minPxSpinner, 1, row);
        Label minPxHelp = new Label("Downsample to this resolution. Lower = more detail but slower.");
        minPxHelp.setStyle("-fx-font-size: 9px; -fx-text-fill: #888;");
        minPxHelp.setWrapText(true);
        grid.add(minPxHelp, 0, ++row, 2, 1);

        // Ratio threshold
        Spinner<Double> ratioSpinner = new Spinner<>(
                0.3, 0.95, qupath.ext.qpsc.preferences.PersistentPreferences.getSiftRatioThreshold(), 0.05);
        ratioSpinner.setEditable(true);
        ratioSpinner.setPrefWidth(90);
        grid.add(new Label("Ratio threshold:"), 0, ++row);
        grid.add(ratioSpinner, 1, row);
        Label ratioHelp = new Label("Lowe's ratio test. Higher = more permissive matching (try 0.8 if failing).");
        ratioHelp.setStyle("-fx-font-size: 9px; -fx-text-fill: #888;");
        ratioHelp.setWrapText(true);
        grid.add(ratioHelp, 0, ++row, 2, 1);

        // Min matches
        Spinner<Integer> minMatchSpinner =
                new Spinner<>(3, 50, qupath.ext.qpsc.preferences.PersistentPreferences.getSiftMinMatchCount(), 1);
        minMatchSpinner.setPrefWidth(90);
        grid.add(new Label("Min match count:"), 0, ++row);
        grid.add(minMatchSpinner, 1, row);
        Label matchHelp = new Label("Minimum inlier matches required. Lower = accept weaker matches.");
        matchHelp.setStyle("-fx-font-size: 9px; -fx-text-fill: #888;");
        matchHelp.setWrapText(true);
        grid.add(matchHelp, 0, ++row, 2, 1);

        // Contrast threshold
        Spinner<Double> contrastSpinner = new Spinner<>(
                0.001, 0.2, qupath.ext.qpsc.preferences.PersistentPreferences.getSiftContrastThreshold(), 0.005);
        contrastSpinner.setEditable(true);
        contrastSpinner.setPrefWidth(90);
        grid.add(new Label("Contrast threshold:"), 0, ++row);
        grid.add(contrastSpinner, 1, row);
        Label contrastHelp = new Label("Feature detection sensitivity. Lower = detect more features in pale tissue.");
        contrastHelp.setStyle("-fx-font-size: 9px; -fx-text-fill: #888;");
        contrastHelp.setWrapText(true);
        grid.add(contrastHelp, 0, ++row, 2, 1);

        // Search margin
        Spinner<Double> marginSpinner = new Spinner<>(
                50.0, 500.0, qupath.ext.qpsc.preferences.PersistentPreferences.getSiftSearchMarginUm(), 10.0);
        marginSpinner.setEditable(true);
        marginSpinner.setPrefWidth(90);
        grid.add(new Label("Search margin (um):"), 0, ++row);
        grid.add(marginSpinner, 1, row);
        Label marginHelp = new Label("WSI region extends this far beyond the tile on each side.");
        marginHelp.setStyle("-fx-font-size: 9px; -fx-text-fill: #888;");
        marginHelp.setWrapText(true);
        grid.add(marginHelp, 0, ++row, 2, 1);

        // Confidence threshold
        Spinner<Double> confSpinner = new Spinner<>(
                0.1, 1.0, qupath.ext.qpsc.preferences.PersistentPreferences.getSiftConfidenceThreshold(), 0.05);
        confSpinner.setEditable(true);
        confSpinner.setPrefWidth(90);
        grid.add(new Label("Auto-accept confidence:"), 0, ++row);
        grid.add(confSpinner, 1, row);
        Label confHelp = new Label("Min inlier ratio to auto-accept when Trust SIFT is enabled.");
        confHelp.setStyle("-fx-font-size: 9px; -fx-text-fill: #888;");
        confHelp.setWrapText(true);
        grid.add(confHelp, 0, ++row, 2, 1);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(bt -> {
            if (bt == ButtonType.OK) {
                qupath.ext.qpsc.preferences.PersistentPreferences.setSiftMinPixelSize(minPxSpinner.getValue());
                qupath.ext.qpsc.preferences.PersistentPreferences.setSiftRatioThreshold(ratioSpinner.getValue());
                qupath.ext.qpsc.preferences.PersistentPreferences.setSiftMinMatchCount(minMatchSpinner.getValue());
                qupath.ext.qpsc.preferences.PersistentPreferences.setSiftContrastThreshold(contrastSpinner.getValue());
                qupath.ext.qpsc.preferences.PersistentPreferences.setSiftSearchMarginUm(marginSpinner.getValue());
                qupath.ext.qpsc.preferences.PersistentPreferences.setSiftConfidenceThreshold(confSpinner.getValue());
                logger.info(
                        "SIFT settings updated: minPx={}, ratio={}, minMatches={}, contrast={}, margin={}, confidence={}",
                        minPxSpinner.getValue(),
                        ratioSpinner.getValue(),
                        minMatchSpinner.getValue(),
                        contrastSpinner.getValue(),
                        marginSpinner.getValue(),
                        confSpinner.getValue());
            }
            return null;
        });
        dialog.showAndWait();
    }

    private static double[] performSiftAutoAlign(QuPathGUI gui, PathObject selectedTile) throws Exception {

        var imageData = gui.getImageData();
        if (imageData == null) throw new IllegalStateException("No image data available");

        ImageServer<BufferedImage> server = imageData.getServer();
        double wsiPixelSize = server.getPixelCalibration().getAveragedPixelSizeMicrons();
        if (Double.isNaN(wsiPixelSize) || wsiPixelSize <= 0) {
            throw new IllegalStateException("WSI has no valid pixel size calibration");
        }

        // Get microscope pixel size
        MicroscopeController mc = MicroscopeController.getInstance();
        double microPixelSize = mc.getSocketClient().getMicroscopePixelSize();

        // Calculate search region: tile bounds + configurable margin on each side
        double marginUm = qupath.ext.qpsc.preferences.PersistentPreferences.getSiftSearchMarginUm();
        double marginPx = marginUm / wsiPixelSize;

        double tileX = selectedTile.getROI().getBoundsX();
        double tileY = selectedTile.getROI().getBoundsY();
        double tileW = selectedTile.getROI().getBoundsWidth();
        double tileH = selectedTile.getROI().getBoundsHeight();

        int regionX = Math.max(0, (int) (tileX - marginPx));
        int regionY = Math.max(0, (int) (tileY - marginPx));
        int regionW = Math.min(server.getWidth() - regionX, (int) (tileW + 2 * marginPx));
        int regionH = Math.min(server.getHeight() - regionY, (int) (tileH + 2 * marginPx));

        logger.info(
                "Extracting WSI region: ({}, {}) {}x{} pixels (margin={}um={}px)",
                regionX,
                regionY,
                regionW,
                regionH,
                marginUm,
                (int) marginPx);

        // Read the WSI region at full resolution
        RegionRequest request = RegionRequest.createInstance(server.getPath(), 1.0, regionX, regionY, regionW, regionH);
        BufferedImage wsiRegion = server.readRegion(request);

        // Save to temp file
        File tempFile = File.createTempFile("sift_wsi_region_", ".png");
        tempFile.deleteOnExit();
        ImageIO.write(wsiRegion, "PNG", tempFile);
        logger.info(
                "Saved WSI region to temp file: {} ({}x{})",
                tempFile.getAbsolutePath(),
                wsiRegion.getWidth(),
                wsiRegion.getHeight());

        // Get flip status from image metadata (macro/WSI flip)
        ProjectImageEntry<?> entry = gui.getProject() != null && gui.getImageData() != null
                ? gui.getProject().getEntry(gui.getImageData())
                : null;
        boolean flipX = entry != null && ImageMetadataManager.isFlippedX(entry);
        boolean flipY = entry != null && ImageMetadataManager.isFlippedY(entry);

        // Account for detector optical flip (XOR with macro flip).
        // If the macro is flipped AND the detector is also flipped, they cancel out
        // and no SIFT flip is needed. If only one is flipped, SIFT must compensate.
        String siftDetectorId = entry != null ? ImageMetadataManager.getDetectorId(entry) : null;
        if (siftDetectorId != null) {
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
            if (mgr != null) {
                flipX ^= mgr.getDetectorFlipX(siftDetectorId);
                flipY ^= mgr.getDetectorFlipY(siftDetectorId);
            }
        }

        // Stop live streaming for clean snap
        MicroscopeController.LiveViewState liveState = mc.stopAllLiveViewing();
        try {
            // Call SIFT matching on the Python server
            double minPx = qupath.ext.qpsc.preferences.PersistentPreferences.getSiftMinPixelSize();
            double ratioThreshold = qupath.ext.qpsc.preferences.PersistentPreferences.getSiftRatioThreshold();
            int minMatchCount = qupath.ext.qpsc.preferences.PersistentPreferences.getSiftMinMatchCount();
            double contrastThreshold = qupath.ext.qpsc.preferences.PersistentPreferences.getSiftContrastThreshold();
            int nFeatures = qupath.ext.qpsc.preferences.PersistentPreferences.getSiftNFeatures();
            String response = mc.getSocketClient()
                    .siftAutoAlign(
                            tempFile.getAbsolutePath(),
                            microPixelSize,
                            wsiPixelSize,
                            flipX,
                            flipY,
                            minPx,
                            ratioThreshold,
                            minMatchCount,
                            contrastThreshold,
                            nFeatures);

            // Parse response: "SUCCESS:offsetX,offsetY|inliers:N|confidence:C"
            if (!response.startsWith("SUCCESS:")) {
                logger.warn("SIFT auto-align did not succeed: {}", response);
                return null;
            }

            String[] parts = response.substring(8).split("\\|");
            String[] offsets = parts[0].split(",");
            double offsetX = Double.parseDouble(offsets[0]);
            double offsetY = Double.parseDouble(offsets[1]);

            // Parse inliers and confidence
            int inliers = 0;
            double confidence = 0;
            for (String part : parts) {
                if (part.startsWith("inliers:")) inliers = Integer.parseInt(part.substring(8));
                if (part.startsWith("confidence:")) confidence = Double.parseDouble(part.substring(11));
            }

            logger.info("SIFT offset: ({}, {}) um, inliers={}, confidence={}", offsetX, offsetY, inliers, confidence);

            // Move stage by the offset to correct alignment
            double[] currentPos = mc.getStagePositionXY();
            double newX = currentPos[0] + offsetX;
            double newY = currentPos[1] + offsetY;
            logger.info("Moving stage from ({}, {}) to ({}, {})", currentPos[0], currentPos[1], newX, newY);
            mc.moveStageXY(newX, newY);

            // Return offset + inliers + confidence
            return new double[] {offsetX, offsetY, inliers, confidence};

        } finally {
            mc.restoreLiveViewState(liveState);
            // Clean up temp file
            if (!tempFile.delete()) {
                logger.debug("Could not delete temp file: {}", tempFile);
            }
        }
    }
}
