package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpsc.config.MicroscopeConfigManager;

/** Which saved slide alignment file a lookup accepts. */
class SlideAlignmentLookupTest {

    private static final String TRANSFORM = "\"transform\":[1.0,0.0,0.0,1.0,5.0,7.0]";

    @TempDir
    Path project;

    private File write(String name, String json) throws Exception {
        Path folder = Files.createDirectories(project.resolve("alignmentFiles"));
        return Files.writeString(folder.resolve(name), json).toFile();
    }

    private static AffineTransformManager.SlideAlignmentResult read(File file, String activeScope) throws Exception {
        Method reader =
                AffineTransformManager.class.getDeclaredMethod("readAlignmentJsonWithFrame", File.class, String.class);
        reader.setAccessible(true);
        return (AffineTransformManager.SlideAlignmentResult) reader.invoke(null, file, activeScope);
    }

    @Test
    void aFileForTheActiveScopeLoads() throws Exception {
        var result = read(write("a.json", "{\"microscope\":\"PPM\"," + TRANSFORM + "}"), "PPM");
        assertEquals(5.0, result.getTransform().getTranslateX());
        assertEquals(7.0, result.getTransform().getTranslateY());
    }

    @Test
    void aFileForAnotherScopeIsIgnored() throws Exception {
        assertNull(read(write("a.json", "{\"microscope\":\"OWS3\"," + TRANSFORM + "}"), "PPM"));
    }

    @Test
    void aFileNamingNoScopeIsIgnoredOnceAScopeIsActive() throws Exception {
        assertNull(read(write("a.json", "{" + TRANSFORM + "}"), "PPM"));
    }

    @Test
    void withNoActiveScopeAnyFileLoads() throws Exception {
        assertNotNull(read(write("a.json", "{\"microscope\":\"OWS3\"," + TRANSFORM + "}"), null));
        assertNotNull(read(write("b.json", "{" + TRANSFORM + "}"), null));
    }

    @Test
    void aFileWithoutATransformIsNoAlignment() throws Exception {
        assertNull(read(write("a.json", "{\"microscope\":\"PPM\"}"), "PPM"));
    }

    @Test
    void bothLoadersFindTheSameFile() throws Exception {
        String scope = "PPM";
        var mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        if (mgr != null && mgr.getMicroscopeName() != null) {
            scope = mgr.getMicroscopeName();
        }
        write("sample_alignment.json", "{\"microscope\":\"" + scope + "\"," + TRANSFORM + "}");
        var transform = AffineTransformManager.loadSlideAlignmentFromDirectory(project.toFile(), "sample");
        var result = AffineTransformManager.loadSlideAlignmentWithFrameFromDirectory(project.toFile(), "sample");
        assertNotNull(transform);
        assertEquals(transform, result.getTransform());
        assertNull(AffineTransformManager.loadSlideAlignmentFromDirectory(project.toFile(), "other"));
    }
}
