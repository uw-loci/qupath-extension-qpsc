package qupath.ext.qpsc.modality.lcpolscope.workflow;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import qupath.ext.qpsc.ui.ThemeColors;

/**
 * The pictures of an LC voltage sweep (LCSWEEP): for each crystal, the retardance it really has
 * against drive voltage (from its own fringes), the Meadowlark adapter's table for the same
 * conversion, and where the applied palette's states sit on both. Drawn from the sweep file
 * ({@code lc_sweep_<stamp>.json}) alone.
 *
 * <p>The question it answers: does the adapter's waves-to-volts table fit these crystals? On the
 * LC-PolScope its LC-B column is kinked across 4.1-4.6 V, the working range, which would make an
 * LC-B palette lopsided in waves while the crystal itself is symmetric (2026-10-28).
 */
final class LCVoltageSweepPlots {

    private static final String MEASURED_COLOR = "#1E88E5";
    private static final String TABLE_COLOR = "#9E9E9E";
    private static final String PALETTE_COLOR = "#E65100";

    /** Half-width, in volts, of the "around the palette" view. */
    static final double PALETTE_WINDOW_V = 0.75;

    private LCVoltageSweepPlots() {}

    /** One point of a curve: volts against waves. */
    record Point(double volts, double waves) {}

    /** A palette state on one crystal: drive volts and measured waves (NaN when not measured). */
    record StatePoint(String state, double volts, double measuredWaves, double commandedWaves) {}

    /** The measured curve of one crystal ({@code LCA} or {@code LCB}); empty without one. */
    static List<Point> measured(JsonObject record, String axis) {
        return pairs(curveObject(record, axis, "curve"), "volts", "waves");
    }

    /** The adapter's table for one crystal, as sampled by the sweep; empty without one. */
    static List<Point> table(JsonObject record, String axis) {
        return pairs(curveObject(record, axis, "adapter_table"), "volts", "waves");
    }

    /** Each palette state's drive volts and measured waves on one crystal. */
    static List<StatePoint> palette(JsonObject record, String axis) {
        List<StatePoint> out = new ArrayList<>();
        JsonObject palette = object(record, "palette");
        if (palette == null) return out;
        String key = "LCA".equals(axis) ? "lca" : "lcb";
        for (Map.Entry<String, JsonElement> e : palette.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject row = e.getValue().getAsJsonObject();
            if (!number(row, key + "_volts")) continue;
            out.add(new StatePoint(
                    e.getKey(),
                    row.get(key + "_volts").getAsDouble(),
                    number(row, key + "_waves_measured")
                            ? row.get(key + "_waves_measured").getAsDouble()
                            : Double.NaN,
                    number(row, key + "_waves_commanded")
                            ? row.get(key + "_waves_commanded").getAsDouble()
                            : Double.NaN));
        }
        return out;
    }

    /**
     * The text summary: per crystal its fit or its error, the largest table-vs-measured gap, and
     * each swing state's offset from extinction commanded, measured and in volts.
     */
    static List<String> summaryLines(JsonObject summary) {
        List<String> lines = new ArrayList<>();
        JsonObject axes = object(summary, "axes");
        if (axes != null) {
            for (String axis : List.of("LCA", "LCB")) {
                JsonObject a = object(axes, axis);
                if (a == null) continue;
                String name = "LCA".equals(axis) ? "LC-A" : "LC-B";
                if (a.has("error") && !a.get("error").isJsonNull()) {
                    lines.add(name + ": " + a.get("error").getAsString());
                    continue;
                }
                StringBuilder sb = new StringBuilder(name);
                if (a.has("landmarks") && a.get("landmarks").isJsonArray()) {
                    sb.append(": ").append(a.getAsJsonArray("landmarks").size()).append(" fringes");
                }
                if (number(a, "exposure_ms")) {
                    sb.append(String.format(
                            Locale.ROOT,
                            ", exposure %.2f ms",
                            a.get("exposure_ms").getAsDouble()));
                }
                JsonObject gap = object(a, "table_minus_measured_waves");
                if (gap != null && number(gap, "max_abs") && number(gap, "at_volts")) {
                    sb.append(String.format(
                            Locale.ROOT,
                            "; the adapter's table is off by up to %.4f waves (at %.2f V)",
                            gap.get("max_abs").getAsDouble(),
                            gap.get("at_volts").getAsDouble()));
                }
                lines.add(sb.toString());
            }
        }
        if (summary != null
                && summary.has("palette_lines")
                && summary.get("palette_lines").isJsonArray()) {
            JsonArray palette = summary.getAsJsonArray("palette_lines");
            if (!palette.isEmpty()) {
                lines.add("");
                lines.add("Swing states, offset from extinction (commanded = measured, drive volts):");
                for (JsonElement e : palette) lines.add("  " + e.getAsString());
            }
        }
        return lines;
    }

