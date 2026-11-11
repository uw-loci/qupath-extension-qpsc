package qupath.ext.qpsc.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.service.mda.LiveDimensionDecomposer;

/**
 * Where a time-lapse's timepoints sit: across the region (every timepoint visits every
 * position, e.g. a well plate's rounds), per position, or per Z plane. The flag, the live
 * position/Z/timepoint read-out and the time left all follow the order.
 */
class TimeLapseOrderTest {

    private static AcquisitionCommandBuilder base() {
        return AcquisitionCommandBuilder.builder()
                .yamlPath("config.yml")
                .projectsFolder("/projects")
                .sampleLabel("sample")
                .scanType("ppm_20x")
                .regionName("bounds");
    }

    @Test
    void codesRoundTrip() {
        for (TimeLapseOrder o : TimeLapseOrder.values()) {
            assertThat(TimeLapseOrder.fromCode(o.code())).isEqualTo(o);
        }
        assertThat(TimeLapseOrder.fromCode("PLANE")).isEqualTo(TimeLapseOrder.PLANE);
        assertThat(TimeLapseOrder.fromCode(null)).isEqualTo(TimeLapseOrder.REGION);
        assertThat(TimeLapseOrder.fromCode("sideways")).isEqualTo(TimeLapseOrder.REGION);
    }

    @Test
    void theFlagIsSentOnlyForATimeLapseOutOfTheDefaultOrder() {
        assertThat(base().timeLapse(5, 1.0).timeOrder("plane").buildSocketMessage())
                .contains("--t-order plane");
        assertThat(base().timeLapse(5, 1.0).timeOrder("position").buildSocketMessage())
                .contains("--t-order position");
        assertThat(base().timeLapse(5, 1.0).timeOrder("region").buildSocketMessage())
                .doesNotContain("--t-order");
        assertThat(base().timeLapse(1, 0).timeOrder("plane").buildSocketMessage())
                .doesNotContain("--t-order");
    }

    /** 2 positions, 2 angles, 3 planes (angle per Z), 2 timepoints. */
    private static AcquisitionPlan plan(TimeLapseOrder order) {
        return AcquisitionPlan.compute(2, 0, 3, 2, 2, "angle", true, List.of(), List.of(7.0, -7.0), order);
    }

    private static String at(AcquisitionPlan plan, long k) {
        var d = LiveDimensionDecomposer.decompose(k, plan);
        assertThat(d.drifted()).isFalse();
        return "t" + d.tIdx() + " p" + d.posIdx() + " z" + d.zIdx() + " a" + d.angleIdx();
    }

    @Test
    void regionOrderVisitsEveryPositionEachTimepoint() {
        AcquisitionPlan p = plan(TimeLapseOrder.REGION);
        assertThat(at(p, 0)).isEqualTo("t0 p0 z0 a0");
        assertThat(at(p, 6)).isEqualTo("t0 p1 z0 a0");
        assertThat(at(p, 12)).isEqualTo("t1 p0 z0 a0");
    }

    @Test
    void positionOrderRunsAPositionsTimepointsFirst() {
        AcquisitionPlan p = plan(TimeLapseOrder.POSITION);
        assertThat(at(p, 6)).isEqualTo("t1 p0 z0 a0");
        assertThat(at(p, 12)).isEqualTo("t0 p1 z0 a0");
        assertThat(at(p, 13)).isEqualTo("t0 p1 z0 a1");
    }

    @Test
    void planeOrderRunsAPlanesTimepointsFirst() {
        AcquisitionPlan p = plan(TimeLapseOrder.PLANE);
        assertThat(at(p, 1)).isEqualTo("t0 p0 z0 a1");
        assertThat(at(p, 2)).isEqualTo("t1 p0 z0 a0");
        assertThat(at(p, 4)).isEqualTo("t0 p0 z1 a0");
        assertThat(at(p, 12)).isEqualTo("t0 p1 z0 a0");
        assertThat(at(p, 23)).isEqualTo("t1 p1 z2 a1");
    }

    @Test
    void theSeriesFollowTheOrder() {
        // 2 positions x 6 files (2 angles x 3 planes) per timepoint.
        assertThat(TimeLapseEstimate.series(TimeLapseOrder.REGION, 12, 6, 3))
                .isEqualTo(new TimeLapseEstimate.Series(1, 12));
        assertThat(TimeLapseEstimate.series(TimeLapseOrder.POSITION, 12, 6, 3))
                .isEqualTo(new TimeLapseEstimate.Series(2, 6));
        assertThat(TimeLapseEstimate.series(TimeLapseOrder.PLANE, 12, 6, 3))
                .isEqualTo(new TimeLapseEstimate.Series(6, 2));
        // Without a Z-stack, per plane is per position.
        assertThat(TimeLapseEstimate.series(TimeLapseOrder.PLANE, 12, 6, 1))
                .isEqualTo(new TimeLapseEstimate.Series(2, 6));
    }

    @Test
    void theTimepointIsCountedWithinItsSeries() {
        var perPlane = new TimeLapseEstimate.Series(6, 2);
        assertThat(TimeLapseEstimate.timepointOf(0, perPlane, 2)).isZero();
        assertThat(TimeLapseEstimate.timepointOf(2, perPlane, 2)).isEqualTo(1);
        assertThat(TimeLapseEstimate.timepointOf(4, perPlane, 2)).isZero();
        assertThat(TimeLapseEstimate.timepointOf(24, perPlane, 2)).isEqualTo(1);
    }

    @Test
    void timeLeftWaitsOncePerSeriesTimepoint() {
        // 1 s per file, 10 s interval, 2 timepoints, nothing done yet.
        // Region: one series of 12 files per timepoint: 12 s, then 12 s (interval shorter) = 24 s.
        assertThat(TimeLapseEstimate.remainingMs(new TimeLapseEstimate.Series(1, 12), 2, 10.0, 0, 1000.0, 0))
                .isEqualTo(24_000);
        // Per plane: 6 series of 2 files: each 10 s (wait) + 2 s = 12 s, so 72 s.
        assertThat(TimeLapseEstimate.remainingMs(new TimeLapseEstimate.Series(6, 2), 2, 10.0, 0, 1000.0, 0))
                .isEqualTo(72_000);
    }
}
