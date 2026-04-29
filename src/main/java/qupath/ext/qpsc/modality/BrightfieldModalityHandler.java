package qupath.ext.qpsc.modality;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.utilities.BackgroundExposureMatch;
import qupath.ext.qpsc.utilities.HardwareKey;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.lib.images.ImageData;

/**
 * Modality handler for transmitted-light brightfield microscopy.
 *
 * <p>Brightfield imaging characteristics:
 * <ul>
 *   <li>No rotation stage (single snap per tile position)</li>
 *   <li>Monochrome or color output depending on camera (no debayer for sCMOS)</li>
 *   <li>No software white balance (uniform illumination assumed)</li>
 *   <li>Background correction via flat-field division</li>
 *   <li>DiaLamp or LED illumination controlled via acquisition profiles</li>
 * </ul>
 *
 * <p>Registered under prefixes: "bf", "brightfield"
 */
public class BrightfieldModalityHandler implements ModalityHandler {

    private static final Logger logger = LoggerFactory.getLogger(BrightfieldModalityHandler.class);

    /** Brightfield expects every tile to fill the dynamic range -- dim tiles are a calibration symptom. */
    @Override
    public boolean expectsUniformBrightness() {
        return true;
    }

    @Override
    public CompletableFuture<List<AngleExposure>> getRotationAngles(
            String modalityName, String objective, String detector, String wbMode) {
        // wbMode is unused here -- brightfield uses monochrome detectors or
        // "off" WB only; the angle-selection path has no WB-aware dialog.
        // Brightfield: single image per tile, no rotation. We still return a
        // single AngleExposure with angle=0 so downstream code has an exposure
        // value to use; the AcquisitionCommandBuilder's nonRotation flag
        // prevents --angles from being sent, while --exposures uses this value.
        //
        // Exposure source priority:
        //   1. calibration_targets.background_exposures from the imageprocessing
        //      YAML -- this is what the most recent background collection tuned
        //      to hit the user's target intensity (e.g. 30000 counts). Using
        //      this keeps acquisition consistent with the flat-field reference.
        //   2. PersistentPreferences.lastUnifiedExposureMs -- a generic
        //      fallback that reflects the last value used in any unified-exposure
        //      workflow. Only used when no matching background exposures exist.
        double exposureMs = resolveExposureMs(modalityName, objective, detector);
        return CompletableFuture.completedFuture(List.of(new AngleExposure(0.0, exposureMs)));
    }

    /**
     * Resolve the brightfield exposure value.
     *
     * <p>Resolution policy:
     * <ol>
     *   <li>If background correction is enabled for this modality and a
     *       calibration row matches at any tier, return that exposure.
     *       Tier-2 matches (objective drift) log a WARN with both objective
     *       IDs but proceed - the on-disk background TIFF is shared across
     *       all objectives at this magnification, so the calibrated exposure
     *       is appropriate to reuse.</li>
     *   <li>If background correction is enabled but no calibration row
     *       matches, throw {@link BackgroundCalibrationMismatchException}.
     *       Silently falling back to a stale generic preference would run
     *       acquisition at the wrong exposure and corrupt flat-field
     *       correction without any visible warning - the previous bug class
     *       this method is designed to prevent.</li>
     *   <li>If background correction is disabled, return
     *       {@link PersistentPreferences#getLastUnifiedExposureMs()}. This is
     *       the legitimate no-BG path; the preference value is the user's
     *       last interactive exposure choice.</li>
     * </ol>
     */
    private double resolveExposureMs(String modalityName, String objective, String detector) {
        // Prefer the already-initialized singleton (avoids touching the
        // JavaFX-backed preference layer from headless contexts like tests).
        // The singleton is keyed by config path and is the source of truth
        // for the microscope currently in use.
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        if (mgr == null) {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (configPath == null || configPath.isBlank()) {
                double fallback = PersistentPreferences.getLastUnifiedExposureMs();
                logger.info("Brightfield: no config available, using PersistentPreferences fallback: {} ms", fallback);
                return fallback;
            }
            mgr = MicroscopeConfigManager.getInstance(configPath);
        }
        // Reload so we see the latest background collection output even if
        // it was written during this session. No-arg form is atomic against
        // concurrent getInstance(newPath) / reload(otherPath) callers.
        mgr.reload();

        boolean bgEnabled = mgr.isBackgroundCorrectionEnabled(modalityName);
        BackgroundExposureMatch match = mgr.findBackgroundExposures(modalityName, objective, detector);

        if (match != null) {
            Double exposure = match.exposures().get(0.0);
            if (exposure == null) {
                exposure = match.exposures().values().iterator().next();
            }

            if (match.isObjectiveDrift()) {
                logger.warn(
                        "Brightfield BG calibration objective drift: stored objective '{}' (mag={}) but requested '{}' (mag={}). "
                                + "Reusing calibrated exposure {} ms because both objectives share the same background TIFF on disk. "
                                + "Re-run Background Collection if illumination geometry differs.",
                        match.stored().objective(), match.stored().magnification(),
                        match.requested().objective(), match.requested().magnification(),
                        exposure);
            } else {
                logger.info(
                        "Brightfield using background collection exposure: {} ms (tier={}, modality={}, objective={}, detector={})",
                        exposure, match.tier(), modalityName, objective, detector);
            }
            return exposure;
        }

        if (bgEnabled) {
            HardwareKey requested = HardwareKey.from(modalityName, objective, detector);
            String message = String.format(
                    "Background correction is enabled for '%s' but no matching calibration row was found for "
                            + "objective='%s' detector='%s'. Run Background Collection for this hardware combination, "
                            + "or disable background correction in the imageprocessing config.",
                    modalityName, objective, detector);
            logger.error("Brightfield exposure resolution failed: {}", message);
            throw new BackgroundCalibrationMismatchException(message, requested);
        }

        double fallback = PersistentPreferences.getLastUnifiedExposureMs();
        logger.info(
                "Brightfield BG correction disabled; using PersistentPreferences.lastUnifiedExposureMs: {} ms "
                        + "(modality={}, objective={}, detector={})",
                fallback, modalityName, objective, detector);
        return fallback;
    }

    @Override
    public String getDisplayName() {
        return "Brightfield";
    }

    @Override
    public Optional<ImageData.ImageType> getImageType() {
        return Optional.of(ImageData.ImageType.BRIGHTFIELD_H_E);
    }

    @Override
    public int getDefaultAngleCount() {
        return 1;
    }

    @Override
    public void configureCommandBuilder(AcquisitionCommandBuilder builder) {
        // Monochrome sCMOS -- no Bayer pattern
        builder.enableDebayer(false);
    }

    @Override
    public List<ModalityMenuItem> getMenuContributions() {
        return List.of();
    }
}
