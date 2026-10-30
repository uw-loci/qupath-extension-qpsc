package qupath.ext.qpsc.utilities;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Cleaning up tiles must not remove the backgrounds a run depends on. */
class ProtectedFolderTest {

    @TempDir
    Path modeDir;

    private Path tile;
    private Path keptTif;
    private Path keptJson;

    private static Path write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "data");
    }

    @BeforeEach
    void acquisition() throws IOException {
        Path region = modeDir.resolve("35633_24238");
        tile = write(region.resolve("State0/1.tif"));
        keptTif = write(region.resolve("_protected/background/State0.tif"));
        keptJson = write(region.resolve("_protected/background/background_provenance.json"));
    }

    @Test
    void deletingTilesLeavesTheProtectedFolder() {
        TileProcessingUtilities.deleteTilesAndFolder(modeDir.toString());

        assertThat(tile).doesNotExist();
        assertThat(tile.getParent()).doesNotExist();
        assertThat(keptTif).exists();
        assertThat(keptJson).exists();
    }

    @Test
    void theNameMatchesInAnyCase() throws IOException {
        Path upper = write(modeDir.resolve("other/_PROTECTED/State1.tif"));

        TileProcessingUtilities.deleteTilesAndFolder(modeDir.toString());

        assertThat(upper).exists();
    }

    @Test
    void aProtectedNameAboveTheCleanedFolderProtectsNothing() throws IOException {
        Path root = modeDir.resolve("_protected/run");
        Path inside = write(root.resolve("State0/1.tif"));

        TileProcessingUtilities.deleteTilesAndFolder(root.toString());

        assertThat(inside).doesNotExist();
    }
}
