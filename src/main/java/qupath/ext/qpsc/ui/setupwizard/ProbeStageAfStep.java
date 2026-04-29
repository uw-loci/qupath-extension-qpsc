package qupath.ext.qpsc.ui.setupwizard;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient.ProbeStageAfResult;

/**
 * Wizard step 5b: probe the focus stage's streaming-AF speed parameters.
 *
 * <p>Sends PRBSAFZ to the running server, which discovers the focus
 * device's writable speed property, parses its allowed-values
 * nameplate, time-verifies via a 1-um round-trip, and returns
 * recommended values for {@code stage.streaming_af.*} in the YAML.
 *
 * <p>The server must already be running and connectable on the host/
 * port from preferences. If it isn't, the user can click "Skip" --
 * defaults are written by auto-migration on first config load, and
 * they can re-probe later via the standalone workflow.
 */
public class ProbeStageAfStep implements WizardStep {

    private static final Logger logger = LoggerFactory.getLogger(ProbeStageAfStep.class);

    private final WizardData data;

    private final VBox content;
    private final Button probeButton;
    private final Button skipButton;
    private final ProgressIndicator spinner;
    private final Label statusLabel;
    private final TextArea resultsArea;

    // Editable fields (auto-populated by probe; user can override).
    private final CheckBox enabledCheck;
    private final TextField speedPropertyField;
    private final TextField slowValueField;
    private final TextField slowUmPerSField;
    private final TextField normalValueField;

    public ProbeStageAfStep(WizardData data, ResourceCatalog catalog) {
        this.data = data;

        content = new VBox(12);
        content.setPadding(new Insets(15));

        Label intro = new Label(
                "Streaming autofocus drives the focus stage at a slow velocity while "
                        + "streaming camera frames, then parabolic-fits the peak. The slow "
                        + "value is hardware-specific; this step probes your stage to find "
                        + "the right values automatically.\n\n"
                        + "Click \"Probe Stage\" to query the stage (the server must be "
                        + "running). You can edit the recommended values before saving, or "
                        + "skip this step and re-probe later via Extensions > QPSC > "
                        + "Re-probe Stage AF.");
        intro.setWrapText(true);

        // Probe button + status
        probeButton = new Button("Probe Stage");
        probeButton.setOnAction(e -> runProbe());
        skipButton = new Button("Skip (use defaults)");
        skipButton.setOnAction(e -> applyDefaultsAndContinue());
        spinner = new ProgressIndicator();
        spinner.setPrefSize(20, 20);
        spinner.setVisible(false);
        statusLabel = new Label();
        statusLabel.setWrapText(true);
        HBox actionRow = new HBox(8, probeButton, skipButton, spinner, statusLabel);

        // Results read-out (read-only, populated by probe)
        resultsArea = new TextArea();
        resultsArea.setEditable(false);
        resultsArea.setPrefRowCount(6);
        resultsArea.setWrapText(true);
        resultsArea.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");

        // Editable fields
        enabledCheck = new CheckBox("Streaming AF enabled");
        enabledCheck.setSelected(false);
        speedPropertyField = new TextField();
        speedPropertyField.setPromptText("e.g., MaxSpeed, Speed (blank if no writable property)");
        slowValueField = new TextField();
        slowValueField.setPromptText("Stage value during slow sweep, e.g., '1' or '0.50mm/sec'");
        slowUmPerSField = new TextField();
        slowUmPerSField.setPromptText("Actual velocity in um/s, e.g., 11.5");
        normalValueField = new TextField();
        normalValueField.setPromptText("Stage value to restore after sweep, e.g., '100' or '2.50mm/sec'");

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.setPadding(new Insets(8, 0, 0, 0));
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

        content.getChildren().addAll(
                intro,
                actionRow,
                new Label("Probe results:"),
                resultsArea,
                new Label("Editable values (saved to config):"),
                grid);
    }

