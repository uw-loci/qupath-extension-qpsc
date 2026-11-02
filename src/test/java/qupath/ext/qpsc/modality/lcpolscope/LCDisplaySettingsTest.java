package qupath.ext.qpsc.modality.lcpolscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;

/** The acquisition panel's color-map choice and the flag it puts on the command. */
class LCDisplaySettingsTest {

    @Test
    void onlySendableChoicesAreSent() {
        assertEquals("uniform", LCDisplaySettings.effectiveChoice("uniform", false));
        assertEquals("hsv", LCDisplaySettings.effectiveChoice("hsv", false));
        assertEquals("ppm", LCDisplaySettings.effectiveChoice("ppm", true));
        // No PPM calibration in the config: the server would refuse the reconstruction.
        assertNull(LCDisplaySettings.effectiveChoice("ppm", false));
        assertNull(LCDisplaySettings.effectiveChoice("", true));
        assertNull(LCDisplaySettings.effectiveChoice("jet", true));
        assertNull(LCDisplaySettings.effectiveChoice(null, true));
    }

    @Test
    void theCeilingIsSentOnlyWhenItDiffersFromTheConfig() {
        assertNull(LCDisplaySettings.sendableCeiling(0.0, 20.0)); // never set
        assertNull(LCDisplaySettings.sendableCeiling(-5.0, 20.0));
        assertNull(LCDisplaySettings.sendableCeiling(Double.NaN, 20.0));
        assertNull(LCDisplaySettings.sendableCeiling(20.0, 20.0)); // the config's own value
        assertEquals(35.0, LCDisplaySettings.sendableCeiling(35.0, 20.0));
        assertEquals(5.5, LCDisplaySettings.sendableCeiling(5.5, 20.0));
    }

    @Test
    void theBuilderSendsTheCeilingOnlyWhenSet() {
        String with = AcquisitionCommandBuilder.builder()
                .yamlPath("c.yml")
                .projectsFolder("p")
                .sampleLabel("s")
                .scanType("lcpolscope_20x_1")
                .regionName("r")
                .displayCeilingNm(35.0)
                .buildSocketMessage();
        assertTrue(with.contains("--display-ceiling-nm 35.000"), with);
        String without = AcquisitionCommandBuilder.builder()
                .yamlPath("c.yml")
                .projectsFolder("p")
                .sampleLabel("s")
                .scanType("lcpolscope_20x_1")
                .regionName("r")
                .displayCeilingNm(null)
                .displayCeilingNm(0.0)
                .buildSocketMessage();
        assertFalse(without.contains("--display-ceiling-nm"), without);
    }

    @Test
    void theBuilderSendsTheChoiceOnlyWhenSet() {
        String with = AcquisitionCommandBuilder.builder()
                .yamlPath("c.yml")
                .projectsFolder("p")
                .sampleLabel("s")
                .scanType("lcpolscope_20x_1")
                .regionName("r")
                .displayColormap("hsv")
                .buildSocketMessage();
        assertTrue(with.contains("--display-colormap hsv"), with);

        String without = AcquisitionCommandBuilder.builder()
                .yamlPath("c.yml")
                .projectsFolder("p")
                .sampleLabel("s")
                .scanType("lcpolscope_20x_1")
                .regionName("r")
                .displayColormap(null)
                .buildSocketMessage();
        assertFalse(without.contains("--display-colormap"), without);
    }
}
