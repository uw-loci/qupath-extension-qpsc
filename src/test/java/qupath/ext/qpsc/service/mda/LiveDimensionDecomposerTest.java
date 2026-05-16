package qupath.ext.qpsc.service.mda;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.model.AcquisitionPlan;
import qupath.ext.qpsc.service.mda.LiveDimensionDecomposer.Decomposition;

class LiveDimensionDecomposerTest {

    @Test
    void widefieldZInner_decodesPositionChannelZ() {
        // Order per position with z-inner: for ch (outer) -> for z (inner)
        // 4 channels * 9 z * 12 positions = 432
        AcquisitionPlan plan =
                AcquisitionPlan.compute(12, 4, 9, 0, 1, "z", false, List.of("DAPI", "FITC", "TRITC", "Cy5"), List.of());

        assertThat(plan.totalImages()).isEqualTo(432L);

        Decomposition first = LiveDimensionDecomposer.decompose(0, plan);
        assertThat(first.drifted()).isFalse();
        assertThat(first.tIdx()).isZero();
        assertThat(first.posIdx()).isZero();
        assertThat(first.chIdx()).isZero();
        assertThat(first.zIdx()).isZero();

        // k=8 is still channel 0, z 8 (last z slice of channel 0)
        Decomposition kEightInChannelZero = LiveDimensionDecomposer.decompose(8, plan);
        assertThat(kEightInChannelZero.chIdx()).isZero();
        assertThat(kEightInChannelZero.zIdx()).isEqualTo(8);

        // k=9 advances to next channel (z resets to 0)
        Decomposition kNine = LiveDimensionDecomposer.decompose(9, plan);
        assertThat(kNine.posIdx()).isZero();
        assertThat(kNine.chIdx()).isEqualTo(1);
        assertThat(kNine.zIdx()).isZero();

        // k=36 advances to next position: imagesPerPosition = 4*9 = 36
        Decomposition kThirtySix = LiveDimensionDecomposer.decompose(36, plan);
        assertThat(kThirtySix.posIdx()).isEqualTo(1);
        assertThat(kThirtySix.chIdx()).isZero();
        assertThat(kThirtySix.zIdx()).isZero();

        // Last image: k=431 -> pos 11, ch 3, z 8
        Decomposition last = LiveDimensionDecomposer.decompose(431, plan);
        assertThat(last.drifted()).isFalse();
        assertThat(last.posIdx()).isEqualTo(11);
        assertThat(last.chIdx()).isEqualTo(3);
        assertThat(last.zIdx()).isEqualTo(8);
    }

    @Test
    void widefieldChannelInner_decodesPositionZChannel() {
        // Order per position with channel-inner: for z (outer) -> for ch (inner)
        // 4 channels * 9 z * 12 positions
        AcquisitionPlan plan = AcquisitionPlan.compute(
                12, 4, 9, 0, 1, "channel", false, List.of("DAPI", "FITC", "TRITC", "Cy5"), List.of());

        Decomposition first = LiveDimensionDecomposer.decompose(0, plan);
        assertThat(first.zIdx()).isZero();
        assertThat(first.chIdx()).isZero();

        // k=3 is still z 0, ch 3 (last channel of z slice 0)
        Decomposition kThree = LiveDimensionDecomposer.decompose(3, plan);
        assertThat(kThree.zIdx()).isZero();
        assertThat(kThree.chIdx()).isEqualTo(3);

        // k=4 advances z, ch resets to 0
        Decomposition kFour = LiveDimensionDecomposer.decompose(4, plan);
        assertThat(kFour.posIdx()).isZero();
        assertThat(kFour.zIdx()).isEqualTo(1);
        assertThat(kFour.chIdx()).isZero();

        // k=36 advances position
        Decomposition kThirtySix = LiveDimensionDecomposer.decompose(36, plan);
        assertThat(kThirtySix.posIdx()).isEqualTo(1);
        assertThat(kThirtySix.chIdx()).isZero();
        assertThat(kThirtySix.zIdx()).isZero();
    }

