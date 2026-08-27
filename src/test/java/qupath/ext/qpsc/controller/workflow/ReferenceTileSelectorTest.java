package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * {@link ReferenceTileSelector} -- the automatic replacement for an operator clicking
 * reference tiles before multi-tile refinement.
 *
 * <p>The failure this guards against is subtle: a selection that looks fine (three tiles,
 * all inside the annotation) but puts the camera half on background or fits a rotation from
 * three nearly-collinear neighbours. Every test below states the physical consequence it is
 * protecting, not just the arithmetic.
 *
 * <p>All geometry here is synthetic, so this runs with no image, no hierarchy, and no
 * JavaFX toolkit.
 */
class ReferenceTileSelectorTest {

    private static final int TILE = 100;

    /** A tile at grid (row, col) of the given annotation, laid out on a TILE-sized lattice. */
    private static PathObject tile(String annotationName, int row, int col) {
        return tile(annotationName, row, col, col * TILE, row * TILE);
    }

    private static PathObject tile(String annotationName, int row, int col, double x, double y) {
        ROI roi = ROIs.createRectangleROI(x, y, TILE, TILE, ImagePlane.getDefaultPlane());
        PathObject t = PathObjects.createDetectionObject(roi);
        int index = row * 1000 + col;
        t.setName(index + "_" + annotationName);
        t.getMeasurements().put("TileNumber", index);
        t.getMeasurements().put("Row", row);
        t.getMeasurements().put("Column", col);
        return t;
    }

