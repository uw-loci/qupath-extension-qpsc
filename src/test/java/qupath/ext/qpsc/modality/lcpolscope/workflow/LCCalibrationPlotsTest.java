package qupath.ext.qpsc.modality.lcpolscope.workflow;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The data behind the calibration plots, from a reply shaped like the 2026-10-26 Flash4 run:
 * LC-A swings the full 0.03 waves, LC-B reaches the target at +0.0142 and -0.0251.
 */
class LCCalibrationPlotsTest {

    private static final String REPLY = "{"
            + "\"swing_waves\": 0.03,"
            + "\"palette_waves\": {\"State0\": [0.2504, 0.4380], \"State1\": [0.2204, 0.4380],"
            + " \"State2\": [0.2504, 0.4522], \"State3\": [0.2804, 0.4380], \"State4\": [0.2504, 0.4129]},"
            + "\"state_intensities\": {\"State0\": 1495.8, \"State1\": 4875.5, \"State2\": 4865.6,"
            + " \"State3\": 4894.0, \"State4\": 4864.5},"
            + "\"trace\": ["
            // extinction search along LC-B (LC-A at extinction), with a repeat at 0.4380
            + "{\"state\": \"ext\", \"lca_waves\": 0.2504, \"lcb_waves\": 0.4380, \"intensity\": 1495.0},"
            + "{\"state\": \"ext\", \"lca_waves\": 0.2504, \"lcb_waves\": 0.4380, \"intensity\": 1497.0},"
            + "{\"state\": \"ext\", \"lca_waves\": 0.2504, \"lcb_waves\": 0.4500, \"intensity\": 3844.0},"
            // an earlier extinction iterate off both lines: on neither curve
            + "{\"state\": \"ext\", \"lca_waves\": 0.2494, \"lcb_waves\": 0.4265, \"intensity\": 2448.9},"
            // LC-A swing search (LC-B held)
            + "{\"state\": \"I90\", \"lca_waves\": 0.2804, \"lcb_waves\": 0.4380, \"intensity\": 4893.0},"
            // LC-B swing searches (LC-A held)
            + "{\"state\": \"I45\", \"lca_waves\": 0.2504, \"lcb_waves\": 0.4522, \"intensity\": 4862.0},"
            + "{\"state\": \"I135\", \"lca_waves\": 0.2504, \"lcb_waves\": 0.4129, \"intensity\": 4870.0}"
            + "]}";

    private static JsonObject reply() {
        return JsonParser.parseString(REPLY).getAsJsonObject();
    }

    @Test
    void extinctionIsTheDarkestState() {
        assertEquals("State0", LCCalibrationPlots.extinctionState(reply()).orElseThrow());
    }

    @Test
    void offsetsNameTheCrystalAndTheShareOfTheSwing() {
        List<LCCalibrationPlots.StateOffset> offsets = LCCalibrationPlots.stateOffsets(reply());
        assertEquals(4, offsets.size());
        LCCalibrationPlots.StateOffset s2 = offsets.stream()
                .filter(o -> o.state().equals("State2"))
                .findFirst()
                .orElseThrow();
        assertEquals("LC-B", s2.axis());
        assertEquals(0.0142, s2.offsetWaves(), 1e-9);
        assertEquals(0.0142 / 0.03, s2.fractionOfSwing(), 1e-9);
        LCCalibrationPlots.StateOffset s1 = offsets.stream()
                .filter(o -> o.state().equals("State1"))
                .findFirst()
                .orElseThrow();
        assertEquals("LC-A", s1.axis());
        assertEquals(1.0, s1.fractionOfSwing(), 1e-9);
        assertTrue(
                LCCalibrationPlots.offsetLines(reply()).get(1).contains("47% of the swing"),
                LCCalibrationPlots.offsetLines(reply()).toString());
    }

    @Test
    void curvesKeepOnlyPointsOnTheirLineAndAverageRepeats() {
        List<LCCalibrationPlots.Point> lcb = LCCalibrationPlots.curve(reply(), false);
        // 0.4129, 0.4380 (two readings averaged), 0.4500, 0.4522 -- not the off-line iterate
        assertEquals(4, lcb.size());
        assertEquals(-0.0251, lcb.get(0).x(), 1e-9);
        assertEquals(0.0, lcb.get(1).x(), 1e-9);
        assertEquals(1496.0, lcb.get(1).y(), 1e-9);
        List<LCCalibrationPlots.Point> lca = LCCalibrationPlots.curve(reply(), true);
        // 0.2504 x3 averaged and 0.2804
        assertEquals(2, lca.size());
        assertEquals(0.03, lca.get(1).x(), 1e-9);
    }

