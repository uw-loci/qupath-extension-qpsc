package qupath.ext.qpsc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.utilities.FocusMetricsManifest;

/**
 * Loader smoke tests for FocusMetricsManifest. These exercise the
 * packaged manifest at {@code src/main/resources/focus/} which the
 * loader picks up via the user.dir-based last-resort discovery path
 * when no env var or configDir is supplied.
 *
 * <p>The Python tests in microscope_imageprocessing/tests/
 * test_focus_manifest.py already enforce schema-vs-content
 * invariants. This Java side just confirms the SnakeYAML parse and
 * the GUI accessors work.
 */
public class FocusMetricsManifestTest {

    @BeforeEach
    void resetCache() {
        // Force-reload between tests so we don't carry state across.
        FocusMetricsManifest.refresh(null);
    }

    @Test
    void manifestLoadsAndContainsCanonicalMetrics() {
        FocusMetricsManifest m = FocusMetricsManifest.get(null);
        assertNotNull(m);
        // Canonical 10-metric set from M1.
        for (String name : List.of(
                "tenengrad", "laplacian_variance", "brenner_gradient",
                "normalized_variance", "vollath_f5", "sobel", "p98_p2",
                "robust_sharpness_metric", "hybrid_sharpness_metric", "none")) {
            assertTrue(m.getMetrics().containsKey(name),
                    "Missing canonical metric: " + name);
        }
    }

    @Test
    void recommendedGroupContainsTopThree() {
        FocusMetricsManifest m = FocusMetricsManifest.get(null);
        List<String> recommended = m.metricsByGroup(FocusMetricsManifest.Group.RECOMMENDED)
                .stream().map(s -> s.name).toList();
        assertTrue(recommended.contains("tenengrad"));
        assertTrue(recommended.contains("laplacian_variance"));
        assertTrue(recommended.contains("brenner_gradient"));
    }

    @Test
    void aliasMapNamesCanonicalReplacements() {
        FocusMetricsManifest m = FocusMetricsManifest.get(null);
        Optional<String> v = m.aliasFor("volath5");
        assertTrue(v.isPresent());
        assertEquals("vollath_f5", v.get());

        Optional<String> t = m.aliasFor("tenenbaum_gradient");
        assertTrue(t.isPresent());
        assertEquals("tenengrad", t.get());

        // Names not in the alias table.
        assertFalse(m.aliasFor("not_a_real_metric").isPresent());
    }

    @Test
    void modalityDefaultsResolveCaseInsensitively() {
        FocusMetricsManifest m = FocusMetricsManifest.get(null);
        assertEquals("tenengrad", m.modalityDefault("Brightfield").orElseThrow());
        assertEquals("tenengrad", m.modalityDefault("BF").orElseThrow());
        assertEquals("tenengrad", m.modalityDefault("ppm").orElseThrow());
        assertEquals("vollath_f5", m.modalityDefault("Fluorescence").orElseThrow());
        assertEquals("vollath_f5", m.modalityDefault("LSM").orElseThrow());
        // Unknown -> empty (caller decides fallback).
        assertFalse(m.modalityDefault("not_a_real_modality").isPresent());
    }

    @Test
    void resolveEffectiveMetricMatchesPythonBehaviour() {
        FocusMetricsManifest m = FocusMetricsManifest.get(null);
        // Canonical YAML override wins.
        assertEquals("laplacian_variance",
                m.resolveEffectiveMetric("brightfield", "laplacian_variance", "tenengrad"));
        // Legacy alias resolves to canonical.
        assertEquals("vollath_f5",
                m.resolveEffectiveMetric("fluorescence", "volath5", "tenengrad"));
        // No YAML -> modality default.
        assertEquals("tenengrad",
                m.resolveEffectiveMetric("brightfield", null, "tenengrad"));
        // 'none' sentinel skips override -> modality default.
        assertEquals("vollath_f5",
                m.resolveEffectiveMetric("fluorescence", "none", "tenengrad"));
        // Unknown modality + no YAML -> caller's fallback.
        assertEquals("tenengrad",
                m.resolveEffectiveMetric("not_a_real_modality", null, "tenengrad"));
    }

    @Test
    void validityChecksAndStrategiesPresent() {
        FocusMetricsManifest m = FocusMetricsManifest.get(null);
        for (String c : List.of("texture_and_area", "bright_spot_count",
                "total_gradient_energy", "always_false")) {
            assertTrue(m.getValidityChecks().containsKey(c),
                    "Missing validity check: " + c);
        }
        for (String s : List.of("dense_texture", "sparse_signal",
                "dark_field", "manual_only")) {
            assertTrue(m.getStrategies().containsKey(s),
                    "Missing strategy: " + s);
        }
    }

    @Test
    void supportedPathsReflectManifest() {
        FocusMetricsManifest m = FocusMetricsManifest.get(null);
        // tenengrad supports all three paths (default for tissue).
        FocusMetricsManifest.MetricSpec ten = m.getMetrics().get("tenengrad");
        assertTrue(ten.supportsStreaming());
        assertTrue(ten.supportsStandard());
        assertTrue(ten.supportsStrategy());
        // vollath_f5 is streaming-only (per manifest).
        FocusMetricsManifest.MetricSpec voll = m.getMetrics().get("vollath_f5");
        assertTrue(voll.supportsStreaming());
        assertFalse(voll.supportsStandard());
    }

    @Test
    void dropdownOrderingPlacesRecommendedFirst() {
        FocusMetricsManifest m = FocusMetricsManifest.get(null);
        List<FocusMetricsManifest.MetricSpec> ordered = m.metricsForDropdown();
        // First Recommended entry should appear before any Advanced or Special.
        int firstRec = -1, firstAdv = -1, firstSpec = -1;
        for (int i = 0; i < ordered.size(); i++) {
            FocusMetricsManifest.Group g = ordered.get(i).group;
            if (g == FocusMetricsManifest.Group.RECOMMENDED && firstRec < 0) firstRec = i;
            if (g == FocusMetricsManifest.Group.ADVANCED && firstAdv < 0) firstAdv = i;
            if (g == FocusMetricsManifest.Group.SPECIAL && firstSpec < 0) firstSpec = i;
        }
        assertTrue(firstRec >= 0 && firstAdv > firstRec,
                "Recommended should come before Advanced in dropdown order");
        assertTrue(firstAdv > 0 && firstSpec > firstAdv,
                "Advanced should come before Special in dropdown order");
    }
}
