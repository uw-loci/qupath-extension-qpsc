package qupath.ext.qpsc.modality;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * Bounding-box dialog panel for the Brightfield modality.
 *
 * <p>Unlike fluorescence (per-channel exposures) or PPM (per-angle exposures),
 * brightfield uses a single exposure resolved at acquisition time by
 * {@link BrightfieldModalityHandler}. Previously the Bounded Acquisition dialog
 * showed nothing for brightfield, so the operator had no indication of which
 * exposure would be used and no way to correct an over-exposed ("blinding")
 * acquisition without leaving the dialog.
 *
 * <p>This panel:
 * <ul>
 *   <li>States the resolution rule the handler applies (background-collection
 *       exposure when background correction is on and calibrated, otherwise the
 *       last unified exposure).</li>
 *   <li>Offers an explicit "Override exposure" field so the operator can pin a
 *       known-good exposure for the run.</li>
 *   <li>Warns that an override bypasses the saved background/flat-field profile:
 *       the background reference was captured at the calibrated exposure, so it
 *       no longer matches and background correction should not be trusted for
 *       this run.</li>
 * </ul>
 *
 * <p>The override is surfaced through the standard
 * {@link ModalityHandler.BoundingBoxUI#getAngleOverrides()} mechanism under the
 * key {@code "exposure"}; {@link BrightfieldModalityHandler#applyAngleOverrides}
 * replaces the exposure on the single (angle=0) acquisition step.
 */
public class BrightfieldExposureBoundingBoxUI implements ModalityHandler.BoundingBoxUI {

    private static final Logger logger = LoggerFactory.getLogger(BrightfieldExposureBoundingBoxUI.class);

    /** Override-map key consumed by {@link BrightfieldModalityHandler#applyAngleOverrides}. */
    static final String OVERRIDE_KEY = "exposure";

    private final VBox root;
    private final CheckBox overrideCheck;
    private final TextField exposureField;
    private final Label warningLabel;

    /** Live "what will be used" readout: resolved profile / exposure / illumination. */
    private final Label readoutLabel;

    /** Supplies the current dialog selection so the readout can refresh on change. */
    private Supplier<AcquisitionReadout.Context> readoutContextSupplier;

    public BrightfieldExposureBoundingBoxUI() {
        root = new VBox(5);

        Label title = new Label("Brightfield Exposure");
        title.setStyle("-fx-font-weight: bold;");

        readoutLabel = new Label();
        readoutLabel.setStyle("-fx-font-size: 11px; -fx-font-family: monospace;");
        readoutLabel.setWrapText(true);

        Label explanation = new Label("Brightfield acquires a single exposure, resolved at acquisition time:\n"
                + "  - Background correction ON + calibrated  ->  the Background Collection exposure\n"
                + "  - otherwise  ->  the last unified exposure used\n"
                + "Run Background Collection for this objective/detector to set the calibrated value.");
        explanation.setStyle("-fx-font-size: 10px; -fx-text-fill: #888;");
        explanation.setWrapText(true);

        overrideCheck = new CheckBox("Override exposure (ms):");
        overrideCheck.setSelected(false);
        exposureField = new TextField();
        exposureField.setPromptText("e.g. 8.0");
        exposureField.setPrefWidth(90);
        exposureField.setDisable(true);
        exposureField.setTooltip(new Tooltip("Pin a specific camera exposure (milliseconds) for this acquisition,\n"
                + "bypassing the resolved/background value. Use to fix an over-exposed run."));
        overrideCheck.selectedProperty().addListener((obs, was, now) -> {
            exposureField.setDisable(!now);
            updateWarningVisibility();
            refreshReadout();
        });
        exposureField.textProperty().addListener((obs, was, now) -> refreshReadout());

        HBox overrideRow = new HBox(8, overrideCheck, exposureField);

        Label warning = new Label("Override bypasses the saved background/flat-field profile: the background "
                + "reference was captured at the calibrated exposure, so it no longer matches this exposure. "
                + "Background correction will not be valid for this run -- re-run Background Collection at the new "
                + "exposure if you need flat-field correction.");
        warning.setStyle("-fx-font-size: 10px; -fx-text-fill: #C62828;");
        warning.setWrapText(true);
        warning.setVisible(false);
        warning.setManaged(false);
        this.warningLabel = warning;

        root.getChildren().addAll(new Separator(), title, readoutLabel, explanation, overrideRow, warning);
    }

    private void updateWarningVisibility() {
        boolean show = overrideCheck.isSelected();
        warningLabel.setVisible(show);
        warningLabel.setManaged(show);
    }

    /**
     * Installs a supplier of the current dialog selection (modality/objective/detector)
     * so the readout reflects what the acquisition will actually use, and refreshes now.
     * The host dialog should also call {@link #refreshReadout()} when the objective or
     * detector changes.
     */
    public void installReadoutContext(Supplier<AcquisitionReadout.Context> supplier) {
        this.readoutContextSupplier = supplier;
        refreshReadout();
    }

    /** Recomputes the profile / exposure / illumination readout from the current selection. */
    public void refreshReadout() {
        if (readoutContextSupplier == null) {
            readoutLabel.setText("");
            return;
        }
        AcquisitionReadout.Context ctx = readoutContextSupplier.get();
        if (ctx == null || ctx.modality() == null || ctx.objective() == null) {
            readoutLabel.setText("");
            return;
        }
        MicroscopeConfigManager cfg = MicroscopeConfigManager.getInstanceIfAvailable();
        String profile = AcquisitionReadout.resolveProfileName(cfg, ctx.modality(), ctx.objective());
        Double intensity = AcquisitionReadout.resolveIlluminationIntensity(cfg, ctx.modality(), ctx.objective());
        AcquisitionReadout.Exposure exp =
                AcquisitionReadout.resolveBrightfieldExposure(cfg, ctx.modality(), ctx.objective(), ctx.detector());

        Double overrideMs = parseOverride();
        StringBuilder sb = new StringBuilder();
        sb.append("Profile:      ").append(profile != null ? profile : "(none resolved)");
        sb.append("\nExposure:     ");
        if (overrideMs != null) {
            sb.append(fmt(overrideMs)).append(" ms  (override)");
        } else if (exp.exposureMs() != null) {
            sb.append(fmt(exp.exposureMs()))
                    .append(" ms  (")
                    .append(exp.source())
                    .append(")");
        } else {
            sb.append(exp.source());
        }
        if (intensity != null) {
            sb.append("\nIllumination: ").append(fmt(intensity));
        }
        readoutLabel.setText(sb.toString());
        boolean warn = exp.warning() && overrideMs == null;
        readoutLabel.setStyle(
                warn
                        ? "-fx-font-size: 11px; -fx-font-family: monospace; -fx-text-fill: #C62828;"
                        : "-fx-font-size: 11px; -fx-font-family: monospace;");
    }

    /** Parses the override exposure silently (for the readout); null when absent/invalid. */
    private Double parseOverride() {
        if (!overrideCheck.isSelected()) {
            return null;
        }
        String text = exposureField.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            double ms = Double.parseDouble(text.trim());
            return ms > 0 ? ms : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String fmt(double v) {
        return (v == Math.rint(v)) ? String.valueOf((long) v) : String.valueOf(v);
    }

    @Override
    public Node getNode() {
        return root;
    }

    @Override
    public Map<String, Double> getAngleOverrides() {
        if (!overrideCheck.isSelected()) {
            return Map.of();
        }
        String text = exposureField.getText();
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            double ms = Double.parseDouble(text.trim());
            if (ms <= 0) {
                logger.warn("Ignoring non-positive brightfield exposure override: {}", text);
                return Map.of();
            }
            Map<String, Double> overrides = new HashMap<>();
            overrides.put(OVERRIDE_KEY, ms);
            return overrides;
        } catch (NumberFormatException e) {
            logger.warn("Ignoring unparseable brightfield exposure override: '{}'", text);
            return Map.of();
        }
    }
}