    /** One crystal's chart, with a full range | around the palette toggle. */
    static Node axisPane(JsonObject record, String axis) {
        List<Point> measured = measured(record, axis);
        if (measured.size() < 2) {
            return note("No measured curve for this crystal; see the Summary tab for why.");
        }
        List<Point> table = table(record, axis);
        List<StatePoint> states = palette(record, axis);
        Node full = chart(axis, measured, table, states, Double.NaN, Double.NaN);
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (StatePoint s : states) {
            lo = Math.min(lo, s.volts());
            hi = Math.max(hi, s.volts());
        }
        Label caption = note("Blue is the retardance this crystal really has at each voltage, from its own "
                + "fringes; grey is what the Meadowlark adapter's table assumes. Orange marks the palette's "
                + "states at the volts the adapter drives them. Where blue and grey part, every palette number "
                + "in waves is off by the gap.");
        if (!(hi >= lo)) {
            VBox box = new VBox(6, full, caption);
            box.setPadding(new Insets(8));
            return box;
        }
        Node near = chart(axis, measured, table, states, lo - PALETTE_WINDOW_V, hi + PALETTE_WINDOW_V);
        StackPane holder = new StackPane(near);
        ToggleGroup group = new ToggleGroup();
        ToggleButton aroundPalette = new ToggleButton("Around the palette");
        ToggleButton fullRange = new ToggleButton("Full range");
        aroundPalette.setToggleGroup(group);
        fullRange.setToggleGroup(group);
        aroundPalette.setSelected(true);
        group.selectedToggleProperty().addListener((obs, was, now) -> {
            if (now == null) {
                was.setSelected(true);
                return;
            }
            holder.getChildren().setAll(now == fullRange ? full : near);
        });
        VBox box = new VBox(6, new HBox(6, new Label("Show:"), aroundPalette, fullRange), holder, caption);
        box.setPadding(new Insets(8));
        return box;
    }

    private static Node chart(
            String axis, List<Point> measured, List<Point> table, List<StatePoint> states, double lo, double hi) {
        boolean windowed = !Double.isNaN(lo);
        if (windowed) {
            double step = niceStep(hi - lo);
            lo = Math.floor(lo / step) * step;
            hi = Math.ceil(hi / step) * step;
        }
        NumberAxis x = windowed ? new NumberAxis(lo, hi, niceStep(hi - lo)) : new NumberAxis();
        NumberAxis y = new NumberAxis();
        x.setLabel(("LCA".equals(axis) ? "LC-A" : "LC-B") + " drive voltage (V)");
        y.setLabel("Retardance (waves)");
        y.setForceZeroInRange(false);
        LineChart<Number, Number> chart = new LineChart<>(x, y);
        chart.setAnimated(false);
        chart.setCreateSymbols(false);

        XYChart.Series<Number, Number> m = new XYChart.Series<>();
        m.setName("measured (fringes)");
        XYChart.Series<Number, Number> t = new XYChart.Series<>();
        t.setName("adapter's table");
        XYChart.Series<Number, Number> p = new XYChart.Series<>();
        p.setName("palette states");
        for (Point pt : measured) {
            if (!windowed || (pt.volts() >= lo && pt.volts() <= hi)) {
                m.getData().add(new XYChart.Data<>(pt.volts(), pt.waves()));
            }
        }
        for (Point pt : table) {
            if (!windowed || (pt.volts() >= lo && pt.volts() <= hi)) {
                t.getData().add(new XYChart.Data<>(pt.volts(), pt.waves()));
            }
        }
        // Points only: a line through five states means nothing. The node is set before the series
        // joins the chart, or the chart never places it.
        for (StatePoint s : states) {
            if (Double.isNaN(s.measuredWaves())) continue;
            XYChart.Data<Number, Number> d = new XYChart.Data<>(s.volts(), s.measuredWaves(), s);
            javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(4.5);
            dot.setStyle("-fx-fill: " + PALETTE_COLOR + ";");
            javafx.scene.control.Tooltip.install(
                    dot,
                    new javafx.scene.control.Tooltip(String.format(
                            Locale.ROOT,
                            "%s: %.3f V, commanded %.4f waves, measured %.4f",
                            s.state(),
                            s.volts(),
                            s.commandedWaves(),
                            s.measuredWaves())));
            d.setNode(dot);
            p.getData().add(d);
        }
        chart.getData().add(m);
        chart.getData().add(t);
        chart.getData().add(p);
        chart.setStyle(LCCalibrationPlots.seriesColors(MEASURED_COLOR, TABLE_COLOR, PALETTE_COLOR));
        if (p.getNode() != null) p.getNode().setStyle("-fx-stroke: transparent;");
        chart.setPrefSize(640, 420);
        return chart;
    }

    static double niceStep(double span) {
        if (!(span > 0)) return 1;
        double raw = span / 8;
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        for (double m : new double[] {1, 2, 5, 10}) {
            if (raw <= m * mag) return m * mag;
        }
        return 10 * mag;
    }

    private static JsonObject curveObject(JsonObject record, String axis, String key) {
        return object(object(object(record, "axes"), axis), key);
    }

    private static List<Point> pairs(JsonObject o, String xKey, String yKey) {
        List<Point> out = new ArrayList<>();
        if (o == null
                || !o.has(xKey)
                || !o.has(yKey)
                || !o.get(xKey).isJsonArray()
                || !o.get(yKey).isJsonArray()) {
            return out;
        }
        JsonArray xs = o.getAsJsonArray(xKey);
        JsonArray ys = o.getAsJsonArray(yKey);
        for (int i = 0; i < Math.min(xs.size(), ys.size()); i++) {
            try {
                out.add(new Point(xs.get(i).getAsDouble(), ys.get(i).getAsDouble()));
            } catch (RuntimeException e) {
                // a null or text entry is skipped, not plotted as zero
            }
        }
        out.sort(java.util.Comparator.comparingDouble(Point::volts));
        return out;
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
}
