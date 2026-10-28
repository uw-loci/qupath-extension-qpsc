package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.geom.AffineTransform;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.controller.workflow.RefinementOutcomePolicy.Decision;
import qupath.ext.qpsc.controller.workflow.SingleTileRefinement.RefinementResult;

/**
 * Only an explicit Skip proceeds to acquisition. On 2026-10-27 the refinement dialog's X
 * started an acquisition because every non-accepted result meant "proceed".
 */
class RefinementOutcomePolicyTest {

    private static final AffineTransform PRIOR = new AffineTransform(2, 0, 0, 2, 10, 20);

    @Test
    void acceptedSavesTheRefinedTransform() {
        AffineTransform refined = new AffineTransform(2, 0, 0, 2, 12, 18);
        RefinementResult r = RefinementResult.accepted(refined, null);
        assertTrue(r.accepted);
        assertEquals(Decision.ACCEPT, RefinementOutcomePolicy.decide(r));
    }

    @Test
    void skipProceedsWithThePriorAlignment() {
        RefinementResult r = RefinementResult.skipped(PRIOR, null);
        assertFalse(r.accepted);
        assertEquals(Decision.PROCEED, RefinementOutcomePolicy.decide(r));
    }

    @Test
    void closingTheDialogCancelsTheWorkflow() {
        assertEquals(Decision.CANCEL, RefinementOutcomePolicy.decide(RefinementResult.cancelled(PRIOR, null)));
    }

    @Test
    void createNewAlignmentGoesManual() {
        assertEquals(Decision.MANUAL, RefinementOutcomePolicy.decide(RefinementResult.newAlignment(null)));
    }

    @Test
    void aHardBlockStopsTheRun() {
        assertEquals(Decision.BLOCK, RefinementOutcomePolicy.decide(RefinementResult.blocked(PRIOR, null)));
        assertEquals(Decision.BLOCK, RefinementOutcomePolicy.decide(null), "null is the legacy hard-block signal");
    }

    @Test
    void anAcceptedResultWithoutATransformCannotBeSaved() {
        // Defensive: nothing produces this today, but saving a null transform would NPE.
        assertEquals(Decision.PROCEED, RefinementOutcomePolicy.decide(RefinementResult.accepted(null, null)));
    }
}
