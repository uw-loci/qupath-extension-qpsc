package qupath.ext.qpsc.ui.liveviewer;

import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import javafx.application.Platform;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;

/**
 * Vertical two-bar Z movement widget for the Live Viewer's Navigate tab.
 *
 * <p>The widget consists of:
 * <ul>
 *   <li>A wide <b>coarse</b> bar covering the full stage Z range (or a saved
 *       subset). Numeric tick labels on the left.</li>
 *   <li>A narrow <b>fine</b> bar centered on the current Z with a configurable
 *       half-width. Numeric tick labels on the left, plus a sideways focus-metric
 *       trace immediately to the right of the bar.</li>
 *   <li>Recent autofocus / Mark Z tic marks on the right edge of each bar.
 *       Newest is fully opaque; opacity falls off with age.</li>
 *   <li>A "Mark Z" button which records the current Z as a new tic. Stays
 *       enabled when Live View is off (it does not move the stage).</li>
 * </ul>
 *
 * <p>The bars feed the existing Z gesture-target streaming infrastructure in
 * {@link StageControlPanel} via a callback supplied at construction. Dragging a
 * thumb produces a stream of target updates which the host worker ramps the
 * stage toward via {@code moveStageZNoWait}.
 */
public class ZBarPanel extends HBox {

    private static final Logger logger = LoggerFactory.getLogger(ZBarPanel.class);

    private static final double DEFAULT_FINE_HALF_WIDTH_UM = 20.0;
    private static final double FINE_MIN_HALF_WIDTH_UM = 1.0;
    private static final double FINE_MAX_HALF_WIDTH_UM = 500.0;

    private static final double COARSE_BAR_WIDTH = 28;
    private static final double FINE_BAR_WIDTH = 14;
    private static final double BAR_HEIGHT = 240;

    private final ZBar coarseBar;
    private final ZBar fineBar;
    private final FocusTraceModel focusTrace = new FocusTraceModel();
    private final DoubleProperty currentZ = new SimpleDoubleProperty(Double.NaN);
    private final DoubleProperty fineHalfWidth = new SimpleDoubleProperty(DEFAULT_FINE_HALF_WIDTH_UM);
    private final DoubleProperty coarseMin;
    private final DoubleProperty coarseMax;

    private final String scannerKey;
    private final DoubleSupplier stageZMinSupplier;
    private final DoubleSupplier stageZMaxSupplier;

    private final Runnable afHistoryListener;
    private final Runnable focusTraceListener;
    private final SimpleBooleanProperty focusTraceEmpty = new SimpleBooleanProperty(true);
    private VBox controlsCol;

