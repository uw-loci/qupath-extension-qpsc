package qupath.ext.qpsc.modality.lcpolscope.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The per-slide reminder: who it asks, and that its text says what to do and what is in use.
 * The dialog itself is not opened here.
 */
class LCPolScopeSlideReminderTest {

    @Test
    void asksOnlyForLcPolScopeOutsideABatch() {
        assertTrue(LCPolScopeSlideReminder.shouldAsk("lcpolscope", false));
        assertTrue(LCPolScopeSlideReminder.shouldAsk("lcpolscope_20x", false));
        assertTrue(LCPolScopeSlideReminder.shouldAsk("lcps", false));
        // A running multi-slide batch asked once at Acquire All; asking per slide would stall it.
        assertFalse(LCPolScopeSlideReminder.shouldAsk("lcpolscope", true));
        assertFalse(LCPolScopeSlideReminder.shouldAsk("ppm", false));
        assertFalse(LCPolScopeSlideReminder.shouldAsk("Brightfield_10x", false));
        assertFalse(LCPolScopeSlideReminder.shouldAsk(null, false));
    }

    @Test
    void singleBodyNamesBothStepsAndTheStateInUse() {
        String body = LCPolScopeSlideReminder.singleBody(
                List.of("Calibration 2026-10-26 19:02:22 (2 days ago), extinction ratio 281.0"));
        assertTrue(body.contains("Calibrate Liquid Crystals"), body);
        assertTrue(body.contains("Collect the background"), body);
        assertTrue(body.contains("THIS slide"), body);
        assertTrue(body.contains("In use now:\n  - Calibration 2026-10-26"), body);
    }

    @Test
    void batchBodyCountsSlidesAndOmitsAnEmptyState() {
        String one = LCPolScopeSlideReminder.batchBody(1, List.of());
        assertTrue(one.startsWith("One LC-PolScope slide"), one);
        assertFalse(one.contains("In use now"), one);
        assertTrue(LCPolScopeSlideReminder.batchBody(3, List.of()).startsWith("3 LC-PolScope slides"));
    }

    @Test
    void noConfigMeansNoStateLines() {
        assertTrue(
                LCPolScopeSlideReminder.currentState(null, LocalDateTime.now()).isEmpty());
    }

    @Test
    void zeroLcSlidesNeedsNoAnswer() {
        assertTrue(LCPolScopeSlideReminder.confirmBatch(0));
    }

    @Test
    void textIsAscii() {
        String all =
                LCPolScopeSlideReminder.singleBody(List.of("x")) + LCPolScopeSlideReminder.batchBody(2, List.of("x"));
        assertTrue(all.chars().allMatch(c -> c < 128), "non-ASCII in the reminder text");
    }
}
