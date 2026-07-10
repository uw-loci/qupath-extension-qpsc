package qupath.ext.qpsc.modality.ppm;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.BackgroundSettingsReader;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * Resolves the default exposure time (ms) for a PPM polarization angle, using the same
 * priority the (now-removed) per-image angle-selection popup used:
 *
 * <ol>
 *   <li><b>Background flat-field exposure</b> for the angle (so acquisition exposure matches
 *       the divide-correction flat-field exposure -- a hard requirement),</li>
 *   <li>the per-modality config exposure, then</li>
 *   <li>the persistent preference exposure.</li>
 * </ol>
 *
 * <p>Extracted from {@code PPMAngleSelectionController} so the front-loaded angle panel and the
 * non-interactive angle-resolution path share one source of truth.
 */
public final class PPMExposureResolver {

    private static final Logger logger = LoggerFactory.getLogger(PPMExposureResolver.class);

    private PPMExposureResolver() {}

    /**
     * @param angle the polarization angle (ticks/degrees)
     * @param modality the modality name (e.g. "ppm_20x")
     * @param objective the objective ID
     * @param detector the detector ID
     * @param wbMode the selected white-balance mode (targets the matching background subfolder)
     * @return the default exposure (ms) for the angle
     */
    public static double getDefaultExposureTime(
            double angle, String modality, String objective, String detector, String wbMode) {
        // If any hardware parameter is null, fall back to persistent preferences.
        if (modality == null || objective == null || detector == null) {
            double preferencesValue = getPersistentPreferenceExposure(angle);
            logger.info("Using persistent preferences exposure time for angle {}: {}ms", angle, preferencesValue);
            return preferencesValue;
        }

        // Priority 1: background flat-field per-angle exposure.
        try {
            String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configFileLocation);
            String backgroundFolder = configManager.getBackgroundCorrectionFolder(modality);
            if (backgroundFolder != null) {
                BackgroundSettingsReader.BackgroundSettings backgroundSettings =
                        BackgroundSettingsReader.findBackgroundSettings(
                                backgroundFolder, modality, objective, detector, wbMode);
                if (backgroundSettings != null && backgroundSettings.angleExposures != null) {
                    for (AngleExposure ae : backgroundSettings.angleExposures) {
                        if (Math.abs(ae.ticks() - angle) < 0.001) {
                            logger.info(
                                    "Using background image exposure time for angle {}: {}ms", angle, ae.exposureMs());
                            return ae.exposureMs();
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to read background settings for exposure time", e);
        }

        // Priority 2: per-modality config exposure.
        try {
            String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configFileLocation);
            Map<String, Object> exposures = configManager.getModalityExposures(modality, objective, detector);
            if (exposures != null) {
                String angleKey = String.valueOf(angle);
                if (exposures.get(angleKey) instanceof Number n) {
                    logger.info("Using config file exposure time for angle {}: {}ms", angle, n.doubleValue());
                    return n.doubleValue();
                }
                for (String angleName : getAngleNames(angle)) {
                    if (exposures.get(angleName) instanceof Number n) {
                        logger.info(
                                "Using config file exposure time for angle {} (key={}): {}ms",
                                angle,
                                angleName,
                                n.doubleValue());
                        return n.doubleValue();
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to read config file exposure settings", e);
        }

        // Priority 3: persistent preferences.
        double preferencesValue = getPersistentPreferenceExposure(angle);
        logger.info("Using persistent preferences exposure time for angle {}: {}ms", angle, preferencesValue);
        return preferencesValue;
    }

    /** Common config-key names an angle might be stored under. */
    private static String[] getAngleNames(double angle) {
        if (Math.abs(angle - 0.0) < 0.001) {
            return new String[] {"0", "zero", "0.0"};
        } else if (angle > 0 && angle < 20) {
            return new String[] {"plus", "positive", String.valueOf(angle)};
        } else if (angle < 0 && angle > -20) {
            return new String[] {"minus", "negative", String.valueOf(angle)};
        } else if (angle >= 45) {
            return new String[] {"uncrossed", "parallel", String.valueOf(angle)};
        }
        return new String[] {String.valueOf(angle)};
    }

    /** Persistent-preference exposure for the angle range. */
    private static double getPersistentPreferenceExposure(double angle) {
        if (Math.abs(angle - 0.0) < 0.001) {
            return PPMPreferences.getZeroExposureMs();
        } else if (angle > 0 && angle < 20) {
            return PPMPreferences.getPlusExposureMs();
        } else if (angle < 0 && angle > -20) {
            return PPMPreferences.getMinusExposureMs();
        } else if (angle >= 45) {
            return PPMPreferences.getUncrossedExposureMs();
        }
        return 1.0;
    }
}
