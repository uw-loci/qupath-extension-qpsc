package qupath.ext.qpsc.modality.lcpolscope.workflow;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.ScatterChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import qupath.ext.qpsc.ui.ThemeColors;

/**
 * Two pictures of an LC calibration result: where each swing state landed relative to the ideal
 * palette, and the intensity curves the search walked along each crystal. Both are drawn from the
 * result JSON alone ({@code palette_waves}, {@code state_intensities}, {@code swing_waves},
 * {@code trace}), so a calibration saved weeks ago can be inspected the same way as a fresh one.
 *
 * <p>Why these two: the extinction ratio describes only the extinction state. A palette whose
 * swing states sit short of the swing on one crystal (LC-B on this rig, 2026-10-26: +0.014 and
 * -0.025 waves where the swing is 0.03) posts a good ratio and passes every number in the text
 * summary; the palette plot shows it at a glance, and the curves show why -- one crystal's
 * intensity rises faster on one side of extinction than the other.
 *
 * <p>Results from microscope server 1.5.x on also carry the drive volts ({@code lca_volts},
 * {@code lcb_volts} on each trace point, and {@code palette_readback}). The palette is in the
 * Meadowlark adapter's waves, which pass through its waves-to-volts table; plotted in volts, a curve
 * lopsided in waves but symmetric in volts is the table, not the crystal (LC-B, 2026-10-28).
 */
public final class LCCalibrationPlots {

    /** Tolerance, in waves, for "this trace point lies on the line through extinction". */
    static final double ON_AXIS_TOLERANCE_WAVES = 2e-4;

    private static final String LCA_COLOR = "#1E88E5";
    private static final String LCB_COLOR = "#E65100";
    private static final String IDEAL_COLOR = "#9E9E9E";
    private static final String EXT_COLOR = "#2E7D32";

    private LCCalibrationPlots() {}

    /** One point of a curve or the palette plane. */
    record Point(double x, double y) {}

    /**
     * A swing state's offset from extinction.
     *
     * @param axis "LC-A" or "LC-B", whichever crystal the state moved
     * @param offsetWaves signed offset along that crystal
     * @param fractionOfSwing |offset| / swing, or NaN without a swing
     */
    record StateOffset(
            String state, double dLca, double dLcb, String axis, double offsetWaves, double fractionOfSwing) {}

    /** The palette state with the lowest measured intensity; falls back to the first state. */
    static Optional<String> extinctionState(JsonObject result) {
        JsonObject palette = object(result, "palette_waves");
        if (palette == null || palette.size() == 0) return Optional.empty();
        JsonObject intensities = object(result, "state_intensities");
        String best = null;
        double bestValue = Double.POSITIVE_INFINITY;
        if (intensities != null) {
            for (String state : palette.keySet()) {
                if (number(intensities, state) && intensities.get(state).getAsDouble() < bestValue) {
                    bestValue = intensities.get(state).getAsDouble();
                    best = state;
                }
            }
        }
        return Optional.of(best != null ? best : palette.keySet().iterator().next());
    }

    /** Every non-extinction state's offset from extinction, in palette order. */
    static List<StateOffset> stateOffsets(JsonObject result) {
        List<StateOffset> out = new ArrayList<>();
        JsonObject palette = object(result, "palette_waves");
        Optional<String> ext = extinctionState(result);
        if (palette == null || ext.isEmpty()) return out;
        double[] origin = pair(palette.get(ext.get()));
        if (origin == null) return out;
        double swing = number(result, "swing_waves") ? result.get("swing_waves").getAsDouble() : Double.NaN;
        for (Map.Entry<String, JsonElement> entry : palette.entrySet()) {
            if (entry.getKey().equals(ext.get())) continue;
            double[] p = pair(entry.getValue());
            if (p == null) continue;
            double dA = p[0] - origin[0];
            double dB = p[1] - origin[1];
            boolean alongA = Math.abs(dA) >= Math.abs(dB);
            double offset = alongA ? dA : dB;
            double fraction = swing > 0 ? Math.abs(offset) / swing : Double.NaN;
            out.add(new StateOffset(entry.getKey(), dA, dB, alongA ? "LC-A" : "LC-B", offset, fraction));
        }
        return out;
    }

