package qupath.ext.qpsc.service.microscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The FLATFIT reply as the server writes it. */
class FlatfieldFitReplyTest {

    @Test
    void readsOneProfilePerChannel() {
        List<MicroscopeSocketClient.FlatfieldFit> fits = MicroscopeSocketClient.parseFlatfieldFit(
                "SUCCESS:{\"profiles\": [{\"channel\": \"DAPI\", \"offset\": 6534.0,"
                        + " \"flatfield_min\": 0.936, \"flatfield_max\": 1.039,"
                        + " \"images_used\": 250, \"images_read\": 499},"
                        + " {\"channel\": null, \"offset\": 100.0, \"flatfield_min\": 0.9,"
                        + " \"flatfield_max\": 1.1, \"images_used\": 40, \"images_read\": 60}]}");
        assertEquals(2, fits.size());
        MicroscopeSocketClient.FlatfieldFit fit = fits.get(0);
        assertEquals("DAPI", fit.channel());
        assertEquals(0.936, fit.flatfieldMin(), 1e-9);
        assertEquals(1.039, fit.flatfieldMax(), 1e-9);
        assertEquals(6534.0, fit.offset(), 1e-9);
        assertEquals(250, fit.imagesUsed());
        assertEquals(499, fit.imagesRead());
        assertNull(fits.get(1).channel());
    }
}
