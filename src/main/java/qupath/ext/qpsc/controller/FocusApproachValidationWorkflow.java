package qupath.ext.qpsc.controller;

import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.FocusApproachValidationStore;
import qupath.ext.qpsc.utilities.FocusProfileAnalysis;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

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
        String scope = mgr.getString("microscope", "name");
        String objective = resolveMountedObjective(mgr, controller);
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

        // The record is KEYED on the objective, so a wrong value here does not merely mislabel
        // the result -- it licenses the wrong objective and leaves the mounted one unlicensed.
        // Hence a picker rather than a read-only line: the resolution below is a good default,
        // not something to be trusted silently.
        objective = confirmPlan(mgr, modality, objective, safeZ);
        if (objective == null) {
            return;
        }

        // Scan 1: over tissue. The operator's manual focus is the reference the measured peak
        // is checked against.
        Double manualFocusZ = promptAndReadZ(
                controller,
                "Step 1 of 2: over TISSUE",
                "Move the stage in XY so the camera is over TISSUE, then focus by hand "
                        + "USING LIVE MODE -- judge focus from the live camera image, NOT the eyepiece.\n\n"
                        + "The eyepiece and the camera port are not necessarily parfocal, so an eyepiece "
                        + "focus can sit tens of microns from where the camera is sharp. Everything measured "
                        + "here comes from camera frames, and this Z is the reference the measured peak is "
                        + "checked against -- an eyepiece focus can fail a perfectly good microscope.\n\n"
                        + "Click OK when the tissue is sharply in focus IN LIVE MODE. The stage will retract to "
                        + safeZ + " um and scan back in past this focus.");
        if (manualFocusZ == null) {
            return;
        }
        // The retraction is about to be exercised for the FIRST time, by moving to a number a
        // human typed. If it is on the wrong side of the sample, this move drives the objective
        // into the slide -- before anything has been measured, and it is the one move Cancel
        // cannot interrupt.
        //
        // Two guards, in order of preference. The MECHANICAL one runs first: with a declared
        // retract direction and the focus the operator just set, a wrong-side value is provable
        // and is refused outright. stage.limits.z_um cannot make this call -- PPM's -500 sat
        // comfortably inside [-720, 1000] while pointing at the objective.
        String wrongSide = mgr.validateSafeZDirection(safeZ, manualFocusZ);
        if (wrongSide != null) {
            logger.error("Focus-approach validation refused: {}", wrongSide);
            Dialogs.showErrorMessage(
                    "Focus Approach Validation",
                    "Refusing to move.\n\n" + wrongSide
                            + "\n\nFix stage.safe_z_um (or the per-insert override) in the microscope YAML "
                            + "before running this. Nothing has moved.");
            return;
        }
        // The HUMAN one is the fallback for scopes that have not declared a direction yet, and
        // a cross-check where they have.
        if (!confirmRetractionDirection(manualFocusZ, safeZ)) {
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
                verdict.tissue().globalMaxZ(),
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

    /**
     * Best guess at which objective is actually mounted.
     *
     * <p>NOT {@code TestAutofocusWorkflow.getCurrentObjective}: that reads
     * {@code microscope.objective_in_use}, which is never populated at runtime on these rigs,
     * and then falls back to the FIRST objective in the hardware list. On PPM that reports 10x
     * whatever is mounted -- observed in the 2026-08-14 logs on every autofocus call, and
     * observed again in this dialog.
     *
     * <p>Order: the pixel size MicroManager reports (the server resolves the objective the same
     * way, so the two agree), then the session's objective state, then the stale config value.
     *
     * @return the best available objective ID, or null when nothing resolves
     */
    private static String resolveMountedObjective(MicroscopeConfigManager mgr, MicroscopeController controller) {
        try {
            double pixelSize = controller.getSocketClient().getMicroscopePixelSize();
            var match = mgr.findHardwareByPixelSize(pixelSize, MicroscopeConfigManager.DEFAULT_PIXEL_SIZE_TOLERANCE_UM);
            if (match.isPresent()) {
                logger.info(
                        "Focus-approach: objective resolved as {} via MicroManager pixel size {} um/px",
                        match.get().objectiveId(),
                        pixelSize);
                return match.get().objectiveId();
            }
            logger.warn(
                    "Focus-approach: MicroManager pixel size {} um/px matched no configured objective; "
                            + "falling back to session state",
                    pixelSize);
        } catch (Exception e) {
            logger.warn("Focus-approach: could not read the MicroManager pixel size ({})", e.getMessage());
        }
        String fromState = qupath.ext.qpsc.state.ObjectiveState.getInstance().getObjective();
        if (fromState != null && !fromState.isEmpty()) {
            return fromState;
        }
        return TestAutofocusWorkflow.getCurrentObjective(mgr);
    }

    /**
     * Explains what is about to happen, and lets the operator confirm or correct the objective.
     *
     * @return the chosen objective ID, or null if cancelled
     */
    private static String confirmPlan(
            MicroscopeConfigManager mgr, String modality, String resolvedObjective, double safeZ) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Focus Approach Validation");
        alert.setHeaderText("Measure how focus behaves approaching from the safe Z");
        // Non-modal: this dialog tells the operator to set exposure and illumination, so it
        // must not block the controls that do that. showAndWait still waits for the answer.
        makeNonBlocking(alert);

        // Objective picker, defaulted to the pixel-size match. The result record is keyed on
        // this, so a silently-wrong value licenses the wrong objective AND leaves the mounted
        // one unlicensed -- the operator must be able to see and correct it.
        var objectiveIds = mgr.getAvailableObjectives();
        var objectiveNames = mgr.getObjectiveFriendlyNames(objectiveIds);
        ComboBox<String> objectiveBox = new ComboBox<>();
        objectiveBox
                .getItems()
                .setAll(objectiveIds.stream()
                        .map(id -> objectiveNames.get(id) + " (" + id + ")")
                        .sorted()
                        .toList());
        for (String item : objectiveBox.getItems()) {
            if (item.contains("(" + resolvedObjective + ")")) {
                objectiveBox.setValue(item);
            }
        }
        if (objectiveBox.getValue() == null && !objectiveBox.getItems().isEmpty()) {
            objectiveBox.setValue(objectiveBox.getItems().get(0));
        }
        Label objectiveNote = new Label("Confirm the objective that is actually mounted -- the result is stored "
                + "against it, and a wrong value licenses the wrong objective.");
        objectiveNote.setWrapText(true);
        objectiveNote.setMaxWidth(520);
        objectiveNote.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        HBox objectiveRow = new HBox(8, new Label("Objective:"), objectiveBox);
        objectiveRow.setStyle("-fx-alignment: center-left;");

        TextArea body = new TextArea("This measures whether autofocus can safely approach the sample from the "
                + "retracted position, for ONE modality and objective.\n\n"
                + "Modality:  " + modality + "\n"
                + "Safe Z:    " + safeZ + " um\n\n"
                + "Mount the HIGHEST-magnification objective you use for this kind of scanning. It has the "
                + "shortest working distance and the narrowest focus peak, so a result that is clean there "
                + "is clean for the lower magnifications too.\n\n"
                + "You will be asked to do two things:\n"
                + "  1. Put the camera OVER TISSUE and focus by hand IN LIVE MODE (not the eyepiece --\n"
                + "     the two are not necessarily parfocal, and every measurement here is made on\n"
                + "     camera frames).\n"
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
        VBox content = new VBox(10, body, objectiveRow, objectiveNote);
        alert.getDialogPane().setContent(content);
        alert.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        if (alert.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) {
            return null;
        }
        String chosen = objectiveBox.getValue();
        if (chosen == null) {
            return null;
        }
        int open = chosen.lastIndexOf('(');
        int close = chosen.lastIndexOf(')');
        return (open >= 0 && close > open) ? chosen.substring(open + 1, close) : chosen;
    }

    /**
     * Confirms, with real numbers, that moving from the focused position to the declared safe Z
     * travels AWAY from the sample.
     *
     * <p>Nothing in the software can determine this. Which sign retracts depends on the rig,
     * and the safe Z is operator-entered, so a transposed sign or a value from a different
     * insert sends the objective straight into the slide. The stage-limit check cannot catch it
     * -- a wrong-side value is usually still comfortably inside the envelope.
     *
     * <p>Showing the distance and direction rather than asking "is the safe Z correct?" is the
     * point: the operator can sanity-check 200 um in the negative direction against what they
     * can see, where they cannot meaningfully re-check a number they typed earlier.
     *
     * @param focusZ the Z the operator just focused tissue at
     * @param safeZ  the declared retracted position
     * @return true to proceed
     */
    private static boolean confirmRetractionDirection(double focusZ, double safeZ) {
        double delta = safeZ - focusZ;
        String direction = delta >= 0 ? "POSITIVE (+Z)" : "NEGATIVE (-Z)";
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Focus Approach Validation");
        alert.setHeaderText("Confirm the retraction direction before the stage moves");
        // Non-modal so the operator can act on its own advice: verify the retraction by hand in
        // the Live Viewer before committing.
        makeNonBlocking(alert);
        TextArea body = new TextArea(String.format(
                "The stage is about to move from your focus to the declared safe Z:%n%n"
                        + "    focused at   %.1f um%n"
                        + "    safe Z       %.1f um%n"
                        + "    movement     %.1f um in the %s direction%n%n"
                        + "Confirm that this direction moves the objective AWAY from the sample.%n%n"
                        + "This cannot be checked in software. Which sign retracts depends on the "
                        + "microscope, and the safe Z was entered by hand -- if it is on the wrong side, "
                        + "or belongs to a different stage insert, this move drives the objective into "
                        + "the slide. The stage-limit check will not catch that: a wrong-side value is "
                        + "usually still well inside the configured envelope.%n%n"
                        + "If you are not certain, cancel and verify by retracting manually in the Live "
                        + "Viewer first.",
                focusZ, safeZ, Math.abs(delta), direction));
        body.setEditable(false);
        body.setWrapText(true);
        body.setPrefRowCount(16);
        body.setPrefColumnCount(64);
        alert.getDialogPane().setContent(new VBox(body));
        ButtonType proceed = new ButtonType("Direction is correct -- proceed");
        alert.getButtonTypes().setAll(proceed, ButtonType.CANCEL);
        return alert.showAndWait().filter(b -> b == proceed).isPresent();
    }

    /** Prompts, then reads the stage Z the operator settled on. Null when cancelled. */
    private static Double promptAndReadZ(MicroscopeController controller, String header, String instruction) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Focus Approach Validation");
        alert.setHeaderText(header);
        // These prompts ask the operator to move the stage in XY and focus by hand. An
        // application-modal dialog would block exactly the controls it is asking them to use.
        makeNonBlocking(alert);
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

        double direction = Math.signum(manualFocusZ - safeZ);
        double farEnd = manualFocusZ + direction * PAST_FOCUS_MARGIN_UM;
        double range = Math.abs(farEnd - safeZ);
        double centre = (safeZ + farEnd) / 2.0;

        // The scan moves the stage toward the sample for tens of seconds. Running it on the FX
        // thread would freeze the UI for the whole traverse with no way to intervene, which is
        // exactly the wrong property for the one tool whose job is to make that motion safe. So
        // it runs on a daemon thread behind a Cancel the operator can actually reach.
        final double[][][] holder = new double[1][][];
        final boolean[] cancelled = {false};

        Stage progress = new Stage();
        progress.initModality(Modality.APPLICATION_MODAL);
        progress.setTitle("Focus Approach Validation");
        progress.setAlwaysOnTop(true);

        Label status = new Label(String.format(
                "Retracting to %.1f um, then scanning %.1f um toward the sample.%n%n"
                        + "Watch the stage. Cancel stops the scan and returns to the safe Z.",
                safeZ, range));
        status.setWrapText(true);
        status.setMaxWidth(460);
        ProgressBar bar = new ProgressBar();
        bar.setPrefWidth(460);

        Button cancelButton = new Button("CANCEL - stop the scan");
        cancelButton.setStyle("-fx-base: #c62828; -fx-font-weight: bold;");
        Runnable abort = () -> {
            cancelled[0] = true;
            status.setText("Cancelling -- sending abort and returning to the safe Z...");
            cancelButton.setDisable(true);
            Thread aborter = new Thread(
                    () -> {
                        try {
                            controller.getSocketClient().abortStreamingFocus();
                        } catch (Exception e) {
                            logger.warn("Focus-approach cancel: abort failed: {}", e.getMessage());
                        }
                        try {
                            controller.moveStageZ(safeZ);
                        } catch (Exception e) {
                            logger.error("Focus-approach cancel: could NOT return to the safe Z: {}", e.getMessage());
                        }
                    },
                    "FocusApproach-Abort");
            aborter.setDaemon(true);
            aborter.start();
        };
        cancelButton.setOnAction(e -> abort.run());
        // The window close button must abort too, not orphan a moving stage.
        progress.setOnCloseRequest(e -> {
            e.consume();
            abort.run();
        });

        VBox box = new VBox(12, status, bar, cancelButton);
        box.setPadding(new Insets(18));
        progress.setScene(new Scene(box));

        Thread scanThread = new Thread(
                () -> {
                    try {
                        controller.moveStageZ(safeZ);
                        if (!cancelled[0]) {
                            // Name BOTH endpoints. Asking for a range alone lets the server
                            // derive its window from the current Z, which is the safe Z -- so
                            // the traverse straddles the retracted position instead of running
                            // from it toward the sample.
                            MicroscopeSocketClient.StreamingFocusResult result = controller
                                    .getSocketClient()
                                    .streamingFocus(
                                            configPath,
                                            null,
                                            modality,
                                            range,
                                            true,
                                            1,
                                            Double.NaN,
                                            Double.NaN,
                                            false,
                                            safeZ,
                                            farEnd);
                            if (result != null && result.dumpPath != null) {
                                holder[0] = FocusApproachValidationStore.parseDumpDirectory(Paths.get(result.dumpPath));
                            } else {
                                logger.warn("Focus-approach scan returned no dump path");
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Focus-approach scan failed", e);
                    } finally {
                        Platform.runLater(progress::close);
                    }
                },
                "FocusApproach-Scan");
        scanThread.setDaemon(true);
        scanThread.start();

        progress.showAndWait();
        if (cancelled[0]) {
            logger.info("Focus-approach scan cancelled by the operator");
            return null;
        }
        // centre is computed above for symmetry with the server's window maths; the server
        // derives its own window from --range around the current Z, which is the safe Z here.
        logger.debug("Focus-approach scan window centred near {}", centre);
        return holder[0];
    }

    /**
     * Makes an alert float over QuPath without taking input away from it.
     *
     * <p>Every prompt in this workflow asks the operator to do something with the microscope
     * UI -- set exposure, move in XY, focus by hand, or go and verify a retraction in the Live
     * Viewer. An application-modal dialog blocks precisely those controls, so {@code showAndWait}
     * would sit waiting for an action it had made impossible. {@link Modality#NONE} still lets
     * {@code showAndWait} block this workflow's own flow while leaving the rest of the UI live,
     * which is the same pattern {@code UIFunctions.stageAlignmentConfirmAsync} uses.
     *
     * <p>The scan progress window is deliberately NOT made non-blocking: nothing should be
     * driving the stage while a scan is traversing toward the sample.
     */
    private static void makeNonBlocking(Alert alert) {
        alert.initModality(Modality.NONE);
        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui != null && gui.getStage() != null) {
            alert.initOwner(gui.getStage());
        }
        alert.getDialogPane().getScene().getWindow();
        if (alert.getDialogPane().getScene().getWindow() instanceof Stage stage) {
            stage.setAlwaysOnTop(true);
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
