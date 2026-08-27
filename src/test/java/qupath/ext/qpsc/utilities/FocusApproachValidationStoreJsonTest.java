package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * JSON encoding for {@link FocusApproachValidationStore}.
 *
 * <p>Several fields mean "not measured", and this record spells that NaN. Gson rejects NaN
 * outright because it is not valid JSON, which on 2026-08-26 failed an entire save with "NaN is
 * not a valid double value" AFTER a successful 235 um validation traverse -- the measurement was
 * made, reported to the operator, and then thrown away on the way to disk.
 *
 * <p>What makes this worth pinning rather than patching is the shape of the wrong fix. Writing a
 * bare {@code NaN} token produces JSON nothing else will read; omitting the member produces a
 * {@code double} that Gson reconstructs as 0.0. On PPM the real safe Z IS 0.0, so that second
 * failure would restore a plausible wrong number instead of an obvious missing one.
 */
class FocusApproachValidationStoreJsonTest {

    private static FocusApproachValidationStore.Record recordWith(
            double safeZ, double focusZ, double peakWidth, double exposureMs, double illumination) {
        return new FocusApproachValidationStore.Record(
                "PPM",
                "ppm",
                "OBJ_20X",
                true,
                false,
                safeZ,
                focusZ,
                235.3,
                peakWidth,
                List.of(),
                exposureMs,
                illumination,
                List.of(),
                "2026-08-26T19:18:22Z");
    }

    private static Map<String, FocusApproachValidationStore.Record> oneRecord(FocusApproachValidationStore.Record r) {
        Map<String, FocusApproachValidationStore.Record> m = new LinkedHashMap<>();
        m.put("PPM|ppm|OBJ_20X", r);
        return m;
    }

    @Test
    void aRecordCarryingNaNSerializesInsteadOfThrowing() {
        // The reported failure: illumination was not measurable on this rig.
        String json =
                FocusApproachValidationStore.serialize(oneRecord(recordWith(0.0, -235.6, 18.1, 0.10, Double.NaN)));

        assertNotNull(json);
        assertFalse(json.contains("NaN"), "a bare NaN token is not valid JSON: " + json);
    }

    @Test
    void anUnmeasuredValueComesBackAsNaNAndNotAsZero() {
        // The dangerous restoration: 0.0 is PPM's real safe Z, so an unmeasured field that
        // reloads as 0.0 is indistinguishable from a measured one.
        String json = FocusApproachValidationStore.serialize(
                oneRecord(recordWith(Double.NaN, -235.6, 18.1, 0.10, Double.NaN)));

        var back = FocusApproachValidationStore.deserialize(json).get("PPM|ppm|OBJ_20X");

        assertTrue(Double.isNaN(back.safeZUm()), "unmeasured safe Z must not reload as 0.0");
        assertTrue(Double.isNaN(back.illumination()), "unmeasured illumination must stay unmeasured");
    }

    @Test
    void realMeasurementsRoundTripUnchanged() {
        var original = recordWith(0.0, -235.6, 18.1, 0.10, 42.5);

        var back = FocusApproachValidationStore.deserialize(FocusApproachValidationStore.serialize(oneRecord(original)))
                .get("PPM|ppm|OBJ_20X");

        assertEquals(0.0, back.safeZUm(), 1e-9);
        assertEquals(-235.6, back.focusZUm(), 1e-9);
        assertEquals(18.1, back.peakWidthUm(), 1e-9);
        assertEquals(42.5, back.illumination(), 1e-9);
        assertTrue(back.usable());
    }

    @Test
    void aNaNInsideTheFalsePeakListDoesNotBreakTheSave() {
        // falsePeakZs is List<Double>, so the element adapter has to hold the same contract.
        var withBadPeak = new FocusApproachValidationStore.Record(
                "PPM",
                "ppm",
                "OBJ_20X",
                false,
                true,
                0.0,
                -235.6,
                235.3,
                18.1,
                java.util.Arrays.asList(-180.0, Double.NaN),
                0.10,
                Double.NaN,
                List.of("surface peak before focus"),
                "2026-08-26T19:18:22Z");

        String json = FocusApproachValidationStore.serialize(oneRecord(withBadPeak));

        assertFalse(json.contains("NaN"), json);
        var back = FocusApproachValidationStore.deserialize(json).get("PPM|ppm|OBJ_20X");
        assertEquals(2, back.falsePeakZs().size());
        assertEquals(-180.0, back.falsePeakZs().get(0), 1e-9);
        assertTrue(Double.isNaN(back.falsePeakZs().get(1)));
    }
}
