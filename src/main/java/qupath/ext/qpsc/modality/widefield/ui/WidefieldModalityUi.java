package qupath.ext.qpsc.modality.widefield.ui;

import java.util.Optional;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityUi;

/**
 * The widefield channel picker; the acquisition logic is {@code WidefieldFluorescenceModalityHandler}.
 * BF+IF uses it too, through the handler class it extends.
 */
public class WidefieldModalityUi implements ModalityUi {

    @Override
    public Optional<ModalityHandler.BoundingBoxUI> createBoundingBoxUI(String modalityName) {
        WidefieldChannelBoundingBoxUI ui = new WidefieldChannelBoundingBoxUI(modalityName);
        // Only return the UI if a channel library is actually configured;
        // otherwise fall through to the angle-based default (null UI, single snap).
        return ui.hasChannels() ? Optional.of(ui) : Optional.empty();
    }
}
