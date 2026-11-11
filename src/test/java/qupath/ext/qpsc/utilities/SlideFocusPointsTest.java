package qupath.ext.qpsc.utilities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.projects.Project;
import qupath.lib.projects.Projects;

/**
 * Focus points measured on a slide are kept until there are enough for a plane.
 *
 * <p>2026-11-10, PPM slot 4 (a slide tilted 7.5 um/mm): refinement focused at two points,
 * too few for a plane, and they were dropped. The alignment check then focused and matched
 * a third, but only its Z went on, to a first autofocus tile 21 mm away, 70 um off focus.
 * The three points are the ones below; the plane through them predicts that tile at 210 um
 * and the eight clear autofocus peaks found later to within 4 um.
 */
class SlideFocusPointsTest {

    private static final String SLIDE = "MetroHealth_189";
    private static final String OBJECTIVE = "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001";
    private static final double[] REFINE_1 = {-47116, -29017, 202.8};
    private static final double[] REFINE_2 = {-49739, -8774, 188.6};
    private static final double[] CHECK = {-42826.8, -10508.7, 139.4};

    @TempDir
    Path tmp;

    private Project<BufferedImage> projectWithAlignment() throws Exception {
        File dir = tmp.resolve("proj").toFile();
        assertThat(dir.mkdirs()).isTrue();
        Project<BufferedImage> project = Projects.createProject(dir, BufferedImage.class);
        Path alignments = Files.createDirectories(dir.toPath().resolve("alignmentFiles"));
        Files.writeString(
                alignments.resolve(SLIDE + "_alignment.json"),
                "{\"sampleName\": \"" + SLIDE + "\"}",
                StandardCharsets.UTF_8);
        return project;
    }

    @Test
    void twoRefinementPointsAreKeptAndTheAlignmentCheckMakesThePlane() throws Exception {
        Project<BufferedImage> project = projectWithAlignment();

        var none = AffineTransformManager.recordSlideFocusPoints(
                project, SLIDE, List.of(REFINE_1, REFINE_2), OBJECTIVE, true);
        assertThat(none).isNull();
        assertThat(AffineTransformManager.loadSlideFocusPoints(project, SLIDE, OBJECTIVE))
                .hasSize(2);

        var surface = AffineTransformManager.recordSlideFocusPoints(project, SLIDE, List.of(CHECK), OBJECTIVE, false);
        assertThat(surface).isNotNull();
        assertThat(surface.pointCount()).isEqualTo(3);
        // The first autofocus tile of the region, where the run was sent to 139.4 um.
        assertThat(surface.predict(-47431.8, -31910.4)).isCloseTo(210.0, within(0.5));
        // A clear autofocus peak found in the run: 218.7 um.
        assertThat(surface.predict(-49045.7, -30708.7)).isCloseTo(218.7, within(3.0));
        assertThat(AffineTransformManager.loadSlideFocusSurface(project, SLIDE)).isNotNull();
    }

    @Test
    void aNewRefinementStartsAfresh() throws Exception {
        Project<BufferedImage> project = projectWithAlignment();
        AffineTransformManager.recordSlideFocusPoints(
                project, SLIDE, List.of(REFINE_1, REFINE_2, CHECK), OBJECTIVE, true);
        assertThat(AffineTransformManager.loadSlideFocusSurface(project, SLIDE)).isNotNull();

        // The slide was placed again: one new point, and nothing from before.
        var surface = AffineTransformManager.recordSlideFocusPoints(
                project, SLIDE, List.of(new double[] {-45000, -20000, 50.0}), OBJECTIVE, true);
        assertThat(surface).isNull();
        assertThat(AffineTransformManager.loadSlideFocusPoints(project, SLIDE, OBJECTIVE))
                .hasSize(1);
        assertThat(AffineTransformManager.loadSlideFocusSurface(project, SLIDE)).isNull();
    }

