package qupath.ext.qpsc.ui.focus;

import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.DoubleConsumer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;
import qupath.ext.qpsc.acquisition.StagePositionManager;
import qupath.ext.qpsc.service.autofocus.AutofocusEvent;
import qupath.ext.qpsc.ui.ThemeColors;

/**
 * The microscope's current autofocus search, drawn as it runs: what it is for, its progress, and
 * its focus score against Z, with the best score so far and, once it ends, where it put the stage.
 *
 * <p>Feed it {@link AutofocusEvent}s on the FX thread with {@link #accept}; it keeps showing the
 * last search's result until the next one starts. Drawn with shapes rather than a canvas so the
 * colors follow the QuPath theme.
 *
 * <p>Optionally shows where the stage is while a search runs ({@link #withLiveZ}). The server
 * scores a streaming sweep's frames only once the sweep has finished (each frame's Z comes from a
 * position trace that is complete only then), so the curve arrives a sweep at a time; on a slide
 * change the approach sweep alone takes about 40 s. Without a moving Z the window looks stuck.
 */
public class AutofocusCurvePane extends VBox {

    private static final double PLOT_WIDTH = 320;
    private static final double PLOT_HEIGHT = 80;

    private final Label statusLabel = new Label("");
    private final Pane plot = new Pane();
    private final Label zLowLabel = new Label("");
    private final Label zHighLabel = new Label("");
    private final List<double[]> samples = new ArrayList<>(); // {z, score}
    private final Label liveZLabel = new Label("");

    /** Stage Z updates while a search runs; null when this pane does not show them. */
    private final ZFeed liveZ;
    /** Stops the live Z updates; non-null only while a search is running. */
    private Runnable stopLiveZ;

    private AutofocusEvent start;
    private Double resultZ;
    private boolean resultOk;

    /**
     * Stage Z, pushed to a listener from any thread.
     *
     * <p>Returns the action that stops the updates.
     */
    @FunctionalInterface
    public interface ZFeed {
        Runnable subscribe(DoubleConsumer onZ);
    }

    /**
     * The Z the Live Viewer also reads: {@link StagePositionManager}, which polls only while
     * someone listens and shares one poll among all of them. Sends the last known Z at once.
     */
    public static final ZFeed STAGE_Z = onZ -> {
        StagePositionManager manager = StagePositionManager.getInstance();
        PropertyChangeListener listener = evt -> {
            if (StagePositionManager.PROP_POS_Z.equals(evt.getPropertyName())
                    && evt.getNewValue() instanceof Double z
                    && Double.isFinite(z)) {
                onZ.accept(z);
            }
        };
        manager.addPropertyChangeListener(listener);
        double now = manager.getZ();
        if (Double.isFinite(now)) {
            onZ.accept(now);
        }
        return () -> manager.removePropertyChangeListener(listener);
    };

    /** A pane that shows the search's events only. */
    public AutofocusCurvePane() {
        this(null);
    }

    /**
     * A pane that also shows the stage Z while a search runs, read from {@code liveZ} only between
     * the search's start and end. Call {@link #dispose()} when the pane goes away.
     */
    public static AutofocusCurvePane withLiveZ(ZFeed liveZ) {
        return new AutofocusCurvePane(liveZ);
    }

    private AutofocusCurvePane(ZFeed liveZ) {
        super(4);
        this.liveZ = liveZ;
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
        liveZLabel.setWrapText(true);
        liveZLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: " + ThemeColors.INFO + ";");
        liveZLabel.setVisible(false);
        liveZLabel.setManaged(false);
        setPadding(new Insets(2, 0, 2, 0));
        getChildren().addAll(header, statusLabel, liveZLabel, plot, axis);
    }

    /** Stops the live Z updates, if any. Safe to call more than once (FX thread). */
    public void dispose() {
        endLiveZ();
    }

    /** The scanning line for a stage at {@code z}, or before its position is known (NaN). */
    static String scanningText(double z, boolean haveCurve) {
        String where = Double.isFinite(z) ? String.format("stage at Z %.2f um", z) : "reading the stage position";
        return "Scanning... " + where + ". "
                + (haveCurve
                        ? "The curve grows when each sweep ends."
                        : "The focus curve appears when the sweep ends.");
    }

    private void beginLiveZ() {
        if (liveZ == null) {
            return;
        }
        liveZLabel.setText(scanningText(Double.NaN, false));
        liveZLabel.setVisible(true);
        liveZLabel.setManaged(true);
        if (stopLiveZ == null) {
            stopLiveZ = liveZ.subscribe(z -> {
                if (Platform.isFxApplicationThread()) {
                    showZ(z);
                } else {
                    Platform.runLater(() -> showZ(z));
                }
            });
        }
    }

    private void showZ(double z) {
        if (stopLiveZ != null) {
            liveZLabel.setText(scanningText(z, !samples.isEmpty()));
        }
    }

    private void endLiveZ() {
        Runnable stop = stopLiveZ;
        stopLiveZ = null;
        if (stop != null) {
            stop.run();
        }
        liveZLabel.setVisible(false);
        liveZLabel.setManaged(false);
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
            beginLiveZ();
        } else if (e.isSample()) {
            if (e.z == null || e.score == null) return;
            samples.add(new double[] {e.z, e.score});
            String what = start != null ? start.describe() : "Autofocus";
            String steps = start != null && start.nSteps != null && samples.size() <= start.nSteps
                    ? samples.size() + " of " + start.nSteps
                    : String.valueOf(samples.size());
            statusLabel.setText(String.format("%s: step %s, Z %.2f", what, steps, e.z));
        } else if (e.isEnd()) {
            endLiveZ();
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

        // One line per run of closely spaced samples. A sweep samples densely; the fallback
        // search's single snaps can be tens of um apart, and a line between them reads as a
        // measured curve where nothing was measured.
        for (List<double[]> run : contiguousRuns(byZ)) {
            Polyline curve = new Polyline();
            curve.setStyle("-fx-stroke: " + ThemeColors.INFO + "; -fx-stroke-width: 1.5;");
            for (double[] s : run) {
                curve.getPoints().addAll(px.applyAsDouble(s[0]), py.applyAsDouble(s[1]));
            }
            plot.getChildren().add(curve);
        }
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

    /** A gap wider than this many typical sample spacings ends a line. */
    static final double GAP_FACTOR = 5.0;

    /**
     * Splits samples sorted by Z into runs that a line may join: a new run starts where the
     * gap to the previous sample is more than {@link #GAP_FACTOR} times the median gap.
     */
    static List<List<double[]>> contiguousRuns(List<double[]> byZ) {
        List<List<double[]>> runs = new ArrayList<>();
        if (byZ.isEmpty()) {
            return runs;
        }
        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < byZ.size(); i++) {
            gaps.add(byZ.get(i)[0] - byZ.get(i - 1)[0]);
        }
        double limit = Double.POSITIVE_INFINITY;
        if (gaps.size() >= 2) {
            List<Double> sorted = new ArrayList<>(gaps);
            sorted.sort(Comparator.naturalOrder());
            double median = sorted.get(sorted.size() / 2);
            if (median > 0) {
                limit = GAP_FACTOR * median;
            }
        }
        List<double[]> run = new ArrayList<>();
        run.add(byZ.get(0));
        for (int i = 1; i < byZ.size(); i++) {
            if (gaps.get(i - 1) > limit) {
                runs.add(run);
                run = new ArrayList<>();
            }
            run.add(byZ.get(i));
        }
        runs.add(run);
        return runs;
    }
}
