package qupath.ext.qpsc.modality.lcpolscope.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import qupath.ext.qpsc.utilities.BackgroundSettingsReader;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * Whether the LC-PolScope is ready to acquire, as a list of checks the acquisition panel shows
 * before the stage moves. Every one of these used to surface only as a log line at the first tile,
 * after the run had started: no calibration applied, a background from another calibration (which
 * the server then refuses), a camera on auto gain or gamma, the adapter converting at another
 * wavelength.
 *
 * <p>Pure functions over the configuration and the server's {@code LCREADY} report, so they are
 * testable without a microscope; {@link LCPolScopeReadinessPane} only displays them.
 */
public final class LCPolScopeReadiness {

    /** How a check came out. */
    public enum Level {
        OK,
        WARN,
        FAIL,
        INFO
    }

    /** One line of the summary. */
    public record Check(Level level, String text) {}

    /** Calibrations older than this are flagged (the LCs drift with temperature and age). */
    static final long STALE_CALIBRATION_DAYS = 14;

    private static final String MODALITY = "lcpolscope";

    private LCPolScopeReadiness() {}

    /** Checks answerable from the configuration and the background folders on disk. */
    public static List<Check> configChecks(MicroscopeConfigManager mgr, LocalDateTime now) {
        List<Check> checks = new ArrayList<>();
        if (mgr == null) {
            checks.add(new Check(Level.INFO, "No microscope configuration loaded."));
            return checks;
        }
        Map<String, Object> recon = mgr.getSection("modalities", MODALITY, "reconstruction");
        String calibratedAt = recon == null ? null : stringOf(recon.get("lc_calibrated_at"));
        if (calibratedAt == null) {
            checks.add(new Check(
                    Level.WARN,
                    "No QPSC LC calibration applied: the states come from the YAML's original palette. "
                            + "Calibrate Liquid Crystals, then Apply."));
        } else {
            String age = "";
            try {
                // Calendar days, as a person counts them: 15:01 yesterday is "1 day ago" at 09:00.
                long days = java.time.temporal.ChronoUnit.DAYS.between(
                        LocalDateTime.parse(calibratedAt).toLocalDate(), now.toLocalDate());
                age = days == 0 ? " (today)" : " (" + days + " day" + (days == 1 ? "" : "s") + " ago)";
                Object er = recon.get("lc_extinction_ratio");
                String ratio = er == null ? "" : ", extinction ratio " + er;
                Level level = days > STALE_CALIBRATION_DAYS ? Level.WARN : Level.OK;
                checks.add(new Check(
                        level,
                        "Calibration " + calibratedAt.replace('T', ' ') + age + ratio
                                + (level == Level.WARN ? " -- consider recalibrating" : "")));
            } catch (DateTimeParseException e) {
                checks.add(new Check(Level.OK, "Calibration " + calibratedAt));
            }
            Object er = recon.get("lc_extinction_ratio");
            if (er instanceof Number n && n.doubleValue() < 80) {
                checks.add(
                        new Check(Level.WARN, "Extinction ratio " + n + " is below 80: reconstruction will be noisy."));
            }
        }
        Object black = recon == null ? null : recon.get("black_level");
        if (!(black instanceof Number b) || b.doubleValue() <= 0) {
            checks.add(new Check(
                    Level.WARN,
                    "No black level set (reconstruction.black_level): the camera offset is not subtracted."));
        }

        String base = mgr.getBackgroundProfileFolder();
        for (String profile : mgr.getProfileKeysForModality(MODALITY)) {
            Map<String, Object> p = mgr.getSection("acquisition_profiles", profile);
            String objective = p == null ? null : stringOf(p.get("objective"));
            String detector = p == null ? null : stringOf(p.get("detector"));
            checks.add(backgroundCheck(profile, base, objective, detector, calibratedAt));
        }
        return checks;
    }

    /**
     * Same limit as the server's {@code LC_BACKGROUND_MAX_SWING_SPREAD}: the swing states'
     * background means, above black, may differ by this fraction of their mean on a clear field.
     */
    static final double MAX_BACKGROUND_SWING_SPREAD = 0.10;

    /**
     * Same limit as the server's {@code LC_BACKGROUND_MAX_FIELD_VARIATION}: above it the swing
     * states' balance changes across the field, which a calibration cannot cause.
     */
    static final double MAX_BACKGROUND_FIELD_VARIATION = 0.25;

