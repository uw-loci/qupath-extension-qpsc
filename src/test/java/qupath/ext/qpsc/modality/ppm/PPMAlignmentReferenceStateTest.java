package qupath.ext.qpsc.modality.ppm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Parsing of the uncrossed exposure/gain calibration that
 * {@code PPMModalityHandler.applyAlignmentReferenceState} pushes to the camera before SIFT
 * alignment.
 *
 * <p>These are the values that decide whether the alignment frame is usable. Uncrossed runs
 * at roughly 0.2 ms against ~9 ms at the plus angle, so mis-reading the calibration does not
 * produce a slightly-off image -- it produces a fully saturated one, which fails SIFT exactly
 * as thoroughly as the dark near-extinction frame the rotation was meant to escape. Two YAML
 * gain formats are in the wild, so both are pinned here.
 */
class PPMAlignmentReferenceStateTest {

    @Test
    void perChannelExposuresAreReadAsRgb() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("r", 0.21);
        entry.put("g", 0.20);
        entry.put("b", 0.24);

        float[] exp = PPMModalityHandler.parseExposureEntry(entry);
        assertNotNull(exp);
        assertArrayEquals(new float[] {0.21f, 0.20f, 0.24f}, exp, 1e-6f);
    }

    @Test
    void aSingleNumberIsAUnifiedExposure() {
        float[] exp = PPMModalityHandler.parseExposureEntry(0.2);
        assertNotNull(exp);
        assertArrayEquals(new float[] {0.2f}, exp, 1e-6f);
    }

    @Test
    void anIncompleteRgbEntryIsRejectedRatherThanPartiallyApplied() {
        // Applying two of three channels would white-balance the alignment frame wrongly.
        // Returning null makes the caller throw, which is what surfaces "run PPM white balance".
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("r", 0.21);
        entry.put("g", 0.20);
        assertNull(PPMModalityHandler.parseExposureEntry(entry));

        Map<String, Object> zeroed = new LinkedHashMap<>();
        zeroed.put("r", 0.21);
        zeroed.put("g", 0.0);
        zeroed.put("b", 0.24);
        assertNull(PPMModalityHandler.parseExposureEntry(zeroed), "a zero exposure is not a calibration");
    }

    @Test
    void missingExposureIsRejected() {
        assertNull(PPMModalityHandler.parseExposureEntry(null));
        assertNull(PPMModalityHandler.parseExposureEntry("soon"));
    }

    @Test
    void currentGainFormatIsReadAsUnifiedPlusAnalogRedBlue() {
        Map<String, Object> uncrossed = new LinkedHashMap<>();
        uncrossed.put("unified_gain", 5.0);
        uncrossed.put("analog_red", 1.2);
        uncrossed.put("analog_blue", 0.9);

        float[] gains = PPMModalityHandler.parseGainEntry(Map.of("uncrossed", uncrossed));
        assertArrayEquals(new float[] {5.0f, 1.2f, 0.9f}, gains, 1e-6f);
    }

    @Test
    void unifiedOnlyGainOmitsTheAnalogPair() {
        float[] gains = PPMModalityHandler.parseGainEntry(Map.of("uncrossed", Map.of("unified_gain", 3.0)));
        assertArrayEquals(new float[] {3.0f}, gains, 1e-6f);
    }

    @Test
    void uncalibratedGainFallsBackToUnityNotZero() {
        // Zero gain would black out the alignment frame. Unity is the safe default and matches
        // what the Live Viewer's preset buttons do.
        assertArrayEquals(new float[] {1.0f}, PPMModalityHandler.parseGainEntry(null), 1e-6f);
        assertArrayEquals(new float[] {1.0f}, PPMModalityHandler.parseGainEntry(Map.of()), 1e-6f);
        assertArrayEquals(new float[] {1.0f}, PPMModalityHandler.parseGainEntry(Map.of("plus", Map.of())), 1e-6f);
        assertArrayEquals(
                new float[] {1.0f},
                PPMModalityHandler.parseGainEntry(Map.of("uncrossed", Map.of("unified_gain", 0.0))),
                1e-6f);
    }
}
