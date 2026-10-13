package qupath.ext.qpsc.controller;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.QpscWindows;
import qupath.ext.qpsc.ui.liveviewer.LiveViewerWindow;
import qupath.ext.qpsc.ui.stagemap.StageMapWindow;
import qupath.ext.qpsc.utilities.BundledScopeInstaller;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.dialogs.Dialogs;

/**
 * <em>Connect to a Demo Microscope...</em>: QPSC driving a simulated microscope, the way
 * Micro-Manager's demo configuration lets you try that program with no hardware.
 *
 * <p>The demo is the real command server started with {@code --demo}, which replaces the
 * Micro-Manager core with an in-memory microscope (a virtual H&amp;E slide on an XYZ stage).
 * Everything above that layer -- the socket protocol, the Live Viewer, the Stage Map,
 * autofocus, tiled acquisition and stitching -- is the production code path, so what you
 * see is what a real scope does.
 *
 * <p>In order, a connect:
 * <ol>
 *   <li>extracts the bundled demo config ({@link BundledScopeInstaller.Bundle#DEMO}) if it is
 *       not already on disk;</li>
 *   <li>makes sure the server host preference points at this computer, since that is where
 *       the demo server runs;</li>
 *   <li>finds a server already listening on the configured port, or starts one with
 *       {@code microscope-command-server --demo} (first the program named by the
 *       <em>Server launcher</em> preference, then whatever is on the PATH) and waits for it
 *       to answer;</li>
 *   <li>switches the active microscope to the demo config through
 *       {@link MicroscopeSwitcher} (which closes QP Scope windows, reloads, reconnects and
 *       rebuilds the menu), or just reconnects if the demo config is already active;</li>
 *   <li>opens the Live Viewer.</li>
 * </ol>
 *
 * <p>A server QPSC started is stopped again when QuPath exits. Switching back to a real
 * microscope is the ordinary <em>Switch Microscope...</em>.
 */
public final class DemoMicroscope {

    private static final Logger logger = LoggerFactory.getLogger(DemoMicroscope.class);

    /** The console script the Python package installs; resolved through the PATH. */
    public static final String SERVER_COMMAND = "microscope-command-server";

    /** The server flag that selects the simulated microscope. */
    public static final String DEMO_FLAG = "--demo";

    /** How long a freshly started server gets to open its port (it imports numpy, scipy, ...). */
    static final long SERVER_START_TIMEOUT_MS = 60_000;

    private static final int RECENT_OUTPUT_LINES = 25;

    private static final AtomicBoolean inProgress = new AtomicBoolean(false);

    /** The server process this QuPath started, if any; destroyed on exit. */
    private static volatile Process launched;

    private DemoMicroscope() {}

    /** What {@link #ensureServer} found or did. */
    record ServerOutcome(boolean ok, boolean alreadyRunning, String launcher, String detail) {}

