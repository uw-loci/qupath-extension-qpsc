package qupath.ext.qpsc.modality.ppm.ui;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ppm.PPMPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.mda.MdaExportAction;
import qupath.ext.qpsc.service.mda.MdaExportContext;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * UI component allowing per-acquisition override of PPM angles.
 */
public class PPMBoundingBoxUI implements ModalityHandler.BoundingBoxUI {

    private static final Logger logger = LoggerFactory.getLogger(PPMBoundingBoxUI.class);

    private final VBox root;
    private final CheckBox overrideAngles;
    private final Spinner<Double> plusSpinner;
    private final Spinner<Double> minusSpinner;
    // Which angles to acquire (front-loaded here instead of a per-image popup).
    private final CheckBox selMinus;
    private final CheckBox selZero;
    private final CheckBox selPlus;
    private final CheckBox selUncrossed;
    private final double zeroTick;
    private final double uncrossedTick;
    private final double plusTick;
    private final double minusTick;
    // "Save as MicroManager MDA..." button. Always built but hidden until the
    // parent dialog installs an MdaExportContext supplier so the button has
    // access to the current sample / region / cmdBuilder state.
    private final Button saveMdaButton;
    private Supplier<MdaExportContext> mdaContextSupplier;

    @SuppressWarnings("unchecked")
    public PPMBoundingBoxUI() {
        root = new VBox(5);

        MicroscopeConfigManager mgr =
                MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty());

        double defaultPlus = 7.0;
        double defaultMinus = -7.0;
        double defaultZero = 0.0;
        double defaultUncrossed = 90.0;

        java.util.List<?> angles = mgr.getList("modalities", "ppm", "rotation_angles");
        if (angles != null) {
            for (Object angleObj : angles) {
                if (angleObj instanceof java.util.Map<?, ?> angle) {
                    Object name = angle.get("name");
                    Object tickObj = angle.get("tick");
                    if (name != null && tickObj instanceof Number) {
                        double tick = ((Number) tickObj).doubleValue();
                        if ("positive".equals(name.toString())) defaultPlus = tick;
                        else if ("negative".equals(name.toString())) defaultMinus = tick;
                        else if ("zero".equals(name.toString()) || "crossed".equals(name.toString()))
                            defaultZero = tick;
                        else if ("uncrossed".equals(name.toString()) || "parallel".equals(name.toString()))
                            defaultUncrossed = tick;
                    }
                }
            }
        }
        this.zeroTick = defaultZero;
        this.uncrossedTick = defaultUncrossed;
        this.plusTick = defaultPlus;
        this.minusTick = defaultMinus;

        Label label = new Label("PPM Polarization Angles:");
        label.setStyle("-fx-font-weight: bold;");

        // Angle selection (front-loaded here so nothing pops per image / per slide).
        Label selLabel = new Label("Angles to acquire:");
        selMinus = new CheckBox("minus");
        selZero = new CheckBox("zero");
        selPlus = new CheckBox("plus");
        selUncrossed = new CheckBox("uncrossed");
        selMinus.setSelected(true);
        selZero.setSelected(true);
        selPlus.setSelected(true);
        selUncrossed.setSelected(true);
        HBox angleSelRow = new HBox(10, selMinus, selZero, selPlus, selUncrossed);
        angleSelRow.setAlignment(Pos.CENTER_LEFT);
        Label expNote = new Label("Exposures are auto-derived per angle (background flat-field -> config -> prefs).");
        expNote.setStyle("-fx-font-style: italic;");

        overrideAngles = new CheckBox("Override default angles for this acquisition");
        overrideAngles.setTooltip(new Tooltip("When checked, the plus and minus angles below will be used\n"
                + "instead of the default values from the microscope configuration.\n"
                + "Useful for testing different polarizer offsets without changing the config."));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(5);
        grid.setDisable(true);

        // Load saved preferences or use defaults
        double savedPlusAngle = PPMPreferences.getOverridePlusAngle();
        double savedMinusAngle = PPMPreferences.getOverrideMinusAngle();
        boolean overrideEnabled = PPMPreferences.getAngleOverrideEnabled();

