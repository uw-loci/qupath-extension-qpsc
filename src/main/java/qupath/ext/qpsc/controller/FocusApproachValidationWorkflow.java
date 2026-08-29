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
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.ThemeColors;
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
        // The instructions ask the operator to judge focus from the LIVE image, so make sure
        // there is one. The scan itself does not need this -- the server starts its own
        // sequence when none is running and tears it down after -- but a dark viewer while
        // being told to focus in Live Mode is a confusing place to start.
        if (!qupath.ext.qpsc.ui.liveviewer.LiveViewerWindow.isStreamingActive()) {
            logger.info("Focus-approach: Live Viewer not streaming; opening it");
            qupath.ext.qpsc.ui.liveviewer.LiveViewerWindow.show();
        }

        Plan plan = confirmPlan(mgr, modality, objective, safeZ);
        if (plan == null) {
            return;
        }
        objective = plan.objective();

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

        // Which focus metric the server actually measured with. The verdict is only meaningful
        // FOR THAT METRIC -- it says the metric peaks at the sample, or does not -- so naming it
        // is part of the result, not decoration.
        String[] metricUsed = {null};
        double[][] tissueProfile = captureProfile(
                mgr, controller, configPath, modality, safeZ, manualFocusZ, "tissue", plan.saveFrames(), metricUsed);
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
        double[][] bgProfile = captureProfile(
                mgr, controller, configPath, modality, safeZ, manualFocusZ, "blank", plan.saveFrames(), metricUsed);
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
        // Saved by showVerdict, AFTER the operator has had a chance to override the tissue
        // gate. The measurement is the recommendation, not the decision: it is taken on one
        // slide at one XY, and whether a surface peak shows up there does not settle whether
        // one shows up on every slide the objective will ever see.
        showVerdict(verdict, record, metricUsed[0]);
    }

    /** The same record with a different tissue-gate decision. */
    private static FocusApproachValidationStore.Record withTissueGate(
            FocusApproachValidationStore.Record r, boolean requiresTissueGate) {
        return new FocusApproachValidationStore.Record(
                r.microscope(),
                r.modality(),
                r.objective(),
                r.usable(),
                requiresTissueGate,
                r.safeZUm(),
                r.focusZUm(),
                r.approachDistanceUm(),
                r.peakWidthUm(),
                r.falsePeakZs(),
                r.exposureMs(),
                r.illumination(),
                r.reasons(),
                r.timestamp());
    }

    /**
     * Which objective is actually mounted.
     *
     * <p>This resolution order -- MicroManager's reported pixel size, then the session's
     * objective state, then the config -- used to live here BECAUSE
     * {@code TestAutofocusWorkflow.getCurrentObjective} got it wrong. It no longer does: the
     * order moved there, so every caller resolves the objective the same way instead of this
     * dialog alone being correct. Kept as a named method because the call reads better here.
     *
     * @return the best available objective ID, or null when nothing resolves
     */
    private static String resolveMountedObjective(MicroscopeConfigManager mgr, MicroscopeController controller) {
        return TestAutofocusWorkflow.getCurrentObjective(mgr);
    }

    /**
     * Explains what is about to happen, and lets the operator confirm or correct the objective.
     *
     * @return the chosen objective ID, or null if cancelled
     */
    private static Plan confirmPlan(
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
        objectiveNote.setStyle("-fx-font-size: 11px; -fx-text-fill: " + ThemeColors.MUTED + ";");
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
        // Off by default. Each scan captures several hundred frames, so keeping them costs
        // roughly 750 MB per scan and 1.5 GB per validation run -- and the verdict is computed
        // entirely from samples.csv, which is written either way. Frames are only worth keeping
        // when the curve looks wrong and someone wants to see what the camera saw.
        CheckBox saveImages = new CheckBox("Save images (one TIF per sample)");
        saveImages.setSelected(false);
        Label saveImagesNote = new Label("Off by default: roughly 750 MB per scan. The measurement and the "
                + "verdict come from the CSV trace, which is always saved. Turn this on only to inspect "
                + "individual frames afterwards.");
        saveImagesNote.setWrapText(true);
        saveImagesNote.setMaxWidth(520);
        saveImagesNote.setStyle("-fx-font-size: 11px; -fx-text-fill: " + ThemeColors.MUTED + ";");

        VBox content = new VBox(10, body, objectiveRow, objectiveNote, saveImages, saveImagesNote);
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
        String objectiveId = (open >= 0 && close > open) ? chosen.substring(open + 1, close) : chosen;
        return new Plan(objectiveId, saveImages.isSelected());
    }

    /**
     * What the operator confirmed before the run starts.
     *
     * @param objective  the objective actually mounted; the result record is keyed on it
     * @param saveFrames whether to keep the per-sample TIFs alongside the CSV trace
     */
    private record Plan(String objective, boolean saveFrames) {}

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
            double manualFocusZ,
            String scanLabel,
            boolean saveFrames,
            String[] metricOut) {

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

        // Live feedback: mirror the Live Viewer's per-frame focus trace onto a vertical plot
        // oriented the way the stage travels -- start at the top, filling downward toward the
        // sample. The samples are the LIVE metric, not the server's; the plot names which.
        qupath.ext.qpsc.ui.FocusApproachPlot plot =
                new qupath.ext.qpsc.ui.FocusApproachPlot(safeZ, farEnd, manualFocusZ);
        java.util.List<double[]> livePoints = new java.util.ArrayList<>();
        javafx.animation.Timeline poller =
                new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.millis(250), ev -> {
                    var trace = qupath.ext.qpsc.ui.liveviewer.LiveViewerWindow.getLiveFocusTrace();
                    if (trace != null) {
                        // Accumulate rather than snapshot-and-replace: the Live Viewer's model
                        // caps and evicts by age, which over a 250 um traverse would drop the
                        // early part of the curve just as the interesting end arrives.
                        for (double[] sample : trace.snapshot()) {
                            boolean known =
                                    livePoints.stream().anyMatch(existing -> Math.abs(existing[0] - sample[0]) < 0.05);
                            if (!known) {
                                livePoints.add(new double[] {sample[0], sample[1]});
                            }
                        }
                        livePoints.sort((a2, b2) -> Double.compare(Math.abs(a2[0] - safeZ), Math.abs(b2[0] - safeZ)));
                        plot.setSamples(livePoints, "brenner_gradient (live frames)", false);
                    }
                    try {
                        plot.setCurrentZ(controller.getStagePositionZ());
                    } catch (Exception ignored) {
                        // Position polling is cosmetic; never let it interrupt the scan.
                    }
                }));
        poller.setCycleCount(javafx.animation.Animation.INDEFINITE);
        poller.play();

        VBox box = new VBox(12, status, bar, plot, cancelButton);
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
                                            farEnd,
                                            scanLabel,
                                            saveFrames);
                            if (result != null && result.dumpPath != null) {
                                java.nio.file.Path dumpRoot = Paths.get(result.dumpPath);
                                holder[0] = FocusApproachValidationStore.parseDumpDirectory(dumpRoot);
                                // Replace the live approximation with what the server actually
                                // measured, named for ITS metric. Leaving the client-side curve
                                // up would show one metric under another's name.
                                if (holder[0] != null) {
                                    String serverMetric = FocusApproachValidationStore.readDumpMetricName(dumpRoot);
                                    java.util.List<double[]> pts = new java.util.ArrayList<>();
                                    for (int i = 0; i < holder[0][0].length; i++) {
                                        pts.add(new double[] {holder[0][0][i], holder[0][1][i]});
                                    }
                                    Platform.runLater(() -> {
                                        poller.stop();
                                        plot.setSamples(
                                                pts, serverMetric == null ? "focus metric" : serverMetric, true);
                                        if (metricOut != null && serverMetric != null) {
                                            metricOut[0] = serverMetric;
                                        }
                                    });
                                }
                                // The server leaves the stage at the traverse start (the safe Z),
                                // which is right for it -- it has no idea what the caller was
                                // doing. Here we DO know: the operator focused on this field, and
                                // parking 250 um away means re-focusing before every re-run. The
                                // focus Z is a position we were just at, so returning to it is a
                                // plain move to somewhere known good, not a search.
                                try {
                                    controller.moveStageZ(manualFocusZ);
                                    logger.info("Focus-approach: returned to the focus Z {} um", manualFocusZ);
                                } catch (Exception e) {
                                    logger.warn(
                                            "Focus-approach: could not return to the focus Z ({}); "
                                                    + "stage left at the safe Z",
                                            e.getMessage());
                                }
                            } else {
                                logger.warn("Focus-approach scan returned no dump path");
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Focus-approach scan failed", e);
                    } finally {
                        Platform.runLater(() -> {
                            poller.stop();
                            if (holder[0] != null && !cancelled[0]) {
                                // Hold the window open on the finished curve. Closing the
                                // instant the scan returns would flash the server profile past
                                // the operator, and this plot is the only view of the traverse
                                // they get before the verdict.
                                status.setText("Traverse complete. Review the curve, then continue.");
                                cancelButton.setText("Continue");
                                cancelButton.setDisable(false);
                                cancelButton.setOnAction(ev -> progress.close());
                                progress.setOnCloseRequest(ev -> {});
                                bar.setProgress(1.0);
                            } else {
                                progress.close();
                            }
                        });
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
            FocusProfileAnalysis.PairVerdict verdict, FocusApproachValidationStore.Record record, String scoreMetric) {
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
        sb.append(String.format(
                "Focus metric:      %s%n", scoreMetric == null ? "unknown (server did not report it)" : scoreMetric));
        sb.append("                   This verdict describes how THAT metric behaves. Changing\n"
                + "                   score_metric for this objective invalidates it.\n");
        sb.append('\n');
        if (verdict.requiresTissueGate()) {
            sb.append("A surface peak sits BEFORE focus here, so the tissue gate below is recommended ON: "
                    + "committing to the first peak would land on glass.\n\n");
        } else if (verdict.usable()) {
            sb.append("No surface peak before focus ON THIS SLIDE, at this position -- so the first peak on "
                    + "the way in was the sample here. That is one measurement, not a property of every "
                    + "slide this objective will see. If focus ever lands short of the sample, turn the "
                    + "gate below on.\n\n");
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
            body.setPrefRowCount(16);
            body.setPrefColumnCount(64);

            // The operator decides; the measurement recommends. It is taken on ONE slide at ONE
            // XY, so "no surface peak here" is not "no surface peak ever" -- and getting that
            // wrong is expensive and quiet: the approach commits the first peak it meets, which
            // on 2026-08-28 was 200 um off the sample and logged as a success.
            // Seeded from the measurement OR whatever the operator chose last time, because a
            // measurement can only ever argue the gate ON. It is taken at one XY on one slide,
            // so "no surface peak here" is not evidence of "no surface peak anywhere" -- there
            // is no observation this workflow can make that licenses turning the gate off.
            // Seeding from the measurement alone silently discarded the operator's ON at the
            // next validation run, which is how it read as "always unchecked".
            FocusApproachValidationStore.Record previous = FocusApproachValidationStore.find(
                    record.microscope(), record.modality(), record.objective());
            boolean previouslyGated = previous != null && previous.requiresTissueGate();
            CheckBox gateBox = new CheckBox("Require a tissue gate on every approach");
            gateBox.setSelected(verdict.requiresTissueGate() || previouslyGated);
            if (previouslyGated && !verdict.requiresTissueGate()) {
                gateBox.setText("Require a tissue gate on every approach  (kept on from your last run)");
            }
            Label gateHelp =
                    new Label("With this on, the approach snaps at each candidate peak and only stops where tissue is\n"
                            + "actually detected -- so a coverslip or slide surface is rejected and it carries on to\n"
                            + "the sample. Costs one extra snap per peak it rejects.\n\n"
                            + "Turn it on if focus ever lands short of the sample. Turn it off only if a sparse\n"
                            + "sample makes the gate reject the real peak, which shows up as autofocus failing\n"
                            + "outright rather than landing in the wrong place.");
            gateHelp.setWrapText(true);
            gateHelp.setStyle("-fx-font-size: 11px;");

            VBox content = new VBox(8, body, new Separator(), gateBox, gateHelp);
            alert.getDialogPane().setContent(content);
            UIFunctions.showAlertOverParent(alert, null);

            boolean chosen = gateBox.isSelected();
            FocusApproachValidationStore.Record toSave =
                    (chosen == record.requiresTissueGate()) ? record : withTissueGate(record, chosen);
            if (chosen != record.requiresTissueGate()) {
                logger.info(
                        "Focus approach: operator set the tissue gate to {} (the measurement recommended {})",
                        chosen,
                        record.requiresTissueGate());
            }
            try {
                FocusApproachValidationStore.save(toSave);
            } catch (Exception e) {
                logger.error("Could not save the focus-approach validation", e);
                Dialogs.showErrorMessage("Focus Approach Validation", "Could not save the result: " + e.getMessage());
            }
        });
    }
}
