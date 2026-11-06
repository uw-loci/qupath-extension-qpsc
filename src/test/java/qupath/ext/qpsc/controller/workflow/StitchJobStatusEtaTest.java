package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.stitching.TargetEta;

/** A job's time left from its targets: predicted while waiting, measured while running. */
class StitchJobStatusEtaTest {

    private static StitchJobStatus ppmJob(List<Double> remaining) {
        StitchJobStatus status =
                new StitchJobStatus("angles", List.of("90.0", "7.0", "-7.0"), List.of("7.0.biref"), line -> {});
        status.configureEta(4, Map.of("90.0", 1000.0, "7.0", 1000.0, "-7.0", 1000.0, "7.0.biref", 800.0));
        status.onRemaining(remaining::add);
        return status;
    }

    @Test
    void beforeAnythingStartsThePredictionsAreScheduledOnThePool() {
        List<Double> remaining = new ArrayList<>();
        StitchJobStatus status = ppmJob(remaining);
        assertEquals(1000, status.remainingSeconds(), 1e-9);
    }

    @Test
    void aRunningSolveComesFirst() {
        List<Double> remaining = new ArrayList<>();
        StitchJobStatus status = ppmJob(remaining);
        status.progress(new TargetEta("registration [90.0]", "measuring tile overlaps", 0.5, 300, 300, true));
        assertEquals(300 + 1000, remaining.get(remaining.size() - 1), 1e-9);
        status.solveDone();
        assertEquals(1000, remaining.get(remaining.size() - 1), 1e-9);
    }

    @Test
    void measuredTargetsSetTheirOwnTimeAndScaleTheWaitingOnes() {
        List<Double> remaining = new ArrayList<>();
        StitchJobStatus status = new StitchJobStatus("angles", List.of("a", "b", "c"), List.of(), line -> {});
        status.configureEta(2, Map.of("a", 100.0, "b", 100.0, "c", 100.0));
        status.onRemaining(remaining::add);
        status.started("a");
        status.started("b");
        // Both running take twice their prediction: 100 s in, 100 s left.
        status.progress(new TargetEta("a", "writing the image", 0.5, 100, 100, true));
        status.progress(new TargetEta("b", "writing the image", 0.5, 100, 100, true));
        // c waits for the first free writer (100 s) and then takes 2 x 100 s.
        assertEquals(100 + 200, status.remainingSeconds(), 1e-9);
        assertTrue(remaining.size() >= 2);
    }

    @Test
    void finishedTargetsLeaveTheirMeasuredDurationAsTheScale() {
        StitchJobStatus status = new StitchJobStatus("channels", List.of("a", "b"), List.of(), line -> {});
        status.configureEta(1, Map.of("a", 100.0, "b", 100.0));
        status.started("a");
        status.progress(new TargetEta("a", "writing the image", 1.0, 300, 0, true));
        status.finished("a", true);
        // a took 300 s for a 100 s prediction: b, waiting, is costed at 300 s.
        assertEquals(300, status.remainingSeconds(), 1e-9);
    }
}
