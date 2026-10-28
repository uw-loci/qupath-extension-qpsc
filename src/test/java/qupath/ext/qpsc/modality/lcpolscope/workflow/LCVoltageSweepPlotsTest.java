package qupath.ext.qpsc.modality.lcpolscope.workflow;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The data behind the LCSWEEP result, from a record and summary shaped like the server's. */
class LCVoltageSweepPlotsTest {

    private static final String RECORD = "{"
            + "\"axes\": {"
            + " \"LCB\": {"
            + "  \"curve\": {\"volts\": [4.6, 4.2, 4.4], \"waves\": [0.41, 0.47, 0.44]},"
            + "  \"adapter_table\": {\"volts\": [4.17, 4.35, 4.56], \"waves\": [0.4526, 0.4386, 0.4138]}"
            + " },"
            + " \"LCA\": {\"error\": \"fringe analysis failed: found 1 fringe extrema\"}"
            + "},"
            + "\"palette\": {"
            + " \"State0\": {\"lcb_volts\": 4.353, \"lcb_waves_commanded\": 0.4386, \"lcb_waves_measured\": 0.4415},"
            + " \"State2\": {\"lcb_volts\": 4.174, \"lcb_waves_commanded\": 0.4526, \"lcb_waves_measured\": 0.4715},"
            + " \"State4\": {\"lcb_volts\": 4.555, \"lcb_waves_commanded\": 0.4138, \"lcb_waves_measured\": null}"
            + "}}";

    private static final String SUMMARY = "{"
            + "\"success\": true,"
            + "\"axes\": {"
            + " \"LCA\": {\"error\": \"fringe analysis failed: found 1 fringe extrema\"},"
            + " \"LCB\": {\"landmarks\": [{}, {}, {}], \"exposure_ms\": 4.25,"
            + "  \"table_minus_measured_waves\": {\"max_abs\": 0.0213, \"at_volts\": 4.41}}"
            + "},"
            + "\"palette_lines\": [\"State2: LC-B +0.0140 commanded = +0.0300 measured (-179 mV)\"]"
            + "}";

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void curvesComeBackSortedByVoltage() {
        List<LCVoltageSweepPlots.Point> m = LCVoltageSweepPlots.measured(json(RECORD), "LCB");
        assertEquals(3, m.size());
        assertEquals(4.2, m.get(0).volts(), 1e-12);
        assertEquals(0.47, m.get(0).waves(), 1e-12);
        assertEquals(3, LCVoltageSweepPlots.table(json(RECORD), "LCB").size());
        assertTrue(LCVoltageSweepPlots.measured(json(RECORD), "LCA").isEmpty(), "an axis with an error has no curve");
    }

    @Test
    void paletteKeepsStatesWithVoltsAndMarksUnmeasuredOnes() {
        List<LCVoltageSweepPlots.StatePoint> states = LCVoltageSweepPlots.palette(json(RECORD), "LCB");
        assertEquals(3, states.size());
        assertEquals(0.4715, states.get(1).measuredWaves(), 1e-12);
        assertTrue(Double.isNaN(states.get(2).measuredWaves()), "null measured waves is NaN, not 0");
        assertTrue(LCVoltageSweepPlots.palette(json(RECORD), "LCA").isEmpty());
    }

    @Test
    void summaryNamesErrorsGapsAndOffsets() {
        List<String> lines = LCVoltageSweepPlots.summaryLines(json(SUMMARY));
        assertEquals("LC-A: fringe analysis failed: found 1 fringe extrema", lines.get(0));
        assertTrue(lines.get(1).startsWith("LC-B: 3 fringes, exposure 4.25 ms"), lines.get(1));
        assertTrue(lines.get(1).contains("off by up to 0.0213 waves (at 4.41 V)"), lines.get(1));
        assertTrue(lines.get(lines.size() - 1).contains("State2: LC-B +0.0140 commanded"), lines.toString());
    }

    @Test
    void niceStepsAreRound() {
        assertEquals(0.2, LCVoltageSweepPlots.niceStep(1.5), 1e-12);
        assertEquals(2.0, LCVoltageSweepPlots.niceStep(15), 1e-12);
        assertEquals(1.0, LCVoltageSweepPlots.niceStep(0), 1e-12);
    }

    @Test
    void recordIsReadFromDiskOrNull(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lc_sweep_x.json");
        Files.writeString(file, RECORD);
        assertNotNull(LCVoltageSweepWorkflow.readRecord(file.toString()));
        assertNull(LCVoltageSweepWorkflow.readRecord(dir.resolve("missing.json").toString()));
        assertNull(LCVoltageSweepWorkflow.readRecord(null));
    }
}
