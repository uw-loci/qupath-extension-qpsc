package qupath.ext.qpsc.utilities;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.model.AcquisitionTimeEstimator;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Whole-run acquisition-time estimate for a multi-slide (N-slide) batch.
 *
 * <p>For each slot it counts the tiles its annotations will generate (same grid math the tiler
 * uses, via {@link TilingUtilities#estimateTileCount}), multiplies by the modality's captures per
 * tile (PPM angles / channels), and applies a per-file wall-clock cost:
 * <ul>
 *   <li><b>Learned (preferred):</b> {@link PersistentPreferences#getBaseTileTimeMs()} -- the mean
 *       per-file time measured on this scope, which already folds in autofocus at its real
 *       frequency. It self-calibrates after every run (EMA), so the estimate sharpens with use.</li>
 *   <li><b>Fallback:</b> a rough nominal per-file time when no run has been timed yet.</li>
 * </ul>
 *
 * <p>Only ACQUISITION wall-clock is estimated: in the pipelined acquire pass stitching overlaps the
 * next slide's acquisition, so it is not additive except for the final slide's tail (small relative
 * to the acquire time; omitted here rather than guessed).
 *
 * <p>{@link #estimate} performs project I/O (reads each entry's hierarchy for annotation geometry),
 * so callers must run it OFF the FX thread.
 */
public final class MultiSlideAcquisitionEstimator {

    private static final Logger logger = LoggerFactory.getLogger(MultiSlideAcquisitionEstimator.class);

    /**
     * Nominal per-file wall time (ms) used ONLY before any run has been timed on this scope. Once a
     * real acquisition completes, {@link PersistentPreferences#getBaseTileTimeMs()} replaces this.
     * Deliberately generous (PPM tiles at multiple angles + periodic AF land here).
     */
    static final double FALLBACK_MS_PER_FILE = 900.0;

    private MultiSlideAcquisitionEstimator() {}

    /** One slot's inputs. {@code annotationClasses} empty/null means "all annotations count". */
    public record SlotInput(
            String label,
            ProjectImageEntry<BufferedImage> entry,
            String modality,
            String objective,
            String detector,
            Set<String> annotationClasses) {}

    /** Per-slot result. {@code note} is non-null only when the slot could not be read. */
    public record SlotEstimate(
            String label,
            int annotations,
            long tiles,
            int capturesPerTile,
            long totalImages,
            double seconds,
            String note) {}

    /** Whole-batch result. */
    public record BatchEstimate(
            List<SlotEstimate> slots,
            int slotCount,
            long totalTiles,
            long totalImages,
            double totalSeconds,
            boolean learned) {

        /** One-line summary, e.g. {@code "~1h 40m  --  4 slides, 3,100 tiles (measured timing)"}. */
        public String summary() {
            if (slotCount == 0) {
                return "No set-up slides to estimate yet.";
            }
            return String.format(
                    "~%s  --  %d slide%s, %,d tiles%s",
                    AcquisitionTimeEstimator.formatDuration(totalSeconds),
                    slotCount,
                    slotCount == 1 ? "" : "s",
                    totalTiles,
                    learned ? " (measured timing)" : " (rough -- no measured timing yet)");
        }
    }

    /**
     * Estimates the whole batch. Performs project I/O per slot -- call OFF the FX thread.
     *
     * @param inputs one entry per set-up slot to include
     * @return the batch estimate (never null; slots that fail to read carry a {@code note} and 0 time)
     */
    public static BatchEstimate estimate(List<SlotInput> inputs) {
        boolean learned = PersistentPreferences.hasTimingData();
        double msPerFile = learned ? PersistentPreferences.getBaseTileTimeMs() : FALLBACK_MS_PER_FILE;
        double overlapPct = QPPreferenceDialog.getTileOverlapPercentProperty();

        List<SlotEstimate> slots = new ArrayList<>();
        long totalTiles = 0;
        long totalImages = 0;
        double totalSeconds = 0;
        if (inputs != null) {
            for (SlotInput in : inputs) {
                if (in == null) {
                    continue;
                }
                SlotEstimate se = estimateSlot(in, overlapPct, msPerFile);
                slots.add(se);
                totalTiles += se.tiles();
                totalImages += se.totalImages();
                totalSeconds += se.seconds();
            }
        }
        logger.info(
                "Multi-slide estimate: {} slot(s), {} tiles, {} files, {} ({} per file)",
                slots.size(),
                totalTiles,
                totalImages,
                AcquisitionTimeEstimator.formatDuration(totalSeconds),
                learned ? String.format("%.0f ms measured", msPerFile) : String.format("%.0f ms fallback", msPerFile));
        return new BatchEstimate(slots, slots.size(), totalTiles, totalImages, totalSeconds, learned);
    }

    private static SlotEstimate estimateSlot(SlotInput in, double overlapPct, double msPerFile) {
        ImageData<BufferedImage> data = null;
        try {
            double[] fov = MicroscopeController.getInstance()
                    .getCameraFOVFromConfig(in.modality(), in.objective(), in.detector());
            if (fov == null || fov.length < 2 || fov[0] <= 0 || fov[1] <= 0) {
                return new SlotEstimate(in.label(), 0, 0, 0, 0, 0, "no camera FOV for this config");
            }
            ModalityHandler handler = ModalityRegistry.getHandler(in.modality());
            int captures = Math.max(1, handler.getDefaultAngleCount());

            data = in.entry().readImageData();
            double px = data.getServer().getPixelCalibration().getAveragedPixelSizeMicrons();

            long tiles = 0;
            int annCount = 0;
            for (PathObject a : data.getHierarchy().getAnnotationObjects()) {
                if (a.getROI() == null) {
                    continue;
                }
                if (in.annotationClasses() != null && !in.annotationClasses().isEmpty()) {
                    PathClass pc = a.getPathClass();
                    if (pc == null || !in.annotationClasses().contains(pc.toString())) {
                        continue;
                    }
                }
                annCount++;
                double wUm = a.getROI().getBoundsWidth() * px;
                double hUm = a.getROI().getBoundsHeight() * px;
                tiles += TilingUtilities.estimateTileCount(wUm, hUm, fov[0], fov[1], overlapPct);
            }

            long totalImages = tiles * captures;
            double seconds = (msPerFile / 1000.0) * totalImages;
            return new SlotEstimate(in.label(), annCount, tiles, captures, totalImages, seconds, null);
        } catch (Exception e) {
            logger.warn("Multi-slide estimate: could not estimate slot '{}': {}", in.label(), e.getMessage());
            return new SlotEstimate(in.label(), 0, 0, 0, 0, 0, "could not read entry");
        } finally {
            if (data != null) {
                try {
                    data.getServer().close();
                } catch (Exception ignore) {
                    // best-effort: this is a throwaway server read only for geometry
                }
            }
        }
    }
}
