package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpsc.model.StitchingMetadata;
import qupath.ext.qpsc.utilities.StitchOutputPlanner.Kind;
import qupath.ext.qpsc.utilities.StitchOutputPlanner.ReservedOutput;
import qupath.lib.images.ImageData;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;

/** Reserved entries exist with their metadata before the stitch, and are filled in place. */
class ProjectEntryReservationTest {

    @TempDir
    Path tmp;

    private static StitchingMetadata metadata() {
        return new StitchingMetadata(
                null,
                120.0,
                340.0,
                false,
                false,
                "L004",
                "bf",
                "20x",
                null,
                "Tissue_1",
                3,
                null,
                null,
                null,
                null,
                null,
                null,
                "LOCI_DETECTOR_X",
                "PPM",
                null);
    }

    private Project<BufferedImage> project() throws Exception {
        File dir = tmp.resolve("proj").toFile();
        assertTrue(dir.mkdirs());
        return Projects.createProject(dir, BufferedImage.class);
    }

    private static Path writeTiff(Path file, int w, int h, Color c) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        assertTrue(ImageIO.write(img, "tiff", file.toFile()));
        return file;
    }

    @Test
    void reserveAddsPlaceholderEntriesWithMetadataThenFillRepointsOne() throws Exception {
        Project<BufferedImage> project = project();
        List<ReservedOutput> plan = List.of(
                new ReservedOutput("7.0", "L004_bf_20x_003_Tissue_1_7.0.ome.tif", "7.0", Kind.ANGLE),
                new ReservedOutput("-7.0", "L004_bf_20x_003_Tissue_1_-7.0.ome.tif", "-7.0", Kind.ANGLE));

        ProjectEntryReservation r = ProjectEntryReservation.reserve(project, plan, metadata(), "PPM");
        assertNotNull(r);
        assertEquals(2, project.getImageList().size());
        Path placeholder = tmp.resolve("proj").resolve("qpsc").resolve("stitching-pending.tif");
        assertTrue(Files.isRegularFile(placeholder), "the placeholder picture is written under the project");

        ProjectImageEntry<BufferedImage> e = r.entry("7.0");
        assertNotNull(e);
        assertEquals("L004_bf_20x_003_Tissue_1_7.0.ome.tif", e.getImageName());
        assertEquals(placeholder.toUri(), e.getURIs().iterator().next());
        assertEquals("pending", e.getMetadata().get(ProjectEntryReservation.STATUS_KEY));
        assertEquals("L004", e.getMetadata().get(ImageMetadataManager.SAMPLE_NAME));
        assertEquals("bf", e.getMetadata().get(ImageMetadataManager.MODALITY));
        assertEquals("20x", e.getMetadata().get(ImageMetadataManager.OBJECTIVE));
        assertEquals("7.0", e.getMetadata().get(ImageMetadataManager.ANGLE));
        assertEquals("Tissue_1", e.getMetadata().get(ImageMetadataManager.ANNOTATION_NAME));
        assertEquals("3", e.getMetadata().get(ImageMetadataManager.IMAGE_INDEX));
        assertEquals("PPM", e.getMetadata().get(ImageMetadataManager.ACQUIRED_ON_MICROSCOPE));
        assertTrue(r.has("7.0"));
        assertFalse(r.has("nope"));

        // The stitched file lands (a small RGB TIFF stands in for the OME-TIFF).
        Path stitched = writeTiff(tmp.resolve("L004_bf_20x_003_Tissue_1_7.0.ome.tif"), 64, 48, Color.ORANGE);
        ProjectImageEntry<BufferedImage> filled = r.fill("7.0", stitched.toFile(), null);
        assertEquals(e.getID(), filled.getID());
        assertEquals(stitched.toUri(), filled.getURIs().iterator().next(), "the entry now points at the real file");
        assertNull(filled.getMetadata().get(ProjectEntryReservation.STATUS_KEY));
        assertEquals("", filled.getDescription());
        assertFalse(r.has("7.0"));
        assertTrue(r.isFilled("7.0"));
        ImageData<BufferedImage> data = filled.readImageData();
        try {
            assertEquals(64, data.getServer().getWidth());
            assertNotNull(data.getImageType());
        } finally {
            data.getServer().close();
        }
        assertEquals(2, project.getImageList().size(), "filling adds no entry");

        // The other output never arrived: it is dropped when the job finishes.
        assertEquals(1, r.dropUnfilledNow("test"));
        assertEquals(1, project.getImageList().size());
        assertEquals(filled.getID(), project.getImageList().get(0).getID());
    }

    @Test
    void failKeepsTheEntryWithTheReason() throws Exception {
        Project<BufferedImage> project = project();
        List<ReservedOutput> plan =
                List.of(new ReservedOutput("Tissue_1", "L004_bf_20x_003_Tissue_1.ome.tif", null, Kind.SINGLE));
        ProjectEntryReservation r = ProjectEntryReservation.reserve(project, plan, metadata(), null);
        assertNotNull(r);
        r.failUnfilledNow("No channel tiles found");
        ProjectImageEntry<BufferedImage> e = r.entry("Tissue_1");
        assertEquals("failed", e.getMetadata().get(ProjectEntryReservation.STATUS_KEY));
        assertTrue(e.getDescription().contains("No channel tiles found"));
        assertFalse(r.has("Tissue_1"));
        assertEquals(0, r.dropUnfilledNow("test"), "a failed entry is kept for the operator");
        assertEquals(1, project.getImageList().size());
    }

    @Test
    void anEmptyPlanReservesNothing() throws Exception {
        assertNull(ProjectEntryReservation.reserve(project(), List.of(), metadata(), null));
    }
}
