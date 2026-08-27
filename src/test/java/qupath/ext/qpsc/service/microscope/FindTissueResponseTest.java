package qupath.ext.qpsc.service.microscope;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The FINDTISS wire format, parsed without a socket.
 *
 * <p>Worth pinning down because the three response shapes mean materially different things
 * to the caller: FOUND says the stage was left somewhere new, NOT_FOUND says it was put
 * back, and FAILED says nothing moved at all. Confusing the first two would leave the
 * alignment predicting against a position the stage is not at.
 */
class FindTissueResponseTest {

    @Test
    void parsesFound() throws IOException {
        var result = MicroscopeSocketClient.parseFindTissueResponse("FOUND:1234.500:-987.250:3:7");
        assertEquals(MicroscopeSocketClient.FindTissueResult.Status.FOUND, result.status());
        assertEquals(1234.5, result.x(), 1e-6);
        assertEquals(-987.25, result.y(), 1e-6);
        assertEquals(3, result.attempt());
        assertEquals(7, result.attempts());
        assertTrue(result.found());
    }

    @Test
    void parsesNotFoundAndReportsTheReturnedPosition() throws IOException {
        // The server puts the stage back where the search started, so these coordinates are
        // the caller's own prediction -- not a place the search decided on.
        var result = MicroscopeSocketClient.parseFindTissueResponse("NOTFOUND:100.000:200.000:7");
        assertEquals(MicroscopeSocketClient.FindTissueResult.Status.NOT_FOUND, result.status());
        assertEquals(100.0, result.x(), 1e-6);
        assertEquals(200.0, result.y(), 1e-6);
        assertEquals(7, result.attempts());
        assertFalse(result.found());
        assertNotNull(result.reason());
    }

    @Test
    void parsesFailedWithItsReason() throws IOException {
        var result = MicroscopeSocketClient.parseFindTissueResponse("FAILED:no --step and the camera FOV is unknown");
        assertEquals(MicroscopeSocketClient.FindTissueResult.Status.FAILED, result.status());
        assertFalse(result.found());
        assertEquals("no --step and the camera FOV is unknown", result.reason());
    }

    @Test
    void toleratesSurroundingWhitespace() throws IOException {
        assertTrue(MicroscopeSocketClient.parseFindTissueResponse("  FOUND:1:2:1:4\n")
                .found());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FOUND:1:2:3", "NOTFOUND:1:2", "FOUND:x:2:3:4", "NOTFOUND:1:2:many"})
    void malformedPayloadThrowsRatherThanGuessing(String response) {
        // A half-parsed position is worse than an exception: the caller would move on
        // believing the stage is somewhere it is not.
        assertThrows(IOException.class, () -> MicroscopeSocketClient.parseFindTissueResponse(response));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "OK", "SUCCESS:1:2:3:4:5"})
    void unknownPrefixThrows(String response) {
        assertThrows(IOException.class, () -> MicroscopeSocketClient.parseFindTissueResponse(response));
    }

    @Test
    void nullResponseThrows() {
        assertThrows(IOException.class, () -> MicroscopeSocketClient.parseFindTissueResponse(null));
    }
}
