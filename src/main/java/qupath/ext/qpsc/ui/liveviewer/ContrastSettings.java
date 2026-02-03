package qupath.ext.qpsc.ui.liveviewer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe contrast display settings for the live viewer.
 * Holds min/max display range and auto-scale state.
 */
public class ContrastSettings {

    private final AtomicInteger displayMin = new AtomicInteger(0);
    private final AtomicInteger displayMax = new AtomicInteger(255);
    private final AtomicBoolean autoScale = new AtomicBoolean(false);

    public int getDisplayMin() {
        return displayMin.get();
    }

    public int getDisplayMax() {
        return displayMax.get();
    }

    public void setDisplayMin(int min) {
        displayMin.set(min);
    }

    public void setDisplayMax(int max) {
        displayMax.set(max);
    }

    public boolean isAutoScale() {
        return autoScale.get();
    }

    public void setAutoScale(boolean enabled) {
        autoScale.set(enabled);
    }

    /**
     * Sets min=0, max=maxValue for the given frame's bit depth.
     */
    public void applyFullRange(FrameData frame) {
        displayMin.set(0);
        displayMax.set(frame.maxValue());
        autoScale.set(false);
    }

    /**
     * Computes 0.1%/99.9% percentile from the histogram and applies as min/max.
     *
     * @param histogram 256-bin histogram array
     * @param frame     Current frame (for maxValue mapping)
     */
    public void applyAutoScale(int[] histogram, FrameData frame) {
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

        // Map 256-bin indices back to actual pixel value range
        int maxVal = frame.maxValue();
        int newMin = lowBin * maxVal / 255;
        int newMax = highBin * maxVal / 255;

        // Ensure at least some range
        if (newMax <= newMin) {
            newMax = newMin + 1;
        }

        displayMin.set(newMin);
        displayMax.set(newMax);
    }
}
