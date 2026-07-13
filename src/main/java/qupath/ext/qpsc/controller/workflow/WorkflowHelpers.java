package qupath.ext.qpsc.controller.workflow;

import java.awt.image.BufferedImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.ui.stagemap.StageInsert;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Small, stateless helpers shared across the single-slide, multi-slide, and refinement
 * workflow paths. Consolidates logic that was duplicated verbatim across those paths so
 * there is one home and one behavior (project no-legacy policy: all callers refactored, no
 * parallel copies).
 *
 * <p>Coordinate-transform math is intentionally NOT collapsed here: the slot-center estimate
 * differs meaningfully between the manual and existing-alignment paths (diagonal vs R270
 * anchor), so it stays in each path. Only behavior-identical utilities live here.
 */
public final class WorkflowHelpers {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowHelpers.class);

    private WorkflowHelpers() {}

    /**
     * Saves the currently-open image's data to its project entry, if any, without prompting.
     * Prevents QuPath's modal "Save changes?" dialog when a workflow switches the open image
     * (e.g. between slots, or after a stitched/biref import marks the image changed).
     *
     * <p>Best-effort: any failure is logged, not thrown, so a save hiccup never blocks the
     * caller. Does NOT sync the project -- callers that need the change flushed to disk should
     * call {@code project.syncChanges()} themselves (see
     * {@code ExistingImageWorkflowV2.persistAnnotationsForAcquirePass}).
     *
     * @param gui the QuPath GUI instance
     * @return the entry that was saved, or {@code null} if there was nothing to save
     */
    @SuppressWarnings("unchecked")
    public static ProjectImageEntry<BufferedImage> saveOpenImageDataQuietly(QuPathGUI gui) {
        try {
            if (gui == null) {
                return null;
            }
            ImageData<BufferedImage> currentData = gui.getImageData();
            Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
            ProjectImageEntry<BufferedImage> currentEntry =
                    (currentData != null && project != null) ? project.getEntry(currentData) : null;
            if (currentEntry != null) {
                currentEntry.saveImageData(currentData);
                logger.info("Saved open image data to entry '{}' without prompting", currentEntry.getImageName());
                return currentEntry;
            }
        } catch (Exception ex) {
            logger.warn("Could not save open image data quietly: {}", ex.getMessage());
        }
        return null;
    }

    /**
     * Full-res pixel size (um/px) of the open image, for alignment scaling transforms. Reads
     * the server's averaged pixel calibration; falls back to {@code fallback} only when the
     * server has no positive calibration (should not happen for a real whole-slide image).
     *
     * @param gui      the QuPath GUI instance
     * @param fallback the value to return when no positive server calibration is available
     * @return the full-res pixel size in um/px, or {@code fallback}
     */
    public static double resolveFullResPixelSize(QuPathGUI gui, double fallback) {
        try {
            double px = gui.getImageData().getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
            if (px > 0) {
                return px;
            }
        } catch (Exception e) {
            logger.warn("Could not read full-res pixel size: {}", e.getMessage());
        }
        logger.warn("Full-res pixel size unavailable; falling back to {} um/px", fallback);
        return fallback;
    }

    /**
     * Absolute stage (X, Y) center (um) of the given carrier slot, read from the insert's
     * per-slot calibration ({@code slideK_center_x_um/_y_um}). Returns {@code null} when the
     * holder has no per-slot centers or the config is unavailable -- callers then run without
     * an auto-move estimate.
     *
     * @param carrier      the stage insert (holder) whose slot centers are read
     * @param slotPosition the 1-based slot index
     * @return {@code [x, y]} stage center in um, or {@code null} if not calibrated
     */
    public static double[] resolveSlotCenterStageXY(StageInsert carrier, int slotPosition) {
        try {
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
            if (mgr == null || carrier == null) {
                return null;
            }
            Double cx = mgr.getDouble(
                    "stage", "inserts", "configurations", carrier.getId(), "slide" + slotPosition + "_center_x_um");
            Double cy = mgr.getDouble(
                    "stage", "inserts", "configurations", carrier.getId(), "slide" + slotPosition + "_center_y_um");
            if (cx == null || cy == null) {
                logger.info(
                        "No per-slot center for slot {} of insert '{}'; alignment auto-move disabled",
                        slotPosition,
                        carrier.getId());
                return null;
            }
            logger.info("Slot {} of insert '{}' center = ({}, {}) um", slotPosition, carrier.getId(), cx, cy);
            return new double[] {cx, cy};
        } catch (Exception e) {
            logger.warn("Could not resolve slot center for slot {}: {}", slotPosition, e.getMessage());
            return null;
        }
    }

    /**
     * Centers the viewer on the tile and selects it. Shared by single-tile and multi-tile
     * refinement (previously duplicated as {@code centerViewerOnTile} in each).
     *
     * @param gui  the QuPath GUI instance
     * @param tile the tile to center on and select
     */
    public static void centerAndSelectTile(QuPathGUI gui, PathObject tile) {
        if (gui == null || tile == null) {
            return;
        }
        var viewer = gui.getViewer();
        if (viewer != null && tile.getROI() != null) {
            double cx = tile.getROI().getCentroidX();
            double cy = tile.getROI().getCentroidY();
            viewer.setCenterPixelLocation(cx, cy);
            viewer.getHierarchy().getSelectionModel().setSelectedObject(tile);
            logger.debug("Centered viewer on tile at ({}, {})", cx, cy);
        }
    }
}
