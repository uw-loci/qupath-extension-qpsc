package qupath.ext.qpsc.modality.lcpolscope.ui;

import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import qupath.ext.qpsc.config.MicroscopeConfigManager;
import qupath.ext.qpsc.modality.lcpolscope.LCDisplaySettings;

/**
 * The orientation color image controls in the LC-PolScope acquisition panel: the color map and
 * the retardance shown at full brightness. The values, their defaults and what is sent to the
 * server are in {@link LCDisplaySettings}; this pane only edits them. It depends on the sample: a
 * mounting medium or a thick section can need a higher ceiling.
 */
public final class LCColormapPane extends VBox {

    private record Option(String value, String label, String help) {
        @Override
        public String toString() {
            return label;
        }
    }

    public LCColormapPane() {
        super(2);
        MicroscopeConfigManager mgr = MicroscopeConfigManager.current();
        String ppmSource = LCDisplaySettings.ppmCalibrationSource(mgr);
        String configured = LCDisplaySettings.configuredColormap(mgr);

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
                    "PPM colors (" + ppmSource + ")",
                    "The hue a PPM image shows for the angle. The angle registration to PPM is in the config."));
        }
        options.add(new Option(
                "hsv",
                "HSV rainbow (OpenPolScope)",
                "OpenPolScope's look; yellows and cyans look brighter than blues at the same retardance."));

        ComboBox<Option> combo = new ComboBox<>();
        combo.getItems().setAll(options);
        String saved = LCDisplaySettings.effectiveChoice(LCDisplaySettings.savedChoice(), ppmSource != null);
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
                LCDisplaySettings.setChoice(now.value());
                help.setText(now.help());
            }
        });

        Label heading = new Label("Orientation color image:");
        Tooltip.install(
                heading,
                new Tooltip("Display only: changes how the color image looks, never the retardance or "
                        + "orientation values. Applies to tiles reconstructed from now on."));
        HBox row = new HBox(8, heading, combo);
        row.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(row, help, ceilingRow(mgr));
    }

    /** "Full brightness at [spinner] nm retardance [Config (N nm)]". */
    private static HBox ceilingRow(MicroscopeConfigManager mgr) {
        double configured = LCDisplaySettings.configuredCeilingNm(mgr);
        Spinner<Double> spinner = new Spinner<>();
        spinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                LCDisplaySettings.MIN_CEILING_NM,
                LCDisplaySettings.MAX_CEILING_NM,
                LCDisplaySettings.effectiveCeilingNm(),
                1.0));
        spinner.setEditable(true);
        spinner.setPrefWidth(90);
        spinner.valueProperty().addListener((obs, old, now) -> {
            if (now != null && now > 0) {
                LCDisplaySettings.setCeilingNm(now);
            }
        });
        Button useConfig = new Button("Config (" + trim(configured) + " nm)");
        useConfig.setTooltip(
                new Tooltip("Back to reconstruction.display_retardance_ceiling_nm from the microscope YAML."));
        useConfig.setOnAction(e -> {
            LCDisplaySettings.setCeilingNm(0.0);
            spinner.getValueFactory().setValue(configured);
        });
        Label heading = new Label("Full brightness at:");
        Tooltip.install(
                heading,
                new Tooltip("The retardance shown at full brightness in the orientation color image, and the top "
                        + "of the retardance image's display range. Display only. Raise it for a mounting medium "
                        + "or a section thickness that gives more retardance than the default is made for."));
        HBox row = new HBox(8, heading, spinner, new Label("nm retardance"), useConfig);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
