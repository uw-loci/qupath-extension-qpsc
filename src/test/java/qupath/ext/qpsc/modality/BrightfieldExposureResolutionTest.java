package qupath.ext.qpsc.modality;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.utilities.BackgroundProfiles;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * Integration tests for {@link BrightfieldModalityHandler#getRotationAngles}
 * and the underlying {@code resolveExposureMs} path. The exposure comes from
 * the background set in the active background profile. Covers the contract
 * that:
 *
 * <ul>
 *   <li>BG correction enabled + a set in the active profile -&gt; return its recorded exposure.</li>
 *   <li>BG correction enabled + a set collected with another objective of the same
 *       magnification -&gt; return its exposure (they share the folder; logged as WARN).</li>
 *   <li>BG correction enabled + no active profile, or no set in it -&gt; throw
 *       {@link BackgroundCalibrationMismatchException}. Must NOT silently
 *       fall back to {@link PersistentPreferences#getLastUnifiedExposureMs}.</li>
 *   <li>BG correction disabled -&gt; return the user's last unified exposure
 *       cleanly. This is the legitimate no-BG path.</li>
 * </ul>
 *
 * <p>The third carve-out is critical: the previous bug class was a silent
 * fallback to a stale 2 ms preference even when BG correction was active and
 * the BG was captured at 41 ms. Acquisition then ran at the wrong exposure
 * and flat-field correction blew up with no visible warning.
 */
class BrightfieldExposureResolutionTest {

    @TempDir
    Path tempDir;

    private String previousActive;

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

    @BeforeEach
    void rememberPreferences() {
        previousActive = PersistentPreferences.getActiveBackgroundProfile();
        PersistentPreferences.setActiveBackgroundProfile("");
    }

    @AfterEach
    void teardown() {
        // Reset preferences so each test starts from a known state.
        PersistentPreferences.setLastUnifiedExposureMs(50.0);
        PersistentPreferences.setActiveBackgroundProfile(previousActive);
    }

    private String base() {
        return tempDir.resolve("bg").toString().replace('\\', '/');
    }

    private void writeFixture(boolean enabled) throws IOException {
        Path mainConfig = tempDir.resolve("config_Test.yml");
        Path imgproc = tempDir.resolve("imageprocessing_Test.yml");
        Path resourcesDir = tempDir.resolve("resources");
        Files.createDirectories(resourcesDir);
        Files.writeString(resourcesDir.resolve("resources_LOCI.yml"), "id_detector: {}\n");
        Files.writeString(mainConfig, MINIMAL_MAIN_CONFIG);
        Files.writeString(imgproc, """
                background_correction:
                  base_folder: %s
                  Brightfield:
                    enabled: %s
                    method: divide
                """.formatted(base(), enabled));
        // Wire the singleton -- BrightfieldModalityHandler.resolveExposureMs
        // prefers MicroscopeConfigManager.getInstanceIfAvailable() and asks
        // it for its config path, so this is sufficient.
        MicroscopeConfigManager.getInstance(mainConfig.toString()).reload(mainConfig.toString());
    }

    /** A collected brightfield set in profile {@code p1} for the Hamamatsu at 10x, as Background Collection writes it. */
    private void collected(String objective, double exposureMs) throws IOException {
        var profile = BackgroundProfiles.find(base(), "p1").isPresent()
                ? BackgroundProfiles.find(base(), "p1").get()
                : BackgroundProfiles.create(base(), "p1", "Profile 1", null, null, null, null);
        Path set = profile.folder()
                .resolve("HAMAMATSU_DCAM_01")
                .resolve("Brightfield")
                .resolve("10x");
        Files.createDirectories(set);
        Files.writeString(
                set.resolve("background_settings.yml"), """
                hardware:
                  modality: Brightfield
                  objective: %s
                  detector: HAMAMATSU_DCAM_01
                  magnification: 10x
                acquisition:
                  wb_mode: off
                angle_exposures:
                  - angle: 0.0
                    exposure: %s
                context:
                  profile: p1
                """.formatted(objective, exposureMs), StandardCharsets.UTF_8);
    }

    @Test
    void returnsCollectedExposureWhenBgEnabledAndTheActiveProfileHasASet() throws Exception {
        writeFixture(true);
        collected("0.5NA_AIR_10x", 41.36);
        BackgroundProfiles.setActiveId("p1");
        BrightfieldModalityHandler handler = new BrightfieldModalityHandler();
        List<AngleExposure> result = handler.getRotationAngles(
                        "Brightfield_10x", "0.5NA_AIR_10x", "HAMAMATSU_DCAM_01", "off")
                .get();
        assertEquals(1, result.size());
        assertEquals(41.36, result.get(0).exposureMs());
    }

    @Test
    void usesTheSetOfAnotherObjectiveAtTheSameMagnification() throws Exception {
        // Collected with 0.5NA_AIR_10x, requested 0.75NA_AIR_10x. Both 10x objectives share
        // the folder and the background TIFF, so the collected exposure is reused (with a WARN).
        writeFixture(true);
        collected("0.5NA_AIR_10x", 41.36);
        BackgroundProfiles.setActiveId("p1");
        BrightfieldModalityHandler handler = new BrightfieldModalityHandler();
        List<AngleExposure> result = handler.getRotationAngles(
                        "Brightfield_10x", "0.75NA_AIR_10x", "HAMAMATSU_DCAM_01", "off")
                .get();
        assertEquals(41.36, result.get(0).exposureMs(), "same magnification shares the set");
    }

    @Test
    void throwsWhenBgEnabledButNoProfileIsActive() throws Exception {
        writeFixture(true);
        collected("0.5NA_AIR_10x", 41.36); // exists, but nothing points at it
        PersistentPreferences.setLastUnifiedExposureMs(2.0); // the trap value
        BrightfieldModalityHandler handler = new BrightfieldModalityHandler();
        Exception ex = assertThrows(
                Exception.class,
                () -> handler.getRotationAngles("Brightfield_10x", "0.5NA_AIR_10x", "HAMAMATSU_DCAM_01", "off")
                        .get());
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        assertInstanceOf(BackgroundCalibrationMismatchException.class, cause);
        assertTrue(cause.getMessage().contains("no background profile is active"), cause.getMessage());
    }

    @Test
    void throwsWhenBgEnabledButTheActiveProfileHasNoSetForThisHardware() throws Exception {
        // The profile exists but holds a set for another detector only.
        writeFixture(true);
        BackgroundProfiles.create(base(), "p1", "Profile 1", null, null, null, null);
        BackgroundProfiles.setActiveId("p1");
        PersistentPreferences.setLastUnifiedExposureMs(2.0);
        BrightfieldModalityHandler handler = new BrightfieldModalityHandler();
        Exception ex = assertThrows(
                Exception.class,
                () -> handler.getRotationAngles("Brightfield_10x", "0.5NA_AIR_10x", "HAMAMATSU_DCAM_01", "off")
                        .get());
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        assertInstanceOf(BackgroundCalibrationMismatchException.class, cause);
        assertTrue(cause.getMessage().contains("'p1'"), cause.getMessage());
    }

    @Test
    void usesPersistentPreferenceWhenBgDisabled() throws Exception {
        // BG correction explicitly disabled. There may be a collected set, but the user's
        // choice to skip BG correction must be honored; fall through to the preference.
        writeFixture(false);
        collected("0.5NA_AIR_10x", 999.0);
        BackgroundProfiles.setActiveId("p1");
        PersistentPreferences.setLastUnifiedExposureMs(50.0);
        BrightfieldModalityHandler handler = new BrightfieldModalityHandler();
        List<AngleExposure> result = handler.getRotationAngles(
                        "Brightfield_10x", "0.5NA_AIR_10x", "HAMAMATSU_DCAM_01", "off")
                .get();
        assertEquals(50.0, result.get(0).exposureMs());
    }
}
