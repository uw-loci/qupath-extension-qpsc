package qupath.ext.qpsc.modality.lcpolscope.ui;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.modality.Channel;
import qupath.ext.qpsc.modality.LiveCameraHost;
import qupath.ext.qpsc.modality.lcpolscope.LCPolScopeModalityHandler;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.ThemeColors;

/**
 * The LC-PolScope controls of the Live Viewer camera tab, built into the panel through
 * {@link LiveCameraHost}.
 */
public final class LCLiveCameraControls {
    private static final Logger logger = LoggerFactory.getLogger(LCLiveCameraControls.class);

    private final LiveCameraHost host;

    public LCLiveCameraControls(LiveCameraHost host) {
        this.host = host;
    }

    /**
     * LC-PolScope: brightfield optics with polarization states for channels. The five states share
     * ONE exposure (per-state differences bias retardance and orientation) and one lamp, so this is
     * a state selector for looking at each state live, one exposure for all of them, a measurement
     * of every state at that exposure against the camera's range (with an exposure suggestion for
     * a target brightness), and Save to Profile writing the one value to all five states.
     */
    public void build(String modality) {
        Node profileSelector = host.profileSelector(modality);
        if (profileSelector != null) {
            host.content().getChildren().addAll(profileSelector, new Separator());
        }
        java.util.List<Channel> states = java.util.List.of();
        try {
            states = host.configManager().getModalityChannels(modality);
        } catch (Exception e) {
            logger.debug("Could not load LC-PolScope states for '{}': {}", modality, e.getMessage());
        }
        if (states.isEmpty()) {
            host.buildGenericContent(modality);
            return;
        }
        final java.util.List<String> stateIds = states.stream().map(Channel::id).toList();
        final String profile = host.activeProfile(modality);

        // One exposure for every state, starting from the longest configured (they should agree).
        double configured =
                states.stream().mapToDouble(Channel::defaultExposureMs).max().orElse(50.0);
        boolean unequal = states.stream()
                        .mapToDouble(Channel::defaultExposureMs)
                        .distinct()
                        .count()
                > 1;
        Label expLabel = new Label("Exposure, all states (ms):");
        expLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: bold;");
        TextField expField = new TextField(fmtNum(configured));
        expField.setPrefWidth(70);
        expField.setTooltip(new Tooltip("One exposure for all five states. Per-state differences bias "
                + "retardance and orientation with no visible symptom, so there is no per-state value."));
        java.util.concurrent.atomic.AtomicReference<String> liveState =
                new java.util.concurrent.atomic.AtomicReference<>();
        Runnable commitExposure = () -> {
            Double ms = parsePositive(expField.getText());
            if (ms == null) {
                host.setStatus("Exposure must be a positive number", ThemeColors.ERROR);
                return;
            }
            Thread t = new Thread(
                    () -> {
                        try {
                            MicroscopeController.getInstance()
                                    .getSocketClient()
                                    .setExposures(new float[] {ms.floatValue()});
                            Platform.runLater(() -> {
                                host.setStatus(
                                        "Exposure " + fmtNum(ms) + " ms (live; Save to Profile to keep)",
                                        ThemeColors.SUCCESS);
                            });
                        } catch (Exception ex) {
                            logger.warn("LC-PolScope exposure write failed: {}", ex.getMessage());
                        }
                    },
                    "LCPS-Exposure");
            t.setDaemon(true);
            t.start();
        };
        expField.setOnAction(e -> commitExposure.run());
        expField.focusedProperty().addListener((obs, was, isNow) -> {
            if (was && !isNow) commitExposure.run();
        });
        HBox expRow = new HBox(4, expLabel, expField);
        expRow.setAlignment(Pos.CENTER_LEFT);
        host.content().getChildren().add(expRow);
        if (unequal) {
            Label warn = new Label(
                    "The configured states have different exposures; Save to Profile sets them all " + "to this one.");
            warn.setWrapText(true);
            warn.setStyle("-fx-font-size: 9px; -fx-text-fill: " + ThemeColors.WARNING + ";");
            host.content().getChildren().add(warn);
        }

        // State selector: look at any one state live, at the shared exposure.
        Label viewLabel = new Label("View state:");
        viewLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: bold;");
        ToggleGroup group = new ToggleGroup();
        javafx.scene.layout.FlowPane stateRow = new javafx.scene.layout.FlowPane(6, 2);
        for (Channel ch : states) {
            boolean extinction = LCPolScopeModalityHandler.EXTINCTION_CHANNEL_ID.equals(ch.id());
            RadioButton rb = new RadioButton(ch.id() + (extinction ? " (ext.)" : ""));
            rb.setStyle("-fx-font-size: 10px;");
            rb.setToggleGroup(group);
            rb.setTooltip(new Tooltip(
                    extinction
                            ? "Extinction: near black by design. Not a state to focus or set exposure on."
                            : ch.displayName() + ": a swing state. Focus and judge brightness on these."));
            rb.setOnAction(e -> {
                liveState.set(ch.id());
                host.applyChannelInBackground(profile, ch.id(), parsePositive(expField.getText()));
            });
            stateRow.getChildren().add(rb);
        }
        host.content().getChildren().addAll(new Separator(), viewLabel, stateRow);

        // Only a lamp this modality declares. The server reports whichever source is
        // active, and an LC-PolScope modality without one (the real rig has nothing
        // controllable) would otherwise show the previous profile's, e.g. the epi LED.
        Node illumControl = host.declaredIlluminationControl(modality);
        if (illumControl != null) {
            host.content().getChildren().addAll(new Separator(), illumControl);
        }

        // Measure every state at the shared exposure; suggest an exposure for a target.
        Label targetLabel = new Label("Target, % of full scale:");
        targetLabel.setStyle("-fx-font-size: 10px;");
        TextField targetField = new TextField("75");
        targetField.setPrefWidth(40);
        targetField.setTooltip(new Tooltip("Where the brightest swing state's 99.9th percentile should land. "
                + "75% leaves room for brighter parts of the slide; the calibration warns above 90%."));
        Button measureBtn = new Button("Measure all states");
        measureBtn.setStyle("-fx-font-size: 10px;");
        measureBtn.setTooltip(new Tooltip("Applies each state at this exposure, snaps it, and shows how bright "
                + "it is against the camera's range. Do it on the area you will image, or on clear glass."));
        Button useBtn = new Button("Use suggested");
        useBtn.setStyle("-fx-font-size: 10px;");
        useBtn.setDisable(true);
        VBox resultBox = new VBox(2);
        java.util.concurrent.atomic.AtomicReference<Double> suggestion =
                new java.util.concurrent.atomic.AtomicReference<>();
        measureBtn.setOnAction(e -> {
            Double ms = parsePositive(expField.getText());
            Double targetPct = parsePositive(targetField.getText());
            if (ms == null || targetPct == null || targetPct >= 100) {
                host.setStatus("Exposure and target (under 100%) must be positive numbers", ThemeColors.ERROR);
                return;
            }
            if (profile == null) {
                host.setStatus("No acquisition profile for " + modality, ThemeColors.ERROR);
                return;
            }
            measureBtn.setDisable(true);
            useBtn.setDisable(true);
            host.setStatus("Measuring " + stateIds.size() + " states at " + fmtNum(ms) + " ms...", ThemeColors.MUTED);
            Thread t = new Thread(
                    () -> {
                        java.util.concurrent.atomic.AtomicReference<com.google.gson.JsonObject> report =
                                new java.util.concurrent.atomic.AtomicReference<>();
                        String failure = null;
                        try {
                            MicroscopeController mc = MicroscopeController.getInstance();
                            mc.withLiveModeHandling(() -> {
                                report.set(mc.getSocketClient()
                                        .measureLcStates(profile, ms, targetPct / 100.0)
                                        .orElse(null));
                                // The measurement leaves its last state applied; put back the one
                                // being viewed, at the shared exposure.
                                String viewed = liveState.get();
                                if (viewed != null) {
                                    mc.getSocketClient().applyChannel(profile, viewed);
                                    mc.getSocketClient().setExposures(new float[] {ms.floatValue()});
                                }
                            });
                        } catch (Exception ex) {
                            failure = ex.getMessage();
                            logger.error("LC-PolScope state measurement failed: {}", ex.getMessage(), ex);
                        }
                        final String failed = failure;
                        final com.google.gson.JsonObject r = report.get();
                        Platform.runLater(() -> {
                            measureBtn.setDisable(false);
                            resultBox.getChildren().setAll(renderStateSignal(r, failed));
                            suggestion.set(failed == null ? LCStateSignal.suggestedExposure(r) : null);
                            useBtn.setDisable(suggestion.get() == null);
                            host.setStatus(
                                    failed == null ? "Measured" : "Measurement failed: " + failed,
                                    failed == null ? ThemeColors.SUCCESS : ThemeColors.ERROR);
                        });
                    },
                    "LCPS-Measure");
            t.setDaemon(true);
            t.start();
        });
        useBtn.setOnAction(e -> {
            Double s = suggestion.get();
            if (s != null) {
                expField.setText(fmtNum(s));
                commitExposure.run();
                useBtn.setDisable(true);
                resultBox
                        .getChildren()
                        .add(mutedNote("Exposure set to " + fmtNum(s) + " ms. Measure again to check, "
                                + "then Save to Profile."));
            }
        });
        HBox targetRow = new HBox(4, targetLabel, targetField);
        targetRow.setAlignment(Pos.CENTER_LEFT);
        HBox measureRow = new HBox(6, measureBtn, useBtn);
        measureRow.setAlignment(Pos.CENTER_LEFT);
        host.content().getChildren().addAll(new Separator(), targetRow, measureRow, resultBox);

        // Save the one exposure to all five states.
        Button saveBtn = new Button("Save to Profile");
        saveBtn.setStyle("-fx-font-size: 10px;");
        saveBtn.setTooltip(new Tooltip("Writes this exposure to every state under modalities." + modality
                + ".channels, for every objective of this microscope. A background collected at another "
                + "exposure is flagged at acquisition, so collect it again afterwards."));
        saveBtn.setOnAction(e -> saveExposure(modality, stateIds, parsePositive(expField.getText())));
        HBox saveRow = new HBox(6, saveBtn, host.collectBackgroundButton(modality));
        saveRow.setAlignment(Pos.CENTER_LEFT);
        host.content()
                .getChildren()
                .addAll(new Separator(), saveRow, host.saveToProfileCaption("this exposure, for all five states,"));
    }

