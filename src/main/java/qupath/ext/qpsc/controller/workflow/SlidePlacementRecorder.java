package qupath.ext.qpsc.controller.workflow;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import qupath.ext.qpsc.ui.stagemap.StageMapWindow;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.lib.images.ImageData;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Records where a slide actually sits on the stage whenever a per-slide alignment is saved, and
 * redraws the Stage Map from it. One call from every save site -- the green-box path, manual
 * alignment, Microscope Alignment and refinement -- so none can leave the Stage Map on the
 * preset's assumption or on an older alignment of the same slide.
 *
 * <p>Writes the slide's image centre in stage um twice, for the two readers: on the open entry
 * ({@link ImageMetadataManager#setSlideCenterStageXY}, the multi-slide slot previews) and on the
 * alignment record ({@link AffineTransformManager#saveSlideCentre}, the single-slide macro
 * overlay, which finds a slide by its alignment key).
 */
public final class SlidePlacementRecorder {

    private SlidePlacementRecorder() {}

    /**
     * Call right after {@code AffineTransformManager.saveSlideAlignment}.
     *
     * @param lookupKey the key the alignment was saved under
     * @param imageData the open image whose FULL-RESOLUTION pixels {@code imageToStage} maps
     *     (what {@code pixelFrame="macro"} denotes)
     * @param imageToStage the alignment just saved
     */
    public static void record(
            Project<BufferedImage> project,
            String lookupKey,
            ImageData<BufferedImage> imageData,
            AffineTransform imageToStage) {
        if (project == null || lookupKey == null || imageData == null || imageToStage == null) {
            return;
        }
        var server = imageData.getServer();
        if (server == null) {
            return;
        }
        int width = server.getWidth();
        int height = server.getHeight();
        ProjectImageEntry<BufferedImage> entry = project.getEntry(imageData);
        if (entry != null) {
            ImageMetadataManager.setSlideCenterStageXY(entry, imageToStage, width, height);
        }
        AffineTransformManager.saveSlideCentre(project, lookupKey, imageToStage, width, height);
        StageMapWindow.refreshSlotMacroPlacement();
        StageMapWindow.refreshMacroOverlay();
    }
}
