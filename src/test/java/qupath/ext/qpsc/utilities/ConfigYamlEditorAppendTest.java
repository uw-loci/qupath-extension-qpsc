package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

/**
 * Verifies the comment-preserving list/map append primitives added to
 * {@link ConfigYamlEditor} for the "Register current MM objective" utility.
 * Exercises the compact block-sequence style (list items at the parent-key
 * indent, as in {@code hardware.objectives} / {@code autofocus_settings}), the
 * nested-map style ({@code imaging_profiles.<modality>}), and the 4-space flat
 * style ({@code id_objective_lens}).
 */
class ConfigYamlEditorAppendTest {

    private static final String CONFIG = "hardware:\n"
            + "  objectives:\n"
            + "  - id: OBJ_10X\n"
            + "    pixel_size_xy_um:\n"
            + "      DET_A: 0.45\n"
            + "    pixel_size_z_um: null\n"
            + "  - id: OBJ_20X\n"
            + "    pixel_size_xy_um:\n"
            + "      DET_A: 0.22\n"
            + "    pixel_size_z_um: null\n"
            + "  detectors:\n"
            + "  - DET_A\n"
            + "stage:\n"
            + "  stage_id: S1\n";

    private static final String AUTOFOCUS = "# header comment line that must survive\n"
            + "autofocus_settings:\n"
            + "- objective: OBJ_10X\n"
            + "  calibrated: true\n"
            + "  n_steps: 35\n"
            + "strategies:\n"
            + "  dense_texture:\n"
            + "    score_metric: laplacian_variance\n";

    private static final String IMAGING = "imaging_profiles:\n"
            + "  ppm:\n"
            + "    OBJ_10X:\n"
            + "      DET_A:\n"
            + "        gains: 1.0\n"
            + "  brightfield:\n"
            + "    OBJ_10X:\n"
            + "      DET_A:\n"
            + "        gains: 1.0\n";

    private static final String RESOURCES = "id_objective_lens:\n"
            + "    OBJ_10X:\n"
            + "        name: '10x'\n"
            + "        magnification: 10.0\n"
            + "        na: 0.3\n";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(Path p) throws Exception {
        try (var in = Files.newInputStream(p)) {
            return new Yaml().load(in);
        }
    }

    @Test
    void appendsCompactListItem_hardwareObjectives(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config.yml");
        Files.writeString(f, CONFIG);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "OBJ_60X");
        Map<String, Object> px = new LinkedHashMap<>();
        px.put("DET_A", 0.075);
        item.put("pixel_size_xy_um", px);
        item.put("pixel_size_z_um", null);

        ConfigYamlEditor.Result r =
                ConfigYamlEditor.appendListItem(f, new String[] {"hardware", "objectives"}, "id", "OBJ_60X", item);
        assertTrue(r.changed, r.message);

        Map<String, Object> root = parse(f);
        Map<String, Object> hw = (Map<String, Object>) root.get("hardware");
        List<Map<String, Object>> objs = (List<Map<String, Object>>) hw.get("objectives");
        assertEquals(3, objs.size());
        assertEquals("OBJ_60X", objs.get(2).get("id"));
        assertEquals(0.075, ((Map<String, Object>) objs.get(2).get("pixel_size_xy_um")).get("DET_A"));
        // detectors sibling list still intact
        assertEquals(List.of("DET_A"), hw.get("detectors"));
    }

    @Test
    void listAppendIsIdempotent(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config.yml");
        Files.writeString(f, CONFIG);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "OBJ_20X");
        ConfigYamlEditor.Result r =
                ConfigYamlEditor.appendListItem(f, new String[] {"hardware", "objectives"}, "id", "OBJ_20X", item);
        assertFalse(r.changed, "existing id should be a no-op");
        assertEquals(CONFIG, Files.readString(f), "file must be byte-identical on no-op");
    }

    @Test
    void appendsListItem_preservesComments_autofocus(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("autofocus.yml");
        Files.writeString(f, AUTOFOCUS);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("objective", "OBJ_60X");
        item.put("calibrated", false);
        item.put("n_steps", 40);

        ConfigYamlEditor.Result r =
                ConfigYamlEditor.appendListItem(f, new String[] {"autofocus_settings"}, "objective", "OBJ_60X", item);
        assertTrue(r.changed, r.message);

        String out = Files.readString(f);
        assertTrue(out.contains("# header comment line that must survive"), "header comment lost");
        Map<String, Object> root = parse(f);
        List<Map<String, Object>> settings = (List<Map<String, Object>>) root.get("autofocus_settings");
        assertEquals(2, settings.size());
        assertEquals("OBJ_60X", settings.get(1).get("objective"));
        assertEquals(Boolean.FALSE, settings.get(1).get("calibrated"));
        // strategies sibling survived
        assertNotNull(root.get("strategies"));
    }

    @Test
    void appendsMapEntry_underEachModality_imaging(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("imaging.yml");
        Files.writeString(f, IMAGING);

        Map<String, Object> stub = new LinkedHashMap<>();
        Map<String, Object> det = new LinkedHashMap<>();
        Map<String, Object> prof = new LinkedHashMap<>();
        prof.put("gains", 1.0);
        det.put("DET_A", prof);
        stub.putAll(det);

        for (String mod : List.of("ppm", "brightfield")) {
            ConfigYamlEditor.Result r =
                    ConfigYamlEditor.appendMapEntry(f, new String[] {"imaging_profiles", mod}, "OBJ_60X", stub);
            assertTrue(r.changed, r.message);
        }

        Map<String, Object> root = parse(f);
        Map<String, Object> profiles = (Map<String, Object>) root.get("imaging_profiles");
        for (String mod : List.of("ppm", "brightfield")) {
            Map<String, Object> modMap = (Map<String, Object>) profiles.get(mod);
            assertTrue(modMap.containsKey("OBJ_60X"), mod + " missing new objective");
            Map<String, Object> detMap = (Map<String, Object>) modMap.get("OBJ_60X");
            assertTrue(detMap.containsKey("DET_A"));
        }
    }

    @Test
    void appendsMapEntry_flatFourSpace_resources(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("resources_LOCI.yml");
        Files.writeString(f, RESOURCES);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", "60x Water");
        entry.put("magnification", 60.0);
        entry.put("na", 1.2);
        entry.put("on_scope", "PPM");

        ConfigYamlEditor.Result r =
                ConfigYamlEditor.appendMapEntry(f, new String[] {"id_objective_lens"}, "OBJ_60X", entry);
        assertTrue(r.changed, r.message);

        Map<String, Object> root = parse(f);
        Map<String, Object> objs = (Map<String, Object>) root.get("id_objective_lens");
        assertTrue(objs.containsKey("OBJ_60X"));
        Map<String, Object> e = (Map<String, Object>) objs.get("OBJ_60X");
        assertEquals("60x Water", e.get("name"));
        assertEquals(60.0, e.get("magnification"));
        // existing entry preserved
        assertTrue(objs.containsKey("OBJ_10X"));
    }

    @Test
    void mapAppendIsIdempotent(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("resources_LOCI.yml");
        Files.writeString(f, RESOURCES);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", "dup");
        ConfigYamlEditor.Result r =
                ConfigYamlEditor.appendMapEntry(f, new String[] {"id_objective_lens"}, "OBJ_10X", entry);
        assertFalse(r.changed);
        assertEquals(RESOURCES, Files.readString(f));
    }
}
