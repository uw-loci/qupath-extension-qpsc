package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Unit tests for {@link AlignmentHelper#bakeFlipDeltaForCurrentEntry}.
 *
 * <p>The bake delta reconciles the pixel frame a saved alignment was built in
 * with the frame of the entry the workflow actually runs on. It must be
 * computed against the post-flip-switch entry; these tests pin the
 * frame-matching and frame-differing cases plus the unknown-flip passthrough.
 */
class AlignmentHelperBakeTest {

    @SuppressWarnings("unchecked")
    private static ProjectImageEntry<BufferedImage> entryWithFlip(boolean flipX, boolean flipY) {
        ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
        Map<String, String> meta = new HashMap<>();
        meta.put("flip_x", flipX ? "1" : "0");
        meta.put("flip_y", flipY ? "1" : "0");
        when(entry.getMetadata()).thenReturn(meta);
        return entry;
    }

    @SuppressWarnings("unchecked")
    private static ImageData<BufferedImage> imageData(int width, int height) {
        ImageData<BufferedImage> data = mock(ImageData.class);
        ImageServer<BufferedImage> server = mock(ImageServer.class);
        when(server.getWidth()).thenReturn(width);
        when(server.getHeight()).thenReturn(height);
        when(data.getServer()).thenReturn(server);
        return data;
    }

    @Test
    void noBakeWhenAlignFrameMatchesEntryFrame() {
        AffineTransform raw = AffineTransform.getTranslateInstance(5, 7);
        AffineTransform result = AlignmentHelper.bakeFlipDeltaForCurrentEntry(
                raw, Boolean.TRUE, Boolean.TRUE, entryWithFlip(true, true), imageData(100, 100));
        assertSame(raw, result, "matching align/entry frames must return the raw transform untouched");
    }

    @Test
    void bakesFlipWhenAlignFrameDiffersFromEntryFrame() {
        // raw = identity; alignment built unflipped, entry is flipped XY -> bake a (true,true) flip.
        AffineTransform raw = new AffineTransform();
        AffineTransform result = AlignmentHelper.bakeFlipDeltaForCurrentEntry(
                raw, Boolean.FALSE, Boolean.FALSE, entryWithFlip(true, true), imageData(100, 100));
        assertNotSame(raw, result, "differing frames must produce a new, composed transform");
        // composed = raw . flip(100,100); flip maps (x,y) -> (100-x, 100-y); raw is identity.
        Point2D out = result.transform(new Point2D.Double(10, 20), null);
        assertEquals(90.0, out.getX(), 1e-9);
        assertEquals(80.0, out.getY(), 1e-9);
    }

    @Test
    void bakesSingleAxisFlip() {
        // alignment unflipped, entry flipped in X only -> X mirrors, Y unchanged.
        AffineTransform result = AlignmentHelper.bakeFlipDeltaForCurrentEntry(
                new AffineTransform(), Boolean.FALSE, Boolean.FALSE, entryWithFlip(true, false), imageData(200, 50));
        Point2D out = result.transform(new Point2D.Double(30, 40), null);
        assertEquals(170.0, out.getX(), 1e-9);
        assertEquals(40.0, out.getY(), 1e-9);
    }

    @Test
    void returnsRawWhenAlignFlipUnknown() {
        AffineTransform raw = AffineTransform.getScaleInstance(2, 3);
        assertSame(
                raw,
                AlignmentHelper.bakeFlipDeltaForCurrentEntry(
                        raw, null, null, entryWithFlip(true, true), imageData(100, 100)),
                "null align flip (BoundingBox fallback / legacy JSON) must pass the raw transform through");
    }

    @Test
    void returnsRawWhenEntryNull() {
        AffineTransform raw = AffineTransform.getTranslateInstance(1, 2);
        assertSame(
                raw,
                AlignmentHelper.bakeFlipDeltaForCurrentEntry(
                        raw, Boolean.FALSE, Boolean.FALSE, null, imageData(100, 100)));
    }
}
