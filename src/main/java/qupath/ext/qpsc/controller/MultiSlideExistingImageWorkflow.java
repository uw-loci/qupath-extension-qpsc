package qupath.ext.qpsc.controller;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.ui.MultiSlideAssignmentDialog;
import qupath.ext.qpsc.ui.stagemap.StageInsert;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
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
 * Open / Run / Skip) or clicks <b>Run All Remaining</b> to have the panel walk every
 * not-yet-done slot in order: it opens each entry, runs the single-slide workflow through
 * its completion future ({@link ExistingImageWorkflowV2#startAsync()}), and advances only
 * when the current slide has fully settled. A <b>Stop after current slide</b> control halts
 * cleanly between slides. The panel records {@code slide_position}, {@code slide_carrier},
 * and {@code ms_run_id} metadata per entry, and reports per-slot status
 * (pending / done / skipped) at the end.
 *
 * <p>Two ways to run the slots:
 * <ul>
 *   <li><b>Run All Remaining</b> -- semi-automated single pass. The panel sequences the
 *       full interactive single-slide workflow over each slot; you still answer each
 *       slide's dialogs (alignment, refinement, acquisition setup) as its turn comes up,
 *       but the sequencing between slides is automatic.</li>
 *   <li><b>Two-pass (unattended acquire)</b> -- <b>Set Up All Remaining</b> runs the
 *       interactive setup-only workflow ({@link ExistingImageWorkflowV2#startSetupAsync()})
 *       on every slot: align + optional refine + confirm tissue, which persists a per-slide
 *       alignment JSON, but acquires nothing (each slot advances to "Set up" and stashes its
 *       captured config). Then <b>Acquire All Set-Up</b> replays each captured config
 *       unattended ({@link ExistingImageWorkflowV2#startAcquireAsync}) against the persisted
 *       alignment, with no dialogs -- so you front-load every decision in the setup pass,
 *       then walk away for the long acquisition pass.</li>
 * </ul>
 * A <b>Stop after current slide</b> control halts any driver cleanly between slides. The
 * panel records {@code slide_position}, {@code slide_carrier}, and {@code ms_run_id}
 * metadata per entry, and reports per-slot status at the end.
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

            // Persist slot metadata so partial runs are recoverable
            for (MultiSlideAssignmentDialog.SlotAssignment a : result.assignments()) {
                ImageMetadataManager.setSlideAssignment(
                        a.entry(), a.position(), result.carrier().getId(), runId);
            }
            try {
                project.syncChanges();
            } catch (IOException e) {
                logger.warn("MS workflow: failed to sync project after slot metadata", e);
            }

            Platform.runLater(() -> showShepherdingPanel(gui, project, result.carrier(), result.assignments(), runId));
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

        Label intro = new Label("Two ways to run the slots. Run All Remaining walks every not-yet-done "
                + "slot in order, running the full Existing Image workflow on each (you answer each slide's "
                + "dialogs as its turn comes up; only the sequencing is automated). For a walk-away batch, "
                + "use the two-pass buttons: Set Up All Remaining does the interactive align + tissue pass on "
                + "every slot without acquiring (each becomes Set up), then Acquire All Set-Up acquires them "
                + "all unattended, no dialogs. Tick Stop after current slide to halt cleanly once the running "
                + "slide finishes. The per-row Open / Run / Skip buttons drive a single slot by hand. Click "
                + "Finish when all slots are Done or Skipped.");
        intro.setWrapText(true);
        intro.setMaxWidth(640);

        // Orientation reminder: each slot's orientation is established by its own
        // alignment during setup, not by the holder calibration (which is a center
        // point, invariant to how the slide is rotated). A slide placed with its label
        // at the other end is a pure 180-degree rotation about the slot center -- no
        // mirror/inversion -- and a manual (landmark) alignment measures that rotation
        // directly. The green-box / saved-preset path assumes the macro's canonical
        // orientation, so it would target a rotated slide at the wrong end.
        Label orientationNote = new Label("Orientation is set per slide by its alignment during setup, "
                + "not by the holder calibration. A slide placed with its label at either end is just a "
                + "180-degree rotation (tissue at the slot center stays centered; off-center tissue moves to "
                + "the opposite end). Use MANUAL alignment for any slot whose slide may be rotated, so the "
                + "rotation is measured -- the green-box / existing-preset path assumes the standard "
                + "orientation and would target a rotated slide at the wrong end.");
        orientationNote.setWrapText(true);
        orientationNote.setMaxWidth(640);
        orientationNote.setStyle("-fx-font-style: italic; -fx-text-fill: -fx-accent;");

        List<SlotState> states = new ArrayList<>();
        for (MultiSlideAssignmentDialog.SlotAssignment a : assignments) {
            states.add(new SlotState(a));
        }

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.setPadding(new Insets(8, 0, 8, 0));
        int row = 0;
        grid.add(boldLabel("Slot"), 0, row);
        grid.add(boldLabel("Entry"), 1, row);
        grid.add(boldLabel("Status"), 2, row);
        grid.add(boldLabel("Actions"), 3, row);
        row++;
        Button finishBtn = new Button("Finish");
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
        };

        for (SlotState s : states) {
            int rowFinal = row++;
            Label posLabel = new Label(s.assignment.slotLabel());
            Label entryLabel = new Label(s.assignment.entry().getImageName());
            entryLabel.setMaxWidth(220);
            Label statusLabel = new Label(s.status.label());
            s.statusLabel = statusLabel;

            Button openBtn = new Button("Open");
            Button runBtn = new Button("Run Single-Slide Workflow");
            Button doneBtn = new Button("Mark Done");
            Button skipBtn = new Button("Skip");
            s.openBtn = openBtn;
            s.runBtn = runBtn;
            s.doneBtn = doneBtn;
            s.skipBtn = skipBtn;

            openBtn.setOnAction(e -> openEntry(gui, s, refreshFinish));
            runBtn.setOnAction(e -> {
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
                // leave the slot In progress so the operator can retry or Skip.
                runBtn.setDisable(true);
                runSlot(s, refreshFinish).whenComplete((ok, ex) -> Platform.runLater(() -> runBtn.setDisable(false)));
            });
            doneBtn.setOnAction(e -> {
                s.setStatus(Status.DONE);
                refreshFinish.run();
            });
            skipBtn.setOnAction(e -> {
                s.setStatus(Status.SKIPPED);
                refreshFinish.run();
            });

            HBox actions = new HBox(6, openBtn, runBtn, doneBtn, skipBtn);
            actions.setAlignment(Pos.CENTER_LEFT);

            grid.add(posLabel, 0, rowFinal);
            grid.add(entryLabel, 1, rowFinal);
            grid.add(statusLabel, 2, rowFinal);
            grid.add(actions, 3, rowFinal);
        }

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        finishBtn.setOnAction(e -> {
            showSummary(gui, carrier, states, runId);
            stage.close();
        });
        // Abort-all flag: set by the Abort All button, checked by driveSequential before it
        // advances to the next slot. Without this, closing the panel (or aborting one run)
        // left the driver recursing -- each remaining slot still opened and tried to run.
        java.util.concurrent.atomic.AtomicBoolean aborted = new java.util.concurrent.atomic.AtomicBoolean(false);
        Button abortBtn = new Button("Abort All");
        // Always clickable: never added to the disabled-during-run set, so the operator can
        // halt the whole batch at any point (an in-flight single run still finishes/cancels
        // via its own dialog, but NO further slots will start).
        //
        // TODO(batch abort-all): this halts the DRIVER only -- it stops further slots from
        // starting, but it does NOT cancel an acquisition already running inside
        // AcquisitionManager (the unattended acquire pass has no per-slide dialog to cancel).
        // A true Abort-all must signal AcquisitionManager to stop the current tile loop +
        // ABORTAF/stitching and unwind. When that cancellation hook is built, wire this
        // button (and an always-clickable Abort-all inside the acquisition progress UI) to it.
        // See memory: project_multislide_batch_design (batch Abort-all requirement).
        abortBtn.setOnAction(e -> {
            logger.info("MS workflow: Abort All requested, runId={}", runId);
            aborted.set(true);
            stage.close();
        });

        // Sequential auto-run across every not-yet-terminal slot. Each driver opens the
        // entry and runs a single-slide operation through its completion future, advancing
        // only when the current slide has fully settled. "Stop after current slide" halts
        // cleanly between slides without interrupting an in-flight run, and is shared by all
        // three drivers below.
        Button runAllBtn = new Button("Run All Remaining");
        Button setUpAllBtn = new Button("Set Up All Remaining");
        Button acquireAllBtn = new Button("Acquire All Set-Up");
        CheckBox stopAfterCurrent = new CheckBox("Stop after current slide");
        List<Button> driverButtons = List.of(runAllBtn, setUpAllBtn, acquireAllBtn);

        // Semi-automated single pass: full interactive workflow per slot, sequenced.
        runAllBtn.setOnAction(e -> {
            stopAfterCurrent.setSelected(false);
            setPanelBusy(states, driverButtons, finishBtn, true);
            logger.info("MS workflow: Run All Remaining started, runId={}", runId);
            driveSequential(
                    gui,
                    states,
                    0,
                    s -> s.status == Status.PENDING || s.status == Status.IN_PROGRESS,
                    s -> fullSlot(gui, s, refreshFinish),
                    stopAfterCurrent::isSelected,
                    aborted::get,
                    () -> {
                        logger.info("MS workflow: Run All Remaining finished, runId={}", runId);
                        setPanelBusy(states, driverButtons, finishBtn, false);
                        refreshFinish.run();
                    });
        });

        // Two-pass PASS 1: interactive setup (align + refine + tissue) on every slot, no
        // acquisition. Each slot advances to Set up and stashes its captured config.
        setUpAllBtn.setOnAction(e -> {
            stopAfterCurrent.setSelected(false);
            setPanelBusy(states, driverButtons, finishBtn, true);
            logger.info("MS workflow: Set Up All Remaining started, runId={}", runId);
            driveSequential(
                    gui,
                    states,
                    0,
                    s -> s.status == Status.PENDING || s.status == Status.IN_PROGRESS,
                    s -> setupSlot(gui, s, refreshFinish),
                    stopAfterCurrent::isSelected,
                    aborted::get,
                    () -> {
                        logger.info("MS workflow: Set Up All Remaining finished, runId={}", runId);
                        setPanelBusy(states, driverButtons, finishBtn, false);
                        refreshFinish.run();
                    });
        });

        // Two-pass PASS 2: unattended acquisition on every Set-up slot, replaying its
        // captured config against the alignment JSON persisted during setup.
        acquireAllBtn.setOnAction(e -> {
            stopAfterCurrent.setSelected(false);
            setPanelBusy(states, driverButtons, finishBtn, true);
            logger.info("MS workflow: Acquire All Set-Up started (unattended), runId={}", runId);
            driveSequential(
                    gui,
                    states,
                    0,
                    s -> s.status == Status.SET_UP,
                    s -> acquireSlot(gui, s, refreshFinish),
                    stopAfterCurrent::isSelected,
                    aborted::get,
                    () -> {
                        logger.info("MS workflow: Acquire All Set-Up finished, runId={}", runId);
                        setPanelBusy(states, driverButtons, finishBtn, false);
                        refreshFinish.run();
                    });
        });

        HBox autoRow = new HBox(10, runAllBtn, stopAfterCurrent);
        autoRow.setAlignment(Pos.CENTER_LEFT);
        Label twoPassLabel = new Label("Two-pass (unattended acquire):");
        HBox twoPassRow = new HBox(10, twoPassLabel, setUpAllBtn, acquireAllBtn);
        twoPassRow.setAlignment(Pos.CENTER_LEFT);

        HBox buttons = new HBox(10, abortBtn, finishBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        refreshFinish.run();

        VBox root = new VBox(
                10,
                header,
                intro,
                orientationNote,
                new Separator(),
                scroll,
                new Separator(),
                autoRow,
                twoPassRow,
                buttons);
        root.setPadding(new Insets(14));
        root.setStyle("-fx-pref-width: 780; -fx-pref-height: 520;");
        stage.setScene(new Scene(root));
        stage.show();
    }

    /**
     * Opens a slot's macro entry and marks it In progress. Returns true on success.
     * Shared by the per-row Open button and the Run All driver.
     */
    private static boolean openEntry(QuPathGUI gui, SlotState s, Runnable refreshFinish) {
        try {
            gui.openImageEntry(s.assignment.entry());
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
    private static CompletableFuture<Boolean> runSlot(SlotState s, Runnable refreshFinish) {
        logger.info(
                "MS workflow: launching single-slide workflow for slot {} ({})",
                s.assignment.position(),
                s.assignment.entry().getImageName());
        s.setStatus(Status.IN_PROGRESS);
        refreshFinish.run();
        CompletableFuture<Boolean> acquired = new CompletableFuture<>();
        ExistingImageWorkflowV2.startAsync()
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
     * Full single-slide run (open + interactive workflow + acquire) for one slot.
     * Used by the "Run All Remaining" semi-automated driver.
     */
    private static CompletableFuture<Void> fullSlot(QuPathGUI gui, SlotState s, Runnable refreshFinish) {
        if (!openEntry(gui, s, refreshFinish)) {
            return CompletableFuture.completedFuture(null);
        }
        return runSlot(s, refreshFinish).thenApply(ok -> null);
    }

    /**
     * Two-pass PASS 1 for one slot: open + run the interactive setup-only workflow
     * (align + optional refine + tissue), which persists a per-slide alignment JSON but
     * does NOT acquire. On success the slot advances to Set up and stashes the captured
     * config for the acquire pass; otherwise it is left In progress for retry/Skip.
     */
    private static CompletableFuture<Void> setupSlot(QuPathGUI gui, SlotState s, Runnable refreshFinish) {
        if (!openEntry(gui, s, refreshFinish)) {
            return CompletableFuture.completedFuture(null);
        }
        logger.info(
                "MS workflow: setup pass for slot {} ({})",
                s.assignment.position(),
                s.assignment.entry().getImageName());
        CompletableFuture<Void> done = new CompletableFuture<>();
        ExistingImageWorkflowV2.startSetupAsync()
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
     * the alignment JSON persisted during setup. On a real acquisition the slot advances
     * to Done; otherwise it is restored to Set up so the acquire pass can be retried.
     */
    private static CompletableFuture<Void> acquireSlot(QuPathGUI gui, SlotState s, Runnable refreshFinish) {
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
                "MS workflow: acquire pass (unattended) for slot {} ({})",
                s.assignment.position(),
                s.assignment.entry().getImageName());
        CompletableFuture<Void> done = new CompletableFuture<>();
        ExistingImageWorkflowV2.startAcquireAsync(s.setup)
                .whenComplete((result, ex) -> Platform.runLater(() -> {
                    if (result != null) {
                        s.setStatus(Status.DONE);
                        logger.info("MS workflow: slot {} acquired (unattended)", s.assignment.position());
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
        Label statusLabel;
        // Row action buttons, held so the Run All driver can disable them while it drives.
        Button openBtn;
        Button runBtn;
        Button doneBtn;
        Button skipBtn;
        // Captured during the two-pass setup pass; replayed unattended in the acquire pass.
        ExistingImageWorkflowV2.SetupResult setup;

        SlotState(MultiSlideAssignmentDialog.SlotAssignment a) {
            this.assignment = a;
        }

        void setStatus(Status s) {
            this.status = s;
            if (statusLabel != null) statusLabel.setText(s.label());
        }

        boolean isTerminal() {
            return status == Status.DONE || status == Status.SKIPPED;
        }

        void setRowButtonsDisabled(boolean disabled) {
            if (openBtn != null) openBtn.setDisable(disabled);
            if (runBtn != null) runBtn.setDisable(disabled);
            if (doneBtn != null) doneBtn.setDisable(disabled);
            if (skipBtn != null) skipBtn.setDisable(disabled);
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
