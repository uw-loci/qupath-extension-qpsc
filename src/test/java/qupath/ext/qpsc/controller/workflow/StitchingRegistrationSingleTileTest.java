package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpsc.stitching.TileRegistrationSupport;

/**
 * A one-tile region has nothing to register, and a target must not be handed an Apply mode for
 * a solution that was never written. On 2026-11-19 a one-tile LC-PolScope bounded acquisition
 * ran a solve that could do nothing, then each of its 8 targets logged that
 * TileRegistration.txt was missing before stitching at nominal anyway.
 */
class StitchingRegistrationSingleTileTest {

    private static void tileConfig(Path dir, int tiles) throws Exception {
        StringBuilder sb = new StringBuilder("dim = 2\n");
        for (int i = 0; i < tiles; i++) {
            sb.append("tile_").append(i).append(".tif; ; (").append(i * 100).append(", 0)\n");
        }
        Files.writeString(dir.resolve("TileConfiguration.txt"), sb.toString());
    }

    @Test
    void oneTileIsSingle(@TempDir Path dir) throws Exception {
        tileConfig(dir, 1);
        assertTrue(StitchingRegistration.singleTile(dir));
    }

    @Test
    void twoTilesAreNot(@TempDir Path dir) throws Exception {
        tileConfig(dir, 2);
        assertFalse(StitchingRegistration.singleTile(dir));
    }

    @Test
    void anUnreadableGridIsNotTreatedAsSingle(@TempDir Path dir) {
        // No tile configuration: registration runs as before rather than being skipped on a guess.
        assertFalse(StitchingRegistration.singleTile(dir));
    }

    @Test
    void noSolutionFileGivesNoApplyMode(@TempDir Path dir) {
        assertNull(StitchingRegistration.existingSolutionMode(dir));
    }

    @Test
    void aSolutionFileGivesAnApplyMode(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(TileRegistrationSupport.solutionFileName()), "");
        assertNotNull(StitchingRegistration.existingSolutionMode(dir));
    }
}
