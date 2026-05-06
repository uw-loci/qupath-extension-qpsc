package qupath.ext.qpsc.controller;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.ui.components.ObjectiveSelector;
import qupath.ext.qpsc.utilities.DocumentationHelper;
import qupath.ext.qpsc.utilities.FocusMetricsManifest;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.dialogs.Dialogs;

/**
 * AutofocusEditorWorkflow - Configuration editor for per-objective autofocus settings
 *
 * <p>This workflow provides a GUI for editing autofocus parameters stored in autofocus_{microscope}.yml.
 * The autofocus configuration is separate from the main microscope config and contains three parameters
 * per objective:
 * <ul>
 *   <li>n_steps: Number of Z positions to sample during autofocus</li>
 *   <li>search_range_um: Total Z range to search in micrometers</li>
 *   <li>n_tiles: Spatial frequency - autofocus runs every N tiles during acquisition</li>
 *   <li>gap_index_multiplier: Safety-net force-AF after N x n_tiles positions with no AF</li>
 *   <li>gap_spatial_multiplier: Safety-net force-AF when spatial distance > M x af_min_distance</li>
 * </ul>
 *
 * <p>Key features:
 * <ul>
 *   <li>Reads objectives from main microscope config (respects preference setting)</li>
 *   <li>Loads existing autofocus settings if autofocus_{microscope}.yml exists</li>
 *   <li>"Write to file" button saves immediately but keeps dialog open</li>
 *   <li>"OK" button saves (if changed) and closes dialog</li>
 *   <li>"Cancel" button closes without saving unsaved changes</li>
 *   <li>Parameter validation with warnings for extreme values</li>
 * </ul>
 *
 * @author Mike Nelson
 */
