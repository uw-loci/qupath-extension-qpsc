package qupath.ext.qpsc.ui.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.model.TimeLapseOrder;

/** "Per Z plane" says when the Z-stack settings stop it from running as chosen. */
class TimeLapseOrderNoteTest {

    @Test
    void onlyPerPlaneCanNeedANote() {
        assertThat(AcquisitionDialogBuilder.timeLapseOrderNoteText(true, TimeLapseOrder.REGION, true, "Max Intensity"))
                .isNull();
        assertThat(AcquisitionDialogBuilder.timeLapseOrderNoteText(false, TimeLapseOrder.PLANE, true, "Max Intensity"))
                .isNull();
        assertThat(AcquisitionDialogBuilder.timeLapseOrderNoteText(true, TimeLapseOrder.PLANE, true, "None"))
                .isNull();
    }

    @Test
    void perPlaneWithAProjectionOrWithoutAStackIsExplained() {
        assertThat(AcquisitionDialogBuilder.timeLapseOrderNoteText(true, TimeLapseOrder.PLANE, true, "Max Intensity"))
                .contains("projection to None");
        assertThat(AcquisitionDialogBuilder.timeLapseOrderNoteText(true, TimeLapseOrder.PLANE, false, "None"))
                .contains("as Per position");
    }
}
