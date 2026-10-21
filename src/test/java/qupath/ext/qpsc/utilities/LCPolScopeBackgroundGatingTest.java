package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpsc.preferences.PersistentPreferences;

/**
 * An LC-PolScope background is part of the reconstruction, not a flat-field divide, so it is sent
 * whenever a background folder is configured: neither the monochrome "Use background correction"
 * checkbox (about flat-field) nor the YAML {@code enabled} flag may turn it off. The 2026-10-16
 * test mosaic reconstructed every tile uncorrected and showed the instrument residual as a step at
 * every seam.
 */
class LCPolScopeBackgroundGatingTest {

    @TempDir
    Path tempDir;

    private boolean savedMonoPref;
    private String savedActiveProfile;

    private static final String MAIN_CONFIG = """
            microscope:
              name: 'Test'
              type: 'TestSystem'
            modalities:
              lcpolscope:
                type: 'polarized'
              brightfield:
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
    void savePref() {
        savedMonoPref = PersistentPreferences.getUseBackgroundCorrectionMono();
        savedActiveProfile = PersistentPreferences.getActiveBackgroundProfile();
        PersistentPreferences.setActiveBackgroundProfile("");
    }

    @AfterEach
    void restorePref() {
        PersistentPreferences.setUseBackgroundCorrectionMono(savedMonoPref);
        PersistentPreferences.setActiveBackgroundProfile(savedActiveProfile);
    }

    /** The base folder with one background profile in it, made active, as a YAML scalar. */
    private String baseWithActiveProfile() throws IOException {
        String base = tempDir.resolve("backgrounds").toString().replace('\\', '/');
        BackgroundProfiles.create(base, "p", "P", null, null, null, null);
        BackgroundProfiles.setActiveId("p");
        return "'" + base + "'";
    }

    private MicroscopeConfigManager load(String baseFolder) throws IOException {
        Path main = tempDir.resolve("config_Test.yml");
        Path resources = tempDir.resolve("resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("resources_LOCI.yml"), "id_detector: {}\n");
        Files.writeString(main, MAIN_CONFIG);
        Files.writeString(tempDir.resolve("imageprocessing_Test.yml"), """
                background_correction:
                  base_folder: %s
                  lcpolscope:
                    enabled: false
                    method: none
                  brightfield:
                    enabled: false
                    method: divide
                """.formatted(baseFolder));
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(main.toString());
        mgr.reload(main.toString());
        return mgr;
    }

    @Test
    void aConfiguredFolderIsUsedEvenWithTheFlatFieldToggleOff() throws IOException {
        MicroscopeConfigManager mgr = load(baseWithActiveProfile());
        PersistentPreferences.setUseBackgroundCorrectionMono(false);

        assertTrue(AcquisitionConfigurationBuilder.isBackgroundCorrectionEffectivelyEnabled(
                mgr, "lcpolscope", "DET_DUMMY"));
        assertTrue(AcquisitionConfigurationBuilder.isBackgroundCorrectionEffectivelyEnabled(
                mgr, "lcpolscope_20x", "DET_DUMMY"));
        // Brightfield keeps honouring the toggle.
        assertFalse(AcquisitionConfigurationBuilder.isBackgroundCorrectionEffectivelyEnabled(
                mgr, "brightfield", "DET_DUMMY"));
    }

    @Test
    void noFolderMeansNoBackgroundWhateverTheToggleSays() throws IOException {
        MicroscopeConfigManager mgr = load("null");
        PersistentPreferences.setUseBackgroundCorrectionMono(true);

        assertFalse(AcquisitionConfigurationBuilder.isBackgroundCorrectionEffectivelyEnabled(
                mgr, "lcpolscope", "DET_DUMMY"));
    }

    @Test
    void noActiveProfileMeansNoBackgroundEvenWithAFolder() throws IOException {
        String base = tempDir.resolve("backgrounds").toString().replace('\\', '/');
        BackgroundProfiles.create(base, "p", "P", null, null, null, null);
        MicroscopeConfigManager mgr = load("'" + base + "'");
        PersistentPreferences.setUseBackgroundCorrectionMono(true);

        assertFalse(
                AcquisitionConfigurationBuilder.isBackgroundCorrectionEffectivelyEnabled(
                        mgr, "lcpolscope", "DET_DUMMY"),
                "a profile exists but none is active");
    }

    @Test
    void theFlatFieldIlluminationCheckDoesNotApplyToLcPolScope() throws IOException {
        MicroscopeConfigManager mgr = load(baseWithActiveProfile());
        // Returns true (proceed) without looking for flat-field settings; a modal dialog here
        // would describe a divide the LC-PolScope path never does.
        assertTrue(BackgroundIlluminationCheck.checkAndWarn(
                mgr, "lcpolscope", "OBJ_DUMMY", "DET_DUMMY", "off", "C:/backgrounds", java.util.List.of()));
    }
}
