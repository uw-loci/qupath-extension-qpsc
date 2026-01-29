package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.io.FileInputStream;
import java.util.List;

/**
 * Dialog for displaying calibration results.
 *
 * Shows the calibration metrics, any warnings, and displays the calibration plot image.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class CalibrationResultDialog {

    private static final Logger logger = LoggerFactory.getLogger(CalibrationResultDialog.class);

    /**
     * Calibration result data to display.
     */
    public record CalibrationResultData(
            boolean success,
            double rSquared,
            int rectanglesDetected,
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
        public static CalibrationResultData success(double rSquared, int rectanglesDetected,
                                                    String plotPath, String calibrationPath,
                                                    String imagePath, String maskPath, List<String> warnings) {
            return new CalibrationResultData(true, rSquared, rectanglesDetected,
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
        Platform.runLater(() -> {
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setTitle("PPM Reference Slide Calibration Results");

            if (result.success()) {
                showSuccessDialog(dialog, result, onRedo);
            } else {
                showErrorDialog(dialog, result, onRedo);
            }

            dialog.showAndWait().ifPresent(btn -> {
                if ("REDO".equals(btn.getText()) && onRedo != null) {
                    onRedo.run();
                }
            });
        });
    }

    private static void showSuccessDialog(Dialog<ButtonType> dialog, CalibrationResultData result,
                                             Runnable onRedo) {
        // Header
        VBox headerBox = new VBox(10);
        headerBox.setPadding(new Insets(10));

        Label headerLabel = new Label("Calibration Completed Successfully");
        headerLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: green;");

        headerBox.getChildren().add(headerLabel);
        dialog.getDialogPane().setHeader(headerBox);

        // Main content
        VBox contentBox = new VBox(15);
        contentBox.setPadding(new Insets(20));

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

        // Rectangles detected
        Label rectanglesLabel = new Label("Rectangles Detected:");
        rectanglesLabel.setStyle("-fx-font-weight: bold;");
        Label rectanglesValue = new Label(String.valueOf(result.rectanglesDetected()));
        resultsGrid.add(rectanglesLabel, 0, row);
        resultsGrid.add(rectanglesValue, 1, row);
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

        contentBox.getChildren().add(resultsGrid);

        // Warnings section (if any)
        if (result.warnings() != null && !result.warnings().isEmpty()) {
            contentBox.getChildren().add(new Separator());

            Label warningsHeader = new Label("Warnings:");
            warningsHeader.setStyle("-fx-font-weight: bold; -fx-text-fill: orange;");
            contentBox.getChildren().add(warningsHeader);

            for (String warning : result.warnings()) {
                Label warningLabel = new Label("  - " + warning);
                warningLabel.setStyle("-fx-text-fill: orange; -fx-font-size: 11px;");
                warningLabel.setWrapText(true);
                contentBox.getChildren().add(warningLabel);
            }
        }

        contentBox.getChildren().add(new Separator());

        // Plot image
        if (result.plotPath() != null) {
            Label plotLabel = new Label("Calibration Plot:");
            plotLabel.setStyle("-fx-font-weight: bold;");
            contentBox.getChildren().add(plotLabel);

            try {
                File plotFile = new File(result.plotPath());
                if (plotFile.exists()) {
                    Image plotImage = new Image(new FileInputStream(plotFile));
                    ImageView imageView = new ImageView(plotImage);

                    // Scale image to fit dialog while maintaining aspect ratio
                    double maxWidth = 700;
                    double maxHeight = 500;
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
                    contentBox.getChildren().add(imageBox);
                } else {
                    Label noPlotLabel = new Label("(Plot image not found: " + result.plotPath() + ")");
                    noPlotLabel.setStyle("-fx-text-fill: gray;");
                    contentBox.getChildren().add(noPlotLabel);
                }
            } catch (Exception e) {
                logger.error("Failed to load plot image", e);
                Label errorLabel = new Label("(Failed to load plot image: " + e.getMessage() + ")");
                errorLabel.setStyle("-fx-text-fill: red;");
                contentBox.getChildren().add(errorLabel);
            }
        }

        // Wrap in scroll pane for large content
        ScrollPane scrollPane = new ScrollPane(contentBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefViewportHeight(600);
        scrollPane.setPrefViewportWidth(750);

        dialog.getDialogPane().setContent(scrollPane);

        // Buttons - use CANCEL_CLOSE for Close button so X button works
        ButtonType openFolderType = new ButtonType("Open Folder", ButtonBar.ButtonData.LEFT);
        ButtonType closeType = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(openFolderType, closeType);

        // Add redo button if callback is provided
        if (onRedo != null) {
            ButtonType redoType = new ButtonType("REDO", ButtonBar.ButtonData.BACK_PREVIOUS);
            dialog.getDialogPane().getButtonTypes().add(redoType);
            Button redoButton = (Button) dialog.getDialogPane().lookupButton(redoType);
            redoButton.setText("Go Back and Redo");
        }

        // Handle open folder button
        Button openFolderButton = (Button) dialog.getDialogPane().lookupButton(openFolderType);
        openFolderButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                File calibFile = new File(result.calibrationPath());
                File parentDir = calibFile.getParentFile();
                if (parentDir != null && parentDir.exists()) {
                    Desktop.getDesktop().open(parentDir);
                }
            } catch (Exception e) {
                logger.error("Failed to open folder", e);
            }
            event.consume();  // Don't close dialog
        });
    }

    private static void showErrorDialog(Dialog<ButtonType> dialog, CalibrationResultData result,
                                            Runnable onRedo) {
        // Header
        VBox headerBox = new VBox(10);
        headerBox.setPadding(new Insets(10));

        Label headerLabel = new Label("Calibration Failed");
        headerLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: red;");

        headerBox.getChildren().add(headerLabel);
        dialog.getDialogPane().setHeader(headerBox);

        // Main content
        VBox contentBox = new VBox(15);
        contentBox.setPadding(new Insets(20));

        Label errorLabel = new Label("Error:");
        errorLabel.setStyle("-fx-font-weight: bold;");
        contentBox.getChildren().add(errorLabel);

        TextArea errorText = new TextArea(result.errorMessage());
        errorText.setEditable(false);
        errorText.setWrapText(true);
        errorText.setPrefRowCount(4);
        errorText.setStyle("-fx-font-family: monospace;");
        contentBox.getChildren().add(errorText);

        contentBox.getChildren().add(new Separator());

        // Debug images section (if available)
        boolean hasDebugImages = (result.imagePath() != null || result.maskPath() != null);
        String debugFolderPath = null;

        if (hasDebugImages) {
            Label debugLabel = new Label("Debug Images (for troubleshooting):");
            debugLabel.setStyle("-fx-font-weight: bold;");
            contentBox.getChildren().add(debugLabel);

            // Show segmentation mask if available (most useful for debugging)
            if (result.maskPath() != null) {
                try {
                    File maskFile = new File(result.maskPath());
                    if (maskFile.exists()) {
                        debugFolderPath = maskFile.getParent();

                        Label maskLabel = new Label("Segmentation Mask:");
                        maskLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");
                        contentBox.getChildren().add(maskLabel);

                        Image maskImage = new Image(new FileInputStream(maskFile));
                        ImageView maskView = new ImageView(maskImage);

                        // Scale to fit dialog
                        double maxWidth = 650;
                        double maxHeight = 400;
                        double scale = Math.min(maxWidth / maskImage.getWidth(),
                                               maxHeight / maskImage.getHeight());
                        if (scale < 1.0) {
                            maskView.setFitWidth(maskImage.getWidth() * scale);
                            maskView.setFitHeight(maskImage.getHeight() * scale);
                        }
                        maskView.setPreserveRatio(true);

                        HBox maskBox = new HBox(maskView);
                        maskBox.setAlignment(Pos.CENTER);
                        contentBox.getChildren().add(maskBox);

                        Label maskHint = new Label(
                            "White pixels = detected foreground (colored regions above threshold).\n" +
                            "Black pixels = background (below threshold).\n" +
                            "All black: thresholds too high - lower saturation/value thresholds.\n" +
                            "All white: thresholds too low - raise saturation/value thresholds."
                        );
                        maskHint.setStyle("-fx-font-size: 10px; -fx-text-fill: #888888;");
                        maskHint.setWrapText(true);
                        contentBox.getChildren().add(maskHint);
                    } else {
                        Label noMaskLabel = new Label("(Mask file not found: " + result.maskPath() + ")");
                        noMaskLabel.setStyle("-fx-text-fill: gray; -fx-font-size: 10px;");
                        contentBox.getChildren().add(noMaskLabel);
                    }
                } catch (Exception e) {
                    logger.error("Failed to load mask image", e);
                    Label errorMaskLabel = new Label("(Failed to load mask: " + e.getMessage() + ")");
                    errorMaskLabel.setStyle("-fx-text-fill: gray; -fx-font-size: 10px;");
                    contentBox.getChildren().add(errorMaskLabel);
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
                contentBox.getChildren().add(imgPathLabel);
            }
            if (result.maskPath() != null) {
                Label maskPathLabel = new Label("Segmentation mask: " + result.maskPath());
                maskPathLabel.setStyle("-fx-font-size: 9px; -fx-text-fill: #888888;");
                maskPathLabel.setWrapText(true);
                contentBox.getChildren().add(maskPathLabel);
            }

            contentBox.getChildren().add(new Separator());
        }

        // Troubleshooting tips
        Label tipsLabel = new Label("Troubleshooting Tips:");
        tipsLabel.setStyle("-fx-font-weight: bold;");
        contentBox.getChildren().add(tipsLabel);

        String tips =
            "1. Ensure the calibration slide is properly positioned and focused\n" +
            "2. Check that the slide has visible colored rectangles\n" +
            "3. Try adjusting saturation/value thresholds if detection is failing\n" +
            "4. Verify the microscope server is running and connected\n" +
            "5. Check the server log for detailed error information";

        Label tipsText = new Label(tips);
        tipsText.setStyle("-fx-font-size: 11px;");
        contentBox.getChildren().add(tipsText);

        // Wrap in scroll pane for potentially large content with images
        ScrollPane scrollPane = new ScrollPane(contentBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefViewportHeight(hasDebugImages ? 600 : 300);
        scrollPane.setPrefViewportWidth(hasDebugImages ? 700 : 500);

        dialog.getDialogPane().setContent(scrollPane);

        // Determine folder to open - prefer debug folder, fall back to output folder
        String folderPath = debugFolderPath;
        if (folderPath == null && result.outputFolder() != null) {
            folderPath = result.outputFolder();
        }
        final String folderToOpen = folderPath;

        // Always show Open Folder button when we have a folder path
        ButtonType closeType = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
        if (folderToOpen != null) {
            String buttonLabel = hasDebugImages ? "Open Debug Folder" : "Open Output Folder";
            ButtonType openFolderType = new ButtonType(buttonLabel, ButtonBar.ButtonData.LEFT);
            dialog.getDialogPane().getButtonTypes().addAll(openFolderType, closeType);

            Button openFolderButton = (Button) dialog.getDialogPane().lookupButton(openFolderType);
            openFolderButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                try {
                    File folder = new File(folderToOpen);
                    // Open parent if folder doesn't exist yet (calibration may not have created it)
                    if (!folder.exists()) {
                        folder = folder.getParentFile();
                    }
                    if (folder != null && folder.exists()) {
                        Desktop.getDesktop().open(folder);
                    }
                } catch (Exception e) {
                    logger.error("Failed to open folder", e);
                }
                event.consume();  // Don't close dialog
            });
        } else {
            dialog.getDialogPane().getButtonTypes().add(closeType);
        }

        // Add redo button if callback is provided
        if (onRedo != null) {
            ButtonType redoType = new ButtonType("REDO", ButtonBar.ButtonData.BACK_PREVIOUS);
            dialog.getDialogPane().getButtonTypes().add(redoType);
            Button redoButton = (Button) dialog.getDialogPane().lookupButton(redoType);
            redoButton.setText("Go Back and Redo");
        }
    }
}