public class AutofocusEditorWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(AutofocusEditorWorkflow.class);

    /**
     * Autofocus settings for a single objective
     */
    private static class AutofocusSettings {
        // Standard autofocus parameters
        String objective;
        int nSteps;
        double searchRangeUm;
        int nTiles;
        int interpStrength;
        String interpKind;
        String scoreMetric;
        double textureThreshold;
        double tissueAreaThreshold;

        // Sweep drift check parameters
        double sweepRangeUm;
        int sweepNSteps;
        int edgeRetries;

        // AF safety-net gap multipliers (drive force-AF when scheduled AF is sparse)
        int gapIndexMultiplier;
        double gapSpatialMultiplier;

        // Standard-AF fallback metric. Default true: p98_p2 is always
        // computed alongside the primary metric, and the system falls
        // back to it if the primary's peak validation fails. Operators
        // can opt out per objective by writing
        // {@code p98_p2_fallback_enabled: false} in the YAML; the field
        // is additive (omitted in legacy files = true).
        boolean p98P2FallbackEnabled = true;

        AutofocusSettings(
                String objective,
                int nSteps,
                double searchRangeUm,
                int nTiles,
                int interpStrength,
                String interpKind,
                String scoreMetric,
                double textureThreshold,
                double tissueAreaThreshold,
                double sweepRangeUm,
                int sweepNSteps,
                int edgeRetries,
                int gapIndexMultiplier,
                double gapSpatialMultiplier) {
            this.objective = objective;
            this.nSteps = nSteps;
            this.searchRangeUm = searchRangeUm;
            this.nTiles = nTiles;
            this.interpStrength = interpStrength;
            this.interpKind = interpKind;
            this.scoreMetric = scoreMetric;
            this.textureThreshold = textureThreshold;
            this.tissueAreaThreshold = tissueAreaThreshold;
            this.sweepRangeUm = sweepRangeUm;
            this.sweepNSteps = sweepNSteps;
            this.edgeRetries = edgeRetries;
            this.gapIndexMultiplier = gapIndexMultiplier;
            this.gapSpatialMultiplier = gapSpatialMultiplier;
        }

        // Validation with detailed feedback
        List<String> validate() {
            List<String> warnings = new ArrayList<>();

            if (nSteps <= 0) {
                warnings.add("n_steps must be positive");
            } else if (nSteps > 50) {
                warnings.add("n_steps > 50 may be unnecessarily slow (typical range: 5-20)");
            }

            if (searchRangeUm <= 0) {
                warnings.add("search_range_um must be positive");
            } else if (searchRangeUm > 1000) {
                warnings.add("search_range_um > 1000 um is very large (typical range: 10-50 um)");
            }

            if (nTiles <= 0) {
                warnings.add("n_tiles must be positive");
            } else if (nTiles > 20) {
                warnings.add("n_tiles > 20 may cause infrequent autofocus (typical range: 3-10)");
            }

            if (interpStrength <= 0) {
                warnings.add("interp_strength must be positive");
            } else if (interpStrength > 1000) {
                warnings.add("interp_strength > 1000 may be unnecessarily high (typical: 50-200)");
            }

            if (interpKind == null || interpKind.isEmpty()) {
                warnings.add("interp_kind must be specified");
            }

            if (scoreMetric == null || scoreMetric.isEmpty()) {
                warnings.add("score_metric must be specified");
            }

            if (textureThreshold <= 0) {
                warnings.add("texture_threshold must be positive");
            } else if (textureThreshold > 0.1) {
                warnings.add("texture_threshold > 0.1 is very high (typical range: 0.005-0.030)");
            }

            if (tissueAreaThreshold <= 0) {
                warnings.add("tissue_area_threshold must be positive");
            } else if (tissueAreaThreshold > 0.5) {
                warnings.add("tissue_area_threshold > 0.5 is very high (typical range: 0.05-0.30)");
            }

            // Sweep drift check validation
            if (sweepRangeUm <= 0) {
                warnings.add("sweep_range_um must be positive");
            } else if (sweepRangeUm > 50) {
                warnings.add("sweep_range_um > 50 um is very large (typical range: 6-20 um)");
            }

            if (sweepNSteps < 3) {
                warnings.add("sweep_n_steps must be at least 3 for peak detection");
            } else if (sweepNSteps > 20) {
                warnings.add("sweep_n_steps > 20 may be unnecessarily slow (typical range: 4-8)");
            }

            if (edgeRetries < 0) {
                warnings.add("edge_retries must be >= 0");
            } else if (edgeRetries > 3) {
                warnings.add("edge_retries > 3 rarely helps and adds ~1s per AF point per retry");
            }

            // AF safety-net gap multipliers
            if (gapIndexMultiplier < 1) {
                warnings.add("gap_index_multiplier must be >= 1");
            } else if (gapIndexMultiplier > 10) {
                warnings.add("gap_index_multiplier > 10 disables the index safety net (typical range: 1-5)");
            }

            if (gapSpatialMultiplier <= 0) {
                warnings.add("gap_spatial_multiplier must be positive");
            } else if (gapSpatialMultiplier > 5.0) {
                warnings.add(
                        "gap_spatial_multiplier > 5.0 effectively disables the spatial safety net (typical range: 1.0-3.0)");
            }

            return warnings;
        }
    }

    /**
     * Hardcoded fallback list. Used only when {@link FocusMetricsManifest}
     * fails to load (degraded path). Live combos source from the manifest
     * so the GUI dropdown matches the runtime registry exactly.
     */
    private static final String[] SCORE_METRICS = {
        "tenengrad", "laplacian_variance", "brenner_gradient", "normalized_variance",
        "vollath_f5", "sobel", "p98_p2", "robust_sharpness_metric",
        "hybrid_sharpness_metric", "none"
    };

    /**
     * Build the manifest-sourced score-metric ComboBox. Items are
     * grouped (Recommended, Advanced, Special) with non-selectable
     * separators; each metric line shows constraint badges sourced
     * from the manifest's {@code valid_modalities} / {@code
     * min_magnification} fields so users see at a glance which
     * picks are sample-restricted; tooltip is one short sentence;
     * the caller wires up a "Help me pick" button via {@link
     * #showMetricHelpDialog}.
     */
    private static ComboBox<String> buildScoreMetricCombo(
            String currentValue, FocusMetricsManifest manifest) {
        ComboBox<String> combo = new ComboBox<>();
        FocusMetricsManifest.Group lastGroup = null;
        for (FocusMetricsManifest.MetricSpec m : manifest.metricsForDropdown()) {
            if (lastGroup != null && lastGroup != m.group) {
                combo.getItems().add("--- " + groupLabel(m.group) + " ---");
            } else if (lastGroup == null) {
                combo.getItems().add("--- " + groupLabel(m.group) + " ---");
            }
            combo.getItems().add(m.name);
            lastGroup = m.group;
        }
        combo.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                    setDisable(false);
                    return;
                }
                if (item.startsWith("---")) {
                    setText(item);
                    setDisable(true);
                    setStyle("-fx-font-weight: bold; -fx-text-fill: gray;");
                    return;
                }
                setDisable(false);
                FocusMetricsManifest.MetricSpec spec = manifest.getMetrics().get(item);
                String badges = spec == null ? "" : metricConstraintBadges(spec);
                setText(badges.isEmpty() ? item : item + "  " + badges);
                setStyle(badges.isEmpty() ? "" : "-fx-text-fill: -fx-text-base-color;");
            }
        });
        combo.setValue(currentValue);
        combo.setPrefWidth(260);
        return combo;
    }

    /**
     * One-line constraint summary for a manifest metric: e.g.
     * {@code "[BF/PPM only]"} or {@code "[20x+]"}, suitable for
     * appending to a dropdown row. Returns an empty string when the
     * metric has no declared constraints.
     */
    private static String metricConstraintBadges(FocusMetricsManifest.MetricSpec m) {
        List<String> tags = new ArrayList<>();
        if (!m.validModalities.isEmpty()) {
            List<String> short_ = new ArrayList<>();
            for (String mod : m.validModalities) {
                String canon = FocusMetricsManifest.canonicalModality(mod);
                switch (canon) {
                    case "brightfield": short_.add("BF"); break;
                    case "ppm":         short_.add("PPM"); break;
                    case "fluorescence":short_.add("FL"); break;
                    case "dark_field":  short_.add("DF"); break;
                    default:            short_.add(mod); break;
                }
            }
            tags.add(String.join("/", short_) + " only");
        }
        if (m.minMagnification != null) {
            tags.add(String.format(Locale.ROOT, "%.0fx+", m.minMagnification));
        }
        if ("fallback".equalsIgnoreCase(m.role)) {
            tags.add("auto-fallback");
        }
        if (tags.isEmpty()) return "";
        return "[" + String.join(", ", tags) + "]";
    }

    private static String groupLabel(FocusMetricsManifest.Group g) {
        switch (g) {
            case RECOMMENDED: return "Recommended";
            case ADVANCED:    return "Advanced";
            case SPECIAL:     return "Special";
            default:          return "Other";
        }
    }

    /**
     * "Help me pick" dialog: renders the manifest's best_for /
     * avoid_when text so users see the same guidance the YAML carries.
     * Replaces the wall-of-text inline tooltip that drifted from the
     * actual metric set.
     */
    private static void showMetricHelpDialog(FocusMetricsManifest manifest) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Focus metric guide");
        alert.setHeaderText("Pick a metric for your sample");
        VBox content = new VBox(8);
        content.setPadding(new Insets(8));
        for (FocusMetricsManifest.Group g : new FocusMetricsManifest.Group[]{
                FocusMetricsManifest.Group.RECOMMENDED,
                FocusMetricsManifest.Group.ADVANCED,
                FocusMetricsManifest.Group.SPECIAL}) {
            List<FocusMetricsManifest.MetricSpec> bucket = manifest.metricsByGroup(g);
            if (bucket.isEmpty()) continue;
            Label header = new Label(groupLabel(g));
            header.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
            content.getChildren().add(header);
            for (FocusMetricsManifest.MetricSpec m : bucket) {
                VBox card = new VBox(2);
                card.setPadding(new Insets(2, 0, 6, 12));
                String constraintBadge = metricConstraintBadges(m);
                String headerLine = m.name + "  [" + m.badge + "]"
                        + (constraintBadge.isEmpty() ? "" : "  " + constraintBadge);
                Label name = new Label(headerLine);
                name.setStyle("-fx-font-weight: bold; -fx-font-family: monospace;");
                card.getChildren().add(name);
                if (!m.validModalities.isEmpty() || m.minMagnification != null) {
                    StringBuilder cs = new StringBuilder();
                    if (!m.validModalities.isEmpty()) {
                        cs.append("Restricted to: ")
                                .append(String.join(", ", m.validModalities));
                    }
                    if (m.minMagnification != null) {
                        if (cs.length() > 0) cs.append("   |   ");
                        cs.append("Minimum magnification: ")
                                .append(String.format(Locale.ROOT, "%.0fx",
                                        m.minMagnification));
                    }
                    Label cl = new Label(cs.toString());
                    cl.setWrapText(true);
                    cl.setStyle("-fx-text-fill: #6A6A6A; -fx-font-size: 11px; "
                            + "-fx-font-style: italic;");
                    card.getChildren().add(cl);
                }
                if (!m.bestFor.isEmpty()) {
                    Label bf = new Label("Best for: " + m.bestFor.replace("\n", " "));
                    bf.setWrapText(true);
                    card.getChildren().add(bf);
                }
                if (!m.avoidWhen.isEmpty()) {
                    Label aw = new Label("Avoid: " + m.avoidWhen.replace("\n", " "));
                    aw.setWrapText(true);
                    aw.setStyle("-fx-text-fill: #B0463F;");
                    card.getChildren().add(aw);
                }
                content.getChildren().add(card);
            }
        }
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(620, 480);
        alert.getDialogPane().setContent(scroll);
        alert.getDialogPane().setMinWidth(640);
        alert.showAndWait();
    }

    /**
     * Returns the per-objective resolution preview: shows what metric
     * the runtime will actually use given a (modality, picked metric)
     * pair. Updates as the user changes either dropdown.
     */
    private static String resolutionPreviewText(
            FocusMetricsManifest manifest, String modality, String pickedMetric) {
        String effective = manifest.resolveEffectiveMetric(modality, pickedMetric, "tenengrad");
        String src;
        if (pickedMetric != null && !pickedMetric.isEmpty() && !"none".equalsIgnoreCase(pickedMetric)
                && manifest.getMetrics().containsKey(pickedMetric)) {
            src = "per-objective YAML";
        } else if (modality != null && manifest.modalityDefault(modality).isPresent()) {
            src = "modality default for '" + modality + "'";
        } else {
            src = "fallback";
        }
        return "-> effective: " + effective + "  (source: " + src + ")";
    }

    /**
     * Build the Streaming Autofocus pane shown on the Per-Objective tab.
     *
     * <p>Streaming AF is the Live Viewer's "Autofocus" button -- a fast
     * continuous-Z scan during stage motion. It is NOT used during
     * acquisition (acquisition uses Standard + Sweep above).
     *
     * <p>Stage-side knobs live in {@code config_<scope>.yml} under
     * {@code stage.streaming_af}. Editing those requires touching the
     * main config file, not the autofocus YAML this dialog writes;
     * surfacing them read-only here lets operators see what governs
     * streaming on this rig without having to pop open the YAML.
     *
     * <p>The exposure threshold IS editable here: it's a Java preference
     * that controls when the Live Viewer auto-falls back from streaming
     * to Sweep Focus.
     */
    private static TitledPane buildStreamingAfPane(File configFile) {
        VBox content = new VBox(8);
        content.setPadding(new Insets(8));

        // --- "Where streaming AF actually gets each setting from" map ---
        Label intro = new Label(
                "Streaming AF is the LIVE VIEWER 'Autofocus' button only.\n"
                        + "Acquisition uses Standard AF (initial) + Sweep Drift\n"
                        + "Check (per-tile) -- both edited in the Per-Objective\n"
                        + "settings above. Streaming AF is not invoked during\n"
                        + "acquisition; switch the Live Viewer button to 'Sweep'\n"
                        + "if you want acquisition-style behaviour interactively.");
        intro.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        Label sourcesHeader = new Label("Effective settings (where each comes from):");
        sourcesHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; "
                + "-fx-padding: 6 0 0 0;");
        GridPane srcGrid = new GridPane();
        srcGrid.setHgap(10);
        srcGrid.setVgap(4);
        String[][] sources = {
                {"Scan range (um)",
                 "Per-objective sweep_range_um (above), unless overridden\n"
                 + "by the Live Viewer 'Range' dropdown."},
                {"Score metric",
                 "Per-objective score_metric (above). Falls back to the\n"
                 + "modality default in focus_metrics_manifest.yml."},
                {"Edge retries",
                 "Server-side hardcode (currently 2). Not YAML-driven for\n"
                 + "streaming AF; same name as the per-objective field but\n"
                 + "that field only governs Standard AF."},
                {"Crop factor",
                 "Server-side hardcode (0.5 = center 50% of sensor)."},
                {"Sample density",
                 "Determined by camera frame rate + scan duration; not a\n"
                 + "user knob. Streaming AF samples continuously during\n"
                 + "stage motion (no n_steps)."},
                {"Stage motion speed",
                 "stage.streaming_af in " + configFile.getName()
                 + " (read-only block below)."},
        };
        int sr = 0;
        for (String[] row : sources) {
            Label k = new Label(row[0] + ":");
            k.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
            Label v = new Label(row[1]);
            v.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-text-base-color;");
            v.setWrapText(true);
            srcGrid.add(k, 0, sr);
            srcGrid.add(v, 1, sr);
            sr++;
        }

        // --- Editable: exposure threshold preference (live-saved) ---
        Label expHeader = new Label("Editable here (saved to QuPath preferences live, "
                + "not to the autofocus YAML):");
        expHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; "
                + "-fx-padding: 6 0 0 0;");
        GridPane editGrid = new GridPane();
        editGrid.setHgap(10);
        editGrid.setVgap(6);
        Label expLabel = new Label("Max exposure (ms):");
        Spinner<Double> expSpinner = new Spinner<>(1.0, 1000.0,
                QPPreferenceDialog.getStreamingMaxExposureMs(), 5.0);
        expSpinner.setEditable(true);
        expSpinner.setPrefWidth(120);
        expSpinner.valueProperty().addListener((obs, o, n) -> {
            if (n != null) QPPreferenceDialog.setStreamingMaxExposureMs(n);
        });
        expSpinner.setTooltip(new Tooltip(
                "Live Viewer auto-falls back to Sweep Focus when the active\n"
                        + "exposure exceeds this threshold. 40 ms is the historical\n"
                        + "default (works for most brightfield + PPM). Long-exposure\n"
                        + "fluorescence may want this lower (forces sweep more often)\n"
                        + "or higher experimentally; sweep always works regardless.\n"
                        + "Saves on every change -- no need to press Save and Close."));
        Label expDesc = new Label("(streaming refuses above this; auto-falls back to Sweep)");
        expDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        editGrid.add(expLabel, 0, 0);
        editGrid.add(expSpinner, 1, 0);
        editGrid.add(expDesc, 2, 0);

        // --- Read-only: stage.streaming_af block from main config ---
        Label rigHeader = new Label("Stage block (read-only, from "
                + configFile.getName() + " -> stage.streaming_af):");
        rigHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 6 0 0 0;");

        GridPane rigGrid = new GridPane();
        rigGrid.setHgap(10);
        rigGrid.setVgap(4);
        Map<String, Object> stageBlock = readStreamingAfStageBlock(configFile);
        if (stageBlock.isEmpty()) {
            Label missing = new Label("(no stage.streaming_af block found -- "
                    + "streaming AF will use legacy hardcoded defaults)");
            missing.setStyle("-fx-font-style: italic; -fx-text-fill: gray;");
            rigGrid.add(missing, 0, 0, 2, 1);
        } else {
            int r = 0;
            for (String key : new String[]{
                    "enabled", "speed_property", "slow_speed_value",
                    "slow_speed_um_per_s", "normal_speed_value"}) {
                Label k = new Label(key + ":");
                k.setStyle("-fx-font-family: monospace;");
                Object v = stageBlock.get(key);
                Label val = new Label(v == null ? "(unset)" : String.valueOf(v));
                val.setStyle(v == null
                        ? "-fx-text-fill: gray; -fx-font-style: italic;"
                        : "-fx-font-family: monospace;");
                rigGrid.add(k, 0, r);
                rigGrid.add(val, 1, r);
                r++;
            }
            Label hint = new Label(
                    "Edit these in " + configFile.getName() + " under stage.streaming_af.\n"
                            + "Re-open this dialog after saving to see the new values.");
            hint.setStyle("-fx-font-size: 10px; -fx-text-fill: gray; -fx-padding: 4 0 0 0;");
            rigGrid.add(hint, 0, r, 2, 1);
        }

        content.getChildren().addAll(intro, new Separator(),
                sourcesHeader, srcGrid,
                expHeader, editGrid,
                rigHeader, rigGrid);

        TitledPane pane = new TitledPane(
                "Streaming Autofocus (Live Viewer Only -- mostly read-only)",
                content);
        pane.setCollapsible(true);
        pane.setExpanded(false);
        return pane;
    }

    /**
     * Read the {@code stage.streaming_af} block from the main microscope
     * config YAML. Returns an empty map on any read / parse failure --
     * the caller renders an explanatory note when the block is absent.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readStreamingAfStageBlock(File configFile) {
        if (configFile == null || !configFile.isFile()) return Collections.emptyMap();
        try (java.io.InputStream in = new java.io.FileInputStream(configFile)) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map)) return Collections.emptyMap();
            Object stage = ((Map<String, Object>) loaded).get("stage");
            if (!(stage instanceof Map)) return Collections.emptyMap();
            Object block = ((Map<String, Object>) stage).get("streaming_af");
            if (!(block instanceof Map)) return Collections.emptyMap();
            return (Map<String, Object>) block;
        } catch (IOException e) {
            logger.warn("Could not read streaming_af block from {}: {}",
                    configFile, e.getMessage());
            return Collections.emptyMap();
        }
    }

    private static final String[] VALIDITY_CHECKS = {
        "texture_and_area", "bright_spot_count", "total_gradient_energy", "always_false"
    };

    private static final String[] ON_FAILURE_MODES = {"defer", "proceed", "manual"};

    private static class StrategyDefinition {
        String name;
        String description;
        String scoreMetric;
        String validityCheck;
        Map<String, Object> validityParams;
        String onFailure;

        StrategyDefinition(
                String name,
                String description,
                String scoreMetric,
                String validityCheck,
                Map<String, Object> validityParams,
                String onFailure) {
            this.name = name;
            this.description = description != null ? description : "";
            this.scoreMetric = scoreMetric != null ? scoreMetric : "laplacian_variance";
            this.validityCheck = validityCheck != null ? validityCheck : "texture_and_area";
            this.validityParams = validityParams != null ? new LinkedHashMap<>(validityParams) : new LinkedHashMap<>();
            this.onFailure = onFailure != null ? onFailure : "defer";
        }

        Map<String, Object> toYamlMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("description", description);
            map.put("score_metric", scoreMetric);
            map.put("validity_check", validityCheck);
            map.put("validity_params", new LinkedHashMap<>(validityParams));
            map.put("on_failure", onFailure);
            return map;
        }
    }

    private static class ModalityBinding {
        String modalityKey;
        String strategyName;
        Map<String, Object> overrides; // validity_params overrides only
        // score_metric / on_failure overrides applied at the same precedence
        // as validity_params -- per-modality wins over the strategy's
        // default but loses to per-objective YAML score_metric. Null
        // means "do not override; use strategy default".
        String scoreMetricOverride;
        String onFailureOverride;

        ModalityBinding(String modalityKey, String strategyName, Map<String, Object> overrides) {
            this(modalityKey, strategyName, overrides, null, null);
        }

        ModalityBinding(String modalityKey, String strategyName, Map<String, Object> overrides,
                        String scoreMetricOverride, String onFailureOverride) {
            this.modalityKey = modalityKey;
            this.strategyName = strategyName != null ? strategyName : "dense_texture";
            this.overrides = overrides != null ? new LinkedHashMap<>(overrides) : new LinkedHashMap<>();
            this.scoreMetricOverride = scoreMetricOverride;
            this.onFailureOverride = onFailureOverride;
        }

        boolean hasAnyOverride() {
            return !overrides.isEmpty()
                    || (scoreMetricOverride != null && !scoreMetricOverride.isEmpty())
                    || (onFailureOverride != null && !onFailureOverride.isEmpty());
        }

        Map<String, Object> toYamlMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("strategy", strategyName);
            if (hasAnyOverride()) {
                Map<String, Object> ov = new LinkedHashMap<>();
                if (scoreMetricOverride != null && !scoreMetricOverride.isEmpty()) {
                    ov.put("score_metric", scoreMetricOverride);
                }
                if (onFailureOverride != null && !onFailureOverride.isEmpty()) {
                    ov.put("on_failure", onFailureOverride);
                }
                if (!overrides.isEmpty()) {
                    ov.put("validity_params", new LinkedHashMap<>(overrides));
                }
                map.put("overrides", ov);
            }
            return map;
        }
    }

    /**
     * Main entry point for the autofocus editor workflow
     */
    public static void run() {
        Platform.runLater(() -> {
            try {
                showAutofocusEditorDialog();
            } catch (Exception e) {
                logger.error("Error in autofocus editor workflow", e);
                Dialogs.showErrorMessage(
                        "Autofocus Editor Error", "Failed to open autofocus editor: " + e.getMessage());
            }
        });
    }

    /**
     * Show the autofocus editor dialog
     */
    private static void showAutofocusEditorDialog() throws IOException {
        // Get microscope config path from preferences
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        if (configPath == null || configPath.isEmpty()) {
            Dialogs.showErrorMessage("Configuration Error", "No microscope configuration file set in preferences.");
            return;
        }

        File configFile = new File(configPath);
        if (!configFile.exists()) {
            Dialogs.showErrorMessage("Configuration Error", "Microscope configuration file not found: " + configPath);
            return;
        }

        // Extract microscope name from config filename (e.g., "config_PPM.yml" -> "PPM")
        String configFilename = configFile.getName();
        String microscopeName = extractMicroscopeName(configFilename);

        // Construct autofocus config path
        File configDir = configFile.getParentFile();
        File autofocusFile = new File(configDir, "autofocus_" + microscopeName + ".yml");

        logger.info("Autofocus editor using config: {}", autofocusFile.getAbsolutePath());

        // Detect legacy metric aliases (e.g. volath5 -> vollath_f5) BEFORE
        // loading: if the user accepts migration, the file is rewritten in
        // place so the loaders below see canonical names.
        FocusMetricsManifest migrationManifest = FocusMetricsManifest.get(configDir.toPath());
        Map<String, String> deprecatedFound = findDeprecatedAliasesInFile(autofocusFile, migrationManifest);
        if (!deprecatedFound.isEmpty()) {
            qupath.lib.gui.QuPathGUI guiForBanner = qupath.lib.gui.QuPathGUI.getInstance();
            Window ownerForBanner = guiForBanner != null ? guiForBanner.getStage() : null;
            maybeShowMigrationBanner(autofocusFile, deprecatedFound, ownerForBanner);
        }

        // Load objectives from main config
        MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
        List<String> objectives = loadObjectivesFromConfig(configManager);

        if (objectives.isEmpty()) {
            Dialogs.showErrorMessage("Configuration Error", "No objectives found in microscope configuration.");
            return;
        }

        // Load existing autofocus settings (if file exists)
        Map<String, AutofocusSettings> existingSettings = loadAutofocusSettings(autofocusFile);

        // Create working copy with defaults for all objectives
        Map<String, AutofocusSettings> workingSettings = new LinkedHashMap<>();
        logger.info(
                "Creating working settings from {} objectives and {} existing settings",
                objectives.size(),
                existingSettings.size());
        logger.info("Objectives list: {}", objectives);
        logger.info("Existing settings keys: {}", existingSettings.keySet());

        for (String obj : objectives) {
            logger.info("Processing objective: '{}'", obj);
            if (existingSettings.containsKey(obj)) {
                AutofocusSettings existing = existingSettings.get(obj);
                logger.info("  FOUND in existingSettings: n_steps={}", existing.nSteps);
                AutofocusSettings copy = new AutofocusSettings(
                        obj,
                        existing.nSteps,
                        existing.searchRangeUm,
                        existing.nTiles,
                        existing.interpStrength,
                        existing.interpKind,
                        existing.scoreMetric,
                        existing.textureThreshold,
                        existing.tissueAreaThreshold,
                        existing.sweepRangeUm,
                        existing.sweepNSteps,
                        existing.edgeRetries,
                        existing.gapIndexMultiplier,
                        existing.gapSpatialMultiplier);
                copy.p98P2FallbackEnabled = existing.p98P2FallbackEnabled;
                workingSettings.put(obj, copy);
            } else {
                logger.info("  NOT FOUND in existingSettings - using defaults");
                workingSettings.put(
                        obj,
                        new AutofocusSettings(
                                obj,
                                9,
                                15.0,
                                5,
                                100,
                                "quadratic",
                                "normalized_variance",
                                0.005,
                                0.2,
                                10.0,
                                6,
                                2,
                                3,
                                2.0));
            }
        }

        // Load v2 sections (strategies + modality bindings) up front so they
        // can be referenced by lambdas in the write/validate button handlers.
        final Map<String, StrategyDefinition> strategiesRef = loadStrategies(autofocusFile);
        final Map<String, ModalityBinding> bindingsRef = loadModalityBindings(autofocusFile);

        // Create dialog
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initModality(javafx.stage.Modality.NONE);
        qupath.lib.gui.QuPathGUI gui = qupath.lib.gui.QuPathGUI.getInstance();
        if (gui != null && gui.getStage() != null) {
            dialog.initOwner(gui.getStage());
        }
        dialog.setTitle("Autofocus Configuration Editor");
        dialog.setHeaderText("Edit autofocus parameters for " + microscopeName + " microscope\n"
                + "Settings will be saved to: " + autofocusFile.getName());
        dialog.setGraphic(DocumentationHelper.createHelpButton("autofocusEditor"));

        // Create UI
        VBox mainLayout = new VBox(10);
        mainLayout.setPadding(new Insets(10));

        // Objective selection -- shared component auto-selects MM's
        // currently mounted objective by pixel-size match, falls back
        // to last-used preference, then to the first entry.
        Label objectiveLabel = new Label("Select Objective:");
        ComboBox<String> objectiveCombo = ObjectiveSelector.create(objectives, configManager);

        // ===== ACQUISITION FREQUENCY SECTION =====
        GridPane acquisitionGrid = new GridPane();
        acquisitionGrid.setHgap(10);
        acquisitionGrid.setVgap(8);
        acquisitionGrid.setPadding(new Insets(5));

        Label nTilesLabel = new Label("n_tiles:");
        Spinner<Integer> nTilesSpinner = new Spinner<>(1, 50, 5, 1);
        nTilesSpinner.setEditable(true);
        nTilesSpinner.setPrefWidth(100);
        nTilesSpinner.setTooltip(
                new Tooltip("Spatial frequency: Autofocus runs every N tiles.\n\n" + "Lower values (1-3):\n"
                        + "  + More frequent autofocus\n"
                        + "  + Better tracking of uneven samples\n"
                        + "  - Significantly slower acquisition\n"
                        + "  - More wear on Z motor\n\n"
                        + "Higher values (5-10):\n"
                        + "  + Faster acquisition\n"
                        + "  + Less mechanical wear\n"
                        + "  - May lose focus on tilted samples\n\n"
                        + "Also sets the AF grid spacing:\n"
                        + "  af_min_distance = n_tiles x mean(camera FOV)\n"
                        + "  -- the minimum spacing between planned AF positions.\n\n"
                        + "Typical: 5 tiles (good balance)\n"
                        + "Use 1-3 for tilted or curved samples"));
        Label nTilesDesc = new Label("(Autofocus every N tiles; also sets af_min_distance)");
        nTilesDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        // Gap index multiplier: safety-net force-AF threshold in scan-order space
        Label gapIndexLabel = new Label("gap_index_multiplier:");
        // Range was (1, 10) -- too tight for low-frequency safety nets
        // on long acquisitions. Operators with 200+ tile grids
        // legitimately want gap_index_multiplier in the 10-30 range
        // to suppress wasted AF on dense well-focused regions while
        // still catching long gaps. Cap at 50 (any higher is suspicious;
        // user can hand-edit YAML if they really need it).
        Spinner<Integer> gapIndexSpinner = new Spinner<>(1, 50, 3, 1);
        gapIndexSpinner.setEditable(true);
        gapIndexSpinner.setPrefWidth(100);
        gapIndexSpinner.setTooltip(new Tooltip("Safety net: forces an extra autofocus when the scan has gone\n"
                + "gap_index_multiplier x n_tiles tile positions without one.\n\n"
                + "This catches long stretches of blank/low-tissue tiles where the\n"
                + "planned AF grid skipped all candidates. Effective threshold with\n"
                + "the current n_tiles is shown below.\n\n"
                + "Lower values (1-2):\n"
                + "  + Aggressive safety net, AF fires often in sparse regions\n"
                + "  + Better on tilted or thermally drifting samples\n"
                + "  - More acquisition time\n"
                + "  - Can recreate 'AF pillar' artifacts in serpentine scans\n\n"
                + "Higher values (4-5):\n"
                + "  + Rare intrusive AF, relies on planned grid\n"
                + "  - Risk of Z drift between AF points on non-flat samples\n\n"
                + "Typical: 3 (threshold ~= 3 x n_tiles)\n"
                + "Reduce if long gaps between tissue cause defocus; increase\n"
                + "if you see regular vertical focus bands in serpentine scans."));
        Label gapIndexDesc = new Label();
        gapIndexDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        // Gap spatial multiplier: safety-net force-AF threshold in physical distance
        Label gapSpatialLabel = new Label("gap_spatial_multiplier:");
        TextField gapSpatialField = new TextField("2.0");
        gapSpatialField.setPrefWidth(100);
        gapSpatialField.setTooltip(new Tooltip("Safety net: forces an extra autofocus when the current tile is\n"
                + "more than gap_spatial_multiplier x af_min_distance away from the\n"
                + "nearest committed AF position.\n\n"
                + "This catches disconnected tissue fragments or large jumps between\n"
                + "annotations where the index-based safety net is not enough.\n\n"
                + "Lower values (1.0-1.5):\n"
                + "  + Aggressive: fires on every normal inter-row transition too\n"
                + "  - Extra AF time, not usually needed on contiguous tissue\n\n"
                + "Higher values (2.5-4.0):\n"
                + "  + Only fires on truly isolated fragments\n"
                + "  - Isolated regions may drift out of focus before being caught\n\n"
                + "Typical: 2.0 (threshold = 2 x af_min_distance)\n"
                + "Decrease if scattered tissue fragments go out of focus;\n"
                + "increase if the safety net fires on every serpentine turn."));
        Label gapSpatialDesc = new Label();
        gapSpatialDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        // Read-only derived display: af_min_distance and effective gap thresholds
        // Values update live as n_tiles / multipliers change.
        Label derivedLabel = new Label("af_min_distance (derived):");
        Label derivedValue = new Label();
        derivedValue.setStyle("-fx-font-size: 11px; -fx-text-fill: #444; -fx-font-style: italic;");
        derivedValue.setTooltip(
                new Tooltip("af_min_distance is not a user setting; it is computed at runtime by Python:\n"
                        + "  af_min_distance = ((fov_x + fov_y) / 2) x n_tiles\n\n"
                        + "where fov_x / fov_y come from the active modality + objective + detector.\n"
                        + "The value shown here is an editor estimate using the objective's camera FOV\n"
                        + "from the microscope config. To change af_min_distance, adjust n_tiles.\n\n"
                        + "This value controls the minimum spacing between planned AF grid points\n"
                        + "and also feeds into the gap_spatial_multiplier safety net."));

        acquisitionGrid.add(nTilesLabel, 0, 0);
        acquisitionGrid.add(nTilesSpinner, 1, 0);
        acquisitionGrid.add(nTilesDesc, 2, 0);

        acquisitionGrid.add(gapIndexLabel, 0, 1);
        acquisitionGrid.add(gapIndexSpinner, 1, 1);
        acquisitionGrid.add(gapIndexDesc, 2, 1);

        acquisitionGrid.add(gapSpatialLabel, 0, 2);
        acquisitionGrid.add(gapSpatialField, 1, 2);
        acquisitionGrid.add(gapSpatialDesc, 2, 2);

        acquisitionGrid.add(derivedLabel, 0, 3);
        acquisitionGrid.add(derivedValue, 1, 3, 2, 1);

        TitledPane acquisitionPane = new TitledPane("Acquisition Frequency & Safety Nets", acquisitionGrid);
        acquisitionPane.setCollapsible(false);

        // ===== TISSUE DETECTION SECTION =====
        GridPane tissueGrid = new GridPane();
        tissueGrid.setHgap(10);
        tissueGrid.setVgap(8);
        tissueGrid.setPadding(new Insets(5));

        Label textureThresholdLabel = new Label("texture_threshold:");
        TextField textureThresholdField = new TextField("0.005");
        textureThresholdField.setPrefWidth(100);
        textureThresholdField.setTooltip(new Tooltip("Minimum texture variance required for tissue detection.\n"
                + "Controls whether autofocus runs at a position.\n\n"
                + "Lower values (0.005-0.010):\n"
                + "  + More sensitive - detects smooth tissue\n"
                + "  + Accepts homogeneous samples\n"
                + "  - May accept out-of-focus areas\n\n"
                + "Higher values (0.015-0.030):\n"
                + "  + More selective - requires textured tissue\n"
                + "  + Rejects blurry or empty areas\n"
                + "  - May skip smooth but valid tissue\n\n"
                + "Typical: 0.005 for smooth tissue, 0.010-0.015 for textured"));
        Label textureThresholdDesc = new Label("(Min texture variance, typical: 0.005-0.030)");
        textureThresholdDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label tissueAreaThresholdLabel = new Label("tissue_area_threshold:");
        TextField tissueAreaThresholdField = new TextField("0.2");
        tissueAreaThresholdField.setPrefWidth(100);
        tissueAreaThresholdField.setTooltip(new Tooltip("Minimum fraction of image that must contain tissue.\n"
                + "Determines if enough tissue is present for autofocus.\n\n"
                + "Lower values (0.05-0.15):\n"
                + "  + Accepts sparse tissue coverage\n"
                + "  + Better for small or fragmented samples\n"
                + "  - May autofocus on debris\n\n"
                + "Higher values (0.20-0.30):\n"
                + "  + Requires substantial tissue presence\n"
                + "  + More reliable autofocus targets\n"
                + "  - May skip valid tissue at edges\n\n"
                + "Typical: 0.2 (20% coverage)"));
        Label tissueAreaThresholdDesc = new Label("(Min tissue coverage fraction, typical: 0.05-0.30)");
        tissueAreaThresholdDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        tissueGrid.add(textureThresholdLabel, 0, 0);
        tissueGrid.add(textureThresholdField, 1, 0);
        tissueGrid.add(textureThresholdDesc, 2, 0);

        tissueGrid.add(tissueAreaThresholdLabel, 0, 1);
        tissueGrid.add(tissueAreaThresholdField, 1, 1);
        tissueGrid.add(tissueAreaThresholdDesc, 2, 1);

        // Add score_metric to tissue/shared grid (used by both standard AF and sweep drift check)
        // Items + grouping sourced from focus_metrics_manifest.yml so the
        // dropdown matches the runtime registry. The configDir is the
        // active microscope's config directory, which is also where a
        // per-scope manifest override (if any) would live.
        FocusMetricsManifest manifest = FocusMetricsManifest.get(configDir.toPath());
        Label scoreMetricLabel = new Label("score_metric:");
        ComboBox<String> scoreMetricCombo = buildScoreMetricCombo("laplacian_variance", manifest);
        scoreMetricCombo.setTooltip(new Tooltip(
                "Focus metric used by standard AF + sweep drift check.\n"
                        + "Click 'Help me pick' for the manifest's per-metric guide."));
        Button scoreMetricHelp = new Button("Help me pick");
        scoreMetricHelp.setOnAction(e -> showMetricHelpDialog(manifest));
        Label scoreMetricResolution = new Label(resolutionPreviewText(manifest, null,
                scoreMetricCombo.getValue()));
        scoreMetricResolution.setStyle("-fx-font-size: 10px; -fx-font-style: italic; -fx-text-fill: gray;");
        scoreMetricCombo.valueProperty().addListener((obs, o, n) -> {
            if (n != null && !n.startsWith("---")) {
                scoreMetricResolution.setText(resolutionPreviewText(manifest, null, n));
            }
        });
        HBox scoreMetricRow = new HBox(8, scoreMetricCombo, scoreMetricHelp);
        scoreMetricRow.setAlignment(Pos.CENTER_LEFT);

        tissueGrid.add(scoreMetricLabel, 0, 2);
        tissueGrid.add(scoreMetricRow, 1, 2);
        tissueGrid.add(scoreMetricResolution, 2, 2);

        // Standard-AF p98_p2 fallback opt-out. Always-computed alongside
        // the primary metric; the system falls back to it when the
        // primary's peak validation fails. Default ON; turning it off
        // writes p98_p2_fallback_enabled: false into the YAML so the
        // server skips the fallback path for this objective.
        Label p98FallbackLabel = new Label("p98_p2 fallback:");
        CheckBox p98FallbackCheck = new CheckBox("Use p98_p2 if primary peak fails");
        p98FallbackCheck.setSelected(true);
        p98FallbackCheck.setTooltip(new Tooltip(
                "Standard-AF safety net. p98_p2 is always computed alongside\n"
                        + "the primary metric. When this is enabled, the AF system\n"
                        + "uses the p98_p2 peak whenever the primary metric's\n"
                        + "Z-curve cannot be validated (no clear peak / monotonic\n"
                        + "drift / saturation). Disable only if your primary metric\n"
                        + "is well-tuned and you'd rather see the failure than\n"
                        + "accept a histogram-spread fallback."));
        Label p98FallbackDesc = new Label("(falls back to p98_p2 when primary peak validation fails)");
        p98FallbackDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        tissueGrid.add(p98FallbackLabel, 0, 3);
        tissueGrid.add(p98FallbackCheck, 1, 3);
        tissueGrid.add(p98FallbackDesc, 2, 3);

        TitledPane tissuePane = new TitledPane("Tissue Detection & Shared Settings", tissueGrid);
        tissuePane.setCollapsible(false);

        // ===== STANDARD AUTOFOCUS SECTION =====
        GridPane standardGrid = new GridPane();
        standardGrid.setHgap(10);
        standardGrid.setVgap(8);
        standardGrid.setPadding(new Insets(5));

        Label nStepsLabel = new Label("n_steps:");
        Spinner<Integer> nStepsSpinner = new Spinner<>(1, 100, 9, 1);
        nStepsSpinner.setEditable(true);
        nStepsSpinner.setPrefWidth(100);
        nStepsSpinner.setTooltip(
                new Tooltip("Number of Z positions sampled during autofocus.\n\n" + "Higher values (15-30):\n"
                        + "  + More accurate focus finding\n"
                        + "  + Better for thick samples\n"
                        + "  - Slower autofocus (~2-3x time)\n\n"
                        + "Lower values (5-11):\n"
                        + "  + Faster autofocus\n"
                        + "  + Adequate for thin, flat samples\n"
                        + "  - May miss optimal focus on thick samples\n\n"
                        + "Typical: 9-15 steps"));
        Label nStepsDesc = new Label("(Number of Z positions to sample)");
        nStepsDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label searchRangeLabel = new Label("search_range_um:");
        TextField searchRangeField = new TextField("15.0");
        searchRangeField.setPrefWidth(100);
        searchRangeField.setTooltip(
                new Tooltip("Total Z range to search, centered on current position.\n\n" + "Larger range (30-50um):\n"
                        + "  + Finds focus even when stage is far off\n"
                        + "  + Better for initial acquisition setup\n"
                        + "  - Slower if many steps used\n\n"
                        + "Smaller range (10-20um):\n"
                        + "  + Faster autofocus\n"
                        + "  + Works well when stage is pre-leveled\n"
                        + "  - May fail if sample is very tilted\n\n"
                        + "Typical: 15-25um for most samples"));
        Label searchRangeDesc = new Label("(Total Z range in micrometers)");
        searchRangeDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label interpStrengthLabel = new Label("interp_strength:");
        Spinner<Integer> interpStrengthSpinner = new Spinner<>(10, 1000, 100, 10);
        interpStrengthSpinner.setEditable(true);
        interpStrengthSpinner.setPrefWidth(100);
        interpStrengthSpinner.setTooltip(
                new Tooltip("Density of interpolated points in focus curve.\n\n" + "Higher values (150-200):\n"
                        + "  + Smoother focus curve fitting\n"
                        + "  + More precise peak finding\n"
                        + "  - Minimal speed impact\n\n"
                        + "Lower values (50-100):\n"
                        + "  + Slightly faster computation\n"
                        + "  + Usually sufficient for most samples\n\n"
                        + "Typical: 100 (good default)\n"
                        + "Increase to 150-200 if autofocus is inconsistent"));
        Label interpStrengthDesc = new Label("(Interpolation density factor)");
        interpStrengthDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label interpKindLabel = new Label("interp_kind:");
        ComboBox<String> interpKindCombo = new ComboBox<>();
        interpKindCombo.getItems().addAll("linear", "quadratic", "cubic");
        interpKindCombo.setValue("quadratic");
        interpKindCombo.setPrefWidth(150);
        interpKindCombo.setTooltip(new Tooltip("Interpolation method for focus curve fitting.\n\n" + "Linear:\n"
                + "  + Simple and fast\n"
                + "  - Less accurate peak detection\n\n"
                + "Quadratic (recommended):\n"
                + "  + Good balance of speed and accuracy\n"
                + "  + Smooth parabolic curve fitting\n"
                + "  + Works well for most samples\n\n"
                + "Cubic:\n"
                + "  + Most accurate curve fitting\n"
                + "  - Can be sensitive to noise\n"
                + "  - May overfit sparse data\n\n"
                + "Typical: quadratic for most applications"));
        Label interpKindDesc = new Label("(Interpolation method)");
        interpKindDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        // Add standard autofocus fields to grid
        // Note: score_metric is now in the shared section above
        standardGrid.add(nStepsLabel, 0, 0);
        standardGrid.add(nStepsSpinner, 1, 0);
        standardGrid.add(nStepsDesc, 2, 0);

        standardGrid.add(searchRangeLabel, 0, 1);
        standardGrid.add(searchRangeField, 1, 1);
        standardGrid.add(searchRangeDesc, 2, 1);

        standardGrid.add(interpStrengthLabel, 0, 2);
        standardGrid.add(interpStrengthSpinner, 1, 2);
        standardGrid.add(interpStrengthDesc, 2, 2);

        standardGrid.add(interpKindLabel, 0, 3);
        standardGrid.add(interpKindCombo, 1, 3);
        standardGrid.add(interpKindDesc, 2, 3);

        // ===== SWEEP DRIFT CHECK SECTION =====
        GridPane sweepGrid = new GridPane();
        sweepGrid.setHgap(10);
        sweepGrid.setVgap(8);
        sweepGrid.setPadding(new Insets(5));

        Label sweepRangeLabel = new Label("sweep_range_um:");
        TextField sweepRangeField = new TextField("10.0");
        sweepRangeField.setPrefWidth(100);
        sweepRangeField.setTooltip(new Tooltip("Total Z range for the sweep drift check.\n"
                + "The sweep samples positions from -range/2 to +range/2\n"
                + "around the current Z position.\n\n"
                + "Larger range (15-20um):\n"
                + "  + Catches larger drift\n"
                + "  + Better for samples with significant thermal drift\n"
                + "  - Slightly slower\n\n"
                + "Smaller range (6-10um):\n"
                + "  + Faster sweep\n"
                + "  + Higher precision within range\n"
                + "  - May miss large drift events\n\n"
                + "Typical: 10um (+/-5um) for most samples"));
        Label sweepRangeDesc = new Label("(Total Z range in um, centered on current position)");
        sweepRangeDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label sweepNStepsLabel = new Label("sweep_n_steps:");
        Spinner<Integer> sweepNStepsSpinner = new Spinner<>(3, 20, 6, 1);
        sweepNStepsSpinner.setEditable(true);
        sweepNStepsSpinner.setPrefWidth(100);
        sweepNStepsSpinner.setTooltip(new Tooltip("Number of Z positions sampled during sweep drift check.\n\n"
                + "More steps (8-12):\n"
                + "  + Better peak resolution\n"
                + "  + More reliable on noisy samples\n"
                + "  - Slower (~0.7s per step)\n\n"
                + "Fewer steps (4-6):\n"
                + "  + Faster sweep\n"
                + "  + Adequate for typical drift\n"
                + "  - Coarser peak detection\n\n"
                + "Typical: 6 steps (~3s total)"));
        Label sweepNStepsDesc = new Label("(Number of Z positions to sample)");
        sweepNStepsDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        sweepGrid.add(sweepRangeLabel, 0, 0);
        sweepGrid.add(sweepRangeField, 1, 0);
        sweepGrid.add(sweepRangeDesc, 2, 0);

        Label edgeRetriesLabel = new Label("edge_retries:");
        Spinner<Integer> edgeRetriesSpinner = new Spinner<>(0, 5, 2, 1);
        edgeRetriesSpinner.setEditable(true);
        edgeRetriesSpinner.setPrefWidth(100);
        edgeRetriesSpinner.setTooltip(
                new Tooltip("Additional sweep attempts when peak is at the edge of the search range.\n\n"
                        + "When the sweep detects a monotonic profile (focus is outside\n"
                        + "the window), it shifts one full range in the peak direction\n"
                        + "and sweeps again.\n\n"
                        + "0 = no retries (original 1-window behavior)\n"
                        + "2 = up to 3 total attempts, covering 3x sweep_range_um\n\n"
                        + "WARNING: Each retry adds ~1s per autofocus point.\n"
                        + "For a 750-tile acquisition with 70 AF points,\n"
                        + "going from 2 to 4 adds 2+ minutes.\n"
                        + "Values above 3 rarely help -- if focus is that\n"
                        + "far away, the starting Z estimate is wrong."));
        Label edgeRetriesDesc = new Label("(Extra attempts on boundary peaks, 0-5)");
        edgeRetriesDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        sweepGrid.add(sweepNStepsLabel, 0, 1);
        sweepGrid.add(sweepNStepsSpinner, 1, 1);
        sweepGrid.add(sweepNStepsDesc, 2, 1);

        sweepGrid.add(edgeRetriesLabel, 0, 2);
        sweepGrid.add(edgeRetriesSpinner, 1, 2);
        sweepGrid.add(edgeRetriesDesc, 2, 2);

        // Status label for validation feedback
        Label statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: blue; -fx-font-weight: bold;");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(500);

        // Track current objective for saving changes before switching
        // Initialize to null to skip save on first load (prevents overwriting loaded settings with UI defaults)
        final String[] currentObjective = {null};

        // Save current UI values to working settings
        Runnable saveCurrentSettings = () -> {
            try {
                int nSteps = nStepsSpinner.getValue();
                double searchRange = Double.parseDouble(searchRangeField.getText());
                int nTiles = nTilesSpinner.getValue();
                int interpStrength = interpStrengthSpinner.getValue();
                String interpKind = interpKindCombo.getValue();
                String scoreMetric = scoreMetricCombo.getValue();
                double textureThreshold = Double.parseDouble(textureThresholdField.getText());
                double tissueAreaThreshold = Double.parseDouble(tissueAreaThresholdField.getText());
                double sweepRange = Double.parseDouble(sweepRangeField.getText());
                int sweepNSteps = sweepNStepsSpinner.getValue();
                int edgeRetries = edgeRetriesSpinner.getValue();
                int gapIndexMult = gapIndexSpinner.getValue();
                double gapSpatialMult = Double.parseDouble(gapSpatialField.getText());

                AutofocusSettings updated = new AutofocusSettings(
                        currentObjective[0],
                        nSteps,
                        searchRange,
                        nTiles,
                        interpStrength,
                        interpKind,
                        scoreMetric,
                        textureThreshold,
                        tissueAreaThreshold,
                        sweepRange,
                        sweepNSteps,
                        edgeRetries,
                        gapIndexMult,
                        gapSpatialMult);
                updated.p98P2FallbackEnabled = p98FallbackCheck.isSelected();
                workingSettings.put(currentObjective[0], updated);
            } catch (NumberFormatException ex) {
                logger.warn("Invalid numeric input when saving settings");
            }
        };

        // Refresh the read-only derived display (af_min_distance + effective thresholds).
        // Uses estimateMeanFovUmForObjective() which consults the microscope config for
        // the first modality/detector combo that exposes this objective; if none is
        // available the display falls back to a formula-only message.
        Runnable refreshDerivedDisplay = () -> {
            try {
                int nTiles = nTilesSpinner.getValue();
                int gapIdx = gapIndexSpinner.getValue();
                double gapSp = Double.parseDouble(gapSpatialField.getText());
                String objectiveId = objectiveCombo.getValue();

                Double meanFov = estimateMeanFovUmForObjective(configManager, objectiveId);
                if (meanFov != null) {
                    double afMinDist = meanFov * nTiles;
                    int indexThreshold = gapIdx * nTiles;
                    double spatialThreshold = gapSp * afMinDist;
                    derivedValue.setText(String.format(
                            "%.0f um  (= %d x %.0f um FOV).  Force-AF thresholds: index >= %d tiles, spatial > %.0f um",
                            afMinDist, nTiles, meanFov, indexThreshold, spatialThreshold));
                    gapIndexDesc.setText(String.format("(force AF after %d tiles without one)", indexThreshold));
                    gapSpatialDesc.setText(
                            String.format("(force AF beyond %.0f um from nearest AF)", spatialThreshold));
                } else {
                    derivedValue.setText(String.format(
                            "~ %d x mean(FOV) um  (FOV unavailable for this objective -- value is computed at runtime)",
                            nTiles));
                    gapIndexDesc.setText(String.format("(force AF after %d tiles without one)", gapIdx * nTiles));
                    gapSpatialDesc.setText(String.format("(force AF beyond %.1f x af_min_distance)", gapSp));
                }
            } catch (NumberFormatException nfe) {
                derivedValue.setText("(enter a valid gap_spatial_multiplier to update)");
            } catch (Exception ex) {
                logger.debug("Failed to refresh derived AF display: {}", ex.getMessage());
                derivedValue.setText("");
            }
        };

        // Load settings from working copy for selected objective
        Runnable loadSettingsForObjective = () -> {
            // First save current UI state
            if (currentObjective[0] != null) {
                saveCurrentSettings.run();
            }

            // Update current objective
            String selectedObjective = objectiveCombo.getValue();
            currentObjective[0] = selectedObjective;

            logger.info("Loading settings for objective: {}", selectedObjective);
            logger.info(
                    "Working settings contains {} objectives: {}", workingSettings.size(), workingSettings.keySet());
            logger.info(
                    "Existing settings contains {} objectives: {}", existingSettings.size(), existingSettings.keySet());

            // Load from working settings
            AutofocusSettings settings = workingSettings.get(selectedObjective);

            if (settings != null) {
                logger.info(
                        "Found settings for {}: n_steps={}, search_range={}, texture_threshold={}, tissue_area_threshold={}",
                        selectedObjective,
                        settings.nSteps,
                        settings.searchRangeUm,
                        settings.textureThreshold,
                        settings.tissueAreaThreshold);

                nStepsSpinner.getValueFactory().setValue(settings.nSteps);
                searchRangeField.setText(String.valueOf(settings.searchRangeUm));
                nTilesSpinner.getValueFactory().setValue(settings.nTiles);
                interpStrengthSpinner.getValueFactory().setValue(settings.interpStrength);
                interpKindCombo.setValue(settings.interpKind);
                scoreMetricCombo.setValue(settings.scoreMetric);
                textureThresholdField.setText(String.valueOf(settings.textureThreshold));
                tissueAreaThresholdField.setText(String.valueOf(settings.tissueAreaThreshold));
                sweepRangeField.setText(String.valueOf(settings.sweepRangeUm));
                sweepNStepsSpinner.getValueFactory().setValue(settings.sweepNSteps);
                edgeRetriesSpinner.getValueFactory().setValue(settings.edgeRetries);
                gapIndexSpinner.getValueFactory().setValue(settings.gapIndexMultiplier);
                gapSpatialField.setText(String.valueOf(settings.gapSpatialMultiplier));
                p98FallbackCheck.setSelected(settings.p98P2FallbackEnabled);
                refreshDerivedDisplay.run();
                if (existingSettings.containsKey(selectedObjective)) {
                    statusLabel.setText("Loaded existing settings for " + selectedObjective);
                    logger.info("UI populated with existing settings for {}", selectedObjective);
                } else {
                    statusLabel.setText("Using default values for " + selectedObjective);
                    logger.info("UI populated with default values for {}", selectedObjective);
                }
            } else {
                logger.warn("No settings found in workingSettings for objective: {}", selectedObjective);
            }
        };

        objectiveCombo.setOnAction(e -> loadSettingsForObjective.run());
        // Live-refresh the derived af_min_distance / force-AF threshold display
        // as the user edits the inputs that feed into it.
        nTilesSpinner.valueProperty().addListener((obs, oldV, newV) -> refreshDerivedDisplay.run());
        gapIndexSpinner.valueProperty().addListener((obs, oldV, newV) -> refreshDerivedDisplay.run());
        gapSpatialField.textProperty().addListener((obs, oldV, newV) -> refreshDerivedDisplay.run());
        loadSettingsForObjective.run(); // Load initial settings

        // "Write to file" button
        Button writeButton = new Button("Write to File");
        writeButton.setOnAction(e -> {
            try {
                // Save current UI state to working settings
                saveCurrentSettings.run();

                // Validate all settings
                boolean hasErrors = false;
                for (AutofocusSettings settings : workingSettings.values()) {
                    List<String> warnings = settings.validate();
                    if (!warnings.isEmpty()) {
                        boolean proceed = Dialogs.showConfirmDialog(
                                "Validation Warnings for " + settings.objective,
                                String.join("\n", warnings) + "\n\nContinue saving?");
                        if (!proceed) {
                            hasErrors = true;
                            break;
                        }
                    }
                }

                if (hasErrors) {
                    return;
                }

                // Save to file
                saveAutofocusSettings(autofocusFile, workingSettings, strategiesRef, bindingsRef);
                statusLabel.setText("Settings saved successfully to " + autofocusFile.getName());
                statusLabel.setStyle("-fx-text-fill: green; -fx-font-weight: bold;");
                logger.info("Autofocus settings saved to: {}", autofocusFile.getAbsolutePath());

            } catch (NumberFormatException ex) {
                Dialogs.showErrorMessage("Input Error", "Please enter valid numeric values.");
            } catch (IOException ex) {
                logger.error("Failed to save autofocus settings", ex);
                Dialogs.showErrorMessage("Save Error", "Failed to save settings: " + ex.getMessage());
            }
        });

        // "Test Standard Autofocus" button - will be placed inside standard section
        Button testStandardButton = new Button("Test Standard Autofocus");
        testStandardButton.setOnAction(e -> {
            try {
                // First, save current UI state to working settings
                saveCurrentSettings.run();

                // Validate current settings
                String currentObj = objectiveCombo.getValue();
                AutofocusSettings currentSettings = workingSettings.get(currentObj);

                if (currentSettings != null) {
                    List<String> warnings = currentSettings.validate();
                    if (!warnings.isEmpty()) {
                        Dialogs.showWarningNotification(
                                "Validation Warnings",
                                "Current settings have warnings:\n" + String.join("\n", warnings));
                    }
                }

                // Save to file first so test uses current settings
                saveAutofocusSettings(autofocusFile, workingSettings, strategiesRef, bindingsRef);
                statusLabel.setText("Settings saved - running standard autofocus test...");
                statusLabel.setStyle("-fx-text-fill: blue; -fx-font-weight: bold;");
                logger.info("Autofocus settings saved before standard test");

                // Determine output path for test results (same directory as config file)
                String testOutputPath = new File(configDir, "autofocus_tests").getAbsolutePath();
                logger.info("Using autofocus test output path: {}", testOutputPath);

                // Run the STANDARD test workflow with selected objective
                TestAutofocusWorkflow.runStandard(testOutputPath, currentObj);

                // Update status after launching test
                Platform.runLater(() -> {
                    statusLabel.setText("Standard autofocus test launched - check for results dialog");
                    statusLabel.setStyle("-fx-text-fill: blue; -fx-font-weight: bold;");
                });

            } catch (NumberFormatException ex) {
                Dialogs.showErrorMessage("Input Error", "Please enter valid numeric values before testing.");
            } catch (IOException ex) {
                logger.error("Failed to save autofocus settings before test", ex);
                Dialogs.showErrorMessage("Save Error", "Failed to save settings before test: " + ex.getMessage());
            } catch (Exception ex) {
                logger.error("Failed to start standard autofocus test", ex);
                Dialogs.showErrorMessage("Test Error", "Failed to start standard autofocus test: " + ex.getMessage());
            }
        });

        // "Test Sweep Drift Check" button - will be placed inside sweep section
        Button testSweepButton = new Button("Test Sweep Drift Check");
        testSweepButton.setOnAction(e -> {
            try {
                // First, save current UI state to working settings
                saveCurrentSettings.run();

                // Validate current settings
                String currentObj = objectiveCombo.getValue();
                AutofocusSettings currentSettings = workingSettings.get(currentObj);

                if (currentSettings != null) {
                    List<String> warnings = currentSettings.validate();
                    if (!warnings.isEmpty()) {
                        Dialogs.showWarningNotification(
                                "Validation Warnings",
                                "Current settings have warnings:\n" + String.join("\n", warnings));
                    }
                }

                // Save to file first so test uses current settings
                saveAutofocusSettings(autofocusFile, workingSettings, strategiesRef, bindingsRef);
                statusLabel.setText("Settings saved - running sweep drift check test...");
                statusLabel.setStyle("-fx-text-fill: blue; -fx-font-weight: bold;");
                logger.info("Autofocus settings saved before sweep test");

                // Determine output path for test results (same directory as config file)
                String testOutputPath = new File(configDir, "autofocus_tests").getAbsolutePath();
                logger.info("Using autofocus test output path: {}", testOutputPath);

                // Run the SWEEP test workflow with selected objective
                TestAutofocusWorkflow.runSweep(testOutputPath, currentObj);

                // Update status after launching test
                Platform.runLater(() -> {
                    statusLabel.setText("Sweep drift check test launched - check for results dialog");
                    statusLabel.setStyle("-fx-text-fill: blue; -fx-font-weight: bold;");
                });

            } catch (NumberFormatException ex) {
                Dialogs.showErrorMessage("Input Error", "Please enter valid numeric values before testing.");
            } catch (IOException ex) {
                logger.error("Failed to save autofocus settings before test", ex);
                Dialogs.showErrorMessage("Save Error", "Failed to save settings before test: " + ex.getMessage());
            } catch (Exception ex) {
                logger.error("Failed to start sweep drift check test", ex);
                Dialogs.showErrorMessage("Test Error", "Failed to start sweep drift check test: " + ex.getMessage());
            }
        });

        // Create Standard Autofocus TitledPane with test button inside
        VBox standardContent = new VBox(8);
        standardContent.getChildren().addAll(standardGrid, testStandardButton);
        TitledPane standardPane = new TitledPane("Standard Autofocus (Symmetric Z-Sweep)", standardContent);
        standardPane.setCollapsible(false);

        // Create Sweep Drift Check TitledPane with test button inside
        VBox sweepContent = new VBox(8);
        sweepContent.getChildren().addAll(sweepGrid, testSweepButton);
        TitledPane sweepPane = new TitledPane("Sweep Drift Check (In-Acquisition Focus Correction)", sweepContent);
        sweepPane.setCollapsible(false);

        // ===== STREAMING AUTOFOCUS SECTION =====
        // Streaming AF is the Live Viewer "Autofocus" button -- a fast,
        // continuous-Z scan during stage motion. NOT used during
        // acquisition; that's what STANDARD + SWEEP above are for.
        // Stage-side knobs (slow_speed_value, normal_speed_value,
        // speed_property, slow_speed_um_per_s) live in
        // config_<scope>.yml under stage.streaming_af; this section
        // surfaces the current values read-only so operators can see
        // what governs streaming on this rig.
        TitledPane streamingPane = buildStreamingAfPane(configFile);

        // "Validate Autofocus" button -- runs both sweep + recovery test
        Button validateButton = new Button("Validate Autofocus Settings");
        validateButton.setTooltip(new Tooltip("Test your autofocus settings on the current tissue.\n\n"
                + "1. Manually focus on tissue using the Live Viewer first\n"
                + "2. Click this button to run a two-phase validation:\n"
                + "   - Phase 1: Sweep drift check from your focused position\n"
                + "   - Phase 2: Defocus 80% of search range, then full recovery\n"
                + "3. Results show how close each phase returns to your manual focus"));
        validateButton.setStyle("-fx-font-weight: bold;");
        validateButton.setOnAction(e -> {
            try {
                saveCurrentSettings.run();
                saveAutofocusSettings(autofocusFile, workingSettings, strategiesRef, bindingsRef);
                statusLabel.setText("Settings saved - running autofocus validation...");
                statusLabel.setStyle("-fx-text-fill: blue; -fx-font-weight: bold;");

                String currentObj = objectiveCombo.getValue();
                String testOutputPath = new File(configDir, "autofocus_tests").getAbsolutePath();

                // Run in background to avoid blocking UI
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    MicroscopeController mc = MicroscopeController.getInstance();
                    try {
                        if (!mc.isConnected()) {
                            Platform.runLater(() -> Dialogs.showErrorMessage(
                                    "Connection Required", "Please connect to the microscope server first."));
                            return;
                        }

                        // AF validation moves Z and snaps images. withAllLiveViewingOff
                        // pairs stopAllLiveViewing/restoreLiveViewState in a try/finally
                        // so the Live Viewer status updates correctly and frames resume
                        // afterwards even when the test throws.
                        mc.withAllLiveViewingOff(() -> {
                            var socketClient = mc.getSocketClient();
                            var result = socketClient.testAutofocusValidation(configPath, testOutputPath, currentObj);
                            Platform.runLater(() -> showValidationResult(result, statusLabel));
                        });

                    } catch (Exception ex) {
                        logger.error("Autofocus validation failed", ex);
                        Platform.runLater(() -> {
                            statusLabel.setText("Validation failed: " + ex.getMessage());
                            statusLabel.setStyle("-fx-text-fill: red; -fx-font-weight: bold;");
                            Dialogs.showErrorMessage(
                                    "Autofocus Validation Failed",
                                    "Error: " + ex.getMessage()
                                            + "\n\nMake sure you are focused on tissue before running the test.");
                        });
                    }
                });

            } catch (Exception ex) {
                Dialogs.showErrorMessage("Error", "Failed to start validation: " + ex.getMessage());
            }
        });

        // Write button row
        HBox buttonRow = new HBox(10, writeButton, validateButton);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        // ===== TAB 1: Per-Objective Parameters =====
        HBox objectiveRow = new HBox(10, objectiveLabel, objectiveCombo);
        objectiveRow.setAlignment(Pos.CENTER_LEFT);

        VBox sectionsBox = new VBox(10);
        sectionsBox.getChildren().addAll(acquisitionPane, tissuePane, standardPane, sweepPane, streamingPane);

        VBox tab1Content = new VBox(10);
        tab1Content.setPadding(new Insets(10));
        tab1Content.getChildren().addAll(objectiveRow, new Separator(), sectionsBox);

        Tab tab1 = new Tab("Per-Objective");
        tab1.setClosable(false);
        tab1.setContent(new ScrollPane(tab1Content));

        // ===== TAB 2: Strategy Library (v2) =====
        VBox tab2Content = buildStrategyTab(strategiesRef);
        Tab tab2 = new Tab("Strategies");
        tab2.setClosable(false);
        tab2.setContent(new ScrollPane(tab2Content));

        // ===== TAB 3: Modality Bindings (v2) =====
        VBox tab3Content = buildModalityBindingsTab(bindingsRef, strategiesRef);
        Tab tab3 = new Tab("Modality Bindings");
        tab3.setClosable(false);
        tab3.setContent(new ScrollPane(tab3Content));

        // ===== Tabbed layout =====
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.setPrefHeight(550);

        // Simple/Advanced toggle. Sticky preference: operators who only
        // open the editor to tune n_steps and score_metric stay in
        // Simple by default; users who need strategy/binding editing
        // flip to Advanced once and don't see Simple again. Hides
        // Strategies / Modality Bindings tabs and the Streaming AF
        // section, plus the per-row interp_*, edge_retries, and gap_*
        // fields that almost no one tunes.
        CheckBox advancedToggle = new CheckBox("Show advanced settings");
        advancedToggle.setSelected(QPPreferenceDialog.getAutofocusEditorAdvancedMode());
        advancedToggle.setTooltip(new Tooltip(
                "Simple: per-objective tab only, with the most-edited fields.\n"
                        + "Advanced: also shows the Strategies tab, Modality Bindings tab,\n"
                        + "Streaming AF section, plus the interp_* / edge_retries /\n"
                        + "gap_* fine-tuning fields. Setting persists across sessions."));

        List<javafx.scene.Node> advancedNodes = new ArrayList<>();
        advancedNodes.add(interpStrengthLabel);
        advancedNodes.add(interpStrengthSpinner);
        advancedNodes.add(interpStrengthDesc);
        advancedNodes.add(interpKindLabel);
        advancedNodes.add(interpKindCombo);
        advancedNodes.add(interpKindDesc);
        advancedNodes.add(edgeRetriesLabel);
        advancedNodes.add(edgeRetriesSpinner);
        advancedNodes.add(edgeRetriesDesc);
        advancedNodes.add(gapIndexLabel);
        advancedNodes.add(gapIndexSpinner);
        advancedNodes.add(gapIndexDesc);
        advancedNodes.add(gapSpatialLabel);
        advancedNodes.add(gapSpatialField);
        advancedNodes.add(gapSpatialDesc);
        advancedNodes.add(streamingPane);

        Runnable applyAdvancedMode = () -> {
            boolean adv = advancedToggle.isSelected();
            for (javafx.scene.Node n : advancedNodes) {
                n.setVisible(adv);
                n.setManaged(adv);
            }
            tabPane.getTabs().setAll(tab1);
            if (adv) {
                tabPane.getTabs().addAll(tab2, tab3);
            }
        };
        advancedToggle.selectedProperty().addListener((obs, o, n) -> {
            QPPreferenceDialog.setAutofocusEditorAdvancedMode(n);
            applyAdvancedMode.run();
        });
        applyAdvancedMode.run();

        mainLayout.getChildren().addAll(advancedToggle, tabPane, statusLabel, new Separator(), buttonRow);

        dialog.getDialogPane().setContent(mainLayout);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // OK button behavior - save if changed.
        // Rename to "Save and Close" so novices stop reading "OK" as
        // dismiss-without-saving (recurring confusion in usability
        // sessions). Functional behaviour unchanged.
        Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.setText("Save and Close");
        okButton.setOnAction(e -> {
            writeButton.fire(); // Trigger save
        });

        dialog.showAndWait();
    }

    /**
     * Extract microscope name from config filename
     * E.g., "config_PPM.yml" -> "PPM"
     */
    private static String extractMicroscopeName(String configFilename) {
        // Remove extension
        String nameWithoutExt = configFilename.replaceFirst("\\.[^.]+$", "");

        // Remove "config_" prefix if present
        if (nameWithoutExt.startsWith("config_")) {
            return nameWithoutExt.substring(7);
        }

        return nameWithoutExt;
    }

    /**
     * Estimate the mean camera FOV (in micrometers) for the given objective
     * by searching the available (modality, detector) combinations for one
     * that resolves a valid FOV.
     *
     * <p>Used only for the read-only "af_min_distance" display in the editor --
     * the authoritative value is computed at runtime by the Python acquisition
     * workflow using the active modality/detector, so this estimate may differ
     * from the runtime value if the objective is used under multiple modalities
     * with different camera geometries.
     *
     * @return mean((fov_x + fov_y) / 2) for the first working combo, or null
     *         if no combo resolves.
     */
    private static Double estimateMeanFovUmForObjective(MicroscopeConfigManager mgr, String objectiveId) {
        if (mgr == null || objectiveId == null) return null;
        try {
            Set<String> modalities = mgr.getAvailableModalities();
            Set<String> detectors = mgr.getHardwareDetectors();
            if (modalities == null || modalities.isEmpty() || detectors == null || detectors.isEmpty()) {
                return null;
            }
            for (String modality : modalities) {
                for (String detector : detectors) {
                    try {
                        double[] fov = mgr.getModalityFOV(modality, objectiveId, detector);
                        if (fov != null && fov.length >= 2 && fov[0] > 0 && fov[1] > 0) {
                            return (fov[0] + fov[1]) / 2.0;
                        }
                    } catch (Exception ignored) {
                        // Try next combination
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("estimateMeanFovUmForObjective failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Load list of objectives from hardware configuration section.
     */
    private static List<String> loadObjectivesFromConfig(MicroscopeConfigManager configManager) {
        List<String> objectives = new ArrayList<>();

        try {
            List<Map<String, Object>> hardwareObjectives = configManager.getHardwareObjectives();

            for (Map<String, Object> objectiveConfig : hardwareObjectives) {
                String objectiveId = (String) objectiveConfig.get("id");
                if (objectiveId != null && !objectives.contains(objectiveId)) {
                    objectives.add(objectiveId);
                }
            }
        } catch (Exception e) {
            logger.error("Error loading objectives from hardware config", e);
        }

        return objectives;
    }

    // =========================================================================
    // v2 Strategy + Modality Binding load/UI
    // =========================================================================

    private static Map<String, StrategyDefinition> loadStrategies(File autofocusFile) {
        Map<String, StrategyDefinition> result = new LinkedHashMap<>();
        if (!autofocusFile.exists()) return result;
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(Files.newInputStream(autofocusFile.toPath()));
            if (data == null) return result;
            @SuppressWarnings("unchecked")
            Map<String, Object> strategies = (Map<String, Object>) data.get("strategies");
            if (strategies == null) return result;
            for (Map.Entry<String, Object> entry : strategies.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> s = (Map<String, Object>) entry.getValue();
                @SuppressWarnings("unchecked")
                Map<String, Object> vp = s.get("validity_params") != null
                        ? new LinkedHashMap<>((Map<String, Object>) s.get("validity_params"))
                        : new LinkedHashMap<>();
                result.put(
                        entry.getKey(),
                        new StrategyDefinition(
                                entry.getKey(),
                                s.get("description") != null
                                        ? s.get("description").toString().trim()
                                        : "",
                                (String) s.get("score_metric"),
                                (String) s.get("validity_check"),
                                vp,
                                (String) s.get("on_failure")));
            }
            logger.info("Loaded {} strategies from {}", result.size(), autofocusFile.getName());
        } catch (Exception e) {
            logger.error("Error loading strategies from YAML", e);
        }
        return result;
    }

    private static Map<String, ModalityBinding> loadModalityBindings(File autofocusFile) {
        Map<String, ModalityBinding> result = new LinkedHashMap<>();
        if (!autofocusFile.exists()) return result;
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(Files.newInputStream(autofocusFile.toPath()));
            if (data == null) return result;
            @SuppressWarnings("unchecked")
            Map<String, Object> modalities = (Map<String, Object>) data.get("modalities");
            if (modalities == null) return result;
            for (Map.Entry<String, Object> entry : modalities.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) entry.getValue();
                Map<String, Object> overrides = new LinkedHashMap<>();
                String scoreOv = null;
                String failureOv = null;
                if (m.get("overrides") != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> ov = (Map<String, Object>) m.get("overrides");
                    if (ov.get("validity_params") != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> vp = (Map<String, Object>) ov.get("validity_params");
                        overrides.putAll(vp);
                    }
                    Object sm = ov.get("score_metric");
                    if (sm != null) scoreOv = String.valueOf(sm);
                    Object of = ov.get("on_failure");
                    if (of != null) failureOv = String.valueOf(of);
                }
                result.put(entry.getKey(), new ModalityBinding(
                        entry.getKey(), (String) m.get("strategy"),
                        overrides, scoreOv, failureOv));
            }
            logger.info("Loaded {} modality bindings from {}", result.size(), autofocusFile.getName());
        } catch (Exception e) {
            logger.error("Error loading modality bindings from YAML", e);
        }
        return result;
    }

    private static VBox buildStrategyTab(Map<String, StrategyDefinition> strategies) {
        VBox content = new VBox(8);
        content.setPadding(new Insets(10));
        VBox cardsBox = new VBox(6);
        Runnable rebuildCards = () -> {
            cardsBox.getChildren().clear();
            if (strategies.isEmpty()) {
                Label empty = new Label("No strategies defined. Click '+' to add one.");
                empty.setStyle("-fx-font-style: italic; -fx-text-fill: gray;");
                cardsBox.getChildren().add(empty);
                return;
            }
            for (StrategyDefinition sd : strategies.values()) {
                cardsBox.getChildren().add(buildStrategyCard(sd, strategies, cardsBox));
            }
        };
        rebuildCards.run();
        Button addBtn = new Button("+ Add Strategy");
        addBtn.setOnAction(e -> {
            TextInputDialog nameDialog = new TextInputDialog("new_strategy");
            nameDialog.setTitle("New Strategy");
            nameDialog.setHeaderText("Enter a name for the new strategy:");
            nameDialog.showAndWait().ifPresent(name -> {
                String key = name.trim().toLowerCase().replaceAll("\\s+", "_");
                if (key.isEmpty() || strategies.containsKey(key)) {
                    Dialogs.showWarningNotification("Invalid Name", "Name is empty or already exists: " + key);
                    return;
                }
                strategies.put(
                        key,
                        new StrategyDefinition(
                                key,
                                "",
                                "laplacian_variance",
                                "texture_and_area",
                                getDefaultValidityParams("texture_and_area"),
                                "defer"));
                rebuildCards.run();
            });
        });
        content.getChildren().addAll(cardsBox, new Separator(), addBtn);
        return content;
    }

    private static TitledPane buildStrategyCard(
            StrategyDefinition sd, Map<String, StrategyDefinition> allStrategies, VBox cardsBox) {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.setPadding(new Insets(6));
        int row = 0;

        TextArea descArea = new TextArea(sd.description);
        descArea.setPrefRowCount(2);
        descArea.setPrefColumnCount(40);
        descArea.setWrapText(true);
        descArea.textProperty().addListener((obs, o, n) -> sd.description = n);
        grid.add(new Label("Description:"), 0, row);
        grid.add(descArea, 1, row++);

        // Manifest-sourced grouped dropdown + "Help me pick" + resolution
        // preview, identical pattern to the per-objective combo above.
        // Manifest is loaded with null configDir here -- the strategy
        // card is independent of which scope is active.
        FocusMetricsManifest strategyManifest = FocusMetricsManifest.get(null);
        ComboBox<String> scoreCombo = buildScoreMetricCombo(sd.scoreMetric, strategyManifest);
        scoreCombo.valueProperty().addListener((obs, o, n) -> {
            if (n != null && !n.startsWith("---")) sd.scoreMetric = n;
        });
        scoreCombo.setTooltip(new Tooltip(
                "Default focus metric for this strategy. The per-modality binding\n"
                        + "or per-objective YAML can still override at runtime.\n"
                        + "Click 'Help me pick' for the manifest's per-metric guide."));
        Button scoreHelp = new Button("Help me pick");
        scoreHelp.setOnAction(e -> showMetricHelpDialog(strategyManifest));
        Label scoreResolution = new Label(resolutionPreviewText(strategyManifest, null, sd.scoreMetric));
        scoreResolution.setStyle("-fx-font-size: 10px; -fx-font-style: italic; -fx-text-fill: gray;");
        scoreCombo.valueProperty().addListener((obs, o, n) -> {
            if (n != null && !n.startsWith("---")) {
                scoreResolution.setText(resolutionPreviewText(strategyManifest, null, n));
            }
        });
        HBox scoreRow = new HBox(8, scoreCombo, scoreHelp);
        scoreRow.setAlignment(Pos.CENTER_LEFT);
        grid.add(new Label("Score metric:"), 0, row);
        grid.add(scoreRow, 1, row);
        grid.add(scoreResolution, 2, row++);

        ComboBox<String> validityCombo = new ComboBox<>();
        validityCombo.getItems().addAll(VALIDITY_CHECKS);
        validityCombo.setValue(sd.validityCheck);
        validityCombo.setTooltip(new Tooltip("How the AF system decides if a tile has enough signal to focus on.\n\n"
                + "texture_and_area: Requires texture gradient AND tissue area.\n"
                + "  Best for: H&E, IHC, PPM, confluent IF.\n\n"
                + "bright_spot_count: Counts bright local maxima above background.\n"
                + "  Best for: sparse fluorescence (beads, pollen, FISH spots).\n\n"
                + "total_gradient_energy: Whole-FOV gradient magnitude.\n"
                + "  Best for: SHG, dark-field, laser scanning.\n\n"
                + "always_false: Always fails -- used by manual_only to force\n"
                + "  the manual focus dialog every time."));
        grid.add(new Label("Validity check:"), 0, row);
        grid.add(validityCombo, 1, row++);

        ComboBox<String> failureCombo = new ComboBox<>();
        failureCombo.getItems().addAll(ON_FAILURE_MODES);
        failureCombo.setValue(sd.onFailure);
        failureCombo.setOnAction(e -> sd.onFailure = failureCombo.getValue());
        failureCombo.setTooltip(new Tooltip("What happens when validity check fails at a tile position.\n\n"
                + "defer: Skip this tile, try the next AF candidate.\n"
                + "  Correct for dense tissue where the next tile is better.\n\n"
                + "proceed: Run AF anyway on whatever signal is present.\n"
                + "  Correct for sparse/dark-field where no tile is guaranteed.\n\n"
                + "manual: Pop the manual focus dialog immediately.\n"
                + "  For training runs or edge-case samples."));
        grid.add(new Label("On failure:"), 0, row);
        grid.add(failureCombo, 1, row++);

        VBox paramsBox = new VBox(4);
        paramsBox.setPadding(new Insets(4, 0, 0, 20));
        Runnable rebuildParams = () -> {
            paramsBox.getChildren().clear();
            paramsBox.getChildren().add(new Label("Validity parameters:"));
            GridPane pg = new GridPane();
            pg.setHgap(6);
            pg.setVgap(4);
            int pr = 0;
            // Manifest is the source of truth for the param TYPE (int /
            // float / list_of_float) and RANGE. The editor was previously
            // building a TextField for every key, which let users type
            // "abc" into min_spots and only catch the error at YAML load
            // time. Now: int -> Spinner<Integer>, float -> Spinner<Double>
            // with min/max from the manifest, list_of_float -> two-element
            // numeric editor.
            FocusMetricsManifest paramManifest = FocusMetricsManifest.get(null);
            FocusMetricsManifest.ValidityCheckSpec vspec = paramManifest.getValidityChecks().get(sd.validityCheck);
            Map<String, FocusMetricsManifest.ParamSpec> paramSpecs = new LinkedHashMap<>();
            if (vspec != null) {
                for (FocusMetricsManifest.ParamSpec ps : vspec.params) {
                    paramSpecs.put(ps.name, ps);
                }
            }
            Map<String, Object> defaults = getDefaultValidityParams(sd.validityCheck);
            Map<String, Object> merged = new LinkedHashMap<>(defaults);
            merged.putAll(sd.validityParams);
            sd.validityParams.clear();
            sd.validityParams.putAll(merged);
            for (Map.Entry<String, Object> param : sd.validityParams.entrySet()) {
                String key = param.getKey();
                pg.add(new Label(key + ":"), 0, pr);
                FocusMetricsManifest.ParamSpec spec = paramSpecs.get(key);
                pg.add(buildParamEditor(key, param.getValue(), spec, sd.validityParams), 1, pr);
                pr++;
            }
            if (pr == 0) {
                pg.add(new Label("(none)"), 0, 0);
            }
            paramsBox.getChildren().add(pg);
        };
        rebuildParams.run();
        validityCombo.setOnAction(e -> {
            sd.validityCheck = validityCombo.getValue();
            sd.validityParams.clear();
            sd.validityParams.putAll(getDefaultValidityParams(sd.validityCheck));
            rebuildParams.run();
        });
        grid.add(paramsBox, 0, row, 2, 1);
        row++;

        Button duplicateBtn = new Button("Duplicate");
        duplicateBtn.setTooltip(new Tooltip(
                "Create a new strategy seeded with this strategy's score_metric,\n"
                        + "validity_check, validity_params, and on_failure. The clone\n"
                        + "name is " + sd.name + "_copy (or _copy_2, _copy_3, ...) and you\n"
                        + "can rename or edit it freely afterwards."));
        duplicateBtn.setOnAction(e -> {
            String cloneKey = uniqueCloneName(sd.name, allStrategies.keySet());
            StrategyDefinition clone = new StrategyDefinition(
                    cloneKey,
                    sd.description,
                    sd.scoreMetric,
                    sd.validityCheck,
                    new LinkedHashMap<>(sd.validityParams),
                    sd.onFailure);
            allStrategies.put(cloneKey, clone);
            cardsBox.getChildren().clear();
            for (StrategyDefinition s : allStrategies.values()) {
                cardsBox.getChildren().add(buildStrategyCard(s, allStrategies, cardsBox));
            }
        });

        Button deleteBtn = new Button("Delete Strategy");
        deleteBtn.setStyle("-fx-text-fill: red;");
        deleteBtn.setOnAction(e -> {
            allStrategies.remove(sd.name);
            cardsBox.getChildren().clear();
            for (StrategyDefinition s : allStrategies.values()) {
                cardsBox.getChildren().add(buildStrategyCard(s, allStrategies, cardsBox));
            }
            if (allStrategies.isEmpty()) {
                Label empty = new Label("No strategies defined. Click '+' to add one.");
                empty.setStyle("-fx-font-style: italic; -fx-text-fill: gray;");
                cardsBox.getChildren().add(empty);
            }
        });
        HBox actionRow = new HBox(8, duplicateBtn, deleteBtn);
        actionRow.setAlignment(Pos.CENTER_LEFT);
        grid.add(actionRow, 1, row);

        TitledPane pane = new TitledPane(sd.name, grid);
        pane.setExpanded(false);
        return pane;
    }

    /**
     * Generate a unique clone name like {@code dense_texture_copy} (or
     * {@code dense_texture_copy_2}, ...) given the existing strategy
     * keys. Used by the strategy-card "Duplicate" button.
     */
    private static String uniqueCloneName(String source, Set<String> existing) {
        String base = source + "_copy";
        if (!existing.contains(base)) return base;
        for (int i = 2; i < 1000; i++) {
            String candidate = base + "_" + i;
            if (!existing.contains(candidate)) return candidate;
        }
        // Pathological fallback.
        return base + "_" + System.currentTimeMillis();
    }

    private static Map<String, Object> getDefaultValidityParams(String validityCheck) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        if (validityCheck == null) return defaults;
        switch (validityCheck) {
            case "texture_and_area":
                defaults.put("texture_threshold", 0.010);
                defaults.put("tissue_area_threshold", 0.200);
                defaults.put("rgb_brightness_threshold", 240.0);
                defaults.put("tissue_mask_range", List.of(0.10, 0.90));
                defaults.put("median_floor", 15.0);
                break;
            case "bright_spot_count":
                defaults.put("spot_sigma_above_bg", 5.0);
                defaults.put("spot_min_separation_px", 8);
                defaults.put("min_spots", 3);
                defaults.put("min_peak_intensity", 20.0);
                defaults.put("bright_pixel_floor", 50.0);
                break;
            case "total_gradient_energy":
                defaults.put("min_gradient_energy", 0.002);
                break;
            case "always_false":
                break;
        }
        return defaults;
    }

    /**
     * Build a typed editor for a single validity-check parameter.
     * Manifest's ParamSpec drives the editor type and range:
     *   - "int"          -> Spinner&lt;Integer&gt; clamped to range
     *   - "float"        -> Spinner&lt;Double&gt; clamped to range
     *   - "list_of_float"-> two-element list editor (HBox of TextFields)
     *   - unknown / null -> fallback TextField (legacy path)
     *
     * The editor mutates {@code targetMap.put(key, ...)} on every valid
     * change so the parent dialog's save path picks up live edits
     * without an explicit commit step.
     */
    private static javafx.scene.Node buildParamEditor(
            String key, Object initialValue,
            FocusMetricsManifest.ParamSpec spec,
            Map<String, Object> targetMap) {
        // No spec -> fall back to the legacy TextField behaviour so an
        // unknown YAML key still appears editable.
        if (spec == null || spec.type == null) {
            return _legacyTextEditor(key, initialValue, targetMap);
        }
        switch (spec.type) {
            case "int": {
                int min = spec.rangeMin != null ? spec.rangeMin.intValue() : Integer.MIN_VALUE;
                int max = spec.rangeMax != null ? spec.rangeMax.intValue() : Integer.MAX_VALUE;
                int initial = toInt(initialValue, spec.defaultValue, 0);
                Spinner<Integer> sp = new Spinner<>(min, max, initial, 1);
                sp.setEditable(true);
                sp.setPrefWidth(150);
                sp.valueProperty().addListener((obs, o, n) -> {
                    if (n != null) targetMap.put(key, n);
                });
                return sp;
            }
            case "float": {
                double min = spec.rangeMin != null ? spec.rangeMin : -Double.MAX_VALUE;
                double max = spec.rangeMax != null ? spec.rangeMax : Double.MAX_VALUE;
                double initial = toDouble(initialValue, spec.defaultValue, 0.0);
                // Step is range / 100 with a sensible floor; lets sub-unit
                // params (like 0.005) still be tunable without typing.
                double step = Math.max((max - min) / 100.0, 0.001);
                Spinner<Double> sp = new Spinner<>(min, max, initial, step);
                sp.setEditable(true);
                sp.setPrefWidth(150);
                sp.valueProperty().addListener((obs, o, n) -> {
                    if (n != null) targetMap.put(key, n);
                });
                return sp;
            }
            case "list_of_float": {
                int len = spec.length != null ? spec.length : 2;
                @SuppressWarnings("unchecked")
                List<Number> initial = initialValue instanceof List
                        ? (List<Number>) initialValue
                        : (spec.defaultValue instanceof List
                            ? (List<Number>) spec.defaultValue
                            : Collections.emptyList());
                HBox row = new HBox(4);
                row.setAlignment(Pos.CENTER_LEFT);
                List<TextField> fields = new ArrayList<>();
                for (int i = 0; i < len; i++) {
                    double v = i < initial.size() ? initial.get(i).doubleValue() : 0.0;
                    TextField tf = new TextField(String.valueOf(v));
                    tf.setPrefWidth(70);
                    fields.add(tf);
                    row.getChildren().add(tf);
                }
                Runnable commit = () -> {
                    List<Double> list = new ArrayList<>();
                    boolean ok = true;
                    for (TextField tf : fields) {
                        try { list.add(Double.parseDouble(tf.getText().trim())); }
                        catch (NumberFormatException ex) { ok = false; break; }
                    }
                    if (ok) {
                        targetMap.put(key, list);
                        for (TextField tf : fields) tf.setStyle("");
                    } else {
                        for (TextField tf : fields) tf.setStyle("-fx-border-color: #F44336;");
                    }
                };
                for (TextField tf : fields) {
                    tf.textProperty().addListener((obs, o, n) -> commit.run());
                }
                return row;
            }
            default:
                return _legacyTextEditor(key, initialValue, targetMap);
        }
    }

    private static TextField _legacyTextEditor(
            String key, Object initialValue, Map<String, Object> targetMap) {
        TextField tf = new TextField(String.valueOf(initialValue));
        tf.setPrefWidth(150);
        tf.textProperty().addListener((obs, o, n) -> {
            try { targetMap.put(key, Double.parseDouble(n)); }
            catch (NumberFormatException ex) { targetMap.put(key, n); }
        });
        return tf;
    }

    private static int toInt(Object value, Object fallback, int finalFallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try { return Integer.parseInt(((String) value).trim()); }
            catch (NumberFormatException ignored) {}
        }
        if (fallback instanceof Number) return ((Number) fallback).intValue();
        return finalFallback;
    }

    private static double toDouble(Object value, Object fallback, double finalFallback) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) {
            try { return Double.parseDouble(((String) value).trim()); }
            catch (NumberFormatException ignored) {}
        }
        if (fallback instanceof Number) return ((Number) fallback).doubleValue();
        return finalFallback;
    }

    private static String formatList(List<? extends Number> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i));
        }
        sb.append("]");
        return sb.toString();
    }

    private static List<Double> parseList(String s) {
        List<Double> result = new ArrayList<>();
        s = s.replaceAll("[\\[\\]]", "").trim();
        if (s.isEmpty()) return result;
        for (String part : s.split(",")) {
            try {
                result.add(Double.parseDouble(part.trim()));
            } catch (NumberFormatException e) {
                // skip invalid parts
            }
        }
        return result;
    }

    private static VBox buildModalityBindingsTab(
            Map<String, ModalityBinding> bindings, Map<String, StrategyDefinition> strategies) {
        VBox content = new VBox(8);
        content.setPadding(new Insets(10));
        VBox rowsBox = new VBox(6);
        Runnable rebuildRows = () -> {
            rowsBox.getChildren().clear();
            if (bindings.isEmpty()) {
                Label empty = new Label("No modality bindings defined. Click '+' to add one.");
                empty.setStyle("-fx-font-style: italic; -fx-text-fill: gray;");
                rowsBox.getChildren().add(empty);
                return;
            }
            for (ModalityBinding mb : bindings.values()) {
                rowsBox.getChildren().add(buildBindingRow(mb, bindings, strategies, rowsBox));
            }
        };
        rebuildRows.run();
        Button addBtn = new Button("+ Add Binding");
        addBtn.setOnAction(e -> {
            TextInputDialog nameDialog = new TextInputDialog("modality_name");
            nameDialog.setTitle("New Modality Binding");
            nameDialog.setHeaderText("Enter the modality key (e.g. bf, fluorescence, ppm):");
            nameDialog.showAndWait().ifPresent(name -> {
                String key = name.trim().toLowerCase();
                if (key.isEmpty()) {
                    Dialogs.showWarningNotification("Invalid Name", "Name cannot be empty");
                    return;
                }
                String defaultStrategy = strategies.isEmpty()
                        ? "dense_texture"
                        : strategies.keySet().iterator().next();
                bindings.put(key, new ModalityBinding(key, defaultStrategy, new LinkedHashMap<>()));
                rebuildRows.run();
            });
        });
        content.getChildren().addAll(rowsBox, new Separator(), addBtn);
        return content;
    }

    private static VBox buildBindingRow(
            ModalityBinding mb,
            Map<String, ModalityBinding> allBindings,
            Map<String, StrategyDefinition> strategies,
            VBox rowsBox) {
        VBox container = new VBox(4);
        container.setPadding(new Insets(4));
        container.setStyle("-fx-border-color: #ddd; -fx-border-radius: 4;");
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);

        Label keyLabel = new Label(mb.modalityKey);
        keyLabel.setStyle("-fx-font-weight: bold; -fx-min-width: 100;");
        keyLabel.setTooltip(new Tooltip("Modality key matched via longest-prefix-wins (case-insensitive).\n"
                + "Common keys: bf, brightfield, ppm, fl, fluorescence, bf_if,\n"
                + "lsm, shg, 2p, confocal. Multiple keys can map to the same\n"
                + "strategy (e.g. both 'bf' and 'brightfield' -> dense_texture)."));

        ComboBox<String> strategyCombo = new ComboBox<>();
        strategyCombo.getItems().addAll(strategies.keySet());
        if (!strategies.containsKey(mb.strategyName)) {
            strategyCombo.getItems().add(mb.strategyName);
        }
        strategyCombo.setValue(mb.strategyName);
        strategyCombo.setOnAction(e -> mb.strategyName = strategyCombo.getValue());
        strategyCombo.setTooltip(new Tooltip("Which strategy from the Strategies tab this modality uses.\n"
                + "At acquisition time, the modality-aware loader picks the\n"
                + "strategy by matching the acquisition's modality name against\n"
                + "these keys (longest prefix wins). The user can still override\n"
                + "the strategy per-acquisition via the AF dropdown in the\n"
                + "acquisition wizard."));

        CheckBox overrideCheck = new CheckBox("Overrides");
        overrideCheck.setSelected(!mb.overrides.isEmpty());
        overrideCheck.setTooltip(new Tooltip("When checked, this modality overrides specific validity_params\n"
                + "from the base strategy. Overrides merge into the strategy\n"
                + "defaults -- only the listed parameters change, the rest keep\n"
                + "the strategy's values. Use for per-modality tuning (e.g.\n"
                + "looser tissue_mask_range for PPM polarized images)."));

        Button deleteBtn = new Button("X");
        deleteBtn.setStyle("-fx-text-fill: red; -fx-font-size: 10px;");
        deleteBtn.setOnAction(e -> {
            allBindings.remove(mb.modalityKey);
            rowsBox.getChildren().clear();
            if (allBindings.isEmpty()) {
                Label empty = new Label("No modality bindings defined. Click '+' to add one.");
                empty.setStyle("-fx-font-style: italic; -fx-text-fill: gray;");
                rowsBox.getChildren().add(empty);
            } else {
                for (ModalityBinding m : allBindings.values()) {
                    rowsBox.getChildren().add(buildBindingRow(m, allBindings, strategies, rowsBox));
                }
            }
        });
        row.getChildren().addAll(keyLabel, new Label("->"), strategyCombo, overrideCheck, deleteBtn);

        GridPane overrideGrid = new GridPane();
        overrideGrid.setHgap(6);
        overrideGrid.setVgap(4);
        overrideGrid.setPadding(new Insets(4, 0, 0, 20));
        boolean anyOv = mb.hasAnyOverride();
        overrideGrid.setVisible(anyOv);
        overrideGrid.setManaged(anyOv);

        // score_metric and on_failure overrides. Both null => use the
        // strategy's default. Sentinel "(use strategy default)" combo
        // entry maps to null on save. Manifest-sourced metric list +
        // hardcoded ON_FAILURE_MODES (only three valid values).
        FocusMetricsManifest bindingManifest = FocusMetricsManifest.get(null);
        GridPane bindingOverrideGrid = new GridPane();
        bindingOverrideGrid.setHgap(6);
        bindingOverrideGrid.setVgap(4);
        bindingOverrideGrid.setPadding(new Insets(4, 0, 0, 20));
        bindingOverrideGrid.setVisible(anyOv);
        bindingOverrideGrid.setManaged(anyOv);
        final String SENTINEL = "(use strategy default)";

        Label scoreOvLabel = new Label("score_metric override:");
        ComboBox<String> scoreOvCombo = new ComboBox<>();
        scoreOvCombo.getItems().add(SENTINEL);
        for (FocusMetricsManifest.MetricSpec ms : bindingManifest.metricsForDropdown()) {
            scoreOvCombo.getItems().add(ms.name);
        }
        scoreOvCombo.setValue(mb.scoreMetricOverride == null ? SENTINEL : mb.scoreMetricOverride);
        scoreOvCombo.setPrefWidth(260);
        scoreOvCombo.setTooltip(new Tooltip(
                "Per-modality override for the strategy's score_metric default.\n"
                        + "Loses to per-objective YAML score_metric (per-objective wins).\n"
                        + "Pick the sentinel to inherit the strategy's metric."));
        // Constraint-aware cell renderer: flag metrics whose
        // valid_modalities exclude this binding's modality so the
        // operator sees the mismatch BEFORE saving.
        scoreOvCombo.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); return; }
                FocusMetricsManifest.MetricSpec spec = bindingManifest.getMetrics().get(item);
                if (spec == null) {
                    setText(item);
                    setStyle("");
                    return;
                }
                String badges = metricConstraintBadges(spec);
                boolean modalityOk = spec.isValidForModality(mb.modalityKey);
                String label = badges.isEmpty() ? item : item + "  " + badges;
                if (!modalityOk) {
                    label = label + "  -- not for "
                            + FocusMetricsManifest.canonicalModality(mb.modalityKey);
                    setStyle("-fx-text-fill: #B0463F;");
                } else {
                    setStyle("");
                }
                setText(label);
            }
        });
        // Warning label that surfaces alongside the combo when the
        // current pick would be physically wrong for this modality.
        Label scoreOvWarn = new Label();
        scoreOvWarn.setStyle("-fx-text-fill: #B0463F; -fx-font-size: 10px;");
        scoreOvWarn.setWrapText(true);
        Runnable refreshOvWarn = () -> {
            String picked = mb.scoreMetricOverride;
            if (picked == null) {
                scoreOvWarn.setText("");
                return;
            }
            FocusMetricsManifest.MetricSpec spec =
                    bindingManifest.getMetrics().get(picked);
            if (spec == null || spec.isValidForModality(mb.modalityKey)) {
                scoreOvWarn.setText("");
                return;
            }
            scoreOvWarn.setText(
                    "Warning: metric '" + picked + "' is restricted to "
                            + String.join("/", spec.validModalities)
                            + " by the manifest. Saving will write it anyway, but "
                            + "the runtime will likely produce poor focus on a "
                            + FocusMetricsManifest.canonicalModality(mb.modalityKey)
                            + " sample. Pick a different metric or change the "
                            + "binding's modality key.");
        };
        scoreOvCombo.valueProperty().addListener((obs, o, n) -> {
            mb.scoreMetricOverride = (n == null || SENTINEL.equals(n)) ? null : n;
            refreshOvWarn.run();
        });
        refreshOvWarn.run();
        bindingOverrideGrid.add(scoreOvLabel, 0, 0);
        bindingOverrideGrid.add(scoreOvCombo, 1, 0);
        bindingOverrideGrid.add(scoreOvWarn, 2, 0);

        Label failOvLabel = new Label("on_failure override:");
        ComboBox<String> failOvCombo = new ComboBox<>();
        failOvCombo.getItems().add(SENTINEL);
        failOvCombo.getItems().addAll(ON_FAILURE_MODES);
        failOvCombo.setValue(mb.onFailureOverride == null ? SENTINEL : mb.onFailureOverride);
        failOvCombo.setPrefWidth(200);
        failOvCombo.setTooltip(new Tooltip(
                "Per-modality override for the strategy's on_failure default.\n"
                        + "Useful when the same strategy needs to defer for one\n"
                        + "modality but proceed for another. Pick the sentinel to\n"
                        + "inherit the strategy's setting."));
        failOvCombo.valueProperty().addListener((obs, o, n) -> {
            mb.onFailureOverride = (n == null || SENTINEL.equals(n)) ? null : n;
        });
        bindingOverrideGrid.add(failOvLabel, 0, 1);
        bindingOverrideGrid.add(failOvCombo, 1, 1);

        // Self-referencing Runnable via single-element array (Java lambda limitation).
        Runnable[] rebuildHolder = new Runnable[1];
        rebuildHolder[0] = () -> {
            overrideGrid.getChildren().clear();
            // Look up the strategy's validity check so the manifest's
            // ParamSpec can drive the editor type (same logic as the
            // strategy card uses).
            StrategyDefinition refStrategy = strategies.get(mb.strategyName);
            String vcheck = refStrategy != null ? refStrategy.validityCheck : null;
            FocusMetricsManifest.ValidityCheckSpec vspec = vcheck != null
                    ? bindingManifest.getValidityChecks().get(vcheck) : null;
            Map<String, FocusMetricsManifest.ParamSpec> specsByName = new LinkedHashMap<>();
            if (vspec != null) {
                for (FocusMetricsManifest.ParamSpec ps : vspec.params) {
                    specsByName.put(ps.name, ps);
                }
            }
            int pr = 0;
            for (Map.Entry<String, Object> param : mb.overrides.entrySet()) {
                String key = param.getKey();
                overrideGrid.add(new Label(key + ":"), 0, pr);
                overrideGrid.add(buildParamEditor(
                        key, param.getValue(), specsByName.get(key), mb.overrides), 1, pr);
                pr++;
            }
            if (pr == 0 && overrideCheck.isSelected()) {
                StrategyDefinition ref = strategies.get(mb.strategyName);
                if (ref != null && !ref.validityParams.isEmpty()) {
                    mb.overrides.putAll(ref.validityParams);
                    rebuildHolder[0].run();
                    return;
                }
                overrideGrid.add(new Label("(no parameters to override)"), 0, 0);
            }
        };
        overrideCheck.setOnAction(e -> {
            boolean show = overrideCheck.isSelected();
            overrideGrid.setVisible(show);
            overrideGrid.setManaged(show);
            bindingOverrideGrid.setVisible(show);
            bindingOverrideGrid.setManaged(show);
            if (show && mb.overrides.isEmpty()) {
                rebuildHolder[0].run();
            }
            if (!show) {
                mb.overrides.clear();
                mb.scoreMetricOverride = null;
                mb.onFailureOverride = null;
                scoreOvCombo.setValue(SENTINEL);
                failOvCombo.setValue(SENTINEL);
            }
        });
        rebuildHolder[0].run();

        container.getChildren().addAll(row, bindingOverrideGrid, overrideGrid);
        return container;
    }

    // =========================================================================
    // Legacy-alias migration (volath5 -> vollath_f5, tenenbaum_gradient ->
    // tenengrad, ...). Driven by FocusMetricsManifest.removed_aliases.
    // =========================================================================

    /**
     * Scan the autofocus YAML for any {@code score_metric: <legacy>}
     * lines whose value matches a {@code removed_aliases} entry in the
     * manifest. Returns a map of {@code old -> canonical} replacements
     * actually present in the file (empty if none).
     *
     * <p>Pattern is anchored to {@code score_metric:} so docstrings,
     * comments mentioning the old name, etc. are left alone -- mirrors
     * the {@code microscope_configurations/scripts/migrate_autofocus_yaml.py}
     * regex used by the M5 migration script.
     */
    private static Map<String, String> findDeprecatedAliasesInFile(
            File file, FocusMetricsManifest manifest) {
        Map<String, String> found = new LinkedHashMap<>();
        if (file == null || !file.exists() || manifest == null
                || manifest.getRemovedAliases().isEmpty()) {
            return found;
        }
        String text;
        try {
            text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("Could not read autofocus file for migration check: {}", e.getMessage());
            return found;
        }
        for (Map.Entry<String, String> e : manifest.getRemovedAliases().entrySet()) {
            Pattern p = aliasPattern(e.getKey());
            if (p.matcher(text).find()) {
                found.put(e.getKey(), e.getValue());
            }
        }
        return found;
    }

    /**
     * Rewrite the autofocus YAML in place, replacing every legacy alias
     * with its canonical name. Saves a {@code <file>.pre-migration}
     * backup first. No-op if no replacements actually fire.
     */
    private static boolean migrateAliasesInFile(File file, Map<String, String> aliases)
            throws IOException {
        String original = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        String text = original;
        for (Map.Entry<String, String> e : aliases.entrySet()) {
            Pattern p = aliasPattern(e.getKey());
            text = p.matcher(text).replaceAll(
                    "$1" + Matcher.quoteReplacement(e.getValue()) + "$2");
        }
        if (text.equals(original)) return false;
        File backup = new File(file.getAbsolutePath() + ".pre-migration");
        Files.copy(file.toPath(), backup.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        logger.info("Migrated autofocus YAML aliases in {} ({} replacement(s)); backup at {}",
                file.getName(), aliases.size(), backup.getName());
        return true;
    }

    private static Pattern aliasPattern(String oldName) {
        // Multiline mode so $ matches end-of-line, not end-of-text.
        return Pattern.compile(
                "(?m)(\\bscore_metric\\s*:\\s*['\"]?)"
                        + Pattern.quote(oldName)
                        + "(['\"]?\\s*(?:#.*)?$)");
    }

    /**
     * Show a one-time banner offering to migrate legacy alias names to
     * their canonical replacements. Three buttons:
     * <ul>
     *   <li>Migrate now -- back up and rewrite the YAML</li>
     *   <li>Not now -- leave the file as-is for this session</li>
     *   <li>Don't ask again -- set the persistent suppress preference</li>
     * </ul>
     */
    private static void maybeShowMigrationBanner(
            File file, Map<String, String> found, Window owner) {
        if (found.isEmpty()) return;
        if (QPPreferenceDialog.getAutofocusYamlMigrationAcknowledged()) {
            logger.info(
                    "Autofocus YAML in {} contains legacy aliases {}; banner suppressed by preference.",
                    file.getName(), found.keySet());
            return;
        }
        StringBuilder body = new StringBuilder();
        body.append("This autofocus YAML uses metric names that have been renamed:\n\n");
        for (Map.Entry<String, String> e : found.entrySet()) {
            body.append("  ").append(e.getKey())
                    .append("  ->  ").append(e.getValue()).append("\n");
        }
        body.append("\nThe runtime no longer accepts the old names and will refuse the file.\n\n");
        body.append("Migrate now? A backup will be saved as ")
                .append(file.getName()).append(".pre-migration");

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Autofocus YAML Migration");
        alert.setHeaderText(file.getName() + ": legacy metric names detected");
        alert.setContentText(body.toString());
        ButtonType migrate = new ButtonType("Migrate now", ButtonBar.ButtonData.YES);
        ButtonType notNow = new ButtonType("Not now", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType dontAsk = new ButtonType("Don't ask again", ButtonBar.ButtonData.NO);
        alert.getButtonTypes().setAll(migrate, notNow, dontAsk);
        Optional<ButtonType> choice = UIFunctions.showAlertOverParent(alert, owner);
        if (choice.isPresent() && choice.get() == migrate) {
            try {
                migrateAliasesInFile(file, found);
                Alert ok = new Alert(Alert.AlertType.INFORMATION,
                        "Migrated " + found.size() + " alias(es). Backup saved as "
                                + file.getName() + ".pre-migration");
                ok.setTitle("Migration Complete");
                ok.setHeaderText(null);
                UIFunctions.showAlertOverParent(ok, owner);
            } catch (IOException ex) {
                logger.error("Failed to migrate autofocus YAML", ex);
                Alert err = new Alert(Alert.AlertType.ERROR,
                        "Failed to migrate: " + ex.getMessage());
                err.setTitle("Migration Failed");
                err.setHeaderText(null);
                UIFunctions.showAlertOverParent(err, owner);
            }
        } else if (choice.isPresent() && choice.get() == dontAsk) {
            QPPreferenceDialog.setAutofocusYamlMigrationAcknowledged(true);
            logger.info("User dismissed autofocus YAML migration banner permanently.");
        }
    }

    /**
     * Load autofocus settings from YAML file
     */
    private static Map<String, AutofocusSettings> loadAutofocusSettings(File autofocusFile) {
        Map<String, AutofocusSettings> settings = new LinkedHashMap<>();

        if (!autofocusFile.exists()) {
            logger.info("Autofocus config file does not exist yet: {}", autofocusFile.getAbsolutePath());
            return settings;
        }

        try {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(Files.newInputStream(autofocusFile.toPath()));

            if (data != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> afSettings = (List<Map<String, Object>>) data.get("autofocus_settings");

                if (afSettings != null) {
                    for (Map<String, Object> entry : afSettings) {
                        String objective = (String) entry.get("objective");
                        int nSteps = ((Number) entry.get("n_steps")).intValue();
                        double searchRange = ((Number) entry.get("search_range_um")).doubleValue();
                        int nTiles = ((Number) entry.get("n_tiles")).intValue();

                        // Optional parameters with defaults
                        int interpStrength = entry.containsKey("interp_strength")
                                ? ((Number) entry.get("interp_strength")).intValue()
                                : 100;
                        String interpKind =
                                entry.containsKey("interp_kind") ? (String) entry.get("interp_kind") : "quadratic";
                        String scoreMetric = entry.containsKey("score_metric")
                                ? (String) entry.get("score_metric")
                                : "laplacian_variance";
                        double textureThreshold = entry.containsKey("texture_threshold")
                                ? ((Number) entry.get("texture_threshold")).doubleValue()
                                : 0.005;
                        double tissueAreaThreshold = entry.containsKey("tissue_area_threshold")
                                ? ((Number) entry.get("tissue_area_threshold")).doubleValue()
                                : 0.2;

                        // Sweep drift check parameters with defaults
                        double sweepRangeUm = entry.containsKey("sweep_range_um")
                                ? ((Number) entry.get("sweep_range_um")).doubleValue()
                                : 10.0;
                        int sweepNSteps = entry.containsKey("sweep_n_steps")
                                ? ((Number) entry.get("sweep_n_steps")).intValue()
                                : 6;

                        int edgeRetries =
                                entry.containsKey("edge_retries") ? ((Number) entry.get("edge_retries")).intValue() : 2;

                        // Legacy support: old adaptive_initial_step_um -> sweep_range_um
                        if (!entry.containsKey("sweep_range_um") && entry.containsKey("adaptive_initial_step_um")) {
                            sweepRangeUm = ((Number) entry.get("adaptive_initial_step_um")).doubleValue() * 2;
                        }

                        // Safety-net gap multipliers with defaults matching the Python side.
                        int gapIndexMultiplier = entry.containsKey("gap_index_multiplier")
                                ? ((Number) entry.get("gap_index_multiplier")).intValue()
                                : 3;
                        double gapSpatialMultiplier = entry.containsKey("gap_spatial_multiplier")
                                ? ((Number) entry.get("gap_spatial_multiplier")).doubleValue()
                                : 2.0;

                        // Additive opt-out: missing key = legacy behaviour = enabled.
                        boolean p98Fallback = !entry.containsKey("p98_p2_fallback_enabled")
                                || Boolean.TRUE.equals(entry.get("p98_p2_fallback_enabled"));

                        logger.info(
                                "Loaded from YAML - objective='{}', n_steps={}, search_range={}, sweep_range={}, sweep_n_steps={}, edge_retries={}, gap_index={}, gap_spatial={}, p98_fallback={}",
                                objective,
                                nSteps,
                                searchRange,
                                sweepRangeUm,
                                sweepNSteps,
                                edgeRetries,
                                gapIndexMultiplier,
                                gapSpatialMultiplier,
                                p98Fallback);

                        AutofocusSettings loaded = new AutofocusSettings(
                                objective,
                                nSteps,
                                searchRange,
                                nTiles,
                                interpStrength,
                                interpKind,
                                scoreMetric,
                                textureThreshold,
                                tissueAreaThreshold,
                                sweepRangeUm,
                                sweepNSteps,
                                edgeRetries,
                                gapIndexMultiplier,
                                gapSpatialMultiplier);
                        loaded.p98P2FallbackEnabled = p98Fallback;
                        settings.put(objective, loaded);
                    }
                }
            }

            logger.info("Loaded autofocus settings for {} objectives", settings.size());
        } catch (Exception e) {
            logger.error("Error loading autofocus settings from file", e);
        }

        return settings;
    }

    /**
     * Save autofocus settings to YAML file
     */
    private static void saveAutofocusSettings(
            File autofocusFile,
            Map<String, AutofocusSettings> settings,
            Map<String, StrategyDefinition> strategies,
            Map<String, ModalityBinding> bindings)
            throws IOException {
        // Build YAML structure
        List<Map<String, Object>> afSettingsList = new ArrayList<>();

        for (AutofocusSettings setting : settings.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("objective", setting.objective);
            entry.put("calibrated", true);
            entry.put("n_steps", setting.nSteps);
            entry.put("search_range_um", setting.searchRangeUm);
            entry.put("n_tiles", setting.nTiles);
            entry.put("interp_strength", setting.interpStrength);
            entry.put("interp_kind", setting.interpKind);
            entry.put("score_metric", setting.scoreMetric);
            entry.put("texture_threshold", setting.textureThreshold);
            entry.put("tissue_area_threshold", setting.tissueAreaThreshold);
            entry.put("sweep_range_um", setting.sweepRangeUm);
            entry.put("sweep_n_steps", setting.sweepNSteps);
            entry.put("edge_retries", setting.edgeRetries);
            entry.put("gap_index_multiplier", setting.gapIndexMultiplier);
            entry.put("gap_spatial_multiplier", setting.gapSpatialMultiplier);
            entry.put("p98_p2_fallback_enabled", setting.p98P2FallbackEnabled);
            afSettingsList.add(entry);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", 2);
        root.put("autofocus_settings", afSettingsList);

        // v2 strategy library
        if (strategies != null && !strategies.isEmpty()) {
            Map<String, Object> strategiesMap = new LinkedHashMap<>();
            for (StrategyDefinition sd : strategies.values()) {
                strategiesMap.put(sd.name, sd.toYamlMap());
            }
            root.put("strategies", strategiesMap);
        }

        // v2 modality bindings
        if (bindings != null && !bindings.isEmpty()) {
            Map<String, Object> modalitiesMap = new LinkedHashMap<>();
            for (ModalityBinding mb : bindings.values()) {
                modalitiesMap.put(mb.modalityKey, mb.toYamlMap());
            }
            root.put("modalities", modalitiesMap);
        }

        // Configure YAML dumper for clean output
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);

        Yaml yaml = new Yaml(options);

        // Write with manifest-sourced header comment so the comment block
        // can never drift from the actual metric / strategy / modality
        // vocabulary the runtime accepts.
        try (FileWriter writer = new FileWriter(autofocusFile, StandardCharsets.UTF_8)) {
            writer.write(FocusMetricsManifest.get(autofocusFile.getParentFile() != null
                    ? autofocusFile.getParentFile().toPath() : null).headerCommentBlock());
            yaml.dump(root, writer);
        }

        logger.info(
                "Saved autofocus settings for {} objectives to: {}", settings.size(), autofocusFile.getAbsolutePath());

        // Reload config so acquisition uses the updated autofocus parameters.
        // The config manager is a singleton keyed by config path -- we use
        // getInstanceIfAvailable() which returns null if not yet initialized.
        try {
            var mgr = MicroscopeConfigManager.getInstanceIfAvailable();
            if (mgr != null) {
                // Derive main config path from the autofocus file:
                // autofocus_PPM.yml -> config_PPM.yml (same directory)
                String afName = autofocusFile.getName(); // "autofocus_PPM.yml"
                String configName = afName.replace("autofocus_", "config_");
                String configPath = new File(autofocusFile.getParent(), configName).getAbsolutePath();
                mgr.reload(configPath);
                logger.info("Reloaded config after autofocus settings save");
            }
            // Tell the Python server to re-read the YAML too
            try {
                MicroscopeController.getInstance().sendReconfig();
            } catch (Exception reconfigEx) {
                logger.warn("Server config reload failed (non-fatal): {}", reconfigEx.getMessage());
            }
        } catch (Exception reloadEx) {
            logger.warn("Could not reload config after AF save: {}", reloadEx.getMessage());
        }
    }

    /**
     * Shows the autofocus validation result dialog (public entry point for Acquisition Wizard).
     */
    public static void showValidationResultStatic(Map<String, String> result) {
        showValidationResult(result, null);
    }

    /**
     * Shows the autofocus validation result dialog.
     */
    private static void showValidationResult(Map<String, String> result, Label statusLabel) {
        String sweepDelta = result.getOrDefault("sweep_delta_um", "?");
        String recoveryDelta = result.getOrDefault("recovery_delta_um", "?");
        String groundTruth = result.getOrDefault("ground_truth_z", "?");
        String sweepZ = result.getOrDefault("sweep_z", "?");
        String recoveryZ = result.getOrDefault("recovery_z", "?");
        String defocusDist = result.getOrDefault("defocus_distance_um", "?");

        boolean sweepOk = false;
        boolean recoveryOk = false;
        try {
            sweepOk = Double.parseDouble(sweepDelta) < 5.0;
        } catch (NumberFormatException ignored) {
        }
        try {
            recoveryOk = !"FAILED".equals(recoveryDelta) && Double.parseDouble(recoveryDelta) < 5.0;
        } catch (NumberFormatException ignored) {
        }

        String sweepStatus = sweepOk ? "[PASS]" : "[WARN]";
        String recoveryStatus = recoveryOk ? "[PASS]" : ("FAILED".equals(recoveryDelta) ? "[FAIL]" : "[WARN]");

        boolean allPass = sweepOk && recoveryOk;

        StringBuilder sb = new StringBuilder();
        sb.append("Autofocus Validation Results\n");
        sb.append("===========================\n\n");
        sb.append(String.format("Manual focus (ground truth): Z = %s um\n\n", groundTruth));
        sb.append(String.format(
                "Phase 1 - Sweep Drift Check %s\n" + "  Sweep found: Z = %s um (delta: %s um)\n\n",
                sweepStatus, sweepZ, sweepDelta));
        sb.append(String.format(
                "Phase 2 - Recovery from %s um defocus %s\n" + "  Autofocus recovered: Z = %s um (delta: %s um)\n\n",
                defocusDist, recoveryStatus, recoveryZ, recoveryDelta));

        if (allPass) {
            sb.append("Your autofocus settings are working well for this tissue.");
        } else {
            sb.append("SUGGESTIONS:\n");
            if (!sweepOk) {
                sb.append("  - Sweep drift check was inaccurate. Try a different score_metric\n");
                sb.append("    or increase sweep_n_steps.\n");
            }
            if (!recoveryOk) {
                sb.append("  - Full autofocus did not recover well. Try increasing n_steps\n");
                sb.append("    or search_range_um, or try a different score_metric.\n");
            }
        }

        Alert alert = new Alert(allPass ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING);
        alert.setTitle("Autofocus Validation");
        alert.setHeaderText(allPass ? "Settings validated successfully" : "Settings may need adjustment");

        TextArea textArea = new TextArea(sb.toString());
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        textArea.setPrefHeight(300);

        // "Open AF Configuration" button to jump directly to the editor
        javafx.scene.control.Button afConfigBtn = new javafx.scene.control.Button("Open AF Configuration...");
        afConfigBtn.setOnAction(e -> {
            alert.close();
            try {
                qupath.ext.qpsc.controller.QPScopeController.getInstance().startWorkflow("autofocusEditor");
            } catch (Exception ex) {
                logger.warn("Could not open AF configuration editor: {}", ex.getMessage());
            }
        });
        javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(8, textArea, afConfigBtn);
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setMinWidth(500);
        alert.showAndWait();

        if (statusLabel != null) {
            statusLabel.setText(allPass ? "Validation PASSED" : "Validation: check results");
            statusLabel.setStyle(
                    allPass
                            ? "-fx-text-fill: green; -fx-font-weight: bold;"
                            : "-fx-text-fill: orange; -fx-font-weight: bold;");
        }
    }
}
