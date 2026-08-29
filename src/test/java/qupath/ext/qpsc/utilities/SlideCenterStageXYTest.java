package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import qupath.lib.projects.ProjectImageEntry;

/**
 * The measured slide centre the Stage Map draws slot previews at.
 *
 * <p>A slide sits where it was dropped, not where its holder slot nominally is -- the measured
 * landing error on the first alignment point of a multi-slide run was a median 613 um, worst
 * 1507. So an alignment stamps where the slide actually is, and the map prefers that over slot
 * geometry. These tests pin the two properties that make the number safe to consume without any
 * frame knowledge at the reading end.
 */
class SlideCenterStageXYTest {

    @SuppressWarnings("unchecked")
    private static ProjectImageEntry<BufferedImage> entry() {
        ProjectImageEntry<BufferedImage> e = mock(ProjectImageEntry.class);
        Map<String, String> md = new HashMap<>();
        when(e.getMetadata()).thenReturn(md);
        return e;
    }

    @Test
    @DisplayName("Round-trips the stage point the transform maps the image centre to")
    void roundTrip() {
        ProjectImageEntry<BufferedImage> e = entry();
        // A plain scale+translate: 0.5 um per pixel, image origin at stage (10000, 20000).
        AffineTransform t = new AffineTransform();
        t.translate(10000, 20000);
        t.scale(0.5, 0.5);

        ImageMetadataManager.setSlideCenterStageXY(e, t, 4000, 2000);

        double[] centre = ImageMetadataManager.getSlideCenterStageXY(e);
        assertNotNull(centre);
        assertArrayEquals(new double[] {10000 + 1000, 20000 + 500}, centre, 1e-9);
    }

    @Test
    @DisplayName("The image centre is flip-invariant, so a flip disagreement cannot corrupt it")
    void flipInvariant() {
        // Same physical mapping expressed two ways: unflipped, and mirrored in both axes about
        // the image centre. (W/2, H/2) is the same point in either pixel space, so the stamped
        // stage centre must be identical -- which is why the centre is what gets stored rather
        // than a corner or an origin.
        int w = 4000;
        int h = 2000;

        AffineTransform unflipped = new AffineTransform();
        unflipped.translate(10000, 20000);
        unflipped.scale(0.5, 0.5);

        AffineTransform flipped = new AffineTransform(unflipped);
        flipped.translate(w, h);
        flipped.scale(-1, -1);

        ProjectImageEntry<BufferedImage> a = entry();
        ProjectImageEntry<BufferedImage> b = entry();
        ImageMetadataManager.setSlideCenterStageXY(a, unflipped, w, h);
        ImageMetadataManager.setSlideCenterStageXY(b, flipped, w, h);

        assertArrayEquals(
                ImageMetadataManager.getSlideCenterStageXY(a), ImageMetadataManager.getSlideCenterStageXY(b), 1e-9);
    }

    @Test
    @DisplayName("Passing the wrong pixel frame is detectable, not silently plausible")
    void wrongFrameIsWrongByTheDownsampleFactor() {
        // The mistake this API's javadoc warns about: a slide alignment tagged
        // pixelFrame="macro" is in the ENTRY's full-resolution frame, not the macro
        // thumbnail's. Feeding thumbnail dimensions produces a stage point that looks
        // reasonable but is off by the downsample -- asserted here so the contract is
        // pinned rather than assumed.
        AffineTransform fullResToStage = new AffineTransform();
        fullResToStage.translate(10000, 20000);
        fullResToStage.scale(0.5, 0.5);

        ProjectImageEntry<BufferedImage> right = entry();
        ProjectImageEntry<BufferedImage> wrong = entry();
        ImageMetadataManager.setSlideCenterStageXY(right, fullResToStage, 4000, 2000);
        ImageMetadataManager.setSlideCenterStageXY(wrong, fullResToStage, 250, 125); // 16x downsample

        double[] r = ImageMetadataManager.getSlideCenterStageXY(right);
        double[] w = ImageMetadataManager.getSlideCenterStageXY(wrong);
        assertTrue(Math.hypot(r[0] - w[0], r[1] - w[1]) > 900, "wrong frame must not land near the right one");
    }

    @Test
    @DisplayName("Unmeasured slides report null so the map falls back to slot geometry")
    void unmeasuredIsNull() {
        assertNull(ImageMetadataManager.getSlideCenterStageXY(entry()));
        assertNull(ImageMetadataManager.getSlideCenterStageXY(null));
    }

    @Test
    @DisplayName("Nothing is stamped from a degenerate transform or degenerate dimensions")
    void refusesDegenerateInput() {
        ProjectImageEntry<BufferedImage> e = entry();
        ImageMetadataManager.setSlideCenterStageXY(e, null, 4000, 2000);
        ImageMetadataManager.setSlideCenterStageXY(e, new AffineTransform(), 0, 2000);
        ImageMetadataManager.setSlideCenterStageXY(e, new AffineTransform(), 4000, -1);
        assertNull(ImageMetadataManager.getSlideCenterStageXY(e));
        assertEquals(0, e.getMetadata().size());
    }
}
