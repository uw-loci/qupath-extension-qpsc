package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpsc.utilities.TilingUtilities.TileGrid;

/** The tile grid for a bounding box, and that the file the server reads follows it. */
class TilingUtilitiesBoundingBoxTest {

    private static final double FRAME = 500.0;
    private static final double OVERLAP = 10.0;

    private static TileGrid grid(double x1, double y1, double x2, double y2) {
        return TilingUtilities.boundingBoxGrid(new BoundingBox(x1, y1, x2, y2), FRAME, FRAME, OVERLAP);
    }

    private static List<String> tileLines(Path dir, double x1, double y1, double x2, double y2) throws IOException {
        TilingRequest request = new TilingRequest.Builder()
                .outputFolder(dir.toString())
                .modalityName("test_1")
                .frameSize(FRAME, FRAME)
                .overlapPercent(OVERLAP)
                .boundingBox(x1, y1, x2, y2)
                .stageInvertedAxes(false, false)
                .createDetections(false)
                .build();
        TilingUtilities.createTiles(request);
        return Files.readAllLines(dir.resolve("bounds").resolve("TileConfiguration.txt")).stream()
                .filter(line -> line.contains(".tif"))
                .toList();
    }

    @Test
    void aTinyBoxIsOneTileCenteredOnIt(@TempDir Path dir) throws IOException {
        TileGrid grid = grid(1000, 2000, 1001, 2001);
        assertEquals(1, grid.cols());
        assertEquals(1, grid.rows());
        assertEquals(1000.5 - FRAME / 2, grid.startX(), 1e-9);
        assertEquals(2000.5 - FRAME / 2, grid.startY(), 1e-9);

        assertEquals(List.of("0.tif; ; (1000.500, 2000.500)"), tileLines(dir, 1000, 2000, 1001, 2001));
    }

    @Test
    void aBoxExactlyOneFrameIsOneTile() {
        TileGrid grid = grid(0, 0, FRAME, FRAME);
        assertEquals(1, grid.cols() * grid.rows());
        assertEquals(0.0, grid.startX(), 1e-9);
    }

    @Test
    void aZeroOverlapFrameSizedBoxIsStillOneTile() {
        TileGrid grid = TilingUtilities.boundingBoxGrid(new BoundingBox(0, 0, FRAME, FRAME), FRAME, FRAME, 0.0);
        assertEquals(1, grid.cols() * grid.rows());
    }

    @Test
    void cornersGivenInEitherOrderGiveTheSameGrid() {
        assertEquals(grid(1000, 2000, 1001, 2001), grid(1001, 2001, 1000, 2000));
    }

    @Test
    void aThinStripIsOneRowCenteredOnIt() {
        TileGrid grid = grid(0, 100, 2000, 110);
        assertEquals(1, grid.rows());
        assertEquals(105 - FRAME / 2, grid.startY(), 1e-9);
        // Unchanged along the long axis: half-frame margin, then ceil(2500 / 450)
        assertEquals(6, grid.cols());
        assertEquals(-FRAME / 2, grid.startX(), 1e-9);
    }

    @Test
    void aMultiTileBoxKeepsItsMargin(@TempDir Path dir) throws IOException {
        TileGrid grid = grid(0, 0, 2000, 1000);
        assertEquals(6, grid.cols());
        assertEquals(4, grid.rows());
        assertEquals(-FRAME / 2, grid.startX(), 1e-9);
        assertEquals(-FRAME / 2, grid.startY(), 1e-9);

        List<String> lines = tileLines(dir, 0, 0, 2000, 1000);
        assertEquals(24, lines.size());
        assertEquals("0.tif; ; (0.000, 0.000)", lines.get(0));
    }
}
