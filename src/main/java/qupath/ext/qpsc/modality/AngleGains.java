package qupath.ext.qpsc.modality;

/**
 * Immutable per-angle camera gain settings, used by color modalities (PPM/JAI)
 * whose white-balance calibration applies angle-specific gains in addition to
 * per-channel exposures.
 *
 * <p>This record is <b>separate</b> from {@link AngleExposure} on purpose:
 * {@code AngleExposure} is threaded through the whole acquisition command-building
 * pipeline and must stay a minimal (ticks, exposureMs) pair. Gains, by contrast,
 * are only needed for the <em>background settings-match guard</em> -- deciding
 * whether a stored flat-field background was captured at the same gains that the
 * real acquisition will use. Carrying them in a dedicated structure keeps the hot
 * acquisition path unchanged.</p>
 *
 * <p><strong>Gain components (JAI):</strong></p>
 * <ul>
 *   <li>{@code unifiedGain} -- the master/analog gain applied to all channels.</li>
 *   <li>{@code analogRed} / {@code analogBlue} -- per-channel analog gain trims
 *       used for white balance (green is the reference, so it has no trim).</li>
 * </ul>
 *
 * <p>Every component is a nullable {@link Double}. A component is null when the
 * source (an older {@code background_settings.yml}, or a server that does not yet
 * report gains) did not provide it. The match logic treats a null component as
 * "unknown / do not compare" rather than as a mismatch, so this record is safe to
 * populate partially and safe to read from legacy files.</p>
 *
 * @param ticks       the polarization angle in hardware tick units. Must be finite.
 * @param unifiedGain the master analog gain, or null if unknown.
 * @param analogRed   the red-channel analog gain trim, or null if unknown.
 * @param analogBlue  the blue-channel analog gain trim, or null if unknown.
 */
public record AngleGains(double ticks, Double unifiedGain, Double analogRed, Double analogBlue) {

    /**
     * @throws IllegalArgumentException if {@code ticks} is not finite
     */
    public AngleGains {
        if (!Double.isFinite(ticks)) {
            throw new IllegalArgumentException("Ticks must be finite, got: " + ticks);
        }
    }
}
