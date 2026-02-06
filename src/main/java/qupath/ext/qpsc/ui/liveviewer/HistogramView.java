package qupath.ext.qpsc.ui.liveviewer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * Histogram display with min/max contrast sliders and FullRange/AutoScale buttons.
 * <p>
 * The histogram canvas is aligned directly above the slider tracks, with
 * labels placed to the left so the visual elements line up vertically.
 * <p>
 * Features:
 * <ul>
 *   <li>256-bin luminance histogram with log-scale Y axis</li>
 *   <li>Min/max contrast range sliders aligned under the histogram</li>
 *   <li>Full Range and Auto Scale buttons</li>
 *   <li>Visual overlay showing active contrast range</li>
 * </ul>
 */
public class HistogramView extends VBox {

    private static final int HIST_HEIGHT = 80;
    // Label column width -- keeps histogram and sliders aligned
    private static final double LABEL_WIDTH = 50;

    private final Canvas canvas;
    private final Slider minSlider;
    private final Slider maxSlider;
    private final Label minLabel;
    private final Label maxLabel;
    private final ContrastSettings contrastSettings;

    private int[] currentHistogram = new int[256];
    private int currentMaxValue = 255;

    // Throttling: skip histogram updates if too frequent
    private long lastHistogramUpdateMs = 0;
    private static final long HISTOGRAM_THROTTLE_MS = 200;

