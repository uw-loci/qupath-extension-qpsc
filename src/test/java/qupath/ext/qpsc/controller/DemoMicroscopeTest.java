package qupath.ext.qpsc.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The hardware-free parts of {@link DemoMicroscope}: how it decides where the server is,
 * how it would start one, and the wiring that puts the menu item in front of the user.
 */
class DemoMicroscopeTest {

    private static final Path MAIN = Path.of("src/main/java/qupath/ext/qpsc");

    @Test
    void theConsoleScriptIsTriedAfterThePreferredLauncherAndNeverTwice() {
        assertEquals(List.of(DemoMicroscope.SERVER_COMMAND), DemoMicroscope.candidateLaunchers(""));
        assertEquals(List.of(DemoMicroscope.SERVER_COMMAND), DemoMicroscope.candidateLaunchers(null));
        assertEquals(
                List.of("C:\\envs\\qpsc\\Scripts\\microscope-command-server.exe", DemoMicroscope.SERVER_COMMAND),
                DemoMicroscope.candidateLaunchers("  C:\\envs\\qpsc\\Scripts\\microscope-command-server.exe "));
        assertEquals(
                List.of(DemoMicroscope.SERVER_COMMAND),
                DemoMicroscope.candidateLaunchers(DemoMicroscope.SERVER_COMMAND));
    }

    @Test
    void batchFilesGoThroughCmdAndEverythingElseRunsDirectly() {
        assertEquals(
                List.of("cmd.exe", "/c", "F:\\repo\\start_server.bat", "--demo"),
                DemoMicroscope.command("F:\\repo\\start_server.bat"));
        assertEquals(List.of("cmd.exe", "/c", "x.CMD", "--demo"), DemoMicroscope.command("x.CMD"));
        assertEquals(
                List.of("microscope-command-server", "--demo"), DemoMicroscope.command("microscope-command-server"));
        assertEquals(
                List.of("/home/u/.local/bin/microscope-command-server", "--demo"),
                DemoMicroscope.command("/home/u/.local/bin/microscope-command-server"));
    }

    @Test
    void onlyThisComputerCountsAsLocal() {
        assertTrue(DemoMicroscope.isLocalHost("localhost"));
        assertTrue(DemoMicroscope.isLocalHost("127.0.0.1"));
        assertTrue(DemoMicroscope.isLocalHost(" LOCALHOST "));
        assertTrue(DemoMicroscope.isLocalHost(""));
        assertTrue(DemoMicroscope.isLocalHost(null));
        assertFalse(DemoMicroscope.isLocalHost("10.130.4.12"));
        assertFalse(DemoMicroscope.isLocalHost("ows3.lab.wisc.edu"));
    }

    @Test
    void portProbeSeesAListenerAndNotAClosedPort() throws IOException {
        int port;
        try (ServerSocket listener = new ServerSocket(0)) {
            port = listener.getLocalPort();
            assertTrue(DemoMicroscope.portOpen("127.0.0.1", port, 1000), "a listening socket should read as open");
        }
        assertFalse(DemoMicroscope.portOpen("127.0.0.1", port, 500), "the same port should read closed once released");
    }

    @Test
    void samePathNormalisesBeforeComparing() {
        assertTrue(DemoMicroscope.samePath("/a/b/../c/config_Demo.yml", "/a/c/config_Demo.yml"));
        assertFalse(DemoMicroscope.samePath("/a/c/config_Demo.yml", "/a/c/config_Offline.yml"));
        assertFalse(DemoMicroscope.samePath(null, "/a"));
    }

    @Test
    void theMenuOffersTheDemoNextToTheWizard() throws IOException {
        String setup = Files.readString(MAIN.resolve("SetupScope.java"));
        String strings = Files.readString(Path.of("src/main/resources/qupath/ext/qpsc/ui/strings.properties"));
        assertTrue(strings.contains("menu.connectDemoMicroscope = Connect to a Demo Microscope..."));
        int define = setup.indexOf("res.getString(\"menu.connectDemoMicroscope\")");
        int action = setup.indexOf("DemoMicroscope.connect()");
        int placed = setup.indexOf("extensionMenu.getItems().add(demoOption);");
        assertTrue(
                define > 0 && action > define && placed > action,
                "demo item defined, wired, and added to the top level");
        // Never gated on the config: the whole point is that it works before any scope is set up.
        String defn = setup.substring(define, action);
        assertFalse(defn.contains("setDisable("), "the demo item must stay enabled with an invalid or offline config");
    }

    @Test
    void theSwitcherCanConnectAfterSwitchingAndReportsSuccess() throws IOException {
        String s = Files.readString(MAIN.resolve("controller/MicroscopeSwitcher.java"));
        assertTrue(s.contains("switchTo(String newPath, boolean confirm, boolean connectAfter, Runnable onSuccess)"));
        int connect = s.indexOf("boolean connect = wasConnected || connectAfter;");
        int reconnect = s.indexOf("reconnectIf(connect, controller);");
        int callback = s.indexOf("onSuccess.run();");
        assertTrue(connect > 0 && reconnect > connect && callback > reconnect);
        // The callback fires only on the success branch, after the viewers are reopened.
        int success = s.indexOf("if (err == null) {");
        int failure = s.indexOf("} else {", success);
        assertTrue(callback > success && callback < failure, "onSuccess runs inside the success branch");
    }
}
