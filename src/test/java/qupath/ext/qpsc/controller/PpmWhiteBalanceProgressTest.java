package qupath.ext.qpsc.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;

/** The PPM white balance window highlights the angle the server is calibrating. */
class PpmWhiteBalanceProgressTest {

    private static final double[] ANGLES = {7.0, -7.0, 0.0, 90.0};

    @Test
    void progressMessagesAreSplitEvenWhenTheyArriveTogether() {
        List<String[]> messages = MicroscopeSocketClient.progressMessagesIn(
                "STARTED:/out/wbPROGRESS:1:4:7.0PROGRESS:2:4:-7.0SUCCESS:/out/wb|CONVERGED");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).containsExactly("1", "4", "7.0");
        assertThat(messages.get(1)).containsExactly("2", "4", "-7.0");
        assertThat(MicroscopeSocketClient.finalReplyIn("PROGRESS:4:4:90.0SUCCESS:/out/wb|CONVERGED"))
                .isEqualTo("SUCCESS:/out/wb|CONVERGED");
    }

    @Test
    void theReportedAngleSelectsItsRow() {
        assertThat(WhiteBalanceWorkflow.angleRowFor(ANGLES, "7.0")).isEqualTo(0);
        assertThat(WhiteBalanceWorkflow.angleRowFor(ANGLES, "-7.0")).isEqualTo(1);
        assertThat(WhiteBalanceWorkflow.angleRowFor(ANGLES, "0.0")).isEqualTo(2);
        assertThat(WhiteBalanceWorkflow.angleRowFor(ANGLES, "90")).isEqualTo(3);
        assertThat(WhiteBalanceWorkflow.angleRowFor(ANGLES, "45.0")).isEqualTo(-1);
        assertThat(WhiteBalanceWorkflow.angleRowFor(ANGLES, "")).isEqualTo(-1);
    }
}
