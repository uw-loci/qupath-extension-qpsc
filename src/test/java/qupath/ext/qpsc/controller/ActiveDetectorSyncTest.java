package qupath.ext.qpsc.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Micro-Manager's camera label picks the detector whose id_detector device it is. */
class ActiveDetectorSyncTest {

    private static final Map<String, Object> SECTION = Map.of(
            "LCPS_DETECTOR_FLIR_ORYX_001", Map.of("device", "Oryx ORX-10G-51S5M"),
            "LCPS_DETECTOR_HAMAMATSU_FLASH_001", Map.of("device", "Flash4"),
            "OTHER", Map.of("device", "Flash4"));

    @Test
    void theLoadedCameraNamesItsDetectorAmongTheDeclaredOnes() {
        List<String> declared = List.of("LCPS_DETECTOR_HAMAMATSU_FLASH_001", "LCPS_DETECTOR_FLIR_ORYX_001");
        assertEquals(
                "LCPS_DETECTOR_HAMAMATSU_FLASH_001", ActiveDetectorSync.detectorForCamera(SECTION, declared, "Flash4"));
        assertEquals(
                "LCPS_DETECTOR_FLIR_ORYX_001",
                ActiveDetectorSync.detectorForCamera(SECTION, declared, "Oryx ORX-10G-51S5M"));
        assertNull(ActiveDetectorSync.detectorForCamera(SECTION, declared, "DemoCamera"));
        assertNull(ActiveDetectorSync.detectorForCamera(SECTION, declared, ""));
    }
}
