package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;

import javafx.embed.swing.SwingFXUtils;

import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Non-modal window for displaying calibration results.
 *
 * Shows the calibration metrics, any warnings, and displays the calibration plot image.
 * The window is non-modal so the user can interact with QuPath while viewing results.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class CalibrationResultDialog {

    private static final Logger logger = LoggerFactory.getLogger(CalibrationResultDialog.class);

    /**
     * Callback for retrying calibration with a manually selected center point.
     */
    @FunctionalInterface
    public interface CenterRetryCallback {
        /**
         * Retry calibration with the specified center coordinates.
         *
         * @param centerY Y pixel coordinate of the selected center
         * @param centerX X pixel coordinate of the selected center
         */
        void retry(int centerY, int centerX);
    }

    /**
     * Calibration result data to display.
     */
    public record CalibrationResultData(
            boolean success,
            double rSquared,
            int spokesDetected,
            String plotPath,
            String calibrationPath,
            String imagePath,
            String maskPath,
            String outputFolder,
            List<String> warnings,
            String errorMessage
    ) {
        /**
         * Create a success result.
         */
        public static CalibrationResultData success(double rSquared, int spokesDetected,
                                                    String plotPath, String calibrationPath,
                                                    String imagePath, String maskPath, List<String> warnings) {
            return new CalibrationResultData(true, rSquared, spokesDetected,
                    plotPath, calibrationPath, imagePath, maskPath, null, warnings, null);
        }

        /**
         * Create a failure result with optional debug paths and output folder.
         */
        public static CalibrationResultData failure(String errorMessage, String imagePath,
                                                     String maskPath, String outputFolder) {
            return new CalibrationResultData(false, 0, 0, null, null, imagePath, maskPath,
                    outputFolder, List.of(), errorMessage);
        }

        /**
         * Create a failure result without debug paths.
         */
        public static CalibrationResultData failure(String errorMessage) {
            return failure(errorMessage, null, null, null);
        }
    }

    /**
     * Shows the calibration result dialog.
     *
     * @param result The calibration result data to display
     */
    public static void showResult(CalibrationResultData result) {
        showResult(result, null);
    }

    /**
     * Shows the calibration result dialog with optional redo callback.
     *
     * @param result The calibration result data to display
     * @param onRedo Callback to invoke when user clicks "Go Back and Redo" (null to hide button)
     */
    public static void showResult(CalibrationResultData result, Runnable onRedo) {
        showResult(result, onRedo, null);
    }

    /**
     * Shows the calibration result dialog with optional redo and center-retry callbacks.
     *
     * @param result The calibration result data to display
     * @param onRedo Callback to invoke when user clicks "Go Back and Redo" (null to hide button)
     * @param onCenterRetry Callback to retry with manually selected center (null to hide section)
     */
    public static void showResult(CalibrationResultData result, Runnable onRedo,
                                   CenterRetryCallback onCenterRetry) {
        showResult(result, onRedo, onCenterRetry, null);
    }

    /**
     * Shows the calibration result dialog with optional redo, center-retry, and threshold tuning callbacks.
     *
     * @param result The calibration result data to display
     * @param onRedo Callback to invoke when user clicks "Go Back and Redo" (null to hide button)
     * @param onCenterRetry Callback to retry with manually selected center (null to hide section)
     * @param onTuneThresholds Callback to open threshold tuning preview (null to hide button)
     */
    public static void showResult(CalibrationResultData result, Runnable onRedo,
                                   CenterRetryCallback onCenterRetry, Runnable onTuneThresholds) {
        Platform.runLater(() -> {
            Stage stage = new Stage();
            stage.setTitle("PPM Reference Slide Calibration Results");
            stage.initModality(Modality.NONE);

            QuPathGUI gui = QuPathGUI.getInstance();
            if (gui != null && gui.getStage() != null) {
                stage.initOwner(gui.getStage());
            }

            VBox root = new VBox(10);
            root.setPadding(new Insets(10));

            if (result.success()) {
                buildSuccessContent(root, stage, result, onRedo, onCenterRetry, onTuneThresholds);
            } else {
                buildErrorContent(root, stage, result, onRedo, onCenterRetry, onTuneThresholds);
            }

            ScrollPane scrollPane = new ScrollPane(root);
            scrollPane.setFitToWidth(true);
            scrollPane.setPrefViewportHeight(1200);
            scrollPane.setPrefViewportWidth(1400);

            Scene scene = new Scene(scrollPane);
            stage.setScene(scene);
            stage.setResizable(true);
            stage.show();
        });
    }

    private static void buildSuccessContent(VBox root, Stage stage, CalibrationResultData result,
                                             Runnable onRedo, CenterRetryCallback onCenterRetry,
                                             Runnable onTuneThresholds) {
        // Header
        Label headerLabel = new Label("Calibration Completed Successfully");
        headerLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: green;");
        root.getChildren().add(headerLabel);

        // Results section
        GridPane resultsGrid = new GridPane();
        resultsGrid.setHgap(15);
        resultsGrid.setVgap(8);

        int row = 0;

        // R-squared
        Label rSquaredLabel = new Label("R-squared:");
        rSquaredLabel.setStyle("-fx-font-weight: bold;");
        String rSquaredStatus = result.rSquared() >= 0.95 ? " [GOOD]" : " [LOW - check results]";
        String rSquaredStyle = result.rSquared() >= 0.95 ?
                "-fx-text-fill: green;" : "-fx-text-fill: orange; -fx-font-weight: bold;";
        Label rSquaredValue = new Label(String.format("%.6f%s", result.rSquared(), rSquaredStatus));
        rSquaredValue.setStyle(rSquaredStyle);
        resultsGrid.add(rSquaredLabel, 0, row);
        resultsGrid.add(rSquaredValue, 1, row);
        row++;

        // Spokes detected
        Label spokesLabel = new Label("Spokes Detected:");
        spokesLabel.setStyle("-fx-font-weight: bold;");
        Label spokesValue = new Label(String.valueOf(result.spokesDetected()));
        resultsGrid.add(spokesLabel, 0, row);
        resultsGrid.add(spokesValue, 1, row);
        row++;

        // Calibration file path
        Label calibPathLabel = new Label("Calibration File:");
        calibPathLabel.setStyle("-fx-font-weight: bold;");
        Label calibPathValue = new Label(result.calibrationPath());
        calibPathValue.setStyle("-fx-font-size: 10px;");
        calibPathValue.setWrapText(true);
        resultsGrid.add(calibPathLabel, 0, row);
        resultsGrid.add(calibPathValue, 1, row);
        row++;

        root.getChildren().add(resultsGrid);

        // Warnings section (if any)
        if (result.warnings() != null && !result.warnings().isEmpty()) {
            root.getChildren().add(new Separator());

            Label warningsHeader = new Label("Warnings:");
            warningsHeader.setStyle("-fx-font-weight: bold; -fx-text-fill: orange;");
            root.getChildren().add(warningsHeader);

            for (String warning : result.warnings()) {
                Label warningLabel = new Label("  - " + warning);
                warningLabel.setStyle("-fx-text-fill: orange; -fx-font-size: 11px;");
                warningLabel.setWrapText(true);
                root.getChildren().add(warningLabel);
            }
        }

        root.getChildren().add(new Separator());

        // Plot image
        if (result.plotPath() != null) {
            Label plotLabel = new Label("Calibration Plot:");
            plotLabel.setStyle("-fx-font-weight: bold;");
            root.getChildren().add(plotLabel);

            try {
                File plotFile = new File(result.plotPath());
                if (plotFile.exists()) {
                    Image plotImage = new Image(new FileInputStream(plotFile));
                    ImageView imageView = new ImageView(plotImage);

                    // Scale image to fit dialog while maintaining aspect ratio
                    double maxWidth = 1300;
                    double maxHeight = 900;
                    double scale = Math.min(maxWidth / plotImage.getWidth(),
                                           maxHeight / plotImage.getHeight());
                    if (scale < 1.0) {
                        imageView.setFitWidth(plotImage.getWidth() * scale);
                        imageView.setFitHeight(plotImage.getHeight() * scale);
                    }
                    imageView.setPreserveRatio(true);

                    // Center the image
                    HBox imageBox = new HBox(imageView);
                    imageBox.setAlignment(Pos.CENTER);
                    root.getChildren().add(imageBox);
                } else {
                    Label noPlotLabel = new Label("(Plot image not found: " + result.plotPath() + ")");
                    noPlotLabel.setStyle("-fx-text-fill: gray;");
                    root.getChildren().add(noPlotLabel);
                }
            } catch (Exception e) {
                logger.error("Failed to load plot image", e);
                Label errorLabel = new Label("(Failed to load plot image: " + e.getMessage() + ")");
                errorLabel.setStyle("-fx-text-fill: red;");
                root.getChildren().add(errorLabel);
            }
        }

        // Center selection section (if callback provided and image available)
        if (onCenterRetry != null) {
            addCenterSelectionSection(root, stage, result, onCenterRetry);
        }

        root.getChildren().add(new Separator());

        // Button bar
        HBox buttonBar = buildButtonBar(stage, result, onRedo, onTuneThresholds, true);
        root.getChildren().add(buttonBar);
    }

    private static void buildErrorContent(VBox root, Stage stage, CalibrationResultData result,
                                            Runnable onRedo, CenterRetryCallback onCenterRetry,
                                            Runnable onTuneThresholds) {
        // Header
        Label headerLabel = new Label("Calibration Failed");
        headerLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: red;");
        root.getChildren().add(headerLabel);

        // Error details
        Label errorLabel = new Label("Error:");
        errorLabel.setStyle("-fx-font-weight: bold;");
        root.getChildren().add(errorLabel);

        TextArea errorText = new TextArea(result.errorMessage());
        errorText.setEditable(false);
        errorText.setWrapText(true);
        errorText.setPrefRowCount(4);
        errorText.setStyle("-fx-font-family: monospace;");
        root.getChildren().add(errorText);

        root.getChildren().add(new Separator());

        // Debug images section (if available)
        boolean hasDebugImages = (result.imagePath() != null || result.maskPath() != null);
        String debugFolderPath = null;

        if (hasDebugImages) {
            Label debugLabel = new Label("Debug Images (for troubleshooting):");
            debugLabel.setStyle("-fx-font-weight: bold;");
            root.getChildren().add(debugLabel);

            // Show segmentation mask if available (most useful for debugging)
            if (result.maskPath() != null) {
                try {
                    File maskFile = new File(result.maskPath());
                    if (maskFile.exists()) {
                        debugFolderPath = maskFile.getParent();

                        Label maskLabel = new Label("Segmentation Mask:");
                        maskLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");
                        root.getChildren().add(maskLabel);

                        Image maskImage = new Image(new FileInputStream(maskFile));
                        ImageView maskView = new ImageView(maskImage);

                        // Scale to fit dialog
                        double maxWidth = 1300;
                        double maxHeight = 900;
                        double scale = Math.min(maxWidth / maskImage.getWidth(),
                                               maxHeight / maskImage.getHeight());
                        if (scale < 1.0) {
                            maskView.setFitWidth(maskImage.getWidth() * scale);
                            maskView.setFitHeight(maskImage.getHeight() * scale);
                        }
                        maskView.setPreserveRatio(true);

                        HBox maskBox = new HBox(maskView);
                        maskBox.setAlignment(Pos.CENTER);
                        root.getChildren().add(maskBox);

                        Label maskHint = new Label(
                            "White pixels = detected foreground (colored regions above threshold).\n" +
                            "Black pixels = background (below threshold).\n" +
                            "All black: thresholds too high - lower saturation/value thresholds.\n" +
                            "All white: thresholds too low - raise saturation/value thresholds."
                        );
                        maskHint.setStyle("-fx-font-size: 10px; -fx-text-fill: #888888;");
                        maskHint.setWrapText(true);
                        root.getChildren().add(maskHint);
                    } else {
                        Label noMaskLabel = new Label("(Mask file not found: " + result.maskPath() + ")");
                        noMaskLabel.setStyle("-fx-text-fill: gray; -fx-font-size: 10px;");
                        root.getChildren().add(noMaskLabel);
                    }
                } catch (Exception e) {
                    logger.error("Failed to load mask image", e);
                    Label errorMaskLabel = new Label("(Failed to load mask: " + e.getMessage() + ")");
                    errorMaskLabel.setStyle("-fx-text-fill: gray; -fx-font-size: 10px;");
                    root.getChildren().add(errorMaskLabel);
                }
            }

            // Show file paths for reference
            if (result.imagePath() != null) {
                File imgFile = new File(result.imagePath());
                if (debugFolderPath == null && imgFile.getParent() != null) {
                    debugFolderPath = imgFile.getParent();
                }
                Label imgPathLabel = new Label("Captured image: " + result.imagePath());
                imgPathLabel.setStyle("-fx-font-size: 9px; -fx-text-fill: #888888;");
                imgPathLabel.setWrapText(true);
                root.getChildren().add(imgPathLabel);
            }
            if (result.maskPath() != null) {
                Label maskPathLabel = new Label("Segmentation mask: " + result.maskPath());
                maskPathLabel.setStyle("-fx-font-size: 9px; -fx-text-fill: #888888;");
                maskPathLabel.setWrapText(true);
                root.getChildren().add(maskPathLabel);
            }

            root.getChildren().add(new Separator());
        }

        // Center selection section (if callback provided and image available)
        if (onCenterRetry != null) {
            addCenterSelectionSection(root, stage, result, onCenterRetry);
        }

        // Troubleshooting tips
        Label tipsLabel = new Label("Troubleshooting Tips:");
        tipsLabel.setStyle("-fx-font-weight: bold;");
        root.getChildren().add(tipsLabel);

        String tips =
            "1. Ensure the calibration slide is properly positioned and focused\n" +
            "2. Check that the slide has visible colored spokes\n" +
            "3. Try adjusting saturation/value thresholds if detection is failing\n" +
            "4. Verify the microscope server is running and connected\n" +
            "5. Check the server log for detailed error information";

        Label tipsText = new Label(tips);
        tipsText.setStyle("-fx-font-size: 11px;");
        root.getChildren().add(tipsText);

        root.getChildren().add(new Separator());

        // Determine folder to open - prefer debug folder, fall back to output folder
        String folderPath = debugFolderPath;
        if (folderPath == null && result.outputFolder() != null) {
            folderPath = result.outputFolder();
        }

        // Button bar
        HBox buttonBar = buildButtonBar(stage, result, onRedo, onTuneThresholds, false);

        // Add open folder button if we have a folder path
        if (folderPath != null) {
            String buttonLabel = hasDebugImages ? "Open Debug Folder" : "Open Output Folder";
            Button openFolderButton = new Button(buttonLabel);
            final String folderToOpen = folderPath;
            openFolderButton.setOnAction(event -> {
                try {
                    File folder = new File(folderToOpen);
                    if (!folder.exists()) {
                        folder = folder.getParentFile();
                    }
                    if (folder != null && folder.exists()) {
                        Desktop.getDesktop().open(folder);
                    }
                } catch (Exception e) {
                    logger.error("Failed to open folder", e);
                }
            });
            // Insert at beginning of button bar
            buttonBar.getChildren().add(0, openFolderButton);
        }

        root.getChildren().add(buttonBar);
    }

    /**
     * Builds the button bar for the results window.
     */
    private static HBox buildButtonBar(Stage stage, CalibrationResultData result,
                                        Runnable onRedo, Runnable onTuneThresholds,
                                        boolean isSuccess) {
        HBox buttonBar = new HBox(10);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);
        buttonBar.setPadding(new Insets(10, 0, 0, 0));

        // Open Folder button (success path only - error path adds its own)
        if (isSuccess && result.calibrationPath() != null) {
            Button openFolderButton = new Button("Open Folder");
            openFolderButton.setOnAction(event -> {
                try {
                    File calibFile = new File(result.calibrationPath());
                    File parentDir = calibFile.getParentFile();
                    if (parentDir != null && parentDir.exists()) {
                        Desktop.getDesktop().open(parentDir);
                    }
                } catch (Exception e) {
                    logger.error("Failed to open folder", e);
                }
            });
            buttonBar.getChildren().add(openFolderButton);
        }

        // Tune Thresholds button
        if (onTuneThresholds != null) {
            Button tuneButton = new Button("Tune Thresholds...");
            tuneButton.setTooltip(new Tooltip(
                "Open interactive threshold preview to adjust saturation/value\n" +
                "thresholds with a live mask preview before re-running calibration."));
            tuneButton.setOnAction(event -> {
                stage.close();
                onTuneThresholds.run();
            });
            buttonBar.getChildren().add(tuneButton);
        }

        // Spacer to push close/redo to the right
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        buttonBar.getChildren().add(spacer);

        // Redo button
        if (onRedo != null) {
            Button redoButton = new Button("Go Back and Redo");
            redoButton.setOnAction(event -> {
                stage.close();
                onRedo.run();
            });
            buttonBar.getChildren().add(redoButton);
        }

        // Close button
        Button closeButton = new Button("Close");
        closeButton.setOnAction(event -> stage.close());
        buttonBar.getChildren().add(closeButton);

        return buttonBar;
    }

    /**
     * Loads an image file as a JavaFX Image.
     * Tries JavaFX native loading first, then falls back to ImageIO (which supports TIFF).
     *
     * @param imageFile the image file to load
     * @return the loaded Image, or null if loading fails
     */
    static Image loadImageWithFallback(File imageFile) {
        // Try JavaFX native loading first (supports PNG, JPEG, GIF, BMP)
        try {
            Image image = new Image(new FileInputStream(imageFile));
            if (!image.isError() && image.getWidth() > 0 && image.getHeight() > 0) {
                return image;
            }
        } catch (Exception e) {
            logger.debug("JavaFX native image loading failed for {}, trying ImageIO", imageFile.getName());
        }

        // Fall back to ImageIO (supports TIFF and other formats)
        try {
            BufferedImage buffered = ImageIO.read(imageFile);
            if (buffered != null) {
                return SwingFXUtils.toFXImage(buffered, null);
            }
        } catch (Exception e) {
            logger.debug("ImageIO loading also failed for {}", imageFile.getName());
        }

        return null;
    }

    /**
     * Loads an image file as a BufferedImage.
     * Tries ImageIO first (supports TIFF), then JavaFX native loading as fallback.
     *
     * @param imageFile the image file to load
     * @return the loaded BufferedImage, or null if loading fails
     */
    static BufferedImage loadBufferedImageWithFallback(File imageFile) {
        // Try ImageIO first (supports TIFF and other formats)
        try {
            BufferedImage buffered = ImageIO.read(imageFile);
            if (buffered != null) {
                return buffered;
            }
        } catch (Exception e) {
            logger.debug("ImageIO loading failed for {}", imageFile.getName());
        }

        return null;
    }

    /**
     * Adds a clickable image section for manual center point selection.
     * Displays the original calibration image with a crosshair overlay that follows clicks.
     * A "Retry with Selected Center" button becomes enabled after the user clicks on the image.
     * If the image cannot be loaded, the section is not shown at all.
     */
    private static void addCenterSelectionSection(VBox contentBox, Stage ownerStage,
                                                   CalibrationResultData result,
                                                   CenterRetryCallback onCenterRetry) {
        if (result.imagePath() == null) {
            return;
        }

        File imageFile = new File(result.imagePath());
        if (!imageFile.exists()) {
            logger.warn("Calibration image not found for center selection: {}", result.imagePath());
            return;
        }

        // Load image first - only show the section if loading succeeds
        Image originalImage = loadImageWithFallback(imageFile);
        if (originalImage == null) {
            logger.warn("Could not load calibration image for center selection: {}", result.imagePath());
            return;
        }

        double imgW = originalImage.getWidth();
        double imgH = originalImage.getHeight();

        contentBox.getChildren().add(new Separator());

        Label sectionLabel = new Label("Manual Center Selection:");
        sectionLabel.setStyle("-fx-font-weight: bold;");
        contentBox.getChildren().add(sectionLabel);

        Label instructionLabel = new Label(
            "Click on the center of the sunburst pattern to manually select the center point, "
            + "then press \"Retry with Selected Center\" to re-run calibration."
        );
        instructionLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #555555;");
        instructionLabel.setWrapText(true);
        contentBox.getChildren().add(instructionLabel);

        // Scale the image to fit within the dialog
        double maxDisplayWidth = 600;
        double maxDisplayHeight = 450;
        double scale = Math.min(maxDisplayWidth / imgW, maxDisplayHeight / imgH);
        if (scale > 1.0) {
            scale = 1.0;
        }
        double displayW = imgW * scale;
        double displayH = imgH * scale;
        final double finalScale = scale;

        ImageView imageView = new ImageView(originalImage);
        imageView.setFitWidth(displayW);
        imageView.setFitHeight(displayH);
        imageView.setPreserveRatio(true);

        // Canvas overlay for crosshair
        Canvas crosshairCanvas = new Canvas(displayW, displayH);
        crosshairCanvas.setCursor(Cursor.CROSSHAIR);

        StackPane imageStack = new StackPane(imageView, crosshairCanvas);
        imageStack.setAlignment(Pos.CENTER);
        imageStack.setMaxWidth(displayW);
        imageStack.setMaxHeight(displayH);

        // Coordinate display and retry button
        Label coordLabel = new Label("Selected: (none)");
        coordLabel.setStyle("-fx-font-size: 11px; -fx-font-family: monospace;");

        Button retryButton = new Button("Retry with Selected Center");
        retryButton.setDisable(true);
        retryButton.setStyle("-fx-font-weight: bold;");

        // Track selected pixel coordinates
        final int[] selectedCenter = {-1, -1};

        crosshairCanvas.setOnMouseClicked(event -> {
            double clickX = event.getX();
            double clickY = event.getY();

            // Convert display coordinates to pixel coordinates
            int pixelX = (int) Math.round(clickX / finalScale);
            int pixelY = (int) Math.round(clickY / finalScale);

            // Clamp to image bounds
            pixelX = Math.max(0, Math.min(pixelX, (int) imgW - 1));
            pixelY = Math.max(0, Math.min(pixelY, (int) imgH - 1));

            selectedCenter[0] = pixelY;
            selectedCenter[1] = pixelX;

            // Draw crosshair
            GraphicsContext gc = crosshairCanvas.getGraphicsContext2D();
            gc.clearRect(0, 0, displayW, displayH);

            gc.setStroke(Color.RED);
            gc.setLineWidth(2);
            // Horizontal line
            gc.strokeLine(0, clickY, displayW, clickY);
            // Vertical line
            gc.strokeLine(clickX, 0, clickX, displayH);

            // Center circle
            gc.setStroke(Color.YELLOW);
            gc.setLineWidth(2);
            gc.strokeOval(clickX - 10, clickY - 10, 20, 20);

            coordLabel.setText(String.format("Selected: Y=%d, X=%d (pixel coordinates)", pixelY, pixelX));
            retryButton.setDisable(false);
        });

        retryButton.setOnAction(event -> {
            if (selectedCenter[0] >= 0 && selectedCenter[1] >= 0) {
                ownerStage.close();
                onCenterRetry.retry(selectedCenter[0], selectedCenter[1]);
            }
        });

        HBox imageBox = new HBox(imageStack);
        imageBox.setAlignment(Pos.CENTER);
        contentBox.getChildren().add(imageBox);

        HBox controlsBox = new HBox(15, coordLabel, retryButton);
        controlsBox.setAlignment(Pos.CENTER);
        controlsBox.setPadding(new Insets(5, 0, 0, 0));
        contentBox.getChildren().add(controlsBox);
    }
}
