package qupath.ext.qpsc.modality.lcpolscope.workflow;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
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

    private LCCalibrationWorkflow() {}

    public static void run() {
        Platform.runLater(() -> promptForParameters().ifPresent(LCCalibrationWorkflow::startWithProgress));
    }

    // ------------------------------------------------------------------
    // Parameters
    // ------------------------------------------------------------------

    /** What the operator chooses; everything else comes from the microscope YAML. */
    public record Params(String outputFolder, String strategy, Double blackLevel) {}

    private static Optional<Params> promptForParameters() {
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle("Calibrate Liquid Crystals");
        dialog.setHeaderText("LC-PolScope calibration");

        Label intro = new Label("Move to a clear, specimen-free area before running this. The calibration measures\n"
                + "how dark the extinction state can be made, so anything birefringent in the field\n"
                + "will make the result worse.\n\n"
                + "Swing, scheme and wavelength are taken from the microscope configuration, so the\n"
                + "calibration cannot disagree with the acquisition that uses it.");
        intro.setStyle("-fx-font-size: 11px;");

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

        VBox content = new VBox(8, intro, grid, notes);
        dialog.getDialogPane().setContent(content);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return Optional.empty();
        }
        String folder =
                outputField.getText() == null ? "" : outputField.getText().trim();
        if (folder.isEmpty()) {
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
        progress.setTitle("Calibration In Progress");
        progress.setHeaderText("Calibrating liquid crystals");

        Label status = new Label("Starting...");
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(28, 28);
        Label hint = new Label("A few hundred exposures. Leave the field clear and the illumination steady.");
        hint.setStyle("-fx-font-size: 11px;");

        progress.getDialogPane().setContent(new VBox(10, status, spinner, hint));
        progress.getButtonTypes().setAll(ButtonType.CANCEL);
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
        dialog.setTitle("Calibration Complete");
        dialog.setHeaderText(String.format("Extinction ratio %.1f (%s)", ratio, assessment));

        List<String> lines = new ArrayList<>();
        lines.add(bandExplanation(ratio));
        lines.add("");
        lines.add("Palette (LC-A, LC-B in waves):");
        if (result.has("palette")) {
            JsonObject palette = result.getAsJsonObject("palette");
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

        Label body = new Label(String.join("\n", lines));
        body.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        dialog.getDialogPane().setContent(body);
        dialog.showAndWait();
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
