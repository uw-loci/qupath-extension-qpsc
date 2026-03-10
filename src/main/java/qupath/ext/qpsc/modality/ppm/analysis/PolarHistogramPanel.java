package qupath.ext.qpsc.modality.ppm.analysis;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * JavaFX panel that renders a polar histogram (rose diagram) of PPM fiber
 * orientation angles.
 *
 * <p>Displays a semi-circular chart (0-180 degrees for axial fiber data)
 * where each bin is a wedge with radius proportional to pixel count.
 * Includes circular statistics (mean angle, std) and pixel counts.</p>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class PolarHistogramPanel extends VBox {

    private static final double CANVAS_SIZE = 360;
    private static final double MARGIN = 40;

    private final Canvas canvas;
    private final Label statsLabel;
    private final Label titleLabel;

    private int[] histogramCounts;
    private int bins;
    private double circularMean;
    private double circularStd;
    private double resultantLength;
    private int validPixels;
    private String annotationName;

    public PolarHistogramPanel() {
        setSpacing(8);
        setPadding(new Insets(10));
        setAlignment(Pos.TOP_CENTER);

        titleLabel = new Label("PPM Polarity Plot");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 14));

        canvas = new Canvas(CANVAS_SIZE, CANVAS_SIZE / 2 + MARGIN + 20);

        statsLabel = new Label();
        statsLabel.setFont(Font.font("Monospaced", 12));
        statsLabel.setWrapText(true);

        getChildren().addAll(titleLabel, canvas, statsLabel);
        VBox.setVgrow(canvas, Priority.NEVER);
    }

    /**
     * Updates the display with new analysis results.
     *
     * @param counts histogram bin counts (length = number of bins)
     * @param circularMean circular mean angle in degrees (0-180)
     * @param circularStd circular standard deviation in degrees
     * @param resultantLength mean resultant length (0-1, higher = more aligned)
     * @param validPixels number of valid pixels analyzed
     * @param annotationName name of the annotation (for display)
     */
    public void update(
            int[] counts,
            double circularMean,
            double circularStd,
            double resultantLength,
            int validPixels,
            String annotationName) {
        this.histogramCounts = counts;
        this.bins = counts.length;
        this.circularMean = circularMean;
        this.circularStd = circularStd;
        this.resultantLength = resultantLength;
        this.validPixels = validPixels;
        this.annotationName = annotationName;

        render();
        updateStats();
    }

    private void render() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();

        // Clear
        gc.clearRect(0, 0, w, h);

        if (histogramCounts == null || bins == 0) return;

        // Layout: semi-circle centered horizontally, sitting on a baseline
        double centerX = w / 2;
        double centerY = h - MARGIN;
        double radius = Math.min(w / 2 - MARGIN, h - MARGIN - 20);

        // Find max count for normalization
        int maxCount = 1;
        for (int count : histogramCounts) {
            maxCount = Math.max(maxCount, count);
        }

        double binWidthDeg = 180.0 / bins;

        // Draw filled wedges
        for (int i = 0; i < bins; i++) {
            double startAngleDeg = i * binWidthDeg;
            double wedgeRadius = radius * histogramCounts[i] / (double) maxCount;
            if (wedgeRadius < 1) continue;

            // Convert fiber angle to canvas angle:
            // Fiber 0 deg = right, 180 deg = left (semi-circle above baseline)
            // JavaFX arc: 0 = right (3 o'clock), positive = counter-clockwise
            double canvasStartDeg = -startAngleDeg;
            double canvasExtentDeg = -binWidthDeg;

            // Color by angle (HSV rainbow mapped to 0-180)
            double hue = (startAngleDeg + binWidthDeg / 2) / 180.0 * 360.0;
            Color fillColor = Color.hsb(hue, 0.7, 0.9, 0.7);
            Color strokeColor = Color.hsb(hue, 0.8, 0.7);

            gc.setFill(fillColor);
            gc.setStroke(strokeColor);
            gc.setLineWidth(1);

            // Draw wedge as an arc
            gc.fillArc(
                    centerX - wedgeRadius,
                    centerY - wedgeRadius,
                    wedgeRadius * 2,
                    wedgeRadius * 2,
                    canvasStartDeg,
                    canvasExtentDeg,
                    javafx.scene.shape.ArcType.ROUND);
            gc.strokeArc(
                    centerX - wedgeRadius,
                    centerY - wedgeRadius,
                    wedgeRadius * 2,
                    wedgeRadius * 2,
                    canvasStartDeg,
                    canvasExtentDeg,
                    javafx.scene.shape.ArcType.ROUND);
        }

        // Draw baseline
        gc.setStroke(Color.GRAY);
        gc.setLineWidth(1.5);
        gc.strokeLine(centerX - radius - 5, centerY, centerX + radius + 5, centerY);

        // Draw semi-circle outline
        gc.setStroke(Color.DARKGRAY);
        gc.setLineWidth(0.5);
        gc.strokeArc(
                centerX - radius, centerY - radius, radius * 2, radius * 2, 0, 180, javafx.scene.shape.ArcType.OPEN);

        // Draw angle labels
        gc.setFill(Color.BLACK);
        gc.setFont(Font.font("System", 10));
        gc.fillText("0", centerX + radius + 4, centerY + 4);
        gc.fillText("90", centerX - 4, centerY - radius - 4);
        gc.fillText("180", centerX - radius - 24, centerY + 4);

        // Draw tick marks at 30-degree intervals
        gc.setStroke(Color.LIGHTGRAY);
        gc.setLineWidth(0.5);
        for (int deg = 30; deg < 180; deg += 30) {
            double rad = Math.toRadians(deg);
            double x1 = centerX + (radius - 5) * Math.cos(rad);
            double y1 = centerY - (radius - 5) * Math.sin(rad);
            double x2 = centerX + (radius + 5) * Math.cos(rad);
            double y2 = centerY - (radius + 5) * Math.sin(rad);
            gc.strokeLine(x1, y1, x2, y2);
        }

        // Draw mean angle line
        if (!Double.isNaN(circularMean)) {
            double meanRad = Math.toRadians(circularMean);
            gc.setStroke(Color.RED);
            gc.setLineWidth(2);
            gc.strokeLine(
                    centerX,
                    centerY,
                    centerX + radius * 0.9 * Math.cos(meanRad),
                    centerY - radius * 0.9 * Math.sin(meanRad));

            // Mean angle label
            gc.setFill(Color.RED);
            gc.setFont(Font.font("System", FontWeight.BOLD, 10));
            double labelX = centerX + (radius * 0.95) * Math.cos(meanRad);
            double labelY = centerY - (radius * 0.95) * Math.sin(meanRad);
            gc.fillText(String.format("%.1f", circularMean), labelX, labelY);
        }
    }

    private void updateStats() {
        StringBuilder sb = new StringBuilder();

        if (annotationName != null && !annotationName.isEmpty()) {
            sb.append("Annotation: ").append(annotationName).append("\n");
        }

        sb.append(String.format("Valid pixels:     %,d%n", validPixels));

        if (!Double.isNaN(circularMean)) {
            sb.append(String.format("Circular mean:    %.1f deg%n", circularMean));
            sb.append(String.format("Circular std:     %.1f deg%n", circularStd));
            sb.append(String.format("Alignment (R):    %.3f", resultantLength));
            if (resultantLength > 0.8) {
                sb.append("  (highly aligned)");
            } else if (resultantLength > 0.5) {
                sb.append("  (moderately aligned)");
            } else if (resultantLength > 0.2) {
                sb.append("  (weakly aligned)");
            } else {
                sb.append("  (nearly random)");
            }
            sb.append("\n");
        } else {
            sb.append("No valid angle data\n");
        }

        // Show bin counts summary
        if (histogramCounts != null) {
            int maxBin = 0;
            int maxCount = 0;
            for (int i = 0; i < histogramCounts.length; i++) {
                if (histogramCounts[i] > maxCount) {
                    maxCount = histogramCounts[i];
                    maxBin = i;
                }
            }
            double binWidth = 180.0 / bins;
            double dominantLow = maxBin * binWidth;
            double dominantHigh = (maxBin + 1) * binWidth;
            sb.append(String.format("Dominant bin:     %.0f-%.0f deg (%,d px)%n", dominantLow, dominantHigh, maxCount));
        }

        statsLabel.setText(sb.toString());
        titleLabel.setText(annotationName != null ? "PPM Polarity Plot - " + annotationName : "PPM Polarity Plot");
    }
}
