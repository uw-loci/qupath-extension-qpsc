package qupath.ext.qpsc.utilities;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The failure-path cleanup must remove only what the failed stitch itself wrote.
 *
 * <p>Its output directory is the sample's shared {@code SlideImages} folder, holding every
 * region and every previous run, so an over-broad delete costs the sample's entire stitched
 * history rather than one bad file. On 2026-09-27 it did exactly that: the pre-stitch snapshot
 * was only populated in batch mode, a per-angle stitch failed with an empty snapshot, every file
 * counted as "not pre-existing", and the folder was emptied of everything Windows was not
 * holding open.
 */
class StitchingCleanupBlastRadiusTest {

    private static File write(Path dir, String name, long lastModified) throws Exception {
        File f = dir.resolve(name).toFile();
        Files.writeString(f.toPath(), "x");
        assertThat(f.setLastModified(lastModified)).isTrue();
        return f;
    }

    @Test
    void anEmptySnapshotDoesNotAuthoriseDeletingTheFolder(@TempDir Path dir) throws Exception {
        // Exactly the 2026-09-27 shape: prior runs present, snapshot empty.
        long now = System.currentTimeMillis();
        File a = write(dir, "MetroHealth_FNA_PDAC_2_20x_6022_7751_90.0_007.ome.tif", now - 600_000);
        File b = write(dir, "MetroHealth_FNA_PDAC_3_20x_32180_49369_-7.0_008.ome.tif", now - 300_000);

        TileProcessingUtilities.cleanupCorruptStitchingOutput(dir.toFile(), new HashSet<>(), now);

        assertThat(a)
                .as("a previous run's output must survive a later stitch failure")
                .exists();
        assertThat(b).exists();
    }

    @Test
    void thisRunsPartialOutputIsStillRemoved(@TempDir Path dir) throws Exception {
        // The cleanup must keep doing its actual job.
        long start = System.currentTimeMillis() - 60_000;
        File previous = write(dir, "previous_run_90.0_007.ome.tif", start - 600_000);
        File partial = write(dir, "partial_from_the_failed_stitch_90.0_008.ome.tif", start + 5_000);

        Set<String> snapshot = new HashSet<>();
        snapshot.add(previous.getName());

        TileProcessingUtilities.cleanupCorruptStitchingOutput(dir.toFile(), snapshot, start);

        assertThat(partial)
                .as("the failed stitch's own partial output must be cleaned up")
                .doesNotExist();
        assertThat(previous).exists();
    }

    @Test
    void bothBoundsAreIndependentlySufficient(@TempDir Path dir) throws Exception {
        // In the snapshot but newer than the start: kept (snapshot alone saves it).
        // Absent from the snapshot but older than the start: kept (timestamp alone saves it).
        long start = System.currentTimeMillis() - 60_000;
        File inSnapshotButNew = write(dir, "in_snapshot_but_new.ome.tif", start + 5_000);
        File notInSnapshotButOld = write(dir, "not_in_snapshot_but_old.ome.tif", start - 5_000);

        Set<String> snapshot = new HashSet<>();
        snapshot.add(inSnapshotButNew.getName());

        TileProcessingUtilities.cleanupCorruptStitchingOutput(dir.toFile(), snapshot, start);

        assertThat(inSnapshotButNew).exists();
        assertThat(notInSnapshotButOld).exists();
    }

    @Test
    void nonStitchedFilesAreNeverTouched(@TempDir Path dir) throws Exception {
        long start = System.currentTimeMillis() - 60_000;
        File sidecar = write(dir, "something.stitch-info.txt", start + 5_000);
        File tiles = write(dir, "TileConfiguration.txt", start + 5_000);

        TileProcessingUtilities.cleanupCorruptStitchingOutput(dir.toFile(), new HashSet<>(), start);

        assertThat(sidecar).exists();
        assertThat(tiles).exists();
    }
}
