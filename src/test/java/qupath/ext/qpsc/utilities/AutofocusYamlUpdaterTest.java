package qupath.ext.qpsc.utilities;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Saving from the Autofocus Editor must not delete what the editor cannot see.
 *
 * <p>The old save built a fresh document from a fixed key list and dumped it over the file.
 * On PPM that removed {@code channel_reduction: green} from the 20x entry -- a key the
 * server reads and the editor has no field for, so the September dust fix would have
 * reverted itself the first time anybody opened the dialog -- and deleted 42 comment lines
 * recording why {@code brenner_gradient} was chosen over {@code p98_p2} after two
 * reversals.
 *
 * <p>The fixture below is shaped like the real {@code autofocus_PPM.yml}: a generated
 * header, per-objective rationale comments, an unmodelled key, a strategy block carrying
 * its own argument, and a top-level block this writer knows nothing about.
 */
class AutofocusYamlUpdaterTest {

    private static final String HEADER = "# ========== AUTOFOCUS CONFIGURATION ==========\n"
            + "# Vocabulary is sourced from focus_metrics_manifest.yml.\n";

    private static final String FIXTURE = HEADER + "schema_version: 2\n"
            + "autofocus_settings:\n"
            + "- objective: LOCI_OBJECTIVE_OLYMPUS_10X_001\n"
            + "  calibrated: true\n"
            + "  n_steps: 35\n"
            + "  search_range_um: 50.0\n"
            + "  n_tiles: 4\n"
            + "  score_metric: brenner_gradient\n"
            + "- objective: LOCI_OBJECTIVE_OLYMPUS_20X_POL_001\n"
            + "  calibrated: true\n"
            + "  n_steps: 100\n"
            + "  search_range_um: 100.0\n"
            + "  n_tiles: 1\n"
            + "  # 2026-08-28. Settled after two reversals; brenner reads flat on an empty\n"
            + "  # field, which is the truth, while p98_p2 invents a confident peak there.\n"
            + "  score_metric: brenner_gradient\n"
            + "  # 2026-09-12. Score the green plane only: debris is achromatic, stain is not.\n"
            + "  channel_reduction: green\n"
            + "strategies:\n"
            + "  stained_colour:\n"
            + "    # WHY NOT texture_and_area here: bare glass PASSES it, which is how a\n"
            + "    # focus landed 200 um off the sample and logged a success.\n"
            + "    description: Decides tissue by stain colour.\n"
            + "    score_metric: laplacian_variance\n"
            + "    validity_check: chroma_deviation\n"
            + "    validity_params:\n"
            + "      chroma_area_threshold: 0.15\n"
            + "      median_chroma: 20.0\n"
            + "    on_failure: defer\n"
            + "focus_surface:\n"
            + "  mode: observe\n";

    /** The document the editor would hand the writer, mirroring saveAutofocusSettings. */
    private static Map<String, Object> desired(int tenXSteps) {
        Map<String, Object> tenX = new LinkedHashMap<>();
        tenX.put("objective", "LOCI_OBJECTIVE_OLYMPUS_10X_001");
        tenX.put("calibrated", true);
        tenX.put("n_steps", tenXSteps);
        tenX.put("search_range_um", 50.0);
        tenX.put("n_tiles", 4);
        tenX.put("score_metric", "brenner_gradient");

        Map<String, Object> twentyX = new LinkedHashMap<>();
        twentyX.put("objective", "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001");
        twentyX.put("calibrated", true);
        twentyX.put("n_steps", 100);
        twentyX.put("search_range_um", 100.0);
        twentyX.put("n_tiles", 1);
        twentyX.put("score_metric", "brenner_gradient");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("chroma_area_threshold", 0.15);
        params.put("median_chroma", 20.0);
        Map<String, Object> strategy = new LinkedHashMap<>();
        strategy.put("description", "Decides tissue by stain colour.");
        strategy.put("score_metric", "laplacian_variance");
        strategy.put("validity_check", "chroma_deviation");
        strategy.put("validity_params", params);
        strategy.put("on_failure", "defer");
        Map<String, Object> strategies = new LinkedHashMap<>();
        strategies.put("stained_colour", strategy);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", 2);
        root.put("autofocus_settings", List.of(tenX, twentyX));
        root.put("strategies", strategies);
        return root;
    }

