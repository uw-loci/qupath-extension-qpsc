package qupath.ext.qpsc.geometry;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpsc.config.MicroscopeConfigManager;

/** hardware.camera_orientation in the microscope config: one value per camera. */
class CameraOrientationSettingTest {

    @TempDir
    Path tmp;

    @Test
    void eachCameraReadsItsOwnValue() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("HAMA", "FLIP_V");
        block.put("ORYX", "normal");
        assertEquals(CameraOrientation.FLIP_V, CameraOrientationSetting.read(block, "HAMA"));
        assertEquals(CameraOrientation.NORMAL, CameraOrientationSetting.read(block, "ORYX"));
        assertNull(CameraOrientationSetting.read(block, "OTHER"));
        assertNull(CameraOrientationSetting.read(block, null), "two cameras and none named: no guess");
        assertEquals(
                CameraOrientation.FLIP_H,
                CameraOrientationSetting.read(Map.of("ONLY", "FLIP_H"), null),
                "the only one");
        assertNull(CameraOrientationSetting.read(Map.of("HAMA", "sideways"), "HAMA"));
    }

    @Test
    void saveWritesTheCameraInUsesValueIntoTheConfig() throws Exception {
        Path resources = Files.createDirectories(tmp.resolve("resources"));
        Files.writeString(resources.resolve("resources_LOCI.yml"), "id_detector: {}\n");
        Path config = tmp.resolve("config_Test.yml");
        Files.writeString(
                config,
                String.join(
                        "\n",
                        "microscope:",
                        "  name: Test",
                        "hardware:",
                        "  objectives:",
                        "    - id: OBJ",
                        "  detectors:",
                        "    - CAM_A",
                        "stage:",
                        "  polarity: NORMAL",
                        ""));
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(config.toString());
        mgr.reload(config.toString());

        assertTrue(CameraOrientationSetting.save(CameraOrientation.FLIP_V));
        assertTrue(Files.readString(config).contains("camera_orientation:"));
        assertEquals(CameraOrientation.FLIP_V, CameraOrientationSetting.current());

        assertTrue(CameraOrientationSetting.save(CameraOrientation.ROT_180), "an existing value is replaced");
        assertEquals(CameraOrientation.ROT_180, CameraOrientationSetting.current());
        assertEquals(1, Files.readString(config).split("camera_orientation:", -1).length - 1);
    }
}
