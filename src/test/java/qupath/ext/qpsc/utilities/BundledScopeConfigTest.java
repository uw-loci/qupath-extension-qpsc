package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import qupath.ext.qpsc.utilities.BundledScopeInstaller.Bundle;

/**
 * Verifies the microscope configs that ship inside the JAR: the "Offline / Analysis"
 * placeholder and the demo microscope.
 *
 * <p>This is the authoritative correctness check for the shipped YAML: it copies the actual
 * classpath resources (the same ones {@link BundledScopeInstaller} extracts) into a temp
 * directory, loads them through {@link MicroscopeConfigManager}, and asserts that
 * {@link MicroscopeConfigManager#validateConfiguration()} returns no errors and the right
 * flag is set. If a bundled config ever drifts out of sync with the validation rules, this
 * fails on the build machine instead of on a fresh install.
 */
class BundledScopeConfigTest {

    @TempDir
    Path tempDir;

    private void copyResource(String resource, Path dest) throws IOException {
        try (InputStream is = BundledScopeConfigTest.class.getResourceAsStream(resource)) {
            assertNotNull(is, "Bundled resource missing from classpath: " + resource);
            Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private MicroscopeConfigManager extractAndLoad(Bundle bundle) throws IOException {
        Path config = tempDir.resolve(bundle.configFileName());
        copyResource(bundle.resourceBase() + bundle.configFileName(), config);
        for (String sidecar : bundle.sidecars()) {
            copyResource(bundle.resourceBase() + sidecar, tempDir.resolve(sidecar));
        }
        Path resourcesDir = tempDir.resolve("resources");
        Files.createDirectories(resourcesDir);
        // Exactly what the installer does: the bundle's own resources file if it ships one,
        // else the shared LOCI template.
        String own = bundle.ownResources();
        String resources = BundledScopeConfigTest.class.getResource(own) != null
                ? own
                : "/qupath/ext/qpsc/templates/" + BundledScopeInstaller.RESOURCES_FILENAME;
        copyResource(resources, resourcesDir.resolve(BundledScopeInstaller.RESOURCES_FILENAME));

        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(config.toString());
        mgr.reload(config.toString());
        return mgr;
    }

    @ParameterizedTest
    @EnumSource(Bundle.class)
    void everyBundledConfigValidatesClean(Bundle bundle) throws IOException {
        MicroscopeConfigManager mgr = extractAndLoad(bundle);
        List<String> errors = mgr.validateConfiguration();
        assertTrue(errors.isEmpty(), bundle + " config should validate clean but had errors: " + errors);

        // Both ship a complete stage.streaming_af block so a fresh extract is NOT
        // auto-migrated (and rewritten) on first load.
        assertEquals(
                Boolean.FALSE,
                mgr.getBoolean("stage", "streaming_af", "enabled"),
                bundle + " should ship streaming_af.enabled: false to avoid first-load migration");
    }

    @Test
    void offlineIsThePlaceholderAndNotTheDemo() throws IOException {
        MicroscopeConfigManager mgr = extractAndLoad(Bundle.OFFLINE);
        assertTrue(mgr.isOfflineScope(), "Offline config should report isOfflineScope() == true");
        assertFalse(mgr.isDemoScope(), "Offline config must not read as the demo scope");
    }

    @Test
    void demoIsAcquisitionCapableAndNotThePlaceholder() throws IOException {
        MicroscopeConfigManager mgr = extractAndLoad(Bundle.DEMO);
        assertTrue(mgr.isDemoScope(), "Demo config should report isDemoScope() == true");
        assertFalse(mgr.isOfflineScope(), "Demo config must not be analysis-only: acquisition is the point");
        // The demo server's devices carry Micro-Manager Demo adapter names, and the config
        // must name the same ones or the server cannot build its camera and stage.
        @SuppressWarnings("unchecked")
        Map<String, Object> demoCamera =
                (Map<String, Object>) mgr.getMergedDetectorSection().get("DEMO_CAMERA");
        assertNotNull(demoCamera, "DEMO_CAMERA should arrive through the bundle's own resources file");
        assertEquals("DCam", demoCamera.get("device"));
        assertEquals("DStage", mgr.getString("stage", "z_stage"));
        assertTrue(mgr.getAvailableModalities().contains("Brightfield"), "demo should offer Brightfield");
        // The demo ships its own resources file and defines its hardware ONLY there: the
        // server replaces the config's id_* sections with that file's, so an inline copy
        // would be the one that silently disagrees.
        assertNotNull(BundledScopeConfigTest.class.getResource(Bundle.DEMO.ownResources()), "demo resources file");
        String configText = Files.readString(tempDir.resolve(Bundle.DEMO.configFileName()));
        assertFalse(configText.contains("\nid_detector:"), "no inline id_detector in config_Demo.yml");
        assertFalse(configText.contains("\nid_objective_lens:"), "no inline id_objective_lens in config_Demo.yml");
    }

    @Test
    void bundlePathsAreRecognisedOnlyAtTheirOwnLocation() {
        assertTrue(BundledScopeInstaller.isBundleConfigPath(
                Bundle.OFFLINE, "/x/qpsc-offline-microscope/config_Offline.yml"));
        assertTrue(BundledScopeInstaller.isBundleConfigPath(Bundle.DEMO, "/x/qpsc-demo-microscope/config_Demo.yml"));
        // Each bundle recognises only its own folder and filename.
        assertFalse(
                BundledScopeInstaller.isBundleConfigPath(Bundle.DEMO, "/x/qpsc-offline-microscope/config_Offline.yml"));
        assertFalse(
                BundledScopeInstaller.isBundleConfigPath(Bundle.OFFLINE, "/x/qpsc-demo-microscope/config_Demo.yml"));
        // A real scope at a normal location must not be mistaken for either.
        assertFalse(BundledScopeInstaller.isBundleConfigPath(Bundle.OFFLINE, "/configs/config_OWS3.yml"));
        assertFalse(BundledScopeInstaller.isBundleConfigPath(Bundle.OFFLINE, "/somewhere/else/config_Offline.yml"));
        assertFalse(BundledScopeInstaller.isBundleConfigPath(Bundle.OFFLINE, ""));
        assertFalse(BundledScopeInstaller.isBundleConfigPath(Bundle.OFFLINE, null));
    }

    @Test
    void bundleNamesDriveSidecarResolution() {
        assertEquals("config_Demo.yml", Bundle.DEMO.configFileName());
        assertEquals("/qupath/ext/qpsc/templates/demo/", Bundle.DEMO.resourceBase());
        assertTrue(Bundle.DEMO.sidecars().contains("autofocus_Demo.yml"), "the demo ships an autofocus sidecar");
        assertEquals("/qupath/ext/qpsc/templates/offline/", Bundle.OFFLINE.resourceBase());
    }
}
