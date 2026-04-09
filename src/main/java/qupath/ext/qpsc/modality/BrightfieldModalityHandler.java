package qupath.ext.qpsc.modality;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
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

    @Override
    public CompletableFuture<List<AngleExposure>> getRotationAngles(
            String modalityName, String objective, String detector) {
        // Brightfield: single image per tile, no rotation. We still return a
        // single AngleExposure with angle=0 so downstream code has an exposure
        // value to use; the AcquisitionCommandBuilder's nonRotation flag
        // prevents --angles from being sent, while --exposures uses this value.
        double exposureMs = PersistentPreferences.getLastUnifiedExposureMs();
        return CompletableFuture.completedFuture(List.of(new AngleExposure(0.0, exposureMs)));
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
