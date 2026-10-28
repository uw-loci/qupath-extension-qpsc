package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The remembered Stage Map captions round-trip, and a caption's "(A)" suffix comes off cleanly. */
class LightPathModelCaptionStoreTest {

    @Test
    void theStoreRoundTripsAndSkipsBrokenLines() {
        Map<String, String[]> store = new LinkedHashMap<>();
        store.put("LCPolScope_Hamamatsu|single_h", new String[] {"Label on your right (A)", "Label on your left (B)"});
        store.put("PPM|quad_v", new String[] {"Label away from you (A)", "Label toward you (B)"});
        String text = LightPathModel.formatCaptionStore(store) + "garbage line\n\tonly\ttabs\n";
        Map<String, String[]> back = LightPathModel.parseCaptionStore(text);
        assertEquals(2, back.size());
        assertArrayEquals(store.get("PPM|quad_v"), back.get("PPM|quad_v"));
        assertArrayEquals(store.get("LCPolScope_Hamamatsu|single_h"), back.get("LCPolScope_Hamamatsu|single_h"));
        assertTrue(LightPathModel.parseCaptionStore(null).isEmpty());
        assertTrue(LightPathModel.parseCaptionStore("").isEmpty());
    }

    @Test
    void theSuffixComesOff() {
        assertEquals("Label on your right", LightPathModel.stripPlacementSuffix("Label on your right (A)"));
        assertEquals("Label toward you", LightPathModel.stripPlacementSuffix("Label toward you (B)"));
        assertEquals("Placement A (as scanned)", LightPathModel.stripPlacementSuffix("Placement A (as scanned)"));
    }
}
