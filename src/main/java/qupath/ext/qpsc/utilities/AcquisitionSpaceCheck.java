package qupath.ext.qpsc.utilities;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;

/**
 * Estimates the disk space required for an acquisition and warns the user if
 * the free space at the save location is insufficient.
 *
 * <p>Bytes-per-tile is derived from detector width/height plus a
 * bytes-per-pixel choice made from {@code camera_type} and
 * {@code requires_debayering} in the detector resource YAML:
 *
 * <pre>
 *   jai (3-CCD)              -> 3 bytes/pixel (RGB8)
 *   generic + debayered      -> 3 bytes/pixel (debayered RGB8)
 *   generic + not debayered  -> 2 bytes/pixel (16-bit mono sCMOS)
 *   laser_scanning (PMT)     -> 2 bytes/pixel (16-bit mono)
 *   unknown                  -> 10 MB/tile fallback
 * </pre>
 *
 * <p>The estimate is still approximate. Modalities may write additional
 * per-position derived files (e.g. PPM writes a single-channel derived image
 * alongside the raw RGB), TIFF compression varies, OME-XML adds overhead, and
 * the post-acquisition stitched output is not included here. A 1.25x headroom
 * multiplier covers those sources of slop.
 *
 * <p>Users can disable the warning via the "Warn On Low Disk Space" preference
 * or via the "Don't warn me again" checkbox in the warning dialog itself.
 */
public class AcquisitionSpaceCheck {

    private static final Logger logger = LoggerFactory.getLogger(AcquisitionSpaceCheck.class);

    /** Conservative multiplier applied to the raw estimate (covers metadata, modality extras, stitching output). */
    private static final double HEADROOM = 1.25;

    /** Fallback bytes per tile per angle when detector dimensions are unavailable. */
    private static final long FALLBACK_BYTES_PER_TILE = 10L * 1024 * 1024;

    private AcquisitionSpaceCheck() {}

    /**
     * Picks bytes-per-pixel from detector metadata in the config YAML.
     * Returns -1 if the detector is not found or has no camera_type set,
     * so callers can decide whether to fall back.
     */
    static int bytesPerPixelFor(MicroscopeConfigManager configManager, String detector) {
        if (configManager == null || detector == null) return -1;
        String cameraType = configManager.getDetectorCameraType(detector);
        if (cameraType == null) {
            // Fall back to JAI name-based detection used elsewhere in the codebase
            return configManager.isJAICamera(detector) ? 3 : -1;
        }
        switch (cameraType) {
            case "jai":
                return 3; // 3-CCD prism RGB8
            case "laser_scanning":
                return 2; // 16-bit mono PMT
            case "generic":
                // Bayer sensors debayer to RGB8; non-debayered generics are mono sCMOS (16-bit)
                return configManager.detectorRequiresDebayering(detector) ? 3 : 2;
            default:
                logger.debug("Unknown camera_type '{}' for detector {} -- falling back", cameraType, detector);
                return -1;
        }
    }

    /**
     * Estimates bytes per tile per angle from detector width, height, and the
     * bytes/pixel inferred from camera_type + requires_debayering. Uses a
     * 10 MB fallback when any of those are unavailable.
     *
     * @param configManager shared config manager
     * @param detector detector identifier (e.g. "LOCI_DETECTOR_JAI_001")
     * @return estimated bytes written per tile per angle
     */
    public static long estimateBytesPerTilePerAngle(MicroscopeConfigManager configManager, String detector) {
        if (configManager == null || detector == null) {
            return FALLBACK_BYTES_PER_TILE;
        }
        try {
            int[] dims = configManager.getDetectorDimensions(detector);
            if (dims == null || dims.length < 2 || dims[0] <= 0 || dims[1] <= 0) {
                logger.debug("Detector {} has no usable dimensions -- using {} MB fallback",
                        detector, FALLBACK_BYTES_PER_TILE / (1024 * 1024));
                return FALLBACK_BYTES_PER_TILE;
            }
            int bpp = bytesPerPixelFor(configManager, detector);
            if (bpp <= 0) {
                logger.debug("Detector {} bytes-per-pixel unknown -- using {} MB fallback",
                        detector, FALLBACK_BYTES_PER_TILE / (1024 * 1024));
                return FALLBACK_BYTES_PER_TILE;
            }
            long bytesPerTile = (long) dims[0] * dims[1] * bpp;
            logger.info("Detector {} estimate: {}x{} pixels x {} bytes/pixel = {}/tile/angle",
                    detector, dims[0], dims[1], bpp, formatBytes(bytesPerTile));
            return bytesPerTile;
        } catch (Exception e) {
            logger.debug("Could not estimate bytes/tile for {}: {}", detector, e.getMessage());
            return FALLBACK_BYTES_PER_TILE;
        }
    }

    /**
     * Result of a space check.
     *
     * @param proceed true if acquisition should continue (either enough space, or user confirmed)
     * @param estimatedBytes the estimated total bytes that will be written
     * @param usableBytes the free bytes at the save location, or -1 if unreadable
     */
    public record Result(boolean proceed, long estimatedBytes, long usableBytes) {}

