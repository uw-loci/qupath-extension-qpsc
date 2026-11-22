package qupath.ext.qpsc.service.microscope;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * ZSTACK and TLAPSE messages are split by the server's {@code split_flag_message}
 * (microscope_command_server/server/handlers/utils.py): on spaces, keeping double-quoted runs
 * whole. A path flag must arrive as one token there.
 */
class ZStackPathQuotingTest {

    /** The same pattern as split_flag_message: {@code "([^"]*)"|(\S+)}. */
    private static List<String> splitLikeTheServer(String message) {
        Matcher m = Pattern.compile("\"([^\"]*)\"|(\\S+)").matcher(message);
        List<String> parts = new ArrayList<>();
        while (m.find()) {
            parts.add(m.group(1) != null ? m.group(1) : m.group(2));
        }
        return parts;
    }

    @Test
    void aPathWithSpacesStaysOneToken() {
        StringBuilder msg = new StringBuilder();
        MicroscopeSocketClient.appendQuotedPath(msg, "--output", "D:\\QPSC data\\run 1");
        msg.append(" --z-start ").append(10.0);
        MicroscopeSocketClient.appendQuotedPath(msg, "--yaml", "C:/configs/config PPM.yml");

        List<String> parts = splitLikeTheServer(msg.toString());

        assertEquals(
                List.of("--output", "D:\\QPSC data\\run 1", "--z-start", "10.0", "--yaml", "C:/configs/config PPM.yml"),
                parts);
    }

    @Test
    void theWhiteBalanceFolderIsSentOnlyWhenWhiteBalanceIsOn() {
        StringBuilder on = new StringBuilder("--output \"x\"");
        MicroscopeSocketClient.appendWbFolder(on, "per_angle", "D:/bg/profiles/gel/JAI/ppm/20x/per_angle");
        assertEquals(
                List.of("--output", "x", "--wb-folder", "D:/bg/profiles/gel/JAI/ppm/20x/per_angle"),
                splitLikeTheServer(on.toString()));

        StringBuilder off = new StringBuilder("--output \"x\"");
        MicroscopeSocketClient.appendWbFolder(off, "off", "D:/x");
        assertEquals(List.of("--output", "x"), splitLikeTheServer(off.toString()));
    }
}