    @Test
    void ppmAngleInner_decodesPositionZAngle() {
        // Default PPM order with angle-inner: for z (outer) -> for angle (inner)
        // 5 angles * 1 z * 12 positions = 60
        AcquisitionPlan plan =
                AcquisitionPlan.compute(12, 0, 1, 5, 1, "angle", true, List.of(), List.of(-7.0, 0.0, 7.0, 14.0, 21.0));

        assertThat(plan.totalImages()).isEqualTo(60L);

        Decomposition first = LiveDimensionDecomposer.decompose(0, plan);
        assertThat(first.angleIdx()).isZero();
        assertThat(first.zIdx()).isZero();
        assertThat(first.posIdx()).isZero();

        // k=4 is last angle in position 0
        Decomposition kFour = LiveDimensionDecomposer.decompose(4, plan);
        assertThat(kFour.posIdx()).isZero();
        assertThat(kFour.angleIdx()).isEqualTo(4);

        // k=5 advances position
        Decomposition kFive = LiveDimensionDecomposer.decompose(5, plan);
        assertThat(kFive.posIdx()).isEqualTo(1);
        assertThat(kFive.angleIdx()).isZero();
    }

    @Test
    void ppmZInner_decodesPositionAngleZ() {
        // PPM with z-inner: for angle (outer) -> for z (inner)
        // 5 angles * 7 z * 12 positions = 420
        AcquisitionPlan plan =
                AcquisitionPlan.compute(12, 0, 7, 5, 1, "z", true, List.of(), List.of(-7.0, 0.0, 7.0, 14.0, 21.0));

        assertThat(plan.totalImages()).isEqualTo(420L);

        // imagesPerPosition = 5*7 = 35
        Decomposition first = LiveDimensionDecomposer.decompose(0, plan);
        assertThat(first.angleIdx()).isZero();
        assertThat(first.zIdx()).isZero();

        // k=6 -> still angle 0, z 6 (last z of angle 0)
        Decomposition kSix = LiveDimensionDecomposer.decompose(6, plan);
        assertThat(kSix.posIdx()).isZero();
        assertThat(kSix.angleIdx()).isZero();
        assertThat(kSix.zIdx()).isEqualTo(6);

        // k=7 -> angle 1, z 0
        Decomposition kSeven = LiveDimensionDecomposer.decompose(7, plan);
        assertThat(kSeven.angleIdx()).isEqualTo(1);
        assertThat(kSeven.zIdx()).isZero();

        // k=35 -> next position
        Decomposition kThirtyFive = LiveDimensionDecomposer.decompose(35, plan);
        assertThat(kThirtyFive.posIdx()).isEqualTo(1);
        assertThat(kThirtyFive.angleIdx()).isZero();
        assertThat(kThirtyFive.zIdx()).isZero();
    }

    @Test
    void timeLapseWidefieldZInner_decodesTimepointTransitions() {
        // T=3 with 2 ch * 3 z * 4 pos. imagesPerTimepoint = 2*3*4 = 24
        AcquisitionPlan plan = AcquisitionPlan.compute(4, 2, 3, 0, 3, "z", false, List.of("DAPI", "FITC"), List.of());

        assertThat(plan.totalImages()).isEqualTo(72L);

        Decomposition first = LiveDimensionDecomposer.decompose(0, plan);
        assertThat(first.tIdx()).isZero();

        // k=23 -> last image of timepoint 0
        Decomposition lastOfT0 = LiveDimensionDecomposer.decompose(23, plan);
        assertThat(lastOfT0.tIdx()).isZero();
        assertThat(lastOfT0.posIdx()).isEqualTo(3);
        assertThat(lastOfT0.chIdx()).isEqualTo(1);
        assertThat(lastOfT0.zIdx()).isEqualTo(2);

        // k=24 -> first image of timepoint 1
        Decomposition firstOfT1 = LiveDimensionDecomposer.decompose(24, plan);
        assertThat(firstOfT1.tIdx()).isEqualTo(1);
        assertThat(firstOfT1.posIdx()).isZero();
        assertThat(firstOfT1.chIdx()).isZero();
        assertThat(firstOfT1.zIdx()).isZero();

        // k=48 -> first image of timepoint 2
        Decomposition firstOfT2 = LiveDimensionDecomposer.decompose(48, plan);
        assertThat(firstOfT2.tIdx()).isEqualTo(2);
        assertThat(firstOfT2.posIdx()).isZero();
    }

    @Test
    void driftSentinel_returnsDriftedWhenIndexOutOfRange() {
        AcquisitionPlan plan =
                AcquisitionPlan.compute(12, 4, 9, 0, 1, "z", false, List.of("DAPI", "FITC", "TRITC", "Cy5"), List.of());

        Decomposition past = LiveDimensionDecomposer.decompose(plan.totalImages() + 1, plan);
        assertThat(past.drifted()).isTrue();

        Decomposition negative = LiveDimensionDecomposer.decompose(-1, plan);
        assertThat(negative.drifted()).isTrue();
    }
}
