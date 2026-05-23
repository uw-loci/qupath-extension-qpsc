package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sidecar round-trip + override-precedence tests for the parfocality
 * calibration feature. The sidecar wins over inline {@code parfocal_offset_um}
 * on a profile; with no sidecar the inline value is used; with neither the
 * accessor returns null.
 */
class ParfocalityCalibrationTest {

    @TempDir
    Path tempDir;

    private static final String CONFIG_WITH_INLINE = """
            microscope:
              name: 'Test'
              type: 'TestSystem'
            modalities:
              Brightfield:
                type: 'brightfield'
              Fluorescence:
                type: 'fluorescence'
            acquisition_profiles:
              Brightfield_10x:
                modality: 'Brightfield'
                detector: 'DET'
                objective: 'OBJ_10X'
                parfocal_offset_um: 0.0
              Fluorescence_10x:
                modality: 'Fluorescence'
                detector: 'DET'
                objective: 'OBJ_10X'
                parfocal_offset_um: -7.5
              Brightfield_40x:
                modality: 'Brightfield'
                detector: 'DET'
                objective: 'OBJ_40X'
              Fluorescence_40x:
                modality: 'Fluorescence'
                detector: 'DET'
                objective: 'OBJ_40X'
            parfocality:
              reference_profile_per_objective:
                OBJ_10X: 'Brightfield_10x'
            hardware:
              objectives:
                - id: 'OBJ_10X'
                - id: 'OBJ_40X'
              detectors:
                - 'DET'
            stage:
              stage_id: 'S'
              limits:
                x_um: { low: -1, high: 1 }
                y_um: { low: -1, high: 1 }
                z_um: { low: -1, high: 1 }
            slide_size_um: { x: 1, y: 1 }
            """;

    private static MicroscopeConfigManager loadConfig(Path tempDir, String configYaml) throws IOException {
        Path mainConfig = tempDir.resolve("config_Test.yml");
        Path resourcesDir = tempDir.resolve("resources");
        Files.createDirectories(resourcesDir);
        Files.writeString(resourcesDir.resolve("resources_LOCI.yml"), "id_detector: {}\n");
        Files.writeString(mainConfig, configYaml);
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(mainConfig.toString());
        mgr.reload(mainConfig.toString());
        return mgr;
    }

    @Test
    void inlineParfocalOffsetReadsWithoutSidecar() throws IOException {
        var mgr = loadConfig(tempDir, CONFIG_WITH_INLINE);
        assertEquals(0.0, mgr.getProfileParfocalOffset("Brightfield_10x"), 1e-9);
        assertEquals(-7.5, mgr.getProfileParfocalOffset("Fluorescence_10x"), 1e-9);
        assertNull(mgr.getProfileParfocalOffset("Brightfield_40x"));
        assertNull(mgr.getProfileParfocalOffset("DoesNotExist"));
    }

    @Test
    void sidecarOverridesInlineValue() throws IOException {
        var mgr = loadConfig(tempDir, CONFIG_WITH_INLINE);
        // Write a sidecar with a different value for Fluorescence_10x.
        Path sidecar = tempDir.resolve("parfocality_Test.yml");
        Files.writeString(sidecar, """
                metadata: {generated: '2026-05-23', version: '1.0'}
                reference_profile_per_objective:
                  OBJ_10X: 'Brightfield_10x'
                  OBJ_40X: 'Brightfield_40x'
                offsets:
                  Brightfield_10x: 0.0
                  Fluorescence_10x: -12.5
                  Brightfield_40x: 0.0
                  Fluorescence_40x: -18.0
                """, StandardCharsets.UTF_8);
        mgr.reload();
        // Sidecar wins for Fluorescence_10x; covers the inline-missing 40x pair.
        assertEquals(-12.5, mgr.getProfileParfocalOffset("Fluorescence_10x"), 1e-9);
        assertEquals(-18.0, mgr.getProfileParfocalOffset("Fluorescence_40x"), 1e-9);
        assertEquals(0.0, mgr.getProfileParfocalOffset("Brightfield_40x"), 1e-9);
    }

    @Test
    void saveAndReloadRoundTrip() throws IOException {
        var mgr = loadConfig(tempDir, CONFIG_WITH_INLINE);
        Map<String, Double> offsets = new LinkedHashMap<>();
        offsets.put("Brightfield_10x", 0.0);
        offsets.put("Fluorescence_10x", -10.0);
        offsets.put("Brightfield_40x", 0.0);
        offsets.put("Fluorescence_40x", -20.0);
        Map<String, String> refs = new LinkedHashMap<>();
        refs.put("OBJ_10X", "Brightfield_10x");
        refs.put("OBJ_40X", "Brightfield_40x");

        mgr.saveParfocalityCalibration(offsets, refs);

        // Sidecar file should exist next to the main config.
        Path sidecar = tempDir.resolve("parfocality_Test.yml");
        assertTrue(Files.exists(sidecar), "Sidecar not written");

        // In-memory copy should already reflect the new values (save updates state).
        assertEquals(-10.0, mgr.getProfileParfocalOffset("Fluorescence_10x"), 1e-9);
        assertEquals(-20.0, mgr.getProfileParfocalOffset("Fluorescence_40x"), 1e-9);
        assertEquals("Brightfield_10x", mgr.getReferenceProfileForObjective("OBJ_10X"));
        assertEquals("Brightfield_40x", mgr.getReferenceProfileForObjective("OBJ_40X"));

        // Reload from disk -- values should survive.
        mgr.reload();
        assertEquals(-10.0, mgr.getProfileParfocalOffset("Fluorescence_10x"), 1e-9);
        assertEquals(-20.0, mgr.getProfileParfocalOffset("Fluorescence_40x"), 1e-9);
    }

    @Test
    void referenceLookupFallsBackToFirstMatchingObjective() throws IOException {
        // No sidecar, but main YAML has an inline reference for OBJ_10X.
        var mgr = loadConfig(tempDir, CONFIG_WITH_INLINE);
        assertEquals("Brightfield_10x", mgr.getReferenceProfileForObjective("OBJ_10X"));
        // OBJ_40X has no inline reference -- falls back to first profile whose
        // objective field matches, which is Brightfield_40x (first in YAML order).
        assertEquals("Brightfield_40x", mgr.getReferenceProfileForObjective("OBJ_40X"));
        assertNull(mgr.getReferenceProfileForObjective("NOSUCH"));
        assertNull(mgr.getReferenceProfileForObjective(null));
    }

    @Test
    void profileObjectiveAndDetectorAccessors() throws IOException {
        var mgr = loadConfig(tempDir, CONFIG_WITH_INLINE);
        assertEquals("OBJ_10X", mgr.getProfileObjective("Brightfield_10x"));
        assertEquals("DET", mgr.getProfileDetector("Brightfield_10x"));
        assertNull(mgr.getProfileObjective("NoSuchProfile"));
        assertNull(mgr.getProfileDetector("NoSuchProfile"));
    }
}
