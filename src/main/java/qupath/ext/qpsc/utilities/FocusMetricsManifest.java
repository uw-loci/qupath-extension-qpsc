package qupath.ext.qpsc.utilities;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * Java loader for {@code focus_metrics_manifest.yml} -- the single
 * source of truth for which focus metrics, validity checks, and
 * strategies exist in QPSC. Both Python (microscope_imageprocessing.
 * focus) and this class read the same manifest so the editor dropdown
 * cannot drift from what the runtime accepts.
 *
 * <p>Discovery order matches the Python loader:
 * <ol>
 *   <li>{@code $QPSC_CONFIG_DIR/focus_metrics_manifest.yml} (env override)</li>
 *   <li>Alongside the active {@code config_<scope>.yml} (configurations dir)</li>
 *   <li>The packaged copy bundled with this extension at
 *       {@code resources/focus/focus_metrics_manifest.yml} (last resort
 *       so a dev install with no QPSC_CONFIG_DIR still works)</li>
 * </ol>
 *
 * <p>The class is a singleton: load once per active config dir, cache,
 * provide accessors. Call {@link #refresh(Path)} after the active
 * microscope config changes to re-read.
 */
public final class FocusMetricsManifest {

    private static final Logger logger = LoggerFactory.getLogger(FocusMetricsManifest.class);
    private static final String MANIFEST_FILENAME = "focus_metrics_manifest.yml";

    private static FocusMetricsManifest INSTANCE;
    private static Path cachedConfigDir;

    private final int schemaVersion;
    private final Map<String, MetricSpec> metrics;
    private final Map<String, String> removedAliases; // old -> canonical
    private final Map<String, ValidityCheckSpec> validityChecks;
    private final Map<String, StrategySpec> strategies;
    private final Map<String, String> modalityDefaults; // modality (lowercase) -> metric name
    private final Path sourcePath;

    private FocusMetricsManifest(
            int schemaVersion,
            Map<String, MetricSpec> metrics,
            Map<String, String> removedAliases,
            Map<String, ValidityCheckSpec> validityChecks,
            Map<String, StrategySpec> strategies,
            Map<String, String> modalityDefaults,
            Path sourcePath) {
        this.schemaVersion = schemaVersion;
        this.metrics = Collections.unmodifiableMap(metrics);
        this.removedAliases = Collections.unmodifiableMap(removedAliases);
        this.validityChecks = Collections.unmodifiableMap(validityChecks);
        this.strategies = Collections.unmodifiableMap(strategies);
        this.modalityDefaults = Collections.unmodifiableMap(modalityDefaults);
        this.sourcePath = sourcePath;
    }

    /** Sensitivity bucket displayed in the GUI dropdown. */
    public enum Group { RECOMMENDED, ADVANCED, SPECIAL, UNKNOWN;
        public static Group fromString(String s) {
            if (s == null) return UNKNOWN;
            switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "recommended": return RECOMMENDED;
                case "advanced": return ADVANCED;
                case "special": return SPECIAL;
                default: return UNKNOWN;
            }
        }
    }

    /** A single metric entry from the manifest. */
    public static final class MetricSpec {
        public final String name;
        public final Group group;
        public final String badge;
        public final String bestFor;
        public final String avoidWhen;
        public final String requires;
        public final List<String> supportedPaths;
        public final String role; // "fallback" for p98_p2; null otherwise
        /**
         * Optical modalities the metric is physically meaningful for
         * (e.g. ["brightfield","ppm"]). Empty list = "any modality" --
         * the manifest treats absence of the field as unrestricted.
         * Lowercase canonical names; the matcher in
         * {@link #isValidForModality} normalises casing and accepts
         * common aliases (bf, fl, polarized, ...).
         */
        public final List<String> validModalities;
        /**
         * Smallest objective magnification at which the metric is
         * reliable. Null = unrestricted. Used by the GUI to surface a
         * "[20x+]" badge next to the metric name when the active
         * objective is below this threshold; never enforced as a hard
         * block (operators may have a special-case where the warning
         * is wrong).
         */
        public final Double minMagnification;

        MetricSpec(String name, Group group, String badge, String bestFor,
                   String avoidWhen, String requires, List<String> supportedPaths,
                   String role, List<String> validModalities, Double minMagnification) {
            this.name = name;
            this.group = group;
            this.badge = badge;
            this.bestFor = bestFor == null ? "" : bestFor;
            this.avoidWhen = avoidWhen == null ? "" : avoidWhen;
            this.requires = requires;
            this.supportedPaths = Collections.unmodifiableList(supportedPaths);
            this.role = role;
            this.validModalities = validModalities == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(validModalities);
            this.minMagnification = minMagnification;
        }

        /** True if this metric can be selected for the streaming AF code path. */
        public boolean supportsStreaming() { return supportedPaths.contains("streaming"); }
        /** True if this metric can be selected for the standard / sweep AF code path. */
        public boolean supportsStandard() { return supportedPaths.contains("standard"); }
        /** True if this metric can be wired up as a strategy's score_metric. */
        public boolean supportsStrategy() { return supportedPaths.contains("strategy"); }

        /**
         * Check whether this metric is appropriate for the given
         * modality key (the same string used in the manifest's
         * modality_defaults map and in modality binding rows).
         *
         * <p>Returns true when the manifest declares no restriction
         * (empty {@link #validModalities}), when the modality argument
         * is null/blank (caller can't tell), or when the canonical
         * form of the modality matches one of the listed entries.
         *
         * <p>The match is canonicalised: short aliases like {@code bf}
         * and {@code fl} expand to {@code brightfield} / {@code
         * fluorescence}; {@code polarized}, {@code ppm_*} resolve to
         * {@code ppm}; {@code wf}, {@code widefield}, {@code lsm},
         * {@code shg}, {@code 1p}, {@code 2p} all map to {@code
         * fluorescence}; {@code dark_field} stays distinct.
         */
        public boolean isValidForModality(String modality) {
            if (validModalities.isEmpty()) return true;
            if (modality == null || modality.isBlank()) return true;
            String canon = canonicalModality(modality);
            for (String allowed : validModalities) {
                if (canonicalModality(allowed).equals(canon)) return true;
            }
            return false;
        }

        /**
         * Check whether this metric is appropriate at the given
         * objective nominal magnification. Always true if the
         * manifest sets no {@link #minMagnification} or the caller
         * passes a non-positive value (unknown).
         */
        public boolean isValidForMagnification(double nominalMag) {
            if (minMagnification == null) return true;
            if (Double.isNaN(nominalMag) || nominalMag <= 0.0) return true;
            return nominalMag >= minMagnification;
        }
    }

    /**
     * Reduce a modality string (or alias) to one of the canonical
     * buckets {@code brightfield}, {@code ppm}, {@code fluorescence},
     * {@code dark_field}, {@code other}. Lowercase, prefix-aware. The
     * manifest's {@code valid_modalities} entries are normalised
     * through this same function so that
     * {@code valid_modalities: [brightfield]} matches a binding key
     * like {@code bf_dia}, {@code BF_IF}, {@code Brightfield}, etc.
     *
     * <p>Bucket assignments mirror {@code modality_defaults} in the
     * manifest: any modality whose default is {@code vollath_f5} is
     * treated as fluorescence here. {@code shg} is separately mapped
     * to {@code dark_field} only when explicitly named that way; in
     * the default mapping it counts as fluorescence (it ships with
     * vollath_f5 as the streaming default).
     */
    public static String canonicalModality(String modality) {
        if (modality == null) return "other";
        String s = modality.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return "other";
        if (s.equals("brightfield") || s.equals("bf") || s.startsWith("bf_")
                || s.startsWith("brightfield_") || s.equals("dia")
                || s.equals("transmission") || s.equals("trans")) {
            return "brightfield";
        }
        if (s.equals("ppm") || s.startsWith("ppm_") || s.equals("polarized")
                || s.equals("pol") || s.equals("polarised")) {
            return "ppm";
        }
        if (s.equals("dark_field") || s.equals("darkfield") || s.equals("df")) {
            return "dark_field";
        }
        if (s.equals("fluorescence") || s.equals("fluorescent") || s.equals("fl")
                || s.startsWith("fl_") || s.equals("widefield") || s.equals("wf")
                || s.startsWith("wf_") || s.equals("laser_scanning")
                || s.equals("lsm") || s.equals("confocal") || s.equals("shg")
                || s.equals("multiphoton") || s.equals("1p") || s.equals("2p")) {
            return "fluorescence";
        }
        return s;
    }

    /** A validity-check entry. Params drive the typed editor in the GUI. */
    public static final class ValidityCheckSpec {
        public final String name;
        public final String description;
        public final List<ParamSpec> params;
        ValidityCheckSpec(String name, String description, List<ParamSpec> params) {
            this.name = name;
            this.description = description == null ? "" : description;
            this.params = Collections.unmodifiableList(params);
        }
    }

    /** A single param of a validity check. {@code length} is non-null for list types. */
    public static final class ParamSpec {
        public final String name;
        public final String type; // float | int | list_of_float
        public final Object defaultValue;
        public final Double rangeMin; // nullable
        public final Double rangeMax; // nullable
        public final Integer length; // nullable, for list types

        ParamSpec(String name, String type, Object defaultValue,
                  Double rangeMin, Double rangeMax, Integer length) {
            this.name = name;
            this.type = type;
            this.defaultValue = defaultValue;
            this.rangeMin = rangeMin;
            this.rangeMax = rangeMax;
            this.length = length;
        }
    }

    /** A strategy entry from the manifest. */
    public static final class StrategySpec {
        public final String name;
        public final String description;
        public final String scoreMetricDefault;
        public final String validityCheck;
        public final String onFailure;
        StrategySpec(String name, String description, String scoreMetricDefault,
                     String validityCheck, String onFailure) {
            this.name = name;
            this.description = description == null ? "" : description;
            this.scoreMetricDefault = scoreMetricDefault;
            this.validityCheck = validityCheck;
            this.onFailure = onFailure;
        }
    }

    // --------------- accessors ---------------

    public int getSchemaVersion() { return schemaVersion; }
    public Path getSourcePath() { return sourcePath; }
    public Map<String, MetricSpec> getMetrics() { return metrics; }
    public Map<String, String> getRemovedAliases() { return removedAliases; }
    public Map<String, ValidityCheckSpec> getValidityChecks() { return validityChecks; }
    public Map<String, StrategySpec> getStrategies() { return strategies; }
    public Map<String, String> getModalityDefaults() { return modalityDefaults; }

    /** All metrics in the given group, in manifest order. */
    public List<MetricSpec> metricsByGroup(Group group) {
        List<MetricSpec> out = new ArrayList<>();
        for (MetricSpec m : metrics.values()) {
            if (m.group == group) out.add(m);
        }
        return out;
    }

    /** All canonical metric names, in manifest order. */
    public List<String> metricNames() { return new ArrayList<>(metrics.keySet()); }

    /** Lookup the canonical replacement for an old/legacy metric name (e.g. "volath5" -> "vollath_f5"). */
    public Optional<String> aliasFor(String oldName) {
        if (oldName == null) return Optional.empty();
        return Optional.ofNullable(removedAliases.get(oldName.trim().toLowerCase(Locale.ROOT)));
    }

    /** Modality-default metric name (case-insensitive lookup); empty if unknown. */
    public Optional<String> modalityDefault(String modality) {
        if (modality == null) return Optional.empty();
        return Optional.ofNullable(modalityDefaults.get(modality.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * Resolve which metric will actually be used given the (modality,
     * per-objective YAML score_metric) pair. Mirrors the Python
     * resolution order in streaming_focus._resolve_metric_name and
     * the standard AF path.
     */
    public String resolveEffectiveMetric(String modality, String yamlScoreMetric, String fallback) {
        if (yamlScoreMetric != null && !yamlScoreMetric.isEmpty()
                && !"none".equalsIgnoreCase(yamlScoreMetric)) {
            String key = yamlScoreMetric.trim().toLowerCase(Locale.ROOT);
            if (metrics.containsKey(key)) return key;
            // Legacy alias -- effective name is the canonical replacement.
            String canon = removedAliases.get(key);
            if (canon != null) return canon;
        }
        return modalityDefault(modality).orElse(fallback != null ? fallback : "tenengrad");
    }

    // --------------- discovery + loader ---------------

    /**
     * Get the cached manifest, loading from disk if needed. Pass the
     * directory of the active microscope config so per-scope overrides
     * win; pass null to use only env / packaged.
     */
    public static synchronized FocusMetricsManifest get(Path configDir) {
        if (INSTANCE == null || !java.util.Objects.equals(cachedConfigDir, configDir)) {
            INSTANCE = load(configDir);
            cachedConfigDir = configDir;
        }
        return INSTANCE;
    }

    /** Drop the cache and reload on next get(). */
    public static synchronized void refresh(Path configDir) {
        INSTANCE = null;
        cachedConfigDir = null;
        get(configDir);
    }

    /** Force-reload bypassing the cache. */
    public static synchronized FocusMetricsManifest load(Path configDir) {
        for (Path candidate : candidatePaths(configDir)) {
            if (candidate == null) continue;
            File f = candidate.toFile();
            if (!f.isFile()) continue;
            try (InputStream in = new FileInputStream(f)) {
                Yaml yaml = new Yaml();
                Object loaded = yaml.load(in);
                if (loaded instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> doc = (Map<String, Object>) loaded;
                    FocusMetricsManifest m = parse(doc, candidate);
                    logger.info("Loaded focus manifest from {} ({} metrics, {} strategies)",
                            candidate, m.metrics.size(), m.strategies.size());
                    return m;
                }
            } catch (IOException e) {
                logger.warn("Could not read focus manifest {}: {}", candidate, e.getMessage());
            } catch (RuntimeException e) {
                logger.warn("Manifest at {} did not parse: {}", candidate, e.getMessage());
            }
        }
        // Last resort: empty manifest so the editor still works (degraded).
        logger.warn("No focus_metrics_manifest.yml found in any candidate path; "
                + "GUI will fall back to a minimal hardcoded list");
        return emptyManifest();
    }

    private static List<Path> candidatePaths(Path configDir) {
        List<Path> out = new ArrayList<>();
        String env = System.getenv("QPSC_CONFIG_DIR");
        if (env != null && !env.isEmpty()) out.add(Paths.get(env, MANIFEST_FILENAME));
        if (configDir != null) out.add(configDir.resolve(MANIFEST_FILENAME));
        // Packaged copy bundled with the extension (optional).
        try {
            Path pkg = Paths.get(System.getProperty("user.dir"), "src", "main",
                    "resources", "focus", MANIFEST_FILENAME);
            if (Files.exists(pkg)) out.add(pkg);
        } catch (RuntimeException ignored) {
            // best effort
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static FocusMetricsManifest parse(Map<String, Object> doc, Path source) {
        int schemaVersion = doc.get("schema_version") instanceof Number
                ? ((Number) doc.get("schema_version")).intValue() : 1;

        Map<String, MetricSpec> metrics = new LinkedHashMap<>();
        Object metricsObj = doc.get("metrics");
        if (metricsObj instanceof List) {
            for (Object e : (List<Object>) metricsObj) {
                if (!(e instanceof Map)) continue;
                Map<String, Object> entry = (Map<String, Object>) e;
                String name = String.valueOf(entry.get("name"));
                if (name == null || "null".equals(name)) continue;
                List<String> paths = entry.get("supported_paths") instanceof List
                        ? ((List<Object>) entry.get("supported_paths")).stream()
                            .map(Object::toString).collect(Collectors.toList())
                        : Collections.emptyList();
                List<String> validModalities = entry.get("valid_modalities") instanceof List
                        ? ((List<Object>) entry.get("valid_modalities")).stream()
                            .map(Object::toString).collect(Collectors.toList())
                        : Collections.emptyList();
                Double minMag = toDoubleOrNull(entry.get("min_magnification"));
                metrics.put(name, new MetricSpec(
                        name,
                        Group.fromString((String) entry.get("group")),
                        (String) entry.get("badge"),
                        (String) entry.get("best_for"),
                        (String) entry.get("avoid_when"),
                        (String) entry.get("requires"),
                        paths,
                        (String) entry.get("role"),
                        validModalities,
                        minMag));
            }
        }

        Map<String, String> aliases = new LinkedHashMap<>();
        Object aliasObj = doc.get("removed_aliases");
        if (aliasObj instanceof Map) {
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) aliasObj).entrySet()) {
                aliases.put(String.valueOf(e.getKey()).toLowerCase(Locale.ROOT),
                        String.valueOf(e.getValue()));
            }
        }

        Map<String, ValidityCheckSpec> vchecks = new LinkedHashMap<>();
        Object vObj = doc.get("validity_checks");
        if (vObj instanceof List) {
            for (Object e : (List<Object>) vObj) {
                if (!(e instanceof Map)) continue;
                Map<String, Object> entry = (Map<String, Object>) e;
                String name = String.valueOf(entry.get("name"));
                List<ParamSpec> params = new ArrayList<>();
                Object paramsObj = entry.get("params");
                if (paramsObj instanceof Map) {
                    for (Map.Entry<Object, Object> p : ((Map<Object, Object>) paramsObj).entrySet()) {
                        String pname = String.valueOf(p.getKey());
                        Map<String, Object> pdef = (Map<String, Object>) p.getValue();
                        Double rmin = null, rmax = null;
                        Object rng = pdef.get("range");
                        if (rng instanceof List && ((List<?>) rng).size() == 2) {
                            rmin = toDoubleOrNull(((List<?>) rng).get(0));
                            rmax = toDoubleOrNull(((List<?>) rng).get(1));
                        }
                        Integer length = pdef.get("length") instanceof Number
                                ? ((Number) pdef.get("length")).intValue() : null;
                        params.add(new ParamSpec(
                                pname,
                                (String) pdef.get("type"),
                                pdef.get("default"),
                                rmin, rmax, length));
                    }
                }
                vchecks.put(name, new ValidityCheckSpec(
                        name, (String) entry.get("description"), params));
            }
        }

        Map<String, StrategySpec> strategies = new LinkedHashMap<>();
        Object sObj = doc.get("strategies");
        if (sObj instanceof List) {
            for (Object e : (List<Object>) sObj) {
                if (!(e instanceof Map)) continue;
                Map<String, Object> entry = (Map<String, Object>) e;
                String name = String.valueOf(entry.get("name"));
                strategies.put(name, new StrategySpec(
                        name,
                        (String) entry.get("description"),
                        (String) entry.get("score_metric_default"),
                        (String) entry.get("validity_check"),
                        (String) entry.getOrDefault("on_failure", "defer")));
            }
        }

        Map<String, String> modality = new TreeMap<>();
        Object mObj = doc.get("modality_defaults");
        if (mObj instanceof Map) {
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) mObj).entrySet()) {
                modality.put(String.valueOf(e.getKey()).toLowerCase(Locale.ROOT),
                        String.valueOf(e.getValue()));
            }
        }

        return new FocusMetricsManifest(
                schemaVersion, metrics, aliases, vchecks, strategies, modality, source);
    }

    private static Double toDoubleOrNull(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o == null) return null;
        try { return Double.parseDouble(o.toString()); }
        catch (NumberFormatException e) { return null; }
    }

    private static FocusMetricsManifest emptyManifest() {
        // Minimal degraded manifest: lets the GUI render even when no
        // file is found. Names match the canonical set the runtime
        // dispatcher accepts so dropdown picks won't immediately error.
        Map<String, MetricSpec> m = new LinkedHashMap<>();
        for (String n : List.of("tenengrad", "laplacian_variance", "brenner_gradient",
                "normalized_variance", "vollath_f5", "sobel", "p98_p2",
                "robust_sharpness_metric", "hybrid_sharpness_metric", "none")) {
            m.put(n, new MetricSpec(n, Group.UNKNOWN, "na", "", "", "numpy",
                    List.of("streaming", "standard", "strategy"), null,
                    Collections.emptyList(), null));
        }
        return new FocusMetricsManifest(0, m, Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), null);
    }

    /**
     * Grouping helper for the GUI dropdown: returns metrics ordered as
     * Recommended, Advanced, Special, Unknown so the most likely picks
     * appear at the top.
     */
    public List<MetricSpec> metricsForDropdown() {
        List<MetricSpec> out = new ArrayList<>();
        for (Group g : new Group[]{Group.RECOMMENDED, Group.ADVANCED, Group.SPECIAL, Group.UNKNOWN}) {
            for (MetricSpec m : metrics.values()) {
                if (m.group == g) out.add(m);
            }
        }
        return out;
    }

    /** Names of all groups present in the dropdown order. */
    public Set<Group> groupsPresent() {
        return metrics.values().stream().map(s -> s.group).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /**
     * Generate the header comment block written above the autofocus
     * YAML on save. Replaces the previously hardcoded block in
     * {@code AutofocusEditorWorkflow.saveAutofocusSettings} so the
     * comment can never drift from the real metric / strategy /
     * modality-default vocabulary the runtime accepts.
     *
     * <p>Each line is prefixed with {@code "# "} and the block ends in
     * a single blank line so it concatenates cleanly with the YAML body.
     */
    public String headerCommentBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("# ========== AUTOFOCUS CONFIGURATION ==========\n");
        sb.append("# Per-objective autofocus parameters and per-modality strategy bindings.\n");
        sb.append("# Vocabulary (metric / strategy / validity-check names) is sourced from\n");
        sb.append("# focus_metrics_manifest.yml; this header is regenerated on every save.\n");
        sb.append("#\n");
        sb.append("# OBJECTIVE FIELDS:\n");
        sb.append("#   n_steps              -- Z positions sampled for the standard sweep\n");
        sb.append("#   search_range_um      -- total Z range searched in micrometres\n");
        sb.append("#   interp_strength      -- interpolation density factor (typical 50-200)\n");
        sb.append("#   interp_kind          -- 'linear', 'quadratic', or 'cubic'\n");
        sb.append("#   sweep_range_um       -- in-acquisition drift-check Z range (default 10)\n");
        sb.append("#   sweep_n_steps        -- in-acquisition drift-check Z samples (default 6)\n");
        sb.append("#   n_tiles              -- run AF every N tiles; sets af_min_distance\n");
        sb.append("#   score_metric         -- focus metric (see AVAILABLE SCORE METRICS below)\n");
        sb.append("#   gap_index_multiplier -- force AF after (this x n_tiles) positions w/o one\n");
        sb.append("#   gap_spatial_multiplier -- force AF beyond (this x af_min_distance)\n");
        sb.append("#\n");
        sb.append("# AVAILABLE SCORE METRICS:\n");
        for (Group g : new Group[]{Group.RECOMMENDED, Group.ADVANCED, Group.SPECIAL}) {
            List<MetricSpec> entries = metricsByGroup(g);
            if (entries.isEmpty()) continue;
            sb.append("#   [").append(g.name().toLowerCase(Locale.ROOT)).append("]\n");
            for (MetricSpec m : entries) {
                String role = m.role != null ? "  (" + m.role + ")" : "";
                sb.append("#     ").append(m.name).append(role);
                List<String> tags = new ArrayList<>();
                if (!m.validModalities.isEmpty()) {
                    tags.add(String.join("/", m.validModalities) + " only");
                }
                if (m.minMagnification != null) {
                    tags.add(String.format(Locale.ROOT, "%.0fx+", m.minMagnification));
                }
                if (!tags.isEmpty()) {
                    sb.append("  [").append(String.join(", ", tags)).append("]");
                }
                sb.append("\n");
                if (!m.bestFor.isEmpty()) {
                    sb.append("#       best for : ").append(oneLine(m.bestFor)).append("\n");
                }
                if (!m.avoidWhen.isEmpty()) {
                    sb.append("#       avoid    : ").append(oneLine(m.avoidWhen)).append("\n");
                }
            }
        }
        if (!strategies.isEmpty()) {
            sb.append("#\n");
            sb.append("# STRATEGIES (referenced by per-modality bindings):\n");
            for (StrategySpec s : strategies.values()) {
                sb.append("#   ").append(s.name)
                        .append(" -> score_metric_default=").append(s.scoreMetricDefault)
                        .append(", validity_check=").append(s.validityCheck)
                        .append(", on_failure=").append(s.onFailure).append("\n");
            }
        }
        if (!modalityDefaults.isEmpty()) {
            sb.append("#\n");
            sb.append("# MODALITY DEFAULTS (used when no per-objective score_metric is set):\n");
            for (Map.Entry<String, String> e : modalityDefaults.entrySet()) {
                sb.append("#   ").append(e.getKey()).append(" -> ").append(e.getValue()).append("\n");
            }
        }
        if (!removedAliases.isEmpty()) {
            sb.append("#\n");
            sb.append("# RENAMED METRICS (loader rejects the old name):\n");
            for (Map.Entry<String, String> e : removedAliases.entrySet()) {
                sb.append("#   ").append(e.getKey()).append(" -> ").append(e.getValue()).append("\n");
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    private static String oneLine(String s) {
        return s.replace("\n", " ").replace("\r", " ").trim();
    }
}
