package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code background_correction} entry whose keys are present but empty means "not configured",
 * exactly like absent keys. The LC-PolScope image-processing YAML declares {@code base_folder: null},
 * and the first tiled acquisition on that scope (2026-10-16) died in the base-folder lookup with a
 * NullPointerException before anything reached the microscope. Without a base folder there is no
 * background profile folder either.
 */
class BackgroundCorrectionEmptyValuesTest {

    @TempDir
    Path tempDir;

    private static final String MAIN_CONFIG = """
            microscope:
              name: 'Test'
              type: 'TestSystem'
            modalities:
              lcpolscope:
                type: 'polarized'
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

    private MicroscopeConfigManager load(String imgproc) throws IOException {
        Path main = tempDir.resolve("config_Test.yml");
        Path resources = tempDir.resolve("resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("resources_LOCI.yml"), "id_detector: {}\n");
        Files.writeString(main, MAIN_CONFIG);
        Files.writeString(tempDir.resolve("imageprocessing_Test.yml"), imgproc);
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(main.toString());
        mgr.reload(main.toString());
        return mgr;
    }

    @Test
    void nullValuesAreNotConfiguredRatherThanACrash() throws IOException {
        MicroscopeConfigManager mgr = load("""
                background_correction:
                  base_folder: null
                  lcpolscope:
                    enabled: null
                    method: null
                """);
        assertNull(mgr.getBackgroundBaseFolder());
        assertNull(mgr.getBackgroundProfileFolder());
        assertNull(mgr.getBackgroundCorrectionMethod("lcpolscope"));
        assertFalse(mgr.isBackgroundCorrectionEnabled("lcpolscope"));
        assertNull(mgr.getBackgroundCorrectionMethod("lcpolscope_20x"), "family fallback too");
    }

    @Test
    void blankStringsAreNotConfigured() throws IOException {
        MicroscopeConfigManager mgr = load("""
                background_correction:
                  base_folder: ''
                  lcpolscope:
                    enabled: false
                    method: ''
                """);
        assertNull(mgr.getBackgroundBaseFolder());
        assertNull(mgr.getBackgroundProfileFolder());
        assertNull(mgr.getBackgroundCorrectionMethod("lcpolscope"));
        assertFalse(mgr.isBackgroundCorrectionEnabled("lcpolscope"));
    }

    @Test
    void realValuesStillComeThrough() throws IOException {
        MicroscopeConfigManager mgr = load("""
                background_correction:
                  base_folder: 'C:/bg'
                  lcpolscope:
                    enabled: true
                    method: divide
                """);
        assertEquals("C:/bg", mgr.getBackgroundBaseFolder());
        assertEquals("divide", mgr.getBackgroundCorrectionMethod("lcpolscope"));
        assertTrue(mgr.isBackgroundCorrectionEnabled("lcpolscope"));
    }
}
