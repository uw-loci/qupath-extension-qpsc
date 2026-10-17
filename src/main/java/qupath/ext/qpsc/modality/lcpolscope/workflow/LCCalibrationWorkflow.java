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
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.DialogOwner;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.ui.UserErrors;
import qupath.ext.qpsc.utilities.ConfigYamlEditor;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.lib.gui.QuPathGUI;

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
    public record Params(String outputFolder, String strategy, Double blackLevel, Double exposureMs) {}

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
     * instrument: the value moved between 546 and 549 before the filter was read. It is a
     * Semrock FF01-549/15 on the transmission lamp (identified 2026-10-13), so 549 is right; the
     * LC adapter's own Wavelength property still says 546 and the server warns about the gap.
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

    /**
     * Where the calibration files go by default: {@code calibrations/lcpolscope} inside the open
     * project, or the projects folder preference when no project is open.
     *
     * <p>The first rig runs defaulted to the projects folder, which is one level ABOVE the
     * project, so the files landed beside the projects rather than in the one they belonged to.
     * A calibration is a record of the instrument on the day, and the project is where the data
     * from that day is; when there is a project it goes in there. The server creates the folder.
     */
    private static String defaultOutputFolder() {
        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui != null && gui.getProject() != null && gui.getProject().getPath() != null) {
            Path projectDir = gui.getProject().getPath().getParent();
            if (projectDir != null) {
                return projectDir.resolve("calibrations").resolve("lcpolscope").toString();
            }
        }
        return QPPreferenceDialog.getProjectsFolderProperty();
    }

    /**
     * The exposure the acquisition uses for the LC-PolScope states, from the first channel in
     * {@code modalities.lcpolscope.channels}; null when none is declared.
     *
     * <p>Offered as the default calibration exposure because it answers the question that
     * matters: whether the field has enough signal at the exposure the acquisition will run at.
     * The states all share one exposure by contract (see the YAML), so the first one stands for
     * all of them.
     */
    private static Double acquisitionExposureMs() {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        if (mgr == null) {
            String path = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (path == null || path.isBlank()) {
                return null;
            }
            mgr = MicroscopeConfigManager.getInstance(path);
        }
        try {
            var channels = mgr.getModalityChannels("lcpolscope");
            if (channels == null || channels.isEmpty()) {
                return null;
            }
            double exposure = channels.get(0).defaultExposureMs();
            return exposure > 0 ? exposure : null;
        } catch (RuntimeException e) {
            logger.debug("Could not read the LC-PolScope channel exposure from the YAML", e);
            return null;
        }
    }

    /** {@code reconstruction.black_level} from the microscope YAML, or null when unset or 0 (0 means
     *  "no clamp" to the reconstruction, not a measurement). */
    private static Double configuredBlackLevel() {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        if (mgr == null) {
            String path = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (path == null || path.isBlank()) {
                return null;
            }
            mgr = MicroscopeConfigManager.getInstance(path);
        }
        Double value = mgr.getDouble("modalities", MODALITY_KEY, "reconstruction", "black_level");
        return value == null || value <= 0 ? null : value;
    }

    /** {@code Oryx, 50 ms (request), 12-bit Mono12, 2448x2048, binning 1, gain 0}; whatever the server knew. */
    private static String describeCamera(JsonObject camera) {
        List<String> parts = new ArrayList<>();
        if (camera.has("device")) {
            parts.add(camera.get("device").getAsString());
        }
        if (camera.has("exposure_ms")) {
            String exposure = formatCounts(camera.get("exposure_ms").getAsDouble()) + " ms";
            if (camera.has("exposure_source")) {
                exposure += " (" + camera.get("exposure_source").getAsString() + ")";
            }
            parts.add(exposure);
        }
        if (camera.has("bit_depth")) {
            String depth = camera.get("bit_depth").getAsInt() + "-bit";
            if (camera.has("pixel_type")) {
                depth += " " + camera.get("pixel_type").getAsString();
            }
            parts.add(depth);
        }
        if (camera.has("width") && camera.has("height")) {
            parts.add(
                    camera.get("width").getAsInt() + "x" + camera.get("height").getAsInt());
        }
        if (camera.has("binning")) {
            parts.add("binning " + camera.get("binning").getAsString());
        }
        if (camera.has("gain")) {
            parts.add("gain " + camera.get("gain").getAsString());
        }
        return parts.isEmpty() ? "unknown (the camera could not be asked)" : String.join(", ", parts);
    }

    private static boolean hasNumber(JsonObject object, String key) {
        return object.has(key)
                && object.get(key).isJsonPrimitive()
                && object.get(key).getAsJsonPrimitive().isNumber();
    }

    /**
     * {@code extinction 410 (308 above black), swing contrast 1480 (36% of range), grid peak 3950 of
     * 4095 (96%) counts}. The swing contrast is what the palette was resolved from; the server warns
     * separately when it is under 1 percent of the camera's range.
     */
    private static String describeSignal(JsonObject signal) {
        List<String> parts = new ArrayList<>();
        if (hasNumber(signal, "extinction")) {
            String extinction =
                    "extinction " + formatCounts(signal.get("extinction").getAsDouble());
            if (hasNumber(signal, "extinction_above_black")) {
                extinction +=
                        " (" + formatCounts(signal.get("extinction_above_black").getAsDouble()) + " above black)";
            }
            parts.add(extinction);
        }
        if (hasNumber(signal, "swing_contrast")) {
            String contrast = "swing contrast "
                    + formatCounts(signal.get("swing_contrast").getAsDouble());
            if (hasNumber(signal, "swing_contrast_fraction")) {
                contrast += String.format(
                        " (%.1f%% of range)",
                        100 * signal.get("swing_contrast_fraction").getAsDouble());
            }
            parts.add(contrast);
        } else if (hasNumber(signal, "brightest_state")) {
            parts.add("brightest state "
                    + formatCounts(signal.get("brightest_state").getAsDouble()));
        }
        if (hasNumber(signal, "grid_peak")) {
            String peak = "grid peak " + formatCounts(signal.get("grid_peak").getAsDouble());
            if (hasNumber(signal, "full_scale")) {
                peak += " of " + formatCounts(signal.get("full_scale").getAsDouble());
                if (hasNumber(signal, "grid_peak_fraction")) {
                    peak += String.format(
                            " (%.0f%%)", 100 * signal.get("grid_peak_fraction").getAsDouble());
                }
            } else {
                peak += " (full scale unknown)";
            }
            parts.add(peak);
        }
        return parts.isEmpty() ? "not reported" : String.join(", ", parts) + " counts";
    }

    /**
     * {@code At the acquisition exposure (50 ms): swing contrast 148 counts; usable 17 to 1370 ms at
     * this illumination}. The palette does not depend on exposure, but the acquisition runs at the
     * YAML's, so the server scales the measured counts there. Absent when the YAML declares none.
     */
    private static Optional<String> describeAcquisitionExposure(JsonObject signal) {
        if (!hasNumber(signal, "acquisition_exposure_ms")) {
            return Optional.empty();
        }
        StringBuilder text = new StringBuilder("At the acquisition exposure (")
                .append(formatCounts(signal.get("acquisition_exposure_ms").getAsDouble()))
                .append(" ms)");
        if (hasNumber(signal, "projected_swing_contrast")) {
            text.append(": swing contrast ")
                    .append(formatCounts(signal.get("projected_swing_contrast").getAsDouble()))
                    .append(" counts");
        }
        if (signal.has("usable_exposure_ms") && signal.get("usable_exposure_ms").isJsonArray()) {
            JsonArray range = signal.getAsJsonArray("usable_exposure_ms");
            if (range.size() == 2 && !range.get(0).isJsonNull() && !range.get(1).isJsonNull()) {
                text.append("; usable ")
                        .append(formatCounts(range.get(0).getAsDouble()))
                        .append(" to ")
                        .append(formatCounts(range.get(1).getAsDouble()))
                        .append(" ms at this illumination");
            }
        }
        return Optional.of(text.toString());
    }

    private static String formatCounts(double counts) {
        return counts == Math.rint(counts) ? String.valueOf((long) counts) : String.valueOf(counts);
    }

    /**
     * A dialog body the operator can read, resize and copy. The first rig result dialog used a
     * plain Label: one long warning line stretched it to the width of the screen, nothing wrapped,
     * and the palette could not be selected to paste anywhere.
     */
    private static TextArea copyableBody(String text, int rows) {
        TextArea area = new TextArea(text);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(rows);
        area.setPrefColumnCount(72);
        area.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        return area;
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
                UserErrors.show(
                        logger,
                        "LC-PolScope Calibration",
                        "Could not reveal the microscope configuration file:\n\n" + file.getAbsolutePath()
                                + "\n\nCheck that the path in the QPSC preferences still exists.");
            }
        });

        TextField outputField = new TextField(defaultOutputFolder());
        outputField.setPrefColumnCount(32);

        ChoiceBox<String> strategyBox = new ChoiceBox<>();
        strategyBox.getItems().addAll("single_pass", "iterative");
        strategyBox.setValue("single_pass");

        // Prefilled from the YAML: the camera's own offset lives there (reconstruction.black_level)
        // and the first rig calibration ran with "0.0 (assumed)" while 103 sat in the file.
        Double configuredBlack = configuredBlackLevel();
        TextField blackField = new TextField(configuredBlack == null ? "" : formatCounts(configuredBlack));
        blackField.setPromptText("blank = measure a dark frame");
        blackField.setPrefColumnCount(10);

        // Prefilled with the acquisition's exposure: the first two rig runs snapped at whatever
        // Micro-Manager happened to be set to, and nothing recorded what that was.
        Double acquisitionExposure = acquisitionExposureMs();
        TextField exposureField = new TextField(acquisitionExposure == null ? "" : formatCounts(acquisitionExposure));
        exposureField.setPromptText("blank = current Micro-Manager exposure");
        exposureField.setPrefColumnCount(10);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10, 0, 0, 0));
        grid.addRow(0, new Label("Output folder:"), outputField);
        grid.addRow(1, new Label("Search:"), strategyBox);
        grid.addRow(2, new Label("Exposure (ms):"), exposureField);
        grid.addRow(3, new Label("Black level:"), blackField);

        Label notes = new Label("Search: one pass is usually enough. Iterative refines until the residual is small,\n"
                + "at roughly three times the exposures -- a trade, not an upgrade.\n\n"
                + "Exposure: set on the camera for the calibration and restored afterwards. Prefilled\n"
                + "with the acquisition's exposure from the YAML, so the run shows whether the field\n"
                + "has signal at the exposure acquisition will use. The result records the exposure,\n"
                + "bit depth and pixel format the frames were taken with, and a field that reaches\n"
                + "less than a tenth of the camera's range anywhere is refused as too dark.\n\n"
                + "Black level: the camera's offset in counts, from the microscope YAML\n"
                + "(reconstruction.black_level) when it is set there. It must be in the counts of the\n"
                + "camera's current pixel format. Blank measures a dark frame, which needs lamp\n"
                + "control this scope does not have; it then falls to 0, assumed, and the extinction\n"
                + "ratio is overstated by about 10 percent per 50 counts.");
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
            UserErrors.show(logger, "LC-PolScope Calibration", "An output folder is required.");
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
                UserErrors.show(logger, "LC-PolScope Calibration", "Black level must be a number, or blank.");
                return Optional.empty();
            }
        }
        Double exposure = null;
        String exposureText =
                exposureField.getText() == null ? "" : exposureField.getText().trim();
        if (!exposureText.isEmpty()) {
            try {
                exposure = Double.parseDouble(exposureText);
            } catch (NumberFormatException e) {
                logger.error("LC-PolScope calibration not started: exposure '{}' is not a number", exposureText);
                UserErrors.show(
                        logger, "LC-PolScope Calibration", "Exposure must be a number of milliseconds, or blank.");
                return Optional.empty();
            }
            if (!(exposure > 0)) {
                logger.error("LC-PolScope calibration not started: exposure {} ms is not positive", exposure);
                UserErrors.show(logger, "LC-PolScope Calibration", "Exposure must be greater than 0 ms.");
                return Optional.empty();
            }
        }
        return Optional.of(new Params(folder, strategyBox.getValue(), black, exposure));
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
                UserErrors.show(logger, "LC-PolScope Calibration", "Calibration failed: " + ex.getMessage());
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
                    params.exposureMs(),
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
                UserErrors.show(logger, "LC-PolScope Calibration", e.getMessage());
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
            UserErrors.show(logger, "LC-PolScope Calibration", error);
            return;
        }

        double ratio =
                result.has("extinction_ratio") ? result.get("extinction_ratio").getAsDouble() : Double.NaN;
        String assessment = result.has("assessment") ? result.get("assessment").getAsString() : "unknown";

        // The server marks a result "unreliable" when the frames cannot support the ratio: a
        // clipped state, a black level in the wrong units, a contrast resolved from noise, a camera
        // that is not linear. The 2026-10-16 rig run read "Good, ready to use" at ER 133.8 with
        // State1 at 99.9% saturation; the ratio's band is not the headline in that case.
        boolean unreliable = "unreliable".equals(assessment);
        List<String> issues = new ArrayList<>();
        if (result.has("reliability_issues") && result.get("reliability_issues").isJsonArray()) {
            result.getAsJsonArray("reliability_issues").forEach(e -> issues.add(e.getAsString()));
        }

        // A poor calibration is shown as a warning, not an error: it is recorded
        // and inspectable, and the operator decides whether to accept it.
        Alert.AlertType type =
                ratio >= ER_ACCEPTABLE && !unreliable ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING;
        Alert dialog = new Alert(type);
        dialog.setTitle("LC Calibration Complete");
        dialog.setHeaderText(String.format("Extinction ratio %.1f (%s)", ratio, assessment));

        List<String> lines = new ArrayList<>();
        lines.add(unreliable ? unreliableExplanation(issues) : bandExplanation(ratio));
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
        // The counts above mean nothing without these two lines: what the camera was set to,
        // and how much of its range the field actually used.
        if (result.has("camera") && result.get("camera").isJsonObject()) {
            lines.add("Camera: " + describeCamera(result.getAsJsonObject("camera")));
        }
        if (result.has("signal") && result.get("signal").isJsonObject()) {
            JsonObject signal = result.getAsJsonObject("signal");
            lines.add("Signal: " + describeSignal(signal));
            describeAcquisitionExposure(signal).ifPresent(lines::add);
            if (signal.has("histogram_path") && !signal.get("histogram_path").isJsonNull()) {
                lines.add("Histograms: " + signal.get("histogram_path").getAsString());
            }
        }
        if (result.has("warnings")) {
            JsonArray warnings = result.getAsJsonArray("warnings");
            if (warnings.size() > 0) {
                lines.add("");
                lines.add("Warnings:");
                warnings.forEach(w -> {
                    lines.add("    " + w.getAsString());
                    logger.warn("LC-PolScope calibration warning: {}", w.getAsString());
                });
            }
        }
        if (result.has("metadata_path")) {
            lines.add("");
            lines.add("Written to " + result.get("metadata_path").getAsString());
        }
        // The dialog is not a record. Everything it shows goes to the QuPath log too, so a
        // calibration can be compared with the next one, or attached to a bug report, without
        // anyone having transcribed it.
        logger.info("LC-PolScope calibration result:\n{}", String.join("\n", lines));
        if (unreliable) {
            logger.warn("LC-PolScope calibration is unreliable: {}", String.join("; ", issues));
        }
        if (!(ratio >= ER_ACCEPTABLE)) {
            logger.warn(
                    "LC-PolScope calibration extinction ratio {} is below {} ({})", ratio, ER_ACCEPTABLE, assessment);
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

        dialog.getDialogPane().setContent(copyableBody(String.join("\n", lines), Math.min(lines.size() + 1, 28)));
        dialog.setResizable(true);
        DialogOwner.own(dialog);
        if (dialog.showAndWait().orElse(close) != apply) {
            return;
        }
        if (unreliable || !(ratio >= ER_ACCEPTABLE)) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle(unreliable ? "Apply an unreliable calibration?" : "Apply a poor calibration?");
            confirm.setHeaderText(
                    unreliable
                            ? "The frames do not support this palette: " + String.join("; ", issues)
                            : String.format("Extinction ratio %.1f is below %.0f", ratio, ER_ACCEPTABLE));
            confirm.setContentText(
                    unreliable
                            ? "The warnings on the result say what to change. Apply it anyway?"
                            : "Reconstruction from this palette will be noisy. Apply it anyway?");
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
            UserErrors.show(
                    logger,
                    "LC-PolScope Calibration",
                    "No microscope configuration file is set in the QPSC preferences.");
            return;
        }
        // The exposure the acquisition will use, decided before anything is written: the
        // palette does not depend on it, but the calibration measured the range that works at
        // this illumination, so this is the moment to act on it.
        Optional<Double> exposure = chooseExposure(result);
        if (exposure == null) {
            logger.info("LC palette not applied: the exposure step was cancelled");
            return;
        }
        List<String> summary;
        try {
            summary = applyPaletteToConfig(Path.of(configPath), result);
            if (exposure.isPresent()) {
                summary.add(applyExposureToStates(Path.of(configPath), result, exposure.get()));
            }
        } catch (IOException | IllegalStateException e) {
            logger.error("Applying the LC palette to {} failed", configPath, e);
            UserErrors.show(
                    logger,
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
        summary.add("");
        summary.add("A background collected for the previous calibration no longer applies; the acquisition");
        summary.add("refuses it and reconstructs uncorrected. Collect a new one before acquiring.");
        logger.info("LC palette applied to {}:\n{}", configPath, String.join("\n", summary));
        Alert done = new Alert(Alert.AlertType.INFORMATION);
        done.setTitle("LC palette applied");
        done.setHeaderText("Acquisition now uses this calibration");
        done.getDialogPane().setContent(copyableBody(String.join("\n", summary), Math.min(summary.size() + 1, 24)));
        done.setResizable(true);
        ButtonType collect = new ButtonType("Collect background now...", ButtonBar.ButtonData.OK_DONE);
        ButtonType later = new ButtonType("Later", ButtonBar.ButtonData.CANCEL_CLOSE);
        done.getButtonTypes().setAll(collect, later);
        DialogOwner.own(done);
        if (done.showAndWait().orElse(later) == collect) {
            startBackgroundCollection();
        }
    }

    /**
     * Straight from Apply into background collection, which a new palette makes necessary. The
     * background must be specimen-free and slightly defocused, as OpenPolScope takes it, so the
     * operator is told to move first.
     */
    private static void startBackgroundCollection() {
        Alert prepare = new Alert(Alert.AlertType.CONFIRMATION);
        prepare.setTitle("LC-PolScope background");
        prepare.setHeaderText("Move to a specimen-free area, then defocus 10-20 um");
        prepare.getDialogPane()
                .setContent(copyableBody(
                        "The background is the five states on an empty field, taken slightly out of focus so\n"
                                + "dust does not imprint on every tile. Choose the LC-PolScope profile in the\n"
                                + "Background Collection dialog that opens next. Each state is averaged over 10 frames\n"
                                + "and recorded against this calibration.",
                        5));
        DialogOwner.own(prepare);
        if (prepare.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            qupath.ext.qpsc.controller.BackgroundCollectionWorkflow.run();
        }
    }

    /**
     * The exposure to write to every state, suggested from the calibration's usable range.
     *
     * @return {@code Optional.of(ms)} to write, {@code Optional.empty()} to keep the current
     *     exposure, or {@code null} if the operator cancelled the Apply
     */
    private static Optional<Double> chooseExposure(JsonObject result) {
        JsonObject signal =
                result.has("signal") && result.get("signal").isJsonObject() ? result.getAsJsonObject("signal") : null;
        Double current = signal != null && hasNumber(signal, "acquisition_exposure_ms")
                ? signal.get("acquisition_exposure_ms").getAsDouble()
                : null;
        Double low = null;
        Double high = null;
        if (signal != null
                && signal.has("usable_exposure_ms")
                && signal.get("usable_exposure_ms").isJsonArray()) {
            JsonArray range = signal.getAsJsonArray("usable_exposure_ms");
            if (range.size() == 2 && !range.get(0).isJsonNull() && !range.get(1).isJsonNull()) {
                low = range.get(0).getAsDouble();
                high = range.get(1).getAsDouble();
            }
        }
        Double suggested = suggestExposure(current, low, high);
        if (suggested == null || current == null) {
            return Optional.empty(); // nothing measured to suggest from; keep what the YAML has
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Exposure for the LC-PolScope states");
        dialog.setHeaderText(String.format(
                "Usable at this illumination: %s to %s ms. Now: %s ms.",
                formatCounts(round1(low)), formatCounts(round1(high)), formatCounts(current)));
        Spinner<Double> spinner = new Spinner<>(0.1, 60000.0, suggested, 1.0);
        spinner.setEditable(true);
        spinner.setPrefWidth(110);
        Label note = new Label("One exposure for all five states. The low end keeps the swing contrast above 1% of\n"
                + "the camera's range; the high end keeps the brightest state below 90%. Changing it later\n"
                + "means collecting the background again.");
        note.setStyle("-fx-font-size: 11px;");
        HBox row = new HBox(8, new Label("Exposure, all states (ms):"), spinner);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        dialog.getDialogPane().setContent(new VBox(10, row, note));
        ButtonType set = new ButtonType("Apply with this exposure", ButtonBar.ButtonData.OK_DONE);
        ButtonType keep = new ButtonType("Apply, keep " + formatCounts(current) + " ms", ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().getButtonTypes().setAll(set, keep, ButtonType.CANCEL);
        DialogOwner.own(dialog);
        ButtonType choice = dialog.showAndWait().orElse(ButtonType.CANCEL);
        if (choice == set) {
            // Take what is typed in the field, not only what the arrows last committed.
            Double value = spinner.getValue();
            try {
                Double typed = spinner.getValueFactory()
                        .getConverter()
                        .fromString(spinner.getEditor().getText());
                if (typed != null && typed > 0) {
                    value = typed;
                }
            } catch (RuntimeException e) {
                logger.debug("Exposure field text not a number; using the spinner value: {}", e.getMessage());
            }
            return Optional.of(value);
        }
        if (choice == keep) {
            return Optional.empty();
        }
        return null;
    }

    /**
     * The exposure to offer: the current one if it is inside the usable range, otherwise the
     * nearer usable end pulled 10% inside it. {@code null} when there is no range to go on, or the
     * range is empty (no exposure keeps the contrast up without clipping at this illumination).
     */
    static Double suggestExposure(Double current, Double low, Double high) {
        if (low == null || high == null || !(high > low) || !(low > 0)) {
            return null;
        }
        if (current != null && current >= low && current <= high) {
            return current;
        }
        double target = (current != null && current > high) ? high * 0.9 : low * 1.1;
        return round1(Math.min(Math.max(target, low), high));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** Writes one exposure to every palette state's channel; returns the summary line. */
    static String applyExposureToStates(Path configPath, JsonObject result, double exposureMs) throws IOException {
        JsonObject palette = result.getAsJsonObject("palette_waves");
        for (String state : palette.keySet()) {
            qupath.ext.qpsc.utilities.ConfigYamlEditor.setChannelExposureMs(
                    configPath, MODALITY_KEY, state, exposureMs);
        }
        return String.format("Exposure %s ms written to all %d states.", formatCounts(exposureMs), palette.size());
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
        // The adapter wavelength the palette was MEASURED at. The adapter converts each
        // retardance from waves to volts at its own Wavelength property, which Micro-Manager
        // restarts and OpenPolScope both reset, so it is written ahead of the retardances in every
        // state. It is the calibration's value, not the reconstruction's: a palette found at 546
        // must be replayed at 546, or every state lands 0.55 percent off what was measured.
        String wavelengthProperty = result.has("lc_wavelength_property")
                        && !result.get("lc_wavelength_property").isJsonNull()
                ? result.get("lc_wavelength_property").getAsString()
                : null;
        Double adapterWavelength = result.has("lc_adapter_wavelength_nm")
                        && !result.get("lc_adapter_wavelength_nm").isJsonNull()
                ? result.get("lc_adapter_wavelength_nm").getAsDouble()
                : null;

        List<String> states = new ArrayList<>(palette.keySet());
        List<String> missing = missingChannels(configPath, states);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("the palette names states with no channel under modalities." + MODALITY_KEY
                    + ".channels: " + missing + ". Nothing was written.");
        }

        List<String> summary = new ArrayList<>();
        List<String> comment = List.of(
                "LC calibration " + calibratedAt + ", extinction ratio " + extinction + ".",
                "Retardance in WAVES; the " + device + " adapter converts to volts at its Wavelength,",
                "which is set first so the palette replays as measured. Written by QPSC.");
        for (String state : states) {
            JsonArray pair = palette.getAsJsonArray(state);
            double lcaWaves = pair.get(0).getAsDouble();
            double lcbWaves = pair.get(1).getAsDouble();
            List<Map<String, Object>> items = new ArrayList<>();
            if (wavelengthProperty != null && adapterWavelength != null) {
                items.add(propertyWrite(device, wavelengthProperty, adapterWavelength));
            }
            items.add(propertyWrite(device, propertyNames.get("LCA"), lcaWaves));
            items.add(propertyWrite(device, propertyNames.get("LCB"), lcbWaves));
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
        if (wavelengthProperty != null && adapterWavelength != null) {
            summary.add(String.format(
                    "Each state first sets %s '%s' to %s nm, the wavelength the palette was measured at.",
                    device, wavelengthProperty, formatCounts(adapterWavelength)));
        } else {
            summary.add("The calibration did not report the adapter's wavelength; it is not written.");
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

    /** The headline when the server could not trust its own frames, whatever the ratio says. */
    private static String unreliableExplanation(List<String> issues) {
        return "Unreliable. The extinction ratio is computed, but the frames do not support it:\n"
                + (issues.isEmpty() ? "    see the warnings below." : "    " + String.join("; ", issues) + ".")
                + "\nFix what the warnings name and calibrate again before applying this palette.";
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
