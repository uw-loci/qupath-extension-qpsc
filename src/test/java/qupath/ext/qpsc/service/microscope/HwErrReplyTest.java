package qupath.ext.qpsc.service.microscope;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The GETZ and GETR replies are one float, or the 5-byte marker {@code HWERR} in its place.
 *
 * <p>The marker is one byte longer than the value. The client must recognize it from the four
 * bytes it reads and then take the fifth off the socket, or the next reply is read one byte late.
 */
class HwErrReplyTest {

    private static DataInputStream stream(byte[]... parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part);
        }
        return new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
    }

    private static byte[] floatBytes(float value) {
        return ByteBuffer.allocate(4).putFloat(value).array();
    }

    @Test
    @DisplayName("A float reply is returned and nothing after it is consumed")
    void floatReply() throws IOException {
        DataInputStream in = stream(floatBytes(-420.5f), floatBytes(7.0f));
        assertEquals(-420.5f, MicroscopeSocketClient.readFloatOrHwErr(in, "z"));
        assertEquals(7.0f, MicroscopeSocketClient.readFloatOrHwErr(in, "r"));
        assertEquals(0, in.available());
    }

    @Test
    @DisplayName("NaN (no rotation stage) is a value, not an error")
    void nanIsAValue() throws IOException {
        DataInputStream in = stream(floatBytes(Float.NaN));
        assertTrue(Float.isNaN(MicroscopeSocketClient.readFloatOrHwErr(in, "r")));
    }

    @Test
    @DisplayName("HWERR raises a hardware exception and leaves the socket at the next reply")
    void hwErrIsConsumedWhole() throws IOException {
        DataInputStream in = stream("HWERR".getBytes(StandardCharsets.US_ASCII), floatBytes(12.25f));
        MicroscopeHardwareException e = assertThrows(
                MicroscopeHardwareException.class,
                () -> MicroscopeSocketClient.readFloatOrHwErr(in, "Hardware error getting Z position."));
        assertEquals("Hardware error getting Z position.", e.getMessage());
        // The byte after the marker belongs to the next reply.
        assertEquals(12.25f, MicroscopeSocketClient.readFloatOrHwErr(in, "z"));
        assertEquals(0, in.available());
    }

    @Test
    @DisplayName("A reply cut short is an I/O failure, not a value")
    void truncatedReply() throws IOException {
        DataInputStream in = stream(new byte[] {0x42, 0x28});
        assertThrows(EOFException.class, () -> MicroscopeSocketClient.readFloatOrHwErr(in, "z"));
    }
}
