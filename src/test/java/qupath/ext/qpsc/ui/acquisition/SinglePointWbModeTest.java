package qupath.ext.qpsc.ui.acquisition;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.modality.WbMode;

/** Z-stack / time-lapse runs send the white balance mode the acquisition dialogs would. */
class SinglePointWbModeTest {

    @Test
    void aJaiCameraWithAWhiteBalancedModalityUsesTheLastChosenMode() {
        String perAngle = WbMode.PER_ANGLE.getProtocolName();
        String simple = WbMode.SIMPLE.getProtocolName();
        assertEquals(
                simple, SinglePointAcquisitionController.wbModeForRun(true, perAngle, WbMode.SIMPLE.getDisplayName()));
        assertEquals(
                perAngle,
                SinglePointAcquisitionController.wbModeForRun(true, perAngle, null),
                "the modality's default");
        assertEquals(perAngle, SinglePointAcquisitionController.wbModeForRun(true, perAngle, "nonsense"));
    }

    @Test
    void otherwiseWhiteBalanceStaysOff() {
        assertEquals(
                "off",
                SinglePointAcquisitionController.wbModeForRun(false, "per_angle", WbMode.PER_ANGLE.getDisplayName()));
        assertEquals(
                "off", SinglePointAcquisitionController.wbModeForRun(true, "off", WbMode.PER_ANGLE.getDisplayName()));
    }
}
