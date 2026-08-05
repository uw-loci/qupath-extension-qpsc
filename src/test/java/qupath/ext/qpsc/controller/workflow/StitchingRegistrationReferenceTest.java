package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ppm.PPMModalityHandler;

/**
 * Tests for which sibling target content-based registration solves on, and for the positional
 * contract that choice has to preserve.
 *
 * <p>Registration solves once and every other angle/channel reuses that solve, so the reference
 * decides where all of them are placed. Before 2026-08-05 it was hardcoded to the first target,
 * which on PPM meant solving on a near-extinction angle -- dark, low-contrast images of tissue that
 * is not remotely featureless -- and shipping those measurements to every output.
 */
public class StitchingRegistrationReferenceTest {

    /** A handler with no opinion, i.e. every modality that has not implemented the hook. */
    private static class NoPreferenceHandler implements ModalityHandler {
        @Override
        public java.util.concurrent.CompletableFuture<List<qupath.ext.qpsc.modality.AngleExposure>> getRotationAngles(
                String modalityName, String objective, String detector, String wbMode) {
            return java.util.concurrent.CompletableFuture.completedFuture(List.of());
        }
    }

    private static final ModalityHandler NO_PREFERENCE = new NoPreferenceHandler();

    @Test
    public void ppmSolvesOnNinetyDegrees() {
        List<String> angles = List.of("7.0", "-7.0", "90.0");
        assertEquals(
                2,
                StitchingRegistration.referenceIndexFor(angles, new PPMModalityHandler()),
                "PPM must solve on the open 90-degree angle, not the first listed");
    }

    @Test
    public void ppmFindsNinetyRegardlessOfPosition() {
        assertEquals(
                0, StitchingRegistration.referenceIndexFor(List.of("90.0", "7.0", "-7.0"), new PPMModalityHandler()));
        assertEquals(
                1, StitchingRegistration.referenceIndexFor(List.of("7.0", "-90.0", "5.0"), new PPMModalityHandler()));
    }

    @Test
    public void ppmWithoutNinetyFallsBackToFirst() {
        // Among crossed angles alone there is no principled winner, so the historical default
        // stands rather than the handler guessing.
        assertEquals(0, StitchingRegistration.referenceIndexFor(List.of("7.0", "-7.0"), new PPMModalityHandler()));
    }

    @Test
    public void nonAngleTargetNamesAreIgnoredNotCrashed() {
        // Channel-split targets and post-processing dirs flow through the same list.
        List<String> mixed = List.of("DAPI", "FITC", "7.0.biref", "90.0");
        assertEquals(3, StitchingRegistration.referenceIndexFor(mixed, new PPMModalityHandler()));
        assertEquals(0, StitchingRegistration.referenceIndexFor(List.of("DAPI", "FITC"), new PPMModalityHandler()));
    }

    @Test
    public void noPreferenceKeepsFirstTarget() {
        assertEquals(0, StitchingRegistration.referenceIndexFor(List.of("a", "b", "c"), NO_PREFERENCE));
        assertEquals(0, StitchingRegistration.referenceIndexFor(List.of("a", "b"), null));
    }

    @Test
    public void outOfRangePreferenceIsClampedNotPropagated() {
        ModalityHandler rogue = new NoPreferenceHandler() {
            @Override
            public OptionalInt registrationReferenceIndex(List<String> targetNames) {
                return OptionalInt.of(99);
            }
        };
        assertEquals(0, StitchingRegistration.referenceIndexFor(List.of("a", "b"), rogue));
    }

    @Test
    public void emptyTargetListIsSafe() {
        assertEquals(0, StitchingRegistration.referenceIndexFor(List.of(), new PPMModalityHandler()));
        assertEquals(0, StitchingRegistration.referenceIndexFor(null, new PPMModalityHandler()));
    }

    /**
     * The reference is no longer necessarily target 0, so results must still come back aligned with
     * the inputs. Callers that pair targets with outputs by position -- channel split/merge -- would
     * otherwise attach each stitched file to the wrong channel, silently.
     */
    @Test
    public void resultsStayAlignedWithInputsWhateverTheReference() {
        for (int reference = 0; reference < 4; reference++) {
            List<String> targets = List.of("t0", "t1", "t2", "t3");
            List<String> out = StitchingRegistration.stitchTargets(
                    targets, java.nio.file.Path.of("."), 4, reference, (t, mode, position, total) -> t + "-out");
            assertEquals(
                    List.of("t0-out", "t1-out", "t2-out", "t3-out"),
                    out,
                    "results must be in input order with reference index " + reference);
        }
    }

    @Test
    public void everyTargetIsStitchedExactlyOnce() {
        List<String> targets = List.of("a", "b", "c", "d", "e");
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        StitchingRegistration.stitchTargets(targets, java.nio.file.Path.of("."), 3, 2, (t, mode, position, total) -> {
            seen.add(t);
            return t;
        });
        List<String> sorted = new ArrayList<>(seen);
        Collections.sort(sorted);
        assertEquals(targets, sorted, "each target stitched exactly once, none dropped or repeated");
    }

    @Test
    public void aFailedTargetLeavesNullInItsOwnSlot() {
        List<String> targets = List.of("ok0", "boom", "ok2");
        List<String> out = StitchingRegistration.stitchTargets(
                targets, java.nio.file.Path.of("."), 2, 2, (t, mode, position, total) -> {
                    if ("boom".equals(t)) {
                        throw new IllegalStateException("stitch failed");
                    }
                    return t + "-out";
                });
        assertEquals(3, out.size());
        assertEquals("ok0-out", out.get(0));
        assertNull(out.get(1), "the failure must land in the failing target's own slot");
        assertEquals("ok2-out", out.get(2));
    }
}
