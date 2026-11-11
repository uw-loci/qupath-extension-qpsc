package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class TileConfigurationFileTest {

    @Test
    void lastEntryInFileOrder() {
        List<String> lines = List.of(
                "# Define the number of dimensions we are working on",
                "dim = 2",
                "0.tif; ; (10.5, 20.25)",
                "1.tif; ; (110.5, 20.25)",
                "2.tif; ; (110.5, 120.0)",
                "");
        assertArrayEquals(new double[] {110.5, 120.0}, TileConfigurationFile.lastTileCenter(lines));
    }

    @Test
    void nothingParsableGivesNull() {
        assertNull(TileConfigurationFile.lastTileCenter(List.of("dim = 2", "x.tif; ; (a, b)")));
        assertNull(TileConfigurationFile.lastTileCenter(null));
    }
}