    @Test
    void targetIsTheMeanOfTheSwingStates() {
        assertEquals(
                (4875.5 + 4865.6 + 4894.0 + 4864.5) / 4,
                LCCalibrationPlots.targetIntensity(reply()).orElseThrow(),
                1e-9);
    }

    @Test
    void aResultWithoutTraceOrSwingStillDescribesItsPalette() {
        JsonObject r = reply();
        r.remove("trace");
        r.remove("swing_waves");
        assertTrue(LCCalibrationPlots.curve(r, false).isEmpty());
        assertTrue(Double.isNaN(LCCalibrationPlots.stateOffsets(r).get(0).fractionOfSwing()));
        assertFalse(LCCalibrationPlots.offsetLines(r).isEmpty());
    }

    @Test
    void seriesColoursAreModenaLookups() {
        assertEquals("CHART_COLOR_1: #111; CHART_COLOR_2: #222;", LCCalibrationPlots.seriesColors("#111", "#222"));
    }

    /**
     * The same LC-B search with drive volts, as server 1.5.x records them: lopsided in waves
     * (+0.0142 / -0.0251) but -179 / +202 mV in volts, the 2026-10-26 run seen through the table.
     */
    private static final String REPLY_WITH_VOLTS = "{"
            + "\"swing_waves\": 0.03,"
            + "\"palette_waves\": {\"State0\": [0.2504, 0.4380], \"State2\": [0.2504, 0.4522],"
            + " \"State4\": [0.2504, 0.4129]},"
            + "\"state_intensities\": {\"State0\": 1495.8, \"State2\": 4865.6, \"State4\": 4864.5},"
            + "\"palette_readback\": {"
            + " \"State0\": {\"lca_volts\": 7.0145, \"lcb_volts\": 4.3528},"
            + " \"State2\": {\"lca_volts\": 7.0145, \"lcb_volts\": 4.1738},"
            + " \"State4\": {\"lca_volts\": 7.0145, \"lcb_volts\": 4.5548}},"
            + "\"trace\": ["
            + "{\"lca_waves\": 0.2504, \"lcb_waves\": 0.4380, \"lca_volts\": 7.0145, \"lcb_volts\": 4.3528,"
            + " \"intensity\": 1496.0},"
            + "{\"lca_waves\": 0.2504, \"lcb_waves\": 0.4522, \"lca_volts\": 7.0145, \"lcb_volts\": 4.1738,"
            + " \"intensity\": 4862.0},"
            + "{\"lca_waves\": 0.2504, \"lcb_waves\": 0.4129, \"lca_volts\": 7.0145, \"lcb_volts\": 4.5548,"
            + " \"intensity\": 4870.0},"
            // a point without volts (older library) is left out of the volts curve only
            + "{\"lca_waves\": 0.2504, \"lcb_waves\": 0.4500, \"intensity\": 3844.0}"
            + "]}";

    private static JsonObject replyWithVolts() {
        return JsonParser.parseString(REPLY_WITH_VOLTS).getAsJsonObject();
    }

    @Test
    void voltsCurveIsInMillivoltsFromExtinction() {
        assertTrue(LCCalibrationPlots.hasVolts(replyWithVolts()));
        List<LCCalibrationPlots.Point> lcb = LCCalibrationPlots.curve(replyWithVolts(), false, true);
        assertEquals(3, lcb.size(), lcb.toString());
        assertEquals(-179.0, lcb.get(0).x(), 1e-9);
        assertEquals(0.0, lcb.get(1).x(), 1e-9);
        assertEquals(202.0, lcb.get(2).x(), 1e-9);
        assertEquals(4, LCCalibrationPlots.curve(replyWithVolts(), false, false).size(), "waves keep every point");
    }

    @Test
    void offsetLinesCarryTheVoltsWhenThereIsAReadback() {
        List<String> lines = LCCalibrationPlots.offsetLines(replyWithVolts());
        assertTrue(lines.get(0).contains("+0.0142 waves (-179 mV)"), lines.toString());
        assertTrue(lines.get(1).contains("-0.0251 waves (+202 mV)"), lines.toString());
        assertFalse(LCCalibrationPlots.offsetLines(reply()).get(1).contains("mV"), "no readback, no volts");
    }

    @Test
    void anOldResultHasNoVolts() {
        assertFalse(LCCalibrationPlots.hasVolts(reply()));
        assertTrue(LCCalibrationPlots.curve(reply(), false, true).isEmpty());
    }
}
