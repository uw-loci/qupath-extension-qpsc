package qupath.ext.qpsc.utilities;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists the outcome of a focus-approach validation run, keyed by
 * (microscope, modality, objective).
 *
 * <p>Stored as a sidecar JSON beside the microscope config rather than inside the autofocus
 * YAML: the YAML is hand-edited and round-tripping it risks reformatting an operator's file, and
 * this record is machine-written measurement rather than configuration.
 *
 * <h2>Staleness</h2>
 * A stale pass is worse than no pass -- it licenses an unattended Z approach on a rig whose
 * optics have since changed. The record therefore carries what it was measured against, and
 * {@link Record#isStaleAgainst} reports when any of it no longer matches. It deliberately does
 * NOT expire on time alone: a rig that has not been touched does not become unsafe because a
 * month passed, and an arbitrary expiry would train operators to re-run it without thinking.
 */
public final class FocusApproachValidationStore {

    private static final Logger logger = LoggerFactory.getLogger(FocusApproachValidationStore.class);

    private static final String FILENAME = "focus_approach_validation.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private FocusApproachValidationStore() {}

    /**
     * One validation outcome.
     *
     * @param microscope        microscope name the run was performed on
     * @param modality          modality validated
     * @param objective         objective ID validated (the highest-magnification one in use)
     * @param usable            whether approach-from-safe-Z is licensed for this combination
     * @param safeZUm           the retracted Z the approach was measured from
     * @param approachDistanceUm measured distance from safe Z to focus
     * @param peakWidthUm       measured focus-peak FWHM; bounds how fast the approach may scan
     * @param falsePeakZs       Z positions of prominent peaks encountered before focus
     * @param reasons           why it failed, empty when it passed
     * @param timestamp         ISO-8601 instant the run completed
     */
    public record Record(
            String microscope,
            String modality,
            String objective,
            boolean usable,
            double safeZUm,
            double approachDistanceUm,
            double peakWidthUm,
            List<Double> falsePeakZs,
            List<String> reasons,
            String timestamp) {

        /**
         * Why this record no longer applies, or null when it still does.
         *
         * <p>The safe Z is the load-bearing one: the profile was measured as the approach from
         * THAT position, so moving it invalidates both the approach distance and any claim about
         * what lies between there and the sample.
         *
         * @param currentSafeZUm the safe Z currently configured
         * @return a human-readable reason, or null
         */
        public String isStaleAgainst(Double currentSafeZUm) {
            if (currentSafeZUm == null) {
                return "stage.safe_z_um is no longer configured";
            }
            if (Math.abs(currentSafeZUm - safeZUm) > 1.0) {
                return String.format(
                        "stage.safe_z_um changed from %.1f to %.1f since this was measured", safeZUm, currentSafeZUm);
            }
            return null;
        }
    }

    private static Path storePath() {
        String configPath = qupath.ext.qpsc.preferences.QPPreferenceDialog.getMicroscopeConfigFileProperty();
        if (configPath == null || configPath.isBlank()) {
            return null;
        }
        Path parent = Paths.get(configPath).getParent();
        return (parent == null) ? null : parent.resolve(FILENAME);
    }

    private static String key(String microscope, String modality, String objective) {
        return microscope + "|" + modality + "|" + objective;
    }

    /** All stored records, keyed by microscope|modality|objective. Empty when none exist. */
    public static Map<String, Record> loadAll() {
        Path path = storePath();
        if (path == null || !Files.exists(path)) {
            return Map.of();
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Record> loaded = GSON.fromJson(
                    json,
                    com.google.gson.reflect.TypeToken.getParameterized(LinkedHashMap.class, String.class, Record.class)
                            .getType());
            return (loaded == null) ? Map.of() : loaded;
        } catch (Exception e) {
            logger.warn("Could not read {}: {}", path, e.getMessage());
            return Map.of();
        }
    }

    /**
     * The stored record for one combination, or null when it has never been validated.
     *
     * @param microscope microscope name
     * @param modality   modality
     * @param objective  objective ID
     * @return the record, or null
     */
    public static Record find(String microscope, String modality, String objective) {
        return loadAll().get(key(microscope, modality, objective));
    }

    /**
     * Writes (or replaces) one record.
     *
     * @param record the outcome to persist
     * @throws IOException if the store cannot be written
     */
    public static void save(Record record) throws IOException {
        Path path = storePath();
        if (path == null) {
            throw new IOException("No microscope config path set; cannot locate the validation store");
        }
        Map<String, Record> all = new LinkedHashMap<>(loadAll());
        all.put(key(record.microscope(), record.modality(), record.objective()), record);
        Files.createDirectories(path.getParent());
        Files.writeString(path, GSON.toJson(all), StandardCharsets.UTF_8);
        logger.info(
                "Focus-approach validation saved: {}/{}/{} usable={} -> {}",
                record.microscope(),
                record.modality(),
                record.objective(),
                record.usable(),
                path);
    }

    /**
     * Parses a server dump's {@code samples.csv} into parallel Z and metric arrays.
     *
     * <p>Columns are {@code (idx, wall_ms, z_assumed_um, z_actual_um, metric)}. The ACTUAL Z is
     * used, not the assumed one -- the assumed value is interpolated from the commanded motion
     * profile, and the whole point of this measurement is to characterise where the metric
     * really peaks.
     *
     * @param samplesCsv path to the dump's samples.csv
     * @return {@code [z[], metric[]]}, or null when the file cannot be parsed
     */
    public static double[][] parseSamplesCsv(Path samplesCsv) {
        if (samplesCsv == null || !Files.exists(samplesCsv)) {
            return null;
        }
        List<Double> zs = new ArrayList<>();
        List<Double> ms = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(samplesCsv, StandardCharsets.UTF_8);
            for (String line : lines) {
                String[] parts = line.split(",");
                if (parts.length < 5) {
                    continue;
                }
                try {
                    double zActual = Double.parseDouble(parts[3].trim());
                    double metric = Double.parseDouble(parts[4].trim());
                    if (!Double.isNaN(zActual) && !Double.isNaN(metric)) {
                        zs.add(zActual);
                        ms.add(metric);
                    }
                } catch (NumberFormatException ignored) {
                    // Header row or a torn line; skip it rather than abandoning the profile.
                }
            }
        } catch (IOException e) {
            logger.warn("Could not read {}: {}", samplesCsv, e.getMessage());
            return null;
        }
        if (zs.size() < 5) {
            return null;
        }
        double[] z = new double[zs.size()];
        double[] m = new double[ms.size()];
        for (int i = 0; i < z.length; i++) {
            z[i] = zs.get(i);
            m[i] = ms.get(i);
        }
        return new double[][] {z, m};
    }
}
