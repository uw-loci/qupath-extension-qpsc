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
    void theSuggestedExposureStaysInsideTheUsableRange() {
        // Inside the range: keep what the operator has.
        assertEquals(50.0, LCCalibrationWorkflow.suggestExposure(50.0, 6.3, 60.0));
        // Too bright (the 2026-10-16 Oryx run clipped at 50 ms, usable 1.6-45): pull 10% inside.
        assertEquals(40.5, LCCalibrationWorkflow.suggestExposure(50.0, 1.6, 45.0));
        // Too dim: 10% above the low end.
        assertEquals(22.0, LCCalibrationWorkflow.suggestExposure(5.0, 20.0, 300.0));
        // No range, or an empty one: nothing to suggest.
        assertNull(LCCalibrationWorkflow.suggestExposure(50.0, null, null));
        assertNull(LCCalibrationWorkflow.suggestExposure(50.0, 80.0, 40.0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theChosenExposureIsWrittenToEveryState(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config_LCPolScope.yml");
        Files.writeString(f, ConfigYamlEditorChannelTest.CONFIG);
        String line = LCCalibrationWorkflow.applyExposureToStates(
                f, JsonParser.parseString(REPLY).getAsJsonObject(), 40.5);
        assertTrue(line.contains("40.5 ms") && line.contains("2 states"), line);
        for (String state : List.of("State0", "State1")) {
            Map<String, Object> ch = ConfigYamlEditorChannelTest.channel(f, state);
            assertEquals(40.5, ((Number) ch.get("exposure_ms")).doubleValue(), 1e-9, state);
        }
    }

    @Test
    void theMeasuredBlackLevelIsOfferedOnlyWhenItIsTrustworthyAndDifferent() {
        java.util.function.Function<String, com.google.gson.JsonObject> r =
                json -> JsonParser.parseString(json).getAsJsonObject();
        // 103 typed in (the Hamamatsu's); the Oryx measures 4.6 -> offer 5.
        assertEquals(
                5.0,
                LCCalibrationWorkflow.measuredBlackLevelToOffer(
                        r.apply(
                                "{\"black_level\": 103, \"black_level_estimate\": {\"black_level\": 4.6, \"r_squared\": 0.99995}}")));
        // Already right: nothing to offer.
        assertNull(LCCalibrationWorkflow.measuredBlackLevelToOffer(r.apply(
                "{\"black_level\": 103, \"black_level_estimate\": {\"black_level\": 103.3, \"r_squared\": 0.9999}}")));
        // Not a straight line (gamma, auto gain): nothing trustworthy to write.
        assertNull(LCCalibrationWorkflow.measuredBlackLevelToOffer(r.apply(
                "{\"black_level\": 103, \"black_level_estimate\": {\"black_level\": 4.6, \"r_squared\": 0.98}}")));
        // A negative intercept (noise on a near-zero offset) is offered as 0, never below.
        assertEquals(
                0.0,
                LCCalibrationWorkflow.measuredBlackLevelToOffer(
                        r.apply(
                                "{\"black_level\": 103, \"black_level_estimate\": {\"black_level\": -1.2, \"r_squared\": 0.9999}}")));
        assertNull(LCCalibrationWorkflow.measuredBlackLevelToOffer(r.apply("{\"black_level\": 103}")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theMeasuredBlackLevelIsWrittenToTheReconstruction(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config_LCPolScope.yml");
        Files.writeString(f, ConfigYamlEditorChannelTest.CONFIG);
        String line = LCCalibrationWorkflow.applyBlackLevel(f, 5.0);
        assertTrue(line.contains("Black level 5 counts"), line);
        Map<String, Object> recon = ConfigYamlEditorChannelTest.reconstruction(f);
        assertEquals(5.0, ((Number) recon.get("black_level")).doubleValue(), 1e-9);
        assertTrue(Files.readString(f).contains("# file header must survive"));
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