    /**
     * Intensity against the offset from extinction along one crystal, from every trace point that
     * holds the other crystal at its extinction value. Repeat readings at the same setting are
     * averaged; points are sorted by offset.
     *
     * @param lca true for the LC-A curve (LC-B held), false for the LC-B curve
     */
    static List<Point> curve(JsonObject result, boolean lca) {
        return curve(result, lca, false);
    }

    /**
     * As {@link #curve(JsonObject, boolean)}, with the offset in millivolts of drive voltage when
     * {@code volts} is true: trace points without volts are left out, and extinction is the drive
     * voltage of the trace points at the extinction setting.
     */
    static List<Point> curve(JsonObject result, boolean lca, boolean volts) {
        List<Point> out = new ArrayList<>();
        JsonObject palette = object(result, "palette_waves");
        Optional<String> ext = extinctionState(result);
        if (palette == null
                || ext.isEmpty()
                || !result.has("trace")
                || !result.get("trace").isJsonArray()) {
            return out;
        }
        double[] origin = pair(palette.get(ext.get()));
        if (origin == null) return out;
        String voltsKey = lca ? "lca_volts" : "lcb_volts";
        Double originVolts = volts ? extinctionVolts(result, lca, origin) : null;
        if (volts && originVolts == null) return out;
        // Keyed by the offset rounded to 1e-4 waves, the adapter's own resolution, or to 1 mV.
        double scale = volts ? 1.0 : 1e4;
        TreeMap<Long, double[]> sums = new TreeMap<>();
        for (JsonElement e : result.getAsJsonArray("trace")) {
            if (!e.isJsonObject()) continue;
            JsonObject t = e.getAsJsonObject();
            if (!number(t, "lca_waves") || !number(t, "lcb_waves") || !number(t, "intensity")) continue;
            if (volts && !number(t, voltsKey)) continue;
            double a = t.get("lca_waves").getAsDouble();
            double b = t.get("lcb_waves").getAsDouble();
            double held = lca ? b - origin[1] : a - origin[0];
            if (Math.abs(held) > ON_AXIS_TOLERANCE_WAVES) continue;
            double offset = volts
                    ? (t.get(voltsKey).getAsDouble() - originVolts) * 1000.0
                    : (lca ? a - origin[0] : b - origin[1]);
            long key = Math.round(offset * scale);
            double[] s = sums.computeIfAbsent(key, k -> new double[2]);
            s[0] += t.get("intensity").getAsDouble();
            s[1] += 1;
        }
        sums.forEach((key, s) -> out.add(new Point(key / scale, s[0] / s[1])));
        return out;
    }

