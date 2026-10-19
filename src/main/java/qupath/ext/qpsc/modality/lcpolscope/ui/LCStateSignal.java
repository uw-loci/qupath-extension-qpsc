package qupath.ext.qpsc.modality.lcpolscope.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import qupath.ext.qpsc.utilities.ConfigYamlEditor;

/**
 * The LC-PolScope exposure, read and written as ONE value: the server's per-state signal report
 * (LCSIGNAL) turned into rows and a one-line verdict for the Live Viewer Camera tab, and the
 * write that puts one exposure on every state's channel.
 *
 * <p>Pure: no JavaFX, no socket. The tab and the calibration's Apply both write through
 * {@link #writeExposureToStates}, so "the exposure" means the same thing in both places.
 */
public final class LCStateSignal {

    private LCStateSignal() {}

    /** One state's line in the Camera tab table. */
    public record Row(String id, boolean extinction, double mean, double p999, double percentOfFull, boolean clipped) {}

    /** Per-state rows in the order the server measured them; empty on an error report. */
    public static List<Row> rows(JsonObject report) {
        List<Row> out = new ArrayList<>();
        if (report == null || !report.has("states")) {
            return out;
        }
        double full = number(report, "full_scale");
        for (JsonElement e : report.getAsJsonArray("states")) {
            JsonObject s = e.getAsJsonObject();
            double p999 = number(s, "p999");
            double saturated = number(s, "saturated_fraction");
            out.add(new Row(
                    s.get("id").getAsString(),
                    s.has("extinction") && s.get("extinction").getAsBoolean(),
                    number(s, "mean"),
                    p999,
                    full > 0 ? 100.0 * p999 / full : Double.NaN,
                    saturated > 0.001));
        }
        return out;
    }

    /**
     * One line saying where the brightest swing state sits and what to do about it. Clipping
     * outranks everything else; a dark field is called out, because the swing states differ from
     * extinction by about 1% of the open intensity and a dark field leaves that a few counts.
     */
    public static String verdict(JsonObject report) {
        if (report == null) {
            return "No reply from the microscope server (it may predate this measurement).";
        }
        if (report.has("error")) {
            return "Could not measure: " + report.get("error").getAsString();
        }
        double exposure = number(report, "exposure_ms");
        double fraction = number(report, "brightest_fraction");
        double target = number(report, "target_fraction");
        double full = number(report, "full_scale");
        String brightest =
                report.has("brightest_state") ? report.get("brightest_state").getAsString() : "?";
        boolean clipped = report.has("clipped") && report.get("clipped").getAsBoolean();
        Double suggested = report.has("suggested_exposure_ms")
                        && !report.get("suggested_exposure_ms").isJsonNull()
                ? report.get("suggested_exposure_ms").getAsDouble()
                : null;
        String where = String.format(
                Locale.ROOT,
                "At %s ms the brightest swing state (%s) reaches %.0f%% of full scale (%s).",
                fmt(exposure),
                brightest,
                100 * fraction,
                fmt(full));
        if (clipped) {
            return where + " Some pixels are clipped: lower the exposure and measure again.";
        }
        if (suggested == null) {
            return where;
        }
        String advice =
                String.format(Locale.ROOT, " For %.0f%% use %s ms (Use suggested).", 100 * target, fmt(suggested));
        if (fraction < 0.2) {
            return where + " That is dark: the states will differ by only a few counts." + advice;
        }
        return where + advice;
    }

    /** Camera problems from the report, fatal ones first, each prefixed with its severity. */
    public static List<String> cameraProblems(JsonObject report) {
        List<String> out = new ArrayList<>();
        if (report == null) {
            return out;
        }
        for (String key : new String[] {"camera_fatal", "camera_advisory"}) {
            if (report.has(key) && report.get(key).isJsonArray()) {
                String tag = key.equals("camera_fatal") ? "Must fix: " : "Note: ";
                for (JsonElement e : report.getAsJsonArray(key)) {
                    out.add(tag + e.getAsString());
                }
            }
        }
        return out;
    }

    /** The suggested exposure, or null when the report has none (clipped, dark or an error). */
    public static Double suggestedExposure(JsonObject report) {
        if (report == null
                || !report.has("suggested_exposure_ms")
                || report.get("suggested_exposure_ms").isJsonNull()) {
            return null;
        }
        return report.get("suggested_exposure_ms").getAsDouble();
    }

    /**
     * Writes one exposure to every state's channel under {@code modalities.<modality>.channels}.
     * All states or nothing matters here: per-state differences bias retardance and orientation.
     *
     * @return how many channels changed
     */
    public static int writeExposureToStates(Path configPath, String modality, Collection<String> stateIds, double ms)
            throws IOException {
        if (!(ms > 0)) {
            throw new IllegalArgumentException("Exposure must be positive, got " + ms);
        }
        int changed = 0;
        for (String id : stateIds) {
            ConfigYamlEditor.Result r = ConfigYamlEditor.setChannelExposureMs(configPath, modality, id, ms);
            if (r.changed) {
                changed++;
            }
        }
        return changed;
    }

    private static double number(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : Double.NaN;
    }

    private static String fmt(double v) {
        if (Double.isNaN(v)) {
            return "?";
        }
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
