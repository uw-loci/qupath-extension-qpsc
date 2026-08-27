package qupath.ext.qpsc.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.preferences.MultiSlideAcquisitionMode;

/**
 * Session semantics of {@link AutoAdvanceController} -- the arm / per-slide-override /
 * disarm state machine that decides whether a multi-slide setup dialog auto-confirms.
 *
 * <p>Covers only the parts that need no JavaFX toolkit. The countdown itself (relabel,
 * fire, disabled-button bail-out) drives real controls and is verified by WSL smoke test,
 * not here.
 *
 * <p>The state is process-global by design (see the class Javadoc for why), which is
 * exactly the property that makes leaks dangerous: an armed session outliving its batch
 * would auto-confirm dialogs in an unrelated single-image workflow. Each test disarms in
 * {@link #reset()} so one failure cannot cascade into the rest of the suite.
 */
class AutoAdvanceSessionTest {

    @BeforeEach
    @AfterEach
    void reset() {
        AutoAdvanceController.disarmSession();
    }

    @Test
    void unarmedSessionIsManual() {
        assertFalse(AutoAdvanceController.isArmed());
        assertEquals(MultiSlideAcquisitionMode.MANUAL, AutoAdvanceController.mode());
        assertFalse(AutoAdvanceController.isOverriddenThisSlide());
    }

    @Test
    void armingManualDoesNotArm() {
        AutoAdvanceController.armSession(MultiSlideAcquisitionMode.MANUAL, 10);
        assertFalse(AutoAdvanceController.isArmed(), "MANUAL must never count as armed");
    }

    @Test
    void armingNullModeDegradesToManual() {
        AutoAdvanceController.armSession(null, 10);
        assertEquals(MultiSlideAcquisitionMode.MANUAL, AutoAdvanceController.mode());
        assertFalse(AutoAdvanceController.isArmed());
    }

    @Test
    void armingAutomaticModesArms() {
        AutoAdvanceController.armSession(MultiSlideAcquisitionMode.AUTOMATIC_WITH_OVERRIDE, 10);
        assertTrue(AutoAdvanceController.isArmed());
        assertEquals(MultiSlideAcquisitionMode.AUTOMATIC_WITH_OVERRIDE, AutoAdvanceController.mode());

        AutoAdvanceController.armSession(MultiSlideAcquisitionMode.FULLY_AUTOMATIC, 10);
        assertTrue(AutoAdvanceController.isArmed());
        assertEquals(MultiSlideAcquisitionMode.FULLY_AUTOMATIC, AutoAdvanceController.mode());
    }

    @Test
    void overrideIsScopedToTheCurrentSlide() {
        AutoAdvanceController.armSession(MultiSlideAcquisitionMode.AUTOMATIC_WITH_OVERRIDE, 10);
        AutoAdvanceController.beginSlide();
        assertFalse(AutoAdvanceController.isOverriddenThisSlide());

        AutoAdvanceController.overrideCurrentSlide("operator mouse press");
        assertTrue(AutoAdvanceController.isOverriddenThisSlide());
        // Still armed -- the batch keeps running, only this slide waits for a human.
        assertTrue(AutoAdvanceController.isArmed());

        AutoAdvanceController.beginSlide();
        assertFalse(AutoAdvanceController.isOverriddenThisSlide(), "the next slide resumes automation");
    }

    @Test
    void disarmClearsBothModeAndOverride() {
        AutoAdvanceController.armSession(MultiSlideAcquisitionMode.FULLY_AUTOMATIC, 10);
        AutoAdvanceController.overrideCurrentSlide("test");
        AutoAdvanceController.disarmSession();

        assertFalse(AutoAdvanceController.isArmed(), "a disarmed session must not auto-confirm anything");
        assertEquals(MultiSlideAcquisitionMode.MANUAL, AutoAdvanceController.mode());
        assertFalse(AutoAdvanceController.isOverriddenThisSlide());
    }

    @Test
    void reArmingClearsAStaleOverride() {
        AutoAdvanceController.armSession(MultiSlideAcquisitionMode.AUTOMATIC_WITH_OVERRIDE, 10);
        AutoAdvanceController.overrideCurrentSlide("test");
        // A second batch driver starting must not inherit the previous run's takeover.
        AutoAdvanceController.armSession(MultiSlideAcquisitionMode.AUTOMATIC_WITH_OVERRIDE, 10);
        assertFalse(AutoAdvanceController.isOverriddenThisSlide());
    }

    @Test
    void modeParsingDegradesToManualRatherThanThrowing() {
        assertEquals(MultiSlideAcquisitionMode.MANUAL, MultiSlideAcquisitionMode.fromPreferenceValue(null));
        assertEquals(MultiSlideAcquisitionMode.MANUAL, MultiSlideAcquisitionMode.fromPreferenceValue(""));
        assertEquals(
                MultiSlideAcquisitionMode.MANUAL, MultiSlideAcquisitionMode.fromPreferenceValue("SOMETHING_REMOVED"));
        // Case matters: an almost-right value is still not a licence to run unattended.
        assertEquals(
                MultiSlideAcquisitionMode.MANUAL, MultiSlideAcquisitionMode.fromPreferenceValue("fully_automatic"));

        assertEquals(
                MultiSlideAcquisitionMode.FULLY_AUTOMATIC,
                MultiSlideAcquisitionMode.fromPreferenceValue("FULLY_AUTOMATIC"));
        assertEquals(
                MultiSlideAcquisitionMode.AUTOMATIC_WITH_OVERRIDE,
                MultiSlideAcquisitionMode.fromPreferenceValue("AUTOMATIC_WITH_OVERRIDE"));
    }

    @Test
    void onlyManualReportsNonAutomatic() {
        assertFalse(MultiSlideAcquisitionMode.MANUAL.isAutomatic());
        assertTrue(MultiSlideAcquisitionMode.FULLY_AUTOMATIC.isAutomatic());
        assertTrue(MultiSlideAcquisitionMode.AUTOMATIC_WITH_OVERRIDE.isAutomatic());
    }

    @Test
    void requestOperatorAttentionStopsAutoAdvanceForTheSlide() {
        AutoAdvanceController.armSession(MultiSlideAcquisitionMode.FULLY_AUTOMATIC, 10);
        assertFalse(AutoAdvanceController.isOverriddenThisSlide());

        AutoAdvanceController.requestOperatorAttention("Multi-tile refinement", "SIFT found no match");

        assertTrue(
                AutoAdvanceController.isOverriddenThisSlide(),
                "a step that cannot continue must hand the slide back, not leave it counting down");
        assertTrue(AutoAdvanceController.isArmed(), "the batch keeps running; only this slide waits");
    }

    @Test
    void requestOperatorAttentionIsClearedByTheNextSlide() {
        AutoAdvanceController.armSession(MultiSlideAcquisitionMode.FULLY_AUTOMATIC, 10);
        AutoAdvanceController.requestOperatorAttention("Multi-tile refinement", "SIFT found no match");
        assertTrue(AutoAdvanceController.isOverriddenThisSlide());

        AutoAdvanceController.beginSlide();

        assertFalse(
                AutoAdvanceController.isOverriddenThisSlide(),
                "one slide needing a human must not turn the rest of the batch manual");
    }

    @Test
    void requestOperatorAttentionIsSafeWhenNotArmed() {
        // Reached from single-slide runs of the same code paths; must not throw or arm anything.
        AutoAdvanceController.requestOperatorAttention("Single-tile refinement", "SIFT found no match");
        assertFalse(AutoAdvanceController.isArmed());
    }
}
