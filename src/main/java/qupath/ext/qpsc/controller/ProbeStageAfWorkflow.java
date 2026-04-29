package qupath.ext.qpsc.controller;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient.ProbeStageAfResult;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * Standalone "Re-probe Stage AF" workflow.
 *
 * <p>Re-runs the PRBSAFZ probe on a configured rig and rewrites only
 * the {@code stage.streaming_af.*} block of the active
 * {@code config_<scope>.yml}, preserving every other key. After saving,
 * the {@link MicroscopeConfigManager} singleton is reloaded so the
 * next streaming-AF run picks up the new values immediately.
 *
 * <p>Unlike the wizard step, this workflow assumes the YAML already
 * exists and the server is reachable. It is the recommended way to
 * fix up an OWS3-style stage that was auto-migrated with Prior-style
 * defaults that don't match the hardware.
 */
public class ProbeStageAfWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(ProbeStageAfWorkflow.class);

    private ProbeStageAfWorkflow() {}

    /**
     * Entry point invoked from the QPSC menu. Runs the probe in a
     * background thread, then opens a result dialog on the FX thread
     * with editable fields. On OK, rewrites the YAML and reloads.
     */
    public static void run() throws IOException {
        String yamlPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        if (yamlPath == null || yamlPath.isEmpty()) {
            Platform.runLater(() -> {
                Alert a = new Alert(Alert.AlertType.ERROR);
                a.setTitle("No microscope config");
                a.setHeaderText("No active microscope configuration");
                a.setContentText(
                        "Set the microscope config file in Preferences before running "
                                + "Re-probe Stage AF, or run the Setup Wizard first.");
                a.showAndWait();
            });
            return;
        }

        String host = QPPreferenceDialog.getMicroscopeServerHost();
        int port = QPPreferenceDialog.getMicroscopeServerPort();

        // Show a progress window while the probe runs.
        Stage progressStage = new Stage();
        progressStage.initModality(Modality.APPLICATION_MODAL);
        progressStage.setTitle("Re-probe Stage AF");
        progressStage.setAlwaysOnTop(true);
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(40, 40);
        Label status = new Label("Probing stage at " + host + ":" + port + "...");
        VBox box = new VBox(10, spinner, status);
        box.setPadding(new Insets(20));
        progressStage.setScene(new Scene(box));
        progressStage.show();

        Thread t = new Thread(() -> {
            ProbeStageAfResult result = null;
            String error = null;
            try (MicroscopeSocketClient client = new MicroscopeSocketClient(host, port)) {
                client.connect();
                result = client.probeStageAf(yamlPath, Double.NaN, Double.NaN);
            } catch (IOException ex) {
                logger.warn("Re-probe failed: {}", ex.toString());
                error = ex.getMessage();
            } catch (Exception ex) {
                logger.warn("Re-probe error", ex);
                error = ex.toString();
            }
            ProbeStageAfResult finalResult = result;
            String finalError = error;
            Platform.runLater(() -> {
                progressStage.close();
                if (finalError != null || finalResult == null) {
                    Alert a = new Alert(Alert.AlertType.ERROR);
                    a.setTitle("Probe failed");
                    a.setHeaderText("Could not run streaming-AF probe");
                    a.setContentText("Reason: "
                            + (finalError == null ? "no result" : finalError)
                            + "\n\nCheck the server is running and reachable.");
                    a.showAndWait();
                    return;
                }
                showResultDialog(yamlPath, finalResult);
            });
        }, "ProbeStageAfWorkflow");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Show the probe result with editable fields and OK/Cancel.
     * On OK, rewrite the YAML in place and reload the config manager.
     */
    private static void showResultDialog(String yamlPath, ProbeStageAfResult result) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Re-probe Stage AF -- Results");

        Label header = new Label(
                "The probe queried the focus stage's writable speed property and "
                        + "verified via a 1-um round-trip move. Edit any field below "
                        + "before saving. The values will be written to:\n  "
                        + yamlPath + "\nunder stage.streaming_af.*");
        header.setWrapText(true);

        TextArea diag = new TextArea();
        diag.setEditable(false);
        diag.setPrefRowCount(8);
        diag.setWrapText(true);
        diag.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        diag.setText(formatDiagnostics(result));

        CheckBox enabledCheck = new CheckBox("Streaming AF enabled");
        enabledCheck.setSelected(result.enabled);
        TextField speedPropertyField = new TextField(nullToBlank(result.speedProperty));
        TextField slowValueField = new TextField(nullToBlank(result.slowSpeedValue));
        TextField slowUmPerSField = new TextField(
                result.slowSpeedUmPerS == null ? "" : String.format("%.3f", result.slowSpeedUmPerS));
        TextField normalValueField = new TextField(nullToBlank(result.normalSpeedValue));

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.setPadding(new Insets(10, 0, 0, 0));
        int row = 0;
        grid.add(enabledCheck, 0, row++, 2, 1);
        grid.add(new Label("Speed property:"), 0, row);
        grid.add(speedPropertyField, 1, row++);
        grid.add(new Label("Slow speed value:"), 0, row);
        grid.add(slowValueField, 1, row++);
        grid.add(new Label("Slow speed (um/s):"), 0, row);
        grid.add(slowUmPerSField, 1, row++);
        grid.add(new Label("Normal speed value:"), 0, row);
        grid.add(normalValueField, 1, row++);

        Button saveBtn = new Button("Save to YAML");
        Button cancelBtn = new Button("Cancel");
        cancelBtn.setOnAction(e -> dialog.close());

        saveBtn.setOnAction(e -> {
            // Build the streaming_af map from edited fields.
            Map<String, Object> sa = new LinkedHashMap<>();
            sa.put("enabled", enabledCheck.isSelected());
            String sp = speedPropertyField.getText().trim();
            sa.put("speed_property", sp.isEmpty() ? null : sp);
            String sv = slowValueField.getText().trim();
            sa.put("slow_speed_value", sv.isEmpty() ? null : sv);
            String umsText = slowUmPerSField.getText().trim();
            Double umsValue = null;
            if (!umsText.isEmpty()) {
                try {
                    umsValue = Double.parseDouble(umsText);
                } catch (NumberFormatException nfe) {
                    Alert a = new Alert(Alert.AlertType.ERROR);
                    a.setHeaderText("Invalid slow speed (um/s)");
                    a.setContentText("Must be numeric or blank.");
                    a.showAndWait();
                    return;
                }
            }
            sa.put("slow_speed_um_per_s", umsValue);
            String nv = normalValueField.getText().trim();
            sa.put("normal_speed_value", nv.isEmpty() ? null : nv);

            try {
                writeStreamingAfBlock(Paths.get(yamlPath), sa);
                MicroscopeConfigManager.getInstance(yamlPath).reload(yamlPath);
                logger.info("Re-probe: saved stage.streaming_af to {}", yamlPath);
                dialog.close();
                Alert ok = new Alert(Alert.AlertType.INFORMATION);
                ok.setTitle("Saved");
                ok.setHeaderText("stage.streaming_af updated");
                ok.setContentText(
                        "Wrote new streaming-AF parameters to:\n  " + yamlPath
                                + "\n\nThe next streaming autofocus run will use these values.");
                ok.showAndWait();
            } catch (IOException ex) {
                logger.error("Failed to write streaming_af block to {}", yamlPath, ex);
                Alert err = new Alert(Alert.AlertType.ERROR);
                err.setHeaderText("Failed to save YAML");
                err.setContentText(ex.getMessage());
                err.showAndWait();
            }
        });

        HBox buttons = new HBox(8, saveBtn, cancelBtn);
        buttons.setPadding(new Insets(10, 0, 0, 0));

        VBox root = new VBox(10, header, diag, new Label("Edit values to save:"), grid, buttons);
        root.setPadding(new Insets(15));
        dialog.setScene(new Scene(root, 600, 560));
        dialog.show();
    }

    private static String formatDiagnostics(ProbeStageAfResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Focus device:    ").append(nullToDash(r.focusDevice)).append('\n');
        sb.append("Speed property:  ").append(nullToDash(r.speedProperty)).append('\n');
        sb.append("Current value:   ").append(nullToDash(r.currentValue)).append('\n');
        sb.append("Classification:  ").append(nullToDash(r.classification)).append('\n');
        sb.append("Allowed values:  ").append(formatList(r.allowedValues)).append('\n');
        sb.append("Recommended slow: ").append(nullToDash(r.slowSpeedValue));
        if (r.slowSpeedUmPerS != null) {
            sb.append("  (").append(String.format("%.2f um/s", r.slowSpeedUmPerS)).append(')');
        }
        sb.append('\n');
        if (r.slowSpeedUmPerSMeasured != null) {
            sb.append("Measured slow:   ").append(String.format("%.2f um/s", r.slowSpeedUmPerSMeasured)).append('\n');
        }
        sb.append("Recommended normal: ").append(nullToDash(r.normalSpeedValue)).append('\n');
        sb.append("Verify:          ").append(nullToDash(r.verifyNote)).append('\n');
        sb.append("Viability:       ").append(nullToDash(r.viabilityReason)).append('\n');
        if (r.warnings != null && !r.warnings.isEmpty()) {
            sb.append("Warnings:\n");
            for (String w : r.warnings) sb.append("  - ").append(w).append('\n');
        }
        return sb.toString();
    }

    /**
     * Read the YAML, replace stage.streaming_af with the supplied map,
     * and write the file back. All other content is preserved (within
     * the limits of SnakeYAML's load-then-dump round-trip; comments are
     * lost but key order and values are preserved for our config style).
     */
    @SuppressWarnings("unchecked")
    private static void writeStreamingAfBlock(Path yamlPath, Map<String, Object> streamingAf) throws IOException {
        if (!Files.exists(yamlPath)) {
            throw new IOException("Config file does not exist: " + yamlPath);
        }

        Yaml yaml = new Yaml();
        Map<String, Object> doc;
        try (InputStream in = new FileInputStream(yamlPath.toFile())) {
            Object loaded = yaml.load(in);
            if (!(loaded instanceof Map)) {
                throw new IOException("Config root is not a map: " + yamlPath);
            }
            doc = new LinkedHashMap<>((Map<String, Object>) loaded);
        }

        Object stageObj = doc.get("stage");
        Map<String, Object> stage;
        if (stageObj instanceof Map) {
            stage = new LinkedHashMap<>((Map<String, Object>) stageObj);
        } else {
            stage = new LinkedHashMap<>();
        }
        stage.put("streaming_af", streamingAf);
        doc.put("stage", stage);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        options.setIndent(2);
        options.setPrettyFlow(true);

        Yaml writer = new Yaml(options);
        Files.writeString(yamlPath, writer.dump(doc));
    }

    private static String nullToDash(Object o) { return o == null ? "-" : o.toString(); }
    private static String nullToBlank(Object o) { return o == null ? "" : o.toString(); }
    private static String formatList(List<String> xs) {
        if (xs == null || xs.isEmpty()) return "(none)";
        if (xs.size() <= 10) return String.join(", ", xs);
        return String.join(", ", xs.subList(0, 10)) + ", ... (" + xs.size() + " total)";
    }
}
