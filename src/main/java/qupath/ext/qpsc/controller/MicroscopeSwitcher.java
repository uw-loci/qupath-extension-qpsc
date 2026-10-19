package qupath.ext.qpsc.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.SetupScope;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.QpscWindows;
import qupath.ext.qpsc.ui.UserErrors;
import qupath.ext.qpsc.ui.liveviewer.LiveViewerWindow;
import qupath.ext.qpsc.ui.stagemap.StageInsertRegistry;
import qupath.ext.qpsc.ui.stagemap.StageMapWindow;
import qupath.ext.qpsc.utilities.HolderTiltRegistry;
import qupath.ext.qpsc.utilities.LightPathModel;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.StagePositionManager;
import qupath.fx.dialogs.Dialogs;

/**
 * Switches QuPath SCope to a different microscope YAML without restarting QuPath.
 *
 * <p>Everything built at install time from the config is rebuilt here, in this order:
 * <ol>
 *   <li>refuse if an acquisition is running -- locally or by the server's own account;</li>
 *   <li>confirm with the operator, naming what will happen;</li>
 *   <li>close every QP Scope window ({@link QpscWindows}), remembering whether the Live Viewer
 *       and Stage Map were open;</li>
 *   <li>disconnect from the server, reload the config manager, validate; on an invalid file roll
 *       the config manager and the preference back and stop;</li>
 *   <li>drop the registries derived from the config (stage inserts, holder tilts);</li>
 *   <li>reconnect, which re-sends CONFIG with the new path on both sockets;</li>
 *   <li>rebuild the QP Scope menu, reopen the two viewers if they were open.</li>
 * </ol>
 *
 * <p>This is a deliberate action, not a property listener: the preferences text field fires on
 * every keystroke, and the switch drops a live connection and closes windows, so it must fire
 * once, on purpose. The preference change debounces into {@link #offerSwitch(String)}, which
 * asks; the menu item calls {@link #switchTo(String, boolean)} directly.
 *
 * @since 0.9.3
 */
public final class MicroscopeSwitcher {

    private static final Logger logger = LoggerFactory.getLogger(MicroscopeSwitcher.class);

    private static final AtomicBoolean inProgress = new AtomicBoolean(false);

    private MicroscopeSwitcher() {}

    /** The YAML the extension is currently built against, or null before install. */
    public static String activePath() {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        return mgr == null ? null : mgr.getConfigPath();
    }

    /**
     * Whether a changed preference is worth offering a switch for: a real file, different from
     * the one in use. Pure, so it can be tested without JavaFX.
     */
    static boolean shouldOffer(String active, String candidate, boolean candidateExists) {
        if (candidate == null || candidate.isBlank() || !candidateExists) {
            return false;
        }
        return !Objects.equals(normalise(active), normalise(candidate));
    }

    private static String normalise(String path) {
        if (path == null) {
            return null;
        }
        try {
            return Path.of(path).toAbsolutePath().normalize().toString();
        } catch (RuntimeException e) {
            return path;
        }
    }

    /**
     * Called (debounced) when the config-file preference changes. Offers the switch if the new
     * value names a different, existing file; otherwise does nothing.
     */
    public static void offerSwitch(String candidate) {
        boolean exists = candidate != null && !candidate.isBlank() && Files.isRegularFile(Path.of(candidate));
        if (!shouldOffer(activePath(), candidate, exists)) {
            return;
        }
        switchTo(candidate, true);
    }

    /**
     * Performs the switch to {@code newPath}.
     *
     * @param newPath the YAML to switch to (normally the current preference value)
     * @param confirm whether to ask the operator first; false only for tests and scripts
     */
    public static void switchTo(String newPath, boolean confirm) {
        switchTo(newPath, confirm, false, null);
    }

