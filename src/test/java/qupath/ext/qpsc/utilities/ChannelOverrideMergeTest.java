package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import qupath.ext.qpsc.modality.Channel;
import qupath.ext.qpsc.modality.PropertyWrite;

/**
 * Tests for channel override merging in {@link MicroscopeConfigManager#getChannelsForProfile}.
 *
 * <p>Locks in the two critical behaviors that the BF_IF (and in general any
 * channel-based multi-profile) schema depends on:
 * <ol>
 *   <li>Profile-level {@code channel_overrides.<id>.exposure_ms} replaces the
 *       channel library's default exposure.</li>
 *   <li>Profile-level {@code channel_overrides.<id>.device_properties} merges
 *       into the library's device_properties list: match by (device, property)
 *       replaces the value in place (preserving order), no-match appends.</li>
 * </ol>
 *
 * <p>Without these tests the next refactor could silently drop the override
 * semantics and the OWS3 BF_IF_10x profile would go back to using the default
 * 500 lamp intensity (overexposing 10x BF tiles).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ChannelOverrideMergeTest {

    private MicroscopeConfigManager configManager;
    private Path tempDir;

    @BeforeAll
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("qpsc_channel_override_test_");
        Path configPath = tempDir.resolve("config_Test.yml");
        Path resourcesDir = tempDir.resolve("resources");
        Files.createDirectories(resourcesDir);
        Files.writeString(resourcesDir.resolve("resources_LOCI.yml"), "# empty test resources\n");
        Files.writeString(configPath, TEST_CONFIG);
        configManager = MicroscopeConfigManager.getInstance(configPath.toString());
        // Ensure we see this test's config, not a cached one from a sibling test.
        configManager.reload(configPath.toString());
    }

    @Test
    public void testLibraryReturnedUnchangedWhenProfileHasNoOverrides() {
        List<Channel> channels = configManager.getChannelsForProfile("BF_IF_20x");
        assertEquals(2, channels.size(), "BF_IF_20x should resolve to 2 channels");
        assertEquals("BF", channels.get(0).id());
        assertEquals("DAPI", channels.get(1).id());
        assertEquals(
                500.0,
                findPropertyValue(channels.get(0).properties(), "DiaLamp", "Intensity"),
                0.0001,
                "BF at 20x should keep the library default DiaLamp intensity (no override)");
        assertEquals(10.0, channels.get(0).defaultExposureMs(), 0.0001);
    }

    @Test
    public void testExposureOverrideIsApplied() {
        List<Channel> channels = configManager.getChannelsForProfile("BF_IF_10x");
        Channel bf =
                channels.stream().filter(c -> c.id().equals("BF")).findFirst().orElseThrow();
        assertEquals(20.0, bf.defaultExposureMs(), 0.0001, "BF exposure should be overridden to 20 ms for 10x");
    }

    @Test
    public void testDevicePropertyOverrideReplacesExistingEntry() {
        List<Channel> channels = configManager.getChannelsForProfile("BF_IF_10x");
        Channel bf =
                channels.stream().filter(c -> c.id().equals("BF")).findFirst().orElseThrow();

        // The (DiaLamp, Intensity) pair exists in the library at value 500.
        // The 10x override specifies value 70, which must replace it in place.
        assertEquals(
                70.0,
                findPropertyValue(bf.properties(), "DiaLamp", "Intensity"),
                0.0001,
                "BF_IF_10x DiaLamp intensity should be overridden to 70");

        // The other library properties should still be present, same order.
        assertEquals(2, bf.properties().size(), "BF channel should still have 2 properties after override");
        assertEquals(
                "DiaLamp",
                bf.properties().get(0).device(),
                "DiaLamp Intensity should still be the first entry (replaced in place, not appended)");
        assertEquals("Intensity", bf.properties().get(0).property());
        assertEquals("70", bf.properties().get(0).value());
    }

    @Test
    public void testDevicePropertyOverrideAppendsNewEntry() {
        // BF_IF_40x adds a NEW (device, property) pair that doesn't exist in the library.
        // The merge must append it to the end of the channel's property list.
        List<Channel> channels = configManager.getChannelsForProfile("BF_IF_40x");
        Channel bf =
                channels.stream().filter(c -> c.id().equals("BF")).findFirst().orElseThrow();

        assertEquals(3, bf.properties().size(), "BF should have library's 2 properties plus 1 appended override");
        // Library entries preserved
        assertEquals(500.0, findPropertyValue(bf.properties(), "DiaLamp", "Intensity"), 0.0001);
        // New appended entry
        assertEquals("ND", bf.properties().get(bf.properties().size() - 1).device());
        assertEquals("Position", bf.properties().get(bf.properties().size() - 1).property());
        assertEquals("2", bf.properties().get(bf.properties().size() - 1).value());
    }

    @Test
    public void testOverridesOnlyAffectNamedChannels() {
        // BF_IF_10x only overrides BF. The DAPI channel should be returned unmodified.
        List<Channel> channels = configManager.getChannelsForProfile("BF_IF_10x");
        Channel dapi =
                channels.stream().filter(c -> c.id().equals("DAPI")).findFirst().orElseThrow();
        assertEquals(100.0, dapi.defaultExposureMs(), 0.0001);
        assertEquals(1, dapi.properties().size());
        assertEquals("DLED", dapi.properties().get(0).device());
        assertEquals("Intensity-385nm", dapi.properties().get(0).property());
        assertEquals("25", dapi.properties().get(0).value());
    }

    // ----- helpers -----

    private static double findPropertyValue(List<PropertyWrite> props, String device, String property) {
        for (PropertyWrite p : props) {
            if (p.device().equals(device) && p.property().equals(property)) {
                try {
                    return Double.parseDouble(p.value());
                } catch (NumberFormatException e) {
                    fail("Property value not numeric: " + p.value());
                }
            }
        }
        fail("Property " + device + "." + property + " not found in channel properties: " + props);
        return Double.NaN;
    }

    // Minimal self-contained test config. Keeps the BF channel library small
    // (one DAPI channel as a control) so the assertions can be precise.
    private static final String TEST_CONFIG =
            """
microscope:
  name: TestScope
  type: Test
modalities:
  TestBfIf:
    type: bf_if
    channels:
      - id: BF
        display_name: Brightfield
        exposure_ms: 10
        mm_setup_presets:
          - { group: LightPath, preset: BF }
        device_properties:
          - { device: DiaLamp, property: Intensity, value: 500 }
          - { device: DLED, property: Intensity-385nm, value: 0 }
      - id: DAPI
        display_name: DAPI
        exposure_ms: 100
        mm_setup_presets:
          - { group: LightPath, preset: Epi }
        device_properties:
          - { device: DLED, property: Intensity-385nm, value: 25 }
hardware:
  objectives:
    - id: 10x
      pixel_size_xy_um:
        TEST_DETECTOR: 0.65
    - id: 20x
      pixel_size_xy_um:
        TEST_DETECTOR: 0.32
    - id: 40x
      pixel_size_xy_um:
        TEST_DETECTOR: 0.16
  detectors:
    - TEST_DETECTOR
id_detector:
  TEST_DETECTOR:
    name: Test Camera
    camera_type: generic
    device: TestCam
    width_px: 2048
    height_px: 2048
stage:
  stage_id: TEST_STAGE
  limits:
    x_um: {low: -10000, high: 10000}
    y_um: {low: -10000, high: 10000}
    z_um: {low: 0, high: 1000}
slide_size_um:
  x: 25000
  y: 15000
acquisition_profiles:
  # No channel_overrides at all: library returned verbatim.
  BF_IF_20x:
    modality: TestBfIf
    detector: TEST_DETECTOR
    channels: [BF, DAPI]
  # exposure_ms + device_properties overrides, with device_properties REPLACING
  # an existing (DiaLamp, Intensity) library entry.
  BF_IF_10x:
    modality: TestBfIf
    detector: TEST_DETECTOR
    channels: [BF, DAPI]
    channel_overrides:
      BF:
        exposure_ms: 20
        device_properties:
          - { device: DiaLamp, property: Intensity, value: 70 }
  # device_properties override with APPEND semantics (no matching library entry).
  BF_IF_40x:
    modality: TestBfIf
    detector: TEST_DETECTOR
    channels: [BF, DAPI]
    channel_overrides:
      BF:
        device_properties:
          - { device: ND, property: Position, value: 2 }
""";
}
