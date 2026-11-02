package qupath.ext.qpsc.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.modality.WbMode;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.state.ObjectiveState;

/**
 * A calibration hands its hardware to Background Collection through the same state and
 * preferences the dialog reads its defaults from; a field left null keeps the dialog's own default.
 */
class BackgroundCollectionPreselectionTest {

    /**
     * Two objectives the active config accepts. ObjectiveState clamps to the loaded config's
     * objectives, and which config is loaded depends on the tests that ran before this one.
     */
    @SuppressWarnings("unchecked")
    private static List<String> objectives() throws Exception {
        Method lookup = ObjectiveState.class.getDeclaredMethod("lookupValidObjectivesFromActiveConfig");
        lookup.setAccessible(true);
        Set<String> valid = (Set<String>) lookup.invoke(null);
        if (valid == null || valid.isEmpty()) {
            return List.of("LOCI_OBJECTIVE_OLYMPUS_20X_POL_001", "LOCI_OBJECTIVE_OLYMPUS_10X_001");
        }
        List<String> ids = new ArrayList<>(new TreeSet<>(valid));
        return List.of(ids.get(0), ids.get(ids.size() - 1));
    }

    @Test
    void fullPreselectionReachesTheDialogDefaults() throws Exception {
        String objective = objectives().get(0);
        BackgroundCollectionWorkflow.preselect(new BackgroundCollectionWorkflow.Preselection(
                null, objective, "LOCI_DETECTOR_JAI_001", WbMode.PER_ANGLE));

        assertEquals(objective, ObjectiveState.getInstance().getObjective());
        assertEquals("LOCI_DETECTOR_JAI_001", PersistentPreferences.getLastDetector());
        // The dialog's combo holds display names, not protocol names.
        assertEquals("Per-angle (PPM)", PersistentPreferences.getLastWhiteBalanceMode());
    }

    @Test
    void nullFieldsLeaveTheDefaultsAlone() throws Exception {
        String objective = objectives().get(1);
        PersistentPreferences.setLastDetector("LOCI_DETECTOR_TELEDYNE_001");
        PersistentPreferences.setLastWhiteBalanceMode("Simple (90deg)");
        ObjectiveState.getInstance().setObjective(objective);

        BackgroundCollectionWorkflow.preselect(BackgroundCollectionWorkflow.Preselection.of(null));
        BackgroundCollectionWorkflow.preselect(null);

        assertEquals(objective, ObjectiveState.getInstance().getObjective());
        assertEquals("LOCI_DETECTOR_TELEDYNE_001", PersistentPreferences.getLastDetector());
        assertEquals("Simple (90deg)", PersistentPreferences.getLastWhiteBalanceMode());
    }

    @Test
    void theHandoffLabelIsTheOneTheLcCalibrationShipped() {
        assertEquals("Collect background now...", BackgroundCollectionWorkflow.COLLECT_NOW);
    }
}
