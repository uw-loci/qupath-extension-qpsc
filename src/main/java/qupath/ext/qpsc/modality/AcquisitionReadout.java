package qupath.ext.qpsc.modality;

import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * Resolves, for an acquisition-dialog readout, the same values the acquisition
 * will actually use: which profile the objective maps to, the illumination
 * intensity that profile applies, and the exposure that will be sent.
 *
 * <p>These come from a different chain than the Live Viewer Camera tab (which
 * shows live hardware inputs), so this centralizes the "what will be used"
 * resolution so both the brightfield and PPM dialog panels display the real
 * acquisition values, sourced from the same resolvers the handlers use. All
 * methods are non-throwing and null-tolerant so they are safe to call while the
 * dialog's objective/detector selection is still settling.
 */
public final class AcquisitionReadout {

    private AcquisitionReadout() {}

    /**
     * The current dialog selection a panel needs to resolve its readout. Supplied
     * lazily by the host dialog so the panel can refresh when the selection changes.
     * {@code wbMode} is the protocol name (e.g. {@code "off"}, {@code "per_angle"});
     * used by PPM to target the matching background subfolder, ignored by brightfield.
     */
    public record Context(String modality, String objective, String detector, String wbMode) {}

    /** The acquisition-profile name the objective resolves to, or {@code null} if none. */
    public static String resolveProfileName(MicroscopeConfigManager cfg, String modality, String objective) {
        if (cfg == null || modality == null || objective == null) {
            return null;
        }
        try {
            return cfg.resolveProfileKey(modality, objective);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The illumination intensity the resolved profile applies, or {@code null} when
     * the modality has no adjustable illumination (e.g. PPM, which has exposure but
     * no intensity).
     */
    public static Double resolveIlluminationIntensity(MicroscopeConfigManager cfg, String modality, String objective) {
        String profileKey = resolveProfileName(cfg, modality, objective);
        if (cfg == null || profileKey == null) {
            return null;
        }
        try {
            return cfg.getProfileIlluminationIntensity(profileKey);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolved brightfield exposure and where it came from -- mirrors
     * {@code BrightfieldModalityHandler.resolveExposureMs} without throwing. When
     * background correction is on but not calibrated for this hardware, returns a
     * warning ({@code exposureMs == null}) rather than the exception the handler
     * raises, so the dialog can surface it early.
     *
     * @return an {@link Exposure} describing the value and its source
     */
    public static Exposure resolveBrightfieldExposure(
            MicroscopeConfigManager cfg, String modality, String objective, String detector) {
        if (cfg == null || modality == null || objective == null || detector == null) {
            return new Exposure(null, "unavailable", false);
        }
        try {
            var match = cfg.findBackgroundExposures(modality, objective, detector);
            if (match != null) {
                Double exposure = match.exposures().get(0.0);
                if (exposure == null && !match.exposures().isEmpty()) {
                    exposure = match.exposures().values().iterator().next();
                }
                return new Exposure(exposure, "from Background Collection", false);
            }
            if (cfg.isBackgroundCorrectionEnabled(modality)) {
                // The handler throws BackgroundCalibrationMismatchException here.
                return new Exposure(
                        null, "Background correction ON but not calibrated -- run Background Collection", true);
            }
            return new Exposure(
                    PersistentPreferences.getLastUnifiedExposureMs(), "last used (no background correction)", false);
        } catch (Exception e) {
            return new Exposure(null, "unavailable", false);
        }
    }

    /**
     * A resolved exposure value and a short description of where it came from.
     *
     * @param exposureMs the exposure in ms, or {@code null} when it could not be
     *                   resolved (see {@code warning})
     * @param source     a short human-readable source description
     * @param warning    true when {@code exposureMs == null} because a required
     *                   calibration is missing (acquisition would fail), not merely
     *                   unavailable
     */
    public record Exposure(Double exposureMs, String source, boolean warning) {}
}
