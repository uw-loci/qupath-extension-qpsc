package qupath.ext.qpsc.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.modality.WbMode;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.state.ObjectiveState;

/**
 * A calibration hands its hardware to Background Collection through the same state and
 * preferences the dialog reads its defaults from; a field left null keeps the dialog's own default.
 */
class BackgroundCollectionPreselectionTest {

    @Test
    void fullPreselectionReachesTheDialogDefaults() {
        BackgroundCollectionWorkflow.preselect(new BackgroundCollectionWorkflow.Preselection(
                null, "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001", "LOCI_DETECTOR_JAI_001", WbMode.PER_ANGLE));

        assertEquals(
                "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001",
                ObjectiveState.getInstance().getObjective());
        assertEquals("LOCI_DETECTOR_JAI_001", PersistentPreferences.getLastDetector());
        // The dialog's combo holds display names, not protocol names.
        assertEquals("Per-angle (PPM)", PersistentPreferences.getLastWhiteBalanceMode());
    }

    @Test
    void nullFieldsLeaveTheDefaultsAlone() {
        PersistentPreferences.setLastDetector("LOCI_DETECTOR_TELEDYNE_001");
        PersistentPreferences.setLastWhiteBalanceMode("Simple (90deg)");
        ObjectiveState.getInstance().setObjective("LOCI_OBJECTIVE_OLYMPUS_10X_001");

        BackgroundCollectionWorkflow.preselect(BackgroundCollectionWorkflow.Preselection.of(null));
        BackgroundCollectionWorkflow.preselect(null);

        assertEquals(
                "LOCI_OBJECTIVE_OLYMPUS_10X_001", ObjectiveState.getInstance().getObjective());
        assertEquals("LOCI_DETECTOR_TELEDYNE_001", PersistentPreferences.getLastDetector());
        assertEquals("Simple (90deg)", PersistentPreferences.getLastWhiteBalanceMode());
    }

    @Test
    void theHandoffLabelIsTheOneTheLcCalibrationShipped() {
        assertEquals("Collect background now...", BackgroundCollectionWorkflow.COLLECT_NOW);
    }
}
