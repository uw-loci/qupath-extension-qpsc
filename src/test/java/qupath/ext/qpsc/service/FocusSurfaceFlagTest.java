package qupath.ext.qpsc.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;

/**
 * {@code --focus-surface} opts a run into fitting a plane through its autofocus results
 * and judging each new result against it.
 *
 * <p>It must be absent unless somebody chose it. The flag changes which Z a tile is
 * imaged at, so an update that started emitting it on its own would silently change
 * every acquisition on the rig -- and the preference defaults to {@code off} precisely
 * so that a pull is inert.
 */
class FocusSurfaceFlagTest {

    private final String original = QPPreferenceDialog.getFocusSurfaceMode();
    private final boolean originalAfDisabled = QPPreferenceDialog.getDisableAllAutofocus();

    @AfterEach
    void restore() {
        QPPreferenceDialog.focusSurfaceModeProperty().set(original);
        QPPreferenceDialog.setDisableAllAutofocus(originalAfDisabled);
    }

    private static String command() {
        return AcquisitionCommandBuilder.builder()
                .yamlPath("config.yml")
                .projectsFolder("/projects")
                .sampleLabel("sample")
                .scanType("ppm_20x")
                .regionName("bounds")
                .buildSocketMessage();
    }

    @Test
    void theDefaultIsOffAndEmitsNothing() {
        QPPreferenceDialog.focusSurfaceModeProperty().set("off");
        assertThat(command()).doesNotContain("--focus-surface");
    }

    @Test
    void observeAndEnforceAreBothSentThrough() {
        QPPreferenceDialog.focusSurfaceModeProperty().set("observe");
        assertThat(command()).contains("--focus-surface observe");
        QPPreferenceDialog.focusSurfaceModeProperty().set("enforce");
        assertThat(command()).contains("--focus-surface enforce");
    }

    @Test
    void itIsNotSentWhenThereWillBeNoAutofocusToFit() {
        // With all autofocus off there are no measurements, so a surface could never
        // be fitted. Sending the flag would put a mode in the log that did nothing.
        QPPreferenceDialog.focusSurfaceModeProperty().set("enforce");
        QPPreferenceDialog.setDisableAllAutofocus(true);
        assertThat(command()).doesNotContain("--focus-surface");
    }

    @Test
    void aBlankPreferenceReadsAsOff() {
        // PathPrefs can hand back an empty string for a preference that was written
        // and cleared. "off" is the only safe reading of that.
        QPPreferenceDialog.focusSurfaceModeProperty().set("");
        assertThat(QPPreferenceDialog.getFocusSurfaceMode()).isEqualTo("off");
        assertThat(command()).doesNotContain("--focus-surface");
    }
}
