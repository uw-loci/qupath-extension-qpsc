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

    @Test
    void anEightBitTileDoesNotClampTheSixteenBitRange() {
        // LC-PolScope, Hamamatsu, 2026-10-21: the 8-bit orientation-colour tile previewed between
        // 16-bit camera frames clamped the range to 254..255 and every camera frame went white.
        ContrastSettings c = new ContrastSettings();
        c.useScale(65535);
        c.setDisplayMin(1200);
        c.setDisplayMax(9000);

        c.useScale(255);
        assertArrayEquals(new int[] {0, 255}, c.rangeFor(255), "a new scale starts at its full range");
        assertArrayEquals(new int[] {1200, 9000}, c.rangeFor(65535), "the 16-bit range is kept");

        c.useScale(65535);
        assertEquals(1200, c.getDisplayMin());
        assertEquals(9000, c.getDisplayMax());
    }

    @Test
    void autoScaleSetsTheRangeOfTheScaleItMeasured() {
        ContrastSettings c = new ContrastSettings();
        c.useScale(65535);
        c.setDisplayMin(1200);
        c.setDisplayMax(9000);
        int[] hist = new int[256];
        hist[100] = 1000;
        c.applyAutoScale(hist, 255);
        assertEquals(255, c.getScale());
        assertArrayEquals(new int[] {1200, 9000}, c.rangeFor(65535));
    }

    @Test
    void computedOutputFoldersAreNotPreviewedAsCameraTiles() {
        assertTrue(LiveViewerWindow.isComputedOutputDirectory("orientation_color"));
        assertTrue(LiveViewerWindow.isComputedOutputDirectory("retardance"));
        assertTrue(LiveViewerWindow.isComputedOutputDirectory("orientation"));
        assertTrue(LiveViewerWindow.isComputedOutputDirectory("7.0.biref"));
        assertTrue(LiveViewerWindow.isComputedOutputDirectory("90.0.sum"));
        assertFalse(LiveViewerWindow.isComputedOutputDirectory("State0"));
        assertFalse(LiveViewerWindow.isComputedOutputDirectory("7.0"));
        assertFalse(LiveViewerWindow.isComputedOutputDirectory("-7"));
        assertFalse(LiveViewerWindow.isComputedOutputDirectory("DAPI"));
    }
}
