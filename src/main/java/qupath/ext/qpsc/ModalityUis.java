package qupath.ext.qpsc;

import qupath.ext.qpsc.modality.BrightfieldModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.modality.lcpolscope.LCPolScopeModalityHandler;
import qupath.ext.qpsc.modality.lcpolscope.ui.LCPolScopeModalityUi;
import qupath.ext.qpsc.modality.ppm.PPMModalityHandler;
import qupath.ext.qpsc.modality.ppm.ui.PPMModalityUi;
import qupath.ext.qpsc.modality.ui.BrightfieldModalityUi;
import qupath.ext.qpsc.modality.widefield.WidefieldFluorescenceModalityHandler;
import qupath.ext.qpsc.modality.widefield.ui.WidefieldModalityUi;

/**
 * Registers each modality's UI half with {@link ModalityRegistry}. It sits at the top of the
 * package layers because the UI classes use dialogs and workflows, which the modality handlers
 * in the lower layer may not import. {@link SetupScope} calls it first when the extension starts;
 * a harness that builds QPSC dialogs without the extension calls it too.
 */
public final class ModalityUis {

    private ModalityUis() {}

    /** Registers every modality's UI. Safe to call more than once. */
    public static void registerAll() {
        ModalityRegistry.registerUi(PPMModalityHandler.class, new PPMModalityUi());
        ModalityRegistry.registerUi(BrightfieldModalityHandler.class, new BrightfieldModalityUi());
        // BF+IF extends the widefield handler, so it gets the widefield channel picker.
        ModalityRegistry.registerUi(WidefieldFluorescenceModalityHandler.class, new WidefieldModalityUi());
        ModalityRegistry.registerUi(LCPolScopeModalityHandler.class, new LCPolScopeModalityUi());
    }
}
