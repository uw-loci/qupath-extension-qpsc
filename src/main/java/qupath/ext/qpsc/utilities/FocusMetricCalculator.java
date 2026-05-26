package qupath.ext.qpsc.utilities;

import qupath.ext.qpsc.ui.liveviewer.FrameData;

/**
 * Static helpers for computing focus-quality metrics from live frames.
 *
 * <p>Used by the Live Viewer's focus-trace overlay (right of the fine Z bar) to
 * plot per-frame focus quality against the stage Z position. Designed to run on
 * the histogram throttle executor (~5 Hz) so cost stays bounded for large frames.
 *
 * <p>Brenner gradient is preferred over Laplacian variance for QPSC scopes: the
 * variance-of-squared-Laplace metric is dominated by bright dust pixels, which
 * matters in PPM BF imaging where dust on the optical path is common.
 */
public final class FocusMetricCalculator {

    private FocusMetricCalculator() {}

    /**
     * Brenner gradient: per-pixel mean of (I(x+2,y) - I(x,y))^2, sampled at a
     * row-step to keep cost bounded. Returns 0 for null/empty frames.
     *
     * <p>For multichannel frames the first channel is used (R for RGB). Brenner
     * captures edge sharpness which is broadly channel-invariant, so this
     * avoids a luminance conversion in the hot path.
     */
    public static double brennerGradient(FrameData frame) {
        if (frame == null || frame.rawPixels() == null) return 0.0;
        int w = frame.width();
        int h = frame.height();
        int ch = frame.channels();
        int bpp = frame.bytesPerPixel();
        if (w < 4 || h < 1 || ch < 1 || bpp < 1) return 0.0;

        int stride = ch * bpp;
        int rowBytes = w * stride;
        int shift = 2;
        int rowStep = Math.max(1, h / 256);

        long sumSq = 0L;
        long count = 0L;
        for (int y = 0; y < h; y += rowStep) {
            int rowBase = y * rowBytes;
            for (int x = 0; x < w - shift; x++) {
                int o1 = rowBase + x * stride;
                int o2 = rowBase + (x + shift) * stride;
                int v1 = frame.readPixelValue(o1);
                int v2 = frame.readPixelValue(o2);
                int d = v2 - v1;
                sumSq += (long) d * d;
                count++;
            }
        }
        return count == 0L ? 0.0 : (double) sumSq / (double) count;
    }
}
