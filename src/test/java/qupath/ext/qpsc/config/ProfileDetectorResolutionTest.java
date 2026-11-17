package qupath.ext.qpsc.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * With two cameras in one config, the profile for the camera in use is chosen. Mirrors the
 * server's {@code _resolve_background_profile_key}.
 */
class ProfileDetectorResolutionTest {

    private static final String OBJ = "LCPS_OBJECTIVE_20X_POL_001";
    private static final String HAMA = "LCPS_DETECTOR_HAMAMATSU_FLASH_001";
    private static final String ORYX = "LCPS_DETECTOR_FLIR_ORYX_001";

    private static Map<String, Object> profiles() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("lcpolscope_20x", Map.of("modality", "lcpolscope", "objective", OBJ, "detector", HAMA));
        p.put("lcpolscope_20x_oryx", Map.of("modality", "lcpolscope", "objective", OBJ, "detector", ORYX));
        p.put("brightfield_20x", Map.of("modality", "brightfield", "detector", HAMA));
        p.put("brightfield_20x_oryx", Map.of("modality", "brightfield", "detector", ORYX));
        return p;
    }

    @Test
    void theActiveCamerasProfileWinsByItsFields() {
        assertEquals("lcpolscope_20x", MicroscopeConfigManager.matchProfileKey(profiles(), "lcpolscope", OBJ, HAMA));
        assertEquals(
                "lcpolscope_20x_oryx", MicroscopeConfigManager.matchProfileKey(profiles(), "LCPolScope", OBJ, ORYX));
    }

    @Test
    void withoutObjectiveFieldsTheNameRulesRunOnTheCamerasProfilesOnly() {
        assertEquals("brightfield_20x", MicroscopeConfigManager.matchProfileKey(profiles(), "brightfield", OBJ, HAMA));
        assertNull(
                MicroscopeConfigManager.matchProfileKey(profiles(), "brightfield", OBJ, ORYX),
                "brightfield_20x_oryx's suffix '20x_oryx' is not in the objective id, and the Hamamatsu profile is "
                        + "not a candidate");
    }

    @Test
    void anUnknownCameraConsidersEveryProfileInFileOrder() {
        assertEquals("lcpolscope_20x", MicroscopeConfigManager.matchProfileKey(profiles(), "lcpolscope", OBJ, null));
    }
}