    /** True when the trace carries drive volts for both crystals. */
    static boolean hasVolts(JsonObject result) {
        if (result == null || !result.has("trace") || !result.get("trace").isJsonArray()) return false;
        for (JsonElement e : result.getAsJsonArray("trace")) {
            if (e.isJsonObject()
                    && number(e.getAsJsonObject(), "lca_volts")
                    && number(e.getAsJsonObject(), "lcb_volts")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drive voltage of one crystal at extinction: the palette readback when the file has one,
     * else the mean over trace points sitting at the extinction setting on both crystals.
     */
    static Double extinctionVolts(JsonObject result, boolean lca, double[] origin) {
        String key = lca ? "lca_volts" : "lcb_volts";
        JsonObject readback = object(result, "palette_readback");
        Optional<String> ext = extinctionState(result);
        if (readback != null && ext.isPresent()) {
            JsonObject entry = object(readback, ext.get());
            if (number(entry, key)) return entry.get(key).getAsDouble();
        }
        double sum = 0;
        int n = 0;
        for (JsonElement e : result.getAsJsonArray("trace")) {
            if (!e.isJsonObject()) continue;
            JsonObject t = e.getAsJsonObject();
            if (!number(t, "lca_waves") || !number(t, "lcb_waves") || !number(t, key)) continue;
            if (Math.abs(t.get("lca_waves").getAsDouble() - origin[0]) > ON_AXIS_TOLERANCE_WAVES
                    || Math.abs(t.get("lcb_waves").getAsDouble() - origin[1]) > ON_AXIS_TOLERANCE_WAVES) {
                continue;
            }
            sum += t.get(key).getAsDouble();
            n++;
        }
        return n == 0 ? null : sum / n;
    }

    /** A state's drive-voltage offset from extinction along one crystal, in mV, from the readback. */
    static Optional<Double> readbackOffsetMillivolts(JsonObject result, String state, boolean lca) {
        JsonObject readback = object(result, "palette_readback");
        Optional<String> ext = extinctionState(result);
        String key = lca ? "lca_volts" : "lcb_volts";
        if (readback == null || ext.isEmpty()) return Optional.empty();
        JsonObject here = object(readback, state);
        JsonObject origin = object(readback, ext.get());
        if (!number(here, key) || !number(origin, key)) return Optional.empty();
        return Optional.of((here.get(key).getAsDouble() - origin.get(key).getAsDouble()) * 1000.0);
    }

    /** The intensity every swing state is matched to: the mean of the non-extinction states. */
    static Optional<Double> targetIntensity(JsonObject result) {
        JsonObject intensities = object(result, "state_intensities");
        Optional<String> ext = extinctionState(result);
        if (intensities == null || ext.isEmpty()) return Optional.empty();
        double sum = 0;
        int n = 0;
        for (String state : intensities.keySet()) {
            if (state.equals(ext.get()) || !number(intensities, state)) continue;
            sum += intensities.get(state).getAsDouble();
            n++;
        }
        return n == 0 ? Optional.empty() : Optional.of(sum / n);
    }

    /**
     * One line per swing state, e.g. {@code State2  LC-B +0.0142 waves (-179 mV)  47% of the swing};
     * the volts appear when the result has a palette readback.
     */
    static List<String> offsetLines(JsonObject result) {
        List<String> lines = new ArrayList<>();
        for (StateOffset s : stateOffsets(result)) {
            String pct = Double.isNaN(s.fractionOfSwing())
                    ? ""
                    : String.format(Locale.ROOT, "  %3.0f%% of the swing", s.fractionOfSwing() * 100);
            String mv = readbackOffsetMillivolts(result, s.state(), "LC-A".equals(s.axis()))
                    .map(v -> String.format(Locale.ROOT, " (%+.0f mV)", v))
                    .orElse("");
            lines.add(String.format(
                    Locale.ROOT, "%-8s %s %+.4f waves%s%s", s.state(), s.axis(), s.offsetWaves(), mv, pct));
        }
        return lines;
    }

    /**
     * The summary text plus the two plots, as tabs. The summary stays first so the dialog opens on
     * what it always showed.
     */
    public static TabPane tabs(Node summary, JsonObject result) {
        Tab text = new Tab("Summary", summary);
        Tab palette = new Tab("Palette", palettePane(result));
        Tab curves = new Tab("Search curves", curvesPane(result));
        for (Tab t : List.of(text, palette, curves)) t.setClosable(false);
        TabPane pane = new TabPane(text, palette, curves);
        pane.setPrefSize(720, 560);
        return pane;
    }

    /** The palette plane: measured states against the ideal +/- swing cross around extinction. */
    static Node palettePane(JsonObject result) {
        List<StateOffset> offsets = stateOffsets(result);
        if (offsets.isEmpty()) return note("This result has no palette to plot.");
        double swing = number(result, "swing_waves") ? result.get("swing_waves").getAsDouble() : Double.NaN;
        double reach = Double.isNaN(swing) ? 0 : swing;
        for (StateOffset s : offsets) reach = Math.max(reach, Math.max(Math.abs(s.dLca()), Math.abs(s.dLcb())));
        double bound = Math.ceil(reach * 1.25 * 200) / 200; // to 0.005 waves
        if (!(bound > 0)) bound = 0.05;

        NumberAxis x = new NumberAxis(-bound, bound, 0.01);
        NumberAxis y = new NumberAxis(-bound, bound, 0.01);
        x.setLabel("LC-A offset from extinction (waves)");
        y.setLabel("LC-B offset from extinction (waves)");
        ScatterChart<Number, Number> chart = new ScatterChart<>(x, y);
        chart.setAnimated(false);
        chart.setLegendVisible(true);

        String ext = extinctionState(result).orElse("extinction");
        XYChart.Series<Number, Number> origin = new XYChart.Series<>();
        origin.setName(ext + " (extinction)");
        origin.getData().add(new XYChart.Data<>(0, 0));

        XYChart.Series<Number, Number> ideal = new XYChart.Series<>();
        ideal.setName(
                Double.isNaN(swing)
                        ? "ideal (swing unknown)"
                        : String.format(Locale.ROOT, "ideal (swing %.3f)", swing));
        XYChart.Series<Number, Number> measured = new XYChart.Series<>();
        measured.setName("measured");
        for (StateOffset s : offsets) {
            if (!Double.isNaN(swing)) {
                double sign = Math.signum(s.offsetWaves()) == 0 ? 1 : Math.signum(s.offsetWaves());
                ideal.getData()
                        .add(
                                "LC-A".equals(s.axis())
                                        ? new XYChart.Data<>(sign * swing, 0)
                                        : new XYChart.Data<>(0, sign * swing));
            }
            measured.getData().add(new XYChart.Data<>(s.dLca(), s.dLcb(), s));
        }
        chart.getData().add(origin);
        chart.getData().add(ideal);
        chart.getData().add(measured);
        // Series colours through modena's looked-up colours, so the legend matches the points.
        chart.setStyle(seriesColors(EXT_COLOR, IDEAL_COLOR, LCB_COLOR));
        for (XYChart.Data<Number, Number> d : measured.getData()) {
            if (d.getExtraValue() instanceof StateOffset s && d.getNode() != null) {
                Tooltip.install(
                        d.getNode(),
                        new Tooltip(String.format(
                                Locale.ROOT, "%s: LC-A %+.4f, LC-B %+.4f waves", s.state(), s.dLca(), s.dLcb())));
            }
        }
        chart.setPrefSize(520, 440);

        Label caption = note("Each swing state should sit on the grey point of its own axis: the same distance "
                + "from extinction on both crystals. A state short of its grey point needed less retardance "
                + "than the swing to reach the same brightness.\n" + String.join("\n", offsetLines(result)));
        caption.setStyle(caption.getStyle() + " -fx-font-family: monospace;");
        VBox box = new VBox(6, chart, caption);
        box.setPadding(new Insets(8));
        return box;
    }

    /**
     * Intensity against the offset from extinction on each crystal, with the swing-state target. With
     * drive volts in the trace, a waves | volts toggle redraws the same curves against the voltage
     * the crystals actually received.
     */
    static Node curvesPane(JsonObject result) {
        Node waves = curvesChart(result, false);
        if (waves == null) {
            return note("This result has no search trace to plot (it predates the trace, or the search "
                    + "recorded too few points along either crystal).");
        }
        Label caption = note("Both curves should be the same shape and symmetric about 0: the swing states "
                + "are where each curve crosses the dashed target. A curve that rises faster on one side "
                + "puts that side's state closer to extinction. Each point is the mean of the readings the "
                + "search took at that setting.");
        if (!hasVolts(result)) {
            VBox box = new VBox(6, waves, caption);
            box.setPadding(new Insets(8));
            return box;
        }
        Node volts = curvesChart(result, true);
        StackPane holder = new StackPane(waves);
        ToggleGroup group = new ToggleGroup();
        ToggleButton inWaves = new ToggleButton("Commanded waves");
        ToggleButton inVolts = new ToggleButton("Drive volts");
        inWaves.setToggleGroup(group);
        inVolts.setToggleGroup(group);
        inWaves.setSelected(true);
        inVolts.setDisable(volts == null);
        group.selectedToggleProperty().addListener((obs, was, now) -> {
            if (now == null) {
                // A toggle group lets the selected button be clicked off; keep one selected.
                was.setSelected(true);
                return;
            }
            holder.getChildren().setAll(now == inVolts ? volts : waves);
        });
        Label voltsNote = note("Waves are what the palette commands; the Meadowlark adapter turns them into "
                + "volts through its own table. A crystal whose curve is lopsided in waves but symmetric in "
                + "volts is behaving normally and the table does not fit it.");
        HBox toggle = new HBox(6, new Label("x axis:"), inWaves, inVolts);
        VBox box = new VBox(6, toggle, holder, caption, voltsNote);
        box.setPadding(new Insets(8));
        return box;
    }

    /** The two crystals' curves in waves or millivolts; null when neither has two points. */
    private static Node curvesChart(JsonObject result, boolean volts) {
        List<Point> a = curve(result, true, volts);
        List<Point> b = curve(result, false, volts);
        if (a.size() < 2 && b.size() < 2) return null;
        NumberAxis x = new NumberAxis();
        NumberAxis y = new NumberAxis();
        x.setLabel(volts ? "Drive voltage offset from extinction (mV)" : "Offset from extinction (waves)");
        y.setLabel("Mean intensity (counts)");
        x.setForceZeroInRange(true);
        y.setForceZeroInRange(false);
        LineChart<Number, Number> chart = new LineChart<>(x, y);
        chart.setAnimated(false);
        chart.setCreateSymbols(true);

        XYChart.Series<Number, Number> lca = series("LC-A (LC-B held at extinction)", a);
        XYChart.Series<Number, Number> lcb = series("LC-B (LC-A held at extinction)", b);
        chart.getData().add(lca);
        chart.getData().add(lcb);

        Optional<Double> target = targetIntensity(result);
        if (target.isPresent()) {
            double lo = Double.POSITIVE_INFINITY;
            double hi = Double.NEGATIVE_INFINITY;
            for (Point p : a) {
                lo = Math.min(lo, p.x());
                hi = Math.max(hi, p.x());
            }
            for (Point p : b) {
                lo = Math.min(lo, p.x());
                hi = Math.max(hi, p.x());
            }
            XYChart.Series<Number, Number> ref = new XYChart.Series<>();
            ref.setName(String.format(Locale.ROOT, "swing-state target (%.0f counts)", target.get()));
            ref.getData().add(new XYChart.Data<>(lo, target.get()));
            ref.getData().add(new XYChart.Data<>(hi, target.get()));
            chart.getData().add(ref);
            for (XYChart.Data<Number, Number> d : ref.getData()) {
                if (d.getNode() != null) d.getNode().setVisible(false);
            }
            if (ref.getNode() != null) ref.getNode().setStyle("-fx-stroke-dash-array: 6 4;");
        }
        chart.setStyle(seriesColors(LCA_COLOR, LCB_COLOR, IDEAL_COLOR));
        chart.setPrefSize(640, 420);
        return chart;
    }

    private static XYChart.Series<Number, Number> series(String name, List<Point> points) {
        XYChart.Series<Number, Number> s = new XYChart.Series<>();
        s.setName(name);
        points.stream()
                .sorted(Comparator.comparingDouble(Point::x))
                .forEach(p -> s.getData().add(new XYChart.Data<>(p.x(), p.y())));
        return s;
    }

    /** Modena colours its series from CHART_COLOR_1..8; set the first few, in series order. */
    static String seriesColors(String... colors) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < colors.length; i++) {
            sb.append("CHART_COLOR_")
                    .append(i + 1)
                    .append(": ")
                    .append(colors[i])
                    .append("; ");
        }
        return sb.toString().trim();
    }

    private static Label note(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle("-fx-text-fill: " + ThemeColors.MUTED + "; -fx-font-size: 11px;");
        return label;
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : null;
    }

    private static boolean number(JsonObject o, String key) {
        return o != null
                && o.has(key)
                && o.get(key).isJsonPrimitive()
                && o.get(key).getAsJsonPrimitive().isNumber();
    }

    private static double[] pair(JsonElement e) {
        if (e == null || !e.isJsonArray()) return null;
        JsonArray a = e.getAsJsonArray();
        if (a.size() < 2) return null;
        try {
            return new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble()};
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
