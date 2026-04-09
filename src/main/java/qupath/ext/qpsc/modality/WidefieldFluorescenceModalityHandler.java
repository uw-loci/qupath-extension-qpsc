package qupath.ext.qpsc.modality;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
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

    @Override
    public CompletableFuture<List<AngleExposure>> getRotationAngles(
            String modalityName, String objective, String detector) {
        // Widefield fluorescence: single image per tile, no rotation. Return
        // a single AngleExposure with angle=0 so the command builder has an
        // exposure to emit via --exposures; the nonRotation flag suppresses
        // --angles.
        double exposureMs = PersistentPreferences.getLastUnifiedExposureMs();
        return CompletableFuture.completedFuture(List.of(new AngleExposure(0.0, exposureMs)));
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
}
