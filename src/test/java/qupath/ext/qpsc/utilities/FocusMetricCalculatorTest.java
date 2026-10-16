package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.ui.liveviewer.FrameData;

/** The Live Viewer's focus-trace metrics on frames whose answer is known. */
class FocusMetricCalculatorTest {

    /** An 8-bit single-channel frame from a row-major array of values. */
    private static FrameData gray8(int w, int h, int[] values) {
        byte[] raw = new byte[w * h];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) values[i];
        }
        return new FrameData(w, h, 1, 1, raw, 0L);
    }

    @Test
    void aFlatFrameHasNoSpread() {
        int[] v = new int[100 * 10];
        java.util.Arrays.fill(v, 120);
        assertEquals(0.0, FocusMetricCalculator.p98MinusP2(gray8(100, 10, v)));
    }

    @Test
    void theSpreadIgnoresTheTwoPercentTails() {
        // 1000 pixels: values 0..999 scaled into 0..249, so the 2nd and 98th percentiles
        // are about 5 and 245 -- the single extreme pixels at either end do not count.
        int w = 100;
        int h = 10;
        int[] v = new int[w * h];
        for (int i = 0; i < v.length; i++) {
            v[i] = i / 4;
        }
        double spread = FocusMetricCalculator.p98MinusP2(gray8(w, h, v));
        assertEquals(240.0, spread, 2.0);
    }

    @Test
    void aSharperFieldHasALargerSpreadThanABlurredOne() {
        int w = 64;
        int h = 4;
        int[] sharp = new int[w * h];
        int[] blurred = new int[w * h];
        for (int i = 0; i < sharp.length; i++) {
            int x = i % w;
            sharp[i] = (x / 4) % 2 == 0 ? 40 : 220; // crisp stripes
            blurred[i] = 130 + (int) (40 * Math.sin(x * Math.PI / 4)); // the same stripes, smoothed
        }
        assertTrue(FocusMetricCalculator.p98MinusP2(gray8(w, h, sharp))
                > FocusMetricCalculator.p98MinusP2(gray8(w, h, blurred)));
    }

    @Test
    void sixteenBitFramesUseTheirFullRange() {
        int w = 50;
        int h = 2;
        byte[] raw = new byte[w * h * 2];
        for (int i = 0; i < w * h; i++) {
            int value = i < w ? 1000 : 60000; // half dark, half bright
            raw[2 * i] = (byte) (value >> 8);
            raw[2 * i + 1] = (byte) value;
        }
        assertEquals(59000.0, FocusMetricCalculator.p98MinusP2(new FrameData(w, h, 1, 2, raw, 0L)));
    }
}