    private static VBox renderStateSignal(com.google.gson.JsonObject report, String failure) {
        VBox box = new VBox(2);
        if (failure != null) {
            return box;
        }
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(1);
        String[] headers = {"State", "Mean", "Bright (p99.9)", "% of full"};
        for (int c = 0; c < headers.length; c++) {
            Label h = new Label(headers[c]);
            h.setStyle("-fx-font-size: 9px; -fx-font-weight: bold;");
            grid.add(h, c, 0);
        }
        int row = 1;
        for (var r : LCStateSignal.rows(report)) {
            String color = r.clipped() ? ThemeColors.ERROR : (r.extinction() ? ThemeColors.MUTED : ThemeColors.NORMAL);
            String[] cells = {
                r.id() + (r.extinction() ? " (ext.)" : ""),
                String.format("%.0f", r.mean()),
                String.format("%.0f", r.p999()),
                Double.isNaN(r.percentOfFull())
                        ? "?"
                        : String.format("%.0f%%", r.percentOfFull()) + (r.clipped() ? " clipped" : "")
            };
            for (int c = 0; c < cells.length; c++) {
                Label l = new Label(cells[c]);
                l.setStyle("-fx-font-size: 9px; -fx-text-fill: " + color + ";");
                grid.add(l, c, row);
            }
            row++;
        }
        Label verdict = new Label(LCStateSignal.verdict(report));
        verdict.setWrapText(true);
        verdict.setStyle("-fx-font-size: 10px;");
        box.getChildren().addAll(grid, verdict);
        for (String p : LCStateSignal.cameraProblems(report)) {
            Label l = new Label(p);
            l.setWrapText(true);
            l.setStyle("-fx-font-size: 9px; -fx-text-fill: "
                    + (p.startsWith("Must fix") ? ThemeColors.ERROR : ThemeColors.WARNING) + ";");
            box.getChildren().add(l);
        }
        return box;
    }

