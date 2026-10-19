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
    void theMenuOffersTheDemoUnderMicroscopeConfiguration() throws IOException {
        String setup = Files.readString(MAIN.resolve("SetupScope.java"));
        String strings = Files.readString(Path.of("src/main/resources/qupath/ext/qpsc/ui/strings.properties"));
        assertTrue(strings.contains("menu.connectDemoMicroscope = Connect to a Demo Microscope..."));
        int define = setup.indexOf("res.getString(\"menu.connectDemoMicroscope\")");
        int action = setup.indexOf("DemoMicroscope.connect()");
        int configMenu = setup.indexOf("Menu microscopeConfigMenu = new Menu(\"Microscope Configuration\")");
        int placed = setup.indexOf("demoOption,", configMenu);
        int menuEnd = setup.indexOf("parfocalityCalibrationOption);", configMenu);
        assertTrue(
                define > 0 && action > define && configMenu > action && placed > configMenu && placed < menuEnd,
                "demo item defined, wired, and added to Utilities > Microscope Configuration");
        assertFalse(
                setup.contains("extensionMenu.getItems().add(demoOption);"),
                "the demo item is no longer on the top level");
        // Never gated on the config: the whole point is that it works before any scope is set up.
        String defn = setup.substring(define, action);
        assertFalse(defn.contains("setDisable("), "the demo item must stay enabled with an invalid or offline config");
    }

    @Test
    void theLauncherPreferenceIsOnThePaneAndEveryErrorDialogReachesTheLog() throws IOException {
        String prefs = Files.readString(MAIN.resolve("preferences/QPPreferenceDialog.java"));
        assertTrue(
                prefs.contains("PersistentPreferences.serverLauncherProperty()"), "launcher on the preferences pane");
        assertTrue(prefs.contains(".name(\"Server Launcher\")"), "pane item named Server Launcher");

        // Project rule: an error dialog with no ERROR log cannot be found from a bug report.
        // UserErrors logs before it shows; the launch-failure dialog is a custom Alert and logs itself.
        String demo = Files.readString(MAIN.resolve("controller/DemoMicroscope.java"));
        int connectDialog = demo.indexOf("\"Could not connect to the demo server: \"");
        assertTrue(demo.lastIndexOf("UserErrors.show(", connectDialog) > 0, "connect failure goes through UserErrors");
        int failDef = demo.indexOf("private static void fail(String message) {");
        assertTrue(demo.indexOf("UserErrors.show(logger, \"Demo microscope\", message)", failDef) > failDef);
        int launchDef = demo.indexOf("private static void explainLaunchFailure(");
        int launchLog = demo.indexOf("logger.error(", launchDef);
        int launchAlert = demo.indexOf("new Alert(Alert.AlertType.WARNING)", launchDef);
        assertTrue(
                launchLog > launchDef && launchLog < launchAlert, "launch failure logged at ERROR before its dialog");

        String switcher = Files.readString(MAIN.resolve("controller/MicroscopeSwitcher.java"));
        int missing = switcher.indexOf("The config file does not exist:");
        assertTrue(switcher.lastIndexOf("UserErrors.show(", missing) > 0, "missing-file refusal is logged");
        int refuseDef = switcher.indexOf("private static void refuse(String why) {");
        assertTrue(switcher.indexOf("UserErrors.show(logger, \"Switch Microscope\", why)", refuseDef) > refuseDef);
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

    /**
     * A fake server: accepts connections until the listener closes. A connection that sends
     * nothing is the port-open check; one that sends a command is recorded and answered with
     * {@code reply}, or closed unanswered when {@code reply} is null.
     */
    private static Thread fakeServer(ServerSocket listener, String reply, StringBuilder received) {
        Thread t = new Thread(() -> {
            while (!listener.isClosed()) {
                try (var socket = listener.accept()) {
                    byte[] cmd = socket.getInputStream().readNBytes(8);
                    if (cmd.length < 8) {
                        continue;
                    }
                    synchronized (received) {
                        received.append(new String(cmd, java.nio.charset.StandardCharsets.US_ASCII));
                    }
                    if (reply != null) {
                        byte[] out =
                                java.util.Arrays.copyOf(reply.getBytes(java.nio.charset.StandardCharsets.UTF_8), 32);
                        socket.getOutputStream().write(out);
                        socket.getOutputStream().flush();
                        socket.getInputStream().readNBytes(8); // the goodbye
                    }
                } catch (IOException ignored) {
                    // listener closed, or the client went away; the test asserts on the result
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Test
    void theProbeAsksForTheCameraWithoutConfiguring() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            StringBuilder received = new StringBuilder();
            Thread server = fakeServer(listener, "DCam", received);
            assertEquals("DCam", DemoMicroscope.probeCameraName("localhost", listener.getLocalPort(), 3000));
            server.join(3000);
            assertEquals("getcam__", received.toString(), "GETCAM only; CONFIG could take over a session");
        }
    }

    @Test
    void aServerWithARealCameraIsNotAdoptedAsTheDemo() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            fakeServer(listener, "JAICamera", new StringBuilder());
            DemoMicroscope.ServerOutcome outcome = DemoMicroscope.ensureServer("localhost", listener.getLocalPort());
            assertFalse(outcome.ok());
            assertTrue(outcome.alreadyRunning(), "refused, not a launch failure");
            assertTrue(outcome.detail().contains("JAICamera"), outcome.detail());
        }
    }

    @Test
    void somethingThatDoesNotAnswerIsNotAdoptedEither() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            fakeServer(listener, null, new StringBuilder());
            assertNull(DemoMicroscope.probeCameraName("localhost", listener.getLocalPort(), 2000));
        }
    }

    @Test
    void theDemoServerIsAdoptedWhenItIsAlreadyRunning() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            fakeServer(listener, DemoMicroscope.DEMO_CAMERA, new StringBuilder());
            DemoMicroscope.ServerOutcome outcome = DemoMicroscope.ensureServer("localhost", listener.getLocalPort());
            assertTrue(outcome.ok(), outcome.detail());
            assertTrue(outcome.alreadyRunning());
        }
    }

    @Test
    void aDemoServerLeftOnItsMonoCameraIsStillTheDemo() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            fakeServer(listener, "DMonoCam", new StringBuilder());
            DemoMicroscope.ServerOutcome outcome = DemoMicroscope.ensureServer("localhost", listener.getLocalPort());
            assertTrue(outcome.ok(), outcome.detail());
        }
    }

    @Test
    void stoppingALaunchedServerStopsWhatItStarted() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "needs /bin/sh");
        // The start_server.bat shape: a shell that starts the real server and waits on it.
        Process shell = new ProcessBuilder("/bin/sh", "-c", "sleep 120 & wait").start();
        ProcessHandle child = null;
        for (int i = 0; i < 50 && child == null; i++) {
            child = shell.toHandle().children().findFirst().orElse(null);
            if (child == null) {
                Thread.sleep(50);
            }
        }
        assertNotNull(child, "the shell started its child");
        DemoMicroscope.stopTree(shell);
        child.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(child.isAlive(), "the child must not outlive the launcher");
        shell.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
}
