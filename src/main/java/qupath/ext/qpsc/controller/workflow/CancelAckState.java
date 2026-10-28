package qupath.ext.qpsc.controller.workflow;

import java.util.function.LongSupplier;

/**
 * What the progress dialog shows after Cancel is pressed, and when it may close.
 *
 * <p>The dialog used to close itself two seconds after Cancel regardless of whether the
 * microscope had stopped, which on 2026-10-27 left an acquisition running with no Cancel
 * control anywhere. Now it stays open, saying it is waiting, until the run acknowledges the
 * cancel; after {@link #timeoutMs} without acknowledgement it closes and tells the operator
 * where the stop control is.
 */
public final class CancelAckState {

    /** The dialog's state after Cancel. */
    public enum Phase {
        /** Cancel sent; waiting for the run to confirm it stopped. */
        REQUESTED,
        /** The run confirmed it stopped; the dialog may close. */
        ACKNOWLEDGED,
        /** No confirmation within the timeout; the dialog closes with a pointer to Stop acquisition. */
        TIMED_OUT
    }

    /** Status text while waiting. */
    public static final String WAITING_TEXT = "Cancelling... waiting for the microscope to stop";

    /** Status text after the timeout. */
    public static final String TIMED_OUT_TEXT =
            "No confirmation from the server; use QP Scope > Stop acquisition if it is still running";

    private final LongSupplier clockMs;
    private final long timeoutMs;
    private final long requestedAtMs;
    private Phase phase = Phase.REQUESTED;

    /**
     * @param clockMs monotonic clock in milliseconds
     * @param timeoutMs how long to wait for an acknowledgement before giving up
     */
    public CancelAckState(LongSupplier clockMs, long timeoutMs) {
        this.clockMs = clockMs;
        this.timeoutMs = timeoutMs;
        this.requestedAtMs = clockMs.getAsLong();
    }

    /** The run confirmed it stopped. Idempotent; a late acknowledgement after a timeout is kept as TIMED_OUT. */
    public void acknowledge() {
        if (phase == Phase.REQUESTED) {
            phase = Phase.ACKNOWLEDGED;
        }
    }

    /** Re-evaluate the timeout against the clock and return the current phase. */
    public Phase phase() {
        if (phase == Phase.REQUESTED && clockMs.getAsLong() - requestedAtMs >= timeoutMs) {
            phase = Phase.TIMED_OUT;
        }
        return phase;
    }

    /** @return the status text for the current phase */
    public String statusText(String acknowledgedText) {
        return switch (phase()) {
            case REQUESTED -> WAITING_TEXT;
            case ACKNOWLEDGED -> acknowledgedText;
            case TIMED_OUT -> TIMED_OUT_TEXT;
        };
    }

    /** @return true when the dialog may close (acknowledged or timed out) */
    public boolean mayClose() {
        return phase() != Phase.REQUESTED;
    }
}
