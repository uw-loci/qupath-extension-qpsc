package qupath.ext.qpsc.modality.ppm.analysis;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.overlays.AbstractOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.regions.ImageRegion;
import qupath.lib.regions.RegionRequest;

/**
 * Custom overlay that highlights pixels within a user-specified fiber angle range.
 *
 * <p>Computes fiber angles from a PPM sum image using a {@link PPMCalibration},
 * then creates a semi-transparent colored overlay for pixels whose angle falls
 * within the specified range. The overlay covers the full image at a moderate
 * downsample for performance.</p>
 *
 * <p>The angle computation uses the Java-side linear hue-to-angle transform
 * ({@link PPMCalibration#hueToAngle(double)}) for interactive speed. The same
 * filtering logic is available in Python via
 * {@code ppm_library.analysis.region_analysis.filter_angles_by_range()} for
 * standalone use outside QuPath.</p>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class PPMHueRangeOverlay extends AbstractOverlay {

    private static final Logger logger = LoggerFactory.getLogger(PPMHueRangeOverlay.class);

    /** Max pixels in overlay computation to limit memory and CPU. */
    private static final int MAX_OVERLAY_PIXELS = 4_000_000;

    // Overlay parameters (set from FX thread, read from paint thread)
    private volatile float angleLow = 0;
    private volatile float angleHigh = 180;
    private volatile float saturationThreshold = 0.2f;
    private volatile float valueThreshold = 0.2f;
    private volatile int highlightRGB = 0x00FF00; // green
    private volatile boolean active = false;

    // Cached overlay image (ARGB, fully opaque where matching)
    private volatile BufferedImage overlayImage;
    private volatile int overlayW;
    private volatile int overlayH;

    // Stats from last computation
    private volatile int matchingPixels;
    private volatile int totalValidPixels;

    // Cancellation tracking
    private final AtomicLong computationId = new AtomicLong(0);

    // Resources
    private PPMCalibration calibration;
    private ImageServer<BufferedImage> server;
    private final QuPathViewer viewer;

    // Callback for stats updates
    private StatsListener statsListener;

    /** Callback interface for overlay statistics updates. */
    public interface StatsListener {
        void onStatsUpdated(int matchingPixels, int totalValidPixels);
    }

    public PPMHueRangeOverlay(QuPathViewer viewer) {
        super(viewer.getOverlayOptions());
        this.viewer = viewer;
    }

    public void setCalibration(PPMCalibration calibration) {
        this.calibration = calibration;
    }

    public void setServer(ImageServer<BufferedImage> server) {
        this.server = server;
    }

    public void setStatsListener(StatsListener listener) {
        this.statsListener = listener;
    }

    public void setAngleRange(float low, float high) {
        this.angleLow = low;
        this.angleHigh = high;
    }

    public void setSaturationThreshold(float threshold) {
        this.saturationThreshold = threshold;
    }

    public void setValueThreshold(float threshold) {
        this.valueThreshold = threshold;
    }

    public void setHighlightRGB(int rgb) {
        this.highlightRGB = rgb & 0xFFFFFF;
    }

    public void setActive(boolean active) {
        this.active = active;
        if (!active) {
            overlayImage = null;
            viewer.repaint();
        }
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Triggers asynchronous recomputation of the overlay.
     * Cancels any in-progress computation.
     */
    public void recompute() {
        if (!active || calibration == null || server == null) return;

        long id = computationId.incrementAndGet();

        CompletableFuture.runAsync(() -> {
            try {
                doRecompute(id);
            } catch (Exception e) {
                logger.error("Failed to compute hue range overlay", e);
            }
        });
    }

    private void doRecompute(long id) throws IOException {
        if (id != computationId.get()) return;

        int imgW = server.getWidth();
        int imgH = server.getHeight();

        // Choose downsample to stay within pixel budget
        double ds = 1.0;
        while ((imgW / ds) * (imgH / ds) > MAX_OVERLAY_PIXELS) {
            ds *= 2;
        }

        logger.info("Computing hue range overlay: {}x{} at downsample {}",
                imgW, imgH, ds);

        RegionRequest request = RegionRequest.createInstance(
                server.getPath(), ds, 0, 0, imgW, imgH);

        BufferedImage img = server.readRegion(request);
        int w = img.getWidth();
        int h = img.getHeight();

        if (id != computationId.get()) return;

        // Snapshot parameters
        float lo = this.angleLow;
        float hi = this.angleHigh;
        float satTh = this.saturationThreshold;
        float valTh = this.valueThreshold;
        int highlight = 0xFF000000 | this.highlightRGB;

        BufferedImage overlay = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        float[] hsb = new float[3];
        int matching = 0;
        int valid = 0;

        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int rgb = img.getRGB(px, py);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                Color.RGBtoHSB(r, g, b, hsb);

                if (hsb[1] >= satTh && hsb[2] >= valTh) {
                    valid++;
                    float angle = (float) calibration.hueToAngle(hsb[0]);

                    boolean inRange;
                    if (lo <= hi) {
                        inRange = angle >= lo && angle <= hi;
                    } else {
                        // Wrap-around (e.g., 170 to 10 deg)
                        inRange = angle >= lo || angle <= hi;
                    }

                    if (inRange) {
                        matching++;
                        overlay.setRGB(px, py, highlight);
                    }
                }
            }

            // Check cancellation every row
            if (py % 100 == 0 && id != computationId.get()) return;
        }

        logger.info("Hue range overlay: {}/{} pixels in range [{}, {}] deg",
                matching, valid, lo, hi);

        // Store results
        this.overlayImage = overlay;
        this.overlayW = imgW;
        this.overlayH = imgH;
        this.matchingPixels = matching;
        this.totalValidPixels = valid;

        // Notify listener and repaint
        StatsListener listener = this.statsListener;
        final int finalMatching = matching;
        final int finalValid = valid;
        Platform.runLater(() -> {
            if (listener != null) {
                listener.onStatsUpdated(finalMatching, finalValid);
            }
            viewer.repaint();
        });
    }

    @Override
    public void paintOverlay(Graphics2D g2d, ImageRegion region, double downsample,
            ImageData<BufferedImage> imageData, boolean isSelected) {
        BufferedImage img = this.overlayImage;
        if (!active || img == null) return;

        // Apply opacity
        Composite oldComposite = g2d.getComposite();
        g2d.setComposite(AlphaComposite.getInstance(
                AlphaComposite.SRC_OVER, (float) getOpacity()));

        // Draw overlay at full image coordinates (Graphics2D is in image space)
        g2d.drawImage(img, 0, 0, overlayW, overlayH, null);

        g2d.setComposite(oldComposite);
    }

    /**
     * Cleans up resources when the overlay is removed.
     */
    public void dispose() {
        computationId.incrementAndGet(); // cancel any in-progress computation
        active = false;
        overlayImage = null;
        statsListener = null;
    }
}
