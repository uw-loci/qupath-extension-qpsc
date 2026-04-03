package qupath.ext.qpsc.modality.laserscan;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityMenuItem;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.lib.images.ImageData;

/**
 * Base modality handler for all laser/point scanning microscopy.
 *
 * <p>Covers confocal (1-photon), two-photon, and SHG (second harmonic generation).
 * All point scanning modalities share these characteristics:
 * <ul>
 *   <li>No rotation stage (single snap per tile position)</li>
 *   <li>Monochrome output (PMT / hybrid detector, not Bayer camera)</li>
 *   <li>No software white balance (single-channel detector)</li>
 *   <li>No debayering</li>
 *   <li>Resolution, pixel rate, laser power, PMT gain set via acquisition profiles</li>
 * </ul>
 *
 * <p>Sub-types (confocal, 2P, SHG) differ in hardware configuration
 * (laser wavelength, detector type) but not in acquisition behavior.
 * The distinction is in the YAML acquisition profile, not this handler.
 *
 * <p>Registered under prefixes: "lsm", "shg", "2p", "confocal"
 */
public class LaserScanningModalityHandler implements ModalityHandler {

    @Override
    public CompletableFuture<List<AngleExposure>> getRotationAngles(
            String modalityName, String objective, String detector) {
        // Point scanning acquires a single image per tile -- no rotation
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public String getDisplayName() {
        return "Laser Scanning";
    }

    @Override
    public Optional<ImageData.ImageType> getImageType() {
        // Point scanning produces monochrome from PMT/hybrid detector;
        // OTHER prevents QuPath from misinterpreting as brightfield H&E
        return Optional.of(ImageData.ImageType.OTHER);
    }

    @Override
    public int getDefaultAngleCount() {
        return 1;
    }

    @Override
    public void configureCommandBuilder(AcquisitionCommandBuilder builder) {
        // Monochrome detector -- no Bayer pattern to decode
        builder.enableDebayer(false);
    }

    @Override
    public List<ModalityMenuItem> getMenuContributions() {
        // No laser-scanning-specific calibration workflows yet.
        // Future: PMT gain optimization, laser power calibration, etc.
        return List.of();
    }
}
