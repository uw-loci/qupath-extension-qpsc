package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parsing and staleness for {@link FocusApproachValidationStore}.
 *
 * <p>The store licenses an unattended Z approach toward the sample, so the two behaviours that
 * matter are that a profile is read faithfully and that a record stops applying when what it was
 * measured against changes. A stale pass is worse than no pass.
 *
 * <p>The persistence round-trip is not exercised here: the store's location derives from the
 * microscope-config preference, which needs a JavaFX prefs backing.
 */
class FocusApproachValidationStoreTest {

    @TempDir
    Path tempDir;

    private Path csv(String content) throws IOException {
        Path p = tempDir.resolve("samples.csv");
        Files.writeString(p, content);
        return p;
    }

    @Test
    void readsActualZAndMetricColumns() throws IOException {
        Path p = csv("""
                idx,wall_ms,z_assumed_um,z_actual_um,metric
                0,0,-500.0,-500.2,10.0
                1,30,-499.7,-499.9,11.0
                2,60,-499.4,-499.5,12.5
                3,90,-499.1,-499.2,14.0
                4,120,-498.8,-498.9,15.5
                """);

        double[][] out = FocusApproachValidationStore.parseSamplesCsv(p);

        assertNotNull(out);
        assertEquals(5, out[0].length);
        // The ACTUAL column (index 3), not the assumed one (index 2): the assumed value is
        // interpolated from the commanded motion, and where the metric really peaks is the
        // whole point of the measurement.
        assertEquals(-500.2, out[0][0], 1e-9);
        assertEquals(-498.9, out[0][4], 1e-9);
        assertEquals(10.0, out[1][0], 1e-9);
        assertEquals(15.5, out[1][4], 1e-9);
    }

    @Test
    void tornOrHeaderLinesAreSkippedRatherThanAbandoningTheProfile() throws IOException {
        Path p = csv("""
                idx,wall_ms,z_assumed_um,z_actual_um,metric
                0,0,-500.0,-500.0,10.0
                this line is garbage
                1,30,-499.0,-499.0,11.0
                2,60,-498.0,
                3,90,-497.0,-497.0,12.0
                4,120,-496.0,-496.0,13.0
                5,150,-495.0,-495.0,14.0
                """);

        double[][] out = FocusApproachValidationStore.parseSamplesCsv(p);

        assertNotNull(out);
        assertEquals(5, out[0].length, "the five parseable rows should survive");
    }

    @Test
    void tooFewUsableSamplesYieldsNull() throws IOException {
        Path p = csv("""
                idx,wall_ms,z_assumed_um,z_actual_um,metric
                0,0,-500.0,-500.0,10.0
                1,30,-499.0,-499.0,11.0
                """);
        assertNull(FocusApproachValidationStore.parseSamplesCsv(p));
    }

    @Test
    void missingFileYieldsNullNotAnException() {
        assertNull(FocusApproachValidationStore.parseSamplesCsv(tempDir.resolve("nope.csv")));
        assertNull(FocusApproachValidationStore.parseSamplesCsv(null));
    }

    @Test
    void aRecordGoesStaleWhenSafeZMoves() {
        var rec = new FocusApproachValidationStore.Record(
                "PPM",
                "ppm_20x",
                "OBJ_40X",
                true,
                false,
                -700.0,
                -400.0,
                300.0,
                9.4,
                List.of(),
                0.2,
                50.0,
                List.of(),
                "2026-08-24T12:00:00Z");

        assertNull(rec.isStaleAgainst(-700.0), "unchanged safe Z keeps the record valid");
        assertNull(rec.isStaleAgainst(-700.4), "sub-micron drift is not a change");

        String why = rec.isStaleAgainst(-650.0);
        assertNotNull(why, "the profile was measured as the approach FROM the old safe Z");
        assertTrue(why.contains("-700") && why.contains("-650"), why);

        assertNotNull(rec.isStaleAgainst(null), "an unset safe Z invalidates the record");
    }

