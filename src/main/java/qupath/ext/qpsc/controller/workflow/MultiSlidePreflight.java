package qupath.ext.qpsc.controller.workflow;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Checks, before an unattended multi-slide batch starts, that every slot it is about to drive
 * actually has what the setup pass needs.
 *
 * <h2>Why this exists</h2>
 * The setup pass stops on a slide with no annotations: {@code ManualAlignmentPath} shows the
 * per-slide annotation dialog and waits for a human to run detection or draw a region. That is
 * correct behaviour -- the software must not invent a region to image -- but in an automatic
 * batch it means the operator walks away, and the run stops on slide 3 with nobody there. The
 * cost of learning that is an hour of wall clock; the cost of learning it up front is one
 * dialog before the batch starts.
 *
 * <p>The check is deliberately advisory rather than a block. An operator may intend to draw
 * regions as the pass reaches each slide, and only they know that.
 *
 * <h2>Reading annotations without opening the images</h2>
 * Opening four whole-slide images to count annotations would be slower than the batch it is
 * checking. {@link ProjectImageEntry#readHierarchy()} reads the saved {@code .qpdata} instead.
 * Two consequences are handled here: the entry currently open in the viewer may hold unsaved
 * annotations, so the live hierarchy is used for it; and a slot is only reported when BOTH its
 * alignment entry and its base entry come back empty, because the multi-slide path copies the
 * base entry's annotations onto the alignment entry as the slide's turn comes up.
 */
public final class MultiSlidePreflight {

    private static final Logger logger = LoggerFactory.getLogger(MultiSlidePreflight.class);

    private MultiSlidePreflight() {}

    /**
     * Slot labels whose images carry no annotations on either the alignment entry or its base.
     *
     * <p>An entry whose hierarchy cannot be read is NOT reported: an unreadable {@code .qpdata}
     * says nothing about whether annotations exist, and a false "slide 3 has no regions" would
     * send the operator to fix something that is not broken.
     *
     * @param gui   QuPath GUI, used to prefer the live hierarchy of the open entry
     * @param slots alignment entry / base entry / label for each slot to check
     * @return labels of slots with no annotations, in the order given; empty when all are ready
     */
    public static List<String> slotsWithoutAnnotations(QuPathGUI gui, List<SlotAnnotationCheck> slots) {
        List<String> missing = new ArrayList<>();
        if (slots == null) {
            return missing;
        }
        for (SlotAnnotationCheck slot : slots) {
            if (!hasAnnotations(gui, slot.entry()) && !hasAnnotations(gui, slot.baseEntry())) {
                missing.add(slot.label());
            }
        }
        if (missing.isEmpty()) {
            logger.info("Multi-slide pre-flight: all {} slot(s) have annotations", slots.size());
        } else {
            logger.warn("Multi-slide pre-flight: no annotations found for slot(s) {}", missing);
        }
        return missing;
    }

    /** One slot's inputs for the annotation check. */
    public record SlotAnnotationCheck(
            String label, ProjectImageEntry<BufferedImage> entry, ProjectImageEntry<BufferedImage> baseEntry) {}

    /**
     * True when {@code entry} has at least one annotation with a non-empty ROI. Unknown (null
     * entry, or an unreadable hierarchy) reads as false here; the caller only reports a slot
     * when every one of its entries is false, and {@link #readHierarchy} logs what it could not
     * read so an unreadable entry is visible in the log rather than silently assumed empty.
     */
    private static boolean hasAnnotations(QuPathGUI gui, ProjectImageEntry<BufferedImage> entry) {
        if (entry == null) {
            return false;
        }
        PathObjectHierarchy hierarchy = readHierarchy(gui, entry);
        if (hierarchy == null) {
            // Cannot tell. Treat as "has annotations" so the slot is not reported on a guess.
            return true;
        }
        for (PathObject a : hierarchy.getAnnotationObjects()) {
            if (a.getROI() != null && !a.getROI().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The entry's hierarchy: the live one when it is the entry open in the viewer (so unsaved
     * annotations count), otherwise the saved one. Null when it cannot be read.
     */
    private static PathObjectHierarchy readHierarchy(QuPathGUI gui, ProjectImageEntry<BufferedImage> entry) {
        try {
            if (gui != null && gui.getImageData() != null && gui.getProject() != null) {
                @SuppressWarnings("unchecked")
                qupath.lib.projects.Project<BufferedImage> project =
                        (qupath.lib.projects.Project<BufferedImage>) gui.getProject();
                ProjectImageEntry<BufferedImage> open = project.getEntry(gui.getImageData());
                // getID() is a String -- compare by value, not identity: the project's entry
                // object and the one held by the slot assignment need not be the same instance.
                if (open != null && java.util.Objects.equals(open.getID(), entry.getID())) {
                    return gui.getImageData().getHierarchy();
                }
            }
            return entry.readHierarchy();
        } catch (Exception e) {
            logger.warn(
                    "Multi-slide pre-flight: could not read annotations for '{}' ({}); not reporting it as empty",
                    entry.getImageName(),
                    e.getMessage());
            return null;
        }
    }
}
