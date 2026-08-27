package qupath.ext.qpsc.ui;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * Vertical focus-metric trace for the approach traverse: Z runs down the page from the
 * retracted start to the far end, and the metric extends sideways.
 *
 * <h2>Why vertical, and why this orientation</h2>
 * The plot is a picture of the stage's journey. Top is where the traverse starts (clear of the
 * sample) and it fills downward as the objective closes on the sample, so the drawing grows the
 * same way the motion does. Reading it against the Live Viewer's Z bars needs no mental
 * transposition.
 *
 * <h2>Two traces, and why they are labelled separately</h2>
 * During the traverse the only metric available client-side is the one the Live Viewer computes
 * per frame -- currently Brenner gradient -- because the server's own per-sample metrics do not
 * come back until the scan returns. That is genuinely useful as live feedback but it is NOT
 * necessarily the metric being validated: PPM at 20x uses {@code p98_p2} server-side. Showing
 * one and calling it the other would misrepresent the very measurement this tool exists to make,
 * so the live trace is named for what it is, and is replaced by the server's profile (named for
 * ITS metric) when the scan returns.
 *
 * <p>Pure rendering: no hardware, no socket, no timers. The caller feeds it samples.
 */
public final class FocusApproachPlot extends Pane {

    private final Canvas canvas = new Canvas(260, 420);

    /** Z at the top of the plot -- where the traverse starts (retracted). */
    private final double zStart;

    /** Z at the bottom -- the far end of the traverse, nearest the sample. */
    private final double zEnd;

    /** The operator's focus, drawn as a labelled reference line. May be NaN. */
    private final double sampleFocusZ;

    private final List<double[]> samples = new ArrayList<>();
    private String metricLabel = "waiting for frames";
    private boolean isServerProfile = false;
    private Double currentZ = null;

    /**
     * @param zStart       Z at the top of the plot (traverse start, retracted)
     * @param zEnd         Z at the bottom (traverse end, nearest the sample)
     * @param sampleFocusZ the operator's focus, drawn as a reference line; NaN to omit
     */
    public FocusApproachPlot(double zStart, double zEnd, double sampleFocusZ) {
        this.zStart = zStart;
        this.zEnd = zEnd;
        this.sampleFocusZ = sampleFocusZ;
        getChildren().add(canvas);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        widthProperty().addListener((o, a, b) -> redraw());
        heightProperty().addListener((o, a, b) -> redraw());
        setPrefSize(260, 420);
        redraw();
    }

    /**
     * Replaces the trace with a new set of samples.
     *
     * @param zAndMetric   samples as {@code {z, metric}} pairs
     * @param metricName   the metric these came from, shown to the operator
     * @param fromServer   true when this is the server's own profile rather than the live
     *                     client-side approximation; changes the label and the colour
     */
    public void setSamples(List<double[]> zAndMetric, String metricName, boolean fromServer) {
        samples.clear();
        if (zAndMetric != null) {
            samples.addAll(zAndMetric);
        }
        this.metricLabel = metricName;
        this.isServerProfile = fromServer;
        redraw();
    }

    /** Marks where the stage is now, so the operator can see progress against the plot. */
    public void setCurrentZ(Double z) {
        this.currentZ = z;
        redraw();
    }

    private void redraw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        g.setFill(Color.web("#1b1b1b"));
        g.fillRect(0, 0, w, h);

        // Left gutter carries the Z labels; the trace uses the rest.
        double gutter = 62;
        double top = 22;
        double bottom = h - 22;
        double plotW = Math.max(10, w - gutter - 12);

        g.setFont(Font.font(10));
        g.setTextAlign(TextAlignment.RIGHT);

        // Endpoint labels: what the traverse actually spans.
        g.setStroke(Color.web("#555"));
        g.setFill(Color.web("#bbb"));
        g.strokeLine(gutter, top, w - 12, top);
        g.strokeLine(gutter, bottom, w - 12, bottom);
        g.fillText(String.format("%.1f", zStart), gutter - 6, top + 4);
        g.fillText(String.format("%.1f", zEnd), gutter - 6, bottom + 4);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFill(Color.web("#888"));
        g.fillText("start (retracted)", gutter + 4, top - 6);
        g.fillText("end (toward sample)", gutter + 4, bottom + 14);

        // The operator's focus: the line the measured peak should land on.
        if (!Double.isNaN(sampleFocusZ)) {
            double y = yFor(sampleFocusZ, top, bottom);
            if (y >= top && y <= bottom) {
                g.setStroke(Color.web("#2E7D32"));
                g.setLineDashes(5, 4);
                g.strokeLine(gutter, y, w - 12, y);
                g.setLineDashes(null);
                g.setFill(Color.web("#66BB6A"));
                g.fillText("Sample Focus", gutter + 4, y - 4);
                g.setTextAlign(TextAlignment.RIGHT);
                g.fillText(String.format("%.1f", sampleFocusZ), gutter - 6, y + 4);
                g.setTextAlign(TextAlignment.LEFT);
            }
        }

        // Metric name, so nobody has to guess which curve they are looking at.
        g.setFill(isServerProfile ? Color.web("#4FC3F7") : Color.web("#FFB74D"));
        g.fillText((isServerProfile ? "server: " : "live: ") + metricLabel, gutter + 4, h - 6);

        if (samples.size() >= 2) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (double[] s : samples) {
                min = Math.min(min, s[1]);
                max = Math.max(max, s[1]);
            }
            double span = (max - min);
            if (span <= 0) {
                span = 1;
            }
            g.setStroke(isServerProfile ? Color.web("#4FC3F7") : Color.web("#FFB74D"));
            g.setLineWidth(1.5);
            double prevX = Double.NaN;
            double prevY = Double.NaN;
            for (double[] s : samples) {
                double y = yFor(s[0], top, bottom);
                double x = gutter + ((s[1] - min) / span) * plotW;
                if (!Double.isNaN(prevX)) {
                    g.strokeLine(prevX, prevY, x, y);
                }
                prevX = x;
                prevY = y;
            }
        }

        // Where the stage is right now.
        if (currentZ != null) {
            double y = yFor(currentZ, top, bottom);
            if (y >= top && y <= bottom) {
                g.setStroke(Color.web("#E0E0E0"));
                g.setLineWidth(1);
                g.strokeLine(gutter - 4, y, w - 12, y);
                g.setFill(Color.web("#E0E0E0"));
                g.fillOval(gutter - 8, y - 3, 6, 6);
            }
        }
    }

    /** Maps a Z to a vertical pixel, with zStart at the top regardless of which sign that is. */
    private double yFor(double z, double top, double bottom) {
        double denom = (zEnd - zStart);
        if (denom == 0) {
            return top;
        }
        double frac = (z - zStart) / denom;
        return top + frac * (bottom - top);
    }
}
