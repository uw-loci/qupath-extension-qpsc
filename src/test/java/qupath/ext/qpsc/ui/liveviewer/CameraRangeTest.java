package qupath.ext.qpsc.ui.liveviewer;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * The Live Viewer's histogram and contrast use the camera's range, not the 16-bit buffer's:
 * a 12-bit camera's frames crowded into the first sixteenth of the axis (seen on the
 * LC-PolScope Oryx, 2026-10-19).
 */
class CameraRangeTest {

    @Test
    void theCamerasBitsSetTheTopOfTheRange() {
        assertEquals(4095, CameraBitDepth.maxValueFor(12, 65535));
        assertEquals(16383, CameraBitDepth.maxValueFor(14, 65535));
        assertEquals(65535, CameraBitDepth.maxValueFor(16, 65535));
        assertEquals(65535, CameraBitDepth.maxValueFor(null, 65535), "unknown: the buffer's range");
        assertEquals(65535, CameraBitDepth.maxValueFor(3, 65535), "nonsense is ignored");
        assertEquals(255, CameraBitDepth.maxValueFor(12, 255), "never beyond the container");
    }

    @Test
    void autoScaleResolvesA12BitImageFinely() {
        // A bell between 500 and 4095 in 256 bins of 16 counts each.
        int[] hist = new int[256];
        for (int v = 500; v <= 4095; v += 4) {
            hist[(int) ((long) v * 255 / 4095)]++;
        }
        ContrastSettings c = new ContrastSettings();
        c.applyAutoScale(hist, 4095);
        assertTrue(Math.abs(c.getDisplayMin() - 500) < 40, "min " + c.getDisplayMin());
        assertTrue(c.getDisplayMax() <= 4095 && c.getDisplayMax() > 4000, "max " + c.getDisplayMax());
    }

    @Test
    void theCurveIsLinearAndTheEndBinsDoNotFlattenIt() {
        int[] hist = new int[256];
        hist[0] = 100_000; // a dark corner
        hist[100] = 400;
        hist[120] = 800;
        hist[255] = 50_000; // clipped highlights
        assertEquals(800, HistogramView.peakCount(hist), 1e-9);
        assertEquals(1, HistogramView.peakCount(new int[256]), 1e-9);
    }
}
