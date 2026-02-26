package qupath.ext.qpsc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;

/**
 * Unit tests for QPProjectFunctions.
 */
@ExtendWith(MockitoExtension.class)
class QPProjectFunctionsTest {

    @TempDir Path tmp;

    @Test
    void testCreateProjectFolder_createsNewProject() throws IOException {
        // arrange
        String projectsRoot = tmp.resolve("projects").toString();
        String sampleLabel = "MySample";

        // act
        Project<BufferedImage> project = QPProjectFunctions.createProject(projectsRoot, sampleLabel);

        // assert
        assertNotNull(project, "Should return a non-null Project");
        Path sampleDir = tmp.resolve("projects").resolve(sampleLabel);
        assertTrue(Files.isDirectory(sampleDir), "Sample directory must be created");
        // .qpproj file created
        boolean hasQpproj = Files.list(sampleDir)
                .anyMatch(p -> p.toString().endsWith(".qpproj"));
        assertTrue(hasQpproj, "Should have created a .qpproj file");
        // SlideImages folder
        assertTrue(Files.isDirectory(sampleDir.resolve("SlideImages")),
                "Should have created SlideImages directory");
    }

    @Test
    void testCreateProjectFolder_loadsExistingProject() throws IOException {
        String projectsRoot = tmp.resolve("projects").toString();
        String sampleLabel = "ReloadTest";

        // first call creates it
        Project<BufferedImage> first = QPProjectFunctions.createProject(projectsRoot, sampleLabel);
        // count .qpproj files
        Path sampleDir = tmp.resolve("projects").resolve(sampleLabel);
        long count1 = Files.list(sampleDir).filter(p -> p.toString().endsWith(".qpproj")).count();

        // second call should load the same project (not duplicate .qpproj)
        Project<BufferedImage> second = QPProjectFunctions.createProject(projectsRoot, sampleLabel);
        long count2 = Files.list(sampleDir).filter(p -> p.toString().endsWith(".qpproj")).count();

        assertNotNull(second);
        assertEquals(count1, count2,
                "Reloading an existing project should not create additional .qpproj files");
    }

    @Test
    void testCreateAndOpenQuPathProject_noCurrentImage(@TempDir Path tmpDir) throws IOException {
        // mock QuPathGUI
        QuPathGUI gui = mock(QuPathGUI.class);

        // call under test -- method now takes String enhancedModality instead of ObservableList
        Map<String,Object> result = QPProjectFunctions.createAndOpenQuPathProject(
                gui,
                tmpDir.toString(),
                "SampleA",
                "10x_bf",
                /*flipX*/ false,
                /*flipY*/ true
        );

        assertNotNull(result.get("currentQuPathProject"), "Project must be returned");
        String modeWithIndex = (String) result.get("imagingModeWithIndex");
        assertTrue(modeWithIndex.startsWith("10x_bf"),
                "imagingModeWithIndex should start with the enhancedModality value");
        assertNull(result.get("matchingImage"),
                "When no image is open, matchingImage should be null");
        assertTrue(Files.isDirectory(tmpDir.resolve("SampleA").resolve(modeWithIndex)),
                "tempTileDirectory must exist on disk");
    }
}