    @Test
    void aRefinementWithNoPointsClearsTheOldPointsButNotTheSurface() throws Exception {
        Project<BufferedImage> project = projectWithAlignment();
        AffineTransformManager.recordSlideFocusPoints(
                project, SLIDE, List.of(REFINE_1, REFINE_2, CHECK), OBJECTIVE, true);

        AffineTransformManager.recordSlideFocusPoints(project, SLIDE, List.of(), OBJECTIVE, true);

        assertThat(AffineTransformManager.loadSlideFocusPoints(project, SLIDE, OBJECTIVE))
                .isEmpty();
        assertThat(AffineTransformManager.loadSlideFocusSurface(project, SLIDE)).isNotNull();
    }

    @Test
    void pointsFromAnotherObjectiveAreNotMixedIn() throws Exception {
        Project<BufferedImage> project = projectWithAlignment();
        AffineTransformManager.recordSlideFocusPoints(
                project, SLIDE, List.of(REFINE_1, REFINE_2), "LOCI_OBJECTIVE_10X", true);

        var surface = AffineTransformManager.recordSlideFocusPoints(project, SLIDE, List.of(CHECK), OBJECTIVE, false);

        assertThat(surface).isNull();
        assertThat(AffineTransformManager.loadSlideFocusPoints(project, SLIDE, OBJECTIVE))
                .hasSize(1);
    }

    @Test
    void aSurfaceFromMorePointsIsNotReplacedByAThreePointFit() throws Exception {
        Project<BufferedImage> project = projectWithAlignment();
        var regionSurface = new AffineTransformManager.SlideFocusSurface(
                190.0, -7.9, -0.6, -46000, -28000, 1200, 0.7, OBJECTIVE, "2026-11-10T20:00:00Z");
        assertThat(AffineTransformManager.saveSlideFocusSurface(project, SLIDE, regionSurface, null))
                .isTrue();
        AffineTransformManager.recordSlideFocusPoints(project, SLIDE, List.of(REFINE_1, REFINE_2), OBJECTIVE, false);

        var surface = AffineTransformManager.recordSlideFocusPoints(project, SLIDE, List.of(CHECK), OBJECTIVE, false);

        assertThat(surface).isNull();
        assertThat(AffineTransformManager.loadSlideFocusSurface(project, SLIDE).pointCount())
                .isEqualTo(1200);
    }

    @Test
    void anAlignmentResaveKeepsThePoints() throws Exception {
        String src = Files.readString(Path.of("src/main/java/qupath/ext/qpsc/utilities/AffineTransformManager.java"));
        assertThat(src).contains("alignmentData.put(\"focusPoints\", existingData.get(\"focusPoints\"))");
    }

    @Test
    void theAlignmentCheckAddsOnlyAConfirmedPoint() throws Exception {
        String verify = Files.readString(
                Path.of("src/main/java/qupath/ext/qpsc/controller/workflow/AlignmentVerification.java"));
        // Only the CONFIRMED verdict carries the point: an in-focus field that matched the
        // macro image. A field that focused but did not match may be the wrong plane.
        assertThat(verify.split("focusHere\\)", -1).length - 1).isEqualTo(1);
        int confirmed = verify.indexOf("if (status == Status.CONFIRMED)");
        int misaligned = verify.indexOf("beyond the %.0f um tolerance", confirmed);
        int handedOver = verify.indexOf("focusHere)", confirmed);
        assertThat(handedOver).isBetween(confirmed, misaligned);

        String workflow =
                Files.readString(Path.of("src/main/java/qupath/ext/qpsc/controller/ExistingImageWorkflowV2.java"));
        assertThat(workflow).contains("recordConfirmedFocusPoint(state, verdict);");
        assertThat(workflow).contains("project, lookupKey, List.of(point.toArray()), objective, false);");
        assertThat(workflow).contains("project, lookupKey, focusPoints, state.objective, true);");
    }
}
