package qupath.ext.qpsc.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpsc.background.BackgroundProfiles;
import qupath.ext.qpsc.background.ProfileWhiteBalance;
import qupath.ext.qpsc.modality.WbMode;
import qupath.ext.qpsc.preferences.PersistentPreferences;

/**
 * A background profile's white balance is read for the mode being asked about: per-angle
 * presets get the profile's per-angle calibration and {@code simple_wb} the profile's simple
 * one, whatever white balance mode the last acquisition used.
 */
class WhiteBalanceModeLookupTest {

    private static final String OBJ = "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001";
    private static final String DET = "LOCI_DETECTOR_JAI_001";

    @TempDir
    Path tmp;

    private String previousProfile;
    private String previousMode;
    private MicroscopeConfigManager mgr;

    @BeforeEach
    void setUp() throws Exception {
        previousProfile = PersistentPreferences.getActiveBackgroundProfile();
        previousMode = PersistentPreferences.getLastWhiteBalanceMode();

        String base = tmp.resolve("bg").toString().replace('\\', '/');
        Path resources = tmp.resolve("resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("resources_LOCI.yml"), "id_detector: {}\n");
        Path main = tmp.resolve("config_Test.yml");
        Files.writeString(main, """
                microscope:
                  name: 'TestMicroscope'
                  type: 'TestSystem'
                modalities:
                  ppm:
                    type: 'ppm'
                hardware:
                  objectives:
                    - id: '%s'
                  detectors:
                    - '%s'
                stage:
                  stage_id: 'S'
                  limits:
                    x_um: { low: -1, high: 1 }
                    y_um: { low: -1, high: 1 }
                    z_um: { low: -1, high: 1 }
                slide_size_um: { x: 1, y: 1 }
                """.formatted(OBJ, DET));
        Files.writeString(tmp.resolve("imageprocessing_Test.yml"), """
                background_correction:
                  base_folder: %s
                imaging_profiles:
                  ppm:
                    %s:
                      %s:
                        exposures_ms:
                          positive: {g: 3.9}
                        gains:
                          positive: {unified_gain: 1.0}
                        simple_wb:
                          base_angle: scope-wide
                """.formatted(base, OBJ, DET));

        var profile = BackgroundProfiles.create(base, "gel", "Gel slides", null, null, null, null);
        writeProfileWb(profile.folder().toString(), "per_angle", """
                white_balance:
                  exposures_ms:
                    positive: {g: 1.5}
                  gains:
                    positive: {unified_gain: 2.0}
                """);
        writeProfileWb(profile.folder().toString(), "simple", """
                white_balance:
                  exposures_ms:
                    positive: {g: 2.5}
                  simple_wb:
                    base_angle: profile-simple
                """);
        PersistentPreferences.setActiveBackgroundProfile("gel");

        mgr = MicroscopeConfigManager.getInstance(main.toString());
        mgr.reload(main.toString());
    }

    @AfterEach
    void tearDown() {
        PersistentPreferences.setActiveBackgroundProfile(previousProfile);
        PersistentPreferences.setLastWhiteBalanceMode(previousMode);
    }

    private static void writeProfileWb(String profileFolder, String mode, String text) throws Exception {
        File f = ProfileWhiteBalance.file(profileFolder, "ppm", OBJ, DET, mode);
        Files.createDirectories(f.toPath().getParent());
        Files.writeString(f.toPath(), text);
    }

    @SuppressWarnings("unchecked")
    private static Object g(Map<String, Object> exposures) {
        return ((Map<String, Object>) exposures.get("positive")).get("g");
    }

    @Test
    void perAnglePresetsReadThePerAngleCalibrationAfterASimpleRun() {
        PersistentPreferences.setLastWhiteBalanceMode(WbMode.SIMPLE.getDisplayName());

        assertThat(g(mgr.getModalityExposures("ppm", OBJ, DET, "per_angle"))).isEqualTo(1.5);
        assertThat(mgr.getModalityGains("ppm", OBJ, DET, "per_angle"))
                .isEqualTo(Map.of("positive", Map.of("unified_gain", 2.0)));
        // Without a named mode the selected mode (simple) still decides.
        assertThat(g(mgr.getModalityExposures("ppm", OBJ, DET))).isEqualTo(2.5);
    }

    @Test
    void simpleWbReadsTheSimpleCalibrationAfterAPerAngleRun() {
        PersistentPreferences.setLastWhiteBalanceMode(WbMode.PER_ANGLE.getDisplayName());

        assertThat(mgr.getProfileSetting("ppm", OBJ, DET, "simple_wb", "base_angle"))
                .isEqualTo("profile-simple");
        assertThat(mgr.getWhiteBalanceSetting("simple", "ppm", OBJ, DET, "simple_wb", "base_angle"))
                .isEqualTo("profile-simple");
        assertThat(g(mgr.getModalityExposures("ppm", OBJ, DET))).isEqualTo(1.5);
    }

    @Test
    void aModeTheProfileLacksFallsBackToScopeWide() {
        PersistentPreferences.setActiveBackgroundProfile("");

        assertThat(g(mgr.getModalityExposures("ppm", OBJ, DET, "per_angle"))).isEqualTo(3.9);
        assertThat(mgr.getWhiteBalanceSetting("simple", "ppm", OBJ, DET, "simple_wb", "base_angle"))
                .isEqualTo("scope-wide");
    }

    @Test
    void simpleWbAlwaysBelongsToTheSimpleMode() {
        assertThat(MicroscopeConfigManager.whiteBalanceModeFor("simple_wb", WbMode.PER_ANGLE.getDisplayName()))
                .isEqualTo("simple");
        assertThat(MicroscopeConfigManager.whiteBalanceModeFor("exposures_ms", WbMode.SIMPLE.getDisplayName()))
                .isEqualTo("simple");
        assertThat(MicroscopeConfigManager.whiteBalanceModeFor("gains", WbMode.PER_ANGLE.getDisplayName()))
                .isEqualTo("per_angle");
    }
}
