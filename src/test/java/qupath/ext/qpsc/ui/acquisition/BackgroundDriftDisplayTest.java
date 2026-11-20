package qupath.ext.qpsc.ui.acquisition;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.ui.acquisition.AcquisitionDialogBuilder.BackgroundDriftDisplay;

/** The background drift row shows for LC-PolScope and (disabled) fluorescence only. */
class BackgroundDriftDisplayTest {

    private static BackgroundDriftDisplay forModality(String modality) {
        return BackgroundDriftDisplay.forHandler(ModalityRegistry.getHandler(modality));
    }

    @Test
    void lcPolScopeCanSwitchItOn() {
        assertEquals(BackgroundDriftDisplay.ENABLED, forModality("lcpolscope"));
        assertEquals(BackgroundDriftDisplay.ENABLED, forModality("lcps_20x"));
    }

    @Test
    void fluorescenceShowsItAsNotYetAvailable() {
        assertEquals(BackgroundDriftDisplay.NOT_YET_AVAILABLE, forModality("fl"));
        assertEquals(BackgroundDriftDisplay.NOT_YET_AVAILABLE, forModality("fluorescence"));
        assertEquals(BackgroundDriftDisplay.NOT_YET_AVAILABLE, forModality("bf_if"));
    }

    @Test
    void otherModalitiesHideIt() {
        assertEquals(BackgroundDriftDisplay.HIDDEN, forModality("ppm"));
        assertEquals(BackgroundDriftDisplay.HIDDEN, forModality("brightfield"));
        assertEquals(BackgroundDriftDisplay.HIDDEN, forModality("shg"));
    }
}