    private void runProbe() {
        probeButton.setDisable(true);
        skipButton.setDisable(true);
        spinner.setVisible(true);
        statusLabel.setText("Probing stage...");
        resultsArea.clear();

        String host = QPPreferenceDialog.getMicroscopeServerHost();
        int port = QPPreferenceDialog.getMicroscopeServerPort();
        // Best-effort yaml path: if the user already has a config preference set it works,
        // otherwise the server tolerates a missing/blank yaml -- it just skips Z-limit
        // safety for the round-trip move.
        String yamlPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();

        AtomicReference<ProbeStageAfResult> resultRef = new AtomicReference<>();
        AtomicReference<String> errorRef = new AtomicReference<>();

        Thread t = new Thread(() -> {
            try (MicroscopeSocketClient client = new MicroscopeSocketClient(host, port)) {
                client.connect();
                ProbeStageAfResult result = client.probeStageAf(yamlPath, Double.NaN, Double.NaN);
                resultRef.set(result);
            } catch (IOException ex) {
                logger.warn("PRBSAFZ probe failed: {}", ex.toString());
                errorRef.set(ex.getMessage());
            } catch (Exception ex) {
                logger.warn("PRBSAFZ probe error", ex);
                errorRef.set(ex.toString());
            } finally {
                Platform.runLater(this::onProbeFinished);
            }
        }, "ProbeStageAf");
        t.setDaemon(true);
        t.start();

        // Stash refs for the runLater callback.
        this.lastProbeResult = resultRef;
        this.lastProbeError = errorRef;
    }

    private AtomicReference<ProbeStageAfResult> lastProbeResult;
    private AtomicReference<String> lastProbeError;

    private void onProbeFinished() {
        spinner.setVisible(false);
        probeButton.setDisable(false);
        skipButton.setDisable(false);

        String error = lastProbeError == null ? null : lastProbeError.get();
        ProbeStageAfResult result = lastProbeResult == null ? null : lastProbeResult.get();

        if (error != null) {
            statusLabel.setText("Probe failed: " + error);
            statusLabel.setStyle("-fx-text-fill: red;");
            resultsArea.setText(
                    "Probe could not reach the server.\n\n"
                            + "Check that the microscope server is running at "
                            + QPPreferenceDialog.getMicroscopeServerHost() + ":"
                            + QPPreferenceDialog.getMicroscopeServerPort() + ".\n\n"
                            + "You can also click \"Skip\" -- safe defaults will be written "
                            + "and you can re-probe later from the menu.");
            return;
        }
        if (result == null) {
            statusLabel.setText("Probe returned no result");
            statusLabel.setStyle("-fx-text-fill: red;");
            return;
        }

        statusLabel.setStyle("-fx-text-fill: green;");
        statusLabel.setText(result.enabled
                ? "Probe complete -- streaming AF is viable on this stage."
                : "Probe complete -- streaming AF will fall back to Brent on this stage.");

        StringBuilder sb = new StringBuilder();
        sb.append("Focus device:    ").append(nullToDash(result.focusDevice)).append('\n');
        sb.append("Speed property:  ").append(nullToDash(result.speedProperty)).append('\n');
        sb.append("Current value:   ").append(nullToDash(result.currentValue)).append('\n');
        sb.append("Classification:  ").append(nullToDash(result.classification)).append('\n');
        sb.append("Allowed values:  ").append(formatList(result.allowedValues)).append('\n');
        sb.append("Recommended slow: ").append(nullToDash(result.slowSpeedValue))
                .append("  (").append(formatUmS(result.slowSpeedUmPerS)).append(")\n");
        if (result.slowSpeedUmPerSMeasured != null) {
            sb.append("Measured slow:   ").append(formatUmS(result.slowSpeedUmPerSMeasured)).append('\n');
        }
        sb.append("Recommended normal: ").append(nullToDash(result.normalSpeedValue)).append('\n');
        sb.append("Verify:          ").append(nullToDash(result.verifyNote)).append('\n');
        sb.append("Viability:       ").append(nullToDash(result.viabilityReason)).append('\n');
        if (result.warnings != null && !result.warnings.isEmpty()) {
            sb.append("Warnings:\n");
            for (String w : result.warnings) sb.append("  - ").append(w).append('\n');
        }
        resultsArea.setText(sb.toString());

        // Auto-fill the editable fields with the probe result.
        enabledCheck.setSelected(result.enabled);
        speedPropertyField.setText(nullToBlank(result.speedProperty));
        slowValueField.setText(nullToBlank(result.slowSpeedValue));
        slowUmPerSField.setText(result.slowSpeedUmPerS == null ? ""
                : String.format("%.3f", result.slowSpeedUmPerS));
        normalValueField.setText(nullToBlank(result.normalSpeedValue));
    }