    /**
     * @param scannerKey       per-scope key used to persist coarse/fine ranges
     * @param stageZMinSupplier stage Z minimum (microns), evaluated lazily
     * @param stageZMaxSupplier stage Z maximum (microns), evaluated lazily
     * @param streamZ          called on every drag delta to push a new Z target
     *                         into the host gesture worker
     * @param markZ            called when the user presses Mark Z (the caller
     *                         reads the current polled Z and pushes onto
     *                         {@link AfHistoryService})
     * @param movementDisabled binding that is true when stage movement should
     *                         be blocked (e.g. Live View off); disables drag
     *                         + range editors but not Mark Z
     * @param scrollHandler    optional scroll-wheel handler installed on both
     *                         bar canvases so mousewheel scrolling on a bar
     *                         feeds the host's existing Z streaming. May be null.
     */
    public ZBarPanel(
            String scannerKey,
            DoubleSupplier stageZMinSupplier,
            DoubleSupplier stageZMaxSupplier,
            DoubleConsumer streamZ,
            Runnable markZ,
            BooleanBinding movementDisabled,
            EventHandler<ScrollEvent> scrollHandler) {
        this.scannerKey = scannerKey;
        this.stageZMinSupplier = stageZMinSupplier;
        this.stageZMaxSupplier = stageZMaxSupplier;

        double zMin = safeStageMin();
        double zMax = safeStageMax();
        double[] savedCoarse = PersistentPreferences.getZBarCoarseRange(scannerKey);
        if (savedCoarse != null) {
            this.coarseMin = new SimpleDoubleProperty(savedCoarse[0]);
            this.coarseMax = new SimpleDoubleProperty(savedCoarse[1]);
        } else {
            this.coarseMin = new SimpleDoubleProperty(zMin);
            this.coarseMax = new SimpleDoubleProperty(zMax);
        }
        double savedFineHalf = PersistentPreferences.getZBarFineHalfWidth(scannerKey, DEFAULT_FINE_HALF_WIDTH_UM);
        this.fineHalfWidth.set(clamp(savedFineHalf, FINE_MIN_HALF_WIDTH_UM, FINE_MAX_HALF_WIDTH_UM));

        coarseBar = new ZBar(true, COARSE_BAR_WIDTH, BAR_HEIGHT, streamZ);
        fineBar = new ZBar(false, FINE_BAR_WIDTH, BAR_HEIGHT, streamZ);

        // Persistent listeners (retained refs so we can detach in dispose())
        afHistoryListener = this::redrawAll;
        focusTraceListener = () -> {
            focusTraceEmpty.set(focusTrace.isEmpty());
            redrawAll();
        };
        AfHistoryService.addListener(afHistoryListener);
        focusTrace.addListener(focusTraceListener);

        // Install scroll handler on both bar canvases so mousewheel scrolling
        // on a bar feeds the host's existing Z streaming. The handler reads the
        // shared zStepField for step size, so this just works through the host.
        if (scrollHandler != null) {
            coarseBar.canvas.setOnScroll(scrollHandler);
            fineBar.canvas.setOnScroll(scrollHandler);
        }

        // Recenter fine bar + redraw on Z updates
        currentZ.addListener((obs, oldV, newV) -> {
            if (newV == null) return;
            double v = newV.doubleValue();
            if (Double.isNaN(v)) return;
            // Fine bar has a comfort zone -- the central portion of its window
            // where Z polls don't trigger a recenter. Recenter triggers when Z
            // enters the outer 20% of the window (10% each side). Smaller
            // hysteresis than the original 10%-outer, so coarse drags pull the
            // fine view along quickly while sub-um Z polls still don't jitter.
            // No recenter while the fine bar is being dragged.
            if (!fineBar.dragging) {
                double half = fineHalfWidth.get();
                if (v < fineBar.zMin + 0.2 * half || v > fineBar.zMax - 0.2 * half) {
                    fineBar.zMin = v - half;
                    fineBar.zMax = v + half;
                }
            }
            redrawAll();
        });

        // Re-evaluate fine window whenever the half-width changes
        fineHalfWidth.addListener((obs, oldV, newV) -> {
            double z = currentZ.get();
            if (!Double.isNaN(z)) {
                fineBar.zMin = z - newV.doubleValue();
                fineBar.zMax = z + newV.doubleValue();
            }
            PersistentPreferences.setZBarFineHalfWidth(scannerKey, newV.doubleValue());
            redrawAll();
        });
        coarseMin.addListener((obs, oldV, newV) -> {
            coarseBar.zMin = newV.doubleValue();
            PersistentPreferences.setZBarCoarseRange(scannerKey, coarseMin.get(), coarseMax.get());
            redrawAll();
        });
        coarseMax.addListener((obs, oldV, newV) -> {
            coarseBar.zMax = newV.doubleValue();
            PersistentPreferences.setZBarCoarseRange(scannerKey, coarseMin.get(), coarseMax.get());
            redrawAll();
        });

        coarseBar.zMin = coarseMin.get();
        coarseBar.zMax = coarseMax.get();
        double z = stageZMaxSupplier.getAsDouble();
        fineBar.zMin = z - fineHalfWidth.get();
        fineBar.zMax = z + fineHalfWidth.get();

        // Disable drag when movement blocked (Live View off, acquisition active)
        coarseBar.canvas.disableProperty().bind(movementDisabled);
        fineBar.canvas.disableProperty().bind(movementDisabled);

        // Layout
        Button coarseEdit = makeEllipsisButton(this::showCoarseEditor);
        Button fineEdit = makeEllipsisButton(this::showFineEditor);
        coarseEdit.disableProperty().bind(movementDisabled);
        fineEdit.disableProperty().bind(movementDisabled);

        Label coarseLbl = new Label("Coarse");
        Label fineLbl = new Label("Fine");
        coarseLbl.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
        fineLbl.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");

        HBox coarseHeader = new HBox(4, coarseLbl, coarseEdit);
        coarseHeader.setAlignment(Pos.CENTER_LEFT);
        HBox fineHeader = new HBox(4, fineLbl, fineEdit);
        fineHeader.setAlignment(Pos.CENTER_LEFT);

        VBox coarseCol = new VBox(2, coarseHeader, coarseBar.canvas);
        coarseCol.setAlignment(Pos.TOP_LEFT);
        VBox fineCol = new VBox(2, fineHeader, fineBar.canvas);
        fineCol.setAlignment(Pos.TOP_LEFT);

        Button markZBtn = new Button("Mark Z");
        markZBtn.setMaxWidth(Double.MAX_VALUE);
        markZBtn.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        markZBtn.setTooltip(new javafx.scene.control.Tooltip(
                "Record the current Z as a tic on both bars. Stays enabled when Live View is off."));
        markZBtn.setOnAction(e -> {
            if (markZ != null) markZ.run();
        });

        Button maxZFocusBtn = new Button("Max Z Focus");
        maxZFocusBtn.setMaxWidth(Double.MAX_VALUE);
        maxZFocusBtn.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        maxZFocusBtn.setTooltip(new javafx.scene.control.Tooltip(
                "Move Z to the position of the highest recorded focus metric in the current trace."
                        + " Greyed out when no trace samples are present."));
        maxZFocusBtn.setOnAction(e -> {
            Double argmaxZ = findFocusArgmaxZ();
            if (argmaxZ == null) return;
            if (streamZ != null) streamZ.accept(argmaxZ);
        });
        // Disabled while movement is gated OR no focus samples have landed yet
        maxZFocusBtn.disableProperty().bind(movementDisabled.or(focusTraceEmpty));

        controlsCol = new VBox(6, markZBtn, maxZFocusBtn);
        controlsCol.setAlignment(Pos.TOP_CENTER);
        controlsCol.setFillWidth(true);
        // Min width keeps "Max Z Focus" from truncating to "Max Z Foc...".
        // Width was tighter before; freeing the separate trace column let us
        // pull the buttons in but the longest button label still drives the
        // floor. Use the button's pref text width as the floor.
        controlsCol.setMinWidth(95);
        controlsCol.setPrefWidth(95);
        controlsCol.setPadding(new Insets(20, 4, 0, 4));

        setSpacing(8);
        setPadding(new Insets(4, 4, 4, 4));
        setAlignment(Pos.TOP_LEFT);
        getChildren().addAll(coarseCol, fineCol, controlsCol);

        redrawAll();
    }

