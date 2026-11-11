package qupath.ext.qpsc.ui.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The center button glows red while the stage is not at the typed center. */
class StagePositionHaloTest {

    @Test
    void theTypedCenterMatchesWithinAMicrometer() {
        assertThat(StagePositionHalo.differs("1000.00", "-250.50", 1000.4, -250.9))
                .isFalse();
        assertThat(StagePositionHalo.differs(" 1000 ", "-250.5", 1000.0, -250.5))
                .isFalse();
    }

    @Test
    void aStageElsewhereLightsTheHalo() {
        assertThat(StagePositionHalo.differs("1000", "-250", 1002.0, -250.0)).isTrue();
        assertThat(StagePositionHalo.differs("1000", "-250", 1000.0, -248.5)).isTrue();
    }

    @Test
    void anEmptyOrUnreadableCenterLightsTheHalo() {
        assertThat(StagePositionHalo.differs("", "", 0.0, 0.0)).isTrue();
        assertThat(StagePositionHalo.differs("abc", "1", 0.0, 1.0)).isTrue();
    }

    @Test
    void anUnknownStagePositionLeavesItOff() {
        assertThat(StagePositionHalo.differs("1000", "-250", Double.NaN, Double.NaN))
                .isFalse();
    }
}
