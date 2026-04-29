package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for {@link MicroscopeConfigManager#findBackgroundExposures}.
 *
 * <p>These tests exercise the tiered lookup policy that protects against
 * objective-ID renames and modality-suffix drift while still hard-rejecting
 * cross-detector and cross-modality reuse. Each test writes a real on-disk
 * pair of {@code config_*.yml} + {@code imageprocessing_*.yml} fixtures and
 * loads them through the singleton (no mocks).
 *
 * <p>The minimal main-config skeleton just needs to satisfy the YAML loader
 * so the imageprocessing file is found alongside it; the matching logic
 * itself only reads {@code calibration_targets.background_exposures}.
 */
class BackgroundExposureLookupTest {

    @TempDir
    Path tempDir;

    private MicroscopeConfigManager mgr;

    private static final String MINIMAL_MAIN_CONFIG = """
            microscope:
              name: 'TestMicroscope'
              type: 'TestSystem'
            modalities:
              Brightfield:
                type: 'brightfield'
            hardware:
              objectives:
                - id: 'OBJ_DUMMY'
              detectors:
                - 'DET_DUMMY'
            stage:
              stage_id: 'S'
              limits:
                x_um: { low: -1, high: 1 }
                y_um: { low: -1, high: 1 }
                z_um: { low: -1, high: 1 }
            slide_size_um: { x: 1, y: 1 }
            """;

    @AfterEach
    void teardown() {
        mgr = null;
    }

    private void writeFixture(String imgprocYaml) throws IOException {
        Path mainConfig = tempDir.resolve("config_Test.yml");
        Path imgproc = tempDir.resolve("imageprocessing_Test.yml");
        Path resourcesDir = tempDir.resolve("resources");
        Files.createDirectories(resourcesDir);
        Files.writeString(resourcesDir.resolve("resources_LOCI.yml"), "id_detector: {}\n");
        Files.writeString(mainConfig, MINIMAL_MAIN_CONFIG);
        Files.writeString(imgproc, imgprocYaml);
        mgr = MicroscopeConfigManager.getInstance(mainConfig.toString());
        mgr.reload(mainConfig.toString());
    }

    @Test
    void exactTripleMatchReturnsExposureAtTierExact() throws IOException {
        writeFixture("""
                calibration_targets:
                  background_exposures:
                    modality: Brightfield_10x
                    objective: 0.5NA_AIR_10x
                    detector: HAMAMATSU_DCAM_01
                    angles:
                      angle_0:
                        angle_degrees: 0.0
                        exposure_ms: 41.36
                """);
        BackgroundExposureMatch match = mgr.findBackgroundExposures(
                "Brightfield_10x", "0.5NA_AIR_10x", "HAMAMATSU_DCAM_01");
        assertNotNull(match, "exact match must succeed");
        assertEquals(BackgroundExposureMatch.Tier.EXACT, match.tier());
        assertEquals(41.36, match.exposures().get(0.0));
    }

    @Test
    void modalityFamilyFallbackMatches() throws IOException {
        // Stored in family form (Brightfield), requested with magnification (Brightfield_10x).
        // Same objective + detector. Must match at FAMILY tier.
        writeFixture("""
                calibration_targets:
                  background_exposures:
                    modality: Brightfield
                    objective: 0.5NA_AIR_10x
                    detector: HAMAMATSU_DCAM_01
                    angles:
                      angle_0:
                        angle_degrees: 0.0
                        exposure_ms: 41.36
                """);
        BackgroundExposureMatch match = mgr.findBackgroundExposures(
                "Brightfield_10x", "0.5NA_AIR_10x", "HAMAMATSU_DCAM_01");
        assertNotNull(match);
        // Family stripping makes both keys identical; this is EXACT under the
        // canonical key, which is the desired property.
        assertTrue(match.tier() == BackgroundExposureMatch.Tier.EXACT
                || match.tier() == BackgroundExposureMatch.Tier.FAMILY);
        assertEquals(41.36, match.exposures().get(0.0));
        assertFalse(match.isObjectiveDrift());
    }

    @Test
    void objectiveRenamedSameMagnificationMatchesAtMagnificationTier() throws IOException {
        // The actual reported bug: BG was captured for 0.5NA_AIR_10x, but the
        // user's wizard now selects 0.75NA_AIR_10x. Both have magnification 10x.
        // Under the old strict-match policy this returned null and BF fell
        // back to a stale 2 ms preference. The new policy reuses the calibrated
        // 41.36 ms exposure and flags it as MAGNIFICATION-tier (objective drift).
        writeFixture("""
                calibration_targets:
                  background_exposures:
                    modality: Brightfield
                    objective: 0.5NA_AIR_10x
                    detector: HAMAMATSU_DCAM_01
                    angles:
                      angle_0:
                        angle_degrees: 0.0
                        exposure_ms: 41.36
                """);
        BackgroundExposureMatch match = mgr.findBackgroundExposures(
                "Brightfield", "0.75NA_AIR_10x", "HAMAMATSU_DCAM_01");
        assertNotNull(match, "objective-renamed-same-mag must match at tier MAGNIFICATION");
        assertEquals(BackgroundExposureMatch.Tier.MAGNIFICATION, match.tier());
        assertTrue(match.isObjectiveDrift());
        assertEquals("0.5NA_AIR_10x", match.stored().objective());
        assertEquals("0.75NA_AIR_10x", match.requested().objective());
        assertEquals(41.36, match.exposures().get(0.0));
    }

    @Test
    void detectorMismatchReturnsNoMatch() throws IOException {
        writeFixture("""
                calibration_targets:
                  background_exposures:
                    modality: Brightfield
                    objective: 0.5NA_AIR_10x
                    detector: HAMAMATSU_DCAM_01
                    angles:
                      angle_0:
                        angle_degrees: 0.0
                        exposure_ms: 41.36
                """);
        BackgroundExposureMatch match = mgr.findBackgroundExposures(
                "Brightfield", "0.5NA_AIR_10x", "OTHER_DETECTOR");
        assertNull(match, "detector axis is hard -- never softened");
    }

    @Test
    void modalityFamilyMismatchReturnsNoMatch() throws IOException {
        // PPM stored, Brightfield requested. Different physics; never reuse.
        writeFixture("""
                calibration_targets:
                  background_exposures:
                    modality: ppm
                    objective: 0.5NA_AIR_10x
                    detector: HAMAMATSU_DCAM_01
                    angles:
                      angle_0:
                        angle_degrees: 0.0
                        exposure_ms: 41.36
                """);
        BackgroundExposureMatch match = mgr.findBackgroundExposures(
                "Brightfield", "0.5NA_AIR_10x", "HAMAMATSU_DCAM_01");
        assertNull(match, "modality family is hard -- never softened");
    }

    @Test
    void magnificationMismatchReturnsNoMatch() throws IOException {
        // 10x stored, 20x requested. Same family + detector, but objective IDs
        // resolve to different magnifications. The on-disk BG TIFF is in a
        // different folder -- there is no BG image to share, so the lookup
        // must refuse rather than return mismatched exposure.
        writeFixture("""
                calibration_targets:
                  background_exposures:
                    modality: Brightfield
                    objective: 0.5NA_AIR_10x
                    detector: HAMAMATSU_DCAM_01
                    angles:
                      angle_0:
                        angle_degrees: 0.0
                        exposure_ms: 41.36
                """);
        BackgroundExposureMatch match = mgr.findBackgroundExposures(
                "Brightfield", "0.75NA_AIR_20x", "HAMAMATSU_DCAM_01");
        assertNull(match, "different magnification has no shared BG TIFF -- refuse");
    }

    @Test
    void explicitMagnificationFieldOverridesObjectiveDerivation() throws IOException {
        // Forward-compat: when the YAML carries an explicit `magnification`
        // field, it wins over re-deriving from `objective`. Lets the writer
        // declare the magnification authoritatively for cases where the
        // objective string would not parse cleanly.
        writeFixture("""
                calibration_targets:
                  background_exposures:
                    modality: Brightfield
                    objective: weird_objective_no_mag
                    detector: HAMAMATSU_DCAM_01
                    magnification: 10x
                    angles:
                      angle_0:
                        angle_degrees: 0.0
                        exposure_ms: 41.36
                """);
        BackgroundExposureMatch match = mgr.findBackgroundExposures(
                "Brightfield", "0.75NA_AIR_10x", "HAMAMATSU_DCAM_01");
        assertNotNull(match, "explicit magnification field must enable Tier-2 match");
        assertEquals(BackgroundExposureMatch.Tier.MAGNIFICATION, match.tier());
        assertEquals(41.36, match.exposures().get(0.0));
    }

    @Test
    void noCalibrationRowReturnsNullCleanly() throws IOException {
        // No calibration_targets section at all. Lookup must return null
        // without throwing, so callers can differentiate "never collected"
        // from "collected for wrong rig".
        writeFixture("# empty imageprocessing\n");
        BackgroundExposureMatch match = mgr.findBackgroundExposures(
                "Brightfield", "0.5NA_AIR_10x", "HAMAMATSU_DCAM_01");
        assertNull(match);
    }

    @Test
    void multiAnglePpmCalibrationReturnsAllAngles() throws IOException {
        // PPM stores per-angle exposures. The lookup must return the full map.
        writeFixture("""
                calibration_targets:
                  background_exposures:
                    modality: ppm
                    objective: 0.5NA_AIR_20x
                    detector: HAMAMATSU_DCAM_01
                    angles:
                      angle_0:
                        angle_degrees: 0.0
                        exposure_ms: 12.5
                      angle_45:
                        angle_degrees: 45.0
                        exposure_ms: 18.0
                      angle_90:
                        angle_degrees: 90.0
                        exposure_ms: 25.5
                      angle_135:
                        angle_degrees: 135.0
                        exposure_ms: 18.0
                """);
        BackgroundExposureMatch match = mgr.findBackgroundExposures(
                "ppm_20x", "0.5NA_AIR_20x", "HAMAMATSU_DCAM_01");
        assertNotNull(match);
        assertEquals(4, match.exposures().size());
        assertEquals(12.5, match.exposures().get(0.0));
        assertEquals(25.5, match.exposures().get(90.0));
    }
}