    private void applyDefaultsAndContinue() {
        // Mirrors the legacy hardcoded values (Prior 1-100 percent scale).
        // Auto-migration in MicroscopeConfigManager would write the same
        // values; doing it here makes the YAML correct from the first
        // wizard write so re-runs see populated fields.
        enabledCheck.setSelected(true);
        speedPropertyField.setText("");
        slowValueField.setText("1");
        slowUmPerSField.setText("11.5");
        normalValueField.setText("100");
        statusLabel.setStyle("-fx-text-fill: gray;");
        statusLabel.setText("Using legacy defaults (Prior-style 1-100 percent). "
                + "Run \"Re-probe Stage AF\" later to verify.");
        resultsArea.setText("Skipped probe -- legacy defaults applied.");
    }

    private static String nullToDash(Object o) { return o == null ? "-" : o.toString(); }
    private static String nullToBlank(Object o) { return o == null ? "" : o.toString(); }
    private static String formatUmS(Double v) {
        return v == null ? "n/a" : String.format("%.2f um/s", v);
    }
    private static String formatList(List<String> xs) {
        if (xs == null || xs.isEmpty()) return "(none)";
        if (xs.size() <= 10) return String.join(", ", xs);
        return String.join(", ", xs.subList(0, 10)) + ", ... (" + xs.size() + " total)";
    }

    @Override
    public String getTitle() { return "Streaming AF"; }

    @Override
    public String getDescription() {
        return "Probe the focus stage's slow-speed property for streaming autofocus.";
    }

    @Override
    public Node getContent() { return content; }

    @Override
    public String validate() {
        // Step is optional; user may skip or accept defaults. We only
        // reject obviously-malformed numeric input in slow_um_per_s.
        String umsText = slowUmPerSField.getText().trim();
        if (!umsText.isEmpty()) {
            try {
                double v = Double.parseDouble(umsText);
                if (v < 0) return "Slow speed (um/s) must be non-negative.";
            } catch (NumberFormatException e) {
                return "Slow speed (um/s) must be a number.";
            }
        }
        return null;
    }

    @Override
    public void onEnter() {
        // Repopulate from WizardData on Back navigation. Empty/null
        // WizardData fields leave the UI at its previous values.
        if (data.streamingAfEnabled != null) {
            enabledCheck.setSelected(Boolean.TRUE.equals(data.streamingAfEnabled));
        }
        if (data.streamingAfSpeedProperty != null) {
            speedPropertyField.setText(data.streamingAfSpeedProperty);
        }
        if (data.streamingAfSlowSpeedValue != null) {
            slowValueField.setText(data.streamingAfSlowSpeedValue);
        }
        if (data.streamingAfSlowSpeedUmPerS != null) {
            slowUmPerSField.setText(String.format("%.3f", data.streamingAfSlowSpeedUmPerS));
        }
        if (data.streamingAfNormalSpeedValue != null) {
            normalValueField.setText(data.streamingAfNormalSpeedValue);
        }
    }

    @Override
    public void onLeave() {
        data.streamingAfEnabled = enabledCheck.isSelected();
        String sp = speedPropertyField.getText().trim();
        data.streamingAfSpeedProperty = sp.isEmpty() ? null : sp;
        String sv = slowValueField.getText().trim();
        data.streamingAfSlowSpeedValue = sv.isEmpty() ? null : sv;
        String nv = normalValueField.getText().trim();
        data.streamingAfNormalSpeedValue = nv.isEmpty() ? null : nv;
        String ums = slowUmPerSField.getText().trim();
        if (ums.isEmpty()) {
            data.streamingAfSlowSpeedUmPerS = null;
        } else {
            try {
                data.streamingAfSlowSpeedUmPerS = Double.parseDouble(ums);
            } catch (NumberFormatException e) {
                data.streamingAfSlowSpeedUmPerS = null;
            }
        }
        logger.debug(
                "ProbeStageAfStep: saved enabled={}, prop={}, slow_value={}, slow_ums={}, normal_value={}",
                data.streamingAfEnabled,
                data.streamingAfSpeedProperty,
                data.streamingAfSlowSpeedValue,
                data.streamingAfSlowSpeedUmPerS,
                data.streamingAfNormalSpeedValue);
    }
}
