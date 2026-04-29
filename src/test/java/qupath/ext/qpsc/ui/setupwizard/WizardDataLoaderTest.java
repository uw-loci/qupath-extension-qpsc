package qupath.ext.qpsc.ui.setupwizard;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Round-trip test: a fully-populated {@link WizardData} survives a
 * {@link ConfigFileWriter#writeAll} -> {@link WizardDataLoader#loadFromExistingConfigs}
 * cycle with all fields preserved.
 *
 * <p>This catches drift between the writer (mapping WizardData -> YAML)
 * and the loader (mapping YAML -> WizardData) -- the most likely
 * failure mode after schema changes. If a new WizardData field is
 * written but not loaded (or vice-versa), this test fails.
 */
class WizardDataLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Round-trip preserves all populated WizardData fields (PPM-shaped fixture)")
    void roundTripPreservesAllFields() throws IOException {
        WizardData out = makeFullyPopulatedWizardData(tempDir, "RoundTripScope");

        ConfigFileWriter.writeAll(out);

        WizardData in = new WizardData();
        boolean loaded = WizardDataLoader.loadFromExistingConfigs(
                tempDir, out.microscopeName, in);
        assertTrue(loaded, "Loader should report success on a freshly-written config");

        // Identity / paths
        assertEquals(out.microscopeName, in.microscopeName);
        assertEquals(out.microscopeType, in.microscopeType);
        assertEquals(out.configDirectory, in.configDirectory);

        // Stage limits
        assertEquals(out.stageId, in.stageId);
        assertEquals(out.zStageDevice, in.zStageDevice);
        assertEquals(out.stageLimitXLow, in.stageLimitXLow, 0.001);
        assertEquals(out.stageLimitXHigh, in.stageLimitXHigh, 0.001);
        assertEquals(out.stageLimitYLow, in.stageLimitYLow, 0.001);
        assertEquals(out.stageLimitYHigh, in.stageLimitYHigh, 0.001);
        assertEquals(out.stageLimitZLow, in.stageLimitZLow, 0.001);
        assertEquals(out.stageLimitZHigh, in.stageLimitZHigh, 0.001);

        // Streaming-AF probe results
        assertEquals(out.streamingAfEnabled, in.streamingAfEnabled);
        assertEquals(out.streamingAfSpeedProperty, in.streamingAfSpeedProperty);
        assertEquals(out.streamingAfSlowSpeedValue, in.streamingAfSlowSpeedValue);
        assertEquals(out.streamingAfSlowSpeedUmPerS, in.streamingAfSlowSpeedUmPerS, 0.001);
        assertEquals(out.streamingAfNormalSpeedValue, in.streamingAfNormalSpeedValue);

        // Slide
        assertEquals(out.slideSizeX, in.slideSizeX);
        assertEquals(out.slideSizeY, in.slideSizeY);

        // Objectives + pixel sizes (id round-trip + each combo)
        assertEquals(out.objectives.size(), in.objectives.size());
        for (int i = 0; i < out.objectives.size(); i++) {
            assertEquals(out.objectives.get(i).get("id"),
                    in.objectives.get(i).get("id"));
        }
        assertEquals(out.pixelSizes.size(), in.pixelSizes.size());
        for (Map.Entry<String, Double> e : out.pixelSizes.entrySet()) {
            assertEquals(e.getValue(), in.pixelSizes.get(e.getKey()), 1e-6,
                    "pixelSizes mismatch for " + e.getKey());
        }

        // Detectors (id + key fields)
        assertEquals(out.detectors.size(), in.detectors.size());
        for (int i = 0; i < out.detectors.size(); i++) {
            Map<String, Object> a = out.detectors.get(i);
            Map<String, Object> b = in.detectors.get(i);
            assertEquals(a.get("id"), b.get("id"));
            assertEquals(a.get("name"), b.get("name"));
            assertEquals(a.get("camera_type"), b.get("camera_type"));
            assertEquals(a.get("width_px"), b.get("width_px"));
            assertEquals(a.get("height_px"), b.get("height_px"));
        }

        // Modalities
        assertEquals(out.modalities.size(), in.modalities.size());
        for (int i = 0; i < out.modalities.size(); i++) {
            Map<String, Object> a = out.modalities.get(i);
            Map<String, Object> b = in.modalities.get(i);
            assertEquals(a.get("name"), b.get("name"));
            assertEquals(a.get("type"), b.get("type"));
        }
    }

    @Test
    @DisplayName("Loader returns false when no main config exists yet (fresh install)")
    void loaderNoOpsWhenConfigMissing() {
        WizardData target = new WizardData();
        boolean loaded = WizardDataLoader.loadFromExistingConfigs(
                tempDir, "NeverExisted", target);
        assertFalse(loaded, "Loader should report no-op when config_<name>.yml is missing");
        // Constructor defaults preserved.
        assertEquals(-36000, target.stageLimitXLow, 0.001);
        assertNull(target.streamingAfEnabled);
    }

    @Test
    @DisplayName("Streaming-AF block round-trips with disabled+null speed_property (OWS3 shape)")
    void streamingAfRoundTripDisabled() throws IOException {
        WizardData out = makeFullyPopulatedWizardData(tempDir, "OWS3Shape");
        out.streamingAfEnabled = false;
        out.streamingAfSpeedProperty = "Speed";
        out.streamingAfSlowSpeedValue = "0.50mm/sec";
        out.streamingAfSlowSpeedUmPerS = 500.0;
        out.streamingAfNormalSpeedValue = "2.50mm/sec";

        ConfigFileWriter.writeAll(out);

        WizardData in = new WizardData();
        WizardDataLoader.loadFromExistingConfigs(tempDir, out.microscopeName, in);

        assertEquals(Boolean.FALSE, in.streamingAfEnabled);
        assertEquals("Speed", in.streamingAfSpeedProperty);
        assertEquals("0.50mm/sec", in.streamingAfSlowSpeedValue);
        assertEquals(500.0, in.streamingAfSlowSpeedUmPerS, 0.001);
        assertEquals("2.50mm/sec", in.streamingAfNormalSpeedValue);
    }

    // ----- helpers -----

    private static WizardData makeFullyPopulatedWizardData(Path dir, String name) {
        WizardData d = new WizardData();
        d.configDirectory = dir;
        d.microscopeName = name;
        d.microscopeType = "UprightBrightfield";

        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("id", "LOCI_OBJECTIVE_TEST_10X_001");
        d.objectives.add(obj);

        Map<String, Object> det = new LinkedHashMap<>();
        det.put("id", "LOCI_DETECTOR_TEST_001");
        det.put("name", "TestCamera");
        det.put("camera_type", "generic");
        det.put("device", "TestCamera");
        det.put("width_px", 2048);
        det.put("height_px", 2048);
        d.detectors.add(det);

        d.pixelSizes.put("LOCI_OBJECTIVE_TEST_10X_001::LOCI_DETECTOR_TEST_001", 0.65);

        d.stageId = "LOCI_STAGE_TEST_001";
        d.zStageDevice = "ZDrive";
        d.stageLimitXLow = -10000;
        d.stageLimitXHigh = 10000;
        d.stageLimitYLow = -10000;
        d.stageLimitYHigh = 10000;
        d.stageLimitZLow = 0;
        d.stageLimitZHigh = 2400;

        // Default streaming-AF (PPM-shaped)
        d.streamingAfEnabled = true;
        d.streamingAfSpeedProperty = "MaxSpeed";
        d.streamingAfSlowSpeedValue = "1";
        d.streamingAfSlowSpeedUmPerS = 11.5;
        d.streamingAfNormalSpeedValue = "100";

        Map<String, Object> mod = new LinkedHashMap<>();
        mod.put("name", "brightfield");
        mod.put("type", "brightfield");
        d.modalities.add(mod);

        d.slideSizeX = 40000;
        d.slideSizeY = 20000;

        return d;
    }
}
