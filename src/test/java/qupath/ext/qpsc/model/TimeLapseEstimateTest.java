package qupath.ext.qpsc.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The progress windows count a time-lapse's remaining timepoints and the interval between
 * them. They used to expect one timepoint's files per region, so the time left ran out
 * after the first timepoint and the waits were never counted.
 */
class TimeLapseEstimateTest {

    // 5 timepoints, 10 minutes apart, 100 files per timepoint at 1 s each (100 s per timepoint).
    private static final int T = 5;
    private static final double INTERVAL_S = 600;
    private static final int PER_TP = 100;
    private static final double MS_PER_FILE = 1000;

    @Test
    void atTheStartTheWholeRunIsCounted() {
        long ms = TimeLapseEstimate.remainingMs(T, INTERVAL_S, PER_TP, 0, MS_PER_FILE, 0);
        // Four intervals to the last start, then the last timepoint.
        assertThat(ms).isEqualTo(4 * 600_000L + 100_000L);
    }

    @Test
    void waitingBetweenTimepointsCountsDown() {
        // 50 files into timepoint 2, which started 50 s ago: 50 s to finish it, the next
        // start is 550 s away, then two more intervals and the last timepoint.
        long ms = TimeLapseEstimate.remainingMs(T, INTERVAL_S, PER_TP, PER_TP + 50, MS_PER_FILE, 50_000);
        assertThat(ms).isEqualTo(550_000L + 2 * 600_000L + 100_000L);
    }

    @Test
    void theLastTimepointIsJustItsFiles() {
        long ms = TimeLapseEstimate.remainingMs(T, INTERVAL_S, PER_TP, 4 * PER_TP + 40, MS_PER_FILE, 40_000);
        assertThat(ms).isEqualTo(60_000L);
    }

    @Test
    void aTimepointLongerThanTheIntervalRunsBackToBack() {
        // 30 s interval, 100 s per timepoint: the interval never holds anything up.
        long ms = TimeLapseEstimate.remainingMs(T, 30, PER_TP, 0, MS_PER_FILE, 0);
        assertThat(ms).isEqualTo(5 * 100_000L);
    }

    @Test
    void timepointsAreCountedFromTheFilesDone() {
        assertThat(TimeLapseEstimate.timepointOf(0, 500, 5)).isZero();
        assertThat(TimeLapseEstimate.timepointOf(99, 500, 5)).isZero();
        assertThat(TimeLapseEstimate.timepointOf(100, 500, 5)).isEqualTo(1);
        assertThat(TimeLapseEstimate.timepointOf(500, 500, 5)).isEqualTo(4);
        assertThat(TimeLapseEstimate.timepointOf(40, 100, 1)).isZero();
    }

    @Test
    void bothProgressWindowsCountTheTimepoints() throws Exception {
        String manager =
                Files.readString(Path.of("src/main/java/qupath/ext/qpsc/controller/workflow/AcquisitionManager.java"));
        assertThat(manager).contains("tilesPerAngle * stepsPerPositionForProgress * timepointsForProgress");
        assertThat(manager).contains("progressDialog.setTimeLapse(");
        assertThat(manager).contains("PersistentPreferences.getTimeLapseOrder(),");
        String bounded =
                Files.readString(Path.of("src/main/java/qupath/ext/qpsc/controller/BoundedAcquisitionWorkflow.java"));
        assertThat(bounded).contains("regionTiles * expectedFilesPerTile * timepoints;");
        String dialogs = Files.readString(Path.of("src/main/java/qupath/ext/qpsc/ui/components/WorkflowDialogs.java"));
        assertThat(dialogs).contains("long allowedMs = timeoutMs + (timepointsFinal > 1");
    }
}
