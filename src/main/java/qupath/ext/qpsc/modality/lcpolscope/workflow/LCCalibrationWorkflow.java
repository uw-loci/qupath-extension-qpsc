package qupath.ext.qpsc.modality.lcpolscope.workflow;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.DialogOwner;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.ConfigYamlEditor;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.dialogs.Dialogs;

/**
 * Calibrates the LC-PolScope liquid crystals from QuPath.
 *
 * <p>Finds the extinction point -- the crystal settings that transmit least light -- then the
 * swing states either side of it, and writes the palette the acquisition will use. Runs on a
 * clear, specimen-free field.
 *
 * <p>Reached through {@code LCPolScopeModalityHandler.getMenuContributions()}, so the menu
 * appears only on a microscope whose configuration declares this modality.
 */
public class LCCalibrationWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(LCCalibrationWorkflow.class);

    /** Extinction-ratio bands, from recOrder. This rig reached 267 after a good calibration. */
    private static final double ER_GOOD = 100.0;

    private static final double ER_ACCEPTABLE = 80.0;

    /** The modality key in the microscope YAML whose channels the palette is written to. */
    static final String MODALITY_KEY = "lcpolscope";

    /** Where the calibration's provenance is recorded, beside the settings it was made with. */
    static final String[] RECONSTRUCTION_BLOCK = {"modalities", MODALITY_KEY, "reconstruction"};

    /** Fallbacks for a server that predates the property names in the reply. */
    private static final String DEFAULT_LC_DEVICE = "MeadowlarkLC";

    private static final Map<String, String> DEFAULT_RETARDANCE_PROPERTIES_WAVES =
            Map.of("LCA", "Retardance LC-A [in waves]", "LCB", "Retardance LC-B [in waves]");

    private LCCalibrationWorkflow() {}

    public static void run() {
        Platform.runLater(() -> promptForParameters().ifPresent(LCCalibrationWorkflow::startWithProgress));
    }

    // ------------------------------------------------------------------
    // Parameters
    // ------------------------------------------------------------------

    /** What the operator chooses; everything else comes from the microscope YAML. */
    public record Params(String outputFolder, String strategy, Double blackLevel) {}

    /**
     * The reconstruction settings this calibration will run with, read from the microscope YAML.
     *
     * <p>Shown rather than made editable. The wavelength is a pure scale on reported retardance,
     * so a value that disagrees with the one the acquisition uses produces maps that look
     * entirely correct and are uniformly wrong -- there is no symptom to notice. Keeping one
     * source of truth is what prevents that, so this displays the value and points at where to
     * change it instead of offering a second place to set it.
     *
     * <p>The wavelength in particular is worth reading before every calibration on this
     * instrument: the interference filter has never been identified, and the value has already
     * moved between 546 and 549 once.
     */
    private static String describeReconstructionSettings() {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        if (mgr == null) {
            String path = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (path != null && !path.isBlank()) {
                mgr = MicroscopeConfigManager.getInstance(path);
            }
        }
        if (mgr == null) {
            return "  (microscope configuration not loaded -- cannot show the settings in effect)";
        }
        Double swing = mgr.getDouble("modalities", "lcpolscope", "reconstruction", "swing_waves");
        Double wavelength = mgr.getDouble("modalities", "lcpolscope", "reconstruction", "wavelength_nm");
        Object scheme = mgr.getSection("modalities", "lcpolscope", "reconstruction") == null
                ? null
                : mgr.getSection("modalities", "lcpolscope", "reconstruction").get("scheme");
        Object mode = mgr.getSection("modalities", "lcpolscope", "reconstruction") == null
                ? null
                : mgr.getSection("modalities", "lcpolscope", "reconstruction").get("lc_control_mode");
        // Shown because the server refuses MM-Voltage (no retardance/voltage curve is wired
        // up), and before this line existed the operator had no way to see the setting that
        // made the calibration fail.
        String modeText = mode == null ? "MM-Retardance (default)" : String.valueOf(mode);
        if ("MM-Voltage".equals(String.valueOf(mode))) {
            modeText += "  -- NOT SUPPORTED by calibration; set MM-Retardance in the YAML";
        }
        return "  swing       " + (swing == null ? "NOT SET" : String.valueOf(swing)) + " waves\n"
                + "  wavelength  " + (wavelength == null ? "NOT SET" : String.valueOf(wavelength)) + " nm\n"
                + "  scheme      " + (scheme == null ? "NOT SET" : String.valueOf(scheme)) + "\n"
                + "  LC control  " + modeText;
    }

    private static Optional<Params> promptForParameters() {
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle("Calibrate Liquid Crystals");
        dialog.setHeaderText("LC-PolScope calibration");

        Label intro = new Label("Move to a clear, specimen-free area before running this. The calibration measures\n"
                + "how dark the extinction state can be made, so anything birefringent in the field\n"
                + "will make the result worse.\n\n"
                + "Swing, scheme, wavelength and LC control mode are taken from the microscope\n"
                + "configuration, so the calibration cannot disagree with the acquisition that uses\n"
                + "it. They are shown below so you can check them; change them in the microscope\n"
                + "YAML (Show config file...), not here.");
        intro.setStyle("-fx-font-size: 11px;");

        Label fromConfig = new Label(describeReconstructionSettings());
        fromConfig.setStyle("-fx-font-size: 11px; -fx-font-family: monospace;");

        // The settings above are read-only here on purpose, so the operator needs a way to
        // reach the file that owns them. Revealing it beats printing the path: these live
        // wherever the rig was set up, and on the LC-PolScope machine that is several
        // folders deep in a OneDrive-backed profile.
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        boolean haveConfigPath = configPath != null && !configPath.isBlank();
        Button showConfig = new Button("Show config file...");
        showConfig.setDisable(!haveConfigPath);
        showConfig.setTooltip(new Tooltip(
                haveConfigPath
                        ? "Reveal " + configPath + " in the file browser"
                        : "No microscope configuration file is set in the QPSC preferences."));
        showConfig.setOnAction(e -> {
            java.io.File file = new java.io.File(configPath);
            if (!UIFunctions.revealInFileBrowser(file)) {
                // Says which path failed: "could not open the file browser" with no path is
                // useless when the likely cause is the path being wrong or on a disconnected
                // drive, which is exactly the case on a rig with a cloud-synced profile.
                logger.error("Could not reveal the microscope configuration file {}", file.getAbsolutePath());
                Dialogs.showErrorMessage(
                        "LC-PolScope Calibration",
                        "Could not reveal the microscope configuration file:\n\n" + file.getAbsolutePath()
                                + "\n\nCheck that the path in the QPSC preferences still exists.");
            }
        });

        TextField outputField = new TextField(QPPreferenceDialog.getProjectsFolderProperty());
        outputField.setPrefColumnCount(32);

        ChoiceBox<String> strategyBox = new ChoiceBox<>();
        strategyBox.getItems().addAll("single_pass", "iterative");
        strategyBox.setValue("single_pass");

        TextField blackField = new TextField();
        blackField.setPromptText("blank = measure it");
        blackField.setPrefColumnCount(10);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10, 0, 0, 0));
        grid.addRow(0, new Label("Output folder:"), outputField);
        grid.addRow(1, new Label("Search:"), strategyBox);
        grid.addRow(2, new Label("Black level:"), blackField);

        Label notes = new Label("Search: one pass is usually enough. Iterative refines until the residual is small,\n"
                + "at roughly three times the exposures -- a trade, not an upgrade.\n\n"
                + "Black level: leave blank and the camera measures a dark frame. It matters --\n"
                + "an error of 50 counts moves the extinction ratio by about 10 percent.");
        notes.setStyle("-fx-font-size: 11px;");

        VBox content = new VBox(8, intro, fromConfig, showConfig, grid, notes);
        dialog.getDialogPane().setContent(content);

        DialogOwner.own(dialog);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return Optional.empty();
        }
        String folder =
                outputField.getText() == null ? "" : outputField.getText().trim();
        if (folder.isEmpty()) {
            logger.error("LC-PolScope calibration not started: no output folder given");
            Dialogs.showErrorMessage("LC-PolScope Calibration", "An output folder is required.");
            return Optional.empty();
        }
        Double black = null;
        String blackText =
                blackField.getText() == null ? "" : blackField.getText().trim();
        if (!blackText.isEmpty()) {
            try {
                black = Double.parseDouble(blackText);
            } catch (NumberFormatException e) {
                logger.error("LC-PolScope calibration not started: black level '{}' is not a number", blackText);
                Dialogs.showErrorMessage("LC-PolScope Calibration", "Black level must be a number, or blank.");
                return Optional.empty();
            }
        }
        return Optional.of(new Params(folder, strategyBox.getValue(), black));
    }

    // ------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------

    private static void startWithProgress(Params params) {
        Alert progress = new Alert(Alert.AlertType.INFORMATION);
        progress.setTitle("LC Calibration In Progress");
        progress.setHeaderText("Calibrating liquid crystals");

        Label status = new Label("Starting...");
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(28, 28);
        Label hint = new Label("A few hundred exposures. Leave the field clear and the illumination steady.");
        hint.setStyle("-fx-font-size: 11px;");

        progress.getDialogPane().setContent(new VBox(10, status, spinner, hint));
        progress.getButtonTypes().setAll(ButtonType.CANCEL);
        DialogOwner.own(progress);
        progress.show();

        CompletableFuture.runAsync(() -> execute(params, progress, status)).exceptionally(ex -> {
            logger.error("LC-PolScope calibration failed", ex);
            Platform.runLater(() -> {
                progress.close();
                Dialogs.showErrorMessage("LC-PolScope Calibration", "Calibration failed: " + ex.getMessage());
            });
            return null;
        });
    }

    private static void execute(Params params, Alert progress, Label status) {
        try {
            MicroscopeController controller = MicroscopeController.getInstance();
            if (!controller.isConnected()) {
                logger.info("Not connected to the microscope server; connecting");
                controller.userTriggeredConnect();
            }
            MicroscopeSocketClient client = controller.getSocketClient();

            String json = client.runLcCalibration(
                    QPPreferenceDialog.getMicroscopeConfigFileProperty(),
                    params.outputFolder(),
                    "lcpolscope",
                    null, // swing, scheme and wavelength come from the YAML so they
                    null, // cannot drift from the acquisition that will use them
                    null,
                    params.blackLevel(),
                    params.strategy(),
                    message -> Platform.runLater(() -> status.setText(message)));

            JsonObject result = JsonParser.parseString(json).getAsJsonObject();
            Platform.runLater(() -> {
                progress.close();
                showResult(result);
            });
        } catch (Exception e) {
            logger.error("LC-PolScope calibration error", e);
            Platform.runLater(() -> {
                progress.close();
                Dialogs.showErrorMessage("LC-PolScope Calibration", e.getMessage());
            });
        }
    }

    // ------------------------------------------------------------------
    // Result
    // ------------------------------------------------------------------

    private static void showResult(JsonObject result) {
        boolean success = result.has("success") && result.get("success").getAsBoolean();
        if (!success) {
            String error = result.has("error") ? result.get("error").getAsString() : "unknown error";
            // The server logs this in its own log; log it here too so it reaches the QuPath
            // log and a bug report, not only the dialog.
            logger.error("LC-PolScope calibration failed on the microscope server: {}", error);
            Dialogs.showErrorMessage("LC-PolScope Calibration", error);
            return;
        }

        double ratio =
                result.has("extinction_ratio") ? result.get("extinction_ratio").getAsDouble() : Double.NaN;
        String assessment = result.has("assessment") ? result.get("assessment").getAsString() : "unknown";

        // A poor calibration is shown as a warning, not an error: it is recorded
        // and inspectable, and the operator decides whether to accept it.
        Alert.AlertType type = ratio >= ER_ACCEPTABLE ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING;
        Alert dialog = new Alert(type);
        dialog.setTitle("LC Calibration Complete");
        dialog.setHeaderText(String.format("Extinction ratio %.1f (%s)", ratio, assessment));

        List<String> lines = new ArrayList<>();
        lines.add(bandExplanation(ratio));
        lines.add("");
        lines.add("Palette (LC-A, LC-B in waves):");
        if (result.has("palette_waves")) {
            JsonObject palette = result.getAsJsonObject("palette_waves");
            for (Map.Entry<String, com.google.gson.JsonElement> entry : palette.entrySet()) {
                JsonArray pair = entry.getValue().getAsJsonArray();
                lines.add(String.format(
                        "    %-8s  %.4f, %.4f",
                        entry.getKey(), pair.get(0).getAsDouble(), pair.get(1).getAsDouble()));
            }
        }
        if (result.has("black_level")) {
            lines.add("");
            lines.add(String.format(
                    "Black level %.1f (%s)",
                    result.get("black_level").getAsDouble(),
                    result.has("black_level_source")
                            ? result.get("black_level_source").getAsString()
                            : "?"));
        }
        if (result.has("exposures") && result.has("elapsed_s")) {
            lines.add(String.format(
                    "%d exposures in %.1f s",
                    result.get("exposures").getAsInt(), result.get("elapsed_s").getAsDouble()));
        }
        if (result.has("warnings")) {
            JsonArray warnings = result.getAsJsonArray("warnings");
            if (warnings.size() > 0) {
                lines.add("");
                lines.add("Warnings:");
                warnings.forEach(w -> lines.add("    " + w.getAsString()));
            }
        }
        if (result.has("metadata_path")) {
            lines.add("");
            lines.add("Written to " + result.get("metadata_path").getAsString());
        }

        boolean canApply = result.has("palette_waves")
                && result.getAsJsonObject("palette_waves").size() > 0;
        ButtonType apply = new ButtonType("Apply to acquisition", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        ButtonType close = new ButtonType("Close", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        if (canApply) {
            lines.add("");
            lines.add("Apply to acquisition writes these retardances into the microscope YAML as the");
            lines.add("LC settings each state uses, replacing the Micro-Manager Channel presets. Until");
            lines.add("you do, acquisition keeps using whatever calibrated the presets.");
            dialog.getButtonTypes().setAll(apply, close);
        } else {
            dialog.getButtonTypes().setAll(close);
        }

        Label body = new Label(String.join("\n", lines));
        body.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        dialog.getDialogPane().setContent(body);
        DialogOwner.own(dialog);
        if (dialog.showAndWait().orElse(close) != apply) {
            return;
        }
        if (!(ratio >= ER_ACCEPTABLE)) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Apply a poor calibration?");
            confirm.setHeaderText(String.format("Extinction ratio %.1f is below %.0f", ratio, ER_ACCEPTABLE));
            confirm.setContentText("Reconstruction from this palette will be noisy. Apply it anyway?");
            DialogOwner.own(confirm);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return;
            }
        }
        applyToAcquisition(result);
    }

    // ------------------------------------------------------------------
    // Apply: palette -> microscope YAML
    // ------------------------------------------------------------------

    /** Applies the palette to the active configuration, then reloads it here and on the server. */
    private static void applyToAcquisition(JsonObject result) {
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        if (configPath == null || configPath.isBlank()) {
            logger.error("Cannot apply the LC palette: no microscope configuration file is set");
            Dialogs.showErrorMessage(
                    "LC-PolScope Calibration", "No microscope configuration file is set in the QPSC preferences.");
            return;
        }
        List<String> summary;
        try {
            summary = applyPaletteToConfig(Path.of(configPath), result);
        } catch (IOException | IllegalStateException e) {
            logger.error("Applying the LC palette to {} failed", configPath, e);
            Dialogs.showErrorMessage(
                    "LC-PolScope Calibration",
                    "Could not write the palette to the microscope YAML:\n\n" + e.getMessage());
            return;
        }
        // The in-memory config and the server both hold the old channels until told otherwise.
        try {
            MicroscopeConfigManager.getInstance(configPath).reload();
        } catch (Exception e) {
            logger.error("Palette written but the configuration did not reload", e);
            summary.add("");
            summary.add("The file was written but QPSC could not reload it: " + e.getMessage());
            summary.add("Restart QuPath before acquiring.");
        }
        try {
            MicroscopeController controller = MicroscopeController.getInstance();
            if (controller.isConnected()) {
                controller.sendReconfig();
                summary.add("Microscope server reloaded the configuration.");
            } else {
                summary.add("Not connected: the microscope server will read the new file when it next loads it.");
            }
        } catch (Exception e) {
            logger.error("Palette written but the microscope server did not reload it", e);
            summary.add("");
            summary.add("The server did not reload the configuration: " + e.getMessage());
            summary.add("Reconnect, or restart the server, before acquiring.");
        }
        logger.info("LC palette applied to {}:\n{}", configPath, String.join("\n", summary));
        Alert done = new Alert(Alert.AlertType.INFORMATION);
        done.setTitle("LC palette applied");
        done.setHeaderText("Acquisition now uses this calibration");
        Label body = new Label(String.join("\n", summary));
        body.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        done.getDialogPane().setContent(body);
        DialogOwner.own(done);
        done.showAndWait();
    }

    /**
     * Write a calibration's palette into the microscope YAML so acquisition reproduces it.
     *
     * <p>For every state in {@code palette_waves}, the channel of the same id under
     * {@code modalities.lcpolscope.channels} gets its {@code device_properties} replaced with the
     * two retardance writes (in waves; the Meadowlark adapter converts to volts) and loses its
     * {@code mm_setup_presets}, which hold the previous calibration as volts and would otherwise
     * be applied first on every state. Then {@code reconstruction.state_order} is reset to the
     * palette's own order, because polscope_library names its states so that State0..State4 is
     * already the order {@code reconstruct()} consumes -- the permutation that suited OpenPolScope's
     * presets would rotate the orientation map with no visible symptom. Finally the calibration
     * file, time and extinction ratio are recorded under {@code reconstruction}.
     *
     * <p>Checks that every palette state has a channel before writing anything, so a config that
     * does not match the palette is left untouched.
     *
     * @return human-readable lines describing what changed
     * @throws IllegalStateException if the palette does not match the channels in the file
     */
    static List<String> applyPaletteToConfig(Path configPath, JsonObject result) throws IOException {
        JsonObject palette = result.getAsJsonObject("palette_waves");
        if (palette == null || palette.size() == 0) {
            throw new IllegalStateException("the calibration result carries no palette_waves");
        }
        String device = result.has("lc_device") ? result.get("lc_device").getAsString() : DEFAULT_LC_DEVICE;
        Map<String, String> propertyNames = new LinkedHashMap<>(DEFAULT_RETARDANCE_PROPERTIES_WAVES);
        if (result.has("lc_retardance_properties_waves")) {
            for (Map.Entry<String, com.google.gson.JsonElement> e :
                    result.getAsJsonObject("lc_retardance_properties_waves").entrySet()) {
                propertyNames.put(e.getKey(), e.getValue().getAsString());
            }
        }
        String calibratedAt = result.has("calibrated_at")
                ? result.get("calibrated_at").getAsString()
                : LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String extinction = result.has("extinction_ratio")
                ? String.format("%.1f", result.get("extinction_ratio").getAsDouble())
                : "unmeasured";
        String metadataPath =
                result.has("metadata_path") ? result.get("metadata_path").getAsString() : null;

        List<String> states = new ArrayList<>(palette.keySet());
        List<String> missing = missingChannels(configPath, states);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("the palette names states with no channel under modalities." + MODALITY_KEY
                    + ".channels: " + missing + ". Nothing was written.");
        }

        List<String> summary = new ArrayList<>();
        List<String> comment = List.of(
                "LC calibration " + calibratedAt + ", extinction ratio " + extinction + ".",
                "Retardance in WAVES; the " + device + " adapter converts to volts. Written by QPSC.");
        for (String state : states) {
            JsonArray pair = palette.getAsJsonArray(state);
            double lcaWaves = pair.get(0).getAsDouble();
            double lcbWaves = pair.get(1).getAsDouble();
            List<Map<String, Object>> items = List.of(
                    propertyWrite(device, propertyNames.get("LCA"), lcaWaves),
                    propertyWrite(device, propertyNames.get("LCB"), lcbWaves));
            ConfigYamlEditor.replaceChannelListField(
                    configPath, MODALITY_KEY, state, "device_properties", items, comment);
            ConfigYamlEditor.replaceChannelListField(configPath, MODALITY_KEY, state, "mm_setup_presets", null, null);
            summary.add(String.format("%-8s LC-A %.4f  LC-B %.4f waves", state, lcaWaves, lcbWaves));
        }

        String order = "[" + String.join(", ", states) + "]";
        ConfigYamlEditor.Result orderResult =
                ConfigYamlEditor.setNestedChildScalar(configPath, RECONSTRUCTION_BLOCK, "state_order", order);
        summary.add("");
        summary.add("state_order " + order + (orderResult.changed ? "  (changed)" : "  (unchanged)"));
        ConfigYamlEditor.setNestedChildScalar(
                configPath, RECONSTRUCTION_BLOCK, "lc_calibrated_at", "'" + calibratedAt + "'");
        ConfigYamlEditor.setNestedChildScalar(configPath, RECONSTRUCTION_BLOCK, "lc_extinction_ratio", extinction);
        if (metadataPath != null) {
            ConfigYamlEditor.setNestedChildScalar(
                    configPath,
                    RECONSTRUCTION_BLOCK,
                    "lc_calibration_file",
                    "'" + metadataPath.replace("'", "''") + "'");
        }
        summary.add("Micro-Manager Channel presets are no longer applied by these states.");
        summary.add("Written to " + configPath);
        return summary;
    }

    private static Map<String, Object> propertyWrite(String device, String property, double valueWaves) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("device", device);
        m.put("property", property);
        m.put("value", valueWaves);
        return m;
    }

    /** States in the palette that have no channel of the same id in the YAML. */
    @SuppressWarnings("unchecked")
    private static List<String> missingChannels(Path configPath, List<String> states) throws IOException {
        Map<String, Object> root;
        try (var in = Files.newInputStream(configPath)) {
            root = new org.yaml.snakeyaml.Yaml().load(in);
        }
        List<String> present = new ArrayList<>();
        Object modalities = root == null ? null : root.get("modalities");
        if (modalities instanceof Map<?, ?> mods && mods.get(MODALITY_KEY) instanceof Map<?, ?> mod) {
            if (mod.get("channels") instanceof List<?> channels) {
                for (Object c : channels) {
                    if (c instanceof Map<?, ?> cm && cm.get("id") != null) {
                        present.add(cm.get("id").toString());
                    }
                }
            }
        }
        List<String> missing = new ArrayList<>();
        for (String s : states) {
            if (!present.contains(s)) missing.add(s);
        }
        return missing;
    }

    private static String bandExplanation(double ratio) {
        if (Double.isNaN(ratio)) {
            return "The extinction ratio could not be measured, which usually means the search\n"
                    + "never found a dark state. Check the light path and that the field is clear.";
        }
        if (ratio >= ER_GOOD) {
            return "Good. This calibration is ready to use.";
        }
        if (ratio >= ER_ACCEPTABLE) {
            return "Acceptable, but lower than this instrument has reached before (267).\n"
                    + "Worth checking the field is clear and the optics are clean.";
        }
        return "Poor. The calibration has been recorded so you can inspect it, but reconstruction\n"
                + "from it will be noisy. Re-run on a cleaner blank field before acquiring.";
    }
}
