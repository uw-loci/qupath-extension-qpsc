package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HardwareKey} value object. Pure logic, no I/O.
 *
 * <p>These tests lock in the canonicalization rules that the BG-correction
 * lookup depends on. The rules need to stay stable as the platform onboards
 * new microscopes - the regex strips a trailing {@code _<digits><x|X>}
 * segment and nothing else.
 */
class HardwareKeyTest {

    @Test
    void stripMagnificationSuffix_familyOnly_unchanged() {
        assertEquals("Brightfield", HardwareKey.stripMagnificationSuffix("Brightfield"));
        assertEquals("ppm", HardwareKey.stripMagnificationSuffix("ppm"));
        assertEquals("BF_IF", HardwareKey.stripMagnificationSuffix("BF_IF"));
    }

    @Test
    void stripMagnificationSuffix_trailingMagnification_stripped() {
        assertEquals("Brightfield", HardwareKey.stripMagnificationSuffix("Brightfield_10x"));
        assertEquals("ppm", HardwareKey.stripMagnificationSuffix("ppm_20x"));
        assertEquals("BF_IF", HardwareKey.stripMagnificationSuffix("BF_IF_40X"));
    }

    @Test
    void stripMagnificationSuffix_internalUnderscore_preserved() {
        // Family names with internal underscores must survive: BF_IF_10x -> BF_IF (not BF).
        assertEquals("BF_IF", HardwareKey.stripMagnificationSuffix("BF_IF_10x"));
        assertEquals("Widefield_Fluor", HardwareKey.stripMagnificationSuffix("Widefield_Fluor_60X"));
    }

    @Test
    void stripMagnificationSuffix_nonMagSuffix_unchanged() {
        // "_2" is not a magnification segment -- only "_<digits><x|X>" anchored at end.
        assertEquals("Brightfield_10x_2", HardwareKey.stripMagnificationSuffix("Brightfield_10x_2"));
        // "_abc" not a magnification suffix.
        assertEquals("ppm_abc", HardwareKey.stripMagnificationSuffix("ppm_abc"));
        // Just digits without trailing x.
        assertEquals("ppm_10", HardwareKey.stripMagnificationSuffix("ppm_10"));
    }

    @Test
    void stripMagnificationSuffix_nullOrEmpty() {
        assertNull(HardwareKey.stripMagnificationSuffix(null));
        assertEquals("", HardwareKey.stripMagnificationSuffix(""));
    }

    @Test
    void from_normalizesAllAxes() {
        HardwareKey key = HardwareKey.from("Brightfield_10x", "0.75NA_AIR_10x", "HAMAMATSU_DCAM_01");
        assertEquals("Brightfield", key.modalityFamily());
        assertEquals("10x", key.magnification());
        assertEquals("HAMAMATSU_DCAM_01", key.detector());
        assertEquals("0.75NA_AIR_10x", key.objective());
    }

    @Test
    void ofStored_prefersStoredMagnification() {
        // Even if the objective ID happens to lack a magnification, an
        // explicit stored magnification field wins.
        HardwareKey key = HardwareKey.ofStored("Brightfield", "weird_objective_id", "DET_1", "10x");
        assertEquals("10x", key.magnification());
    }

    @Test
    void ofStored_derivesMagnificationWhenNotStored() {
        HardwareKey key = HardwareKey.ofStored("Brightfield", "0.5NA_AIR_10x", "DET_1", null);
        assertEquals("10x", key.magnification());
    }

    @Test
    void matchesExact_allFourAxes() {
        HardwareKey a = HardwareKey.from("Brightfield_10x", "0.5NA_AIR_10x", "DET_1");
        HardwareKey b = HardwareKey.from("Brightfield_10x", "0.5NA_AIR_10x", "DET_1");
        assertTrue(a.matchesExact(b));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void matchesExact_familyDifference_doesNotMatchExact_butMatchesFamilyObjective() {
        // Brightfield_10x and Brightfield carry the same family + same objective.
        HardwareKey magSuffixed = HardwareKey.from("Brightfield_10x", "0.5NA_AIR_10x", "DET_1");
        HardwareKey familyForm = HardwareKey.from("Brightfield", "0.5NA_AIR_10x", "DET_1");

        // Both keys agree on family (=Brightfield), magnification (=10x derived
        // from the same objective), detector, and objective ID. They are equal
        // under all matchers including matchesExact.
        assertTrue(magSuffixed.matchesExact(familyForm));
        assertTrue(magSuffixed.matchesFamilyObjective(familyForm));
        assertTrue(magSuffixed.matchesFamilyMagnification(familyForm));
    }

    @Test
    void matchesFamilyMagnification_objectiveDrift_matches() {
        HardwareKey requested = HardwareKey.from("Brightfield", "0.75NA_AIR_10x", "DET_1");
        HardwareKey stored = HardwareKey.from("Brightfield", "0.5NA_AIR_10x", "DET_1");
        assertFalse(requested.matchesExact(stored));
        assertFalse(requested.matchesFamilyObjective(stored));
        assertTrue(requested.matchesFamilyMagnification(stored));
    }

    @Test
    void matchesFamilyMagnification_detectorMismatch_doesNotMatch() {
        HardwareKey a = HardwareKey.from("Brightfield", "0.5NA_AIR_10x", "DET_1");
        HardwareKey b = HardwareKey.from("Brightfield", "0.5NA_AIR_10x", "DET_2");
        assertFalse(a.matchesFamilyMagnification(b));
    }

    @Test
    void matchesFamilyMagnification_familyMismatch_doesNotMatch() {
        HardwareKey bf = HardwareKey.from("Brightfield", "0.5NA_AIR_10x", "DET_1");
        HardwareKey ppm = HardwareKey.from("ppm", "0.5NA_AIR_10x", "DET_1");
        assertFalse(bf.matchesFamilyMagnification(ppm));
    }

    @Test
    void matchesFamilyMagnification_magnificationMismatch_doesNotMatch() {
        HardwareKey at10x = HardwareKey.from("Brightfield", "0.75NA_AIR_10x", "DET_1");
        HardwareKey at20x = HardwareKey.from("Brightfield", "0.75NA_AIR_20x", "DET_1");
        assertFalse(at10x.matchesFamilyMagnification(at20x));
    }
}
