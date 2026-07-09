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
 * <p>This is intentionally a thin orchestrator over the existing workflow rather than a
 * deep refactor: the underlying alignment + acquisition logic is unchanged, and Run All
 * automates only the <em>sequencing</em> between slides -- each slide's own dialogs
 * (alignment, refinement, acquisition setup) still run as its turn comes up. A fully
 * unattended two-pass mode (front-load every human decision in a setup pass, then acquire
 * all N with no dialogs) additionally requires splitting the single-slide workflow into a
 * setup-only path that persists per-slide alignment and a non-interactive acquire path
 * that replays a captured acquisition config -- tracked as later batch work.
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
        stage.setAlwaysOnTop(true);

        Label header = new Label("Multi-Slide Existing Image -- " + carrier.getName());
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 13;");

        Label intro = new Label("Click Run All Remaining to walk every not-yet-done slot in order: "
                + "the panel opens each macro entry and runs the regular Existing Image workflow on it, "
                + "advancing the slot to Done when acquisition completes. You still answer each slide's "
                + "own dialogs (alignment, refinement, acquisition setup) as its turn comes up; the panel "
                + "only automates the sequencing between slides. Tick Stop after current slide to halt "
                + "cleanly once the running slide finishes. The per-row Open / Run / Skip buttons remain "
                + "for driving a single slot by hand. Click Finish when all slots are Done or Skipped.");
        intro.setWrapText(true);
        intro.setMaxWidth(640);

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
                if (s.status == Status.PENDING || s.status == Status.IN_PROGRESS) {
                    allTerminal = false;
                    break;
                }
            }
            finishBtn.setDisable(!allTerminal);
        };

        for (SlotState s : states) {
            int rowFinal = row++;
            Label posLabel = new Label("#" + s.assignment.position() + " " + s.assignment.slotLabel());
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
        Button abortBtn = new Button("Abort");
        abortBtn.setOnAction(e -> {
            logger.info("MS workflow aborted by user, runId={}", runId);
            stage.close();
        });

        // Sequential auto-run across every not-yet-terminal slot. The driver opens
        // each entry and runs its single-slide workflow through the completion future,
        // advancing only when the current slide has fully settled. "Stop after current
        // slide" halts cleanly between slides without interrupting an in-flight run.
        Button runAllBtn = new Button("Run All Remaining");
        CheckBox stopAfterCurrent = new CheckBox("Stop after current slide");
        runAllBtn.setOnAction(e -> {
            stopAfterCurrent.setSelected(false);
            setPanelBusy(states, runAllBtn, finishBtn, true);
            logger.info("MS workflow: Run All Remaining started, runId={}", runId);
            runAllRemaining(gui, states, 0, refreshFinish, stopAfterCurrent::isSelected, () -> {
                logger.info("MS workflow: Run All Remaining finished, runId={}", runId);
                setPanelBusy(states, runAllBtn, finishBtn, false);
                refreshFinish.run();
            });
        });

        HBox autoRow = new HBox(10, runAllBtn, stopAfterCurrent);
        autoRow.setAlignment(Pos.CENTER_LEFT);

        HBox buttons = new HBox(10, abortBtn, finishBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        refreshFinish.run();

        VBox root = new VBox(10, header, intro, new Separator(), scroll, new Separator(), autoRow, buttons);
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

    /**
     * Sequentially drives every not-yet-terminal slot from {@code index} onward:
     * open its entry, run its single-slide workflow, wait for completion, then advance
     * to the next. Terminal slots (Done / Skipped) are stepped over. Halts early when
     * {@code stopRequested} is true (checked between slides, so an in-flight run always
     * finishes cleanly). Calls {@code onDone} on the FX thread when there is nothing
     * left to run or a stop was honoured.
     *
     * <p>Implemented as an FX-thread recursion over the completion future rather than a
     * blocking loop, so the UI stays responsive and each slide's own dialogs run
     * normally as its turn comes up. This automates the sequencing between slides; it
     * does not make the per-slide runs unattended.
     */
    private static void runAllRemaining(
            QuPathGUI gui,
            List<SlotState> states,
            int index,
            Runnable refreshFinish,
            java.util.function.BooleanSupplier stopRequested,
            Runnable onDone) {

        int i = index;
        while (i < states.size() && states.get(i).isTerminal()) {
            i++;
        }
        if (i >= states.size()) {
            onDone.run();
            return;
        }
        if (stopRequested.getAsBoolean()) {
            logger.info("MS workflow: Run All halted by 'Stop after current slide' before slot index {}", i);
            onDone.run();
            return;
        }

        final int idx = i;
        SlotState s = states.get(idx);
        if (!openEntry(gui, s, refreshFinish)) {
            // Opening failed; leave the slot In progress and continue with the rest.
            runAllRemaining(gui, states, idx + 1, refreshFinish, stopRequested, onDone);
            return;
        }
        runSlot(s, refreshFinish)
                .whenComplete((ok, ex) -> Platform.runLater(
                        () -> runAllRemaining(gui, states, idx + 1, refreshFinish, stopRequested, onDone)));
    }

    /**
     * Disables (or restores) the panel's driving controls while Run All is active.
     * Abort is intentionally left live so the user can always close the panel; Finish
     * is disabled here and re-gated by {@code refreshFinish} when the run ends. Per-row
     * controls are disabled so the manual and auto drivers cannot overlap.
     */
    private static void setPanelBusy(List<SlotState> states, Button runAllBtn, Button finishBtn, boolean busy) {
        runAllBtn.setDisable(busy);
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
            body.append("#")
                    .append(s.assignment.position())
                    .append(" ")
                    .append(s.assignment.slotLabel())
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
