package qupath.ext.qpsc.service.microscope;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The FLATFIT reply as the server writes it. */
class FlatfieldFitReplyTest {

    @Test
    void readsTheServerSummary() {
        MicroscopeSocketClient.FlatfieldFit fit = MicroscopeSocketClient.parseFlatfieldFit(
                "SUCCESS:{\"flatfield_min\": 0.936, \"flatfield_max\": 1.039, \"offset\": 6534.0,"
                        + " \"images_used\": 250, \"images_read\": 499}");
        assertEquals(0.936, fit.flatfieldMin(), 1e-9);
        assertEquals(1.039, fit.flatfieldMax(), 1e-9);
        assertEquals(6534.0, fit.offset(), 1e-9);
        assertEquals(250, fit.imagesUsed());
        assertEquals(499, fit.imagesRead());
    }
}
