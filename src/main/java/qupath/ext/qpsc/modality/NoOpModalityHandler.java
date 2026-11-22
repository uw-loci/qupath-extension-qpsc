package qupath.ext.qpsc.modality;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import qupath.ext.qpsc.model.AngleExposure;

/**
 * Handler for modalities that require no special processing.
 */
public class NoOpModalityHandler implements ModalityHandler {
    @Override
    public CompletableFuture<List<AngleExposure>> getRotationAngles(
            String modalityName, String objective, String detector, String wbMode) {
        return CompletableFuture.completedFuture(List.of());
    }
}
