package qupath.ext.qpsc.controller.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The stage frame guard's call sites, which are what make it a guard rather than a class.
 *
 * <p>Asserted against the source because reaching these branches needs a JavaFX stage, a live
 * socket, a project and a multi-hour acquisition, while the invariants worth protecting are
 * structural: the watchdog is armed for every region and released afterwards, and a moved origin
 * halts the whole pass rather than one slide.
 */
class StageFrameGuardWiringTest {

    private static final Path ACQUISITION_MANAGER =
            Path.of("src/main/java/qupath/ext/qpsc/controller/workflow/AcquisitionManager.java");
    private static final Path MULTI_SLIDE =
            Path.of("src/main/java/qupath/ext/qpsc/controller/MultiSlideExistingImageWorkflow.java");

    @Test
    void everyRegionArmsTheWatchdogAndReleasesItAfterwards() throws Exception {
        String src = Files.readString(ACQUISITION_MANAGER);

        assertThat(src).contains("armStageFrameWatchdog(annotation);");
        // Released in a finally: a region that throws must not leave the watchdog holding
        // position polling open, nor leave the next region judged against this one's bounds.
        int arm = src.indexOf("armStageFrameWatchdog(annotation);");
        int tryAfterArm = src.indexOf("try {", arm);
        int disarm = src.indexOf("StageFrameWatchdog.getInstance().disarm();", arm);
        int finallyAfterArm = src.indexOf("} finally {", arm);
        assertThat(tryAfterArm).isGreaterThan(arm);
        assertThat(finallyAfterArm).isLessThan(disarm);
        assertThat(disarm).isGreaterThan(tryAfterArm);
    }

    @Test
    void theEnvelopeIsTheRegionNotTheSlide() throws Exception {
        // A slide box can straddle the stage origin -- slot 2 of the PPM quad holder does --
        // so a re-zeroed controller reporting 0,0 would look like it was legally on the slide.
        String src = Files.readString(ACQUISITION_MANAGER);
        int arm = src.indexOf("private void armStageFrameWatchdog(");
        assertThat(arm).isGreaterThan(0);
        String body = src.substring(arm, arm + 3000);
        assertThat(body).contains("annotation.getROI()");
        assertThat(body).contains("transformQuPathFullResToStage");
        assertThat(body).doesNotContain("resolveSlotCenterStageXY");
    }

    @Test
    void anUncomputableEnvelopeLeavesItUnarmed() throws Exception {
        String src = Files.readString(ACQUISITION_MANAGER);
        int arm = src.indexOf("private void armStageFrameWatchdog(");
        String body = src.substring(arm, arm + 3000);
        // Guessing an envelope would either cancel good runs or watch nothing.
        assertThat(body).contains("state.transform == null");
        assertThat(body).contains("annotation.getROI() == null");
    }

    @Test
    void aSuspectFrameHaltsThePassRatherThanTheSlide() throws Exception {
        String src = Files.readString(MULTI_SLIDE);
        // Inside driveSequential, which both the setup and the acquire pass run through, so
        // neither pass can start another slide on coordinates measured against the old origin.
        int driver = src.indexOf("private static void driveSequential(");
        assertThat(driver).isGreaterThan(0);
        String body = src.substring(driver);
        int suspectCheck = body.indexOf("StageFrameWatchdog.getInstance().suspectReason()");
        assertThat(suspectCheck).isGreaterThan(0);
        // It must bail out of the pass, not merely log on the way into the next slide.
        String afterCheck = body.substring(suspectCheck, suspectCheck + 600);
        assertThat(afterCheck).contains("onDone.run();");
        assertThat(afterCheck).contains("return;");
    }

    @Test
    void theOperatorIsTold() throws Exception {
        String src = Files.readString(MULTI_SLIDE);
        assertThat(src).contains("warnFrameSuspectOnce(");
        // Unattended run, hours of work abandoned, and the recovery is physical -- a log line
        // alone would be found the next morning at best.
        assertThat(src).contains("Stage position is not trustworthy");
        assertThat(src).contains("fiducial");
    }

    @Test
    void deliberatelyStartingAgainClearsAPreviousSuspicion() throws Exception {
        String src = Files.readString(MULTI_SLIDE);
        // Both passes, so neither is permanently blocked by a stale flag once the operator has
        // seen the alert and chosen to go again.
        assertThat(src.split("StageFrameWatchdog\\.getInstance\\(\\)\\.clearSuspect\\(\\);", -1).length - 1)
                .isEqualTo(2);
    }
}
