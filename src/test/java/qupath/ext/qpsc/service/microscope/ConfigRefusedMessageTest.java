package qupath.ext.qpsc.service.microscope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** What the operator is told when the server refuses the config file. */
class ConfigRefusedMessageTest {

    @Test
    void aCompanionFileIsNamedAsSuch() {
        // 2026-10-21: the preference named the autofocus file; the server refused it once a second.
        String m = MicroscopeSocketClient.configRefusedMessage(
                "/cfg/autofocus_LCPolScope_Hamamatsu.yml", "Config missing required sections: microscope, stage");
        assertThat(m)
                .contains("refused autofocus_LCPolScope_Hamamatsu.yml")
                .contains("Config missing required sections: microscope, stage")
                .contains("companion file")
                .contains("Switch Microscope");
    }

    @Test
    void anyOtherFileGetsTheGeneralHint() {
        String m = MicroscopeSocketClient.configRefusedMessage("/cfg/config_PPM.yml", "bad YAML");
        assertThat(m).contains("refused config_PPM.yml: bad YAML").doesNotContain("companion file");
    }
}
