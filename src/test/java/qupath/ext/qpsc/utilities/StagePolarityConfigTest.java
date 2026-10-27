package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** stage.polarity in the microscope config: the values it accepts. */
class StagePolarityConfigTest {

    @Test
    void parsesTheFourNamesInAnyCase() {
        assertEquals(StagePolarity.NORMAL, StagePolarity.parse("NORMAL"));
        assertEquals(StagePolarity.INVERT_X, StagePolarity.parse("invert_x"));
        assertEquals(StagePolarity.INVERT_Y, StagePolarity.parse(" Invert_Y "));
        assertEquals(StagePolarity.INVERT_XY, StagePolarity.parse("INVERT_XY"));
    }

    @Test
    void anythingElseIsNotAPolarity() {
        assertNull(StagePolarity.parse(null));
        assertNull(StagePolarity.parse(""));
        assertNull(StagePolarity.parse("true"));
        assertNull(StagePolarity.parse("xy"));
    }
}
