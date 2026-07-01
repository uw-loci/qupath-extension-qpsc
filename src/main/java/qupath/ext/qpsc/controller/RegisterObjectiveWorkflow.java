package qupath.ext.qpsc.controller;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.ObjectiveConfigWriter;
import qupath.fx.dialogs.Dialogs;

/**
 * "Register Current Objective" utility: captures the pixel size MicroManager
 * reports for the objective currently in the light path, collects the
 * metadata MM cannot know (id, display name, magnification, NA), and writes
 * skeleton entries across the config files so the objective becomes
 * immediately selectable with a correct pixel size.
 *
 * <p>This is the registration half of a two-step flow. MicroManager exposes
 * only pixel size (and binning / a raw turret label) -- no magnification, NA,
 * or human name -- so everything else is prompted. The calibration-heavy data
 * (autofocus params, white balance, exposures, background) is written as
 * {@code calibrated: false} placeholders; the summary points the user at the
 * existing calibration workflows to finish. See {@link ObjectiveConfigWriter}.
 */
public final class RegisterObjectiveWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(RegisterObjectiveWorkflow.class);
    private static final String TITLE = "Register Current Objective";

    private RegisterObjectiveWorkflow() {}

    /** Menu entry point (FX thread). */
    public static void run(qupath.lib.gui.QuPathGUI qupath) {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        if (mgr == null) {
            Dialogs.showErrorMessage(
                    TITLE, "Microscope configuration is not loaded. Check the config file in Preferences.");
            return;
        }
        MicroscopeController mc = MicroscopeController.getInstance();
        if (!mc.isConnected()) {
            Dialogs.showErrorMessage(
                    TITLE,
                    "Not connected to the microscope server.\n\n"
                            + "This utility reads the current pixel size from MicroManager, so a live "
                            + "connection is required. Connect first (QP Scope > Server Connection), put "
                            + "the objective you want to register in the light path, and try again.");
            return;
        }

        double mmPixelSize;
        try {
            mmPixelSize = mc.getSocketClient().getMicroscopePixelSize();
        } catch (Exception e) {
            logger.warn("Failed to read MM pixel size", e);
            Dialogs.showErrorMessage(TITLE, "Could not read the pixel size from MicroManager: " + e.getMessage());
            return;
        }
        if (mmPixelSize <= 0) {
            Dialogs.showErrorMessage(
                    TITLE,
                    "MicroManager returned a pixel size of " + mmPixelSize + " um/px.\n\n"
                            + "Set up MicroManager's pixel-size calibration for this objective first, "
                            + "or enter the correct value in the dialog after this.");
            // Still allow the user to proceed and type the value manually.
        }

        // Is this pixel size already a configured objective?
        String matchNote = "";
        try {
            Optional<MicroscopeConfigManager.HardwareSelection> match =
                    mgr.findHardwareByPixelSize(mmPixelSize, MicroscopeConfigManager.DEFAULT_PIXEL_SIZE_TOLERANCE_UM);
            if (match.isPresent()) {
                matchNote = "NOTE: this pixel size already matches configured objective '"
                        + match.get().objectiveId() + "' (detector '"
                        + match.get().detectorId()
                        + "'). Registering again is only needed for a genuinely new objective "
                        + "or a different detector.";
            }
        } catch (Exception e) {
            logger.debug("pixel-size match check failed: {}", e.getMessage());
        }

        Optional<ObjectiveConfigWriter.Data> data = showDialog(mgr, mmPixelSize, matchNote);
        if (data.isEmpty()) {
            return;
        }

        String configPathStr = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        if (configPathStr == null || configPathStr.isBlank()) {
            Dialogs.showErrorMessage(TITLE, "No microscope config file is set in Preferences.");
            return;
        }
        Path configPath = Path.of(configPathStr);

        ObjectiveConfigWriter.Report report = ObjectiveConfigWriter.writeAll(configPath, data.get());

        // Reload so the new objective is immediately visible.
        try {
            mgr.reload(configPathStr);
        } catch (Exception e) {
            logger.warn("Config reload after objective registration failed", e);
            report.errors.add("config reload: " + e.getMessage());
        }

        showSummary(data.get().objectiveId(), report);
    }

    private static Optional<ObjectiveConfigWriter.Data> showDialog(
            MicroscopeConfigManager mgr, double mmPixelSize, String matchNote) {

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(TITLE);
        dialog.setHeaderText("Register the objective currently in the light path.\n"
                + "Pixel size is read from MicroManager; the rest is not something MicroManager knows.");
        ButtonType registerType = new ButtonType("Register", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(registerType, ButtonType.CANCEL);

        TextField idField = new TextField();
        idField.setPromptText("e.g. LOCI_OBJECTIVE_OLYMPUS_60X_WATER_001");
        idField.setPrefColumnCount(32);
        TextField nameField = new TextField();
        nameField.setPromptText("e.g. 60x Water");
        TextField magField = new TextField();
        magField.setPromptText("e.g. 60");
        TextField naField = new TextField();
        naField.setPromptText("optional, e.g. 1.2");
        TextField wdField = new TextField();
        wdField.setPromptText("optional working distance (um)");
        TextField mfrField = new TextField();
        mfrField.setPromptText("optional part number");
        TextField pxField = new TextField(mmPixelSize > 0 ? String.valueOf(mmPixelSize) : "");
        pxField.setPromptText("um per pixel");

        ComboBox<String> detectorCombo = new ComboBox<>();
        try {
            detectorCombo.getItems().addAll(mgr.getHardwareDetectors());
        } catch (Exception e) {
            logger.debug("could not list detectors: {}", e.getMessage());
        }
        if (!detectorCombo.getItems().isEmpty()) {
            detectorCombo.getSelectionModel().select(0);
        }

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.setPadding(new Insets(12));
        int r = 0;
        if (matchNote != null && !matchNote.isEmpty()) {
            Label note = new Label(matchNote);
            note.setWrapText(true);
            note.setStyle("-fx-text-fill: #a05000;");
            note.setMaxWidth(420);
            grid.add(note, 0, r++, 2, 1);
        }
        grid.add(new Label("Objective id:"), 0, r);
        grid.add(idField, 1, r++);
        grid.add(new Label("Display name:"), 0, r);
        grid.add(nameField, 1, r++);
        grid.add(new Label("Magnification (x):"), 0, r);
        grid.add(magField, 1, r++);
        grid.add(new Label("Numerical aperture:"), 0, r);
        grid.add(naField, 1, r++);
        grid.add(new Label("Working distance (um):"), 0, r);
        grid.add(wdField, 1, r++);
        grid.add(new Label("Manufacturer id:"), 0, r);
        grid.add(mfrField, 1, r++);
        grid.add(new Label("Detector:"), 0, r);
        grid.add(detectorCombo, 1, r++);
        grid.add(new Label("Pixel size (um/px):"), 0, r);
        grid.add(pxField, 1, r++);

        Label hint = new Label("Autofocus, white balance and exposures are written as placeholders "
                + "(calibrated: false). Run the calibration utilities afterwards to make the "
                + "objective acquisition-ready.");
        hint.setWrapText(true);
        hint.setMaxWidth(420);
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: #606060;");
        grid.add(hint, 0, r, 2, 1);

        dialog.getDialogPane().setContent(grid);

        // Live validation: enable Register only when required fields are sane.
        Button registerBtn = (Button) dialog.getDialogPane().lookupButton(registerType);
        Runnable validate = () -> registerBtn.setDisable(idField.getText().isBlank()
                || nameField.getText().isBlank()
                || parsePositive(magField.getText()) == null
                || parsePositive(pxField.getText()) == null
                || detectorCombo.getSelectionModel().getSelectedItem() == null);
        idField.textProperty().addListener((o, a, b) -> validate.run());
        nameField.textProperty().addListener((o, a, b) -> validate.run());
        magField.textProperty().addListener((o, a, b) -> validate.run());
        pxField.textProperty().addListener((o, a, b) -> validate.run());
        detectorCombo.valueProperty().addListener((o, a, b) -> validate.run());
        validate.run();

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != registerType) {
            return Optional.empty();
        }

        // Modality map: name -> isLaserScanning (chooses the imaging-profile stub).
        Map<String, Boolean> modalities = new LinkedHashMap<>();
        try {
            for (String mod : mgr.getAvailableModalities()) {
                boolean isLsm = false;
                try {
                    Map<String, Object> mconf = mgr.getModalityConfig(mod);
                    isLsm = mconf != null && "multiphoton".equals(String.valueOf(mconf.get("type")));
                } catch (Exception ignore) {
                    // default non-LSM
                }
                modalities.put(mod, isLsm);
            }
        } catch (Exception e) {
            logger.debug("could not enumerate modalities: {}", e.getMessage());
        }

        String scopeName;
        try {
            scopeName = mgr.getMicroscopeName();
        } catch (Exception e) {
            scopeName = "";
        }

        ObjectiveConfigWriter.Data d = new ObjectiveConfigWriter.Data(
                idField.getText().trim(),
                detectorCombo.getSelectionModel().getSelectedItem(),
                parsePositive(pxField.getText()),
                nameField.getText().trim(),
                parsePositive(magField.getText()),
                parseOrNull(naField.getText()),
                parseOrNull(wdField.getText()),
                mfrField.getText().isBlank() ? null : mfrField.getText().trim(),
                scopeName,
                modalities);
        return Optional.of(d);
    }

    private static void showSummary(String objectiveId, ObjectiveConfigWriter.Report report) {
        StringBuilder sb = new StringBuilder();
        sb.append("Objective: ").append(objectiveId).append("\n\n");
        if (!report.changed.isEmpty()) {
            sb.append("Written:\n");
            for (String c : report.changed) sb.append("  - ").append(c).append('\n');
            sb.append('\n');
        }
        if (!report.skipped.isEmpty()) {
            sb.append("Skipped (already present or file absent):\n");
            for (String s : report.skipped) sb.append("  - ").append(s).append('\n');
            sb.append('\n');
        }
        if (report.hasErrors()) {
            sb.append("Errors:\n");
            for (String e : report.errors) sb.append("  - ").append(e).append('\n');
            sb.append('\n');
        }
        sb.append("Next steps to make it acquisition-ready:\n")
                .append("  1. Autofocus: review search_range_um, then set calibrated: true\n")
                .append("     (QP Scope > Utilities > Autofocus Editor / Benchmark).\n")
                .append("  2. White Balance + exposures (QP Scope > Utilities > camera tools).\n")
                .append("  3. Background collection for this objective's magnification.\n");

        if (report.hasErrors()) {
            Dialogs.showErrorMessage(TITLE, sb.toString());
        } else if (report.anyChange()) {
            Dialogs.showMessageDialog(TITLE, sb.toString());
        } else {
            Dialogs.showMessageDialog(TITLE, "Nothing to write -- this objective is already registered.\n\n" + sb);
        }
    }

    private static Double parsePositive(String s) {
        Double v = parseOrNull(s);
        return (v != null && v > 0) ? v : null;
    }

    private static Double parseOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
