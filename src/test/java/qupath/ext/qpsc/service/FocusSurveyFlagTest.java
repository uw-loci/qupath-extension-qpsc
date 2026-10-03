package qupath.ext.qpsc.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code --focus-survey} asks the server to measure the focus surface before the tile
 * loop. It costs a wide autofocus per point, so it must never appear uninvited.
 *
 * <p>The tile list is separate and optional: the client picks tiles from the macro
 * image for tissue and spread, but the server can spread its own points when it gets
 * none, so a scoring failure degrades to a worse survey rather than to no survey.
 */
class FocusSurveyFlagTest {

    private static AcquisitionCommandBuilder base() {
        return AcquisitionCommandBuilder.builder()
                .yamlPath("config.yml")
                .projectsFolder("/projects")
                .sampleLabel("sample")
                .scanType("ppm_20x")
                .regionName("bounds");
    }

    @Test
    void absentByDefault() {
        assertThat(base().buildSocketMessage()).doesNotContain("--focus-survey");
    }

    @Test
    void zeroOrNegativePointsEmitNothing() {
        // 0 is the documented "no survey" value, and a negative preference is
        // nonsense that should mean the same rather than reaching the server.
        assertThat(base().focusSurvey(0).buildSocketMessage()).doesNotContain("--focus-survey");
        assertThat(base().focusSurvey(-3).buildSocketMessage()).doesNotContain("--focus-survey");
    }

    @Test
    void thePointCountIsSent() {
        assertThat(base().focusSurvey(12).buildSocketMessage()).contains("--focus-survey 12");
    }

    @Test
    void tilesAreSentAsAQuotedCommaSeparatedList() {
        // The builder quotes any argument containing a comma, and the server parses the
        // message with shlex.split, which strips the quotes before the comma split. The
        // quoting is asserted explicitly because an un-quoted list would survive shlex
        // too -- so a future change that dropped the quotes would pass a looser test
        // while breaking any argument that genuinely needs them.
        String message =
                base().focusSurvey(3).focusSurveyTiles(List.of(4, 97, 230)).buildSocketMessage();
        assertThat(message).contains("--focus-survey-tiles \"4,97,230\"");
    }

    @Test
    void theQuotedListSurvivesShlexStyleParsing() {
        // Mirror of what workflow.py does: shlex.split, then split the value on commas.
        String message =
                base().focusSurvey(3).focusSurveyTiles(List.of(4, 97, 230)).buildSocketMessage();
        int at = message.indexOf("--focus-survey-tiles");
        String value = message.substring(at + "--focus-survey-tiles".length()).trim();
        if (value.startsWith("\"")) {
            value = value.substring(1, value.indexOf('"', 1));
        }
        assertThat(value.split(",")).containsExactly("4", "97", "230");
    }

    @Test
    void anEmptyTileListIsOmittedSoTheServerPicksItsOwn() {
        // Scoring the macro image can fail -- no tile config, no pixel size, an
        // unreadable region. The survey should still run, with the server spreading
        // its own points, rather than being cancelled by a bookkeeping failure.
        String message = base().focusSurvey(9).focusSurveyTiles(List.of()).buildSocketMessage();
        assertThat(message).contains("--focus-survey 9");
        assertThat(message).doesNotContain("--focus-survey-tiles");
    }

    @Test
    void tilesWithoutAPointCountAreNotSentAlone() {
        // The server keys the whole feature off --focus-survey. A tile list without it
        // would be silently ignored, so it must not be emitted.
        assertThat(base().focusSurveyTiles(List.of(1, 2, 3)).buildSocketMessage())
                .doesNotContain("--focus-survey-tiles");
    }
}
