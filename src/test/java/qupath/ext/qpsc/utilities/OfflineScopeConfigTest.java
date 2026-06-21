package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the bundled "Offline / Analysis" placeholder microscope config.
 *
 * <p>This is the authoritative correctness check for the shipped YAML: it copies
 * the actual classpath resources (the same ones {@link OfflineScopeInstaller}
 * extracts) into a temp directory, loads them through
 * {@link MicroscopeConfigManager}, and asserts that
 * {@link MicroscopeConfigManager#validateConfiguration()} returns no errors and
 * {@link MicroscopeConfigManager#isOfflineScope()} is true. If the placeholder
 * config ever drifts out of sync with the validation rules, this fails on the
 * build machine instead of silently re-popping the "settings missing" warning on
 * a fresh install.
 */
class OfflineScopeConfigTest {

    @TempDir
    Path tempDir;

    private void copyResource(String resource, Path dest) throws IOException {
        try (InputStream is = OfflineScopeConfigTest.class.getResourceAsStream(resource)) {
            assertNotNull(is, "Bundled resource missing from classpath: " + resource);
            Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test
    void bundledOfflineConfigValidatesCleanAndIsOffline() throws IOException {
        Path config = tempDir.resolve("config_Offline.yml");
        copyResource("/qupath/ext/qpsc/templates/offline/config_Offline.yml", config);
        copyResource(
                "/qupath/ext/qpsc/templates/offline/imageprocessing_Offline.yml",
                tempDir.resolve("imageprocessing_Offline.yml"));
        Path resourcesDir = tempDir.resolve("resources");
        Files.createDirectories(resourcesDir);
        copyResource("/qupath/ext/qpsc/templates/resources_LOCI.yml", resourcesDir.resolve("resources_LOCI.yml"));

        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(config.toString());
        mgr.reload(config.toString());

        List<String> errors = mgr.validateConfiguration();
        assertTrue(errors.isEmpty(), "Offline config should validate clean but had errors: " + errors);
        assertTrue(mgr.isOfflineScope(), "Offline config should report isOfflineScope() == true");

        // The bundled config ships a complete stage.streaming_af block so a fresh
        // extract is NOT auto-migrated (and rewritten) on first load.
        assertNotNull(
                mgr.getBoolean("stage", "streaming_af", "enabled"),
                "Offline config should ship a complete streaming_af block to avoid first-load migration");
        assertEquals(
                Boolean.FALSE,
                mgr.getBoolean("stage", "streaming_af", "enabled"),
                "Offline placeholder should keep streaming autofocus disabled");
    }

    @Test
    void isOfflineConfigPathRecognizesPlaceholderLocation() {
        assertTrue(OfflineScopeInstaller.isOfflineConfigPath("/x/qpsc-offline-microscope/config_Offline.yml"));
        // A real scope at a normal location must not be mistaken for the placeholder.
        assertFalse(OfflineScopeInstaller.isOfflineConfigPath("/configs/config_OWS3.yml"));
        assertFalse(OfflineScopeInstaller.isOfflineConfigPath("/somewhere/else/config_Offline.yml"));
        assertFalse(OfflineScopeInstaller.isOfflineConfigPath(""));
        assertFalse(OfflineScopeInstaller.isOfflineConfigPath(null));
    }
}
