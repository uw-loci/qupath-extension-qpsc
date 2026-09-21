package qupath.ext.qpsc.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * {@code --batch-acquire} tells the server that no operator is waiting at a start
 * position, so it should park on the region it just finished instead of on the
 * "starting position" it captured -- which, in a batch, is inherited from whichever
 * slide ran before.
 *
 * <p>The flag must be absent by default. A single-slide run that emitted it would stop
 * returning the stage to where the operator left it, which is behaviour they rely on
 * and would notice immediately.
 */
class BatchAcquireFlagTest {

    private static AcquisitionCommandBuilder base() {
        return AcquisitionCommandBuilder.builder()
                .yamlPath("config.yml")
                .projectsFolder("/projects")
                .sampleLabel("sample")
                .scanType("ppm_20x")
                .regionName("bounds");
    }

    @Test
    void absentByDefault_soSingleSlideBehaviourIsUnchanged() {
        assertFalse(base().buildSocketMessage().contains("--batch-acquire"));
    }

    @Test
    void absentWhenExplicitlyFalse() {
        assertFalse(base().batchAcquire(false).buildSocketMessage().contains("--batch-acquire"));
    }

    @Test
    void emittedWhenSet() {
        assertTrue(base().batchAcquire(true).buildSocketMessage().contains("--batch-acquire"));
    }

    @Test
    void isABareFlag_takingNoValue() {
        // The Python parser advances i by 1 for this flag. If a value were ever appended
        // here, that parser would consume the NEXT flag's name as this one's argument and
        // silently drop it.
        String msg = base().batchAcquire(true).scanType("ppm_20x").buildSocketMessage();
        String[] args = msg.split("\\s+");
        int i = java.util.List.of(args).indexOf("--batch-acquire");
        assertTrue(i >= 0, msg);
        if (i + 1 < args.length) {
            assertTrue(
                    args[i + 1].startsWith("--"),
                    "--batch-acquire must be followed by another flag, not a value: " + msg);
        }
    }
}
