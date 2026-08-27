package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.utilities.LightPathModel;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.dialogs.Dialogs;

/**
 * Set-once per-microscope light-path orientation: <b>scope type</b> (upright vs inverted) and
 * <b>optical flip</b> (objective + tube-lens parity). These are fixed properties of a rig, so they
 * live here in Utilities rather than on the Stage Map -- the Stage Map exposes only the run-to-run
 * slide-180 rotation.
 *
 * <p>Writes to the active microscope YAML {@code light_path} block via {@link LightPathModel} and
 * reloads the config so the change takes effect immediately (reopen the Stage Map / Live Viewer to
 * see it applied). This is the same block the setup wizard will edit, and the single source of truth
 * {@link LightPathModel} reads.
 *
 * <p><b>What each does:</b>
 * <ul>
 *   <li><b>Scope type</b> is recorded for the per-microscope orientation model and the setup wizard
 *       preview; the Stage Map's Stage View no longer re-applies it (the physical inversion is
 *       already carried by the stage polarity + alignment transform, so re-applying double-counts).
 *   <li><b>Optical flip</b> drives the Stage Map's <b>Camera View</b> so the map matches the Live
 *       Viewer -- e.g. PPM's objective inversion is {@code xy}. Set it by matching the camera, not by
 *       reasoning about the light path.
 * </ul>
 */
public final class LightPathSetupDialog {

    private static final Logger logger = LoggerFactory.getLogger(LightPathSetupDialog.class);

    private static final String SCOPE_UPRIGHT_LABEL = "Upright (objective above, coverslip up)";
    private static final String SCOPE_INVERTED_LABEL = "Inverted (objective below, coverslip down)";

    private static final String[] OPTICS_KEYS = {
        LightPathModel.OPTICAL_NONE, LightPathModel.OPTICAL_X, LightPathModel.OPTICAL_Y, LightPathModel.OPTICAL_XY
    };
    private static final String[] OPTICS_LABELS = {"none", "flip X", "flip Y", "180 (XY)"};

    private LightPathSetupDialog() {}

    /** Show the dialog on the FX thread. */
    public static void show() {
        Platform.runLater(LightPathSetupDialog::build);
    }

    private static void build() {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        if (mgr == null || mgr.getConfigPath() == null) {
            Dialogs.showErrorMessage(
                    "No Microscope Config",
                    "No writable microscope configuration is loaded, so the light-path orientation "
                            + "cannot be saved. Set the microscope config file in Preferences first.");
            return;
        }

        String scope = LightPathModel.scopeType();
        String optics = LightPathModel.opticalFlip();

        ComboBox<String> scopeCombo = new ComboBox<>();
        scopeCombo.getItems().addAll(SCOPE_UPRIGHT_LABEL, SCOPE_INVERTED_LABEL);
        scopeCombo.getSelectionModel().select(LightPathModel.SCOPE_INVERTED.equals(scope) ? 1 : 0);
        scopeCombo.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> opticsCombo = new ComboBox<>();
        opticsCombo.getItems().addAll(OPTICS_LABELS);
        opticsCombo.getSelectionModel().select(opticsLabelForKey(optics));
        opticsCombo.setMaxWidth(Double.MAX_VALUE);

        Label intro = new Label("Set-once orientation for microscope '" + mgr.getMicroscopeName()
                + "'. These are fixed properties of the rig; the run-to-run slide-180 rotation stays "
                + "on the Stage Map.");
        intro.setWrapText(true);
        intro.setMaxWidth(460);

        Label scopeHelp = new Label("Physical scope type. Recorded for the orientation model; does not "
                + "re-flip the Stage Map (the stage polarity + alignment transform already carry the "
                + "inversion).");
        scopeHelp.setWrapText(true);
        scopeHelp.setMaxWidth(460);
        scopeHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: " + ThemeColors.MUTED + ";");

        Label opticsHelp = new Label("Optical flip (objective + tube-lens parity). Drives the Stage "
                + "Map's Camera View so it matches the Live Viewer (e.g. PPM = 180 (XY)). Set it by "
                + "matching the camera. Reopen the Stage Map / Live Viewer to see a change applied.");
        opticsHelp.setWrapText(true);
        opticsHelp.setMaxWidth(460);
        opticsHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: " + ThemeColors.MUTED + ";");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);
        grid.addRow(0, new Label("Scope type:"), scopeCombo);
        grid.add(scopeHelp, 0, 1, 2, 1);
        grid.addRow(2, new Label("Optical flip:"), opticsCombo);
        grid.add(opticsHelp, 0, 3, 2, 1);

        VBox content = new VBox(12, intro, grid);
        content.setPadding(new Insets(15));

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Light Path Orientation");
        dialog.setHeaderText("Per-microscope scope type and optical flip");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setMinWidth(500);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        ((javafx.scene.control.Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).setText("Save");

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) {
                return;
            }
            String newScope = scopeCombo.getSelectionModel().getSelectedIndex() == 1
                    ? LightPathModel.SCOPE_INVERTED
                    : LightPathModel.SCOPE_UPRIGHT;
            String newOptics = opticsKeyForLabel(opticsCombo.getValue());

            boolean changed = LightPathModel.writeFactor(LightPathModel.KEY_SCOPE_TYPE, newScope);
            changed |= LightPathModel.writeFactor(LightPathModel.KEY_OPTICAL_FLIP, newOptics);

            // Reload so LightPathModel reads (Stage Map Camera View, wizard preview, startup dump) see
            // the new values immediately instead of the stale cached config.
            try {
                mgr.reload();
            } catch (Exception e) {
                logger.warn("Config reload after light-path edit failed: {}", e.getMessage());
            }
            logger.info(
                    "Light Path Orientation saved: scope_type={}, optical_flip={} (changed={})",
                    newScope,
                    newOptics,
                    changed);
            LightPathModel.logCurrent("Orientation stack after Light Path edit");
            Dialogs.showInfoNotification(
                    "Light Path Orientation",
                    "Saved scope type = " + newScope + ", optical flip = " + newOptics
                            + ".\nReopen the Stage Map / Live Viewer to see it applied.");
        });
    }

    private static String opticsLabelForKey(String key) {
        for (int i = 0; i < OPTICS_KEYS.length; i++) {
            if (OPTICS_KEYS[i].equalsIgnoreCase(key)) {
                return OPTICS_LABELS[i];
            }
        }
        return OPTICS_LABELS[0];
    }

    private static String opticsKeyForLabel(String label) {
        for (int i = 0; i < OPTICS_LABELS.length; i++) {
            if (OPTICS_LABELS[i].equals(label)) {
                return OPTICS_KEYS[i];
            }
        }
        return OPTICS_KEYS[0];
    }
}
