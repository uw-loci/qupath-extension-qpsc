package qupath.ext.qpsc.ui.stagemap;

import java.awt.image.BufferedImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.utilities.ImageFlipHelper;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Applies a per-slide placement change (label-left "A" &lt;-&gt; label-right "B", a 180)
 * made in the Stage Map. Placement is a property of the physical slide, so it is
 * stamped on the base entry. Because the existing "(Camera View)" companion was
 * baked at the OLD placement -- and the saved per-slide alignment is in that old
 * placement's pixel frame -- both are stale after a placement change:
 *
 * <ol>
 *   <li>Stamp the new placement on the base entry.</li>
 *   <li>Delete the stale "(Camera View)" companion so the next alignment/acquisition
 *       re-bakes it (via {@code ImageFlipHelper.validateAndFlipIfNeeded}) with the new
 *       placement composed in.</li>
 *   <li>Clear the live stage transform (it was fit in the old companion frame).</li>
 *   <li>Warn the operator to re-run alignment for this slide.</li>
 * </ol>
 *
 * <p>Kept as its own class so the coordination lives in one place rather than inline
 * in the Stage Map's combo handler.</p>
 */
final class PlacementChangeCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(PlacementChangeCoordinator.class);

    private PlacementChangeCoordinator() {}

    /**
     * @param insertion the new placement token ({@code A}/{@code B})
     * @return true if a placement was stamped (there was an open project entry)
     */
    @SuppressWarnings("unchecked")
    static boolean applyPlacementChange(String insertion) {
        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui == null || gui.getProject() == null || gui.getImageData() == null) {
            return false;
        }
        Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
        ProjectImageEntry<BufferedImage> open = project.getEntry(gui.getImageData());
        if (open == null) {
            return false;
        }

        boolean openIsCompanion = ImageMetadataManager.isCameraView(open);
        ProjectImageEntry<BufferedImage> base = openIsCompanion ? findBaseOf(project, open) : open;
        ProjectImageEntry<BufferedImage> target = base != null ? base : open;

        ImageMetadataManager.setSlideInsertion(target, insertion);

        // Delete the stale companion (baked at the old placement).
        ProjectImageEntry<BufferedImage> companion =
                openIsCompanion ? open : ImageFlipHelper.findFlippedSibling(project, target, false, false);
        if (companion != null && ImageMetadataManager.isCameraView(companion)) {
            try {
                project.removeImage(companion, true);
                logger.info("Placement change: removed stale companion '{}'", companion.getImageName());
            } catch (Exception ex) {
                logger.warn("Placement change: could not remove stale companion: {}", ex.getMessage());
            }
        }
        try {
            project.syncChanges();
        } catch (Exception ignored) {
            // best-effort persist
        }

        // The live/saved alignment was fit in the old companion frame -- drop it.
        try {
            MicroscopeController.getInstance().setCurrentTransform(null);
        } catch (Exception ignored) {
            // no active controller
        }

        logger.info("Placement change applied to '{}': insertion={}", target.getImageName(), insertion);
        Dialogs.showWarningNotification(
                "Slide placement changed",
                "Placement set to "
                        + ("B".equalsIgnoreCase(insertion) ? "label-right (180)" : "label-left (as scanned)")
                        + ". The corrected (Camera View) image and any saved alignment for this slide are now stale --"
                        + " re-run alignment before acquiring.");
        return true;
    }

    /** Find the base entry a "(Camera View)" companion derives from, via base_image. */
    private static ProjectImageEntry<BufferedImage> findBaseOf(
            Project<BufferedImage> project, ProjectImageEntry<BufferedImage> companion) {
        String baseImage = ImageMetadataManager.getBaseImage(companion);
        if (baseImage == null || baseImage.isBlank()) {
            return null;
        }
        for (ProjectImageEntry<BufferedImage> e : project.getImageList()) {
            if (e == companion || ImageMetadataManager.isCameraView(e)) {
                continue;
            }
            if (baseImage.equals(GeneralTools.stripExtension(e.getImageName())) || baseImage.equals(e.getImageName())) {
                return e;
            }
        }
        return null;
    }
}
