package qupath.ext.qpsc.utilities;

import java.util.Map;

/**
 * Outcome of a {@link MicroscopeConfigManager#findBackgroundExposures} lookup.
 *
 * <p>Carries both the exposure data and the strength of the match so callers
 * can differentiate "found exact calibration" from "found compatible
 * calibration but with caveats" from "found nothing".
 *
 * <p>The lookup is layered:
 * <ul>
 *   <li>{@link Tier#EXACT} - all four axes (modality family, magnification,
 *       detector, full objective ID) match. Use silently.</li>
 *   <li>{@link Tier#FAMILY} - modality differs only in magnification suffix
 *       (e.g. requested {@code Brightfield_10x} matched stored {@code Brightfield});
 *       objective + detector identical. Use silently - this is the standard
 *       calling convention everywhere else in the codebase.</li>
 *   <li>{@link Tier#MAGNIFICATION} - objective IDs differ but resolve to the
 *       same magnification (e.g. {@code 0.5NA_AIR_10x} vs {@code 0.75NA_AIR_10x});
 *       detector + family identical. The on-disk background TIFF is already
 *       shared across all objectives at this magnification, so the exposure
 *       value is appropriate to reuse - but log a WARN with both objective
 *       IDs for traceability.</li>
 *   <li>No row at all - lookup returns {@code null} (not a match object).</li>
 * </ul>
 *
 * <p>Detector mismatch and modality-family mismatch are always hard rejects;
 * lookups never degrade across those axes.
 */
public record BackgroundExposureMatch(
        Map<Double, Double> exposures,
        Tier tier,
        HardwareKey requested,
        HardwareKey stored) {

    public enum Tier {
        /** All four axes match. */
        EXACT,
        /** Modality family + objective + detector match (modality may differ in magnification suffix). */
        FAMILY,
        /** Modality family + magnification + detector match (objective IDs differ). */
        MAGNIFICATION
    }

    /** True when objective IDs differ between requested and stored. */
    public boolean isObjectiveDrift() {
        return tier == Tier.MAGNIFICATION;
    }
}
