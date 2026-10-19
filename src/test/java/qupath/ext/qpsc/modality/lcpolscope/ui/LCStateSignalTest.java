package qupath.ext.qpsc.modality.lcpolscope.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpsc.utilities.ConfigYamlEditorChannelTest;

/**
 * The Camera tab's reading of the server's LCSIGNAL report, and the one write that puts a single
 * exposure on every LC-PolScope state.
 */
class LCStateSignalTest {

    private static JsonObject report(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static final String MEASURED = """
            {"exposure_ms": 50.0, "full_scale": 4095.0, "target_fraction": 0.75,
             "brightest_state": "State2", "brightest_fraction": 0.3, "clipped": false,
             "suggested_exposure_ms": 127.3,
             "camera_fatal": ["Gain Auto is Continuous"], "camera_advisory": ["8-bit frames"],
             "states": [
               {"id": "State0", "extinction": true, "mean": 110, "p999": 140, "max": 160, "saturated_fraction": 0.0},
               {"id": "State2", "extinction": false, "mean": 1100, "p999": 1228.5, "max": 1300, "saturated_fraction": 0.0},
               {"id": "State3", "extinction": false, "mean": 4000, "p999": 4095, "max": 4095, "saturated_fraction": 0.02}
             ]}
            """;

    @Test
    void rowsCarryPercentOfFullScaleAndClipping() {
        List<LCStateSignal.Row> rows = LCStateSignal.rows(report(MEASURED));
        assertEquals(3, rows.size());
        assertTrue(rows.get(0).extinction());
        assertEquals(30.0, rows.get(1).percentOfFull(), 1e-9);
        assertFalse(rows.get(1).clipped());
        assertTrue(rows.get(2).clipped(), "2% of pixels at full scale");
    }

    @Test
    void verdictNamesTheBrightestStateAndTheSuggestion() {
        String v = LCStateSignal.verdict(report(MEASURED));
        assertTrue(v.contains("State2"), v);
        assertTrue(v.contains("30% of full scale (4095)"), v);
        assertTrue(v.contains("For 75% use 127.3 ms"), v);
        assertEquals(127.3, LCStateSignal.suggestedExposure(report(MEASURED)), 1e-9);
    }

    @Test
    void clippingOutranksTheSuggestion() {
        String v = LCStateSignal.verdict(report(MEASURED.replace("\"clipped\": false", "\"clipped\": true")));
        assertTrue(v.contains("clipped"), v);
        assertFalse(v.contains("Use suggested"), v);
    }

    @Test
    void aDarkFieldIsCalledOut() {
        String v = LCStateSignal.verdict(
                report(MEASURED.replace("\"brightest_fraction\": 0.3", "\"brightest_fraction\": 0.05")));
        assertTrue(v.contains("dark"), v);
    }

    @Test
    void errorsAndSilenceReadAsSuch() {
        assertTrue(LCStateSignal.verdict(report("{\"error\": \"unknown acquisition profile 'x'\"}"))
                .contains("unknown acquisition profile"));
        assertTrue(LCStateSignal.verdict(null).contains("No reply"));
        assertNull(LCStateSignal.suggestedExposure(report("{\"suggested_exposure_ms\": null}")));
        assertTrue(LCStateSignal.rows(report("{\"error\": \"x\"}")).isEmpty());
    }

    @Test
    void cameraProblemsListFatalFirst() {
        List<String> problems = LCStateSignal.cameraProblems(report(MEASURED));
        assertEquals(List.of("Must fix: Gain Auto is Continuous", "Note: 8-bit frames"), problems);
    }

    @Test
    void oneExposureLandsOnEveryState(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("c.yml");
        Files.writeString(f, ConfigYamlEditorChannelTest.CONFIG);
        int changed = LCStateSignal.writeExposureToStates(f, "lcpolscope", List.of("State0", "State1"), 127.3);
        assertEquals(2, changed);
        for (String id : List.of("State0", "State1")) {
            assertEquals(
                    127.3,
                    ((Number) ConfigYamlEditorChannelTest.channel(f, id).get("exposure_ms")).doubleValue(),
                    1e-9);
        }
        assertEquals(0, LCStateSignal.writeExposureToStates(f, "lcpolscope", List.of("State0", "State1"), 127.3));
        assertThrows(
                IllegalArgumentException.class,
                () -> LCStateSignal.writeExposureToStates(f, "lcpolscope", List.of("State0"), 0));
    }
}
