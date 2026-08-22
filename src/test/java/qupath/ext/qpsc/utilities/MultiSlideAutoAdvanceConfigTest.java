package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading {@code acquisition > multislide > auto_advance_seconds} out of a scope YAML.
 *
 * <p>The key is optional -- most scopes never run an unattended batch and none of the
 * shipped configs declare it today -- so the absent case is the one that actually runs in
 * production and is tested first. The clamps matter because this number gates how long a
 * dialog sits before confirming itself: a negative would fire instantly (removing the
 * operator's chance to intervene) and an accidental extra digit would stall a batch for
 * hours while looking like a hang.
 */
class MultiSlideAutoAdvanceConfigTest {

    @TempDir
    Path tempDir;

    private MicroscopeConfigManager managerFor(String acquisitionBlock) throws IOException {
        Path config = tempDir.resolve("config_Test.yml");
        Files.writeString(config, """
                microscope:
                  name: TestScope
                  type: test
                """ + acquisitionBlock);
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(config.toString());
        mgr.reload(config.toString());
        return mgr;
    }

    @Test
    void absentKeyFallsBackToTheDefault() throws IOException {
        assertEquals(
                MicroscopeConfigManager.DEFAULT_AUTO_ADVANCE_SECONDS,
                managerFor("").getMultiSlideAutoAdvanceSeconds(),
                "a scope with no acquisition block must still yield a usable countdown");
    }

    @Test
    void configuredValueIsUsed() throws IOException {
        MicroscopeConfigManager mgr = managerFor("""
                acquisition:
                  multislide:
                    auto_advance_seconds: 25
                """);
        assertEquals(25, mgr.getMultiSlideAutoAdvanceSeconds());
    }

    @Test
    void zeroIsHonoredNotTreatedAsAbsent() throws IOException {
        MicroscopeConfigManager mgr = managerFor("""
                acquisition:
                  multislide:
                    auto_advance_seconds: 0
                """);
        assertEquals(
                0, mgr.getMultiSlideAutoAdvanceSeconds(), "0 is a deliberate 'confirm at once', not a missing key");
    }

    @Test
    void negativeFallsBackRatherThanFiringInstantly() throws IOException {
        MicroscopeConfigManager mgr = managerFor("""
                acquisition:
                  multislide:
                    auto_advance_seconds: -5
                """);
        assertEquals(MicroscopeConfigManager.DEFAULT_AUTO_ADVANCE_SECONDS, mgr.getMultiSlideAutoAdvanceSeconds());
    }

    @Test
    void absurdValueIsClamped() throws IOException {
        MicroscopeConfigManager mgr = managerFor("""
                acquisition:
                  multislide:
                    auto_advance_seconds: 100000
                """);
        assertEquals(
                MicroscopeConfigManager.MAX_AUTO_ADVANCE_SECONDS,
                mgr.getMultiSlideAutoAdvanceSeconds(),
                "an extra digit must not look like a hung batch");
    }

    @Test
    void nonNumericFallsBackToTheDefault() throws IOException {
        MicroscopeConfigManager mgr = managerFor("""
                acquisition:
                  multislide:
                    auto_advance_seconds: soon
                """);
        assertEquals(MicroscopeConfigManager.DEFAULT_AUTO_ADVANCE_SECONDS, mgr.getMultiSlideAutoAdvanceSeconds());
    }
}
