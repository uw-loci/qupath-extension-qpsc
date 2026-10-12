package qupath.ext.qpsc.ui;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import javafx.application.Platform;
import javafx.scene.control.Dialog;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every top-level window QuPath SCope opens, so they can be found again.
 *
 * <p>Exists for one operation: switching microscopes while QuPath stays open. A camera-control
 * panel, an autofocus editor or a progress window built against one scope describes hardware
 * that is no longer connected the moment the config changes, and the only honest thing to do
 * with it is close it. JavaFX offers no way to tell our windows from QuPath's own (script editor,
 * log viewer, measurement tables) once they are on screen, so each one is registered where it is
 * created -- a single call after {@code new Stage()}, or {@link #track(Dialog)} for a
 * {@code Dialog}-based panel that stays open.
 *
 * <p>The two viewers, {@code LiveViewerWindow} and {@code StageMapWindow}, are deliberately NOT
 * registered: they are closed and reopened by the switch itself, through their own
 * {@code hide()}/{@code show()}, because the user wants them back.
 *
 * <p>Registration is weak; a window that is garbage-collected is simply forgotten.
 *
 * @since 0.9.3
 */
public final class QpscWindows {

    private static final Logger logger = LoggerFactory.getLogger(QpscWindows.class);

    private static final Set<Window> WINDOWS = Collections.newSetFromMap(new WeakHashMap<>());

    private QpscWindows() {}

    /** Registers a window this extension opened. Safe to call more than once. */
    public static void track(Window window) {
        if (window == null) {
            return;
        }
        synchronized (WINDOWS) {
            WINDOWS.add(window);
        }
    }

    /**
     * Registers a {@link Dialog}'s window once it is shown. For non-modal dialogs that stay open
     * (camera control); a modal {@code showAndWait} dialog blocks the switch from being started
     * at all, so there is nothing to close.
     */
    public static void track(Dialog<?> dialog) {
        if (dialog == null) {
            return;
        }
        dialog.setOnShown(e -> {
            if (dialog.getDialogPane() != null && dialog.getDialogPane().getScene() != null) {
                track(dialog.getDialogPane().getScene().getWindow());
            }
        });
    }

    /** The registered windows that are currently showing. FX thread. */
    public static List<Window> showing() {
        synchronized (WINDOWS) {
            return WINDOWS.stream().filter(Window::isShowing).toList();
        }
    }

    /**
     * Closes every registered window that is showing, the way the user would: a close request is
     * fired first so a window's own {@code setOnCloseRequest} cleanup (stop polling, dispose a
     * stream) runs, and only a window that ignores the request is hidden outright.
     *
     * @return how many windows were closed
     */
    public static int closeAll() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("QpscWindows.closeAll must run on the FX thread");
        }
        int closed = 0;
        for (Window w : showing()) {
            try {
                w.fireEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSE_REQUEST));
                if (w.isShowing()) {
                    if (w instanceof Stage stage) {
                        stage.close();
                    } else {
                        w.hide();
                    }
                }
                closed++;
                logger.info("Closed window for microscope switch: {}", describe(w));
            } catch (Exception e) {
                logger.warn("Could not close window {}: {}", describe(w), e.getMessage());
            }
        }
        return closed;
    }

    private static String describe(Window w) {
        if (w instanceof Stage stage
                && stage.getTitle() != null
                && !stage.getTitle().isBlank()) {
            return stage.getTitle();
        }
        return w.getClass().getSimpleName();
    }
}
