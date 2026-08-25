package qupath.ext.qpsc.controller;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.FocusApproachValidationStore;
import qupath.ext.qpsc.utilities.FocusProfileAnalysis;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.dialogs.Dialogs;

/**
 * Guided measurement of how the focus metric behaves while approaching the sample from the
 * declared safe (retracted) Z, for one modality and objective.
 *
 * <h2>What it measures and why two scans</h2>
 * Approach-from-safe-Z autofocus commits to the first real peak it meets on the way in. Whether
 * that is safe depends on the rig: approaching from outside, the scan may cross a coverslip or
 * slide/air interface that produces a genuine contrast peak BEFORE the tissue, and at high
 * magnification the coverslip is the first thing the objective meets.
 *
 * <p>Rather than guess with a threshold, this runs the same scan twice -- once with the camera
 * over tissue, once over bare slide. A peak present in BOTH cannot be tissue, because there was
 * no tissue in the second scan. That makes the coverslip question a measurement.
 *
 * <h2>Why the highest-magnification objective</h2>
 * It has the shortest working distance, so a safe Z clear for it is clear for everything else,
 * and the narrowest focus peak, so a profile that is clean at 40x is cleaner at 10x. Validating
 * the worst case licenses the rest.
 *
 * <h2>What it produces</h2>
 * Besides a verdict: whether the approach must gate on tissue detection, the focus-peak width
 * (which bounds how fast the approach may scan without stepping over focus), the safe-Z-to-focus
 * distance, and the exposure and illumination the profile was measured at -- because the focus
 * metric is an intensity spread, so changing those rescales it and enough of a change saturates
 * the sensor and flattens the peak entirely.
 */
