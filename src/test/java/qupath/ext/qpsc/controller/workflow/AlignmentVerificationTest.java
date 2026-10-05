package qupath.ext.qpsc.controller.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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

    /** PPM at 20x: 357.5 x 267.4 um, so one field diagonal. Read from config at runtime. */
    private static final double PPM_20X_TOLERANCE = AlignmentVerification.toleranceUm(357.4848, 267.4208);

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
        assertThat(AlignmentVerification.judge(match(20.8, 8.0, 269, 0.996), PPM_20X_TOLERANCE))
                .isEqualTo(Status.CONFIRMED);
        assertThat(AlignmentVerification.judge(match(36.3, -3.9, 1019, 0.999), PPM_20X_TOLERANCE))
                .isEqualTo(Status.CONFIRMED);
    }

    @Test
    void aWeakButConfidentMatchStillCounts() {
        // 91 inliers at 0.958 was the weakest real match in that run, and it was correct.
        assertThat(AlignmentVerification.judge(match(-49.5, -60.6, 91, 0.958), PPM_20X_TOLERANCE))
                .isEqualTo(Status.CONFIRMED);
    }

    @Test
    void aDistantConfidentMatchIsMisalignment() {
        // Matched, so the number is believed: the slide is 3 mm from where it should be.
        assertThat(AlignmentVerification.judge(match(3000, 0, 800, 0.999), PPM_20X_TOLERANCE))
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
        assertThat(AlignmentVerification.judge(null, PPM_20X_TOLERANCE)).isEqualTo(Status.INCONCLUSIVE);
    }

    @Test
    void aLowConfidenceMatchIsInconclusiveEitherWay() {
        // Believing it would stop a run on a number we do not trust; dismissing it would
        // clear an alignment on the same number. Neither is honest.
        assertThat(AlignmentVerification.judge(match(5, 5, 12, 0.4), PPM_20X_TOLERANCE))
                .isEqualTo(Status.INCONCLUSIVE);
        assertThat(AlignmentVerification.judge(match(9000, 0, 12, 0.4), PPM_20X_TOLERANCE))
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
        assertThat(Verdict.of(Status.CONFIRMED, 20, 800, 0.99, 1, "").shouldStop())
                .isFalse();
        assertThat(Verdict.of(Status.SKIPPED, Double.NaN, 0, 0, 0, "").shouldStop())
                .isFalse();
        assertThat(Verdict.of(Status.MISALIGNED, 3000, 800, 0.99, 1, "").shouldStop())
                .isTrue();
        // Unconfirmed stops too. Not stopping here would miss the only case that produced
        // thirty hours of slide label, since a slide that far off cannot be matched.
        assertThat(Verdict.of(Status.INCONCLUSIVE, Double.NaN, 0, 0, 3, "").shouldStop())
                .isTrue();
    }

    // ----------------------------------------------------------------------
    // the gate
    // ----------------------------------------------------------------------

    @Test
    void theGateStartsOpenAndRemembersTheFirstFailure() {
        assertThat(AlignmentVerificationGate.isBlocked()).isFalse();

        Verdict first = Verdict.of(Status.MISALIGNED, 3000, 800, 0.99, 1, "first failure");
        AlignmentVerificationGate.record("PDAC_2", first);
        assertThat(AlignmentVerificationGate.isBlocked()).isTrue();
        assertThat(AlignmentVerificationGate.reason()).contains("PDAC_2").contains("first failure");
        assertThat(AlignmentVerificationGate.lastVerdict()).isSameAs(first);

        // A later slide must not overwrite the first explanation: the operator needs to know
        // which slide stopped the batch, not which one failed last.
        AlignmentVerificationGate.record("PDAC_3", Verdict.of(Status.INCONCLUSIVE, Double.NaN, 0, 0, 3, "second"));
        assertThat(AlignmentVerificationGate.reason()).contains("PDAC_2");
        assertThat(AlignmentVerificationGate.lastVerdict()).isSameAs(first);
    }

    @Test
    void onlyAnExplicitClearReopensTheGate() {
        AlignmentVerificationGate.record("PDAC_4", Verdict.of(Status.INCONCLUSIVE, Double.NaN, 0, 0, 3, "x"));
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

    // ----------------------------------------------------------------------
    // the Z the check measures becomes the acquisition's baseline
    // ----------------------------------------------------------------------

    @Test
    void aVerdictCarriesTheFocusItMeasured() {
        // Needed on EVERY outcome, not just failures: a confirmed slide that was refocused
        // must still hand the fresh Z to the tile loop.
        Verdict confirmed = new Verdict(Status.CONFIRMED, 25, 900, 0.998, 1, "ok", -405.6);
        assertThat(confirmed.focusedZUm()).isEqualTo(-405.6);
        assertThat(Verdict.of(Status.CONFIRMED, 25, 900, 0.998, 1, "ok").focusedZUm())
                .as("no refocus happened, so there is nothing to adopt")
                .isNull();
    }

    @Test
    void theAcquisitionAdoptsTheMeasuredZRatherThanTheStoredOne() throws Exception {
        String src = Files.readString(Path.of("src/main/java/qupath/ext/qpsc/controller/ExistingImageWorkflowV2.java"));
        // state.seedZ seeds the first tile's autofocus. After refocusing at a point inside
        // this region, the setup-pass value is the stale one.
        int verify = src.indexOf("AlignmentVerification.verify(");
        assertThat(verify).isGreaterThan(0);
        String after = src.substring(verify, verify + 1600);
        assertThat(after).contains("verdict.focusedZUm()");
        assertThat(after).contains("state.seedZ = verdict.focusedZUm();");
        // Adopted before the stop decision, so a confirmed slide benefits too.
        assertThat(after.indexOf("state.seedZ = verdict.focusedZUm();"))
                .isLessThan(after.indexOf("verdict.shouldStop()"));
    }

    @Test
    void focusEscalatesOnlyWhereTheApproachIsLicensed() throws Exception {
        String src =
                Files.readString(Path.of("src/main/java/qupath/ext/qpsc/controller/workflow/SlotJumpAutofocus.java"));
        int m = src.indexOf("static Double focusForAlignmentCheck(");
        assertThat(m).isGreaterThan(0);
        String body = src.substring(m, m + 3200);
        // Narrow first.
        assertThat(body.indexOf("narrowRangeUm)")).isLessThan(body.indexOf("resolveApproachPlan("));
        // And the approach only when measured for this combination: it drives the objective
        // the whole way toward the sample, which is why it is licensed rather than assumed.
        assertThat(body).contains("if (!plan.enabled())");
        assertThat(body.indexOf("if (!plan.enabled())")).isLessThan(body.indexOf("plan.safeZUm()"));
    }

    // ----------------------------------------------------------------------
    // nothing about the tolerance or the focus range is invented in Java
    // ----------------------------------------------------------------------

    @Test
    void theToleranceIsOneCameraFieldDiagonal() {
        // Physical criterion, not a tuned number: beyond one diagonal the field the
        // acquisition would image does not overlap the one the alignment intended.
        assertThat(AlignmentVerification.toleranceUm(357.4848, 267.4208)).isCloseTo(446.4, within(0.5));
        // And it scales with the objective, which a constant could not.
        assertThat(AlignmentVerification.toleranceUm(714.97, 534.84))
                .as("10x sees twice the field, so tolerates twice the offset")
                .isCloseTo(892.8, within(1.0));
    }

    @Test
    void noSearchRangeOrToleranceConstantIsHardcoded() throws Exception {
        // The autofocus YAML already declares sweep_range_um, and the config already declares
        // the FOV. A number here would be a second, invisible answer to the same question.
        String src = Files.readString(
                Path.of("src/main/java/qupath/ext/qpsc/controller/workflow/AlignmentVerification.java"));
        assertThat(src).doesNotContain("REFOCUS_RANGE_UM").doesNotContain("MAX_OFFSET_UM");
        assertThat(src).contains("Math.hypot(fovWidthUm, fovHeightUm)");

        String slotJump =
                Files.readString(Path.of("src/main/java/qupath/ext/qpsc/controller/workflow/SlotJumpAutofocus.java"));
        int m = slotJump.indexOf("static Double focusForAlignmentCheck(");
        assertThat(m).isGreaterThan(0);
        String body = slotJump.substring(m, m + 3200);
        // No override, so the server reads sweep_range_um from autofocus_<scope>.yml.
        assertThat(body).contains("streamingFocus(configPath, null, modality, Double.NaN)");
    }
}
