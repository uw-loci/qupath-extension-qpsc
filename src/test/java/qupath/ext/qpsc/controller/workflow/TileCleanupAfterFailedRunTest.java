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
 * was then canceled, and the error path force-deleted the whole run folder, region 1's tiles
 * with it. The stitch failed every read for seven minutes and three of region 1's four images
 * were never written.
 *
 * <p>2026-11-10: a region stopped at the stage limit after 741 positions and ten hours, and the
 * error path deleted its 2964 images. A region that did not finish but holds acquired images is
 * now kept and returned for the operator to stitch or delete; only empty tile grids are removed.
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

    private Path emptyGrid(String name) throws IOException {
        Path dir = Files.createDirectories(runDir.resolve(name));
        Files.writeString(dir.resolve("TileConfiguration.txt"), "0.tif; ; (0.0, 0.0)");
        return dir;
    }

    @Test
    void aFinishedRegionKeepsItsTilesAndAStoppedOneIsHeld() throws IOException {
        Path finished = region("27688_18635");
        Path stopped = region("27699_34794");

        List<java.io.File> held = TileCleanupHelper.cleanupAfterFailedRun(
                runDir.toString(), List.of("27688_18635", "27699_34794"), Set.of("27688_18635"));

        assertThat(hasTiles(finished)).isTrue();
        assertThat(hasTiles(stopped)).isTrue();
        assertThat(held).containsExactly(stopped.toFile());
    }

    @Test
    void aRegionWithoutImagesIsStillDeleted() throws IOException {
        Path grid = emptyGrid("never-imaged");

        List<java.io.File> held =
                TileCleanupHelper.cleanupAfterFailedRun(runDir.toString(), List.of("never-imaged"), Set.of());

        assertThat(held).isEmpty();
        // The normal delete: no tile images remain (metadata files are kept, as after any run).
        assertThat(TileCleanupHelper.countTileImages(grid)).isZero();
    }

    @Test
    void tileImagesAreCountedInAngleFolders() throws IOException {
        Path r = region("42606_60590");
        Files.writeString(r.resolve("90.0").resolve("1.tif"), "tile");
        Path angle = Files.createDirectories(r.resolve("7.0"));
        Files.writeString(angle.resolve("0.tiff"), "tile");
        Files.writeString(r.resolve("TileConfiguration.txt"), "not an image");

        assertThat(TileCleanupHelper.countTileImages(r)).isEqualTo(3);
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
    void withNothingFinishedAStoppedRegionIsHeldNotDeleted() throws IOException {
        Path only = region("27699_34794");

        List<java.io.File> held =
                TileCleanupHelper.cleanupAfterFailedRun(runDir.toString(), List.of("27699_34794"), Set.of());

        assertThat(held).containsExactly(only.toFile());
        assertThat(hasTiles(only)).isTrue();
    }
}