    private static Label mutedNote(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setStyle("-fx-font-size: 9px; -fx-text-fill: " + ThemeColors.MUTED + ";");
        return l;
    }

    private static Double parsePositive(String text) {
        try {
            double v = Double.parseDouble(text == null ? "" : text.trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void saveExposure(String modality, java.util.List<String> stateIds, Double ms) {
        final String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        if (configPath == null || configPath.isBlank() || ms == null) {
            host.setStatus(
                    ms == null ? "Exposure must be a positive number" : "No config path configured", ThemeColors.ERROR);
            return;
        }
        String details = "Modality:  " + modality + "\n\n" + "Exposure " + fmtNum(ms) + " ms for "
                + String.join(", ", stateIds) + "\n\nWritten to modalities." + modality + ".channels[*].exposure_ms."
                + "\nBackgrounds collected at another exposure are flagged at acquisition:"
                + "\ncollect a new background afterwards (LC-PolScope Setup, step 3).";
        if (!host.confirmSaveToYaml("Save the LC-PolScope exposure?", details)) {
            host.setStatus("Save canceled", ThemeColors.MUTED);
            return;
        }
        Thread t = new Thread(
                () -> {
                    try {
                        int changed = LCStateSignal.writeExposureToStates(
                                java.nio.file.Paths.get(configPath), modality, stateIds, ms);
                        host.configManager().reload(configPath);
                        try {
                            MicroscopeController mc = MicroscopeController.getInstance();
                            if (mc != null && mc.isConnected()) {
                                mc.getSocketClient().sendReconfig();
                            }
                        } catch (Exception reconfigEx) {
                            logger.warn("RECONFG after save failed: {}", reconfigEx.getMessage());
                        }
                        logger.info("LC-PolScope exposure {} ms saved to {} state(s) of {}", ms, changed, modality);
                        Platform.runLater(() -> {
                            host.setStatus(
                                    "Saved " + fmtNum(ms) + " ms to all states (" + changed
                                            + " changed). Collect a new background if this exposure is new.",
                                    ThemeColors.SUCCESS);
                            if (changed > 0) {
                                host.requestBackgroundRecollection(modality);
                            }
                            host.rebuild(modality);
                        });
                    } catch (Exception ex) {
                        logger.error("Save LC-PolScope exposure failed: {}", ex.getMessage(), ex);
                        Platform.runLater(() -> {
                            host.setStatus("Save failed: " + ex.getMessage(), ThemeColors.ERROR);
                        });
                    }
                },
                "Save-Profile-LCPS");
        t.setDaemon(true);
        t.start();
    }

    /** Formats a value with no trailing ".0" for whole numbers (e.g. 603, 3.11). */
    private static String fmtNum(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return String.format("%.2f", v);
    }
}
