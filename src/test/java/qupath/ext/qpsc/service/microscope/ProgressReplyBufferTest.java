package qupath.ext.qpsc.service.microscope;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** A long command's reply stream, cut and glued the ways TCP delivers it. */
class ProgressReplyBufferTest {

    private static final String RESULT = "{\"success\": true, \"extinction_ratio\": 264.68, \"full_scale\": 4095.0}";

    @Test
    void aResultGluedToTheLastProgressMessageIsNotLost() {
        // The 2026-10-19 demo run: the last progress message and the head of the result
        // arrived in one read, the rest of the result in the next.
        ProgressReplyBuffer b = new ProgressReplyBuffer();
        int cut = RESULT.indexOf("full");
        b.append("PROGRESS:9:9:Black level 100 counts" + "SUCCESS:" + RESULT.substring(0, cut));
        assertNull(b.success(), "half a result is not a result");
        assertNull(b.latestProgressMessage(), "a result has started; progress is over");
        b.append(RESULT.substring(cut));
        assertEquals(RESULT, b.success());
        assertNull(b.failure());
    }

    @Test
    void severalProgressMessagesInOneReadReportTheNewest() {
        ProgressReplyBuffer b = new ProgressReplyBuffer();
        b.append("PROGRESS:1:5:Grid 1PROGRESS:2:5:Grid 2PROGRESS:3:5:Searching LC-A");
        assertEquals("Searching LC-A", b.latestProgressMessage());
        assertNull(b.latestProgressMessage(), "nothing new since");
        b.append("PROGRESS:4:5:Swing states");
        assertEquals("Swing states", b.latestProgressMessage());
    }

    @Test
    void aProgressMessageCutInHalfIsReadWhole() {
        ProgressReplyBuffer b = new ProgressReplyBuffer();
        b.append("PROGRESS:1:5:Sear");
        assertEquals("Sear", b.latestProgressMessage());
        b.append("ching");
        assertEquals("Searching", b.latestProgressMessage(), "the rest of the same message");
    }

    @Test
    void aFailureIsReported() {
        ProgressReplyBuffer b = new ProgressReplyBuffer();
        b.append("PROGRESS:1:5:Grid 1FAILED:field too dark");
        assertEquals("field too dark", b.failure());
        assertNull(b.success());
    }
}
