package qupath.ext.qpsc.ui.liveviewer;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;

/**
 * Collapsible panel for Live Viewer showing per-channel noise statistics.
 *
 * <p>Displays a 3x4 grid: Channel | Mean | StdDev | SNR for R/G/B.
 * Single-frame spatial noise estimates are updated periodically from live frames.
 * A "Measure" button triggers multi-frame temporal noise analysis via the GETNOISE command.
 */
public class NoiseStatsPanel extends TitledPane {

    private static final Logger logger = LoggerFactory.getLogger(NoiseStatsPanel.class);

    // Per-channel labels
    private final Label redMean = createValueLabel();
    private final Label greenMean = createValueLabel();
    private final Label blueMean = createValueLabel();
    private final Label redStd = createValueLabel();
    private final Label greenStd = createValueLabel();
    private final Label blueStd = createValueLabel();
    private final Label redSnr = createValueLabel();
    private final Label greenSnr = createValueLabel();
    private final Label blueSnr = createValueLabel();

    private final Button measureButton;
    private final Label measureStatus;

    // Throttling for single-frame updates
    private long lastUpdateMs = 0;
    private static final long UPDATE_THROTTLE_MS = 500;  // ~2Hz

    public NoiseStatsPanel() {
        setText("Noise Stats");
        setExpanded(false);
        setAnimated(false);

        VBox content = new VBox(5);
        content.setPadding(new Insets(5));

        // Grid: Channel | Mean | StdDev | SNR
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(3);

        // Header row
        grid.add(createHeaderLabel("Channel"), 0, 0);
        grid.add(createHeaderLabel("Mean"), 1, 0);
        grid.add(createHeaderLabel("StdDev"), 2, 0);
        grid.add(createHeaderLabel("SNR"), 3, 0);

        // Red row
        Label redLabel = new Label("Red");
        redLabel.setStyle("-fx-text-fill: #cc0000; -fx-font-weight: bold; -fx-font-size: 10px;");
        grid.add(redLabel, 0, 1);
        grid.add(redMean, 1, 1);
        grid.add(redStd, 2, 1);
        grid.add(redSnr, 3, 1);

        // Green row
        Label greenLabel = new Label("Green");
        greenLabel.setStyle("-fx-text-fill: #009900; -fx-font-weight: bold; -fx-font-size: 10px;");
        grid.add(greenLabel, 0, 2);
        grid.add(greenMean, 1, 2);
        grid.add(greenStd, 2, 2);
        grid.add(greenSnr, 3, 2);

        // Blue row
        Label blueLabel = new Label("Blue");
        blueLabel.setStyle("-fx-text-fill: #0000cc; -fx-font-weight: bold; -fx-font-size: 10px;");
        grid.add(blueLabel, 0, 3);
        grid.add(blueMean, 1, 3);
        grid.add(blueStd, 2, 3);
        grid.add(blueSnr, 3, 3);

        // Measure button and status
        measureButton = new Button("Measure (10 frames)");
        measureButton.setStyle("-fx-font-size: 10px;");
        measureButton.setOnAction(e -> runMultiFrameMeasurement());

        measureStatus = new Label("");
        measureStatus.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666;");

        HBox buttonRow = new HBox(10, measureButton, measureStatus);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        content.getChildren().addAll(grid, buttonRow);
        setContent(content);
    }

    /**
     * Updates noise stats from a single frame (spatial estimate).
     * Called from the live viewer polling loop. Internally throttled to ~2Hz.
     *
     * @param frame The current frame data
     */
    public void updateFromFrame(FrameData frame) {
        if (frame == null || !frame.isRGB()) return;

        long now = System.currentTimeMillis();
        if (now - lastUpdateMs < UPDATE_THROTTLE_MS) return;
        lastUpdateMs = now;

        // Compute per-channel spatial mean and std dev
        int w = frame.width();
        int h = frame.height();
        int pixelCount = w * h;
        int bpp = frame.bytesPerPixel();
        int stride = frame.channels() * bpp;

        // Accumulate sums for mean
        long[] sum = new long[3];
        long[] sumSq = new long[3];

        for (int i = 0; i < pixelCount; i++) {
            int baseOffset = i * stride;
            for (int c = 0; c < 3; c++) {
                int val = frame.readPixelValue(baseOffset + c * bpp);
                sum[c] += val;
                sumSq[c] += (long) val * val;
            }
        }

        double[] means = new double[3];
        double[] stds = new double[3];
        double[] snrs = new double[3];
        for (int c = 0; c < 3; c++) {
            means[c] = (double) sum[c] / pixelCount;
            double variance = (double) sumSq[c] / pixelCount - means[c] * means[c];
            stds[c] = Math.sqrt(Math.max(0, variance));
            snrs[c] = stds[c] > 0 ? means[c] / stds[c] : 0;
        }

        Platform.runLater(() -> {
            redMean.setText(String.format("%.1f", means[0]));
            greenMean.setText(String.format("%.1f", means[1]));
            blueMean.setText(String.format("%.1f", means[2]));
            redStd.setText(String.format("%.2f", stds[0]));
            greenStd.setText(String.format("%.2f", stds[1]));
            blueStd.setText(String.format("%.2f", stds[2]));
            redSnr.setText(String.format("%.1f", snrs[0]));
            greenSnr.setText(String.format("%.1f", snrs[1]));
            blueSnr.setText(String.format("%.1f", snrs[2]));
        });
    }

    /**
     * Runs multi-frame noise measurement via GETNOISE command asynchronously.
     */
    private void runMultiFrameMeasurement() {
        measureButton.setDisable(true);
        measureStatus.setText("Measuring...");
        measureStatus.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666;");

        Thread thread = new Thread(() -> {
            try {
                MicroscopeController controller = MicroscopeController.getInstance();
                MicroscopeSocketClient.NoiseResult result = controller.getNoise(10);

                Platform.runLater(() -> {
                    redMean.setText(String.format("%.1f", result.redMean()));
                    greenMean.setText(String.format("%.1f", result.greenMean()));
                    blueMean.setText(String.format("%.1f", result.blueMean()));
                    redStd.setText(String.format("%.2f", result.redStdDev()));
                    greenStd.setText(String.format("%.2f", result.greenStdDev()));
                    blueStd.setText(String.format("%.2f", result.blueStdDev()));
                    redSnr.setText(String.format("%.1f", result.redSNR()));
                    greenSnr.setText(String.format("%.1f", result.greenSNR()));
                    blueSnr.setText(String.format("%.1f", result.blueSNR()));
                    measureStatus.setText("Temporal (10 frames)");
                    measureStatus.setStyle("-fx-font-size: 10px; -fx-text-fill: green;");
                    measureButton.setDisable(false);
                });
            } catch (Exception e) {
                logger.error("Failed to measure noise: {}", e.getMessage());
                Platform.runLater(() -> {
                    measureStatus.setText("Error: " + e.getMessage());
                    measureStatus.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
                    measureButton.setDisable(false);
                });
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private static Label createHeaderLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold; -fx-font-size: 10px; -fx-text-fill: #444444;");
        return label;
    }

    private static Label createValueLabel() {
        Label label = new Label("--");
        label.setStyle("-fx-font-size: 10px; -fx-font-family: monospace;");
        label.setMinWidth(50);
        return label;
    }
}
