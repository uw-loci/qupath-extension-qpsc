package qupath.ext.qpsc.ui.components;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.model.AcquisitionPlan;
import qupath.ext.qpsc.model.TimeLapseOrder;

/** The Bounded progress window names the tile, the Z plane and the timepoint together. */
class ProgressWhereTextTest {

    @Test
    void aZStackTimeLapseShowsTileZAndTimepoint() {
        // 1 tile, 3 angles, 5 planes, 30 timepoints, region order: 15 files per timepoint.
        AcquisitionPlan plan =
                AcquisitionPlan.compute(1, 0, 5, 3, 30, "angle", true, List.of(), List.of(), TimeLapseOrder.REGION);
        assertThat(WorkflowDialogs.whereText(plan, 17, 450, 1, 15, 1, 30))
                .isEqualTo("Tile 1 of 1, Z 1 of 5, Timepoint 2 of 30");
    }

    @Test
    void perPlaneTheTimepointMovesBeforeZ() {
        AcquisitionPlan plan =
                AcquisitionPlan.compute(1, 0, 5, 3, 30, "angle", true, List.of(), List.of(), TimeLapseOrder.PLANE);
        // 3 files per plane timepoint: file 6 is timepoint 3 of plane 1.
        assertThat(WorkflowDialogs.whereText(plan, 6, 450, 1, 15, 2, 30))
                .isEqualTo("Tile 1 of 1, Z 1 of 5, Timepoint 3 of 30");
    }

    @Test
    void withoutAPlanItCountsPositions() {
        assertThat(WorkflowDialogs.whereText(null, 20, 40, 10, 1, 0, 1)).isEqualTo("Positions acquired: 10 of 10");
        assertThat(WorkflowDialogs.whereText(null, 45, 60, 20, 1, 2, 3))
                .isEqualTo("Positions acquired: 5 of 20 (timepoint 3 of 3)");
    }
}
