package qupath.ext.qpsc.ui.acquisition;

import java.beans.PropertyChangeListener;
import java.util.Locale;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.stage.Window;
import qupath.ext.qpsc.acquisition.StagePositionManager;

/**
 * A red halo on a "use the current stage position" button while the stage is not at the
 * coordinates typed beside it, so the operator sees that pressing it would change them.
 *
 * <p>The stage position comes from {@link StagePositionManager}'s cached poll (the
 * auxiliary socket, every 500 ms), never from a new query. The halo is off while the
 * position is unknown (not connected). The manager listener is held only while the
 * button's window is showing.
 */
public final class StagePositionHalo {

    /** How far the stage may be from the typed coordinates, in um, and still match. */
    static final double TOLERANCE_UM = 1.0;

    static final String HALO_STYLE = "-fx-effect: dropshadow(gaussian, #e53935, 12, 0.55, 0, 0);";

    private StagePositionHalo() {}

    /**
     * Whether the typed X and Y differ from the stage by more than {@link #TOLERANCE_UM}, or
     * are not numbers. False when the stage position is unknown (NaN), since there is then
     * nothing to compare with.
     */
    static boolean differs(String xText, String yText, double stageX, double stageY) {
        if (Double.isNaN(stageX) || Double.isNaN(stageY)) {
            return false;
        }
        try {
            double x = Double.parseDouble(xText.trim());
            double y = Double.parseDouble(yText.trim());
            return Math.abs(x - stageX) > TOLERANCE_UM || Math.abs(y - stageY) > TOLERANCE_UM;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * Keeps {@code button}'s halo in step with the stage and the two fields.
     *
     * @param baseTooltip the button's tooltip text; the stage position is appended while it differs
     */
    public static void attach(Button button, TextField xField, TextField yField, String baseTooltip) {
        String baseStyle = button.getStyle() == null ? "" : button.getStyle();
        Tooltip tooltip = new Tooltip(baseTooltip);
        button.setTooltip(tooltip);
        StagePositionManager manager = StagePositionManager.getInstance();

        Runnable refresh = () -> {
            double sx = manager.getX();
            double sy = manager.getY();
            boolean differs = differs(xField.getText(), yField.getText(), sx, sy);
            button.setStyle(differs ? baseStyle + HALO_STYLE : baseStyle);
            tooltip.setText(
                    differs
                            ? baseTooltip
                                    + String.format(
                                            Locale.ROOT,
                                            "%n%nThe stage is at X %.2f, Y %.2f, not at the center below.",
                                            sx,
                                            sy)
                            : baseTooltip);
        };
        PropertyChangeListener onMove = evt -> {
            String name = evt.getPropertyName();
            if (StagePositionManager.PROP_POS_X.equals(name) || StagePositionManager.PROP_POS_Y.equals(name)) {
                Platform.runLater(refresh);
            }
        };
        xField.textProperty().addListener((obs, o, n) -> refresh.run());
        yField.textProperty().addListener((obs, o, n) -> refresh.run());

        // Listen only while the window is open: the manager polls the stage while it has listeners.
        button.sceneProperty().addListener((obs, oldScene, scene) -> {
            if (scene == null) {
                return;
            }
            scene.windowProperty().addListener((o2, oldWindow, window) -> watch(window, manager, onMove, refresh));
            watch(scene.getWindow(), manager, onMove, refresh);
        });
        refresh.run();
    }

    private static void watch(
            Window window, StagePositionManager manager, PropertyChangeListener onMove, Runnable refresh) {
        if (window == null || window.getProperties().containsKey(StagePositionHalo.class)) {
            return;
        }
        window.getProperties().put(StagePositionHalo.class, Boolean.TRUE);
        Runnable start = () -> {
            // The poller starts at once for a first listener; otherwise its cache is current.
            manager.addPropertyChangeListener(onMove);
            refresh.run();
        };
        if (window.isShowing()) {
            start.run();
        }
        window.addEventHandler(javafx.stage.WindowEvent.WINDOW_SHOWN, e -> start.run());
        window.addEventHandler(
                javafx.stage.WindowEvent.WINDOW_HIDDEN, e -> manager.removePropertyChangeListener(onMove));
    }
}
