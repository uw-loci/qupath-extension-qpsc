package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Derived outputs (LC-PolScope retardance, orientation, orientation_color; PPM .biref / .sum)
 * stitch on the angles' or channels' pool instead of one after another after the channel merge.
 * Fake stitch tasks stand in for the real stitcher.
 */
@Timeout(60)
class DerivedStitchSchedulingTest {

    private static final Path BASE = Path.of(".");

    private static StitchingRegistration.TargetStitcher<String> quick() {
        return (t, mode, position, total) -> t + "-out";
    }

    @Test
    void siblingsReturnWhileDerivedOutputsStillRun() throws Exception {
        CountDownLatch derivedStarted = new CountDownLatch(2);
        CountDownLatch mergeDone = new CountDownLatch(1);
        StitchingRegistration.Extras extras =
                new StitchingRegistration.Extras(List.of("retardance", "orientation"), (t, mode, p, n) -> {
                    derivedStarted.countDown();
                    assertTrue(mergeDone.await(30, TimeUnit.SECONDS));
                    return t + "-out";
                });

        StitchingRegistration.Batch batch =
                StitchingRegistration.stitchTargets(List.of("State0", "State1"), BASE, 4, 0, quick(), extras);

        assertEquals(List.of("State0-out", "State1-out"), batch.siblings());
        assertTrue(derivedStarted.await(10, TimeUnit.SECONDS), "derived outputs start while the job goes on");
        assertFalse(batch.extras().isDone(), "the merge runs while derived outputs are still stitching");
        mergeDone.countDown();
        assertEquals(
                List.of("retardance-out", "orientation-out"), batch.extras().get(30, TimeUnit.SECONDS));
    }

    @Test
    void derivedOutputsStartBeforeTheLastSiblingFinishes() {
        CountDownLatch derivedStarted = new CountDownLatch(1);
        StitchingRegistration.TargetStitcher<String> siblings = (t, mode, p, n) -> {
            if (t.equals("State1")) {
                // Ends only once a derived output has started, i.e. they overlap.
                return derivedStarted.await(10, TimeUnit.SECONDS) ? t + "-out" : "timed out";
            }
            return t + "-out";
        };
        StitchingRegistration.Extras extras = new StitchingRegistration.Extras(List.of("retardance"), (t, m, p, n) -> {
            derivedStarted.countDown();
            return t + "-out";
        });

        StitchingRegistration.Batch batch = StitchingRegistration.stitchChannels(
                List.of("State0", "State1"), List.of(), BASE, 1, 1, 4, siblings, extras);

        assertEquals(List.of("State0-out", "State1-out"), batch.siblings());
        assertEquals(List.of("retardance-out"), batch.extras().join());
    }

    @Test
    void siblingsAreQueuedAheadOfDerivedOutputs() {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        StitchingRegistration.TargetStitcher<String> record = (t, m, p, n) -> {
            order.add(t);
            return t;
        };
        StitchingRegistration.Batch batch = StitchingRegistration.stitchTargets(
                List.of("s0", "s1", "s2"),
                BASE,
                1,
                0,
                record,
                new StitchingRegistration.Extras(List.of("d0", "d1"), record));
        batch.extras().join();
        assertEquals(List.of("s0", "s1", "s2", "d0", "d1"), order, "a free slot goes to a sibling first");
    }

