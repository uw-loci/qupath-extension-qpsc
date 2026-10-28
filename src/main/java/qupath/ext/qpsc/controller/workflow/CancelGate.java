package qupath.ext.qpsc.controller.workflow;

import java.util.function.BooleanSupplier;

/**
 * The one place the acquisition loop asks "has anyone cancelled?".
 *
 * <p>Three signals can cancel a run: the progress dialog's Cancel button, a stop request from
 * outside the dialog (the QP Scope menu, the Live Viewer, the workflow registry), and the
 * multi-slide batch token. On 2026-10-27 a Cancel pressed between the dialog opening and the
 * ACQUIRE command being sent was lost, because only the monitor loop read the dialog's flag.
 * The loop now consults this gate before every stage-driving step.
 */
public final class CancelGate {

    private final BooleanSupplier dialogCancelled;
    private final BooleanSupplier stopRequested;
    private final BooleanSupplier batchCancelled;

    /**
     * @param dialogCancelled the progress dialog's cancelled flag; may return false when no dialog
     * @param stopRequested the workflow's own stop flag
     * @param batchCancelled the batch token; may return false when there is no batch
     */
    public CancelGate(BooleanSupplier dialogCancelled, BooleanSupplier stopRequested, BooleanSupplier batchCancelled) {
        this.dialogCancelled = dialogCancelled;
        this.stopRequested = stopRequested;
        this.batchCancelled = batchCancelled;
    }

    /** @return true when any signal says cancel */
    public boolean isCancelled() {
        return dialogCancelled.getAsBoolean() || stopRequested.getAsBoolean() || batchCancelled.getAsBoolean();
    }

    /** @return which signal fired first in the fixed order, for the log; "none" when none did */
    public String source() {
        if (dialogCancelled.getAsBoolean()) {
            return "progress dialog";
        }
        if (stopRequested.getAsBoolean()) {
            return "stop request";
        }
        if (batchCancelled.getAsBoolean()) {
            return "batch abort";
        }
        return "none";
    }
}
