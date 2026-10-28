package qupath.ext.qpsc.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;
import qupath.ext.qpsc.service.autofocus.AutofocusEvent;

/**
 * The microscope's current autofocus search, drawn as it runs: what it is for, its progress, and
 * its focus score against Z, with the best score so far and, once it ends, where it put the stage.
 *
 * <p>Feed it {@link AutofocusEvent}s on the FX thread with {@link #accept}; it keeps showing the
 * last search's result until the next one starts. Drawn with shapes rather than a canvas so the
 * colours follow the QuPath theme.
 */
public class AutofocusCurvePane extends VBox {

    private static final double PLOT_WIDTH = 320;
    private static final double PLOT_HEIGHT = 80;

    private final Label statusLabel = new Label("");
    private final Pane plot = new Pane();
    private final Label zLowLabel = new Label("");
    private final Label zHighLabel = new Label("");
    private final List<double[]> samples = new ArrayList<>(); // {z, score}

    private AutofocusEvent start;
    private Double resultZ;
    private boolean resultOk;

    public AutofocusCurvePane() {
        super(4);
        Label header = new Label("Autofocus");
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-font-size: 10px;");
        plot.setPrefSize(PLOT_WIDTH, PLOT_HEIGHT);
        plot.setMinSize(PLOT_WIDTH, PLOT_HEIGHT);
        plot.setMaxSize(PLOT_WIDTH, PLOT_HEIGHT);
        plot.setClip(new Rectangle(PLOT_WIDTH, PLOT_HEIGHT));
        plot.setStyle("-fx-border-color: " + ThemeColors.MUTED + "; -fx-border-width: 1;");
        for (Label l : new Label[] {zLowLabel, zHighLabel}) {
            l.setStyle("-fx-font-size: 9px; -fx-text-fill: " + ThemeColors.MUTED + ";");
        }
        BorderPane axis = new BorderPane();
        axis.setLeft(zLowLabel);
        axis.setRight(zHighLabel);
        axis.setMaxWidth(PLOT_WIDTH);
        setPadding(new Insets(2, 0, 2, 0));
        getChildren().addAll(header, statusLabel, plot, axis);
    }

    /** Takes one event of the feed (FX thread). */
    public void accept(AutofocusEvent e) {
        if (e.isStart()) {
            start = e;
            samples.clear();
            resultZ = null;
            statusLabel.setText(
                    e.describe() + ": searching" + (e.z0 != null ? String.format(" from Z %.2f", e.z0) : ""));
            statusLabel.setStyle("-fx-font-size: 10px;");
        } else if (e.isSample()) {
            if (e.z == null || e.score == null) return;
            samples.add(new double[] {e.z, e.score});
            String what = start != null ? start.describe() : "Autofocus";
            String steps = start != null && start.nSteps != null && samples.size() <= start.nSteps
                    ? samples.size() + " of " + start.nSteps
                    : String.valueOf(samples.size());
            statusLabel.setText(String.format("%s: step %s, Z %.2f", what, steps, e.z));
        } else if (e.isEnd()) {
            resultZ = e.z;
            resultOk = "ok".equals(e.status);
            statusLabel.setText(AutofocusEvent.describeOutcome(start, e));
            statusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: "
                    + (resultOk ? ThemeColors.SUCCESS : ThemeColors.WARNING) + ";");
        }
        redraw();
    }

    private void redraw() {
        plot.getChildren().clear();
        if (samples.isEmpty()) {
            zLowLabel.setText("");
            zHighLabel.setText("");
            return;
        }
        List<double[]> byZ = new ArrayList<>(samples);
        byZ.sort(Comparator.comparingDouble(s -> s[0]));
        double zMin = byZ.get(0)[0];
        double zMax = byZ.get(byZ.size() - 1)[0];
        if (resultZ != null) {
            zMin = Math.min(zMin, resultZ);
            zMax = Math.max(zMax, resultZ);
        }
        double sMin = Double.POSITIVE_INFINITY;
        double sMax = Double.NEGATIVE_INFINITY;
        double[] best = byZ.get(0);
        for (double[] s : byZ) {
            sMin = Math.min(sMin, s[1]);
            sMax = Math.max(sMax, s[1]);
            if (s[1] > best[1]) best = s;
        }
        double zSpan = Math.max(zMax - zMin, 1e-6);
        double sSpan = Math.max(sMax - sMin, 1e-12);
        double pad = 4;
        double w = PLOT_WIDTH - 2 * pad;
        double h = PLOT_HEIGHT - 2 * pad;
        final double z0 = zMin;
        final double s0 = sMin;
        java.util.function.DoubleUnaryOperator px = z -> pad + (z - z0) / zSpan * w;
        java.util.function.DoubleUnaryOperator py = s -> pad + h - (s - s0) / sSpan * h;

        Polyline curve = new Polyline();
        curve.setStyle("-fx-stroke: " + ThemeColors.INFO + "; -fx-stroke-width: 1.5;");
        for (double[] s : byZ) {
            curve.getPoints().addAll(px.applyAsDouble(s[0]), py.applyAsDouble(s[1]));
        }
        plot.getChildren().add(curve);
        for (double[] s : byZ) {
            Circle dot = new Circle(px.applyAsDouble(s[0]), py.applyAsDouble(s[1]), 2);
            dot.setStyle("-fx-fill: " + ThemeColors.INFO + ";");
            plot.getChildren().add(dot);
        }
        Circle bestDot = new Circle(px.applyAsDouble(best[0]), py.applyAsDouble(best[1]), 4);
        bestDot.setStyle("-fx-fill: transparent; -fx-stroke: " + ThemeColors.WARNING + "; -fx-stroke-width: 1.5;");
        plot.getChildren().add(bestDot);
        if (resultZ != null) {
            double x = px.applyAsDouble(resultZ);
            Line mark = new Line(x, 0, x, PLOT_HEIGHT);
            mark.setStyle("-fx-stroke: " + (resultOk ? ThemeColors.SUCCESS : ThemeColors.ERROR)
                    + "; -fx-stroke-width: 1.5; -fx-stroke-dash-array: 4 3;");
            plot.getChildren().add(mark);
        }
        zLowLabel.setText(String.format("Z %.1f", zMin));
        zHighLabel.setText(String.format("%.1f", zMax));
    }
}
