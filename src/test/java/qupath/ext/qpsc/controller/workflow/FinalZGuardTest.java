package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.controller.workflow.AcquisitionManager.FinalZVerdict;

/**
 * The runaway guard on each annotation's final Z, against the two runs it has to tell apart:
 * the 2026-05-31 PPM 40x runaway (Z=7 -> 34 -> 104, rightly rejected) and slide 4 of
 * 2026-10-04 (a stale -285.8 seed; -330.2 then -313.0 measured, wrongly rejected before).
 */
class FinalZGuardTest {

    @Test
    void aSmallStepIsCarriedForward() {
        assertEquals(FinalZVerdict.ACCEPT, AcquisitionManager.judgeFinalZ(-285.8, null, -300.0));
        assertEquals(FinalZVerdict.ACCEPT, AcquisitionManager.judgeFinalZ(null, null, -330.2));
    }

    @Test
    void aSingleLargeJumpIsHeld() {
        assertEquals(FinalZVerdict.REJECT, AcquisitionManager.judgeFinalZ(-285.8, null, -330.2));
    }

    @Test
    void twoAgreeingRejectionsOverruleAStaleAnchor() {
        // Slide 4: annotation A measured -330.2, annotation B -313.0, seed -285.8.
        assertEquals(FinalZVerdict.ADOPT_AGREED, AcquisitionManager.judgeFinalZ(-285.8, -330.2, -313.0));
    }

    @Test
    void aRunawayThatKeepsWalkingIsNeverAdopted() {
        // 2026-05-31: 7 -> 34 rejected, then 104 is 70 um from 34, so the two do not agree.
        assertEquals(FinalZVerdict.REJECT, AcquisitionManager.judgeFinalZ(7.0, null, 34.0));
        assertEquals(FinalZVerdict.REJECT, AcquisitionManager.judgeFinalZ(7.0, 34.0, 104.0));
    }

    @Test
    void rejectionsOnOppositeSidesDoNotAgree() {
        assertEquals(FinalZVerdict.REJECT, AcquisitionManager.judgeFinalZ(0.0, 30.0, -30.0));
    }
}
