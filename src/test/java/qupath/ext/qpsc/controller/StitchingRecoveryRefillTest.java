package qupath.ext.qpsc.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpsc.model.StitchingMetadata;
import qupath.ext.qpsc.project.ProjectEntryReservation;
import qupath.ext.qpsc.stitching.StitchOutputPlanner.Kind;
import qupath.ext.qpsc.stitching.StitchOutputPlanner.ReservedOutput;
import qupath.ext.qpsc.stitching.StitchPlaceholderImage;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;

/** Re-stitch Tiles fills the placeholder an earlier run left unfilled instead of adding a second entry. */
class StitchingRecoveryRefillTest {

    @TempDir
    Path tmp;

    private static StitchingMetadata metadata() {
        return new StitchingMetadata(
                null,
                0.0,
                0.0,
                false,
                false,
                "L004",
                "ppm",
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

    private Project<BufferedImage> projectWithFailedReservation() throws Exception {
        File dir = tmp.resolve("proj").toFile();
        assertTrue(dir.mkdirs());
        Project<BufferedImage> project = Projects.createProject(dir, BufferedImage.class);
        List<ReservedOutput> plan = List.of(
                new ReservedOutput("7.0", "L004_ppm_20x_003_Tissue_1_7.0.ome.tif", "7.0", Kind.ANGLE),
                new ReservedOutput("-7.0", "L004_ppm_20x_003_Tissue_1_-7.0.ome.tif", "-7.0", Kind.ANGLE));
        ProjectEntryReservation r = ProjectEntryReservation.reserve(project, plan, metadata(), "PPM");
        assertNotNull(r);
        r.fail("7.0", "the run stopped");
        r.fail("-7.0", "the run stopped");
        return project;
    }

    @Test
    void aFailedPlaceholderIsFoundByItsSampleRegionAndAngleAndFilledInPlace() throws Exception {
        Project<BufferedImage> project = projectWithFailedReservation();

        ProjectImageEntry<BufferedImage> found = StitchingRecoveryWorkflow.findUnfilledReservation(
                project.getImageList(), "L004", "Tissue_1", StitchingRecoveryWorkflow.angleTokens("7.0", null), "20x");
        assertNotNull(found);
        assertEquals("L004_ppm_20x_003_Tissue_1_7.0.ome.tif", found.getImageName());

        BufferedImage img = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, 32, 24);
        g.dispose();
        File stitched = tmp.resolve(found.getImageName()).toFile();
        assertTrue(ImageIO.write(img, "tiff", stitched));
        ProjectEntryReservation.fillEntry(
                found, StitchPlaceholderImage.ensure(tmp.resolve("proj")).toUri(), stitched, null);

        assertEquals(stitched.toURI(), found.getURIs().iterator().next());
        assertFalse(ProjectEntryReservation.isUnfilled(found));
        assertEquals(2, project.getImageList().size(), "filling adds no entry");
    }

    @Test
    void noMatchWhenTheRegionOrObjectiveDiffersOrTheChoiceIsAmbiguous() throws Exception {
        Project<BufferedImage> project = projectWithFailedReservation();
        List<ProjectImageEntry<BufferedImage>> entries = project.getImageList();

        assertNull(
                StitchingRecoveryWorkflow.findUnfilledReservation(entries, "L004", "Tissue_2", List.of("7.0"), "20x"));
        assertNull(
                StitchingRecoveryWorkflow.findUnfilledReservation(entries, "L004", "Tissue_1", List.of("7.0"), "10x"));
        assertNull(
                StitchingRecoveryWorkflow.findUnfilledReservation(entries, "L004", "Tissue_1", List.of(), "20x"),
                "a folder with no angle does not take an angle's placeholder");
        assertNull(
                StitchingRecoveryWorkflow.findUnfilledReservation(
                        entries, "L004", "Tissue_1", List.of("7.0", "-7.0"), "20x"),
                "two candidates: nothing is guessed");
    }
}
