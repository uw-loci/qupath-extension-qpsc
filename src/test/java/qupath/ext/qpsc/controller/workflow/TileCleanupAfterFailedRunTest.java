package qupath.ext.qpsc.controller.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A cancel or failure late in a run must not delete regions that already finished.
 *
 * <p>2026-10-19, JOVE_PPM: region 27688_18635 finished and started stitching; region 27699_34794
 * was then cancelled, and the error path force-deleted the whole run folder, region 1's tiles
 * with it. The stitch failed every read for seven minutes and three of region 1's four images
 * were never written.
 */
class TileCleanupAfterFailedRunTest {

    @TempDir
    Path runDir;

    private Path region(String name) throws IOException {
        Path dir = Files.createDirectories(runDir.resolve(name).resolve("90.0"));
        Files.writeString(dir.resolve("0.tif"), "tile");
        return dir.getParent();
    }

    private static boolean hasTiles(Path regionDir) throws IOException {
        try (var walk = Files.walk(regionDir)) {
            return walk.anyMatch(p -> p.toString().endsWith(".tif"));
        }
    }

    @Test
    void aFinishedRegionKeepsItsTilesWhenALaterRegionIsCancelled() throws IOException {
        Path finished = region("27688_18635");
        Path cancelled = region("27699_34794");

        TileCleanupHelper.cleanupAfterFailedRun(
                runDir.toString(), List.of("27688_18635", "27699_34794"), Set.of("27688_18635"));

        assertThat(hasTiles(finished)).isTrue();
        assertThat(Files.exists(cancelled) && hasTiles(cancelled)).isFalse();
    }

    @Test
    void onlyTheUnfinishedRegionsAreCandidates() throws IOException {
        region("a");
        region("b");
        region("c");

        List<String> dirs =
                TileCleanupHelper.incompleteRegionDirs(runDir.toString(), List.of("a", "b", "c"), Set.of("b"));

        assertThat(dirs)
                .containsExactlyInAnyOrder(
                        runDir.resolve("a").toString(), runDir.resolve("c").toString());
    }

    @Test
    void aRegionWithNoFolderYetIsSkipped() throws IOException {
        region("a");

        List<String> dirs =
                TileCleanupHelper.incompleteRegionDirs(runDir.toString(), List.of("a", "never-started"), Set.of());

        assertThat(dirs).containsExactly(runDir.resolve("a").toString());
    }

    @Test
    void withNothingFinishedTheRunFolderIsClearedAsBefore() throws IOException {
        Path only = region("27699_34794");

        TileCleanupHelper.cleanupAfterFailedRun(runDir.toString(), List.of("27699_34794"), Set.of());

        assertThat(Files.exists(only) && hasTiles(only)).isFalse();
    }
}