    /** Push a new polled Z into the widget; recenters the fine bar if needed. */
    public void setCurrentZ(double z) {
        if (Platform.isFxApplicationThread()) {
            currentZ.set(z);
        } else {
            Platform.runLater(() -> currentZ.set(z));
        }
    }

    public double getCurrentZ() {
        return currentZ.get();
    }

    public FocusTraceModel getFocusTrace() {
        return focusTrace;
    }

    /** Detach listeners. Call when the host panel is being disposed. */
    public void dispose() {
        AfHistoryService.removeListener(afHistoryListener);
        focusTrace.removeListener(focusTraceListener);
    }

    /**
     * The right-side controls VBox (Mark Z + Max Z Focus). Exposed so the host
     * panel can append related controls (e.g. the Z step field) below the
     * buttons without having to thread them through the constructor.
     */
    public VBox getControlsColumn() {
        return controlsCol;
    }

    /**
     * Returns the Z position of the largest focus-metric sample in the current
     * trace, or null if the trace is empty.
     */
    private Double findFocusArgmaxZ() {
        List<double[]> samples = focusTrace.snapshot();
        if (samples.isEmpty()) return null;
        double bestZ = samples.get(0)[0];
        double bestMetric = samples.get(0)[1];
        for (double[] s : samples) {
            if (s[1] > bestMetric) {
                bestMetric = s[1];
                bestZ = s[0];
            }
        }
        return bestZ;
    }

