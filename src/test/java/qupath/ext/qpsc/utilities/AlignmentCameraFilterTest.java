package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpsc.config.MicroscopeConfigManager;
import qupath.ext.qpsc.preferences.PersistentPreferences;

/**
 * On a microscope with two cameras, an alignment made with the other camera is not used: the
 * LC-PolScope's two ports are 4.8 mm apart in the stage frame.
 */
class AlignmentCameraFilterTest {

    private static final String ORYX = "LCPS_DETECTOR_FLIR_ORYX_001";
    private static final String HAMA = "LCPS_DETECTOR_HAMAMATSU_FLASH_001";

    @TempDir
    Path tmp;

    private String savedDetector;

    @BeforeEach
    void remember() {
        savedDetector = PersistentPreferences.getLastDetector();
    }

    @AfterEach
    void restore() {
        PersistentPreferences.setLastDetector(savedDetector == null ? "" : savedDetector);
    }

    private void load(String... detectors) throws Exception {
        Files.createDirectories(tmp.resolve("resources"));
        Files.writeString(tmp.resolve("resources").resolve("resources_LOCI.yml"), "id_detector: {}\n");
        StringBuilder sb = new StringBuilder("microscope:\n  name: LCPolScope\nhardware:\n  detectors:\n");
        for (String d : detectors) {
            sb.append("    - ").append(d).append('\n');
        }
        Path config = tmp.resolve("config_LCPolScope.yml");
        Files.writeString(config, sb.toString());
        MicroscopeConfigManager.getInstance(config.toString()).reload(config.toString());
    }

    @Test
    void theOtherCamerasRecordIsRejectedAndTheCameraInUsesKept() throws Exception {
        load(HAMA, ORYX);
        PersistentPreferences.setLastDetector(HAMA);
        assertEquals(ORYX, AffineTransformManager.otherCamera(ORYX));
        assertNull(AffineTransformManager.otherCamera(HAMA));
        assertNull(AffineTransformManager.otherCamera(null), "an unrecorded camera is not rejected");
        assertEquals(ORYX, AffineTransformManager.otherCameraOf(Map.of("detector", ORYX)));
        assertNull(AffineTransformManager.otherCameraOf(Map.of("microscope", "LCPolScope")));
    }

    @Test
    void aOneCameraMicroscopeNeverRejectsByCamera() throws Exception {
        load(HAMA);
        assertNull(AffineTransformManager.otherCamera(ORYX));
    }
}
