package qupath.ext.qpsc.modality.widefield.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpsc.modality.Channel;
import qupath.ext.qpsc.utilities.BundledScopeInstaller.Bundle;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * The channel picker shows the selected modality's library. The demo config lists
 * Fluorescence before lcpolscope, and the LC-PolScope acquisition dialog used to offer
 * DAPI..Cy5 as its "states" (OWS3's BF_IF, listed after Fluorescence, had the same fault).
 */
class ChannelLibraryForModalityTest {

    @TempDir
    Path dir;

    private MicroscopeConfigManager demo() throws IOException {
        Bundle b = Bundle.DEMO;
        copy(b.resourceBase() + b.configFileName(), dir.resolve(b.configFileName()));
        for (String sidecar : b.sidecars()) {
            copy(b.resourceBase() + sidecar, dir.resolve(sidecar));
        }
        Files.createDirectories(dir.resolve("resources"));
        copy(b.ownResources(), dir.resolve("resources").resolve("resources_LOCI.yml"));
        Path config = dir.resolve(b.configFileName());
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(config.toString());
        mgr.reload(config.toString());
        return mgr;
    }

    private static void copy(String resource, Path dest) throws IOException {
        try (InputStream is = ChannelLibraryForModalityTest.class.getResourceAsStream(resource)) {
            assertNotNull(is, resource);
            Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test
    void eachChannelModalityGetsItsOwnLibrary() throws IOException {
        MicroscopeConfigManager mgr = demo();
        var lc = WidefieldChannelBoundingBoxUI.libraryFor(mgr, "lcpolscope");
        assertEquals("lcpolscope", lc.getKey());
        assertEquals(
                List.of("State0", "State1", "State2", "State3", "State4"),
                lc.getValue().stream().map(Channel::id).toList());
        var fl = WidefieldChannelBoundingBoxUI.libraryFor(mgr, "fluorescence");
        assertEquals("Fluorescence", fl.getKey(), "matched ignoring case");
        assertEquals(4, fl.getValue().size());
        assertNull(
                WidefieldChannelBoundingBoxUI.libraryFor(mgr, "Brightfield"),
                "a modality without channels gets none, not another modality's");
        assertEquals(
                "Fluorescence",
                WidefieldChannelBoundingBoxUI.libraryFor(mgr, null).getKey(),
                "no modality named: the first library, as before");
    }
}
