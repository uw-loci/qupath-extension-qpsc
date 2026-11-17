package qupath.ext.qpsc.ui.liveviewer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns the server's HWSTAT reply into the Live Viewer banner text: which part of the
 * microscope is not answering, and since when ("Stage not responding since 12:40").
 */
public final class HardwareStatusText {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private HardwareStatusText() {}

    /**
     * The banner for a HWSTAT reply, or null when every part answers (or the reply is empty).
     *
     * @param report the reply: {stage, camera, micromanager}, each {ok, since, message, failures}
     * @param zone the time zone to show the time in
     */
    public static String describe(JsonObject report, ZoneId zone) {
        if (report == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        add(parts, report, "micromanager", "Micro-Manager not answering", zone);
        add(parts, report, "stage", "Stage not responding", zone);
        add(parts, report, "camera", "Camera snaps failing", zone);
        return parts.isEmpty() ? null : "Server connected; " + String.join("; ", parts);
    }

    private static void add(List<String> out, JsonObject report, String key, String what, ZoneId zone) {
        if (!(report.get(key) instanceof JsonObject part)) {
            return;
        }
        JsonElement ok = part.get("ok");
        if (ok == null || !ok.isJsonPrimitive() || ok.getAsBoolean()) {
            return;
        }
        StringBuilder sb = new StringBuilder(what);
        JsonElement since = part.get("since");
        if (since != null
                && since.isJsonPrimitive()
                && since.getAsJsonPrimitive().isNumber()) {
            long ms = Math.round(since.getAsDouble() * 1000.0);
            sb.append(" since ").append(CLOCK.format(Instant.ofEpochMilli(ms).atZone(zone)));
        }
        out.add(sb.toString());
    }
}
