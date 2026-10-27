package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The per-insert sample-side Z limit ({@code stage.inserts.configurations.<insert>.z_limit_um})
 * and the orientation rule that turns it into a refusal.
 *
 * <p>{@code stage.limits.z_um} is the hardware's travel envelope, about seven times wider than
 * any focus range on PPM; it stopped nothing on 2026-10-26, when an approach scan ran 450 um past
 * the focus plane toward the objective. This limit is the bound on the sample side, per insert
 * because a dish and a slide put the sample at different heights.
 */
class InsertZLimitTest {

    @TempDir
    Path tempDir;

    private MicroscopeConfigManager managerWith(String stageTail) throws IOException {
        Path config = tempDir.resolve("config_Test.yml");
        Files.writeString(config, """
                microscope:
                  name: TestScope
                stage:
                  limits:
                    z_um: {low: -720, high: 1000}
                  inserts:
                    configurations:
                      quad_v:
                        name: Four Slides
                        z_limit_um: -300
                      single_h:
                        name: Single Slide
                """ + stageTail);
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(config.toString());
        mgr.reload(config.toString());
        return mgr;
    }

    @Test
    void theLimitIsPerInsertWithNoFallback() throws IOException {
        MicroscopeConfigManager mgr = managerWith("  focus:\n    retract_sign: positive\n");
        assertEquals(-300.0, mgr.getInsertZLimitUm("quad_v"), 1e-9);
        assertNull(mgr.getInsertZLimitUm("single_h"), "an insert without one has no sample-side limit");
        assertNull(mgr.getInsertZLimitUm(null));
        assertNull(mgr.getInsertZLimitUm(""));
    }

    @Test
    void whenIncreasingZRetractsTheLimitIsAFloor() throws IOException {
        MicroscopeConfigManager mgr = managerWith("  focus:\n    retract_sign: positive\n");
        // PPM: focus near -121, limit -300, the approach's far end -693.
        assertNull(mgr.insertZLimitProblem(-121.0, "quad_v"));
        assertNull(mgr.insertZLimitProblem(-300.0, "quad_v"), "the limit itself is reachable");
        String problem = mgr.insertZLimitProblem(-693.0, "quad_v");
        assertNotNull(problem);
        assertTrue(problem.contains("-693.0") && problem.contains("-300.0") && problem.contains("quad_v"), problem);
        assertNull(mgr.insertZLimitProblem(-693.0, "single_h"), "no limit declared, nothing to refuse");
    }

    @Test
    void whenDecreasingZRetractsTheLimitIsACeiling() throws IOException {
        MicroscopeConfigManager mgr = managerWith("  focus:\n    retract_sign: negative\n");
        assertNull(mgr.insertZLimitProblem(-400.0, "quad_v"));
        assertNotNull(mgr.insertZLimitProblem(-200.0, "quad_v"));
    }

    @Test
    void anUndeclaredRetractDirectionLeavesTheLimitUnenforced() throws IOException {
        // It cannot be oriented, and guessing the side is exactly the kind of composed sign the
        // project has been bitten by. Logged, not enforced.
        MicroscopeConfigManager mgr = managerWith("");
        assertNull(mgr.insertZLimitProblem(-693.0, "quad_v"));
    }

    @Test
    void theRuleAloneIsSymmetric() {
        assertNull(MicroscopeConfigManager.zLimitProblem(-100.0, -300.0, 1.0, "x"));
        assertNotNull(MicroscopeConfigManager.zLimitProblem(-301.0, -300.0, 1.0, "x"));
        assertNull(MicroscopeConfigManager.zLimitProblem(-301.0, -300.0, -1.0, "x"));
        assertNotNull(MicroscopeConfigManager.zLimitProblem(-100.0, -300.0, -1.0, "x"));
    }
}
