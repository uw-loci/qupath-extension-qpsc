package qupath.ext.qpsc.modality.lcpolscope.ui;

import java.util.ArrayList;
import java.util.List;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * The "Orientation colour" choice in the LC-PolScope acquisition panel: which colour map the
 * server uses for the display-only orientation colour image of the next runs. Remembered across
 * sessions; "As in the config" sends nothing, so {@code reconstruction.display_colormap} applies.
 * The choice is sent as {@code --display-colormap} by the LC-PolScope handler's
 * {@code configureCommandBuilder}. Maps: claude-reports/design/2026-10-22_orientation-colormaps.md.
 */
public final class LCColormapPane extends VBox {

    private static final StringProperty CHOICE = PathPrefs.createPersistentPreference("lcpolscope.displayColormap", "");

    private record Option(String value, String label, String help) {
        @Override
        public String toString() {
            return label;
        }
    }

    public LCColormapPane() {
        super(2);
        MicroscopeConfigManager mgr = configManager();
        String ppmSource = ppmCalibrationSource(mgr);
        String configured = configuredColormap(mgr);

        List<Option> options = new ArrayList<>();
        options.add(new Option(
                "",
                "As in the config (" + configured + ")",
                "Uses reconstruction.display_colormap from the microscope YAML."));
        options.add(new Option(
                "uniform",
                "Uniform lightness",
                "Every orientation equally light at equal retardance, so brightness means retardance only."));
        if (ppmSource != null) {
            options.add(new Option(
                    "ppm",
                    "PPM colours (" + ppmSource + ")",
                    "The hue a PPM image shows for the angle. The angle registration to PPM is in the config."));
        }
        options.add(new Option(
                "hsv",
                "HSV rainbow (OpenPolScope)",
                "OpenPolScope's look; yellows and cyans look brighter than blues at the same retardance."));

        ComboBox<Option> combo = new ComboBox<>();
        combo.getItems().setAll(options);
        String saved = effectiveChoice(CHOICE.get(), ppmSource != null);
        combo.getSelectionModel()
                .select(options.stream()
                        .filter(o -> o.value().equals(saved == null ? "" : saved))
                        .findFirst()
                        .orElse(options.get(0)));
        Label help = new Label(combo.getValue().help());
        help.setWrapText(true);
        help.setStyle("-fx-font-size: 11px;");
        combo.valueProperty().addListener((obs, old, now) -> {
            if (now != null) {
                CHOICE.set(now.value());
                help.setText(now.help());
            }
        });

        Label heading = new Label("Orientation colour image:");
        Tooltip.install(
                heading,
                new Tooltip("Display only: changes how the colour image looks, never the retardance or "
                        + "orientation values. Applies to tiles reconstructed from now on."));
        HBox row = new HBox(8, heading, combo);
        row.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(row, help);
    }

    /**
     * The colour map to send with the next acquisition, or null to send nothing (the config's
     * own setting applies).
     */
    public static String selectedColormap() {
        return effectiveChoice(CHOICE.get(), ppmCalibrationSource(configManager()) != null);
    }

    /**
     * The saved choice as it can be sent: null for "as in the config", an unknown value, or "ppm"
     * on a config without a PPM hue calibration (the server would refuse the whole reconstruction).
     */
    static String effectiveChoice(String saved, boolean hasPpmCalibration) {
        if (saved == null) {
            return null;
        }
        return switch (saved) {
            case "uniform", "hsv" -> saved;
            case "ppm" -> hasPpmCalibration ? saved : null;
            default -> null;
        };
    }

    private static String configuredColormap(MicroscopeConfigManager mgr) {
        if (mgr != null) {
            Object value = mgr.getConfigItem("modalities", "lcpolscope", "reconstruction", "display_colormap");
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return "uniform";
    }

    private static String ppmCalibrationSource(MicroscopeConfigManager mgr) {
        if (mgr == null
                || mgr.getDouble("modalities", "lcpolscope", "reconstruction", "display_hue_calibration", "slope")
                        == null) {
            return null;
        }
        Object source =
                mgr.getConfigItem("modalities", "lcpolscope", "reconstruction", "display_hue_calibration", "source");
        return source == null ? "calibration" : source.toString();
    }

    private static MicroscopeConfigManager configManager() {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        if (mgr == null) {
            String path = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (path != null && !path.isBlank()) {
                mgr = MicroscopeConfigManager.getInstance(path);
            }
        }
        return mgr;
    }
}
