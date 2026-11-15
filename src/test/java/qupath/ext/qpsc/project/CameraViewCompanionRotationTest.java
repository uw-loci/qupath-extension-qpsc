package qupath.ext.qpsc.project;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpsc.geometry.ImageFlipHelper;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.images.servers.RotatedImageServer;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;

/**
 * A Camera View companion built from a "(rotated N)" entry must carry that rotation, so the
 * next run finds it instead of building another copy.
 */
class CameraViewCompanionRotationTest {

    @TempDir
    Path tmp;

    @Test
    void aMirrorOfARotatedEntryKeepsItsRotationAndIsFoundAgain() throws Exception {
        File dir = tmp.resolve("proj").toFile();
        assertTrue(dir.mkdirs());
        Project<BufferedImage> project = Projects.createProject(dir, BufferedImage.class);

        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.PINK);
        g.fillRect(0, 0, 64, 48);
        g.dispose();
        Path file = tmp.resolve("Slide_1.tif");
        assertTrue(ImageIO.write(img, "tiff", file.toFile()));
        ProjectImageEntry<BufferedImage> base;
        try (ImageServer<BufferedImage> server = ImageServers.buildServer(file.toUri())) {
            base = project.addImage(server.getBuilder());
        }
        base.setImageName("Slide_1.tif");

        ProjectImageEntry<BufferedImage> rotated = QPProjectFunctions.createRotatedDuplicate(
                project, base, RotatedImageServer.Rotation.ROTATE_270, "Slide_1");
        assertEquals(270, ImageMetadataManager.getRotationDeg(rotated));

        // The single-slide path: mirror the rotated entry, then mark it as the companion.
        ProjectImageEntry<BufferedImage> companion =
                QPProjectFunctions.createFlippedDuplicate(project, rotated, true, true, "Slide_1");
        ImageMetadataManager.setCameraView(companion, true);

        assertEquals(270, ImageMetadataManager.getRotationDeg(companion));
        assertSame(
                companion,
                ImageFlipHelper.findFlippedSibling(project, rotated, true, true),
                "the rotated entry finds its companion, so no second copy is built");
        assertNull(
                ImageFlipHelper.findFlippedSibling(project, base, true, true),
                "the unrotated original does not pick up the rotated companion");
    }
}
