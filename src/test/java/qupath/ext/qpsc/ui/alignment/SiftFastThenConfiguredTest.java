package qupath.ext.qpsc.ui.alignment;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;

/**
 * The fast SIFT search hands over to the configured one. On 2026-11-09 a no-match reply from the
 * fast search was thrown as an error, so the configured 1 mm search never ran and SIFT only matched
 * once the stage was within the fast search's 179 um.
 */
class SiftFastThenConfiguredTest {

    private static final double[] MATCH = {12.0, -3.0, 500, 0.99};

    @Test
    void noMatchInTheFastSearchRunsTheConfiguredSearch() throws Exception {
        AtomicInteger configuredRuns = new AtomicInteger();
        double[] result = SiftAutoAlignHelper.fastThenConfigured(
                () -> null,
                179,
                () -> {
                    configuredRuns.incrementAndGet();
                    return MATCH;
                },
                1000,
                0.5);
        assertEquals(1, configuredRuns.get());
        assertArrayEquals(MATCH, result);
    }

    @Test
    void weakFastMatchRunsTheConfiguredSearch() throws Exception {
        AtomicInteger configuredRuns = new AtomicInteger();
        SiftAutoAlignHelper.fastThenConfigured(
                () -> new double[] {1, 1, 20, 0.2},
                179,
                () -> {
                    configuredRuns.incrementAndGet();
                    return MATCH;
                },
                1000,
                0.5);
        assertEquals(1, configuredRuns.get());
    }

    @Test
    void confidentFastMatchSkipsTheConfiguredSearch() throws Exception {
        double[] result = SiftAutoAlignHelper.fastThenConfigured(
                () -> MATCH,
                179,
                () -> {
                    throw new AssertionError("configured search should not run");
                },
                1000,
                0.5);
        assertArrayEquals(MATCH, result);
    }

    @Test
    void noFastSearchGoesStraightToTheConfiguredOne() throws Exception {
        assertArrayEquals(MATCH, SiftAutoAlignHelper.fastThenConfigured(null, 0, () -> MATCH, 1000, 0.5));
    }

    @Test
    void onlyTheServersNoMatchReplyCountsAsNoMatch() {
        assertTrue(
                MicroscopeSocketClient.isSiftNoMatch("FAILED:SIFT matching failed - insufficient features or matches"));
        assertFalse(MicroscopeSocketClient.isSiftNoMatch("FAILED:OpenCV not available: no module"));
        assertFalse(MicroscopeSocketClient.isSiftNoMatch("FAILED:Missing --image-a or --image-b"));
        assertFalse(MicroscopeSocketClient.isSiftNoMatch("SUCCESS:1,2|inliers:3|confidence:0.9"));
        assertFalse(MicroscopeSocketClient.isSiftNoMatch(null));
    }
}