        plusSpinner = new Spinner<>();
        plusSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(-180, 180, savedPlusAngle, 0.5));
        plusSpinner.setEditable(true);
        plusSpinner.setPrefWidth(100);
        plusSpinner.setTooltip(new Tooltip("Positive polarizer rotation in tick marks.\n"
                + "Overrides the default plus angle from the microscope configuration."));

        minusSpinner = new Spinner<>();
        minusSpinner.setValueFactory(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(-180, 180, savedMinusAngle, 0.5));
        minusSpinner.setEditable(true);
        minusSpinner.setPrefWidth(100);
        minusSpinner.setTooltip(new Tooltip("Negative polarizer rotation in tick marks.\n"
                + "Overrides the default minus angle from the microscope configuration."));

        grid.add(new Label("Plus angle (ticks):"), 0, 0);
        grid.add(plusSpinner, 1, 0);
        grid.add(new Label("Minus angle (ticks):"), 0, 1);
        grid.add(minusSpinner, 1, 1);

        // Set checkbox to saved state
        overrideAngles.setSelected(overrideEnabled);
        grid.setDisable(!overrideEnabled);

        // Save preferences when values change
        overrideAngles.selectedProperty().addListener((obs, old, sel) -> {
            grid.setDisable(!sel);
            PPMPreferences.setAngleOverrideEnabled(sel);
        });

        plusSpinner.valueProperty().addListener((obs, old, newVal) -> {
            if (newVal != null) {
                PPMPreferences.setOverridePlusAngle(newVal);
            }
        });

        minusSpinner.valueProperty().addListener((obs, old, newVal) -> {
            if (newVal != null) {
                PPMPreferences.setOverrideMinusAngle(newVal);
            }
        });

        saveMdaButton = new Button("Save as MicroManager MDA...");
        saveMdaButton.setTooltip(
                new Tooltip("Write Micro-Manager-compatible files (MDA_<region>.txt, .pos, NOTES) for each\n"
                        + "selected region. Use this to set up an MM run that mirrors the planned\n"
                        + "QPSC acquisition without actually starting acquisition."));
        saveMdaButton.setVisible(false);
        saveMdaButton.setManaged(false);
        saveMdaButton.setOnAction(e -> onSaveMda());
        HBox mdaBar = new HBox(8, saveMdaButton);
        mdaBar.setAlignment(Pos.CENTER_LEFT);

        root.getChildren().addAll(new Separator(), label, selLabel, angleSelRow, expNote, overrideAngles, grid, mdaBar);
    }

    /**
     * Installs the parent dialog's MDA-export context supplier and reveals the
     * "Save as MicroManager MDA..." button. The supplier is invoked on the FX
     * thread when the button is clicked, so it can read the parent dialog's
     * live state. Pass {@code null} to hide the button again.
     */
    public void installMdaExportContext(Supplier<MdaExportContext> supplier) {
        this.mdaContextSupplier = supplier;
        boolean show = supplier != null;
        saveMdaButton.setVisible(show);
        saveMdaButton.setManaged(show);
    }

    private void onSaveMda() {
        if (mdaContextSupplier == null) {
            return;
        }
        MdaExportContext ctx;
        try {
            ctx = mdaContextSupplier.get();
        } catch (RuntimeException ex) {
            logger.error("MDA export context build failed: {}", ex.getMessage(), ex);
            Window win = root.getScene() != null ? root.getScene().getWindow() : null;
            Alert err = new Alert(Alert.AlertType.ERROR);
            err.setTitle("MicroManager MDA Export");
            err.setHeaderText("Failed to build export context");
            err.setContentText(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            UIFunctions.showAlertOverParent(err, win);
            return;
        }
        if (ctx == null || ctx.hasError()) {
            Window win = root.getScene() != null ? root.getScene().getWindow() : null;
            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.setTitle("MicroManager MDA Export");
            info.setHeaderText("Not ready to export");
            info.setContentText(
                    ctx != null
                                    && ctx.errorMessage() != null
                                    && !ctx.errorMessage().isBlank()
                            ? ctx.errorMessage()
                            : "Select at least one region and confirm channel/Z settings before exporting MDA.");
            UIFunctions.showAlertOverParent(info, win);
            return;
        }
        final Window parentWindow = root.getScene() != null ? root.getScene().getWindow() : null;
        saveMdaButton.setDisable(true);
        CompletableFuture.runAsync(() -> {
                    try {
                        MdaExportAction.exportAndConfirm(
                                parentWindow,
                                ctx.sample(),
                                ctx.cmdBuilder(),
                                ctx.regions(),
                                ctx.configManager(),
                                ctx.channelLibrary());
                    } finally {
                        Platform.runLater(() -> saveMdaButton.setDisable(false));
                    }
                })
                .exceptionally(t -> {
                    logger.error("MDA export task failed unexpectedly: {}", t.getMessage(), t);
                    return null;
                });
    }

    @Override
    public Node getNode() {
        return root;
    }

    /**
     * Returns the selected angles as {name -> tick}. Presence of a key = that angle is
     * acquired; the value is the tick (a plus/minus override when "Override default angles" is
     * on, else the configured tick). Consumed non-interactively by
     * {@code PPMModalityHandler.getRotationAnglesWithOverrides} -- there is no per-image popup.
     * Never null (always at least the checked angles), so PPM always drives its own selection.
     */
    @Override
    public Map<String, Double> getAngleOverrides() {
        boolean override = overrideAngles.isSelected();
        Map<String, Double> map = new HashMap<>();
        if (selMinus.isSelected()) {
            map.put("minus", override ? minusSpinner.getValue() : minusTick);
        }
        if (selZero.isSelected()) {
            map.put("zero", zeroTick);
        }
        if (selPlus.isSelected()) {
            map.put("plus", override ? plusSpinner.getValue() : plusTick);
        }
        if (selUncrossed.isSelected()) {
            map.put("uncrossed", uncrossedTick);
        }
        return map;
    }
}
