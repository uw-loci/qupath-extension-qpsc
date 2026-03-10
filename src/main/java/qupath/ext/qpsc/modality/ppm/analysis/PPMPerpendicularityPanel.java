package qupath.ext.qpsc.modality.ppm.analysis;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * JavaFX panel for displaying surface perpendicularity analysis results.
 *
 * <p>Shows a deviation histogram (10-degree bins), 3-way split bar (parallel/
 * oblique/perpendicular), PS-TACS results, and summary statistics for each
 * analyzed annotation.</p>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class PPMPerpendicularityPanel extends VBox {

    private static final double HISTOGRAM_WIDTH = 400;
    private static final double HISTOGRAM_HEIGHT = 180;
    private static final double BAR_HEIGHT = 30;

    private final VBox contentBox;
    private final Label statusLabel;
    private final Label titleLabel;

    public PPMPerpendicularityPanel() {
        setSpacing(8);
        setPadding(new Insets(10));

        titleLabel = new Label("Surface Perpendicularity Analysis");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 14));

        statusLabel = new Label("Waiting for analysis...");
        statusLabel.setWrapText(true);
        statusLabel.setFont(Font.font("System", 11));

        contentBox = new VBox(12);
        contentBox.setPadding(new Insets(5));

        ScrollPane scrollPane = new ScrollPane(contentBox);
        scrollPane.setFitToWidth(true);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        // Paper reference
        Label refLabel = new Label("PS-TACS: Qian et al., Am J Pathol 2025; DOI: 10.1016/j.ajpath.2025.04.017");
        refLabel.setStyle("-fx-text-fill: #666666; -fx-font-size: 9px;");
        refLabel.setWrapText(true);

        getChildren().addAll(titleLabel, statusLabel, new Separator(), scrollPane, new Separator(), refLabel);
    }

    /**
     * Clears all results from the panel.
     */
    public void clear() {
        contentBox.getChildren().clear();
        statusLabel.setText("Waiting for analysis...");
    }

    /**
     * Sets the status message.
     */
    public void setStatus(String status) {
        statusLabel.setText(status);
    }

    /**
     * Adds analysis results for one annotation.
     */
    public void addResult(JsonObject result, String annotationName, int index, int totalAnnotations) {

        VBox annotationBox = new VBox(6);
        annotationBox.setPadding(new Insets(8));
        annotationBox.setStyle("-fx-border-color: #cccccc; -fx-border-radius: 4;");

        // Header
        Label header = new Label(String.format("Annotation %d/%d: %s", index, totalAnnotations, annotationName));
        header.setFont(Font.font("System", FontWeight.BOLD, 12));
        annotationBox.getChildren().add(header);

        // Metadata
        double pixelSizeUm = getDouble(result, "pixel_size_um", 0);
        int dilationPx = getInt(result, "dilation_px", 0);
        double contourLengthUm = getDouble(result, "contour_length_um", 0);
        int nContours = getInt(result, "n_contours", 0);

        Label metaLabel = new Label(String.format(
                "Contours: %d | Contour length: %.1f um | Dilation: %d px (%.1f um)",
                nContours, contourLengthUm, dilationPx, dilationPx * pixelSizeUm));
        metaLabel.setFont(Font.font("System", 10));
        metaLabel.setStyle("-fx-text-fill: #666666;");
        annotationBox.getChildren().add(metaLabel);

        // Simple results
        JsonObject simple =
                result.has("simple") && !result.get("simple").isJsonNull() ? result.getAsJsonObject("simple") : null;

        if (simple != null) {
            annotationBox.getChildren().add(new Separator());
            annotationBox.getChildren().add(createSimpleResults(simple));
        }

        // PS-TACS results
        JsonObject pstacs =
                result.has("pstacs") && !result.get("pstacs").isJsonNull() ? result.getAsJsonObject("pstacs") : null;

        if (pstacs != null) {
            annotationBox.getChildren().add(new Separator());
            annotationBox.getChildren().add(createTACSResults(pstacs));
        }

        contentBox.getChildren().add(annotationBox);
    }

    private VBox createSimpleResults(JsonObject simple) {
        VBox box = new VBox(4);

        Label title = new Label("Simple Perpendicularity");
        title.setFont(Font.font("System", FontWeight.BOLD, 11));
        box.getChildren().add(title);

        // Stats
        double meanDev = getDouble(simple, "mean_deviation_deg", Double.NaN);
        double stdDev = getDouble(simple, "std_deviation_deg", Double.NaN);
        int nPixels = getInt(simple, "n_valid_pixels", 0);

        Label statsLabel = new Label(String.format(
                "Mean deviation: %.1f deg | Std: %.1f deg | Valid pixels: %,d", meanDev, stdDev, nPixels));
        statsLabel.setFont(Font.font("Monospaced", 11));
        box.getChildren().add(statsLabel);

        // 3-way split bar
        JsonObject split3 = simple.has("histogram_3way") ? simple.getAsJsonObject("histogram_3way") : null;
        if (split3 != null) {
            box.getChildren().add(create3WayBar(split3));
        }

        // 10-degree histogram
        JsonObject hist10 = simple.has("histogram_10deg") ? simple.getAsJsonObject("histogram_10deg") : null;
        if (hist10 != null) {
            box.getChildren().add(createDeviationHistogram(hist10));
        }

        return box;
    }

    private VBox createTACSResults(JsonObject pstacs) {
        VBox box = new VBox(4);

        Label title = new Label("PS-TACS Scoring");
        title.setFont(Font.font("System", FontWeight.BOLD, 11));
        box.getChildren().add(title);

        double pctTacs2 = getDouble(pstacs, "pct_tacs2", 0);
        double pctTacs3 = getDouble(pstacs, "pct_tacs3", 0);
        int nClusters = getInt(pstacs, "n_tacs3_clusters", 0);
        int contourLen = getInt(pstacs, "contour_length_px", 0);
        double threshold = getDouble(pstacs, "tacs_threshold_deg", 30);

        Label statsLabel = new Label(String.format(
                "TACS-2 (parallel): %.1f%% | TACS-3 (perpendicular): %.1f%%\n"
                        + "TACS-3 clusters: %d | Threshold: %.0f deg | Contour: %d px",
                pctTacs2, pctTacs3, nClusters, threshold, contourLen));
        statsLabel.setFont(Font.font("Monospaced", 11));
        box.getChildren().add(statsLabel);

        // TACS bar
        Canvas tacsBar = new Canvas(HISTOGRAM_WIDTH, BAR_HEIGHT + 20);
        drawTACSBar(tacsBar.getGraphicsContext2D(), pctTacs2, pctTacs3);
        box.getChildren().add(tacsBar);

        return box;
    }

    private Canvas create3WayBar(JsonObject split3) {
        double pctParallel = getDouble(split3, "pct_parallel", 0);
        double pctOblique = getDouble(split3, "pct_oblique", 0);
        double pctPerp = getDouble(split3, "pct_perpendicular", 0);

        Canvas canvas = new Canvas(HISTOGRAM_WIDTH, BAR_HEIGHT + 30);
        GraphicsContext gc = canvas.getGraphicsContext2D();

        double barY = 15;
        double totalWidth = HISTOGRAM_WIDTH - 20;
        double x = 10;

        // Label
        gc.setFill(Color.BLACK);
        gc.setFont(Font.font("System", 10));
        gc.fillText("Deviation from boundary (0=parallel, 90=perpendicular)", x, 12);

        // Parallel (blue)
        double w1 = totalWidth * pctParallel / 100.0;
        gc.setFill(Color.rgb(66, 133, 244, 0.8));
        gc.fillRect(x, barY, w1, BAR_HEIGHT);

        // Oblique (yellow)
        double w2 = totalWidth * pctOblique / 100.0;
        gc.setFill(Color.rgb(251, 188, 5, 0.8));
        gc.fillRect(x + w1, barY, w2, BAR_HEIGHT);

        // Perpendicular (red)
        double w3 = totalWidth * pctPerp / 100.0;
        gc.setFill(Color.rgb(234, 67, 53, 0.8));
        gc.fillRect(x + w1 + w2, barY, w3, BAR_HEIGHT);

        // Labels inside bars
        gc.setFill(Color.WHITE);
        gc.setFont(Font.font("System", FontWeight.BOLD, 10));
        if (w1 > 50) gc.fillText(String.format("%.0f%% ||", pctParallel), x + 4, barY + 18);
        if (w2 > 50) gc.fillText(String.format("%.0f%% /", pctOblique), x + w1 + 4, barY + 18);
        if (w3 > 50) gc.fillText(String.format("%.0f%% +", pctPerp), x + w1 + w2 + 4, barY + 18);

        // Legend below
        gc.setFont(Font.font("System", 9));
        double legendY = barY + BAR_HEIGHT + 12;
        gc.setFill(Color.rgb(66, 133, 244));
        gc.fillRect(x, legendY - 8, 8, 8);
        gc.setFill(Color.BLACK);
        gc.fillText("0-30 (parallel)", x + 12, legendY);

        gc.setFill(Color.rgb(251, 188, 5));
        gc.fillRect(x + 110, legendY - 8, 8, 8);
        gc.setFill(Color.BLACK);
        gc.fillText("30-60 (oblique)", x + 124, legendY);

        gc.setFill(Color.rgb(234, 67, 53));
        gc.fillRect(x + 230, legendY - 8, 8, 8);
        gc.setFill(Color.BLACK);
        gc.fillText("60-90 (perpendicular)", x + 244, legendY);

        return canvas;
    }

    private Canvas createDeviationHistogram(JsonObject hist10) {
        JsonArray countsArr = hist10.has("counts") ? hist10.getAsJsonArray("counts") : null;
        JsonArray centersArr = hist10.has("bin_centers") ? hist10.getAsJsonArray("bin_centers") : null;

        if (countsArr == null || countsArr.size() == 0) {
            return new Canvas(1, 1);
        }

        int nBins = countsArr.size();
        int[] counts = new int[nBins];
        double[] centers = new double[nBins];
        int maxCount = 1;

        for (int i = 0; i < nBins; i++) {
            counts[i] = countsArr.get(i).getAsInt();
            if (centersArr != null && i < centersArr.size()) {
                centers[i] = centersArr.get(i).getAsDouble();
            } else {
                centers[i] = i * 10.0 + 5.0;
            }
            maxCount = Math.max(maxCount, counts[i]);
        }

        double chartWidth = HISTOGRAM_WIDTH - 40;
        double chartHeight = HISTOGRAM_HEIGHT - 40;
        Canvas canvas = new Canvas(HISTOGRAM_WIDTH, HISTOGRAM_HEIGHT);
        GraphicsContext gc = canvas.getGraphicsContext2D();

        double barWidth = chartWidth / nBins;
        double originX = 30;
        double originY = chartHeight + 10;

        // Bars
        for (int i = 0; i < nBins; i++) {
            double barHeight = chartHeight * counts[i] / (double) maxCount;
            double barX = originX + i * barWidth;

            // Color gradient from blue (parallel) to red (perpendicular)
            double t = (double) i / (nBins - 1);
            Color barColor = Color.color(t, 0.3, 1.0 - t, 0.8);

            gc.setFill(barColor);
            gc.fillRect(barX, originY - barHeight, barWidth - 1, barHeight);

            gc.setStroke(Color.DARKGRAY);
            gc.setLineWidth(0.5);
            gc.strokeRect(barX, originY - barHeight, barWidth - 1, barHeight);
        }

        // X axis labels
        gc.setFill(Color.BLACK);
        gc.setFont(Font.font("System", 9));
        for (int i = 0; i < nBins; i++) {
            double labelX = originX + i * barWidth + barWidth / 2 - 4;
            gc.fillText(String.format("%.0f", centers[i]), labelX, originY + 14);
        }

        // Axis label
        gc.setFont(Font.font("System", 10));
        gc.fillText("Deviation from boundary (deg)", originX + chartWidth / 4, originY + 28);

        // Y axis
        gc.setStroke(Color.GRAY);
        gc.setLineWidth(0.5);
        gc.strokeLine(originX, originY, originX, 10);
        gc.strokeLine(originX, originY, originX + chartWidth, originY);

        return canvas;
    }

    private void drawTACSBar(GraphicsContext gc, double pctTacs2, double pctTacs3) {
        double barY = 5;
        double totalWidth = HISTOGRAM_WIDTH - 20;
        double x = 10;

        // TACS-2 (blue = parallel to boundary)
        double w2 = totalWidth * pctTacs2 / 100.0;
        gc.setFill(Color.rgb(66, 133, 244, 0.8));
        gc.fillRect(x, barY, w2, BAR_HEIGHT);

        // TACS-3 (red = perpendicular to boundary)
        double w3 = totalWidth * pctTacs3 / 100.0;
        gc.setFill(Color.rgb(234, 67, 53, 0.8));
        gc.fillRect(x + w2, barY, w3, BAR_HEIGHT);

        gc.setFill(Color.WHITE);
        gc.setFont(Font.font("System", FontWeight.BOLD, 11));
        if (w2 > 60) gc.fillText(String.format("TACS-2: %.1f%%", pctTacs2), x + 4, barY + 20);
        if (w3 > 60) gc.fillText(String.format("TACS-3: %.1f%%", pctTacs3), x + w2 + 4, barY + 20);
    }

    // Utility methods for safe JSON extraction

    private static double getDouble(JsonObject obj, String key, double defaultVal) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultVal;
        }
        try {
            return obj.get(key).getAsDouble();
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private static int getInt(JsonObject obj, String key, int defaultVal) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultVal;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (Exception e) {
            return defaultVal;
        }
    }
}
