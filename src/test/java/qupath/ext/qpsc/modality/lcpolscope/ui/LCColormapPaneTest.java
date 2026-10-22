package qupath.ext.qpsc.modality.lcpolscope.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;

/** The acquisition panel's colour-map choice and the flag it puts on the command. */
class LCColormapPaneTest {

    @Test
    void onlySendableChoicesAreSent() {
        assertEquals("uniform", LCColormapPane.effectiveChoice("uniform", false));
        assertEquals("hsv", LCColormapPane.effectiveChoice("hsv", false));
        assertEquals("ppm", LCColormapPane.effectiveChoice("ppm", true));
        // No PPM calibration in the config: the server would refuse the reconstruction.
        assertNull(LCColormapPane.effectiveChoice("ppm", false));
        assertNull(LCColormapPane.effectiveChoice("", true));
        assertNull(LCColormapPane.effectiveChoice("jet", true));
        assertNull(LCColormapPane.effectiveChoice(null, true));
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
