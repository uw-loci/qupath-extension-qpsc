package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qupath.lib.projects.ProjectImageEntry;

/**
 * The background profile check compares the profile's slide placement with the slide's RECORDED
 * placement. PPM, 2026-10-22: a stitched sub-image without its own value read as "A" (the
 * getSlideInsertion default) while the slide was set to B on its base image, and every acquisition
 * warned "Slide placement now: A; the profile expects: B".
 */
class RecordedSlideInsertionTest {

    @SuppressWarnings("unchecked")
    private static ProjectImageEntry<BufferedImage> entry(String name, Map<String, String> metadata) {
        ProjectImageEntry<BufferedImage> e = mock(ProjectImageEntry.class);
        when(e.getImageName()).thenReturn(name);
        when(e.getMetadata()).thenReturn(new HashMap<>(metadata));
        return e;
    }

    @Test
    void aSubImageTakesItsBaseImagesPlacement() {
        var base = entry("MetroHealth_384.svs", Map.of(ImageMetadataManager.LP_SLIDE_INSERTION, "B"));
        var sub = entry("MetroHealth_384.svs imaging", Map.of(ImageMetadataManager.BASE_IMAGE, "MetroHealth_384"));
        assertEquals("B", BackgroundProfiles.recordedSlideInsertion(sub, List.of(base, sub)));
    }

    @Test
    void anEntrysOwnValueWins() {
        var base = entry("S.svs", Map.of(ImageMetadataManager.LP_SLIDE_INSERTION, "B"));
        var companion = entry(
                "S.svs (Camera View)",
                Map.of(ImageMetadataManager.LP_SLIDE_INSERTION, "A", ImageMetadataManager.BASE_IMAGE, "S"));
        assertEquals("A", BackgroundProfiles.recordedSlideInsertion(companion, List.of(base, companion)));
    }

    @Test
    void nothingRecordedIsUnknownNotA() {
        var base = entry("S.svs", Map.of());
        var sub = entry("S.svs imaging", Map.of(ImageMetadataManager.BASE_IMAGE, "S"));
        assertNull(BackgroundProfiles.recordedSlideInsertion(sub, List.of(base, sub)));
        assertNull(BackgroundProfiles.recordedSlideInsertion(base, List.of(base)));
    }
}