    /**
     * Runs the check and, if insufficient space is estimated, shows a modal
     * warning dialog. Blocks until the user responds.
     *
     * <p>If the "Warn On Low Disk Space" preference is disabled, the check is
     * skipped entirely and {@link Result#proceed} is always true.
     *
     * @param outputPath directory where tiles will be written
     * @param totalTileCount number of unique tile positions across all annotations
     * @param angleCount number of angles per tile (1 for single-angle modalities)
     * @param bytesPerTilePerAngle estimated bytes written per tile per angle
     * @return result indicating whether the caller should proceed
     */
    public static Result checkAndWarn(Path outputPath, long totalTileCount, int angleCount, long bytesPerTilePerAngle) {
        if (!QPPreferenceDialog.getWarnOnLowDiskSpace()) {
            logger.info("Disk space warning disabled by preference -- skipping check");
            return new Result(true, -1, -1);
        }

        long effectiveAngles = Math.max(1, angleCount);
        long rawEstimate = Math.max(0L, totalTileCount) * bytesPerTilePerAngle * effectiveAngles;
        long estimatedBytes = (long) (rawEstimate * HEADROOM);

        long usableBytes = -1;
        try {
            Path probePath = resolveExistingAncestor(outputPath);
            if (probePath != null) {
                usableBytes = Files.getFileStore(probePath).getUsableSpace();
            }
        } catch (IOException e) {
            logger.warn("Could not read free disk space at {}: {}", outputPath, e.getMessage());
        }

        logger.info(
                "Disk space check: estimated {} ({} tiles x {} angles x {} bytes, x{} headroom), "
                        + "available {} at {}",
                formatBytes(estimatedBytes),
                totalTileCount,
                effectiveAngles,
                bytesPerTilePerAngle,
                HEADROOM,
                usableBytes < 0 ? "unknown" : formatBytes(usableBytes),
                outputPath);

        if (usableBytes < 0 || usableBytes >= estimatedBytes) {
            return new Result(true, estimatedBytes, usableBytes);
        }

        boolean proceed = showWarningDialog(outputPath, estimatedBytes, usableBytes);
        return new Result(proceed, estimatedBytes, usableBytes);
    }

    /**
     * Walks up from the target path to the first existing ancestor so
     * {@link Files#getFileStore(Path)} can resolve the mount point even when
     * the tile directory has not yet been created on disk.
     */
    private static Path resolveExistingAncestor(Path path) {
        if (path == null) return null;
        Path current = path.toAbsolutePath();
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        return current;
    }

    private static boolean showWarningDialog(Path outputPath, long estimatedBytes, long usableBytes) {
        if (Platform.isFxApplicationThread()) {
            return showWarningDialogFx(outputPath, estimatedBytes, usableBytes);
        }
        AtomicBoolean proceed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                proceed.set(showWarningDialogFx(outputPath, estimatedBytes, usableBytes));
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return proceed.get();
    }

    private static boolean showWarningDialogFx(Path outputPath, long estimatedBytes, long usableBytes) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Low Disk Space (Estimate)");
        alert.setHeaderText("Estimated acquisition size may exceed free disk space");

        Label body = new Label(String.format(
                "Save location:%n  %s%n%n"
                        + "Estimated output size:  %s%n"
                        + "Free space available:   %s%n%n"
                        + "NOTE: This is an approximate estimate based on detector dimensions "
                        + "and tile count. Actual size varies with modality, compression, and "
                        + "stitched output. If you run out of space mid-acquisition, tiles will "
                        + "fail to write and the run will be lost.%n%n"
                        + "Continue anyway?",
                outputPath,
                formatBytes(estimatedBytes),
                formatBytes(usableBytes)));
        body.setWrapText(true);
        body.setMaxWidth(520);

        CheckBox dontWarn = new CheckBox("Don't warn me again (Danger)");
        dontWarn.setSelected(false);

        VBox content = new VBox(12, body, dontWarn);
        content.setPadding(new Insets(4, 4, 4, 4));
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setPrefWidth(560);

        ButtonType proceedButton = new ButtonType("Proceed Anyway", ButtonBar.ButtonData.YES);
        ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(proceedButton, cancelButton);

        var response = alert.showAndWait();
        boolean proceed = response.isPresent() && response.get() == proceedButton;

        if (dontWarn.isSelected()) {
            QPPreferenceDialog.setWarnOnLowDiskSpace(false);
            logger.warn("User disabled low-disk-space warnings via dialog checkbox");
        }
        return proceed;
    }

    /** Formats a byte count as a human-readable string (GB/MB/KB). */
    public static String formatBytes(long bytes) {
        if (bytes < 0) return "unknown";
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        if (gb >= 1.0) return String.format("%.2f GB", gb);
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1.0) return String.format("%.1f MB", mb);
        double kb = bytes / 1024.0;
        return String.format("%.0f KB", kb);
    }

    /** Convenience for callers that have a String path. */
    public static Result checkAndWarn(String outputPath, long totalTileCount, int angleCount, long bytesPerTilePerAngle) {
        return checkAndWarn(Paths.get(outputPath), totalTileCount, angleCount, bytesPerTilePerAngle);
    }
}
