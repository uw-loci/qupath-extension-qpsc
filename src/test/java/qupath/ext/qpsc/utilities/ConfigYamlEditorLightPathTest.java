package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

/**
 * Verifies {@link ConfigYamlEditor#setTopLevelChildScalar} -- the create/update/idempotent scalar
 * setter used to persist the per-microscope {@code light_path} slide-placement factors. Covers:
 * creating the block from scratch, adding a field to an existing block, updating in place, the
 * no-op idempotent case, and that unrelated content (including comments) survives.
 */
class ConfigYamlEditorLightPathTest {

    private static final String BASE = "# top-of-file comment must survive\n"
            + "microscope:\n"
            + "  name: TestScope\n"
            + "stage:\n"
            + "  stage_id: S1\n";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(Path p) throws Exception {
        try (var in = Files.newInputStream(p)) {
            return new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> block(Path p, String key) throws Exception {
        return (Map<String, Object>) parse(p).get(key);
    }

    @Test
    void createsBlockWhenAbsent(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("m.yml");
        Files.writeString(cfg, BASE);

        ConfigYamlEditor.Result r = ConfigYamlEditor.setTopLevelChildScalar(
                cfg, LightPathModel.BLOCK, LightPathModel.KEY_SCOPE_TYPE, "inverted");
        assertTrue(r.changed, "creating the block should report a change");

        assertEquals("inverted", block(cfg, "light_path").get("scope_type"));
        // Unrelated content preserved.
        assertEquals("TestScope", block(cfg, "microscope").get("name"));
        assertTrue(Files.readString(cfg).contains("# top-of-file comment must survive"));
    }

    @Test
    void addsFieldToExistingBlock(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("m.yml");
        Files.writeString(cfg, BASE);

        ConfigYamlEditor.setTopLevelChildScalar(cfg, LightPathModel.BLOCK, LightPathModel.KEY_SCOPE_TYPE, "inverted");
        ConfigYamlEditor.Result r = ConfigYamlEditor.setTopLevelChildScalar(
                cfg, LightPathModel.BLOCK, LightPathModel.KEY_SLIDE_INSERTION, "B");
        assertTrue(r.changed);

        Map<String, Object> lp = block(cfg, "light_path");
        assertEquals("inverted", lp.get("scope_type"));
        assertEquals("B", lp.get("slide_insertion"));
    }

    @Test
    void updatesInPlaceAndIsIdempotent(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("m.yml");
        Files.writeString(cfg, BASE);

        ConfigYamlEditor.setTopLevelChildScalar(cfg, LightPathModel.BLOCK, LightPathModel.KEY_SCOPE_TYPE, "upright");
        ConfigYamlEditor.Result upd = ConfigYamlEditor.setTopLevelChildScalar(
                cfg, LightPathModel.BLOCK, LightPathModel.KEY_SCOPE_TYPE, "inverted");
        assertTrue(upd.changed, "changing the value should report a change");
        assertEquals("inverted", block(cfg, "light_path").get("scope_type"));

        ConfigYamlEditor.Result noop = ConfigYamlEditor.setTopLevelChildScalar(
                cfg, LightPathModel.BLOCK, LightPathModel.KEY_SCOPE_TYPE, "inverted");
        assertFalse(noop.changed, "writing the same value should be a no-op");
    }
}