    private static List<PathObject> grid(String annotationName, int rows, int cols) {
        List<PathObject> tiles = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                tiles.add(tile(annotationName, r, c));
            }
        }
        return tiles;
    }

    // ---- interior filter -------------------------------------------------------

    @Test
    void onlyTilesWithACompleteNeighbourRingAreInterior() {
        // 5x5 grid -> the 3x3 core is interior; the 16-tile border is not, because a border
        // tile can have the camera land partly off the tissue.
        List<PathObject> interior = ReferenceTileSelector.interiorTiles(grid("a", 5, 5), null);
        assertEquals(9, interior.size());
        for (PathObject t : interior) {
            int row = t.getMeasurements().get("Row").intValue();
            int col = t.getMeasurements().get("Column").intValue();
            assertTrue(row >= 1 && row <= 3 && col >= 1 && col <= 3, "border tile leaked into the interior set");
        }
    }

    @Test
    void tissueThinnerThanThreeTilesYieldsNothing() {
        // A 2-tile-wide strip has no tile with a full ring. Returning nothing is correct and
        // the caller must fall back to asking a human -- silently picking a border tile is the
        // bug this prevents.
        assertTrue(ReferenceTileSelector.interiorTiles(grid("a", 2, 9), null).isEmpty());
        assertTrue(ReferenceTileSelector.interiorTiles(grid("a", 9, 1), null).isEmpty());
    }

    @Test
    void gridsAreScopedPerAnnotation() {
        // Row/Column restart at 0 for every annotation. If the neighbour test pooled all tiles,
        // annotation "b"'s lone tile would borrow annotation "a"'s ring and be called interior.
        List<PathObject> tiles = new ArrayList<>(grid("a", 3, 3));
        tiles.add(tile("b", 1, 1, 10_000, 10_000));

        List<PathObject> interior = ReferenceTileSelector.interiorTiles(tiles, null);
        assertEquals(1, interior.size(), "only annotation a's centre tile is interior");
        assertEquals("a", ReferenceTileSelector.annotationKey(interior.get(0)));
    }

    @Test
    void aTileWhoseCentroidIsOutsideTheTissueIsRejected() {
        // Tiling keeps edge tiles that merely INTERSECT the annotation, so a full neighbour ring
        // is not on its own proof the tile is on tissue.
        List<PathObject> tiles = grid("a", 5, 5);
        // Tissue covers only the left half of the 5x5 lattice (columns 0-1).
        ROI tissue = ROIs.createRectangleROI(0, 0, 2 * TILE, 5 * TILE, ImagePlane.getDefaultPlane());

        List<PathObject> interior = ReferenceTileSelector.interiorTiles(tiles, Map.of("a", tissue));

        assertFalse(interior.isEmpty());
        for (PathObject t : interior) {
            ROI roi = t.getROI();
            assertTrue(
                    tissue.contains(roi.getCentroidX(), roi.getCentroidY()),
                    "a tile centred outside the tissue survived the filter");
        }
        // Column 1 is the only interior column left inside the tissue.
        assertEquals(3, interior.size());
    }

    @Test
    void tilesWithoutGridMeasurementsAreDroppedNotAssumedInterior() {
        List<PathObject> tiles = new ArrayList<>(grid("a", 5, 5));
        ROI roi = ROIs.createRectangleROI(0, 0, TILE, TILE, ImagePlane.getDefaultPlane());
        PathObject legacy = PathObjects.createDetectionObject(roi);
        legacy.setName("999_a");
        legacy.getMeasurements().put("TileNumber", 999);
        tiles.add(legacy);

        List<PathObject> interior = ReferenceTileSelector.interiorTiles(tiles, null);
        assertEquals(9, interior.size(), "an untestable tile must not be treated as interior");
        assertFalse(interior.contains(legacy));
    }

    // ---- rank + spread ---------------------------------------------------------

    @Test
    void theBestScoringTileSeedsTheSelection() {
        List<PathObject> tiles = grid("a", 7, 7);
        PathObject favourite = tiles.get(24);
        ToDoubleFunction<PathObject> scorer = t -> (t == favourite) ? 100.0 : 1.0;

        List<PathObject> chosen = ReferenceTileSelector.rankAndSpread(tiles, scorer, 3);
        assertEquals(3, chosen.size());
        assertSame(favourite, chosen.get(0), "the most textured tile should anchor the set");
    }

    @Test
    void chosenTilesAreSpreadOutNotClustered() {
        // Three tiles clustered together fit a rotation about as badly as one. With a flat
        // score the spread step is the only thing keeping them apart.
        List<PathObject> tiles = grid("a", 9, 9);
        List<PathObject> chosen = ReferenceTileSelector.rankAndSpread(tiles, t -> 1.0, 3);

        assertEquals(3, chosen.size());
        double minSeparation = Double.MAX_VALUE;
        for (int i = 0; i < chosen.size(); i++) {
            for (int j = i + 1; j < chosen.size(); j++) {
                double dx = chosen.get(i).getROI().getCentroidX()
                        - chosen.get(j).getROI().getCentroidX();
                double dy = chosen.get(i).getROI().getCentroidY()
                        - chosen.get(j).getROI().getCentroidY();
                minSeparation = Math.min(minSeparation, Math.hypot(dx, dy));
            }
        }
        assertTrue(minSeparation > 3 * TILE, "tiles are clustered; separation was only " + minSeparation);
    }

    @Test
    void aSmallTissueReturnsFewerTilesRatherThanRepeatingOne() {
        // Two distinct tiles still give a valid similarity solve. Padding to K by repeating a
        // tile would silently produce a degenerate fit.
        List<PathObject> two = List.of(tile("a", 1, 1), tile("a", 5, 5));
        List<PathObject> chosen = ReferenceTileSelector.rankAndSpread(two, t -> 1.0, 3);
        assertEquals(2, chosen.size());
        assertNotSame(chosen.get(0), chosen.get(1));
    }

    @Test
    void noCandidatesYieldsAnEmptySelection() {
        assertTrue(ReferenceTileSelector.rankAndSpread(List.of(), t -> 1.0, 3).isEmpty());
        assertTrue(ReferenceTileSelector.rankAndSpread(null, t -> 1.0, 3).isEmpty());
        assertTrue(ReferenceTileSelector.rankAndSpread(List.of(tile("a", 0, 0)), t -> 1.0, 0)
                .isEmpty());
    }

    // ---- end-to-end without an image server ------------------------------------

    @Test
    void selectDegradesToInteriorPlusSpreadWithNoServer() {
        List<PathObject> chosen = ReferenceTileSelector.select(grid("a", 9, 9), null, null, 3);
        assertEquals(3, chosen.size());
        for (PathObject t : chosen) {
            int row = t.getMeasurements().get("Row").intValue();
            int col = t.getMeasurements().get("Column").intValue();
            assertTrue(row >= 1 && row <= 7 && col >= 1 && col <= 7);
        }
    }

    @Test
    void selectOnTissueTooThinReturnsEmptySoTheCallerCanFallBack() {
        assertTrue(ReferenceTileSelector.select(grid("a", 2, 6), null, null, 3).isEmpty());
    }

    // ---- texture metric --------------------------------------------------------

    @Test
    void aFlatRegionScoresZeroAndStructureScoresHigher() {
        BufferedImage flat = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                flat.setRGB(x, y, 0x808080);
            }
        }
        assertEquals(0.0, ReferenceTileSelector.gradientStd(flat), 1e-9);

        // Stripes: strong edges in some places, none in others -- a wide spread of gradient
        // magnitudes, which is what "has structure SIFT can key on" looks like.
        BufferedImage stripes = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                stripes.setRGB(x, y, ((x / 8) % 2 == 0) ? 0x000000 : 0xFFFFFF);
            }
        }
        assertTrue(ReferenceTileSelector.gradientStd(stripes) > 1.0);
    }

    @Test
    void aUniformGradientRampScoresBelowRealStructure() {
        // A smooth ramp has a high MEAN gradient but almost no spread. Ranking by mean would
        // rate it as interesting; standard deviation correctly does not.
        BufferedImage ramp = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int v = (x * 4) & 0xFF;
                ramp.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        BufferedImage stripes = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                stripes.setRGB(x, y, ((x / 8) % 2 == 0) ? 0x000000 : 0xFFFFFF);
            }
        }
        assertTrue(
                ReferenceTileSelector.gradientStd(ramp) < ReferenceTileSelector.gradientStd(stripes),
                "a smooth ramp must not outrank genuine structure");
    }

    @Test
    void degenerateImagesScoreZeroRatherThanThrowing() {
        assertEquals(0.0, ReferenceTileSelector.gradientStd(null), 1e-9);
        assertEquals(0.0, ReferenceTileSelector.gradientStd(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)), 1e-9);
    }

    // ---- name parsing ----------------------------------------------------------

    @Test
    void annotationKeySplitsOnTheFirstUnderscoreOnly() {
        // Annotation names are themselves "<x>_<y>", so a greedy split would shred them.
        PathObject t = tile("42208_34931", 1, 1);
        assertEquals("42208_34931", ReferenceTileSelector.annotationKey(t));
    }

    @Test
    void boundingBoxTilesWithNoAnnotationSuffixShareOneGroup() {
        ROI roi = ROIs.createRectangleROI(0, 0, TILE, TILE, ImagePlane.getDefaultPlane());
        PathObject t = PathObjects.createDetectionObject(roi);
        t.setName("7");
        assertEquals("", ReferenceTileSelector.annotationKey(t));
    }

    @Test
    void autoPickReturnsNothingWhenNoBatchIsArmed() {
        // The gate every "pick a tile" site consults. Outside an automatic batch it must return
        // empty so the operator gets the selection dialog they expect.
        qupath.ext.qpsc.ui.AutoAdvanceController.disarmSession();
        assertTrue(ReferenceTileSelector.autoPickIfArmed(null, null, 3).isEmpty());
    }

    @Test
    void autoPickReturnsNothingWhenTheOperatorHasTakenOverTheSlide() {
        qupath.ext.qpsc.ui.AutoAdvanceController.armSession(
                qupath.ext.qpsc.preferences.MultiSlideAcquisitionMode.FULLY_AUTOMATIC, 5);
        try {
            qupath.ext.qpsc.ui.AutoAdvanceController.overrideCurrentSlide("operator took over");
            assertTrue(ReferenceTileSelector.autoPickIfArmed(null, null, 3).isEmpty());
        } finally {
            qupath.ext.qpsc.ui.AutoAdvanceController.disarmSession();
        }
    }

    @Test
    void autoPickReturnsNothingWithNoOpenImage() {
        qupath.ext.qpsc.ui.AutoAdvanceController.armSession(
                qupath.ext.qpsc.preferences.MultiSlideAcquisitionMode.FULLY_AUTOMATIC, 5);
        try {
            assertTrue(
                    ReferenceTileSelector.autoPickIfArmed(null, null, 3).isEmpty(),
                    "no GUI / no open image must fall back to asking, not throw");
        } finally {
            qupath.ext.qpsc.ui.AutoAdvanceController.disarmSession();
        }
    }
}