    @Test
    void derivedOutputsShareThePoolBound() {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        StitchingRegistration.TargetStitcher<String> busy = (t, m, p, n) -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            Thread.sleep(50);
            running.decrementAndGet();
            return t;
        };
        StitchingRegistration.Batch batch = StitchingRegistration.stitchTargets(
                List.of("s0", "s1", "s2", "s3", "s4"),
                BASE,
                2,
                0,
                busy,
                new StitchingRegistration.Extras(List.of("d0", "d1", "d2"), busy));
        batch.extras().join();
        assertTrue(peak.get() <= 2, "never more writers than the preference allows: " + peak.get());
    }

    @Test
    void derivedOutputsApplyTheSiblingsMode() {
        List<Object> siblingModes = Collections.synchronizedList(new ArrayList<>());
        List<Object> derivedModes = Collections.synchronizedList(new ArrayList<>());
        StitchingRegistration.Batch batch = StitchingRegistration.stitchTargets(
                List.of("ref", "s1", "s2"),
                BASE,
                3,
                0,
                (t, mode, p, n) -> {
                    if (!t.equals("ref")) {
                        siblingModes.add(mode);
                    }
                    return t;
                },
                new StitchingRegistration.Extras(List.of("d0", "d1"), (t, mode, p, n) -> {
                    derivedModes.add(mode);
                    return t;
                }));
        batch.extras().join();
        assertEquals(2, derivedModes.size());
        for (Object mode : derivedModes) {
            assertTrue(Objects.equals(mode, siblingModes.get(0)), "a derived output reuses the siblings' solve");
        }
    }

    @Test
    void aFailedDerivedOutputLeavesTheOthersAndTheSiblings() {
        StitchingRegistration.Extras extras = new StitchingRegistration.Extras(
                List.of("retardance", "orientation", "orientation_color"), (t, m, p, n) -> {
                    if (t.equals("orientation")) {
                        throw new IllegalStateException("disk full");
                    }
                    if (t.equals("orientation_color")) {
                        throw new Error("simulated out of memory");
                    }
                    return t + "-out";
                });
        StitchingRegistration.Batch batch =
                StitchingRegistration.stitchTargets(List.of("State0", "State1"), BASE, 4, 0, quick(), extras);

        assertEquals(List.of("State0-out", "State1-out"), batch.siblings());
        List<String> derived = batch.extras().join();
        assertEquals("retardance-out", derived.get(0));
        assertNull(derived.get(1), "a failure lands in its own slot");
        assertNull(derived.get(2), "even an Error does not make the future exceptional");
    }

    @Test
    void aFailedSiblingLeavesTheDerivedOutputs() {
        StitchingRegistration.Batch batch = StitchingRegistration.stitchTargets(
                List.of("State0", "boom"),
                BASE,
                4,
                0,
                (t, m, p, n) -> {
                    if (t.equals("boom")) {
                        throw new IllegalStateException("stitch failed");
                    }
                    return t + "-out";
                },
                new StitchingRegistration.Extras(List.of("retardance"), (t, m, p, n) -> t + "-out"));
        assertEquals("State0-out", batch.siblings().get(0));
        assertNull(batch.siblings().get(1));
        assertEquals(List.of("retardance-out"), batch.extras().join());
    }

    @Test
    void noDerivedOutputsMeansACompletedFuture() {
        StitchingRegistration.Batch batch = StitchingRegistration.stitchTargets(
                List.of("a"), BASE, 2, 0, quick(), StitchingRegistration.Extras.none());
        assertTrue(batch.extras().isDone());
        assertEquals(List.of(), batch.extras().join());
    }

    @Test
    void statusLineNamesWhatIsStillStitching() {
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        StitchJobStatus status = new StitchJobStatus(
                "channels", List.of("State0", "State1"), List.of("retardance", "orientation"), lines::add);
        status.phase("Stitching bounds");
        status.started("State0");
        status.started("retardance");
        status.finished("State0", true);
        assertEquals(
                "Stitching bounds (channels 1 of 2 done, queued: State1; derived outputs 0 of 2 done,"
                        + " stitching: retardance, queued: orientation)",
                status.text());
        status.finished("State1", false);
        status.phase("Merging 1 channels");
        status.finished("retardance", true);
        assertTrue(status.derivedPending());
        status.finished("orientation", true);
        assertFalse(status.derivedPending());
        assertEquals(
                "Merging 1 channels (channels 1 of 2 done, failed: State1; derived outputs 2 of 2 done)",
                status.text());
        assertEquals(status.text(), lines.get(lines.size() - 1), "every change is published");
    }
}
