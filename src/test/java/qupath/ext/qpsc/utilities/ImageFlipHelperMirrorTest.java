package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/**
 * Unit tests for {@link ImageFlipHelper#mirrorAnnotationsToSibling}.
 *
 * <p>The flipped sibling's annotation set must be a deterministic mirror of the
 * base's: every run-from-base <i>replaces</i> it, so unsaved annotations are
 * carried and re-runs never accumulate duplicates.
 */
class ImageFlipHelperMirrorTest {

    private static PathObject namedAnnotation(String name, double x, double y) {
        PathObject ann =
                PathObjects.createAnnotationObject(ROIs.createRectangleROI(x, y, 10, 10, ImagePlane.getDefaultPlane()));
        if (name != null) {
            ann.setName(name);
        }
        return ann;
    }

    @SuppressWarnings("unchecked")
    private static ProjectImageEntry<BufferedImage> siblingEntry(PathObjectHierarchy siblingHierarchy)
            throws Exception {
        ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
        ImageData<BufferedImage> sibData = mock(ImageData.class);
        when(sibData.getHierarchy()).thenReturn(siblingHierarchy);
        when(entry.readImageData()).thenReturn(sibData);
        when(entry.getImageName()).thenReturn("slide (flipped XY)");
        return entry;
    }

    @Test
    void mirrorsAllBaseAnnotationsReplacingStaleOnes() throws Exception {
        // Base has one named + one unnamed annotation (unnamed is the case the
        // old name-only dedup missed, producing duplicates).
        PathObjectHierarchy base = new PathObjectHierarchy();
        base.addObject(namedAnnotation("Region A", 5, 5));
        base.addObject(namedAnnotation(null, 40, 40));

        // Sibling already holds a stale annotation from a previous run.
        PathObjectHierarchy sibling = new PathObjectHierarchy();
        sibling.addObject(namedAnnotation("stale", 1, 1));

        ImageFlipHelper.mirrorAnnotationsToSibling(base, siblingEntry(sibling), true, true, 100, 100);

        assertEquals(
                2,
                sibling.getAnnotationObjects().size(),
                "sibling must hold exactly the base's 2 annotations; stale one replaced");
    }

    @Test
    void reRunIsIdempotentNoAccumulation() throws Exception {
        PathObjectHierarchy base = new PathObjectHierarchy();
        base.addObject(namedAnnotation(null, 10, 10));

        PathObjectHierarchy sibling = new PathObjectHierarchy();
        ProjectImageEntry<BufferedImage> entry = siblingEntry(sibling);

        ImageFlipHelper.mirrorAnnotationsToSibling(base, entry, true, true, 100, 100);
        ImageFlipHelper.mirrorAnnotationsToSibling(base, entry, true, true, 100, 100);
        ImageFlipHelper.mirrorAnnotationsToSibling(base, entry, true, true, 100, 100);

        assertEquals(
                1,
                sibling.getAnnotationObjects().size(),
                "repeated mirroring must not accumulate -- the replace step keeps the count fixed");
    }

    @Test
    void flipTransformsCoordinates() throws Exception {
        // Base annotation at (10,10)-(20,20); flip XY over 100x100 -> (80,80)-(90,90).
        PathObjectHierarchy base = new PathObjectHierarchy();
        base.addObject(namedAnnotation("r", 10, 10));

        PathObjectHierarchy sibling = new PathObjectHierarchy();
        ImageFlipHelper.mirrorAnnotationsToSibling(base, siblingEntry(sibling), true, true, 100, 100);

        PathObject mirrored = sibling.getAnnotationObjects().iterator().next();
        assertEquals(80.0, mirrored.getROI().getBoundsX(), 1e-6);
        assertEquals(80.0, mirrored.getROI().getBoundsY(), 1e-6);
    }
}