    /**
     * Performs the switch to {@code newPath}, optionally connecting afterwards even if no
     * server was connected before (the demo microscope uses this) and running a callback on
     * the FX thread once the switch has fully succeeded.
     *
     * @param newPath the YAML to switch to
     * @param confirm whether to ask the operator first
     * @param connectAfter connect to the server after the switch regardless of prior state
     * @param onSuccess run on the FX thread after the menu is rebuilt and viewers reopened;
     *                  not run on refusal, decline or failure. May be null.
     */
    public static void switchTo(String newPath, boolean confirm, boolean connectAfter, Runnable onSuccess) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> switchTo(newPath, confirm, connectAfter, onSuccess));
            return;
        }
        if (newPath == null || newPath.isBlank() || !Files.isRegularFile(Path.of(newPath))) {
            UserErrors.show(logger, "Switch Microscope", "The config file does not exist:\n" + newPath);
            return;
        }
        if (!inProgress.compareAndSet(false, true)) {
            logger.info("Microscope switch already in progress; ignoring request for {}", newPath);
            return;
        }
        try {
            String oldPath = activePath();
            MicroscopeController controller = MicroscopeController.getInstance();

            // 1. Never under an acquisition. The server owns the stage until its thread
            // finishes, and a CONFIG mid-acquisition is the takeover the server guards against.
            if (controller.isAcquisitionActive()) {
                refuse("An acquisition is running in this QuPath. Wait for it to finish or cancel it first.");
                return;
            }
            if (controller.getSocketClient().isConnected()) {
                try {
                    if (!controller.awaitServerIdle(0)) {
                        refuse("The microscope server reports an acquisition in progress. "
                                + "Wait for it to finish, or cancel it, before switching.");
                        return;
                    }
                } catch (IOException e) {
                    logger.warn("Could not ask the server whether it is idle: {}", e.getMessage());
                }
            }

            // 2. Confirm, naming the consequences.
            boolean wasLive = LiveViewerWindow.isVisible();
            boolean wasMap = StageMapWindow.isVisible();
            int others = QpscWindows.showing().size();
            if (confirm && !confirmed(oldPath, newPath, wasLive, wasMap, others)) {
                // Declined: leave the preference as the user set it; the menu offers it again.
                logger.info("Microscope switch to {} declined by the operator", newPath);
                return;
            }

            // 3. Windows. Viewers are closed here and reopened at the end; the rest are closed.
            LiveViewerWindow.hide();
            StageMapWindow.hide();
            int closed = QpscWindows.closeAll();
            logger.info(
                    "Switching microscope: {} -> {} (Live Viewer {}, Stage Map {}, {} other window(s) closed)",
                    oldPath,
                    newPath,
                    wasLive ? "open" : "closed",
                    wasMap ? "open" : "closed",
                    closed);

            boolean wasConnected = controller.getSocketClient().isConnected();
            boolean connect = wasConnected || connectAfter;
            CompletableFuture.runAsync(() -> {
                        // 4. Disconnect, reload, validate -- or roll back.
                        if (wasConnected) {
                            controller.disconnect();
                        }
                        QPPreferenceDialog.setMicroscopeConfigFileProperty(newPath);
                        if (!loadAndValidate(newPath)) {
                            logger.error("New config {} failed validation; rolling back to {}", newPath, oldPath);
                            if (oldPath != null && !oldPath.isBlank()) {
                                QPPreferenceDialog.setMicroscopeConfigFileProperty(oldPath);
                                MicroscopeConfigManager.getInstance(oldPath);
                            }
                            reconnectIf(wasConnected, controller);
                            throw new IllegalStateException("The new config did not validate; the previous microscope "
                                    + "configuration has been restored.");
                        }

                        // 5. Registries derived from the config.
                        StageInsertRegistry.reset();
                        HolderTiltRegistry.clear();
                        try {
                            LightPathModel.logCurrent("Orientation stack after microscope switch");
                        } catch (Exception e) {
                            logger.debug("Could not log LightPathModel: {}", e.getMessage());
                        }

                        // 6. Reconnect: connect() re-sends CONFIG with the preference, now the new path.
                        reconnectIf(connect, controller);
                        StagePositionManager.getInstance().forceRefresh();
                    })
                    .whenComplete((v, err) -> Platform.runLater(() -> {
                        try {
                            // Leaving the demo puts back the stage and camera orientation it
                            // replaced (FX thread: they are bound preferences).
                            if (err == null && DemoOrientation.restoreIfLeaving(newPath)) {
                                LightPathModel.logCurrent("Orientation stack restored after the demo");
                            }
                            // 7. Rebuild what the UI shows, against whichever config is now active.
                            SetupScope.reloadForNewConfig();
                            if (err == null) {
                                if (wasLive) {
                                    LiveViewerWindow.show();
                                }
                                if (wasMap) {
                                    StageMapWindow.show();
                                }
                                Dialogs.showInfoNotification(
                                        "Switch Microscope",
                                        "Now using " + Path.of(newPath).getFileName()
                                                + (connect ? " (server connected)" : " (not connected)"));
                                if (onSuccess != null) {
                                    onSuccess.run();
                                }
                            } else {
                                Throwable cause = err.getCause() != null ? err.getCause() : err;
                                logger.error("Microscope switch failed", cause);
                                UserErrors.show(logger, "Switch Microscope", cause.getMessage());
                            }
                        } finally {
                            inProgress.set(false);
                        }
                    }));
        } catch (RuntimeException e) {
            inProgress.set(false);
            throw e;
        }
    }

    /**
     * Loads {@code path} into the config manager and validates it. A YAML that cannot even be
     * parsed is "invalid" here too, rather than an exception escaping with the preference already
     * pointing at it; the config manager swaps its maps only after a successful load, so a parse
     * failure leaves the previous configuration in place.
     */
    private static boolean loadAndValidate(String path) {
        try {
            loadConfig(path);
        } catch (RuntimeException e) {
            logger.error("Could not load {}: {}", path, e.getMessage());
            return false;
        }
        return qupath.ext.qpsc.QPScopeChecks.validateMicroscopeConfig();
    }

    /**
     * Loads {@code path} into the config manager. {@code getInstance} reloads only when the
     * path changes, so a switch to the active path (the demo after its bundle was replaced, or
     * a config edited on disk) re-reads it explicitly.
     */
    private static void loadConfig(String path) {
        MicroscopeConfigManager current = MicroscopeConfigManager.getInstanceIfAvailable();
        if (current != null && path.equals(current.getConfigPath())) {
            current.reload(path);
        } else {
            MicroscopeConfigManager.getInstance(path);
        }
    }

    private static void reconnectIf(boolean wasConnected, MicroscopeController controller) {
        if (!wasConnected) {
            return;
        }
        try {
            controller.userTriggeredConnect();
        } catch (IOException e) {
            logger.warn("Reconnect after microscope switch failed: {}", e.getMessage());
        }
    }

    private static void refuse(String why) {
        inProgress.set(false);
        UserErrors.show(logger, "Switch Microscope", why);
    }

    private static boolean confirmed(String oldPath, String newPath, boolean live, boolean map, int others) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Switch Microscope");
        alert.setHeaderText("Switch to " + Path.of(newPath).getFileName() + "?");
        StringBuilder text = new StringBuilder();
        text.append("From: ").append(oldPath == null ? "(none)" : oldPath).append("\n");
        text.append("To:   ").append(newPath).append("\n\n");
        text.append("This will:\n");
        text.append("  - disconnect from the microscope server and reconnect with the new config\n");
        text.append("  - rebuild the QP Scope menu for the new microscope\n");
        if (live || map) {
            text.append("  - close and reopen ")
                    .append(
                            live && map
                                    ? "the Live Viewer and the Stage Map"
                                    : live ? "the Live Viewer" : "the Stage Map")
                    .append("\n");
        }
        if (others > 0) {
            text.append("  - close ").append(others).append(" other QP Scope window(s)\n");
        }
        text.append("\nUsing the wrong config can damage the microscope. Check the file name above.");
        Label content = new Label(text.toString());
        content.setWrapText(true);
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setMinWidth(560);
        alert.getButtonTypes().setAll(new ButtonType("Switch now"), ButtonType.CANCEL);
        Optional<ButtonType> choice = alert.showAndWait();
        return choice.isPresent() && choice.get() != ButtonType.CANCEL;
    }
}