    @Test
    void aFailedRecordCarriesItsReasons() {
        var rec = new FocusApproachValidationStore.Record(
                "PPM",
                "ppm_20x",
                "OBJ_40X",
                false,
                true,
                -700.0,
                -400.0,
                300.0,
                Double.NaN,
                List.of(-380.0),
                0.2,
                50.0,
                List.of("A surface peak sits at Z=-380.0 um, before focus"),
                "2026-08-24T12:00:00Z");

        assertFalse(rec.usable());
        assertEquals(1, rec.falsePeakZs().size());
        assertEquals(-380.0, rec.falsePeakZs().get(0), 1e-9);
        assertFalse(rec.reasons().isEmpty());
        assertTrue(rec.requiresTissueGate(), "a surface before focus means the gate is required");
    }

    @Test
    void theApproachBoundCarriesTheDirectionTheRunMeasured() {
        // PPM as measured 2026-08-26: retract is the POSITIVE direction (safe Z 0, samples
        // near -250), so the approach must scan NEGATIVE. An unsigned bound would send the
        // scan away from the sample and find nothing.
        var ppm = new FocusApproachValidationStore.Record(
                "PPM",
                "ppm_20x",
                "OBJ_20X",
                true,
                false,
                0.0,
                -250.0,
                250.0,
                8.0,
                List.of(),
                0.2,
                50.0,
                List.of(),
                "2026-08-26T12:00:00Z");
        assertEquals(-350.0, ppm.signedApproachBoundUm(1.4), 1e-9);

        // A rig where retract is the negative direction gets the opposite sign from the same
        // arithmetic -- nothing here encodes a convention.
        var other = new FocusApproachValidationStore.Record(
                "Other",
                "bf_10x",
                "OBJ_10X",
                true,
                false,
                -700.0,
                -400.0,
                300.0,
                9.0,
                List.of(),
                0.2,
                50.0,
                List.of(),
                "2026-08-26T12:00:00Z");
        assertEquals(420.0, other.signedApproachBoundUm(1.4), 1e-9);
    }

    @Test
    void anUnusableBoundIsReportedRatherThanGuessed() {
        var noFocus = new FocusApproachValidationStore.Record(
                "PPM",
                "ppm_20x",
                "OBJ_20X",
                true,
                false,
                0.0,
                Double.NaN,
                250.0,
                8.0,
                List.of(),
                0.2,
                50.0,
                List.of(),
                "2026-08-26T12:00:00Z");
        assertTrue(Double.isNaN(noFocus.signedApproachBoundUm(1.4)));

        // Focus AT the safe Z gives no direction, so there is nothing to infer.
        var degenerate = new FocusApproachValidationStore.Record(
                "PPM",
                "ppm_20x",
                "OBJ_20X",
                true,
                false,
                0.0,
                0.0,
                250.0,
                8.0,
                List.of(),
                0.2,
                50.0,
                List.of(),
                "2026-08-26T12:00:00Z");
        assertTrue(Double.isNaN(degenerate.signedApproachBoundUm(1.4)));
    }

    @Test
    void aBigExposureChangeGoesStaleButASmallOneDoesNot() {
        // The focus metric is an intensity spread, so exposure rescales it; enough of a change
        // saturates the sensor and flattens the peak the record claims exists.
        var rec = new FocusApproachValidationStore.Record(
                "PPM",
                "ppm_20x",
                "OBJ_40X",
                true,
                false,
                -700.0,
                -400.0,
                300.0,
                9.4,
                List.of(),
                0.2,
                50.0,
                List.of(),
                "2026-08-24T12:00:00Z");

        assertNull(rec.isStaleAgainst(-700.0, 0.2, 50.0), "unchanged conditions");
        assertNull(rec.isStaleAgainst(-700.0, 0.25, 55.0), "a 25% drift is not a re-measure");
        assertNull(rec.isStaleAgainst(-700.0, Double.NaN, Double.NaN), "unknown conditions cannot invalidate");

        String why = rec.isStaleAgainst(-700.0, 2.0, 50.0);
        assertNotNull(why, "a 10x exposure change must invalidate");
        assertTrue(why.contains("exposure"), why);

        assertNotNull(rec.isStaleAgainst(-700.0, 0.2, 5.0), "a 10x illumination change must invalidate");
    }
}
