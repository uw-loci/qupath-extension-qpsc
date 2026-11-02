package qupath.ext.qpsc.ui.acquisition;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpsc.config.MicroscopeConfigManager;

/**
 * The two acquisition dialogs' Z range limit and per-tile image count.
 *
 * <p>The Z range maximum is the stage's Z travel from {@code stage.limits.z_um}; before, it was
 * 2000 um in one dialog and 200 um in the other, with one shared preference between them.
 */
class AcquisitionDialogLimitsTest {

    @TempDir
    Path tempDir;

    private MicroscopeConfigManager managerWith(String stage) throws IOException {
        Path config = tempDir.resolve("config_Test.yml");
        Files.writeString(config, "microscope:\n  name: TestScope\nstage:\n" + stage);
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(config.toString());
        mgr.reload(config.toString());
        return mgr;
    }

    @Test
    void zRangeMaximumIsTheConfiguredZTravel() throws IOException {
        MicroscopeConfigManager mgr = managerWith("  limits:\n    z_um: {low: -10700, high: 100}\n");
        assertEquals(10800.0, mgr.getStageZTravelUm(), 1e-9);
        assertEquals(10800.0, AcquisitionDialogBuilder.zRangeMaxUm(mgr), 1e-9);
    }

    @Test
    void zTravelIgnoresTheOrderOfLowAndHigh() throws IOException {
        // low/high are numeric labels; a config may list them either way round.
        MicroscopeConfigManager mgr = managerWith("  limits:\n    z_um: {low: 500, high: -500}\n");
        assertEquals(1000.0, AcquisitionDialogBuilder.zRangeMaxUm(mgr), 1e-9);
    }

    @Test
    void missingZLimitsFallBackToTheUnconfiguredMaximum() throws IOException {
        MicroscopeConfigManager mgr = managerWith("  limits:\n    z_um: {low: -500}\n");
        assertNull(mgr.getStageZTravelUm());
        assertEquals(
                AcquisitionDialogBuilder.Z_RANGE_MAX_UNCONFIGURED_UM, AcquisitionDialogBuilder.zRangeMaxUm(mgr), 1e-9);
        assertEquals(
                AcquisitionDialogBuilder.Z_RANGE_MAX_UNCONFIGURED_UM, AcquisitionDialogBuilder.zRangeMaxUm(null), 1e-9);
    }

    @Test
    void imagesPerTileCountsTheTickedChannelsNotTheConfiguredOnes() {
        Map<String, Double> ticked = new LinkedHashMap<>();
        ticked.put("DAPI", 50.0);
        ticked.put("FITC", 100.0);
        assertEquals(2, AcquisitionDialogBuilder.imagesPerTile(ticked, 5));
    }

    @Test
    void imagesPerTileUsesTheConfiguredCountWithoutASelection() {
        // null: the override is off, so every configured channel is acquired.
        assertEquals(5, AcquisitionDialogBuilder.imagesPerTile(null, 5));
        // empty: nothing ticked, which Start refuses; the estimate shows the configured set.
        assertEquals(5, AcquisitionDialogBuilder.imagesPerTile(Map.of(), 5));
        assertEquals(1, AcquisitionDialogBuilder.imagesPerTile(null, 0));
    }
}
