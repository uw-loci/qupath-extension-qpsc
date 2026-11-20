package qupath.ext.qpsc.controller.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A focus survey measures points for the focus surface, so it is asked for only while the
 * surface is on. On 2026-11-20 an LC-PolScope run with the surface off still picked nine survey
 * tiles and sent {@code --focus-survey 9}, which the server then skipped with a warning.
 */
class FocusSurveyRequestTest {

    @Test
    void surfaceOffAsksForNoSurvey() {
        assertThat(AcquisitionManager.focusSurveyPointsToRequest(9, "off", false))
                .isZero();
        assertThat(AcquisitionManager.focusSurveyPointsToRequest(9, "", false)).isZero();
        assertThat(AcquisitionManager.focusSurveyPointsToRequest(9, null, false))
                .isZero();
    }

    @Test
    void surfaceOnAsksForThePreference() {
        assertThat(AcquisitionManager.focusSurveyPointsToRequest(9, "observe", false))
                .isEqualTo(9);
        assertThat(AcquisitionManager.focusSurveyPointsToRequest(9, "enforce", false))
                .isEqualTo(9);
    }

    @Test
    void autofocusOffOrPreferenceZeroAsksForNoSurvey() {
        assertThat(AcquisitionManager.focusSurveyPointsToRequest(9, "enforce", true))
                .isZero();
        assertThat(AcquisitionManager.focusSurveyPointsToRequest(0, "enforce", false))
                .isZero();
    }
}
