package qupath.ext.qpsc.modality.multiphoton;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityMenuItem;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.lib.images.ImageData;

/**
 * Modality handler for multiphoton / SHG (Second Harmonic Generation) imaging.
 *
 * <p>SHG is a single-snap modality -- no rotation stage, no per-angle exposures,
 * no white balance calibration, and no PPM-style post-processing directories.
 * The laser scanning hardware (resolution, dwell time, PMT gain, Pockels power)
 * is configured via acquisition profiles in the YAML config, not by this handler.
 *
 * <p>This handler tells the system:
 * <ul>
 *   <li>No rotation angles (empty list = single snap per tile)</li>
 *   <li>Grayscale image type (16-bit PMT output)</li>
 *   <li>No debayering (monochrome detector)</li>
 * </ul>
 */
public class MultiphotonModalityHandler implements ModalityHandler {

    @Override
    public CompletableFuture<List<AngleExposure>> getRotationAngles(
            String modalityName, String objective, String detector) {
        // SHG acquires a single image per tile -- no rotation angles
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public String getDisplayName() {
        return "SHG";
    }

    @Override
    public Optional<ImageData.ImageType> getImageType() {
        // SHG produces grayscale 16-bit from PMT; OTHER prevents
        // QuPath from trying to interpret it as brightfield H&E
        return Optional.of(ImageData.ImageType.OTHER);
    }

    @Override
    public int getDefaultAngleCount() {
        return 1;
    }

    @Override
    public void configureCommandBuilder(AcquisitionCommandBuilder builder) {
        // LSM images are monochrome from the PMT -- no Bayer pattern
        builder.enableDebayer(false);
    }

    @Override
    public List<ModalityMenuItem> getMenuContributions() {
        // No SHG-specific calibration workflows yet
        return List.of();
    }
}
