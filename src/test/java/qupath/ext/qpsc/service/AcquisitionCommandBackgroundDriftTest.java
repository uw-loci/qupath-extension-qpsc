package qupath.ext.qpsc.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The acquisition dialog's background drift choice reaches the server as --bg-drift. */
class AcquisitionCommandBackgroundDriftTest {

    private static AcquisitionCommandBuilder builder() {
        return AcquisitionCommandBuilder.builder()
                .yamlPath("config.yml")
                .projectsFolder("projects")
                .sampleLabel("sample")
                .scanType("LCPolScope_20x_1")
                .regionName("bounds");
    }

    @Test
    void onSendsTheInterval() {
        String message = builder().backgroundDrift(true, 10).buildSocketMessage();
        assertTrue(message.contains("--bg-drift on --bg-drift-interval-min 10.00"), message);
    }

    @Test
    void offSendsOffAndNoInterval() {
        String message = builder().backgroundDrift(false, 10).buildSocketMessage();
        assertTrue(message.contains("--bg-drift off"), message);
        assertFalse(message.contains("--bg-drift-interval-min"), message);
    }

    @Test
    void notSetLeavesTheYamlInCharge() {
        assertFalse(builder().buildSocketMessage().contains("--bg-drift"));
    }
}
