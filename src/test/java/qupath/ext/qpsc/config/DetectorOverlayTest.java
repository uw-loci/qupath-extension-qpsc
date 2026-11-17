package qupath.ext.qpsc.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** The active camera's per_detector values replace the shared ones; the server applies the same rules. */
class DetectorOverlayTest {

    private static final String ORYX = "LCPS_DETECTOR_FLIR_ORYX_001";
    private static final String HAMA = "LCPS_DETECTOR_HAMAMATSU_FLASH_001";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> config() {
        return (Map<String, Object>) new Yaml()
                .load(String.join(
                        "\n",
                        "modalities:",
                        "  lcpolscope:",
                        "    reconstruction:",
                        "      wavelength_nm: 549",
                        "      black_level: 51",
                        "      per_detector:",
                        "        " + ORYX + ": {black_level: 99, display: {ceiling: 30}}",
                        "      display: {ceiling: 20, colormap: ppm}",
                        "    channels:",
                        "    - id: State0",
                        "      exposure_ms: 141.5",
                        "      device_properties:",
                        "      - {device: LC, property: Wavelength, value: 549.0}",
                        "      - {device: LC, property: A, value: 0.256}",
                        "      per_detector:",
                        "        " + ORYX + ":",
                        "          exposure_ms: 30",
                        "          device_properties:",
                        "          - {device: LC, property: A, value: 0.2504}",
                        "          - {device: LC, property: B, value: 0.438}",
                        "    - id: State1",
                        "      exposure_ms: 141.5",
                        "hardware:",
                        "  objectives:",
                        "  - id: OBJ20",
                        "    mm_preset: {group: Objective, preset: 20x}",
                        "    per_detector:",
                        "      " + ORYX + ": {mm_preset: {group: Objective Lens}}"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return (List<Object>) o;
    }

    @Test
    void theActiveCamerasValuesReplaceTheSharedOnesEverywhere() {
        Map<String, Object> raw = config();
        Map<String, Object> r = DetectorOverlay.apply(raw, ORYX);

        Map<String, Object> recon =
                map(map(map(r.get("modalities")).get("lcpolscope")).get("reconstruction"));
        assertEquals(99, recon.get("black_level"));
        assertEquals(549, recon.get("wavelength_nm"), "shared values stay");
        assertEquals(Map.of("ceiling", 30, "colormap", "ppm"), recon.get("display"), "nested maps merge");

        List<Object> channels =
                list(map(map(r.get("modalities")).get("lcpolscope")).get("channels"));
        Map<String, Object> s0 = map(channels.get(0));
        assertEquals(30, s0.get("exposure_ms"));
        List<Object> props = list(s0.get("device_properties"));
        assertEquals(3, props.size());
        assertEquals(549.0, map(props.get(0)).get("value"), "a property the camera does not name stays");
        assertEquals(0.2504, map(props.get(1)).get("value"), "a named (device, property) is replaced in place");
        assertEquals("B", map(props.get(2)).get("property"), "a new pair is appended");
        assertEquals(141.5, map(channels.get(1)).get("exposure_ms"), "a channel without a block is unchanged");

        Map<String, Object> obj =
                map(list(map(r.get("hardware")).get("objectives")).get(0));
        assertEquals(Map.of("group", "Objective Lens", "preset", "20x"), obj.get("mm_preset"));
        assertNotNull(obj.get(DetectorOverlay.KEY), "the block stays for writers");

        assertEquals(
                51,
                map(map(map(raw.get("modalities")).get("lcpolscope")).get("reconstruction"))
                        .get("black_level"),
                "the input is not changed");
    }

    @Test
    void noCameraOrAnUnnamedCameraKeepsTheSharedValues() {
        for (String detector : new String[] {null, HAMA}) {
            Map<String, Object> r = DetectorOverlay.apply(config(), detector);
            Map<String, Object> recon =
                    map(map(map(r.get("modalities")).get("lcpolscope")).get("reconstruction"));
            assertEquals(51, recon.get("black_level"));
        }
    }

    @Test
    void theOverlayCameraIsTheOnlyOneOrNoneWhenSeveralAreUnrecorded() {
        Map<String, Object> one = Map.of("hardware", Map.of("detectors", List.of(HAMA)));
        assertEquals(HAMA, MicroscopeConfigManager.overlayDetector(one));
    }
}