    static Check backgroundCheck(String profile, String base, String objective, String detector, String calibratedAt) {
        if (base == null || base.isBlank()) {
            return new Check(Level.WARN, profile + ": no background collected yet; it reconstructs uncorrected.");
        }
        String folder =
                BackgroundSettingsReader.resolveChannelBackgroundFolder(base, MODALITY, objective, detector, profile);
        File provenance = folder == null ? null : new File(folder, "background_provenance.json");
        if (provenance == null || !provenance.isFile()) {
            return new Check(Level.WARN, profile + ": no background for this profile; it reconstructs uncorrected.");
        }
        try {
            JsonObject json = com.google.gson.JsonParser.parseString(Files.readString(provenance.toPath()))
                    .getAsJsonObject();
            String taken = json.has("lc_calibrated_at")
                            && !json.get("lc_calibrated_at").isJsonNull()
                    ? json.get("lc_calibrated_at").getAsString()
                    : null;
            String collected =
                    json.has("collected_at") ? json.get("collected_at").getAsString() : "?";
            if (!Objects.equals(taken, calibratedAt)) {
                return new Check(
                        Level.FAIL,
                        profile + ": the background is from a different calibration and will be refused. "
                                + "Collect it again.");
            }
            Double spread = numberOf(json, "swing_state_spread");
            Double fieldVariation = numberOf(json, "swing_field_variation");
            boolean patchy = fieldVariation != null && fieldVariation > MAX_BACKGROUND_FIELD_VARIATION;
            if (spread != null && spread > MAX_BACKGROUND_SWING_SPREAD && calibratedAt == null && !patchy) {
                // Without a QPSC calibration the states are whatever palette the YAML carries,
                // not balanced for this light path, so their means differ whatever the field.
                // The Stokes background correction removes a uniform imbalance like that.
                // A balance that changes across the field still means specimen (below).
                return new Check(
                        Level.OK,
                        String.format(
                                Locale.ROOT,
                                "%s: background %s; its swing states differ by %.0f%%, as expected without a "
                                        + "QPSC LC calibration (the background correction removes it). Collect "
                                        + "it again after Calibrate + Apply.",
                                profile,
                                collected.replace('T', ' '),
                                spread * 100));
            }
            if (spread != null && spread > MAX_BACKGROUND_SWING_SPREAD) {
                return new Check(
                        Level.WARN,
                        String.format(
                                Locale.ROOT,
                                "%s: the background's swing states differ by %.0f%%; on a clear field they agree to "
                                        + "a few percent, so it was probably taken on specimen. Collect it again on an "
                                        + "empty area.",
                                profile,
                                spread * 100));
            }
            return new Check(Level.OK, profile + ": background " + collected.replace('T', ' ') + ", this calibration");
        } catch (Exception e) {
            return new Check(Level.WARN, profile + ": background record unreadable (" + e.getMessage() + ").");
        }
    }

    /** Checks from the server's {@code LCREADY} report; empty input means it was not available. */
    public static List<Check> liveChecks(JsonObject report) {
        List<Check> checks = new ArrayList<>();
        if (report == null) {
            checks.add(new Check(Level.INFO, "Not connected: camera settings not checked."));
            return checks;
        }
        if (report.has("error")) {
            checks.add(new Check(
                    Level.INFO, "Server could not check: " + report.get("error").getAsString()));
            return checks;
        }
        for (JsonElement e : arrayOf(report, "camera_fatal")) {
            checks.add(new Check(Level.FAIL, e.getAsString()));
        }
        for (JsonElement e : arrayOf(report, "camera_advisory")) {
            checks.add(new Check(Level.WARN, e.getAsString()));
        }
        if (arrayOf(report, "camera_fatal").isEmpty() && report.has("camera_text")) {
            Level level = arrayOf(report, "camera_advisory").isEmpty() ? Level.OK : Level.INFO;
            checks.add(new Check(level, "Camera: " + report.get("camera_text").getAsString()));
        }
        Double adapter = numberOf(report, "adapter_wavelength_nm");
        Double recon = numberOf(report, "reconstruction_wavelength_nm");
        if (adapter != null && recon != null && Math.abs(adapter - recon) > 0.05) {
            checks.add(new Check(
                    Level.WARN,
                    String.format(
                            "LC adapter at %s nm, reconstruction at %s nm: retardance is scaled by %.4f until "
                                    + "the next calibration sets the adapter.",
                            trim(adapter), trim(recon), adapter / recon)));
        } else if (adapter != null) {
            checks.add(new Check(Level.OK, "LC adapter wavelength " + trim(adapter) + " nm"));
        }
        return checks;
    }

    private static List<JsonElement> arrayOf(JsonObject o, String key) {
        List<JsonElement> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            o.getAsJsonArray(key).forEach(out::add);
        }
        return out;
    }

    private static Double numberOf(JsonObject o, String key) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String stringOf(Object o) {
        return o == null || o.toString().isBlank() ? null : o.toString();
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
