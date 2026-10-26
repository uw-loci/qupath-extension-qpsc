package qupath.ext.qpsc.modality.lcpolscope.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** The "Measure on N% of the field" choice becomes a fraction only when it narrows the field. */
class LCCalibrationFieldTest {

    @Test
    void wholeFieldSendsNothing() {
        assertNull(LCCalibrationWorkflow.fieldFraction(100));
        assertNull(LCCalibrationWorkflow.fieldFraction(150));
        assertNull(LCCalibrationWorkflow.fieldFraction(0));
    }

    @Test
    void aNarrowerFieldIsAFraction() {
        assertEquals(0.5, LCCalibrationWorkflow.fieldFraction(50));
        assertEquals(0.1, LCCalibrationWorkflow.fieldFraction(10));
    }
}
