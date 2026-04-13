package qupath.ext.qpsc.modality.widefield.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.geometry.HPos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.Channel;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
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

    private final VBox root;
    private final CheckBox masterOverride;
    private final LinkedHashMap<String, CheckBox> channelCheckboxes = new LinkedHashMap<>();
    private final LinkedHashMap<String, Spinner<Double>> channelExposures = new LinkedHashMap<>();

    public WidefieldChannelBoundingBoxUI() {
        root = new VBox(5);

        Label title = new Label("Fluorescence Channels");
        title.setStyle("-fx-font-weight: bold;");

        List<Channel> library = loadChannelLibrary();
        if (library.isEmpty()) {
            Label empty = new Label(
                    "No fluorescence channels configured for this microscope.\n"
                            + "Contact facility staff to add a 'channels' list under\n"
                            + "modalities.<widefield-modality>.channels in the YAML.");
            empty.setStyle("-fx-text-fill: gray; -fx-font-style: italic;");
            root.getChildren().addAll(new Separator(), title, empty);
            masterOverride = null;
            return;
        }

        boolean masterEnabled = Boolean.parseBoolean(
                PersistentPreferences.getStringPreference(PREF_KEY_MASTER, "false"));
        masterOverride = new CheckBox("Customize channel selection for this acquisition");
        masterOverride.setSelected(masterEnabled);
        masterOverride.setTooltip(new Tooltip(
                "Unchecked: acquire every channel in the library with default exposures.\n"
                        + "Checked: pick which channels to acquire and override exposures.\n"
                        + "When checked, deselecting all channels blocks the acquisition."));
        masterOverride.selectedProperty().addListener((obs, oldVal, newVal) ->
                PersistentPreferences.setStringPreference(PREF_KEY_MASTER, String.valueOf(newVal)));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(5);

        // Column 0: checkbox (fixed), column 1: name (grow), column 2: spinner (right-aligned)
        ColumnConstraints col0 = new ColumnConstraints();
        col0.setHalignment(HPos.CENTER);
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setHgrow(Priority.ALWAYS);
        col1.setFillWidth(true);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setHalignment(HPos.RIGHT);
        grid.getColumnConstraints().addAll(col0, col1, col2);

        grid.add(boldLabel("Use"), 0, 0);
        grid.add(boldLabel("Channel"), 1, 0);
        grid.add(boldLabel("Exposure (ms)"), 2, 0);

        int row = 1;
        for (Channel channel : library) {
            String id = channel.id();
            String selectedPrefKey = PREF_KEY_PREFIX + id + ".selected";
            String exposurePrefKey = PREF_KEY_PREFIX + id + ".exposure_ms";

            boolean selected = Boolean.parseBoolean(
                    PersistentPreferences.getStringPreference(selectedPrefKey, "false"));
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

            Spinner<Double> spinner = new Spinner<>();
            spinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(0.1, 60000.0, exposure, 10.0));
            spinner.setEditable(true);
            spinner.setPrefWidth(110);
            spinner.setTooltip(new Tooltip("Camera exposure for this channel.\n"
                    + "Default from YAML (with profile overrides): " + channel.defaultExposureMs() + " ms"));
            commitOnFocusLost(spinner);

            // Spinner is enabled only when master override is on AND the channel row is selected.
            spinner.disableProperty().bind(
                    masterOverride.selectedProperty().not().or(cb.selectedProperty().not()));
            cb.disableProperty().bind(masterOverride.selectedProperty().not());

            cb.selectedProperty().addListener((obs, oldVal, newVal) ->
                    PersistentPreferences.setStringPreference(selectedPrefKey, String.valueOf(newVal)));
            spinner.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null) {
                    PersistentPreferences.setStringPreference(exposurePrefKey, String.valueOf(newVal));
                }
            });

            grid.add(cb, 0, row);
            grid.add(nameLabel, 1, row);
            grid.add(spinner, 2, row);

            channelCheckboxes.put(id, cb);
            channelExposures.put(id, spinner);
            row++;
        }

        Label hint = new Label(String.format(
                "Library has %d channels. Default mode uses all of them at YAML exposures.",
                library.size()));
        hint.setStyle("-fx-text-fill: gray; -fx-font-size: 10.5px;");

        root.getChildren().addAll(new Separator(), title, masterOverride, grid, hint);
    }

    private static Label boldLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold;");
        return l;
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
     * Returns true if the UI is actually rendering a channel picker
     * (a library was found in the config). When false, the UI is a
     * placeholder message and channel-based acquisition is not available.
     */
    public boolean hasChannels() {
        return !channelCheckboxes.isEmpty();
    }
}
