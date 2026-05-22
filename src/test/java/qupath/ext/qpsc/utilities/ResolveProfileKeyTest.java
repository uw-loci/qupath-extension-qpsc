package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link MicroscopeConfigManager#resolveProfileKey}, the Java mirror
 * of the Python server's {@code _resolve_background_profile_key}. The two MUST
 * stay algorithmically identical so the {@code --profile} arg Java sends and the
 * fallback Python derives agree.
 */
class ResolveProfileKeyTest {

    @TempDir
    Path tempDir;

    private MicroscopeConfigManager mgr;

    private static final String CONFIG = """
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
                illumination_intensity: 700
              Brightfield_40x:
                modality: 'Brightfield'
                illumination_intensity: 1200
              Fluorescence_20x:
                modality: 'Fluorescence'
                illumination_intensity: 1.0
            hardware:
              objectives:
                - id: 'OBJ_DUMMY'
              detectors:
                - 'DET_DUMMY'
            stage:
              stage_id: 'S'
              limits:
                x_um: { low: -1, high: 1 }
                y_um: { low: -1, high: 1 }
                z_um: { low: -1, high: 1 }
            slide_size_um: { x: 1, y: 1 }
            """;

    @AfterEach
    void teardown() {
        mgr = null;
    }

    private void load() throws IOException {
        Path mainConfig = tempDir.resolve("config_Test.yml");
        Path resourcesDir = tempDir.resolve("resources");
        Files.createDirectories(resourcesDir);
        Files.writeString(resourcesDir.resolve("resources_LOCI.yml"), "id_detector: {}\n");
        Files.writeString(mainConfig, CONFIG);
        mgr = MicroscopeConfigManager.getInstance(mainConfig.toString());
        mgr.reload(mainConfig.toString());
    }

    @Test
    void resolvesBySuffixSubstringOfObjective() throws IOException {
        load();
        assertEquals("Brightfield_10x", mgr.resolveProfileKey("Brightfield", "LOCI_OBJECTIVE_OLYMPUS_10X_001"));
        assertEquals("Brightfield_40x", mgr.resolveProfileKey("Brightfield", "LOCI_OBJECTIVE_OLYMPUS_40X_001"));
        assertEquals("Fluorescence_20x", mgr.resolveProfileKey("Fluorescence", "0.75NA_AIR_20x"));
    }

    @Test
    void returnsNullWhenNoSuffixMatches() throws IOException {
        load();
        assertNull(mgr.resolveProfileKey("Brightfield", "OBJECTIVE_60X"));
    }

    @Test
    void returnsNullForUnknownModality() throws IOException {
        load();
        assertNull(mgr.resolveProfileKey("NotAModality", "OBJ_10x"));
        assertNull(mgr.resolveProfileKey(null, "OBJ_10x"));
    }

    @Test
    void profileLookupHelpers() throws IOException {
        load();
        assertEquals(700.0, mgr.getProfileIlluminationIntensity("Brightfield_10x"), 1e-6);
        assertNull(mgr.getProfileIlluminationIntensity("NoSuchProfile"));
        assertEquals("Brightfield", mgr.getProfileModality("Brightfield_40x"));
        assertEquals(2, mgr.getProfileKeysForModality("Brightfield").size());
        assertTrue(mgr.getProfileKeysForModality("Fluorescence").contains("Fluorescence_20x"));
    }
}
