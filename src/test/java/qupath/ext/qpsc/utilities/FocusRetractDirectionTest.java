package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code stage.focus.retract_sign} and the wrong-side check it enables.
 *
 * <p>The value being modelled is the objective-sample SEPARATION, not which element moves. PPM
 * drives the stage; the Nikon Eclipse on LC-PolScope drives the objective; in an
 * infinity-corrected system those are optically equivalent, and only the gap is real. So one
 * declared sign covers both, and no test here knows or cares about the mechanism.
 *
 * <p>The check exists because {@code stage.limits.z_um} cannot make it. PPM's limits are
 * [-720, 1000] and a wrong-side safe Z of -500 sat comfortably inside them while pointing at
 * the objective -- which is exactly the value that shipped before this was caught.
 */
class FocusRetractDirectionTest {

    @TempDir
    Path tempDir;

    private MicroscopeConfigManager managerWith(String focusBlock) throws IOException {
        Path config = tempDir.resolve("config_Test.yml");
        Files.writeString(config, """
                microscope:
                  name: TestScope
                stage:
                  limits:
                    z_um: {low: -720, high: 1000}
                """ + focusBlock);
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(config.toString());
        mgr.reload(config.toString());
        return mgr;
    }

    @Test
    void positiveAndNegativeAreBothReadable() throws IOException {
        assertEquals(1.0, managerWith("  focus:\n    retract_sign: positive\n").getFocusRetractSign(), 1e-9);
        assertEquals(-1.0, managerWith("  focus:\n    retract_sign: negative\n").getFocusRetractSign(), 1e-9);
    }

    @Test
    void anUndeclaredDirectionIsNullRatherThanADefault() throws IOException {
        // No default is defensible: guessing this wrong is a collision, and a scope that has
        // not been measured should disable the approach rather than run on an assumption.
        assertNull(managerWith("").getFocusRetractSign());
        assertNull(managerWith("  focus:\n    retract_sign: sideways\n").getFocusRetractSign());
    }

    @Test
    void theWrongSideValueThatShippedIsCaught() throws IOException {
        // The real case: PPM retracts in +Z, samples focus near -250, and -500 was configured.
        MicroscopeConfigManager mgr = managerWith("  focus:\n    retract_sign: positive\n");

        String why = mgr.validateSafeZDirection(-500.0, -250.0);

        assertNotNull(why, "-500 is on the objective side of a -250 sample; this must be refused");
        assertTrue(why.contains("WRONG SIDE"), why);
        assertTrue(why.contains("increasing"), "should say which direction this scope retracts: " + why);
    }

    @Test
    void theCorrectedValuePasses() throws IOException {
        MicroscopeConfigManager mgr = managerWith("  focus:\n    retract_sign: positive\n");
        assertNull(mgr.validateSafeZDirection(0.0, -250.0), "0 is retracted from a -250 sample on this scope");
    }

    @Test
    void aScopeThatRetractsDownwardGetsTheOppositeAnswer() throws IOException {
        // Same arithmetic, opposite declaration -- nothing here encodes a convention.
        MicroscopeConfigManager mgr = managerWith("  focus:\n    retract_sign: negative\n");
        assertNull(mgr.validateSafeZDirection(-500.0, -250.0));
        assertNotNull(mgr.validateSafeZDirection(0.0, -250.0));
    }

    @Test
    void aSafeZEqualToFocusIsNotARetraction() throws IOException {
        MicroscopeConfigManager mgr = managerWith("  focus:\n    retract_sign: positive\n");
        String why = mgr.validateSafeZDirection(-250.0, -250.0);
        assertNotNull(why);
        assertTrue(why.contains("not a retraction"), why);
    }

    @Test
    void anUndeclaredDirectionCannotRefuse() throws IOException {
        // Without a declaration there is nothing to check against, so the human confirmation
        // remains the only guard. It must not silently pass or silently block.
        MicroscopeConfigManager mgr = managerWith("");
        assertNull(mgr.validateSafeZDirection(-500.0, -250.0));
        assertNull(mgr.validateSafeZDirection(0.0, -250.0));
    }

    @Test
    void aWrongSideValueCanSitInsideTheTravelLimits() throws IOException {
        // The point of the whole check: -500 is well within [-720, 1000], so the limit gate
        // passes it. Only the direction distinguishes retracted from plunging.
        MicroscopeConfigManager mgr = managerWith("  focus:\n    retract_sign: positive\n");
        assertNull(mgr.validateSafeZUm(null, null), "the limit check sees nothing wrong");
        assertNotNull(mgr.validateSafeZDirection(-500.0, -250.0), "the direction check does");
    }
}
