package qupath.ext.qpsc.ui;

import java.util.Optional;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager.ObjectiveMmPreset;

/**
 * Asks before QPSC applies a Micro-Manager preset that turns a motorized objective turret.
 *
 * <p>The dialog has to be seen and answered wherever it appears, because it stands between a
 * click in a QPSC combo box and a turret moving under the stage. So it is owned by the focused
 * window and floats above an always-on-top parent ({@link UIFunctions#showAlertOverParent}),
 * sizes itself to its wrapped text instead of a fixed size that cuts lines off, is moved fully
 * onto the screen once shown, and makes "Don't move" the default button: Enter or Escape never
 * turns the turret.
 */
public final class ObjectivePresetConfirmDialog {

    private ObjectivePresetConfirmDialog() {}

    /** The explanation shown in the dialog (pure; tested). */
    static String message(String objectiveId, ObjectiveMmPreset spec, String current) {
        String now = current == null || current.isBlank() ? "no matching preset" : "'" + current + "'";
        return "QPSC will apply Micro-Manager preset '" + spec.preset() + "' of group '" + spec.group()
                + "' (now " + now + ") so its pixel size matches " + objectiveId + ".\n\n"
                + "On this microscope that MOVES THE OBJECTIVE TURRET. Before you continue, make sure "
                + "the turret can turn freely: focus retracted, nothing on the stage that a longer "
                + "objective could hit, and immersion medium dealt with.\n\n"
                + "Don't move leaves the turret where it is; Micro-Manager's pixel size then stays "
                + "that of the current objective until the turret is on " + spec.preset() + ".";
    }

    /**
     * Shows the dialog and waits. Call on the FX thread.
     *
     * @return true only when the operator chose to move the turret
     */
    public static boolean ask(String objectiveId, ObjectiveMmPreset spec, String current) {
        ButtonType move = new ButtonType("Move turret to " + spec.preset(), ButtonBar.ButtonData.OK_DONE);
        ButtonType stay = new ButtonType("Don't move", ButtonBar.ButtonData.CANCEL_CLOSE);

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "", move, stay);
        alert.setTitle("Move the objective turret?");
        alert.setHeaderText("Move the objective turret to " + spec.preset() + "?");

        Label text = new Label(message(objectiveId, spec, current));
        text.setWrapText(true);
        text.setMaxWidth(460);
        text.setPrefWidth(460);
        text.setMinHeight(Region.USE_PREF_SIZE);
        VBox content = new VBox(text);
        content.setMinHeight(Region.USE_PREF_SIZE);
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        alert.getDialogPane().setMinWidth(Region.USE_PREF_SIZE);
        alert.setResizable(true);

        // Enter must not move hardware: the safe answer is the default.
        Button moveButton = (Button) alert.getDialogPane().lookupButton(move);
        Button stayButton = (Button) alert.getDialogPane().lookupButton(stay);
        moveButton.setDefaultButton(false);
        stayButton.setDefaultButton(true);

        Window owner = focusedWindow();
        alert.setOnShown(e -> {
            Window w = alert.getDialogPane().getScene().getWindow();
            if (w instanceof Stage stage) {
                stage.setAlwaysOnTop(true);
                stage.sizeToScene();
                keepOnScreen(stage, owner);
                stage.toFront();
                stage.requestFocus();
                stayButton.requestFocus();
            }
        });
        Optional<ButtonType> answer = UIFunctions.showAlertOverParent(alert, owner);
        return answer.isPresent() && answer.get() == move;
    }

    /**
     * The window the operator is using, to own the dialog: a focused always-on-top window first
     * (a dialog owned elsewhere sinks behind it), then any other focused window that is not
     * QuPath's main window, then null (QuPath's main window). Some window systems (WSLg) report
     * every window as focused, which is why the main window is the last choice, not the first.
     */
    private static Window focusedWindow() {
        Window main = null;
        try {
            var gui = qupath.lib.gui.QuPathGUI.getInstance();
            main = gui == null ? null : gui.getStage();
        } catch (Exception ignored) {
            // no QuPath GUI: fall through to any focused window
        }
        Window other = null;
        for (Window w : Window.getWindows()) {
            if (!w.isShowing() || !w.isFocused()) {
                continue;
            }
            if (w instanceof Stage s && s.isAlwaysOnTop()) {
                return w;
            }
            if (other == null && w != main) {
                other = w;
            }
        }
        return other;
    }

    /** Moves {@code stage} so all of it lies within the visual bounds of the owner's screen. */
    static void keepOnScreen(Stage stage, Window owner) {
        Rectangle2D bounds = screenBounds(owner);
        double w = Math.min(stage.getWidth(), bounds.getWidth());
        double h = Math.min(stage.getHeight(), bounds.getHeight());
        if (w < stage.getWidth()) {
            stage.setWidth(w);
        }
        if (h < stage.getHeight()) {
            stage.setHeight(h);
        }
        double x = clamp(stage.getX(), bounds.getMinX(), bounds.getMaxX() - w);
        double y = clamp(stage.getY(), bounds.getMinY(), bounds.getMaxY() - h);
        if (Double.isNaN(stage.getX()) || Double.isNaN(stage.getY())) {
            x = bounds.getMinX() + (bounds.getWidth() - w) / 2;
            y = bounds.getMinY() + (bounds.getHeight() - h) / 2;
        }
        stage.setX(x);
        stage.setY(y);
    }

    private static Rectangle2D screenBounds(Window owner) {
        if (owner != null) {
            var screens = Screen.getScreensForRectangle(
                    owner.getX(), owner.getY(), Math.max(1, owner.getWidth()), Math.max(1, owner.getHeight()));
            if (!screens.isEmpty()) {
                return screens.get(0).getVisualBounds();
            }
        }
        return Screen.getPrimary().getVisualBounds();
    }

    static double clamp(double v, double lo, double hi) {
        return hi < lo ? lo : Math.max(lo, Math.min(hi, v));
    }
}