    private void redrawAll() {
        coarseBar.redraw();
        fineBar.redraw();
    }

    private Button makeEllipsisButton(Runnable onClick) {
        Button btn = new Button("...");
        btn.setStyle("-fx-font-size: 10px; -fx-padding: 0 4 0 4;");
        btn.setOnAction(e -> onClick.run());
        return btn;
    }

    private void showCoarseEditor() {
        double zMin = safeStageMin();
        double zMax = safeStageMax();
        Spinner<Double> minSpinner = new Spinner<>();
        minSpinner.setValueFactory(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(zMin, zMax, coarseMin.get(), 10.0));
        minSpinner.setEditable(true);
        minSpinner.setPrefWidth(80);
        Spinner<Double> maxSpinner = new Spinner<>();
        maxSpinner.setValueFactory(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(zMin, zMax, coarseMax.get(), 10.0));
        maxSpinner.setEditable(true);
        maxSpinner.setPrefWidth(80);
        Button applyBtn = new Button("Apply");
        Button resetBtn = new Button("Full range");
        VBox content = new VBox(
                4,
                new Label("Coarse bar range (um)"),
                new HBox(4, new Label("Min:"), minSpinner),
                new HBox(4, new Label("Max:"), maxSpinner),
                new HBox(4, applyBtn, resetBtn));
        content.setPadding(new Insets(6));
        ContextMenu menu = new ContextMenu();
        CustomMenuItem item = new CustomMenuItem(content, false);
        menu.getItems().add(item);
        applyBtn.setOnAction(e -> {
            double mn = Math.min(minSpinner.getValue(), maxSpinner.getValue());
            double mx = Math.max(minSpinner.getValue(), maxSpinner.getValue());
            if (mx - mn < 1.0) {
                logger.warn("Coarse range too narrow ({} um); ignored", mx - mn);
            } else {
                coarseMin.set(mn);
                coarseMax.set(mx);
            }
            menu.hide();
        });
        resetBtn.setOnAction(e -> {
            coarseMin.set(zMin);
            coarseMax.set(zMax);
            menu.hide();
        });
        menu.show(coarseBar.canvas, javafx.geometry.Side.RIGHT, 0, 0);
    }

