package qupath.ext.qpsc.modality.lcpolscope.workflow;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.DialogOwner;
import qupath.ext.qpsc.ui.UserErrors;

/**
 * Measure LC voltage curves: runs the server's LCSWEEP on a clear field and shows, per crystal, the
 * retardance it really has against drive voltage beside the Meadowlark adapter's table, with the
 * applied palette marked on both.
 *
 * <p>Diagnostic, and changes nothing: the crystals, exposure and camera ROI are restored by the
 * server, and the measured curve is written as a file but never installed. Whether to replace the
 * adapter's table, or drive the crystals by voltage, is left to a person.
 */
public final class LCVoltageSweepWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(LCVoltageSweepWorkflow.class);
    private static final String TITLE = "Measure LC Voltage Curves";

    private LCVoltageSweepWorkflow() {}

    /** Asks for the crystals and folder, runs the sweep with a progress dialog, shows the result. */
    public static void run() {
        Platform.runLater(() -> prompt().ifPresent(LCVoltageSweepWorkflow::start));
    }

    private record Params(String axes, String outputFolder, Double fieldFraction) {}

    private static Optional<Params> prompt() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(TITLE);
        dialog.setHeaderText("Measure each liquid crystal's own voltage curve");
        DialogOwner.own(dialog);

        Label about = new Label("On a clear, specimen-free field, as for a calibration. Each crystal is swept "
                + "through its whole voltage range with the other parked: about a thousand frames per crystal. "
                + "At full frame that is roughly 20 minutes per crystal on the LC-PolScope (about 1 s a frame); "
                + "a smaller Measure on area in the calibration dialog makes it faster, and one crystal at a "
                + "time halves it. The exposure is chosen for the sweep. The crystals, exposure and camera "
                + "region are put back afterwards, and nothing in the configuration changes.");
        about.setWrapText(true);
        about.setPrefWidth(540);
        about.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);

        ChoiceBox<String> axes = new ChoiceBox<>();
        axes.getItems().addAll("Both crystals", "LC-A only", "LC-B only");
        axes.setValue("Both crystals");
        TextField folder = new TextField(LCCalibrationWorkflow.defaultOutputFolder());
        folder.setPrefColumnCount(36);
        int percent = LCCalibrationWorkflow.FIELD_PERCENT.get();
        Label field = new Label(
                percent >= 100
                        ? "Whole field (as the calibration measures)"
                        : "Central " + percent + "% of the field (as the calibration measures)");

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.addRow(0, new Label("Crystals:"), axes);
        grid.addRow(1, new Label("Output folder:"), folder);
        grid.addRow(2, new Label("Measured on:"), field);

        VBox content = new VBox(12, about, grid);
        content.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(content);
        // Without a width the pane sizes to the label's one-line width and nothing wraps.
        dialog.getDialogPane().setPrefWidth(600);
        dialog.setResizable(true);
        ButtonType go = new ButtonType("Sweep", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(go, ButtonType.CANCEL);

        Optional<ButtonType> answer = dialog.showAndWait();
        if (answer.isEmpty() || answer.get() != go) return Optional.empty();
        String out = folder.getText() == null ? "" : folder.getText().trim();
        if (out.isEmpty()) {
            UserErrors.show(logger, TITLE, "Choose an output folder for the sweep files.");
            return Optional.empty();
        }
        String axesArg =
                switch (axes.getValue()) {
                    case "LC-A only" -> "LCA";
                    case "LC-B only" -> "LCB";
                    default -> "LCA,LCB";
                };
        return Optional.of(new Params(axesArg, out, LCCalibrationWorkflow.fieldFraction(percent)));
    }

    private static void start(Params params) {
        Alert progress = new Alert(Alert.AlertType.INFORMATION);
        progress.setTitle(TITLE);
        progress.setHeaderText("Sweeping the liquid crystals");
        Label status = new Label("Starting...");
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(28, 28);
        Label hint = new Label(
                "Leave the field clear and the illumination steady. About 20 minutes per " + "crystal at full frame.");
        hint.setStyle("-fx-font-size: 11px;");
        for (Label l : List.of(status, hint)) {
            l.setWrapText(true);
            l.setPrefWidth(460);
            l.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        }
        progress.getDialogPane().setContent(new VBox(10, status, spinner, hint));
        progress.getDialogPane().setPrefWidth(520);
        progress.getButtonTypes().setAll(ButtonType.CLOSE);
        DialogOwner.own(progress);
        progress.show();

        CompletableFuture.runAsync(() -> {
            try {
                MicroscopeController controller = MicroscopeController.getInstance();
                if (!controller.isConnected()) {
                    logger.info("Not connected to the microscope server; connecting");
                    controller.userTriggeredConnect();
                }
                MicroscopeSocketClient client = controller.getSocketClient();
                String[] reply = new String[1];
                // Live streaming stops for the run, as for the calibration.
                controller.withLiveModeHandling(() -> reply[0] = client.runLcVoltageSweep(
                        params.outputFolder(),
                        "lcpolscope",
                        params.axes(),
                        null,
                        null,
                        params.fieldFraction(),
                        message -> Platform.runLater(() -> status.setText(message))));
                JsonObject summary = JsonParser.parseString(reply[0]).getAsJsonObject();
                Platform.runLater(() -> {
                    progress.close();
                    showResult(summary);
                });
            } catch (Exception e) {
                logger.error("LC voltage sweep error", e);
                Platform.runLater(() -> {
                    progress.close();
                    UserErrors.show(logger, TITLE, e.getMessage());
                });
            }
        });
    }

    private static void showResult(JsonObject summary) {
        boolean success = summary.has("success") && summary.get("success").getAsBoolean();
        List<String> lines = LCVoltageSweepPlots.summaryLines(summary);
        if (!success) {
            String error = summary.has("error") ? summary.get("error").getAsString() : String.join("\n", lines);
            logger.error("LC voltage sweep failed on the microscope server: {}", error);
            UserErrors.show(logger, TITLE, error.isBlank() ? "The sweep produced no curve." : error);
            return;
        }
        String file =
                summary.has("metadata_path") ? summary.get("metadata_path").getAsString() : null;
        String csv =
                summary.has("curve_csv_path") ? summary.get("curve_csv_path").getAsString() : null;
        StringBuilder text = new StringBuilder(String.join("\n", lines));
        text.append("\n\nFiles:\n  ").append(file).append("\n  ").append(csv);
        text.append("\nThe measured curve CSV is not in the adapter's format and is not installed anywhere.");
        logger.info("LC voltage sweep result:\n{}", text);

        TextArea body = new TextArea(text.toString());
        body.setEditable(false);
        body.setWrapText(true);
        body.setPrefRowCount(16);
        Tab summaryTab = new Tab("Summary", body);
        TabPane tabs = new TabPane(summaryTab);

        JsonObject record = readRecord(file);
        if (record != null) {
            tabs.getTabs().add(new Tab("LC-A", LCVoltageSweepPlots.axisPane(record, "LCA")));
            tabs.getTabs().add(new Tab("LC-B", LCVoltageSweepPlots.axisPane(record, "LCB")));
        } else {
            body.appendText("\n\nThe sweep file could not be read here, so there are no plots. It is on the "
                    + "microscope computer at the path above.");
        }
        tabs.getTabs().forEach(t -> t.setClosable(false));
        tabs.setPrefSize(720, 560);

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(TITLE);
        alert.setHeaderText("LC voltage curves measured");
        alert.getDialogPane().setContent(tabs);
        alert.setResizable(true);
        DialogOwner.own(alert);
        alert.show();
    }

    /** The full sweep record, or null when it is not readable from this computer. */
    static JsonObject readRecord(String path) {
        if (path == null) return null;
        try {
            Path p = Path.of(path);
            if (!Files.isRegularFile(p)) return null;
            return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
        } catch (Exception e) {
            logger.warn("Could not read the sweep file {}: {}", path, e.getMessage());
            return null;
        }
    }
}