public final class FocusApproachValidationWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(FocusApproachValidationWorkflow.class);

    /**
     * How far past the operator's focus the scan continues, so the far side of the peak is
     * captured and its width can be measured. This is the one time the stage deliberately
     * travels past focus toward the sample; it is supervised and still bounded by
     * {@code stage.limits.z_um}.
     */
    private static final double PAST_FOCUS_MARGIN_UM = 30.0;

    private FocusApproachValidationWorkflow() {}

    /** Entry point from the menu. */
    public static void run() {
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        if (configPath == null || configPath.isBlank()) {
            Dialogs.showErrorMessage("Focus Approach Validation", "No microscope configuration is selected.");
            return;
        }
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
        MicroscopeController controller = MicroscopeController.getInstance();
        if (controller == null || !controller.isConnected()) {
            Dialogs.showErrorMessage(
                    "Focus Approach Validation", "Connect to the microscope server before running this.");
            return;
        }

        String modality = qupath.ext.qpsc.state.ModalityState.getInstance().getModality();
        String objective = TestAutofocusWorkflow.getCurrentObjective(mgr);
        String scope = mgr.getString("microscope", "name");
        Double safeZ = mgr.getSafeZUm(null, modality);

        if (safeZ == null) {
            Dialogs.showErrorMessage(
                    "Focus Approach Validation",
                    "No safe Z is configured for this microscope.\n\n"
                            + "Add stage.safe_z_um to the microscope YAML (optionally per insert and modality "
                            + "under stage.inserts.configurations.<insert>.safe_z_um). It must be a position "
                            + "where the objective is clearly clear of the sample -- there is no safe default, "
                            + "because a guessed retraction could be on the wrong side of the sample.");
            return;
        }
        String safeZProblem = mgr.validateSafeZUm(null, modality);
        if (safeZProblem != null) {
            Dialogs.showErrorMessage("Focus Approach Validation", "Safe Z is not usable: " + safeZProblem);
            return;
        }

        if (!confirmPlan(modality, objective, safeZ)) {
            return;
        }

        // Scan 1: over tissue. The operator's manual focus is the reference the measured peak
        // is checked against.
        Double manualFocusZ = promptAndReadZ(
                controller,
                "Step 1 of 2: over TISSUE",
                "Move the stage in XY so the camera is over TISSUE, then focus on it by hand.\n\n"
                        + "Click OK when the tissue is sharply in focus. The stage will retract to "
                        + safeZ + " um and scan back in past this focus.");
        if (manualFocusZ == null) {
            return;
        }
        double[][] tissueProfile = captureProfile(mgr, controller, configPath, modality, safeZ, manualFocusZ);
        if (tissueProfile == null) {
            Dialogs.showErrorMessage("Focus Approach Validation", "The over-tissue scan produced no usable profile.");
            return;
        }

        // Scan 2: same Z range, no tissue. Anything that peaks here is a surface.
        Double bgConfirm = promptAndReadZ(
                controller,
                "Step 2 of 2: NOT over tissue",
                "Now move the stage in XY to a part of the SAME slide with NO TISSUE -- bare glass "
                        + "under the coverslip.\n\nDo NOT change focus, exposure or illumination. Click OK "
                        + "to run the identical scan there. Any peak that shows up in both scans is a "
                        + "surface rather than the sample.");
        if (bgConfirm == null) {
            return;
        }
        double[][] bgProfile = captureProfile(mgr, controller, configPath, modality, safeZ, manualFocusZ);
        if (bgProfile == null) {
            Dialogs.showErrorMessage("Focus Approach Validation", "The background scan produced no usable profile.");
            return;
        }

        FocusProfileAnalysis.PairVerdict verdict = FocusProfileAnalysis.analysePair(
                tissueProfile[0], tissueProfile[1], bgProfile[0], bgProfile[1], manualFocusZ);

        double exposureMs = readExposure(controller);
        List<Double> surfaceZs = new ArrayList<>();
        verdict.surfacePeaks().forEach(pk -> surfaceZs.add(pk.z()));

        FocusApproachValidationStore.Record record = new FocusApproachValidationStore.Record(
                scope,
                modality,
                objective,
                verdict.usable(),
                verdict.requiresTissueGate(),
                safeZ,
                verdict.tissue().approachDistanceUm(),
                verdict.tissue().peakWidthUm(),
                List.copyOf(surfaceZs),
                exposureMs,
                Double.NaN,
                verdict.reasons(),
                Instant.now().toString());
        try {
            FocusApproachValidationStore.save(record);
        } catch (Exception e) {
            logger.error("Could not save the focus-approach validation", e);
            Dialogs.showErrorMessage("Focus Approach Validation", "Could not save the result: " + e.getMessage());
        }
        showVerdict(verdict, record);
    }

    /** Explains what is about to happen and what the result depends on. */
    private static boolean confirmPlan(String modality, String objective, double safeZ) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Focus Approach Validation");
        alert.setHeaderText("Measure how focus behaves approaching from the safe Z");

        TextArea body = new TextArea("This measures whether autofocus can safely approach the sample from the "
                + "retracted position, for ONE modality and objective.\n\n"
                + "Modality:  " + modality + "\n"
                + "Objective: " + objective + "\n"
                + "Safe Z:    " + safeZ + " um\n\n"
                + "Mount the HIGHEST-magnification objective you use for this kind of scanning. It has the "
                + "shortest working distance and the narrowest focus peak, so a result that is clean there "
                + "is clean for the lower magnifications too.\n\n"
                + "You will be asked to do two things:\n"
                + "  1. Put the camera OVER TISSUE and focus by hand.\n"
                + "  2. Move to a BARE part of the same slide, changing nothing else.\n\n"
                + "The same scan runs in both positions. Any peak that appears in both is a surface "
                + "(coverslip, slide face) rather than tissue -- that is what the second scan is for.\n\n"
                + "The stage will retract to the safe Z and scan in, continuing about "
                + PAST_FOCUS_MARGIN_UM + " um past your focus so the far side of the peak is captured.\n\n"
                + "IMPORTANT -- exposure and illumination: the focus metric is an intensity measure, so it "
                + "scales with both. Set them to what you actually acquire with before starting, and do not "
                + "change them between the two scans. If they change substantially later, this result stops "
                + "applying and QPSC will tell you to re-run it.");
        body.setEditable(false);
        body.setWrapText(true);
        body.setPrefRowCount(22);
        body.setPrefColumnCount(64);
        alert.getDialogPane().setContent(new VBox(body));
        alert.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    /** Prompts, then reads the stage Z the operator settled on. Null when cancelled. */
    private static Double promptAndReadZ(MicroscopeController controller, String header, String instruction) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Focus Approach Validation");
        alert.setHeaderText(header);
        Label label = new Label(instruction);
        label.setWrapText(true);
        label.setMaxWidth(520);
        alert.getDialogPane().setContent(new VBox(label));
        alert.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        if (alert.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) {
            return null;
        }
        try {
            return controller.getStagePositionZ();
        } catch (Exception e) {
            Dialogs.showErrorMessage("Focus Approach Validation", "Could not read the stage Z: " + e.getMessage());
            return null;
        }
    }

    /**
     * Retracts to the safe Z and runs ONE scan in to just past the operator's focus, returning
     * the captured profile.
     *
     * <p>{@code maxAttempts = 1} deliberately: this is a characterisation, not a focus hunt.
     * The server's multi-attempt escalation would move the window and produce a profile of
     * somewhere other than the range asked for.
     */
    private static double[][] captureProfile(
            MicroscopeConfigManager mgr,
            MicroscopeController controller,
            String configPath,
            String modality,
            double safeZ,
            double manualFocusZ) {
        // The scan runs from the safe Z to just past focus, so it is centred between them.
        double direction = Math.signum(manualFocusZ - safeZ);
        double farEnd = manualFocusZ + direction * PAST_FOCUS_MARGIN_UM;
        double range = Math.abs(farEnd - safeZ);
        double centre = (safeZ + farEnd) / 2.0;
        try {
            controller.moveStageZ(safeZ);
            controller.moveStageZ(centre);
            MicroscopeSocketClient.StreamingFocusResult result =
                    controller.getSocketClient().streamingFocus(configPath, null, modality, range, true, 1);
            if (result == null || result.dumpPath == null) {
                logger.warn("Focus-approach scan returned no dump path");
                return null;
            }
            Path samples = Paths.get(result.dumpPath).resolve("samples.csv");
            double[][] profile = FocusApproachValidationStore.parseSamplesCsv(samples);
            if (profile == null) {
                logger.warn("Focus-approach scan dump had no usable samples.csv at {}", samples);
            }
            return profile;
        } catch (Exception e) {
            logger.error("Focus-approach scan failed", e);
            Dialogs.showErrorMessage("Focus Approach Validation", "The scan failed: " + e.getMessage());
            return null;
        }
    }

    /** Current camera exposure, or NaN when it cannot be read. */
    private static double readExposure(MicroscopeController controller) {
        try {
            MicroscopeSocketClient.ExposuresResult exposures = controller.getExposures();
            if (exposures != null) {
                // The unified value is the one the scan actually ran at; per-channel splits
                // are a white-balance concern, not a focus-metric one.
                return exposures.unified();
            }
        } catch (Exception e) {
            logger.debug("Could not read exposure for the validation record: {}", e.getMessage());
        }
        return Double.NaN;
    }

    /** Shows what was measured and what it licenses. */
    private static void showVerdict(
            FocusProfileAnalysis.PairVerdict verdict, FocusApproachValidationStore.Record record) {
        StringBuilder sb = new StringBuilder();
        sb.append(verdict.usable() ? "PASSED\n\n" : "FAILED\n\n");
        sb.append(String.format(
                "Focus peak:        Z = %.1f um%n", verdict.tissue().globalMaxZ()));
        sb.append(String.format("Approach distance: %.1f um from the safe Z%n", record.approachDistanceUm()));
        if (!Double.isNaN(record.peakWidthUm())) {
            sb.append(String.format("Peak width (FWHM): %.1f um%n", record.peakWidthUm()));
        }
        if (!Double.isNaN(record.exposureMs())) {
            sb.append(String.format("Measured at:       %.2f ms exposure%n", record.exposureMs()));
        }
        sb.append('\n');
        if (verdict.requiresTissueGate()) {
            sb.append("A surface peak sits BEFORE focus, so the approach must stop only on a peak where "
                    + "tissue is detected -- committing to the first peak would land on glass.\n\n");
        } else if (verdict.usable()) {
            sb.append("No surface peaks before focus: the first peak on the way in is the sample.\n\n");
        }
        if (!verdict.reasons().isEmpty()) {
            sb.append("Findings:\n");
            for (String r : verdict.reasons()) {
                sb.append("  - ").append(r).append('\n');
            }
            sb.append('\n');
        }
        sb.append("Saved for ")
                .append(record.microscope())
                .append(" / ")
                .append(record.modality())
                .append(" / ")
                .append(record.objective())
                .append(". Re-run this if the safe Z, exposure or illumination change substantially.");

        Platform.runLater(() -> {
            Alert alert = new Alert(verdict.usable() ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING);
            alert.setTitle("Focus Approach Validation");
            alert.setHeaderText(
                    verdict.usable()
                            ? "Focus approach characterised"
                            : "Focus approach is NOT usable for this combination");
            TextArea body = new TextArea(sb.toString());
            body.setEditable(false);
            body.setWrapText(true);
            body.setPrefRowCount(18);
            body.setPrefColumnCount(64);
            alert.getDialogPane().setContent(new VBox(body));
            UIFunctions.showAlertOverParent(alert, null);
        });
    }
}
