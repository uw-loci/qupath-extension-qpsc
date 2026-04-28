package qupath.ext.qpsc.modality;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.widefield.ui.WidefieldChannelBoundingBoxUI;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.lib.images.ImageData;

/**
 * Modality handler for widefield epi-fluorescence microscopy.
 *
 * <p>Widefield fluorescence imaging characteristics:
 * <ul>
 *   <li>No rotation stage (single snap per tile position)</li>
 *   <li>Monochrome detector output (sCMOS, no debayering)</li>
 *   <li>No software white balance (single-channel fluorescence signal)</li>
 *   <li>Epi-illumination (LED or arc lamp) with filter cube/turret selection</li>
 *   <li>Filter, shutter, and light path controlled via acquisition profiles (mm_setup_presets)</li>
 * </ul>
 *
 * <p>Different fluorescence channels (DAPI, GFP, RFP, etc.) are represented
 * as separate acquisition profiles rather than separate modality handlers,
 * since they differ only in hardware configuration (filter cube, excitation
 * wavelength) and not in acquisition behavior.
 *
 * <p>Registered under prefixes: "fl", "fluorescence", "widefield", "epi"
 */
public class WidefieldFluorescenceModalityHandler implements ModalityHandler {

    private static final Logger logger = LoggerFactory.getLogger(WidefieldFluorescenceModalityHandler.class);

    @Override
    public CompletableFuture<List<AngleExposure>> getRotationAngles(
            String modalityName, String objective, String detector, String wbMode) {
        // wbMode is unused -- widefield fluorescence is always channel-based
        // or a single snap with no WB-aware angle dialog.
        // If a channel library is declared for this profile, the workflow will
        // take the channel path and ignore angles. Return an empty list so the
        // command builder doesn't also emit a bogus single exposure.
        if (!resolveChannels(modalityName).isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        // Legacy single-channel fallback: one snap with last-used exposure.
        double exposureMs = PersistentPreferences.getLastUnifiedExposureMs();
        return CompletableFuture.completedFuture(List.of(new AngleExposure(0.0, exposureMs)));
    }

    @Override
    public CompletableFuture<List<Channel>> getChannels(String modalityName, String objective, String detector) {
        return CompletableFuture.completedFuture(resolveChannels(modalityName));
    }

    @Override
    public Optional<BoundingBoxUI> createBoundingBoxUI() {
        WidefieldChannelBoundingBoxUI ui = new WidefieldChannelBoundingBoxUI();
        // Only return the UI if a channel library is actually configured;
        // otherwise fall through to the angle-based default (null UI, single snap).
        return ui.hasChannels() ? Optional.of(ui) : Optional.empty();
    }

    private List<Channel> resolveChannels(String profileKey) {
        if (profileKey == null || profileKey.isBlank()) {
            return List.of();
        }
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        if (mgr == null) {
            String path = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (path == null || path.isBlank()) {
                return List.of();
            }
            mgr = MicroscopeConfigManager.getInstance(path);
        }
        try {
            return mgr.getChannelsForProfile(profileKey);
        } catch (Exception e) {
            logger.warn("Failed to resolve channels for profile '{}': {}", profileKey, e.getMessage());
            return List.of();
        }
    }

    @Override
    public String getDisplayName() {
        return "Widefield Fluorescence";
    }

    @Override
    public Optional<ImageData.ImageType> getImageType() {
        return Optional.of(ImageData.ImageType.FLUORESCENCE);
    }

    @Override
    public int getDefaultAngleCount() {
        return 1;
    }

    @Override
    public void configureCommandBuilder(AcquisitionCommandBuilder builder) {
        // Monochrome sCMOS detector -- no Bayer pattern
        builder.enableDebayer(false);
    }

    @Override
    public String getDefaultWbMode() {
        // Fluorescence does not use white balance
        return "off";
    }

    @Override
    public List<ModalityMenuItem> getMenuContributions() {
        // No fluorescence-specific calibration workflows yet.
        // Future: flat-field correction, channel registration, etc.
        return List.of();
    }

    /**
     * Fluorescence detectors are typically monochrome sCMOS sensors -- the
     * "channel" identity comes from the active filter cube (DAPI/FITC/etc.),
     * not from a Bayer pattern. Saturation is reported per filter-cube
     * channel rather than collapsed into a single worst column.
     */
    @Override
    public ChannelDisplay channelDisplay(boolean rgbCamera) {
        return ChannelDisplay.PER_CHANNEL;
    }
}

