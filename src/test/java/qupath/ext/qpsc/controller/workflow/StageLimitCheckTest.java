package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class StageLimitCheckTest {

    // PPM stage limits (config_PPM.yml, 2026-11-10)
    private static final double[] LIMITS = {-56723, 51571, -50072, 25826};

    @Test
    void theNovember10RegionIsCaughtAtItsFirstOvershootingTile() {
        // Tiles from the log: 739 and 740 were inside, 741 is X -56790.211.
        var tiles = TileConfigurationFile.entries(List.of(
                "739.tif; ; (-56145.729, -25903.350)",
                "740.tif; ; (-56467.970, -25904.067)",
                "741.tif; ; (-56790.211, -25904.784)",
                "742.tif; ; (-56790.211, -26145.000)",
                "743.tif; ; (-56467.970, -26145.000)"));
        var r = StageLimitCheck.check("42606_60590", tiles, LIMITS);
        assertNotNull(r);
        assertEquals(2, r.outside());
        assertEquals("741.tif", r.firstOutside());
        assertEquals("X", r.worstAxis());
        assertEquals(67.2, r.overshootUm(), 0.1);
        assertTrue(r.describe().contains("up to 67 um past X -56723 um"), r.describe());
        assertTrue(r.describe().contains("stop at tile 741.tif"), r.describe());
    }

    @Test
    void aRegionInsideTheLimitsPasses() {
        var tiles = TileConfigurationFile.entries(
                List.of("0.tif; ; (0.0, 0.0)", "1.tif; ; (51571.0, 25826.0)", "2.tif; ; (-56723.0, -50072.0)"));
        assertNull(StageLimitCheck.check("ok", tiles, LIMITS));
    }

    @Test
    void limitsInEitherOrderAndBothAxes() {
        double[] reversed = {51571, -56723, 25826, -50072};
        var tiles = TileConfigurationFile.entries(List.of("0.tif; ; (0.0, 26000.0)"));
        var r = StageLimitCheck.check("y", tiles, reversed);
        assertEquals("Y", r.worstAxis());
        assertEquals(174, r.overshootUm(), 1e-9);
    }
}
