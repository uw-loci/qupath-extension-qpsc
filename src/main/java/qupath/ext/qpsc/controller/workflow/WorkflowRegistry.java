package qupath.ext.qpsc.controller.workflow;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One owner for the globals an acquisition workflow sets: the microscope controller's current
 * transform and its acquisition-active flag.
 *
 * <p>Two Existing Image runs overlap routinely, because run N's stitching outlives its
 * acquisition and run N+1 starts while it finishes. On 2026-10-27 run N's {@code cleanup()}
 * nulled the current transform that run N+1 had just set. Now each run activates here when it
 * starts; a new run takes over the active slot (which is fine while the old one is only
 * stitching), and {@code cleanup()} clears the globals only when the run still holds the slot.
 *
 * <p>Starting a run while an acquisition holds the stage is refused: two runs cannot drive one
 * stage, and the operator needs Stop acquisition, not a second dialog.
 *
 * <p>Thread-safe; the FX thread and worker threads both call it.
 */
public final class WorkflowRegistry {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowRegistry.class);

    /** What {@link #tryActivate} decided. */
    public record Activation(long id, boolean activated, String refusal) {
        static Activation refused(String why) {
            return new Activation(0, false, why);
        }
    }

    private static final WorkflowRegistry INSTANCE = new WorkflowRegistry(
            () -> qupath.ext.qpsc.controller.MicroscopeController.getInstance().isAcquisitionActive());

    /** The registry every workflow uses. */
    public static WorkflowRegistry get() {
        return INSTANCE;
    }

    private final BooleanSupplier acquisitionActive;
    private final AtomicLong nextId = new AtomicLong(1);
    private long activeId;
    private String activeKind;
    private String activeSource;

    /** @param acquisitionActive whether an acquisition holds the stage right now */
    public WorkflowRegistry(BooleanSupplier acquisitionActive) {
        this.acquisitionActive = acquisitionActive;
    }

    /**
     * Claims the active slot for a new run.
     *
     * @param kind a label for the log ("existing-image", "bounded", ...)
     * @param source the entry or region the run works on, for the log
     * @return the activation; when refused, {@code refusal} says why
     */
    public synchronized Activation tryActivate(String kind, String source) {
        if (acquisitionActive.getAsBoolean()) {
            String why = "an acquisition is running"
                    + (activeKind != null ? " (" + activeKind + " on " + activeSource + ")" : "")
                    + "; stop it (QP Scope > Acquisition controls > Stop acquisition) before starting another run";
            logger.warn("Refusing to start {} on {}: {}", kind, source, why);
            return Activation.refused(why);
        }
        if (activeId != 0) {
            logger.info(
                    "Workflow #{} ({} on {}) takes over from #{} ({} on {}), which is no longer acquiring",
                    nextId.get(),
                    kind,
                    source,
                    activeId,
                    activeKind,
                    activeSource);
        }
        activeId = nextId.getAndIncrement();
        activeKind = kind;
        activeSource = source;
        logger.info("Workflow #{} active: {} on {}", activeId, kind, source);
        return new Activation(activeId, true, null);
    }

    /** True while {@code id} still holds the active slot (so its cleanup may clear the globals). */
    public synchronized boolean isActive(long id) {
        return id != 0 && id == activeId;
    }

    /** The run is over. Frees the slot only when this run still holds it. */
    public synchronized void release(long id) {
        if (id != 0 && id == activeId) {
            logger.info("Workflow #{} released", id);
            activeId = 0;
            activeKind = null;
            activeSource = null;
        } else if (id != 0) {
            logger.info("Workflow #{} finished after being superseded; leaving the active run's globals alone", id);
        }
    }

    public synchronized long activeId() {
        return activeId;
    }
}