    /** Menu entry point. Safe to call from any thread. */
    public static void connect() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(DemoMicroscope::connect);
            return;
        }
        if (!inProgress.compareAndSet(false, true)) {
            logger.info("Demo microscope connect already in progress; ignoring");
            return;
        }
        try {
            Path config;
            try {
                config = BundledScopeInstaller.ensureInstalled(BundledScopeInstaller.Bundle.DEMO);
            } catch (IOException e) {
                fail("Could not write the demo microscope config: " + e.getMessage());
                return;
            }

            String host = QPPreferenceDialog.getMicroscopeServerHost();
            int port = QPPreferenceDialog.getMicroscopeServerPort();
            if (!isLocalHost(host)) {
                if (!confirmLocalHost(host)) {
                    inProgress.set(false);
                    return;
                }
                QPPreferenceDialog.setMicroscopeServerHost("localhost");
                host = "localhost";
            }
            final String finalHost = host;

            CompletableFuture.supplyAsync(() -> ensureServer(finalHost, port))
                    .whenComplete((outcome, err) -> Platform.runLater(() -> {
                        if (err != null) {
                            Throwable cause = err.getCause() != null ? err.getCause() : err;
                            logger.error("Demo microscope: server start failed", cause);
                            fail("Could not start the demo microscope server: " + cause.getMessage());
                            return;
                        }
                        if (!outcome.ok()) {
                            inProgress.set(false);
                            explainLaunchFailure(outcome, port);
                            return;
                        }
                        inProgress.set(false);
                        activate(config, outcome, port);
                    }));
        } catch (RuntimeException e) {
            inProgress.set(false);
            throw e;
        }
    }

    /** Switch to the demo config (or reconnect if it is already active) and open the viewer. */
    private static void activate(Path config, ServerOutcome outcome, int port) {
        if (outcome.alreadyRunning()) {
            logger.info("Demo microscope: using the server already listening on port {}", port);
        } else {
            logger.info("Demo microscope: started the server with {} {}", outcome.launcher(), DEMO_FLAG);
        }
        Runnable onConnected = () -> {
            adoptProfileExposure();
            LiveViewerWindow.show();
            Dialogs.showInfoNotification(
                    "Demo microscope",
                    (outcome.alreadyRunning()
                                    ? "Connected to the server already running on port " + port + "."
                                    : "Started the demo server and connected.")
                            + "\nThe Live Viewer shows a simulated slide; open the Stage Map to drive it, "
                            + "or use Bounded Acquisition to acquire and stitch tiles.");
        };

        MicroscopeController controller = MicroscopeController.getInstance();
        if (samePath(MicroscopeSwitcher.activePath(), config.toString())) {
            CompletableFuture.runAsync(() -> {
                        try {
                            controller.userTriggeredConnect();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .whenComplete((v, err) -> Platform.runLater(() -> {
                        if (err == null) {
                            onConnected.run();
                        } else {
                            Throwable cause = err.getCause() != null ? err.getCause() : err;
                            Dialogs.showErrorMessage(
                                    "Demo microscope", "Could not connect to the demo server: " + cause.getMessage());
                        }
                    }));
            return;
        }
        // Ask first only when the switch disturbs something: a live connection or open windows.
        boolean disruptive = controller.getSocketClient().isConnected()
                || LiveViewerWindow.isVisible()
                || StageMapWindow.isVisible()
                || !QpscWindows.showing().isEmpty();
        MicroscopeSwitcher.switchTo(config.toString(), disruptive, true, onConnected);
    }

    // ----- the server ---------------------------------------------------- //

    /**
     * Finds a server on {@code host:port} or starts one with {@code --demo}. Blocking; call off
     * the FX thread.
     */
    static ServerOutcome ensureServer(String host, int port) {
        if (portOpen(host, port, 1000)) {
            return new ServerOutcome(true, true, null, "already listening");
        }
        List<String> failures = new ArrayList<>();
        for (String launcher : candidateLaunchers(PersistentPreferences.getServerLauncher())) {
            List<String> cmd = command(launcher);
            logger.info("Demo microscope: starting server with {}", cmd);
            Process process;
            try {
                process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            } catch (IOException e) {
                failures.add(launcher + ": " + e.getMessage());
                logger.info("Demo microscope: {} did not start ({})", launcher, e.getMessage());
                continue;
            }
            Deque<String> recent = pumpOutput(process);
            long deadline = System.currentTimeMillis() + SERVER_START_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (portOpen(host, port, 500)) {
                    launched = process;
                    installShutdownHook();
                    return new ServerOutcome(true, false, launcher, "started");
                }
                if (!process.isAlive()) {
                    failures.add(
                            launcher + " exited with code " + process.exitValue() + ":\n" + String.join("\n", recent));
                    break;
                }
                sleepQuietly(250);
            }
            if (process.isAlive()) {
                process.destroy();
                failures.add(launcher + ": still no server on port " + port + " after " + SERVER_START_TIMEOUT_MS / 1000
                        + " s");
            }
        }
        return new ServerOutcome(false, false, null, String.join("\n\n", failures));
    }

    /** The programs to try, in order: the preference if set, then the console script on the PATH. */
    static List<String> candidateLaunchers(String preferred) {
        List<String> out = new ArrayList<>();
        if (preferred != null && !preferred.isBlank()) {
            out.add(preferred.trim());
        }
        if (!out.contains(SERVER_COMMAND)) {
            out.add(SERVER_COMMAND);
        }
        return out;
    }

    /** The command line for a launcher: batch files go through cmd.exe, everything else runs directly. */
    static List<String> command(String launcher) {
        String lower = launcher.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".bat") || lower.endsWith(".cmd")) {
            return List.of("cmd.exe", "/c", launcher, DEMO_FLAG);
        }
        return List.of(launcher, DEMO_FLAG);
    }

    /** True when a TCP connection to {@code host:port} succeeds within {@code timeoutMs}. */
    static boolean portOpen(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Whether the server host preference names this computer. */
    static boolean isLocalHost(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("0.0.0.0");
    }

    static boolean samePath(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return Path.of(a)
                    .toAbsolutePath()
                    .normalize()
                    .equals(Path.of(b).toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return a.equals(b);
        }
    }

    /**
     * Makes the demo profile's default exposure the Live Viewer's remembered one, so the first
     * frame is exposed as the slide was designed. The viewer otherwise restores the last value
     * used on whatever scope came before (50 ms by default), which is several times over the
     * simulated camera's reference and shows as a white frame.
     */
    static void adoptProfileExposure() {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        if (mgr == null) {
            return;
        }
        for (String modality : mgr.getAvailableModalities()) {
            for (String objective : mgr.getAvailableObjectives()) {
                for (String detector : mgr.getHardwareDetectors()) {
                    Map<String, Object> exposures = mgr.getModalityExposures(modality, objective, detector);
                    if (exposures != null && exposures.get("default") instanceof Number n) {
                        PersistentPreferences.setLastUnifiedExposureMs(n.doubleValue());
                        logger.info(
                                "Demo microscope: Live Viewer exposure set to the {} profile default, {} ms",
                                modality,
                                n);
                        return;
                    }
                }
            }
        }
        logger.debug("Demo microscope: no default exposure in the imaging profiles; leaving the viewer's");
    }

    /** Whether this QuPath started a demo server that is still running. */
    public static boolean launchedServerAlive() {
        Process p = launched;
        return p != null && p.isAlive();
    }

    private static Deque<String> pumpOutput(Process process) {
        Deque<String> recent = new ArrayDeque<>();
        Thread pump = new Thread(
                () -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            logger.debug("[demo server] {}", line);
                            synchronized (recent) {
                                recent.addLast(line);
                                while (recent.size() > RECENT_OUTPUT_LINES) {
                                    recent.removeFirst();
                                }
                            }
                        }
                    } catch (IOException e) {
                        logger.debug("Demo server output ended: {}", e.getMessage());
                    }
                },
                "qpsc-demo-server-output");
        pump.setDaemon(true);
        pump.start();
        return recent;
    }

    private static void installShutdownHook() {
        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            Process p = launched;
                            if (p != null && p.isAlive()) {
                                p.destroy();
                            }
                        },
                        "qpsc-demo-server-stop"));
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ----- dialogs ------------------------------------------------------- //

    private static void fail(String message) {
        inProgress.set(false);
        logger.warn("Demo microscope: {}", message);
        Dialogs.showErrorMessage("Demo microscope", message);
    }

    private static boolean confirmLocalHost(String host) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Demo microscope");
        alert.setHeaderText("Point the server connection at this computer?");
        Label text = new Label("The server host preference is \"" + host + "\", but the demo server runs here.\n\n"
                + "Set the host to localhost now? You can change it back under Communication Settings "
                + "when you return to a real microscope.");
        text.setWrapText(true);
        text.setMaxWidth(460);
        alert.getDialogPane().setContent(text);
        ButtonType setLocal = new ButtonType("Use localhost");
        alert.getButtonTypes().setAll(setLocal, ButtonType.CANCEL);
        Optional<ButtonType> choice = alert.showAndWait();
        return choice.isPresent() && choice.get() == setLocal;
    }

    private static void explainLaunchFailure(ServerOutcome outcome, int port) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Demo microscope");
        alert.setHeaderText("QPSC could not start the microscope command server");
        Label text = new Label("Nothing is listening on port " + port + " and the server did not start:\n\n"
                + outcome.detail()
                + "\n\nStart it yourself from the Python environment where the QPSC server packages are "
                + "installed (see the Installation Guide, step 3):\n\n    " + SERVER_COMMAND + " " + DEMO_FLAG
                + "\n\nthen choose Connect to a Demo Microscope again. Or point QPSC at the program -- "
                + "the microscope-command-server executable in that environment's Scripts or bin folder, "
                + "or the repository's start_server.bat -- and it will be started for you from now on.");
        text.setWrapText(true);
        text.setMaxWidth(560);
        alert.getDialogPane().setContent(text);
        ButtonType locate = new ButtonType("Locate server program...");
        alert.getButtonTypes().setAll(locate, ButtonType.CLOSE);
        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isPresent() && choice.get() == locate) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Microscope command server program");
            File picked = chooser.showOpenDialog(alert.getOwner());
            if (picked != null && Files.isRegularFile(picked.toPath())) {
                PersistentPreferences.setServerLauncher(picked.getAbsolutePath());
                logger.info("Demo microscope: server launcher set to {}", picked.getAbsolutePath());
                connect();
            }
        }
    }
}
