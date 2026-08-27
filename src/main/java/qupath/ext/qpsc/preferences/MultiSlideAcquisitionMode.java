package qupath.ext.qpsc.preferences;

/**
 * How much of the multi-slide batch setup pass runs without a human.
 *
 * <p>The multi-slide batch is a two-pass flow: an interactive SETUP pass (align /
 * refine / configure each slide) followed by an already-unattended ACQUIRE pass.
 * This mode governs the SETUP pass only -- the acquire pass shows no dialogs in
 * any mode.
 *
 * <p>Stored as {@link #name()} in a string preference so an unrecognised value
 * (rollback, rename) degrades to {@link #MANUAL} rather than leaving the batch in
 * an undefined state.
 *
 * <p>WARNING: the automatic modes confirm each setup dialog's primary action on a
 * timer, so an auto-confirmed alignment accepts whatever position the base transform
 * predicted with no human check. Treat them as unvalidated for production runs.
 *
 * <p>Every tile pick along the setup pass is now made by {@link
 * qupath.ext.qpsc.controller.workflow.ReferenceTileSelector}: the first landmark point
 * of each slide, the single-tile refinement pick, and each multi-tile reference point.
 * That matters because those picks could not be automated by a countdown --
 * {@code UIFunctions.promptTileSelectionDialogAsync} creates its Confirm button disabled
 * and only enables it once a polling Timeline sees a tile-bearing detection selected in
 * the viewer, so a countdown would hit the disabled-primary guard and hand the slide
 * back. Landmark points 2 and 3 never needed a pick; they are chosen programmatically.
 *
 * <p>Two situations still hand a slide back deliberately, both because proceeding would
 * mis-align it: tissue too small to contain an interior tile (nothing survives the
 * selector's filters), and a SIFT match below the confidence threshold. Both call
 * {@link qupath.ext.qpsc.ui.AutoAdvanceController#requestOperatorAttention}, which stops
 * auto-advance for that slide only and sends a push notification.
 *
 * <p>The first landmark of each slide also runs a tissue search before focusing
 * ({@code SlotJumpAutofocus.TissueSearchHint} -> the server's {@code FINDTISS}): the base
 * transform puts that landmark a median 613 um from its target, often on blank glass where
 * a focus scan finds coverslip contrast rather than the sample. Later landmarks land within
 * 26 um and do not search. The search is UNVERIFIED ON HARDWARE.
 */
public enum MultiSlideAcquisitionMode {

    /** Today's flow: every setup dialog waits for a human. The default. */
    MANUAL("Manual (every dialog waits)"),

    /**
     * No waiting: each setup dialog confirms its primary action almost immediately
     * and user interaction cannot pause it. Failures are handled by policy, never
     * by prompting.
     */
    FULLY_AUTOMATIC("Fully automatic (no waiting)"),

    /**
     * Each setup dialog counts down and then confirms its primary action. Any
     * deliberate interaction with a dialog cancels the countdown and hands the
     * REST OF THAT SLIDE back to the operator; automation resumes at the next
     * slide.
     */
    AUTOMATIC_WITH_OVERRIDE("Automatic, operator can take over");

    private final String displayName;

    MultiSlideAcquisitionMode(String displayName) {
        this.displayName = displayName;
    }

    /** Human-readable label for preference UI. */
    public String displayName() {
        return displayName;
    }

    /** True for the two modes that auto-confirm setup dialogs. */
    public boolean isAutomatic() {
        return this != MANUAL;
    }

    /**
     * Parse a stored preference value, degrading to {@link #MANUAL} for null,
     * empty, or unrecognised input. Never throws -- a bad preference must not be
     * able to start an unattended run.
     */
    public static MultiSlideAcquisitionMode fromPreferenceValue(String stored) {
        if (stored == null || stored.isEmpty()) {
            return MANUAL;
        }
        try {
            return valueOf(stored);
        } catch (IllegalArgumentException e) {
            return MANUAL;
        }
    }
}
