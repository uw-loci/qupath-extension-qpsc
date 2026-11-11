package qupath.ext.qpsc.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.acquisition.AcquisitionConfigurationBuilder;

/**
 * A time-lapse can combine its timepoints, the way a Z-stack combines planes. Without a
 * projection the server keeps every timepoint (in t{tt}/ folders) for a time series.
 */
class TimeProjectionFlagTest {

    private static AcquisitionCommandBuilder base() {
        return AcquisitionCommandBuilder.builder()
                .yamlPath("config.yml")
                .projectsFolder("/projects")
                .sampleLabel("sample")
                .scanType("ppm_20x")
                .regionName("bounds");
    }

    @Test
    void aTimeMeanIsSentWithTheTimeLapse() {
        String cmd = base().timeLapse(30, 2.0).timeProjection("mean").buildSocketMessage();
        assertThat(cmd).contains("--timepoints 30").contains("--t-projection mean");
    }

    @Test
    void keepingEveryTimepointSendsNoProjection() {
        assertThat(base().timeLapse(30, 2.0).timeProjection(null).buildSocketMessage())
                .doesNotContain("--t-projection");
        assertThat(base().timeLapse(30, 2.0).timeProjection("none").buildSocketMessage())
                .doesNotContain("--t-projection");
    }

    @Test
    void aSingleTimepointSendsNoProjection() {
        assertThat(base().timeLapse(1, 0).timeProjection("mean").buildSocketMessage())
                .doesNotContain("--t-projection");
    }

    @Test
    void theDialogNamesMapToTheServerNames() {
        assertThat(AcquisitionConfigurationBuilder.timeProjectionCode("Mean")).isEqualTo("mean");
        assertThat(AcquisitionConfigurationBuilder.timeProjectionCode("Max Intensity"))
                .isEqualTo("max");
        assertThat(AcquisitionConfigurationBuilder.timeProjectionCode("Min Intensity"))
                .isEqualTo("min");
        assertThat(AcquisitionConfigurationBuilder.timeProjectionCode("Sum")).isEqualTo("sum");
        assertThat(AcquisitionConfigurationBuilder.timeProjectionCode("Std Deviation"))
                .isEqualTo("std");
        assertThat(AcquisitionConfigurationBuilder.timeProjectionCode("None")).isNull();
        assertThat(AcquisitionConfigurationBuilder.timeProjectionCode(null)).isNull();
    }
}
