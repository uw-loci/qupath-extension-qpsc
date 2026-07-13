package qupath.ext.qpsc.controller.workflow;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A one-shot cancellation signal shared across a multi-slide batch run, so the panel's
 * "Abort All" button can cancel an in-flight single-slide acquisition instead of merely
 * halting the driver between slots.
 *
 * <p>The multi-slide orchestrator holds ONE token for the whole batch and threads it into
 * every slot's workflow via {@code WorkflowState.cancellationToken}. The active
 * {@link AcquisitionManager} registers a cancel action -- a callback that flips its
 * {@code DualProgressDialog}'s cancelled flag AND sends the {@code CANCEL} socket command
 * -- while its acquisition is running, and clears it once the slot settles. At any moment
 * at most one slot's acquisition is registered. {@link #cancel()} trips the flag and runs
 * whichever action is currently registered; when no acquisition is running it is a no-op on
 * the acquire side (the batch driver's own {@code aborted} flag still stops further slots).
 *
 * <p>Registering an action that does BOTH (a) sends CANCEL and (b) flips the dialog's
 * cancelled flag is the critical detail. The acquire loop discriminates cancel-vs-error by
 * reading {@code progressDialog.isCancelled()} (AcquisitionManager, the
 * {@code !result && !progressDialog.isCancelled()} branch). A raw CANCEL that did not also
 * flip that flag would be scored as an unexpected error, not a clean cancel.
 *
 * <p>Thread-safe. The flag and the registered action are held in atomics; the action itself
 * is expected to marshal any UI work to the JavaFX thread ({@code DualProgressDialog}
 * already does this).
 */
public final class CancellationToken {

    private static final Logger logger = LoggerFactory.getLogger(CancellationToken.class);

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<Runnable> cancelAction = new AtomicReference<>(null);

    /** Returns true once {@link #cancel()} has been called. */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Registers the action {@link #cancel()} should run to stop an in-flight acquisition
     * (typically: flip the active {@code DualProgressDialog}'s cancelled flag and send
     * CANCEL). If the token was already cancelled when this is called, the action runs
     * immediately so a slot that begins acquiring after an abort is stopped at once.
     *
     * @param action the cancel action, or {@code null} to clear (see
     *     {@link #clearCancelAction()})
     */
    public void registerCancelAction(Runnable action) {
        cancelAction.set(action);
        if (action != null && cancelled.get()) {
            logger.info("CancellationToken: registered after cancel already requested -- running action now");
            runQuietly(action);
        }
    }

    /** Clears any registered cancel action once a slot's acquisition has settled. */
    public void clearCancelAction() {
        cancelAction.set(null);
    }

    /**
     * Trips the token: marks it cancelled and runs the currently-registered cancel action
     * (if any). Idempotent -- a second call is a no-op. Safe to call when no acquisition is
     * running (no action registered), in which case only the flag is set.
     */
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        Runnable action = cancelAction.get();
        if (action != null) {
            logger.info("CancellationToken: cancel requested with an active acquisition -- running cancel action");
            runQuietly(action);
        } else {
            logger.info("CancellationToken: cancel requested with no active acquisition -- flag only");
        }
    }

    private static void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            logger.error("CancellationToken: cancel action threw", ex);
        }
    }
}
