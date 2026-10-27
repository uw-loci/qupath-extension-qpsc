package qupath.ext.qpsc.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code --af-tissue-tiles} is the autofocus allow-list: the tiles the macro image shows holding
 * tissue. The server never starts a focus search outside it, so the three states must stay
 * distinguishable on the wire: unscored (flag absent, server plans by distance as before),
 * scored with tissue (the list), and scored with none ({@code none}, the server searches only the
 * preferred tile and says so).
 *
 * <p>On 2026-10-27 the server ran 28 searches in a sparse LC-PolScope region, most on bare
 * glass, because nothing told it which tiles were glass.
 */
class AfTissueTilesFlagTest {

    private static AcquisitionCommandBuilder base() {
        return AcquisitionCommandBuilder.builder()
                .yamlPath("config.yml")
                .projectsFolder("/projects")
                .sampleLabel("sample")
                .scanType("lcpolscope_20x")
                .regionName("bounds");
    }

    @Test
    void absentWhenTheMacroWasNotScored() {
        assertThat(base().buildSocketMessage()).doesNotContain("--af-tissue-tiles");
    }

    @Test
    void theListIsSentQuotedAndInOrder() {
        // Quoted like --focus-survey-tiles: the server's shlex.split strips the quotes before
        // the comma split, and a bare list would survive too, so the quoting is asserted so a
        // change that dropped it cannot pass quietly.
        String message = base().afTissueTiles(List.of(0, 2, 5, 9)).buildSocketMessage();
        assertThat(message).contains("--af-tissue-tiles \"0,2,5,9\"");
    }

    @Test
    void anEmptyListIsSentAsNone() {
        // Scored and nothing qualified is a different fact from unscored: the server must not
        // fall back to distance-only planning, which is what put it on glass.
        assertThat(base().afTissueTiles(List.of()).buildSocketMessage()).contains("--af-tissue-tiles none");
        assertThat(base().afTissueTiles(null).buildSocketMessage()).contains("--af-tissue-tiles none");
    }

    @Test
    void thePreferredTileStillTravelsBesideIt() {
        String message =
                base().preferredAfTile(5).afTissueTiles(List.of(2, 5, 9)).buildSocketMessage();
        assertThat(message).contains("--preferred-af-tile 5");
        assertThat(message).contains("--af-tissue-tiles \"2,5,9\"");
    }

    @Test
    void theQuotedListSurvivesShlexStyleParsing() {
        String message = base().afTissueTiles(List.of(4, 97, 230)).buildSocketMessage();
        int at = message.indexOf("--af-tissue-tiles");
        String value = message.substring(at + "--af-tissue-tiles".length()).trim();
        if (value.startsWith("\"")) {
            value = value.substring(1, value.indexOf('"', 1));
        }
        assertThat(value.split(",")).containsExactly("4", "97", "230");
    }
}
