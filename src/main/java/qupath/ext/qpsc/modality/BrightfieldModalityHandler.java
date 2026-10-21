package qupath.ext.qpsc.modality;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.utilities.BackgroundProfiles;
import qupath.ext.qpsc.utilities.BackgroundSettingsReader;
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
        //   1. The background set in the active background profile for this detector,
        //      magnification and white balance mode: its background_settings.yml records
        //      the exposure the collection tuned to hit the target intensity. Using it
        //      keeps acquisition consistent with the flat-field reference it will be
        //      divided by.
        //   2. PersistentPreferences.lastUnifiedExposureMs -- a generic
        //      fallback that reflects the last value used in any unified-exposure
        //      workflow. Only used when background correction is off.
        double exposureMs = resolveExposureMs(modalityName, objective, detector, wbMode);
        return CompletableFuture.completedFuture(List.of(new AngleExposure(0.0, exposureMs)));
    }

    /**
     * Resolve the brightfield exposure value.
     *
     * <p>Resolution policy:
     * <ol>
     *   <li>If the active background profile holds a set for this detector,
     *       magnification and white balance mode, return the exposure its
     *       {@code background_settings.yml} records. Objectives of one
     *       magnification share that folder, so a different objective at the
     *       same magnification reuses it, as the background TIFF itself is.</li>
     *   <li>If background correction is enabled but there is no such set (no
     *       active profile, or none collected in it), throw
     *       {@link BackgroundCalibrationMismatchException}. Silently falling
     *       back to a stale generic preference would run acquisition at the
     *       wrong exposure and corrupt flat-field correction without any
     *       visible warning - the previous bug class this method is designed
     *       to prevent.</li>
     *   <li>If background correction is disabled, return
     *       {@link PersistentPreferences#getLastUnifiedExposureMs()}. This is
     *       the legitimate no-BG path; the preference value is the user's
     *       last interactive exposure choice.</li>
     * </ol>
     */
    private double resolveExposureMs(String modalityName, String objective, String detector, String wbMode) {
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
        String profileFolder = mgr.getBackgroundProfileFolder();
        String mode = (wbMode == null || wbMode.isBlank()) ? "off" : wbMode;
        // The background's exposure is the reference only when the tiles will be divided by
        // that background; with correction off the operator's last exposure is the answer.
        BackgroundSettingsReader.BackgroundSettings bg = (!bgEnabled || profileFolder == null)
                ? null
                : BackgroundSettingsReader.findBackgroundSettings(
                        profileFolder, modalityName, objective, detector, mode);

        if (bg != null && bg.angleExposures != null && !bg.angleExposures.isEmpty()) {
            Double exposure = null;
            for (AngleExposure ae : bg.angleExposures) {
                if (Math.abs(ae.ticks()) < 0.001) {
                    exposure = ae.exposureMs();
                }
            }
            if (exposure == null) {
                exposure = bg.angleExposures.get(0).exposureMs();
            }
            if (bg.objective != null && !bg.objective.equals(objective)) {
                logger.warn(
                        "Brightfield background was collected with objective '{}' but '{}' is in use; reusing its"
                                + " exposure {} ms because both share the background set at this magnification."
                                + " Re-run Background Collection if the illumination geometry differs.",
                        bg.objective,
                        objective,
                        exposure);
            } else {
                logger.info(
                        "Brightfield using background collection exposure: {} ms (profile={}, modality={}, objective={},"
                                + " detector={}, wb={})",
                        exposure,
                        BackgroundProfiles.activeId(),
                        modalityName,
                        objective,
                        detector,
                        mode);
            }
            return exposure;
        }

        if (bgEnabled) {
            HardwareKey requested = HardwareKey.from(modalityName, objective, detector);
            String profileId = BackgroundProfiles.activeId();
            String message = profileFolder == null
                    ? String.format(
                            "Background correction is enabled for '%s' but no background profile is active%s. Choose one"
                                    + " in the acquisition dialog or Utilities > Image Quality > Background Profiles, or"
                                    + " disable background correction.",
                            modalityName,
                            profileId == null ? "" : " (the active profile '" + profileId + "' was not found)")
                    : String.format(
                            "Background correction is enabled for '%s' but the active background profile '%s' has no"
                                    + " background for objective='%s' detector='%s' (white balance '%s'). Run Background"
                                    + " Collection into this profile for this hardware, or disable background"
                                    + " correction.",
                            modalityName, profileId, objective, detector, mode);
            logger.error("Brightfield exposure resolution failed: {}", message);
            throw new BackgroundCalibrationMismatchException(message, requested);
        }

        double fallback = PersistentPreferences.getLastUnifiedExposureMs();
        logger.info(
                "Brightfield BG correction disabled; using PersistentPreferences.lastUnifiedExposureMs: {} ms "
                        + "(modality={}, objective={}, detector={})",
                fallback,
                modalityName,
                objective,
                detector);
        return fallback;
    }

    /**
     * Brightfield contributes an exposure panel to the Bounded Acquisition
     * dialog: it states how the single exposure is resolved and lets the
     * operator override it (with a warning that overriding invalidates the
     * saved background/flat-field profile). See
     * {@link BrightfieldExposureBoundingBoxUI}.
     */
    @Override
    public Optional<BoundingBoxUI> createBoundingBoxUI() {
        return Optional.of(new BrightfieldExposureBoundingBoxUI());
    }

    /**
     * Applies the operator's exposure override from
     * {@link BrightfieldExposureBoundingBoxUI}. The override map carries a
     * single {@code "exposure"} entry (milliseconds); when present it replaces
     * the exposure on every (single, angle=0) acquisition step. Without it the
     * handler-resolved exposure is used unchanged.
     */
    @Override
    public List<AngleExposure> applyAngleOverrides(List<AngleExposure> angles, Map<String, Double> overrides) {
        if (overrides == null) {
            return angles;
        }
        Double exposure = overrides.get(BrightfieldExposureBoundingBoxUI.OVERRIDE_KEY);
        if (exposure == null || exposure <= 0) {
            return angles;
        }
        logger.info("Brightfield exposure overridden by user to {} ms (bypasses background profile)", exposure);
        List<AngleExposure> adjusted = new ArrayList<>(angles.size());
        for (AngleExposure ae : angles) {
            adjusted.add(new AngleExposure(ae.ticks(), exposure));
        }
        return adjusted;
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
