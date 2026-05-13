package qupath.ext.qpsc.modality.widefield.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.modality.Channel;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.PropertyRef;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.liveviewer.LiveViewerWindow;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * Generic channel selection UI for multi-channel widefield modalities
 * (e.g. widefield immunofluorescence).
 *
 * <p>Shows one row per channel from the first widefield modality declared
 * with a channel library in the microscope YAML, letting the user toggle
 * each channel and override its exposure per-acquisition. All UI content is
 * driven by the config file -- there is nothing vendor-specific in this
 * class.
 *
 * <p>The UI has a master "Customize channel selection" checkbox. When
 * unchecked (default), the workflow uses all channels from the library
 * with their config-declared exposures -- persisted per-channel edits are
 * ignored. When checked, the user's selection map is honored and empty
 * selections are treated as an error (refuses to start the acquisition).
 *
 * <p>Reuses {@link ModalityHandler.BoundingBoxUI#getAngleOverrides()} to
 * return selections. Return semantics:
 * <ul>
 *   <li>{@code null} -- no library loaded, OR master override is unchecked;
 *       caller should use library defaults.</li>
 *   <li>non-empty map -- user has selected channels; keys are channel ids,
 *       values are per-channel exposures in milliseconds.</li>
 *   <li>empty map -- master override is checked but user selected zero
 *       channels; caller must refuse to start the acquisition.</li>
 * </ul>
 */
public class WidefieldChannelBoundingBoxUI implements ModalityHandler.BoundingBoxUI {

    private static final Logger logger = LoggerFactory.getLogger(WidefieldChannelBoundingBoxUI.class);

    private static final String PREF_KEY_PREFIX = "widefield.channel.";
    private static final String PREF_KEY_MASTER = PREF_KEY_PREFIX + "master_override_enabled";
    private static final String PREF_KEY_FOCUS_CHANNEL = PREF_KEY_PREFIX + "focus_channel";
    // Named-preset persistence. NAMES is a TAB-separated list of display names;
    // each preset is stored as a single pipe-delimited blob under DATA + safeKey.
    private static final String PREF_KEY_PRESET_NAMES = PREF_KEY_PREFIX + "preset.names";
    private static final String PREF_KEY_PRESET_DATA = PREF_KEY_PREFIX + "preset.";
    private static final String PREF_KEY_LAST_PRESET = PREF_KEY_PREFIX + "preset.last";

    private final VBox root;
    private final CheckBox masterOverride;
    private final LinkedHashMap<String, CheckBox> channelCheckboxes = new LinkedHashMap<>();
    private final LinkedHashMap<String, Spinner<Double>> channelExposures = new LinkedHashMap<>();
    // Seeded only for channels whose library entry declares an intensity_property.
    // Channels without intensity control have no spinner here.
    private final LinkedHashMap<String, Spinner<Double>> channelIntensities = new LinkedHashMap<>();
    private final LinkedHashMap<String, Double> defaultIntensities = new LinkedHashMap<>();
    // Focus-channel radio buttons (one per row, mutually exclusive via ToggleGroup).
    // Only enabled when 2+ channels are selected -- with one channel there's no
    // choice to make. The selected channel is collected first per tile so its
    // hardware state is the same one autofocus runs against.
    private final LinkedHashMap<String, javafx.scene.control.RadioButton> channelFocusRadios = new LinkedHashMap<>();
    private final javafx.scene.control.ToggleGroup focusToggleGroup = new javafx.scene.control.ToggleGroup();
    // Channel definitions retained so the Test button can look up
    // intensity_property and the preset save/load can capture per-channel state.
    private final LinkedHashMap<String, Channel> channelDefs = new LinkedHashMap<>();
    // Modality the channel library was sourced from -- needed to resolve the
    // APPLYCH acquisition_profiles key when the Test button fires.
    private String loadedModality;
    private ComboBox<String> presetCombo;
    private Label statusLabel;
    private boolean suppressPresetComboListener = false;

    public WidefieldChannelBoundingBoxUI() {
        root = new VBox(5);

        Label title = new Label("Fluorescence Channels");
        title.setStyle("-fx-font-weight: bold;");

        List<Channel> library = loadChannelLibrary();
        if (library.isEmpty()) {
            Label empty = new Label("No fluorescence channels configured for this microscope.\n"
                    + "Contact facility staff to add a 'channels' list under\n"
                    + "modalities.<widefield-modality>.channels in the YAML.");
            empty.setStyle("-fx-text-fill: gray; -fx-font-style: italic;");
            root.getChildren().addAll(new Separator(), title, empty);
            masterOverride = null;
            return;
        }

        boolean masterEnabled =
                Boolean.parseBoolean(PersistentPreferences.getStringPreference(PREF_KEY_MASTER, "false"));
        masterOverride = new CheckBox("Customize channel selection for this acquisition");
        masterOverride.setSelected(masterEnabled);
        masterOverride.setTooltip(
                new Tooltip("Unchecked: acquire every channel in the library with default exposures.\n"
                        + "Checked: pick which channels to acquire and override exposures.\n"
                        + "When checked, deselecting all channels blocks the acquisition."));
        masterOverride
                .selectedProperty()
                .addListener((obs, oldVal, newVal) ->
                        PersistentPreferences.setStringPreference(PREF_KEY_MASTER, String.valueOf(newVal)));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(5);

        // Columns: checkbox | name (grow) | exposure | intensity | focus radio
        ColumnConstraints col0 = new ColumnConstraints();
        col0.setHalignment(HPos.CENTER);
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setHgrow(Priority.ALWAYS);
        col1.setFillWidth(true);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setHalignment(HPos.RIGHT);
        ColumnConstraints col3 = new ColumnConstraints();
        col3.setHalignment(HPos.RIGHT);
        ColumnConstraints col4 = new ColumnConstraints();
        col4.setHalignment(HPos.CENTER);
        grid.getColumnConstraints().addAll(col0, col1, col2, col3, col4);

        grid.add(boldLabel("Use"), 0, 0);
        grid.add(boldLabel("Channel"), 1, 0);
        grid.add(boldLabel("Exposure (ms)"), 2, 0);
        grid.add(boldLabel("Intensity"), 3, 0);
        grid.add(boldLabel("Focus"), 4, 0);

        // Persisted last focus-channel selection from a previous run.
        String savedFocusChannel = PersistentPreferences.getStringPreference(PREF_KEY_FOCUS_CHANNEL, "");

        int row = 1;
        for (Channel channel : library) {
            String id = channel.id();
            String selectedPrefKey = PREF_KEY_PREFIX + id + ".selected";
            String exposurePrefKey = PREF_KEY_PREFIX + id + ".exposure_ms";
            String intensityPrefKey = PREF_KEY_PREFIX + id + ".intensity";

            boolean selected =
                    Boolean.parseBoolean(PersistentPreferences.getStringPreference(selectedPrefKey, "false"));
            double exposure;
            try {
                exposure = Double.parseDouble(PersistentPreferences.getStringPreference(
                        exposurePrefKey, String.valueOf(channel.defaultExposureMs())));
            } catch (NumberFormatException e) {
                exposure = channel.defaultExposureMs();
            }

            CheckBox cb = new CheckBox();
            cb.setSelected(selected);
            cb.setTooltip(new Tooltip("Include " + channel.displayName() + " in this acquisition"));

            Label nameLabel = new Label(channel.displayName());
            nameLabel.setMaxWidth(Double.MAX_VALUE);

            Spinner<Double> expSpinner = new Spinner<>();
            expSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(0.1, 60000.0, exposure, 10.0));
            expSpinner.setEditable(true);
            expSpinner.setPrefWidth(110);
            expSpinner.setTooltip(new Tooltip("Camera exposure for this channel.\n"
                    + "Default from YAML (with profile overrides): " + channel.defaultExposureMs() + " ms"));
            commitOnFocusLost(expSpinner);

            // Spinner enabled only when master override is on AND the channel row is selected.
            expSpinner
                    .disableProperty()
                    .bind(masterOverride
                            .selectedProperty()
                            .not()
                            .or(cb.selectedProperty().not()));
            cb.disableProperty().bind(masterOverride.selectedProperty().not());

            cb.selectedProperty()
                    .addListener((obs, oldVal, newVal) ->
                            PersistentPreferences.setStringPreference(selectedPrefKey, String.valueOf(newVal)));
            expSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null) {
                    PersistentPreferences.setStringPreference(exposurePrefKey, String.valueOf(newVal));
                }
            });

            grid.add(cb, 0, row);
            grid.add(nameLabel, 1, row);
            grid.add(expSpinner, 2, row);

            // Intensity spinner: only created for channels that declared an
            // intensity_property in YAML. For channels without one (e.g. legacy
            // modalities), insert a placeholder label so column alignment is
            // preserved without leaking null spinners into the override map.
            double defaultIntensity = channel.currentIntensityValue();
            if (channel.intensityProperty() != null && !Double.isNaN(defaultIntensity)) {
                double intensity;
                try {
                    intensity = Double.parseDouble(PersistentPreferences.getStringPreference(
                            intensityPrefKey, String.valueOf(defaultIntensity)));
                } catch (NumberFormatException e) {
                    intensity = defaultIntensity;
                }

                Spinner<Double> intensitySpinner = new Spinner<>();
                // LED / lamp intensities vary wildly across vendors -- span 0..65535
                // to cover 8-bit DACs, 16-bit DACs, and normalized-float controllers
                // at 1% granularity. UI clamping is soft; real validation lives in
                // the hardware layer.
                intensitySpinner.setValueFactory(
                        new SpinnerValueFactory.DoubleSpinnerValueFactory(0.0, 65535.0, intensity, 1.0));
                intensitySpinner.setEditable(true);
                intensitySpinner.setPrefWidth(100);
                intensitySpinner.setTooltip(new Tooltip(
                        String.format("Override for %s (default: %s)", channel.intensityProperty(), defaultIntensity)));
                commitOnFocusLost(intensitySpinner);

                intensitySpinner
                        .disableProperty()
                        .bind(masterOverride
                                .selectedProperty()
                                .not()
                                .or(cb.selectedProperty().not()));

                intensitySpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
                    if (newVal != null) {
                        PersistentPreferences.setStringPreference(intensityPrefKey, String.valueOf(newVal));
                    }
                });

                grid.add(intensitySpinner, 3, row);
                channelIntensities.put(id, intensitySpinner);
                defaultIntensities.put(id, defaultIntensity);
            } else {
                Label placeholder = new Label("-");
                placeholder.setStyle("-fx-text-fill: gray;");
                placeholder.setTooltip(new Tooltip("This channel has no intensity_property declared in the YAML, "
                        + "so there is no runtime intensity knob for it."));
                grid.add(placeholder, 3, row);
            }

            // Focus-channel radio button. Disabled until 2+ channels are
            // selected (with 1 channel there's no choice). Restored from
            // last-run preference if present, otherwise the first channel
            // wins by default after the loop ends.
            javafx.scene.control.RadioButton focusRadio = new javafx.scene.control.RadioButton();
            focusRadio.setToggleGroup(focusToggleGroup);
            focusRadio.setTooltip(new Tooltip("Use " + channel.displayName() + " as the autofocus reference channel.\n"
                    + "The focus channel is collected first per tile so its hardware state\n"
                    + "matches the autofocus snap, avoiding a second hardware switch."));
            // Focus radio is enabled only when this channel row is selected
            // AND at least one OTHER channel is also selected (so there's
            // an actual choice to make). Single-channel runs hide the
            // focus selection entirely.
            focusRadio
                    .disableProperty()
                    .bind(masterOverride
                            .selectedProperty()
                            .not()
                            .or(cb.selectedProperty().not()));
            if (savedFocusChannel != null && savedFocusChannel.equals(id)) {
                focusRadio.setSelected(true);
            }
            focusRadio.selectedProperty().addListener((obs, oldVal, newVal) -> {
                if (Boolean.TRUE.equals(newVal)) {
                    PersistentPreferences.setStringPreference(PREF_KEY_FOCUS_CHANNEL, id);
                }
            });
            grid.add(focusRadio, 4, row);
            channelFocusRadios.put(id, focusRadio);

            channelCheckboxes.put(id, cb);
            channelExposures.put(id, expSpinner);
            channelDefs.put(id, channel);
            row++;
        }

        // After all rows are built, ensure exactly one focus radio is selected
        // (the first one in library order if the user hasn't picked anything).
        if (focusToggleGroup.getSelectedToggle() == null && !channelFocusRadios.isEmpty()) {
            channelFocusRadios.values().iterator().next().setSelected(true);
        }

        Label hint = new Label(String.format(
                "Library has %d channels. Default mode uses all of them at YAML exposures.", library.size()));
        hint.setStyle("-fx-text-fill: gray; -fx-font-size: 10.5px;");

        HBox presetBar = buildPresetBar();
        HBox testBar = buildTestBar();
        statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 11px;");
        statusLabel.setWrapText(true);

        root.getChildren().addAll(new Separator(), title, masterOverride, grid, presetBar, testBar, statusLabel, hint);
    }

    private static Label boldLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }

    // ====================================================================
    // Preset bar -- named save/load/delete of channel + exposure + intensity sets
    // ====================================================================

    private HBox buildPresetBar() {
        presetCombo = new ComboBox<>();
        presetCombo.setPromptText("(no preset)");
        presetCombo.setPrefWidth(180);
        presetCombo.setTooltip(
                new Tooltip("Select a saved channel preset to apply its checkbox / exposure / intensity values."));
        presetCombo.disableProperty().bind(masterOverride.selectedProperty().not());

        refreshPresetCombo(PersistentPreferences.getStringPreference(PREF_KEY_LAST_PRESET, ""));

        presetCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (suppressPresetComboListener || newVal == null || newVal.isEmpty()) return;
            applyPreset(newVal);
        });

        Button saveBtn = new Button("Save...");
        saveBtn.setTooltip(
                new Tooltip("Save the current channel selection / exposure / intensity state as a named preset."));
        saveBtn.disableProperty().bind(masterOverride.selectedProperty().not());
        saveBtn.setOnAction(e -> onSavePreset());

        Button deleteBtn = new Button("Delete");
        deleteBtn.setTooltip(new Tooltip("Delete the currently-selected preset."));
        deleteBtn
                .disableProperty()
                .bind(masterOverride
                        .selectedProperty()
                        .not()
                        .or(presetCombo.valueProperty().isNull()));
        deleteBtn.setOnAction(e -> onDeletePreset());

        Label label = new Label("Preset:");
        label.setStyle("-fx-font-weight: bold;");
        HBox bar = new HBox(8, label, presetCombo, saveBtn, deleteBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private HBox buildTestBar() {
        Button testBtn = new Button("Test Current Channel");
        testBtn.setTooltip(
                new Tooltip("Apply the selected channel's hardware (cube / illumination / intensity / exposure)\n"
                        + "and open the Live Viewer so you can verify the result without leaving this dialog.\n"
                        + "Exactly one channel must be selected with the master toggle on."));
        testBtn.setOnAction(e -> onTestCurrentChannel());
        HBox bar = new HBox(8, testBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private void refreshPresetCombo(String selectAfter) {
        suppressPresetComboListener = true;
        try {
            presetCombo.getItems().setAll(loadPresetNames());
            if (selectAfter != null
                    && !selectAfter.isEmpty()
                    && presetCombo.getItems().contains(selectAfter)) {
                presetCombo.setValue(selectAfter);
            } else {
                presetCombo.setValue(null);
            }
        } finally {
            suppressPresetComboListener = false;
        }
    }

    private void onSavePreset() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Save Channel Preset");
        dialog.setHeaderText("Save current channel selection + exposures + intensities");
        dialog.setContentText("Preset name:");
        dialog.showAndWait().ifPresent(rawName -> {
            String name = rawName == null ? "" : rawName.trim();
            if (name.isEmpty()) {
                setStatus("Preset name cannot be empty.", true);
                return;
            }
            if (name.contains("\t") || name.contains("|")) {
                setStatus("Preset name cannot contain TAB or '|' characters.", true);
                return;
            }
            if (name.length() > 40) {
                setStatus("Preset name too long (max 40 characters).", true);
                return;
            }
            List<String> names = loadPresetNames();
            boolean overwrite = names.contains(name);
            persistPreset(name);
            if (!overwrite) {
                names.add(name);
                persistPresetNames(names);
            }
            PersistentPreferences.setStringPreference(PREF_KEY_LAST_PRESET, name);
            refreshPresetCombo(name);
            setStatus((overwrite ? "Updated preset: " : "Saved preset: ") + name, false);
        });
    }

    private void onDeletePreset() {
        String name = presetCombo.getValue();
        if (name == null || name.isEmpty()) return;
        List<String> names = loadPresetNames();
        names.remove(name);
        persistPresetNames(names);
        PersistentPreferences.setStringPreference(PREF_KEY_PRESET_DATA + safeKey(name), null);
        if (name.equals(PersistentPreferences.getStringPreference(PREF_KEY_LAST_PRESET, ""))) {
            PersistentPreferences.setStringPreference(PREF_KEY_LAST_PRESET, null);
        }
        refreshPresetCombo(null);
        setStatus("Deleted preset: " + name, false);
    }

    private void applyPreset(String name) {
        String blob = PersistentPreferences.getStringPreference(PREF_KEY_PRESET_DATA + safeKey(name), null);
        if (blob == null || blob.isEmpty()) {
            setStatus("Preset '" + name + "' has no stored data.", true);
            return;
        }
        // Format: v1|focus=<id>|<chId>=<sel>:<exp>:<int>|...
        String[] parts = blob.split("\\|", -1);
        if (parts.length < 1 || !"v1".equals(parts[0])) {
            setStatus("Unsupported preset format for '" + name + "'.", true);
            return;
        }
        String focusId = null;
        Map<String, String[]> channelStates = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.startsWith("focus=")) {
                focusId = part.substring("focus=".length());
            } else {
                int eq = part.indexOf('=');
                if (eq < 1) continue;
                String chId = part.substring(0, eq);
                String[] fields = part.substring(eq + 1).split(":", -1); // selected:exposure:intensity
                channelStates.put(chId, fields);
            }
        }
        for (Map.Entry<String, CheckBox> entry : channelCheckboxes.entrySet()) {
            String id = entry.getKey();
            String[] fields = channelStates.get(id);
            if (fields == null) {
                // Channel not in preset -- leave its current state alone.
                continue;
            }
            try {
                entry.getValue().setSelected(Boolean.parseBoolean(fields[0]));
                if (fields.length > 1 && !fields[1].isEmpty()) {
                    Spinner<Double> exp = channelExposures.get(id);
                    if (exp != null) exp.getValueFactory().setValue(Double.parseDouble(fields[1]));
                }
                if (fields.length > 2 && !fields[2].isEmpty()) {
                    Spinner<Double> intensity = channelIntensities.get(id);
                    if (intensity != null) intensity.getValueFactory().setValue(Double.parseDouble(fields[2]));
                }
            } catch (Exception ex) {
                logger.warn("Failed to apply preset field for channel '{}': {}", id, ex.getMessage());
            }
        }
        if (focusId != null && channelFocusRadios.containsKey(focusId)) {
            channelFocusRadios.get(focusId).setSelected(true);
        }
        PersistentPreferences.setStringPreference(PREF_KEY_LAST_PRESET, name);
        setStatus("Applied preset: " + name, false);
    }

    private void persistPreset(String name) {
        StringBuilder sb = new StringBuilder("v1");
        String focusId = getFocusChannelId();
        if (focusId != null) {
            sb.append("|focus=").append(focusId);
        }
        for (Map.Entry<String, CheckBox> entry : channelCheckboxes.entrySet()) {
            String id = entry.getKey();
            boolean selected = entry.getValue().isSelected();
            Spinner<Double> exp = channelExposures.get(id);
            Spinner<Double> intensity = channelIntensities.get(id);
            sb.append("|").append(id).append("=").append(selected).append(":");
            if (exp != null && exp.getValue() != null) sb.append(exp.getValue());
            sb.append(":");
            if (intensity != null && intensity.getValue() != null) sb.append(intensity.getValue());
        }
        PersistentPreferences.setStringPreference(PREF_KEY_PRESET_DATA + safeKey(name), sb.toString());
    }

    private List<String> loadPresetNames() {
        String raw = PersistentPreferences.getStringPreference(PREF_KEY_PRESET_NAMES, "");
        if (raw.isEmpty()) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (String s : raw.split("\t")) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private void persistPresetNames(List<String> names) {
        PersistentPreferences.setStringPreference(PREF_KEY_PRESET_NAMES, String.join("\t", names));
    }

    private static String safeKey(String name) {
        // Java Preferences keys are capped at 80 chars. Compact, deterministic
        // mapping: lowercase + non-alphanumerics -> '_'. Names are validated to
        // <= 40 chars on save, so total key length stays well under the limit.
        return name.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
    }

    private void setStatus(String message, boolean isError) {
        if (statusLabel == null) return;
        Platform.runLater(() -> {
            statusLabel.setText(message);
            statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + (isError ? "#cc3333" : "#2e7d32") + ";");
        });
    }

    // ====================================================================
    // Test Current Channel -- apply the one selected channel to hardware
    // and open the Live Viewer so the user can verify settings without
    // closing this (APPLICATION_MODAL) dialog.
    // ====================================================================

    private void onTestCurrentChannel() {
        if (masterOverride == null || !masterOverride.isSelected()) {
            setStatus("Enable 'Customize channel selection' first, then select exactly one channel.", true);
            return;
        }
        List<String> selectedIds = new ArrayList<>();
        for (Map.Entry<String, CheckBox> entry : channelCheckboxes.entrySet()) {
            if (entry.getValue().isSelected()) selectedIds.add(entry.getKey());
        }
        if (selectedIds.isEmpty()) {
            setStatus("Select exactly one channel to test (none selected).", true);
            return;
        }
        if (selectedIds.size() > 1) {
            setStatus(
                    "Test Current Channel requires exactly one channel. " + selectedIds.size()
                            + " are selected -- uncheck the others first.",
                    true);
            return;
        }
        String channelId = selectedIds.get(0);
        Channel channel = channelDefs.get(channelId);
        if (channel == null) {
            setStatus("Channel definition missing for '" + channelId + "'.", true);
            return;
        }
        MicroscopeController mc = MicroscopeController.getInstance();
        if (mc == null || !mc.isConnected()) {
            setStatus("Not connected to microscope server.", true);
            return;
        }
        String profile = findFirstMatchingProfile();
        if (profile == null) {
            setStatus("No acquisition profile found for modality '" + loadedModality + "'.", true);
            return;
        }

        Spinner<Double> expSpinner = channelExposures.get(channelId);
        Double exposureMs = expSpinner != null ? expSpinner.getValue() : null;
        Spinner<Double> intensitySpinner = channelIntensities.get(channelId);
        Double intensity = intensitySpinner != null ? intensitySpinner.getValue() : null;
        PropertyRef intensityProp = channel.intensityProperty();

        LiveViewerWindow.show();
        setStatus("Applying " + channelId + " (exp " + exposureMs + " ms)...", false);

        Thread worker = new Thread(
                () -> {
                    try {
                        mc.withLiveModeHandling(() -> {
                            mc.getSocketClient().applyChannel(profile, channelId);
                            if (exposureMs != null) {
                                mc.getSocketClient().setExposures(new float[] {exposureMs.floatValue()});
                            }
                            if (intensityProp != null && intensity != null) {
                                mc.getSocketClient()
                                        .setProperty(
                                                intensityProp.device(),
                                                intensityProp.property(),
                                                formatIntensityValue(intensity));
                            }
                        });
                        setStatus(
                                "Live Viewer streaming " + channelId
                                        + " -- adjust spinners and click Test again to re-apply.",
                                false);
                    } catch (Exception ex) {
                        logger.error("Test Current Channel failed: {}", ex.getMessage(), ex);
                        setStatus("Test failed: " + ex.getMessage(), true);
                    }
                },
                "Widefield-TestChannel");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Resolve the first acquisition_profiles entry whose modality matches the
     * channel library's source modality. Ported from
     * {@code StageControlPanel.findFirstMatchingProfile} -- prefix match on the
     * first two characters so "fluorescence" and "fl" both resolve.
     */
    @SuppressWarnings("unchecked")
    private String findFirstMatchingProfile() {
        if (loadedModality == null) return null;
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        if (configPath == null || configPath.isBlank()) return null;
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        if (mgr == null) mgr = MicroscopeConfigManager.getInstance(configPath);
        try {
            Object profiles = mgr.getConfigItem("acquisition_profiles");
            if (!(profiles instanceof Map<?, ?> profileMap) || profileMap.isEmpty()) return null;
            String modalityLower = loadedModality.toLowerCase();
            for (Map.Entry<?, ?> entry : profileMap.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> profileCfg)) continue;
                Object profModality = profileCfg.get("modality");
                if (profModality == null) continue;
                String profModStr = profModality.toString().toLowerCase();
                int prefix = Math.min(2, Math.min(modalityLower.length(), profModStr.length()));
                if (modalityLower.regionMatches(0, profModStr, 0, prefix)) {
                    return String.valueOf(entry.getKey());
                }
            }
        } catch (Exception e) {
            logger.debug("findFirstMatchingProfile({}) failed: {}", loadedModality, e.getMessage());
        }
        return null;
    }

    /**
     * Format an intensity value as a Micro-Manager property string. Integer-
     * valued doubles drop the trailing ".0" so DLED.Intensity-475nm receives
     * "30" rather than "30.0" (some MM drivers reject the latter).
     * Mirrors {@code StageControlPanel.formatIntensityValue}.
     */
    private static String formatIntensityValue(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return Integer.toString((int) v);
        }
        return Double.toString(v);
    }

    private static void commitOnFocusLost(Spinner<Double> spinner) {
        spinner.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                try {
                    spinner.increment(0);
                } catch (Exception ignored) {
                    // If the editor contains an invalid value, leave it; validation is downstream.
                }
            }
        });
    }

    private List<Channel> loadChannelLibrary() {
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        if (configPath == null || configPath.isBlank()) {
            return List.of();
        }
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        if (mgr == null) {
            mgr = MicroscopeConfigManager.getInstance(configPath);
        }

        Map<String, Object> modalities = mgr.getSection("modalities");
        if (modalities == null) {
            return List.of();
        }
        // Accept ANY modality with a non-empty channels list. This keeps the UI
        // fully generic -- widefield IF, BF+IF, multispectral, or any future
        // channel-based modality is picked up without touching this class.
        for (Map.Entry<String, Object> entry : modalities.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?>)) {
                continue;
            }
            List<Channel> channels = mgr.getModalityChannels(entry.getKey());
            if (!channels.isEmpty()) {
                logger.debug("Loaded {} channels from modalities.{}", channels.size(), entry.getKey());
                loadedModality = entry.getKey();
                return channels;
            }
        }
        return List.of();
    }

    @Override
    public Node getNode() {
        return root;
    }

    /**
     * Returns the user's channel selection.
     *
     * <ul>
     *   <li>{@code null} -- no library loaded, or master override is unchecked.
     *       Caller should use library defaults (acquire every channel at its
     *       config-declared exposure).</li>
     *   <li>non-empty map -- user's selected channels keyed by channel id, with
     *       per-channel exposures in milliseconds as the values.</li>
     *   <li>empty map -- master override is checked but the user deselected
     *       every channel. Caller MUST refuse to start the acquisition.</li>
     * </ul>
     *
     * <p>Despite the interface method name (inherited from the angle-based PPM
     * UI), the map carries channel selection data, not angle overrides.
     */
    @Override
    public Map<String, Double> getAngleOverrides() {
        // No library or master override disabled -> defer to defaults.
        if (masterOverride == null || !masterOverride.isSelected()) {
            return null;
        }
        Map<String, Double> result = new LinkedHashMap<>();
        for (Map.Entry<String, CheckBox> entry : channelCheckboxes.entrySet()) {
            if (!entry.getValue().isSelected()) {
                continue;
            }
            Spinner<Double> spinner = channelExposures.get(entry.getKey());
            Double exposure = spinner != null ? spinner.getValue() : null;
            if (exposure != null && exposure > 0) {
                result.put(entry.getKey(), exposure);
            }
        }
        // Empty result here means "user actively selected nothing" -- return it as-is so
        // the workflow can block the acquisition with a clear error. Do NOT fall through.
        return result;
    }

    /**
     * Returns the channel id the user picked as the autofocus reference, or
     * {@code null} if no library is loaded. The picked channel is always one
     * of the currently-selected channels (the radio button is disabled when
     * the row is unselected); when only one channel is selected, that channel
     * implicitly becomes the focus channel and the radios are effectively
     * a no-op. The downstream workflow moves this channel to position 0 in
     * the per-tile acquisition sequence so the autofocus snap and the first
     * acquired image share hardware state.
     */
    @Override
    public String getFocusChannelId() {
        if (channelFocusRadios.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, javafx.scene.control.RadioButton> entry : channelFocusRadios.entrySet()) {
            if (entry.getValue().isSelected()) {
                return entry.getKey();
            }
        }
        // No radio selected (shouldn't happen because we set one in the
        // constructor): fall back to the first channel in library order.
        return channelFocusRadios.keySet().iterator().next();
    }

    /**
     * Returns per-channel intensity overrides for the currently-selected
     * channels. Only channels whose YAML entry declared an
     * {@code intensity_property} appear here, and only if the user changed the
     * spinner away from its library default (to keep the command line compact
     * and make "no override" legible at a glance).
     */
    @Override
    public Map<String, Double> getChannelIntensityOverrides() {
        if (masterOverride == null || !masterOverride.isSelected()) {
            return Map.of();
        }
        LinkedHashMap<String, Double> result = new LinkedHashMap<>();
        for (Map.Entry<String, CheckBox> entry : channelCheckboxes.entrySet()) {
            if (!entry.getValue().isSelected()) {
                continue;
            }
            String id = entry.getKey();
            Spinner<Double> spinner = channelIntensities.get(id);
            if (spinner == null) {
                continue; // Channel has no intensity_property -> no knob to override.
            }
            Double value = spinner.getValue();
            if (value == null) {
                continue;
            }
            // Emit the override only if it differs from the YAML default, so
            // unchanged values don't clutter the CLI flag. Epsilon = 1e-6.
            Double libraryDefault = defaultIntensities.get(id);
            if (libraryDefault == null || Math.abs(value - libraryDefault) > 1e-6) {
                result.put(id, value);
            }
        }
        return result;
    }

    /**
     * Returns true if the UI is actually rendering a channel picker
     * (a library was found in the config). When false, the UI is a
     * placeholder message and channel-based acquisition is not available.
     */
    public boolean hasChannels() {
        return !channelCheckboxes.isEmpty();
    }
}
