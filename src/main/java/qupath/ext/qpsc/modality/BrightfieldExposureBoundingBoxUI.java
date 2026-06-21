package qupath.ext.qpsc.modality;

import java.util.HashMap;
import java.util.Map;
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

    public BrightfieldExposureBoundingBoxUI() {
        root = new VBox(5);

        Label title = new Label("Brightfield Exposure");
        title.setStyle("-fx-font-weight: bold;");

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
        });

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

        root.getChildren().addAll(new Separator(), title, explanation, overrideRow, warning);
    }

    private void updateWarningVisibility() {
        boolean show = overrideCheck.isSelected();
        warningLabel.setVisible(show);
        warningLabel.setManaged(show);
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
