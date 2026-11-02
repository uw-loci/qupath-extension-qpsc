package qupath.ext.qpsc;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.service.microscope.ServerAnswerException;

/**
 * How commands that the server acknowledges and then answers read their replies, against the
 * replies the server really sends. Runs each command through the wire, so it also pins each
 * command's name.
 */
class SocketCommandRepliesTest {

    private static final String ACK = "STARTED:out";

    /** One command: its wire name, a real success reply, and how to call it. */
    private record AnsweredCommand(String label, String wireName, String success, Call call) {
        @Override
        public String toString() {
            return label;
        }
    }

    @FunctionalInterface
    private interface Call {
        Object run(MicroscopeSocketClient client) throws IOException;
    }

    private static Stream<AnsweredCommand> commands() {
        return Stream.of(
                new AnsweredCommand(
                        "autofocus test",
                        "testaf",
                        "SUCCESS:plot.png|10.0:12.0:2.0",
                        c -> c.testAutofocus("c.yml", "out", "OBJ")),
                new AnsweredCommand(
                        "autofocus validation",
                        "testafv",
                        "SUCCESS:{\"message\": \"ok\"}",
                        c -> c.testAutofocusValidation("c.yml", "out", "OBJ")),
                new AnsweredCommand(
                        "PPM sensitivity test",
                        "ppmsens",
                        "SUCCESS:out/result",
                        c -> c.runPPMSensitivityTest(new MicroscopeSocketClient.PPMSensitivityParams(
                                "c.yml", "out", "comprehensive", 7.0, 3))),
                new AnsweredCommand(
                        "polarizer calibration",
                        "polcal",
                        "SUCCESS:out/report.txt",
                        c -> c.startPolarizerCalibration("c.yml", "out", 0, 180, 5, 10)),
                new AnsweredCommand(
                        "sunburst calibration",
                        "sbcalib",
                        "SUCCESS:{\"r2\": 0.99}",
                        c -> c.runSunburstCalibration(
                                "c.yml", "out", "ppm", 16, 0.1, 0.1, "cal", 30, 150, "image.tif", 100, 100)),
                new AnsweredCommand(
                        "simple white balance",
                        "wbsimple",
                        "SUCCESS:out|CONVERGED|exp_r:10,exp_g:11,exp_b:12|gain:1.0,analog_red:1.0,analog_blue:1.0",
                        c -> c.runSimpleWhiteBalance(
                                "out", 10, 200, 5, 12, 1.2, 10, false, 1, 100, 24, 2, "c.yml", "OBJ", "DET", "ppm", 0,
                                0, 0)),
                new AnsweredCommand(
                        "background acquisition",
                        "bgacquir",
                        "SUCCESS:out|10.0|lamp=none",
                        c -> c.startBackgroundAcquisition(
                                "c.yml", "out", "ppm", "(7.0)", "(10.0)", "off", "OBJ", "DET", 0, "")));
    }

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

    private int connectionErrors() throws Exception {
        var errors = MicroscopeSocketClient.class.getDeclaredField("consecutiveErrors");
        errors.setAccessible(true);
        return ((AtomicInteger) errors.get(client)).get();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("commands")
    void returnsOnceTheResultArrives(AnsweredCommand command) throws Exception {
        server.script(command.wireName(), ACK, command.success());
        assertNotNull(command.call().run(client));
        assertTrue(server.getLastMessage(command.wireName()).contains("ENDOFSTR"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("commands")
    void anAcknowledgmentAndResultInOneReadStillComplete(AnsweredCommand command) throws Exception {
        server.script(command.wireName(), ACK + command.success());
        assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(5), () -> command.call().run(client));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("commands")
    void aRejectionIsAnAnswerAndLeavesTheConnectionAlone(AnsweredCommand command) throws Exception {
        server.script(command.wireName(), "FAILED:no such profile");
        var answer =
                assertThrows(ServerAnswerException.class, () -> command.call().run(client));
        assertTrue(answer.getMessage().contains("no such profile"), answer.getMessage());
        assertEquals("no such profile", answer.reason());
        assertEquals(0, connectionErrors());
        assertTrue(client.isConnected());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("commands")
    void aFailureAfterStartingIsAnAnswerToo(AnsweredCommand command) throws Exception {
        server.script(command.wireName(), ACK, "FAILED:stage stopped");
        var answer =
                assertThrows(ServerAnswerException.class, () -> command.call().run(client));
        assertEquals("stage stopped", answer.reason());
        assertEquals(0, connectionErrors());
    }

    @Test
    void autofocusTestParsesItsResult() throws Exception {
        server.script("testaf", ACK, "SUCCESS:plot.png|10.0:12.0:2.0");
        var result = client.testAutofocus("c.yml", "out", "OBJ");
        assertEquals("plot.png", result.get("plot_path"));
        assertEquals("12.0", result.get("final_z"));
    }

    @Test
    void autofocusValidationReportsACancel() throws Exception {
        server.script("testafv", ACK, "CANCELLED:operator");
        assertEquals(
                "true", client.testAutofocusValidation("c.yml", "out", "OBJ").get("cancelled"));
    }

    @Test
    void sensitivityTestReturnsItsOutputFolder() throws Exception {
        server.script("ppmsens", ACK, "SUCCESS:out/result");
        var params = new MicroscopeSocketClient.PPMSensitivityParams("c.yml", "out", "comprehensive", 7.0, 3);
        assertEquals("out/result", client.runPPMSensitivityTest(params));
    }

    @Test
    void whiteBalanceParsesItsResult() throws Exception {
        server.script(
                "wbsimple",
                ACK,
                "SUCCESS:out|CONVERGED|exp_r:10,exp_g:11,exp_b:12|gain:1.0,analog_red:1.0,analog_blue:1.0");
        var result = client.runSimpleWhiteBalance(
                "out", 10, 200, 5, 12, 1.2, 10, false, 1, 100, 24, 2, "c.yml", "OBJ", "DET", "ppm", 0, 0, 0);
        assertTrue(result.toString().contains("10"), result.toString());
    }

    @Test
    void repliesLongerThanTheOldBuffersArriveWhole() throws Exception {
        String path = "out/" + "x".repeat(5000);
        server.script("ppmsens", ACK, "SUCCESS:" + path);
        var params = new MicroscopeSocketClient.PPMSensitivityParams("c.yml", "out", "comprehensive", 7.0, 3);
        assertEquals(path, client.runPPMSensitivityTest(params));
    }

    @Test
    void progressBeforeTheResultIsSkipped() {
        assertNull(MicroscopeSocketClient.finalReplyIn("STARTED:out"));
        assertNull(MicroscopeSocketClient.finalReplyIn("PROGRESS:1:5:working"));
        assertEquals("SUCCESS:x", MicroscopeSocketClient.finalReplyIn("STARTED:outPROGRESS:1:5:aSUCCESS:x"));
        assertEquals("FAILED:y", MicroscopeSocketClient.finalReplyIn("STARTED:outFAILED:y"));
        assertEquals("SUCCESS:x", MicroscopeSocketClient.finalReplyIn("SUCCESS:x"));
    }
}
