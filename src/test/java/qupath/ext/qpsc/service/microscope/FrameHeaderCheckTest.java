package qupath.ext.qpsc.service.microscope;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class FrameHeaderCheckTest {

    @Test
    void aRawFrameHeaderIsAccepted() {
        assertDoesNotThrow(() -> MicroscopeSocketClient.checkFrameHeader(2048, 2048, 1, 2, 2048 * 2048 * 2));
        assertDoesNotThrow(() -> MicroscopeSocketClient.checkFrameHeader(1920, 1200, 3, 1, 1920 * 1200 * 3));
    }

    @Test
    void aHeaderFromAStreamOutOfStepIsAnIoException() {
        // A negative length used to throw NegativeArraySizeException, which skipped the
        // socket reset and left every later frame out of step.
        assertThrows(IOException.class, () -> MicroscopeSocketClient.checkFrameHeader(2048, 2048, 1, 2, -5));
        assertThrows(IOException.class, () -> MicroscopeSocketClient.checkFrameHeader(2048, 2048, 1, 2, 100));
        assertThrows(IOException.class, () -> MicroscopeSocketClient.checkFrameHeader(-1, 2048, 1, 2, 0));
        // Self-consistent but larger than any camera frame: refused before the allocation.
        assertThrows(IOException.class, () -> MicroscopeSocketClient.checkFrameHeader(20000, 20000, 1, 2, 800_000_000));
    }
}
