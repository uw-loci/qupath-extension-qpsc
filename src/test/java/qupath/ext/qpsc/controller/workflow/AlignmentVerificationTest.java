package qupath.ext.qpsc.controller.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.controller.workflow.AlignmentVerification.Status;
import qupath.ext.qpsc.controller.workflow.AlignmentVerification.Verdict;

/**
 * What a SIFT measurement is allowed to conclude.
 *
 * <p>The three-way outcome is the substance here. A failure to match and a measured
 * disagreement are different facts with different recoveries, and collapsing them would
 * either miss the case this exists for -- a slide displaced far enough to land on its label,
 * where there is nothing to match -- or stop good runs on a defocused field.
 */
class AlignmentVerificationTest {

    @AfterEach
    void tearDown() {
        AlignmentVerificationGate.clear();
    }

    /** A server match: offset X, offset Y, inliers, confidence. */
    private static double[] match(double dx, double dy, int inliers, double confidence) {
        return new double[] {dx, dy, inliers, confidence};
    }

    // ----------------------------------------------------------------------
    // judge
    // ----------------------------------------------------------------------

    @Test
    void aCloseConfidentMatchConfirmsTheAlignment() {
        // The refined residuals actually measured on 2026-10-02: 20-40 um on every slide.
        assertThat(AlignmentVerification.judge(match(20.8, 8.0, 269, 0.996), AlignmentVerification.MAX_OFFSET_UM))
                .isEqualTo(Status.CONFIRMED);
        assertThat(AlignmentVerification.judge(match(36.3, -3.9, 1019, 0.999), AlignmentVerification.MAX_OFFSET_UM))
                .isEqualTo(Status.CONFIRMED);
    }

    @Test
    void aWeakButConfidentMatchStillCounts() {
        // 91 inliers at 0.958 was the weakest real match in that run, and it was correct.
        assertThat(AlignmentVerification.judge(match(-49.5, -60.6, 91, 0.958), AlignmentVerification.MAX_OFFSET_UM))
                .isEqualTo(Status.CONFIRMED);
    }

    @Test
    void aDistantConfidentMatchIsMisalignment() {
        // Matched, so the number is believed: the slide is 3 mm from where it should be.
        assertThat(AlignmentVerification.judge(match(3000, 0, 800, 0.999), AlignmentVerification.MAX_OFFSET_UM))
                .isEqualTo(Status.MISALIGNED);
    }

    @Test
    void offsetIsMeasuredAsDistanceNotPerAxis() {
        // 400 um on each axis is 566 um of real disagreement; a per-axis test passes both.
        assertThat(AlignmentVerification.judge(match(400, 400, 800, 0.999), 500))
                .isEqualTo(Status.MISALIGNED);
        assertThat(AlignmentVerification.judge(match(400, 0, 800, 0.999), 500)).isEqualTo(Status.CONFIRMED);
    }

    @Test
    void noMatchAtAllIsInconclusiveNotMisaligned() {
        // This is the slide-4 case: nothing recognisable under the objective. It must not be
        // reported as a measurement, because no distance was measured.
        assertThat(AlignmentVerification.judge(null, AlignmentVerification.MAX_OFFSET_UM))
                .isEqualTo(Status.INCONCLUSIVE);
    }

    @Test
    void aLowConfidenceMatchIsInconclusiveEitherWay() {
        // Believing it would stop a run on a number we do not trust; dismissing it would
        // clear an alignment on the same number. Neither is honest.
        assertThat(AlignmentVerification.judge(match(5, 5, 12, 0.4), AlignmentVerification.MAX_OFFSET_UM))
                .isEqualTo(Status.INCONCLUSIVE);
        assertThat(AlignmentVerification.judge(match(9000, 0, 12, 0.4), AlignmentVerification.MAX_OFFSET_UM))
                .isEqualTo(Status.INCONCLUSIVE);
    }

    @Test
    void malformedOrNonFiniteMeasurementsAreInconclusive() {
        assertThat(AlignmentVerification.judge(new double[] {1, 2}, 500)).isEqualTo(Status.INCONCLUSIVE);
        assertThat(AlignmentVerification.judge(match(Double.NaN, 0, 800, 0.999), 500))
                .isEqualTo(Status.INCONCLUSIVE);
    }

    // ----------------------------------------------------------------------
    // what stops a run
    // ----------------------------------------------------------------------

    @Test
    void bothFailureKindsStopTheRunAndSuccessDoesNot() {
        assertThat(new Verdict(Status.CONFIRMED, 20, 800, 0.99, 1, "").shouldStop())
                .isFalse();
        assertThat(new Verdict(Status.SKIPPED, Double.NaN, 0, 0, 0, "").shouldStop())
                .isFalse();
        assertThat(new Verdict(Status.MISALIGNED, 3000, 800, 0.99, 1, "").shouldStop())
                .isTrue();
        // Unconfirmed stops too. Not stopping here would miss the only case that produced
        // thirty hours of slide label, since a slide that far off cannot be matched.
        assertThat(new Verdict(Status.INCONCLUSIVE, Double.NaN, 0, 0, 3, "").shouldStop())
                .isTrue();
    }