    private void showFineEditor() {
        Spinner<Double> halfSpinner = new Spinner<>();
        halfSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                FINE_MIN_HALF_WIDTH_UM, FINE_MAX_HALF_WIDTH_UM, fineHalfWidth.get(), 1.0));
        halfSpinner.setEditable(true);
        halfSpinner.setPrefWidth(80);
        Button applyBtn = new Button("Apply");
        VBox content = new VBox(
                4, new Label("Fine bar half-width (um)"), new HBox(4, new Label("+/-:"), halfSpinner), applyBtn);
        content.setPadding(new Insets(6));
        ContextMenu menu = new ContextMenu();
        CustomMenuItem item = new CustomMenuItem(content, false);
        menu.getItems().add(item);
        applyBtn.setOnAction(e -> {
            double h = clamp(halfSpinner.getValue(), FINE_MIN_HALF_WIDTH_UM, FINE_MAX_HALF_WIDTH_UM);
            fineHalfWidth.set(h);
            menu.hide();
        });
        menu.show(fineBar.canvas, javafx.geometry.Side.RIGHT, 0, 0);
    }

    private double safeStageMin() {
        try {
            return stageZMinSupplier.getAsDouble();
        } catch (Exception ex) {
            return -1000;
        }
    }

    private double safeStageMax() {
        try {
            return stageZMaxSupplier.getAsDouble();
        } catch (Exception ex) {
            return 1000;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ===================================================================
    // Inner ZBar -- one vertical bar (coarse or fine) with its own Canvas
    // ===================================================================
    private final class ZBar {
        final boolean coarse;
        final Canvas canvas;
        final DoubleConsumer streamZ;
        double zMin;
        double zMax;
        boolean dragging;

        ZBar(boolean coarse, double w, double h, DoubleConsumer streamZ) {
            this.coarse = coarse;
            this.streamZ = streamZ;
            this.canvas = new Canvas(w + 36, h); // +36 px for left-side numeric labels
            this.canvas.setCursor(Cursor.HAND);
            this.canvas.setOnMousePressed(this::onPressed);
            this.canvas.setOnMouseDragged(this::onDragged);
            this.canvas.setOnMouseReleased(this::onReleased);
        }

        double trackLeft() {
            return 36;
        }

        double trackTop() {
            return 4;
        }

        double trackBottom() {
            return canvas.getHeight() - 4;
        }

        double trackWidth() {
            return canvas.getWidth() - trackLeft() - 4;
        }

        double yForZ(double z) {
            double span = zMax - zMin;
            if (span <= 0) return trackTop();
            double frac = (z - zMin) / span;
            frac = clamp(frac, 0, 1);
            return trackBottom() - frac * (trackBottom() - trackTop());
        }

        double zForY(double y) {
            double span = zMax - zMin;
            double frac = (trackBottom() - y) / (trackBottom() - trackTop());
            frac = clamp(frac, 0, 1);
            return zMin + frac * span;
        }

        void onPressed(MouseEvent e) {
            if (canvas.isDisabled()) return;
            dragging = true;
            sendDragTo(e.getY());
        }

        void onDragged(MouseEvent e) {
            if (canvas.isDisabled()) return;
            if (!dragging) return;
            sendDragTo(e.getY());
        }

        void onReleased(MouseEvent e) {
            dragging = false;
        }

        void sendDragTo(double y) {
            double z = zForY(y);
            double zMinStage = safeStageMin();
            double zMaxStage = safeStageMax();
            z = clamp(z, zMinStage, zMaxStage);
            if (streamZ != null) streamZ.accept(z);
        }

        /**
         * Draws the focus-metric trace into the left-margin area of this bar.
         * The curve anchors at {@code tx} (track's left edge) and extends
         * leftward by an amount proportional to the normalized metric value.
         * Drawn semi-transparent so the numeric labels (rendered after) remain
         * readable on top.
         */
        void drawFocusTrace(GraphicsContext g, double tx) {
            if (focusTrace.isEmpty()) return;
            List<double[]> samples = focusTrace.snapshot();
            double mMin = focusTrace.getRunningMin();
            double mMax = focusTrace.getRunningMax();
            double mSpan = mMax - mMin;
            if (mSpan <= 0) return;
            double traceMaxWidth = tx - 2; // reserve 2 px gutter on the far left
            double[] xs = new double[samples.size()];
            double[] ys = new double[samples.size()];
            int n = 0;
            for (double[] s : samples) {
                double zS = s[0];
                if (zS < zMin || zS > zMax) continue;
                double norm = (s[1] - mMin) / mSpan;
                xs[n] = tx - norm * traceMaxWidth;
                ys[n] = yForZ(zS);
                n++;
            }
            if (n < 1) return;
            g.setFill(Color.rgb(255, 140, 0, 0.20));
            g.setStroke(Color.rgb(255, 130, 0, 0.65));
            g.setLineWidth(1.0);
            double[] polyX = new double[n + 2];
            double[] polyY = new double[n + 2];
            for (int i = 0; i < n; i++) {
                polyX[i] = xs[i];
                polyY[i] = ys[i];
            }
            polyX[n] = tx;
            polyY[n] = ys[n - 1];
            polyX[n + 1] = tx;
            polyY[n + 1] = ys[0];
            g.fillPolygon(polyX, polyY, n + 2);
            if (n >= 2) g.strokePolyline(xs, ys, n);
        }

        void redraw() {
            GraphicsContext g = canvas.getGraphicsContext2D();
            double cw = canvas.getWidth();
            double ch = canvas.getHeight();
            g.clearRect(0, 0, cw, ch);

            double tx = trackLeft();
            double ty = trackTop();
            double tw = trackWidth();
            double tb = trackBottom();
            double th = tb - ty;

            // Track background -- visually distinct for coarse vs fine
            if (coarse) {
                LinearGradient grad = new LinearGradient(
                        0,
                        0,
                        0,
                        1,
                        true,
                        javafx.scene.paint.CycleMethod.NO_CYCLE,
                        new Stop(0.0, Color.rgb(70, 100, 140)),
                        new Stop(0.5, Color.rgb(50, 80, 120)),
                        new Stop(1.0, Color.rgb(30, 50, 90)));
                g.setFill(grad);
                g.fillRect(tx, ty, tw, th);
                // Hash overlay so the coarse bar reads as "topographic"
                g.setStroke(Color.rgb(255, 255, 255, 0.08));
                g.setLineWidth(1.0);
                for (double yy = ty + 6; yy < tb; yy += 8) {
                    g.strokeLine(tx, yy, tx + tw, yy);
                }
            } else {
                LinearGradient grad = new LinearGradient(
                        0,
                        0,
                        0,
                        1,
                        true,
                        javafx.scene.paint.CycleMethod.NO_CYCLE,
                        new Stop(0.0, Color.rgb(220, 180, 90)),
                        new Stop(1.0, Color.rgb(180, 140, 60)));
                g.setFill(grad);
                g.fillRect(tx, ty, tw, th);
            }
            g.setStroke(Color.rgb(20, 20, 20, 0.6));
            g.setLineWidth(0.5);
            g.strokeRect(tx, ty, tw, th);

            // Focus-metric trace, drawn UNDER the numeric labels in the left
            // margin. The curve anchors at the track's left edge and extends
            // leftward in proportion to the normalized Brenner metric. Both
            // bars show the trace, scaled to their own Z range -- the coarse
            // bar gives the big-picture peak, the fine bar the local shape.
            drawFocusTrace(g, tx);

            // Tick labels on the LEFT
            g.setFill(Color.rgb(50, 50, 50));
            g.setFont(javafx.scene.text.Font.font(9));
            int ticks = coarse ? 5 : 5;
            for (int i = 0; i < ticks; i++) {
                double frac = (double) i / (ticks - 1);
                double zVal = zMax - frac * (zMax - zMin);
                double yy = ty + frac * th;
                g.setStroke(Color.rgb(60, 60, 60, 0.6));
                g.setLineWidth(0.5);
                g.strokeLine(tx - 3, yy, tx, yy);
                g.fillText(String.format("%.0f", zVal), 2, yy + 3);
            }

            // AF tic marks on the RIGHT edge (opposite the labels)
            List<Double> tics = AfHistoryService.snapshot();
            for (int i = 0; i < tics.size(); i++) {
                double z = tics.get(i);
                if (z < zMin || z > zMax) continue;
                double yy = yForZ(z);
                double opacity = 1.0 - (i / (double) AfHistoryService.CAPACITY);
                g.setStroke(Color.color(0.85, 0.15, 0.85, opacity));
                g.setLineWidth(2.0);
                g.strokeLine(tx + tw, yy, tx + tw + 6, yy);
                g.strokeLine(tx + tw + 6, yy - 3, tx + tw + 6, yy + 3);
            }

            // Current Z thumb
            double z = currentZ.get();
            if (!Double.isNaN(z) && z >= zMin && z <= zMax) {
                double yy = yForZ(z);
                g.setFill(Color.WHITE);
                g.setStroke(Color.BLACK);
                g.setLineWidth(1.0);
                double thumbHalf = 5;
                g.fillRect(tx - 2, yy - 2, tw + 4, 4);
                g.strokeRect(tx - 2, yy - 2, tw + 4, 4);
                // Triangle indicator pointing inward from left
                double[] triX = {tx - 8, tx - 2, tx - 8};
                double[] triY = {yy - thumbHalf, yy, yy + thumbHalf};
                g.setFill(Color.rgb(220, 40, 40));
                g.fillPolygon(triX, triY, 3);
            }
        }
    }
}
