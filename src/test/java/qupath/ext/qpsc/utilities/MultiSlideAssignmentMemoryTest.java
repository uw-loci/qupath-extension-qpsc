package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Regression test for multi-slide assignment memory. The assignment dialog pre-fills each
 * slot with the FIRST candidate whose {@code slide_position} matches. {@code setSlideAssignment}
 * only ADDS metadata to the newly-picked entries, so an entry assigned in an earlier run of the
 * same carrier -- but not this one -- keeps its old {@code slide_position} and, if it sits earlier
 * in the candidate list, wins the pre-fill over this run's pick (the "reverts to an old default"
 * bug seen on quad_v). The workflow now clears same-carrier assignments before stamping. This test
 * reproduces the failing ordering and asserts the fix at the metadata level, and that clearing is
 * scoped to the one carrier.
 */
class MultiSlideAssignmentMemoryTest {

    @SuppressWarnings("unchecked")
    private static ProjectImageEntry<BufferedImage> entry(String name) {
        ProjectImageEntry<BufferedImage> e = mock(ProjectImageEntry.class);
        Map<String, String> md = new HashMap<>();
        when(e.getMetadata()).thenReturn(md);
        when(e.getImageName()).thenReturn(name);
        return e;
    }

    /** First candidate whose slide_position == slot and carrier matches -- mirrors the dialog pre-fill. */
    private static ProjectImageEntry<BufferedImage> prefill(
            List<ProjectImageEntry<BufferedImage>> candidates, int slot, String carrierId) {
        for (ProjectImageEntry<BufferedImage> e : candidates) {
            int pos = ImageMetadataManager.getSlidePosition(e);
            String c = ImageMetadataManager.getSlideCarrier(e);
            if (pos == slot && (c == null || c.isEmpty() || c.equals(carrierId))) {
                return e;
            }
        }
        return null;
    }

    @Test
    @DisplayName("new run's assignment is not shadowed by a stale same-carrier entry")
    void newRunOverridesStaleAssignment() {
        String carrier = "quad_v";
        ProjectImageEntry<BufferedImage> p5 = entry("Pseudo_5");
        ProjectImageEntry<BufferedImage> t12 = entry("T1_2");
        ProjectImageEntry<BufferedImage> p1 = entry("Pseudo_1");
        ProjectImageEntry<BufferedImage> p2 = entry("Pseudo_2");

        // Prior run stamped Pseudo_5 -> slot 1, T1_2 -> slot 2.
        ImageMetadataManager.setSlideAssignment(p5, 1, carrier, "run-A");
        ImageMetadataManager.setSlideAssignment(t12, 2, carrier, "run-A");

        // Candidate order puts the stale entries first, as in the failing 2026-08-14 log.
        List<ProjectImageEntry<BufferedImage>> candidates = new ArrayList<>(List.of(p5, t12, p1, p2));

        // Without the fix, the first-match pre-fill returns the stale entry for slot 1.
        assertEquals(p5, prefill(candidates, 1, carrier));

        // New run assigns Pseudo_1 -> slot 1, Pseudo_2 -> slot 2. The workflow clears same-carrier
        // assignments first, then stamps.
        for (ProjectImageEntry<BufferedImage> e : candidates) {
            if (carrier.equals(ImageMetadataManager.getSlideCarrier(e))) {
                ImageMetadataManager.clearSlideAssignment(e);
            }
        }
        ImageMetadataManager.setSlideAssignment(p1, 1, carrier, "run-B");
        ImageMetadataManager.setSlideAssignment(p2, 2, carrier, "run-B");

        // Stale entries no longer carry a position; pre-fill resolves to this run's picks.
        assertEquals(-1, ImageMetadataManager.getSlidePosition(p5));
        assertEquals(-1, ImageMetadataManager.getSlidePosition(t12));
        assertEquals(p1, prefill(candidates, 1, carrier));
        assertEquals(p2, prefill(candidates, 2, carrier));
    }

    @Test
    @DisplayName("clearing one carrier leaves another carrier's assignment intact")
    void clearingIsScopedToCarrier() {
        ProjectImageEntry<BufferedImage> a = entry("A");
        ProjectImageEntry<BufferedImage> b = entry("B");
        ImageMetadataManager.setSlideAssignment(a, 1, "quad_v", "run-A");
        ImageMetadataManager.setSlideAssignment(b, 1, "dual_h", "run-A");

        for (ProjectImageEntry<BufferedImage> e : List.of(a, b)) {
            if ("quad_v".equals(ImageMetadataManager.getSlideCarrier(e))) {
                ImageMetadataManager.clearSlideAssignment(e);
            }
        }

        assertEquals(-1, ImageMetadataManager.getSlidePosition(a));
        assertEquals(1, ImageMetadataManager.getSlidePosition(b));
        assertEquals("dual_h", ImageMetadataManager.getSlideCarrier(b));
    }
}