    private static Path fixture(Path dir) throws IOException {
        Path file = dir.resolve("autofocus_PPM.yml");
        Files.writeString(file, FIXTURE, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void anUnmodelledKeySurvivesASave(@TempDir Path dir) throws Exception {
        Path file = fixture(dir);

        AutofocusYamlUpdater.update(file, HEADER, desired(35));

        // The exact regression: channel_reduction has no field in AutofocusSettings.
        assertThat(Files.readString(file))
                .as("a key the editor does not model must not be deleted by the editor")
                .contains("channel_reduction: green");
    }

    @Test
    void everyCommentSurvivesAValueChange(@TempDir Path dir) throws Exception {
        Path file = fixture(dir);

        AutofocusYamlUpdater.Result result = AutofocusYamlUpdater.update(file, HEADER, desired(40));

        assertThat(result.changed()).isTrue();
        assertThat(result.fullRender())
                .as("an existing file is edited, never re-rendered")
                .isFalse();
        String after = Files.readString(file);
        assertThat(after).contains("n_steps: 40");
        assertThat(after)
                .as("the metric rationale is why nobody re-litigates these settings")
                .contains("2026-08-28. Settled after two reversals")
                .contains("2026-09-12. Score the green plane only")
                .contains("WHY NOT texture_and_area here")
                .contains("focus landed 200 um off the sample");
        assertThat(after).as("unknown top-level blocks pass through").contains("focus_surface:");
    }

    @Test
    void anUnchangedSaveRewritesNothing(@TempDir Path dir) throws Exception {
        Path file = fixture(dir);
        String before = Files.readString(file);

        AutofocusYamlUpdater.Result result = AutofocusYamlUpdater.update(file, HEADER, desired(35));

        assertThat(result.changed()).as("a no-op save must not touch the file").isFalse();
        assertThat(Files.readString(file)).isEqualTo(before);
    }

    @Test
    void onlyTheChangedLineChanges(@TempDir Path dir) throws Exception {
        Path file = fixture(dir);
        List<String> before = Files.readAllLines(file);

        AutofocusYamlUpdater.update(file, HEADER, desired(40));

        List<String> after = Files.readAllLines(file);
        assertThat(after).hasSameSizeAs(before);
        List<String> differing = new ArrayList<>();
        for (int i = 0; i < before.size(); i++) {
            if (!before.get(i).equals(after.get(i))) {
                differing.add(before.get(i) + " => " + after.get(i));
            }
        }
        assertThat(differing).as("a one-field edit is a one-line edit").hasSize(1);
        assertThat(differing.get(0)).contains("n_steps: 35 =>   n_steps: 40");
    }

    @Test
    void anEquivalentNumberIsNotTreatedAsAChange(@TempDir Path dir) throws Exception {
        // autofocus_mock.yml really does write "texture_threshold: 0.010". Rendering that
        // back as 0.01 is the same number, so the line must not be rewritten -- a diff with
        // unexplainable churn in it is a diff nobody reads.
        Path file = dir.resolve("autofocus_PPM.yml");
        Files.writeString(
                file,
                HEADER + "schema_version: 2\n"
                        + "autofocus_settings:\n"
                        + "- objective: OBJ\n"
                        + "  texture_threshold: 0.010\n"
                        + "  search_range_um: 50\n",
                StandardCharsets.UTF_8);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("objective", "OBJ");
        item.put("texture_threshold", 0.01);
        item.put("search_range_um", 50.0);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", 2);
        root.put("autofocus_settings", List.of(item));

        AutofocusYamlUpdater.Result result = AutofocusYamlUpdater.update(file, HEADER, root);

        assertThat(result.changed()).isFalse();
        assertThat(Files.readString(file)).contains("texture_threshold: 0.010").contains("search_range_um: 50");
    }

    @Test
    void strategiesAreNotRerenderedWhenOnlyNumericBoxingDiffers(@TempDir Path dir) throws Exception {
        Path file = fixture(dir);
        Map<String, Object> root = desired(35);
        // SnakeYAML reads 0.15 as a Double; the editor's spinner may hand back an Integer-
        // valued Double or an Integer. That must not read as "the strategy changed", or the
        // save would re-render the block and delete the argument written above it.
        @SuppressWarnings("unchecked")
        Map<String, Object> strategies = (Map<String, Object>) root.get("strategies");
        @SuppressWarnings("unchecked")
        Map<String, Object> strategy = (Map<String, Object>) strategies.get("stained_colour");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) strategy.get("validity_params");
        params.put("median_chroma", 20); // was 20.0

        AutofocusYamlUpdater.update(file, HEADER, root);

        assertThat(Files.readString(file)).contains("WHY NOT texture_and_area here");
    }

    @Test
    void aChangedStrategyIsRewrittenAndSaysItLostComments(@TempDir Path dir) throws Exception {
        Path file = fixture(dir);
        Map<String, Object> root = desired(35);
        @SuppressWarnings("unchecked")
        Map<String, Object> strategies = (Map<String, Object>) root.get("strategies");
        @SuppressWarnings("unchecked")
        Map<String, Object> strategy = (Map<String, Object>) strategies.get("stained_colour");
        strategy.put("on_failure", "proceed");

        AutofocusYamlUpdater.Result result = AutofocusYamlUpdater.update(file, HEADER, root);

        String after = Files.readString(file);
        assertThat(after).contains("on_failure: proceed");
        assertThat(result.edits()).anyMatch(e -> e.contains("strategies") && e.contains("comments"));
        // The rest of the document is untouched by a strategies change.
        assertThat(after)
                .contains("channel_reduction: green")
                .contains("2026-08-28")
                .contains("focus_surface:");
    }

    @Test
    void aNewObjectiveIsAppendedWithoutDisturbingTheOthers(@TempDir Path dir) throws Exception {
        Path file = fixture(dir);
        Map<String, Object> root = desired(35);
        Map<String, Object> fortyX = new LinkedHashMap<>();
        fortyX.put("objective", "LOCI_OBJECTIVE_OLYMPUS_40X_POL_001");
        fortyX.put("calibrated", false);
        fortyX.put("n_steps", 20);
        List<Map<String, Object>> items = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> current = (List<Map<String, Object>>) root.get("autofocus_settings");
        items.addAll(current);
        items.add(fortyX);
        root.put("autofocus_settings", items);

        AutofocusYamlUpdater.update(file, HEADER, root);

        String after = Files.readString(file);
        assertThat(after).contains("- objective: LOCI_OBJECTIVE_OLYMPUS_40X_POL_001");
        assertThat(after).contains("channel_reduction: green").contains("2026-09-12");
        // Appended into the list, not after the next section.
        assertThat(after.indexOf("LOCI_OBJECTIVE_OLYMPUS_40X_POL_001")).isLessThan(after.indexOf("strategies:"));
    }

    @Test
    void aMissingFileIsRenderedFromScratch(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("autofocus_NEW.yml");

        AutofocusYamlUpdater.Result result = AutofocusYamlUpdater.update(file, HEADER, desired(35));

        assertThat(result.fullRender()).isTrue();
        String written = Files.readString(file);
        assertThat(written).startsWith("# ========== AUTOFOCUS CONFIGURATION ==========");
        assertThat(written).contains("schema_version: 2").contains("n_steps: 35");
    }
}
