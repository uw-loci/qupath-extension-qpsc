package qupath.ext.qpsc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;

/**
 * REQFINSH: the server's notice that a run no longer needs the microscope. QPSC switches the stage
 * check off when it arrives, so it must be read exactly as the server frames it, and an older
 * server without the command must simply read as "no notice".
 */
class FinishingNoticeTest {

    private MockMicroscopeServer server;
    private MicroscopeSocketClient client;
    private String savedConfigPath;

    @BeforeEach
    void connect() throws IOException {
        savedConfigPath = MockMicroscopeServer.useMockConfigPath();
        server = MockMicroscopeServer.createOnRandomPort();
        server.start();
        client = new MicroscopeSocketClient("localhost", server.getPort());
        client.connect();
    }

    @AfterEach
    void close() {
        MockMicroscopeServer.restoreConfigPath(savedConfigPath);
        client.close();
        server.stop();
    }

    private static byte[] framed(String status, String message) {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(status.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(ByteBuffer.allocate(4).putInt(body.length).array());
        out.writeBytes(body);
        return out.toByteArray();
    }

    @Test
    void theNoticeIsReadWhenTheServerSendsIt() {
        String notice = "All images are acquired and the microscope is parked.";
        server.scriptMessageless("reqfinsh", framed("FINSH___", notice));
        assertEquals(notice, client.checkFinishingNotice());
    }

    @Test
    void beforeTheMicroscopeIsReleasedThereIsNoNotice() {
        server.scriptMessageless("reqfinsh", "IDLE____".getBytes(StandardCharsets.US_ASCII));
        assertNull(client.checkFinishingNotice());
    }
}
