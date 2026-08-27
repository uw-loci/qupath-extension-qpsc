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
     * @param requiresTissueGate surfaces sit before focus, so the approach must gate on tissue
     *                          detection rather than committing to the first peak
     * @param safeZUm           the retracted Z the approach was measured from
     * @param focusZUm          the Z the focus peak was measured at. Stored because
     *                          {@code approachDistanceUm} is unsigned: the approach DIRECTION is
     *                          {@code sign(focusZUm - safeZUm)}, and getting it wrong sends the
     *                          scan away from the sample (useless) or, on a rig where retract is
     *                          the negative direction, further into it
     * @param approachDistanceUm measured distance from safe Z to focus (unsigned)
     * @param peakWidthUm       measured focus-peak FWHM; bounds how fast the approach may scan
     * @param falsePeakZs       Z of peaks before focus that the background scan confirmed are
     *                          surfaces rather than tissue
     * @param exposureMs        camera exposure the profile was measured at, or NaN if unknown
     * @param illumination      illumination intensity the profile was measured at, or NaN
     * @param reasons           why it failed, empty when it passed
     * @param timestamp         ISO-8601 instant the run completed
     */
    public record Record(
            String microscope,
            String modality,
            String objective,
            boolean usable,
            boolean requiresTissueGate,
            double safeZUm,
            double focusZUm,
            double approachDistanceUm,
            double peakWidthUm,
            List<Double> falsePeakZs,
            double exposureMs,
            double illumination,
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
        /**
         * Signed travel bound for the approach: how far, and in which direction, to scan from
         * the safe Z.
         *
         * <p>The sign is the whole point. It is what lets the server avoid inferring which way
         * retracts -- a determination that differs per rig and is dangerous to get wrong. It is
         * recovered from the two positions this run actually measured rather than from any
         * convention.
         *
         * @param headroomFactor multiplier on the measured distance; the validation measured ONE
         *                       slide, so some headroom is needed for slide-to-slide variation
         * @return signed bound in micrometers, or NaN when the record cannot supply one
         */
        public double signedApproachBoundUm(double headroomFactor) {
            if (Double.isNaN(focusZUm) || Double.isNaN(approachDistanceUm) || approachDistanceUm <= 0) {
                return Double.NaN;
            }
            double direction = Math.signum(focusZUm - safeZUm);
            if (direction == 0) {
                return Double.NaN;
            }
            return direction * approachDistanceUm * headroomFactor;
        }

        public String isStaleAgainst(Double currentSafeZUm) {
            return isStaleAgainst(currentSafeZUm, Double.NaN, Double.NaN);
        }

        /**
         * As {@link #isStaleAgainst(Double)}, but also checks the imaging conditions the profile
         * was measured under.
         *
         * <p>Exposure and illumination matter because the focus metric is an intensity spread.
         * Changing them rescales it, and a large enough increase saturates the sensor, which
         * flattens the metric and destroys the peak this record claims exists. The peak's
         * POSITION should not move, so a change is a warning to re-measure rather than proof the
         * record is wrong -- but silently trusting a profile taken at a tenth of the current
         * exposure is not defensible either.
         *
         * @param currentSafeZUm    safe Z currently configured
         * @param currentExposureMs current camera exposure, or NaN to skip the check
         * @param currentIllumination current illumination intensity, or NaN to skip
         * @return a human-readable reason, or null when the record still applies
         */
        public String isStaleAgainst(Double currentSafeZUm, double currentExposureMs, double currentIllumination) {
            if (currentSafeZUm == null) {
                return "stage.safe_z_um is no longer configured";
            }
            if (Math.abs(currentSafeZUm - safeZUm) > 1.0) {
                return String.format(
                        "stage.safe_z_um changed from %.1f to %.1f since this was measured", safeZUm, currentSafeZUm);
            }
            // Ratio, not absolute: 0.2 -> 0.4 ms matters as much as 9 -> 18 ms.
            if (!Double.isNaN(currentExposureMs) && !Double.isNaN(exposureMs) && exposureMs > 0) {
                double ratio = currentExposureMs / exposureMs;
                if (ratio < 0.5 || ratio > 2.0) {
                    return String.format(
                            "exposure changed from %.2f to %.2f ms since this was measured; the focus metric "
                                    + "scales with it, and enough of a change saturates the sensor and flattens "
                                    + "the peak",
                            exposureMs, currentExposureMs);
                }
            }
            if (!Double.isNaN(currentIllumination) && !Double.isNaN(illumination) && illumination > 0) {
                double ratio = currentIllumination / illumination;
                if (ratio < 0.5 || ratio > 2.0) {
                    return String.format(
                            "illumination changed from %.1f to %.1f since this was measured",
                            illumination, currentIllumination);
                }
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
    /**
     * The focus metric the server used, read from a dump's {@code manifest.json}.
     *
     * <p>Used to LABEL a plotted profile. The client cannot infer this: the metric is chosen
     * server-side per modality and objective, and showing a curve under the wrong metric name
     * would misrepresent the measurement.
     *
     * @param dumpRoot the directory the server reported
     * @return the metric name, or null when it cannot be read
     */
    public static String readDumpMetricName(Path dumpRoot) {
        if (dumpRoot == null) {
            return null;
        }
        List<Path> candidates = new ArrayList<>();
        candidates.add(dumpRoot.resolve("manifest.json"));
        try (var entries = Files.list(dumpRoot)) {
            entries.filter(Files::isDirectory)
                    .filter(d -> d.getFileName().toString().startsWith("attempt_"))
                    .sorted()
                    .forEach(d -> candidates.add(d.resolve("manifest.json")));
        } catch (IOException e) {
            logger.debug("Could not list {}: {}", dumpRoot, e.getMessage());
        }
        for (Path manifest : candidates) {
            if (!Files.exists(manifest)) {
                continue;
            }
            try {
                var map = GSON.fromJson(Files.readString(manifest, StandardCharsets.UTF_8), Map.class);
                Object name = (map == null) ? null : map.get("metric_name");
                if (name != null) {
                    return name.toString();
                }
            } catch (Exception e) {
                logger.debug("Could not read {}: {}", manifest, e.getMessage());
            }
        }
        return null;
    }

    /**
     * Finds the sample trace inside a server dump directory.
     *
     * <p>The layout depends on which scan path produced it. A profiling traverse writes
     * {@code samples.csv} directly into the dump root; the multi-attempt retry loop gives each
     * attempt its own {@code attempt_N/} subdirectory. Checking both means a dump from either
     * path is readable, including ones already on disk.
     *
     * <p>When several attempts are present the LAST is used: the retry loop walks toward the
     * peak, so the final attempt is the one nearest focus.
     *
     * @param dumpRoot the directory the server reported
     * @return the trace, or null when no readable one is found
     */
    public static double[][] parseDumpDirectory(Path dumpRoot) {
        if (dumpRoot == null) {
            return null;
        }
        double[][] flat = parseSamplesCsv(dumpRoot.resolve("samples.csv"));
        if (flat != null) {
            return flat;
        }
        try (var entries = Files.list(dumpRoot)) {
            java.util.List<Path> attempts = entries.filter(Files::isDirectory)
                    .filter(d -> d.getFileName().toString().startsWith("attempt_"))
                    .sorted()
                    .toList();
            for (int i = attempts.size() - 1; i >= 0; i--) {
                double[][] parsed = parseSamplesCsv(attempts.get(i).resolve("samples.csv"));
                if (parsed != null) {
                    logger.info("Read focus profile from {}", attempts.get(i).getFileName());
                    return parsed;
                }
            }
        } catch (IOException e) {
            logger.warn("Could not list dump directory {}: {}", dumpRoot, e.getMessage());
        }
        logger.warn("No usable samples.csv in {} (checked the root and any attempt_* subdirectories)", dumpRoot);
        return null;
    }

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
