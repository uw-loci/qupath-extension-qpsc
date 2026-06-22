package qupath.ext.qpsc.ui.liveviewer;

import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * Histogram display with min/max contrast sliders and FullRange/AutoScale buttons.
 *
 * <p>Supports two layouts (see {@link #setVertical(boolean)}):
 * <ul>
 *   <li><b>Horizontal</b> (default) -- intensity on X, bars rising from the
 *       bottom, horizontal sliders beneath. Suits a panel docked under the
 *       live image.</li>
 *   <li><b>Vertical</b> -- intensity on Y (low at the bottom), bars extending
 *       rightward, vertical sliders beside the canvas. Suits a tall panel
 *       docked on the right. The text labels and buttons stay in their normal
 *       (horizontal) orientation for readability.</li>
 * </ul>
 */
public class HistogramView extends VBox {

    // Thickness of the "count" axis of the histogram canvas (height in
    // horizontal mode, width in vertical mode). The other axis fills.
    private static final int HIST_THICKNESS = 80;
    // Label column width -- keeps histogram and sliders aligned in horizontal
    // mode. Must fit "Max: 65535" for 16-bit images.
    private static final double LABEL_WIDTH = 80;

    private final Canvas canvas;
    private final Slider minSlider;
    private final Slider maxSlider;
    private final Label minLabel;
    private final Label maxLabel;
    private final Label meanLabel;
    private final Button fullRangeBtn;
    private final Button autoScaleBtn;
    private final CheckBox alwaysAutoScale;
    private final ContrastSettings contrastSettings;

    private boolean vertical = false;

    private int[] currentHistogram = new int[256];
    private int currentMaxValue = 255;

    // Throttling: skip histogram updates if too frequent
    private long lastHistogramUpdateMs = 0;
    private static final long HISTOGRAM_THROTTLE_MS = 200;

    public HistogramView(ContrastSettings contrastSettings) {
        this.contrastSettings = contrastSettings;

        setSpacing(2);
        setPadding(new Insets(4, 8, 4, 8));

        canvas = new Canvas(256, HIST_THICKNESS);
        // Redraw whenever the canvas is resized (its size is driven by the
        // container listeners installed in applyLayout()).
        canvas.widthProperty().addListener((obs, oldVal, newVal) -> drawHistogram());
        canvas.heightProperty().addListener((obs, oldVal, newVal) -> drawHistogram());

        meanLabel = new Label("Mean: --");
        meanLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 11;");
        meanLabel.setPadding(new Insets(2, 0, 2, 0));

        minLabel = new Label("Min: 0");
        minLabel.setMinWidth(LABEL_WIDTH);
        minLabel.setMaxWidth(LABEL_WIDTH);
        minSlider = new Slider(0, 255, 0);

        maxLabel = new Label("Max: 255");
        maxLabel.setMinWidth(LABEL_WIDTH);
        maxLabel.setMaxWidth(LABEL_WIDTH);
        maxSlider = new Slider(0, 255, 255);

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

        // --- Buttons + Always Auto-Scale checkbox ---
        fullRangeBtn = new Button("Full Range");
        autoScaleBtn = new Button("Auto Scale");
        alwaysAutoScale = new CheckBox("Always Auto-Scale");
        alwaysAutoScale.setTooltip(
                new javafx.scene.control.Tooltip("When checked, every live frame and acquired tile is auto-scaled.\n"
                        + "When unchecked, your manual Min/Max sliders are preserved.\n"
                        + "Auto Scale button always does a one-shot rescale."));
        alwaysAutoScale.setSelected(contrastSettings.isAutoScale());

        fullRangeBtn.setOnAction(e -> {
            contrastSettings.setDisplayMin(0);
            contrastSettings.setDisplayMax(currentMaxValue);
            contrastSettings.setAutoScale(false);
            alwaysAutoScale.setSelected(false);
            updateSlidersFromSettings();
            drawHistogram();
        });

        // One-shot rescale: apply auto-scale once but do NOT flip the
        // persistent autoScale flag. The "Always Auto-Scale" checkbox
        // owns the persistent flag.
        autoScaleBtn.setOnAction(e -> {
            contrastSettings.applyAutoScale(
                    currentHistogram, new FrameData(0, 0, 1, currentMaxValue > 255 ? 2 : 1, new byte[0], 0));
            updateSlidersFromSettings();
            drawHistogram();
        });

        alwaysAutoScale.selectedProperty().addListener((obs, oldVal, newVal) -> {
            contrastSettings.setAutoScale(newVal);
            if (newVal) {
                contrastSettings.applyAutoScale(
                        currentHistogram, new FrameData(0, 0, 1, currentMaxValue > 255 ? 2 : 1, new byte[0], 0));
                updateSlidersFromSettings();
                drawHistogram();
            }
        });

        // Slider drag turns off the persistent flag. Reflect that in the
        // checkbox so its state stays in sync with the underlying setting.
        minSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (alwaysAutoScale.isSelected()) alwaysAutoScale.setSelected(false);
        });
        maxSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (alwaysAutoScale.isSelected()) alwaysAutoScale.setSelected(false);
        });

        applyLayout();
    }

    /**
     * Switches between the horizontal (below-image) and vertical (right-side)
     * layouts. The histogram bars and the min/max sliders reorient; the text
     * labels and buttons stay upright.
     */
    public void setVertical(boolean v) {
        if (this.vertical == v) {
            return;
        }
        this.vertical = v;
        applyLayout();
    }

    public boolean isVertical() {
        return vertical;
    }

    /** (Re)builds the child layout for the current orientation. */
    private void applyLayout() {
        getChildren().clear();

        if (vertical) {
            minSlider.setOrientation(Orientation.VERTICAL);
            maxSlider.setOrientation(Orientation.VERTICAL);
            minSlider.setMaxHeight(Double.MAX_VALUE);
            maxSlider.setMaxHeight(Double.MAX_VALUE);
            canvas.setWidth(HIST_THICKNESS);

            HBox histArea = new HBox(4, canvas, minSlider, maxSlider);
            histArea.setAlignment(Pos.TOP_LEFT);
            histArea.setMinHeight(0);
            VBox.setVgrow(histArea, Priority.ALWAYS);
            VBox.setVgrow(minSlider, Priority.ALWAYS);
            VBox.setVgrow(maxSlider, Priority.ALWAYS);
            // Canvas height tracks the (growing) histogram area.
            histArea.heightProperty().addListener((obs, oldVal, newVal) -> {
                double h = newVal.doubleValue();
                if (h > 0) {
                    canvas.setHeight(h);
                }
            });

            HBox valueRow = new HBox(8, minLabel, maxLabel);
            valueRow.setAlignment(Pos.CENTER_LEFT);
            VBox buttons = new VBox(4, fullRangeBtn, autoScaleBtn, alwaysAutoScale);
            buttons.setPadding(new Insets(2, 0, 0, 0));

            getChildren().addAll(histArea, meanLabel, valueRow, buttons);
        } else {
            minSlider.setOrientation(Orientation.HORIZONTAL);
            maxSlider.setOrientation(Orientation.HORIZONTAL);
            minSlider.setMaxHeight(Region.USE_COMPUTED_SIZE);
            maxSlider.setMaxHeight(Region.USE_COMPUTED_SIZE);
            canvas.setHeight(HIST_THICKNESS);

            HBox histRow = new HBox(canvas);
            histRow.setAlignment(Pos.CENTER_LEFT);
            // Prevent the non-resizable Canvas from dictating the parent's min
            // width (otherwise the window slowly grows on every layout pass).
            histRow.setMinWidth(0);
            histRow.widthProperty().addListener((obs, oldVal, newVal) -> {
                double w = newVal.doubleValue();
                if (w > 0) {
                    canvas.setWidth(w);
                }
            });

            HBox.setHgrow(minSlider, Priority.ALWAYS);
            HBox.setHgrow(maxSlider, Priority.ALWAYS);
            HBox minRow = new HBox(4, minLabel, minSlider);
            minRow.setAlignment(Pos.CENTER_LEFT);
            HBox maxRow = new HBox(4, maxLabel, maxSlider);
            maxRow.setAlignment(Pos.CENTER_LEFT);

            HBox buttonRow = new HBox(8, fullRangeBtn, autoScaleBtn, alwaysAutoScale);
            buttonRow.setAlignment(Pos.CENTER);
            buttonRow.setPadding(new Insets(2, 0, 0, 0));

            getChildren().addAll(histRow, meanLabel, minRow, maxRow, buttonRow);
        }

        drawHistogram();
    }

    /**
     * Updates the histogram from a new frame. Throttled to max ~5 Hz.
     * Auto-scale is applied only if {@link ContrastSettings#isAutoScale()} is on
     * (i.e. when "Always Auto-Scale" is checked).
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

        long sumR = 0, sumG = 0, sumB = 0;
        long satR = 0, satG = 0, satB = 0;

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
                sumR += val;
                if (val >= currentMaxValue) satR++;
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
                sumR += r;
                sumG += g;
                sumB += b;
                if (r >= currentMaxValue) satR++;
                if (g >= currentMaxValue) satG++;
                if (b >= currentMaxValue) satB++;
                int luminance = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                int bin = luminance * 255 / currentMaxValue;
                bin = Math.min(bin, 255);
                histogram[bin]++;
            }
        }

        currentHistogram = histogram;

        // Update mean label with raw pixel means and saturation percentages
        if (pixelCount > 0) {
            if (channels == 1) {
                double satPct = 100.0 * satR / pixelCount;
                meanLabel.setText(String.format("Mean: %.1f  |  Sat: %.1f%%", (double) sumR / pixelCount, satPct));
                meanLabel.setTextFill(satPct > 1.0 ? Color.RED : Color.GRAY);
            } else {
                double satPctR = 100.0 * satR / pixelCount;
                double satPctG = 100.0 * satG / pixelCount;
                double satPctB = 100.0 * satB / pixelCount;
                boolean anySaturated = satPctR > 1.0 || satPctG > 1.0 || satPctB > 1.0;
                meanLabel.setText(String.format(
                        "Mean: R=%.1f  G=%.1f  B=%.1f  |  Sat: R=%.1f%%  G=%.1f%%  B=%.1f%%",
                        (double) sumR / pixelCount,
                        (double) sumG / pixelCount,
                        (double) sumB / pixelCount,
                        satPctR,
                        satPctG,
                        satPctB));
                meanLabel.setTextFill(anySaturated ? Color.RED : Color.GRAY);
            }
        }

        // Auto-scale only if "Always Auto-Scale" is on.
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
        if (vertical) {
            drawHistogramVertical();
        } else {
            drawHistogramHorizontal();
        }
    }

    private void drawHistogramHorizontal() {
        double canvasWidth = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext gc = canvas.getGraphicsContext2D();

        gc.setFill(Color.BLACK);
        gc.fillRect(0, 0, canvasWidth, h);

        double maxLog = maxLogCount();

        int rangeMin = contrastSettings.getDisplayMin() * 255 / Math.max(1, currentMaxValue);
        int rangeMax = contrastSettings.getDisplayMax() * 255 / Math.max(1, currentMaxValue);
        double barWidth = canvasWidth / 256.0;

        // Contrast range shading
        gc.setFill(Color.rgb(40, 40, 80));
        gc.fillRect(rangeMin * barWidth, 0, (rangeMax - rangeMin) * barWidth, h);

        // Bars (rise from the bottom)
        gc.setFill(Color.LIGHTGRAY);
        for (int i = 0; i < 256; i++) {
            if (currentHistogram[i] > 0) {
                double barHeight = (Math.log1p(currentHistogram[i]) / maxLog) * (h - 2);
                gc.fillRect(i * barWidth, h - barHeight, Math.max(barWidth, 1), barHeight);
            }
        }

        // Min/max lines
        gc.setStroke(Color.CYAN);
        gc.setLineWidth(1);
        gc.strokeLine(rangeMin * barWidth, 0, rangeMin * barWidth, h);
        gc.strokeLine(rangeMax * barWidth, 0, rangeMax * barWidth, h);
    }

    private void drawHistogramVertical() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext gc = canvas.getGraphicsContext2D();

        gc.setFill(Color.BLACK);
        gc.fillRect(0, 0, w, h);

        double maxLog = maxLogCount();

        int rangeMin = contrastSettings.getDisplayMin() * 255 / Math.max(1, currentMaxValue);
        int rangeMax = contrastSettings.getDisplayMax() * 255 / Math.max(1, currentMaxValue);
        double barHeight = h / 256.0;

        // y for a bin (intensity low at the bottom, high at the top)
        // bin i occupies [yTop(i), yTop(i)+barHeight); yTop(i) = h - (i+1)*barHeight
        // Contrast range shading (a horizontal band on the intensity axis)
        double yRangeTop = h - rangeMax * barHeight;
        double yRangeBot = h - rangeMin * barHeight;
        gc.setFill(Color.rgb(40, 40, 80));
        gc.fillRect(0, yRangeTop, w, yRangeBot - yRangeTop);

        // Bars (extend rightward from the left edge)
        gc.setFill(Color.LIGHTGRAY);
        for (int i = 0; i < 256; i++) {
            if (currentHistogram[i] > 0) {
                double barLen = (Math.log1p(currentHistogram[i]) / maxLog) * (w - 2);
                double y = h - (i + 1) * barHeight;
                gc.fillRect(0, y, barLen, Math.max(barHeight, 1));
            }
        }

        // Min/max lines (horizontal)
        gc.setStroke(Color.CYAN);
        gc.setLineWidth(1);
        gc.strokeLine(0, yRangeBot, w, yRangeBot);
        gc.strokeLine(0, yRangeTop, w, yRangeTop);
    }

    private double maxLogCount() {
        double maxLog = 0;
        for (int count : currentHistogram) {
            if (count > 0) {
                double logVal = Math.log1p(count);
                if (logVal > maxLog) maxLog = logVal;
            }
        }
        return maxLog == 0 ? 1 : maxLog;
    }
}
