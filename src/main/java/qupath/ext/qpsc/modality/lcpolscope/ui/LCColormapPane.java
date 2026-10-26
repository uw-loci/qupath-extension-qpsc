package qupath.ext.qpsc.modality.lcpolscope.ui;

import java.util.ArrayList;
import java.util.List;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * The orientation colour image settings in the LC-PolScope acquisition panel: which colour map
 * the server uses for the display-only orientation colour image of the next runs, and the
 * retardance shown at full brightness (the display ceiling). Both are remembered across
 * sessions. "As in the config" sends nothing, so {@code reconstruction.display_colormap} applies;
 * a ceiling left at the config's value sends nothing either. The LC-PolScope handler's
 * {@code configureCommandBuilder} sends them as {@code --display-colormap} and
 * {@code --display-ceiling-nm}. The ceiling also sets the display range QPSC gives a stitched
 * retardance image and the starting ceiling of the Orientation Colour View. It depends on the
 * sample: a mounting medium or a thick section can need a higher one. Maps:
 * claude-reports/design/2026-10-22_orientation-colormaps.md.
 */
public final class LCColormapPane extends VBox {

    private static final StringProperty CHOICE = PathPrefs.createPersistentPreference("lcpolscope.displayColormap", "");

    /** The chosen ceiling in nm; 0 (or anything not above 0) means the config's own value. */
    private static final DoubleProperty CEILING =
            PathPrefs.createPersistentPreference("lcpolscope.displayCeilingNm", 0.0);

    static final double FALLBACK_CEILING_NM = 20.0;
    static final double MIN_CEILING_NM = 0.5;
    static final double MAX_CEILING_NM = 300.0;

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
        getChildren().addAll(row, help, ceilingRow(mgr));
    }

    /** "Full brightness at [spinner] nm retardance [Config (N nm)]". */
    private static HBox ceilingRow(MicroscopeConfigManager mgr) {
        double configured = configuredCeilingNm(mgr);
        Spinner<Double> spinner = new Spinner<>();
        spinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                MIN_CEILING_NM, MAX_CEILING_NM, effectiveCeilingNm(), 1.0));
        spinner.setEditable(true);
        spinner.setPrefWidth(90);
        spinner.valueProperty().addListener((obs, old, now) -> {
            if (now != null && now > 0) {
                CEILING.set(now);
            }
        });
        Button useConfig = new Button("Config (" + trim(configured) + " nm)");
        useConfig.setTooltip(
                new Tooltip("Back to reconstruction.display_retardance_ceiling_nm from the microscope YAML."));
        useConfig.setOnAction(e -> {
            CEILING.set(0.0);
            spinner.getValueFactory().setValue(configured);
        });
        Label heading = new Label("Full brightness at:");
        Tooltip.install(
                heading,
                new Tooltip("The retardance shown at full brightness in the orientation colour image, and the top "
                        + "of the retardance image's display range. Display only. Raise it for a mounting medium "
                        + "or a section thickness that gives more retardance than the default is made for."));
        HBox row = new HBox(8, heading, spinner, new Label("nm retardance"), useConfig);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * The ceiling to send with the next acquisition as {@code --display-ceiling-nm}, or null to
     * send nothing: when the pane was never changed from the config's value.
     */
    public static Double sendableCeilingNm() {
        return sendableCeiling(CEILING.get(), configuredCeilingNm(configManager()));
    }

    /**
     * The display ceiling in force: the pane's value when one was set, else the config's
     * {@code display_retardance_ceiling_nm}, else 20 nm.
     */
    public static double effectiveCeilingNm() {
        double saved = CEILING.get();
        return saved > 0 && Double.isFinite(saved) ? saved : configuredCeilingNm(configManager());
    }

    /** The saved ceiling if it is a usable number that differs from the config's; else null. */
    static Double sendableCeiling(double saved, double configured) {
        if (!(saved > 0) || !Double.isFinite(saved)) {
            return null;
        }
        return Math.abs(saved - configured) < 1e-9 ? null : saved;
    }

    private static double configuredCeilingNm(MicroscopeConfigManager mgr) {
        try {
            Double c = mgr == null
                    ? null
                    : mgr.getDouble("modalities", "lcpolscope", "reconstruction", "display_retardance_ceiling_nm");
            if (c != null && c > 0 && Double.isFinite(c)) {
                return c;
            }
        } catch (RuntimeException e) {
            // an unreadable config falls back below
        }
        return FALLBACK_CEILING_NM;
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(java.util.Locale.ROOT, "%.1f", v);
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
