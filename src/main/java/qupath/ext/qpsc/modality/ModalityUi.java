package qupath.ext.qpsc.modality;

import java.util.List;
import java.util.Optional;

/**
 * The user-interface half of a modality: the panels, confirmations and menu items a modality
 * shows. {@link ModalityHandler} holds the rest, the acquisition logic, and lives in the lower
 * layer with no dependency on any dialog or workflow.
 *
 * <p>Implementations live in the modality's own {@code ui} package and are registered with
 * {@link ModalityRegistry#registerUi} when the extension starts, because they use the upper
 * layers (dialogs, workflows) that the lower layer may not import. A modality with no
 * registered UI gets these defaults: no panel, no Live Viewer controls, no confirmation, no menu.
 */
public interface ModalityUi {

    /** The defaults, for a modality that has no UI of its own. */
    ModalityUi NONE = new ModalityUi() {};

    /**
     * The modality's panel in the acquisition dialogs, whose selections reach
     * {@link ModalityHandler#applyAngleOverrides} when the run starts. One handler serves every
     * modality name its prefix matches, so a channel-based panel must show the channels of the
     * modality picked, not those of whichever channel library comes first in the YAML. Runs on
     * the FX thread.
     *
     * @param modalityName the modality key selected in the dialog, as in the YAML; may be null
     * @return the panel, or empty when the modality has nothing to choose
     */
    default Optional<ModalityHandler.BoundingBoxUI> createBoundingBoxUI(String modalityName) {
        return Optional.empty();
    }

    /**
     * Builds this modality's controls in the Live Viewer camera tab, into
     * {@link LiveCameraHost#content()}. Runs on the FX thread.
     *
     * @return true when the controls are built; false leaves the panel to build its own
     */
    default boolean buildLiveCameraControls(String modality, LiveCameraHost host) {
        return false;
    }

    /**
     * Last check before a single acquisition, for anything the operator must confirm in person.
     *
     * @param modality the modality name
     * @return false to stop the acquisition
     */
    default boolean confirmBeforeAcquiring(String modality) {
        return true;
    }

    /**
     * One check for a batch, asked while the operator is present.
     *
     * @param slides how many slides of this modality the batch holds
     * @return false to stop the batch
     */
    default boolean confirmBatch(int slides) {
        return true;
    }

    /**
     * Modality-specific items for the QPSC menu, added to a modality submenu during extension
     * setup. Each item names an id, label, tooltip and action.
     *
     * @return the items; empty when the modality adds none
     */
    default List<ModalityMenuItem> getMenuContributions() {
        return List.of();
    }
}
