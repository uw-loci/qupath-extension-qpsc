package qupath.ext.qpsc.controller;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.Channel;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

/**
 * Dialog and workflow for basic Z-stack and time-lapse acquisition.
 *
 * <p>Single-tile acquisitions at the current stage position:
 * <ul>
 *   <li>Z-stack: multiple Z planes around current focus</li>
 *   <li>Time-lapse: repeat acquisition at regular intervals</li>
 * </ul>
 *
 * <p>A shared modality + profile (+ channel) row above the tabs sets the
 * microscope hardware state via APPLYPR (and APPLYCH for channel-based
 * modalities) before each Start. Without this, brightfield runs at
 * whatever exposure/lamp/condenser the previous workflow left behind,
 * which is what produces the low-dynamic-range brightfield captures.
 */
public class StackTimeLapseWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(StackTimeLapseWorkflow.class);

    /** Modality + profile + channel selection shared between the Z-Stack and Time-Lapse tabs. */
    private static final class SetupState {
        final ComboBox<String> modalityCombo = new ComboBox<>();
        final ComboBox<String> profileCombo = new ComboBox<>();
        final ComboBox<ChannelChoice> channelCombo = new ComboBox<>();
        final HBox channelRow;
        final Label setupErrorLabel = new Label();
        final MicroscopeConfigManager configMgr;
        // Listener guards: prevent feedback loops while we re-populate
        // dependent combos in response to a parent combo's change.
        boolean rebuilding = false;

        SetupState(MicroscopeConfigManager configMgr, HBox channelRow) {
            this.configMgr = configMgr;
            this.channelRow = channelRow;
            setupErrorLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: -fx-accent;");
            setupErrorLabel.setWrapText(true);
        }

        String getSelectedProfile() {
            return profileCombo.getValue();
        }

        String getSelectedModality() {
            return modalityCombo.getValue();
        }

        String getSelectedChannelId() {
            if (!channelRow.isVisible()) return null;
            ChannelChoice c = channelCombo.getValue();
            return c == null ? null : c.id();
        }

        boolean modalityHasChannels() {
            String m = getSelectedModality();
            if (m == null || m.isBlank()) return false;
            try {
                List<Channel> chans = configMgr.getModalityChannels(m);
                return chans != null && !chans.isEmpty();
            } catch (Exception ignored) {
                return false;
            }
        }

        boolean isReady() {
            if (getSelectedProfile() == null || getSelectedProfile().isBlank()) return false;
            if (channelRow.isVisible() && getSelectedChannelId() == null) return false;
            return true;
        }
    }

    /** Channel combo entry; toString drives the displayed label but we keep the id for the wire. */
    private record ChannelChoice(String id, String displayName) {
        @Override
        public String toString() {
            return displayName;
        }
    }

    /**
     * Show the Z-stack/time-lapse dialog.
     */
    public static void show(QuPathGUI qupath) {
        MicroscopeController mc = MicroscopeController.getInstance();
        if (!mc.isConnected()) {
            try {
                mc.userTriggeredConnect();
            } catch (IOException e) {
                logger.error("Failed to connect to microscope server: {}", e.getMessage());
                Dialogs.showErrorMessage(
                        "Z-Stack / Time-Lapse", "Not connected to microscope server: " + e.getMessage());
                return;
            }
        }

        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        if (configPath == null || configPath.isBlank()) {
            Dialogs.showErrorMessage("Z-Stack / Time-Lapse", "No microscope configuration file set in preferences.");
            return;
        }
        MicroscopeConfigManager configMgr = MicroscopeConfigManager.getInstance(configPath);

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Z-Stack / Time-Lapse");
        dialog.setHeaderText("Single-tile acquisition at current position");

        // ===== Shared setup row (modality + profile + channel) =====
        GridPane setupGrid = new GridPane();
        setupGrid.setHgap(8);
        setupGrid.setVgap(6);
        setupGrid.setPadding(new Insets(10));

        HBox channelRow = new HBox(8);
        SetupState setup = new SetupState(configMgr, channelRow);
        setup.modalityCombo.setPrefWidth(180);
        setup.profileCombo.setPrefWidth(220);
        setup.channelCombo.setPrefWidth(180);

        setupGrid.add(new Label("Modality:"), 0, 0);
        setupGrid.add(setup.modalityCombo, 1, 0);
        setupGrid.add(new Label("Profile:"), 0, 1);
        setupGrid.add(setup.profileCombo, 1, 1);
        channelRow.getChildren().addAll(new Label("Channel:"), setup.channelCombo);
        // The setupGrid layout uses 2 columns (label, control). Channel row
        // is added as a single full-width entry so it can hide/show as one
        // unit without leaving a blank label column.
        setupGrid.add(channelRow, 0, 2, 2, 1);
        setupGrid.add(setup.setupErrorLabel, 0, 3, 2, 1);

        populateModalityCombo(setup);
        wireSetupListeners(setup);

        TitledPane setupPane = new TitledPane("Setup", setupGrid);
        setupPane.setCollapsible(false);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // ===== Z-Stack Tab =====
        Tab zTab = new Tab("Z-Stack");
        GridPane zGrid = new GridPane();
        zGrid.setHgap(8);
        zGrid.setVgap(6);
        zGrid.setPadding(new Insets(10));

        // Get current Z for default range
        double currentZ = 0;
        try {
            double[] pos = mc.getSocketClient().getStageXYZ();
            currentZ = pos[2];
        } catch (Exception e) {
            logger.debug("Could not get current Z: {}", e.getMessage());
        }

        Spinner<Double> zRangeSpinner = new Spinner<>(1.0, 200.0, 20.0, 5.0);
        zRangeSpinner.setEditable(true);
        zRangeSpinner.setPrefWidth(100);
        Spinner<Double> zStepSpinner = new Spinner<>(0.1, 50.0, 1.0, 0.5);
        zStepSpinner.setEditable(true);
        zStepSpinner.setPrefWidth(100);

        Label zInfoLabel = new Label();
        zInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");
        final double fCurrentZ = currentZ;

        Runnable updateZInfo = () -> {
            double range = zRangeSpinner.getValue();
            double step = zStepSpinner.getValue();
            int planes = (int) Math.ceil(range / step) + 1;
            zInfoLabel.setText(String.format(
                    "Z range: %.1f to %.1f um (%d planes, centered on current Z=%.1f)",
                    fCurrentZ - range / 2, fCurrentZ + range / 2, planes, fCurrentZ));
        };

        zRangeSpinner.valueProperty().addListener((obs, o, n) -> updateZInfo.run());
        zStepSpinner.valueProperty().addListener((obs, o, n) -> updateZInfo.run());
        updateZInfo.run();

        TextField zOutputField = new TextField();
        zOutputField.setPromptText("Output folder for Z-stack images");
        String defaultZOutput = getDefaultOutputFolder("zstack");
        zOutputField.setText(defaultZOutput);

        Button zBrowseBtn = new Button("Browse...");
        zBrowseBtn.setOnAction(e -> {
            var chooser = new javafx.stage.DirectoryChooser();
            chooser.setTitle("Select Z-Stack Output Folder");
            File dir = chooser.showDialog(dialog.getOwner());
            if (dir != null) zOutputField.setText(dir.getAbsolutePath());
        });

        zGrid.add(new Label("Total range (um):"), 0, 0);
        zGrid.add(zRangeSpinner, 1, 0);
        zGrid.add(new Label("Step size (um):"), 0, 1);
        zGrid.add(zStepSpinner, 1, 1);
        zGrid.add(zInfoLabel, 0, 2, 2, 1);
        zGrid.add(new Label("Output:"), 0, 3);
        zGrid.add(new HBox(4, zOutputField, zBrowseBtn), 1, 3);
        HBox.setHgrow(zOutputField, Priority.ALWAYS);

        Label zStatusLabel = new Label();
        zStatusLabel.setStyle("-fx-font-size: 11px;");
        zStatusLabel.setWrapText(true);

        Button zStartBtn = new Button("Start Z-Stack");
        zStartBtn.setStyle("-fx-font-weight: bold;");
        zStartBtn.setOnAction(e -> {
            if (!setup.isReady()) {
                zStatusLabel.setText("Pick a modality, profile, and channel (if shown) in Setup first.");
                zStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: red;");
                return;
            }
            double range = zRangeSpinner.getValue();
            double step = zStepSpinner.getValue();
            double zStart = fCurrentZ - range / 2;
            double zEnd = fCurrentZ + range / 2;
            String output = zOutputField.getText().trim();
            if (output.isEmpty()) {
                zStatusLabel.setText("Please specify an output folder.");
                return;
            }

            zStartBtn.setDisable(true);
            zStatusLabel.setText("Acquiring Z-stack...");
            zStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

            new Thread(
                            () -> {
                                try {
                                    applyProfileBeforeAcquire(mc, setup);
                                    String profile = setup.getSelectedProfile();
                                    String modality = configMgr.getProfileModality(profile);
                                    String objective = configMgr.getProfileObjective(profile);
                                    String detector = configMgr.getProfileDetector(profile);
                                    String response = mc.getSocketClient()
                                            .startZStack(
                                                    output,
                                                    zStart,
                                                    zEnd,
                                                    step,
                                                    modality,
                                                    null,
                                                    "off",
                                                    configPath,
                                                    objective,
                                                    detector,
                                                    null);
                                    Platform.runLater(() -> {
                                        zStatusLabel.setText("Complete: " + response);
                                        zStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: green;");
                                        zStartBtn.setDisable(false);
                                    });
                                } catch (Exception ex) {
                                    logger.error("Z-stack failed: {}", ex.getMessage());
                                    Platform.runLater(() -> {
                                        zStatusLabel.setText("Failed: " + ex.getMessage());
                                        zStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: red;");
                                        zStartBtn.setDisable(false);
                                    });
                                }
                            },
                            "ZStack-Acquire")
                    .start();
        });

        VBox zContent = new VBox(8, zGrid, zStartBtn, zStatusLabel);
        zContent.setPadding(new Insets(8));
        zTab.setContent(zContent);

        // ===== Time-Lapse Tab =====
        Tab tTab = new Tab("Time-Lapse");
        GridPane tGrid = new GridPane();
        tGrid.setHgap(8);
        tGrid.setVgap(6);
        tGrid.setPadding(new Insets(10));

        Spinner<Integer> tpSpinner = new Spinner<>(2, 10000, 10, 5);
        tpSpinner.setEditable(true);
        tpSpinner.setPrefWidth(100);
        Spinner<Double> intervalSpinner = new Spinner<>(0.0, 3600.0, 5.0, 1.0);
        intervalSpinner.setEditable(true);
        intervalSpinner.setPrefWidth(100);

        Label tInfoLabel = new Label();
        tInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        Runnable updateTInfo = () -> {
            int tp = tpSpinner.getValue();
            double interval = intervalSpinner.getValue();
            double totalMinutes = (tp - 1) * interval / 60.0;
            tInfoLabel.setText(String.format("%d timepoints, %.1f min total duration", tp, totalMinutes));
        };
        tpSpinner.valueProperty().addListener((obs, o, n) -> updateTInfo.run());
        intervalSpinner.valueProperty().addListener((obs, o, n) -> updateTInfo.run());
        updateTInfo.run();

        TextField tOutputField = new TextField();
        tOutputField.setPromptText("Output folder for time-lapse images");
        tOutputField.setText(getDefaultOutputFolder("timelapse"));

        Button tBrowseBtn = new Button("Browse...");
        tBrowseBtn.setOnAction(e -> {
            var chooser = new javafx.stage.DirectoryChooser();
            chooser.setTitle("Select Time-Lapse Output Folder");
            File dir = chooser.showDialog(dialog.getOwner());
            if (dir != null) tOutputField.setText(dir.getAbsolutePath());
        });

        tGrid.add(new Label("Timepoints:"), 0, 0);
        tGrid.add(tpSpinner, 1, 0);
        tGrid.add(new Label("Interval (sec):"), 0, 1);
        tGrid.add(intervalSpinner, 1, 1);
        tGrid.add(tInfoLabel, 0, 2, 2, 1);
        tGrid.add(new Label("Output:"), 0, 3);
        tGrid.add(new HBox(4, tOutputField, tBrowseBtn), 1, 3);
        HBox.setHgrow(tOutputField, Priority.ALWAYS);

        Label tStatusLabel = new Label();
        tStatusLabel.setStyle("-fx-font-size: 11px;");
        tStatusLabel.setWrapText(true);

        Button tStartBtn = new Button("Start Time-Lapse");
        tStartBtn.setStyle("-fx-font-weight: bold;");
        tStartBtn.setOnAction(e -> {
            if (!setup.isReady()) {
                tStatusLabel.setText("Pick a modality, profile, and channel (if shown) in Setup first.");
                tStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: red;");
                return;
            }
            int tp = tpSpinner.getValue();
            double interval = intervalSpinner.getValue();
            String output = tOutputField.getText().trim();
            if (output.isEmpty()) {
                tStatusLabel.setText("Please specify an output folder.");
                return;
            }

            tStartBtn.setDisable(true);
            tStatusLabel.setText("Acquiring time-lapse...");
            tStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

            new Thread(
                            () -> {
                                try {
                                    applyProfileBeforeAcquire(mc, setup);
                                    String profile = setup.getSelectedProfile();
                                    String modality = configMgr.getProfileModality(profile);
                                    String objective = configMgr.getProfileObjective(profile);
                                    String detector = configMgr.getProfileDetector(profile);
                                    String response = mc.getSocketClient()
                                            .startTimeLapse(
                                                    output,
                                                    tp,
                                                    interval,
                                                    modality,
                                                    null,
                                                    "off",
                                                    configPath,
                                                    objective,
                                                    detector);
                                    Platform.runLater(() -> {
                                        tStatusLabel.setText("Complete: " + response);
                                        tStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: green;");
                                        tStartBtn.setDisable(false);
                                    });
                                } catch (Exception ex) {
                                    logger.error("Time-lapse failed: {}", ex.getMessage());
                                    Platform.runLater(() -> {
                                        tStatusLabel.setText("Failed: " + ex.getMessage());
                                        tStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: red;");
                                        tStartBtn.setDisable(false);
                                    });
                                }
                            },
                            "TimeLapse-Acquire")
                    .start();
        });

        VBox tContent = new VBox(8, tGrid, tStartBtn, tStatusLabel);
        tContent.setPadding(new Insets(8));
        tTab.setContent(tContent);

        tabs.getTabs().addAll(zTab, tTab);

        VBox root = new VBox(8, setupPane, tabs);
        dialog.getDialogPane().setContent(root);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setPrefWidth(540);

        dialog.showAndWait();
    }

    /**
     * Push the current Setup-row selection to the microscope: profile mode
     * switch (APPLYPR) plus channel state (APPLYCH) when the modality has
     * channels. The mode switch is idempotent and ensures objective /
     * detector / illumination / exposure match the YAML profile entry
     * before tiles are snapped, which is what fixes the brightfield
     * dynamic-range problem that motivated this dialog overhaul.
     */
    private static void applyProfileBeforeAcquire(MicroscopeController mc, SetupState setup) throws IOException {
        String profile = setup.getSelectedProfile();
        if (profile == null || profile.isBlank()) {
            throw new IOException("No acquisition profile selected.");
        }
        mc.getSocketClient().applyProfile(profile);
        String channelId = setup.getSelectedChannelId();
        if (channelId != null && !channelId.isBlank()) {
            mc.getSocketClient().applyChannel(profile, channelId);
        }
    }

    private static void populateModalityCombo(SetupState setup) {
        // Distinct, case-insensitive sorted modality list pulled from every
        // entry in acquisition_profiles. Empty if the YAML has no profiles.
        Set<String> modalities = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String key : setup.configMgr.getAcquisitionProfileKeys()) {
            String mod = setup.configMgr.getProfileModality(key);
            if (mod != null && !mod.isBlank()) {
                modalities.add(mod);
            }
        }
        setup.modalityCombo.getItems().setAll(modalities);
        if (modalities.isEmpty()) {
            setup.setupErrorLabel.setText("No 'acquisition_profiles' entries in the microscope YAML. "
                    + "Configure profiles before running a Z-stack or time-lapse.");
            setup.modalityCombo.setDisable(true);
            setup.profileCombo.setDisable(true);
            setup.channelCombo.setDisable(true);
            return;
        }
        String preferred = PersistentPreferences.getStackTimeLapseModality();
        if (preferred != null && !preferred.isBlank() && modalities.contains(preferred)) {
            setup.modalityCombo.setValue(preferred);
        } else {
            setup.modalityCombo.setValue(setup.modalityCombo.getItems().get(0));
        }
    }

    private static void wireSetupListeners(SetupState setup) {
        setup.modalityCombo.valueProperty().addListener((obs, oldM, newM) -> {
            if (setup.rebuilding) return;
            PersistentPreferences.setStackTimeLapseModality(newM);
            rebuildProfileCombo(setup);
            rebuildChannelRow(setup);
        });
        setup.profileCombo.valueProperty().addListener((obs, oldP, newP) -> {
            if (setup.rebuilding) return;
            if (newP != null && !newP.isBlank()) {
                PersistentPreferences.setStackTimeLapseProfile(newP);
            }
        });
        setup.channelCombo.valueProperty().addListener((obs, oldC, newC) -> {
            if (setup.rebuilding) return;
            if (newC != null) {
                PersistentPreferences.setStackTimeLapseChannel(newC.id());
            }
        });
        // First-time population now that listeners are wired -- the modality
        // combo already has a value (set during populateModalityCombo), so
        // rebuilding the dependent combos picks it up.
        rebuildProfileCombo(setup);
        rebuildChannelRow(setup);
    }

    private static void rebuildProfileCombo(SetupState setup) {
        String modality = setup.getSelectedModality();
        setup.rebuilding = true;
        try {
            if (modality == null || modality.isBlank()) {
                setup.profileCombo.getItems().clear();
                setup.profileCombo.setValue(null);
                return;
            }
            List<String> profiles = new ArrayList<>(setup.configMgr.getProfileKeysForModality(modality));
            setup.profileCombo.getItems().setAll(profiles);
            String preferred = PersistentPreferences.getStackTimeLapseProfile();
            if (preferred != null && !preferred.isBlank() && profiles.contains(preferred)) {
                setup.profileCombo.setValue(preferred);
            } else if (!profiles.isEmpty()) {
                setup.profileCombo.setValue(profiles.get(0));
            } else {
                setup.profileCombo.setValue(null);
            }
        } finally {
            setup.rebuilding = false;
        }
    }

    private static void rebuildChannelRow(SetupState setup) {
        String modality = setup.getSelectedModality();
        setup.rebuilding = true;
        try {
            List<Channel> channels = (modality == null || modality.isBlank())
                    ? List.of()
                    : setup.configMgr.getModalityChannels(modality);
            if (channels == null || channels.isEmpty()) {
                setup.channelCombo.getItems().clear();
                setup.channelCombo.setValue(null);
                setup.channelRow.setVisible(false);
                setup.channelRow.setManaged(false);
                return;
            }
            List<ChannelChoice> choices = new ArrayList<>(channels.size());
            for (Channel c : channels) {
                choices.add(new ChannelChoice(c.id(), c.displayName()));
            }
            setup.channelCombo.getItems().setAll(choices);
            String preferred = PersistentPreferences.getStackTimeLapseChannel();
            ChannelChoice picked = null;
            if (preferred != null && !preferred.isBlank()) {
                for (ChannelChoice cc : choices) {
                    if (preferred.equals(cc.id())) {
                        picked = cc;
                        break;
                    }
                }
            }
            if (picked == null) picked = choices.get(0);
            setup.channelCombo.setValue(picked);
            setup.channelRow.setVisible(true);
            setup.channelRow.setManaged(true);
        } finally {
            setup.rebuilding = false;
        }
    }

    private static String getDefaultOutputFolder(String type) {
        try {
            var project = QuPathGUI.getInstance().getProject();
            if (project != null) {
                File projectDir = project.getPath().toFile().getParentFile();
                return new File(projectDir, type).getAbsolutePath();
            }
        } catch (Exception e) {
            // Fall through
        }
        return "";
    }
}
