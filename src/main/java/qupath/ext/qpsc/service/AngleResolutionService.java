package qupath.ext.qpsc.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;

/**
 * Shared service for resolving rotation angles for a given modality/hardware combination.
 *
 * <p>This consolidates the identical angle-resolution pattern that was duplicated in
 * {@code BoundedAcquisitionWorkflow} and {@code AcquisitionManager}:
 * <ol>
 *   <li>Look up the {@link ModalityHandler} for the modality prefix</li>
 *   <li>Call {@link ModalityHandler#prepareForAcquisition} to load profile defaults</li>
 *   <li>Resolve angles via {@link ModalityHandler#getRotationAnglesWithOverrides} (or
 *       plain {@link ModalityHandler#getRotationAngles} when no overrides are present)</li>
 * </ol>
 */
public final class AngleResolutionService {

    private static final Logger logger = LoggerFactory.getLogger(AngleResolutionService.class);

    private AngleResolutionService() {}

    /**
     * Resolves the rotation angle/exposure list for an acquisition.
     *
     * @param modality       modality identifier (e.g. "ppm_20x")
     * @param objective      objective ID for hardware-specific lookup
     * @param detector       detector ID for hardware-specific lookup
     * @param angleOverrides user-provided angle overrides (may be null or empty)
     * @return future containing the resolved angle-exposure list
     */
    public static CompletableFuture<List<AngleExposure>> resolve(
            String modality, String objective, String detector, Map<String, Double> angleOverrides) {

        ModalityHandler handler = ModalityRegistry.getHandler(modality);

        logger.info("Resolving rotation angles for modality={} obj={} det={}", modality, objective, detector);

        // Load modality-specific profile defaults (e.g. PPM exposure profiles)
        handler.prepareForAcquisition(modality, objective, detector);

        // Resolve angles -- handler decides whether to show dialog, apply overrides, etc.
        if (angleOverrides != null && !angleOverrides.isEmpty()) {
            logger.info("Applying angle overrides from user dialog: {}", angleOverrides);
            return handler.getRotationAnglesWithOverrides(modality, objective, detector, angleOverrides);
        } else {
            return handler.getRotationAngles(modality, objective, detector);
        }
    }
}
