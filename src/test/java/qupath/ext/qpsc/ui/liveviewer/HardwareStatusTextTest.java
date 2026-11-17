package qupath.ext.qpsc.ui.liveviewer;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** The Live Viewer banner names the part that is not answering and since when. */
class HardwareStatusTextTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void everythingAnsweringShowsNoBanner() {
        assertNull(HardwareStatusText.describe(
                json("{\"stage\":{\"ok\":true},\"camera\":{\"ok\":true},\"micromanager\":{\"ok\":true}}"),
                ZoneOffset.UTC));
        assertNull(HardwareStatusText.describe(null, ZoneOffset.UTC));
    }

    @Test
    void aFailingPartIsNamedWithTheTimeItStarted() {
        // 1700000000 s = 2023-11-14 22:13:20 UTC
        String text = HardwareStatusText.describe(
                json("{\"stage\":{\"ok\":false,\"since\":1700000000.0,\"message\":\"x\"},"
                        + "\"camera\":{\"ok\":true},\"micromanager\":{\"ok\":true}}"),
                ZoneOffset.UTC);
        assertEquals("Server connected; Stage not responding since 22:13", text);
    }
}
