package qupath.ext.qpsc.controller.workflow;

import qupath.ext.qpsc.controller.workflow.SingleTileRefinement.RefinementResult;

/**
 * What the Existing Image workflow does with a refinement result.
 *
 * <p>Pure: the decision is a function of the result alone, so it is unit-tested without a GUI.
 * Before 2026-10-27 every non-accepted result proceeded to acquisition, so closing the
 * refinement dialog with the window's X (or cancelling the tile pick) started a run the
 * operator had not asked for.
 */
public final class RefinementOutcomePolicy {

    private RefinementOutcomePolicy() {}

    /** The workflow's next step after refinement. */
    public enum Decision {
        /** Stop: the microscope could not be put into a usable state (hard block). */
        BLOCK,
        /** Stop: the operator cancelled. No acquisition. */
        CANCEL,
        /** Acquire with the prior alignment (explicit Skip). */
        PROCEED,
        /** Switch to the full manual alignment path, then acquire. */
        MANUAL,
        /** Save the refined transform and acquire with it. */
        ACCEPT
    }

    /**
     * @param result the refinement result; {@code null} is the legacy hard-block signal
     * @return the decision
     */
    public static Decision decide(RefinementResult result) {
        if (result == null) {
            return Decision.BLOCK;
        }
        return switch (result.outcome) {
            case ACCEPTED -> result.transform != null ? Decision.ACCEPT : Decision.PROCEED;
            case SKIPPED -> Decision.PROCEED;
            case CANCELLED -> Decision.CANCEL;
            case NEW_ALIGNMENT -> Decision.MANUAL;
            case BLOCKED -> Decision.BLOCK;
        };
    }
}
