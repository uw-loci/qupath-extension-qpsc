package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** One owner for the acquisition globals; a run that is only stitching yields to the next. */
class WorkflowRegistryTest {

    @Test
    void aSecondRunTakesOverFromOneThatIsOnlyStitching() {
        WorkflowRegistry r = new WorkflowRegistry(() -> false);
        WorkflowRegistry.Activation first = r.tryActivate("existing-image", "L004");
        assertTrue(first.activated());
        WorkflowRegistry.Activation second = r.tryActivate("existing-image", "L005");
        assertTrue(second.activated());
        assertNotEquals(first.id(), second.id());
        assertFalse(r.isActive(first.id()), "run N no longer owns the globals");
        assertTrue(r.isActive(second.id()));
        // Run N's late cleanup must not free run N+1's slot.
        r.release(first.id());
        assertTrue(r.isActive(second.id()));
        r.release(second.id());
        assertEquals(0, r.activeId());
    }

    @Test
    void aRunIsRefusedWhileAnAcquisitionHoldsTheStage() {
        AtomicBoolean acquiring = new AtomicBoolean(false);
        WorkflowRegistry r = new WorkflowRegistry(acquiring::get);
        WorkflowRegistry.Activation first = r.tryActivate("existing-image", "L004");
        acquiring.set(true);
        WorkflowRegistry.Activation second = r.tryActivate("existing-image", "L005");
        assertFalse(second.activated());
        assertTrue(second.refusal().contains("Stop acquisition"), second.refusal());
        assertTrue(r.isActive(first.id()), "the refused attempt changed nothing");
        acquiring.set(false);
        assertTrue(r.tryActivate("existing-image", "L005").activated());
    }

    @Test
    void zeroIsNeverActive() {
        WorkflowRegistry r = new WorkflowRegistry(() -> false);
        assertFalse(r.isActive(0));
        r.release(0);
        assertEquals(0, r.activeId());
    }
}
