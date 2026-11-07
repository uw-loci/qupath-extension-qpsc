package qupath.ext.qpsc.ui.acquisition;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The line the assignment dialog logs per slot as it closes: the only record once it is gone. */
class MultiSlideSlotLogTest {

    @Test
    void anEmptySlotSaysEmpty() {
        assertEquals("empty", MultiSlideAssignmentDialog.slotText(null, 270, false));
        assertEquals("empty", MultiSlideAssignmentDialog.slotText(null, null, true));
    }

    @Test
    void aSkippedSlotStillNamesItsSlide() {
        assertEquals(
                "'MetroHealth_211.svs', rotation 270 deg, skipped (in the slot, not acquired)",
                MultiSlideAssignmentDialog.slotText("MetroHealth_211.svs", 270, true));
    }

    @Test
    void anAcquiredSlotNamesItsSlideAndRotation() {
        assertEquals(
                "'MetroHealth_183.svs', rotation 0 deg, acquire",
                MultiSlideAssignmentDialog.slotText("MetroHealth_183.svs", null, false));
    }
}
