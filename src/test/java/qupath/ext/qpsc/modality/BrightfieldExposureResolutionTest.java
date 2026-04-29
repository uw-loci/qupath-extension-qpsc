package qupath.ext.qpsc.modality;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * Integration tests for {@link BrightfieldModalityHandler#getRotationAngles}
 * and the underlying {@code resolveExposureMs} path. Covers the contract
 * that:
 *
 * <ul>
 *   <li>BG correction enabled + matching calibration -&gt; return calibrated exposure.</li>
 *   <li>BG correction enabled + objective-drift calibration -&gt; return calibrated
 *       exposure (Tier-2 reuse, logged as WARN).</li>
 *   <li>BG correction enabled + no matching calibration -&gt; throw
 *       {@link BackgroundCalibrationMismatchException}. Must NOT silently
 *       fall back to {@link PersistentPreferences#getLastUnifiedExposureMs}.</li>
 *   <li>BG correction disabled -&gt; return the user's last unified exposure
 *       cleanly. This is the legitimate no-BG path.</li>
 * </ul>
 *
 * <p>The fourth carve-out is critical: the previous bug class was a silent
 * fallback to a stale 2 ms preference even when BG correction was active and
 * the BG was captured at 41 ms. Acquisition then ran at the wrong exposure
 * and flat-field correction blew up with no visible warning.
 */
class BrightfieldExposureResolutionTest {

    @TempDir
    Path tempDir;

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
        // Reset preference so each test starts from a known state.
        PersistentPreferences.setLastUnifiedExposureMs(50.0);
    }

    private Path writeFixture(String imgprocYaml) throws IOException {
        Path mainConfig = tempDir.resolve("config_Test.yml");
        Path imgproc = tempDir.resolve("imageprocessing_Test.yml");
        Path resourcesDir = tempDir.resolve("resources");
        Files.createDirectories(resourcesDir);
        Files.writeString(resourcesDir.resolve("resources_LOCI.yml"), "id_detector: {}\n");
        Files.writeString(mainConfig, MINIMAL_MAIN_CONFIG);
        Files.writeString(imgproc, imgprocYaml);
        // Wire the singleton -- BrightfieldModalityHandler.resolveExposureMs
        // prefers MicroscopeConfigManager.getInstanceIfAvailable() and asks
        // it for its config path, so this is sufficient.
        MicroscopeConfigManager.getInstance(mainConfig.toString()).reload(mainConfig.toString());
        return mainConfig;
    }

    @Test
    void returnsCalibratedExposureWhenBgEnabledAndMatched() throws Exception {
        writeFixture("""
                background_correction:
                  Brightfield:
                    enabled: true
                    method: divide
                    base_folder: /tmp
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
        BrightfieldModalityHandler handler = new BrightfieldModalityHandler();
        List<AngleExposure> result = handler
                .getRotationAngles("Brightfield_10x", "0.5NA_AIR_10x", "HAMAMATSU_DCAM_01", "off")
                .get();
        assertEquals(1, result.size());
        assertEquals(41.36, result.get(0).exposureMs());
    }

    @Test
    void usesCalibratedExposureUnderObjectiveDriftAtMagnificationTier() throws Exception {
        // Simulates the real-world bug: stored 0.5NA_AIR_10x, requested 0.75NA_AIR_10x.
        // Both 10x objectives share the same on-disk BG TIFF, so the calibrated
        // 41.36 ms is appropriate to reuse (with a WARN log entry).
        writeFixture("""
                background_correction:
                  Brightfield:
                    enabled: true
                    method: divide
                    base_folder: /tmp
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
        BrightfieldModalityHandler handler = new BrightfieldModalityHandler();
        List<AngleExposure> result = handler
                .getRotationAngles("Brightfield_10x", "0.75NA_AIR_10x", "HAMAMATSU_DCAM_01", "off")
                .get();
        assertEquals(41.36, result.get(0).exposureMs(),
                "objective-drift case must reuse calibrated exposure -- not fall back to stale preference");
    }

    @Test
    void throwsWhenBgEnabledButNoMatchingCalibration() throws Exception {
        // BG correction is on for Brightfield, but the only stored row is for
        // a different detector. Old behavior: silently used 2 ms preference,
        // produced visibly wrong divides. New behavior: throw at acquisition
        // setup so the user re-collects backgrounds rather than running with
        // garbage.
        writeFixture("""
                background_correction:
                  Brightfield:
                    enabled: true
                    method: divide
                    base_folder: /tmp
                calibration_targets:
                  background_exposures:
                    modality: Brightfield
                    objective: 0.5NA_AIR_10x
                    detector: SOME_OTHER_DETECTOR
                    angles:
                      angle_0:
                        angle_degrees: 0.0
                        exposure_ms: 41.36
                """);
        PersistentPreferences.setLastUnifiedExposureMs(2.0); // the trap value
        BrightfieldModalityHandler handler = new BrightfieldModalityHandler();
        Exception ex = assertThrows(Exception.class, () -> handler
                .getRotationAngles("Brightfield_10x", "0.5NA_AIR_10x", "HAMAMATSU_DCAM_01", "off")
                .get());
        // CompletableFuture wraps the runtime exception in an ExecutionException;
        // the cause must be our typed mismatch exception.
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        assertInstanceOf(BackgroundCalibrationMismatchException.class, cause);
    }

    @Test
    void usesPersistentPreferenceWhenBgDisabled() throws Exception {
        // BG correction explicitly disabled. There may be a stale calibration
        // row, but the user's choice to skip BG correction must be honored;
        // fall through to the last-unified preference cleanly.
        writeFixture("""
                background_correction:
                  Brightfield:
                    enabled: false
                    method: divide
                    base_folder: /tmp
                calibration_targets:
                  background_exposures:
                    modality: Brightfield
                    objective: SOMETHING_ELSE
                    detector: HAMAMATSU_DCAM_01
                    angles:
                      angle_0:
                        angle_degrees: 0.0
                        exposure_ms: 999.0
                """);
        PersistentPreferences.setLastUnifiedExposureMs(50.0);
        BrightfieldModalityHandler handler = new BrightfieldModalityHandler();
        List<AngleExposure> result = handler
                .getRotationAngles("Brightfield_10x", "0.5NA_AIR_10x", "HAMAMATSU_DCAM_01", "off")
                .get();
        assertEquals(50.0, result.get(0).exposureMs());
    }
}
