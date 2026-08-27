package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/**
 * The multi-slide annotation pre-flight: which slots get reported as having nothing to acquire.
 *
 * <p>The behaviour that matters most here is what it does NOT report. A false "slide 3 has no
 * regions" sends the operator to fix something that is not broken, and an unreadable
 * {@code .qpdata} is not evidence of an empty slide -- so both the unreadable case and the
 * "annotations live on the base entry" case must come back clean.
 */
class MultiSlidePreflightTest {

    private static PathObject annotation() {
        return PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 10, 10, ImagePlane.getDefaultPlane()));
    }

    private static PathObject emptyRoiAnnotation() {
        return PathObjects.createAnnotationObject(ROIs.createEmptyROI());
    }

    private static ProjectImageEntry<BufferedImage> entryWith(PathObject... objects) throws IOException {
        @SuppressWarnings("unchecked")
        ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        for (PathObject o : objects) {
            hierarchy.addObject(o);
        }
        when(entry.readHierarchy()).thenReturn(hierarchy);
        when(entry.getImageName()).thenReturn("entry");
        return entry;
    }

    private static ProjectImageEntry<BufferedImage> unreadableEntry() throws IOException {
        @SuppressWarnings("unchecked")
        ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
        when(entry.readHierarchy()).thenThrow(new IOException("no data file"));
        when(entry.getImageName()).thenReturn("broken");
        return entry;
    }

    @Test
    void slotWithAnnotationsIsNotReported() throws IOException {
        var slot = new MultiSlidePreflight.SlotAnnotationCheck("Slot 1", entryWith(annotation()), null);
        assertTrue(
                MultiSlidePreflight.slotsWithoutAnnotations(null, List.of(slot)).isEmpty());
    }

    @Test
    void slotWithNoAnnotationsIsReported() throws IOException {
        var slot = new MultiSlidePreflight.SlotAnnotationCheck("Slot 2", entryWith(), entryWith());
        assertEquals(List.of("Slot 2"), MultiSlidePreflight.slotsWithoutAnnotations(null, List.of(slot)));
    }

    @Test
    void annotationsOnTheBaseEntryCountForTheSlot() throws IOException {
        // The multi-slide path copies the base entry's annotations onto the alignment entry as
        // the slide's turn comes up, so an empty alignment entry is not an empty slide.
        var slot = new MultiSlidePreflight.SlotAnnotationCheck("Slot 3", entryWith(), entryWith(annotation()));
        assertTrue(
                MultiSlidePreflight.slotsWithoutAnnotations(null, List.of(slot)).isEmpty());
    }

    @Test
    void emptyRoiDoesNotCountAsAnAnnotation() throws IOException {
        var slot = new MultiSlidePreflight.SlotAnnotationCheck("Slot 4", entryWith(emptyRoiAnnotation()), null);
        assertEquals(List.of("Slot 4"), MultiSlidePreflight.slotsWithoutAnnotations(null, List.of(slot)));
    }

    @Test
    void unreadableEntryIsNotReportedAsEmpty() throws IOException {
        var slot = new MultiSlidePreflight.SlotAnnotationCheck("Slot 5", unreadableEntry(), unreadableEntry());
        assertTrue(
                MultiSlidePreflight.slotsWithoutAnnotations(null, List.of(slot)).isEmpty(),
                "an unreadable hierarchy says nothing about whether annotations exist");
    }

    @Test
    void reportsOnlyTheEmptySlotsAndKeepsOrder() throws IOException {
        var slots = List.of(
                new MultiSlidePreflight.SlotAnnotationCheck("Slot 1", entryWith(annotation()), null),
                new MultiSlidePreflight.SlotAnnotationCheck("Slot 2", entryWith(), null),
                new MultiSlidePreflight.SlotAnnotationCheck("Slot 3", entryWith(), null),
                new MultiSlidePreflight.SlotAnnotationCheck("Slot 4", entryWith(annotation()), null));
        assertEquals(List.of("Slot 2", "Slot 3"), MultiSlidePreflight.slotsWithoutAnnotations(null, slots));
    }

    @Test
    void nullSlotListIsHandled() {
        assertTrue(MultiSlidePreflight.slotsWithoutAnnotations(null, null).isEmpty());
    }
}
