package qupath.ext.qpsc.modality.lcpolscope.workflow;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpsc.utilities.ConfigYamlEditorChannelTest;

/**
 * The calibration reply, as the server sends it, applied to a config shaped like
 * {@code config_LCPolScope.yml}. What matters: every state's LC writes become the palette's
 * retardances, the voltage presets are gone, the state order is reset to the palette's order, and
 * the provenance lands beside the reconstruction settings.
 */
class LCCalibrationApplyTest {

    private static final String REPLY = "{"
            + "\"success\": true, \"extinction_ratio\": 267.3, \"calibrated_at\": \"2026-10-12T14:03:00\","
            + "\"metadata_path\": \"C:\\\\data\\\\lc_calibration_20261012_140300.json\","
            + "\"lc_device\": \"MeadowlarkLC\","
            + "\"lc_retardance_properties_waves\": {\"LCA\": \"Retardance LC-A [in waves]\","
            + " \"LCB\": \"Retardance LC-B [in waves]\"},"
            + "\"palette_waves\": {\"State0\": [0.2520, 0.4420], \"State1\": [0.2820, 0.4420]}}";

    @Test
    @SuppressWarnings("unchecked")
    void appliesPaletteStateOrderAndProvenance(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config_LCPolScope.yml");
        Files.writeString(f, ConfigYamlEditorChannelTest.CONFIG);

        List<String> summary = LCCalibrationWorkflow.applyPaletteToConfig(
                f, JsonParser.parseString(REPLY).getAsJsonObject());
        assertFalse(summary.isEmpty());

        for (String state : List.of("State0", "State1")) {
            Map<String, Object> ch = ConfigYamlEditorChannelTest.channel(f, state);
            assertNull(ch.get("mm_setup_presets"), state + " must not apply the stale voltage preset first");
            List<Map<String, Object>> props = (List<Map<String, Object>>) ch.get("device_properties");
            assertEquals(2, props.size(), state);
            assertEquals("Retardance LC-A [in waves]", props.get(0).get("property"));
            assertEquals("Retardance LC-B [in waves]", props.get(1).get("property"));
            assertEquals(50, ch.get("exposure_ms"), "exposure is not the calibration's to change");
        }
        Map<String, Object> s1 = ConfigYamlEditorChannelTest.channel(f, "State1");
        List<Map<String, Object>> p1 = (List<Map<String, Object>>) s1.get("device_properties");
        assertEquals(0.2820, ((Number) p1.get(0).get("value")).doubleValue(), 1e-12);
        assertEquals(0.4420, ((Number) p1.get(1).get("value")).doubleValue(), 1e-12);

        Map<String, Object> recon = ConfigYamlEditorChannelTest.reconstruction(f);
        assertEquals(List.of("State0", "State1"), recon.get("state_order"), "palette order, not the preset order");
        assertEquals("2026-10-12T14:03:00", recon.get("lc_calibrated_at"));
        assertEquals(267.3, ((Number) recon.get("lc_extinction_ratio")).doubleValue(), 1e-9);
        assertEquals("C:\\data\\lc_calibration_20261012_140300.json", recon.get("lc_calibration_file"));
        assertEquals(0.03, ((Number) recon.get("swing_waves")).doubleValue(), 1e-12, "settings are left alone");
        assertTrue(Files.readString(f).contains("# file header must survive"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void writesTheMeasuredAdapterWavelengthAheadOfTheRetardances(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config_LCPolScope.yml");
        Files.writeString(f, ConfigYamlEditorChannelTest.CONFIG);
        // Measured at 546 (the adapter's value that afternoon), reconstructed at 549: the
        // palette must be replayed at 546, the wavelength it was found at.
        String reply = REPLY.replace(
                "\"lc_device\": \"MeadowlarkLC\",",
                "\"lc_device\": \"MeadowlarkLC\", \"lc_wavelength_property\": \"Wavelength\","
                        + " \"lc_adapter_wavelength_nm\": 546.0,");
        List<String> summary = LCCalibrationWorkflow.applyPaletteToConfig(
                f, JsonParser.parseString(reply).getAsJsonObject());

        for (String state : List.of("State0", "State1")) {
            List<Map<String, Object>> props = (List<Map<String, Object>>)
                    ConfigYamlEditorChannelTest.channel(f, state).get("device_properties");
            assertEquals(3, props.size(), state);
            assertEquals("Wavelength", props.get(0).get("property"), state + ": wavelength before the crystals");
            assertEquals("MeadowlarkLC", props.get(0).get("device"));
            assertEquals(546.0, ((Number) props.get(0).get("value")).doubleValue(), 1e-12);
            assertEquals("Retardance LC-A [in waves]", props.get(1).get("property"));
        }
        assertTrue(summary.stream().anyMatch(l -> l.contains("546 nm")), String.join("\n", summary));
    }

    @Test
    void refusesWhenAPaletteStateHasNoChannelAndWritesNothing(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config_LCPolScope.yml");
        Files.writeString(f, ConfigYamlEditorChannelTest.CONFIG);
        String reply = REPLY.replace("\"State1\": [0.2820, 0.4420]", "\"State7\": [0.2820, 0.4420]");
        IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> LCCalibrationWorkflow.applyPaletteToConfig(
                        f, JsonParser.parseString(reply).getAsJsonObject()));
        assertTrue(e.getMessage().contains("State7"));
        assertEquals(ConfigYamlEditorChannelTest.CONFIG, Files.readString(f), "nothing written");
    }

    @Test
    void refusesAnEmptyPalette(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config_LCPolScope.yml");
        Files.writeString(f, ConfigYamlEditorChannelTest.CONFIG);
        assertThrows(
                IllegalStateException.class,
                () -> LCCalibrationWorkflow.applyPaletteToConfig(
                        f, JsonParser.parseString("{\"success\": true}").getAsJsonObject()));
    }
}
