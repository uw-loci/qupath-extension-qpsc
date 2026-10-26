package qupath.ext.qpsc.modality.lcpolscope.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpsc.modality.lcpolscope.ui.LCPolScopeReadiness.Check;
import qupath.ext.qpsc.modality.lcpolscope.ui.LCPolScopeReadiness.Level;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.utilities.BackgroundProfiles;
import qupath.ext.qpsc.utilities.BackgroundSettingsReader;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * The LC-PolScope readiness summary: what the panel shows before the stage moves. Each of these
 * used to surface only as a log line at the first tile.
 */
class LCPolScopeReadinessTest {

    @TempDir
    Path dir;

    private static final String CALIBRATED = "2026-10-16T15:01:27";

    private MicroscopeConfigManager config(String reconExtra, String bgBase) throws Exception {
        String main = """
                microscope:
                  name: 'Test'
                  type: 'TestSystem'
                modalities:
                  lcpolscope:
                    type: 'polarized'
                    reconstruction:
                      wavelength_nm: 549
                %s
                hardware:
                  objectives:
                    - id: 'OBJ20'
                  detectors:
                    - 'DET'
                stage:
                  stage_id: 'S'
                  limits:
                    x_um: { low: -1, high: 1 }
                    y_um: { low: -1, high: 1 }
                    z_um: { low: -1, high: 1 }
                slide_size_um: { x: 1, y: 1 }
                acquisition_profiles:
                  lcpolscope_20x:
                    modality: lcpolscope
                    objective: OBJ20
                    detector: DET
                """.formatted(reconExtra);
        Path resources = Files.createDirectories(dir.resolve("resources"));
        Files.writeString(resources.resolve("resources_LOCI.yml"), "id_detector: {}\n");
        Files.writeString(dir.resolve("config_Test.yml"), main);
        String imgproc = bgBase == null
                ? "background_correction: {}\n"
                : "background_correction:\n  base_folder: '" + bgBase.replace('\\', '/')
                        + "'\n  lcpolscope:\n    enabled: true\n    method: none\n";
        Files.writeString(dir.resolve("imageprocessing_Test.yml"), imgproc);
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                dir.resolve("config_Test.yml").toString());
        mgr.reload(dir.resolve("config_Test.yml").toString());
        return mgr;
    }

    private String savedActiveProfile;

    @BeforeEach
    void saveActiveProfile() {
        savedActiveProfile = PersistentPreferences.getActiveBackgroundProfile();
        PersistentPreferences.setActiveBackgroundProfile("");
    }

    @AfterEach
    void restoreActiveProfile() {
        PersistentPreferences.setActiveBackgroundProfile(savedActiveProfile);
    }

    /** Backgrounds live under the active background profile; make one and activate it. */
    private static String activeProfileFolder(Path base) throws Exception {
        var profile = BackgroundProfiles.create(base.toString(), "p", "P", null, null, null, null);
        BackgroundProfiles.setActiveId("p");
        return profile.folder().toString();
    }

    private static Check find(List<Check> checks, String contains) {
        return checks.stream()
                .filter(c -> c.text().contains(contains))
                .findFirst()
                .orElse(null);
    }

    @Test
    void noCalibrationNoBlackLevelNoBackgroundAreAllSaid() throws Exception {
        List<Check> checks = LCPolScopeReadiness.configChecks(config("", null), LocalDateTime.now());
        assertEquals(Level.WARN, find(checks, "No QPSC LC calibration").level());
        assertEquals(Level.WARN, find(checks, "No black level").level());
        assertEquals(Level.WARN, find(checks, "lcpolscope_20x: no background").level());
    }

    @Test
    void aCurrentCalibrationAndMatchingBackgroundAreOk() throws Exception {
        Path base = Files.createDirectories(dir.resolve("bg"));
        String folder = BackgroundSettingsReader.resolveChannelBackgroundFolder(
                activeProfileFolder(base), "lcpolscope", "OBJ20", "DET", "lcpolscope_20x");
        Files.createDirectories(Path.of(folder));
        Files.writeString(
                Path.of(folder, "background_provenance.json"),
                "{\"lc_calibrated_at\": \"" + CALIBRATED + "\", \"collected_at\": \"2026-10-16T15:30:00\"}");
        MicroscopeConfigManager mgr = config(
                "      lc_calibrated_at: '" + CALIBRATED
                        + "'\n      lc_extinction_ratio: 258.5\n      black_level: 103",
                base.toString());
        List<Check> checks = LCPolScopeReadiness.configChecks(mgr, LocalDateTime.parse("2026-10-17T09:00:00"));
        Check cal = find(checks, "Calibration 2026-10-16");
        assertEquals(Level.OK, cal.level());
        assertTrue(cal.text().contains("1 day ago") && cal.text().contains("258.5"), cal.text());
        assertNull(find(checks, "No black level"));
        assertEquals(Level.OK, find(checks, "lcpolscope_20x: background").level());
    }

    @Test
    void aBackgroundTakenOnSpecimenIsAWarning() throws Exception {
        Path base = Files.createDirectories(dir.resolve("bg"));
        String folder = BackgroundSettingsReader.resolveChannelBackgroundFolder(
                activeProfileFolder(base), "lcpolscope", "OBJ20", "DET", "lcpolscope_20x");
        Files.createDirectories(Path.of(folder));
        Files.writeString(
                Path.of(folder, "background_provenance.json"),
                "{\"lc_calibrated_at\": \"" + CALIBRATED + "\", \"collected_at\": \"2026-10-16T15:30:00\","
                        + " \"swing_state_spread\": 0.42}");
        MicroscopeConfigManager mgr =
                config("      lc_calibrated_at: '" + CALIBRATED + "'\n      black_level: 103", base.toString());
        List<Check> checks = LCPolScopeReadiness.configChecks(mgr, LocalDateTime.parse("2026-10-17T09:00:00"));
        Check bg = find(checks, "lcpolscope_20x: the background's swing states differ by 42%");
        assertNotNull(bg);
        assertEquals(Level.WARN, bg.level());
    }

    @Test
    void anUnevenBackgroundWithoutACalibrationIsExpected() throws Exception {
        // 10-21 Hamamatsu: OpenPolScope's palette, no QPSC calibration, swing states 28% apart
        // on a clear field; the run reconstructed normally. Blaming specimen sent the user
        // looking for debris that was not there.
        Path base = Files.createDirectories(dir.resolve("bg"));
        String folder = BackgroundSettingsReader.resolveChannelBackgroundFolder(
                activeProfileFolder(base), "lcpolscope", "OBJ20", "DET", "lcpolscope_20x");
        Files.createDirectories(Path.of(folder));
        Files.writeString(
                Path.of(folder, "background_provenance.json"),
                "{\"lc_calibrated_at\": null, \"collected_at\": \"2026-10-21T18:47:51\","
                        + " \"swing_state_spread\": 0.28}");
        MicroscopeConfigManager mgr = config("      black_level: 103", base.toString());
        List<Check> checks = LCPolScopeReadiness.configChecks(mgr, LocalDateTime.parse("2026-10-22T09:00:00"));
        Check bg = find(checks, "lcpolscope_20x: background 2026-10-21 18:47:51; its swing states differ by 28%");
        assertNotNull(bg);
        assertEquals(Level.OK, bg.level());
        assertFalse(bg.text().contains("specimen"), bg.text());

        // The same spread with a balance that changes across the field is specimen.
        Files.writeString(
                Path.of(folder, "background_provenance.json"),
                "{\"lc_calibrated_at\": null, \"collected_at\": \"2026-10-21T18:47:51\","
                        + " \"swing_state_spread\": 0.28, \"swing_field_variation\": 2.0}");
        checks = LCPolScopeReadiness.configChecks(mgr, LocalDateTime.parse("2026-10-22T09:00:00"));
        Check patchy = find(checks, "lcpolscope_20x: the background's swing states differ by 28%");
        assertNotNull(patchy);
        assertEquals(Level.WARN, patchy.level());
    }

    @Test
    void aBackgroundFromAnotherCalibrationIsAFailAndAnOldCalibrationAWarning() throws Exception {
        Path base = Files.createDirectories(dir.resolve("bg"));
        String folder = BackgroundSettingsReader.resolveChannelBackgroundFolder(
                activeProfileFolder(base), "lcpolscope", "OBJ20", "DET", "lcpolscope_20x");
        Files.createDirectories(Path.of(folder));
        Files.writeString(
                Path.of(folder, "background_provenance.json"), "{\"lc_calibrated_at\": \"2026-10-12T16:57:22\"}");
        MicroscopeConfigManager mgr =
                config("      lc_calibrated_at: '" + CALIBRATED + "'\n      black_level: 103", base.toString());
        List<Check> checks = LCPolScopeReadiness.configChecks(mgr, LocalDateTime.parse("2026-11-20T09:00:00"));
        assertEquals(Level.FAIL, find(checks, "different calibration").level());
        assertEquals(Level.WARN, find(checks, "Calibration 2026-10-16").level(), "35 days old");
    }

    @Test
    void theLiveReportBecomesCameraAndAdapterLines() {
        var report = JsonParser.parseString("""
                {"camera_fatal": ["Gain Auto is Continuous: ..."], "camera_advisory": [],
                 "camera_text": "Oryx, 33 ms, 12-bit", "adapter_wavelength_nm": 546.0,
                 "reconstruction_wavelength_nm": 549}
                """).getAsJsonObject();
        List<Check> checks = LCPolScopeReadiness.liveChecks(report);
        assertEquals(Level.FAIL, find(checks, "Gain Auto").level());
        assertNull(find(checks, "Camera: "), "no all-clear camera line when something is fatal");
        assertEquals(Level.WARN, find(checks, "LC adapter at 546").level());

        var clean = JsonParser.parseString("""
                {"camera_fatal": [], "camera_advisory": [], "camera_text": "Oryx, 33 ms, 12-bit",
                 "adapter_wavelength_nm": 549.0, "reconstruction_wavelength_nm": 549}
                """).getAsJsonObject();
        List<Check> ok = LCPolScopeReadiness.liveChecks(clean);
        assertEquals(Level.OK, find(ok, "Camera: Oryx").level());
        assertEquals(Level.OK, find(ok, "LC adapter wavelength 549").level());
        assertEquals(Level.INFO, LCPolScopeReadiness.liveChecks(null).get(0).level());
    }

    @Test
    void serverAndQpscOnDifferentFilesIsAFailure() {
        var check = LCPolScopeReadiness.serverConfigCheck(
                "C:\\cfg\\config_LCPolScope.yml", "C:\\cfg\\config_LCPolScope_Hamamatsu.yml");
        org.junit.jupiter.api.Assertions.assertNotNull(check);
        org.junit.jupiter.api.Assertions.assertEquals(LCPolScopeReadiness.Level.FAIL, check.level());
        org.junit.jupiter.api.Assertions.assertTrue(check.text().contains("config_LCPolScope.yml"), check.text());
        org.junit.jupiter.api.Assertions.assertTrue(
                check.text().contains("config_LCPolScope_Hamamatsu.yml"), check.text());
    }

    @Test
    void theSameFileOrAnUnknownSideIsNoCheck() {
        org.junit.jupiter.api.Assertions.assertNull(
                LCPolScopeReadiness.serverConfigCheck("/cfg/config_PPM.yml", "/cfg/./config_PPM.yml"));
        org.junit.jupiter.api.Assertions.assertNull(LCPolScopeReadiness.serverConfigCheck(null, "/cfg/a.yml"));
        org.junit.jupiter.api.Assertions.assertNull(LCPolScopeReadiness.serverConfigCheck("/cfg/a.yml", ""));
    }
}
