package qupath.ext.qpsc.ui.liveviewer;

import java.util.HashMap;
import java.util.Map;

/**
 * Thread-safe contrast display settings for the live viewer: a min/max display range and the
 * auto-scale state.
 *
 * <p>The range belongs to a scale: the top of the camera range of the frames it was set on (255
 * for 8-bit, 4095 for a 12-bit camera, 65535 for 16-bit). Each scale keeps its own range, so a
 * frame of another scale passing through the viewer does not overwrite the operator's range.
 * That happened during LC-PolScope acquisitions: the 8-bit orientation-colour tile clamped a
 * 16-bit range to 254..255, and every following camera frame rendered white.
 */
public class ContrastSettings {

    private int scale = 255;
    private int displayMin = 0;
    private int displayMax = 255;
    private final Map<Integer, int[]> otherScales = new HashMap<>();
    private volatile boolean autoScale = false;

    public synchronized int getDisplayMin() {
        return displayMin;
    }

    public synchronized int getDisplayMax() {
        return displayMax;
    }

    public synchronized void setDisplayMin(int min) {
        displayMin = min;
    }

    public synchronized void setDisplayMax(int max) {
        displayMax = max;
    }

    /** The scale the current range belongs to. */
    public synchronized int getScale() {
        return scale;
    }

    /**
     * Makes {@code newScale} the current one. The current range is kept for its own scale, and
     * the range last set for {@code newScale} comes back, or its full range the first time.
     *
     * @param newScale the top of the camera range of the frame now shown
     */
    public synchronized void useScale(int newScale) {
        if (newScale == scale || newScale <= 0) {
            return;
        }
        otherScales.put(scale, new int[] {displayMin, displayMax});
        int[] saved = otherScales.remove(newScale);
        scale = newScale;
        displayMin = saved != null ? saved[0] : 0;
        displayMax = saved != null ? saved[1] : newScale;
    }

    /**
     * The range to render a frame of scale {@code frameScale} with, as {@code [min, max]},
     * without changing the current scale.
     *
     * @param frameScale the top of the camera range of the frame
     * @return the range set for that scale, or its full range when none has been
     */
    public synchronized int[] rangeFor(int frameScale) {
        if (frameScale == scale) {
            return new int[] {displayMin, displayMax};
        }
        int[] saved = otherScales.get(frameScale);
        return saved != null ? saved.clone() : new int[] {0, frameScale};
    }

    public boolean isAutoScale() {
        return autoScale;
    }

    public void setAutoScale(boolean enabled) {
        autoScale = enabled;
    }

    /**
     * Computes 0.1%/99.9% percentile from the histogram and applies as min/max.
     *
     * @param histogram 256-bin histogram array spanning 0..maxVal
     * @param maxVal    the value the top of the histogram stands for (the camera's range)
     */
    public void applyAutoScale(int[] histogram, int maxVal) {
        int totalPixels = 0;
        for (int count : histogram) {
            totalPixels += count;
        }

        if (totalPixels == 0) return;

        double lowThreshold = totalPixels * 0.001;
        double highThreshold = totalPixels * 0.999;

        // Find low percentile bin
        int lowBin = 0;
        long cumulative = 0;
        for (int i = 0; i < 256; i++) {
            cumulative += histogram[i];
            if (cumulative >= lowThreshold) {
                lowBin = i;
                break;
            }
        }

        // Find high percentile bin
        int highBin = 255;
        cumulative = 0;
        for (int i = 0; i < 256; i++) {
            cumulative += histogram[i];
            if (cumulative >= highThreshold) {
                highBin = i;
                break;
            }
        }

        // Map 256-bin indices back to actual pixel value range.
        // Use lower edge of lowBin and upper edge of highBin to avoid
        // truncation when 16-bit data spans a narrow intensity range.
        int newMin = (int) ((long) lowBin * maxVal / 255);
        int newMax = (int) ((long) (highBin + 1) * maxVal / 255);
        newMax = Math.min(newMax, maxVal);

        // Ensure at least some range
        if (newMax <= newMin) {
            newMax = newMin + Math.max(1, maxVal / 255);
        }

        synchronized (this) {
            useScale(maxVal);
            displayMin = newMin;
            displayMax = newMax;
        }
    }
}
