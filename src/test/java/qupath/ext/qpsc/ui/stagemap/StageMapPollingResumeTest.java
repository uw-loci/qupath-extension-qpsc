package qupath.ext.qpsc.ui.stagemap;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** After repeated failures the Stage Map keeps trying, slowly, so it recovers when the stage does. */
class StageMapPollingResumeTest {

    @Test
    void healthyPollingRunsEveryTickAndPausedPollingRetriesEveryFewSeconds() {
        for (long tick = 1; tick <= 50; tick++) {
            assertTrue(StageMapWindow.shouldPoll(0, tick));
        }
        int paused = StageMapWindow.MAX_CONSECUTIVE_ERRORS;
        long tries = 0;
        for (long tick = 1; tick <= 100; tick++) {
            if (StageMapWindow.shouldPoll(paused, tick)) {
                tries++;
            }
        }
        assertEquals(100 / StageMapWindow.PAUSED_PROBE_EVERY, tries, "never stops trying");
    }
}
