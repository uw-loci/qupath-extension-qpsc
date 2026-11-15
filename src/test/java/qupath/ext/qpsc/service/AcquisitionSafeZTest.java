package qupath.ext.qpsc.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpsc.preferences.PersistentPreferences;

/** The acquisition request carries the mounted insert's safe Z, which the server retracts to. */
class AcquisitionSafeZTest {

    @TempDir
    Path dir;

    private String message(Path yaml) {
        return AcquisitionCommandBuilder.builder()
                .yamlPath(yaml.toString())
                .projectsFolder("/projects")
                .sampleLabel("sample001")
                .scanType("BF_10x")
                .regionName("region1")
                .buildSocketMessage();
    }

    @Test
    void theInsertsSafeZIsSentAndTheScopeLevelValueIsTheFallback() throws IOException {
        // PPM after the 2026-11-13 Z re-zero: the four-slide holder declares 200, the
        // scope-level value is still 0.
        Path yaml = dir.resolve("config_test.yml");
        Files.writeString(
                yaml,
                "microscope:\n  name: TestScope\n"
                        + "stage:\n  safe_z_um: 0\n  inserts:\n    configurations:\n"
                        + "      quad_v:\n        safe_z_um: 200\n");
        String saved = PersistentPreferences.getStageMapInsert();
        try {
            PersistentPreferences.setStageMapInsert("quad_v");
            assertTrue(message(yaml).contains("--safe-z 200.00"), message(yaml));
            PersistentPreferences.setStageMapInsert("single_h");
            assertTrue(message(yaml).contains("--safe-z 0.00"), message(yaml));
        } finally {
            PersistentPreferences.setStageMapInsert(saved);
        }
    }
}
