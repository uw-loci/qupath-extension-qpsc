package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link MicroscopeConfigManager#profileHardwareMismatch}: a profile named for its
 * objective (OWS3 style), one that declares objective and detector (LC-PolScope style), and
 * the water 60x objective that has a profile of its own.
 */
class ProfileHardwareMismatchTest {

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
              lcpolscope:
                type: 'lcpolscope'
            acquisition_profiles:
              Brightfield_10x:
                modality: 'Brightfield'
                detector: 'CAM_A'
              Brightfield_60x:
                modality: 'Brightfield'
                detector: 'CAM_A'
              Brightfield_water_60x:
                modality: 'Brightfield'
                detector: 'CAM_A'
              lcpolscope_20x:
                modality: 'lcpolscope'
                objective: 'OBJ_20X_POL'
                detector: 'CAM_B'
            hardware:
              objectives:
                - id: 'OBJ_20X_POL'
                  pixel_size_xy_um:
                    CAM_A: 0.5
                    CAM_B: 0.5
                    CAM_C: 0.3
              detectors:
                - 'CAM_A'
                - 'CAM_B'
                - 'CAM_C'
            stage:
              stage_id: 'S'
              limits:
                x_um: { low: -1, high: 1 }
                y_um: { low: -1, high: 1 }
                z_um: { low: -1, high: 1 }
            slide_size_um: { x: 1, y: 1 }
            """;

    @BeforeEach
    void load() throws IOException {
        Path mainConfig = tempDir.resolve("config_Test.yml");
        Path resourcesDir = tempDir.resolve("resources");
        Files.createDirectories(resourcesDir);
        Files.writeString(resourcesDir.resolve("resources_LOCI.yml"), "id_detector: {}\n");
        Files.writeString(mainConfig, CONFIG);
        mgr = MicroscopeConfigManager.getInstance(mainConfig.toString());
        mgr.reload(mainConfig.toString());
    }

    @Test
    void namedProfileFitsItsObjective() {
        assertNull(mgr.profileHardwareMismatch("Brightfield_10x", "LOCI_OBJECTIVE_OLYMPUS_10X_001", "CAM_A"));
    }

    @Test
    void namedProfileOnAnotherObjectiveIsReported() {
        String m = mgr.profileHardwareMismatch("Brightfield_10x", "LOCI_OBJECTIVE_OLYMPUS_20X_001", "CAM_A");
        assertNotNull(m);
        assertTrue(m.contains("is for a 10x objective"), m);
        assertTrue(m.contains("LOCI_OBJECTIVE_OLYMPUS_20X_001"), m);
    }

    @Test
    void plainSixtyOnTheWaterObjectiveNamesTheBetterProfile() {
        String m = mgr.profileHardwareMismatch("Brightfield_60x", "LOCI_OBJECTIVE_WATER_60X_001", "CAM_A");
        assertNotNull(m);
        assertTrue(m.contains("Brightfield_water_60x is"), m);
        assertNull(mgr.profileHardwareMismatch("Brightfield_water_60x", "LOCI_OBJECTIVE_WATER_60X_001", "CAM_A"));
    }

    @Test
    void declaredObjectiveAndDetectorAreCompared() {
        assertNull(mgr.profileHardwareMismatch("lcpolscope_20x", "OBJ_20X_POL", "CAM_B"));
        String m = mgr.profileHardwareMismatch("lcpolscope_20x", "OBJ_10X", "CAM_A");
        assertNotNull(m);
        assertTrue(m.contains("is for objective OBJ_20X_POL"), m);
        assertTrue(m.contains("uses camera CAM_B"), m);
    }

    @Test
    void camerasThePixelSizeCannotTellApartAreNotReported() {
        assertNull(mgr.profileHardwareMismatch("lcpolscope_20x", "OBJ_20X_POL", "CAM_A"));
        String m = mgr.profileHardwareMismatch("lcpolscope_20x", "OBJ_20X_POL", "CAM_C");
        assertNotNull(m);
        assertTrue(m.contains("uses camera CAM_B"), m);
    }

    @Test
    void unknownHardwareOrProfileReportsNothing() {
        assertNull(mgr.profileHardwareMismatch("Brightfield_10x", null, null));
        assertNull(mgr.profileHardwareMismatch("No_such_profile", "OBJ_10X", "CAM_A"));
    }
}
