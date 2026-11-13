package qupath.ext.qpsc.acquisition;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The disk-space estimate counts every image a tile position writes, not just its angles. */
class AcquisitionSpaceCheckFilesTest {

    @Test
    void anglesAloneWhenThereIsNoStackOrTimeLapse() {
        assertEquals(3, AcquisitionSpaceCheck.filesPerTile(3, 1, 1, true, true));
    }

    @Test
    void aTimeLapseMultipliesByTimepointsAndAddsItsProjection() {
        // PPM, 3 angles, 4 timepoints, mean projection: the 2026-11-13 whole-slide run that the
        // old estimate counted as 3 images per tile.
        assertEquals(3 * 4 + 3, AcquisitionSpaceCheck.filesPerTile(3, 1, 4, true, true));
        assertEquals(3 * 4, AcquisitionSpaceCheck.filesPerTile(3, 1, 4, false, false));
    }

    @Test
    void aZStackMultipliesByPlanesAndAddsOneProjectedSetPerTimepoint() {
        assertEquals(2 * 5 * 3 + 2 * 3, AcquisitionSpaceCheck.filesPerTile(2, 5, 3, true, false));
        assertEquals(2 * 5, AcquisitionSpaceCheck.filesPerTile(2, 5, 1, false, false));
    }

    @Test
    void zeroOrNegativeCountsReadAsOne() {
        assertEquals(1, AcquisitionSpaceCheck.filesPerTile(0, 0, 0, true, true));
    }
}
