package qupath.ext.qpsc.controller.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The multi-tile refinement's first point must apply the Stage Map view assists whether or not the
 * stage move succeeded.
 *
 * <p>There are two routes to the capture pane: the normal one after the move and autofocus, and the
 * catch block that presents the pane in manual mode when the move threw. Only the first called
 * {@code applyAlignStartViewAssists()}, so a stage fault silently left the Stage Map in whatever
 * view it was in -- which reads as "Camera View stopped working" even though that code had not
 * changed in weeks. On this rig the move goes over the same Prior serial link that was throwing
 * {@code Serial command failed (14)} eleven times in eleven hours, so the failure branch was being
 * taken in normal operation.
 *
 * <p>Asserted against the source rather than by driving the UI: reaching either branch needs a
 * JavaFX stage, a live socket and a project, and the invariant worth protecting is structural --
 * every path to {@code hostCapturePane} runs the assists first.
 */
class RefinementViewAssistsOnBothPathsTest {

    private static final Path SOURCE =
            Path.of("src/main/java/qupath/ext/qpsc/controller/workflow/MultiTileRefinement.java");

    @Test
    void everyCapturePaneCallIsPrecededByTheViewAssists() throws Exception {
        String src = Files.readString(SOURCE);

        // Count the call sites (the declaration has a different shape: "private static void").
        int callSites = src.split("hostCapturePane\\(", -1).length - 1;
        int declarations = src.split("void hostCapturePane\\(", -1).length - 1;
        int invocations = callSites - declarations;
        assertThat(invocations).as("both the success and the move-failed paths").isEqualTo(2);

        int assists = src.split("applyAlignStartViewAssists\\(\\)", -1).length - 1;
        assertThat(assists)
                .as("one guarded call per path to the capture pane; a new path must add its own")
                .isEqualTo(invocations);
    }

    @Test
    void theAssistsAreGuardedToTheFirstPointOnBothPaths() throws Exception {
        String src = Files.readString(SOURCE);
        // The assists are a once-per-alignment-step action, not once per captured point.
        int guarded = src.split(
                                "if \\(pointNumber == 1\\) \\{\\s*\\n\\s*MultiSlideExistingImageWorkflow\\.applyAlignStartViewAssists\\(\\);",
                                -1)
                        .length
                - 1;
        assertThat(guarded).isEqualTo(2);
    }

    @Test
    void theManualFallbackSaysWhyItHappened() throws Exception {
        // A bare "stage move failed" sent the last investigation looking at the refinement code
        // when the cause was the serial link.
        String src = Files.readString(SOURCE);
        assertThat(src).contains("stage move failed");
        assertThat(src).contains("MANUAL");
        assertThat(src)
                .as("point the reader at the controller, not at this file")
                .contains("serial link");
    }
}
