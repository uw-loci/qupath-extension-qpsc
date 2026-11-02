package qupath.ext.qpsc.modality.ui;

import java.util.Optional;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityUi;

/** Brightfield's acquisition panel; the acquisition logic is {@code BrightfieldModalityHandler}. */
public class BrightfieldModalityUi implements ModalityUi {

    /**
     * Brightfield contributes an exposure panel to the Bounded Acquisition
     * dialog: it states how the single exposure is resolved and lets the
     * operator override it (with a warning that overriding invalidates the
     * saved background/flat-field profile). See
     * {@link BrightfieldExposureBoundingBoxUI}.
     */
    @Override
    public Optional<ModalityHandler.BoundingBoxUI> createBoundingBoxUI(String modalityName) {
        return Optional.of(new BrightfieldExposureBoundingBoxUI());
    }
}
