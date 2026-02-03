package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.CompletableFuture;

/**
 * Non-modal dialog for interactive threshold tuning with live mask preview.
 *
 * <p>Displays a calibration image alongside a binary foreground mask computed
 * using HSV (HSB in Java) thresholding. Sliders allow the user to adjust
 * saturation and value thresholds in real time, with the mask updating
 * immediately to show which pixels would be classified as foreground.
 *
 * <p>This allows users to visually determine the optimal thresholds before
 * re-running calibration, avoiding the full server round-trip for each
 * threshold adjustment.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class ThresholdPreviewDialog {

    private static final Logger logger = LoggerFactory.getLogger(ThresholdPreviewDialog.class);

    /**
     * Result containing the tuned threshold values.
     */
    public record ThresholdResult(
        double saturationThreshold,
        double valueThreshold
    ) {}

    /**
     * Shows the threshold preview dialog with live mask visualization.
     *
     * <p>The dialog is non-modal and owned by the QuPath main window.
     * The returned future completes with the tuned thresholds when the user
     * clicks "Run Calibration", or with null if cancelled.
     *
     * @param imagePath Path to the acquired calibration image (TIFF or other format)
     * @param initialSaturation Starting saturation threshold (0.0-1.0)
     * @param initialValue Starting value threshold (0.0-1.0)
     * @return CompletableFuture with tuned thresholds, or null if cancelled
     */
    public static CompletableFuture<ThresholdResult> showDialog(
            String imagePath, double initialSaturation, double initialValue) {

        CompletableFuture<ThresholdResult> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            // Load the image as BufferedImage for pixel access
            File imageFile = new File(imagePath);
            if (!imageFile.exists()) {
                logger.error("Calibration image not found: {}", imagePath);
                future.complete(null);
                return;
            }

            BufferedImage bufferedImage = CalibrationResultDialog.loadBufferedImageWithFallback(imageFile);
            if (bufferedImage == null) {
                logger.error("Could not load calibration image: {}", imagePath);
                future.complete(null);
                return;
            }

            Image fxImage = SwingFXUtils.toFXImage(bufferedImage, null);

            Stage stage = new Stage();
            stage.setTitle("Threshold Preview - Tune Detection Thresholds");
            stage.initModality(Modality.NONE);

            QuPathGUI gui = QuPathGUI.getInstance();
            if (gui != null && gui.getStage() != null) {
                stage.initOwner(gui.getStage());
            }

            VBox root = new VBox(10);
            root.setPadding(new Insets(10));

            // Header
            Label headerLabel = new Label("Interactive Threshold Tuning");
            headerLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

            Label instructionLabel = new Label(
                "Adjust the sliders to change threshold values. The mask on the right\n" +
                "shows which pixels are classified as foreground (white) at the current\n" +
                "thresholds. When satisfied, click \"Run Calibration\" to re-analyze."
            );
            instructionLabel.setStyle("-fx-font-size: 11px;");
            instructionLabel.setWrapText(true);

            root.getChildren().addAll(headerLabel, instructionLabel, new Separator());

            // Compute display dimensions
            int imgW = bufferedImage.getWidth();
            int imgH = bufferedImage.getHeight();
            double maxDisplayWidth = 450;
            double maxDisplayHeight = 400;
            double scale = Math.min(maxDisplayWidth / imgW, maxDisplayHeight / imgH);
            if (scale > 1.0) scale = 1.0;
            double displayW = imgW * scale;
            double displayH = imgH * scale;

            // Original image view
            ImageView originalView = new ImageView(fxImage);
            originalView.setFitWidth(displayW);
            originalView.setFitHeight(displayH);
            originalView.setPreserveRatio(true);

            // Mask image view
            ImageView maskView = new ImageView();
            maskView.setFitWidth(displayW);
            maskView.setFitHeight(displayH);
            maskView.setPreserveRatio(true);

            // Labels for images
            VBox originalBox = new VBox(5);
            originalBox.setAlignment(Pos.TOP_CENTER);
            Label origLabel = new Label("Original Image");
            origLabel.setStyle("-fx-font-weight: bold;");
            originalBox.getChildren().addAll(origLabel, originalView);

            VBox maskBox = new VBox(5);
            maskBox.setAlignment(Pos.TOP_CENTER);
            Label maskLabel = new Label("Foreground Mask");
            maskLabel.setStyle("-fx-font-weight: bold;");
            maskBox.getChildren().addAll(maskLabel, maskView);

            HBox imageRow = new HBox(15, originalBox, maskBox);
            imageRow.setAlignment(Pos.CENTER);
            root.getChildren().add(imageRow);

            // Foreground pixel count label
            Label countLabel = new Label();
            countLabel.setStyle("-fx-font-size: 11px; -fx-font-family: monospace;");
            root.getChildren().add(countLabel);

            root.getChildren().add(new Separator());

            // Sliders
            GridPane sliderGrid = new GridPane();
            sliderGrid.setHgap(10);
            sliderGrid.setVgap(8);
            sliderGrid.setPadding(new Insets(5));

            // Saturation slider
            Label satLabel = new Label("Saturation Threshold:");
            satLabel.setStyle("-fx-font-weight: bold;");
            Slider satSlider = new Slider(0.0, 0.5, initialSaturation);
            satSlider.setShowTickLabels(true);
            satSlider.setShowTickMarks(true);
            satSlider.setMajorTickUnit(0.1);
            satSlider.setMinorTickCount(4);
            satSlider.setBlockIncrement(0.01);
            satSlider.setPrefWidth(300);
            Label satValueLabel = new Label(String.format("%.3f", initialSaturation));
            satValueLabel.setStyle("-fx-font-family: monospace; -fx-min-width: 50px;");

            sliderGrid.add(satLabel, 0, 0);
            sliderGrid.add(satSlider, 1, 0);
            sliderGrid.add(satValueLabel, 2, 0);

            // Value slider
            Label valLabel = new Label("Value Threshold:");
            valLabel.setStyle("-fx-font-weight: bold;");
            Slider valSlider = new Slider(0.0, 0.5, initialValue);
            valSlider.setShowTickLabels(true);
            valSlider.setShowTickMarks(true);
            valSlider.setMajorTickUnit(0.1);
            valSlider.setMinorTickCount(4);
            valSlider.setBlockIncrement(0.01);
            valSlider.setPrefWidth(300);
            Label valValueLabel = new Label(String.format("%.3f", initialValue));
            valValueLabel.setStyle("-fx-font-family: monospace; -fx-min-width: 50px;");

            sliderGrid.add(valLabel, 0, 1);
            sliderGrid.add(valSlider, 1, 1);
            sliderGrid.add(valValueLabel, 2, 1);

            root.getChildren().add(sliderGrid);

            // Compute initial mask
            WritableImage[] maskImageHolder = new WritableImage[1];
            maskImageHolder[0] = computeMask(bufferedImage, initialSaturation, initialValue, countLabel);
            maskView.setImage(maskImageHolder[0]);

            // Slider listeners - update mask on change
            satSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
                double sat = newVal.doubleValue();
                satValueLabel.setText(String.format("%.3f", sat));
                maskImageHolder[0] = computeMask(bufferedImage, sat, valSlider.getValue(), countLabel);
                maskView.setImage(maskImageHolder[0]);
            });

            valSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
                double val = newVal.doubleValue();
                valValueLabel.setText(String.format("%.3f", val));
                maskImageHolder[0] = computeMask(bufferedImage, satSlider.getValue(), val, countLabel);
                maskView.setImage(maskImageHolder[0]);
            });

            root.getChildren().add(new Separator());

            // Button bar
            Button runButton = new Button("Run Calibration with These Thresholds");
            runButton.setDefaultButton(true);
            runButton.setStyle("-fx-font-weight: bold;");

            Button cancelButton = new Button("Cancel");
            cancelButton.setCancelButton(true);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox buttonBar = new HBox(10, spacer, runButton, cancelButton);
            buttonBar.setAlignment(Pos.CENTER_RIGHT);
            buttonBar.setPadding(new Insets(5, 0, 0, 0));

            root.getChildren().add(buttonBar);

            runButton.setOnAction(e -> {
                ThresholdResult result = new ThresholdResult(
                    satSlider.getValue(), valSlider.getValue());
                stage.close();
                future.complete(result);
            });

            cancelButton.setOnAction(e -> {
                stage.close();
                future.complete(null);
            });

            stage.setOnCloseRequest(e -> {
                if (!future.isDone()) {
                    future.complete(null);
                }
            });

            // Wrap in scroll pane for smaller screens
            ScrollPane scrollPane = new ScrollPane(root);
            scrollPane.setFitToWidth(true);

            double sceneWidth = Math.max(700, displayW * 2 + 60);
            double sceneHeight = displayH + 350;
            Scene scene = new Scene(scrollPane, sceneWidth, sceneHeight);
            stage.setScene(scene);
            stage.setResizable(true);
            stage.show();
        });

        return future;
    }

    /**
     * Computes a binary foreground mask using HSV thresholding.
     *
     * <p>A pixel is classified as foreground if its HSV saturation exceeds
     * the saturation threshold AND its HSV value (brightness) exceeds the
     * value threshold. This matches the Python ppm_library implementation.
     *
     * @param image Source image
     * @param satThreshold Minimum saturation (0.0-1.0)
     * @param valThreshold Minimum value/brightness (0.0-1.0)
     * @param countLabel Label to update with foreground pixel count (may be null)
     * @return WritableImage with white foreground and black background
     */
    private static WritableImage computeMask(BufferedImage image, double satThreshold,
                                              double valThreshold, Label countLabel) {
        int w = image.getWidth();
        int h = image.getHeight();
        WritableImage mask = new WritableImage(w, h);
        PixelWriter writer = mask.getPixelWriter();

        int foregroundCount = 0;
        int totalPixels = w * h;
        float[] hsb = new float[3];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                java.awt.Color.RGBtoHSB(r, g, b, hsb);

                boolean isForeground = (hsb[1] > satThreshold) && (hsb[2] > valThreshold);

                if (isForeground) {
                    writer.setArgb(x, y, 0xFFFFFFFF); // White
                    foregroundCount++;
                } else {
                    writer.setArgb(x, y, 0xFF000000); // Black
                }
            }
        }

        if (countLabel != null) {
            double percentage = (100.0 * foregroundCount) / totalPixels;
            final String text = String.format("Foreground: %,d / %,d pixels (%.1f%%)",
                    foregroundCount, totalPixels, percentage);
            countLabel.setText(text);
        }

        return mask;
    }
}