    public HistogramView(ContrastSettings contrastSettings) {
        this.contrastSettings = contrastSettings;

        setSpacing(2);
        setPadding(new Insets(4, 8, 4, 8));

        // --- Row 1: Histogram canvas (full width) ---
        // Canvas stretches to fill available width
        canvas = new Canvas(256, HIST_HEIGHT);

        HBox histRow = new HBox(canvas);
        histRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(histRow, Priority.ALWAYS);

        // Bind canvas width to its parent HBox width (minus padding)
        histRow.widthProperty().addListener((obs, oldVal, newVal) -> {
            double newWidth = newVal.doubleValue();
            if (newWidth > 0) {
                canvas.setWidth(newWidth);
                drawHistogram();
            }
        });

        drawHistogram();

        // --- Row 2: Min label + slider ---
        minLabel = new Label("Min: 0");
        minLabel.setMinWidth(LABEL_WIDTH);
        minLabel.setMaxWidth(LABEL_WIDTH);

        minSlider = new Slider(0, 255, 0);
        HBox.setHgrow(minSlider, Priority.ALWAYS);

        HBox minRow = new HBox(4, minLabel, minSlider);
        minRow.setAlignment(Pos.CENTER_LEFT);

        // --- Row 3: Max label + slider ---
        maxLabel = new Label("Max: 255");
        maxLabel.setMinWidth(LABEL_WIDTH);
        maxLabel.setMaxWidth(LABEL_WIDTH);

        maxSlider = new Slider(0, 255, 255);
        HBox.setHgrow(maxSlider, Priority.ALWAYS);

        HBox maxRow = new HBox(4, maxLabel, maxSlider);
        maxRow.setAlignment(Pos.CENTER_LEFT);

        // --- Slider listeners ---
        minSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            int min = newVal.intValue();
            int scaledMin = min * currentMaxValue / 255;
            contrastSettings.setDisplayMin(scaledMin);
            contrastSettings.setAutoScale(false);
            minLabel.setText("Min: " + scaledMin);
            drawHistogram();
        });

        maxSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            int max = newVal.intValue();
            int scaledMax = max * currentMaxValue / 255;
            contrastSettings.setDisplayMax(scaledMax);
            contrastSettings.setAutoScale(false);
            maxLabel.setText("Max: " + scaledMax);
            drawHistogram();
        });

        // --- Row 4: Buttons ---
        Button fullRangeBtn = new Button("Full Range");
        Button autoScaleBtn = new Button("Auto Scale");

        fullRangeBtn.setOnAction(e -> {
            contrastSettings.setDisplayMin(0);
            contrastSettings.setDisplayMax(currentMaxValue);
            contrastSettings.setAutoScale(false);
            updateSlidersFromSettings();
            drawHistogram();
        });

        autoScaleBtn.setOnAction(e -> {
            contrastSettings.applyAutoScale(currentHistogram,
                    new FrameData(0, 0, 1, currentMaxValue > 255 ? 2 : 1, new byte[0], 0));
            contrastSettings.setAutoScale(true);
            updateSlidersFromSettings();
            drawHistogram();
        });

        HBox buttonRow = new HBox(8, fullRangeBtn, autoScaleBtn);
        buttonRow.setAlignment(Pos.CENTER);
        buttonRow.setPadding(new Insets(2, 0, 0, 0));

        getChildren().addAll(histRow, minRow, maxRow, buttonRow);
    }

    /**
     * Updates the histogram from a new frame. Throttled to max ~5 Hz.
     *
     * @param frame The current frame data
     */
    public void updateHistogram(FrameData frame) {
        long now = System.currentTimeMillis();
        if (now - lastHistogramUpdateMs < HISTOGRAM_THROTTLE_MS) {
            return;
        }
        lastHistogramUpdateMs = now;

        currentMaxValue = frame.maxValue();

        int[] histogram = new int[256];
        int bpp = frame.bytesPerPixel();
        int channels = frame.channels();
        int pixelCount = frame.pixelCount();
        byte[] raw = frame.rawPixels();

        if (channels == 1) {
            // Grayscale
            for (int i = 0; i < pixelCount; i++) {
                int val;
                if (bpp == 1) {
                    val = raw[i] & 0xFF;
                } else {
                    int offset = i * 2;
                    val = ((raw[offset] & 0xFF) << 8) | (raw[offset + 1] & 0xFF);
                }
                int bin = val * 255 / currentMaxValue;
                bin = Math.min(bin, 255);
                histogram[bin]++;
            }
        } else {
            // RGB: luminance histogram L = 0.299*R + 0.587*G + 0.114*B
            int bytesPerSample = bpp;
            int stride = channels * bytesPerSample;
            for (int i = 0; i < pixelCount; i++) {
                int offset = i * stride;
                int r, g, b;
                if (bpp == 1) {
                    r = raw[offset] & 0xFF;
                    g = raw[offset + 1] & 0xFF;
                    b = raw[offset + 2] & 0xFF;
                } else {
                    r = ((raw[offset] & 0xFF) << 8) | (raw[offset + 1] & 0xFF);
                    g = ((raw[offset + 2] & 0xFF) << 8) | (raw[offset + 3] & 0xFF);
                    b = ((raw[offset + 4] & 0xFF) << 8) | (raw[offset + 5] & 0xFF);
                }
                int luminance = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                int bin = luminance * 255 / currentMaxValue;
                bin = Math.min(bin, 255);
                histogram[bin]++;
            }
        }

        currentHistogram = histogram;

        // Auto-scale if enabled
        if (contrastSettings.isAutoScale()) {
            contrastSettings.applyAutoScale(histogram, frame);
        }

        // Update sliders to reflect current settings
        updateSlidersFromSettings();
        drawHistogram();
    }

    private void updateSlidersFromSettings() {
        if (currentMaxValue == 0) return;
        int sliderMin = contrastSettings.getDisplayMin() * 255 / currentMaxValue;
        int sliderMax = contrastSettings.getDisplayMax() * 255 / currentMaxValue;
        minSlider.setValue(Math.max(0, Math.min(255, sliderMin)));
        maxSlider.setValue(Math.max(0, Math.min(255, sliderMax)));
        minLabel.setText("Min: " + contrastSettings.getDisplayMin());
        maxLabel.setText("Max: " + contrastSettings.getDisplayMax());
    }

    private void drawHistogram() {
        double canvasWidth = canvas.getWidth();
        GraphicsContext gc = canvas.getGraphicsContext2D();

        // Clear
        gc.setFill(Color.BLACK);
        gc.fillRect(0, 0, canvasWidth, HIST_HEIGHT);

        // Find max for log scale normalization
        double maxLog = 0;
        for (int count : currentHistogram) {
            if (count > 0) {
                double logVal = Math.log1p(count);
                if (logVal > maxLog) maxLog = logVal;
            }
        }

        if (maxLog == 0) maxLog = 1;

        // Draw contrast range shading
        int rangeMin = contrastSettings.getDisplayMin() * 255 / Math.max(1, currentMaxValue);
        int rangeMax = contrastSettings.getDisplayMax() * 255 / Math.max(1, currentMaxValue);
        double barWidth = canvasWidth / 256.0;
        gc.setFill(Color.rgb(40, 40, 80));
        gc.fillRect(rangeMin * barWidth, 0, (rangeMax - rangeMin) * barWidth, HIST_HEIGHT);

        // Draw histogram bars
        gc.setFill(Color.LIGHTGRAY);
        for (int i = 0; i < 256; i++) {
            if (currentHistogram[i] > 0) {
                double logVal = Math.log1p(currentHistogram[i]);
                double barHeight = (logVal / maxLog) * (HIST_HEIGHT - 2);
                gc.fillRect(i * barWidth, HIST_HEIGHT - barHeight, Math.max(barWidth, 1), barHeight);
            }
        }

        // Draw min/max lines
        gc.setStroke(Color.CYAN);
        gc.setLineWidth(1);
        gc.strokeLine(rangeMin * barWidth, 0, rangeMin * barWidth, HIST_HEIGHT);
        gc.strokeLine(rangeMax * barWidth, 0, rangeMax * barWidth, HIST_HEIGHT);
    }
}
