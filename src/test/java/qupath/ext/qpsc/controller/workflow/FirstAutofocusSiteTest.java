package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FirstAutofocusSiteTest {

    private static List<FirstAutofocusSite.Site> grid(int cols, int rows, double step) {
        List<FirstAutofocusSite.Site> sites = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                sites.add(new FirstAutofocusSite.Site(sites.size(), c * step, r * step));
            }
        }
        return sites;
    }

    @Test
    void theTissueScoredTileWins() {
        // 2026-11-06: QPSC sent tile 0, the server focused there, and the hint had been taken
        // at the centroid 10 mm away.
        List<FirstAutofocusSite.Site> sites = grid(10, 10, 1000);
        assertEquals(0, FirstAutofocusSite.of(sites, 0, 1400, 1000).index());
        assertEquals(57, FirstAutofocusSite.of(sites, 57, 1400, 1000).index());
    }

    @Test
    void anUnknownPreferredTileFallsBackToTheServersRule() {
        List<FirstAutofocusSite.Site> sites = grid(10, 10, 1000);
        // One diagonal (1720 px) in from tile 0 toward the center lands nearest tile 11 (1000, 1000).
        assertEquals(11, FirstAutofocusSite.of(sites, 999, 1400, 1000).index());
        assertEquals(11, FirstAutofocusSite.of(sites, -1, 1400, 1000).index());
    }

    @Test
    void aSmallGridStartsAtItsFirstTile() {
        assertEquals(0, FirstAutofocusSite.of(grid(3, 3, 1000), -1, 1400, 1000).index());
    }

    @Test
    void noSitesGivesNull() {
        assertNull(FirstAutofocusSite.of(List.of(), 3, 1400, 1000));
        assertNull(FirstAutofocusSite.of(null, 3, 1400, 1000));
    }
}
