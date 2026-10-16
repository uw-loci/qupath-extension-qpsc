package qupath.ext.qpsc.ui.liveviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The trace keeps the faded P98 - P2 curve beside the main metric, on its own range. */
class FocusTraceModelTest {

    @Test
    void eachSampleCarriesBothMetricsAndEachHasItsOwnRange() {
        FocusTraceModel trace = new FocusTraceModel();
        trace.addSample(10.0, 5.0, 100.0);
        trace.addSample(20.0, 9.0, 40.0);
        List<double[]> samples = trace.snapshot();
        assertEquals(2, samples.size());
        assertEquals(3, samples.get(0).length);
        assertEquals(100.0, samples.get(0)[2]);
        assertEquals(5.0, trace.getRunningMin());
        assertEquals(9.0, trace.getRunningMax());
        assertEquals(40.0, trace.getBackgroundMin());
        assertEquals(100.0, trace.getBackgroundMax());
    }

    @Test
    void aSampleWithoutABackgroundValueLeavesItsRangeAlone() {
        FocusTraceModel trace = new FocusTraceModel();
        trace.addSample(10.0, 5.0, Double.NaN);
        assertEquals(Double.POSITIVE_INFINITY, trace.getBackgroundMin());
        trace.addSample(20.0, 6.0, 30.0);
        assertEquals(30.0, trace.getBackgroundMin());
        assertEquals(30.0, trace.getBackgroundMax());
    }

    @Test
    void clearingResetsTheBackgroundRange() {
        FocusTraceModel trace = new FocusTraceModel();
        trace.addSample(10.0, 5.0, 50.0);
        trace.clear();
        assertTrue(trace.isEmpty());
        assertEquals(Double.NEGATIVE_INFINITY, trace.getBackgroundMax());
    }
}
