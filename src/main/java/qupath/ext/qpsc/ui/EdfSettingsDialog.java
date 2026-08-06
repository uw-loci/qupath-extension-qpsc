package qupath.ext.qpsc.ui;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;

/**
 * Small settings dialog for the Extended Depth of Field projection.
 *
 * <p>EDF is the only Z-projection with anything to tune -- max/min/sum/mean/std
 * have no parameters at all -- which is why these live behind a button next to
 * the projection dropdown rather than cluttering the acquisition dialog for
 * everyone.
 *
 * <p>The three settings are not cosmetic. Their defaults are reasoned starting
 * points rather than measured optima, and the right values depend on pixel
 * size, camera noise, and whether the sample's focal surface is smooth or
 * stepped -- none of which the extension can know. The dialog says what each
 * one does to the output so a user can tell which way to move it from what
 * they see, instead of guessing.
 *
 * <p>Shared by both acquisition controllers so the two cannot drift apart.
 */
public class EdfSettingsDialog {

    private static final Logger logger = LoggerFactory.getLogger(EdfSettingsDialog.class);

    /** Sharpness maps the Python side implements, in the order most users should try them. */
    private static final String[] METRICS = {"tenengrad", "modified_laplacian", "variance"};

    private EdfSettingsDialog() {}

    /**
     * Shows the dialog and writes the result to persistent preferences on OK.
     * Cancel leaves every stored value untouched.
     *
     * <p>Must be called on the JavaFX application thread.
     *
     * @param parent the owning window, typically the always-on-top acquisition
     *     dialog; may be null
     */
    public static void show(javafx.stage.Window parent) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Extended Depth of Field settings");
        dialog.setHeaderText("How the sharpest plane is chosen for each pixel.\n"
                + "Defaults are reasonable starting points, not measured optima.");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        ComboBox<String> metricCombo = new ComboBox<>();
        metricCombo.getItems().addAll(METRICS);
        metricCombo.setValue(PersistentPreferences.getEdfMetric());
        metricCombo.setTooltip(new Tooltip("How sharpness is measured at each pixel.\n\n"
                + "tenengrad -- gradient strength. Matches the autofocus metric of the same name, so the "
                + "fused image agrees with what autofocus was optimising. Good default for stained tissue.\n\n"
                + "modified_laplacian -- second differences taken per axis, so opposite curvature in X and Y "
                + "cannot cancel. Peaks more sharply in Z than tenengrad, which helps on fibres and other "
                + "elongated structures, but is more sensitive to noise.\n\n"
                + "variance -- local contrast. Cheapest and the most forgiving of noise, but responds to a "
                + "blob whether or not its edges are sharp. Try it when the others chase noise."));

        Spinner<Integer> windowSpinner = new Spinner<>(1, 99, PersistentPreferences.getEdfWindow(), 2);
        windowSpinner.setEditable(true);
        windowSpinner.setPrefWidth(90);
        windowSpinner.setTooltip(new Tooltip("Averaging window for the sharpness measurement, in pixels.\n\n"
                + "Raw per-pixel sharpness is too noisy to choose a plane from, so it is averaged over "
                + "this window first. This is the setting that matters most.\n\n"
                + "Too small: fused output looks blocky or speckled, stitched from several planes at random "
                + "in flat areas. Raise it.\n"
                + "Too large: the boundary between in-focus regions is smeared. Lower it.\n\n"
                + "Scales with pixel size -- a finer pixel size wants a larger window for the same physical "
                + "area. The default 9 suits roughly 0.65 um/px."));

        Spinner<Integer> smoothSpinner = new Spinner<>(0, 99, PersistentPreferences.getEdfIndexSmooth(), 2);
        smoothSpinner.setEditable(true);
        smoothSpinner.setPrefWidth(90);
        smoothSpinner.setTooltip(
                new Tooltip(
                        "Median filter applied to the map of which plane each pixel chose. 0 disables it.\n\n"
                                + "Real focal surfaces are smooth, so this removes pixels that picked an odd plane for no "
                                + "physical reason.\n\n"
                                + "Raise it for a tilted but flat sample, where the focal surface really is a smooth plane.\n"
                                + "Lower it (or set 0) where focus genuinely steps -- a fold, or a torn section -- because "
                                + "a large median will bridge across the step and pick a plane that is sharp on neither side."));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));

        grid.add(new Label("Sharpness metric:"), 0, 0);
        grid.add(metricCombo, 1, 0);
        grid.add(new Label("Averaging window (px):"), 0, 1);
        grid.add(windowSpinner, 1, 1);
        grid.add(new Label("Focal-surface smoothing:"), 0, 2);
        grid.add(smoothSpinner, 1, 2);

        Label note = new Label("These apply on the microscope server during acquisition, so they cannot be "
                + "changed after the fact without re-acquiring. Hover each control for what to do when the "
                + "output looks wrong.");
        note.setWrapText(true);
        note.setFont(Font.font(11));
        // Wrapped labels default to about one line of preferred height, so the
        // text truncates to an ellipsis unless the min height tracks the pref.
        note.setMinHeight(Region.USE_PREF_SIZE);
        note.setMaxWidth(380);
        grid.add(note, 0, 3, 2, 1);

        dialog.getDialogPane().setContent(grid);

        // Re-parent over the always-on-top acquisition dialog; a plain
        // showAndWait sinks behind it while still holding modal focus, which
        // reads as a frozen UI. See the module CLAUDE.md UI conventions.
        var result = UIFunctions.showAlertOverParent(dialog, parent);

        if (result.isPresent() && result.get() == ButtonType.OK) {
            // Commit the editor text for a spinner the user typed into without
            // pressing Enter -- otherwise the typed value is silently dropped.
            commitEditorText(windowSpinner);
            commitEditorText(smoothSpinner);

            PersistentPreferences.setEdfMetric(metricCombo.getValue());
            PersistentPreferences.setEdfWindow(windowSpinner.getValue());
            PersistentPreferences.setEdfIndexSmooth(smoothSpinner.getValue());
            logger.info(
                    "EDF settings saved: metric={}, window={}, indexSmooth={}",
                    metricCombo.getValue(),
                    windowSpinner.getValue(),
                    smoothSpinner.getValue());
        }
    }

    /** Pushes an editable spinner's typed text into its value. */
    private static void commitEditorText(Spinner<Integer> spinner) {
        if (!spinner.isEditable()) {
            return;
        }
        try {
            spinner.getValueFactory()
                    .setValue(spinner.getValueFactory()
                            .getConverter()
                            .fromString(spinner.getEditor().getText()));
        } catch (Exception e) {
            logger.debug(
                    "Ignoring uncommittable spinner text '{}'",
                    spinner.getEditor().getText());
        }
    }
}