    // ----------------------------------------------------------------------
    // the gate
    // ----------------------------------------------------------------------

    @Test
    void theGateStartsOpenAndRemembersTheFirstFailure() {
        assertThat(AlignmentVerificationGate.isBlocked()).isFalse();

        Verdict first = new Verdict(Status.MISALIGNED, 3000, 800, 0.99, 1, "first failure");
        AlignmentVerificationGate.record("PDAC_2", first);
        assertThat(AlignmentVerificationGate.isBlocked()).isTrue();
        assertThat(AlignmentVerificationGate.reason()).contains("PDAC_2").contains("first failure");
        assertThat(AlignmentVerificationGate.lastVerdict()).isSameAs(first);

        // A later slide must not overwrite the first explanation: the operator needs to know
        // which slide stopped the batch, not which one failed last.
        AlignmentVerificationGate.record("PDAC_3", new Verdict(Status.INCONCLUSIVE, Double.NaN, 0, 0, 3, "second"));
        assertThat(AlignmentVerificationGate.reason()).contains("PDAC_2");
        assertThat(AlignmentVerificationGate.lastVerdict()).isSameAs(first);
    }

    @Test
    void onlyAnExplicitClearReopensTheGate() {
        AlignmentVerificationGate.record("PDAC_4", new Verdict(Status.INCONCLUSIVE, Double.NaN, 0, 0, 3, "x"));
        assertThat(AlignmentVerificationGate.isBlocked()).isTrue();
        AlignmentVerificationGate.clear();
        assertThat(AlignmentVerificationGate.isBlocked()).isFalse();
        assertThat(AlignmentVerificationGate.lastVerdict()).isNull();
    }

    @Test
    void aNullVerdictStillBlocksWithSomethingReadable() {
        AlignmentVerificationGate.record("PDAC_5", null);
        assertThat(AlignmentVerificationGate.isBlocked()).isTrue();
        assertThat(AlignmentVerificationGate.reason()).contains("PDAC_5");
    }

    // ----------------------------------------------------------------------
    // candidate points
    // ----------------------------------------------------------------------

    @Test
    void severalPointsAreTriedBeforeConcludingNothingMatches() {
        // One point with no features is indistinguishable from a displaced slide, so a
        // single attempt would turn an empty corner into a halted batch.
        var roi = qupath.lib.roi.ROIs.createRectangleROI(1000, 2000, 600, 400, null);
        var points = AlignmentVerification.candidatePoints(roi);
        assertThat(points).hasSizeGreaterThanOrEqualTo(3);
        // First the centroid, where the operator drew the region.
        assertThat(points.get(0)[0]).isEqualTo(roi.getCentroidX());
        assertThat(points.get(0)[1]).isEqualTo(roi.getCentroidY());
        // The rest are distinct and inside the region.
        for (double[] p : points) {
            assertThat(p[0]).isBetween(1000.0, 1600.0);
            assertThat(p[1]).isBetween(2000.0, 2400.0);
        }
        assertThat(points.get(1)).isNotEqualTo(points.get(2));
    }

    // ----------------------------------------------------------------------
    // wiring
    // ----------------------------------------------------------------------

    @Test
    void theCheckRunsOnlyForUnattendedAcquisitionAndHonoursTheMode() throws Exception {
        String src = Files.readString(Path.of("src/main/java/qupath/ext/qpsc/controller/ExistingImageWorkflowV2.java"));
        // An interactive single-slide run with a saved alignment must not suddenly spend a
        // minute matching; the risk being covered is the unattended batch.
        assertThat(src).contains("if (mode == Mode.ACQUIRE_ONLY) {");
        assertThat(src).contains("verifyAlignmentBeforeUnattendedAcquire(state)");
        assertThat(src).contains("QPPreferenceDialog.getAlignmentCheckMode()");
        // warn must acquire anyway, so the numbers can be learned before it stops a run.
        assertThat(src).contains("\"warn\".equalsIgnoreCase(checkMode)");
    }

    @Test
    void aFailedCheckHaltsTheWholePassNotJustTheSlide() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/qupath/ext/qpsc/controller/MultiSlideExistingImageWorkflow.java"));
        int driver = src.indexOf("private static void driveSequential(");
        assertThat(driver).isGreaterThan(0);
        String body = src.substring(driver);
        int check = body.indexOf("AlignmentVerificationGate.reason()");
        assertThat(check).isGreaterThan(0);
        String after = body.substring(check, check + 500);
        assertThat(after).contains("onDone.run();").contains("return;");
        // Both passes clear it when the operator deliberately starts again.
        assertThat(src.split("AlignmentVerificationGate\\.clear\\(\\);", -1).length - 1)
                .isEqualTo(2);
    }
}
