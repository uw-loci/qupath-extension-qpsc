package qupath.ext.qpsc.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

/** Writers put a camera's value in that camera's per_detector block and leave shared values shared. */
class PerDetectorWriteTest {

    private static final String ORYX = "LCPS_DETECTOR_FLIR_ORYX_001";
    private static final String HAMA = "LCPS_DETECTOR_HAMAMATSU_FLASH_001";

    @TempDir
    Path tmp;

    private Path config() throws Exception {
        Path p = tmp.resolve("config_LCPolScope.yml");
        Files.writeString(
                p,
                String.join(
                        "\n",
                        "modalities:",
                        "  lcpolscope:",
                        "    reconstruction:",
                        "      wavelength_nm: 549",
                        "      state_order: [State0]",
                        "      black_level: 51",
                        "      per_detector:",
                        "        " + HAMA + ":",
                        "          black_level: 51",
                        "        " + ORYX + ":",
                        "          black_level: 99",
                        "    channels:",
                        "    - id: State0",
                        "      exposure_ms: 141.5",
                        "      device_properties:",
                        "      - device: LC",
                        "        property: Wavelength",
                        "        value: 549.0",
                        "      - device: LC",
                        "        property: A",
                        "        value: 0.256",
                        "      per_detector:",
                        "        " + HAMA + ":",
                        "          exposure_ms: 141.5",
                        "          device_properties:",
                        "          - device: LC",
                        "            property: A",
                        "            value: 0.256",
                        "        " + ORYX + ":",
                        "          exposure_ms: 30",
                        "          device_properties:",
                        "          - device: LC",
                        "            property: A",
                        "            value: 0.2504",
                        "      settle_ms: 50",
                        ""),
                StandardCharsets.UTF_8);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolved(Path p, String detector) throws Exception {
        Map<String, Object> raw = new Yaml().load(Files.readString(p));
        return DetectorOverlay.apply(raw, detector);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> state0(Map<String, Object> cfg) {
        Map<String, Object> mod = (Map<String, Object>) ((Map<String, Object>) cfg.get("modalities")).get("lcpolscope");
        return (Map<String, Object>) ((List<Object>) mod.get("channels")).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Object recon(Map<String, Object> cfg, String key) {
        Map<String, Object> mod = (Map<String, Object>) ((Map<String, Object>) cfg.get("modalities")).get("lcpolscope");
        return ((Map<String, Object>) mod.get("reconstruction")).get(key);
    }

    @Test
    void exposureAndPropertiesGoToTheCameraInUse() throws Exception {
        Path p = config();
        assertTrue(ConfigYamlEditor.setChannelExposureMs(p, "lcpolscope", "State0", 25, ORYX).changed);
        assertTrue(ConfigYamlEditor.setChannelDevicePropertyValue(p, "lcpolscope", "State0", "LC", "A", "0.3", ORYX)
                .changed);
        assertTrue(
                ConfigYamlEditor.setChannelDevicePropertyValue(
                                p, "lcpolscope", "State0", "LC", "Wavelength", "546", ORYX)
                        .changed,
                "a property the camera does not keep is shared and written there");

        Map<String, Object> oryx = state0(resolved(p, ORYX));
        Map<String, Object> hama = state0(resolved(p, HAMA));
        assertEquals(25, ((Number) oryx.get("exposure_ms")).intValue());
        assertEquals(141.5, hama.get("exposure_ms"), "the other camera keeps its exposure");
        assertTrue(oryx.get("device_properties").toString().contains("value=0.3"));
        assertTrue(hama.get("device_properties").toString().contains("value=0.256"));
        assertTrue(hama.get("device_properties").toString().contains("value=546"), "shared for both");
        assertEquals(50, oryx.get("settle_ms"), "the rest of the channel is intact");
    }

    @Test
    void replacingAListWritesTheCamerasOwnList() throws Exception {
        Path p = config();
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("device", "LC");
        a.put("property", "A");
        a.put("value", 0.27);
        assertTrue(ConfigYamlEditor.replaceChannelListField(
                        p, "lcpolscope", "State0", "device_properties", List.of(a), null, HAMA)
                .changed);
        assertTrue(state0(resolved(p, HAMA)).get("device_properties").toString().contains("value=0.27"));
        assertTrue(state0(resolved(p, ORYX)).get("device_properties").toString().contains("value=0.2504"));
        assertTrue(
                state0(resolved(p, null)).get("device_properties").toString().contains("Wavelength"),
                "the shared list is untouched");
    }

    @Test
    void reconstructionValuesKeptPerCameraAreWrittenPerCameraAndSharedOnesStayShared() throws Exception {
        Path p = config();
        String[] block = {"modalities", "lcpolscope", "reconstruction"};
        String[] own = ConfigYamlEditor.blockPathFor(p, block, "black_level", ORYX);
        assertEquals(List.of("modalities", "lcpolscope", "reconstruction", "per_detector", ORYX), List.of(own));
        ConfigYamlEditor.setNestedChildScalar(p, own, "black_level", "101");
        assertArrayEquals(block, ConfigYamlEditor.blockPathFor(p, block, "state_order", ORYX));
        assertArrayEquals(block, ConfigYamlEditor.blockPathFor(p, block, "black_level", null));

        assertEquals(101, recon(resolved(p, ORYX), "black_level"));
        assertEquals(51, recon(resolved(p, HAMA), "black_level"));
    }
}
