package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.projects.Project;
import qupath.lib.projects.Projects;

/** The alignment check's refocused Z becomes the slide's seed, and is newer than the set-up's. */
class SlideFocusSeedPersistenceTest {

    @TempDir
    Path tmp;

    @Test
    void theCheckedFocusReplacesTheSeedAndIsSeenAsNewerThanTheSetUp() throws Exception {
        File dir = tmp.resolve("proj").toFile();
        assertTrue(dir.mkdirs());
        Project<BufferedImage> project = Projects.createProject(dir, BufferedImage.class);
        Path alignments = Files.createDirectories(dir.toPath().resolve("alignmentFiles"));
        Files.writeString(
                alignments.resolve("MetroHealth_189_alignment.json"),
                "{\"sampleName\":\"MetroHealth_189\",\"focusZ\":72.6,\"transform\":[1,0,0,1,0,0]}");

        Instant setUp = Instant.now().minusSeconds(60);
        assertNull(
                AffineTransformManager.loadSlideFocusZCheckedSince(project, "MetroHealth_189", setUp),
                "a set-up value is not a checked one");

        assertTrue(AffineTransformManager.updateSlideFocusZ(project, "MetroHealth_189", 27.6));
        assertEquals(27.6, AffineTransformManager.loadSlideFocusZ(project, "MetroHealth_189"), 1e-9);
        assertEquals(27.6, AffineTransformManager.loadSlideFocusZCheckedSince(project, "MetroHealth_189", setUp), 1e-9);
        assertNull(
                AffineTransformManager.loadSlideFocusZCheckedSince(
                        project, "MetroHealth_189", Instant.now().plusSeconds(60)),
                "a set-up after the check measured the slide again");
        assertTrue(Files.readString(alignments.resolve("MetroHealth_189_alignment.json"))
                .contains("transform"));
    }
}
