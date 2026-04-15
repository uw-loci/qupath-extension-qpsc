package qupath.ext.qpsc.modality;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
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
     * Resolve the brightfield exposure value, preferring the most recent
     * background collection's adaptive-exposure result over generic preferences.
     */
    private double resolveExposureMs(String modalityName, String objective, String detector) {
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (configPath != null) {
                MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
                // Reload so we see the latest background collection output even if
                // it was written during this session.
                mgr.reload(configPath);
                Map<Double, Double> bgExposures = mgr.getBackgroundExposures(modalityName, objective, detector);
                if (bgExposures != null && !bgExposures.isEmpty()) {
                    // Brightfield has a single angle (0.0). Prefer the 0.0 entry
                    // if present, otherwise take the first available entry --
                    // background collection writes a single-angle entry for
                    // non-rotation modalities.
                    Double exposure = bgExposures.get(0.0);
                    if (exposure == null) {
                        exposure = bgExposures.values().iterator().next();
                    }
                    logger.info(
                            "Brightfield using background collection exposure: {} ms (modality={}, objective={}, detector={})",
                            exposure, modalityName, objective, detector);
                    return exposure;
                }
            }
        } catch (Exception e) {
            logger.warn(
                    "Failed to read background exposures for brightfield; falling back to PersistentPreferences: {}",
                    e.getMessage());
        }

        double fallback = PersistentPreferences.getLastUnifiedExposureMs();
        logger.info(
                "Brightfield using PersistentPreferences.lastUnifiedExposureMs fallback: {} ms (no matching background_exposures for modality={}, objective={}, detector={})",
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
