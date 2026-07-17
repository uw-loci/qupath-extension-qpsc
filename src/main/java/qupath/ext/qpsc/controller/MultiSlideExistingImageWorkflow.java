package qupath.ext.qpsc.controller;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.workflow.CancellationToken;
import qupath.ext.qpsc.controller.workflow.SlotJumpAutofocus;
import qupath.ext.qpsc.controller.workflow.WorkflowHelpers;
import qupath.ext.qpsc.model.AcquisitionTimeEstimator;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.notification.NotificationEvent;
import qupath.ext.qpsc.service.notification.NotificationPriority;
import qupath.ext.qpsc.service.notification.NotificationService;
import qupath.ext.qpsc.ui.AttentionPulse;
import qupath.ext.qpsc.ui.DialogPlacement;
import qupath.ext.qpsc.ui.MultiSlideAssignmentDialog;
import qupath.ext.qpsc.ui.SaturationSummaryDialog;
import qupath.ext.qpsc.ui.SectionBuilder;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.ui.stagemap.StageInsert;
import qupath.ext.qpsc.ui.stagemap.StageMapWindow;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.ext.qpsc.utilities.MultiSlideAcquisitionEstimator;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Experimental Multi-Slide Existing Image workflow.
 *
 * <p>Drives the user through the regular single-slide existing-image workflow across
 * each occupied slot of a slide-carrier insert (e.g. {@code quad_v}). The user assigns
 * each project macro entry to a slot, then either drives one slot at a time (per-row
 * Open / Run Single / Skip) or runs the whole holder unattended with the two-pass drivers.
 * The panel records {@code slide_position}, {@code slide_carrier}, and {@code ms_run_id}
 * metadata per entry, and reports per-slot status (pending / done / skipped) at the end.
 *
 * <p>Two-pass (unattended acquire): <b>Step 1: Set Up All Remaining</b> runs the interactive
 * setup-only workflow ({@link ExistingImageWorkflowV2#startSetupAsync()}) on every slot --
 * align + optional refine + confirm tissue, which persists a per-slide alignment JSON, but
 * acquires nothing (each slot advances to "Set up" and stashes its captured config). Then
 * <b>Step 2: Acquire All Set-Up</b> replays each captured config unattended
 * ({@link ExistingImageWorkflowV2#startAcquireAsync}) against the persisted alignment, with
 * no dialogs -- so you front-load every decision in the setup pass, then walk away for the
 * long acquisition pass. A <b>Stop after current slide</b> control halts either driver
 * cleanly between slides, and an attention pulse glows the recommended next step (Step 1 ->
 * Step 2 -> Finish) whenever the panel is idle.
 *
 * <p>The unattended acquire pass is safe across the entry reopen because the hand-off from
 * setup to acquire is persistence-based, not in-memory: the alignment lives in a per-slide
 * JSON on disk (written by the manual / existing alignment paths during setup) and the
 * acquire pass re-reads both the alignment and the annotations from the freshly opened
 * hierarchy. The only thing carried in memory is the captured acquisition config (modality,
 * objective, angles, etc.), which is frame-independent.
 */
public final class MultiSlideExistingImageWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(MultiSlideExistingImageWorkflow.class);

    private MultiSlideExistingImageWorkflow() {}

    /**
     * TEST-ONLY: resolve whether this batch should reuse each slot's saved per-slide
     * alignment JSON instead of re-deriving it (skipping SIFT/manual alignment and
     * single-tile refinement). Gated by the "Reuse saved alignment (TESTING ONLY)"
     * preference (default off) AND a per-batch confirmation dialog. The confirmation
     * fires at most once per batch: the resolved decision is cached in {@code decision}
     * so the driver does not re-prompt on every slot. When the preference is off, returns
     * false without prompting.
     *
     * <p><b>UNSAFE for real acquisition.</b> Reuse assumes every slide is still physically
     * mounted exactly as it was when its alignment was captured -- any remount invalidates
     * the reused transform. Slots with no valid saved alignment fall back to fresh
     * alignment automatically (per-slot, not all-or-nothing).
     */
    private static boolean resolveReuseForBatch(java.util.concurrent.atomic.AtomicReference<Boolean> decision) {
        if (!QPPreferenceDialog.getMultiSlideReuseAlignmentProperty()) {
            return false;
        }
        Boolean cached = decision.get();
        if (cached != null) {
            return cached;
        }
        boolean confirmed = Dialogs.showConfirmDialog(
                "Reuse saved alignment (TESTING ONLY)",
                "TESTING ONLY: reuse each slot's saved per-slide alignment instead of re-aligning?\n\n"
                        + "Click OK ONLY if the holder has NOT been moved since the alignments were "
                        + "saved -- a remount invalidates them. Slots with no saved alignment will still "
                        + "align fresh.\n\n"
                        + "OK = reuse saved alignment (skip alignment/refinement).\n"
                        + "Cancel = align fresh (normal, safe).");
        decision.set(confirmed);
        logger.warn(
                "MS workflow: TEST-ONLY alignment reuse {} for this batch (holder assumed {}).",
                confirmed ? "ENABLED" : "declined",
                confirmed ? "untouched -- UNSAFE for real runs" : "possibly moved");
        return confirmed;
    }

    /**
     * The slot entry the multi-slide driver most recently opened. The setup pipeline
     * ({@code ExistingImageWorkflowV2}) re-asserts this as the open viewer entry before building
     * a fresh alignment, so that if the operator clicks a different entry in the project browser
     * mid-dialog -- e.g. a stale non-rotated {@code (flipped XY)} sibling left over from an earlier
     * single-slide run -- the transform is still built from the correct rotated holder entry
     * rather than the landscape sibling (which maps out of stage bounds). Cleared when the panel
     * closes; null outside a batch (single-slide runs are unaffected).
     */
    private static volatile ProjectImageEntry<BufferedImage> intendedSlotEntry;

    /** Returns the slot entry the multi-slide driver last opened, or null when no batch is active. */
    static ProjectImageEntry<BufferedImage> getIntendedSlotEntry() {
        return intendedSlotEntry;
    }

    /**
     * A hook that trips the whole batch's Abort All. A long-lived refinement/alignment dialog can
     * cover the batch panel's Abort All button (the dialogs are non-modal but owned by the main
     * window, so they can stack over the panel), leaving the operator unable to stop the run. The
     * dialog surfaces its own "Abort Batch" affordance that invokes this. Non-null only while a
     * batch panel is showing.
     */
    private static volatile Runnable batchAbortAction;

    /** Returns the batch Abort-All hook, or null when no batch panel is active. */
    public static Runnable getBatchAbortAction() {
        return batchAbortAction;
    }

    /**
     * Applies the multi-slide "alignment start" Stage Map view assists -- force Camera View and
     * zoom to the tissue box -- per the Advanced / SIFT-settings toggles, but ONLY while a
     * multi-slide batch is active (an intended slot entry is set). The manual landmark path
     * (AffineTransformationController) already does this on its slot jump; the green-box + preset
     * flow instead drives the stage in the single-/multi-tile REFINEMENT step, so those call this
     * when the operator arrives at a refinement tile. Best-effort: no-op for standalone
     * single-slide refinement, when the Stage Map is closed, or when no tissue box is set. Must be
     * called on the FX thread.
     */
    public static void applyAlignStartViewAssists() {
        if (intendedSlotEntry == null) {
            return; // not in a multi-slide batch -- leave the Stage Map view as the user set it
        }
        if (PersistentPreferences.isMultiSlideForceCameraViewOnAlignStart()) {
            StageMapWindow.forceCameraView();
        }
        if (PersistentPreferences.isMultiSlideZoomToTissueOnAlignStart()) {
            StageMapWindow.zoomToBoundingBoxPreview();
        }
    }

    /** Entry point invoked from the menu. */
    public static void start() {
        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui == null) {
            logger.warn("MS workflow: QuPathGUI is null, aborting");
            return;
        }
        Project<BufferedImage> project = gui.getProject();
        if (project == null) {
            Dialogs.showErrorMessage(
                    "Multi-Slide workflow", "Open a QuPath project containing the macro images for each slide first.");
            return;
        }

        MultiSlideAssignmentDialog.show(gui.getStage(), project).thenAccept(result -> {
            if (result == null) {
                logger.info("MS workflow cancelled at assignment dialog");
                return;
            }
            String runId = UUID.randomUUID().toString();
            logger.info(
                    "MS workflow starting: carrier={}, runId={}, slots={}",
                    result.carrier().getId(),
                    runId,
                    result.assignments().size());

            // Persist slot metadata so partial runs are recoverable AND the assignment dialog can
            // restore the slide->position picks next time. Stamp the BASE macro entry: the dialog's
            // dropdown lists base macros and pre-fills by reading their slide_position. Stamping the
            // rotated duplicate (a.entry()) instead left the base without the metadata, so
            // assignments were forgotten once vertical holders defaulted to a 270 rotation.
            for (MultiSlideAssignmentDialog.SlotAssignment a : result.assignments()) {
                ImageMetadataManager.setSlideAssignment(
                        a.baseEntry(), a.position(), result.carrier().getId(), runId);
                logger.info(
                        "MS workflow: stamped slide_position={} carrier='{}' on base entry '{}' (assigned entry '{}')",
                        a.position(),
                        result.carrier().getId(),
                        a.baseEntry() == null ? "<null>" : a.baseEntry().getImageName(),
                        a.entry() == null ? "<null>" : a.entry().getImageName());
            }
            try {
                project.syncChanges();
            } catch (IOException e) {
                logger.warn("MS workflow: failed to sync project after slot metadata", e);
            }

            Platform.runLater(() -> {
                // The assignment dialog created the rotated+flipped duplicate entries via
                // addImage() + syncChanges() but could not refresh the project browser (it has no
                // QuPathGUI). Refresh here so the new entries are visible before the setup pass --
                // otherwise the correct rotated entries are on disk but invisible, and the operator
                // can land on a stale non-rotated sibling that is still shown (which then aligns
                // out of frame). Must run on the FX thread.
                if (gui.getProject() != null) {
                    gui.refreshProject();
                }
                showShepherdingPanel(gui, project, result.carrier(), result.assignments(), runId);
            });
        });
    }

    private static void showShepherdingPanel(
            QuPathGUI gui,
            Project<BufferedImage> project,
            StageInsert carrier,
            List<MultiSlideAssignmentDialog.SlotAssignment> assignments,
            String runId) {

        Stage stage = new Stage();
        stage.initModality(Modality.NONE);
        stage.initOwner(gui.getStage());
        stage.setTitle("MS Workflow Progress (experimental)");
        // NOT always-on-top: every driver launches the single-slide workflow, which shows
        // application-modal dialogs (annotation selection, acquisition setup) owned by the
        // main window. An always-on-top panel would float over those dialogs while the modal
        // grab blocks all input -- the dialog hides behind this panel and the whole app looks
        // frozen (the documented always-on-top + modal deadlock). The panel stays owned by the
        // main window so it remains reachable without floating above the workflow's dialogs.

        Label header = new Label("Multi-Slide Existing Image -- " + carrier.getName());
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 13;");

        Label intro = new Label("AUTOMATED RUN (blue frame, at the bottom) -- run every slide unattended, in order:\n"
                + "   Step 1 (Set Up All Remaining): interactive align + tissue pass on every slot, no acquisition.\n"
                + "   Step 2 (Acquire All Set-Up): acquires all set-up slots unattended (no dialogs).\n"
                + "   Stop after current slide: halts cleanly once the running slide finishes.\n"
                + "ONE SLIDE AT A TIME (green frame, the Slots table) -- per-row Open / Run Single / Skip to drive "
                + "one slot by hand. Finish when all slots are Done or Skipped.");
        intro.setWrapText(true);
        intro.setMaxWidth(640);
        intro.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);

        // Orientation reminder: each slot's orientation is established by its own
        // alignment during setup, not by the holder calibration (which is a center
        // point, invariant to how the slide is rotated). A slide placed with its label
        // at the other end is a pure 180-degree rotation about the slot center -- no
        // mirror/inversion -- and a manual (landmark) alignment measures that rotation
        // directly. The green-box / saved-preset path assumes the macro's canonical
        // orientation, so it would target a rotated slide at the wrong end.
        Label orientationNote = new Label("Orientation is set per slide by its alignment, not the holder calibration.\n"
                + "- A slide with its label at either end is just a 180-degree rotation.\n"
                + "- Use MANUAL alignment for any slot that may be rotated; the green-box / preset path "
                + "assumes standard orientation and would target a rotated slide at the wrong end.");
        orientationNote.setWrapText(true);
        orientationNote.setMaxWidth(640);
        // Reserve the full wrapped height so the last line is not clipped with an ellipsis
        // (a wrapText Label truncates when its allotted height is computed for fewer lines).
        orientationNote.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        orientationNote.setStyle("-fx-font-style: italic; -fx-text-fill: -fx-accent;");

        List<SlotState> states = new ArrayList<>();
        for (MultiSlideAssignmentDialog.SlotAssignment a : assignments) {
            states.add(new SlotState(a));
        }

        // Next-step attention pulse: while the panel is idle, glow the recommended next driver
        // (Step 1 Set Up All -> Step 2 Acquire All -> Finish) so the operator always knows what to
        // click next. nextStepHolder is a forward reference: refreshFinish (defined just below) runs
        // on every state change but the concrete updater needs the driver buttons (defined later),
        // so refreshFinish calls through the holder, which is pointed at the real updater once the
        // buttons exist.
        AttentionPulse nextStepPulse = new AttentionPulse();
        boolean[] panelBusy = {false};
        Runnable[] nextStepHolder = {() -> {}};
        // Forward reference (like nextStepHolder): refreshFinish runs on every state change and
        // re-sums the cached per-slot estimates for the REMAINING (not-yet-Done) slots, so the
        // whole-run prediction shrinks live as each slide finishes. Pointed at the real updater once
        // the estimate label exists (below). This re-sum is cheap (no I/O); the I/O read that builds
        // the cache runs only at pass boundaries.
        Runnable[] estimateRefreshHolder = {() -> {}};

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.setPadding(new Insets(8, 0, 8, 0));
        int row = 0;
        grid.add(boldLabel("Slot"), 0, row);
        grid.add(boldLabel("Entry"), 1, row);
        grid.add(boldLabel("Status"), 2, row);
        grid.add(boldLabel("Action"), 3, row);
        row++;
        Button finishBtn = new Button("Finish");
        finishBtn.setTooltip(
                new Tooltip("Close the batch and show the run summary. Enabled once every slot is Done or Skipped."));
        Runnable refreshFinish = () -> {
            boolean allTerminal = true;
            for (SlotState s : states) {
                // SET_UP counts as non-terminal: a set-up-but-not-acquired slot still
                // needs an acquire pass (or an explicit Skip) before the run can finish.
                if (!s.isTerminal()) {
                    allTerminal = false;
                    break;
                }
            }
            finishBtn.setDisable(!allTerminal);
            nextStepHolder[0].run();
            estimateRefreshHolder[0].run();
        };

        // TEST-ONLY per-batch alignment-reuse decision, resolved once (pref on -> confirm)
        // and cached so the driver does not re-prompt per slot. null = not yet asked.
        // Declared before the slot rows so the per-slot Run button can reach it.
        java.util.concurrent.atomic.AtomicReference<Boolean> reuseDecision =
                new java.util.concurrent.atomic.AtomicReference<>(null);

        // One cancel token for the whole batch. Threaded into every slot's single-slide
        // workflow so the Abort All button can cancel the currently-acquiring slot (the
        // active AcquisitionManager registers its DualProgressDialog with this token while
        // acquiring). Declared before the row loop so the per-row Run Single handler can
        // reach it, and captured by the driver handlers and the Abort All button below.
        CancellationToken cancelToken = new CancellationToken();

        for (SlotState s : states) {
            int rowFinal = row++;
            Label posLabel = new Label(s.assignment.slotLabel());
            // Drop the "(rotated N)(flipped XY)" suffix for display -- it is identical on every
            // row (pure width, no signal); the full entry name stays in the tooltip.
            String fullEntryName = s.assignment.entry().getImageName();
            int suffixIdx = fullEntryName.indexOf(" (rotated");
            String displayEntryName = suffixIdx > 0 ? fullEntryName.substring(0, suffixIdx) : fullEntryName;
            Label entryLabel = new Label(displayEntryName);
            entryLabel.setMaxWidth(220);
            entryLabel.setTooltip(new Tooltip(fullEntryName));

            // Status column: a per-row ChoiceBox<Status> replacing the old Done/Skip buttons.
            // The driver owns the state machine; the operator may only set Done or Skipped
            // directly (the two manual overrides). Selecting a system-driven state
            // (Pending / In progress / Set up) is a no-op advisory that reverts to the
            // current state. During a live run the row is disabled (read-only) via
            // setRowButtonsDisabled.
            ChoiceBox<Status> statusChoice = new ChoiceBox<>(FXCollections.observableArrayList(Status.values()));
            statusChoice.setConverter(new StringConverter<Status>() {
                @Override
                public String toString(Status st) {
                    return st == null ? "" : st.label();
                }

                @Override
                public Status fromString(String str) {
                    return null;
                }
            });
            statusChoice.setValue(s.status);
            statusChoice.setPrefWidth(170);
            statusChoice.setMaxWidth(170);
            statusChoice.setTooltip(new Tooltip(
                    "Set this slot's state. Pick Done if you handled it outside the batch, or Skipped to exclude it."));
            s.statusChoice = statusChoice;

            Button openBtn = new Button("Open");
            openBtn.setTooltip(new Tooltip("Switch QuPath to this slot's macro image before aligning or running it."));
            s.openBtn = openBtn;

            // Run Single moved off a per-row button into a right-click context menu on the row,
            // narrowing the Action column to a single [Open] button.
            MenuItem runSingleItem = new MenuItem("Run Single");
            s.runSingleItem = runSingleItem;
            ContextMenu rowMenu = new ContextMenu(runSingleItem);
            entryLabel.setContextMenu(rowMenu);

            openBtn.setOnAction(e -> openEntry(gui, s, refreshFinish));

            statusChoice.valueProperty().addListener((obs, oldV, newV) -> {
                if (s.suppressStatusListener || newV == null) {
                    return;
                }
                if (newV == Status.DONE || newV == Status.SKIPPED) {
                    s.setStatus(newV);
                    refreshFinish.run();
                } else if (newV != s.status) {
                    // System-driven state picked manually: no-op advisory, revert to current.
                    s.suppressStatusListener = true;
                    statusChoice.setValue(s.status);
                    s.suppressStatusListener = false;
                }
            });

            runSingleItem.setOnAction(e -> {
                ProjectImageEntry<BufferedImage> currentEntry = gui.getProject() != null && gui.getImageData() != null
                        ? gui.getProject().getEntry(gui.getImageData())
                        : null;
                if (currentEntry == null || !currentEntry.equals(s.assignment.entry())) {
                    Dialogs.showWarningNotification(
                            "MS workflow", "Click Open to switch to this slot's macro before running the workflow.");
                    return;
                }
                // Drive the single-slide workflow through its completion future so the
                // slot advances to Done automatically when acquisition finishes. A null
                // result means the run short-circuited (cancel / gate / handled error) --
                // leave the slot In progress so the operator can retry or Skip. The row is
                // held read-only for the duration of the run.
                s.setRowButtonsDisabled(true);
                runSlot(carrier, s, refreshFinish, resolveReuseForBatch(reuseDecision), cancelToken)
                        .whenComplete((ok, ex) -> Platform.runLater(() -> s.setRowButtonsDisabled(false)));
            });

            grid.add(posLabel, 0, rowFinal);
            grid.add(entryLabel, 1, rowFinal);
            grid.add(statusChoice, 2, rowFinal);
            grid.add(openBtn, 3, rowFinal);
        }

        finishBtn.setOnAction(e -> {
            showSummary(gui, carrier, states, runId);
            stage.close();
        });
        // Abort-all flag: set by the Abort All button, checked by driveSequential before it
        // advances to the next slot. Without this, closing the panel (or aborting one run)
        // left the driver recursing -- each remaining slot still opened and tried to run.
        java.util.concurrent.atomic.AtomicBoolean aborted = new java.util.concurrent.atomic.AtomicBoolean(false);
        Button abortBtn = new Button("ABORT ALL");
        abortBtn.setTooltip(
                new Tooltip("Stop the whole batch. If an acquisition is running you will be asked to confirm; "
                        + "captured tiles are kept, the current tile is discarded."));
        // Red styling to mark the destructive action; anchored to the footer's right edge.
        abortBtn.setStyle("-fx-base: #b00020; -fx-text-fill: white;");
        // Always clickable: never added to the disabled-during-run set, so the operator can
        // halt the whole batch at any point. The Abort All handler is wired below (after the
        // driver buttons exist), since a mid-acquire abort disables them while cancelling.

        // Sequential auto-run across every not-yet-terminal slot. Each driver opens the
        // entry and runs a single-slide operation through its completion future, advancing
        // only when the current slide has fully settled. "Stop after current slide" halts
        // cleanly between slides without interrupting an in-flight run, and is shared by both
        // drivers below.
        Button setUpAllBtn = new Button("Step 1: Set Up All Remaining");
        setUpAllBtn.setTooltip(new Tooltip(
                "Step 1 of the unattended two-pass run: interactively align and set up every unfinished slot, "
                        + "without acquiring."));
        Button acquireAllBtn = new Button("Step 2: Acquire All Set-Up");
        acquireAllBtn.setTooltip(new Tooltip(
                "Step 2 of the unattended two-pass run: acquire every set-up slot unattended (no prompts)."));
        CheckBox stopAfterCurrent = new CheckBox("Stop after current slide");
        stopAfterCurrent.setTooltip(
                new Tooltip("Halt cleanly once the running slide finishes; does not interrupt an in-flight slide."));
        List<Button> driverButtons = List.of(setUpAllBtn, acquireAllBtn);

        // Whole-run acquisition-time estimate. Populated once slides have been SET UP (each slot's
        // captured config + confirmed annotations are what the estimate needs); it reads each set-up
        // entry's hierarchy off the FX thread, so it is recomputed at pass boundaries, not on every
        // state tick. Uses this scope's learned per-tile timing when available (self-calibrating).
        Label estimateLabel = new Label("Estimated run time: set up slides to see an estimate.");
        estimateLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #444;");
        estimateLabel.setWrapText(true);
        estimateLabel.setMinHeight(Region.USE_PREF_SIZE);
        boolean[] estimateLearned = {false};

        // Cheap re-sum (no I/O) of the cached per-slot estimates: REMAINING = slots not yet Done or
        // Skipped, TOTAL = every estimated slot. Runs on every state change (via refreshFinish) so
        // the prediction shrinks live as each slide finishes acquiring.
        Runnable refreshEstimateLabel = () -> {
            long remTiles = 0;
            long totTiles = 0;
            double remSec = 0;
            double totSec = 0;
            int remSlides = 0;
            int totSlides = 0;
            for (SlotState s : states) {
                if (s.estimate == null) {
                    continue;
                }
                totTiles += s.estimate.tiles();
                totSec += s.estimate.seconds();
                totSlides++;
                if (s.status != Status.DONE && s.status != Status.SKIPPED) {
                    remTiles += s.estimate.tiles();
                    remSec += s.estimate.seconds();
                    remSlides++;
                }
            }
            if (totSlides == 0) {
                estimateLabel.setText("Estimated run time: set up slides to see an estimate.");
                return;
            }
            String qualifier = estimateLearned[0] ? " (measured timing)" : " (rough -- no measured timing yet)";
            if (remSlides == 0) {
                estimateLabel.setText(String.format(
                        "Run complete -- estimated %s for %d slide%s, %,d tiles%s",
                        AcquisitionTimeEstimator.formatDuration(totSec),
                        totSlides,
                        totSlides == 1 ? "" : "s",
                        totTiles,
                        qualifier));
                return;
            }
            estimateLabel.setText(String.format(
                    "Remaining ~%s of ~%s  --  %d of %d slides, %,d of %,d tiles left%s",
                    AcquisitionTimeEstimator.formatDuration(remSec),
                    AcquisitionTimeEstimator.formatDuration(totSec),
                    remSlides,
                    totSlides,
                    remTiles,
                    totTiles,
                    qualifier));
        };
        estimateRefreshHolder[0] = refreshEstimateLabel;

        // Heavy path (I/O): read each set-up slot's hierarchy off the FX thread to count tiles/regions,
        // cache the per-slot estimate on the SlotState, then re-sum. Run at pass boundaries only.
        Runnable recomputeEstimate = () -> {
            List<SlotState> setUpSlots = new ArrayList<>();
            List<MultiSlideAcquisitionEstimator.SlotInput> inputs = new ArrayList<>();
            for (SlotState s : states) {
                if (s.setup == null) {
                    continue; // only set-up slots have a captured config + confirmed annotations
                }
                var cfg = s.setup.config();
                java.util.Set<String> classes = s.setup.selectedAnnotationClasses() == null
                        ? java.util.Set.of()
                        : new java.util.HashSet<>(s.setup.selectedAnnotationClasses());
                setUpSlots.add(s);
                inputs.add(new MultiSlideAcquisitionEstimator.SlotInput(
                        s.assignment.slotLabel(),
                        s.assignment.entry(),
                        cfg.modality(),
                        cfg.objective(),
                        cfg.detector(),
                        classes));
            }
            if (inputs.isEmpty()) {
                refreshEstimateLabel.run();
                return;
            }
            estimateLabel.setText("Estimated run time: computing...");
            Thread t = new Thread(
                    () -> {
                        MultiSlideAcquisitionEstimator.BatchEstimate est =
                                MultiSlideAcquisitionEstimator.estimate(inputs);
                        Platform.runLater(() -> {
                            estimateLearned[0] = est.learned();
                            for (int i = 0;
                                    i < setUpSlots.size() && i < est.slots().size();
                                    i++) {
                                setUpSlots.get(i).estimate = est.slots().get(i);
                            }
                            refreshEstimateLabel.run();
                        });
                    },
                    "MS-run-estimate");
            t.setDaemon(true);
            t.start();
        };

        // Concrete next-step updater (buttons now exist). Idle-gated: a run in progress clears the
        // pulse. Recommends Step 1 while any slot still needs setup, then Step 2 once slots are set
        // up, then Finish once all are terminal.
        Runnable updateNextStep = () -> {
            if (panelBusy[0]) {
                nextStepPulse.clear();
                return;
            }
            boolean anyNeedsSetup = false;
            boolean anySetUp = false;
            for (SlotState s : states) {
                if (s.status == Status.PENDING || s.status == Status.IN_PROGRESS) {
                    anyNeedsSetup = true;
                }
                if (s.status == Status.SET_UP) {
                    anySetUp = true;
                }
            }
            if (anyNeedsSetup) {
                nextStepPulse.highlight(setUpAllBtn, "#1565C0"); // Step 1
            } else if (anySetUp) {
                nextStepPulse.highlight(acquireAllBtn, "#1565C0"); // Step 2
            } else {
                nextStepPulse.highlight(finishBtn, "#2E7D32"); // all terminal -> Finish
            }
        };
        nextStepHolder[0] = updateNextStep;

        // Centralized busy toggle used by the drivers: disables the controls AND stops/refreshes
        // the next-step pulse in one place.
        java.util.function.Consumer<Boolean> setBusy = busy -> {
            panelBusy[0] = busy;
            setPanelBusy(states, driverButtons, finishBtn, busy);
            updateNextStep.run();
        };

        // Abort All state machine (design 02_design_uiux.md section 1.4):
        //   Idle "ABORT ALL" -> (confirm iff acquiring) -> "Cancelling..." (disabled) -> "Aborted".
        // When no acquisition is in flight, Abort All is the immediate driver halt it always was
        // (set the aborted flag + close). When an acquisition IS running, it trips the shared
        // cancel token -- which flips the active DualProgressDialog's cancelled flag AND sends
        // CANCEL, so the acquire loop scores a clean cancel, not an error -- then waits for the
        // acquisition to settle before showing the terminal state and the run summary.
        abortBtn.setOnAction(e -> {
            boolean acquiring = MicroscopeController.getInstance().isAcquisitionActive();
            logger.info("MS workflow: Abort All requested, runId={}, acquisitionActive={}", runId, acquiring);
            if (!acquiring) {
                // No in-flight acquisition: immediate driver halt + close (today's behavior).
                aborted.set(true);
                cancelToken.cancel();
                stage.close();
                return;
            }
            // Acquisition in flight: destructive confirm (the ONE legitimate app-modal here).
            boolean confirmed = Dialogs.showConfirmDialog(
                    "Abort running acquisition",
                    "Abort the running acquisition? Tiles already captured are kept; the current tile "
                            + "is discarded and no further slots will start.");
            if (!confirmed) {
                return;
            }
            // Drive the button to the "Cancelling..." terminal-in-progress state and lock the
            // panel so nothing else can start while the cancel unwinds.
            aborted.set(true);
            abortBtn.setText("Cancelling...");
            abortBtn.setDisable(true);
            abortBtn.setTooltip(new Tooltip("Cancelling the running acquisition and landing the stage safely..."));
            setPanelBusy(states, driverButtons, finishBtn, true);
            // Trip the token: flips the active DualProgressDialog's cancelled flag + sends CANCEL.
            cancelToken.cancel();
            // Wait off-thread for the acquisition to settle (setAcquisitionActive(false) fires
            // when the acquire loop unwinds), then show the terminal state + summary on the FX
            // thread. If the server never confirms within the timeout, warn but still mark aborted
            // (UX honesty: we do not claim a safe stop we cannot verify).
            waitForAbortToSettle(gui, carrier, states, runId, stage, abortBtn);
        });
        // Expose the Abort All action to long-lived alignment/refinement dialogs that can stack
        // over this panel (see getBatchAbortAction). Firing the button reuses the exact Abort All
        // logic, including the confirm-if-acquiring path. Cleared on panel hide.
        batchAbortAction = abortBtn::fire;

        // Two-pass PASS 1: interactive setup (align + refine + tissue) on every slot, no
        // acquisition. Each slot advances to Set up and stashes its captured config.
        setUpAllBtn.setOnAction(e -> {
            stopAfterCurrent.setSelected(false);
            boolean reuse = resolveReuseForBatch(reuseDecision);
            setBusy.accept(true);
            logger.info("MS workflow: Set Up All Remaining started, runId={}", runId);
            driveSequential(
                    gui,
                    states,
                    0,
                    s -> s.status == Status.PENDING || s.status == Status.IN_PROGRESS,
                    s -> setupSlot(gui, carrier, s, refreshFinish, reuse, cancelToken),
                    stopAfterCurrent::isSelected,
                    aborted::get,
                    () -> {
                        logger.info("MS workflow: Set Up All Remaining finished, runId={}", runId);
                        setBusy.accept(false);
                        refreshFinish.run();
                        // Every set-up slot now has a captured config + confirmed annotations, so a
                        // whole-run acquire estimate can be computed (reads hierarchies off-thread).
                        recomputeEstimate.run();
                    });
        });

        // Auto-collapse-on-focus-loss is wanted during the interactive SETUP/alignment pass (the panel
        // is in the way of the alignment dialogs), but NOT during the unattended ACQUIRE pass -- there
        // the operator wants the live progress list visible while focus sits on the viewer/stitching.
        // The acquire pass toggles this off for its duration.
        boolean[] autoCollapseEnabled = {true};

        // Two-pass PASS 2: unattended acquisition on every Set-up slot, replaying its
        // captured config against the alignment JSON persisted during setup.
        acquireAllBtn.setOnAction(e -> {
            stopAfterCurrent.setSelected(false);
            setBusy.accept(true);
            // Keep the panel visible for the whole unattended pass (see autoCollapseEnabled above).
            autoCollapseEnabled[0] = false;
            logger.info("MS workflow: Acquire All Set-Up started (unattended, pipelined), runId={}", runId);
            // Collect every slot's saturation report into ONE combined dialog at batch end instead of
            // popping a per-acquisition dialog (a 4-slide run otherwise pops 4+). Paired with
            // endBatchAndShow() (normal end) / endBatchAndShow() after abort settles.
            SaturationSummaryDialog.beginBatch();
            // Batch-scoped collector for each slot's stitch+import completion. Pipelining advances
            // the driver to slot N+1 at slot N's acquisition-complete (while N still stitches), so
            // the batch tail must await every collected future before declaring the batch done.
            // Populated on the FX thread (driveSequential runs on FX), so a plain list is safe.
            List<CompletableFuture<Void>> pendingStitches = new ArrayList<>();
            driveSequential(
                    gui,
                    states,
                    0,
                    s -> s.status == Status.SET_UP,
                    s -> acquireSlot(gui, s, refreshFinish, cancelToken, pendingStitches),
                    stopAfterCurrent::isSelected,
                    aborted::get,
                    () -> {
                        logger.info("MS workflow: Acquire All Set-Up driver finished, runId={}", runId);
                        if (aborted.get()) {
                            // Abort All owns teardown (waitForAbortToSettle shows the summary and
                            // closes the panel). Do NOT block the tail on stitches the abort may
                            // have left running -- just release the panel controls.
                            logger.info(
                                    "MS workflow: acquire pass ended via Abort All; not awaiting pending stitches, runId={}",
                                    runId);
                            // Still surface whatever saturation was collected before the abort.
                            SaturationSummaryDialog.endBatchAndShow();
                            autoCollapseEnabled[0] = true;
                            setBusy.accept(false);
                            refreshFinish.run();
                            return;
                        }
                        // Keep the panel busy (Finish stays disabled) until every slot's stitching
                        // has drained, then release. This is the point the batch is truly done.
                        awaitPendingStitches(pendingStitches, runId, () -> {
                            // Per-slot stitched-image imports suppressed their own project refresh
                            // (to avoid yanking the active viewer mid-batch), so refresh the project
                            // view ONCE here now that every stitched entry has been added.
                            if (gui != null && gui.getProject() != null) {
                                gui.refreshProject();
                            }
                            notifyBatchComplete(carrier, states, runId);
                            // One combined saturation dialog for the whole run (worst samples flagged
                            // at the top), instead of the per-acquisition popups collected above.
                            SaturationSummaryDialog.endBatchAndShow();
                            autoCollapseEnabled[0] = true;
                            setBusy.accept(false);
                            refreshFinish.run();
                        });
                    });
        });

        refreshFinish.run();

        // ---- Consolidated panel: mode banner + section stack (scrolled) + fixed footer ----

        // Mode banner (blue = interactive setup pass). Full-width, above the section stack.
        Label banner = new Label("SETUP PASS - " + carrier.getName());
        HBox.setHgrow(banner, Priority.ALWAYS);
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.setStyle(
                "-fx-background-color: #1565c0; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 6 12 6 12;");

        // Collapse toggle: shrink the panel to just this banner so it is out of the way while the
        // operator performs (and checks) per-slide alignment in the Stage Map / refinement dialogs.
        // Standard window chrome stays, so the thin collapsed bar is still draggable/closable, and
        // Abort Batch remains available inside the refinement dialog while collapsed.
        Button collapseBtn = new Button("Collapse");
        collapseBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: white; -fx-font-weight: bold;");
        collapseBtn.setTooltip(
                new Tooltip("Collapse this panel to a thin bar so it does not cover the alignment views. "
                        + "Click again to expand."));
        HBox bannerRow = new HBox(banner, collapseBtn);
        bannerRow.setAlignment(Pos.CENTER_LEFT);
        bannerRow.setStyle("-fx-background-color: #1565c0;");

        VBox topBox = new VBox(6, bannerRow, header);
        topBox.setPadding(new Insets(12, 12, 4, 12));

        // Section A: Slots (the per-slot table). Expanded -- the batch's home base. Green frame +
        // heading marks the MANUAL "one slide at a time" controls, distinct from the blue AUTOMATED
        // RUN group in the footer (run every slide unattended).
        Label manualHint = new Label("ONE SLIDE AT A TIME -- Open a row (or right-click a row > Run Single) to drive "
                + "a single slide by hand. To run every slide, use the blue AUTOMATED RUN controls at the bottom.");
        manualHint.setWrapText(true);
        manualHint.setMaxWidth(560);
        manualHint.setMinHeight(Region.USE_PREF_SIZE);
        manualHint.setStyle("-fx-font-weight: bold; -fx-text-fill: #2E7D32;");
        VBox slotsContent = new VBox(8, manualHint, grid);
        slotsContent.setStyle("-fx-border-color: #2E7D32; -fx-border-width: 2; -fx-border-radius: 6; -fx-padding: 10;");
        TitledPane slotsSection = SectionBuilder.section("Slots", true, slotsContent);
        slotsSection.setTooltip(new Tooltip("Every slide in the holder, its status, and per-row controls."));

        // Section B: Alignment. Expanded. Stage Map view controls + autofocus-on-slot-jump status.
        // (The SIFT / manual-align controls themselves still run in the alignment step; folding
        // them into this section is a later increment.)
        CheckBox cameraViewCheck = new CheckBox("Camera View");
        cameraViewCheck.setSelected(true);
        cameraViewCheck.setTooltip(
                new Tooltip(
                        "Show the Stage Map as the live camera sees it (checked) or as the slides physically sit (unchecked)."));
        cameraViewCheck.selectedProperty().addListener((obs, oldV, newV) -> StageMapWindow.setCameraView(newV));

        Button zoomTissueBtn = new Button("Zoom to tissue");
        zoomTissueBtn.setTooltip(new Tooltip("Zoom the Stage Map to this slot's green tissue box."));
        // Best-effort: zooms to whatever green bounding-box preview is currently set on the Stage
        // Map. The per-slot tissue box is not tracked at panel scope, so the current preview box is
        // used. TODO(increment: align-start-hook): pass the active slot's box explicitly.
        zoomTissueBtn.setOnAction(e -> StageMapWindow.zoomToBoundingBoxPreview());

        HBox alignRow1 = new HBox(8, cameraViewCheck, zoomTissueBtn);
        alignRow1.setAlignment(Pos.CENTER_LEFT);

        Label afStatusLabel = new Label("AF status: Ready");
        afStatusLabel.setWrapText(true);
        afStatusLabel.setMaxWidth(560);
        afStatusLabel.setTooltip(new Tooltip(
                "Autofocus after each slot jump: waits for the stage move to finish, then focuses before you align."));

        Label alignmentNote =
                new Label("These controls drive the Stage Map view and show autofocus-on-slot-jump status. The SIFT / "
                        + "manual-align step for the current slot still runs in its own dialog.");
        alignmentNote.setWrapText(true);
        alignmentNote.setMaxWidth(560);
        alignmentNote.setMinHeight(Region.USE_PREF_SIZE);

        VBox alignmentBox = new VBox(8, alignRow1, afStatusLabel, alignmentNote);

        // Slot-jump autofocus (fired from the alignment controller after each blocking slot move)
        // publishes phase text here: "Moving to slot..." -> "Focusing..." -> "Ready", or amber
        // "Focus failed -- align manually" on failure.
        SlotJumpAutofocus.setStatusSink((message, error) -> {
            afStatusLabel.setText("AF status: " + message);
            afStatusLabel.setStyle(error ? "-fx-text-fill: #E65100; -fx-font-weight: bold;" : "");
        });

        TitledPane alignmentSection = SectionBuilder.section("Alignment", true, alignmentBox);
        alignmentSection.setTooltip(new Tooltip("Align the current slot: Stage Map view, SIFT, and manual alignment."));

        // Section C: Refinement. Collapsed. Increment-1 placeholder.
        Label refinementPlaceholder =
                new Label("Multi-tile refinement runs in its own step for now. The folded Multi-Tile panel "
                        + "(reusing a shared SIFT capture pane per point) arrives in a later increment.");
        refinementPlaceholder.setWrapText(true);
        refinementPlaceholder.setMaxWidth(560);
        refinementPlaceholder.setMinHeight(Region.USE_PREF_SIZE);
        // TODO(increment 2: SiftCapturePane + Multi-Tile fold): embed the refinement panel here.
        TitledPane refinementSection = SectionBuilder.section("Refinement", false, refinementPlaceholder);
        refinementSection.setTooltip(new Tooltip("Multi-tile rotation + scale correction for the current slot."));

        // Section D: Advanced / SIFT settings. Collapsed. On/off switches for the items above,
        // plus the existing SIFT auto-align controls (reusing existing prefs -- no new SIFT pref).
        CheckBox afOnJumpCheck = new CheckBox("Autofocus on slot jump");
        afOnJumpCheck.setSelected(PersistentPreferences.isMultiSlideAutofocusOnJump());
        afOnJumpCheck.setTooltip(new Tooltip(
                "Automatically focus after the stage reaches each slot, before alignment. Turn off to focus by hand."));
        afOnJumpCheck
                .selectedProperty()
                .addListener((o, ov, nv) -> PersistentPreferences.setMultiSlideAutofocusOnJump(nv));

        CheckBox forceCamCheck = new CheckBox("Force Camera View on alignment start");
        forceCamCheck.setSelected(PersistentPreferences.isMultiSlideForceCameraViewOnAlignStart());
        forceCamCheck.setTooltip(
                new Tooltip("When an alignment step begins, switch the Stage Map to Camera View automatically."));
        forceCamCheck
                .selectedProperty()
                .addListener((o, ov, nv) -> PersistentPreferences.setMultiSlideForceCameraViewOnAlignStart(nv));

        CheckBox zoomOnStartCheck = new CheckBox("Zoom Stage Map to tissue on alignment start");
        zoomOnStartCheck.setSelected(PersistentPreferences.isMultiSlideZoomToTissueOnAlignStart());
        zoomOnStartCheck.setTooltip(new Tooltip(
                "When an alignment step begins, zoom the Stage Map to the green tissue box automatically."));
        zoomOnStartCheck
                .selectedProperty()
                .addListener((o, ov, nv) -> PersistentPreferences.setMultiSlideZoomToTissueOnAlignStart(nv));

        CheckBox trustSiftCheck = new CheckBox("Trust SIFT auto-align");
        trustSiftCheck.setSelected(PersistentPreferences.isTrustSiftAlignment());
        trustSiftCheck.setTooltip(
                new Tooltip("Auto-accept a SIFT match above the confidence threshold without asking for each point."));
        trustSiftCheck.selectedProperty().addListener((o, ov, nv) -> PersistentPreferences.setTrustSiftAlignment(nv));

        Label confidenceLabel = new Label("Confidence threshold:");
        confidenceLabel.setTooltip(new Tooltip("Minimum SIFT confidence (0-1) required to auto-accept a match."));
        Spinner<Double> confidenceSpinner =
                new Spinner<>(0.0, 1.0, PersistentPreferences.getSiftConfidenceThreshold(), 0.05);
        confidenceSpinner.setEditable(true);
        confidenceSpinner.setPrefWidth(90);
        confidenceSpinner.valueProperty().addListener((o, ov, nv) -> {
            if (nv != null) {
                PersistentPreferences.setSiftConfidenceThreshold(nv);
            }
        });
        HBox confidenceRow = new HBox(8, confidenceLabel, confidenceSpinner);
        confidenceRow.setAlignment(Pos.CENTER_LEFT);

        VBox advancedBox = new VBox(
                8, afOnJumpCheck, forceCamCheck, zoomOnStartCheck, new Separator(), trustSiftCheck, confidenceRow);
        TitledPane advancedSection = SectionBuilder.section("Advanced / SIFT settings", false, advancedBox);
        advancedSection.setTooltip(new Tooltip("Autofocus, camera-view, zoom, and SIFT auto-align options."));

        // The batch guidance notes scroll with the section stack (not pinned in the header).
        VBox sections = new VBox(
                10, intro, orientationNote, slotsSection, alignmentSection, refinementSection, advancedSection);
        sections.setPadding(new Insets(12));

        ScrollPane scroll = new ScrollPane(sections);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        // Fixed footer (never in a section, never scrolls): a blue-framed AUTOMATED RUN group
        // (numbered two-pass steps first, then the one-pass alternative + stop toggle), then the
        // Finish / Abort row. The blue frame pairs with the green MANUAL Slots section above: blue
        // = run every slide unattended, green = drive one slide by hand.
        Label autoTitle = new Label("AUTOMATED RUN -- every slide in the holder");
        autoTitle.setStyle("-fx-font-weight: bold; -fx-text-fill: #1565C0;");

        HBox stepRow = new HBox(10, setUpAllBtn, acquireAllBtn);
        stepRow.setAlignment(Pos.CENTER_LEFT);

        VBox autoBox = new VBox(8, autoTitle, stepRow, stopAfterCurrent, estimateLabel);
        autoBox.setStyle("-fx-border-color: #1565C0; -fx-border-width: 2; -fx-border-radius: 6; -fx-padding: 10;");

        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox finishRow = new HBox(10, finishBtn, footerSpacer, abortBtn);
        finishRow.setAlignment(Pos.CENTER_LEFT);

        VBox footer = new VBox(8, autoBox, finishRow);

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: -fx-base;");
        root.setTop(topBox);
        root.setCenter(scroll);
        root.setBottom(footer);
        BorderPane.setMargin(footer, new Insets(0, 12, 12, 12));

        // Collapse/expand: shrink to just the banner bar, and float THAT bar above other windows so
        // it can never be buried by the alignment dialogs / Stage Map / QuPath ("other modality
        // things block it"). Only the tiny COLLAPSED bar is always-on-top; the expanded panel is
        // never always-on-top. The documented no-always-on-top invariant is about the full expanded
        // panel deadlocking app-modal CHILD dialogs (an Alert opened FROM it sinks behind it) -- the
        // collapsed bar parents no dialog, so it cannot deadlock, and the expanded panel stays
        // non-on-top exactly as before.
        boolean[] collapsed = {false};
        java.util.function.Consumer<Boolean> applyCollapsed = doCollapse -> {
            if (collapsed[0] == doCollapse) {
                return; // already in that state -- avoid redundant sizeToScene churn / flicker
            }
            collapsed[0] = doCollapse;
            boolean show = !doCollapse;
            header.setVisible(show);
            header.setManaged(show);
            scroll.setVisible(show);
            scroll.setManaged(show);
            footer.setVisible(show);
            footer.setManaged(show);
            collapseBtn.setText(doCollapse ? "Expand" : "Collapse");
            stage.setMinHeight(doCollapse ? 0 : 480);
            // Keep the collapsed bar on top and reachable; drop always-on-top the instant it expands.
            stage.setAlwaysOnTop(doCollapse);
            stage.sizeToScene();
        };
        collapseBtn.setOnAction(e -> applyCollapsed.accept(!collapsed[0]));

        // Auto-collapse when the panel loses focus (a child alignment dialog, the Stage Map, or the
        // QuPath viewer takes over) so it gets out of the way exactly when you want it gone -- the
        // collapsed bar is always-on-top, so Expand is always one reachable click away. Re-expand is
        // explicit (the Expand button) rather than on focus-regain, so clicking the bar to reposition
        // or expand it does not immediately fight the child dialogs for focus.
        stage.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (Boolean.FALSE.equals(isFocused) && !collapsed[0] && autoCollapseEnabled[0]) {
                applyCollapsed.accept(true);
            }
        });

        // Drop the AF status sink when the panel closes so slot-jump AF stops pushing status into
        // a disposed label (and a later panel can register its own). Also clear the intended-slot
        // entry so a later single-slide run is not re-asserted against a stale batch entry.
        stage.setOnHidden(e -> {
            SlotJumpAutofocus.clearStatusSink();
            intendedSlotEntry = null;
            batchAbortAction = null;
            nextStepPulse.clear();
            DialogPlacement.clearBatchAnchor();
        });

        stage.setMinWidth(560);
        stage.setMinHeight(480);
        stage.setScene(new Scene(root));
        stage.sizeToScene();
        // Anchor the per-slot dialog chain (acquisition config, green box, tile select, refinement)
        // beside this panel so they dock next to it instead of piling on the alignment views.
        DialogPlacement.setBatchAnchor(stage);
        stage.show();
        // If the panel opens with slots already set up (e.g. a resumed run), show an estimate now.
        recomputeEstimate.run();
    }

    /**
     * Opens a slot's macro entry and marks it In progress. Returns true on success.
     * Shared by the per-row Open button and the Run All driver.
     */
    private static boolean openEntry(QuPathGUI gui, SlotState s, Runnable refreshFinish) {
        try {
            // Save the currently-open image quietly BEFORE switching. After a slot's acquire pass
            // the just-stitched/biref image is left open and marked changed (import sets the image
            // type / renames channels); QuPath's openImageEntry would otherwise pop a modal
            // "Save changes?" dialog and stall the unattended two-pass run for the whole gap
            // between slides (observed: a 7.5h pause waiting for the operator). Clearing the dirty
            // flag here makes the switch silent -- the same pattern TileProcessingUtilities uses
            // before opening a stitched result.
            WorkflowHelpers.saveOpenImageDataQuietly(gui);
            gui.openImageEntry(s.assignment.entry());
            // Publish the intended entry so the setup pipeline can re-assert it if the operator
            // clicks a different (e.g. stale non-rotated sibling) entry in the browser mid-dialog.
            intendedSlotEntry = s.assignment.entry();
            s.setStatus(Status.IN_PROGRESS);
            refreshFinish.run();
            return true;
        } catch (Exception ex) {
            logger.error(
                    "MS workflow: failed to open entry {}", s.assignment.entry().getImageName(), ex);
            Dialogs.showErrorMessage("Open failed", ex.getMessage());
            return false;
        }
    }

    /**
     * Runs the single-slide workflow for the given slot (its entry must already be
     * open) and returns a future that completes {@code true} when a real acquisition
     * ran, {@code false} on any short-circuit / cancel / handled error. The slot
     * status is advanced to Done on success and left In progress otherwise so the
     * operator can retry or Skip. The returned future always completes on the FX
     * thread and never exceptionally.
     */
    private static CompletableFuture<Boolean> runSlot(
            StageInsert carrier,
            SlotState s,
            Runnable refreshFinish,
            boolean reuseAlignment,
            CancellationToken cancelToken) {
        logger.info(
                "MS workflow: launching single-slide workflow for slot {} ({})",
                s.assignment.position(),
                s.assignment.entry().getImageName());
        s.setStatus(Status.IN_PROGRESS);
        refreshFinish.run();
        CompletableFuture<Boolean> acquired = new CompletableFuture<>();
        double[] slotCenter = WorkflowHelpers.resolveSlotCenterStageXY(carrier, s.assignment.position());
        // forceFreshAlignment=true: multi-slide runs must re-derive each slide's
        // position/orientation for its current mount via fresh MANUAL alignment, never
        // reuse a prior alignment or the SIFT/preset path (both assume the horizontal
        // scanner orientation and mis-target a slide mounted in the holder). The slot
        // center feeds the alignment's auto-move-to-selected-tile.
        // TEST-ONLY: reuseAlignment (pref + per-batch confirm) flips this to false so a
        // saved per-slide JSON is reused (holder assumed untouched); see resolveReuseForBatch.
        boolean forceFresh = !reuseAlignment;
        ExistingImageWorkflowV2.startAsync(forceFresh, slotCenter, cancelToken)
                .whenComplete((result, ex) -> Platform.runLater(() -> {
                    boolean ok = result != null;
                    if (ok) {
                        s.setStatus(Status.DONE);
                        logger.info("MS workflow: slot {} acquired", s.assignment.position());
                    } else {
                        logger.info(
                                "MS workflow: slot {} did not acquire (cancelled / gated); leaving In progress",
                                s.assignment.position());
                    }
                    refreshFinish.run();
                    acquired.complete(ok);
                }));
        return acquired;
    }

    /** A per-slot operation: open the entry, do the work, update status; complete when settled. */
    @FunctionalInterface
    private interface SlotOp {
        CompletableFuture<Void> run(SlotState s);
    }

    /**
     * Two-pass PASS 1 for one slot: open + run the interactive setup-only workflow
     * (align + optional refine + tissue), which persists a per-slide alignment JSON but
     * does NOT acquire. On success the slot advances to Set up and stashes the captured
     * config for the acquire pass; otherwise it is left In progress for retry/Skip.
     */
    private static CompletableFuture<Void> setupSlot(
            QuPathGUI gui,
            StageInsert carrier,
            SlotState s,
            Runnable refreshFinish,
            boolean reuseAlignment,
            CancellationToken cancelToken) {
        if (!openEntry(gui, s, refreshFinish)) {
            return CompletableFuture.completedFuture(null);
        }
        logger.info(
                "MS workflow: setup pass for slot {} ({})",
                s.assignment.position(),
                s.assignment.entry().getImageName());
        CompletableFuture<Void> done = new CompletableFuture<>();
        double[] slotCenter = WorkflowHelpers.resolveSlotCenterStageXY(carrier, s.assignment.position());
        // TEST-ONLY: reuseAlignment (pref + per-batch confirm) lets the setup pass reuse a
        // saved per-slide JSON (holder assumed untouched) instead of re-aligning; slots
        // without a valid saved alignment still fall back to fresh alignment.
        boolean forceFresh = !reuseAlignment;
        ExistingImageWorkflowV2.startSetupAsync(slotCenter, forceFresh, cancelToken)
                .whenComplete((setup, ex) -> Platform.runLater(() -> {
                    if (setup != null) {
                        s.setup = setup;
                        s.setStatus(Status.SET_UP);
                        logger.info("MS workflow: slot {} set up (ready to acquire)", s.assignment.position());
                    } else {
                        logger.info(
                                "MS workflow: slot {} setup cancelled / gated; leaving In progress",
                                s.assignment.position());
                    }
                    refreshFinish.run();
                    done.complete(null);
                }));
        return done;
    }

    /**
     * Two-pass PASS 2 for one slot: open + replay the captured config unattended against
     * the alignment JSON persisted during setup, PIPELINED. On a real acquisition the slot
     * advances to Done; otherwise it is restored to Set up so the acquire pass can be retried.
     *
     * <p>Pipelining: the returned future (which the driver awaits to advance to the NEXT slot)
     * completes at acquisition-complete (stage work done), NOT after stitching. Each slot's
     * stitch+import completion is collected into {@code pendingStitches} so the batch tail can
     * await every slot's stitching before the run summary. Because the stitched-image import
     * suppresses its viewer side effects (see {@code ExistingImageWorkflowV2.startAcquireAsync}
     * pipelined mode), a background import cannot yank the active viewer out from under the next
     * slot as it opens its base entry.
     */
    private static CompletableFuture<Void> acquireSlot(
            QuPathGUI gui,
            SlotState s,
            Runnable refreshFinish,
            CancellationToken cancelToken,
            List<CompletableFuture<Void>> pendingStitches) {
        if (s.setup == null) {
            logger.warn("MS workflow: acquireSlot called on slot {} with no setup; skipping", s.assignment.position());
            return CompletableFuture.completedFuture(null);
        }
        if (!openEntry(gui, s, refreshFinish)) {
            // openEntry set IN_PROGRESS then failed; restore SET_UP so it stays retryable.
            s.setStatus(Status.SET_UP);
            refreshFinish.run();
            return CompletableFuture.completedFuture(null);
        }
        logger.info(
                "MS workflow: acquire pass (unattended, pipelined) for slot {} ({})",
                s.assignment.position(),
                s.assignment.entry().getImageName());
        CompletableFuture<Void> done = new CompletableFuture<>();
        ExistingImageWorkflowV2.AcquireHandle handle =
                ExistingImageWorkflowV2.startAcquireAsync(s.setup, cancelToken, true);
        // Collect this slot's stitch+import completion for the batch tail. Never completes
        // exceptionally, so allOf(...) over the collected list cannot hang.
        pendingStitches.add(handle.stitchingComplete());
        handle.acquisitionComplete()
                .whenComplete((result, ex) -> Platform.runLater(() -> {
                    if (result != null) {
                        s.setStatus(Status.DONE);
                        logger.info(
                                "MS workflow: slot {} acquired (unattended); advancing while its stitching finishes in the background",
                                s.assignment.position());
                        // Per-slide success alert. The pipelined batch path suppresses
                        // ExistingImageWorkflowV2.showSuccessNotification() (the batch driver owns the
                        // tail), so emit the beep + toast + ntfy push here instead -- otherwise an
                        // unattended multi-slide run is silent per slide. Fires at acquisition-complete
                        // (matches the row's DONE semantics); stitching finishes in the background.
                        notifySlideAcquired(s);
                    } else {
                        s.setStatus(Status.SET_UP);
                        logger.info(
                                "MS workflow: slot {} acquire failed / gated; restored to Set up",
                                s.assignment.position());
                    }
                    refreshFinish.run();
                    done.complete(null);
                }));
        return done;
    }

    /**
     * Batch tail for the pipelined ACQUIRE pass: waits for every collected slot stitch+import
     * completion future, then runs {@code onSettled} on the FX thread. Called after the driver
     * has advanced through all slots (each slot advanced at acquisition-complete, so earlier
     * slots may still be stitching). The collected futures never complete exceptionally, so the
     * {@code allOf} cannot hang on a failed stitch. Runs entirely via completion callbacks -- it
     * does not block the FX thread while waiting.
     */
    private static void awaitPendingStitches(
            List<CompletableFuture<Void>> pendingStitches, String runId, Runnable onSettled) {
        // Snapshot on the FX thread (the drive is done, so no further adds, but snapshot anyway).
        CompletableFuture<?>[] snapshot = pendingStitches.toArray(new CompletableFuture<?>[0]);
        if (snapshot.length == 0) {
            logger.info("MS workflow: batch tail has no pending stitches, runId={}", runId);
            onSettled.run();
            return;
        }
        logger.info(
                "MS workflow: batch tail waiting on {} pending stitch(es) before finishing, runId={}",
                snapshot.length,
                runId);
        CompletableFuture.allOf(snapshot)
                .whenComplete((v, ex) -> Platform.runLater(() -> {
                    if (ex != null) {
                        // Defensive: the collected futures are built to never complete exceptionally.
                        logger.warn(
                                "MS workflow: batch tail stitch-await completed with error, runId={}: {}", runId, ex);
                    }
                    logger.info("MS workflow: all pending stitches drained; batch done, runId={}", runId);
                    onSettled.run();
                }));
    }

    /**
     * Sequentially drives every slot matching {@code match} from {@code index} onward:
     * apply {@code op} to it, wait for the op to settle, then advance to the next. Slots
     * that do not match (already terminal, or not in the pass's target state) are stepped
     * over. Halts early when {@code stopRequested} is true (checked between slides, so an
     * in-flight run always finishes cleanly). Calls {@code onDone} on the FX thread when
     * there is nothing left to run or a stop was honoured.
     *
     * <p>Implemented as an FX-thread recursion over each op's completion future rather
     * than a blocking loop, so the UI stays responsive and any interactive dialogs run
     * normally as each slot's turn comes up.
     */
    private static void driveSequential(
            QuPathGUI gui,
            List<SlotState> states,
            int index,
            java.util.function.Predicate<SlotState> match,
            SlotOp op,
            java.util.function.BooleanSupplier stopRequested,
            java.util.function.BooleanSupplier abortRequested,
            Runnable onDone) {

        // Abort All: stop immediately, do not open or run any further slot. Checked first so
        // it wins over stop-after-current and the match scan.
        if (abortRequested.getAsBoolean()) {
            logger.info("MS workflow: driver aborted (Abort All) at slot index {}", index);
            onDone.run();
            return;
        }

        int i = index;
        while (i < states.size() && !match.test(states.get(i))) {
            i++;
        }
        if (i >= states.size()) {
            onDone.run();
            return;
        }
        if (stopRequested.getAsBoolean()) {
            logger.info("MS workflow: driver halted by 'Stop after current slide' before slot index {}", i);
            onDone.run();
            return;
        }

        final int idx = i;
        op.run(states.get(idx))
                .whenComplete((v, ex) -> Platform.runLater(
                        () -> driveSequential(gui, states, idx + 1, match, op, stopRequested, abortRequested, onDone)));
    }

    /**
     * Disables (or restores) the panel's driving controls while a driver is active.
     * Abort is intentionally left live so the user can always close the panel; Finish
     * is disabled here and re-gated by {@code refreshFinish} when the run ends. Per-row
     * controls are disabled so the manual and auto drivers cannot overlap.
     */
    private static void setPanelBusy(
            List<SlotState> states, List<Button> driverButtons, Button finishBtn, boolean busy) {
        for (Button b : driverButtons) {
            b.setDisable(busy);
        }
        finishBtn.setDisable(busy);
        for (SlotState s : states) {
            s.setRowButtonsDisabled(busy);
        }
    }

    /** Max time to wait for a mid-acquire Abort All to settle before warning the operator. */
    private static final long ABORT_SETTLE_TIMEOUT_MS = 60_000L;

    /** Poll interval while waiting for the aborted acquisition to release the acquisition lock. */
    private static final long ABORT_SETTLE_POLL_MS = 250L;

    /**
     * After a mid-acquire Abort All trips the cancel token, waits off the FX thread for the
     * acquisition to settle (the acquire loop clears {@code acquisitionActive} when it unwinds),
     * then drives the Abort button to its terminal "Aborted" state and shows the run summary on
     * the FX thread. If the server does not confirm the cancel within
     * {@link #ABORT_SETTLE_TIMEOUT_MS}, warns the operator (cancel sent but unconfirmed) and
     * still marks the batch aborted -- the UI must not claim a safe stop it cannot verify.
     */
    private static void waitForAbortToSettle(
            QuPathGUI gui, StageInsert carrier, List<SlotState> states, String runId, Stage stage, Button abortBtn) {
        Thread waiter = new Thread(
                () -> {
                    long deadline = System.currentTimeMillis() + ABORT_SETTLE_TIMEOUT_MS;
                    boolean settled = false;
                    while (System.currentTimeMillis() < deadline) {
                        if (!MicroscopeController.getInstance().isAcquisitionActive()) {
                            settled = true;
                            break;
                        }
                        try {
                            Thread.sleep(ABORT_SETTLE_POLL_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    final boolean confirmed = settled;
                    Platform.runLater(() -> {
                        abortBtn.setText("Aborted");
                        abortBtn.setDisable(true);
                        if (confirmed) {
                            logger.info("MS workflow: Abort All settled (acquisition released), runId={}", runId);
                        } else {
                            logger.warn(
                                    "MS workflow: Abort All not confirmed within {} ms; marking aborted anyway, runId={}",
                                    ABORT_SETTLE_TIMEOUT_MS,
                                    runId);
                            Dialogs.showWarningNotification(
                                    "Abort All", "Cancel sent; the microscope did not confirm -- verify stage state.");
                        }
                        showSummary(gui, carrier, states, runId);
                        stage.close();
                    });
                },
                "MS-abort-settle-waiter");
        waiter.setDaemon(true);
        waiter.start();
    }

    /**
     * Per-slide success alert for the unattended (pipelined) acquire pass: completion beep, a QuPath
     * toast, and an ntfy push. Mirrors {@code ExistingImageWorkflowV2.showSuccessNotification()},
     * which the pipelined path skips because the batch driver owns the tail. Fires when a slide's
     * acquisition completes (its stitching may still be running in the background).
     */
    private static void notifySlideAcquired(SlotState s) {
        String slot = s.assignment.slotLabel();
        String name = s.assignment.entry().getImageName();
        UIFunctions.playWorkflowCompletionBeep();
        Dialogs.showInfoNotification("Slide Acquired", slot + " (" + name + ") acquired.");
        NotificationService.getInstance()
                .notify(
                        "Slide Acquired",
                        slot + " - \"" + name + "\" acquisition finished (stitching in progress)",
                        NotificationPriority.DEFAULT,
                        NotificationEvent.ACQUISITION_COMPLETE);
    }

    /**
     * Batch-complete alert once every slot's stitching has drained. Sent in addition to the per-slide
     * alerts so an operator away from the scope gets one "batch done" ping with the tally.
     */
    private static void notifyBatchComplete(StageInsert carrier, List<SlotState> states, String runId) {
        long done = states.stream().filter(st -> st.status == Status.DONE).count();
        NotificationService.getInstance()
                .notify(
                        "Multi-Slide Run Complete",
                        carrier.getName() + ": " + done + " of " + states.size() + " slide(s) acquired (run " + runId
                                + ")",
                        NotificationPriority.DEFAULT,
                        NotificationEvent.ALL_ANGLES_COMPLETE);
    }

    private static void showSummary(QuPathGUI gui, StageInsert carrier, List<SlotState> states, String runId) {
        StringBuilder body = new StringBuilder();
        body.append("Carrier: ").append(carrier.getName()).append("\n");
        body.append("Run id: ").append(runId).append("\n\n");
        for (SlotState s : states) {
            body.append(s.assignment.slotLabel())
                    .append("  ")
                    .append(s.assignment.entry().getImageName())
                    .append("  -- ")
                    .append(s.status.label())
                    .append("\n");
        }
        Dialogs.showMessageDialog("MS workflow summary", body.toString());
    }

    private static Label boldLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }

    /** Per-slot UI state for the shepherding panel. */
    private static final class SlotState {
        final MultiSlideAssignmentDialog.SlotAssignment assignment;
        Status status = Status.PENDING;
        // Per-row status control (replaces the old status Label + Done/Skip buttons).
        ChoiceBox<Status> statusChoice;
        // True while the driver sets status programmatically, so the ChoiceBox change
        // listener does not re-interpret a system-driven update as an operator transition.
        boolean suppressStatusListener;
        // Row action controls, held so the drivers can disable them while driving.
        Button openBtn;
        MenuItem runSingleItem;
        // Captured during the two-pass setup pass; replayed unattended in the acquire pass.
        ExistingImageWorkflowV2.SetupResult setup;
        // Cached whole-run estimate contribution for this slot (tiles + seconds), computed off the FX
        // thread after setup; re-summed cheaply for the live "remaining" prediction.
        MultiSlideAcquisitionEstimator.SlotEstimate estimate;

        SlotState(MultiSlideAssignmentDialog.SlotAssignment a) {
            this.assignment = a;
        }

        void setStatus(Status s) {
            this.status = s;
            if (statusChoice != null) {
                suppressStatusListener = true;
                statusChoice.setValue(s);
                suppressStatusListener = false;
            }
        }

        boolean isTerminal() {
            return status == Status.DONE || status == Status.SKIPPED;
        }

        void setRowButtonsDisabled(boolean disabled) {
            if (openBtn != null) openBtn.setDisable(disabled);
            if (statusChoice != null) statusChoice.setDisable(disabled);
            if (runSingleItem != null) runSingleItem.setDisable(disabled);
        }
    }

    private enum Status {
        PENDING("Pending"),
        IN_PROGRESS("In progress"),
        SET_UP("Set up (ready to acquire)"),
        DONE("Done"),
        SKIPPED("Skipped");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }
}
